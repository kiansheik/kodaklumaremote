package dev.framextv.lumareceiver;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.util.AtomicFile;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.locks.LockSupport;

/** Offline NES display/audio. Input is sampled once per frame, without network polling. */
public final class NesGameView extends SurfaceView implements SurfaceHolder.Callback {
    public interface Listener {
        void onError(String message);
        default void onWarning(String message) { }
    }
    private static final long FRAME_NS = 1_000_000_000L / 60;
    private final Object coreLock = new Object();
    private final AudioManager audioManager;
    private final AudioManager.OnAudioFocusChangeListener focusListener;
    private long core;
    private File saveDirectory;
    private CheckpointHistory checkpoints;
    private long checkpointLoads;
    private Listener listener;
    private final NesFrameInput input = new NesFrameInput();
    private volatile boolean running;
    private volatile long frames;
    private boolean surfaceReady, resumeWanted, released, audioFocus;
    private Thread worker;

    public NesGameView(Context context) {
        super(context);
        getHolder().addCallback(this);
        setFocusable(false); // Activity owns gamepad/menu routing.
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        focusListener = change -> { if (change < 0) post(this::pause); };
    }

    public void setListener(Listener value) { listener = value; }

    public void load(File rom, File directory) throws IOException {
        pause();
        synchronized (coreLock) {
            if (released) throw new IOException("The game view has closed.");
            if (core != 0) { NesNative.destroy(core); core = 0; }
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create game saves.");
            saveDirectory = directory;
            checkpoints = new CheckpointHistory(directory);
            try {
                core = NesNative.create(readFile(rom, 8 * 1024 * 1024));
                File battery = new File(directory, "battery.sav");
                if (battery.isFile()) restoreAutomatic(battery, true);
                File automatic = new File(directory, "auto.state");
                if (automatic.isFile()) restoreAutomatic(automatic, false);
                frames = 0;
                input.resetStatistics();
            } catch (LinkageError error) {
                throw new IOException("The NES engine is unavailable on this device.", error);
            } catch (IOException error) {
                if (core != 0) { NesNative.destroy(core); core = 0; }
                throw error;
            }
        }
    }

    public void setButtons(int mask) { input.update(mask, 0); }
    public void setInput(int mask, int freshPresses) { input.update(mask, freshPresses); }
    public void cancelRetriggers() { input.cancelRetriggers(); }
    public long retriggerCount() { return input.retriggerCount(); }
    public long frameCount() { return frames; }
    public boolean isRunning() { return running; }
    java.util.List<CheckpointHistory.Entry> checkpoints() throws IOException {
        java.util.List<CheckpointHistory.Entry> entries = checkpoints.list();
        File legacy = new File(saveDirectory, "manual.state");
        if (entries.isEmpty() && (legacy.isFile() || new File(saveDirectory, "manual.state.bak").isFile()))
            entries.add(new CheckpointHistory.Entry(0, legacy));
        return entries;
    }
    public long checkpointLoadCount() { return checkpointLoads; }

    public void saveState() throws IOException {
        synchronized (coreLock) {
            requireCore();
            // Preserve the old single-slot checkpoint before publishing the first new save.
            java.util.List<CheckpointHistory.Entry> entries = checkpoints();
            if (!entries.isEmpty() && entries.get(0).id == 0) {
                File legacy = entries.get(0).file;
                try (FileInputStream stream = new AtomicFile(legacy).openRead()) {
                    checkpoints.save(readStream(stream, 1024 * 1024), legacy.lastModified());
                }
            }
            checkpoints.save(NesNative.save(core, false));
        }
    }

    public void loadState() throws IOException {
        java.util.List<CheckpointHistory.Entry> entries = checkpoints();
        if (entries.isEmpty()) throw new IOException("No checkpoint saved yet.");
        loadState(entries.get(0).id);
    }

    public void loadState(long id) throws IOException {
        synchronized (coreLock) {
            requireCore();
            File file = id == 0 ? new File(saveDirectory, "manual.state") : checkpoints.file(id);
            restore(file, false); checkpointLoads++;
        }
        input.clear();
    }

    public void pause() {
        resumeWanted = false;
        input.clear();
        stopWorker();
        saveAutomatic();
        setKeepScreenOn(false);
        if (audioFocus) { audioManager.abandonAudioFocus(focusListener); audioFocus = false; }
    }

    public void resume() {
        if (released) return;
        resumeWanted = true;
        startWorkerIfReady();
    }

    public void release() {
        pause();
        released = true;
        synchronized (coreLock) { if (core != 0) { NesNative.destroy(core); core = 0; } }
    }

    @Override public void surfaceCreated(SurfaceHolder holder) { surfaceReady = true; startWorkerIfReady(); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        input.clear();
        stopWorker();
        saveAutomatic();
    }

    private void startWorkerIfReady() {
        if (released || !resumeWanted || !surfaceReady || core == 0 || worker != null) return;
        audioFocus = audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        if (!audioFocus) { report("Another app is using game audio. Close it and resume the game."); return; }
        running = true;
        setKeepScreenOn(true);
        worker = new Thread(this::runFrames, "nes-frame-audio");
        worker.start();
    }

    private void stopWorker() {
        running = false;
        Thread previous = worker;
        if (previous == null || previous == Thread.currentThread()) return;
        previous.interrupt();
        try { previous.join(500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        if (!previous.isAlive() && worker == previous) worker = null;
    }

    private void runFrames() {
        AudioTrack audio = null;
        Bitmap bitmap = Bitmap.createBitmap(256, 240, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[256 * 240];
        short[] samples = new short[4096];
        Paint paint = new Paint();
        paint.setFilterBitmap(false);
        Rect destination = new Rect();
        try {
            int minimum = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IOException("48 kHz game audio is unavailable.");
            AudioTrack.Builder builder = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(minimum, 4800));
            if (Build.VERSION.SDK_INT >= 26) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            audio = builder.build();
            if (audio.getState() != AudioTrack.STATE_INITIALIZED) throw new IOException("Game audio could not start.");
            audio.play();
            long next = System.nanoTime();
            while (running) {
                int count;
                synchronized (coreLock) {
                    if (!running || core == 0) break;
                    count = NesNative.step(core, input.nextFrame(), pixels, samples);
                    frames++;
                }
                bitmap.setPixels(pixels, 0, 256, 0, 0, 256, 240);
                Canvas canvas = null;
                try {
                    canvas = Build.VERSION.SDK_INT >= 26 ? getHolder().lockHardwareCanvas() : getHolder().lockCanvas();
                    if (canvas != null) {
                        canvas.drawColor(Color.BLACK);
                        int width = Math.min(canvas.getWidth(), canvas.getHeight() * 4 / 3);
                        int height = width * 3 / 4;
                        int left = (canvas.getWidth() - width) / 2, top = (canvas.getHeight() - height) / 2;
                        destination.set(left, top, left + width, top + height);
                        canvas.drawBitmap(bitmap, null, destination, paint);
                    }
                } finally { if (canvas != null) getHolder().unlockCanvasAndPost(canvas); }
                int offset = 0;
                while (running && offset < count) {
                    int written = audio.write(samples, offset, count - offset, AudioTrack.WRITE_NON_BLOCKING);
                    if (written < 0) throw new IOException("Game audio stopped (" + written + ").");
                    if (written == 0) LockSupport.parkNanos(1_000_000);
                    else offset += written;
                }
                next += FRAME_NS;
                long now = System.nanoTime();
                if (next < now - 3 * FRAME_NS) next = now; // Never burst minutes of stale frames after suspension.
                if (next > now) LockSupport.parkNanos(next - now);
            }
        } catch (Exception error) {
            if (running) {
                running = false;
                post(() -> { resumeWanted = false; report(error.getMessage()); });
            }
        } finally {
            if (audio != null) { try { audio.pause(); audio.flush(); } catch (RuntimeException ignored) { } audio.release(); }
            bitmap.recycle();
            Thread finished = Thread.currentThread();
            post(() -> {
                if (worker == finished) worker = null;
                startWorkerIfReady();
            });
        }
    }

    private void requireCore() throws IOException { if (core == 0) throw new IOException("No game is loaded."); }
    private void restore(File file, boolean battery) throws IOException {
        AtomicFile atomic = new AtomicFile(file);
        try (FileInputStream stream = atomic.openRead()) { NesNative.restore(core, readStream(stream, 1024 * 1024), battery); }
    }
    private void restoreAutomatic(File file, boolean battery) {
        try { restore(file, battery); }
        catch (IOException error) {
            post(() -> { if (!released && listener != null)
                listener.onWarning("The previous save could not be restored; starting the game again."); });
        }
    }
    private void saveAutomatic() {
        synchronized (coreLock) {
            if (core == 0 || saveDirectory == null) return;
            try {
                writeFile(new File(saveDirectory, "auto.state"), NesNative.save(core, false));
                byte[] battery = NesNative.save(core, true);
                if (battery.length != 0) writeFile(new File(saveDirectory, "battery.sav"), battery);
            } catch (IOException error) { report("Could not save this game: " + error.getMessage()); }
        }
    }
    private void report(String message) { post(() -> { if (!released && listener != null) listener.onError(message == null ? "The game stopped." : message); }); }
    private static byte[] readFile(File file, int maximum) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) { return readStream(input, maximum); }
    }
    private static byte[] readStream(FileInputStream input, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int count;
        while ((count = input.read(chunk)) != -1) {
            if (output.size() > maximum - count) throw new IOException("Game file is too large.");
            output.write(chunk, 0, count);
        }
        return output.toByteArray();
    }
    private static void writeFile(File file, byte[] bytes) throws IOException {
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream output = atomic.startWrite();
        try { output.write(bytes); atomic.finishWrite(output); }
        catch (IOException error) { atomic.failWrite(output); throw error; }
    }
}

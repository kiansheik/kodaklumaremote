package dev.framextv.lumareceiver;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.ToneGenerator;

import org.json.JSONObject;

import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/** Sparse, quiet, generated chirps. No recorded or distress-call audio is bundled. */
final class CatSoundPlayer implements AutoCloseable {
    // Cheap Android TV/projector HALs commonly expose only the native 48 kHz
    // media sink. The previous 24 kHz static track could stay completely silent.
    static final int SAMPLE_RATE = 48_000;
    static final float TRACK_GAIN = 0.85f;
    private final ExecutorService audio = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "cat-chirp");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong generation = new AtomicLong();
    private volatile AudioTrack current;
    private volatile ToneGenerator currentTone;
    private volatile boolean closed;
    private static volatile String lastState = "not-run";
    private static volatile int lastWrittenSamples, lastPlayedFrames;

    void play(int kind) {
        if (closed) return;
        submit(synthesize(kind, SAMPLE_RATE));
    }

    /** A distinct two-part call is audible immediately when a session starts. */
    void playAttention() {
        if (closed) return;
        submit(synthesizeAttention(SAMPLE_RATE));
    }

    private void submit(short[] samples) {
        long ticket = generation.get();
        try { audio.execute(() -> playBlocking(samples, ticket)); }
        catch (RejectedExecutionException ignored) { }
    }

    void stop() {
        generation.incrementAndGet();
        AudioTrack playing = current;
        if (playing != null) {
            try { playing.stop(); } catch (RuntimeException ignored) { }
        }
        ToneGenerator tone = currentTone;
        if (tone != null) {
            try { tone.stopTone(); } catch (RuntimeException ignored) { }
        }
    }

    @Override public void close() {
        closed = true;
        stop();
        audio.shutdownNow();
    }

    private void playBlocking(short[] samples, long ticket) {
        if (closed || ticket != generation.get()) return;
        AudioTrack track = null;
        boolean fallback = false;
        try {
            lastState = "initializing";
            lastWrittenSamples = lastPlayedFrames = 0;
            AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
            AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
            int minimum = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IllegalStateException("MIN_BUFFER");
            track = new AudioTrack(attributes, format, Math.max(minimum, 4_096),
                AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            if (track.getState() != AudioTrack.STATE_INITIALIZED)
                throw new IllegalStateException("TRACK_UNINITIALIZED");
            current = track;
            if (closed || ticket != generation.get()) return;
            // Master loudness remains under the projector's media-volume
            // control. This per-track gain is intentionally below clipping but
            // clearly audible; the former 0.30 gain was lost across a room.
            track.setVolume(TRACK_GAIN);
            track.play();
            int offset = 0;
            while (offset < samples.length && !closed && ticket == generation.get()) {
                int written = track.write(samples, offset, samples.length - offset,
                    AudioTrack.WRITE_BLOCKING);
                if (written <= 0) throw new IllegalStateException("WRITE_" + written);
                offset += written;
                lastWrittenSamples = offset;
            }
            if (closed || ticket != generation.get()) return;
            Thread.sleep(samples.length * 1000L / SAMPLE_RATE + 80);
            lastPlayedFrames = track.getPlaybackHeadPosition();
            fallback = lastPlayedFrames <= 0;
            if (!fallback) lastState = "pcm-stream";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException unavailable) {
            fallback = true;
            android.util.Log.w("LumaCat", "CHIRP_UNAVAILABLE");
        } finally {
            if (current == track) current = null;
            if (track != null) {
                try { track.stop(); } catch (RuntimeException ignored) { }
                track.release();
            }
        }
        if (fallback && !closed && ticket == generation.get()) playFallback(ticket);
    }

    /** Last-resort native media-stream tone: an audio failure is never silent. */
    private void playFallback(long ticket) {
        ToneGenerator tone = null;
        try {
            tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
            currentTone = tone;
            if (closed || ticket != generation.get()) return;
            if (!tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 450))
                throw new IllegalStateException("TONE_START");
            lastState = "system-tone-fallback";
            Thread.sleep(520);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException unavailable) {
            lastState = "failed";
            android.util.Log.w("LumaCat", "TONE_UNAVAILABLE");
        } finally {
            if (currentTone == tone) currentTone = null;
            if (tone != null) {
                try { tone.release(); } catch (RuntimeException ignored) { }
            }
        }
    }

    static JSONObject diagnostics() throws Exception {
        return new JSONObject().put("state", lastState).put("sampleRate", SAMPLE_RATE)
            .put("writtenSamples", Math.max(0, lastWrittenSamples))
            .put("playedFrames", Math.max(0, lastPlayedFrames));
    }

    /** Pure PCM generator used by local tests as well as the projector. */
    static short[] synthesize(int kind, int sampleRate) {
        int variant = (kind % 3 + 3) % 3;
        double duration = variant == 2 ? 0.22 : variant == 1 ? 0.14 : 0.19;
        short[] samples = new short[Math.max(1, (int) Math.round(duration * sampleRate))];
        Random noise = new Random(0xCA7100L + variant);
        double phase = 0, previousNoise = 0;
        for (int i = 0; i < samples.length; i++) {
            double t = i / (double) Math.max(1, samples.length - 1);
            double envelope = Math.min(1, t / 0.08) * Math.min(1, (1 - t) / 0.18);
            double value;
            if (variant == 2) {
                double raw = noise.nextDouble() * 2 - 1;
                value = (raw - previousNoise * 0.72) * 0.09 * envelope;
                previousNoise = raw;
            } else {
                double from = variant == 0 ? 2_900 : 5_800;
                double to = variant == 0 ? 6_300 : 3_500;
                double frequency = from + (to - from) * t + Math.sin(t * Math.PI * 4) * 220;
                phase += 2 * Math.PI * frequency / sampleRate;
                double pulse = variant == 0 && t > 0.44 && t < 0.56 ? 0.25 : 1;
                value = Math.sin(phase) * 0.22 * envelope * pulse;
            }
            value = Math.max(-0.24, Math.min(0.24, value));
            samples[i] = (short) Math.round(value * Short.MAX_VALUE);
        }
        return samples;
    }

    /** Two separated rising chirps: attention-getting without continuous noise. */
    static short[] synthesizeAttention(int sampleRate) {
        double duration = 0.46;
        short[] samples = new short[Math.max(1, (int) Math.round(duration * sampleRate))];
        double phase = 0;
        for (int i = 0; i < samples.length; i++) {
            double time = i / (double) sampleRate;
            double local = time < 0.19 ? time / 0.19 : time >= 0.25 ? (time - 0.25) / 0.21 : -1;
            double value = 0;
            if (local >= 0 && local <= 1) {
                double envelope = Math.sin(Math.PI * local);
                envelope *= envelope;
                double frequency = 2_600 + 2_800 * local + Math.sin(local * Math.PI * 4) * 180;
                phase += 2 * Math.PI * frequency / sampleRate;
                value = (Math.sin(phase) * 0.22 + Math.sin(phase * 2) * 0.035) * envelope;
            }
            value = Math.max(-0.26, Math.min(0.26, value));
            samples[i] = (short) Math.round(value * Short.MAX_VALUE);
        }
        samples[0] = 0;
        samples[samples.length - 1] = 0;
        return samples;
    }
}

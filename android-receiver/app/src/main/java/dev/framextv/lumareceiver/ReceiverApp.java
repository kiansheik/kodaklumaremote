package dev.framextv.lumareceiver;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import java.security.SecureRandom;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Process-wide receiver components; the service only keeps the process foreground. */
public final class ReceiverApp extends Application {
    private static ReceiverApp instance;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    SharedPreferences preferences;
    LibraryStore library;
    PlaybackController playback;
    DisplaySettings display;
    Pairing pairing;
    Updater updater;
    ControlServer server;
    Diagnostics diagnostics;
    ThumbnailStore thumbnails;
    private Pairing.Listener pairingUi;
    private volatile java.util.function.BiConsumer<String, Integer> noticeUi;

    static ReceiverApp get(Context context) { return (ReceiverApp) context.getApplicationContext(); }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        preferences = getSharedPreferences("receiver", MODE_PRIVATE);
        library = new LibraryStore(this, receiverId());
        display = new DisplaySettings(preferences);
        playback = new PlaybackController(this, library, display);
        pairing = new Pairing(preferences, code -> MAIN.post(() -> {
            if (code != null) MainActivity.bringToFront(this);
            if (pairingUi != null) pairingUi.onPairingChanged(code);
        }));
        updater = new Updater(this, preferences);
        updater.attach(null, playback);
        server = new ControlServer(this);
        thumbnails = new ThumbnailStore(this);
        ExternalCaptions.cacheDirectory = new java.io.File(getCacheDir(), "captions");
        diagnostics = new Diagnostics(this);
        diagnostics.start(playback);
        startReceiverService(this);
        updater.schedule();
    }

    void setPairingUi(Pairing.Listener listener) { pairingUi = listener; }

    void setNoticeUi(java.util.function.BiConsumer<String, Integer> listener) { noticeUi = listener; }

    private volatile java.util.function.Supplier<android.graphics.Bitmap> snapshotUi;
    void setSnapshotUi(java.util.function.Supplier<android.graphics.Bitmap> supplier) { snapshotUi = supplier; }
    /** Main thread: the caption overlay exactly as drawn (video surfaces are not included). */
    android.graphics.Bitmap snapshot() { java.util.function.Supplier<android.graphics.Bitmap> target = snapshotUi; return target == null ? null : target.get(); }

    interface ScrubUi { void onScrub(boolean active, double position, double duration, android.graphics.Bitmap frame); }
    private volatile ScrubUi scrubUi;
    void setScrubUi(ScrubUi listener) { scrubUi = listener; }
    boolean scrub(boolean active, double position, double duration, android.graphics.Bitmap frame) {
        ScrubUi target = scrubUi;
        if (target == null) return false;
        MAIN.post(() -> target.onScrub(active, position, duration, frame));
        return true;
    }

    /** Shows a short on-screen message; false when the receiver UI is not open. */
    boolean notice(String text, int durationMs) {
        java.util.function.BiConsumer<String, Integer> target = noticeUi;
        if (target == null) return false;
        MAIN.post(() -> target.accept(text, durationMs));
        return true;
    }

    /** Stable per-install identity, also used as this device's sync origin. */
    String receiverId() {
        String id = preferences.getString("receiverId", null);
        if (id == null) {
            byte[] bytes = new byte[12]; new SecureRandom().nextBytes(bytes);
            id = "luma-" + Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            preferences.edit().putString("receiverId", id).apply();
        }
        return id;
    }

    static void startReceiverService(Context context) {
        Intent intent = new Intent(context, ReceiverService.class);
        try { if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent); else context.startService(intent); }
        catch (RuntimeException backgroundStartBlocked) {
            // Android 12+ forbids this from the background (e.g. after an update);
            // the projector's Android 9 allows it, and opening the screen retries.
            android.util.Log.w("LumaReceiver", "SERVICE_START_DEFERRED");
        }
    }

    /** Runs on the main looper and waits; HTTP threads use this for every player call. */
    static <T> T onMain(Callable<T> work) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return work.call();
        FutureTask<T> task = new FutureTask<>(work);
        MAIN.post(task);
        try { return task.get(5, TimeUnit.SECONDS); }
        catch (java.util.concurrent.ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw failure;
        }
    }

    static Handler main() { return MAIN; }
}

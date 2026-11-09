package dev.framextv.lumareceiver;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Process-wide receiver components; the service only keeps the process foreground. */
public final class ReceiverApp extends Application {
    private static ReceiverApp instance;
    private final long processStartedAt = android.os.SystemClock.elapsedRealtime();
    private long accessibilityStartedAt, bootReceivedAt;
    private String listenerSource = "application";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String HOME_OK_PRESSES = "homeOkPresses";
    private static final String HOME_SETTINGS_OPENS = "homeSettingsOpens";
    private static final String HOME_LAST_OK_AT = "homeLastOkAt";
    private static final String HOME_LAST_SETTINGS_AT = "homeLastSettingsAt";
    private static final String HOME_LAST_SETTINGS_SOURCE = "homeLastSettingsSource";
    SharedPreferences preferences;
    LibraryStore library;
    PlaybackController playback;
    DisplaySettings display;
    Pairing pairing;
    Updater updater;
    ControlServer server;
    Diagnostics diagnostics;
    ThumbnailStore thumbnails;
    LauncherCatalog launcher;
    CatModeState catMode;
    GameLibrary games;
    ControllerLeds controllerLeds;
    LocalProjectorKeys projectorKeys;
    ControllerHomeHold controllerHome;
    android.app.Activity foregroundActivity;
    private java.util.function.Supplier<JSONObject> controllerNavigationUi;
    private GamesActivity gamesUi;
    HistoryActivity historyUi;
    final StreamRecovery streamRecovery = new StreamRecovery();
    private ControllerSetupActivity controllerSetupUi;
    private Pairing.Listener pairingUi;
    private volatile java.util.function.BiConsumer<String, Integer> noticeUi;

    static ReceiverApp get(Context context) { return (ReceiverApp) context.getApplicationContext(); }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        projectorKeys = new LocalProjectorKeys();
        projectorKeys.probe();
        controllerHome = new ControllerHomeHold(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            public void onActivityResumed(android.app.Activity activity) { foregroundActivity = activity; }
            public void onActivityPaused(android.app.Activity activity) { if (foregroundActivity == activity) foregroundActivity = null; }
            public void onActivityCreated(android.app.Activity activity, android.os.Bundle state) { }
            public void onActivityStarted(android.app.Activity activity) { }
            public void onActivityStopped(android.app.Activity activity) { }
            public void onActivitySaveInstanceState(android.app.Activity activity, android.os.Bundle state) { }
            public void onActivityDestroyed(android.app.Activity activity) { }
        });
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
        launcher = new LauncherCatalog(this);
        catMode = new CatModeState();
        games = new GameLibrary(new java.io.File(getFilesDir(), "games"));
        controllerLeds = new ControllerLeds(this);
        server = new ControlServer(this);
        thumbnails = new ThumbnailStore(this);
        ExternalCaptions.cacheDirectory = new java.io.File(getCacheDir(), "captions");
        diagnostics = new Diagnostics(this);
        diagnostics.start(playback);
        ensureListening("application");
        startReceiverService(this);
        updater.schedule();
    }

    void ensureListening(String source) {
        listenerSource = source;
        long now = android.os.SystemClock.elapsedRealtime();
        if ("accessibility".equals(source)) accessibilityStartedAt = now;
        if ("boot".equals(source)) bootReceivedAt = now;
        server.start();
    }

    void setPairingUi(Pairing.Listener listener) { pairingUi = listener; }

    void setGamesUi(GamesActivity activity) { gamesUi = activity; }
    boolean gamesOpen() { return gamesUi != null; }
    void refreshGames() { if (gamesUi != null) gamesUi.refreshLibrary(); }
    void stopGame() { if (gamesUi != null) gamesUi.stopGame(); }
    void closeGames() { if (gamesUi != null) gamesUi.finish(); }
    JSONObject gameStatus() throws Exception {
        return gamesUi == null ? new JSONObject().put("active", false) : gamesUi.gameStatus();
    }
    void setControllerSetupUi(ControllerSetupActivity activity) { controllerSetupUi = activity; }
    void clearControllerSetupUi(ControllerSetupActivity activity) { if (controllerSetupUi == activity) controllerSetupUi = null; }
    boolean controllerSetupOpen() { return controllerSetupUi != null; }
    JSONObject controllerSetupStatus() throws Exception {
        return controllerSetupUi == null ? new JSONObject().put("active", false).put("devices", ControllerInput.devices()) : controllerSetupUi.snapshot();
    }
    android.graphics.Bitmap controllerSetupSnapshot() { return controllerSetupUi == null ? null : controllerSetupUi.captureSnapshot(); }
    void setControllerNavigationUi(java.util.function.Supplier<JSONObject> supplier) { controllerNavigationUi = supplier; }
    JSONObject controllerNavigationStatus() { return controllerNavigationUi == null ? new JSONObject() : controllerNavigationUi.get(); }

    void setNoticeUi(java.util.function.BiConsumer<String, Integer> listener) { noticeUi = listener; }

    private volatile java.util.function.Supplier<android.graphics.Bitmap> snapshotUi;
    void setSnapshotUi(java.util.function.Supplier<android.graphics.Bitmap> supplier) { snapshotUi = supplier; }
    /** Main thread: the caption overlay exactly as drawn (video surfaces are not included). */
    android.graphics.Bitmap snapshot() { java.util.function.Supplier<android.graphics.Bitmap> target = snapshotUi; return target == null ? null : target.get(); }

    boolean isDefaultHome() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo resolved = getPackageManager().resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
        return resolved != null && resolved.activityInfo != null
            && getPackageName().equals(resolved.activityInfo.packageName)
            && MainActivity.class.getName().equals(resolved.activityInfo.name);
    }

    synchronized long noteOkPress() {
        long count = preferences.getLong(HOME_OK_PRESSES, 0) + 1;
        preferences.edit().putLong(HOME_OK_PRESSES, count).putLong(HOME_LAST_OK_AT, System.currentTimeMillis()).apply();
        diagnostics.note("key.ok count=" + count + " defaultHome=" + isDefaultHome());
        return count;
    }

    synchronized void noteHomeSettingsOpen(String source) {
        long count = preferences.getLong(HOME_SETTINGS_OPENS, 0) + 1;
        preferences.edit().putLong(HOME_SETTINGS_OPENS, count)
            .putLong(HOME_LAST_SETTINGS_AT, System.currentTimeMillis())
            .putString(HOME_LAST_SETTINGS_SOURCE, source).apply();
        diagnostics.note("home.settings count=" + count + " source=" + source);
    }

    long okPressCount() { return preferences.getLong(HOME_OK_PRESSES, 0); }

    JSONObject startupStatus() throws Exception {
        return new JSONObject().put("defaultHome", isDefaultHome()).put("uiAttached", snapshotUi != null)
            .put("processStartedBootMs", processStartedAt).put("listenerReadyBootMs", server.listeningAt)
            .put("accessibilityBootMs", accessibilityStartedAt).put("bootBroadcastMs", bootReceivedAt)
            .put("uptimeMs", android.os.SystemClock.elapsedRealtime()).put("listenerSource", listenerSource)
            .put("okPresses", preferences.getLong(HOME_OK_PRESSES, 0))
            .put("homeSettingsOpens", preferences.getLong(HOME_SETTINGS_OPENS, 0))
            .put("lastOkAt", preferences.getLong(HOME_LAST_OK_AT, 0))
            .put("lastHomeSettingsAt", preferences.getLong(HOME_LAST_SETTINGS_AT, 0))
            .put("lastHomeSettingsSource", preferences.getString(HOME_LAST_SETTINGS_SOURCE, ""));
    }

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

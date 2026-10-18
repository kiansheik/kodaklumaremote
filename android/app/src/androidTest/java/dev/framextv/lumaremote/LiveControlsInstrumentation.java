package dev.framextv.lumaremote;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import org.json.JSONObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Explicitly confirmed live Pause/Play and volume checks. No fixture setup, settings writes,
 * source opening, subtitle changes, seeks, Stop or Disconnect are permitted. */
public final class LiveControlsInstrumentation {
    private static final long CONTROL_MS = 8000;
    private static final long CLEANUP_MS = 60000;
    private final Instrumentation runner;
    private final Bundle args;
    private final Context context;
    private final Bundle diagnostics = new Bundle();
    private ConnectionService service;
    private ServiceConnection binding;
    private boolean bound;
    private String source;
    private int originalVolume = -1, maximumVolume = -1, volumeActionsRequested;
    private int passed;
    private String failure = "";

    private static final class LiveFailure extends Exception {
        final String code;
        LiveFailure(String code) { super(code); this.code = code; }
    }
    private LiveControlsInstrumentation(Instrumentation runner, Bundle args) {
        this.runner = runner; this.args = args; context = runner.getTargetContext();
    }
    public static void run(Instrumentation runner, Bundle args) {
        new LiveControlsInstrumentation(runner, args).execute();
    }
    private static void require(boolean condition, String code) throws LiveFailure {
        if (!condition) throw new LiveFailure(code);
    }
    private static long deadline() { return SystemClock.elapsedRealtime() + CONTROL_MS; }
    private void bind() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<ConnectionService> result = new AtomicReference<>();
        binding = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (binder instanceof ConnectionService.LocalBinder) result.set(((ConnectionService.LocalBinder) binder).getService());
                ready.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) { service = null; }
        };
        AtomicReference<Boolean> accepted = new AtomicReference<>(false);
        runner.runOnMainSync(() -> accepted.set(context.bindService(new Intent(context, ConnectionService.class), binding, Context.BIND_AUTO_CREATE)));
        require(accepted.get(), "LIVE_BIND_REJECTED");
        bound = true;
        require(ready.await(CONTROL_MS, TimeUnit.MILLISECONDS) && result.get() != null, "LIVE_BIND_TIMEOUT");
        service = result.get();
    }
    private JSONObject request(String method, JSONObject params, long end) throws Exception {
        require(service != null, "LIVE_SERVICE_UNAVAILABLE");
        long remaining = end - SystemClock.elapsedRealtime();
        require(remaining > 0, "LIVE_STATUS_TIMEOUT");
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<JSONObject> value = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        service.request(method, params, new ConnectionService.Callback() {
            @Override public void success(JSONObject result) { value.set(result); ready.countDown(); }
            @Override public void failure(String code, String message) { error.set(code); ready.countDown(); }
        });
        require(ready.await(remaining, TimeUnit.MILLISECONDS), "LIVE_STATUS_TIMEOUT");
        require(error.get() == null && value.get() != null, "LIVE_STATUS_FAILED");
        return value.get();
    }
    private JSONObject status(long end) throws Exception {
        return request("kodi.status", new JSONObject(), end);
    }
    private void exactSource(JSONObject playback) throws LiveFailure {
        require(playback.optBoolean("active") && source.equals(playback.optString("sourceUrl")), "LIVE_SOURCE_CHANGED");
    }
    private Notification notification(int id) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        for (StatusBarNotification entry : manager.getActiveNotifications()) {
            if (entry.getId() == id && context.getPackageName().equals(entry.getPackageName())) return entry.getNotification();
        }
        return null;
    }
    private PendingIntent action(String label, long end) throws Exception {
        while (SystemClock.elapsedRealtime() < end) {
            Notification card = notification(41);
            if (card != null && card.actions != null) for (Notification.Action action : card.actions) {
                if (label.contentEquals(action.title) && action.actionIntent != null) return action.actionIntent;
            }
            Thread.sleep(100);
        }
        throw new LiveFailure("LIVE_NOTIFICATION_ACTION_MISSING");
    }
    private void observed(boolean playing, long end) throws Exception {
        while (SystemClock.elapsedRealtime() < end) {
            JSONObject playback = status(end);
            exactSource(playback);
            if (playback.has("playing") && playback.optBoolean("playing") == playing) return;
            Thread.sleep(Math.min(150, Math.max(1, end - SystemClock.elapsedRealtime())));
        }
        throw new LiveFailure(playing ? "LIVE_PLAY_NOT_OBSERVED" : "LIVE_PAUSE_NOT_OBSERVED");
    }
    private void awaitReady() throws Exception {
        long end = SystemClock.elapsedRealtime() + 25000;
        while (SystemClock.elapsedRealtime() < end) {
            JSONObject snapshot = service.snapshot();
            JSONObject connection = snapshot.optJSONObject("connection");
            if (connection != null && connection.optBoolean("connected") && connection.optBoolean("configured")) {
                JSONObject playback;
                try { playback = status(Math.min(end, deadline())); }
                catch (LiveFailure error) {
                    if (!"LIVE_STATUS_FAILED".equals(error.code) && !"LIVE_STATUS_TIMEOUT".equals(error.code)) throw error;
                    Thread.sleep(150); continue;
                }
                JSONObject remote = service.snapshot().optJSONObject("remote");
                JSONObject media = remote == null ? null : remote.optJSONObject("media");
                if (playback.optBoolean("active") && playback.optBoolean("playing") && media != null
                        && playback.optString("sourceUrl").equals(media.optString("url"))) {
                    source = NativeBackend.publicUrl(playback.opt("sourceUrl"));
                    diagnostics.putBoolean("connected", true); diagnostics.putBoolean("configured", true);
                    diagnostics.putBoolean("verifiedRemote", true); diagnostics.putBoolean("initiallyPlaying", true);
                    return;
                }
            }
            Thread.sleep(150);
        }
        throw new LiveFailure("LIVE_READY_TIMEOUT");
    }
    private void passed(String name) {
        passed++; Bundle progress = new Bundle();
        progress.putString("test", name); progress.putInt("current", passed); runner.sendStatus(0, progress);
    }
    private void notificationControls() throws Exception {
        long end = deadline(); exactSource(status(end));
        Notification card = notification(41);
        require(card != null, "LIVE_NOTIFICATION_MISSING");
        require(Notification.CATEGORY_SERVICE.equals(card.category) && !card.extras.containsKey(Notification.EXTRA_MEDIA_SESSION), "LIVE_ORDINARY_NOTIFICATION_REQUIRED");
        diagnostics.putBoolean("ordinaryPosted", true); diagnostics.putBoolean("mediaPosted", notification(42) != null);
        action("Pause", end).send(); observed(false, end);
        diagnostics.putBoolean("notificationPauseObserved", true);
        end = deadline(); exactSource(status(end));
        action("Play", end).send(); observed(true, end);
        diagnostics.putBoolean("notificationPlayObserved", true);
        passed("ordinary_notification_pause_play");
    }
    private void mediaControls() throws Exception {
        MediaController controller = new MediaController(context, service.getMediaSessionToken());
        PlaybackState state = controller.getPlaybackState();
        require(state != null && state.getState() == PlaybackState.STATE_PLAYING
                && (state.getActions() & PlaybackState.ACTION_PAUSE) != 0 && (state.getActions() & PlaybackState.ACTION_PLAY) != 0,
                "LIVE_MEDIA_SESSION_UNAVAILABLE");
        diagnostics.putInt("mediaStateBefore", state.getState());
        diagnostics.putBoolean("sessionActivityPresent", controller.getSessionActivity() != null);
        long end = deadline(); exactSource(status(end));
        controller.getTransportControls().pause(); observed(false, end);
        diagnostics.putBoolean("mediaPauseObserved", true);
        end = deadline(); exactSource(status(end));
        controller.getTransportControls().play(); observed(true, end);
        diagnostics.putBoolean("mediaPlayObserved", true);
        PlaybackState finalState = controller.getPlaybackState();
        diagnostics.putInt("mediaStateAfter", finalState == null ? PlaybackState.STATE_NONE : finalState.getState());
        passed("media_session_pause_play");
    }
    private int volume(long end) throws Exception {
        exactSource(status(end));
        JSONObject observed = request("luma.status", new JSONObject(), end).optJSONObject("volume");
        require(observed != null && observed.optInt("max", 0) > 0 && observed.optInt("current", -1) >= 0
                && observed.optInt("current") <= observed.optInt("max"), "LIVE_VOLUME_UNAVAILABLE");
        if (maximumVolume < 0) maximumVolume = observed.getInt("max");
        require(observed.getInt("max") == maximumVolume, "LIVE_VOLUME_RANGE_CHANGED");
        return observed.getInt("current");
    }
    private int observedVolume(int before, boolean increase, long end) throws Exception {
        while (SystemClock.elapsedRealtime() < end) {
            int current = volume(end);
            if (increase ? current > before : current < before) return current;
            Thread.sleep(Math.min(150, Math.max(1, end - SystemClock.elapsedRealtime())));
        }
        throw new LiveFailure(increase ? "LIVE_VOLUME_UP_NOT_OBSERVED" : "LIVE_VOLUME_DOWN_NOT_OBSERVED");
    }
    private PlaybackState.CustomAction volumeAction(MediaController controller, String label, long end) throws Exception {
        while (SystemClock.elapsedRealtime() < end) {
            PlaybackState state = controller.getPlaybackState();
            if (state != null) for (PlaybackState.CustomAction candidate : state.getCustomActions()) {
                if (label.contentEquals(candidate.getName())) return candidate;
            }
            Thread.sleep(100);
        }
        throw new LiveFailure("LIVE_MEDIA_VOLUME_ACTION_MISSING");
    }
    private void volumeControls(boolean media) throws Exception {
        String prefix = media ? "media" : "notification";
        MediaController controller = media ? new MediaController(context, service.getMediaSessionToken()) : null;
        long end = deadline(); int before = volume(end);
        require(before > 0, "LIVE_VOLUME_BASELINE_TOO_LOW");
        if (media) {
            PlaybackState.CustomAction down = volumeAction(controller, "Volume −", end);
            controller.getTransportControls().sendCustomAction(down.getAction(), down.getExtras());
        } else action("Volume −", end).send();
        volumeActionsRequested++;
        int lowered = observedVolume(before, false, end);
        diagnostics.putInt(prefix + "VolumeBefore", before); diagnostics.putInt(prefix + "VolumeAfterDown", lowered);
        diagnostics.putBoolean(prefix + "VolumeDownObserved", true);
        end = deadline(); exactSource(status(end));
        if (media) {
            PlaybackState.CustomAction up = volumeAction(controller, "Volume +", end);
            controller.getTransportControls().sendCustomAction(up.getAction(), up.getExtras());
        } else action("Volume +", end).send();
        volumeActionsRequested++;
        int raised = observedVolume(lowered, true, end);
        diagnostics.putInt(prefix + "VolumeAfterUp", raised); diagnostics.putBoolean(prefix + "VolumeUpObserved", true);
        passed(media ? "media_session_volume_down_up" : "ordinary_notification_volume_down_up");
    }
    private void restorePlaying() throws Exception {
        if (source == null) return;
        require(service != null, "LIVE_SERVICE_UNAVAILABLE");
        // The service has one FIFO network worker. This read follows earlier
        // Pause/Play and observation requests, so do not finish while an earlier
        // control is still waiting behind a slow multi-request Kodi operation.
        long end = SystemClock.elapsedRealtime() + CLEANUP_MS;
        JSONObject playback = status(end);
        diagnostics.putBoolean("priorControlsDrained", true);
        boolean same = playback.optBoolean("active") && source.equals(playback.optString("sourceUrl"));
        diagnostics.putBoolean("sourcePreserved", same);
        require(same, "LIVE_SOURCE_CHANGED");
        Exception volumeFailure = null;
        try {
            if (originalVolume >= 0) {
                int current = volume(end);
                if (current != originalVolume) {
                    diagnostics.putBoolean("cleanupVolumeRestoreAttempted", true);
                    JSONObject restored = request("luma.setVolume", new JSONObject().put("value", originalVolume).put("expectedSourceUrl", source), end).optJSONObject("volume");
                    require(restored != null && restored.optInt("current", -1) == originalVolume && restored.optInt("max", -1) == maximumVolume, "LIVE_VOLUME_RESTORE_FAILED");
                }
                int finalVolume = volume(end);
                diagnostics.putInt("finalRawVolume", finalVolume);
                require(finalVolume == originalVolume, "LIVE_VOLUME_RESTORE_FAILED");
                diagnostics.putBoolean("volumeRestored", true);
            }
        } catch (Exception error) { volumeFailure = error; }
        // A volume timeout must not consume the playback recovery window. This
        // fresh FIFO read also drains a timed-out volume request before resume.
        end = SystemClock.elapsedRealtime() + CLEANUP_MS;
        playback = status(end); exactSource(playback);
        if (!playback.optBoolean("playing")) {
            diagnostics.putBoolean("cleanupResumeAttempted", true);
            request("kodi.resume", new JSONObject().put("expectedSourceUrl", source), end);
            observed(true, end);
        }
        diagnostics.putBoolean("finalPlaying", true);
        if (volumeFailure != null) throw volumeFailure;
    }
    private void execute() {
        try {
            require(args != null && "true".equals(args.getString("confirmLivePlayback")), "LIVE_CONFIRMATION_REQUIRED");
            diagnostics.putBoolean("confirmedLivePlayback", true);
            JSONObject saved = new SettingsStore(context).metadata();
            require(saved.optBoolean("hasReceiver") && saved.optBoolean("hasKodi"), "LIVE_SAVED_SETUP_REQUIRED");
            Intent launch = new Intent(context, MainActivity.class).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            runner.startActivitySync(launch);
            bind(); awaitReady();
            originalVolume = volume(deadline());
            diagnostics.putInt("startingRawVolume", originalVolume); diagnostics.putInt("rawVolumeMaximum", maximumVolume);
            require(originalVolume > 0, "LIVE_VOLUME_BASELINE_TOO_LOW");
            notificationControls(); mediaControls(); volumeControls(false); volumeControls(true);
        } catch (Throwable error) { failure = error instanceof LiveFailure ? ((LiveFailure) error).code : "LIVE_TEST_FAILED"; }
        finally {
            try { restorePlaying(); }
            catch (Throwable error) { if (failure.isEmpty()) failure = error instanceof LiveFailure ? ((LiveFailure) error).code : "LIVE_CLEANUP_FAILED"; }
            if (bound && binding != null) {
                try { ServiceConnection previous = binding; runner.runOnMainSync(() -> context.unbindService(previous)); }
                catch (RuntimeException ignored) { /* Never stop the user's controller service. */ }
            }
        }
        Bundle result = new Bundle(diagnostics);
        result.putInt("volumeActionsRequested", volumeActionsRequested);
        result.putInt("tests", 4); result.putInt("passed", passed); result.putInt("failures", failure.isEmpty() ? 0 : 1); result.putString("failure", failure);
        runner.finish(failure.isEmpty() ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }
}

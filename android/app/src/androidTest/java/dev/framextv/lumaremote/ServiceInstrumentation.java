package dev.framextv.lumaremote;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ServiceInfo;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Real Android service/session tests against explicit emulator-only fixtures.
 * No discovery, physical projector, production website, or local media playback. */
public final class ServiceInstrumentation {
    private static final String FIRST = "https://media.example/first.m3u8";
    private static final String OTHER = "https://media.example/other.m3u8";
    private final Instrumentation runner;
    private final Bundle args;
    private final Context context;
    private MainActivity activity;
    private ConnectionService service;
    private ServiceConnection binding;
    private int checks;
    private boolean sleptEmulator;
    private boolean disabledKeyguardOriginally;
    private String phase = "setup";
    private static final class PendingCall {
        final CountDownLatch ready = new CountDownLatch(1);
        final AtomicReference<JSONObject> result = new AtomicReference<>();
        final AtomicReference<String> failure = new AtomicReference<>();
    }

    private ServiceInstrumentation(Instrumentation runner, Bundle args) {
        this.runner = runner; this.args = args; context = runner.getTargetContext();
    }
    public static void run(Instrumentation runner, Bundle args) { new ServiceInstrumentation(runner, args).execute(); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static JSONObject object(Object... values) throws Exception {
        JSONObject result = new JSONObject();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }
    private void passed(String name) {
        checks++; Bundle status = new Bundle(); status.putString("test", name); status.putInt("current", checks); runner.sendStatus(0, status);
    }
    private void quietBrowser(MainActivity target) {
        runner.runOnMainSync(() -> {
            WebView browser = target.getWindow().getDecorView().findViewWithTag("luma-browser");
            require(browser != null, "Browsing WebView missing"); browser.stopLoading(); browser.loadUrl("about:blank");
        });
    }
    private void bind() throws Exception {
        CountDownLatch ready = new CountDownLatch(1); AtomicReference<ConnectionService> result = new AtomicReference<>();
        binding = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                result.set(((ConnectionService.LocalBinder) binder).getService()); ready.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        runner.runOnMainSync(() -> require(context.bindService(new Intent(context, ConnectionService.class), binding, Context.BIND_AUTO_CREATE), "Service bind rejected"));
        require(ready.await(8, TimeUnit.SECONDS), "Service did not bind"); service = result.get();
    }
    private void unbind() {
        if (binding == null) return;
        final ServiceConnection previous = binding; binding = null;
        runner.runOnMainSync(() -> context.unbindService(previous));
    }
    private void finishActivity() throws Exception {
        MainActivity closing = activity;
        runner.runOnMainSync(closing::finish);
        long end = SystemClock.elapsedRealtime() + 5000;
        while (!closing.isDestroyed() && SystemClock.elapsedRealtime() < end) Thread.sleep(40);
        require(closing.isDestroyed(), "Fixture Activity did not release its service binding");
        activity = null; runner.waitForIdleSync();
    }
    private PendingCall submit(String method, Object... fields) throws Exception {
        PendingCall pending = new PendingCall();
        service.request(method, object(fields), new ConnectionService.Callback() {
            @Override public void success(JSONObject value) { pending.result.set(value); pending.ready.countDown(); }
            @Override public void failure(String code, String message) { pending.failure.set(code); pending.ready.countDown(); }
        });
        return pending;
    }
    private JSONObject call(String method, Object... fields) throws Exception {
        PendingCall pending = submit(method, fields);
        require(pending.ready.await(8, TimeUnit.SECONDS), "Service request timed out");
        require(pending.failure.get() == null, "Service request failed: " + pending.failure.get()); return pending.result.get();
    }
    private JSONObject fixture(String query) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http", args.getString("targetHost"), Integer.parseInt(args.getString("kodiPort")), "/fixture" + query).openConnection();
        connection.setConnectTimeout(2000); connection.setReadTimeout(2000); connection.setInstanceFollowRedirects(false);
        try (InputStream input = connection.getInputStream()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] chunk = new byte[1024]; int count;
            while ((count = input.read(chunk)) >= 0) { require(output.size() + count <= 8192, "Fixture response exceeded bound"); output.write(chunk, 0, count); }
            return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
        } finally { connection.disconnect(); }
    }
    private void remember() throws Exception {
        JSONObject declared = object("url", "https://media.example/pt.vtt", "language", "pt-BR", "label", "Português", "index", JSONObject.NULL);
        JSONObject media = object("url", FIRST, "contentType", "application/vnd.apple.mpegurl", "title", "Owned service fixture", "tracks", new JSONArray().put(declared));
        JSONObject result = object("tracks", new JSONArray(), "catalog", new JSONArray().put(declared), "subtitleCount", 0, "selectedIndex", JSONObject.NULL, "selectedLanguage", JSONObject.NULL);
        runner.runOnMainSync(() -> service.rememberHandoff(media, result));
    }
    private JSONObject preparedMedia() throws Exception {
        return object("url", FIRST, "contentType", "application/vnd.apple.mpegurl", "title", "Owned service fixture",
                "tracks", new JSONArray().put(object("url", "https://media.example/pt.vtt", "language", "pt-BR", "label", "Português")));
    }
    private JSONObject remoteUpdate() throws Exception {
        return object("media", object("url", FIRST, "contentType", "application/vnd.apple.mpegurl"),
            "tracks", new JSONArray().put(object("index", 1, "language", "pt-BR", "label", "Português")),
            "catalog", new JSONArray().put(object("url", "https://media.example/pt.vtt", "language", "pt-BR", "label", "Português", "index", 1)),
            "selectedIndex", JSONObject.NULL, "subtitlesEnabled", false);
    }
    private Notification notification(int id) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        for (StatusBarNotification entry : manager.getActiveNotifications()) {
            if (entry.getId() == id) return entry.getNotification();
        }
        return null;
    }
    private Notification notification() { return notification(41); }
    private boolean cardsGone() { return notification(41) == null && notification(42) == null; }
    private Notification waitCard(int id, boolean exists) throws Exception {
        long end = SystemClock.elapsedRealtime() + 4000;
        while (SystemClock.elapsedRealtime() < end) {
            Notification value = notification(id); if ((value != null) == exists) return value;
            Thread.sleep(80);
        }
        throw new AssertionError(exists ? "Expected control notification missing" : "Obsolete control notification remained");
    }
    private Notification waitNotification(boolean exists) throws Exception { return waitCard(41, exists); }
    private Notification currentCard(int id, String action) throws Exception {
        long end = SystemClock.elapsedRealtime() + 6000;
        while (SystemClock.elapsedRealtime() < end) {
            JSONObject snapshot = service.snapshot(), remote = snapshot.optJSONObject("remote"), playback = snapshot.optJSONObject("playback");
            JSONObject media = remote == null ? null : remote.optJSONObject("media");
            boolean verified = media != null && playback != null && playback.optBoolean("active") && media.optString("url").equals(playback.optString("sourceUrl"));
            String expected = verified ? media.optString("title") : "LUMA Remote";
            Notification card = notification(id);
            if (card != null && expected.contentEquals(card.extras.getCharSequence(Notification.EXTRA_TITLE, ""))) {
                if (action == null) return card;
                if (card.actions != null) for (Notification.Action button : card.actions) {
                    if (button.title.toString().toLowerCase(java.util.Locale.ROOT).contains(action)) return card;
                }
            }
            Thread.sleep(80); // Wait for the posted current state, without forcing notification updates.
        }
        throw new AssertionError("The current notification title and action were not posted");
    }
    private PendingIntent currentAction(String title) throws Exception { return notificationAction(currentCard(41, title), title); }
    private void ordinaryCard(Notification card) {
        require((card.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0 && Notification.CATEGORY_SERVICE.equals(card.category), "Ordinary controls do not own the foreground service");
        require(!card.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) && !Notification.MediaStyle.class.getName().equals(card.extras.getString("android.template")), "Ordinary controls were published as a media card");
    }
    private void volumeLayout(Notification card, String playback) {
        require(card.actions != null && card.actions.length == 3, "Ordinary playback controls must have exactly three priority actions");
        require("Volume −".contentEquals(card.actions[0].title) && playback.contentEquals(card.actions[1].title)
                && "Volume +".contentEquals(card.actions[2].title), "Ordinary controls do not prioritize volume, playback, volume");
    }
    private PendingIntent notificationAction(Notification notification, String title) {
        require(notification.actions != null, "Notification controls missing");
        for (Notification.Action action : notification.actions) {
            if (action.title.toString().toLowerCase(java.util.Locale.ROOT).contains(title)) return action.actionIntent;
        }
        throw new AssertionError("Notification action missing");
    }
    private PlaybackState.CustomAction customAction(MediaController controller, String title) throws Exception {
        long end = SystemClock.elapsedRealtime() + 4000;
        while (SystemClock.elapsedRealtime() < end) {
            PlaybackState state = controller.getPlaybackState();
            if (state != null) for (PlaybackState.CustomAction action : state.getCustomActions()) {
                if (action.getName().toString().toLowerCase(java.util.Locale.ROOT).contains(title)) return action;
            }
            Thread.sleep(40);
        }
        throw new AssertionError("MediaSession custom action missing");
    }
    private void waitPlaying(boolean playing) throws Exception {
        long end = SystemClock.elapsedRealtime() + 4000;
        while (SystemClock.elapsedRealtime() < end) {
            JSONObject playback = call("kodi.status");
            if (playback.getBoolean("active") && playback.getBoolean("playing") == playing) return;
            Thread.sleep(100);
        }
        throw new AssertionError("MediaSession control was not observed");
    }
    private void waitReceiverPlaying(boolean expected) throws Exception {
        long end = SystemClock.elapsedRealtime() + 8000;
        while (SystemClock.elapsedRealtime() < end) {
            if (fixture("").getBoolean("playing") == expected) return;
            Thread.sleep(40);
        }
        throw new AssertionError("The receiver did not observe the requested playback state");
    }
    private boolean mediaWakeHeld() throws Exception {
        java.lang.reflect.Field field = ConnectionService.class.getDeclaredField("pending"); field.setAccessible(true);
        for (Object work : (java.util.Set<?>) field.get(service)) {
            java.lang.reflect.Field lockField = work.getClass().getDeclaredField("wakeLock"); lockField.setAccessible(true);
            PowerManager.WakeLock lock = (PowerManager.WakeLock) lockField.get(work);
            if (lock != null && lock.isHeld()) return true;
        }
        return false;
    }
    private void waitMediaWake(boolean expected) throws Exception {
        long end = SystemClock.elapsedRealtime() + 3000;
        while (SystemClock.elapsedRealtime() < end) { if (mediaWakeHeld() == expected) return; Thread.sleep(20); }
        throw new AssertionError(expected ? "A user control did not hold its bounded wake lock" : "A completed control retained its wake lock");
    }
    private String shell(String command) throws Exception {
        try (ParcelFileDescriptor descriptor = runner.getUiAutomation().executeShellCommand(command);
                InputStream input = new java.io.FileInputStream(descriptor.getFileDescriptor())) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] bytes = new byte[1024]; int count;
            while ((count = input.read(bytes)) >= 0) { require(output.size() + count <= 8192, "Emulator shell response exceeded bound"); output.write(bytes, 0, count); }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
    private void sleepEmulator() throws Exception {
        require(!context.getSystemService(KeyguardManager.class).isKeyguardSecure(), "Use an isolated emulator without a secure credential");
        String disabled = shell("locksettings get-disabled").trim();
        require("true".equals(disabled) || "false".equals(disabled), "Could not read the emulator's original keyguard setting");
        disabledKeyguardOriginally = "true".equals(disabled);
        sleptEmulator = true;
        if (disabledKeyguardOriginally) shell("locksettings set-disabled false");
        shell("input keyevent 224"); shell("input keyevent 223");
        long end = SystemClock.elapsedRealtime() + 8000;
        while (SystemClock.elapsedRealtime() < end && (context.getSystemService(PowerManager.class).isInteractive()
                || !context.getSystemService(KeyguardManager.class).isKeyguardLocked())) Thread.sleep(40);
        require(!context.getSystemService(PowerManager.class).isInteractive() && context.getSystemService(KeyguardManager.class).isKeyguardLocked(), "Emulator did not enter the locked, screen-off state");
    }
    private void waitVolume(int expected) throws Exception {
        long end = SystemClock.elapsedRealtime() + 6000;
        while (SystemClock.elapsedRealtime() < end) {
            if (fixture("").getInt("volume") == expected) {
                JSONObject observed = call("luma.status").getJSONObject("volume");
                require(observed.getInt("current") == expected && observed.getInt("max") == 15, "Projector volume readback does not match the wire result");
                return;
            }
            Thread.sleep(40);
        }
        throw new AssertionError("Projector volume action was not observed");
    }
    private void projectedVolume(int expected, boolean pending) throws Exception {
        long end = SystemClock.elapsedRealtime() + (pending ? 2000 : 6000);
        while (SystemClock.elapsedRealtime() < end) {
            JSONObject state = service.snapshot(), volume = state.optJSONObject("volume");
            if (volume != null && volume.optInt("current", -1) == expected && state.optBoolean("volumePending") == pending) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Projected volume or pending state was lost");
    }
    private void execute() {
        Bundle result = new Bundle();
        int resultCode = Activity.RESULT_CANCELED;
        try {
            require("10.0.2.2".equals(args.getString("targetHost")), "Only the explicit emulator fixture is supported");
            new SettingsStore(context).clear("all");
            Intent launch = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.invalid/"));
            launch.putExtra("dev.framextv.lumaremote.TEST_NO_AUTO_CONNECT", true);
            launch.setClassName(context, MainActivity.class.getName()); launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity = (MainActivity) runner.startActivitySync(launch); quietBrowser(activity); bind();
            call("app.selectTarget", "target", "luma");
            require(call("device.connect", "host", args.getString("targetHost")).getBoolean("connected"), "Fixture connection failed");
            ordinaryCard(waitNotification(true)); waitCard(42, false);
            notificationAction(waitNotification(true), "disconnect");
            SettingsStore settings = new SettingsStore(context);
            require(args.getString("targetHost").equals(settings.readReceiver().getString("host")), "Verified projector was not cached");
            call("kodi.configure", "username", "fixture", "password", "wrong-fixture", "port", Integer.parseInt(args.getString("kodiPort")));
            require(settings.readKodi() == null, "Unauthenticated credentials were persisted");
            PendingCall denied = submit("kodi.connect");
            require(denied.ready.await(8, TimeUnit.SECONDS) && "KODI_AUTH".equals(denied.failure.get()), "Wrong credentials did not produce a fixed authentication failure");
            require(!service.snapshot().getJSONObject("connection").getBoolean("configured"), "Rejected Kodi credentials remained configured");
            require(settings.readKodi() == null, "Rejected credentials were persisted");
            call("kodi.configure", "username", "fixture", "password", "fixture", "port", Integer.parseInt(args.getString("kodiPort")));
            require(settings.readKodi() == null, "Candidate credentials were saved before authentication");
            call("kodi.connect");
            require("fixture".equals(settings.readKodi().getString("password")), "Authenticated credentials were not saved");
            call("kodi.configure", "username", "fixture", "password", "wrong-fixture", "port", Integer.parseInt(args.getString("kodiPort")));
            PendingCall replacementDenied = submit("kodi.connect");
            require(replacementDenied.ready.await(8, TimeUnit.SECONDS) && "KODI_AUTH".equals(replacementDenied.failure.get()), "Replacement credentials were not authenticated");
            require("fixture".equals(settings.readKodi().getString("password")) && !service.snapshot().getJSONObject("connection").getBoolean("configured"), "Rejected replacement changed saved credentials or remained configured");
            call("kodi.configure", "username", "fixture", "password", "fixture", "port", Integer.parseInt(args.getString("kodiPort")));
            call("kodi.connect");
            service.authorizeToken("prepared-handoff-fixture");
            require(!service.prepareHandoff(preparedMedia(), "obsolete-handoff-fixture") && !service.snapshot().has("remote"), "An obsolete token retained or exposed a pending subtitle catalog");
            require(service.prepareHandoff(preparedMedia(), "prepared-handoff-fixture") && !service.snapshot().has("remote"), "Preparation exposed controls before this source was submitted");
            call("kodi.open", "media", preparedMedia(), "expectedSourceUrl", FIRST, "sourceToken", "prepared-handoff-fixture");
            require(call("kodi.status").getBoolean("active"), "Fixture media not active");
            JSONObject pendingRemote = service.snapshot().getJSONObject("remote");
            require(pendingRemote.getJSONArray("catalog").length() == 1 && pendingRemote.getJSONArray("tracks").length() == 0
                    && pendingRemote.getJSONArray("catalog").getJSONObject(0).isNull("index"), "The in-progress handoff lost its declared subtitle catalog");
            passed("an authorized Open retains the complete pending subtitle catalog before handoff completion without adding subtitles");
            JSONObject observedVolume = call("luma.setVolume", "value", 7, "expectedSourceUrl", FIRST).getJSONObject("volume");
            require(observedVolume.getInt("current") == 7 && observedVolume.getInt("max") == 15, "Absolute volume did not return the receiver readback");
            call("luma.queueVolume", "value", 8); remember(); runner.waitForIdleSync(); projectedVolume(8, true);
            call("luma.queueVolume", "value", 7); projectedVolume(7, true); projectedVolume(7, false);
            require(fixture("").getInt("volumeSets") == 2, "Completing prepared metadata discarded the pending volume burst");
            service.revokeToken("prepared-handoff-fixture");
            passed("prepared handoff completion preserves a user's pending volume while replacing caption metadata");
            Notification firstNotification = currentCard(41, "pause");
            ordinaryCard(firstNotification);
            volumeLayout(firstNotification, "Pause");
            Notification firstMedia = currentCard(42, "pause");
            require(firstMedia.actions != null && firstMedia.actions.length == 5
                    && "Volume −".contentEquals(firstMedia.actions[0].title) && "Pause".contentEquals(firstMedia.actions[1].title)
                    && "Volume +".contentEquals(firstMedia.actions[2].title) && "Stop video".contentEquals(firstMedia.actions[3].title)
                    && "Disconnect".contentEquals(firstMedia.actions[4].title)
                    && java.util.Arrays.equals(firstMedia.extras.getIntArray(Notification.EXTRA_COMPACT_ACTIONS), new int[] {0, 1, 2}), "Media card priority and expanded actions are incorrect");
            MediaSession.Token token = service.getMediaSessionToken();
            require(token.equals(firstMedia.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION)) && Notification.MediaStyle.class.getName().equals(firstMedia.extras.getString("android.template")), "Separate media card is not attached to the service MediaSession");
            PendingIntent sessionActivity = new MediaController(context, token).getSessionActivity();
            require(sessionActivity != null && sessionActivity.equals(firstNotification.contentIntent) && sessionActivity.equals(firstMedia.contentIntent) && context.getPackageName().equals(sessionActivity.getCreatorPackage()), "Ordinary and system media controls cannot open the same app remote");
            if (Build.VERSION.SDK_INT >= 29) require((service.getForegroundServiceType() & ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) != 0, "Service has the wrong foreground type");
            passed("ordinary foreground controls and a separate media card share the service session and remote launch target");

            ConnectionService original = service; unbind();
            Instrumentation.ActivityMonitor monitor = runner.addMonitor(MainActivity.class.getName(), null, false);
            runner.runOnMainSync(() -> activity.recreate());
            Activity replacement = runner.waitForMonitorWithTimeout(monitor, 8000); runner.removeMonitor(monitor);
            require(replacement instanceof MainActivity && replacement != activity, "Activity recreation did not produce a new owner");
            activity = (MainActivity) replacement; quietBrowser(activity); bind();
            require(service == original && token.equals(service.getMediaSessionToken()), "Activity recreation replaced the service/session");
            JSONObject saved = service.snapshot(); JSONObject connection = saved.getJSONObject("connection");
            require(connection.getBoolean("connected") && connection.getBoolean("configured"), "Connection or Kodi configuration was lost");
            require(FIRST.equals(saved.getJSONObject("remote").getJSONObject("media").getString("url")), "Remote handoff state was lost");
            unbind(); finishActivity(); bind();
            require(service == original && service.snapshot().getJSONObject("connection").getBoolean("connected"), "Closing the Activity ended the background connection");
            require(fixture("").getInt("connections") == 1, "Activity lifecycle opened another projector socket");
            int beforeRestore = fixture("").getInt("mutations");
            MediaController oldController = new MediaController(context, token);
            PlaybackState.CustomAction initialDisconnect = customAction(oldController, "disconnect");
            require(call("luma.queueVolume", "value", 8).getBoolean("volumePending"), "Disconnect fixture did not queue volume");
            oldController.getTransportControls().sendCustomAction(initialDisconnect.getAction(), initialDisconnect.getExtras()); waitNotification(false); waitCard(42, false);
            require(!service.snapshot().getJSONObject("connection").getBoolean("connected"), "Notification disconnect retained the connection");
            unbind(); runner.waitForIdleSync();
            java.lang.reflect.Field destroyed = ConnectionService.class.getDeclaredField("destroyed"); destroyed.setAccessible(true);
            long destroyedBy = SystemClock.elapsedRealtime() + 3000;
            while (!destroyed.getBoolean(original) && SystemClock.elapsedRealtime() < destroyedBy) Thread.sleep(40);
            require(destroyed.getBoolean(original), "Disconnected service was not destroyed after its final binding closed");
            activity = (MainActivity) runner.startActivitySync(launch); quietBrowser(activity); bind();
            require(service != original, "Test did not recreate the service");
            service.authorizeToken("active-handoff-fixture");
            JSONObject restored = call("app.settings.autoConnect");
            require(restored.getBoolean("connected") && !restored.has("devices"), "Saved projector did not take the cached connection path");
            require(service.snapshot().getJSONObject("connection").getBoolean("configured"), "Cached connection did not restore encrypted Kodi configuration");
            call("kodi.connect");
            require(fixture("").getInt("connections") == 2 && fixture("").getInt("mutations") == beforeRestore, "Restoring saved setup replayed media or opened extra sockets");
            require(fixture("").getInt("volumeSets") == 2 && fixture("").getInt("volume") == 7, "A destroyed/disconnected service sent its old queued volume");
            require(call("kodi.status").getBoolean("active") && !service.snapshot().has("remote"), "An active handoff job was replaced by playback adoption");
            ordinaryCard(waitNotification(true)); waitCard(42, false);
            service.revokeToken("active-handoff-fixture");
            require(call("kodi.status").getBoolean("active"), "Continuing receiver playback was lost during reconnect");
            JSONObject adopted = service.snapshot().getJSONObject("remote");
            require(FIRST.equals(adopted.getJSONObject("media").getString("url")) && adopted.getJSONArray("tracks").length() == 0 && adopted.getJSONArray("catalog").length() == 0, "Fresh receiver playback was not adopted without old caption aliases");
            require(fixture("").getInt("mutations") == beforeRestore, "Adopting receiver playback replayed the video or changed its subtitles");
            ordinaryCard(currentCard(41, "pause")); volumeLayout(currentCard(41, "pause"), "Pause"); currentCard(42, "pause");
            token = service.getMediaSessionToken();
            ConnectionService restoredService = service; unbind(); finishActivity(); bind();
            require(service == restoredService, "Restored connection did not remain owned by the started service");
            passed("Activity lifecycle preserves the session and a recreated service safely adopts continuing playback without replay");

            MediaController controller = new MediaController(context, token);
            phase = "ordinary_pause";
            sleepEmulator(); fixture("?hold=1");
            currentAction("pause").send();
            long actionHeldBy = SystemClock.elapsedRealtime() + 3000;
            while (fixture("").getInt("held") == 0 && SystemClock.elapsedRealtime() < actionHeldBy) Thread.sleep(20);
            require(fixture("").getInt("held") > 0, "Fixture did not hold the locked-screen action's observation");
            waitMediaWake(true); fixture("?hold=0"); waitReceiverPlaying(false); waitMediaWake(false); waitPlaying(false);
            require(!context.getSystemService(PowerManager.class).isInteractive() && context.getSystemService(KeyguardManager.class).isKeyguardLocked(), "A background control opened or unlocked the phone");
            passed("notification Pause completes with the Activity closed and the screen locked, releasing its bounded wake lock");
            phase = "ordinary_play";
            volumeLayout(currentCard(41, "play"), "Play");
            JSONObject beforeOutage = fixture("?failStatus=1");
            PendingCall failedRead = submit("kodi.status");
            require(failedRead.ready.await(8, TimeUnit.SECONDS) && "KODI_RESPONSE".equals(failedRead.failure.get()), "Fixture did not reject the transient status read");
            require(service.snapshot().getJSONObject("playback").getBoolean("stale"), "A failed observation was not marked stale");
            PendingIntent retryPlay = currentAction("play"); retryPlay.send(); waitMediaWake(true);
            long failureBy = SystemClock.elapsedRealtime() + 3000;
            while (fixture("").getInt("failedStatusReads") < beforeOutage.getInt("failedStatusReads") + 3 && SystemClock.elapsedRealtime() < failureBy) Thread.sleep(30);
            waitMediaWake(false); currentCard(42, "play");
            require(controller.getPlaybackState().getState() == PlaybackState.STATE_PAUSED
                    && fixture("").getInt("mutations") == beforeOutage.getInt("mutations"), "Unreachable Kodi erased the media session or sent an unverified write");
            fixture("?failStatus=0&failStatusOnce=1"); retryPlay.send(); waitReceiverPlaying(true); waitMediaWake(false); waitPlaying(true);
            require(!service.snapshot().getJSONObject("playback").optBoolean("stale"), "A successful observation did not clear the stale state");
            passed("failed reads preserve the locked-screen card without writes and a later button tap recovers with a read-only retry");
            phase = "session_pause";
            fixture("?failAfterPause=2"); controller.getTransportControls().pause(); waitReceiverPlaying(false); waitMediaWake(false);
            require(notification(42) != null && controller.getPlaybackState().getState() != PlaybackState.STATE_NONE, "A lost post-write read erased the control card");
            require(service.snapshot().getJSONObject("playback").optBoolean("stale"), "A lost post-write read did not preserve uncertain playback");
            waitPlaying(false);
            phase = "session_play";
            controller.getTransportControls().play(); waitPlaying(true);
            phase = "ordinary_volume";
            currentAction("volume −").send(); waitVolume(6);
            currentAction("volume +").send(); waitVolume(7);
            phase = "custom_volume";
            java.util.List<PlaybackState.CustomAction> actions = controller.getPlaybackState().getCustomActions();
            require(actions.size() == 4 && "Volume −".contentEquals(actions.get(0).getName()) && "Volume +".contentEquals(actions.get(1).getName())
                    && "Stop video".contentEquals(actions.get(2).getName()) && "Disconnect".contentEquals(actions.get(3).getName()), "MediaSession priority/custom action order is incorrect");
            PlaybackState.CustomAction volumeDown = customAction(controller, "volume −"), volumeUp = customAction(controller, "volume +");
            controller.getTransportControls().sendCustomAction(volumeDown.getAction(), volumeDown.getExtras()); waitVolume(6);
            controller.getTransportControls().sendCustomAction(volumeUp.getAction(), volumeUp.getExtras()); waitVolume(7);
            phase = "custom_stop";
            PlaybackState.CustomAction stop = customAction(controller, "stop");
            controller.getTransportControls().sendCustomAction(stop.getAction(), stop.getExtras());
            long stopBy = SystemClock.elapsedRealtime() + 4000;
            while (fixture("").getInt("stops") == 0 && SystemClock.elapsedRealtime() < stopBy) Thread.sleep(40);
            require(fixture("").getInt("stops") == 1 && !call("kodi.status").getBoolean("active"), "The current custom Stop action did not stop the selected video");
            ordinaryCard(currentCard(41, "disconnect")); waitCard(42, false);
            notificationAction(waitNotification(true), "disconnect");
            phase = "restore_after_stop";
            call("kodi.open", "media", object("url", FIRST, "contentType", "application/vnd.apple.mpegurl"), "expectedSourceUrl", FIRST);
            call("kodi.status"); remember(); runner.waitForIdleSync(); currentCard(41, "pause"); currentCard(42, "pause");
            phase = "remote_volume";
            JSONObject beforeBurst = fixture(""); int setsBeforeBurst = beforeBurst.getInt("volumeSets");
            for (Object invalid : new Object[] {-1, 16, 1.5, "8", true}) {
                PendingCall rejectedVolume = submit("luma.queueVolume", "value", invalid);
                require(rejectedVolume.ready.await(1, TimeUnit.SECONDS) && "INVALID_INPUT".equals(rejectedVolume.failure.get()), "Queued volume accepted a value outside its observed integer range");
            }
            require(call("luma.queueVolume", "value", 15).getJSONObject("volume").getInt("current") == 15, "UI queue did not return its local projection");
            controller.adjustVolume(AudioManager.ADJUST_RAISE, 0); controller.adjustVolume(AudioManager.ADJUST_RAISE, 0); runner.waitForIdleSync(); projectedVolume(15, true);
            call("luma.queueVolume", "value", 0);
            controller.adjustVolume(AudioManager.ADJUST_LOWER, 0); controller.adjustVolume(AudioManager.ADJUST_LOWER, 0); runner.waitForIdleSync(); projectedVolume(0, true);
            call("luma.queueVolume", "value", 10);
            currentAction("volume −").send(); runner.waitForIdleSync(); projectedVolume(9, true);
            PlaybackState.CustomAction burstUp = customAction(controller, "volume +");
            controller.getTransportControls().sendCustomAction(burstUp.getAction(), burstUp.getExtras()); runner.waitForIdleSync(); projectedVolume(10, true);
            controller.adjustVolume(AudioManager.ADJUST_LOWER, 0); runner.waitForIdleSync(); projectedVolume(9, true);
            call("luma.queueVolume", "value", 8); long finalTapAt = SystemClock.elapsedRealtime(); projectedVolume(8, true);
            JSONObject oldRead = call("luma.status");
            require(oldRead.getBoolean("volumePending") && oldRead.getJSONObject("volume").getInt("current") == 8, "An older receiver read overwrote the pending UI volume");
            Thread.sleep(1000);
            require(fixture("").getInt("volumeSets") == setsBeforeBurst && fixture("").getInt("volumeKeys") == 0 && fixture("").getInt("volume") == 7, "Volume taps sent network writes before the trailing cooldown");
            waitVolume(8); projectedVolume(8, false);
            require(SystemClock.elapsedRealtime() - finalTapAt >= 1900 && fixture("").getInt("volumeSets") == setsBeforeBurst + 1, "A mixed UI/system burst did not produce one delayed absolute volume write");
            passed("UI, notification and locked-screen volume taps share a bounded local projection and one trailing absolute write");

            phase = "new_volume_during_readback";
            fixture("?holdAfterVolumeSet=1"); call("luma.queueVolume", "value", 9);
            long volumeHeldBy = SystemClock.elapsedRealtime() + 5000;
            while (fixture("").getInt("volumeReadHeld") == 0 && SystemClock.elapsedRealtime() < volumeHeldBy) Thread.sleep(30);
            require(fixture("").getInt("volumeReadHeld") == 1, "Fixture did not hold the first volume write's readback");
            long newestTap = SystemClock.elapsedRealtime(); JSONObject newProjection = call("luma.queueVolume", "value", 10);
            require(SystemClock.elapsedRealtime() - newestTap < 750 && newProjection.getJSONObject("volume").getInt("current") == 10, "New volume input waited for an older network readback");
            fixture("?releaseVolume=1"); projectedVolume(10, true); Thread.sleep(200); projectedVolume(10, true);
            waitVolume(10); projectedVolume(10, false);
            call("luma.queueVolume", "value", 8); waitVolume(8); projectedVolume(8, false);
            passed("new input during an in-flight volume write remains projected and survives the older readback");

            phase = "cancel_volume_target";
            int beforeTargetCancel = fixture("").getInt("volumeSets");
            fixture("?holdNextVolumeRead=1"); PendingCall oldDelivery = submit("luma.status");
            long statusHeldBy = SystemClock.elapsedRealtime() + 2000;
            while (fixture("").getInt("volumeReadHeld") == 0 && SystemClock.elapsedRealtime() < statusHeldBy) Thread.sleep(20);
            require(fixture("").getInt("volumeReadHeld") == 1, "Fixture did not hold the old volume observation");
            java.lang.reflect.Field networkField = ConnectionService.class.getDeclaredField("network"); networkField.setAccessible(true);
            java.util.concurrent.ThreadPoolExecutor executor = (java.util.concurrent.ThreadPoolExecutor) networkField.get(service);
            long completedBeforeDelivery = executor.getCompletedTaskCount();
            java.lang.reflect.Field mainField = ConnectionService.class.getDeclaredField("main"); mainField.setAccessible(true);
            android.os.Handler serviceMain = (android.os.Handler) mainField.get(service);
            CountDownLatch mainEntered = new CountDownLatch(1), releaseMain = new CountDownLatch(1);
            PendingCall newestProjection;
            serviceMain.post(() -> { mainEntered.countDown(); try { releaseMain.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } });
            try {
                require(mainEntered.await(1, TimeUnit.SECONDS), "Fixture did not pause main-thread delivery");
                newestProjection = submit("luma.queueVolume", "value", 10);
                fixture("?releaseVolume=1");
                long preparedBy = SystemClock.elapsedRealtime() + 1500;
                while (executor.getCompletedTaskCount() == completedBeforeDelivery && SystemClock.elapsedRealtime() < preparedBy) Thread.sleep(15);
                require(executor.getCompletedTaskCount() > completedBeforeDelivery, "The old network result was not prepared before the queued local input");
            } finally { releaseMain.countDown(); }
            require(newestProjection.ready.await(1, TimeUnit.SECONDS) && newestProjection.failure.get() == null, "Local volume input did not complete before the old delivery");
            require(oldDelivery.ready.await(1, TimeUnit.SECONDS) && oldDelivery.failure.get() == null
                    && oldDelivery.result.get().getJSONObject("volume").getInt("current") == 10 && oldDelivery.result.get().getBoolean("volumePending"), "A delayed RPC callback delivered an obsolete volume after newer local input");
            projectedVolume(10, true);
            call("app.selectTarget", "target", "philips"); require(!service.snapshot().optBoolean("volumePending"), "Target switch retained an old volume projection");
            Thread.sleep(2200); require(fixture("").getInt("volumeSets") == beforeTargetCancel && fixture("").getInt("volume") == 8, "A target switch sent volume to the previous receiver");
            call("app.selectTarget", "target", "luma"); currentCard(41, "pause"); currentCard(42, "pause");
            passed("late RPC delivery uses the newest local projection and target switches cancel it before any device write");
            PendingIntent stalePause = currentAction("pause");
            PendingIntent staleVolumeDown = currentAction("volume −"), staleVolumeUp = currentAction("volume +");
            PlaybackState.CustomAction staleStop = customAction(controller, "stop");
            PlaybackState.CustomAction staleVolumeDownCustom = customAction(controller, "volume −"), staleVolumeUpCustom = customAction(controller, "volume +");
            phase = "catalog";
            require(call("app.rememberRemote", "expectedSourceUrl", FIRST, "remote", remoteUpdate()).getBoolean("accepted"), "Source-bound catalog update was rejected");
            require(service.snapshot().getJSONObject("remote").getJSONArray("catalog").getJSONObject(0).getInt("index") == 1, "Updated lazy catalog binding was not retained in RAM");
            JSONObject cleared = remoteUpdate().put("tracks", new JSONArray());
            cleared.getJSONArray("catalog").getJSONObject(0).put("index", JSONObject.NULL);
            require(call("app.rememberRemote", "expectedSourceUrl", FIRST, "remote", cleared).getBoolean("accepted"), "A removed receiver track could not clear its old catalog binding");
            JSONObject clearedRemote = service.snapshot().getJSONObject("remote");
            require(clearedRemote.getJSONArray("tracks").length() == 0 && clearedRemote.getJSONArray("catalog").getJSONObject(0).isNull("index"), "Cleared catalog retained the obsolete receiver index");
            require(call("app.rememberRemote", "expectedSourceUrl", FIRST, "remote", remoteUpdate()).getBoolean("accepted"), "A cleared catalog could not bind its observed receiver index again");
            phase = "stale_other_source";
            JSONObject changed = fixture("?source=other"); int before = changed.getInt("mutations");
            PendingCall staleCatalog = submit("app.rememberRemote", "expectedSourceUrl", FIRST, "remote", remoteUpdate());
            require(staleCatalog.ready.await(3, TimeUnit.SECONDS) && "SOURCE_CHANGED".equals(staleCatalog.failure.get()), "A stale source updated the remote catalog");
            controller.getTransportControls().pause(); controller.adjustVolume(AudioManager.ADJUST_RAISE, 0); stalePause.send();
            staleVolumeDown.send(); staleVolumeUp.send();
            controller.getTransportControls().sendCustomAction(staleVolumeDownCustom.getAction(), staleVolumeDownCustom.getExtras());
            controller.getTransportControls().sendCustomAction(staleVolumeUpCustom.getAction(), staleVolumeUpCustom.getExtras());
            controller.getTransportControls().sendCustomAction(staleStop.getAction(), staleStop.getExtras()); Thread.sleep(2700); call("kodi.status");
            JSONObject untouched = fixture("");
            require(untouched.getInt("mutations") == before && untouched.getInt("volumeKeys") == 0 && untouched.getInt("volume") == 8 && untouched.getBoolean("playing") && OTHER.equals(untouched.getString("source")), "A stale media control changed another source");
            phase = "stale_same_source";
            fixture("?source=first"); call("kodi.status"); remember(); runner.waitForIdleSync();
            int renewed = fixture("").getInt("mutations");
            staleVolumeDown.send(); staleVolumeUp.send();
            controller.getTransportControls().sendCustomAction(staleVolumeDownCustom.getAction(), staleVolumeDownCustom.getExtras());
            controller.getTransportControls().sendCustomAction(staleVolumeUpCustom.getAction(), staleVolumeUpCustom.getExtras());
            stalePause.send(); controller.getTransportControls().sendCustomAction(staleStop.getAction(), staleStop.getExtras()); Thread.sleep(250); call("kodi.status");
            require(fixture("").getInt("mutations") == renewed && fixture("").getInt("volumeKeys") == 0 && fixture("").getInt("volume") == 8, "A stale notification retargeted a renewed handoff of the same source");
            passed("MediaSession controls work in background and stale notification/session controls cannot alter another source");

            phase = "independent_kodi_http";
            fixture("?dropTcp=1"); PendingCall hardwareLost = submit("luma.status");
            require(hardwareLost.ready.await(8, TimeUnit.SECONDS) && hardwareLost.failure.get() != null
                    && !service.snapshot().getJSONObject("connection").getBoolean("connected"), "Fixture did not disconnect the projector socket");
            Notification httpOnly = currentCard(41, "pause");
            long hardwareCardBy = SystemClock.elapsedRealtime() + 3000;
            while (httpOnly.actions.length != 1 && SystemClock.elapsedRealtime() < hardwareCardBy) { Thread.sleep(50); httpOnly = currentCard(41, "pause"); }
            require(httpOnly.actions.length == 1 && "Pause".contentEquals(httpOnly.actions[0].title), "Disconnected projector volume remained available or Kodi controls disappeared");
            notificationAction(httpOnly, "pause").send(); waitReceiverPlaying(false); waitMediaWake(false); waitPlaying(false);
            controller.getTransportControls().play(); waitReceiverPlaying(true); waitMediaWake(false); waitPlaying(true); currentCard(42, "pause");
            require(context.getSystemService(KeyguardManager.class).isKeyguardLocked(), "Independent HTTP controls unlocked the device");
            passed("locked-screen Kodi HTTP controls survive an independent projector TCP failure without hardware volume actions");

            phase = "disconnect";
            int stops = fixture("").getInt("stops");
            PlaybackState.CustomAction disconnect = customAction(controller, "disconnect");
            fixture("?hold=1"); PendingCall held = submit("kodi.status");
            long heldBy = SystemClock.elapsedRealtime() + 3000;
            while (fixture("").getInt("held") == 0 && SystemClock.elapsedRealtime() < heldBy) Thread.sleep(40);
            require(fixture("").getInt("held") > 0, "Fixture did not hold a native HTTP read");
            PendingCall queued = submit("kodi.pause", "expectedSourceUrl", FIRST);
            long disconnectStarted = SystemClock.elapsedRealtime();
            controller.getTransportControls().sendCustomAction(disconnect.getAction(), disconnect.getExtras());
            long disconnectedBy = disconnectStarted + 750;
            while ((service.snapshot().getJSONObject("connection").getBoolean("connected") || !cardsGone()) && SystemClock.elapsedRealtime() < disconnectedBy) Thread.sleep(20);
            require(!service.snapshot().getJSONObject("connection").getBoolean("connected") && cardsGone(), "Disconnect waited behind a blocked command or retained a control card");
            result.putLong("disconnectMillis", SystemClock.elapsedRealtime() - disconnectStarted);
            require(queued.ready.await(1, TimeUnit.SECONDS) && queued.failure.get() != null, "Queued mutation was not rejected during disconnect");
            require(held.ready.await(1, TimeUnit.SECONDS) && held.failure.get() != null, "Held request was not cancelled during disconnect");
            fixture("?hold=0"); Thread.sleep(250);
            JSONObject independent = fixture("");
            require(!service.snapshot().getJSONObject("connection").getBoolean("connected"), "Disconnect retained the control session");
            require(FIRST.equals(independent.getString("source")) && independent.getBoolean("playing") && independent.getInt("stops") == stops, "Disconnect stopped receiver playback");
            require(cardsGone(), "A stale completion revived a disconnected control card");
            passed("system disconnect bypasses blocked reads, rejects queued writes and preserves receiver playback");

            phase = "power_off";
            require(call("device.connect", "host", args.getString("targetHost")).getBoolean("connected"), "Power fixture reconnect failed");
            require(call("kodi.status").getBoolean("active"), "Power fixture did not retain independent playback");
            currentCard(41, "pause"); currentCard(42, "pause");
            for (Object confirmation : new Object[] {JSONObject.NULL, false, "true"}) {
                PendingCall unconfirmed = confirmation == JSONObject.NULL ? submit("luma.powerOff") : submit("luma.powerOff", "confirmed", confirmation);
                require(unconfirmed.ready.await(3, TimeUnit.SECONDS) && "INVALID_INPUT".equals(unconfirmed.failure.get()), "Power-off did not require explicit boolean confirmation");
                require(service.snapshot().getJSONObject("connection").getBoolean("connected") && fixture("").getInt("powerCommands") == 0, "Rejected power-off changed the control session or wrote a power key");
            }
            int mutationsBeforePower = fixture("").getInt("mutations");
            require(call("luma.powerOff", "confirmed", true).getBoolean("accepted"), "Confirmed power-off did not return its send receipt");
            waitNotification(false); waitCard(42, false);
            JSONObject powered = service.snapshot();
            require(!powered.getJSONObject("connection").getBoolean("connected") && powered.getBoolean("autoConnectPaused"), "Power-off retained the control connection or automatic reconnect");
            JSONObject savedAfterPower = settings.metadata();
            require(savedAfterPower.getBoolean("hasReceiver") && savedAfterPower.getBoolean("hasKodi")
                    && args.getString("targetHost").equals(settings.readReceiver().getString("host"))
                    && "fixture".equals(settings.readKodi().getString("password")), "Power-off erased or changed the saved setup");
            JSONObject paused = call("app.settings.autoConnect"), powerFixture = fixture("");
            require(paused.getBoolean("paused") && !paused.getBoolean("connected") && powerFixture.getInt("connections") == 3, "Power-off immediately reconnected to the projector");
            require(powerFixture.getInt("powerCommands") == 1 && powerFixture.getInt("mutations") == mutationsBeforePower
                    && powerFixture.getInt("volume") == 8, "Confirmed power-off wrote extra commands or changed unrelated playback controls");
            passed("confirmed power send tears down local controls, pauses automatic reconnect and retains saved setup");

            phase = "matter_async_lifecycle";
            java.lang.reflect.Field adapterField = ConnectionService.class.getDeclaredField("matter"); adapterField.setAccessible(true);
            MatterControllerAdapter adapter = (MatterControllerAdapter) adapterField.get(service);
            java.lang.reflect.Field stateField = MatterControllerAdapter.class.getDeclaredField("published"); stateField.setAccessible(true);
            java.lang.reflect.Field listenerField = MatterControllerAdapter.class.getDeclaredField("listener"); listenerField.setAccessible(true);
            MatterControllerAdapter.Listener listener = (MatterControllerAdapter.Listener) listenerField.get(adapter);
            java.lang.reflect.Field foregroundField = ConnectionService.class.getDeclaredField("foreground"); foregroundField.setAccessible(true);
            java.lang.reflect.Field pollField = ConnectionService.class.getDeclaredField("matterPoll"); pollField.setAccessible(true);
            java.lang.reflect.Field handlerField = ConnectionService.class.getDeclaredField("main"); handlerField.setAccessible(true);
            android.os.Handler handler = (android.os.Handler) handlerField.get(service);
            java.lang.reflect.Method promote = ConnectionService.class.getDeclaredMethod("promote"); promote.setAccessible(true);
            JSONObject matterOriginal = adapter.snapshot();
            runner.runOnMainSync(() -> { try { promote.invoke(service); listener.onStateChanged(matterOriginal); } catch (Exception error) { throw new RuntimeException(error); } });
            runner.waitForIdleSync();
            require(foregroundField.getBoolean(service), "An initial healthy SDK callback demoted an active connection attempt");
            JSONObject paired = new JSONObject(matterOriginal.toString()).put("paired", true).put("connected", true).put("pairing", false).put("paused", false).put("errorCode", JSONObject.NULL);
            stateField.set(adapter, paired); runner.runOnMainSync(() -> listener.onStateChanged(paired)); runner.waitForIdleSync();
            require(handler.hasCallbacks((Runnable) pollField.get(service)), "Asynchronous pairing completion did not schedule background status polling");
            JSONObject failed = new JSONObject(matterOriginal.toString()).put("paired", false).put("connected", false).put("pairing", false).put("paused", false).put("errorCode", "MATTER_PAIRING");
            stateField.set(adapter, failed); runner.runOnMainSync(() -> listener.onStateChanged(failed)); runner.waitForIdleSync();
            require(!foregroundField.getBoolean(service) && !handler.hasCallbacks((Runnable) pollField.get(service)), "Failed background pairing retained the idle foreground service or polling timer");
            stateField.set(adapter, matterOriginal);
            passed("asynchronous Matter state starts background polling and failed pairing releases the idle foreground service");
            result.putInt("passed", checks); result.putInt("failures", 0); resultCode = Activity.RESULT_OK;
        } catch (Throwable error) {
            result.putString("phase", phase); result.putInt("passed", checks); result.putInt("failures", 1); result.putString("failure", error.getClass().getSimpleName() + ": " + error.getMessage());
        } finally {
            if (sleptEmulator) {
                try {
                    shell("input keyevent 224"); shell("wm dismiss-keyguard");
                    if (disabledKeyguardOriginally) shell("locksettings set-disabled true");
                } catch (Exception ignored) { }
            }
            if (service != null) { try { call("device.disconnect"); } catch (Exception ignored) { } }
            try { new SettingsStore(context).clear("all"); } catch (Exception ignored) { }
            unbind(); if (activity != null) runner.runOnMainSync(() -> activity.finish());
        }
        runner.finish(resultCode, result);
    }
}

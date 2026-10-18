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
import android.content.pm.ServiceInfo;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
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
    private JSONObject remoteUpdate() throws Exception {
        return object("media", object("url", FIRST, "contentType", "application/vnd.apple.mpegurl"),
            "tracks", new JSONArray().put(object("index", 1, "language", "pt-BR", "label", "Português")),
            "catalog", new JSONArray().put(object("url", "https://media.example/pt.vtt", "language", "pt-BR", "label", "Português", "index", 1)),
            "selectedIndex", JSONObject.NULL, "subtitlesEnabled", false);
    }
    private Notification notification() {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        for (StatusBarNotification entry : manager.getActiveNotifications()) {
            Notification notification = entry.getNotification();
            if ((notification.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) return notification;
        }
        return null;
    }
    private Notification waitNotification(boolean exists) throws Exception {
        long end = SystemClock.elapsedRealtime() + 4000;
        while (SystemClock.elapsedRealtime() < end) {
            Notification value = notification(); if ((value != null) == exists) return value;
            Thread.sleep(80);
        }
        throw new AssertionError(exists ? "Foreground notification missing" : "Disconnected foreground notification remained");
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
    private void execute() {
        Bundle result = new Bundle();
        try {
            require("10.0.2.2".equals(args.getString("targetHost")), "Only the explicit emulator fixture is supported");
            new SettingsStore(context).clear("all");
            Intent launch = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.invalid/"));
            launch.putExtra("dev.framextv.lumaremote.TEST_NO_AUTO_CONNECT", true);
            launch.setClassName(context, MainActivity.class.getName()); launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity = (MainActivity) runner.startActivitySync(launch); quietBrowser(activity); bind();
            require(call("device.connect", "host", args.getString("targetHost")).getBoolean("connected"), "Fixture connection failed");
            require(!waitNotification(true).extras.containsKey(Notification.EXTRA_MEDIA_SESSION), "An idle projector notification was published as active media");
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
            call("kodi.open", "media", object("url", FIRST, "contentType", "application/vnd.apple.mpegurl"), "expectedSourceUrl", FIRST);
            require(call("kodi.status").getBoolean("active"), "Fixture media not active"); remember();
            JSONObject observedVolume = call("luma.setVolume", "value", 7, "expectedSourceUrl", FIRST).getJSONObject("volume");
            require(observedVolume.getInt("current") == 7 && observedVolume.getInt("max") == 15, "Absolute volume did not return the receiver readback");
            Notification firstNotification = waitNotification(true);
            MediaSession.Token token = service.getMediaSessionToken();
            require(token.equals(firstNotification.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION)), "Notification is not attached to the service MediaSession");
            PendingIntent sessionActivity = new MediaController(context, token).getSessionActivity();
            require(sessionActivity != null && sessionActivity.equals(firstNotification.contentIntent) && context.getPackageName().equals(sessionActivity.getCreatorPackage()), "System media controls cannot open the app remote");
            if (Build.VERSION.SDK_INT >= 29) require((service.getForegroundServiceType() & ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) != 0, "Service has the wrong foreground type");
            passed("connection is owned by the started foreground service and MediaSession");

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
            notificationAction(waitNotification(true), "disconnect").send(); waitNotification(false);
            require(!service.snapshot().getJSONObject("connection").getBoolean("connected"), "Notification disconnect retained the connection");
            unbind(); runner.waitForIdleSync();
            java.lang.reflect.Field destroyed = ConnectionService.class.getDeclaredField("destroyed"); destroyed.setAccessible(true);
            long destroyedBy = SystemClock.elapsedRealtime() + 3000;
            while (!destroyed.getBoolean(original) && SystemClock.elapsedRealtime() < destroyedBy) Thread.sleep(40);
            require(destroyed.getBoolean(original), "Disconnected service was not destroyed after its final binding closed");
            activity = (MainActivity) runner.startActivitySync(launch); quietBrowser(activity); bind();
            require(service != original, "Test did not recreate the service");
            JSONObject restored = call("app.settings.autoConnect");
            require(restored.getBoolean("connected") && !restored.has("devices"), "Saved projector did not take the cached connection path");
            require(service.snapshot().getJSONObject("connection").getBoolean("configured"), "Cached connection did not restore encrypted Kodi configuration");
            call("kodi.connect");
            require(fixture("").getInt("connections") == 2 && fixture("").getInt("mutations") == beforeRestore, "Restoring saved setup replayed media or opened extra sockets");
            require(!service.snapshot().has("remote"), "A service restart replayed the previous handoff");
            call("kodi.status"); remember(); token = service.getMediaSessionToken();
            ConnectionService restoredService = service; unbind(); finishActivity(); bind();
            require(service == restoredService, "Restored connection did not remain owned by the started service");
            passed("Activity lifecycle preserves the session and a recreated service restores authenticated saved setup without media replay");

            MediaController controller = new MediaController(context, token);
            controller.getTransportControls().pause(); waitPlaying(false);
            controller.getTransportControls().play(); waitPlaying(true);
            controller.adjustVolume(AudioManager.ADJUST_RAISE, 0);
            long volumeBy = SystemClock.elapsedRealtime() + 3000;
            while (fixture("").getInt("volumeKeys") == 0 && SystemClock.elapsedRealtime() < volumeBy) Thread.sleep(40);
            require(fixture("").getInt("volumeKeys") == 1, "Framework remote volume did not send one projector control");
            PendingIntent stalePause = notificationAction(waitNotification(true), "pause");
            PlaybackState.CustomAction staleStop = customAction(controller, "stop");
            require(call("app.rememberRemote", "expectedSourceUrl", FIRST, "remote", remoteUpdate()).getBoolean("accepted"), "Source-bound catalog update was rejected");
            require(service.snapshot().getJSONObject("remote").getJSONArray("catalog").getJSONObject(0).getInt("index") == 1, "Updated lazy catalog binding was not retained in RAM");
            JSONObject cleared = remoteUpdate().put("tracks", new JSONArray());
            cleared.getJSONArray("catalog").getJSONObject(0).put("index", JSONObject.NULL);
            require(call("app.rememberRemote", "expectedSourceUrl", FIRST, "remote", cleared).getBoolean("accepted"), "A removed receiver track could not clear its old catalog binding");
            JSONObject clearedRemote = service.snapshot().getJSONObject("remote");
            require(clearedRemote.getJSONArray("tracks").length() == 0 && clearedRemote.getJSONArray("catalog").getJSONObject(0).isNull("index"), "Cleared catalog retained the obsolete receiver index");
            require(call("app.rememberRemote", "expectedSourceUrl", FIRST, "remote", remoteUpdate()).getBoolean("accepted"), "A cleared catalog could not bind its observed receiver index again");
            JSONObject changed = fixture("?source=other"); int before = changed.getInt("mutations");
            PendingCall staleCatalog = submit("app.rememberRemote", "expectedSourceUrl", FIRST, "remote", remoteUpdate());
            require(staleCatalog.ready.await(3, TimeUnit.SECONDS) && "SOURCE_CHANGED".equals(staleCatalog.failure.get()), "A stale source updated the remote catalog");
            controller.getTransportControls().pause(); controller.adjustVolume(AudioManager.ADJUST_RAISE, 0); stalePause.send();
            controller.getTransportControls().sendCustomAction(staleStop.getAction(), staleStop.getExtras()); Thread.sleep(350); call("kodi.status");
            JSONObject untouched = fixture("");
            require(untouched.getInt("mutations") == before && untouched.getInt("volumeKeys") == 1 && untouched.getBoolean("playing") && OTHER.equals(untouched.getString("source")), "A stale media control changed another source");
            fixture("?source=first"); call("kodi.status"); remember(); runner.waitForIdleSync();
            int renewed = fixture("").getInt("mutations");
            stalePause.send(); controller.getTransportControls().sendCustomAction(staleStop.getAction(), staleStop.getExtras()); Thread.sleep(250); call("kodi.status");
            require(fixture("").getInt("mutations") == renewed, "A stale notification retargeted a renewed handoff of the same source");
            passed("MediaSession controls work in background and stale notification/session controls cannot alter another source");

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
            while ((service.snapshot().getJSONObject("connection").getBoolean("connected") || notification() != null) && SystemClock.elapsedRealtime() < disconnectedBy) Thread.sleep(20);
            require(!service.snapshot().getJSONObject("connection").getBoolean("connected") && notification() == null, "Disconnect waited behind a blocked or queued network command");
            result.putLong("disconnectMillis", SystemClock.elapsedRealtime() - disconnectStarted);
            require(queued.ready.await(1, TimeUnit.SECONDS) && queued.failure.get() != null, "Queued mutation was not rejected during disconnect");
            require(held.ready.await(1, TimeUnit.SECONDS) && held.failure.get() != null, "Held request was not cancelled during disconnect");
            fixture("?hold=0"); Thread.sleep(250);
            JSONObject independent = fixture("");
            require(!service.snapshot().getJSONObject("connection").getBoolean("connected"), "Disconnect retained the control session");
            require(FIRST.equals(independent.getString("source")) && independent.getBoolean("playing") && independent.getInt("stops") == stops, "Disconnect stopped receiver playback");
            require(notification() == null, "A stale completion revived the disconnected foreground service");
            passed("system disconnect bypasses blocked reads, rejects queued writes and preserves receiver playback");
            result.putInt("passed", checks); result.putInt("failures", 0); runner.finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putInt("passed", checks); result.putInt("failures", 1); result.putString("failure", error.getClass().getSimpleName() + ": " + error.getMessage()); runner.finish(Activity.RESULT_CANCELED, result);
        } finally {
            if (service != null) { try { call("device.disconnect"); } catch (Exception ignored) { } }
            try { new SettingsStore(context).clear("all"); } catch (Exception ignored) { }
            unbind(); if (activity != null) runner.runOnMainSync(() -> activity.finish());
        }
    }
}

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
    private static final String NEXT = "https://media.example/next.m3u8";
    private static final String TOKEN = "fixture_receiver_token_0123456789", WRONG_TOKEN = "fixture_revoked_token_0123456789", CODE = "123456";
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
        HttpURLConnection connection = (HttpURLConnection) new URL("http", args.getString("targetHost"), Integer.parseInt(args.getString("receiverPort")), "/fixture" + query).openConnection();
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
    private JSONObject waitCanonicalRemote(String source, String key, String title, String subtitleUrl) throws Exception {
        long end = SystemClock.elapsedRealtime() + 5000;
        while (SystemClock.elapsedRealtime() < end) {
            JSONObject remote = service.snapshot().optJSONObject("remote"), media = remote == null ? null : remote.optJSONObject("media");
            JSONObject content = media == null ? null : media.optJSONObject("content"); JSONArray catalog = remote == null ? null : remote.optJSONArray("catalog");
            if (media != null && source.equals(media.optString("url")) && title.equals(media.optString("title"))
                    && content != null && key.equals(content.optString("key")) && title.equals(content.optString("title"))
                    && catalog != null && catalog.length() == 1 && subtitleUrl.equals(catalog.optJSONObject(0).optString("url"))) return remote;
            Thread.sleep(40);
        }
        throw new AssertionError("Canonical playback metadata and catalog subtitles were not hydrated for " + key);
    }
    private PendingIntent currentAction(String title) throws Exception { return notificationAction(currentCard(41, title), title); }
    private void ordinaryCard(Notification card) {
        require((card.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0 && Notification.CATEGORY_SERVICE.equals(card.category), "Ordinary controls do not own the foreground service");
        require(!card.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) && !Notification.MediaStyle.class.getName().equals(card.extras.getString("android.template")), "Ordinary controls were published as a media card");
    }
    private void volumeLayout(Notification card, String playback) {
        require(card.actions != null && card.actions.length == 4, "Ordinary playback controls must have exactly four priority actions");
        require("Volume −".contentEquals(card.actions[0].title) && "Back 30 seconds".contentEquals(card.actions[1].title)
                && playback.contentEquals(card.actions[2].title) && "Volume +".contentEquals(card.actions[3].title),
                "Ordinary controls do not prioritize volume, rewind, playback, volume");
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
            JSONObject playback = call("receiver.status");
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
    private void waitReceiverPosition(int expected) throws Exception {
        long end = SystemClock.elapsedRealtime() + 8000;
        while (SystemClock.elapsedRealtime() < end) {
            if (fixture("").getInt("position") == expected) return;
            Thread.sleep(40);
        }
        throw new AssertionError("The receiver did not observe the requested playback position");
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
            // The fixture serves the shipped default port, so the saved-pairing restore path below is real.
            int receiverPort = Integer.parseInt(args.getString("receiverPort"));
            require(receiverPort == 4191, "The service fixture must use LUMA Receiver's default port");
            PendingCall unpaired = submit("receiver.connect");
            require(unpaired.ready.await(8, TimeUnit.SECONDS) && "RECEIVER_CONFIG".equals(unpaired.failure.get()), "An unpaired phone did not report missing pairing");
            call("receiver.configure", "token", WRONG_TOKEN, "port", receiverPort);
            require(settings.readPairing() == null, "A directly configured token was persisted");
            PendingCall denied = submit("receiver.connect");
            require(denied.ready.await(8, TimeUnit.SECONDS) && "RECEIVER_AUTH".equals(denied.failure.get()), "A revoked token did not produce a fixed authentication failure");
            require(!service.snapshot().getJSONObject("connection").getBoolean("configured"), "A rejected receiver token remained configured");
            require(settings.readPairing() == null && !settings.metadata().getBoolean("hasPairing"), "A rejected token was persisted");
            String pairingId = call("receiver.pairBegin", "port", receiverPort).getString("pairingId");
            PendingCall wrongCode = submit("receiver.pairComplete", "pairingId", pairingId, "code", "000000");
            require(wrongCode.ready.await(8, TimeUnit.SECONDS) && "PAIRING_CODE_WRONG".equals(wrongCode.failure.get()), "A wrong pairing code was not rejected");
            require(settings.readPairing() == null && !service.snapshot().getJSONObject("connection").getBoolean("configured"), "A wrong pairing code saved or configured a token");
            JSONObject pairedReply = call("receiver.pairComplete", "pairingId", pairingId, "code", CODE);
            require(pairedReply.getBoolean("configured") && !pairedReply.has("token") && !service.snapshot().toString().contains(TOKEN), "The pairing token reached the WebView reply or service snapshot");
            require(TOKEN.equals(settings.readPairing().getString("token")) && settings.metadata().getBoolean("hasPairing"), "The issued pairing token was not saved encrypted");
            require(service.snapshot().getJSONObject("connection").getBoolean("configured"), "Pairing did not configure the live receiver connection");
            call("receiver.connect");
            call("receiver.configure", "token", WRONG_TOKEN, "port", receiverPort);
            PendingCall replacementDenied = submit("receiver.connect");
            require(replacementDenied.ready.await(8, TimeUnit.SECONDS) && "RECEIVER_AUTH".equals(replacementDenied.failure.get()), "A replacement token was not authenticated");
            require(TOKEN.equals(settings.readPairing().getString("token")) && !service.snapshot().getJSONObject("connection").getBoolean("configured"), "A rejected replacement changed the saved pairing or remained configured");
            call("receiver.configure", "token", TOKEN, "port", receiverPort);
            call("receiver.connect");
            service.authorizeToken("prepared-handoff-fixture");
            require(!service.prepareHandoff(preparedMedia(), "obsolete-handoff-fixture") && !service.snapshot().has("remote"), "An obsolete token retained or exposed a pending subtitle catalog");
            require(service.prepareHandoff(preparedMedia(), "prepared-handoff-fixture") && !service.snapshot().has("remote"), "Preparation exposed controls before this source was submitted");
            call("receiver.open", "media", preparedMedia(), "expectedSourceUrl", FIRST, "sourceToken", "prepared-handoff-fixture");
            require(call("receiver.status").getBoolean("active"), "Fixture media not active");
            JSONObject pendingRemote = service.snapshot().getJSONObject("remote");
            require(pendingRemote.getJSONArray("catalog").length() == 1 && pendingRemote.getJSONArray("tracks").length() == 0
                    && pendingRemote.getJSONArray("catalog").getJSONObject(0).isNull("index"), "The in-progress handoff lost its declared subtitle catalog");
            passed("an authorized Open retains the complete pending subtitle catalog before handoff completion without adding subtitles");
            JSONObject observedVolume = call("luma.setVolume", "value", 7, "expectedSourceUrl", FIRST).getJSONObject("volume");
            require(observedVolume.getInt("current") == 7 && observedVolume.getInt("max") == 15, "Absolute volume did not return the receiver readback");
            call("luma.queueVolume", "value", 8);
            java.lang.reflect.Field preparedRevisionField = ConnectionService.class.getDeclaredField("mediaRevision"); preparedRevisionField.setAccessible(true);
            String preparedRevision = (String) preparedRevisionField.get(service);
            java.lang.reflect.Field capturedTokenField = MainActivity.class.getDeclaredField("capturedToken"); capturedTokenField.setAccessible(true);
            java.lang.reflect.Field capturedMediaField = MainActivity.class.getDeclaredField("capturedMedia"); capturedMediaField.setAccessible(true);
            java.lang.reflect.Field capturedGenerationField = MainActivity.class.getDeclaredField("capturedGeneration"); capturedGenerationField.setAccessible(true);
            java.lang.reflect.Field pageGenerationField = MainActivity.class.getDeclaredField("pageGeneration"); pageGenerationField.setAccessible(true);
            java.util.concurrent.atomic.AtomicLong pageGeneration = (java.util.concurrent.atomic.AtomicLong) pageGenerationField.get(activity);
            capturedTokenField.set(activity, "prepared-handoff-fixture"); capturedMediaField.set(activity, preparedMedia());
            capturedGenerationField.setLong(activity, pageGeneration.get());
            JSONObject readyRemote = object("tracks", new JSONArray(), "catalog", pendingRemote.getJSONArray("catalog"),
                    "selectedIndex", JSONObject.NULL, "subtitlesEnabled", false);
            java.lang.reflect.Method readyRequest = MainActivity.class.getDeclaredMethod("appRequest", String.class, String.class, JSONObject.class);
            readyRequest.setAccessible(true);
            runner.runOnMainSync(() -> {
                try { readyRequest.invoke(activity, "ready-order-fixture", "app.handoffReady",
                        object("sourceToken", "prepared-handoff-fixture", "remote", readyRemote, "resumeApplied", false)); }
                catch (Exception error) { throw new RuntimeException(error); }
            });
            runner.waitForIdleSync(); projectedVolume(8, true);
            java.lang.reflect.Field preparedHandoffField = ConnectionService.class.getDeclaredField("handoffInProgress"); preparedHandoffField.setAccessible(true);
            require(preparedRevision.equals(preparedRevisionField.get(service)) && !preparedHandoffField.getBoolean(service),
                    "Successful handoff completion ran before prepared metadata was consumed");
            call("luma.queueVolume", "value", 7); projectedVolume(7, true); projectedVolume(7, false);
            require(fixture("").getInt("volumeSets") == 2, "Completing prepared metadata discarded the pending volume burst");
            service.revokeToken("prepared-handoff-fixture");
            passed("MainActivity handoff completion preserves prepared revision and a user's pending volume while replacing caption metadata");
            Notification firstNotification = currentCard(41, "pause");
            ordinaryCard(firstNotification);
            volumeLayout(firstNotification, "Pause");
            Notification firstMedia = currentCard(42, "pause");
            require(firstMedia.actions != null && firstMedia.actions.length == 4
                    && "Back 30 seconds".contentEquals(firstMedia.actions[0].title) && "Pause".contentEquals(firstMedia.actions[1].title)
                    && "Stop video".contentEquals(firstMedia.actions[2].title) && "Disconnect".contentEquals(firstMedia.actions[3].title)
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
            require(connection.getBoolean("connected") && connection.getBoolean("configured"), "Connection or receiver pairing was lost");
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
            JSONObject restored = call("receiver.connect", "sourceToken", "active-handoff-fixture");
            require(restored.getBoolean("ready"), "A direct handoff did not restore saved receiver configuration");
            JSONObject direct = service.snapshot().getJSONObject("connection");
            require(direct.getBoolean("configured") && direct.getBoolean("receiverConnected") && !direct.getBoolean("nativeConnected"),
                    "Direct handoff did not restore the receiver before native navigation");
            require(fixture("").getInt("connections") == 1 && fixture("").getInt("mutations") == beforeRestore,
                    "Direct receiver restore replayed media or waited for the native projector socket");
            passed("a direct cast restores the saved receiver before native projector navigation is available");
            JSONObject navigation = call("app.settings.autoConnect");
            require(navigation.getBoolean("connected") && service.snapshot().getJSONObject("connection").getBoolean("nativeConnected")
                    && fixture("").getInt("connections") == 2, "A later retry did not attach native projector navigation");
            require(fixture("").getInt("volumeSets") == 2 && fixture("").getInt("volume") == 7, "A destroyed/disconnected service sent its old queued volume");
            require(call("receiver.status").getBoolean("active") && !service.snapshot().has("remote"), "An active handoff job was replaced by playback adoption");
            ordinaryCard(waitNotification(true)); waitCard(42, false);
            java.util.List<String> catalogRequests = new java.util.concurrent.CopyOnWriteArrayList<>();
            AtomicReference<String> nextStream = new AtomicReference<>(NEXT);
            CountDownLatch sameSourceResolveStarted = new CountDownLatch(1), allowSameSourceResolve = new CountDownLatch(1);
            CatalogClient fixtureCatalog = new CatalogClient((url, remainingMs) -> {
                catalogRequests.add(url);
                if (url.contains("/api/details")) return new CatalogClient.Response(200,
                        object("success", true, "id", 42, "type", "tv", "title", "Shared series").toString());
                if (url.contains("/api/subtitles")) return new CatalogClient.Response(200,
                        object("success", true, "subtitles", new JSONArray().put(object("url", "https://media.example/catalog-pt.vtt",
                                "language", "pt-BR", "label", "Português", "format", "vtt"))).toString());
                if (url.contains("/api/stream")) {
                    String source = nextStream.get();
                    if (OTHER.equals(source)) {
                        sameSourceResolveStarted.countDown();
                        if (!allowSameSourceResolve.await(8, TimeUnit.SECONDS)) throw new AssertionError("Same-source Next fixture was not released");
                    }
                    return new CatalogClient.Response(200,
                            object("success", true, "sources", new JSONArray().put(object("url", source, "type", "hls"))).toString());
                }
                throw new AssertionError("Playback hydration requested a media source: " + url);
            });
            java.lang.reflect.Field catalogField = ConnectionService.class.getDeclaredField("catalogClient"); catalogField.setAccessible(true);
            catalogField.set(service, fixtureCatalog);
            int beforeAdoption = fixture("?lumaMetadata=1&position=321").getInt("mutations");
            java.lang.reflect.Method appRequest = MainActivity.class.getDeclaredMethod("appRequest", String.class, String.class, JSONObject.class);
            appRequest.setAccessible(true);
            runner.runOnMainSync(() -> {
                try { appRequest.invoke(activity, "failed-ready-fixture", "app.handoffReady", object("sourceToken", "active-handoff-fixture")); }
                catch (Exception error) { throw new RuntimeException(error); }
            });
            runner.waitForIdleSync();
            java.lang.reflect.Field handoffField = ConnectionService.class.getDeclaredField("handoffInProgress"); handoffField.setAccessible(true);
            require(!handoffField.getBoolean(service), "A rejected handoffReady left passive receiver adoption suppressed");
            require(call("receiver.status").getBoolean("active"), "Continuing receiver playback was lost during reconnect");
            JSONObject adopted = waitCanonicalRemote(FIRST, "framextv:tv:42:1:3", "Shared series · S1 E3", "https://media.example/catalog-pt.vtt");
            JSONObject adoptedMedia = adopted.getJSONObject("media"), adoptedContent = adoptedMedia.getJSONObject("content");
            require(FIRST.equals(adoptedMedia.getString("url")) && "framextv:tv:42:1:3".equals(adoptedContent.getString("key"))
                    && "Shared series · S1 E3".equals(adoptedContent.getString("title")), "Fresh playback did not adopt the exact source and canonical episode identity");
            require(adopted.getJSONArray("tracks").length() == 0 && "https://media.example/catalog-pt.vtt".equals(
                    adopted.getJSONArray("catalog").getJSONObject(0).getString("url")), "Hydrated catalog subtitle did not appear without claiming a receiver track");
            require(catalogRequests.equals(java.util.Arrays.asList(
                    "https://api.framextv.tech/api/details?id=42&type=tv",
                    "https://api.framextv.tech/api/subtitles?type=tv&id=42&season=1&episode=3"))
                    && catalogRequests.stream().noneMatch(url -> url.contains("/api/stream")), "Playback hydration did not stay on fixed detail/subtitle reads");
            java.lang.reflect.Field historyField = ConnectionService.class.getDeclaredField("watchHistory"); historyField.setAccessible(true);
            java.lang.reflect.Field libraryField = ConnectionService.class.getDeclaredField("libraryStore"); libraryField.setAccessible(true);
            WatchHistory history = (WatchHistory) historyField.get(service); LibraryStore library = (LibraryStore) libraryField.get(service);
            JSONObject historyAtAdoption = history.find("framextv:tv:42:1:3"), libraryAtAdoption = library.findProgress(adoptedContent);
            require(historyAtAdoption != null && historyAtAdoption.getDouble("positionSeconds") == 321
                    && libraryAtAdoption != null && libraryAtAdoption.getDouble("positionSeconds") == 321,
                    "Canonical adoption did not persist the current receiver position to history and library");
            fixture("?position=444"); require(call("receiver.status").getBoolean("active"), "Subsequent canonical status update failed");
            JSONObject updatedHistory = history.find("framextv:tv:42:1:3"), updatedLibrary = library.findProgress(adoptedContent);
            require(updatedHistory != null && updatedHistory.getDouble("positionSeconds") == 444
                    && updatedLibrary != null && updatedLibrary.getDouble("positionSeconds") == 444,
                    "Subsequent receiver status did not advance canonical history and library progress");
            require(fixture("").getInt("mutations") == beforeAdoption && beforeAdoption == beforeRestore,
                    "Adopting or updating receiver playback replayed the video or mutated the receiver");

            phase = "library_sync";
            // Pull: the receiver's own progress row (recorded while no phone was on) lands in the Library.
            JSONObject receiverMovie = WatchHistory.contentFromCatalog("movie", "77", null, null, "");
            JSONObject pulledMovie = null;
            long pulledBy = SystemClock.elapsedRealtime() + 6000;
            while (SystemClock.elapsedRealtime() < pulledBy && ((pulledMovie = library.findProgress(receiverMovie)) == null
                    || pulledMovie.optDouble("positionSeconds", -1) != 500)) Thread.sleep(50);
            require(pulledMovie != null && pulledMovie.optDouble("positionSeconds", -1) == 500 && "luma".equals(pulledMovie.optString("lastReceiver")),
                    "The receiver's Library progress was not pulled into this phone's Library");
            // Push: force the next 60 s round now; this phone's newer progress must reach the receiver.
            java.lang.reflect.Field syncField = ConnectionService.class.getDeclaredField("lastLibrarySync"); syncField.setAccessible(true);
            syncField.setLong(service, 0);
            JSONObject pushed = null;
            long pushedBy = SystemClock.elapsedRealtime() + 8000;
            while (SystemClock.elapsedRealtime() < pushedBy) {
                pushed = fixture("").getJSONObject("libraryPushed").optJSONObject("framextv:tv:42:1:3");
                if (pushed != null && pushed.optDouble("positionSeconds", -1) == 444) break;
                Thread.sleep(80);
            }
            require(pushed != null && pushed.optDouble("positionSeconds", -1) == 444 && pushed.optString("origin").startsWith("phone-"),
                    "This phone's newer Library progress was not pushed to the receiver");
            require(fixture("").getInt("mutations") == beforeAdoption, "Library sync changed receiver playback");
            passed("two-way Library sync pulls receiver progress by cursor and pushes this phone's newer progress");

            phase = "canonical_source_transition";
            PendingIntent episodeThreePause = currentAction("pause");
            java.lang.reflect.Field revisionField = ConnectionService.class.getDeclaredField("mediaRevision"); revisionField.setAccessible(true);
            String episodeThreeRevision = (String) revisionField.get(service);
            long episodeThreeUpdatedAt = updatedHistory.getLong("updatedAt"), episodeThreeLibraryUpdatedAt = updatedLibrary.getLong("updatedAt");
            fixture("?source=other&episode=4&position=33");
            require(call("receiver.status").getBoolean("active"), "Receiver next-episode source transition was not observed");
            JSONObject episodeFourRemote = waitCanonicalRemote(OTHER, "framextv:tv:42:1:4", "Shared series · S1 E4", "https://media.example/catalog-pt.vtt");
            JSONObject episodeFourContent = episodeFourRemote.getJSONObject("media").getJSONObject("content");
            String episodeFourRevision = (String) revisionField.get(service);
            require(!episodeThreeRevision.equals(episodeFourRevision), "A different-source canonical episode retained the old control revision");
            JSONObject oldHistoryAfterSourceChange = history.find("framextv:tv:42:1:3"), oldLibraryAfterSourceChange = library.findProgress(adoptedContent);
            JSONObject episodeFourHistory = history.find("framextv:tv:42:1:4"), episodeFourLibrary = library.findProgress(episodeFourContent);
            require(oldHistoryAfterSourceChange.getDouble("positionSeconds") == 444 && oldHistoryAfterSourceChange.getLong("updatedAt") == episodeThreeUpdatedAt
                    && oldLibraryAfterSourceChange.getDouble("positionSeconds") == 444 && oldLibraryAfterSourceChange.getLong("updatedAt") == episodeThreeLibraryUpdatedAt,
                    "The next episode's first status polluted the previous episode progress");
            require(episodeFourHistory != null && episodeFourHistory.getDouble("positionSeconds") == 33
                    && episodeFourLibrary != null && episodeFourLibrary.getDouble("positionSeconds") == 33,
                    "Different-source canonical playback did not persist the new episode progress");
            int mutationsBeforeStaleControl = fixture("").getInt("mutations"); episodeThreePause.send(); Thread.sleep(250);
            require(fixture("").getInt("mutations") == mutationsBeforeStaleControl && fixture("").getBoolean("playing"),
                    "An old episode notification control targeted the new canonical episode");
            passed("rejected handoff completion unblocks canonical adoption, and different-source transitions retarget controls and progress without replay");

            phase = "canonical_same_source_transition";
            PendingIntent episodeFourPause = currentAction("pause");
            JSONObject episodeFourBeforeKeyChange = history.find("framextv:tv:42:1:4"), episodeFourLibraryBeforeKeyChange = library.findProgress(episodeFourContent);
            long episodeFourUpdatedAt = episodeFourBeforeKeyChange.getLong("updatedAt"), episodeFourLibraryUpdatedAt = episodeFourLibraryBeforeKeyChange.getLong("updatedAt");
            int mutationsBeforeUntaggedWindow = fixture("?lumaMetadata=0&position=77").getInt("mutations");
            require(call("receiver.status").getBoolean("active"), "Same-source untagged transition window was not observed");
            JSONObject historyDuringUntagged = history.find("framextv:tv:42:1:4"), libraryDuringUntagged = library.findProgress(episodeFourContent);
            require(historyDuringUntagged.getDouble("positionSeconds") == 33 && historyDuringUntagged.getLong("updatedAt") == episodeFourUpdatedAt
                    && libraryDuringUntagged.getDouble("positionSeconds") == 33 && libraryDuringUntagged.getLong("updatedAt") == episodeFourLibraryUpdatedAt,
                    "A contentless same-source transition window was attributed to the prior episode");
            waitCard(42, false); episodeFourPause.send(); Thread.sleep(250);
            require(fixture("").getInt("mutations") == mutationsBeforeUntaggedWindow && fixture("").getBoolean("playing"),
                    "A canonical control remained verified during a same-source contentless transition window");
            fixture("?lumaMetadata=1&episode=5&position=77");
            require(call("receiver.status").getBoolean("active"), "Same-source canonical episode transition was not observed");
            JSONObject episodeFiveRemote = waitCanonicalRemote(OTHER, "framextv:tv:42:1:5", "Shared series · S1 E5", "https://media.example/catalog-pt.vtt");
            JSONObject episodeFiveContent = episodeFiveRemote.getJSONObject("media").getJSONObject("content");
            String episodeFiveRevision = (String) revisionField.get(service);
            require(!episodeFourRevision.equals(episodeFiveRevision), "A same-source canonical item change retained the old control revision");
            JSONObject oldHistoryAfterKeyChange = history.find("framextv:tv:42:1:4"), oldLibraryAfterKeyChange = library.findProgress(episodeFourContent);
            JSONObject episodeFiveHistory = history.find("framextv:tv:42:1:5"), episodeFiveLibrary = library.findProgress(episodeFiveContent);
            require(oldHistoryAfterKeyChange.getDouble("positionSeconds") == 33 && oldHistoryAfterKeyChange.getLong("updatedAt") == episodeFourUpdatedAt
                    && oldLibraryAfterKeyChange.getDouble("positionSeconds") == 33 && oldLibraryAfterKeyChange.getLong("updatedAt") == episodeFourLibraryUpdatedAt,
                    "A same-source canonical key change wrote the new timestamp against the old episode");
            require(episodeFiveHistory != null && episodeFiveHistory.getDouble("positionSeconds") == 77
                    && episodeFiveLibrary != null && episodeFiveLibrary.getDouble("positionSeconds") == 77,
                    "A same-source canonical key change did not rebind progress to the new episode");
            java.lang.reflect.Method retainNext = ConnectionService.class.getDeclaredMethod("retainNextEpisode", String.class, String.class,
                    JSONObject.class, String.class, JSONObject.class); retainNext.setAccessible(true);
            JSONObject staleNext = object("kind", "tv", "id", "42", "season", 1, "episode", 99, "title", "Stale next episode");
            runner.runOnMainSync(() -> {
                try { retainNext.invoke(service, "luma", OTHER, episodeFourContent, episodeFourRevision, staleNext); }
                catch (Exception error) { throw new RuntimeException(error); }
            });
            JSONObject retained = service.snapshot().getJSONObject("remote").optJSONObject("nextEpisode");
            require(retained == null || retained.optInt("episode") != 99, "A late next-episode lookup attached to a newer same-source episode");
            require(catalogRequests.equals(java.util.Arrays.asList(
                    "https://api.framextv.tech/api/details?id=42&type=tv",
                    "https://api.framextv.tech/api/subtitles?type=tv&id=42&season=1&episode=3",
                    "https://api.framextv.tech/api/details?id=42&type=tv",
                    "https://api.framextv.tech/api/subtitles?type=tv&id=42&season=1&episode=4",
                    "https://api.framextv.tech/api/details?id=42&type=tv",
                    "https://api.framextv.tech/api/subtitles?type=tv&id=42&season=1&episode=5")),
                    "Canonical transitions did not use one fixed metadata/subtitle hydration per episode");
            JSONObject validNext = object("kind", "tv", "id", "42", "season", 1, "episode", 6, "title", "Shared series · S1 E6");
            runner.runOnMainSync(() -> {
                try { retainNext.invoke(service, "luma", OTHER, episodeFiveContent, episodeFiveRevision, validNext); }
                catch (Exception error) { throw new RuntimeException(error); }
            });
            java.lang.reflect.Field systemNextField = ConnectionService.class.getDeclaredField("systemNextScope");
            systemNextField.setAccessible(true);
            java.lang.reflect.Method captureSystemNext = ConnectionService.class.getDeclaredMethod("captureSystemNext");
            captureSystemNext.setAccessible(true);
            AtomicReference<Object> abandonedScopeRef = new AtomicReference<>();
            runner.runOnMainSync(() -> {
                try { abandonedScopeRef.set(captureSystemNext.invoke(service)); }
                catch (Exception error) { throw new RuntimeException(error); }
            });
            Object abandonedScope = abandonedScopeRef.get();
            require(abandonedScope != null, "Wake-lock fixture could not capture a system Next scope");
            java.lang.reflect.Field nextWakeField = abandonedScope.getClass().getDeclaredField("wakeLock"); nextWakeField.setAccessible(true);
            PowerManager.WakeLock abandonedWake = (PowerManager.WakeLock) nextWakeField.get(abandonedScope);
            require(abandonedWake.isHeld(), "Captured system Next did not hold its bounded wake lock");
            runner.runOnMainSync(() -> {
                try { systemNextField.set(service, null); }
                catch (Exception error) { throw new RuntimeException(error); }
            });
            java.lang.reflect.Method failSystemNext = ConnectionService.class.getDeclaredMethod("failSystemNext", abandonedScope.getClass(), String.class);
            failSystemNext.setAccessible(true);
            runner.runOnMainSync(() -> {
                try { failSystemNext.invoke(service, abandonedScope, "stale fixture"); }
                catch (Exception error) { throw new RuntimeException(error); }
            });
            require(!abandonedWake.isHeld(), "A late stale system Next failure retained its wake lock");

            String activeRevision = (String) revisionField.get(service);
            AtomicReference<Object> replacedScopeRef = new AtomicReference<>(), replacementScopeRef = new AtomicReference<>();
            runner.runOnMainSync(() -> {
                try {
                    replacedScopeRef.set(captureSystemNext.invoke(service));
                    revisionField.set(service, activeRevision + "-replacement");
                    replacementScopeRef.set(captureSystemNext.invoke(service));
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            Object replacedScope = replacedScopeRef.get(), replacementScope = replacementScopeRef.get();
            require(replacedScope != null && replacementScope != null,
                    "Wake-lock fixture could not replace a stale system Next scope");
            PowerManager.WakeLock replacedWake = (PowerManager.WakeLock) nextWakeField.get(replacedScope);
            PowerManager.WakeLock replacementWake = (PowerManager.WakeLock) nextWakeField.get(replacementScope);
            require(!replacedWake.isHeld() && replacementWake.isHeld(),
                    "Replacing a stale system Next scope did not rotate its bounded wake lock");
            java.lang.reflect.Method cancelSystemNext = ConnectionService.class.getDeclaredMethod("cancelSystemNext");
            cancelSystemNext.setAccessible(true);
            java.lang.reflect.Method publishState = ConnectionService.class.getDeclaredMethod("publish"); publishState.setAccessible(true);
            runner.runOnMainSync(() -> {
                // A 2 s poll may have published while the fixture's reflective scope was set;
                // republish the restored state, as production paths do after changing scope.
                try { cancelSystemNext.invoke(service); revisionField.set(service, activeRevision); publishState.invoke(service); }
                catch (Exception error) { throw new RuntimeException(error); }
            });
            require(!replacementWake.isHeld(), "Cleaning up the replacement system Next scope retained its wake lock");
            PendingIntent nextEpisodeAction = notificationAction(currentCard(42, "next episode"), "next episode");
            require(nextEpisodeAction.isActivity(), "Notification Next used a blocked service-to-Activity trampoline");
            MediaController nextController = new MediaController(context, service.getMediaSessionToken());
            PlaybackState nextState = nextController.getPlaybackState();
            require(nextState != null && (nextState.getActions() & PlaybackState.ACTION_SKIP_TO_NEXT) != 0,
                    "MediaSession did not advertise its verified next episode");
            finishActivity();
            sleepEmulator();
            Instrumentation.ActivityMonitor nextMonitor = runner.addMonitor(MainActivity.class.getName(), null, false);

            int beforeStaleNext = fixture("?episode=7&position=81&hold=1").getInt("mutations");
            nextController.getTransportControls().skipToNext();
            long staleReadBy = SystemClock.elapsedRealtime() + 4000;
            while (fixture("").getInt("held") == 0 && SystemClock.elapsedRealtime() < staleReadBy) Thread.sleep(20);
            require(fixture("").getInt("held") > 0, "Fixture did not hold system Next's fresh playback read");
            Object staleScope = systemNextField.get(service);
            require(staleScope != null, "MediaSession Next did not capture its source/revision scope while screen-off");
            PowerManager.WakeLock staleNextWake = (PowerManager.WakeLock) nextWakeField.get(staleScope);
            require(staleNextWake.isHeld(), "System Next did not retain its bounded wake lock across the asynchronous flow");
            fixture("?hold=0");
            long staleRejectedBy = SystemClock.elapsedRealtime() + 5000;
            JSONObject stalePlayback = null;
            while (SystemClock.elapsedRealtime() < staleRejectedBy) {
                stalePlayback = service.snapshot().optJSONObject("playback");
                JSONObject content = stalePlayback == null ? null : stalePlayback.optJSONObject("content");
                if (systemNextField.get(service) == null && content != null
                        && "framextv:tv:42:1:7".equals(content.optString("key"))) break;
                Thread.sleep(40);
            }
            JSONObject staleContent = stalePlayback == null ? null : stalePlayback.optJSONObject("content");
            require(staleContent != null && "framextv:tv:42:1:7".equals(staleContent.optString("key")),
                    "System Next did not retain the fresh canonical identity that invalidated its stale scope");
            require(fixture("").getInt("mutations") == beforeStaleNext,
                    "MediaSession Next wrote after its fresh playback read found a stale canonical identity");
            require(systemNextField.get(service) == null && !staleNextWake.isHeld(),
                    "Rejected stale system Next retained its scope or wake lock");
            require(nextMonitor.getHits() == 0, "MediaSession Next tried to launch an Activity from the background service");
            require(!context.getSystemService(PowerManager.class).isInteractive()
                    && context.getSystemService(KeyguardManager.class).isKeyguardLocked(),
                    "MediaSession Next unexpectedly woke or unlocked the emulator");

            fixture("?episode=5&position=77");
            require(call("receiver.status").getBoolean("active"), "Could not restore the verified episode before testing system Next");
            JSONObject restoredFive = waitCanonicalRemote(OTHER, "framextv:tv:42:1:5", "Shared series · S1 E5", "https://media.example/catalog-pt.vtt");
            JSONObject restoredFiveContent = restoredFive.getJSONObject("media").getJSONObject("content");
            String restoredFiveRevision = (String) revisionField.get(service);
            runner.runOnMainSync(() -> {
                try { retainNext.invoke(service, "luma", OTHER, restoredFiveContent, restoredFiveRevision, validNext); }
                catch (Exception error) { throw new RuntimeException(error); }
            });
            PlaybackState restoredNextState = nextController.getPlaybackState();
            require(restoredNextState != null && (restoredNextState.getActions() & PlaybackState.ACTION_SKIP_TO_NEXT) != 0,
                    "MediaSession did not restore Next after adopting the original episode again");

            // The fixture switches episodes just before evaluating the forwarded
            // expectedCurrent* guard, so the receiver must reject the atomic open.
            int beforeProbeRace = fixture("?switchBeforeOpen=7").getInt("mutations");
            nextController.getTransportControls().skipToNext();
            long probeRejectedBy = SystemClock.elapsedRealtime() + 8000;
            JSONObject probeState = null;
            while (SystemClock.elapsedRealtime() < probeRejectedBy) {
                probeState = fixture("");
                if (probeState.optInt("switchBeforeOpen") == 0 && systemNextField.get(service) == null) break;
                Thread.sleep(40);
            }
            require(probeState != null && probeState.optInt("switchBeforeOpen") == 0 && systemNextField.get(service) == null,
                    "System Next did not start and finish its guarded open against the switched episode");
            JSONObject probeObserved = call("receiver.status"), probeContent = probeObserved.optJSONObject("content");
            require(probeContent != null && "framextv:tv:42:1:7".equals(probeContent.optString("key")),
                    "Receiver fixture did not expose the canonical switch made before the guarded open");
            require(fixture("").getInt("mutations") == beforeProbeRace,
                    "MediaSession Next overwrote a canonical switch the receiver rejected atomically");
            require(nextMonitor.getHits() == 0 && !context.getSystemService(PowerManager.class).isInteractive()
                    && context.getSystemService(KeyguardManager.class).isKeyguardLocked(),
                    "A rejected guarded-open race launched the Activity, woke, or unlocked the emulator");

            fixture("?episode=5&position=77");
            require(call("receiver.status").getBoolean("active"), "Could not restore the verified episode after the guarded-open race");
            JSONObject finalFive = waitCanonicalRemote(OTHER, "framextv:tv:42:1:5", "Shared series · S1 E5", "https://media.example/catalog-pt.vtt");
            JSONObject finalFiveContent = finalFive.getJSONObject("media").getJSONObject("content");
            String finalFiveRevision = (String) revisionField.get(service);
            runner.runOnMainSync(() -> {
                try { retainNext.invoke(service, "luma", OTHER, finalFiveContent, finalFiveRevision, validNext); }
                catch (Exception error) { throw new RuntimeException(error); }
            });
            PlaybackState finalNextState = nextController.getPlaybackState();
            require(finalNextState != null && (finalNextState.getActions() & PlaybackState.ACTION_SKIP_TO_NEXT) != 0,
                    "MediaSession did not restore Next after rejecting the guarded-open race");
            JSONObject previousEpisodeSixHistory = history.find("framextv:tv:42:1:6");
            int beforeSystemNext = fixture("").getInt("contentOpens");
            nextController.getTransportControls().skipToNext();
            long openedBy = SystemClock.elapsedRealtime() + 25000;
            JSONObject opened = null;
            while (SystemClock.elapsedRealtime() < openedBy) {
                opened = fixture("");
                if (NEXT.equals(opened.optString("source")) && opened.optInt("lumaEpisode") == 6) break;
                Thread.sleep(40);
            }
            require(opened != null && NEXT.equals(opened.optString("source")) && opened.optInt("lumaEpisode") == 6,
                    "MediaSession Next did not open the retained canonical episode on the receiver");
            JSONObject episodeSixRemote = waitCanonicalRemote(NEXT, "framextv:tv:42:1:6", "Shared series · S1 E6", "https://media.example/catalog-pt.vtt");
            JSONObject episodeSixContent = episodeSixRemote.getJSONObject("media").getJSONObject("content");
            require(fixture("").getInt("contentOpens") == beforeSystemNext + 1,
                    "MediaSession Next did not perform exactly one guarded receiver open");
            long differentSourceBoundBy = SystemClock.elapsedRealtime() + 5000;
            JSONObject differentSourceHistory = null, differentSourceLibrary = null, differentSourceReceiver = null;
            while (SystemClock.elapsedRealtime() < differentSourceBoundBy) {
                differentSourceReceiver = fixture("");
                differentSourceHistory = history.find("framextv:tv:42:1:6");
                differentSourceLibrary = library.findProgress(episodeSixContent);
                double receiverPosition = Math.min(1355, differentSourceReceiver.optDouble("position", -1));
                if (systemNextField.get(service) == null && differentSourceHistory != null && differentSourceLibrary != null
                        && differentSourceHistory.optDouble("positionSeconds", -1) == receiverPosition
                        && differentSourceLibrary.optDouble("positionSeconds", -1) == receiverPosition) break;
                Thread.sleep(40);
            }
            double differentSourcePosition = Math.min(1355, differentSourceReceiver == null ? -1 : differentSourceReceiver.optDouble("position", -1));
            require(differentSourceHistory != null && differentSourceLibrary != null
                    && differentSourceHistory.optDouble("positionSeconds", -1) == differentSourcePosition
                    && differentSourceLibrary.optDouble("positionSeconds", -1) == differentSourcePosition
                    && (previousEpisodeSixHistory == null || differentSourceHistory.optLong("lastStartedAt") > previousEpisodeSixHistory.optLong("lastStartedAt")),
                    "A different-source service Next did not bind its accepted canonical episode at the receiver's observed position");

            fixture("?source=other&episode=5&position=77");
            require(call("receiver.status").getBoolean("active"), "Could not restore episode five before the same-source resume failure");
            JSONObject sameSourceFive = waitCanonicalRemote(OTHER, "framextv:tv:42:1:5", "Shared series · S1 E5", "https://media.example/catalog-pt.vtt");
            JSONObject sameSourceFiveContent = sameSourceFive.getJSONObject("media").getJSONObject("content");
            String sameSourceFiveRevision = (String) revisionField.get(service);

            JSONObject seededNextProgress = new JSONObject(episodeSixContent.toString());
            long seededUpdatedAt = Math.max(System.currentTimeMillis(), differentSourceLibrary.optLong("updatedAt") + 1);
            seededNextProgress.put("positionSeconds", 120).put("durationSeconds", 1355).put("resumeSeconds", 120)
                    .put("completed", false).put("lastStartedAt", differentSourceLibrary.optLong("lastStartedAt"))
                    .put("updatedAt", seededUpdatedAt).put("lastReceiver", "luma");
            library.record(seededNextProgress);
            JSONObject seededResume = library.findProgress(episodeSixContent);
            require(seededResume != null && seededResume.optDouble("resumeSeconds", -1) == 120,
                    "Same-source Next fixture did not retain a nonzero saved resume");
            nextStream.set(OTHER);
            runner.runOnMainSync(() -> {
                try { retainNext.invoke(service, "luma", OTHER, sameSourceFiveContent, sameSourceFiveRevision, validNext); }
                catch (Exception error) { throw new RuntimeException(error); }
            });
            require((nextController.getPlaybackState().getActions() & PlaybackState.ACTION_SKIP_TO_NEXT) != 0,
                    "MediaSession did not advertise the same-source next episode");
            JSONObject sameSourceSetup = fixture("?nextSource=other&failNextSeek=1");
            int beforeSameSourceOpen = sameSourceSetup.getInt("contentOpens"), beforeFailedSeek = sameSourceSetup.getInt("seekFailures");
            nextController.getTransportControls().skipToNext();
            boolean sameSourceResolving = sameSourceResolveStarted.await(8, TimeUnit.SECONDS);
            JSONObject episodeFiveBeforeNext = history.find("framextv:tv:42:1:5");
            JSONObject episodeFiveLibraryBeforeNext = library.findProgress(sameSourceFiveContent);
            allowSameSourceResolve.countDown();
            require(sameSourceResolving && episodeFiveBeforeNext != null && episodeFiveLibraryBeforeNext != null,
                    "Same-source Next did not reach its held resolution after the fresh episode-five observation");

            long sameSourceFinishedBy = SystemClock.elapsedRealtime() + 12000;
            JSONObject sameSourceReceiver = null, sameSourceHistory = null, sameSourceLibrary = null;
            while (SystemClock.elapsedRealtime() < sameSourceFinishedBy) {
                sameSourceReceiver = fixture("");
                sameSourceHistory = history.find("framextv:tv:42:1:6");
                sameSourceLibrary = library.findProgress(episodeSixContent);
                if (systemNextField.get(service) == null && sameSourceReceiver.optInt("contentOpens") == beforeSameSourceOpen + 1
                        && sameSourceReceiver.optInt("seekFailures") == beforeFailedSeek + 1
                        && OTHER.equals(sameSourceReceiver.optString("source")) && sameSourceReceiver.optInt("lumaEpisode") == 6
                        && sameSourceHistory != null && sameSourceLibrary != null
                        && sameSourceHistory.optDouble("positionSeconds", -1) == 20
                        && sameSourceLibrary.optDouble("positionSeconds", -1) == 20) break;
                Thread.sleep(40);
            }
            waitCanonicalRemote(OTHER, "framextv:tv:42:1:6", "Shared series · S1 E6", "https://media.example/catalog-pt.vtt");
            JSONObject episodeFiveAfterNext = history.find("framextv:tv:42:1:5");
            JSONObject episodeFiveLibraryAfterNext = library.findProgress(sameSourceFiveContent);
            require(sameSourceReceiver != null && sameSourceReceiver.optInt("contentOpens") == beforeSameSourceOpen + 1
                    && sameSourceReceiver.optInt("seekFailures") == beforeFailedSeek + 1
                    && !sameSourceReceiver.optBoolean("failNextSeek") && OTHER.equals(sameSourceReceiver.optString("source"))
                    && sameSourceReceiver.optInt("lumaEpisode") == 6 && sameSourceReceiver.optDouble("position", -1) == 20,
                    "Same-source service Next did not keep accepted playback at the receiver's actual position after its resume failed");
            require(sameSourceHistory != null && sameSourceLibrary != null
                    && sameSourceHistory.optDouble("positionSeconds", -1) == 20
                    && sameSourceLibrary.optDouble("positionSeconds", -1) == 20,
                    "An unconfirmed resume did not bind the accepted same-source episode to the receiver's actual position");
            require(episodeFiveAfterNext != null && episodeFiveLibraryAfterNext != null
                    && episodeFiveAfterNext.optDouble("positionSeconds", -1) == episodeFiveBeforeNext.optDouble("positionSeconds", -1)
                    && episodeFiveAfterNext.optLong("updatedAt") == episodeFiveBeforeNext.optLong("updatedAt")
                    && episodeFiveLibraryAfterNext.optDouble("positionSeconds", -1) == episodeFiveLibraryBeforeNext.optDouble("positionSeconds", -1)
                    && episodeFiveLibraryAfterNext.optLong("updatedAt") == episodeFiveLibraryBeforeNext.optLong("updatedAt"),
                    "A same-source Next with an unconfirmed resume attributed episode six to episode five");
            runner.waitForIdleSync(); runner.removeMonitor(nextMonitor);
            require(nextMonitor.getHits() == 0 && activity == null,
                    "MediaSession Next launched an Activity instead of using the guarded service path");
            require(!context.getSystemService(PowerManager.class).isInteractive()
                    && context.getSystemService(KeyguardManager.class).isKeyguardLocked(),
                    "A successful MediaSession Next woke or unlocked the emulator");
            passed("stale races reject system Next, while locked-screen different- and same-source episodes bind actual receiver progress without launching the Activity");
            ordinaryCard(currentCard(41, "pause")); volumeLayout(currentCard(41, "pause"), "Pause"); currentCard(42, "pause");
            token = service.getMediaSessionToken();
            ConnectionService restoredService = service; unbind(); bind();
            require(service == restoredService, "Restored connection did not remain owned by the started service");
            passed("Activity-free service rebinding preserves the session, canonical playback, subtitles and progress without replay");

            MediaController controller = new MediaController(context, token);
            PlaybackState rewindState = controller.getPlaybackState();
            require(rewindState != null && (rewindState.getActions() & PlaybackState.ACTION_REWIND) != 0, "MediaSession Back 30 action missing");
            PlaybackState.CustomAction backThirty = customAction(controller, "back 30");
            phase = "custom_back_30";
            fixture("?position=75"); call("receiver.status"); fixture("?hold=1");
            controller.getTransportControls().sendCustomAction(backThirty.getAction(), backThirty.getExtras());
            long rewindHeldBy = SystemClock.elapsedRealtime() + 3000;
            while (fixture("").getInt("held") == 0 && SystemClock.elapsedRealtime() < rewindHeldBy) Thread.sleep(20);
            require(fixture("").getInt("held") > 0, "Fixture did not hold the locked-screen rewind's fresh observation");
            waitMediaWake(true); fixture("?hold=0"); waitReceiverPosition(45); waitMediaWake(false);
            phase = "ordinary_back_30";
            fixture("?position=20"); call("receiver.status"); currentAction("back 30").send(); waitReceiverPosition(0); waitMediaWake(false);
            require(!context.getSystemService(PowerManager.class).isInteractive() && context.getSystemService(KeyguardManager.class).isKeyguardLocked(), "Back 30 opened or unlocked the phone");
            fixture("?position=20"); call("receiver.status");
            passed("notification and custom MediaSession Back 30 use fresh source-bound playback while standard rewind remains advertised");
            phase = "ordinary_pause";
            fixture("?hold=1");
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
            PendingCall failedRead = submit("receiver.status");
            require(failedRead.ready.await(8, TimeUnit.SECONDS) && "RECEIVER_RESPONSE".equals(failedRead.failure.get()), "Fixture did not reject the transient status read");
            require(service.snapshot().getJSONObject("playback").getBoolean("stale"), "A failed observation was not marked stale");
            PendingIntent retryPlay = currentAction("play"); retryPlay.send(); waitMediaWake(true);
            long failureBy = SystemClock.elapsedRealtime() + 3000;
            while (fixture("").getInt("failedStatusReads") < beforeOutage.getInt("failedStatusReads") + 3 && SystemClock.elapsedRealtime() < failureBy) Thread.sleep(30);
            waitMediaWake(false); currentCard(42, "play");
            require(controller.getPlaybackState().getState() == PlaybackState.STATE_PAUSED
                    && fixture("").getInt("mutations") == beforeOutage.getInt("mutations"), "An unreachable receiver erased the media session or sent an unverified write");
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
            require(actions.size() == 5 && "Back 30 seconds".contentEquals(actions.get(0).getName())
                    && "Volume −".contentEquals(actions.get(1).getName()) && "Volume +".contentEquals(actions.get(2).getName())
                    && "Stop video".contentEquals(actions.get(3).getName()) && "Disconnect".contentEquals(actions.get(4).getName()),
                    "MediaSession priority/custom action order is incorrect");
            PlaybackState.CustomAction volumeDown = customAction(controller, "volume −"), volumeUp = customAction(controller, "volume +");
            controller.getTransportControls().sendCustomAction(volumeDown.getAction(), volumeDown.getExtras()); waitVolume(6);
            controller.getTransportControls().sendCustomAction(volumeUp.getAction(), volumeUp.getExtras()); waitVolume(7);
            phase = "custom_stop";
            PlaybackState.CustomAction stop = customAction(controller, "stop");
            controller.getTransportControls().sendCustomAction(stop.getAction(), stop.getExtras());
            long stopBy = SystemClock.elapsedRealtime() + 4000;
            while (fixture("").getInt("stops") == 0 && SystemClock.elapsedRealtime() < stopBy) Thread.sleep(40);
            require(fixture("").getInt("stops") == 1 && !call("receiver.status").getBoolean("active"), "The current custom Stop action did not stop the selected video");
            ordinaryCard(currentCard(41, "disconnect")); waitCard(42, false);
            notificationAction(waitNotification(true), "disconnect");
            phase = "restore_after_stop";
            fixture("?lumaMetadata=0");
            call("receiver.open", "media", object("url", FIRST, "contentType", "application/vnd.apple.mpegurl"), "expectedSourceUrl", FIRST);
            call("receiver.status"); remember(); runner.waitForIdleSync(); currentCard(41, "pause"); currentCard(42, "pause");
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
            PendingIntent staleBack = currentAction("back 30");
            PendingIntent staleVolumeDown = currentAction("volume −"), staleVolumeUp = currentAction("volume +");
            PlaybackState.CustomAction staleBackCustom = customAction(controller, "back 30");
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
            controller.getTransportControls().pause(); controller.adjustVolume(AudioManager.ADJUST_RAISE, 0); stalePause.send(); staleBack.send();
            staleVolumeDown.send(); staleVolumeUp.send();
            controller.getTransportControls().sendCustomAction(staleVolumeDownCustom.getAction(), staleVolumeDownCustom.getExtras());
            controller.getTransportControls().sendCustomAction(staleVolumeUpCustom.getAction(), staleVolumeUpCustom.getExtras());
            controller.getTransportControls().sendCustomAction(staleBackCustom.getAction(), staleBackCustom.getExtras());
            controller.getTransportControls().sendCustomAction(staleStop.getAction(), staleStop.getExtras()); Thread.sleep(2700); call("receiver.status");
            JSONObject untouched = fixture("");
            require(untouched.getInt("mutations") == before && untouched.getInt("volumeKeys") == 0 && untouched.getInt("volume") == 8 && untouched.getBoolean("playing") && OTHER.equals(untouched.getString("source")), "A stale media control changed another source");
            phase = "stale_same_source";
            fixture("?source=first"); call("receiver.status"); remember(); runner.waitForIdleSync();
            int renewed = fixture("").getInt("mutations");
            staleVolumeDown.send(); staleVolumeUp.send();
            controller.getTransportControls().sendCustomAction(staleVolumeDownCustom.getAction(), staleVolumeDownCustom.getExtras());
            controller.getTransportControls().sendCustomAction(staleVolumeUpCustom.getAction(), staleVolumeUpCustom.getExtras());
            controller.getTransportControls().sendCustomAction(staleBackCustom.getAction(), staleBackCustom.getExtras());
            stalePause.send(); staleBack.send(); controller.getTransportControls().sendCustomAction(staleStop.getAction(), staleStop.getExtras()); Thread.sleep(250); call("receiver.status");
            require(fixture("").getInt("mutations") == renewed && fixture("").getInt("volumeKeys") == 0 && fixture("").getInt("volume") == 8, "A stale notification retargeted a renewed handoff of the same source");
            passed("MediaSession controls work in background and stale notification/session controls cannot alter another source");

            phase = "independent_receiver_http";
            int beforeDisconnectedTransition = fixture("?source=first&lumaMetadata=1&episode=6&position=90").getInt("mutations");
            call("receiver.status");
            waitCanonicalRemote(FIRST, "framextv:tv:42:1:6", "Shared series · S1 E6", "https://media.example/catalog-pt.vtt");
            fixture("?dropTcp=1"); PendingCall hardwareLost = submit("luma.status");
            require(hardwareLost.ready.await(8, TimeUnit.SECONDS) && hardwareLost.failure.get() != null,
                    "Fixture did not disconnect the projector socket");
            JSONObject receiverOnly = service.snapshot().getJSONObject("connection");
            require(receiverOnly.getBoolean("connected") && receiverOnly.getBoolean("receiverConnected") && !receiverOnly.getBoolean("nativeConnected"),
                    "Losing native navigation also disconnected the independent receiver");
            fixture("?source=other&lumaMetadata=0&position=11");
            long contentlessBy = SystemClock.elapsedRealtime() + 5000;
            while (SystemClock.elapsedRealtime() < contentlessBy) {
                JSONObject observed = service.snapshot().optJSONObject("playback");
                if (observed != null && OTHER.equals(observed.optString("sourceUrl")) && observed.optJSONObject("content") == null) break;
                Thread.sleep(40);
            }
            JSONObject contentless = service.snapshot().optJSONObject("playback");
            require(contentless != null && OTHER.equals(contentless.optString("sourceUrl")) && contentless.optJSONObject("content") == null,
                    "Disconnected polling did not observe the new source's metadata startup window");
            require("framextv:tv:42:1:6".equals(service.snapshot().getJSONObject("remote").getJSONObject("media")
                    .getJSONObject("content").getString("key")), "Contentless transition replaced the previous canonical item");
            fixture("?lumaMetadata=1&episode=7&position=11");
            waitCanonicalRemote(OTHER, "framextv:tv:42:1:7", "Shared series · S1 E7", "https://media.example/catalog-pt.vtt");
            require(fixture("").getInt("mutations") == beforeDisconnectedTransition,
                    "Disconnected canonical transition replayed or mutated the receiver");
            fixture("?source=first&episode=8&position=12");
            waitCanonicalRemote(FIRST, "framextv:tv:42:1:8", "Shared series · S1 E8", "https://media.example/catalog-pt.vtt");
            Notification httpOnly = currentCard(41, "pause");
            long hardwareCardBy = SystemClock.elapsedRealtime() + 3000;
            while (httpOnly.actions.length != 2 && SystemClock.elapsedRealtime() < hardwareCardBy) { Thread.sleep(50); httpOnly = currentCard(41, "pause"); }
            require(httpOnly.actions.length == 2 && "Back 30 seconds".contentEquals(httpOnly.actions[0].title)
                    && "Pause".contentEquals(httpOnly.actions[1].title), "Disconnected projector volume remained available or receiver playback controls disappeared");
            notificationAction(httpOnly, "pause").send(); waitReceiverPlaying(false); waitMediaWake(false); waitPlaying(false);
            controller.getTransportControls().play(); waitReceiverPlaying(true); waitMediaWake(false); waitPlaying(true); currentCard(42, "pause");
            require(context.getSystemService(KeyguardManager.class).isKeyguardLocked(), "Independent HTTP controls unlocked the device");
            passed("disconnected polling survives a contentless source transition, then locked-screen receiver controls remain available without hardware actions");

            phase = "disconnect";
            int stops = fixture("").getInt("stops");
            PlaybackState.CustomAction disconnect = customAction(controller, "disconnect");
            fixture("?hold=1"); PendingCall held = submit("receiver.status");
            long heldBy = SystemClock.elapsedRealtime() + 3000;
            while (fixture("").getInt("held") == 0 && SystemClock.elapsedRealtime() < heldBy) Thread.sleep(40);
            require(fixture("").getInt("held") > 0, "Fixture did not hold a native HTTP read");
            PendingCall queued = submit("receiver.pause", "expectedSourceUrl", FIRST);
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
            require(call("receiver.status").getBoolean("active"), "Power fixture did not retain independent playback");
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
            require(savedAfterPower.getBoolean("hasReceiver") && savedAfterPower.getBoolean("hasPairing")
                    && args.getString("targetHost").equals(settings.readReceiver().getString("host"))
                    && TOKEN.equals(settings.readPairing().getString("token")), "Power-off erased or changed the saved setup");
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

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
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Two transport fixtures in the isolated emulator application. No physical receivers. */
public final class MultiReceiverInstrumentation {
    private static final String PACKAGE = "dev.framextv.lumaremote.receiverfixture";
    private static final String CLIENT = "multi_receiver_fixture_1234567890";
    private static final String LUMA = "https://media.example/luma-first.m3u8";
    private static final String FIRST = "https://media.example/tv-first.m3u8", SECOND = "https://media.example/tv-second.m3u8";
    private static final String THIRD = "https://media.example/tv-third.m3u8", FOURTH = "https://media.example/tv-fourth.m3u8";
    private final Instrumentation runner;
    private final Bundle arguments;
    private final Context context;
    private final List<String> received = new ArrayList<>();
    private MainActivity activity;
    private ConnectionService service;
    private ServiceConnection binding;
    private String base, session, phase = "setup", tvSource = FIRST;
    private boolean tvPaused;
    private double tvPosition = 15;
    private boolean fixtureAuthorized;
    private long after;
    private int checks;
    private static final class PendingCall {
        final CountDownLatch ready = new CountDownLatch(1);
        final AtomicReference<JSONObject> result = new AtomicReference<>();
        final AtomicReference<String> error = new AtomicReference<>();
    }
    private MultiReceiverInstrumentation(Instrumentation runner, Bundle arguments) {
        this.runner = runner; this.arguments = arguments; context = runner.getTargetContext();
    }
    public static void run(Instrumentation runner, Bundle arguments) { new MultiReceiverInstrumentation(runner, arguments).execute(); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static JSONObject object(Object... fields) throws Exception {
        JSONObject result = new JSONObject(); for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]); return result;
    }
    private void passed(String name) { Bundle progress = new Bundle(); progress.putString("test", name); progress.putInt("current", ++checks); runner.sendStatus(0, progress); }
    private PendingCall submit(String method, Object... fields) throws Exception {
        PendingCall call = new PendingCall();
        service.request(method, object(fields), new ConnectionService.Callback() {
            @Override public void success(JSONObject value) { call.result.set(value); call.ready.countDown(); }
            @Override public void failure(String code, String message) { call.error.set(code); call.ready.countDown(); }
        });
        return call;
    }
    private JSONObject await(PendingCall call, long seconds) throws Exception {
        require(call.ready.await(seconds, TimeUnit.SECONDS), "Service request timed out");
        require(call.error.get() == null, "Service request failed: " + call.error.get()); return call.result.get();
    }
    private JSONObject call(String method, Object... fields) throws Exception { return await(submit(method, fields), 8); }
    private JSONObject request(String address, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection(Proxy.NO_PROXY);
        try {
            connection.setConnectTimeout(2000); connection.setReadTimeout(2000); connection.setInstanceFollowRedirects(false);
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8); connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json"); connection.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            require(connection.getResponseCode() == 200, "Fixture HTTP rejected request");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[2048]; int count;
                while ((count = input.read(buffer)) >= 0) { require(output.size() + count <= 65536, "Fixture response exceeded bound"); output.write(buffer, 0, count); }
                return new JSONObject(output.toString("UTF-8"));
            }
        } finally { connection.disconnect(); }
    }
    private JSONObject fixture() throws Exception { return request("http://10.0.2.2:" + arguments.getString("receiverPort") + "/fixture", null); }
    private void tvState() throws Exception { tvState(true); }
    private void tvState(boolean active) throws Exception {
        request(base + "state", object("client", CLIENT, "sessionId", session, "playback", object("active", active, "source", tvSource,
                "title", "TV fixture", "paused", tvPaused, "positionSeconds", tvPosition, "durationSeconds", 120, "canSeek", true,
                "volume", object("current", 40, "max", 100), "tracks", new JSONArray(), "selectedIndex", -1, "subtitlesEnabled", false,
                "receiverDebug", object("revision", JSONObject.NULL, "uptimeSeconds", 2, "pingCount", 0, "remoteReload", true, "remoteCommands", true))));
    }
    private JSONArray poll() throws Exception { return request(base + "poll?client=" + CLIENT + "&sessionId=" + session + "&after=" + after, null).getJSONArray("commands"); }
    private JSONObject nextCommand() throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 4000;
        while (SystemClock.elapsedRealtime() < deadline) {
            JSONArray commands = poll();
            if (commands.length() > 0) {
                require(commands.length() == 1, "Unexpected extra TV command"); JSONObject command = commands.getJSONObject(0);
                received.add(command.getString("method")); return command;
            }
            Thread.sleep(30);
        }
        throw new AssertionError("Expected TV command missing");
    }
    private void acknowledge(JSONObject command) throws Exception {
        tvState(); after = command.getLong("id");
        request(base + "ack", object("client", CLIENT, "sessionId", session, "id", after, "ok", true));
    }
    private void connectLuma() throws Exception {
        call("device.connect", "host", "10.0.2.2");
        call("receiver.configure", "token", "fixture_receiver_token_0123456789", "port", Integer.parseInt(arguments.getString("receiverPort")));
        require(call("receiver.connect").getBoolean("ready"), "LUMA Receiver API not ready"); call("receiver.status");
    }
    private void bothConnected() throws Exception {
        require(service.snapshot().getJSONObject("connection").getBoolean("connected"), "LUMA transport disconnected");
        require(call("msx.status").getBoolean("connected"), "TV transport disconnected");
        require(call("receiver.status").getBoolean("active"), "Independent receiver playback disappeared");
    }
    private PendingCall open(String source) throws Exception {
        runner.runOnMainSync(() -> service.authorizeToken("multi-current", "philips"));
        return submit("msx.open", "source", source, "mimeType", "application/vnd.apple.mpegurl", "title", "TV fixture",
                "subtitles", new JSONArray(), "preferredLanguage", "pt-BR", "sourceToken", "multi-current");
    }
    private void rememberLuma() throws Exception {
        JSONObject media = object("url", LUMA, "contentType", "application/vnd.apple.mpegurl", "title", "LUMA fixture video", "tracks", new JSONArray());
        JSONObject result = object("tracks", new JSONArray(), "catalog", new JSONArray(), "selectedIndex", JSONObject.NULL, "subtitlesEnabled", false);
        runner.runOnMainSync(() -> require(service.rememberHandoff(media, result), "LUMA handoff metadata rejected")); runner.waitForIdleSync();
    }
    private Notification waitCard(String title, String action) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 6000;
        while (SystemClock.elapsedRealtime() < deadline) {
            for (StatusBarNotification entry : ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).getActiveNotifications()) {
                Notification card = entry.getNotification();
                if (entry.getId() == 42 && title.contentEquals(card.extras.getCharSequence(Notification.EXTRA_TITLE, "")) && card.actions != null) {
                    for (Notification.Action button : card.actions) if (action.contentEquals(button.title)) return card;
                }
            }
            Thread.sleep(60);
        }
        throw new AssertionError("Selected receiver media card missing");
    }
    private PendingIntent action(Notification card, String name) {
        for (Notification.Action button : card.actions) if (name.contentEquals(button.title)) return button.actionIntent;
        throw new AssertionError("Media action missing");
    }
    private void launchAndBind() throws Exception {
        Intent launch = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.invalid/"));
        launch.setClassName(context, MainActivity.class.getName()); launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        launch.putExtra("dev.framextv.lumaremote.TEST_NO_AUTO_CONNECT", true);
        activity = (MainActivity) runner.startActivitySync(launch);
        runner.runOnMainSync(() -> {
            WebView browser = activity.getWindow().getDecorView().findViewWithTag("luma-browser");
            require(browser != null, "Owned browsing WebView missing"); browser.stopLoading(); browser.loadUrl("about:blank");
        });
        CountDownLatch ready = new CountDownLatch(1);
        binding = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) { service = ((ConnectionService.LocalBinder) binder).getService(); ready.countDown(); }
            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        runner.runOnMainSync(() -> require(context.bindService(new Intent(context, ConnectionService.class), binding, Context.BIND_AUTO_CREATE), "Service bind rejected"));
        require(ready.await(8, TimeUnit.SECONDS), "Service did not bind");
    }
    private void execute() {
        Bundle result = new Bundle();
        try {
            require(PACKAGE.equals(context.getPackageName()), "Only the isolated receiver fixture package is supported");
            require(Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator") || Build.MODEL.contains("sdk_gphone"), "Destructive setup requires an emulator");
            require("10.0.2.2".equals(arguments.getString("targetHost")), "Only the explicit emulator loopback fixture is supported");
            fixtureAuthorized = true;
            new SettingsStore(context).clear("all"); new MsxServer(context).reset();
            context.getSharedPreferences("receiver-selection", Context.MODE_PRIVATE).edit().clear().commit();
            launchAndBind(); connectLuma();
            JSONObject started = call("msx.start"); String origin = started.getString("startupUrl");
            require(new URL(origin).getHost().startsWith("10.0.2."), "TV HTTP fixture escaped the emulator interface");
            require((origin + "/tv/connect.html").equals(started.getString("browserSetupUrl")), "Browser setup URL was not the active native LAN origin and fixed path");
            String home = request(origin + "/msx/start.json", null).getString("parameter").substring("content:".length());
            base = home.substring(0, home.length() - "home.json".length());
            session = request(base + "hello?client=" + CLIENT, null).getString("sessionId");
            call("msx.confirm", "host", new URL(origin).getHost()); tvState();
            for (String target : new String[] {"philips", "luma", "philips"}) { call("app.selectTarget", "target", target); bothConnected(); }
            for (String method : new String[] {"msx.textPairRequest", "msx.typeAddress", "msx.typeBrowserAddress", "msx.launchBrowser"}) {
                for (String key : new String[] {"host", "url", "text"}) {
                    PendingCall invalidText = submit(method, key, "https://elsewhere.test");
                    require(invalidText.ready.await(3, TimeUnit.SECONDS) && "TEXT_INVALID_INPUT".equals(invalidText.error.get()), "Fixed text RPC accepted a caller target or text");
                }
            }
            PendingCall invalidPin = submit("msx.textPairGrant", "pin", "123");
            require(invalidPin.ready.await(3, TimeUnit.SECONDS) && "TEXT_INVALID_PIN".equals(invalidPin.error.get()), "Text RPC accepted an invalid PIN");
            runner.runOnMainSync(() -> service.authorizeToken("multi-current", "philips"));
            for (String debugMethod : new String[] {"msx.debugPing", "msx.debugLabel", "msx.reloadReceiver"}) {
                PendingCall invalidDebug = submit(debugMethod, "label", "Caller text");
                require(invalidDebug.ready.await(3, TimeUnit.SECONDS) && "INVALID_REQUEST".equals(invalidDebug.error.get()), "Debug RPC accepted caller parameters");
            }
            call("app.selectTarget", "target", "luma");
            for (String debugMethod : new String[] {"msx.debugPing", "msx.debugLabel", "msx.reloadReceiver"}) {
                PendingCall wrongTarget = submit(debugMethod);
                require(wrongTarget.ready.await(3, TimeUnit.SECONDS) && "INVALID_REQUEST".equals(wrongTarget.error.get()), "Debug RPC crossed selected receiver targets");
            }
            call("app.selectTarget", "target", "philips");
            for (String debugMethod : new String[] {"msx.debugPing", "msx.debugLabel"}) {
                PendingCall debug = submit(debugMethod); JSONObject debugCommand = nextCommand();
                require(("msx.debugPing".equals(debugMethod) ? "ping" : "set-label").equals(debugCommand.getString("method")), "Debug RPC used the wrong wire command");
                require("msx.debugPing".equals(debugMethod) ? debugCommand.getJSONObject("params").length() == 0
                        : debugCommand.getJSONObject("params").length() == 1 && "LUMA debug channel ready".equals(debugCommand.getJSONObject("params").getString("label")), "Debug RPC changed the fixed payload");
                require(debug.ready.getCount() == 1, "Debug RPC completed before its receipt"); acknowledge(debugCommand);
                require(await(debug, 4).getBoolean("accepted") && FIRST.equals(tvSource), "Debug control changed media or lost its receipt");
            }
            PendingCall activeReload = submit("msx.reloadReceiver");
            require(activeReload.ready.await(3, TimeUnit.SECONDS) && "RELOAD_BLOCKED".equals(activeReload.error.get()) && poll().length() == 0, "Reload interrupted active media");
            tvState(false); Thread.sleep(50);
            PendingCall reload = submit("msx.reloadReceiver"); JSONObject reloadCommand = nextCommand();
            require("reload".equals(reloadCommand.getString("method")) && reloadCommand.getJSONObject("params").length() == 0 && reload.ready.getCount() == 1, "Idle reload was not a fixed receipt-bound command");
            after = reloadCommand.getLong("id"); request(base + "ack", object("client", CLIENT, "sessionId", session, "id", after, "ok", true));
            require(await(reload, 4).getBoolean("accepted"), "Idle reload receipt failed"); tvState();
            for (Object[] supplied : new Object[][] {{"source", FIRST}, {"sourceToken", "multi-current"}, {"title", "Replacement"}}) {
                PendingCall rejected = submit("msx.sample", supplied);
                require(rejected.ready.await(3, TimeUnit.SECONDS) && "INVALID_REQUEST".equals(rejected.error.get()), "Sample accepted caller-supplied parameters");
            }
            PendingCall sample = submit("msx.sample"); JSONObject sampleCommand = nextCommand();
            String fixedSample = "lan-fixture".equals(call("msx.status").getJSONObject("diagnostics").getString("sampleTransport"))
                    ? base + "diagnostic-sintel.mp4" : "https://media.w3.org/2010/05/sintel/trailer.mp4";
            require("open".equals(sampleCommand.getString("method")) && fixedSample.equals(sampleCommand.getJSONObject("params").getString("source")), "Service sample did not use its fixed owned or public media");
            require(sample.ready.getCount() == 1, "Service sample completed without a TV receipt");
            tvSource = sampleCommand.getJSONObject("params").getString("source"); acknowledge(sampleCommand);
            require(await(sample, 4).getBoolean("accepted"), "Service sample failed after its receipt");
            // A normal open using the pre-existing token proves the sample did
            // not replace capture authorization. No fresh authorizeToken call.
            PendingCall retainedCapture = submit("msx.open", "source", FIRST, "mimeType", "application/vnd.apple.mpegurl", "title", "TV fixture", "subtitles", new JSONArray(), "sourceToken", "multi-current");
            JSONObject captured = nextCommand(); require(FIRST.equals(captured.getJSONObject("params").getString("source")), "Sample replaced capture authorization");
            tvSource = FIRST; acknowledge(captured); require(await(retainedCapture, 4).getBoolean("accepted"), "Captured source failed after sample");
            passed("both receiver transports survive selected-target changes with LUMA Receiver API ready");

            phase = "independent queues";
            PendingCall opening = open(SECOND); JSONObject delivered = nextCommand();
            require("open".equals(delivered.getString("method")), "TV Open was not delivered");
            PendingCall pendingReload = submit("msx.reloadReceiver");
            require(pendingReload.ready.await(3, TimeUnit.SECONDS) && "RELOAD_BLOCKED".equals(pendingReload.error.get()), "Reload waited behind or interrupted a pending command");
            long before = SystemClock.elapsedRealtime(); call("msx.status"); require(SystemClock.elapsedRealtime() - before < 1500, "MSX status blocked behind Open");
            before = SystemClock.elapsedRealtime(); call("luma.status"); call("luma.key", "key", "right");
            require(SystemClock.elapsedRealtime() - before < 2500, "LUMA controls blocked behind TV Open");
            require(opening.ready.getCount() == 1, "Open completed before the TV receipt");
            call("device.disconnect"); require(call("msx.status").getBoolean("connected"), "LUMA disconnect closed TV transport");
            tvSource = SECOND; acknowledge(delivered); require(await(opening, 4).getBoolean("accepted"), "TV Open failed after LUMA disconnect");
            require(fixture().getInt("stops") == 0, "LUMA disconnect sent Stop"); connectLuma(); bothConnected();
            passed("pending TV Open permits status and LUMA controls; LUMA disconnect preserves its receipt");

            phase = "TV canonical ownership";
            JSONObject tvContent = WatchHistory.contentFromCatalog("tv", "42", 1, 1, "TV fixture · S1 E1");
            JSONObject tvMedia = object("url", SECOND, "contentType", "application/vnd.apple.mpegurl", "title", "TV fixture · S1 E1",
                    "tracks", new JSONArray(), "content", tvContent, "resumeSeconds", 0);
            runner.runOnMainSync(() -> service.rememberWatchHandoff(tvMedia, "philips", true)); runner.waitForIdleSync();
            java.lang.reflect.Field televisionMedia = ConnectionService.class.getDeclaredField("televisionMedia"); televisionMedia.setAccessible(true);
            JSONObject retainedTvMedia = (JSONObject) televisionMedia.get(service);
            require(retainedTvMedia != null && tvContent.getString("key").equals(retainedTvMedia.getJSONObject("content").getString("key")),
                    "Accepted TV handoff lost its canonical metadata because MSX playback has no content tags");
            JSONArray tvHistory = call("watch.history").getJSONArray("items"); JSONObject tvProgress = null;
            for (int index = 0; index < tvHistory.length(); index++) {
                JSONObject candidate = tvHistory.getJSONObject(index);
                if (tvContent.getString("key").equals(candidate.optString("key"))) { tvProgress = candidate; break; }
            }
            require(tvProgress != null && tvProgress.getDouble("positionSeconds") == tvPosition && "philips".equals(tvProgress.getString("lastReceiver")),
                    "Accepted TV handoff did not bind canonical progress to the observed MSX timestamp");
            passed("TV handoff retains canonical metadata and progress even though MSX exposes only the exact source");

            phase = "selected system controls";
            call("app.selectTarget", "target", "luma"); call("receiver.status"); rememberLuma();
            Notification lumaCard = waitCard("LUMA fixture video", "Pause"); PendingIntent staleLumaPause = action(lumaCard, "Pause");
            PendingIntent staleLumaBack = action(lumaCard, "Back 30 seconds");
            MediaController controller = new MediaController(context, service.getMediaSessionToken());
            PlaybackState.CustomAction staleLumaStop = null;
            for (PlaybackState.CustomAction candidate : controller.getPlaybackState().getCustomActions()) if ("Stop video".contentEquals(candidate.getName())) staleLumaStop = candidate;
            require(staleLumaStop != null, "LUMA system Stop action missing");
            int mutations = fixture().getInt("mutations");
            call("app.selectTarget", "target", "philips"); Notification rewindCard = waitCard("TV fixture", "Back 30 seconds");
            PendingIntent staleTvBack = action(rewindCard, "Back 30 seconds");
            require((controller.getPlaybackState().getActions() & PlaybackState.ACTION_REWIND) != 0, "Selected TV did not advertise MediaSession rewind");
            controller.getTransportControls().rewind(); JSONObject rewind = nextCommand();
            require("seek".equals(rewind.getString("method")) && rewind.getJSONObject("params").getDouble("seconds") == 0
                    && SECOND.equals(rewind.getJSONObject("params").getString("expectedSourceUrl")), "MediaSession did not route a source-guarded TV Back 30");
            tvPosition = 0; acknowledge(rewind);
            controller.getTransportControls().pause(); JSONObject pause = nextCommand();
            require("playPause".equals(pause.getString("method")) && !pause.getJSONObject("params").getBoolean("play")
                    && SECOND.equals(pause.getJSONObject("params").getString("expectedSourceUrl")), "MediaSession did not route a source-guarded TV pause");
            tvPaused = true; acknowledge(pause); Notification tvCard = waitCard("TV fixture", "Play"); PendingIntent staleTvPlay = action(tvCard, "Play");
            staleLumaPause.send(); staleLumaBack.send(); controller.getTransportControls().sendCustomAction(staleLumaStop.getAction(), staleLumaStop.getExtras()); Thread.sleep(350);
            require(poll().length() == 0 && fixture().getInt("mutations") == mutations, "Stale LUMA actions changed a receiver after target switch");
            tvSource = THIRD; tvPaused = false; tvPosition = 15; tvState(); call("msx.status"); staleTvPlay.send(); staleTvBack.send(); Thread.sleep(200);
            require(poll().length() == 0, "Stale TV notification changed replacement video");
            PendingCall staleSource = submit("msx.playPause", "play", false, "expectedSourceUrl", SECOND);
            require(staleSource.ready.await(3, TimeUnit.SECONDS) && "SOURCE_CHANGED".equals(staleSource.error.get()), "TV source guard accepted a stale source");
            call("app.selectTarget", "target", "luma"); waitCard("LUMA fixture video", "Back 30 seconds"); controller.getTransportControls().rewind();
            long rewindDeadline = SystemClock.elapsedRealtime() + 4000;
            while (fixture().getInt("position") != 0 && SystemClock.elapsedRealtime() < rewindDeadline) Thread.sleep(50);
            require(fixture().getInt("position") == 0 && fixture().getInt("mutations") == mutations + 1, "Selected LUMA MediaSession Back 30 was not observed exactly once");
            controller.getTransportControls().pause();
            long deadline = SystemClock.elapsedRealtime() + 4000;
            while (fixture().getBoolean("playing") && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
            require(!fixture().getBoolean("playing") && fixture().getInt("mutations") == mutations + 2, "Selected LUMA system pause was not observed exactly once");
            staleTvPlay.send(); staleTvBack.send(); Thread.sleep(200); require(poll().length() == 0 && fixture().getInt("mutations") == mutations + 2, "Stale TV action retargeted to LUMA");
            passed("MediaSession routes source-guarded Back 30 and playback to the selected receiver without crossing stale targets");

            phase = "immediate TV disconnect";
            call("app.selectTarget", "target", "philips"); PendingCall pending = open(FOURTH); JSONObject last = nextCommand();
            require("open".equals(last.getString("method")), "Final Open not delivered"); tvSource = FOURTH; tvState();
            before = SystemClock.elapsedRealtime(); call("msx.disconnect"); require(SystemClock.elapsedRealtime() - before < 1500, "TV disconnect blocked behind delivered Open");
            require(call("msx.status").getString("browserSetupUrl").isEmpty(), "Stopped server retained a browser setup URL");
            require(pending.ready.await(3, TimeUnit.SECONDS) && pending.error.get() != null, "TV disconnect did not cancel pending caller");
            require(service.snapshot().getJSONObject("connection").getBoolean("connected") && call("receiver.status").getBoolean("active"), "TV disconnect closed or stopped LUMA");
            require(!received.contains("stop") && FOURTH.equals(tvSource) && fixture().getInt("stops") == 0, "Disconnect sent a playback Stop");
            passed("TV disconnect immediately cancels its pending receipt while independent playback and LUMA survive");
            result.putInt("passed", checks); result.putInt("failures", 0);
        } catch (Throwable failure) {
            result.putInt("passed", checks); result.putInt("failures", 1);
            result.putString("failure", phase + ": " + failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage()));
        } finally {
            // These resets are reachable only after all emulator/package guards passed.
            if (service != null) {
                try { call("msx.reset"); call("device.disconnect"); service.revokeTokens(); } catch (Exception ignored) { }
            }
            if (binding != null) runner.runOnMainSync(() -> context.unbindService(binding));
            if (activity != null) runner.runOnMainSync(activity::finish);
            if (fixtureAuthorized) context.stopService(new Intent(context, ConnectionService.class));
        }
        runner.finish(result.getInt("failures") == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }
}

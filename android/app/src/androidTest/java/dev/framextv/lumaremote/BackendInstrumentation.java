package dev.framextv.lumaremote;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;

/** Defaults to explicit Mac loopback fixtures. The separate live-controls mode
 * requires explicit confirmation and never runs the destructive fixture setup. */
public final class BackendInstrumentation extends UiSmokeInstrumentation {
    private NativeBackend backend;
    private Bundle args;
    private int tests, passed, failures;
    private String failure = "";
    private static final String FIRST = "https://media.example/first.m3u8";
    private interface Check { void run() throws Exception; }
    @Override public void onCreate(Bundle arguments) { args = arguments; start(); }
    private static JSONObject data(Object... values) throws Exception {
        JSONObject object = new JSONObject();
        for (int i = 0; i < values.length; i += 2) object.put((String) values[i], values[i + 1]);
        return object;
    }
    private JSONObject call(String method, Object... params) throws Exception { return backend.handle(method, data(params)); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private void rejects(String expected, String method, Object... params) throws Exception {
        try { call(method, params); throw new AssertionError("Expected " + expected); }
        catch (NativeBackend.BackendException error) { require(error.code.equals(expected), "Unexpected error code " + error.code); }
    }
    private JSONObject media(String url) throws Exception { return data("url", url, "contentType", "application/vnd.apple.mpegurl"); }
    private JSONObject episodeMedia() throws Exception {
        String title = "Fixture Series · S2 E3";
        return data("url", FIRST, "contentType", "application/vnd.apple.mpegurl", "title", title,
                "content", WatchHistory.contentFromCatalog("tv", "42", 2, 3, title));
    }
    private void connect() throws Exception {
        JSONObject result = call("device.connect", "host", args.getString("targetHost"));
        require(result.getBoolean("connected") && "LUMA fixture".equals(result.getString("name")), "Device identity missing");
    }
    private static final String TOKEN = "fixture_receiver_token_0123456789", CODE = "123456";
    private void configure(int port) throws Exception { call("receiver.configure", "token", TOKEN, "port", port); }
    private void check(String name, Check action) {
        tests++;
        Bundle progress = new Bundle(); progress.putString("class", getClass().getName()); progress.putString("test", name); progress.putInt("current", tests); sendStatus(1, progress);
        try { action.run(); passed++; sendStatus(0, progress); }
        catch (Throwable error) {
            failures++; failure = name + ": " + (error instanceof NativeBackend.BackendException ? ((NativeBackend.BackendException) error).code : error.getClass().getSimpleName());
            progress.putString("stack", failure); sendStatus(-2, progress);
        }
    }
    @Override public void onStart() {
        if ("matter".equals(args.getString("mode"))) { MatterInstrumentation.run(this); return; }
        if ("multi-receiver".equals(args.getString("mode"))) { MultiReceiverInstrumentation.run(this, args); return; }
        if ("msx".equals(args.getString("mode"))) { MsxInstrumentation.run(this); return; }
        if ("philips-text".equals(args.getString("mode"))) { PhilipsTextInstrumentation.run(this); return; }
        if ("watch-history".equals(args.getString("mode"))) { WatchHistoryInstrumentation.run(this); return; }
        if ("library".equals(args.getString("mode"))) { LibraryInstrumentation.run(this); return; }
        if ("catalog".equals(args.getString("mode"))) { CatalogInstrumentation.run(this); return; }
        if ("tv-catalog".equals(args.getString("mode"))) { TVCatalogInstrumentation.run(this); return; }
        if ("web-content".equals(args.getString("mode")) || "web-content-live".equals(args.getString("mode"))) { WebContentInstrumentation.run(this, args); return; }
        if ("migration-seed".equals(args.getString("mode")) || "migration-check".equals(args.getString("mode"))) { SigningMigrationInstrumentation.run(this, args); return; }
        if ("live-controls".equals(args.getString("mode"))) { LiveControlsInstrumentation.run(this, args); return; }
        if ("updates".equals(args.getString("mode"))) { UpdateInstrumentation.run(this, args); return; }
        if ("ui".equals(args.getString("mode"))) { super.onStart(); return; }
        if ("service".equals(args.getString("mode"))) { ServiceInstrumentation.run(this, args); return; }
        if ("settings".equals(args.getString("mode"))) { SettingsInstrumentation.run(this); return; }
        if ("subtitles".equals(args.getString("mode"))) { SubtitleInstrumentation.run(this, args); return; }
        if ("scrub-preview".equals(args.getString("mode"))) { ScrubPreviewInstrumentation.run(this); return; }
        backend = new NativeBackend(getTargetContext());
        backend.setSourceGuard(token -> "current".equals(token));
        int receiverPort = Integer.parseInt(args.getString("receiverPort")), slowPort = Integer.parseInt(args.getString("slowPort"));
        check("connection, pairing and receiver identity", () -> {
            connect();
            rejects("RECEIVER_CONFIG", "receiver.status");
            JSONObject begun = call("receiver.pairBegin", "port", receiverPort);
            String pairing = begun.getString("pairingId");
            require(pairing.matches("[A-Za-z0-9_-]{8,64}") && begun.getInt("expiresInSeconds") == 120, "Pairing window missing");
            rejects("PAIRING_CODE_WRONG", "receiver.pairComplete", "pairingId", pairing, "code", "000000");
            rejects("PAIRING_CODE_WRONG", "receiver.pairComplete", "pairingId", pairing, "code", "12ab56");
            rejects("PAIRING_EXPIRED", "receiver.pairComplete", "pairingId", "expiredPairing01", "code", CODE);
            rejects("RECEIVER_CONFIG", "receiver.status");
            JSONObject paired = call("receiver.pairComplete", "pairingId", pairing, "code", "123 456");
            require(paired.getBoolean("configured") && TOKEN.equals(paired.getString("token")), "Issued pairing token missing");
            JSONObject receiver = call("receiver.connect");
            require(receiver.getBoolean("ready") && "LUMA Receiver".equals(receiver.getString("name"))
                    && "fixture-1.0".equals(receiver.getString("version")) && "fixture-receiver".equals(receiver.getString("receiverId")), "Receiver identity missing");
        });
        check("bounded keys and Unicode text", () -> {
            call("luma.key", "key", "right"); call("luma.text", "text", "Olá 👋");
            JSONObject volume = call("luma.setVolume", "value", 7).getJSONObject("volume");
            require(volume.getInt("current") == 6 && volume.getInt("max") == 15, "Volume readback was replaced with the requested value");
            rejects("INVALID_INPUT", "luma.setVolume", "value", -1);
            rejects("INVALID_INPUT", "luma.setVolume", "value", 16);
            rejects("INVALID_INPUT", "luma.setVolume", "value", 1.5);
            rejects("INVALID_TEXT", "luma.text", "text", "text\r\nKEYEVENT\r\n3");
            rejects("INVALID_INPUT", "luma.key", "key", "power");
            rejects("INVALID_INPUT", "luma.powerOff");
            rejects("INVALID_INPUT", "luma.powerOff", "confirmed", false);
            rejects("INVALID_INPUT", "luma.powerOff", "confirmed", "true");
            call("luma.powerOff", "confirmed", true);
        });
        check("verified app inventory and receiver launch", () -> {
            JSONArray apps = call("luma.apps").getJSONArray("apps"); require(apps.length() == 2, "App inventory incomplete");
            call("luma.launchReceiver", "sourceToken", "current");
            rejects("SOURCE_CHANGED", "luma.launchReceiver", "sourceToken", "stale");
            rejects("APP_NOT_AVAILABLE", "luma.launchApp", "packageName", "example.not_installed");
            rejects("INVALID_INPUT", "luma.launchApp", "packageName", "dev.framextv.lumareceiver\r\nKEYEVENT");
        });
        check("media open and source guards", () -> {
            // The fixture starts idle, so the forwarded onlyIfInactive guard accepts this cold open.
            call("receiver.open", "media", media(FIRST), "expectedSourceUrl", FIRST, "onlyIfInactive", true, "sourceToken", "current");
            JSONObject status = call("receiver.status"); require(status.getBoolean("active") && FIRST.equals(status.getString("sourceUrl")) && !status.has("content"), "Current media identity missing");
            JSONObject invalid = episodeMedia(); invalid.getJSONObject("content").put("key", "framextv:tv:42:2:4");
            call("receiver.open", "media", invalid, "sourceToken", "current");
            require(!call("receiver.status").has("content"), "Invalid content identity was forwarded");
            call("receiver.open", "media", episodeMedia(), "sourceToken", "current");
            JSONObject adopted = call("receiver.status"), content = adopted.optJSONObject("content");
            require(content != null && "framextv:tv:42:2:3".equals(content.optString("key"))
                    && "Fixture Series · S2 E3".equals(content.optString("title")), "Canonical receiver identity was not recovered");
            rejects("SOURCE_CHANGED", "receiver.open", "media", episodeMedia(), "expectedSourceUrl", FIRST,
                    "expectedContentKey", "framextv:tv:42:2:4", "sourceToken", "current");
            rejects("INVALID_INPUT", "receiver.open", "media", episodeMedia(), "expectedSourceUrl", FIRST,
                    "expectedContentKey", "not-a-canonical-key", "sourceToken", "current");
            // Something is playing now: the receiver rejects the forwarded onlyIfInactive guard.
            rejects("SOURCE_CHANGED", "receiver.open", "media", media(FIRST), "expectedSourceUrl", FIRST, "onlyIfInactive", true, "sourceToken", "current");
            rejects("INVALID_INPUT", "receiver.open", "media", media(FIRST), "onlyIfInactive", "true", "sourceToken", "current");
            rejects("SOURCE_CHANGED", "receiver.open", "media", media(FIRST), "expectedSourceUrl", FIRST, "sourceToken", "stale");
            rejects("SOURCE_CHANGED", "receiver.open", "media", media(FIRST), "expectedSourceUrl", "https://media.example/other.m3u8", "sourceToken", "current");
            rejects("SOURCE_CHANGED", "receiver.pause", "expectedSourceUrl", "https://media.example/other.m3u8", "sourceToken", "current");
            rejects("SOURCE_CHANGED", "luma.setVolume", "value", 7, "expectedSourceUrl", "https://media.example/other.m3u8");
        });
        check("receiver applies forwarded replacement guards atomically", () -> {
            JSONObject resumed = episodeMedia().put("resumeSeconds", 30);
            call("receiver.open", "media", resumed, "expectedCurrentSourceUrl", FIRST, "expectedCurrentContentKey", "framextv:tv:42:2:3", "sourceToken", "current");
            require(call("receiver.status").getInt("positionSeconds") == 30, "Forwarded resume position was not applied by the receiver");
            rejects("SOURCE_CHANGED", "receiver.open", "media", episodeMedia(), "expectedCurrentSourceUrl", FIRST,
                    "expectedCurrentContentKey", "framextv:tv:42:2:4", "sourceToken", "current");
            rejects("SOURCE_CHANGED", "receiver.open", "media", episodeMedia(), "expectedCurrentSourceUrl", "https://media.example/other.m3u8",
                    "expectedCurrentContentKey", "framextv:tv:42:2:3", "sourceToken", "current");
            rejects("INVALID_INPUT", "receiver.open", "media", episodeMedia(), "expectedCurrentSourceUrl", FIRST, "sourceToken", "current");
            rejects("INVALID_INPUT", "receiver.open", "media", episodeMedia(), "onlyIfInactive", true, "expectedCurrentSourceUrl", FIRST,
                    "expectedCurrentContentKey", "framextv:tv:42:2:3", "sourceToken", "current");
            rejects("INVALID_INPUT", "receiver.open", "media", episodeMedia(), "expectedCurrentSourceUrl", FIRST,
                    "expectedCurrentContentKey", "framextv:tv:42", "sourceToken", "current");
            rejects("SOURCE_CHANGED", "receiver.pause", "expectedSourceUrl", FIRST, "expectedContentKey", "framextv:tv:42:2:4", "sourceToken", "current");
            rejects("INVALID_INPUT", "receiver.pause", "expectedSourceUrl", FIRST, "expectedContentKey", "framextv:tv:42", "sourceToken", "current");
            require(call("receiver.status").getBoolean("playing"), "A rejected guard changed playback");
        });
        check("public URL and pairing token boundaries", () -> {
            rejects("INVALID_MEDIA", "receiver.open", "media", media("http://192.168.1.2/private"), "sourceToken", "current");
            rejects("INVALID_MEDIA", "receiver.addSubtitle", "track", data("url", "http://127.0.0.1/private"), "expectedSourceUrl", FIRST);
            rejects("RECEIVER_CONFIG", "receiver.configure", "token", TOKEN + "\r\nInjected", "port", receiverPort);
            rejects("RECEIVER_CONFIG", "receiver.configure", "token", "short", "port", receiverPort);
            rejects("INVALID_INPUT", "receiver.configure", "token", TOKEN, "port", 80);
            require(call("receiver.status").getBoolean("active"), "Rejected configuration replaced the paired token");
        });
        check("subtitle identity and selection", () -> {
            JSONArray tracks = call("receiver.getSubtitles", "expectedSourceUrl", FIRST).getJSONArray("tracks"); require(tracks.length() == 2, "Subtitle inventory incomplete");
            require("Português".equals(tracks.getJSONObject(1).getString("name")) && "pt-br".equals(tracks.getJSONObject(1).getString("language")), "Subtitle labels changed");
            call("receiver.addSubtitle", "track", data("url", "https://media.example/pt.vtt", "language", "pt-BR", "label", "Português"), "expectedSourceUrl", FIRST, "sourceToken", "current");
            call("receiver.setSubtitle", "index", 1, "expectedSourceUrl", FIRST, "sourceToken", "current");
            call("receiver.setSubtitle", "index", "off", "expectedSourceUrl", FIRST, "sourceToken", "current");
            rejects("INVALID_SUBTITLE", "receiver.setSubtitle", "index", 8, "expectedSourceUrl", FIRST);
            rejects("INVALID_INPUT", "receiver.setSubtitle", "index", "8", "expectedSourceUrl", FIRST);
            rejects("SOURCE_CHANGED", "receiver.getSubtitles", "expectedSourceUrl", "https://media.example/other.m3u8");
        });
        check("rejected receipts versus uncertain responses", () -> {
            rejects("RECEIVER_REJECTED", "receiver.addSubtitle", "track", data("url", "https://media.example/rejected.vtt"), "expectedSourceUrl", FIRST);
            rejects("RECEIVER_REJECTED", "receiver.addSubtitle", "track", data("url", "https://media.example/bare-4xx.vtt"), "expectedSourceUrl", FIRST);
            rejects("RECEIVER_RESPONSE", "receiver.addSubtitle", "track", data("url", "https://media.example/unknown.vtt"), "expectedSourceUrl", FIRST);
            rejects("RECEIVER_RESPONSE", "receiver.addSubtitle", "track", data("url", "https://media.example/missing-result.vtt"), "expectedSourceUrl", FIRST);
            rejects("RECEIVER_RESPONSE", "receiver.addSubtitle", "track", data("url", "https://media.example/malformed.vtt"), "expectedSourceUrl", FIRST);
            rejects("RECEIVER_RESPONSE", "receiver.addSubtitle", "track", data("url", "https://media.example/trailing.vtt"), "expectedSourceUrl", FIRST);
            rejects("RECEIVER_RESPONSE", "receiver.addSubtitle", "track", data("url", "https://media.example/false-result.vtt"), "expectedSourceUrl", FIRST);
            rejects("RECEIVER_RESPONSE", "receiver.addSubtitle", "track", data("url", "https://media.example/null-result.vtt"), "expectedSourceUrl", FIRST);
            rejects("RECEIVER_REJECTED", "receiver.open", "media", media("https://media.example/rejected.m3u8"), "sourceToken", "current");
            rejects("RECEIVER_RESPONSE", "receiver.open", "media", media("https://media.example/unknown.m3u8"), "sourceToken", "current");
            rejects("RECEIVER_RESPONSE", "receiver.open", "media", media("https://media.example/false-result.m3u8"), "sourceToken", "current");
            rejects("RECEIVER_RESPONSE", "receiver.open", "media", media("https://media.example/null-result.m3u8"), "sourceToken", "current");
            rejects("INVALID_MEDIA", "receiver.open", "media", media("https://media.example/invalid-content.m3u8"), "sourceToken", "current");
            rejects("CANNOT_SEEK", "receiver.seek", "position", 86000, "expectedSourceUrl", FIRST, "sourceToken", "current");
            require(FIRST.equals(call("receiver.status").getString("sourceUrl")), "A rejected receipt changed playback");
        });
        check("revoked pairing clears the in-memory token", () -> {
            rejects("RECEIVER_AUTH", "receiver.addSubtitle", "track", data("url", "https://media.example/revoked.vtt"), "expectedSourceUrl", FIRST);
            require(!backend.connectionSnapshot().getBoolean("configured"), "Revoked token remained configured");
            rejects("RECEIVER_CONFIG", "receiver.status");
            configure(receiverPort); require(call("receiver.status").getBoolean("active"), "A fresh token was not accepted");
        });
        check("inactive and ready subtitle snapshots", () -> {
            configure(Integer.parseInt(args.getString("readinessPort")));
            try {
                JSONObject waiting = call("receiver.getSubtitles", "expectedSourceUrl", FIRST);
                require(!waiting.getBoolean("active") && waiting.getJSONArray("tracks").length() == 0 && waiting.isNull("currentIndex"), "Unready player was reported subtitle-ready");
                JSONObject ready = call("receiver.getSubtitles", "expectedSourceUrl", FIRST);
                require(ready.getBoolean("active") && ready.getJSONArray("tracks").length() == 0, "Ready player without tracks was treated as inactive");
                JSONObject unknown = call("receiver.getSubtitles", "expectedSourceUrl", FIRST);
                require(unknown.getBoolean("active") && unknown.getJSONArray("tracks").length() == 1 && unknown.isNull("currentIndex"), "An unlisted current index was trusted");
                rejects("RECEIVER_RESPONSE", "receiver.getSubtitles", "expectedSourceUrl", FIRST);
                rejects("RECEIVER_RESPONSE", "receiver.getSubtitles", "expectedSourceUrl", FIRST);
                rejects("RECEIVER_RESPONSE", "receiver.getSubtitles", "expectedSourceUrl", FIRST);
            } finally { configure(receiverPort); }
        });
        check("malformed status and identity fail closed", () -> {
            int malformed = Integer.parseInt(args.getString("malformedPort"));
            configure(malformed);
            try {
                rejects("RECEIVER_RESPONSE", "receiver.status");
                rejects("RECEIVER_RESPONSE", "receiver.status");
                JSONObject spoofed = call("receiver.status");
                require(spoofed.getBoolean("active") && !spoofed.has("content"), "A non-canonical receiver identity was adopted");
                rejects("RECEIVER_RESPONSE", "receiver.connect");
                rejects("RECEIVER_RESPONSE", "receiver.pairBegin", "port", malformed);
                rejects("RECEIVER_RESPONSE", "receiver.pairComplete", "pairingId", "fixturePairing01", "code", CODE, "port", malformed);
            } finally { configure(receiverPort); }
        });
        check("one bounded deadline covers every call in an operation", () -> {
            configure(Integer.parseInt(args.getString("deadlinePort")));
            long before = SystemClock.elapsedRealtime();
            try {
                rejects("RECEIVER_UNAVAILABLE", "receiver.connect");
                long duration = SystemClock.elapsedRealtime() - before;
                require(duration >= 4000 && duration < 6500, "Receiver connect exceeded its overall deadline");
            } finally { configure(receiverPort); }
            require(call("receiver.status").getBoolean("active"), "Expired read affected the next operation");
        });
        check("explicit pause resume seek stop", () -> {
            call("receiver.pause", "expectedSourceUrl", FIRST, "expectedContentKey", "framextv:tv:42:2:3", "sourceToken", "current");
            require(!call("receiver.status").getBoolean("playing"), "Pause not observed");
            call("receiver.resume", "expectedSourceUrl", FIRST, "sourceToken", "current");
            call("receiver.seek", "position", 60, "expectedSourceUrl", FIRST, "sourceToken", "current");
            require(call("receiver.status").getInt("positionSeconds") == 60, "Seek value not serialized correctly");
            call("receiver.stop", "expectedSourceUrl", FIRST, "sourceToken", "current"); require(!call("receiver.status").getBoolean("active"), "Stop not observed");
            rejects("NO_VIDEO", "receiver.pause", "expectedSourceUrl", FIRST, "sourceToken", "current");
        });
        check("closed TCP reconnect and nonblocking close", () -> {
            call("luma.key", "key", "left"); Thread.sleep(100);
            try { call("luma.status"); throw new AssertionError("Closed fixture socket was accepted"); }
            catch (NativeBackend.BackendException expected) { require(expected.code.equals("INVALID_RESPONSE") || expected.code.equals("CONNECTION_FAILED") || expected.code.equals("NOT_CONNECTED"), "Unexpected closed-socket error"); }
            connect(); configure(slowPort);
            Thread request = new Thread(() -> { try { call("receiver.status"); } catch (Exception ignored) {} }); request.start(); Thread.sleep(150);
            long before = SystemClock.elapsedRealtime(); backend.close(); require(SystemClock.elapsedRealtime() - before < 300, "close blocked the caller");
            request.join(5500); require(!request.isAlive(), "Network cancellation remained active");
        });
        backend.close();
        Bundle result = new Bundle(); result.putInt("tests", tests); result.putInt("passed", passed); result.putInt("failures", failures); result.putString("failure", failure);
        finish(failures == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }
}

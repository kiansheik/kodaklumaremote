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
    private void connect() throws Exception {
        JSONObject result = call("device.connect", "host", args.getString("targetHost"));
        require(result.getBoolean("connected") && "LUMA fixture".equals(result.getString("name")), "Device identity missing");
    }
    private void configure(int port) throws Exception { call("kodi.configure", "username", "fixture", "password", "fixture", "port", port); }
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
        if ("migration-seed".equals(args.getString("mode")) || "migration-check".equals(args.getString("mode"))) { SigningMigrationInstrumentation.run(this, args); return; }
        if ("live-controls".equals(args.getString("mode"))) { LiveControlsInstrumentation.run(this, args); return; }
        if ("updates".equals(args.getString("mode"))) { UpdateInstrumentation.run(this, args); return; }
        if ("ui".equals(args.getString("mode"))) { super.onStart(); return; }
        if ("service".equals(args.getString("mode"))) { ServiceInstrumentation.run(this, args); return; }
        if ("settings".equals(args.getString("mode"))) { SettingsInstrumentation.run(this); return; }
        backend = new NativeBackend(getTargetContext());
        backend.setSourceGuard(token -> "current".equals(token));
        int kodiPort = Integer.parseInt(args.getString("kodiPort")), slowPort = Integer.parseInt(args.getString("slowPort"));
        check("connection and Kodi identity", () -> {
            connect(); configure(kodiPort);
            JSONObject kodi = call("kodi.connect"); require(kodi.getBoolean("ready") && "Kodi".equals(kodi.getString("name")), "Kodi identity missing");
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
        check("verified app inventory and launch", () -> {
            JSONArray apps = call("luma.apps").getJSONArray("apps"); require(apps.length() == 2, "App inventory incomplete");
            call("luma.launchKodi", "sourceToken", "current");
            rejects("SOURCE_CHANGED", "luma.launchKodi", "sourceToken", "stale");
            rejects("APP_NOT_AVAILABLE", "luma.launchApp", "packageName", "example.not_installed");
            rejects("INVALID_INPUT", "luma.launchApp", "packageName", "org.xbmc.kodi\r\nKEYEVENT");
        });
        check("media open and source guards", () -> {
            call("kodi.open", "media", media(FIRST), "expectedSourceUrl", FIRST, "sourceToken", "current");
            JSONObject status = call("kodi.status"); require(status.getBoolean("active") && FIRST.equals(status.getString("sourceUrl")), "Current media identity missing");
            rejects("SOURCE_CHANGED", "kodi.open", "media", media(FIRST), "expectedSourceUrl", FIRST, "onlyIfInactive", true, "sourceToken", "current");
            rejects("INVALID_INPUT", "kodi.open", "media", media(FIRST), "onlyIfInactive", "true", "sourceToken", "current");
            rejects("SOURCE_CHANGED", "kodi.open", "media", media(FIRST), "expectedSourceUrl", FIRST, "sourceToken", "stale");
            rejects("SOURCE_CHANGED", "kodi.open", "media", media(FIRST), "expectedSourceUrl", "https://media.example/other.m3u8", "sourceToken", "current");
            rejects("SOURCE_CHANGED", "kodi.pause", "expectedSourceUrl", "https://media.example/other.m3u8", "sourceToken", "current");
            rejects("SOURCE_CHANGED", "luma.setVolume", "value", 7, "expectedSourceUrl", "https://media.example/other.m3u8");
        });
        check("public URL and credential boundaries", () -> {
            rejects("INVALID_MEDIA", "kodi.open", "media", media("http://192.168.1.2/private"), "sourceToken", "current");
            rejects("INVALID_MEDIA", "kodi.addSubtitle", "track", data("url", "http://127.0.0.1/private"), "expectedSourceUrl", FIRST);
            rejects("KODI_CONFIG", "kodi.configure", "username", "fixture\r\nInjected", "password", "fixture", "port", kodiPort);
        });
        check("subtitle identity and selection", () -> {
            JSONArray tracks = call("kodi.getSubtitles", "expectedSourceUrl", FIRST).getJSONArray("tracks"); require(tracks.length() == 2, "Subtitle inventory incomplete");
            call("kodi.addSubtitle", "track", data("url", "https://media.example/pt.vtt"), "expectedSourceUrl", FIRST, "sourceToken", "current");
            call("kodi.setSubtitle", "index", 1, "expectedSourceUrl", FIRST, "sourceToken", "current");
            rejects("INVALID_SUBTITLE", "kodi.setSubtitle", "index", 8, "expectedSourceUrl", FIRST);
            rejects("SOURCE_CHANGED", "kodi.getSubtitles", "expectedSourceUrl", "https://media.example/other.m3u8");
        });
        check("rejected subtitle receipt versus uncertain responses", () -> {
            rejects("KODI_REJECTED", "kodi.addSubtitle", "track", data("url", "https://media.example/rejected.vtt"), "expectedSourceUrl", FIRST);
            rejects("KODI_RESPONSE", "kodi.addSubtitle", "track", data("url", "https://media.example/unknown.vtt"), "expectedSourceUrl", FIRST);
            rejects("KODI_RESPONSE", "kodi.addSubtitle", "track", data("url", "https://media.example/wrong-id.vtt"), "expectedSourceUrl", FIRST);
            rejects("KODI_RESPONSE", "kodi.addSubtitle", "track", data("url", "https://media.example/malformed.vtt"), "expectedSourceUrl", FIRST);
            rejects("KODI_RESPONSE", "kodi.addSubtitle", "track", data("url", "https://media.example/trailing.vtt"), "expectedSourceUrl", FIRST);
            rejects("KODI_REJECTED", "kodi.open", "media", media("https://media.example/rejected.m3u8"), "sourceToken", "current");
            rejects("KODI_RESPONSE", "kodi.open", "media", media("https://media.example/unknown.m3u8"), "sourceToken", "current");
        });
        check("explicit pause resume seek stop", () -> {
            call("kodi.pause", "expectedSourceUrl", FIRST, "sourceToken", "current");
            call("kodi.resume", "expectedSourceUrl", FIRST, "sourceToken", "current");
            call("kodi.seek", "position", 60, "expectedSourceUrl", FIRST, "sourceToken", "current");
            require(call("kodi.status").getInt("positionSeconds") == 60, "Seek value not serialized correctly");
            call("kodi.stop", "expectedSourceUrl", FIRST, "sourceToken", "current"); require(!call("kodi.status").getBoolean("active"), "Stop not observed");
        });
        check("closed TCP reconnect and nonblocking close", () -> {
            call("luma.key", "key", "left"); Thread.sleep(100);
            try { call("luma.status"); throw new AssertionError("Closed fixture socket was accepted"); }
            catch (NativeBackend.BackendException expected) { require(expected.code.equals("INVALID_RESPONSE") || expected.code.equals("CONNECTION_FAILED") || expected.code.equals("NOT_CONNECTED"), "Unexpected closed-socket error"); }
            connect(); configure(slowPort);
            Thread request = new Thread(() -> { try { call("kodi.status"); } catch (Exception ignored) {} }); request.start(); Thread.sleep(150);
            long before = SystemClock.elapsedRealtime(); backend.close(); require(SystemClock.elapsedRealtime() - before < 300, "close blocked the caller");
            request.join(5500); require(!request.isAlive(), "Network cancellation remained active");
        });
        backend.close();
        Bundle result = new Bundle(); result.putInt("tests", tests); result.putInt("passed", passed); result.putInt("failures", failures); result.putString("failure", failure);
        finish(failures == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }
}

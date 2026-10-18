package dev.framextv.lumaremote;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Build;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Emulator-only private-interface HTTP fixtures. Never discovers or controls a TV. */
public final class MsxInstrumentation {
    private static final String CLIENT = "fixture_receiver_1234567890", SOURCE = "https://media.example/fixture.m3u8";
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static JSONObject object(Object... fields) throws Exception { JSONObject result = new JSONObject(); for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]); return result; }
    private static JSONObject request(String url, JSONObject data, int expected) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);
        try {
            connection.setConnectTimeout(3000); connection.setReadTimeout(3000); connection.setInstanceFollowRedirects(false);
            if (data != null) {
                byte[] body = data.toString().getBytes(StandardCharsets.UTF_8); connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json"); connection.setFixedLengthStreamingMode(body.length);
                try (java.io.OutputStream out = connection.getOutputStream()) { out.write(body); }
            }
            int status = connection.getResponseCode(); require(status == expected, "Unexpected fixture HTTP status " + status + " expected " + expected);
            InputStream stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            if (stream == null) return new JSONObject();
            try (InputStream input = stream; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int count; while ((count = input.read(buffer)) >= 0) bytes.write(buffer, 0, count);
                return new JSONObject(bytes.toString("UTF-8"));
            }
        } finally { connection.disconnect(); }
    }
    private static int rawStatus(String origin, String request) throws Exception {
        URL url = new URL(origin);
        try (Socket socket = new Socket(url.getHost(), url.getPort())) {
            socket.setSoTimeout(3000); socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush();
            ByteArrayOutputStream first = new ByteArrayOutputStream(); int next;
            while ((next = socket.getInputStream().read()) >= 0 && next != '\n' && first.size() < 128) first.write(next);
            return Integer.parseInt(first.toString("US-ASCII").split(" ")[1]);
        }
    }
    private static JSONObject state(String session, boolean active) throws Exception {
        return object("client", CLIENT, "sessionId", session, "playback", object("active", active, "source", active ? SOURCE : "", "title", "Fixture video",
                "paused", false, "positionSeconds", 12, "durationSeconds", 120, "canSeek", true,
                "volume", object("current", 42, "max", 100), "tracks", new JSONArray(), "selectedIndex", -1, "subtitlesEnabled", false));
    }
    private static String pollUrl(String base, String session, long after) { return base + "poll?client=" + CLIENT + "&sessionId=" + session + "&after=" + after; }
    private static JSONObject awaitCommand(String base, String session, long after) throws Exception {
        for (int i = 0; i < 40; i++) { JSONArray commands = request(pollUrl(base, session, after), null, 200).getJSONArray("commands"); if (commands.length() > 0) return commands.getJSONObject(0); Thread.sleep(25); }
        throw new AssertionError("Command was not queued");
    }
    private static void rejects(String code, Callable<?> action) throws Exception {
        try { action.call(); throw new AssertionError("Expected fixed error " + code); }
        catch (MsxServer.MsxException failure) { require(code.equals(failure.code), "Unexpected MSX error " + failure.code); }
    }
    private static void failedFuture(String code, Future<?> future) throws Exception {
        try { future.get(3, TimeUnit.SECONDS); throw new AssertionError("Invalidated command succeeded"); }
        catch (ExecutionException failure) { require(failure.getCause() instanceof MsxServer.MsxException && code.equals(((MsxServer.MsxException) failure.getCause()).code), "Unexpected command failure"); }
    }
    private static void passed(Instrumentation runner, int count, String name) { Bundle progress = new Bundle(); progress.putInt("current", count); progress.putString("test", name); runner.sendStatus(0, progress); }

    public static void run(Instrumentation runner) {
        Bundle result = new Bundle(); int checks = 0; MsxServer server = null; ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            require(Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator") || Build.MODEL.contains("sdk_gphone"), "MSX destructive fixtures require an emulator");
            server = new MsxServer(runner.getTargetContext()); final MsxServer target = server;
            AtomicBoolean authorized = new AtomicBoolean(true); server.setSourceGuard(token -> authorized.get() && "fixture-current".equals(token));
            server.reset(); JSONObject started = server.start(); String origin = started.getString("startupUrl");
            require(origin.matches("http://[0-9.]+:[0-9]+"), "Setup URL contains a capability or nonprivate host");
            JSONObject configuration = request(origin + "/msx/start.json", null, 200);
            String home = configuration.getString("parameter").substring("content:".length()), base = home.substring(0, home.length() - "home.json".length());
            String hostHeader = new URL(origin).getAuthority();
            require(rawStatus(origin, "GET /msx/start.json HTTP/1.1\r\nHost: attacker.example\r\n\r\n") == 403, "Host spoof accepted");
            require(rawStatus(origin, "GET /msx/start.json HTTP/1.1\r\nHost: " + hostHeader + "\r\nOrigin: https://attacker.example\r\n\r\n") == 403, "Foreign origin bootstrap accepted");
            require(rawStatus(origin, "POST /invalid HTTP/1.1\r\nHost: " + hostHeader + "\r\nContent-Type: application/json\r\nContent-Length: 0\r\nContent-Length: 0\r\n\r\n") == 400, "Ambiguous framing accepted");
            require(rawStatus(origin, "POST /invalid HTTP/1.1\r\nHost: " + hostHeader + "\r\nContent-Type: application/json\r\nContent-Length: 999999\r\n\r\n") == 413, "Oversized request accepted");
            require(rawStatus(origin, "GET " + new URL(base).getPath() + "%2e%2e/secret HTTP/1.1\r\nHost: " + hostHeader + "\r\n\r\n") == 400, "Encoded path traversal accepted");
            request(base + "unbundled.js", null, 404);
            require(request(home, null, 200).getJSONArray("items").getJSONObject(0).getString("action").endsWith("receiver.html"), "Bootstrap did not link the receiver");
            String session = request(base + "hello?client=" + CLIENT, null, 200).getString("sessionId");
            require(!request(pollUrl(base, session, 0), null, 200).getBoolean("approved"), "Unapproved receiver accepted");
            request(base + "state", state(session, false), 403);
            rejects("MSX_NOT_CONNECTED", () -> target.command("pause", new JSONObject()));
            rejects("MSX_PAIRING", () -> target.confirmPairing("192.168.99.99"));
            require(server.status().getBoolean("awaitingApproval"), "Pair request not visible");
            require(server.start().getBoolean("awaitingApproval"), "Repeated setup discarded an unexpired pairing request");
            server.confirmPairing(new URL(origin).getHost());
            request(base + "state", state(session, false), 200);
            require(server.start().getBoolean("connected") && request(pollUrl(base, session, 0), null, 200).getBoolean("approved"), "Repeated start disrupted an approved receiver session");
            require(server.status().getBoolean("connected") && server.hasSavedReceiver(), "Confirmed receiver not connected");
            request(base + "hello?client=another_receiver_123456789", null, 403);
            passed(runner, ++checks, "private startup bootstrap requires explicit matching-host approval and resists second client");

            final String firstSession = session;
            JSONObject open = object("source", SOURCE, "mimeType", "application/vnd.apple.mpegurl", "title", "Fixture", "subtitles", new JSONArray(), "sourceToken", "fixture-current");
            Future<JSONObject> opening = executor.submit(() -> target.command("open", open));
            JSONObject command = awaitCommand(base, session, 0); long firstId = command.getLong("id");
            require("open".equals(command.getString("method")) && !command.getJSONObject("params").has("sourceToken"), "Native authorization leaked to TV");
            require(request(pollUrl(base, session, 0), null, 200).getJSONArray("commands").length() == 0, "Delivered command replayed");
            request(base + "state", state(session, true), 200);
            request(base + "ack", object("client", CLIENT, "sessionId", session, "id", firstId, "ok", true), 200);
            require(opening.get(3, TimeUnit.SECONDS).getJSONObject("playback").getBoolean("active"), "Command result lost observed playback");
            require(server.status().getJSONObject("lastCommand").getBoolean("ok") && !server.status().has("pendingCommand"), "Ack receipt not retained");
            passed(runner, ++checks, "TV owns public source, each command delivered once and observed state precedes acknowledged result");

            rejects("INVALID_MEDIA", () -> target.command("open", object("source", "http://192.168.1.1/private", "sourceToken", "fixture-current")));
            rejects("INVALID_INPUT", () -> target.command("volume", object("value", 101)));
            rejects("SOURCE_CHANGED", () -> target.command("open", object("source", SOURCE, "sourceToken", "stale")));
            rejects("SOURCE_CHANGED", () -> target.command("pause", object("expectedSourceUrl", "https://media.example/other.m3u8")));
            JSONObject unsafeState = state(session, true); unsafeState.getJSONObject("playback").put("source", "http://127.0.0.1/private"); request(base + "state", unsafeState, 400);
            require(SOURCE.equals(server.status().getJSONObject("playback").getString("source")), "Rejected state replaced trusted state");
            passed(runner, ++checks, "local media, invalid ranges, stale source controls and invalid playback reports fail closed");

            Future<JSONObject> canceled = executor.submit(() -> target.command("open", open));
            for (int i = 0; i < 40 && !server.status().has("pendingCommand"); i++) Thread.sleep(25);
            authorized.set(false); require(request(pollUrl(base, session, firstId), null, 200).getJSONArray("commands").length() == 0, "Canceled media delivered");
            failedFuture("SOURCE_CHANGED", canceled); authorized.set(true);
            passed(runner, ++checks, "capture authorization checked again immediately before queued media delivery");

            Future<JSONObject> invalidated = executor.submit(() -> target.command("pause", new JSONObject()));
            JSONObject oldCommand = awaitCommand(base, session, firstId);
            session = request(base + "hello?client=" + CLIENT, null, 200).getString("sessionId");
            require(!firstSession.equals(session), "Reload reused session");
            failedFuture("MSX_TIMEOUT", invalidated);
            request(base + "ack", object("client", CLIENT, "sessionId", firstSession, "id", oldCommand.getLong("id"), "ok", true), 403);
            require(request(pollUrl(base, session, 0), null, 200).getJSONArray("commands").length() == 0, "Reload replayed an old command");
            request(base + "state", state(session, true), 200);
            passed(runner, ++checks, "receiver reload revokes stale sessions and queued controls without replay");

            Future<JSONObject> uncertain = executor.submit(() -> target.command("pause", new JSONObject()));
            JSONObject delayed = awaitCommand(base, session, 0); long delayedId = delayed.getLong("id");
            try { uncertain.get(12, TimeUnit.SECONDS); throw new AssertionError("Unacknowledged control succeeded"); }
            catch (ExecutionException failure) { require(failure.getCause() instanceof MsxServer.MsxException && "MSX_TIMEOUT".equals(((MsxServer.MsxException) failure.getCause()).code), "Wrong unknown outcome"); }
            require(server.status().getJSONObject("pendingCommand").getLong("id") == delayedId, "Unknown delivered command receipt lost");
            request(base + "ack", object("client", CLIENT, "sessionId", session, "id", delayedId, "ok", false, "error", "SUBTITLE_FAILED"), 200);
            require(!server.status().has("pendingCommand") && "SUBTITLE_FAILED".equals(server.status().getJSONObject("lastCommand").getString("error")), "Late receiver failure was lost");
            passed(runner, ++checks, "delivered timeout remains unknown and late acknowledgement resolves safe receipt");

            int stablePort = server.status().getInt("port"); server.close(); server = new MsxServer(runner.getTargetContext());
            require(server.hasSavedReceiver() && origin.equals(server.start().getString("startupUrl")) && server.status().getInt("port") == stablePort, "Approved receiver or startup address did not persist");
            require(home.equals(request(origin + "/msx/start.json", null, 200).getString("parameter").substring("content:".length())), "Persistent token changed");
            server.reset(); server.start(); request(home, null, 404);
            require(!server.hasSavedReceiver(), "Reset retained approved receiver");
            passed(runner, ++checks, "approved pairing and stable startup survive restart; explicit reset revokes old capability");
            result.putInt("passed", checks); result.putInt("failures", 0); runner.finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putInt("passed", checks); result.putInt("failures", 1); result.putString("failure", failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage())); runner.finish(Activity.RESULT_CANCELED, result);
        } finally { if (server != null) { server.reset(); server.close(); } executor.shutdownNow(); }
    }
}

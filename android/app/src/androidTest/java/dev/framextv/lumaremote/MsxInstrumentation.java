package dev.framextv.lumaremote;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
        return Integer.parseInt(rawResponse(origin, request).split(" ")[1]);
    }
    private static String rawResponse(String origin, String request) throws Exception {
        URL url = new URL(origin);
        try (Socket socket = new Socket(url.getHost(), url.getPort())) {
            socket.setSoTimeout(3000); socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush();
            ByteArrayOutputStream response = new ByteArrayOutputStream(); int next;
            while ((next = socket.getInputStream().read()) >= 0 && response.size() < 4096) {
                response.write(next); String received = response.toString("US-ASCII"); int boundary = received.indexOf("\r\n\r\n");
                if (boundary >= 0) {
                    java.util.regex.Matcher length = java.util.regex.Pattern.compile("(?i)Content-Length: ([0-9]+)\\r\\n").matcher(received.substring(0, boundary + 2));
                    if (length.find() && response.size() >= boundary + 4 + Integer.parseInt(length.group(1))) break;
                }
            }
            return response.toString("US-ASCII");
        }
    }
    private static final class MediaResponse {
        byte[] body;
        String type, length, range, acceptRanges;
    }
    private static MediaResponse mediaRequest(String url, String method, String range, String origin, int expected) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);
        try {
            connection.setConnectTimeout(3000); connection.setReadTimeout(3000); connection.setInstanceFollowRedirects(false); connection.setRequestMethod(method);
            if (range != null) connection.setRequestProperty("Range", range);
            if (origin != null) connection.setRequestProperty("Origin", origin);
            int status = connection.getResponseCode(); require(status == expected, "Unexpected media fixture HTTP status " + status + " expected " + expected);
            MediaResponse result = new MediaResponse();
            result.type = connection.getHeaderField("Content-Type"); result.length = connection.getHeaderField("Content-Length");
            result.range = connection.getHeaderField("Content-Range"); result.acceptRanges = connection.getHeaderField("Accept-Ranges");
            InputStream stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            try (InputStream input = stream; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                if (input != null) {
                    byte[] buffer = new byte[16384]; int count;
                    while ((count = input.read(buffer)) >= 0) { require(bytes.size() + count <= 5 * 1024 * 1024, "Media fixture exceeded its bound"); bytes.write(buffer, 0, count); }
                }
                result.body = bytes.toByteArray();
            }
            return result;
        } finally { connection.disconnect(); }
    }
    private static void diagnosticMedia(String origin, String base, String hostHeader, boolean bundled) throws Exception {
        String url = base + "diagnostic-sintel.mp4", path = new URL(url).getPath();
        if (!bundled) {
            mediaRequest(url, "GET", null, null, 404); mediaRequest(url, "HEAD", null, null, 404); return;
        }
        final int size = 4372373;
        MediaResponse head = mediaRequest(url, "HEAD", null, null, 200);
        require(head.body.length == 0 && Integer.toString(size).equals(head.length) && "video/mp4".equals(head.type)
                && "bytes".equals(head.acceptRanges) && head.range == null, "Full fixture HEAD headers or empty body are incorrect");
        MediaResponse full = mediaRequest(url, "GET", null, null, 200);
        byte[] expectedDigest = new byte[32]; String hash = "b670602fa00934ca27c4351bb0efe7ea7a07fae57284e44226025eeed7c51254";
        for (int i = 0; i < expectedDigest.length; i++) expectedDigest[i] = (byte) Integer.parseInt(hash.substring(i * 2, i * 2 + 2), 16);
        require(full.body.length == size && Integer.toString(size).equals(full.length) && "video/mp4".equals(full.type)
                && "bytes".equals(full.acceptRanges) && full.range == null && Arrays.equals(expectedDigest, MessageDigest.getInstance("SHA-256").digest(full.body)), "Full LAN fixture differs from the verified public sample bytes");
        String[] ranges = {"bytes=0-31", "bytes=" + (size - 32) + "-" + (size - 1), "bytes=" + (size - 32) + "-", "bytes=-32"};
        for (int i = 0; i < ranges.length; i++) {
            int start = i == 0 ? 0 : size - 32;
            MediaResponse part = mediaRequest(url, "GET", ranges[i], i == 0 ? origin : null, 206);
            require(part.body.length == 32 && "32".equals(part.length) && "video/mp4".equals(part.type) && "bytes".equals(part.acceptRanges)
                    && ("bytes " + start + "-" + (start + 31) + "/" + size).equals(part.range)
                    && Arrays.equals(Arrays.copyOfRange(full.body, start, start + 32), part.body), "Single range returned incorrect bytes or boundaries");
        }
        MediaResponse partialHead = mediaRequest(url, "HEAD", "bytes=0-31", null, 206);
        require(partialHead.body.length == 0 && "32".equals(partialHead.length) && ("bytes 0-31/" + size).equals(partialHead.range), "Range HEAD included a body or lost range headers");
        for (String invalid : new String[] {"bytes=" + size + "-", "bytes=-0", "bytes=31-0", "bytes=0-1,4-5", "items=0-1", "bytes=invalid", "bytes=999999999999999999999-"}) {
            MediaResponse rejected = mediaRequest(url, "GET", invalid, null, 416);
            require(("bytes */" + size).equals(rejected.range), "Unsatisfiable range did not report the fixed asset size");
        }
        mediaRequest(url + "?other=1", "GET", "bytes=0-0", null, 400);
        mediaRequest(url, "GET", "bytes=0-0", "https://attacker.example", 403);
        mediaRequest(url, "GET", "bytes=0-0", "null", 403);
        request(url, new JSONObject(), 404);
        require(rawStatus(origin, "GET " + path + " HTTP/1.1\r\nHost: attacker.example\r\nRange: bytes=0-0\r\n\r\n") == 403, "LAN fixture accepted a foreign Host");
        require(rawStatus(origin, "GET /invalid_capability_not_current/diagnostic-sintel.mp4 HTTP/1.1\r\nHost: " + hostHeader + "\r\nRange: bytes=0-0\r\n\r\n") == 404, "LAN fixture accepted a different capability");
        mediaRequest(base + "receiver.js", "HEAD", null, null, 404);
        mediaRequest(base + "arbitrary-private.mp4", "GET", null, null, 404);
    }
    private static void tlsProbe(String origin) throws Exception {
        URL url = new URL(origin);
        try (Socket socket = new Socket(url.getHost(), url.getPort())) { socket.getOutputStream().write(new byte[] { 22, 3, 3 }); socket.getOutputStream().flush(); }
    }
    public static final class BrowserReceipt {
        final CountDownLatch complete = new CountDownLatch(1);
        volatile boolean success;
        @JavascriptInterface public void finish(boolean ok) { success = ok; complete.countDown(); }
    }
    private static void browserCors(Instrumentation runner, String origin) throws Exception {
        BrowserReceipt receipt = new BrowserReceipt(); AtomicReference<WebView> browser = new AtomicReference<>();
        try {
            runner.runOnMainSync(() -> {
                WebView view = new WebView(runner.getTargetContext()); browser.set(view);
                view.getSettings().setJavaScriptEnabled(true); view.addJavascriptInterface(receipt, "FixtureReceipt");
                view.setWebViewClient(new WebViewClient());
                // Entire document is fixture-owned. It fetches only the explicit
                // private listener, never the remote base URL or any media.
                String html = "<!doctype html><meta charset=utf-8><script>" +
                        "fetch(" + JSONObject.quote(origin + "/msx/start.json") + ",{headers:{'Content-Type':'application/json'}})" +
                        ".then(function(r){if(r.status!==200)throw Error();return r.json();})" +
                        ".then(function(d){if(typeof d.parameter!=='string')throw Error();return fetch(" + JSONObject.quote(origin + "/fixture-missing") + ");})" +
                        ".then(function(r){FixtureReceipt.finish(r.status===404);}).catch(function(){FixtureReceipt.finish(false);});</script>";
                view.loadDataWithBaseURL("http://msx.benzac.de/", html, "text/html", "UTF-8", null);
            });
            require(receipt.complete.await(12, TimeUnit.SECONDS) && receipt.success, "Owned WebView CORS bootstrap or visible error response failed");
        } finally { runner.runOnMainSync(() -> { if (browser.get() != null) { browser.get().stopLoading(); browser.get().removeJavascriptInterface("FixtureReceipt"); browser.get().destroy(); } }); }
    }
    private static JSONObject state(String session, boolean active) throws Exception {
        return object("client", CLIENT, "sessionId", session, "playback", object("active", active, "source", active ? SOURCE : "", "title", "Fixture video",
                "paused", false, "positionSeconds", 12, "durationSeconds", 120, "canSeek", true,
                "volume", object("current", 42, "max", 100), "tracks", new JSONArray(), "selectedIndex", -1, "subtitlesEnabled", false, "error", JSONObject.NULL));
    }
    private static JSONObject debugState(String session, boolean active) throws Exception {
        JSONObject result = state(session, active);
        result.getJSONObject("playback").put("receiverDebug", object("revision", JSONObject.NULL, "uptimeSeconds", 12.5,
                "pingCount", 0, "remoteReload", true, "remoteCommands", true));
        return result;
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
            AtomicInteger subtitleFetches = new AtomicInteger();
            TvSubtitleBridge captions = new TvSubtitleBridge(NativeBackend::publicUrl, (url, current) -> {
                subtitleFetches.incrementAndGet();
                return new TvSubtitleBridge.Body((url.endsWith("bad.vtt") ? "<!doctype html><html>not captions</html>"
                        : "WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nOlá <b>TV</b>\n").getBytes(StandardCharsets.UTF_8), "text/vtt; charset=utf-8");
            });
            server = new MsxServer(runner.getTargetContext(), captions); final MsxServer target = server;
            AtomicBoolean authorized = new AtomicBoolean(true); server.setSourceGuard(token -> authorized.get() && "fixture-current".equals(token));
            server.reset(); JSONObject started = server.start(); String origin = started.getString("startupUrl");
            require(origin.matches("http://[0-9.]+:[0-9]+"), "Setup URL contains a capability or nonprivate host");
            String hostHeader = new URL(origin).getAuthority();
            String preflight = "OPTIONS /msx/start.json HTTP/1.1\r\nHost: " + hostHeader + "\r\nOrigin: http://msx.benzac.de\r\nAccess-Control-Request-Method: GET\r\nAccess-Control-Request-Headers: Content-Type, Accept, X-Requested-With\r\nAccess-Control-Request-Private-Network: true\r\n\r\n";
            String cors = rawResponse(origin, preflight);
            require(cors.startsWith("HTTP/1.1 200 ") && cors.contains("Access-Control-Allow-Origin: *\r\n") && cors.contains("Access-Control-Allow-Private-Network: true\r\n"), "MSX preflight headers missing");
            require(!cors.contains("content:") && !server.status().getBoolean("awaitingApproval") && server.status().getJSONObject("diagnostics").getLong("bootstrapAccepted") == 0, "Preflight claimed or disclosed pairing");
            require(rawStatus(origin, preflight.replace("Content-Type, Accept, X-Requested-With", "Authorization")) == 400, "Unapproved CORS header accepted");
            String denied = rawResponse(origin, preflight.replace("http://msx.benzac.de", "https://attacker.example"));
            require(denied.startsWith("HTTP/1.1 403 ") && !denied.contains("Access-Control-Allow-Origin:") && !denied.contains("content:") && !denied.contains("\"parameter\""), "Foreign preflight accepted or disclosed setup");
            require(rawStatus(origin, preflight.replace("Access-Control-Request-Method: GET", "Access-Control-Request-Method: POST")) == 400, "Mutation preflight accepted");
            require(rawStatus(origin, preflight.replace("Origin: http://msx.benzac.de\r\n", "")) == 400, "Originless preflight accepted");
            require(!server.status().getBoolean("awaitingApproval") && !server.hasSavedReceiver() && server.status().getJSONObject("diagnostics").getLong("bootstrapAccepted") == 0, "Denied preflights changed pairing");
            String connect = "GET /tv/connect.html HTTP/1.1\r\nHost: " + hostHeader + "\r\n\r\n";
            require(rawStatus(origin, connect.replace(hostHeader, "attacker.example")) == 403, "Connect route accepted a foreign Host");
            String foreignConnect = rawResponse(origin, connect.replace("\r\n\r\n", "\r\nOrigin: https://attacker.example\r\n\r\n"));
            require(foreignConnect.startsWith("HTTP/1.1 403 ") && !foreignConnect.contains("Location:"), "Connect route disclosed redirect to foreign origin");
            require(rawStatus(origin, connect.replace("connect.html", "connect.html?target=https://attacker.example")) == 400, "Connect route accepted redirect parameters");
            java.lang.reflect.Field until = MsxServer.class.getDeclaredField("pairingUntil"), peer = MsxServer.class.getDeclaredField("pendingHost");
            until.setAccessible(true); peer.setAccessible(true);
            long originalUntil = until.getLong(server);
            until.setLong(server, 0);
            String expiredConnect = rawResponse(origin, connect);
            require(expiredConnect.startsWith("HTTP/1.1 403 ") && !expiredConnect.contains("Location:"), "Connect route bypassed expired pairing");
            until.setLong(server, originalUntil); peer.set(server, "192.168.99.99");
            String wrongPeerConnect = rawResponse(origin, connect);
            require(wrongPeerConnect.startsWith("HTTP/1.1 403 ") && !wrongPeerConnect.contains("Location:"), "Connect route replaced a different pending peer");
            peer.set(server, null);
            String redirect = rawResponse(origin, connect);
            java.util.regex.Matcher destination = java.util.regex.Pattern.compile("\r\nLocation: (/[A-Za-z0-9_-]{32}/receiver\\.html)\r\n").matcher(redirect);
            require(redirect.startsWith("HTTP/1.1 302 Found\r\n") && destination.find() && redirect.contains("Referrer-Policy: no-referrer\r\n")
                    && redirect.contains("Cache-Control: no-store\r\n"), "Connect route did not issue a private fixed redirect");
            require(new URL(origin).getHost().equals(peer.get(server)), "Connect route did not claim the actual requesting peer");
            tlsProbe(origin);
            for (int i = 0; i < 40 && !"HTTPS_REQUEST".equals(server.status().getJSONObject("diagnostics").getString("lastReason")); i++) Thread.sleep(25);
            require(server.status().getJSONObject("diagnostics").getLong("tlsRequests") == 1 && "HTTPS_REQUEST".equals(server.status().getJSONObject("diagnostics").getString("lastReason")), "TLS mismatch was not classified safely");
            long beforeBrowser = server.status().getJSONObject("diagnostics").getLong("bootstrapRequests");
            browserCors(runner, origin);
            require(server.status().getJSONObject("diagnostics").getLong("bootstrapRequests") >= beforeBrowser + 2, "Owned browser did not perform both OPTIONS and GET");
            passed(runner, ++checks, "real owned WebView performs MSX CORS preflight; pairing stays explicit and HTTP errors remain visible");
            JSONObject configuration = request(origin + "/msx/start.json", null, 200);
            require(configuration.getString("parameter").equals(request(origin + "/msx/start.json?v=0.1.167&t=1760950000000", null, 200).getString("parameter")), "MSX cache query changed bootstrap");
            String home = configuration.getString("parameter").substring("content:".length()), base = home.substring(0, home.length() - "home.json".length());
            String sampleTransport = server.status().getJSONObject("diagnostics").getString("sampleTransport");
            require("public".equals(sampleTransport) || "lan-fixture".equals(sampleTransport), "Unknown diagnostic sample transport");
            boolean bundledSample = "lan-fixture".equals(sampleTransport);
            String diagnosticSampleUrl = base + "diagnostic-sintel.mp4";
            mediaRequest(diagnosticSampleUrl, "GET", "bytes=0-0", null, 403);
            mediaRequest(diagnosticSampleUrl, "HEAD", null, null, 403);
            require((new URL(base).getPath() + "receiver.html").equals(destination.group(1)), "Connect route redirect differs from normal receiver capability");
            require(rawResponse(origin, preflight.replace("/msx/start.json", new URL(home).getPath())).startsWith("HTTP/1.1 200 "), "Home-page CORS preflight rejected");
            require(rawStatus(origin, preflight.replace("/msx/start.json", new URL(base).getPath() + "state")) == 403, "Control endpoint allowed cross-origin preflight");
            require(rawStatus(origin, "GET /msx/start.json HTTP/1.1\r\nHost: attacker.example\r\n\r\n") == 403, "Host spoof accepted");
            require(rawStatus(origin, "GET /msx/start.json HTTP/1.1\r\nHost: " + hostHeader + "\r\nOrigin: https://attacker.example\r\n\r\n") == 403, "Foreign origin bootstrap accepted");
            require(rawStatus(origin, "POST /invalid HTTP/1.1\r\nHost: " + hostHeader + "\r\nContent-Type: application/json\r\nContent-Length: 0\r\nContent-Length: 0\r\n\r\n") == 400, "Ambiguous framing accepted");
            require(rawStatus(origin, "POST /invalid HTTP/1.1\r\nHost: " + hostHeader + "\r\nContent-Type: application/json\r\nContent-Length: 999999\r\n\r\n") == 413, "Oversized request accepted");
            require(rawStatus(origin, "GET " + new URL(base).getPath() + "%2e%2e/secret HTTP/1.1\r\nHost: " + hostHeader + "\r\n\r\n") == 400, "Encoded path traversal accepted");
            request(base + "unbundled.js", null, 404);
            require(request(home, null, 200).getJSONArray("items").getJSONObject(0).getString("action").endsWith("receiver.html"), "Bootstrap did not link the receiver");
            require(request(home + "?v=0.1.167&t=1760950000000", null, 200).getJSONArray("items").getJSONObject(0).getString("action").endsWith("receiver.html"), "MSX cache query rejected home page");
            require("NONE".equals(server.status().getJSONObject("diagnostics").getString("lastClientError")), "Fresh receiver had a fabricated client error");
            require(request(base + "diagnostic?code=STATE_READ_FAILED", null, 200).getBoolean("ok"), "Fixed client diagnostic was not accepted before hello");
            for (String invalid : new String[] {"diagnostic", "diagnostic?code=UNTRUSTED", "diagnostic?code=STATE_READ_FAILED&extra=1", "diagnostic?code=STATE_READ_FAILED&code=CHANNEL_FAILED"}) request(base + invalid, null, 400);
            request(base + "diagnostic", object("code", "CHANNEL_FAILED"), 404);
            String diagnosticPath = new URL(base).getPath() + "diagnostic?code=CHANNEL_FAILED";
            require(rawStatus(origin, "GET " + diagnosticPath + " HTTP/1.1\r\nHost: " + hostHeader + "\r\nOrigin: https://attacker.example\r\n\r\n") == 403, "Cross-origin client diagnostic was accepted");
            require("STATE_READ_FAILED".equals(server.status().getJSONObject("diagnostics").getString("lastClientError")), "Rejected diagnostic changed the fixed client error");
            JSONObject untouchedProtocol = server.status().getJSONObject("diagnostics");
            require(untouchedProtocol.getLong("helloAccepted") == 0 && untouchedProtocol.getLong("pollAccepted") == 0 && untouchedProtocol.getLong("stateAccepted") == 0
                    && untouchedProtocol.getLong("lastHelloMsAgo") == -1 && untouchedProtocol.getLong("lastPollMsAgo") == -1 && untouchedProtocol.getLong("lastStateMsAgo") == -1
                    && !untouchedProtocol.getBoolean("sessionPresent") && !untouchedProtocol.getBoolean("sessionApproved"), "Bootstrap alone was presented as an accepted receiver session");
            String session = request(base + "hello?client=" + CLIENT, null, 200).getString("sessionId");
            JSONObject helloProtocol = server.status().getJSONObject("diagnostics");
            require(helloProtocol.getLong("helloAccepted") == 1 && helloProtocol.getLong("lastHelloMsAgo") >= 0
                    && helloProtocol.getBoolean("sessionPresent") && !helloProtocol.getBoolean("sessionApproved") && "HELLO_READY".equals(helloProtocol.getString("lastReason")), "Accepted hello was not distinguished from approval or state");
            require(!request(pollUrl(base, session, 0), null, 200).getBoolean("approved"), "Unapproved receiver accepted");
            request(base + "state", state(session, false), 403);
            JSONObject pendingProtocol = server.status().getJSONObject("diagnostics");
            require(pendingProtocol.getLong("pollAccepted") == 1 && pendingProtocol.getLong("lastPollMsAgo") >= 0
                    && pendingProtocol.getLong("stateAccepted") == 0 && pendingProtocol.getLong("lastStateMsAgo") == -1, "Unapproved state incremented accepted state diagnostics");
            rejects("MSX_NOT_CONNECTED", () -> target.command("pause", new JSONObject()));
            // Old loaded pages retain the capability but must reacquire a
            // transient peer/session after an explicitly renewed setup window.
            until.setLong(server, 0); peer.set(server, null);
            request(pollUrl(base, session, 0), null, 403);
            request(base + "hello?client=" + CLIENT, null, 403);
            require(peer.get(server) == null, "Expired retained hello claimed a peer");
            require(server.start().getBoolean("pairingOpen") && peer.get(server) == null, "Explicit setup did not renew a clean pairing window");
            request(base + "receiver.html", null, 403); request(pollUrl(base, session, 0), null, 403);
            request(base + "state", state(session, false), 403);
            request(base + "diagnostic?code=CHANNEL_FAILED", null, 403);
            String retainedHello = "GET " + new URL(base).getPath() + "hello?client=" + CLIENT + " HTTP/1.1\r\nHost: " + hostHeader + "\r\n\r\n";
            for (String foreignOrigin : new String[] {"https://attacker.example", "http://msx.benzac.de", "null"}) {
                require(rawStatus(origin, retainedHello.replace("\r\n\r\n", "\r\nOrigin: " + foreignOrigin + "\r\n\r\n")) == 403, "Cross-origin retained hello claimed a peer");
            }
            request(base + "hello?client=bad", null, 403);
            request(base + "hello?client=" + CLIENT + "&other=value", null, 403);
            request(base + "hello?client=" + CLIENT + "&client=" + CLIENT, null, 400);
            require(rawStatus(origin, retainedHello.replace("GET ", "OPTIONS ")) == 403, "OPTIONS retained hello claimed a peer");
            require(rawStatus(origin, retainedHello.replace(new URL(base).getPath(), "/invalid_capability_not_current/")) == 404, "Wrong capability retained hello claimed a peer");
            require(peer.get(server) == null, "An asset, control or invalid hello claimed a peer");
            peer.set(server, "192.168.99.99"); request(base + "hello?client=" + CLIENT, null, 403);
            require("192.168.99.99".equals(peer.get(server)), "Retained hello replaced another pending peer"); peer.set(server, null);
            require(rawStatus(origin, retainedHello.replace("\r\n\r\n", "\r\nOrigin: " + origin + "\r\n\r\n")) == 200, "Same-origin retained hello could not reclaim its peer");
            session = request(base + "hello?client=" + CLIENT, null, 200).getString("sessionId");
            require(server.status().getBoolean("awaitingApproval") && !server.status().getBoolean("connected"), "Retained hello bypassed explicit phone approval");
            request(base + "state", state(session, false), 403);
            rejects("MSX_PAIRING", () -> target.confirmPairing("192.168.99.99"));
            require(server.status().getBoolean("awaitingApproval"), "Pair request not visible");
            require(server.start().getBoolean("awaitingApproval"), "Repeated setup discarded an unexpired pairing request");
            server.confirmPairing(new URL(origin).getHost());
            JSONObject approvalProtocol = server.status().getJSONObject("diagnostics");
            require(approvalProtocol.getBoolean("sessionPresent") && approvalProtocol.getBoolean("sessionApproved") && approvalProtocol.getLong("stateAccepted") == 0
                    && approvalProtocol.getLong("lastStateMsAgo") == -1 && !server.status().getBoolean("connected"), "Phone approval was confused with successful current receiver state");
            for (String clientError : new String[] {"STATE_READ_FAILED", "STATUS_RENDER_FAILED", "NETWORK_UNAVAILABLE", "HTTP_REJECTED", "INVALID_RESPONSE", "CHANNEL_FAILED"}) {
                request(base + "diagnostic?code=" + clientError, null, 200);
                require(clientError.equals(server.status().getJSONObject("diagnostics").getString("lastClientError")), "Fixed client error code was changed or dropped");
            }
            JSONObject clientDiagnostic = server.status().getJSONObject("diagnostics");
            require(clientDiagnostic.getLong("stateAccepted") == 0 && clientDiagnostic.getLong("lastStateMsAgo") == -1 && !server.status().getBoolean("connected"), "Client diagnostic forged current receiver state");
            java.lang.reflect.Field approvedPeer = MsxServer.class.getDeclaredField("approvedHost"); approvedPeer.setAccessible(true);
            String savedPeer = (String) approvedPeer.get(server); approvedPeer.set(server, "192.168.99.99");
            String wrongApproved = rawResponse(origin, connect);
            require(wrongApproved.startsWith("HTTP/1.1 403 ") && !wrongApproved.contains("Location:"), "Connect route bypassed approved-peer pinning");
            request(base + "hello?client=" + CLIENT, null, 403);
            request(base + "diagnostic?code=STATE_READ_FAILED", null, 403);
            mediaRequest(diagnosticSampleUrl, "GET", "bytes=0-0", null, 403);
            mediaRequest(diagnosticSampleUrl, "HEAD", null, null, 403);
            require("CHANNEL_FAILED".equals(server.status().getJSONObject("diagnostics").getString("lastClientError")), "Wrong-peer diagnostic changed the client error");
            approvedPeer.set(server, savedPeer);
            require(rawResponse(origin, connect).startsWith("HTTP/1.1 302 Found\r\n"), "Approved receiver could not use connect entry");
            request(base + "state", state(session, false), 200);
            JSONObject firstStateProtocol = server.status().getJSONObject("diagnostics");
            require(firstStateProtocol.getLong("stateAccepted") == 1 && firstStateProtocol.getLong("lastStateMsAgo") >= 0
                    && "STATE_READY".equals(firstStateProtocol.getString("lastReason")) && server.status().getBoolean("connected"), "Accepted state did not advance state diagnostics and current connection");
            require(server.start().getBoolean("connected") && request(pollUrl(base, session, 0), null, 200).getBoolean("approved"), "Repeated start disrupted an approved receiver session");
            require(server.status().getBoolean("connected") && server.hasSavedReceiver(), "Confirmed receiver not connected");
            diagnosticMedia(origin, base, hostHeader, bundledSample);
            require(server.status().getJSONObject("playback").has("error") && server.status().getJSONObject("playback").isNull("error"), "Healthy JSON-null state rejected or coerced");
            long validHelloCount = server.status().getJSONObject("diagnostics").getLong("helloAccepted");
            long validPollCount = server.status().getJSONObject("diagnostics").getLong("pollAccepted");
            request(base + "hello?client=another_receiver_123456789", null, 403);
            request(base + "poll?client=" + CLIENT + "&sessionId=" + session + "&after=invalid", null, 400);
            JSONObject rejectedProtocol = server.status().getJSONObject("diagnostics");
            require(rejectedProtocol.getLong("helloAccepted") == validHelloCount && rejectedProtocol.getLong("pollAccepted") == validPollCount
                    && rejectedProtocol.getLong("stateAccepted") == 1, "Rejected protocol calls incremented accepted counters");
            passed(runner, ++checks, "private startup bootstrap requires explicit matching-host approval and resists second client");

            final String firstSession = session;
            JSONObject open = object("source", SOURCE, "mimeType", "application/vnd.apple.mpegurl", "title", "Fixture", "subtitles", new JSONArray()
                    .put(object("url", "https://captions.example/fixture.vtt", "language", "pt-BR", "label", "Português"))
                    .put(object("url", "https://captions.example/bad.vtt", "language", "en", "label", "English")), "autoSubtitles", false, "sourceToken", "fixture-current");
            Future<JSONObject> opening = executor.submit(() -> target.command("open", open));
            JSONObject command = awaitCommand(base, session, 0); long firstId = command.getLong("id");
            require("open".equals(command.getString("method")) && !command.getJSONObject("params").has("sourceToken"), "Native authorization leaked to TV");
            request(base + "subtitle-prepare", object("client", CLIENT, "sessionId", session, "index", 0, "expectedSourceUrl", SOURCE), 409);
            require(subtitleFetches.get() == 0, "Local subtitle route used an unacknowledged Open catalog");
            require(request(pollUrl(base, session, 0), null, 200).getJSONArray("commands").length() == 0, "Delivered command replayed");
            request(base + "state", state(session, true), 200);
            request(base + "ack", object("client", CLIENT, "sessionId", session, "id", firstId, "ok", true), 200);
            require(opening.get(3, TimeUnit.SECONDS).getJSONObject("playback").getBoolean("active"), "Command result lost observed playback");
            require(server.status().getJSONObject("lastCommand").getBoolean("ok") && !server.status().has("pendingCommand"), "Ack receipt not retained");
            require(subtitleFetches.get() == 0, "Open fetched subtitles before selection");
            Future<JSONObject> off = executor.submit(() -> target.command("subtitle", object("index", -1, "enabled", false, "expectedSourceUrl", SOURCE)));
            JSONObject offCommand = awaitCommand(base, session, firstId);
            require(!offCommand.getJSONObject("params").has("url") && subtitleFetches.get() == 0, "Off fetched or injected a caption URL");
            request(base + "ack", object("client", CLIENT, "sessionId", session, "id", offCommand.getLong("id"), "ok", true), 200); off.get(3, TimeUnit.SECONDS);
            JSONObject prepareBody = object("client", CLIENT, "sessionId", session, "index", 0, "expectedSourceUrl", SOURCE);
            request(base + "subtitle-prepare", null, 404);
            for (JSONObject invalidPrepare : new JSONObject[] {
                    object("client", CLIENT, "sessionId", session, "index", 0, "expectedSourceUrl", SOURCE, "url", "https://elsewhere.example/caption"),
                    object("client", CLIENT, "sessionId", session, "index", 0.5, "expectedSourceUrl", SOURCE),
                    object("client", CLIENT, "sessionId", session, "index", 2, "expectedSourceUrl", SOURCE),
                    object("client", CLIENT, "sessionId", session, "expectedSourceUrl", SOURCE)}) request(base + "subtitle-prepare", invalidPrepare, 400);
            request(base + "subtitle-prepare?extra=1", prepareBody, 400);
            request(base + "subtitle-prepare", object("client", "different_receiver_12345678", "sessionId", session, "index", 0, "expectedSourceUrl", SOURCE), 403);
            request(base + "subtitle-prepare", object("client", CLIENT, "sessionId", "stale-session", "index", 0, "expectedSourceUrl", SOURCE), 403);
            request(base + "subtitle-prepare", object("client", CLIENT, "sessionId", session, "index", 0, "expectedSourceUrl", "https://media.example/other.m3u8"), 409);
            String preparePayload = prepareBody.toString(), preparePath = new URL(base + "subtitle-prepare").getPath();
            String preparePost = "POST " + preparePath + " HTTP/1.1\r\nHost: " + hostHeader + "\r\nContent-Type: application/json\r\nContent-Length: "
                    + preparePayload.getBytes(StandardCharsets.UTF_8).length + "\r\nOrigin: https://attacker.example\r\n\r\n" + preparePayload;
            require(rawStatus(origin, preparePost) == 403, "Cross-origin local caption preparation was accepted");
            String preparePeer = (String) approvedPeer.get(server); approvedPeer.set(server, "192.168.99.99");
            request(base + "subtitle-prepare", prepareBody, 403); approvedPeer.set(server, preparePeer);
            require(subtitleFetches.get() == 0, "Rejected local preparation fetched a subtitle");
            long prepareStateCount = server.status().getJSONObject("diagnostics").getLong("stateAccepted");
            JSONObject preparedLocal = request(base + "subtitle-prepare", prepareBody, 200);
            require(preparedLocal.length() == 3 && preparedLocal.getInt("index") == 0 && SOURCE.equals(preparedLocal.getString("source"))
                    && preparedLocal.getString("url").startsWith(base + "subtitles/") && subtitleFetches.get() == 1, "Local subtitle preparation did not return its declared source-bound slot");
            require(!server.status().has("pendingCommand") && server.status().getJSONObject("lastCommand").getLong("id") == offCommand.getLong("id")
                    && server.status().getJSONObject("diagnostics").getLong("stateAccepted") == prepareStateCount && server.status().getJSONObject("playback").getBoolean("active"), "Preparation enqueued a command, fabricated state or interrupted video");
            mediaRequest(preparedLocal.getString("url"), "GET", null, origin, 200);
            rejects("SOURCE_CHANGED", () -> target.command("subtitle", object("index", 0, "enabled", true, "expectedSourceUrl", "https://media.example/other.m3u8")));
            rejects("INVALID_INPUT", () -> target.command("subtitle", object("index", 0, "enabled", true, "url", "http://127.0.0.1/private")));
            require(subtitleFetches.get() == 1, "Invalid caption request fetched a body");
            Future<JSONObject> selection = executor.submit(() -> target.command("subtitle", object("index", 0, "enabled", true, "expectedSourceUrl", SOURCE)));
            JSONObject captionCommand = awaitCommand(base, session, firstId); String captionUrl = captionCommand.getJSONObject("params").getString("url");
            require("subtitle".equals(captionCommand.getString("method")) && captionUrl.startsWith(base + "subtitles/")
                    && captionUrl.substring((base + "subtitles/").length()).matches("[a-f0-9]{64}\\.srt") && subtitleFetches.get() == 1, "Caption selection was not an owned normalized slot");
            MediaResponse captionBody = mediaRequest(captionUrl, "GET", null, origin, 200);
            require(captionBody.type.equals("application/x-subrip; charset=utf-8") && new String(captionBody.body, StandardCharsets.UTF_8).contains("Olá TV")
                    && new String(captionBody.body, StandardCharsets.UTF_8).contains("00:00:01,000 --> 00:00:03,000"), "TV caption bytes were not normalized UTF-8 SRT");
            require(mediaRequest(captionUrl, "HEAD", null, null, 200).body.length == 0, "Caption HEAD sent a body");
            mediaRequest(captionUrl, "GET", null, "https://attacker.example", 403); mediaRequest(captionUrl + "?url=https://elsewhere.example", "GET", null, null, 400);
            String approvedCaptionPeer = (String) approvedPeer.get(server); approvedPeer.set(server, "192.168.99.99");
            mediaRequest(captionUrl, "GET", null, null, 403); approvedPeer.set(server, approvedCaptionPeer);
            rejects("INVALID_MEDIA", () -> MsxServer.validateCommand("open", object("source", captionUrl)));
            JSONObject selectedState = state(session, true);
            selectedState.getJSONObject("playback").put("tracks", new JSONArray().put(object("index", 0, "language", "pt-BR", "label", "Português")))
                    .put("selectedIndex", 0).put("subtitlesEnabled", true).put("subtitleError", JSONObject.NULL);
            request(base + "state", selectedState, 200); request(base + "ack", object("client", CLIENT, "sessionId", session, "id", captionCommand.getLong("id"), "ok", true), 200);
            require(selection.get(3, TimeUnit.SECONDS).getJSONObject("playback").getInt("selectedIndex") == 0 && !server.status().has("pendingCommand"), "Caption selection lost its real receipt");
            rejects("SUBTITLE_FAILED", () -> target.command("subtitle", object("index", 1, "enabled", true, "expectedSourceUrl", SOURCE)));
            require(server.status().getJSONObject("playback").getBoolean("active") && server.status().getJSONObject("playback").getInt("selectedIndex") == 0
                    && !server.status().has("pendingCommand") && subtitleFetches.get() == 2, "Failed caption reopened video or replaced prior selection");
            authorized.set(false);
            Future<JSONObject> browsingSelection = executor.submit(() -> target.command("subtitle", object("index", 0, "enabled", true, "expectedSourceUrl", SOURCE)));
            JSONObject browsingCaption = awaitCommand(base, session, firstId);
            require(captionUrl.equals(browsingCaption.getJSONObject("params").getString("url")) && subtitleFetches.get() == 2, "Browsing revoked accepted-video captions or bypassed the normalized cache");
            mediaRequest(captionUrl, "GET", null, null, 200);
            request(base + "state", selectedState, 200); request(base + "ack", object("client", CLIENT, "sessionId", session, "id", browsingCaption.getLong("id"), "ok", true), 200);
            require(browsingSelection.get(3, TimeUnit.SECONDS).getBoolean("accepted"), "Accepted-video subtitle control still required an expired Open token");
            Future<JSONObject> sample = executor.submit(target::sample);
            JSONObject sampleCommand = awaitCommand(base, session, firstId), sampleParams = sampleCommand.getJSONObject("params");
            mediaRequest(captionUrl, "GET", null, null, 403);
            request(base + "subtitle-prepare", prepareBody, 409);
            String expectedSample = bundledSample ? diagnosticSampleUrl : "https://media.w3.org/2010/05/sintel/trailer.mp4";
            require("open".equals(sampleCommand.getString("method")) && expectedSample.equals(sampleParams.getString("source"))
                    && "video/mp4".equals(sampleParams.getString("mimeType")) && !sampleParams.getBoolean("autoSubtitles") && sampleParams.getJSONArray("subtitles").length() == 0
                    && !sampleParams.has("sourceToken"), "Sample altered fixed source or leaked native authorization");
            rejects("SOURCE_CHANGED", () -> target.command("open", object("source", SOURCE, "sourceToken", "fixture-current")));
            JSONObject sampleState = state(session, true); sampleState.getJSONObject("playback").put("source", sampleParams.getString("source"));
            request(base + "state", sampleState, 200);
            request(base + "ack", object("client", CLIENT, "sessionId", session, "id", sampleCommand.getLong("id"), "ok", true), 200);
            require(sample.get(3, TimeUnit.SECONDS).getBoolean("accepted"), "Sample succeeded before receiver receipt or failed independent authorization");
            authorized.set(true); request(base + "state", state(session, true), 200);
            if (bundledSample) {
                rejects("SOURCE_CHANGED", () -> target.command("open", object("source", diagnosticSampleUrl, "sourceToken", "fixture-current")));
                rejects("INVALID_MEDIA", () -> MsxServer.validateCommand("open", object("source", diagnosticSampleUrl)));
                JSONObject staticOwned = state(firstSession, true); staticOwned.getJSONObject("playback").put("source", diagnosticSampleUrl);
                rejects("INVALID_MEDIA", () -> MsxServer.validatePlayback(staticOwned.getJSONObject("playback")));
                for (String unowned : new String[] {base + "arbitrary-private.mp4", diagnosticSampleUrl + "?other=1", diagnosticSampleUrl + "#other", origin + "/invalid_capability_not_current/diagnostic-sintel.mp4"}) {
                    rejects("INVALID_MEDIA", () -> target.command("open", object("source", unowned, "sourceToken", "fixture-current")));
                    JSONObject unownedState = state(session, true); unownedState.getJSONObject("playback").put("source", unowned);
                    request(base + "state", unownedState, 400);
                }
            }
            rejects("CONTROL_UNAVAILABLE", () -> target.command("ping", new JSONObject()));
            rejects("INVALID_INPUT", () -> target.command("ping", object("extra", true)));
            rejects("INVALID_INPUT", () -> target.command("reload", object("force", true)));
            rejects("INVALID_INPUT", () -> target.command("set-label", object("label", "Unapproved text")));
            rejects("INVALID_INPUT", () -> target.command("set-label", object("label", "LUMA debug channel ready", "extra", true)));
            request(base + "state", debugState(session, true), 200);
            for (String debugMethod : new String[] {"ping", "set-label"}) {
                JSONObject debugParams = "set-label".equals(debugMethod) ? object("label", "LUMA debug channel ready") : new JSONObject();
                Future<JSONObject> debugCommand = executor.submit(() -> target.command(debugMethod, debugParams));
                JSONObject deliveredDebug = awaitCommand(base, session, firstId);
                require(debugMethod.equals(deliveredDebug.getString("method")) && deliveredDebug.getJSONObject("params").length() == debugParams.length(), "Fixed debug command changed method or parameters");
                require(!debugCommand.isDone() && server.status().getJSONObject("playback").getBoolean("active"), "Debug control claimed success without ACK or cleared media");
                request(base + "ack", object("client", CLIENT, "sessionId", session, "id", deliveredDebug.getLong("id"), "ok", true), 200);
                require(debugCommand.get(3, TimeUnit.SECONDS).getBoolean("accepted") && SOURCE.equals(server.status().getJSONObject("playback").getString("source")), "Debug receipt changed the playing source");
            }
            rejects("RELOAD_BLOCKED", () -> target.command("reload", new JSONObject()));
            request(base + "state", debugState(session, false), 200);
            Future<JSONObject> blockingPing = executor.submit(() -> target.command("ping", new JSONObject()));
            JSONObject pendingPing = awaitCommand(base, session, firstId);
            rejects("RELOAD_BLOCKED", () -> target.command("reload", new JSONObject()));
            request(base + "ack", object("client", CLIENT, "sessionId", session, "id", pendingPing.getLong("id"), "ok", true), 200);
            blockingPing.get(3, TimeUnit.SECONDS);
            Future<JSONObject> idleReload = executor.submit(() -> target.command("reload", new JSONObject()));
            JSONObject reloadCommand = awaitCommand(base, session, firstId);
            require("reload".equals(reloadCommand.getString("method")) && reloadCommand.getJSONObject("params").length() == 0 && !idleReload.isDone(), "Idle reload did not require a fixed command and receipt");
            request(base + "ack", object("client", CLIENT, "sessionId", session, "id", reloadCommand.getLong("id"), "ok", true), 200);
            require(idleReload.get(3, TimeUnit.SECONDS).getBoolean("accepted"), "Idle reload receipt was rejected");
            request(base + "state", state(session, true), 200);
            passed(runner, ++checks, "TV owns public source, each command delivered once and observed state precedes acknowledged result");

            rejects("INVALID_MEDIA", () -> target.command("open", object("source", "http://192.168.1.1/private", "sourceToken", "fixture-current")));
            rejects("INVALID_INPUT", () -> target.command("volume", object("value", 101)));
            rejects("SOURCE_CHANGED", () -> target.command("open", object("source", SOURCE, "sourceToken", "stale")));
            rejects("SOURCE_CHANGED", () -> target.command("pause", object("expectedSourceUrl", "https://media.example/other.m3u8")));
            long beforeRejectedState = server.status().getJSONObject("diagnostics").getLong("stateAccepted");
            JSONObject unsafeState = state(session, true); unsafeState.getJSONObject("playback").put("source", "http://127.0.0.1/private"); request(base + "state", unsafeState, 400);
            require(server.status().getJSONObject("diagnostics").getLong("stateAccepted") == beforeRejectedState, "Rejected media state incremented accepted state count");
            JSONObject errorState = state(session, true); errorState.getJSONObject("playback").put("error", "MEDIA_FAILED"); request(base + "state", errorState, 200);
            require("MEDIA_FAILED".equals(server.status().getJSONObject("playback").getString("error")), "Fixed playback error was lost");
            request(base + "state", state(session, true), 200);
            require(server.status().getJSONObject("playback").isNull("error"), "Healthy JSON-null state did not clear error");
            for (Object invalidError : new Object[] {"null", 1, true, "https://private.example/error"}) {
                JSONObject invalidState = state(session, true); invalidState.getJSONObject("playback").put("error", invalidError); request(base + "state", invalidState, 400);
            }
            require(SOURCE.equals(server.status().getJSONObject("playback").getString("source")), "Rejected state replaced trusted state");
            require(server.status().getJSONObject("diagnostics").getLong("stateAccepted") == beforeRejectedState + 2, "Invalid playback reports advanced accepted state diagnostics");
            long beforeMediaDiagnostics = server.status().getJSONObject("diagnostics").getLong("stateAccepted");
            JSONObject detailedState = state(session, true);
            detailedState.getJSONObject("playback").put("mediaDiagnostics", object("errorCode", 4, "networkState", 3, "readyState", 4,
                    "message", "https://private.example/must-not-survive"));
            request(base + "state", detailedState, 200);
            JSONObject diagnosticState = server.status().getJSONObject("playback").getJSONObject("mediaDiagnostics");
            require(diagnosticState.length() == 3 && diagnosticState.getInt("errorCode") == 4 && diagnosticState.getInt("networkState") == 3
                    && diagnosticState.getInt("readyState") == 4, "Finite media diagnostics were lost or extra fields survived");
            JSONObject unknownState = state(session, true);
            unknownState.getJSONObject("playback").put("mediaDiagnostics", object("errorCode", JSONObject.NULL, "networkState", JSONObject.NULL, "readyState", JSONObject.NULL));
            request(base + "state", unknownState, 200);
            diagnosticState = server.status().getJSONObject("playback").getJSONObject("mediaDiagnostics");
            require(diagnosticState.isNull("errorCode") && diagnosticState.isNull("networkState") && diagnosticState.isNull("readyState"), "Unavailable diagnostics became fabricated values");
            for (Object invalidDiagnostics : new Object[] {JSONObject.NULL, "invalid", true, new JSONArray(), object("errorCode", 0, "networkState", 0)}) {
                JSONObject invalidState = state(session, true); invalidState.getJSONObject("playback").put("mediaDiagnostics", invalidDiagnostics); request(base + "state", invalidState, 400);
            }
            String[] diagnosticNames = {"errorCode", "networkState", "readyState"}; int[] diagnosticMaxima = {4, 3, 4};
            for (int i = 0; i < diagnosticNames.length; i++) {
                for (Object invalidValue : new Object[] {-1, diagnosticMaxima[i] + 1, 0.5, "1", true, new JSONObject()}) {
                    JSONObject invalidState = state(session, true), invalidDiagnostics = object("errorCode", 0, "networkState", 0, "readyState", 0);
                    invalidDiagnostics.put(diagnosticNames[i], invalidValue); invalidState.getJSONObject("playback").put("mediaDiagnostics", invalidDiagnostics); request(base + "state", invalidState, 400);
                }
            }
            require(server.status().getJSONObject("diagnostics").getLong("stateAccepted") == beforeMediaDiagnostics + 2
                    && server.status().getJSONObject("playback").getJSONObject("mediaDiagnostics").isNull("errorCode"), "Invalid diagnostics replaced accepted state or advanced its count");
            request(base + "state", state(session, true), 200);
            require(!server.status().getJSONObject("playback").has("mediaDiagnostics"), "Legacy state retained stale media diagnostics");
            JSONObject debugReport = debugState(session, true);
            debugReport.getJSONObject("playback").getJSONObject("receiverDebug").put("extra", "must-not-survive");
            request(base + "state", debugReport, 200);
            JSONObject savedDebug = server.status().getJSONObject("playback").getJSONObject("receiverDebug");
            require(savedDebug.length() == 5 && savedDebug.isNull("revision") && savedDebug.getDouble("uptimeSeconds") == 12.5, "Receiver debug state was lost, coerced or retained extras");
            String fixtureRevision = new String(new char[64]).replace('\0', 'a');
            debugReport.getJSONObject("playback").getJSONObject("receiverDebug").put("revision", fixtureRevision).put("uptimeSeconds", 315360000).put("pingCount", 1000000000);
            request(base + "state", debugReport, 200);
            require(fixtureRevision.equals(server.status().getJSONObject("playback").getJSONObject("receiverDebug").getString("revision")), "Valid receiver revision was lost");
            long beforeInvalidDebug = server.status().getJSONObject("diagnostics").getLong("stateAccepted");
            String[] debugKeys = {"revision", "uptimeSeconds", "pingCount", "remoteReload", "remoteCommands"};
            Object[][] invalidDebugValues = {{"not-a-hash", 1, true}, {-1, 315360001, "1", JSONObject.NULL}, {-1, 1000000001L, 0.5, "1", JSONObject.NULL}, {1, "true", JSONObject.NULL}, {1, "true", JSONObject.NULL}};
            for (int i = 0; i < debugKeys.length; i++) {
                for (Object invalidValue : invalidDebugValues[i]) {
                    JSONObject invalidState = debugState(session, true);
                    invalidState.getJSONObject("playback").getJSONObject("receiverDebug").put(debugKeys[i], invalidValue);
                    request(base + "state", invalidState, 400);
                }
                JSONObject missingDebug = debugState(session, true); missingDebug.getJSONObject("playback").getJSONObject("receiverDebug").remove(debugKeys[i]); request(base + "state", missingDebug, 400);
            }
            require(server.status().getJSONObject("diagnostics").getLong("stateAccepted") == beforeInvalidDebug && fixtureRevision.equals(server.status().getJSONObject("playback").getJSONObject("receiverDebug").getString("revision")), "Rejected debug report changed trusted state");
            int eventIndex = 0;
            for (String event : new String[] {"none", "key-enter", "key-arrow", "key-back", "key-media", "pointer-background", "pointer-arrow"}) {
                JSONObject inputState = debugState(session, true); long count = eventIndex++ == 0 ? 0 : 1000000000L;
                inputState.getJSONObject("playback").getJSONObject("receiverDebug").put("inputCount", count).put("lastInput", event).put("rawKey", "must-not-survive");
                request(base + "state", inputState, 200);
                JSONObject acceptedInput = server.status().getJSONObject("playback").getJSONObject("receiverDebug");
                require(acceptedInput.length() == 7 && acceptedInput.getLong("inputCount") == count && event.equals(acceptedInput.getString("lastInput")), "Bounded input class/count was lost or retained raw fields");
            }
            for (String optionalKey : new String[] {"inputCount", "lastInput"}) {
                JSONObject partialInput = debugState(session, true);
                partialInput.getJSONObject("playback").getJSONObject("receiverDebug").put(optionalKey, "inputCount".equals(optionalKey) ? 0 : "none");
                request(base + "state", partialInput, 200);
                require(server.status().getJSONObject("playback").getJSONObject("receiverDebug").length() == 6, "Optional input telemetry required or fabricated its sibling field");
            }
            long beforeInvalidInput = server.status().getJSONObject("diagnostics").getLong("stateAccepted");
            for (String property : new String[] {"pointerLockSupported", "pointerLocked"}) {
                for (boolean value : new boolean[] {false, true}) {
                    JSONObject pointerState = debugState(session, true);
                    pointerState.getJSONObject("playback").getJSONObject("receiverDebug").put(property, value);
                    request(base + "state", pointerState, 200);
                    require(server.status().getJSONObject("playback").getJSONObject("receiverDebug").getBoolean(property) == value, "Pointer capability was coerced or lost");
                }
                for (Object value : new Object[] {"true", 1, JSONObject.NULL, new JSONObject()}) {
                    JSONObject pointerState = debugState(session, true);
                    pointerState.getJSONObject("playback").getJSONObject("receiverDebug").put(property, value);
                    request(base + "state", pointerState, 400);
                }
            }
            for (String error : new String[] {"none", "not-allowed", "unsupported", "event-error", "other"}) {
                JSONObject pointerState = debugState(session, true);
                long count = "none".equals(error) ? 0 : 1000000000L;
                pointerState.getJSONObject("playback").getJSONObject("receiverDebug").put("pointerMoveCount", count).put("pointerLockError", error).put("rawPointer", "must-not-survive");
                request(base + "state", pointerState, 200);
                JSONObject acceptedPointer = server.status().getJSONObject("playback").getJSONObject("receiverDebug");
                require(acceptedPointer.length() == 7 && acceptedPointer.getLong("pointerMoveCount") == count && error.equals(acceptedPointer.getString("pointerLockError")), "Pointer diagnostic lost bounded values or retained raw data");
            }
            for (String optionalKey : new String[] {"pointerMoveCount", "pointerLockError"}) {
                JSONObject pointerState = debugState(session, true);
                pointerState.getJSONObject("playback").getJSONObject("receiverDebug").put(optionalKey, "pointerMoveCount".equals(optionalKey) ? 0 : "none");
                request(base + "state", pointerState, 200);
                require(server.status().getJSONObject("playback").getJSONObject("receiverDebug").length() == 6, "Pointer diagnostic fabricated its absent sibling");
            }
            beforeInvalidInput = server.status().getJSONObject("diagnostics").getLong("stateAccepted");
            for (Object invalidCount : new Object[] {-1, 1000000001L, 0.5, "1", true, JSONObject.NULL, new JSONObject()}) {
                for (String field : new String[] {"inputCount", "pointerMoveCount"}) {
                    JSONObject invalidInput = debugState(session, true); invalidInput.getJSONObject("playback").getJSONObject("receiverDebug").put(field, invalidCount); request(base + "state", invalidInput, 400);
                }
            }
            for (Object invalidEvent : new Object[] {"Enter", "key-a", "https://private.example/key", "key-enter\nsecret", 1, true, JSONObject.NULL, new JSONObject()}) {
                JSONObject invalidInput = debugState(session, true); invalidInput.getJSONObject("playback").getJSONObject("receiverDebug").put("lastInput", invalidEvent); request(base + "state", invalidInput, 400);
            }
            for (Object invalidError : new Object[] {"NotAllowedError", "none\nprivate", "https://private.example", 1, true, JSONObject.NULL, new JSONObject()}) {
                JSONObject invalidInput = debugState(session, true); invalidInput.getJSONObject("playback").getJSONObject("receiverDebug").put("pointerLockError", invalidError); request(base + "state", invalidInput, 400);
            }
            require(server.status().getJSONObject("diagnostics").getLong("stateAccepted") == beforeInvalidInput, "Invalid input telemetry advanced accepted state");
            request(base + "state", debugState(session, true), 200);
            require(!server.status().getJSONObject("playback").getJSONObject("receiverDebug").has("inputCount")
                    && !server.status().getJSONObject("playback").getJSONObject("receiverDebug").has("lastInput")
                    && !server.status().getJSONObject("playback").getJSONObject("receiverDebug").has("pointerMoveCount")
                    && !server.status().getJSONObject("playback").getJSONObject("receiverDebug").has("pointerLockError"), "Legacy debug state fabricated stale input telemetry");
            Object assetRevision = server.status().getJSONObject("diagnostics").get("assetRevision");
            require(assetRevision == JSONObject.NULL || assetRevision instanceof String && ((String) assetRevision).matches("[0-9a-f]{64}"), "Asset revision was missing or malformed");
            request(base + "state", state(session, true), 200);
            require(!server.status().getJSONObject("playback").has("receiverDebug"), "Legacy state retained stale debug capabilities");
            passed(runner, ++checks, "local media, invalid ranges, stale source controls and invalid playback reports fail closed");

            Future<JSONObject> canceled = executor.submit(() -> target.command("open", open));
            for (int i = 0; i < 40 && !server.status().has("pendingCommand"); i++) Thread.sleep(25);
            authorized.set(false); require(request(pollUrl(base, session, firstId), null, 200).getJSONArray("commands").length() == 0, "Canceled media delivered");
            failedFuture("SOURCE_CHANGED", canceled); authorized.set(true);
            passed(runner, ++checks, "capture authorization checked again immediately before queued media delivery");

            Future<JSONObject> invalidated = executor.submit(() -> target.command("pause", new JSONObject()));
            JSONObject oldCommand = awaitCommand(base, session, firstId);
            JSONObject beforeReloadProtocol = server.status().getJSONObject("diagnostics");
            session = request(base + "hello?client=" + CLIENT, null, 200).getString("sessionId");
            require(!firstSession.equals(session), "Reload reused session");
            JSONObject reloadProtocol = server.status().getJSONObject("diagnostics");
            require(reloadProtocol.getLong("helloAccepted") == beforeReloadProtocol.getLong("helloAccepted") + 1
                    && reloadProtocol.getLong("stateAccepted") == beforeReloadProtocol.getLong("stateAccepted") && reloadProtocol.getLong("lastStateMsAgo") == -1
                    && reloadProtocol.getBoolean("sessionPresent") && reloadProtocol.getBoolean("sessionApproved") && !server.status().getBoolean("connected"), "New hello mistook historical state counts for a current connection");
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

            String originalPeer = new URL(origin).getHost(), movedPeer = "192.168.50.9";
            rejects("MSX_PAIRING", () -> target.rebindApprovedPeer("8.8.8.8"));
            server.rebindApprovedPeer(movedPeer);
            require(movedPeer.equals(server.status().getString("host")) && server.hasSavedReceiver() && !server.status().getBoolean("connected"), "Matter peer rebind lost approval or retained a stale session");
            int stablePort = server.status().getInt("port"); server.close(); server = new MsxServer(runner.getTargetContext());
            require(movedPeer.equals(server.status().getString("host")), "Matter peer rebind did not persist");
            server.rebindApprovedPeer(originalPeer);
            require(server.hasSavedReceiver() && origin.equals(server.start().getString("startupUrl")) && server.status().getInt("port") == stablePort, "Approved receiver or startup address did not persist");
            require(home.equals(request(origin + "/msx/start.json", null, 200).getString("parameter").substring("content:".length())), "Persistent token changed");
            server.rebindApprovedPeer(movedPeer);
            require(rawStatus(origin, "GET /tv/connect.html HTTP/1.1\r\nHost: " + new URL(origin).getAuthority() + "\r\nConnection: close\r\n\r\n") == 302, "Connection-peer migration did not redirect");
            require(originalPeer.equals(server.status().getString("host")) && server.hasSavedReceiver(), "Actual connection peer did not replace stale Matter address");
            server.reset(); server.start(); request(home, null, 404);
            mediaRequest(diagnosticSampleUrl, "GET", "bytes=0-0", null, 404);
            require(!server.hasSavedReceiver(), "Reset retained approved receiver");
            String freshHome = request(origin + "/msx/start.json", null, 200).getString("parameter").substring("content:".length());
            String retainedBase = freshHome.substring(0, freshHome.length() - "home.json".length());
            String pendingSession = request(retainedBase + "hello?client=" + CLIENT, null, 200).getString("sessionId");
            server.close(); server = new MsxServer(runner.getTargetContext()); server.start();
            require(!server.hasSavedReceiver() && peer.get(server) == null, "Transient pending peer persisted across process restart");
            request(pollUrl(retainedBase, pendingSession, 0), null, 403);
            JSONObject restoredHello = request(retainedBase + "hello?client=" + CLIENT, null, 200);
            require(!restoredHello.getBoolean("approved") && server.status().getBoolean("awaitingApproval") && !server.status().getBoolean("connected"), "Retained page could not reconnect safely after process restart");
            request(retainedBase + "state", state(restoredHello.getString("sessionId"), false), 403);
            server.confirmPairing(new URL(origin).getHost()); request(retainedBase + "state", state(restoredHello.getString("sessionId"), false), 200);
            require(server.status().getBoolean("connected"), "Reconnected retained page still failed after explicit approval");
            passed(runner, ++checks, "approved pairing and stable startup survive restart; explicit reset revokes old capability");
            result.putInt("passed", checks); result.putInt("failures", 0); runner.finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putInt("passed", checks); result.putInt("failures", 1); result.putString("failure", failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage())); runner.finish(Activity.RESULT_CANCELED, result);
        } finally { if (server != null) { server.reset(); server.close(); } executor.shutdownNow(); }
    }
}

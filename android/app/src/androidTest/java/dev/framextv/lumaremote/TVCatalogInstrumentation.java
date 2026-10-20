package dev.framextv.lumaremote;

import android.app.Activity;
import android.app.Instrumentation;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Emulator and deterministic fake-resolver tests. No catalog/CDN/TV requests. */
public final class TVCatalogInstrumentation {
    private static final String CLIENT = "catalog_fixture_receiver_1234", SOURCE = "https://media.example/catalog-fixture.m3u8";
    private interface Check { void run() throws Exception; }
    private final Instrumentation runner;
    private String phase = "setup";
    private int passed;
    private TVCatalogInstrumentation(Instrumentation runner) { this.runner = runner; }
    public static void run(Instrumentation runner) { new TVCatalogInstrumentation(runner).execute(); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static JSONObject object(Object... fields) throws Exception { JSONObject value = new JSONObject(); for (int i = 0; i < fields.length; i += 2) value.put((String) fields[i], fields[i + 1]); return value; }
    private static JSONObject selection(String id) throws Exception { return object("kind", "tv", "id", id, "season", 0, "episode", 1, "resume", true); }
    private static JSONObject media(JSONObject selection, double resume) throws Exception {
        return object("url", SOURCE, "title", "Catalog fixture", "contentType", "application/vnd.apple.mpegurl", "tracks", new JSONArray(),
                "content", WatchHistory.contentFromCatalog(selection.optString("kind"), selection.optString("id"), selection.has("season") ? selection.getInt("season") : null,
                        selection.has("episode") ? selection.getInt("episode") : null, "Catalog fixture"), "resumeSeconds", resume);
    }
    private static class Driver implements TVCatalogBridge.Driver {
        final AtomicInteger opens = new AtomicInteger(), seeks = new AtomicInteger(), ready = new AtomicInteger();
        double resume; boolean failOpen, failSeek;
        @Override public JSONObject resolve(JSONObject selection, BooleanSupplier current) throws Exception { return media(selection, resume); }
        @Override public void open(JSONObject media, BooleanSupplier current) throws Exception { opens.incrementAndGet(); if (failOpen) throw new MsxServer.MsxException("MSX_TIMEOUT"); }
        @Override public void seek(JSONObject media, double seconds, BooleanSupplier current) throws Exception { seeks.incrementAndGet(); if (failSeek) throw new Exception("Not exposed"); }
        @Override public void ready(JSONObject media, boolean resumed, BooleanSupplier current) { ready.incrementAndGet(); }
    }
    private static JSONObject await(TVCatalogBridge bridge, String job, String expected) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3); JSONObject state;
        do { state = bridge.status(job); if (expected.equals(state.optString("state"))) return state; Thread.sleep(10); } while (System.nanoTime() < until);
        throw new AssertionError("Catalog job did not reach " + expected + ": " + state.optString("state"));
    }
    private void check(String name, Check test) throws Exception {
        phase = name; test.run(); Bundle status = new Bundle(); status.putString("test", name); status.putInt("current", ++passed); runner.sendStatus(0, status);
    }
    private void execute() {
        Bundle result = new Bundle();
        try {
            check("typed selections reject URL input, coercion and out-of-range catalog keys", () -> {
                JSONObject typed = TVCatalogBridge.parameters("play", selection("00042"));
                require("42".equals(typed.getString("id")) && typed.getInt("season") == 0 && typed.getBoolean("resume"), "Typed identity/default changed");
                require(TVCatalogBridge.parameters("history", new JSONObject()).length() == 0, "History acquired caller selection");
                for (JSONObject invalid : new JSONObject[] {selection("42").put("url", SOURCE), selection("42").put("id", 42), selection("42").put("episode", 1.5),
                        selection("42").put("season", -1), selection("42").put("resume", "true"), object("kind", "movie", "id", "42", "season", 0)}) {
                    try { TVCatalogBridge.parameters("play", invalid); throw new AssertionError("Invalid typed selection accepted"); } catch (MsxServer.LibraryException expected) { require("CATALOG_INVALID_INPUT".equals(expected.code), "Wrong validation code"); }
                }
                try { TVCatalogBridge.parameters("history", object("source", SOURCE)); throw new AssertionError("History accepted a source"); } catch (MsxServer.LibraryException expected) { }
                require("CATALOG_UNAVAILABLE".equals(new MsxServer.LibraryException("PRIVATE_URL_AND_MESSAGE").code), "Unrecognized native error leaked");
            });
            check("play returns before resolution and revoked sessions never open media", () -> {
                try (TVCatalogBridge bridge = new TVCatalogBridge()) {
                    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); AtomicBoolean session = new AtomicBoolean(true);
                    Driver driver = new Driver() { @Override public JSONObject resolve(JSONObject value, BooleanSupplier current) throws Exception { entered.countDown(); release.await(3, TimeUnit.SECONDS); return super.resolve(value, current); } };
                    JSONObject receipt = bridge.play(selection("42"), session::get, driver); String job = receipt.getString("jobId");
                    require(receipt.getBoolean("accepted") && "resolving".equals(receipt.getString("state")) && job.matches("[a-f0-9]{32}") && entered.await(1, TimeUnit.SECONDS), "Play was not an immediate tracked job");
                    session.set(false); release.countDown(); await(bridge, job, "cancelled"); require(driver.opens.get() == 0 && driver.ready.get() == 0, "Revoked session reached Open/history");
                }
            });
            check("a newer selection cancels a stalled resolver without sending the old Open", () -> {
                try (TVCatalogBridge bridge = new TVCatalogBridge()) {
                    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); Driver old = new Driver() {
                        @Override public JSONObject resolve(JSONObject value, BooleanSupplier current) throws Exception {
                            entered.countDown(); while (release.getCount() > 0) try { release.await(100, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
                            return super.resolve(value, current);
                        }
                    };
                    String first = bridge.play(selection("41"), () -> true, old).getString("jobId"); require(entered.await(1, TimeUnit.SECONDS), "Old resolver did not begin");
                    Driver latest = new Driver(); String second = bridge.play(selection("42"), () -> true, latest).getString("jobId");
                    await(bridge, second, "ready"); release.countDown(); await(bridge, first, "cancelled"); Thread.sleep(30);
                    require(old.opens.get() == 0 && old.ready.get() == 0 && latest.opens.get() == 1 && latest.ready.get() == 1, "Superseded media opened or history was bound");
                }
            });
            check("Open unknown outcomes are not retried and failed resumes preserve history", () -> {
                try (TVCatalogBridge bridge = new TVCatalogBridge()) {
                    Driver failed = new Driver(); failed.failOpen = true;
                    String first = bridge.play(selection("42"), () -> true, failed).getString("jobId"); require("MSX_TIMEOUT".equals(await(bridge, first, "error").getString("errorCode")), "Unknown Open outcome lost its code");
                    for (int i = 0; i < 5; i++) bridge.status(first);
                    require(failed.opens.get() == 1 && failed.ready.get() == 0, "Status retried Open or bound failed playback");
                    Driver resume = new Driver(); resume.resume = 300; resume.failSeek = true;
                    String second = bridge.play(selection("42"), () -> true, resume).getString("jobId"); require("RESUME_FAILED".equals(await(bridge, second, "error").getString("errorCode")), "Failed seek was reported ready");
                    require(resume.opens.get() == 1 && resume.seeks.get() == 1 && resume.ready.get() == 0, "Failed resume overwrote saved progress");
                    Driver restart = new Driver(); restart.resume = 300;
                    String third = bridge.play(selection("42").put("resume", false), () -> true, restart).getString("jobId");
                    require(await(bridge, third, "ready").getDouble("resumeSeconds") == 0 && restart.seeks.get() == 0 && restart.ready.get() == 1, "Start from beginning secretly resumed");
                    Driver wrong = new Driver() { @Override public JSONObject resolve(JSONObject value, BooleanSupplier current) throws Exception { return media(selection("99"), 0); } };
                    String fourth = bridge.play(selection("42"), () -> true, wrong).getString("jobId");
                    require("CATALOG_RESPONSE".equals(await(bridge, fourth, "error").getString("errorCode")) && wrong.opens.get() == 0, "Resolver identity mismatch reached Open");
                }
            });
            check("approved capability route authorizes only native media, one Open and verified resume", this::serverRoute);
            result.putInt("tests", passed); result.putInt("passed", passed); result.putInt("failures", 0);
            runner.finish(Activity.RESULT_OK, result);
        } catch (Throwable failed) {
            result.putInt("passed", passed); result.putInt("failures", 1);
            result.putString("failure", "TVCatalog failed at " + phase + ": " + failed.getClass().getSimpleName() + " " + failed.getMessage());
            runner.finish(Activity.RESULT_CANCELED, result);
        }
    }
    private static JSONObject request(String url, JSONObject body, int expected) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);
        try {
            connection.setConnectTimeout(3000); connection.setReadTimeout(3000); connection.setInstanceFollowRedirects(false);
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8); connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json"); connection.setFixedLengthStreamingMode(bytes.length); connection.getOutputStream().write(bytes);
            }
            int status = connection.getResponseCode(); require(status == expected, "HTTP " + status + " expected " + expected);
            InputStream stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            if (stream == null) return new JSONObject();
            try (InputStream input = stream; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int count; while ((count = input.read(buffer)) >= 0) { require(bytes.size() + count < 2 * 1024 * 1024, "Fixture response exceeded bound"); bytes.write(buffer, 0, count); }
                return new JSONObject(bytes.toString("UTF-8"));
            }
        } finally { connection.disconnect(); }
    }
    private static int rawStatus(String origin, String path, JSONObject body, String host, String suppliedOrigin) throws Exception {
        URL url = new URL(origin); byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket(url.getHost(), url.getPort())) {
            socket.setSoTimeout(3000); String header = "POST " + path + " HTTP/1.1\r\nHost: " + host + "\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length + "\r\n"
                    + (suppliedOrigin == null ? "" : "Origin: " + suppliedOrigin + "\r\n") + "\r\n";
            socket.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().write(bytes); socket.getOutputStream().flush();
            ByteArrayOutputStream line = new ByteArrayOutputStream(); int next; while ((next = socket.getInputStream().read()) >= 0 && next != '\n' && line.size() < 100) line.write(next);
            return Integer.parseInt(line.toString("US-ASCII").split(" ")[1]);
        }
    }
    private static JSONObject envelope(String session, String action, JSONObject params) throws Exception { return object("client", CLIENT, "sessionId", session, "action", action, "params", params); }
    private static JSONObject state(String session, boolean active, double position) throws Exception {
        return object("client", CLIENT, "sessionId", session, "playback", object("active", active, "source", active ? SOURCE : "", "title", "Catalog fixture", "paused", false,
                "positionSeconds", position, "durationSeconds", active ? 1000 : 0, "canSeek", active, "tracks", new JSONArray(), "selectedIndex", -1, "subtitlesEnabled", false));
    }
    private static JSONObject poll(String base, String session, long after) throws Exception { return request(base + "poll?client=" + CLIENT + "&sessionId=" + session + "&after=" + after, null, 200); }
    private static JSONObject command(String base, String session, long after) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < until) { JSONArray values = poll(base, session, after).getJSONArray("commands"); if (values.length() > 0) return values.getJSONObject(0); Thread.sleep(10); }
        throw new AssertionError("Native catalog command not delivered");
    }
    private static JSONObject job(String base, String session, String id, String expected) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3); JSONObject value;
        do { value = request(base + "library", envelope(session, "playStatus", object("jobId", id)), 200); if (expected.equals(value.optString("state"))) return value; Thread.sleep(10); } while (System.nanoTime() < until);
        throw new AssertionError("HTTP job did not reach " + expected + ": " + value.optString("state"));
    }
    private void serverRoute() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (MsxServer server = new MsxServer(runner.getTargetContext())) {
            server.reset(); AtomicInteger resolutions = new AtomicInteger(), ready = new AtomicInteger(); AtomicBoolean sourceGuardCalled = new AtomicBoolean();
            CountDownLatch metadataEntered = new CountDownLatch(1), metadataRelease = new CountDownLatch(1);
            server.setSourceGuard(token -> { sourceGuardCalled.set(true); return false; });
            server.setLibrary(new MsxServer.Library() {
                @Override public JSONObject metadata(String action, JSONObject params) throws MsxServer.LibraryException {
                    try {
                        if ("details".equals(action)) { metadataEntered.countDown(); metadataRelease.await(3, TimeUnit.SECONDS); }
                        return "history".equals(action) ? object("version", 1, "items", new JSONArray(), "storageAvailable", true) : object("items", new JSONArray(), "page", 1);
                    } catch (Exception failed) { throw new MsxServer.LibraryException("CATALOG_UNAVAILABLE"); }
                }
                @Override public JSONObject resolve(JSONObject params, BooleanSupplier current) throws MsxServer.LibraryException {
                    resolutions.incrementAndGet(); try { return media(params, 300); } catch (Exception invalid) { throw new MsxServer.LibraryException("CATALOG_RESPONSE"); }
                }
                @Override public void onReady(JSONObject media, boolean resumed) { if (resumed) ready.incrementAndGet(); }
            });
            String origin = server.start().getString("startupUrl");
            String home = request(origin + "/msx/start.json", null, 200).getString("parameter").substring("content:".length());
            String base = home.substring(0, home.length() - "home.json".length()); String session = request(base + "hello?client=" + CLIENT, null, 200).getString("sessionId");
            JSONObject history = envelope(session, "history", new JSONObject()); request(base + "library", history, 403);
            server.confirmPairing(server.status().getString("pendingHost")); request(base + "state", state(session, false, 0), 200);
            require(request(base + "library", history, 200).getInt("version") == 1, "History metadata was not delegated");
            for (JSONObject invalid : new JSONObject[] {envelope(session, "play", selection("42").put("url", SOURCE)), envelope(session, "resolve", selection("42")), history.put("extra", true)}) request(base + "library", invalid, 400);
            history.remove("extra"); request(base + "library?extra=1", history, 400); request(base + "library", null, 404);
            request(base + "library", envelope("wrong", "history", new JSONObject()), 403);
            request(base + "library", object("client", "another_receiver_12345678", "sessionId", session, "action", "history", "params", new JSONObject()), 403);
            String host = new URL(origin).getAuthority(), path = new URL(base + "library").getPath();
            require(rawStatus(origin, path, history, "attacker.example", null) == 403 && rawStatus(origin, path, history, host, "https://attacker.example") == 403, "Catalog escaped Host/origin guards");
            require(rawStatus(origin, "/wrong_capability/library", history, host, null) == 404, "Wrong catalog capability accepted");
            java.lang.reflect.Field peer = MsxServer.class.getDeclaredField("approvedHost"); peer.setAccessible(true); String admittedPeer = (String) peer.get(server);
            peer.set(server, "192.168.99.99"); request(base + "library", history, 403); peer.set(server, admittedPeer);
            final String stableSession = session;
            Future<JSONObject> details = executor.submit(() -> request(base + "library", envelope(stableSession, "details", object("kind", "tv", "id", "42")), 200));
            require(metadataEntered.await(1, TimeUnit.SECONDS), "Metadata did not begin"); poll(base, session, 0); metadataRelease.countDown(); details.get(3, TimeUnit.SECONDS);
            String id = request(base + "library", envelope(session, "play", selection("42")), 200).getString("jobId");
            JSONObject open = command(base, session, 0); long openId = open.getLong("id");
            require("open".equals(open.getString("method")) && SOURCE.equals(open.getJSONObject("params").getString("source")) && !open.getJSONObject("params").has("sourceToken") && !sourceGuardCalled.get(), "Native grant leaked or depended on caller capture authorization");
            require(poll(base, session, 0).getJSONArray("commands").length() == 0, "Open was redelivered before its ACK");
            request(base + "state", state(session, true, 0), 200); request(base + "ack", object("client", CLIENT, "sessionId", session, "id", openId, "ok", true), 200);
            JSONObject seek = command(base, session, openId); require("seek".equals(seek.getString("method")) && seek.getJSONObject("params").getDouble("seconds") == 300, "Resume was not a separate native seek");
            require(ready.get() == 0, "History bound before resumed state");
            request(base + "state", state(session, true, 300), 200); request(base + "ack", object("client", CLIENT, "sessionId", session, "id", seek.getLong("id"), "ok", true), 200);
            require(job(base, session, id, "ready").getDouble("resumeSeconds") == 300 && ready.get() == 1 && resolutions.get() == 1, "Resume did not complete exactly once");
            require(poll(base, session, seek.getLong("id")).getJSONArray("commands").length() == 0, "Status polling replayed media");
            // New hello invalidates the private job/session grant and old status cannot be read.
            String replacement = request(base + "hello?client=" + CLIENT, null, 200).getString("sessionId");
            request(base + "library", envelope(session, "playStatus", object("jobId", id)), 403);
            request(base + "library", envelope(replacement, "playStatus", object("jobId", id)), 400);
            server.reset(); String restarted = server.start().getString("startupUrl");
            request(restarted + new URL(base + "library").getPath(), envelope(replacement, "history", new JSONObject()), 404);
        } finally { executor.shutdownNow(); }
    }
}

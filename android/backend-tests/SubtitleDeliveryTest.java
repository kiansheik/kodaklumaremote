package dev.framextv.lumaremote;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

/** Host-JVM localhost transport fixtures. Never contacts a household receiver. */
public final class SubtitleDeliveryTest {
    private static final String SOURCE = "https://media.example/video.mp4";
    private static final byte[] SRT = "1\n00:00:01,000 --> 00:00:03,000\nOlá, mundo!\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] VTT = "WEBVTT\n\n00:01.000 --> 00:03.000 align:start\nOlá, mundo!\n".getBytes(StandardCharsets.UTF_8);
    private interface Action { void run() throws Exception; }
    private static int checks;
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void check(String name, Action test) throws Exception { test.run(); checks++; System.out.println("PASS " + name); }
    private static void rejects(String code, Action test) throws Exception {
        try { test.run(); throw new AssertionError("Expected " + code); }
        catch (SubtitleDelivery.DeliveryException error) { require(code.equals(error.code), "Unexpected code " + error.code); }
    }
    private static String valid(String value) throws Exception {
        URI uri = new URI(value);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) throw new Exception("Invalid public source");
        return value;
    }
    private static byte[] request(String address, String method, int expected) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URI(address).toURL().openConnection(java.net.Proxy.NO_PROXY);
        try {
            connection.setConnectTimeout(1500); connection.setReadTimeout(1500); connection.setRequestMethod(method); connection.setInstanceFollowRedirects(false);
            require(connection.getResponseCode() == expected, "Unexpected local delivery status " + connection.getResponseCode());
            if (expected != 200 || method.equals("HEAD")) return new byte[0];
            require(connection.getContentType().equals("application/x-subrip; charset=utf-8"), "Delivery type is not UTF-8 SRT");
            try (InputStream input = connection.getInputStream()) { return input.readAllBytes(); }
        } finally { connection.disconnect(); }
    }
    private static String raw(String address, String headers) throws Exception {
        URI uri = new URI(address);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 1000); socket.setSoTimeout(1500);
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    public static void main(String[] args) throws Exception {
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        java.util.concurrent.ExecutorService upstreamThreads = Executors.newCachedThreadPool(task -> { Thread thread = new Thread(task); thread.setDaemon(true); return thread; });
        upstream.setExecutor(upstreamThreads);
        AtomicInteger hits = new AtomicInteger(); CountDownLatch slowEntered = new CountDownLatch(1), releaseSlow = new CountDownLatch(1);
        CountDownLatch cancelEntered = new CountDownLatch(1), releaseCancel = new CountDownLatch(1);
        upstream.createContext("/", exchange -> {
            hits.incrementAndGet();
            try {
                String path = exchange.getRequestURI().getPath(); byte[] body = SRT;
                require(exchange.getRequestHeaders().getFirst("Authorization") == null && (exchange.getRequestHeaders().getFirst("Cookie") == null || exchange.getRequestHeaders().getFirst("Cookie").isEmpty()), "Credentials were forwarded to subtitle provider");
                if (path.equals("/redirect")) { exchange.getResponseHeaders().add("Location", "/vtt"); exchange.sendResponseHeaders(302, -1); return; }
                if (path.equals("/redirect-private")) { exchange.getResponseHeaders().add("Location", "http://192.168.1.1/subtitle.srt"); exchange.sendResponseHeaders(302, -1); return; }
                if (path.equals("/loop")) { exchange.getResponseHeaders().add("Location", "/loop"); exchange.sendResponseHeaders(302, -1); return; }
                if (path.equals("/auth")) { exchange.sendResponseHeaders(403, -1); return; }
                if (path.equals("/html")) body = "<!doctype html><html>Login required</html>".getBytes(StandardCharsets.UTF_8);
                if (path.equals("/big-srt")) body = ("1\n00:00:01,000 --> 00:00:03,000\n" + "A".repeat(1024 * 1024) + "\n").getBytes(StandardCharsets.UTF_8);
                if (path.equals("/vtt")) body = VTT;
                if (path.equals("/large")) {
                    exchange.sendResponseHeaders(200, 2 * 1024 * 1024 + 1);
                    exchange.getResponseBody().write(0); exchange.getResponseBody().flush(); return;
                }
                if (path.equals("/slow")) { slowEntered.countDown(); try { releaseSlow.await(2, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } }
                if (path.equals("/cancel")) { cancelEntered.countDown(); try { releaseCancel.await(2, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } }
                if (path.equals("/gzip") || path.equals("/gzip-large")) {
                    ByteArrayOutputStream packed = new ByteArrayOutputStream();
                    try (GZIPOutputStream gzip = new GZIPOutputStream(packed)) { gzip.write(path.equals("/gzip") ? SRT : new byte[2 * 1024 * 1024 + 1]); }
                    body = packed.toByteArray(); exchange.getResponseHeaders().add("Content-Encoding", "gzip");
                }
                exchange.getResponseHeaders().add("Content-Type", "text/vtt; charset=utf-8"); exchange.sendResponseHeaders(200, body.length);
                try (OutputStream output = exchange.getResponseBody()) { output.write(body); }
            } catch (Exception ignored) { } finally { exchange.close(); }
        });
        upstream.start(); String origin = "http://127.0.0.1:" + upstream.getAddress().getPort();
        AtomicLong clock = new AtomicLong(10000); AtomicBoolean current = new AtomicBoolean(true);
        try {
        try (SubtitleDelivery delivery = SubtitleDelivery.loopbackFixture(SubtitleDeliveryTest::valid, clock::get, 8000)) {
            check("normalizes mislabeled SRT, preserves original identity and serves only the prepared SRT", () -> {
                SubtitleDelivery.Prepared prepared = delivery.prepare(SOURCE, origin + "/srt", "127.0.0.1", current::get);
                require(prepared.format.equals("srt") && prepared.cueCount == 1 && prepared.url.endsWith("/subtitle.srt"), "Prepared metadata missing");
                require(delivery.resolve(prepared.id, SOURCE, origin + "/srt", "127.0.0.1").url.equals(prepared.url), "Original URL identity was replaced");
                require(!delivery.downloaded(prepared.id), "Prepared file was reported downloaded before a GET");
                request(prepared.url, "HEAD", 200); require(!delivery.downloaded(prepared.id), "HEAD consumed or completed the subtitle");
                byte[] body = request(prepared.url, "GET", 200); require(new String(body, StandardCharsets.UTF_8).contains("Olá, mundo!"), "UTF-8 caption was corrupted");
                for (int wait = 0; wait < 30 && !delivery.downloaded(prepared.id); wait++) Thread.sleep(10);
                require(delivery.downloaded(prepared.id), "Full body delivery was not observed");
                require(java.util.Arrays.equals(body, request(prepared.url, "GET", 200)), "A bounded receiver retry could not retrieve the same bytes");
                rejects("SUBTITLE_UNAVAILABLE", () -> delivery.resolve(prepared.id, "https://media.example/other.mp4", origin + "/srt", "127.0.0.1"));
                rejects("SUBTITLE_UNAVAILABLE", () -> delivery.resolve(prepared.id, SOURCE, origin + "/vtt", "127.0.0.1"));
                rejects("SUBTITLE_UNAVAILABLE", () -> delivery.resolve(prepared.id, SOURCE, origin + "/srt", "127.0.0.2"));
            });
            check("redirects and gzip use body sniffing and bounded decompression", () -> {
                require(delivery.prepare(SOURCE, origin + "/redirect", "127.0.0.1", current::get).format.equals("vtt"), "Redirected WebVTT not converted");
                require(delivery.prepare(SOURCE, origin + "/gzip", "127.0.0.1", current::get).format.equals("srt"), "gzip SRT not decoded");
                rejects("SUBTITLE_TOO_LARGE", () -> delivery.prepare(SOURCE, origin + "/gzip-large", "127.0.0.1", current::get));
                rejects("SUBTITLE_TOO_LARGE", () -> delivery.prepare(SOURCE, origin + "/large", "127.0.0.1", current::get));
                rejects("SUBTITLE_FETCH", () -> delivery.prepare(SOURCE, origin + "/loop", "127.0.0.1", current::get));
                rejects("SUBTITLE_NETWORK", () -> delivery.prepare(SOURCE, origin + "/redirect-private", "127.0.0.1", current::get));
                rejects("SUBTITLE_FETCH", () -> delivery.prepare(SOURCE, origin + "/auth", "127.0.0.1", current::get));
                try { delivery.prepare(SOURCE, origin + "/html", "127.0.0.1", current::get); throw new AssertionError("HTML provider error was accepted"); }
                catch (SubtitleDelivery.DeliveryException error) { require(error.code.startsWith("SUBTITLE_"), "Provider response leaked an error"); }
            });
            check("capability, Host, receiver peer and request bounds protect the local file", () -> {
                SubtitleDelivery.Prepared prepared = delivery.prepare(SOURCE, origin + "/srt?bounds", "127.0.0.1", current::get); URI url = new URI(prepared.url);
                require(raw(prepared.url, "GET " + url.getPath() + " HTTP/1.1\r\nHost: attacker.example\r\n\r\n").startsWith("HTTP/1.1 403"), "Foreign Host accepted");
                require(raw(prepared.url, "GET " + url.getPath() + " HTTP/1.1\r\nHost: " + url.getAuthority() + "\r\nHost: " + url.getAuthority() + "\r\n\r\n").startsWith("HTTP/1.1 400"), "Duplicate Host accepted");
                require(raw(prepared.url, "POST " + url.getPath() + " HTTP/1.1\r\nHost: " + url.getAuthority() + "\r\nContent-Length: 0\r\n\r\n").startsWith("HTTP/1.1 400"), "Delivery endpoint accepted a write");
                request(prepared.url.replace(prepared.id, "0".repeat(64)), "GET", 403);
                require(raw(prepared.url, "GET /../subtitle.srt HTTP/1.1\r\nHost: " + url.getAuthority() + "\r\n\r\n").startsWith("HTTP/1.1 404"), "Arbitrary file path accepted");
                SubtitleDelivery.Prepared otherPeer = delivery.prepare(SOURCE, origin + "/srt", "127.0.0.2", current::get); request(otherPeer.url, "GET", 403);
                for (int count = 0; count < 16; count++) request(prepared.url, "HEAD", 200);
                request(prepared.url, "GET", 403); require(!delivery.downloaded(prepared.id), "Denied request counted as body delivery");
                delivery.resolve(prepared.id, SOURCE, origin + "/srt?bounds", "127.0.0.1");
                request(prepared.url, "GET", 200);
            });
            check("active-source leases retain the same body and id beyond thirty minutes until revoked", () -> {
                SubtitleDelivery.Prepared prepared = delivery.prepare(SOURCE, origin + "/srt", "127.0.0.1", current::get);
                clock.addAndGet(TimeUnit.HOURS.toMillis(3));
                require(delivery.resolve(prepared.id, SOURCE, origin + "/srt", "127.0.0.1").url.equals(prepared.url), "Active-source lease URL changed with elapsed time");
                int beforeReuse = hits.get();
                require(delivery.prepare(SOURCE, origin + "/srt", "127.0.0.1", current::get).id.equals(prepared.id), "Repreparation replaced an already registered filename");
                require(hits.get() == beforeReuse, "Repreparation refetched cached subtitle bytes");
                request(prepared.url, "GET", 200);
                for (int selection = 0; selection < 20; selection++) {
                    delivery.resolve(prepared.id, SOURCE, origin + "/srt", "127.0.0.1");
                    request(prepared.url, "GET", 200);
                }
                SubtitleDelivery.Prepared stale = delivery.prepare(SOURCE, origin + "/srt", "127.0.0.1", current::get); current.set(false);
                request(stale.url, "GET", 403); rejects("SUBTITLE_UNAVAILABLE", () -> delivery.resolve(stale.id, SOURCE, origin + "/srt", "127.0.0.1"));
                int before = hits.get(); rejects("SOURCE_CHANGED", () -> delivery.prepare(SOURCE, origin + "/srt", "127.0.0.1", current::get)); require(before == hits.get(), "Cancelled preparation fetched a provider");
                current.set(true); delivery.revokeAll(); rejects("SUBTITLE_UNAVAILABLE", () -> delivery.resolve(stale.id, SOURCE, origin + "/srt", "127.0.0.1"));
            });
            check("128 active-source tracks remain referenced and capacity rejection never evicts them", () -> {
                delivery.revokeAll();
                SubtitleDelivery.Prepared first = delivery.prepare(SOURCE, origin + "/srt", "127.0.0.1", current::get);
                for (int index = 0; index < 127; index++) delivery.prepare(SOURCE, origin + "/srt?item=" + index, "127.0.0.1", current::get);
                rejects("SUBTITLE_TOO_LARGE", () -> delivery.prepare(SOURCE, origin + "/srt?overflow", "127.0.0.1", current::get));
                require(delivery.resolve(first.id, SOURCE, origin + "/srt", "127.0.0.1").id.equals(first.id), "Capacity rejection evicted the first referenced track");
                request(first.url, "GET", 200);
            });
            check("aggregate RAM budget rejects a new body while preserving all existing files", () -> {
                delivery.revokeAll();
                SubtitleDelivery.Prepared first = delivery.prepare(SOURCE, origin + "/big-srt", "127.0.0.1", current::get);
                for (int index = 0; index < 14; index++) delivery.prepare(SOURCE, origin + "/big-srt?item=" + index, "127.0.0.1", current::get);
                rejects("SUBTITLE_TOO_LARGE", () -> delivery.prepare(SOURCE, origin + "/big-srt?overflow", "127.0.0.1", current::get));
                require(delivery.resolve(first.id, SOURCE, origin + "/big-srt", "127.0.0.1").id.equals(first.id), "Byte-cap rejection evicted an existing file");
                require(request(first.url, "GET", 200).length > 1024 * 1024, "Existing body was truncated to accommodate capacity");
            });
        }
        check("resolved address boundaries align with native public IPv4 and IPv6 exclusions", () -> {
            java.lang.reflect.Method method = SubtitleDelivery.class.getDeclaredMethod("publicAddress", java.net.InetAddress.class); method.setAccessible(true);
            for (String value : new String[] {"8.8.8.8", "2606:4700::1111", "2001:4860:4860::8888"}) {
                require(Boolean.TRUE.equals(method.invoke(null, java.net.InetAddress.getByName(value))), "Public address rejected");
            }
            for (String value : new String[] {"127.0.0.1", "192.168.1.2", "100.64.0.1", "192.0.2.1", "::1", "fc00::1", "fe80::1", "2001:db8::1", "2001:100::1", "2002:808:808::1", "3fff::1"}) {
                require(Boolean.FALSE.equals(method.invoke(null, java.net.InetAddress.getByName(value))), "Non-public or native-excluded address accepted");
            }
        });
        check("production transport rejects private DNS destinations before a request", () -> {
            int before = hits.get();
            try (SubtitleDelivery production = new SubtitleDelivery(SubtitleDeliveryTest::valid)) {
                rejects("SUBTITLE_NETWORK", () -> production.prepare(SOURCE, origin + "/srt", "192.168.1.2", () -> true));
            }
            require(hits.get() == before, "Production private destination was fetched");
        });
        check("total fetch timeout and revocation remain bounded before delivery is prepared", () -> {
            try (SubtitleDelivery timeout = SubtitleDelivery.loopbackFixture(SubtitleDeliveryTest::valid, clock::get, 150)) {
                long started = System.nanoTime(); rejects("SUBTITLE_FETCH", () -> timeout.prepare(SOURCE, origin + "/slow", "127.0.0.1", () -> true));
                require(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000, "Subtitle fetch exceeded its total deadline");
            }
            require(slowEntered.await(1, TimeUnit.SECONDS), "Slow fixture was not reached"); releaseSlow.countDown();
        });
        check("revoking an in-flight fetch cannot publish a late subtitle lease", () -> {
            try (SubtitleDelivery delivery = SubtitleDelivery.loopbackFixture(SubtitleDeliveryTest::valid, clock::get, 1500)) {
                java.util.concurrent.ExecutorService caller = Executors.newSingleThreadExecutor();
                try {
                    java.util.concurrent.Future<?> result = caller.submit(() -> {
                        try { delivery.prepare(SOURCE, origin + "/cancel", "127.0.0.1", () -> true); throw new AssertionError("Revoked fetch returned a lease"); }
                        catch (SubtitleDelivery.DeliveryException error) { require(error.code.equals("SOURCE_CHANGED"), "Revoked fetch did not retain cancellation identity"); }
                    });
                    require(cancelEntered.await(1, TimeUnit.SECONDS), "Cancellation fixture was not reached");
                    delivery.revokeAll(); releaseCancel.countDown(); result.get(1, TimeUnit.SECONDS);
                } finally { releaseCancel.countDown(); caller.shutdownNow(); }
            }
        });
        } finally { releaseSlow.countDown(); releaseCancel.countDown(); upstream.stop(0); upstreamThreads.shutdownNow(); }
        System.out.println("Subtitle delivery: " + checks + " localhost-only transport checks passed.");
    }
}

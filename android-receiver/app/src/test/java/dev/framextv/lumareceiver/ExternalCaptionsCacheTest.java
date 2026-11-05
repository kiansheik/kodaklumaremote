package dev.framextv.lumareceiver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ExternalCaptionsCacheTest {
    private File directory;
    private ServerSocket server;
    private ExecutorService serverThreads;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> body = new AtomicReference<>(captions("First"));
    private String url;

    @Before public void start() throws Exception {
        directory = Files.createTempDirectory("luma-caption-cache-").toFile();
        ExternalCaptions.cacheDirectory = directory;
        server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        serverThreads = Executors.newSingleThreadExecutor();
        serverThreads.execute(() -> {
            while (!server.isClosed()) try (Socket socket = server.accept()) {
                BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                for (String line; (line = input.readLine()) != null && !line.isEmpty();) { /* consume request headers */ }
                requests.incrementAndGet();
                byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
                OutputStream output = socket.getOutputStream();
                output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/vtt; charset=utf-8\r\nContent-Length: "
                        + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                output.write(bytes); output.flush();
            } catch (Exception stopped) {
                if (!server.isClosed()) throw new RuntimeException(stopped);
            }
        });
        url = "http://127.0.0.1:" + server.getLocalPort() + "/captions";
    }

    @After public void stop() {
        ExternalCaptions.cacheDirectory = null;
        if (server != null) try { server.close(); } catch (Exception ignored) { }
        if (serverThreads != null) serverThreads.shutdownNow();
        File[] files = directory == null ? null : directory.listFiles();
        if (files != null) for (File file : files) file.delete();
        if (directory != null) directory.delete();
    }

    @Test public void cacheIsScopedByContentAndEffectiveHeaders() {
        ExternalCaptions unsafe = external(Collections.emptyMap(), "");
        assertNull(unsafe.cacheFile());

        ExternalCaptions episodeThree = external(Collections.emptyMap(), "framextv:tv:42:2:3");
        ExternalCaptions sameEpisode = external(Collections.singletonMap("User-Agent", "LumaReceiver/1"), "framextv:tv:42:2:3");
        ExternalCaptions episodeFour = external(Collections.emptyMap(), "framextv:tv:42:2:4");
        assertEquals(episodeThree.cacheFile(), sameEpisode.cacheFile());
        assertNotEquals(episodeThree.cacheFile(), episodeFour.cacheFile());

        Map<String, String> firstHeaders = new LinkedHashMap<>(), secondHeaders = new LinkedHashMap<>();
        firstHeaders.put("Referer", "https://first.example/");
        secondHeaders.put("Referer", "https://second.example/");
        assertNotEquals(external(firstHeaders, "framextv:tv:42:2:4").cacheFile(),
                external(secondHeaders, "framextv:tv:42:2:4").cacheFile());
    }

    @Test public void freshEntryIsReusedAndExpiredEntryIsRefetched() {
        ExternalCaptions first = external(Collections.emptyMap(), "framextv:tv:42:2:4");
        first.load();
        assertNull(first.error);
        assertEquals("First", first.textAt(1500));
        assertEquals(1, requests.get());
        File cached = first.cacheFile();
        assertNotNull(cached);
        assertTrue(cached.isFile());

        ExternalCaptions reused = external(Collections.emptyMap(), "framextv:tv:42:2:4");
        reused.load();
        assertNull(reused.error);
        assertEquals("First", reused.textAt(1500));
        assertEquals(1, requests.get());

        body.set(captions("Updated"));
        assertTrue(cached.setLastModified(System.currentTimeMillis() - ExternalCaptions.CACHE_MAX_AGE_MS - 1000));
        ExternalCaptions refreshed = external(Collections.emptyMap(), "framextv:tv:42:2:4");
        refreshed.load();
        assertNull(refreshed.error);
        assertEquals("Updated", refreshed.textAt(1500));
        assertEquals(2, requests.get());
        assertFalse(java.util.Arrays.stream(directory.listFiles()).anyMatch(file -> file.getName().endsWith(".tmp")));
    }

    private ExternalCaptions external(Map<String, String> headers, String identity) {
        MediaRequest.Subtitle subtitle = new MediaRequest.Subtitle(url, "text/vtt", "en", "English", false);
        return new ExternalCaptions(subtitle, headers, identity);
    }

    private static String captions(String text) {
        return "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n" + text + "\n";
    }
}

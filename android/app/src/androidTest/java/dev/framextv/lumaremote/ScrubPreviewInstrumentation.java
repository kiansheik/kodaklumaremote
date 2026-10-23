package dev.framextv.lumaremote;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.os.Bundle;
import org.json.JSONObject;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Memory-only preview scheduler and fetch-policy checks. No external media URL is fetched. */
public final class ScrubPreviewInstrumentation {
    private interface Check { void run() throws Exception; }
    private static final String SOURCE = "https://media.example/episode.m3u8?token=fixture";
    private final Instrumentation instrumentation;
    private int tests, passed, failures;
    private String failure = "";

    private static final class Reply implements ScrubPreviewManager.Callback {
        final CountDownLatch ready = new CountDownLatch(1);
        final AtomicReference<ScrubPreviewManager.Result> result = new AtomicReference<>();
        final AtomicReference<String> error = new AtomicReference<>();
        @Override public void success(ScrubPreviewManager.Result value) { result.set(value); ready.countDown(); }
        @Override public void failure(String code) { error.set(code); ready.countDown(); }
        void await() throws Exception { require(ready.await(3, TimeUnit.SECONDS), "Preview callback timed out"); }
    }
    private static final class MemoryLoader implements ScrubPreviewManager.SourceLoader {
        final AtomicInteger loads = new AtomicInteger();
        @Override public ScrubPreviewManager.MediaPayload load(String source, long positionSeconds, BooleanSupplier current)
                throws ScrubPreviewManager.PreviewException {
            loads.incrementAndGet();
            return new ScrubPreviewManager.MediaPayload(new byte[] { 1, 2, 3, (byte) positionSeconds }, positionSeconds * 1000000L);
        }
        @Override public void cancel() { }
        @Override public void close() { }
    }

    private ScrubPreviewInstrumentation(Instrumentation instrumentation) { this.instrumentation = instrumentation; }
    public static void run(Instrumentation instrumentation) { new ScrubPreviewInstrumentation(instrumentation).execute(); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static JSONObject content() throws Exception { return content("42", 1); }
    private static JSONObject content(String id, int episode) throws Exception {
        return new JSONObject().put("key", "framextv:tv:" + id + ":1:" + episode).put("kind", "tv").put("contentId", id)
                .put("season", 1).put("episode", episode).put("watchUrl", "https://framextv.cc/watch/" + id + "/1/" + episode)
                .put("title", "Fixture episode");
    }
    private static void rejects(String expected, Check action) throws Exception {
        try { action.run(); throw new AssertionError("Expected " + expected); }
        catch (ScrubPreviewManager.PreviewException error) {
            require(expected.equals(error.code), "Unexpected preview error " + error.code);
        }
    }
    private void check(String name, Check action) {
        tests++;
        Bundle progress = new Bundle();
        progress.putString("class", getClass().getName()); progress.putString("test", name); progress.putInt("current", tests);
        instrumentation.sendStatus(1, progress);
        try { action.run(); passed++; instrumentation.sendStatus(0, progress); }
        catch (Throwable error) {
            failures++; failure = name + ": " + error.getClass().getSimpleName();
            progress.putString("stack", failure); instrumentation.sendStatus(-2, progress);
        }
    }
    private static ScrubPreviewManager.FrameExtractorFactory frames(AtomicInteger reads, AtomicInteger closes,
            CountDownLatch firstRead, CountDownLatch releaseFirst) {
        return media -> new ScrubPreviewManager.FrameExtractor() {
            @Override public Bitmap frameAt(long timeUs) throws Exception {
                int read = reads.incrementAndGet();
                if (read == 1 && firstRead != null) {
                    firstRead.countDown();
                    require(releaseFirst.await(3, TimeUnit.SECONDS), "Blocked frame was not released");
                }
                Bitmap frame = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
                frame.eraseColor(0xff204050 + (int) Math.min(0x00b0a0, timeUs / 1000000));
                return frame;
            }
            @Override public void close() { closes.incrementAndGet(); }
        };
    }
    private static ScrubPreviewManager manager(MemoryLoader loader, ScrubPreviewManager.FrameExtractorFactory factory) {
        return new ScrubPreviewManager(loader, factory, 512 * 1024, 1000);
    }
    private static void waitFor(BooleanSupplier ready, String message) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!ready.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        require(ready.getAsBoolean(), message);
    }

    private void execute() {
        check("HTTPS and canonical-content binding", () -> {
            AtomicInteger reads = new AtomicInteger(), closes = new AtomicInteger();
            MemoryLoader loader = new MemoryLoader();
            ScrubPreviewManager manager = manager(loader, frames(reads, closes, null, null));
            try {
                rejects("INVALID_INPUT", () -> manager.bind("http://media.example/video.mp4", content(), 600));
                JSONObject altered = content().put("watchUrl", "https://framextv.cc/watch/43/1/1");
                rejects("INVALID_INPUT", () -> manager.bind(SOURCE, altered, 600));
                long generation = manager.bind(SOURCE, content(), 600);
                Reply wrongGeneration = new Reply(); manager.request(generation + 1, SOURCE, 20, wrongGeneration); wrongGeneration.await();
                require("SOURCE_CHANGED".equals(wrongGeneration.error.get()) && loader.loads.get() == 0, "Unowned source reached loader");
                Reply wrongSource = new Reply(); manager.request(generation, "https://media.example/other.m3u8", 20, wrongSource); wrongSource.await();
                require("SOURCE_CHANGED".equals(wrongSource.error.get()) && loader.loads.get() == 0, "Mismatched source reached loader");
            } finally { manager.close(); }
        });
        check("adaptive buckets stay coarse and bounded", () -> {
            require(ScrubPreviewManager.intervalSeconds(1320) == 10, "Episode interval changed");
            require(ScrubPreviewManager.intervalSeconds(7200) == 30, "Movie interval changed");
            int interval = ScrubPreviewManager.intervalSeconds(86399);
            require(Math.ceil(86399d / interval) <= 240, "Preview count exceeded bound");
            require(ScrubPreviewManager.bucketSeconds(600, 600, 10) == 590, "End bucket escaped bounded grid");
        });
        check("latest request wins and cached frames are reused", () -> {
            AtomicInteger reads = new AtomicInteger(), closes = new AtomicInteger();
            CountDownLatch firstRead = new CountDownLatch(1), releaseFirst = new CountDownLatch(1);
            MemoryLoader loader = new MemoryLoader();
            ScrubPreviewManager manager = manager(loader, frames(reads, closes, firstRead, releaseFirst));
            long generation = manager.bind(SOURCE, content(), 600);
            Reply first = new Reply(), displaced = new Reply(), latest = new Reply();
            manager.request(generation, SOURCE, 20, first);
            require(firstRead.await(2, TimeUnit.SECONDS), "First extraction did not start");
            manager.request(generation, SOURCE, 40, displaced);
            manager.request(generation, SOURCE, 80, latest);
            displaced.await(); require("PREVIEW_SUPERSEDED".equals(displaced.error.get()), "Queued preview was not superseded");
            releaseFirst.countDown(); first.await(); latest.await();
            require("PREVIEW_SUPERSEDED".equals(first.error.get()), "In-flight stale preview was delivered");
            require(latest.result.get() != null && latest.result.get().positionSeconds == 80
                    && latest.result.get().dataUri.startsWith("data:image/jpeg;base64,"), "Latest frame missing");
            require(reads.get() == 2 && manager.cachedFrameCount() == 2, "Extraction/cache count was not bounded");
            Reply cached = new Reply(); manager.request(generation, SOURCE, 89, cached); cached.await();
            require(cached.result.get() != null && cached.result.get().positionSeconds == 80 && reads.get() == 2, "Cached bucket was re-extracted");
            manager.clear();
            Reply revoked = new Reply(); manager.request(generation, SOURCE, 80, revoked); revoked.await();
            require("SOURCE_CHANGED".equals(revoked.error.get()) && manager.cachedFrameCount() == 0, "Revoked source retained previews");
            manager.close();
            waitFor(() -> closes.get() >= 2, "Extractors were not released");
        });
        check("direct HLS VOD selection includes the active initialization map", () -> {
            String playlist = "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-MAP:URI=\"init.mp4\"\n"
                    + "#EXTINF:4.0,\nsegment-1.m4s\n#EXTINF:6.0,\nhttps://cdn.example/video/segment-2.m4s\n#EXT-X-ENDLIST\n";
            ScrubPreviewManager.HlsSelection selected = ScrubPreviewManager.parseMediaPlaylist(
                    new URI("https://media.example/path/index.m3u8?token=fixture"), playlist.getBytes(StandardCharsets.UTF_8), 5);
            require("https://media.example/path/init.mp4".equals(selected.initialization.toString()), "Initialization URL was not resolved");
            require("https://cdn.example/video/segment-2.m4s".equals(selected.segment.toString()), "Wrong time-corresponding segment");
            require(selected.timeUs == 1000000L, "Segment-local timestamp was not preserved");
            rejects("PREVIEW_UNSUPPORTED", () -> ScrubPreviewManager.parseMediaPlaylist(new URI(SOURCE),
                    "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:5,\na.ts\n#EXT-X-ENDLIST\n".getBytes(StandardCharsets.UTF_8), 1));
            rejects("PREVIEW_UNSUPPORTED", () -> ScrubPreviewManager.parseMediaPlaylist(new URI(SOURCE),
                    "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nvariant.m3u8\n".getBytes(StandardCharsets.UTF_8), 1));
            rejects("PREVIEW_UNSUPPORTED", () -> ScrubPreviewManager.parseMediaPlaylist(new URI(SOURCE),
                    "#EXTM3U\n#EXTINF:5,\na.ts\n".getBytes(StandardCharsets.UTF_8), 1));
        });
        check("private destinations and redirect downgrades are rejected", () -> {
            require(!ScrubPreviewManager.publicAddress(InetAddress.getByAddress(new byte[] { 10, 0, 0, 1 })), "Private IPv4 passed");
            require(!ScrubPreviewManager.publicAddress(InetAddress.getByAddress(new byte[] { 100, 64, 0, 1 })), "Carrier NAT passed");
            require(!ScrubPreviewManager.publicAddress(InetAddress.getByAddress(new byte[] { (byte) 192, 0, 2, 1 })), "Documentation IPv4 passed");
            require(ScrubPreviewManager.publicAddress(InetAddress.getByAddress(new byte[] { 8, 8, 8, 8 })), "Public IPv4 was rejected");
            byte[] privateV6 = new byte[16]; privateV6[0] = (byte) 0xfc; privateV6[15] = 1;
            require(!ScrubPreviewManager.publicAddress(InetAddress.getByAddress(privateV6)), "Private IPv6 passed");
            InetAddress approved = InetAddress.getByAddress(new byte[] { 8, 8, 8, 8 });
            InetSocketAddress endpoint = ScrubPreviewManager.pinnedEndpoint(approved, 443);
            require(!endpoint.isUnresolved() && approved.equals(endpoint.getAddress()), "Approved address was re-resolved");
            rejects("PREVIEW_UNSUPPORTED", () -> ScrubPreviewManager.pinnedEndpoint(
                    InetAddress.getByAddress(new byte[] { 10, 0, 0, 1 }), 443));
            URI current = new URI("https://media.example/path/index.m3u8");
            require("https://media.example/next.m3u8".equals(ScrubPreviewManager.redirectTarget(current, "../next.m3u8").toString()), "Safe redirect failed");
            rejects("PREVIEW_UNSUPPORTED", () -> ScrubPreviewManager.redirectTarget(current, "http://media.example/plain.m3u8"));
            rejects("PREVIEW_UNSUPPORTED", () -> ScrubPreviewManager.redirectTarget(current, "https://127.0.0.1/private.m3u8"));
        });
        check("decoder timeout skips while the slow decode runs, then recovers without growing stuck work", () -> {
            MemoryLoader loader = new MemoryLoader();
            AtomicInteger reads = new AtomicInteger(), closes = new AtomicInteger();
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
            ScrubPreviewManager.FrameExtractorFactory factory = media -> new ScrubPreviewManager.FrameExtractor() {
                @Override public Bitmap frameAt(long timeUs) {
                    int read = reads.incrementAndGet();
                    if (read == 1) {
                        started.countDown();
                        while (release.getCount() > 0) {
                            try { release.await(20, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
                        }
                    }
                    Bitmap frame = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888); frame.eraseColor(0xff102030); return frame;
                }
                @Override public void close() { closes.incrementAndGet(); }
            };
            ScrubPreviewManager manager = new ScrubPreviewManager(loader, factory, 512 * 1024, 100);
            long generation = manager.bind(SOURCE, content(), 600);
            Reply timedOut = new Reply(); manager.request(generation, SOURCE, 20, timedOut);
            require(started.await(1, TimeUnit.SECONDS), "Decoder did not start"); timedOut.await();
            require("PREVIEW_UNSUPPORTED".equals(timedOut.error.get()), "Decoder timeout was not bounded");
            Thread.sleep(150);
            require(closes.get() == 0, "Extractor was released while its native decode was still running");
            Reply sameBinding = new Reply(); manager.request(generation, SOURCE, 40, sameBinding); sameBinding.await();
            require("PREVIEW_UNSUPPORTED".equals(sameBinding.error.get()) && reads.get() == 1
                    && manager.activeDecoderCount() == 1, "Timed-out binding created more stuck work");
            release.countDown(); waitFor(() -> manager.activeDecoderCount() == 0, "Timed-out decoder did not settle");
            waitFor(() -> closes.get() == 1, "Timed-out extractor was not released after its decode returned");
            Reply sameVideoLater = new Reply(); manager.request(generation, SOURCE, 50, sameVideoLater); sameVideoLater.await();
            require(sameVideoLater.result.get() != null && reads.get() == 2 && manager.activeDecoderCount() == 0,
                    "A settled slow decode prevented later previews of the same video");
            String nextSource = "https://media.example/next.m3u8";
            long nextGeneration = manager.bind(nextSource, content("42", 2), 600);
            Reply recovered = new Reply(); manager.request(nextGeneration, nextSource, 50, recovered); recovered.await();
            require(recovered.result.get() != null && reads.get() == 3 && manager.activeDecoderCount() == 0,
                    "Settled decoder prevented a later binding");
            manager.close();
        });
        check("close during extractor creation still releases the late resource", () -> {
            MemoryLoader loader = new MemoryLoader();
            CountDownLatch entered = new CountDownLatch(1), releaseFactory = new CountDownLatch(1);
            AtomicInteger closes = new AtomicInteger();
            ScrubPreviewManager.FrameExtractorFactory factory = media -> {
                entered.countDown();
                while (releaseFactory.getCount() > 0) {
                    try { releaseFactory.await(20, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
                }
                return new ScrubPreviewManager.FrameExtractor() {
                    @Override public Bitmap frameAt(long timeUs) { return Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888); }
                    @Override public void close() { closes.incrementAndGet(); }
                };
            };
            ScrubPreviewManager manager = new ScrubPreviewManager(loader, factory, 512 * 1024, 1000);
            Reply reply = new Reply();
            try {
                long generation = manager.bind(SOURCE, content(), 600);
                manager.request(generation, SOURCE, 20, reply);
                require(entered.await(1, TimeUnit.SECONDS), "Extractor factory did not start");
                manager.close();
                releaseFactory.countDown(); reply.await();
                waitFor(() -> closes.get() == 1 && manager.activeDecoderCount() == 0,
                        "Late extractor was leaked after release executor shutdown");
                require("PREVIEW_CLOSED".equals(reply.error.get()), "Close did not revoke the pending callback");
            } finally { releaseFactory.countDown(); manager.close(); }
        });
        Bundle result = new Bundle();
        result.putInt("tests", tests); result.putInt("passed", passed); result.putInt("failures", failures); result.putString("failure", failure);
        instrumentation.finish(failures == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }
}

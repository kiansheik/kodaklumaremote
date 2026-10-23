package dev.framextv.lumaremote;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Process;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Background scrub thumbnails for the playing video. Generation starts as soon
 * as playback is verified after a cast and fills a fixed grid in breadth-first
 * bisection order (middle, quarters, eighths, ...), so coverage is coarse but
 * complete early and gets finer over time. Scrubbing only reads what exists and
 * nudges the area being scrubbed to the front; it never performs network or
 * decoding work on the caller's (main) thread.
 *
 * Frames are small JPEGs held in memory and in a temporary disk cache keyed by
 * canonical content (stream URLs expire). Cache entries expire after 48 hours.
 */
public final class ThumbnailStrip implements AutoCloseable {
    public interface FrameSource extends AutoCloseable {
        /** JPEG bytes for the frame nearest {@code timeUs}, or null if unavailable. Worker thread only. */
        byte[] jpegAt(long timeUs) throws Exception;
        @Override void close();
    }
    public interface SourceFactory { FrameSource open(String url, String contentType, String contentKey, double duration) throws Exception; }
    public static final class Frame {
        public final byte[] jpeg; public final long positionSeconds; public final int intervalSeconds; public final int done, total;
        Frame(byte[] jpeg, long positionSeconds, int intervalSeconds, int done, int total) {
            this.jpeg = jpeg; this.positionSeconds = positionSeconds; this.intervalSeconds = intervalSeconds; this.done = done; this.total = total;
        }
    }

    // Coarse pass of ~COARSE_FRAMES cells, then refinement down to FINE_SECONDS spacing.
    static final int WIDTH = 256, HEIGHT = 144, JPEG_QUALITY = 60, COARSE_FRAMES = 200, MAX_CELLS = 2400, FINE_SECONDS = 3,
        MAX_FRAME_BYTES = 96 * 1024, MEMORY_FRAMES = 500;
    static final long CACHE_TTL_MS = 48L * 60 * 60 * 1000, DISK_CAP_BYTES = 96L * 1024 * 1024, REFINE_PACE_MS = 400;
    private static final int MAX_CONSECUTIVE_FAILURES = 4, MAX_PRIORITY = 6;

    private final File root;
    private final SourceFactory factory;
    private final Object lock = new Object();
    // Guarded by lock.
    private long generation;
    private String source = "", contentType = "", contentKey = "";
    private double duration;
    private int interval, total;
    private int[] order = new int[0];
    private int orderCursor;
    private boolean[] done = new boolean[0];
    private final java.util.TreeSet<Long> available = new java.util.TreeSet<>();
    private final java.util.LinkedHashMap<Long, byte[]> memory = new java.util.LinkedHashMap<Long, byte[]>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) { return size() > MEMORY_FRAMES; }
    };
    private int coarseCells;
    private final Deque<Integer> priority = new ArrayDeque<>();
    private File directory;
    private Thread worker;
    private boolean closed, unsupported;

    public ThumbnailStrip(File cacheRoot) { this(cacheRoot, ThumbnailStrip::platformSource); }

    ThumbnailStrip(File cacheRoot, SourceFactory factory) {
        this.root = new File(cacheRoot, "scrub-thumbnails");
        this.factory = factory;
        prune(root, System.currentTimeMillis());
    }

    /** Grid spacing: every 3 s, coarser only beyond MAX_CELLS (videos longer than 2 h). */
    static int intervalSeconds(double duration) {
        return Math.max(FINE_SECONDS, (int) Math.ceil(duration / MAX_CELLS));
    }

    /** Coarse cells (bisection over every stride-th cell) first, then all remaining cells in bisection order. */
    static int[] generationOrder(int count) {
        int stride = Math.max(1, (int) Math.ceil(count / (double) COARSE_FRAMES));
        int coarseCount = (count + stride - 1) / stride;
        int[] coarse = bisectionOrder(coarseCount), all = bisectionOrder(count), order = new int[coarseCount + count];
        for (int i = 0; i < coarseCount; i++) order[i] = Math.min(count - 1, coarse[i] * stride);
        System.arraycopy(all, 0, order, coarseCount, count);
        return order;
    }

    /** Breadth-first midpoints of [0, count): middle, then quarters, eighths, ... */
    static int[] bisectionOrder(int count) {
        int[] result = new int[count];
        int written = 0;
        Deque<int[]> ranges = new ArrayDeque<>();
        if (count > 0) ranges.add(new int[] {0, count - 1});
        while (!ranges.isEmpty()) {
            int[] range = ranges.poll();
            int mid = (range[0] + range[1]) >>> 1;
            result[written++] = mid;
            if (range[0] <= mid - 1) ranges.add(new int[] {range[0], mid - 1});
            if (mid + 1 <= range[1]) ranges.add(new int[] {mid + 1, range[1]});
        }
        return result;
    }

    long positionOf(int index) { return Math.min((long) Math.floor(duration) - 1, (long) index * interval + interval / 2); }

    /** Starts (or keeps) background generation for this exact item. Cheap and idempotent; any thread. */
    public void bind(String url, String type, String key, double seconds) {
        if (url == null || key == null || !(seconds > 1) || seconds > 86399) { clear(); return; }
        synchronized (lock) {
            if (closed) return;
            if (url.equals(source) && key.equals(contentKey) && seconds == duration) return;
            // A new stream URL for the same episode (URLs expire) keeps the cached frames.
            boolean sameItem = key.equals(contentKey) && Math.abs(seconds - duration) < 2;
            generation++;
            source = url; contentType = type == null ? "" : type; contentKey = key;
            if (!sameItem) {
                duration = seconds; interval = intervalSeconds(seconds);
                total = (int) Math.max(1, Math.min(MAX_CELLS, Math.floor(seconds / interval)));
                order = generationOrder(total); done = new boolean[total];
                coarseCells = (total + Math.max(1, (int) Math.ceil(total / (double) COARSE_FRAMES)) - 1) / Math.max(1, (int) Math.ceil(total / (double) COARSE_FRAMES));
                available.clear(); memory.clear(); priority.clear();
                directory = new File(root, hash(key) + "-" + interval);
                loadDiskLocked();
            } else duration = seconds;
            orderCursor = 0; unsupported = false;
            startWorkerLocked();
        }
    }

    /** Moves the grid cell around {@code seconds} to the front of the queue. */
    public void prioritize(double seconds) {
        synchronized (lock) {
            if (total == 0 || unsupported) return;
            int index = (int) Math.max(0, Math.min(total - 1, Math.floor(seconds / interval)));
            if (done[index]) return;
            priority.remove(index);
            priority.addFirst(index);
            while (priority.size() > MAX_PRIORITY) priority.removeLast();
            lock.notifyAll();
        }
    }

    /** The closest frame already generated, or null. Memory first; an evicted frame is one small file read. */
    public Frame nearest(String url, double seconds) {
        long best; int count; File folder;
        synchronized (lock) {
            if (!url.equals(source) || available.isEmpty()) return null;
            long target = (long) Math.max(0, seconds);
            Long below = available.floor(target), above = available.ceiling(target);
            best = below == null ? above : above == null ? below : target - below <= above - target ? below : above;
            byte[] cached = memory.get(best);
            if (cached != null) return new Frame(cached, best, interval, available.size(), total);
            count = available.size(); folder = directory;
        }
        try {
            byte[] jpeg = Files.readAllBytes(new File(folder, best + ".jpg").toPath());
            synchronized (lock) { if (url.equals(source)) memory.put(best, jpeg); }
            return new Frame(jpeg, best, interval, count, total);
        } catch (Exception missing) { return null; }
    }

    /** Positions already generated (for sharing with the receiver). */
    public java.util.List<Long> positions(String key) {
        synchronized (lock) { return key.equals(contentKey) ? new ArrayList<>(available) : new ArrayList<>(); }
    }

    /** Up to {@code limit} generated positions not in {@code have}, nearest to {@code around} first. */
    public java.util.List<Long> positionsNear(String url, double around, java.util.Set<Long> have, int limit) {
        synchronized (lock) {
            java.util.List<Long> result = new ArrayList<>();
            if (!url.equals(source)) return result;
            for (long position : available) if (!have.contains(position)) result.add(position);
            Collections.sort(result, (a, b) -> Double.compare(Math.abs(a - around), Math.abs(b - around)));
            return result.size() > limit ? new ArrayList<>(result.subList(0, limit)) : result;
        }
    }

    public String contentKey() { synchronized (lock) { return contentKey; } }

    /** Frame bytes for sharing, or null. */
    public byte[] frameBytes(String key, long position) {
        File folder;
        synchronized (lock) {
            if (!key.equals(contentKey) || !available.contains(position)) return null;
            byte[] cached = memory.get(position);
            if (cached != null) return cached;
            folder = directory;
        }
        try { return Files.readAllBytes(new File(folder, position + ".jpg").toPath()); } catch (Exception missing) { return null; }
    }

    /** Adds a frame produced elsewhere (another phone, via the receiver) for this item. */
    public boolean accept(String key, long position, byte[] jpeg) {
        File folder;
        synchronized (lock) {
            if (!key.equals(contentKey) || jpeg == null || jpeg.length == 0 || jpeg.length > MAX_FRAME_BYTES || total == 0) return false;
            int index = (int) (position / interval);
            if (index < 0 || index >= total || positionOf(index) != position || done[index]) return false;
            done[index] = true; available.add(position); memory.put(position, jpeg); folder = directory;
        }
        writeDisk(folder, position, jpeg);
        return true;
    }

    public boolean unsupported() { synchronized (lock) { return unsupported; } }

    public void clear() {
        synchronized (lock) {
            generation++; source = contentType = contentKey = ""; duration = 0; total = 0;
            order = new int[0]; done = new boolean[0]; available.clear(); memory.clear(); priority.clear(); directory = null;
            lock.notifyAll();
        }
    }

    @Override public void close() {
        synchronized (lock) { closed = true; lock.notifyAll(); }
        clear();
    }

    private void startWorkerLocked() {
        if (worker != null && worker.isAlive()) { lock.notifyAll(); return; }
        worker = new Thread(this::run, "Scrub thumbnails");
        worker.setDaemon(true);
        worker.start();
    }

    private void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        FrameSource opened = null;
        long openedGeneration = -1;
        int failures = 0;
        try {
            while (true) {
                long current; String url, type, key; double length; int index; File folder;
                synchronized (lock) {
                    while (!closed && (source.isEmpty() || unsupported || nextIndexLocked() < 0)) {
                        if (opened != null) break; // release the decoder before idling
                        try { lock.wait(); } catch (InterruptedException stop) { return; }
                    }
                    if (closed) return;
                    if (source.isEmpty() || unsupported || nextIndexLocked() < 0) { index = -1; current = generation; url = type = key = ""; length = 0; folder = null; }
                    else {
                        current = generation; url = source; type = contentType; key = contentKey; length = duration;
                        index = takeIndexLocked(); folder = directory;
                    }
                }
                if (index < 0 || current != openedGeneration) {
                    if (opened != null) { opened.close(); opened = null; openedGeneration = -1; }
                    if (index < 0) continue;
                }
                long position;
                synchronized (lock) { position = positionOf(index); }
                try {
                    if (opened == null) { opened = factory.open(url, type, key, length); openedGeneration = current; failures = 0; }
                    byte[] jpeg = opened.jpegAt(position * 1_000_000L);
                    if (jpeg == null || jpeg.length == 0 || jpeg.length > MAX_FRAME_BYTES) throw new IllegalStateException("no frame");
                    failures = 0;
                    boolean refining;
                    synchronized (lock) {
                        if (current != generation) continue;
                        done[index] = true;
                        available.add(position); memory.put(position, jpeg);
                        refining = orderCursor > coarseCells && priority.isEmpty();
                    }
                    writeDisk(folder, position, jpeg);
                    // Refinement is a background nicety: leave bandwidth for the stream itself.
                    if (refining) Thread.sleep(REFINE_PACE_MS);
                } catch (Exception | LinkageError failure) {
                    synchronized (lock) {
                        if (current != generation) continue;
                        done[index] = true; // do not retry a cell that failed; keep filling others
                        if (++failures >= MAX_CONSECUTIVE_FAILURES || opened == null) unsupported = true;
                    }
                    android.util.Log.i("LumaThumbnails", (opened == null ? "SOURCE_UNSUPPORTED " : "FRAME_FAILED ") + failure.getClass().getSimpleName());
                }
            }
        } finally { if (opened != null) opened.close(); }
    }

    private int nextIndexLocked() {
        for (Integer queued : priority) if (!done[queued]) return queued;
        for (int i = orderCursor; i < order.length; i++) if (!done[order[i]]) return order[i];
        return -1;
    }

    private int takeIndexLocked() {
        while (!priority.isEmpty()) { int queued = priority.pollFirst(); if (!done[queued]) return queued; }
        while (orderCursor < order.length) { int next = order[orderCursor++]; if (!done[next]) return next; }
        return -1;
    }

    // ---- temporary disk cache ----

    private void loadDiskLocked() {
        File[] files = directory.listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File file : files) {
            String name = file.getName();
            if (!name.matches("[0-9]{1,6}\\.jpg") || now - file.lastModified() > CACHE_TTL_MS || file.length() > MAX_FRAME_BYTES) continue;
            long position = Long.parseLong(name.substring(0, name.length() - 4));
            int index = (int) (position / interval);
            if (index < 0 || index >= total || positionOf(index) != position) continue;
            available.add(position); done[index] = true;
        }
    }

    private static void writeDisk(File folder, long position, byte[] jpeg) {
        if (folder == null) return;
        try {
            if (!folder.isDirectory() && !folder.mkdirs()) return;
            File part = new File(folder, position + ".part"), target = new File(folder, position + ".jpg");
            try (FileOutputStream out = new FileOutputStream(part)) { out.write(jpeg); }
            if (!part.renameTo(target)) part.delete();
            folder.setLastModified(System.currentTimeMillis());
        } catch (Exception ignored) { /* the in-memory frame still works */ }
    }

    /** Drops expired items and keeps the whole cache under its cap, oldest first. */
    static void prune(File root, long now) {
        File[] items = root.listFiles();
        if (items == null) return;
        List<File> kept = new ArrayList<>();
        for (File item : items) {
            if (now - item.lastModified() > CACHE_TTL_MS) deleteTree(item); else kept.add(item);
        }
        Collections.sort(kept, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        long total = 0;
        for (File item : kept) { total += size(item); if (total > DISK_CAP_BYTES) deleteTree(item); }
    }

    private static long size(File item) {
        File[] files = item.listFiles();
        long total = 0;
        if (files != null) for (File file : files) total += file.length();
        return total;
    }

    private static void deleteTree(File item) {
        File[] files = item.listFiles();
        if (files != null) for (File file : files) file.delete();
        item.delete();
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 12; i++) out.append(String.format(java.util.Locale.ROOT, "%02x", digest[i]));
            return out.toString();
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    // ---- platform frame sources ----

    static FrameSource platformSource(String url, String contentType, String contentKey, double duration) throws Exception {
        String type = contentType == null ? "" : contentType.toLowerCase(java.util.Locale.ROOT);
        if (type.contains("mpegurl")) return new HlsSource(url, contentKey, duration);
        if (type.startsWith("video/")) return new ProgressiveSource(url);
        throw new IllegalArgumentException("unsupported");
    }

    /**
     * MP4/WebM: one retriever reads the index once, then fetches only the bytes
     * near each requested keyframe through HTTP range requests.
     */
    static final class ProgressiveSource implements FrameSource {
        private final MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        ProgressiveSource(String url) throws Exception {
            requirePublicHost(url);
            retriever.setDataSource(url, new HashMap<>());
        }
        @Override public byte[] jpegAt(long timeUs) {
            Bitmap frame = Build.VERSION.SDK_INT >= 27
                ? retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, WIDTH, HEIGHT)
                : retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            try { return encode(frame); } finally { if (frame != null) frame.recycle(); }
        }
        @Override public void close() { try { retriever.release(); } catch (Exception ignored) { } }
    }

    /** HLS: reuses the bounded, DNS-pinned segment loader, called from this worker thread. */
    static final class HlsSource implements FrameSource {
        private final ScrubPreviewManager manager = new ScrubPreviewManager();
        private final String url;
        private final long binding;
        HlsSource(String url, String contentKey, double duration) throws Exception {
            this.url = url;
            binding = manager.bind(url, canonical(contentKey), duration);
        }
        private static org.json.JSONObject canonical(String key) throws Exception {
            String[] parts = key.split(":");
            boolean tv = "tv".equals(parts[1]);
            return WatchHistory.contentFromCatalog(parts[1], parts[2], tv ? Integer.valueOf(parts[3]) : null, tv ? Integer.valueOf(parts[4]) : null, "");
        }
        @Override public byte[] jpegAt(long timeUs) throws Exception {
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            byte[][] result = new byte[1][];
            manager.request(binding, url, timeUs / 1_000_000.0, new ScrubPreviewManager.Callback() {
                @Override public void success(ScrubPreviewManager.Result value) {
                    String data = value.dataUri;
                    result[0] = android.util.Base64.decode(data.substring(data.indexOf(',') + 1), android.util.Base64.DEFAULT);
                    done.countDown();
                }
                @Override public void failure(String code) { done.countDown(); }
            });
            if (!done.await(12, java.util.concurrent.TimeUnit.SECONDS)) return null;
            return result[0];
        }
        @Override public void close() { manager.close(); }
    }

    static byte[] encode(Bitmap frame) {
        if (frame == null || frame.getWidth() < 1 || frame.getHeight() < 1) return null;
        Bitmap output = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(output);
            canvas.drawColor(Color.BLACK);
            float scale = Math.min((float) WIDTH / frame.getWidth(), (float) HEIGHT / frame.getHeight());
            float width = frame.getWidth() * scale, height = frame.getHeight() * scale;
            canvas.drawBitmap(frame, null, new RectF((WIDTH - width) / 2, (HEIGHT - height) / 2, (WIDTH + width) / 2, (HEIGHT + height) / 2),
                new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(16 * 1024);
            if (!output.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bytes)) return null;
            return bytes.toByteArray();
        } finally { output.recycle(); }
    }

    /** Refuses sources that resolve to private or local addresses before the platform decoder sees them. */
    static void requirePublicHost(String url) throws Exception {
        URI uri = new URI(url);
        if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) throw new IllegalArgumentException("scheme");
        InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
        for (InetAddress address : addresses) {
            byte[] raw = address.getAddress();
            boolean cgnat = address instanceof Inet4Address && (raw[0] & 255) == 100 && (raw[1] & 0xC0) == 64;
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || address.isMulticastAddress() || cgnat || (raw.length == 16 && (raw[0] & 0xFE) == 0xFC)) throw new SecurityException("non-public host");
        }
        if (addresses.length == 0) throw new IllegalArgumentException("unresolved");
    }
}

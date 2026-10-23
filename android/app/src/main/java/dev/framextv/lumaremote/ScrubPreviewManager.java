package dev.framextv.lumaremote;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.MediaDataSource;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.util.Base64;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Phone-only, memory-only scrub previews for one service-owned source.
 *
 * Network media is never handed to a platform decoder. This class fetches a
 * bounded direct HLS VOD playlist and the one selected segment itself, checking
 * every redirect and every DNS answer, then gives MediaMetadataRetriever only
 * an in-memory MediaDataSource. Unsupported formats fail closed without
 * changing receiver playback.
 */
public final class ScrubPreviewManager implements AutoCloseable {
    public interface Callback {
        void success(Result result);
        void failure(String code);
    }
    interface FrameExtractor extends AutoCloseable {
        Bitmap frameAt(long timeUs) throws Exception;
        @Override void close();
    }
    interface FrameExtractorFactory { FrameExtractor open(byte[] media) throws Exception; }
    interface SourceLoader extends AutoCloseable {
        MediaPayload load(String source, long positionSeconds, BooleanSupplier current) throws PreviewException;
        void cancel();
        @Override void close();
    }

    public static final class Result {
        public final String dataUri;
        public final long positionSeconds;
        public final int intervalSeconds;
        private Result(byte[] jpeg, long position, int interval) {
            dataUri = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP);
            positionSeconds = position;
            intervalSeconds = interval;
        }
    }
    public static final class PreviewException extends Exception {
        public final String code;
        private PreviewException(String code) { super(code); this.code = code; }
    }
    static final class MediaPayload {
        final byte[] bytes;
        final long timeUs;
        MediaPayload(byte[] bytes, long timeUs) { this.bytes = bytes; this.timeUs = timeUs; }
    }
    static final class HlsSelection {
        final URI initialization, segment;
        final long timeUs;
        HlsSelection(URI initialization, URI segment, long timeUs) {
            this.initialization = initialization; this.segment = segment; this.timeUs = timeUs;
        }
    }

    private static final int WIDTH = 256, HEIGHT = 144, JPEG_QUALITY = 55;
    private static final int MAX_FRAMES = 240, MAX_FRAME_BYTES = 128 * 1024, DEFAULT_CACHE_BYTES = 12 * 1024 * 1024;
    // Physical 1080p HLS segments took longer than 1.8 s to decode from memory.
    private static final long DEFAULT_DECODE_TIMEOUT_MILLIS = 5000;
    private final Object lock = new Object();
    private final SourceLoader sourceLoader;
    private final FrameExtractorFactory factory;
    private final int maximumCacheBytes;
    private final long decodeTimeoutMillis;
    private final ThreadPoolExecutor worker = pool(1, 1, "Scrub preview");
    private final ThreadPoolExecutor decoder = pool(1, 1, "Scrub decoder");
    private final ThreadPoolExecutor releaser = pool(1, 1, "Scrub release");
    private final LinkedHashMap<Long, byte[]> cache = new LinkedHashMap<>(16, .75f, true);
    private long generation, sequence, failedGeneration = -1;
    private String source = "", contentKey = "";
    private double durationSeconds;
    private int cachedBytes;
    private Pending latest;
    private DecodeTask activeDecode;
    private int outstandingReleases;
    private boolean draining, closed;

    private static final class Pending {
        final long generation, sequence, position;
        final int interval;
        final String source;
        final Callback callback;
        Pending(long generation, long sequence, String source, long position, int interval, Callback callback) {
            this.generation = generation; this.sequence = sequence; this.source = source;
            this.position = position; this.interval = interval; this.callback = callback;
        }
    }

    private static final class ByteArraySource extends MediaDataSource {
        private byte[] bytes;
        ByteArraySource(byte[] bytes) { this.bytes = bytes; }
        @Override public synchronized int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
            byte[] current = bytes;
            if (current == null) throw new IOException("closed");
            if (position < 0 || offset < 0 || size < 0 || offset > buffer.length - size) throw new IOException("invalid read");
            if (position >= current.length) return -1;
            int count = (int) Math.min((long) size, current.length - position);
            System.arraycopy(current, (int) position, buffer, offset, count);
            return count;
        }
        @Override public synchronized long getSize() throws IOException {
            if (bytes == null) throw new IOException("closed");
            return bytes.length;
        }
        @Override public synchronized void close() { bytes = null; }
    }

    private static final class PlatformExtractor implements FrameExtractor {
        private final byte[] media;
        private final Object stateLock = new Object();
        private boolean closed;
        private ByteArraySource dataSource;
        private MediaMetadataRetriever retriever;
        PlatformExtractor(byte[] media) { this.media = media; }
        @Override public Bitmap frameAt(long timeUs) {
            if (Build.VERSION.SDK_INT < 27) throw new UnsupportedOperationException("scaled frame extraction unavailable");
            ByteArraySource data = new ByteArraySource(media);
            MediaMetadataRetriever opened = new MediaMetadataRetriever();
            synchronized (stateLock) {
                if (closed) {
                    data.close();
                    try { opened.release(); } catch (Exception ignored) { }
                    throw new IllegalStateException("closed");
                }
                dataSource = data; retriever = opened;
            }
            opened.setDataSource(data);
            Bitmap frame = opened.getScaledFrameAtTime(Math.max(0, timeUs), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, WIDTH, HEIGHT);
            if (frame == null && timeUs > 0) frame = opened.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, WIDTH, HEIGHT);
            return frame;
        }
        @Override public void close() {
            ByteArraySource data;
            MediaMetadataRetriever opened;
            synchronized (stateLock) {
                if (closed) return;
                closed = true; data = dataSource; opened = retriever; dataSource = null; retriever = null;
            }
            if (data != null) data.close();
            if (opened != null) try { opened.release(); } catch (Exception ignored) { }
        }
    }

    private final class DecodeTask {
        final long generation;
        final MediaPayload payload;
        final AtomicBoolean releaseRequested = new AtomicBoolean();
        final AtomicBoolean releaseScheduled = new AtomicBoolean();
        volatile FrameExtractor extractor;
        volatile Future<byte[]> future;
        // Guarded by the manager lock. The start gate makes cancellation
        // before factory.open deterministic rather than a FutureTask race.
        boolean started, cancelBeforeStart;
        // Guarded by this task. MediaMetadataRetriever.release() during a native
        // frame decode is a use-after-free that kills the process, so an abort
        // while decoding only marks the task; the decoder releases on return.
        private boolean decoding;
        DecodeTask(long generation, MediaPayload payload) { this.generation = generation; this.payload = payload; }
        synchronized boolean beginDecode() { if (releaseRequested.get()) return false; decoding = true; return true; }
        synchronized void endDecode() { decoding = false; }
        synchronized void abandon() { releaseRequested.set(true); if (!decoding) scheduleRelease(this); }
        byte[] run() throws Exception {
            FrameExtractor opened = factory.open(payload.bytes);
            extractor = opened;
            if (!beginDecode()) { scheduleRelease(this); throw failure("PREVIEW_UNSUPPORTED"); }
            Bitmap frame = null;
            try {
                frame = opened.frameAt(payload.timeUs);
                return encode(frame);
            } finally {
                endDecode();
                if (frame != null && !frame.isRecycled()) frame.recycle();
                requestRelease(this);
            }
        }
    }

    public ScrubPreviewManager() {
        this(new HlsSourceLoader(), PlatformExtractor::new, DEFAULT_CACHE_BYTES, DEFAULT_DECODE_TIMEOUT_MILLIS);
    }
    ScrubPreviewManager(SourceLoader sourceLoader, FrameExtractorFactory factory, int maximumCacheBytes, long decodeTimeoutMillis) {
        if (sourceLoader == null || factory == null || maximumCacheBytes < MAX_FRAME_BYTES
                || decodeTimeoutMillis < 50 || decodeTimeoutMillis > 5000) throw new IllegalArgumentException("Invalid preview configuration");
        this.sourceLoader = sourceLoader; this.factory = factory; this.maximumCacheBytes = maximumCacheBytes;
        this.decodeTimeoutMillis = decodeTimeoutMillis;
    }

    /** Replaces any prior source and returns the only generation accepted by request(). */
    public long bind(String exactSourceUrl, JSONObject canonicalContent, double duration) throws PreviewException {
        String validSource = httpsSource(exactSourceUrl);
        String validContent = contentKey(canonicalContent);
        if (!Double.isFinite(duration) || duration <= 0 || duration > 86399) throw failure("INVALID_INPUT");
        Pending displaced;
        DecodeTask abort;
        long result;
        synchronized (lock) {
            if (closed) throw failure("PREVIEW_CLOSED");
            if (validSource.equals(source) && validContent.equals(contentKey) && duration == durationSeconds) return generation;
            generation++; sequence++; result = generation;
            source = validSource; contentKey = validContent; durationSeconds = duration; failedGeneration = -1;
            displaced = latest; latest = null; clearCacheLocked();
            abort = activeDecode != null && activeDecode.generation != generation ? activeDecode : null;
        }
        sourceLoader.cancel();
        abortDecode(abort);
        fail(displaced, "SOURCE_CHANGED");
        return result;
    }

    /** Requests the nearest coarse bucket. Only the most recent request is delivered. */
    public void request(long expectedGeneration, String expectedSourceUrl, double positionSeconds, Callback callback) {
        if (callback == null) throw new IllegalArgumentException("Preview callback required");
        String validSource;
        try { validSource = httpsSource(expectedSourceUrl); }
        catch (PreviewException invalid) { callback.failure(invalid.code); return; }
        Pending displaced = null;
        Result cached = null;
        String immediateFailure = null;
        synchronized (lock) {
            if (closed) immediateFailure = "PREVIEW_CLOSED";
            else if (expectedGeneration != generation || !validSource.equals(source) || contentKey.isEmpty()) immediateFailure = "SOURCE_CHANGED";
            else if (!Double.isFinite(positionSeconds) || positionSeconds < 0 || positionSeconds > durationSeconds) immediateFailure = "INVALID_INPUT";
            else if (failedGeneration == generation) immediateFailure = "PREVIEW_UNSUPPORTED";
            else {
                // Cancel only work that pre-dates this request. Holding the
                // manager lock prevents the worker from starting the newly
                // queued request before this cancellation completes.
                sourceLoader.cancel();
                int interval = intervalSeconds(durationSeconds);
                long position = bucketSeconds(durationSeconds, positionSeconds, interval);
                long request = ++sequence;
                displaced = latest; latest = null;
                byte[] bytes = cache.get(position);
                if (bytes != null) cached = new Result(bytes, position, interval);
                else { latest = new Pending(generation, request, source, position, interval, callback); ensureDrainLocked(); }
            }
        }
        fail(displaced, "PREVIEW_SUPERSEDED");
        if (immediateFailure != null) callback.failure(immediateFailure);
        else if (cached != null) callback.success(cached);
    }

    /** Revokes the source, queued work and every cached frame without touching disk. */
    public void clear() {
        Pending displaced;
        DecodeTask abort;
        synchronized (lock) {
            generation++; sequence++; source = contentKey = ""; durationSeconds = 0; failedGeneration = -1;
            displaced = latest; latest = null; clearCacheLocked(); abort = activeDecode;
        }
        sourceLoader.cancel(); abortDecode(abort); fail(displaced, "SOURCE_CHANGED");
    }

    @Override public void close() {
        Pending displaced;
        DecodeTask abort;
        synchronized (lock) {
            if (closed) return;
            closed = true; generation++; sequence++; source = contentKey = ""; durationSeconds = 0; failedGeneration = -1;
            displaced = latest; latest = null; clearCacheLocked(); abort = activeDecode;
        }
        sourceLoader.cancel(); abortDecode(abort); fail(displaced, "PREVIEW_CLOSED"); sourceLoader.close();
        worker.shutdownNow(); decoder.shutdownNow(); maybeShutdownReleaser();
    }

    static int intervalSeconds(double duration) {
        int interval = (int) Math.ceil(duration / MAX_FRAMES);
        interval = (interval + 4) / 5 * 5;
        return Math.max(10, interval);
    }
    static long bucketSeconds(double duration, double position, int interval) {
        int count = Math.max(1, (int) Math.ceil(duration / interval));
        int index = Math.min(count - 1, Math.max(0, (int) Math.floor(position / interval)));
        return (long) index * interval;
    }
    int cachedFrameCount() { synchronized (lock) { return cache.size(); } }
    int activeDecoderCount() { synchronized (lock) { return activeDecode == null ? 0 : 1; } }

    private static ThreadPoolExecutor pool(int threads, int capacity, String name) {
        return new ThreadPoolExecutor(threads, threads, 20, TimeUnit.SECONDS, new ArrayBlockingQueue<>(capacity), task -> {
            Thread thread = new Thread(task, name); thread.setDaemon(true); return thread;
        });
    }
    private static PreviewException failure(String code) { return new PreviewException(code); }
    private static String httpsSource(String value) throws PreviewException {
        try {
            String safe = NativeBackend.publicUrl(value);
            if (!"https".equalsIgnoreCase(new URI(safe).getScheme())) throw failure("INVALID_INPUT");
            return safe;
        } catch (PreviewException error) { throw error; }
        catch (Exception invalid) { throw failure("INVALID_INPUT"); }
    }
    private static String contentKey(JSONObject content) throws PreviewException {
        if (content == null) throw failure("INVALID_INPUT");
        String kind = content.optString("kind"), id = content.optString("contentId");
        JSONObject safe = WatchHistory.contentFromCatalog(kind, id,
                "tv".equals(kind) ? Integer.valueOf(content.optInt("season", -1)) : null,
                "tv".equals(kind) ? Integer.valueOf(content.optInt("episode", -1)) : null,
                content.optString("title"));
        if (safe == null || !safe.optString("key").equals(content.optString("key"))
                || !safe.optString("watchUrl").equals(content.optString("watchUrl"))) throw failure("INVALID_INPUT");
        return safe.optString("key");
    }
    private void ensureDrainLocked() {
        if (draining) return;
        draining = true;
        try { worker.execute(this::drain); }
        catch (RejectedExecutionException unavailable) { draining = false; }
    }
    private void drain() {
        while (true) {
            Pending job;
            synchronized (lock) {
                job = latest; latest = null;
                if (job == null) { draining = false; return; }
            }
            process(job);
        }
    }
    private void process(Pending job) {
        byte[] bytes;
        boolean unavailable;
        synchronized (lock) {
            // A decoder or releaser that ignored cancellation is bounded to
            // its existing thread. Do not make later callbacks wait behind it
            // or fetch more bytes that cannot currently be decoded.
            unavailable = failedGeneration == job.generation || activeDecode != null || outstandingReleases >= 2;
            bytes = currentSourceLocked(job) ? cache.get(job.position) : null;
        }
        if (unavailable && bytes == null) { fail(job, currentFailure(job, "PREVIEW_UNSUPPORTED")); return; }
        if (bytes == null) {
            try {
                MediaPayload payload = sourceLoader.load(job.source, job.position, () -> currentSource(job));
                bytes = decode(job, payload);
            } catch (PreviewException unsupported) {
                String fallback = "SOURCE_CHANGED".equals(unsupported.code) ? "SOURCE_CHANGED" : "PREVIEW_UNSUPPORTED";
                fail(job, currentFailure(job, fallback)); return;
            } catch (Exception | LinkageError unsupported) {
                fail(job, currentFailure(job, "PREVIEW_UNSUPPORTED")); return;
            }
            synchronized (lock) { if (currentSourceLocked(job)) putCacheLocked(job.position, bytes); }
        }
        String failure = currentFailure(job, null);
        if (failure != null) fail(job, failure);
        else job.callback.success(new Result(bytes, job.position, job.interval));
    }
    private byte[] decode(Pending job, MediaPayload payload) throws PreviewException {
        if (payload == null || payload.bytes == null || payload.bytes.length == 0) throw failure("PREVIEW_UNSUPPORTED");
        DecodeTask task = new DecodeTask(job.generation, payload);
        try {
            synchronized (lock) {
                if (!currentSourceLocked(job)) throw failure("SOURCE_CHANGED");
                if (activeDecode != null || outstandingReleases >= 2) throw failure("PREVIEW_UNSUPPORTED");
                activeDecode = task;
                task.future = decoder.submit(() -> {
                    synchronized (lock) {
                        if (task.cancelBeforeStart) throw failure("SOURCE_CHANGED");
                        task.started = true;
                    }
                    try { return task.run(); }
                    finally {
                        boolean shutdown;
                        synchronized (lock) { if (activeDecode == task) activeDecode = null; shutdown = closed; }
                        if (shutdown) releaser.shutdown();
                    }
                });
            }
            return task.future.get(decodeTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            // Not permanent: the single active-decode slot already bounds stuck work,
            // and later positions of the same video may decode faster.
            abortDecode(task); throw failure("PREVIEW_UNSUPPORTED");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); abortDecode(task); throw failure("SOURCE_CHANGED");
        } catch (CancellationException cancelled) {
            throw failure("SOURCE_CHANGED");
        } catch (RejectedExecutionException unavailable) {
            synchronized (lock) { if (activeDecode == task) activeDecode = null; }
            requestRelease(task); throw failure("PREVIEW_UNSUPPORTED");
        } catch (ExecutionException unsupported) {
            requestRelease(task); throw failure("PREVIEW_UNSUPPORTED");
        }
    }
    private boolean currentSource(Pending job) { synchronized (lock) { return currentSourceLocked(job); } }
    private boolean currentSourceLocked(Pending job) { return !closed && job.generation == generation && job.source.equals(source); }
    private String currentFailure(Pending job, String fallback) {
        synchronized (lock) {
            if (closed) return "PREVIEW_CLOSED";
            if (!currentSourceLocked(job)) return "SOURCE_CHANGED";
            if (job.sequence != sequence) return "PREVIEW_SUPERSEDED";
            return fallback;
        }
    }
    private void abortDecode(DecodeTask task) {
        if (task == null) return;
        Future<byte[]> future;
        boolean shutdown;
        synchronized (lock) {
            if (!task.started) {
                task.cancelBeforeStart = true;
                if (activeDecode == task) activeDecode = null;
            }
            future = task.future;
            shutdown = closed && activeDecode == null;
        }
        if (future != null) future.cancel(true);
        task.abandon();
        if (shutdown) releaser.shutdown();
    }
    private void requestRelease(DecodeTask task) {
        if (task == null) return;
        task.releaseRequested.set(true); scheduleRelease(task);
    }
    private void scheduleRelease(DecodeTask task) {
        FrameExtractor opened = task.extractor;
        if (opened == null || !task.releaseScheduled.compareAndSet(false, true)) return;
        synchronized (lock) { outstandingReleases++; }
        try {
            releaser.execute(() -> {
                try { opened.close(); } catch (RuntimeException ignored) { }
                finally { synchronized (lock) { outstandingReleases--; } }
            });
        } catch (RejectedExecutionException ignored) {
            synchronized (lock) { outstandingReleases--; }
            // Never create an unbounded release thread during shutdown.
        }
    }
    private void maybeShutdownReleaser() {
        synchronized (lock) { if (closed && activeDecode == null) releaser.shutdown(); }
    }
    private static byte[] encode(Bitmap frame) throws PreviewException {
        if (frame == null || frame.isRecycled() || frame.getWidth() < 1 || frame.getHeight() < 1) throw failure("PREVIEW_UNSUPPORTED");
        Bitmap output;
        try {
            output = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output); canvas.drawColor(Color.BLACK);
            float scale = Math.min((float) WIDTH / frame.getWidth(), (float) HEIGHT / frame.getHeight());
            float width = frame.getWidth() * scale, height = frame.getHeight() * scale;
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(frame, null, new android.graphics.RectF((WIDTH - width) / 2, (HEIGHT - height) / 2,
                    (WIDTH + width) / 2, (HEIGHT + height) / 2), paint);
        } catch (RuntimeException unavailable) { throw failure("PREVIEW_UNSUPPORTED"); }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(24 * 1024);
            if (!output.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bytes)
                    || bytes.size() == 0 || bytes.size() > MAX_FRAME_BYTES) throw failure("PREVIEW_UNSUPPORTED");
            return bytes.toByteArray();
        } finally { output.recycle(); }
    }
    private void putCacheLocked(long position, byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > maximumCacheBytes) return;
        byte[] replaced = cache.put(position, bytes);
        if (replaced != null) cachedBytes -= replaced.length;
        cachedBytes += bytes.length;
        while (cache.size() > MAX_FRAMES || cachedBytes > maximumCacheBytes) {
            Map.Entry<Long, byte[]> oldest = cache.entrySet().iterator().next();
            cachedBytes -= oldest.getValue().length; cache.remove(oldest.getKey());
        }
    }
    private void clearCacheLocked() { cache.clear(); cachedBytes = 0; }
    private static void fail(Pending pending, String code) {
        if (pending == null) return;
        try { pending.callback.failure(code == null ? "PREVIEW_UNSUPPORTED" : code); } catch (RuntimeException ignored) { }
    }

    /** Strict direct-media-playlist parser kept package-private for isolated tests. */
    static HlsSelection parseMediaPlaylist(URI base, byte[] body, double positionSeconds) throws PreviewException {
        if (base == null || body == null || body.length == 0 || body.length > HlsSourceLoader.MAX_PLAYLIST_BYTES
                || !Double.isFinite(positionSeconds) || positionSeconds < 0) throw failure("PREVIEW_UNSUPPORTED");
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
        } catch (Exception invalid) { throw failure("PREVIEW_UNSUPPORTED"); }
        String normalized = text.startsWith("\ufeff") ? text.substring(1) : text;
        normalized = normalized.replace("\r\n", "\n");
        if (normalized.indexOf('\r') >= 0 || normalized.indexOf('\0') >= 0) throw failure("PREVIEW_UNSUPPORTED");
        String[] lines = normalized.split("\n", -1);
        if (lines.length == 0 || !"#EXTM3U".equals(lines[0].trim())) throw failure("PREVIEW_UNSUPPORTED");
        URI currentMap = null;
        Double pendingDuration = null;
        boolean endList = false;
        int segments = 0;
        double start = 0;
        HlsSelection selected = null, last = null;
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index].trim();
            if (line.length() > 8192) throw failure("PREVIEW_UNSUPPORTED");
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXT-X-STREAM-INF") || line.startsWith("#EXT-X-I-FRAMES-ONLY")
                    || line.startsWith("#EXT-X-BYTERANGE")) throw failure("PREVIEW_UNSUPPORTED");
            if (line.startsWith("#EXT-X-KEY:")) {
                String method = attributes(line.substring(11)).get("METHOD");
                if (method == null || !"NONE".equalsIgnoreCase(method)) throw failure("PREVIEW_UNSUPPORTED");
            } else if (line.startsWith("#EXT-X-MAP:")) {
                Map<String, String> values = attributes(line.substring(11));
                if (values.containsKey("BYTERANGE")) throw failure("PREVIEW_UNSUPPORTED");
                currentMap = mediaUri(base, values.get("URI"));
            } else if (line.startsWith("#EXTINF:")) {
                if (pendingDuration != null) throw failure("PREVIEW_UNSUPPORTED");
                String value = line.substring(8).split(",", 2)[0];
                if (!value.matches("[0-9]+(?:\\.[0-9]+)?")) throw failure("PREVIEW_UNSUPPORTED");
                double duration;
                try { duration = Double.parseDouble(value); } catch (NumberFormatException invalid) { throw failure("PREVIEW_UNSUPPORTED"); }
                if (!Double.isFinite(duration) || duration <= 0 || duration > 3600) throw failure("PREVIEW_UNSUPPORTED");
                pendingDuration = duration;
            } else if ("#EXT-X-ENDLIST".equals(line)) {
                endList = true;
            } else if (!line.startsWith("#")) {
                if (pendingDuration == null || ++segments > 4096) throw failure("PREVIEW_UNSUPPORTED");
                URI segment = mediaUri(base, line);
                double duration = pendingDuration;
                double local = Math.max(0, Math.min(duration - .001, positionSeconds - start));
                HlsSelection candidate = new HlsSelection(currentMap, segment, Math.max(0, (long) (local * 1000000d)));
                last = candidate;
                if (selected == null && positionSeconds < start + duration) selected = candidate;
                start += duration;
                if (!Double.isFinite(start) || start > 172800) throw failure("PREVIEW_UNSUPPORTED");
                pendingDuration = null;
            }
        }
        if (!endList || pendingDuration != null || segments == 0) throw failure("PREVIEW_UNSUPPORTED");
        return selected == null ? last : selected;
    }
    private static Map<String, String> attributes(String raw) throws PreviewException {
        Map<String, String> result = new LinkedHashMap<>();
        int cursor = 0;
        while (cursor < raw.length()) {
            int equals = raw.indexOf('=', cursor);
            if (equals <= cursor) throw failure("PREVIEW_UNSUPPORTED");
            String key = raw.substring(cursor, equals);
            if (!key.matches("[A-Z0-9-]+") || result.containsKey(key)) throw failure("PREVIEW_UNSUPPORTED");
            cursor = equals + 1;
            String value;
            if (cursor < raw.length() && raw.charAt(cursor) == '"') {
                int end = raw.indexOf('"', cursor + 1);
                if (end < 0) throw failure("PREVIEW_UNSUPPORTED");
                value = raw.substring(cursor + 1, end); cursor = end + 1;
            } else {
                int comma = raw.indexOf(',', cursor);
                int end = comma < 0 ? raw.length() : comma;
                value = raw.substring(cursor, end); cursor = end;
            }
            if (value.isEmpty()) throw failure("PREVIEW_UNSUPPORTED");
            result.put(key, value);
            if (cursor == raw.length()) break;
            if (raw.charAt(cursor) != ',') throw failure("PREVIEW_UNSUPPORTED");
            cursor++;
        }
        return result;
    }
    private static URI mediaUri(URI base, String value) throws PreviewException {
        if (value == null || value.isEmpty() || value.length() > 8192) throw failure("PREVIEW_UNSUPPORTED");
        try {
            URI result = base.resolve(value);
            String safe = NativeBackend.publicUrl(result.toString());
            URI parsed = new URI(safe);
            if (!"https".equalsIgnoreCase(parsed.getScheme())) throw failure("PREVIEW_UNSUPPORTED");
            return parsed;
        } catch (PreviewException error) { throw error; }
        catch (Exception invalid) { throw failure("PREVIEW_UNSUPPORTED"); }
    }
    static URI redirectTarget(URI current, String location) throws PreviewException {
        if (current == null || location == null || location.isEmpty() || location.length() > 8192) throw failure("PREVIEW_UNSUPPORTED");
        return mediaUri(current, location);
    }
    static boolean publicAddress(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        int a = bytes[0] & 255, b = bytes[1] & 255;
        if (bytes.length == 4) {
            int c = bytes[2] & 255;
            return !(a == 0 || a >= 224 || (a == 100 && b >= 64 && b <= 127)
                    || (a == 192 && (b == 0 && (c == 0 || c == 2) || b == 88 && c == 99))
                    || a == 198 && (b == 18 || b == 19 || b == 51 && c == 100) || a == 203 && b == 0 && c == 113);
        }
        int first = a * 256 + b, second = (bytes[2] & 255) * 256 + (bytes[3] & 255);
        return first >= 0x2000 && first < 0x4000 && first != 0x2002 && first != 0x3fff
                && !(first == 0x2001 && (second < 0x0200 || second == 0x0db8));
    }
    static InetSocketAddress pinnedEndpoint(InetAddress approved, int port) throws PreviewException {
        if (!publicAddress(approved) || port < 1 || port > 65535) throw failure("PREVIEW_UNSUPPORTED");
        // The resolved InetAddress constructor is essential: using the URI's
        // hostname here would perform a second, attacker-raceable DNS lookup.
        return new InetSocketAddress(approved, port);
    }

    private static final class HlsSourceLoader implements SourceLoader {
        static final int MAX_PLAYLIST_BYTES = 512 * 1024;
        private static final int MAX_MAP_BYTES = 2 * 1024 * 1024;
        private static final int MAX_SEGMENT_BYTES = 12 * 1024 * 1024;
        private static final int MAX_TOTAL_BYTES = 15 * 1024 * 1024;
        private static final long TOTAL_TIMEOUT_MILLIS = 5000, DNS_TIMEOUT_MILLIS = 1000;
        private final AtomicReference<FetchJob> active = new AtomicReference<>();
        private final ThreadPoolExecutor resolver = pool(1, 1, "Scrub DNS");
        private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "Scrub fetch deadline"); thread.setDaemon(true); return thread;
        });

        private static final class FetchJob {
            final AtomicReference<Socket> socket = new AtomicReference<>();
            volatile boolean cancelled, timedOut;
            void cancel() {
                cancelled = true;
                Socket open = socket.get();
                if (open != null) try { open.close(); } catch (IOException ignored) { }
            }
            void timeout() { timedOut = true; cancel(); }
        }
        private static final class Budget {
            final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TOTAL_TIMEOUT_MILLIS);
            int bytes;
            int remainingMillis() throws PreviewException {
                long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remaining <= 0) throw failure("PREVIEW_UNSUPPORTED");
                return (int) Math.min(Integer.MAX_VALUE, remaining);
            }
            void add(int count) throws PreviewException {
                if (count < 0 || bytes > MAX_TOTAL_BYTES - count) throw failure("PREVIEW_UNSUPPORTED");
                bytes += count;
            }
        }
        private static final class Body {
            final URI finalUri;
            final byte[] bytes;
            Body(URI finalUri, byte[] bytes) { this.finalUri = finalUri; this.bytes = bytes; }
        }
        private static final class Headers {
            final int status;
            final Map<String, String> values;
            Headers(int status, Map<String, String> values) { this.status = status; this.values = values; }
        }
        private static final class Response {
            final int status;
            final String location;
            final byte[] bytes;
            Response(int status, String location, byte[] bytes) {
                this.status = status; this.location = location; this.bytes = bytes;
            }
        }

        @Override public MediaPayload load(String source, long positionSeconds, BooleanSupplier current) throws PreviewException {
            FetchJob job = new FetchJob();
            if (!active.compareAndSet(null, job)) throw failure("PREVIEW_UNSUPPORTED");
            Budget budget = new Budget();
            ScheduledFuture<?> deadline;
            try { deadline = deadlines.schedule(job::timeout, TOTAL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS); }
            catch (RejectedExecutionException unavailable) { active.compareAndSet(job, null); throw failure("PREVIEW_UNSUPPORTED"); }
            try {
                Body playlist = fetch(new URI(source), MAX_PLAYLIST_BYTES, budget, current, job);
                HlsSelection selection = parseMediaPlaylist(playlist.finalUri, playlist.bytes, positionSeconds);
                byte[] initialization = selection.initialization == null ? null
                        : fetch(selection.initialization, MAX_MAP_BYTES, budget, current, job).bytes;
                byte[] segment = fetch(selection.segment, MAX_SEGMENT_BYTES, budget, current, job).bytes;
                check(current, job, budget);
                if (initialization == null) return new MediaPayload(segment, selection.timeUs);
                if (initialization.length > MAX_TOTAL_BYTES - segment.length) throw failure("PREVIEW_UNSUPPORTED");
                ByteArrayOutputStream combined = new ByteArrayOutputStream(initialization.length + segment.length);
                combined.write(initialization, 0, initialization.length); combined.write(segment, 0, segment.length);
                return new MediaPayload(combined.toByteArray(), selection.timeUs);
            } catch (PreviewException error) { throw error; }
            catch (Exception unsupported) { throw failure("PREVIEW_UNSUPPORTED"); }
            finally { deadline.cancel(false); job.cancel(); active.compareAndSet(job, null); }
        }
        private Body fetch(URI initial, int maximumBytes, Budget budget, BooleanSupplier current, FetchJob job) throws PreviewException {
            URI address = initial;
            for (int redirect = 0; redirect <= 4; redirect++) {
                check(current, job, budget);
                InetAddress[] approved = resolveDestination(address, budget);
                Response response = request(address, approved, maximumBytes, budget, current, job);
                check(current, job, budget);
                if (response.status == 301 || response.status == 302 || response.status == 303
                        || response.status == 307 || response.status == 308) {
                    if (redirect == 4) throw failure("PREVIEW_UNSUPPORTED");
                    address = redirectTarget(address, response.location);
                    continue;
                }
                if (response.status != 200 || response.bytes == null || response.bytes.length == 0) throw failure("PREVIEW_UNSUPPORTED");
                return new Body(address, response.bytes);
            }
            throw failure("PREVIEW_UNSUPPORTED");
        }
        private InetAddress[] resolveDestination(URI uri, Budget budget) throws PreviewException {
            try {
                String safe = NativeBackend.publicUrl(uri.toString());
                URI parsed = new URI(safe);
                if (!"https".equalsIgnoreCase(parsed.getScheme())) throw failure("PREVIEW_UNSUPPORTED");
                String host = normalizedHost(parsed);
                Future<InetAddress[]> lookup;
                try { lookup = resolver.submit(() -> InetAddress.getAllByName(host)); }
                catch (RejectedExecutionException unavailable) { throw failure("PREVIEW_UNSUPPORTED"); }
                InetAddress[] addresses;
                try { addresses = lookup.get(Math.min(DNS_TIMEOUT_MILLIS, budget.remainingMillis()), TimeUnit.MILLISECONDS); }
                catch (TimeoutException timeout) { lookup.cancel(true); throw failure("PREVIEW_UNSUPPORTED"); }
                catch (InterruptedException interrupted) { lookup.cancel(true); Thread.currentThread().interrupt(); throw failure("SOURCE_CHANGED"); }
                catch (ExecutionException unavailable) { throw failure("PREVIEW_UNSUPPORTED"); }
                if (addresses.length == 0 || addresses.length > 8) throw failure("PREVIEW_UNSUPPORTED");
                for (InetAddress address : addresses) if (!publicAddress(address)) throw failure("PREVIEW_UNSUPPORTED");
                return addresses;
            } catch (PreviewException error) { throw error; }
            catch (Exception invalid) { throw failure("PREVIEW_UNSUPPORTED"); }
        }
        private Response request(URI uri, InetAddress[] approved, int maximumBytes, Budget budget,
                BooleanSupplier current, FetchJob job) throws PreviewException {
            String host = normalizedHost(uri);
            String tlsHost = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
            int port = uri.getPort() < 0 ? 443 : uri.getPort();
            for (InetAddress address : approved) {
                Socket raw = null;
                SSLSocket secure = null;
                try {
                    check(current, job, budget);
                    raw = new Socket(); job.socket.set(raw);
                    raw.connect(pinnedEndpoint(address, port), Math.min(2500, budget.remainingMillis()));
                    check(current, job, budget);
                    secure = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                            .createSocket(raw, tlsHost, port, true);
                    job.socket.set(secure);
                    SSLParameters parameters = secure.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    if (!tlsHost.matches("[0-9.]+") && !tlsHost.contains(":")) {
                        parameters.setServerNames(Collections.singletonList(new SNIHostName(tlsHost)));
                    }
                    secure.setSSLParameters(parameters);
                    secure.setSoTimeout(Math.min(2500, budget.remainingMillis()));
                    secure.startHandshake();
                    check(current, job, budget);
                    writeRequest(secure.getOutputStream(), uri, tlsHost, port);
                    Headers headers = readHeaders(secure, secure.getInputStream(), budget, current, job);
                    if (headers.status == 301 || headers.status == 302 || headers.status == 303
                            || headers.status == 307 || headers.status == 308) {
                        return new Response(headers.status, headers.values.get("location"), null);
                    }
                    if (headers.status != 200) return new Response(headers.status, null, null);
                    String encoding = headers.values.get("content-encoding");
                    if (encoding != null && !encoding.isEmpty() && !"identity".equalsIgnoreCase(encoding)) {
                        throw failure("PREVIEW_UNSUPPORTED");
                    }
                    byte[] body = readBody(secure, secure.getInputStream(), headers.values, maximumBytes, budget, current, job);
                    check(current, job, budget);
                    return new Response(headers.status, null, body);
                } catch (PreviewException error) { throw error; }
                catch (Exception unavailable) {
                    check(current, job, budget);
                    // Try another member of the already-approved DNS answer.
                } finally {
                    Socket owned = secure != null ? secure : raw;
                    if (owned != null) try { owned.close(); } catch (IOException ignored) { }
                    if (owned != null) job.socket.compareAndSet(owned, null);
                    if (raw != null) job.socket.compareAndSet(raw, null);
                }
            }
            throw failure("PREVIEW_UNSUPPORTED");
        }
        private static String normalizedHost(URI uri) throws PreviewException {
            String host = uri == null ? null : uri.getHost();
            if (host == null) throw failure("PREVIEW_UNSUPPORTED");
            if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
            if (host.isEmpty()) throw failure("PREVIEW_UNSUPPORTED");
            return host;
        }
        private static void writeRequest(OutputStream output, URI uri, String host, int port) throws IOException {
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
            String authority = host.contains(":") ? "[" + host + "]" : host;
            if (port != 443) authority += ":" + port;
            String request = "GET " + path + " HTTP/1.1\r\nHost: " + authority
                    + "\r\nAccept: */*\r\nAccept-Encoding: identity\r\nConnection: close\r\nUser-Agent: LUMA-Preview/1\r\n\r\n";
            output.write(request.getBytes(StandardCharsets.US_ASCII)); output.flush();
        }
        private static Headers readHeaders(Socket socket, InputStream input, Budget budget,
                BooleanSupplier current, FetchJob job) throws Exception {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int state = 0;
            while (bytes.size() < 16 * 1024) {
                socket.setSoTimeout(Math.min(2500, budget.remainingMillis()));
                int value = input.read();
                if (value < 0 || value > 126 || value < 32 && value != '\r' && value != '\n' && value != '\t') {
                    throw failure("PREVIEW_UNSUPPORTED");
                }
                budget.add(1); check(current, job, budget); bytes.write(value);
                if (state == 0) {
                    if (value == '\r') state = 1;
                    else if (value == '\n') throw failure("PREVIEW_UNSUPPORTED");
                } else if (state == 1) {
                    if (value != '\n') throw failure("PREVIEW_UNSUPPORTED");
                    state = 2;
                } else if (state == 2) {
                    if (value == '\r') state = 3;
                    else if (value == '\n') throw failure("PREVIEW_UNSUPPORTED");
                    else state = 0;
                } else if (value == '\n') {
                    break;
                } else throw failure("PREVIEW_UNSUPPORTED");
            }
            if (state != 3 || bytes.size() >= 16 * 1024) throw failure("PREVIEW_UNSUPPORTED");
            String[] lines = bytes.toString(StandardCharsets.US_ASCII.name()).split("\\r\\n", -1);
            if (lines.length < 3 || !lines[0].matches("HTTP/1\\.[01] [0-9]{3}(?: .*)?")) throw failure("PREVIEW_UNSUPPORTED");
            int status = Integer.parseInt(lines[0].substring(9, 12));
            Map<String, String> values = new LinkedHashMap<>();
            for (int index = 1; index < lines.length - 2; index++) {
                String line = lines[index];
                int colon = line.indexOf(':');
                if (colon <= 0 || line.charAt(0) == ' ' || line.charAt(0) == '\t') throw failure("PREVIEW_UNSUPPORTED");
                String name = line.substring(0, colon).toLowerCase(java.util.Locale.ROOT);
                if (!name.matches("[!#$%&'*+.^_`|~0-9a-z-]+")) throw failure("PREVIEW_UNSUPPORTED");
                String value = line.substring(colon + 1).trim();
                if (values.containsKey(name) && ("content-length".equals(name) || "transfer-encoding".equals(name)
                        || "content-encoding".equals(name) || "location".equals(name))) throw failure("PREVIEW_UNSUPPORTED");
                values.putIfAbsent(name, value);
            }
            return new Headers(status, values);
        }
        private static byte[] readBody(Socket socket, InputStream input, Map<String, String> headers, int maximumBytes,
                Budget budget, BooleanSupplier current, FetchJob job) throws Exception {
            String transfer = headers.get("transfer-encoding"), lengthValue = headers.get("content-length");
            if (transfer != null && lengthValue != null) throw failure("PREVIEW_UNSUPPORTED");
            if (transfer != null && !"chunked".equalsIgnoreCase(transfer.trim())) throw failure("PREVIEW_UNSUPPORTED");
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(32 * 1024, maximumBytes));
            if (transfer != null) {
                while (true) {
                    String line = readLine(socket, input, 128, budget, current, job);
                    String size = line.split(";", 2)[0];
                    if (!size.matches("[0-9A-Fa-f]{1,8}")) throw failure("PREVIEW_UNSUPPORTED");
                    long count = Long.parseLong(size, 16);
                    if (count == 0) {
                        int trailers = 0;
                        String trailer;
                        do { trailer = readLine(socket, input, 8192 - trailers, budget, current, job); trailers += trailer.length() + 2; }
                        while (!trailer.isEmpty() && trailers < 8192);
                        if (!trailer.isEmpty()) throw failure("PREVIEW_UNSUPPORTED");
                        break;
                    }
                    if (count > maximumBytes - output.size()) throw failure("PREVIEW_UNSUPPORTED");
                    readExact(socket, input, output, (int) count, budget, current, job);
                    if (readByte(socket, input, budget, current, job) != '\r'
                            || readByte(socket, input, budget, current, job) != '\n') throw failure("PREVIEW_UNSUPPORTED");
                }
            } else if (lengthValue != null) {
                if (!lengthValue.matches("[0-9]{1,10}")) throw failure("PREVIEW_UNSUPPORTED");
                long length = Long.parseLong(lengthValue);
                if (length > maximumBytes || length > MAX_TOTAL_BYTES - budget.bytes) throw failure("PREVIEW_UNSUPPORTED");
                readExact(socket, input, output, (int) length, budget, current, job);
            } else {
                byte[] chunk = new byte[16 * 1024];
                while (true) {
                    socket.setSoTimeout(Math.min(2500, budget.remainingMillis()));
                    int count = input.read(chunk);
                    if (count < 0) break;
                    check(current, job, budget);
                    if (count == 0) continue;
                    if (output.size() > maximumBytes - count) throw failure("PREVIEW_UNSUPPORTED");
                    budget.add(count); output.write(chunk, 0, count);
                }
            }
            return output.toByteArray();
        }
        private static void readExact(Socket socket, InputStream input, ByteArrayOutputStream output, int total,
                Budget budget, BooleanSupplier current, FetchJob job) throws Exception {
            byte[] chunk = new byte[Math.min(16 * 1024, Math.max(1, total))];
            int remaining = total;
            while (remaining > 0) {
                socket.setSoTimeout(Math.min(2500, budget.remainingMillis()));
                int count = input.read(chunk, 0, Math.min(chunk.length, remaining));
                if (count <= 0) throw failure("PREVIEW_UNSUPPORTED");
                budget.add(count); check(current, job, budget); output.write(chunk, 0, count); remaining -= count;
            }
        }
        private static String readLine(Socket socket, InputStream input, int maximum, Budget budget,
                BooleanSupplier current, FetchJob job) throws Exception {
            if (maximum < 1) throw failure("PREVIEW_UNSUPPORTED");
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            while (line.size() < maximum) {
                int value = readByte(socket, input, budget, current, job);
                if (value == '\r') {
                    if (readByte(socket, input, budget, current, job) != '\n') throw failure("PREVIEW_UNSUPPORTED");
                    return line.toString(StandardCharsets.US_ASCII.name());
                }
                if (value < 32 || value > 126) throw failure("PREVIEW_UNSUPPORTED");
                line.write(value);
            }
            throw failure("PREVIEW_UNSUPPORTED");
        }
        private static int readByte(Socket socket, InputStream input, Budget budget,
                BooleanSupplier current, FetchJob job) throws Exception {
            socket.setSoTimeout(Math.min(2500, budget.remainingMillis()));
            int value = input.read();
            if (value < 0) throw failure("PREVIEW_UNSUPPORTED");
            budget.add(1); check(current, job, budget); return value;
        }
        private static void check(BooleanSupplier current, FetchJob job, Budget budget) throws PreviewException {
            boolean valid;
            try { valid = current != null && current.getAsBoolean(); } catch (RuntimeException ignored) { valid = false; }
            if (job.timedOut) throw failure("PREVIEW_UNSUPPORTED");
            if (!valid || job.cancelled || Thread.currentThread().isInterrupted()) throw failure("SOURCE_CHANGED");
            budget.remainingMillis();
        }
        @Override public void cancel() { FetchJob job = active.get(); if (job != null) job.cancel(); }
        @Override public void close() { cancel(); resolver.shutdownNow(); deadlines.shutdownNow(); }
    }
}

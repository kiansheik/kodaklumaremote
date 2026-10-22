package dev.framextv.lumaremote;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPInputStream;

/** Selected, declared captions only. Normalized RAM slots are served by MsxServer. */
public final class TvSubtitleBridge implements AutoCloseable {
    public interface UrlValidator { String validate(String value) throws Exception; }
    interface Fetcher { Body fetch(String url, BooleanSupplier current) throws Exception; }
    static final class Body {
        final byte[] bytes; final String type;
        Body(byte[] bytes, String type) { this.bytes = bytes; this.type = type; }
    }
    public static final class BridgeException extends Exception {
        public final String code;
        BridgeException(String code) { super(code); this.code = code; }
    }
    public static final class Prepared {
        public final String id, format, encoding;
        public final int cueCount, discardedCueCount;
        private Prepared(String id, SubtitleNormalizer.Result normalized) {
            this.id = id; format = normalized.format; encoding = normalized.encoding; cueCount = normalized.cueCount; discardedCueCount = normalized.discardedCueCount;
        }
    }
    private static final int MAX_BYTES = 2 * 1024 * 1024, MAX_CACHE = 16 * 1024 * 1024, MAX_SLOTS = 128, TIMEOUT = 8000;
    private final Object lock = new Object();
    private final UrlValidator validator;
    private final Fetcher fixtureFetcher;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Set<Job> jobs = new HashSet<>();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 20, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), task -> { Thread thread = new Thread(task, "TV subtitle fetch"); thread.setDaemon(true); return thread; });
    private long epoch;
    private int cachedBytes;
    private boolean closed;
    private static final class Entry {
        final String source, original, peer; final byte[] bytes; final Prepared prepared;
        volatile BooleanSupplier current; int requests;
        Entry(String source, String original, String peer, SubtitleNormalizer.Result data, Prepared prepared, BooleanSupplier current) {
            this.source = source; this.original = original; this.peer = peer; bytes = data.srt; this.prepared = prepared; this.current = current;
        }
    }
    private static final class Job {
        final AtomicReference<HttpURLConnection> connection = new AtomicReference<>();
        volatile boolean cancelled; Future<Body> future;
        void cancel() { cancelled = true; HttpURLConnection active = connection.get(); if (active != null) active.disconnect(); if (future != null) future.cancel(true); }
    }
    public TvSubtitleBridge(UrlValidator validator) { this(validator, null); }
    /** Package-private deterministic fixture; no production RPC selects a fetcher. */
    TvSubtitleBridge(UrlValidator validator, Fetcher fixtureFetcher) {
        if (validator == null) throw new IllegalArgumentException("Subtitle validator required");
        this.validator = validator; this.fixtureFetcher = fixtureFetcher;
    }
    private static BridgeException failure(String code) { return new BridgeException(code); }
    private static boolean current(BooleanSupplier guard) { try { return guard != null && guard.getAsBoolean(); } catch (RuntimeException ignored) { return false; } }
    private void check(long generation, BooleanSupplier guard) throws BridgeException {
        if (!current(guard)) throw failure("SOURCE_CHANGED");
        synchronized (lock) { if (closed || generation != epoch) throw failure("SOURCE_CHANGED"); }
    }
    private String validate(String value) throws BridgeException {
        try { return validator.validate(value); } catch (Exception ignored) { throw failure("INVALID_SUBTITLE"); }
    }
    public Prepared prepare(String sourceUrl, String originalUrl, String peer, BooleanSupplier guard) throws BridgeException {
        String source = validate(sourceUrl), original = validate(originalUrl);
        if (!MsxServer.privateAddress(peer)) throw failure("SUBTITLE_NETWORK");
        final long generation; synchronized (lock) { generation = epoch; }
        check(generation, guard);
        synchronized (lock) {
            if (closed || generation != epoch) throw failure("SOURCE_CHANGED");
            for (Entry entry : entries.values()) if (entry.source.equals(source) && entry.original.equals(original) && entry.peer.equals(peer)) {
                entry.current = guard; entry.requests = 0; return entry.prepared;
            }
            if (entries.size() >= MAX_SLOTS) throw failure("SUBTITLE_TOO_LARGE");
        }
        Job job = new Job();
        synchronized (lock) { if (closed || generation != epoch) throw failure("SOURCE_CHANGED"); jobs.add(job); }
        Body body;
        try {
            job.future = workers.submit(() -> fixtureFetcher == null ? fetch(original, generation, guard, job) : fixtureFetcher.fetch(original, guard));
            if (job.cancelled) job.future.cancel(true);
            body = job.future.get(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); throw failure("SOURCE_CHANGED"); }
        catch (ExecutionException error) { if (error.getCause() instanceof BridgeException) throw (BridgeException) error.getCause(); throw failure("SUBTITLE_FETCH"); }
        catch (TimeoutException | RuntimeException ignored) { check(generation, guard); throw failure("SUBTITLE_FETCH"); }
        finally { job.cancel(); synchronized (lock) { jobs.remove(job); } }
        check(generation, guard);
        SubtitleNormalizer.Result normalized;
        try { normalized = SubtitleNormalizer.normalize(body.bytes, body.type); }
        catch (SubtitleNormalizer.NormalizeException error) { throw failure(error.code); }
        if (normalized.srt.length == 0 || normalized.srt.length > MAX_BYTES) throw failure("SUBTITLE_TOO_LARGE");
        check(generation, guard);
        synchronized (lock) {
            if (closed || generation != epoch) throw failure("SOURCE_CHANGED");
            for (Entry entry : entries.values()) if (entry.source.equals(source) && entry.original.equals(original) && entry.peer.equals(peer)) {
                entry.current = guard; entry.requests = 0; return entry.prepared;
            }
            if (entries.size() >= MAX_SLOTS || normalized.srt.length > MAX_CACHE - cachedBytes) throw failure("SUBTITLE_TOO_LARGE");
            byte[] secret = new byte[32]; random.nextBytes(secret); StringBuilder id = new StringBuilder(64);
            for (byte part : secret) id.append(String.format(java.util.Locale.ROOT, "%02x", part & 255));
            Prepared prepared = new Prepared(id.toString(), normalized);
            entries.put(prepared.id, new Entry(source, original, peer, normalized, prepared, guard)); cachedBytes += normalized.srt.length;
            return prepared;
        }
    }
    byte[] read(String id, String peer, boolean head) throws BridgeException {
        Entry entry;
        synchronized (lock) { entry = entries.get(id); if (closed || entry == null || !entry.peer.equals(peer) || entry.requests >= 16) throw failure("SUBTITLE_UNAVAILABLE"); }
        if (!current(entry.current)) throw failure("SOURCE_CHANGED");
        synchronized (lock) {
            if (closed || entries.get(id) != entry) throw failure("SOURCE_CHANGED");
            if (!head) { if (entry.requests >= 16) throw failure("SUBTITLE_UNAVAILABLE"); entry.requests++; }
            return entry.bytes.clone();
        }
    }
    private Body fetch(String initial, long generation, BooleanSupplier guard, Job job) throws Exception {
        String address = initial; long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT);
        for (int redirect = 0; redirect <= 4; redirect++) {
            check(generation, guard); if (job.cancelled) throw failure("SOURCE_CHANGED");
            HttpURLConnection connection = null;
            try {
                URI uri = new URI(validate(address));
                if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null) throw failure("INVALID_SUBTITLE");
                for (InetAddress resolved : InetAddress.getAllByName(uri.getHost())) if (!publicAddress(resolved)) throw failure("SUBTITLE_NETWORK");
                int remaining = (int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                check(generation, guard); if (job.cancelled || System.nanoTime() >= deadline) throw failure("SUBTITLE_FETCH");
                connection = (HttpURLConnection) uri.toURL().openConnection(Proxy.NO_PROXY); job.connection.set(connection);
                connection.setConnectTimeout(remaining); connection.setReadTimeout(remaining); connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
                connection.setRequestProperty("Accept-Encoding", "gzip"); connection.setRequestProperty("Cookie", "");
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (redirect == 4 || location == null || location.length() > 8192) throw failure("SUBTITLE_FETCH");
                    URI next = uri.resolve(location);
                    if ("https".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(next.getScheme())) throw failure("SUBTITLE_FETCH");
                    address = validate(next.toString()); continue;
                }
                if (status != 200) throw failure("SUBTITLE_FETCH");
                if (connection.getContentLengthLong() > MAX_BYTES) throw failure("SUBTITLE_TOO_LARGE");
                String encoding = connection.getHeaderField("Content-Encoding");
                try (InputStream input = connection.getInputStream()) {
                    InputStream decoded = input;
                    if (encoding != null && !encoding.trim().isEmpty() && !"identity".equalsIgnoreCase(encoding.trim())) {
                        if (!"gzip".equalsIgnoreCase(encoding.trim())) throw failure("SUBTITLE_FORMAT");
                        decoded = new GZIPInputStream(input);
                    }
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
                    while ((count = decoded.read(buffer)) >= 0) {
                        check(generation, guard); if (job.cancelled || System.nanoTime() >= deadline) throw failure("SUBTITLE_FETCH");
                        if (bytes.size() + count > MAX_BYTES) throw failure("SUBTITLE_TOO_LARGE"); bytes.write(buffer, 0, count);
                    }
                    check(generation, guard); return new Body(bytes.toByteArray(), connection.getContentType());
                }
            } finally { if (connection != null) connection.disconnect(); job.connection.set(null); }
        }
        throw failure("SUBTITLE_FETCH");
    }
    private static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress(); int a = bytes[0] & 255, b = bytes[1] & 255;
        if (bytes.length == 4) {
            int c = bytes[2] & 255;
            return !(a == 0 || a >= 224 || a == 100 && b >= 64 && b <= 127 || a == 192 && (b == 0 && (c == 0 || c == 2) || b == 88 && c == 99)
                    || a == 198 && (b == 18 || b == 19 || b == 51 && c == 100) || a == 203 && b == 0 && c == 113);
        }
        int first = a * 256 + b, second = (bytes[2] & 255) * 256 + (bytes[3] & 255);
        return first >= 0x2000 && first < 0x4000 && first != 0x2002 && first != 0x3fff && !(first == 0x2001 && (second < 0x0200 || second == 0x0db8));
    }
    public void revokeAll() {
        synchronized (lock) { epoch++; entries.clear(); cachedBytes = 0; for (Job job : jobs) job.cancel(); jobs.clear(); }
    }
    @Override public void close() { synchronized (lock) { closed = true; } revokeAll(); workers.shutdownNow(); }
}

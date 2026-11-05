package dev.framextv.lumaremote;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Independently validates a recently observed media URL without browser state. */
final class LiveMediaVerifier implements AutoCloseable {
    static final class Result {
        final String url, contentType;
        final Map<String, String> headers;
        Result(String url, String contentType, Map<String, String> headers) {
            this.url = url; this.contentType = contentType; this.headers = headers;
        }
    }

    interface Callback {
        void success(Result result);
        void failure();
    }

    private static final int MAX_CANDIDATES = 8;
    private static final int MAX_PROBE_BYTES = 256 * 1024;
    private static final long TOTAL_TIMEOUT_MILLIS = 7_500;
    private final ThreadPoolExecutor worker = pool("Live media check");
    private final ThreadPoolExecutor resolver = pool("Live media DNS");
    private final AtomicReference<Future<?>> active = new AtomicReference<>();
    private volatile boolean closed;

    void verify(List<LiveMediaObserver.Candidate> candidates, BooleanSupplier current, Callback callback) {
        cancel();
        if (closed || candidates == null || candidates.isEmpty()) { callback.failure(); return; }
        Future<?> task;
        try {
            task = worker.submit(() -> {
                Result result = null;
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TOTAL_TIMEOUT_MILLIS);
                int limit = Math.min(MAX_CANDIDATES, candidates.size());
                for (int index = 0; index < limit && result == null; index++) {
                    if (!usable(current, deadline)) break;
                    try { result = probe(candidates.get(index), current, deadline); }
                    catch (Exception ignored) { /* Try another bounded observation. */ }
                }
                if (Thread.currentThread().isInterrupted() || !current.getAsBoolean()) return;
                if (result == null) callback.failure(); else callback.success(result);
            });
        } catch (RuntimeException unavailable) { callback.failure(); return; }
        Future<?> replaced = active.getAndSet(task);
        if (replaced != null && replaced != task) replaced.cancel(true);
    }

    void cancel() {
        Future<?> job = active.getAndSet(null);
        if (job != null) job.cancel(true);
    }

    @Override public void close() {
        closed = true;
        cancel();
        worker.shutdownNow();
        resolver.shutdownNow();
    }

    private Result probe(LiveMediaObserver.Candidate candidate, BooleanSupplier current, long deadline) throws Exception {
        URI address = safeHttps(candidate.url);
        for (int redirects = 0; redirects <= 4; redirects++) {
            check(current, deadline);
            validateResolution(address, deadline);
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(address.toASCIIString()).openConnection(Proxy.NO_PROXY);
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setConnectTimeout(remaining(deadline, 2500));
                connection.setReadTimeout(remaining(deadline, 2500));
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("User-Agent", "LUMA/0.4 media verifier");
                for (Map.Entry<String, String> header : candidate.headers.entrySet())
                    connection.setRequestProperty(header.getKey(), header.getValue());
                if (LiveMediaObserverType.HLS.equals(candidate.contentType))
                    connection.setRequestProperty("Accept", "application/vnd.apple.mpegurl, application/x-mpegurl, */*;q=0.5");
                else if (LiveMediaObserverType.DASH.equals(candidate.contentType))
                    connection.setRequestProperty("Accept", "application/dash+xml, application/xml, */*;q=0.5");
                else if (!candidate.contentType.isEmpty()) {
                    connection.setRequestProperty("Accept", candidate.contentType + ", */*;q=0.5");
                    connection.setRequestProperty("Range", "bytes=0-262143");
                } else connection.setRequestProperty("Accept", "*/*");
                int status = connection.getResponseCode();
                if (redirect(status)) {
                    if (redirects == 4) throw new Exception("redirect limit");
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.isEmpty() || location.length() > 8192) throw new Exception("redirect");
                    address = safeHttps(address.resolve(location).toString());
                    continue;
                }
                if (!(status == 200 || status == 206)) throw new Exception("status");
                String encoding = connection.getHeaderField("Content-Encoding");
                if (encoding != null && !encoding.isEmpty() && !"identity".equalsIgnoreCase(encoding)) throw new Exception("encoding");
                String declared = connection.getContentType();
                if (declared != null && declared.toLowerCase(Locale.ROOT).startsWith("text/html")) throw new Exception("html");
                byte[] prefix;
                try (InputStream input = connection.getInputStream()) { prefix = readBounded(input, MAX_PROBE_BYTES, current, deadline); }
                String contentType = detect(candidate.contentType, declared, prefix);
                if (contentType == null) throw new Exception("not media");
                return new Result(address.toASCIIString(), contentType, candidate.headers);
            } finally { if (connection != null) connection.disconnect(); }
        }
        throw new Exception("unreachable");
    }

    /** Package-visible for deterministic format tests. */
    static String detect(String hint, String declared, byte[] prefix) {
        if (prefix == null || prefix.length == 0) return null;
        String type = declared == null ? "" : declared.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (type.equals("application/x-mpegurl") || type.equals("audio/mpegurl") || type.equals("application/mpegurl"))
            type = LiveMediaObserverType.HLS;
        int textLength = Math.min(prefix.length, MAX_PROBE_BYTES);
        String text = new String(prefix, 0, textLength, StandardCharsets.UTF_8).replace("\uFEFF", "").trim();
        if (text.startsWith("#EXTM3U")) {
            String lower = text.toLowerCase(Locale.ROOT);
            if (!lower.contains("#ext-x-") || lower.contains("method=sample-aes")
                    || lower.contains("com.apple.streamingkeydelivery")) return null;
            return LiveMediaObserverType.HLS;
        }
        String lowerText = text.substring(0, Math.min(text.length(), 8192)).toLowerCase(Locale.ROOT);
        if ((lowerText.startsWith("<?xml") || lowerText.startsWith("<mpd") || type.equals(LiveMediaObserverType.DASH))
                && lowerText.contains("<mpd") && !lowerText.contains("<contentprotection")) return LiveMediaObserverType.DASH;
        if (lowerText.startsWith("<!doctype html") || lowerText.startsWith("<html") || lowerText.startsWith("{") || lowerText.startsWith("[")) return null;

        boolean iso = prefix.length >= 8 && ascii(prefix, 4, 4).matches("ftyp|styp|moov|free|wide");
        boolean ebml = prefix.length >= 4 && (prefix[0] & 255) == 0x1a && (prefix[1] & 255) == 0x45
                && (prefix[2] & 255) == 0xdf && (prefix[3] & 255) == 0xa3;
        String expected = hint == null ? "" : hint;
        if (iso || type.equals("video/mp4") || type.equals("application/mp4")) return "video/mp4";
        if (ebml) return expected.equals("video/webm") || type.equals("video/webm") ? "video/webm" : "video/x-matroska";
        if (type.equals("video/webm")) return "video/webm";
        if (type.equals("video/x-matroska") || type.equals("video/matroska")) return "video/x-matroska";
        return null;
    }

    private void validateResolution(URI address, long deadline) throws Exception {
        String host = address.getHost();
        if (host == null || host.isEmpty()) throw new Exception("host");
        Future<InetAddress[]> lookup = resolver.submit(() -> InetAddress.getAllByName(host));
        try {
            InetAddress[] answers = lookup.get(remaining(deadline, 1200), TimeUnit.MILLISECONDS);
            if (answers == null || answers.length == 0 || answers.length > 8) throw new Exception("dns");
            for (InetAddress answer : answers) if (!ScrubPreviewManager.publicAddress(answer)) throw new Exception("private dns");
        } catch (TimeoutException timeout) { lookup.cancel(true); throw timeout; }
        catch (ExecutionException unavailable) { throw new Exception("dns", unavailable); }
        finally { if (!lookup.isDone()) lookup.cancel(true); }
    }

    private static URI safeHttps(String raw) throws Exception {
        String safe = NativeBackend.publicUrl(raw);
        URI uri = new URI(safe);
        if (!"https".equalsIgnoreCase(uri.getScheme())) throw new Exception("https");
        return uri;
    }

    private static byte[] readBounded(InputStream input, int maximum, BooleanSupplier current, long deadline) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 32 * 1024));
        byte[] buffer = new byte[8192];
        while (output.size() < maximum) {
            check(current, deadline);
            int read = input.read(buffer, 0, Math.min(buffer.length, maximum - output.size()));
            if (read < 0) break;
            if (read == 0) continue;
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String ascii(byte[] value, int offset, int length) {
        return new String(value, offset, length, StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);
    }

    private static boolean redirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static boolean usable(BooleanSupplier current, long deadline) {
        return !Thread.currentThread().isInterrupted() && System.nanoTime() < deadline && current.getAsBoolean();
    }

    private static void check(BooleanSupplier current, long deadline) throws InterruptedException, TimeoutException {
        if (Thread.currentThread().isInterrupted() || !current.getAsBoolean()) throw new InterruptedException();
        if (System.nanoTime() >= deadline) throw new TimeoutException();
    }

    private static int remaining(long deadline, int maximum) throws TimeoutException {
        long millis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (millis <= 0) throw new TimeoutException();
        return (int) Math.min(maximum, Math.max(1, millis));
    }

    private static ThreadPoolExecutor pool(String name) {
        return new ThreadPoolExecutor(1, 1, 20, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), task -> {
            Thread thread = new Thread(task, name); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    /** Keeps shared media type spellings out of observer confidence logic. */
    private static final class LiveMediaObserverType {
        static final String HLS = "application/vnd.apple.mpegurl";
        static final String DASH = "application/dash+xml";
    }
}

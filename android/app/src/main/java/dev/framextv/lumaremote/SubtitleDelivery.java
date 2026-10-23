package dev.framextv.lumaremote;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Selected subtitles only: bounded public fetch, normalization and an ephemeral
 * receiver-pinned RAM file. This class never serves arbitrary URLs or files. */
public final class SubtitleDelivery implements AutoCloseable {
    public interface UrlValidator { String validate(String value) throws Exception; }
    interface AddressResolver { InetAddress[] resolve(String host) throws Exception; }
    public static final class DeliveryException extends Exception {
        public final String code;
        DeliveryException(String code) { super(code); this.code = code; }
    }
    public static final class Prepared {
        public final String id, url, format, encoding;
        public final int cueCount, discardedCueCount;
        private Prepared(String id, String url, SubtitleNormalizer.Result normalized) {
            this.id = id; this.url = url; format = normalized.format; encoding = normalized.encoding; cueCount = normalized.cueCount; discardedCueCount = normalized.discardedCueCount;
        }
    }
    private static final int MAX_BYTES = 2 * 1024 * 1024, MAX_ENTRIES = 128, MAX_CACHE_BYTES = 16 * 1024 * 1024, MAX_HEADER = 8192, MAX_REQUESTS = 16;
    private static final int ENDPOINT_TIMEOUT = 2500;
    private final Object lock = new Object();
    private final UrlValidator validator;
    private final AddressResolver addressResolver;
    private final boolean loopbackFixture;
    private final int fetchTimeout;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Set<Socket> clients = new HashSet<>();
    private final Set<FetchJob> fetching = new HashSet<>();
    private final ThreadPoolExecutor fetchers = pool(2, 4, "Subtitle fetch");
    private final ThreadPoolExecutor resolvers = pool(2, 4, "Subtitle DNS");
    private final ThreadPoolExecutor requests = pool(2, 8, "Subtitle delivery");
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(task -> daemon(task, "Subtitle timeouts"));
    private ServerSocket listener;
    private String boundHost;
    private long epoch;
    private int cachedBytes;
    private volatile boolean closed;

    private static final class Entry {
        final Prepared prepared;
        final String source, original, peer;
        final byte[] bytes;
        volatile BooleanSupplier current;
        int requests;
        boolean downloaded;
        Entry(Prepared prepared, String source, String original, String peer, byte[] bytes, BooleanSupplier current) {
            this.prepared = prepared; this.source = source; this.original = original; this.peer = peer;
            this.bytes = bytes; this.current = current;
        }
    }
    private static final class Body {
        final byte[] bytes; final String type;
        Body(byte[] bytes, String type) { this.bytes = bytes; this.type = type; }
    }
    private static final class FetchJob {
        final AtomicReference<Socket> connection = new AtomicReference<>();
        final AtomicReference<Future<?>> resolution = new AtomicReference<>();
        volatile boolean cancelled;
        Future<Body> future;
        void cancel() {
            cancelled = true;
            Socket active = connection.get(); if (active != null) try { active.close(); } catch (IOException ignored) { }
            Future<?> lookup = resolution.get(); if (lookup != null) lookup.cancel(true);
            if (future != null) future.cancel(true);
        }
    }
    private static final class Headers {
        final int status;
        final Map<String, String> values;
        Headers(int status, Map<String, String> values) { this.status = status; this.values = values; }
    }
    private static final class Response {
        final int status;
        final String location, type;
        final byte[] bytes;
        Response(int status, String location, String type, byte[] bytes) {
            this.status = status; this.location = location; this.type = type; this.bytes = bytes;
        }
    }
    public SubtitleDelivery(UrlValidator validator) {
        this(validator, () -> System.nanoTime() / 1000000, false, 8000, InetAddress::getAllByName);
    }
    private SubtitleDelivery(UrlValidator validator, LongSupplier clock, boolean fixture, int timeout, AddressResolver resolver) {
        if (validator == null || clock == null || resolver == null) throw new IllegalArgumentException("Subtitle validator required");
        this.validator = validator; addressResolver = resolver; loopbackFixture = fixture; fetchTimeout = timeout;
    }
    /** Package-private host-JVM fixture only; no production bridge can select it. */
    static SubtitleDelivery loopbackFixture(UrlValidator validator, LongSupplier clock, int timeout) {
        if (timeout < 1 || timeout > 8000) throw new IllegalArgumentException("Invalid fixture timeout");
        return new SubtitleDelivery(validator, clock, true, timeout, InetAddress::getAllByName);
    }
    /** Host-JVM fixture for proving DNS pinning without changing production policy. */
    static SubtitleDelivery loopbackFixture(UrlValidator validator, LongSupplier clock, int timeout, AddressResolver resolver) {
        if (timeout < 1 || timeout > 8000) throw new IllegalArgumentException("Invalid fixture timeout");
        return new SubtitleDelivery(validator, clock, true, timeout, resolver);
    }
    private static Thread daemon(Runnable task, String name) { Thread thread = new Thread(task, name); thread.setDaemon(true); return thread; }
    private static ThreadPoolExecutor pool(int threads, int capacity, String name) {
        return new ThreadPoolExecutor(threads, threads, 20, TimeUnit.SECONDS, new ArrayBlockingQueue<>(capacity), task -> daemon(task, name));
    }
    private static DeliveryException failure(String code) { return new DeliveryException(code); }
    private static boolean current(BooleanSupplier guard) { try { return guard != null && guard.getAsBoolean(); } catch (RuntimeException ignored) { return false; } }
    private void check(long generation, BooleanSupplier guard) throws DeliveryException {
        if (!current(guard)) throw failure("SOURCE_CHANGED");
        synchronized (lock) { if (closed || generation != epoch) throw failure("SOURCE_CHANGED"); }
    }
    private String validate(String value) throws DeliveryException {
        try { return validator.validate(value); } catch (Exception ignored) { throw failure("INVALID_SUBTITLE"); }
    }
    public Prepared prepare(String sourceUrl, String subtitleUrl, String receiverHost, BooleanSupplier guard) throws DeliveryException {
        final String source = validate(sourceUrl), original = validate(subtitleUrl);
        if (!receiver(receiverHost)) throw failure("SUBTITLE_NETWORK");
        final long generation; synchronized (lock) { generation = epoch; }
        check(generation, guard);
        synchronized (lock) {
            if (closed || epoch != generation) throw failure("SOURCE_CHANGED");
            Prepared cached = reuse(source, original, receiverHost, guard);
            if (cached != null) return cached;
            if (entries.size() >= MAX_ENTRIES) throw failure("SUBTITLE_TOO_LARGE");
        }
        FetchJob job = new FetchJob();
        synchronized (lock) { if (closed || epoch != generation) throw failure("SOURCE_CHANGED"); fetching.add(job); }
        Body body;
        try {
            job.future = fetchers.submit(() -> fetch(original, generation, guard, job));
            body = job.future.get(fetchTimeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ignored) { check(generation, guard); throw failure("SUBTITLE_FETCH"); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); throw failure("SOURCE_CHANGED"); }
        catch (ExecutionException error) {
            if (error.getCause() instanceof DeliveryException) throw (DeliveryException) error.getCause();
            throw failure("SUBTITLE_FETCH");
        } catch (RuntimeException ignored) { check(generation, guard); throw failure("SUBTITLE_FETCH"); }
        finally { job.cancel(); synchronized (lock) { fetching.remove(job); } }
        check(generation, guard);
        SubtitleNormalizer.Result normalized;
        try { normalized = SubtitleNormalizer.normalize(body.bytes, body.type); }
        catch (SubtitleNormalizer.NormalizeException error) { throw failure(error.code); }
        if (normalized.srt.length == 0 || normalized.srt.length > MAX_BYTES) throw failure("SUBTITLE_TOO_LARGE");
        check(generation, guard);
        synchronized (lock) {
            if (closed || generation != epoch) throw failure("SOURCE_CHANGED");
            Prepared cached = reuse(source, original, receiverHost, guard);
            if (cached != null) return cached;
            if (entries.size() >= MAX_ENTRIES || normalized.srt.length > MAX_CACHE_BYTES - cachedBytes) throw failure("SUBTITLE_TOO_LARGE");
            InetAddress address = localAddress(receiverHost);
            startListener(address);
            byte[] secret = new byte[32]; random.nextBytes(secret); StringBuilder id = new StringBuilder(64);
            for (byte part : secret) id.append(String.format(java.util.Locale.ROOT, "%02x", part & 255));
            String path = "/subtitles/" + id + "/subtitle.srt";
            Prepared prepared = new Prepared(id.toString(), "http://" + boundHost + ":" + listener.getLocalPort() + path, normalized);
            entries.put(prepared.id, new Entry(prepared, source, original, receiverHost, normalized.srt, guard));
            cachedBytes += normalized.srt.length;
            return prepared;
        }
    }
    /** Called under lock after the new caller's source guard has passed. The
     * existing URL must remain usable by Kodi's registered track index. */
    private Prepared reuse(String source, String original, String peer, BooleanSupplier guard) {
        for (Entry entry : entries.values()) if (entry.source.equals(source) && entry.original.equals(original) && entry.peer.equals(peer)) {
            entry.current = guard; entry.requests = 0; return entry.prepared;
        }
        return null;
    }
    public Prepared resolve(String id, String sourceUrl, String subtitleUrl, String receiverHost) throws DeliveryException {
        if (id == null || !id.matches("[a-f0-9]{64}")) throw failure("SUBTITLE_UNAVAILABLE");
        String source = validate(sourceUrl), original = validate(subtitleUrl);
        Entry entry;
        synchronized (lock) {
            entry = entries.get(id);
            if (closed || entry == null || !entry.source.equals(source) || !entry.original.equals(original)
                    || !entry.peer.equals(receiverHost)) throw failure("SUBTITLE_UNAVAILABLE");
        }
        if (!current(entry.current)) throw failure("SUBTITLE_UNAVAILABLE");
        synchronized (lock) {
            if (closed || entries.get(id) != entry) throw failure("SUBTITLE_UNAVAILABLE");
            // Explicit source-matching reselection permits another bounded
            // transfer; ordinary HEAD/GET traffic cannot renew this allowance.
            entry.requests = 0;
            return entry.prepared;
        }
    }
    public boolean downloaded(String id) {
        if (id == null || !id.matches("[a-f0-9]{64}")) return false;
        Entry entry; synchronized (lock) { entry = entries.get(id); if (entry == null || !entry.downloaded) return false; }
        return current(entry.current);
    }
    private Body fetch(String initial, long generation, BooleanSupplier guard, FetchJob job) throws DeliveryException {
        String address = initial; long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(fetchTimeout);
        for (int redirect = 0; redirect <= 4; redirect++) {
            checkFetch(generation, guard, job, deadline);
            try {
                URI uri = new URI(validate(address));
                InetAddress[] approved = resolveDestination(uri, generation, guard, job, deadline);
                Response response = request(uri, approved, generation, guard, job, deadline);
                checkFetch(generation, guard, job, deadline);
                if (response.status == 301 || response.status == 302 || response.status == 303 || response.status == 307 || response.status == 308) {
                    if (redirect == 4 || response.location == null || response.location.isEmpty() || response.location.length() > 8192) throw failure("SUBTITLE_FETCH");
                    URI next = uri.resolve(response.location);
                    if ("https".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(next.getScheme())) throw failure("SUBTITLE_FETCH");
                    address = validate(next.toString()); continue;
                }
                if (response.status != 200 || response.bytes == null) throw failure("SUBTITLE_FETCH");
                return new Body(response.bytes, response.type);
            } catch (DeliveryException error) { throw error; }
            catch (Exception ignored) { throw failure("SUBTITLE_FETCH"); }
        }
        throw failure("SUBTITLE_FETCH");
    }
    private InetAddress[] resolveDestination(URI uri, long generation, BooleanSupplier guard, FetchJob job, long deadline) throws DeliveryException {
        validateDestination(uri);
        String host = normalizedHost(uri);
        Future<InetAddress[]> lookup;
        try { lookup = resolvers.submit(() -> addressResolver.resolve(host)); }
        catch (RejectedExecutionException unavailable) { throw failure("SUBTITLE_FETCH"); }
        job.resolution.set(lookup);
        if (job.cancelled) lookup.cancel(true);
        try {
            InetAddress[] addresses = lookup.get(remaining(deadline), TimeUnit.MILLISECONDS);
            checkFetch(generation, guard, job, deadline);
            validateResolvedAnswers(addresses, loopbackFixture);
            return addresses.clone();
        } catch (TimeoutException timeout) { lookup.cancel(true); throw failure("SUBTITLE_FETCH"); }
        catch (InterruptedException interrupted) { lookup.cancel(true); Thread.currentThread().interrupt(); throw failure("SOURCE_CHANGED"); }
        catch (ExecutionException unavailable) { throw failure("SUBTITLE_FETCH"); }
        finally { job.resolution.compareAndSet(lookup, null); }
    }
    private static void validateDestination(URI uri) throws DeliveryException {
        if (uri == null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getUserInfo() != null || uri.getHost() == null || uri.getHost().isEmpty()
                || uri.getPort() == 0 || uri.getPort() < -1 || uri.getPort() > 65535) throw failure("INVALID_SUBTITLE");
    }
    static void validateResolvedAnswers(InetAddress[] addresses, boolean loopback) throws DeliveryException {
        if (addresses == null || addresses.length == 0 || addresses.length > 8) throw failure("SUBTITLE_NETWORK");
        for (InetAddress address : addresses) {
            if (address == null || (loopback ? !address.isLoopbackAddress() : !publicAddress(address))) {
                throw failure("SUBTITLE_NETWORK");
            }
        }
    }
    private Response request(URI uri, InetAddress[] approved, long generation, BooleanSupplier guard, FetchJob job, long deadline) throws DeliveryException {
        String host = normalizedHost(uri);
        int port = uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        for (InetAddress address : approved) {
            Socket raw = null, connection = null;
            try {
                checkFetch(generation, guard, job, deadline);
                raw = new Socket(); own(job, raw);
                raw.connect(new InetSocketAddress(address, port), endpointTimeout(deadline));
                raw.setSoTimeout(endpointTimeout(deadline));
                checkFetch(generation, guard, job, deadline);
                connection = raw;
                if ("https".equalsIgnoreCase(uri.getScheme())) {
                    String tlsHost = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
                    SSLSocket secure = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, tlsHost, port, true);
                    connection = secure; own(job, secure); configureTls(secure, tlsHost);
                    secure.setSoTimeout(endpointTimeout(deadline)); secure.startHandshake();
                    checkFetch(generation, guard, job, deadline);
                }
                writeRequest(connection.getOutputStream(), uri, host, port);
                Headers headers = readHeaders(connection, connection.getInputStream(), generation, guard, job, deadline);
                if (headers.status == 301 || headers.status == 302 || headers.status == 303 || headers.status == 307 || headers.status == 308) {
                    return new Response(headers.status, headers.values.get("location"), null, null);
                }
                if (headers.status != 200) return new Response(headers.status, null, null, null);
                byte[] encoded = readBody(connection, connection.getInputStream(), headers.values, generation, guard, job, deadline);
                byte[] decoded = decode(encoded, headers.values.get("content-encoding"), generation, guard, job, deadline);
                return new Response(headers.status, null, headers.values.get("content-type"), decoded);
            } catch (DeliveryException error) { throw error; }
            catch (Exception unavailable) { checkFetch(generation, guard, job, deadline); }
            finally {
                Socket owned = connection != null ? connection : raw;
                if (owned != null) try { owned.close(); } catch (IOException ignored) { }
                if (owned != null) job.connection.compareAndSet(owned, null);
                if (raw != null) job.connection.compareAndSet(raw, null);
            }
        }
        throw failure("SUBTITLE_FETCH");
    }
    private static void own(FetchJob job, Socket socket) throws DeliveryException {
        job.connection.set(socket);
        if (job.cancelled || Thread.currentThread().isInterrupted()) {
            try { socket.close(); } catch (IOException ignored) { }
            throw failure("SOURCE_CHANGED");
        }
    }
    static void configureTls(SSLSocket socket, String host) throws DeliveryException {
        try {
            if (socket == null || host == null || host.isEmpty()) throw new IllegalArgumentException();
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            if (!host.matches("[0-9.]+") && !host.contains(":")) {
                parameters.setServerNames(Collections.singletonList(new SNIHostName(host)));
            }
            socket.setSSLParameters(parameters);
        } catch (RuntimeException invalid) { throw failure("SUBTITLE_FETCH"); }
    }
    private static String normalizedHost(URI uri) throws DeliveryException {
        String host = uri == null ? null : uri.getHost();
        if (host == null) throw failure("INVALID_SUBTITLE");
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.isEmpty()) throw failure("INVALID_SUBTITLE");
        return host;
    }
    private static void writeRequest(OutputStream output, URI uri, String host, int port) throws IOException {
        String path = uri.getRawPath(); if (path == null || path.isEmpty()) path = "/";
        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
        String authority = host.contains(":") ? "[" + host + "]" : host;
        boolean defaultPort = "https".equalsIgnoreCase(uri.getScheme()) ? port == 443 : port == 80;
        if (!defaultPort) authority += ":" + port;
        String request = "GET " + path + " HTTP/1.1\r\nHost: " + authority
                + "\r\nAccept: */*\r\nAccept-Encoding: gzip\r\nConnection: close\r\nUser-Agent: LUMA-Subtitle/1\r\n\r\n";
        output.write(request.getBytes(StandardCharsets.US_ASCII)); output.flush();
    }
    private Headers readHeaders(Socket socket, InputStream input, long generation, BooleanSupplier guard, FetchJob job, long deadline) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); int state = 0;
        while (bytes.size() < MAX_HEADER) {
            socket.setSoTimeout(endpointTimeout(deadline)); int value = input.read();
            if (value < 0 || value == 0 || value == 127 || value < 32 && value != '\r' && value != '\n' && value != '\t') throw failure("SUBTITLE_FETCH");
            bytes.write(value); checkFetch(generation, guard, job, deadline);
            if (state == 0) {
                if (value == '\r') state = 1; else if (value == '\n') throw failure("SUBTITLE_FETCH");
            } else if (state == 1) {
                if (value != '\n') throw failure("SUBTITLE_FETCH"); state = 2;
            } else if (state == 2) {
                if (value == '\r') state = 3; else if (value == '\n') throw failure("SUBTITLE_FETCH"); else state = 0;
            } else if (value == '\n') break;
            else throw failure("SUBTITLE_FETCH");
        }
        if (state != 3 || bytes.size() >= MAX_HEADER) throw failure("SUBTITLE_FETCH");
        String[] lines = bytes.toString(StandardCharsets.ISO_8859_1.name()).split("\\r\\n", -1);
        if (lines.length < 3 || !lines[0].matches("HTTP/1\\.[01] [0-9]{3}(?: .*)?")) throw failure("SUBTITLE_FETCH");
        int status = Integer.parseInt(lines[0].substring(9, 12)); Map<String, String> values = new LinkedHashMap<>();
        for (int index = 1; index < lines.length - 2; index++) {
            String line = lines[index]; int colon = line.indexOf(':');
            if (colon <= 0 || line.charAt(0) == ' ' || line.charAt(0) == '\t') throw failure("SUBTITLE_FETCH");
            String name = line.substring(0, colon).toLowerCase(java.util.Locale.ROOT);
            if (!name.matches("[!#$%&'*+.^_`|~0-9a-z-]+")) throw failure("SUBTITLE_FETCH");
            String value = line.substring(colon + 1).trim();
            if (values.containsKey(name) && ("content-length".equals(name) || "transfer-encoding".equals(name)
                    || "content-encoding".equals(name) || "content-type".equals(name) || "location".equals(name))) throw failure("SUBTITLE_FETCH");
            values.putIfAbsent(name, value);
        }
        return new Headers(status, values);
    }
    private byte[] readBody(Socket socket, InputStream input, Map<String, String> headers,
            long generation, BooleanSupplier guard, FetchJob job, long deadline) throws Exception {
        String transfer = headers.get("transfer-encoding"), lengthValue = headers.get("content-length");
        if (transfer != null && lengthValue != null) throw failure("SUBTITLE_FETCH");
        if (transfer != null && !"chunked".equalsIgnoreCase(transfer.trim())) throw failure("SUBTITLE_FETCH");
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(32 * 1024, MAX_BYTES));
        if (transfer != null) {
            while (true) {
                String line = readLine(socket, input, 128, generation, guard, job, deadline);
                String size = line.split(";", 2)[0]; if (!size.matches("[0-9A-Fa-f]{1,8}")) throw failure("SUBTITLE_FETCH");
                long count = Long.parseLong(size, 16);
                if (count == 0) {
                    int trailers = 0; String trailer;
                    do { trailer = readLine(socket, input, MAX_HEADER - trailers, generation, guard, job, deadline); trailers += trailer.length() + 2; }
                    while (!trailer.isEmpty() && trailers < MAX_HEADER);
                    if (!trailer.isEmpty()) throw failure("SUBTITLE_FETCH");
                    break;
                }
                if (count > MAX_BYTES - output.size()) throw failure("SUBTITLE_TOO_LARGE");
                readExact(socket, input, output, (int) count, generation, guard, job, deadline);
                if (readByte(socket, input, generation, guard, job, deadline) != '\r'
                        || readByte(socket, input, generation, guard, job, deadline) != '\n') throw failure("SUBTITLE_FETCH");
            }
        } else if (lengthValue != null) {
            if (!lengthValue.matches("[0-9]{1,10}")) throw failure("SUBTITLE_FETCH");
            long length = Long.parseLong(lengthValue); if (length > MAX_BYTES) throw failure("SUBTITLE_TOO_LARGE");
            readExact(socket, input, output, (int) length, generation, guard, job, deadline);
        } else {
            byte[] chunk = new byte[8192]; int count;
            while (true) {
                socket.setSoTimeout(remaining(deadline)); count = input.read(chunk);
                if (count < 0) break; checkFetch(generation, guard, job, deadline); if (count == 0) continue;
                if (output.size() > MAX_BYTES - count) throw failure("SUBTITLE_TOO_LARGE");
                output.write(chunk, 0, count);
            }
        }
        return output.toByteArray();
    }
    private byte[] decode(byte[] encoded, String encoding, long generation, BooleanSupplier guard, FetchJob job, long deadline) throws Exception {
        if (encoding == null || encoding.trim().isEmpty() || "identity".equalsIgnoreCase(encoding.trim())) return encoded;
        if (!"gzip".equalsIgnoreCase(encoding.trim())) throw failure("SUBTITLE_FORMAT");
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] chunk = new byte[8192]; int count;
        try (InputStream input = new GZIPInputStream(new ByteArrayInputStream(encoded))) {
            while ((count = input.read(chunk)) != -1) {
                checkFetch(generation, guard, job, deadline);
                if (output.size() > MAX_BYTES - count) throw failure("SUBTITLE_TOO_LARGE");
                output.write(chunk, 0, count);
            }
        }
        return output.toByteArray();
    }
    private void readExact(Socket socket, InputStream input, ByteArrayOutputStream output, int total,
            long generation, BooleanSupplier guard, FetchJob job, long deadline) throws Exception {
        byte[] chunk = new byte[Math.min(8192, Math.max(1, total))]; int left = total;
        while (left > 0) {
            socket.setSoTimeout(remaining(deadline)); int count = input.read(chunk, 0, Math.min(chunk.length, left));
            if (count <= 0) throw failure("SUBTITLE_FETCH");
            output.write(chunk, 0, count); left -= count; checkFetch(generation, guard, job, deadline);
        }
    }
    private String readLine(Socket socket, InputStream input, int maximum,
            long generation, BooleanSupplier guard, FetchJob job, long deadline) throws Exception {
        if (maximum < 1) throw failure("SUBTITLE_FETCH"); ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (line.size() < maximum) {
            int value = readByte(socket, input, generation, guard, job, deadline);
            if (value == '\r') {
                if (readByte(socket, input, generation, guard, job, deadline) != '\n') throw failure("SUBTITLE_FETCH");
                return line.toString(StandardCharsets.US_ASCII.name());
            }
            if (value < 32 || value > 126) throw failure("SUBTITLE_FETCH"); line.write(value);
        }
        throw failure("SUBTITLE_FETCH");
    }
    private int readByte(Socket socket, InputStream input, long generation, BooleanSupplier guard, FetchJob job, long deadline) throws Exception {
        socket.setSoTimeout(remaining(deadline)); int value = input.read(); if (value < 0) throw failure("SUBTITLE_FETCH");
        checkFetch(generation, guard, job, deadline); return value;
    }
    private static int remaining(long deadline) throws DeliveryException {
        long value = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (value <= 0) throw failure("SUBTITLE_FETCH"); return (int) Math.min(Integer.MAX_VALUE, value);
    }
    private static int endpointTimeout(long deadline) throws DeliveryException {
        return Math.min(ENDPOINT_TIMEOUT, remaining(deadline));
    }
    private void checkFetch(long generation, BooleanSupplier guard, FetchJob job, long deadline) throws DeliveryException {
        if (job.cancelled || Thread.currentThread().isInterrupted()) throw failure("SOURCE_CHANGED");
        if (System.nanoTime() >= deadline) throw failure("SUBTITLE_FETCH");
        check(generation, guard);
    }
    private static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress(); int a = bytes[0] & 255, b = bytes[1] & 255;
        if (bytes.length == 4) {
            int c = bytes[2] & 255;
            return !(a == 0 || a >= 224 || (a == 100 && b >= 64 && b <= 127) || (a == 192 && (b == 0 && (c == 0 || c == 2) || b == 88 && c == 99))
                    || a == 198 && (b == 18 || b == 19 || b == 51 && c == 100) || a == 203 && b == 0 && c == 113);
        }
        int first = a * 256 + b, second = (bytes[2] & 255) * 256 + (bytes[3] & 255);
        return first >= 0x2000 && first < 0x4000 && first != 0x2002 && first != 0x3fff
                && !(first == 0x2001 && (second < 0x0200 || second == 0x0db8));
    }
    private boolean receiver(String value) {
        if (value == null || !value.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) return false;
        String[] fields = value.split("\\."); int[] parts = new int[4];
        for (int i = 0; i < 4; i++) { parts[i] = Integer.parseInt(fields[i]); if (parts[i] > 255) return false; }
        return loopbackFixture && parts[0] == 127 || parts[0] == 10 || parts[0] == 172 && parts[1] >= 16 && parts[1] <= 31 || parts[0] == 192 && parts[1] == 168;
    }
    private InetAddress localAddress(String peer) throws DeliveryException {
        try {
            if (loopbackFixture) return InetAddress.getByName("127.0.0.1");
            try (DatagramSocket socket = new DatagramSocket()) {
                // UDP connect selects a route without sending a packet.
                socket.connect(new InetSocketAddress(InetAddress.getByName(peer), 9));
                InetAddress address = socket.getLocalAddress();
                NetworkInterface nic = NetworkInterface.getByInetAddress(address);
                if (!receiver(address.getHostAddress()) || nic == null || !nic.isUp() || nic.isLoopback()) throw failure("SUBTITLE_NETWORK");
                return address;
            }
        } catch (DeliveryException error) { throw error; }
        catch (Exception ignored) { throw failure("SUBTITLE_NETWORK"); }
    }
    private void startListener(InetAddress address) throws DeliveryException {
        if (listener != null && !listener.isClosed() && address.getHostAddress().equals(boundHost)) return;
        if (listener != null && !listener.isClosed()) throw failure("SUBTITLE_NETWORK");
        closeListener();
        ServerSocket opened = null;
        try {
            opened = new ServerSocket(); opened.setReuseAddress(true); opened.bind(new InetSocketAddress(address, 0), 8);
            final ServerSocket owned = opened;
            listener = owned; boundHost = address.getHostAddress();
            daemon(() -> accept(owned), "Subtitle listener").start();
        } catch (IOException ignored) { if (opened != null) try { opened.close(); } catch (IOException ignoredAgain) { } throw failure("SUBTITLE_NETWORK"); }
    }
    private void accept(ServerSocket owner) {
        while (!owner.isClosed()) {
            Socket accepted = null;
            try {
                accepted = owner.accept(); accepted.setSoTimeout(3000);
                synchronized (lock) { if (closed || listener != owner || clients.size() >= 8) { accepted.close(); continue; } clients.add(accepted); }
                final Socket socket = accepted;
                requests.execute(() -> serve(socket, owner));
            } catch (Exception ignored) { if (accepted != null) { synchronized (lock) { clients.remove(accepted); } try { accepted.close(); } catch (IOException ignoredAgain) { } } }
        }
    }
    private void serve(Socket socket, ServerSocket owner) {
        Future<?> timeout = deadlines.schedule(() -> { try { socket.close(); } catch (IOException ignored) { } }, 5, TimeUnit.SECONDS);
        try (Socket owned = socket) {
            String header = header(owned.getInputStream()); String[] lines = header.split("\r\n");
            String[] request = lines[0].split(" ", -1);
            if (request.length != 3 || !("GET".equals(request[0]) || "HEAD".equals(request[0])) || !("HTTP/1.1".equals(request[2]) || "HTTP/1.0".equals(request[2]))) { respond(owned, 400, new byte[0], false); return; }
            String host = null;
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':'); if (colon <= 0) { respond(owned, 400, new byte[0], false); return; }
                String name = lines[i].substring(0, colon), value = lines[i].substring(colon + 1).trim();
                if ("host".equalsIgnoreCase(name)) { if (host != null) { respond(owned, 400, new byte[0], false); return; } host = value; }
                if ("transfer-encoding".equalsIgnoreCase(name) || "content-length".equalsIgnoreCase(name) && !"0".equals(value)) { respond(owned, 400, new byte[0], false); return; }
            }
            Entry entry;
            synchronized (lock) {
                if (closed || listener != owner || !(boundHost + ":" + owner.getLocalPort()).equals(host)) { respond(owned, 403, new byte[0], false); return; }
                if (!request[1].matches("/subtitles/[a-f0-9]{64}/subtitle\\.srt")) { respond(owned, 404, new byte[0], false); return; }
                entry = entries.get(request[1].split("/")[2]);
                if (entry == null || !entry.peer.equals(owned.getInetAddress().getHostAddress()) || entry.requests >= MAX_REQUESTS) { respond(owned, 403, new byte[0], false); return; }
                entry.requests++;
            }
            boolean head = "HEAD".equals(request[0]);
            if (!current(entry.current)) { respond(owned, 403, new byte[0], false); return; }
            respond(owned, 200, entry.bytes, head);
            if (!head && current(entry.current)) synchronized (lock) { if (entries.get(entry.prepared.id) == entry) entry.downloaded = true; }
        } catch (Exception ignored) { /* Fixed status responses only; tokens and provider bodies are never logged. */ }
        finally { timeout.cancel(false); synchronized (lock) { clients.remove(socket); } }
    }
    private static String header(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); int previous = 0, matched = 0;
        while (bytes.size() < MAX_HEADER) {
            int value = input.read(); if (value < 0 || value > 127 || value < 32 && value != '\r' && value != '\n' && value != '\t') throw new IOException("Invalid header");
            if (value == '\n' && previous != '\r' || previous == '\r' && value != '\n') throw new IOException("Invalid header framing");
            bytes.write(value); matched = (previous == '\r' && value == '\n') ? matched + 1 : (value == '\r' ? matched : 0);
            if (matched == 2) return bytes.toString(StandardCharsets.US_ASCII.name());
            previous = value;
        }
        throw new IOException("Header too large");
    }
    private static void respond(Socket socket, int status, byte[] bytes, boolean head) throws IOException {
        OutputStream output = socket.getOutputStream();
        String headers = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Rejected") + "\r\nContent-Type: application/x-subrip; charset=utf-8\r\nContent-Length: " + bytes.length
                + "\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        if (!head) output.write(bytes);
        output.flush();
    }
    private void closeListener() {
        if (listener != null) try { listener.close(); } catch (IOException ignored) { }
        listener = null; boundHost = null;
        for (Socket socket : new ArrayList<>(clients)) try { socket.close(); } catch (IOException ignored) { }
        clients.clear();
    }
    public void revokeAll() {
        synchronized (lock) {
            epoch++; entries.clear(); cachedBytes = 0; closeListener();
            for (FetchJob job : fetching) job.cancel(); fetching.clear();
        }
    }
    @Override public void close() {
        synchronized (lock) { if (closed) return; closed = true; revokeAll(); }
        fetchers.shutdownNow(); resolvers.shutdownNow(); requests.shutdownNow(); deadlines.shutdownNow();
    }
}

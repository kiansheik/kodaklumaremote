package dev.framextv.lumaremote;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.provider.OpenableColumns;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Streams user-selected document-provider files over the active private LAN.
 * URLs are random, process-local capabilities; there is no directory listing,
 * public bind, content-provider URI disclosure or arbitrary path resolution. */
final class LocalMediaServer implements AutoCloseable {
    static final class LocalMediaException extends Exception {
        final String code;
        LocalMediaException(String code) { super(code); this.code = code; }
    }

    private static final int MAX_HEADER = 16 * 1024, MAX_SUBTITLES = 16, MAX_SESSIONS = 8;
    private static final long MAX_SUBTITLE_BYTES = 4L * 1024 * 1024;
    private final Context context;
    private final ContentResolver resolver;
    private final SecureRandom random = new SecureRandom();
    private final Object lock = new Object();
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final Set<Socket> clients = new HashSet<>();
    private volatile ServerSocket listener;
    private ThreadPoolExecutor workers;
    private volatile String host = "";
    private volatile boolean closed;

    private static final class Entry {
        final Uri uri;
        final long size;
        final String type, label, language;
        Entry(Uri uri, long size, String type, String label, String language) {
            this.uri = uri; this.size = size; this.type = type; this.label = label; this.language = language;
        }
    }

    private static final class Session {
        final String token;
        final Entry video;
        final List<Entry> subtitles;
        Session(String token, Entry video, List<Entry> subtitles) {
            this.token = token; this.video = video; this.subtitles = subtitles;
        }
    }

    private static final class Request {
        String method, path;
        final Map<String, String> headers = new LinkedHashMap<>();
    }

    private static final class Span {
        final long start, end;
        final boolean partial;
        Span(long start, long end, boolean partial) { this.start = start; this.end = end; this.partial = partial; }
    }

    LocalMediaServer(Context context) {
        this.context = context.getApplicationContext();
        resolver = this.context.getContentResolver();
    }

    JSONObject prepare(Uri videoUri, List<Uri> subtitleUris) throws LocalMediaException {
        if (videoUri == null) throw failure("LOCAL_MEDIA_INVALID");
        Entry video = entry(videoUri, true);
        List<Entry> subtitles = new ArrayList<>();
        if (subtitleUris != null) for (Uri uri : subtitleUris) {
            if (subtitles.size() >= MAX_SUBTITLES) throw failure("LOCAL_MEDIA_SUBTITLES");
            subtitles.add(entry(uri, false));
        }
        InetAddress address = activeAddress();
        synchronized (lock) {
            if (closed) throw failure("LOCAL_MEDIA_UNAVAILABLE");
            start(address);
            String token = secret();
            sessions.put(token, new Session(token, video, subtitles));
            while (sessions.size() > MAX_SESSIONS) sessions.remove(sessions.keySet().iterator().next());
            String base = "http://" + host + ":" + listener.getLocalPort() + "/local/" + token;
            JSONArray tracks = new JSONArray();
            for (int i = 0; i < subtitles.size(); i++) {
                Entry item = subtitles.get(i);
                JSONObject track = new JSONObject();
                try {
                    track.put("url", base + "/subtitle/" + i).put("contentType", item.type)
                        .put("language", item.language).put("label", item.label);
                } catch (Exception impossible) { throw failure("LOCAL_MEDIA_INVALID"); }
                tracks.put(track);
            }
            try {
                return new JSONObject().put("url", base + "/video").put("contentType", video.type)
                    .put("title", video.label).put("mediaId", "local:" + token.substring(0, 32))
                    .put("tracks", tracks).put("local", true);
            } catch (Exception impossible) { throw failure("LOCAL_MEDIA_INVALID"); }
        }
    }

    /** Accepts only an exact URL belonging to a currently served capability. */
    String resolve(String value, String receiverHost) throws NativeBackend.BackendException {
        if (!privateIpv4(receiverHost) || !owns(value)) throw NativeBackend.fail("INVALID_MEDIA");
        return value;
    }

    String validateOwned(Object value) throws NativeBackend.BackendException {
        if (!(value instanceof String) || !owns((String) value)) throw NativeBackend.fail("INVALID_MEDIA");
        return (String) value;
    }

    boolean owns(String value) {
        if (value == null || value.length() > 8192 || value.indexOf('#') >= 0 || value.indexOf('?') >= 0) return false;
        try {
            java.net.URI uri = new java.net.URI(value);
            if (!"http".equals(uri.getScheme()) || !host.equals(uri.getHost()) || listener == null
                    || uri.getPort() != listener.getLocalPort() || uri.getRawUserInfo() != null) return false;
            String[] parts = uri.getPath().split("/", -1);
            if (parts.length < 4 || !"local".equals(parts[1]) || !parts[2].matches("[a-f0-9]{64}")) return false;
            synchronized (lock) {
                Session session = sessions.get(parts[2]);
                if (session == null) return false;
                if (parts.length == 4 && "video".equals(parts[3])) return true;
                if (parts.length == 5 && "subtitle".equals(parts[3]) && parts[4].matches("0|[1-9][0-9]?")) {
                    int index = Integer.parseInt(parts[4]);
                    return index < session.subtitles.size();
                }
                return false;
            }
        } catch (Exception invalid) { return false; }
    }

    private Entry entry(Uri uri, boolean video) throws LocalMediaException {
        if (!"content".equals(uri.getScheme())) throw failure(video ? "LOCAL_MEDIA_INVALID" : "LOCAL_MEDIA_SUBTITLES");
        String name = "", declared = resolver.getType(uri);
        long size = -1;
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn);
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn);
            }
        } catch (RuntimeException ignored) { }
        if (size < 0) try (AssetFileDescriptor file = resolver.openAssetFileDescriptor(uri, "r")) {
            if (file != null) size = file.getLength();
        } catch (IOException ignored) { }
        if (size <= 0 || !video && size > MAX_SUBTITLE_BYTES) throw failure(video ? "LOCAL_MEDIA_INVALID" : "LOCAL_MEDIA_SUBTITLES");
        name = label(name, video ? "Local video" : "Local subtitles", 180);
        String type = video ? videoType(declared, name) : subtitleType(declared, name);
        if (type == null) throw failure(video ? "LOCAL_MEDIA_FORMAT" : "LOCAL_MEDIA_SUBTITLES");
        return new Entry(uri, size, type, name, video ? "" : language(name));
    }

    private static String videoType(String declared, String name) {
        String type = declared == null ? "" : declared.split(";", 2)[0].toLowerCase(Locale.ROOT);
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mkv") || "video/x-matroska".equals(type)) return "video/x-matroska";
        if (lower.endsWith(".webm") || "video/webm".equals(type)) return "video/webm";
        if (lower.endsWith(".mp4") || lower.endsWith(".m4v") || lower.endsWith(".mov")
                || "video/mp4".equals(type) || "video/quicktime".equals(type)) return "video/mp4";
        return null;
    }

    private static String subtitleType(String declared, String name) {
        String type = declared == null ? "" : declared.split(";", 2)[0].toLowerCase(Locale.ROOT);
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".srt") || "application/x-subrip".equals(type)) return "application/x-subrip";
        if (lower.endsWith(".vtt") || "text/vtt".equals(type)) return "text/vtt";
        if (lower.endsWith(".ass") || lower.endsWith(".ssa")) return "text/x-ssa";
        if (lower.endsWith(".ttml") || lower.endsWith(".dfxp") || "application/ttml+xml".equals(type)) return "application/ttml+xml";
        return null;
    }

    private static String language(String name) {
        String value = name.toLowerCase(Locale.ROOT).replace('_', '-');
        if (value.matches(".*(?:^|[. -])(?:pt-br|pob)(?:[. -]|$).*$")) return "pt-BR";
        if (value.matches(".*(?:^|[. -])(?:pt|por)(?:[. -]|$).*$")) return "pt";
        if (value.matches(".*(?:^|[. -])(?:en|eng)(?:[. -]|$).*$")) return "en";
        if (value.matches(".*(?:^|[. -])(?:es|spa)(?:[. -]|$).*$")) return "es";
        return "";
    }

    private static String label(String value, String fallback, int max) {
        String clean = value == null ? "" : value.replaceAll("[\\p{Cntrl}\\u007f-\\u009f\\u202a-\\u202e\\u2066-\\u2069]", " ").trim();
        if (clean.isEmpty()) clean = fallback;
        return clean.substring(0, Math.min(max, clean.length()));
    }

    private InetAddress activeAddress() throws LocalMediaException {
        try {
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = manager == null ? null : manager.getActiveNetwork();
            NetworkCapabilities capabilities = network == null ? null : manager.getNetworkCapabilities(network);
            if (capabilities == null || !(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) throw failure("LOCAL_MEDIA_NETWORK");
            LinkProperties properties = manager.getLinkProperties(network);
            if (properties != null) for (LinkAddress link : properties.getLinkAddresses()) {
                InetAddress address = link.getAddress();
                if (address instanceof Inet4Address && privateIpv4(address.getHostAddress())) return address;
            }
        } catch (LocalMediaException failure) { throw failure; }
        catch (Exception ignored) { }
        throw failure("LOCAL_MEDIA_NETWORK");
    }

    private void start(InetAddress address) throws LocalMediaException {
        String selected = address.getHostAddress();
        if (listener != null && !listener.isClosed() && selected.equals(host)) return;
        stopListener();
        host = selected;
        try {
            listener = new ServerSocket(); listener.setReuseAddress(true); listener.bind(new InetSocketAddress(address, 0), 8);
            workers = new ThreadPoolExecutor(4, 4, 20, TimeUnit.SECONDS, new ArrayBlockingQueue<>(12), task -> {
                Thread thread = new Thread(task, "Local media"); thread.setDaemon(true); return thread;
            });
            ServerSocket owned = listener; ThreadPoolExecutor pool = workers;
            Thread accept = new Thread(() -> accept(owned, pool), "Local media accept"); accept.setDaemon(true); accept.start();
        } catch (IOException unavailable) { stopListener(); throw failure("LOCAL_MEDIA_NETWORK"); }
    }

    private void accept(ServerSocket owner, ThreadPoolExecutor pool) {
        while (!owner.isClosed()) {
            Socket socket = null;
            try {
                socket = owner.accept(); socket.setSoTimeout(10_000);
                InetAddress peer = socket.getInetAddress();
                if (!(peer.isLoopbackAddress() || peer.isLinkLocalAddress() || peer.isSiteLocalAddress())) { socket.close(); continue; }
                synchronized (lock) { if (listener != owner) { socket.close(); continue; } clients.add(socket); }
                Socket accepted = socket;
                pool.execute(() -> serve(accepted, owner));
            } catch (RejectedExecutionException busy) { if (socket != null) close(socket); }
            catch (IOException closed) { if (socket != null) close(socket); }
        }
    }

    private void serve(Socket socket, ServerSocket owner) {
        try (Socket accepted = socket) {
            Request request = read(accepted.getInputStream());
            Entry entry = find(request.path, owner);
            if (entry == null) { respond(accepted, 404, "Not Found", null, null, 0, 0, false); return; }
            if (!("GET".equals(request.method) || "HEAD".equals(request.method))) {
                respond(accepted, 405, "Method Not Allowed", entry, null, 0, 0, false); return;
            }
            Span span;
            try { span = span(request.headers.get("range"), entry.size); }
            catch (IllegalArgumentException invalid) {
                respond(accepted, 416, "Range Not Satisfiable", entry, null, 0, 0, false); return;
            }
            respond(accepted, span.partial ? 206 : 200, span.partial ? "Partial Content" : "OK", entry, span,
                    span.start, span.end, "GET".equals(request.method));
        } catch (Exception ignored) { }
        finally { synchronized (lock) { clients.remove(socket); } }
    }

    private Entry find(String path, ServerSocket owner) {
        String[] parts = path == null ? new String[0] : path.split("/", -1);
        if (parts.length < 4 || !"local".equals(parts[1])) return null;
        synchronized (lock) {
            if (listener != owner) return null;
            Session session = sessions.get(parts[2]);
            if (session == null) return null;
            if (parts.length == 4 && "video".equals(parts[3])) return session.video;
            if (parts.length == 5 && "subtitle".equals(parts[3]) && parts[4].matches("0|[1-9][0-9]?")) {
                int index = Integer.parseInt(parts[4]); return index < session.subtitles.size() ? session.subtitles.get(index) : null;
            }
            return null;
        }
    }

    private static Request read(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int state = 0;
        while (bytes.size() < MAX_HEADER) {
            int value = input.read(); if (value < 0) throw new IOException(); bytes.write(value);
            state = state == 0 && value == '\r' ? 1 : state == 1 && value == '\n' ? 2
                : state == 2 && value == '\r' ? 3 : state == 3 && value == '\n' ? 4 : value == '\r' ? 1 : 0;
            if (state == 4) break;
        }
        if (state != 4) throw new IOException();
        String[] lines = bytes.toString(StandardCharsets.ISO_8859_1.name()).split("\\r\\n");
        String[] first = lines[0].split(" ", -1);
        if (first.length != 3 || !first[2].matches("HTTP/1\\.[01]") || !first[1].startsWith("/")) throw new IOException();
        Request request = new Request(); request.method = first[0]; request.path = first[1];
        for (int i = 1; i < lines.length && !lines[i].isEmpty(); i++) {
            int separator = lines[i].indexOf(':'); if (separator <= 0) throw new IOException();
            String name = lines[i].substring(0, separator).trim().toLowerCase(Locale.ROOT);
            if (!name.matches("[a-z0-9-]{1,64}") || request.headers.containsKey(name)) throw new IOException();
            request.headers.put(name, lines[i].substring(separator + 1).trim());
        }
        return request;
    }

    private static Span span(String value, long size) {
        if (value == null || value.isEmpty()) return new Span(0, size - 1, false);
        if (!value.startsWith("bytes=") || value.indexOf(',') >= 0) throw new IllegalArgumentException();
        String[] parts = value.substring(6).split("-", -1);
        if (parts.length != 2) throw new IllegalArgumentException();
        long start, end;
        try {
            if (parts[0].isEmpty()) {
                long suffix = Long.parseLong(parts[1]); if (suffix <= 0) throw new IllegalArgumentException();
                start = Math.max(0, size - suffix); end = size - 1;
            } else {
                start = Long.parseLong(parts[0]);
                end = parts[1].isEmpty() ? size - 1 : Math.min(size - 1, Long.parseLong(parts[1]));
            }
        } catch (NumberFormatException invalid) { throw new IllegalArgumentException(); }
        if (start < 0 || start >= size || end < start) throw new IllegalArgumentException();
        return new Span(start, end, true);
    }

    private void respond(Socket socket, int status, String reason, Entry entry, Span span, long start, long end, boolean body) throws IOException {
        OutputStream output = socket.getOutputStream();
        long length = entry == null || status == 416 ? 0 : end - start + 1;
        StringBuilder headers = new StringBuilder("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            .append("Connection: close\r\nCache-Control: no-store\r\nAccept-Ranges: bytes\r\n")
            .append("Content-Length: ").append(length).append("\r\n");
        if (entry != null) headers.append("Content-Type: ").append(entry.type).append("\r\n");
        if (status == 206 && span != null) headers.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(entry.size).append("\r\n");
        if (status == 416 && entry != null) headers.append("Content-Range: bytes */").append(entry.size).append("\r\n");
        headers.append("\r\n"); output.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
        if (!body || entry == null || length == 0) { output.flush(); return; }
        try (AssetFileDescriptor descriptor = resolver.openAssetFileDescriptor(entry.uri, "r")) {
            if (descriptor == null) throw new IOException();
            try (FileInputStream input = descriptor.createInputStream()) {
                long offset = descriptor.getStartOffset() + start;
                try { input.getChannel().position(offset); }
                catch (IOException unsupported) { skip(input, start); }
                byte[] buffer = new byte[64 * 1024]; long remaining = length;
                while (remaining > 0) {
                    int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read < 0) throw new IOException();
                    output.write(buffer, 0, read); remaining -= read;
                }
                output.flush();
            }
        }
    }

    private static void skip(InputStream input, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped > 0) { remaining -= skipped; continue; }
            if (input.read() < 0) throw new IOException();
            remaining--;
        }
    }

    private String secret() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes); StringBuilder text = new StringBuilder(64);
        for (byte value : bytes) text.append(String.format(Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }

    private static boolean privateIpv4(String value) {
        if (value == null || !value.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) return false;
        String[] parts = value.split("\\."); int[] bytes = new int[4];
        for (int i = 0; i < 4; i++) { bytes[i] = Integer.parseInt(parts[i]); if (bytes[i] > 255) return false; }
        return bytes[0] == 10 || bytes[0] == 172 && bytes[1] >= 16 && bytes[1] <= 31 || bytes[0] == 192 && bytes[1] == 168;
    }

    private static LocalMediaException failure(String code) { return new LocalMediaException(code); }
    private static void close(Socket socket) { try { socket.close(); } catch (IOException ignored) { } }

    private void stopListener() {
        ServerSocket old = listener; listener = null;
        if (old != null) try { old.close(); } catch (IOException ignored) { }
        for (Socket client : new ArrayList<>(clients)) close(client);
        clients.clear();
        if (workers != null) workers.shutdownNow(); workers = null;
        sessions.clear(); host = "";
    }

    @Override public void close() {
        synchronized (lock) { closed = true; stopListener(); }
    }
}

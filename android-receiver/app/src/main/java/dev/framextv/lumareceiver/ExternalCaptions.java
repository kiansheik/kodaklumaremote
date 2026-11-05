package dev.framextv.lumareceiver;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * One caption track the receiver downloads and renders itself. Selecting or
 * switching tracks never touches the media pipeline, so playback does not
 * rebuffer. Loading runs off the main thread; state fields are volatile.
 */
final class ExternalCaptions {
    static final int MAX_BYTES = 8 * 1024 * 1024;
    /** Downloaded caption files, so resuming or replaying a title shows its subtitles at once. */
    static volatile java.io.File cacheDirectory;
    static final long CACHE_BYTES = 48L * 1024 * 1024, CACHE_MAX_AGE_MS = 6L * 60 * 60 * 1000;
    private static final String CACHE_VERSION = "caption-cache-v2";
    final String url, language, label, mimeType;
    final Map<String, String> headers;
    final boolean selectOnLoad;
    private final String cacheIdentity;
    private final CountDownLatch done = new CountDownLatch(1);
    volatile List<CaptionParser.Cue> cues;
    volatile String error;

    ExternalCaptions(MediaRequest.Subtitle subtitle, Map<String, String> headers) {
        this(subtitle, headers, "");
    }

    /**
     * {@code cacheIdentity} is the canonical content key or opaque local-media id.
     * Callers without either identity deliberately bypass the disk cache: a URL
     * alone cannot prove that mutable/header-dependent bytes belong to this item.
     */
    ExternalCaptions(MediaRequest.Subtitle subtitle, Map<String, String> headers, String cacheIdentity) {
        url = subtitle.url; language = subtitle.language; label = subtitle.label; mimeType = subtitle.mimeType;
        selectOnLoad = subtitle.select;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.cacheIdentity = cacheIdentity == null ? "" : cacheIdentity;
    }

    String name(int ordinal) { return !label.isEmpty() ? label : !language.isEmpty() ? language : "Subtitles " + ordinal; }
    boolean loading() { return done.getCount() > 0; }
    boolean await(long millis) throws InterruptedException { return done.await(millis, TimeUnit.MILLISECONDS); }

    void load() {
        try {
            String contentType;
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            java.io.File cached = cacheFile();
            if (loadCached(cached, System.currentTimeMillis())) return;
            URL target = new URL(url);
            for (int redirects = 0; ; redirects++) {
                HttpURLConnection connection = (HttpURLConnection) target.openConnection();
                connection.setConnectTimeout(10_000); connection.setReadTimeout(15_000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", headers.containsKey("User-Agent") ? headers.get("User-Agent") : "LumaReceiver/1");
                for (Map.Entry<String, String> header : headers.entrySet()) if (!header.getKey().equals("User-Agent")) connection.setRequestProperty(header.getKey(), header.getValue());
                try {
                    int status = connection.getResponseCode();
                    if (status >= 300 && status < 400 && redirects < 5 && connection.getHeaderField("Location") != null) {
                        target = new URL(target, connection.getHeaderField("Location"));
                        if (!"http".equals(target.getProtocol()) && !"https".equals(target.getProtocol())) throw new ApiException("SUBTITLE_DOWNLOAD", 422);
                        continue;
                    }
                    if (status != 200) throw new ApiException("SUBTITLE_DOWNLOAD", 422);
                    if (connection.getContentLengthLong() > MAX_BYTES) throw new ApiException("SUBTITLE_TOO_LARGE", 422);
                    contentType = connection.getContentType();
                    try (InputStream in = connection.getInputStream()) {
                        byte[] buffer = new byte[16 * 1024]; int count;
                        while ((count = in.read(buffer)) >= 0) {
                            if (body.size() + count > MAX_BYTES) throw new ApiException("SUBTITLE_TOO_LARGE", 422);
                            body.write(buffer, 0, count);
                        }
                    }
                    break;
                } finally { connection.disconnect(); }
            }
            finish(body.toByteArray(), contentType);
            store(body.toByteArray(), contentType);
        } catch (ApiException failure) { error = failure.code; }
        catch (Exception failure) { error = "SUBTITLE_DOWNLOAD"; }
        finally {
            if (error != null) android.util.Log.w("LumaCaptions", "LOAD_FAILED " + error);
            done.countDown();
        }
    }

    private void finish(byte[] body, String contentType) throws ApiException {
        String hint = mimeType.isEmpty() ? contentType : mimeType + (contentType != null && contentType.contains("charset") ? "; " + contentType : "");
        cues = CaptionParser.parse(body, hint, language);
    }

    private boolean loadCached(java.io.File cached, long now) {
        if (cached == null || !cached.isFile()) return false;
        long age = now - cached.lastModified();
        if (age < 0 || age > CACHE_MAX_AGE_MS) { cached.delete(); return false; }
        try {
            byte[] stored = java.nio.file.Files.readAllBytes(cached.toPath());
            if (stored.length > MAX_BYTES + 1024) throw new Exception();
            int newline = 0;
            while (newline < stored.length && stored[newline] != '\n') newline++;
            if (newline >= stored.length || newline > 1024) throw new Exception();
            String contentType = new String(stored, 0, newline, StandardCharsets.UTF_8);
            byte[] body = java.util.Arrays.copyOfRange(stored, newline + 1, stored.length);
            finish(body, contentType.isEmpty() ? null : contentType);
            return true;
        } catch (Exception corrupt) {
            cached.delete();
            return false;
        }
    }

    /** Package-visible for deterministic cache-identity/expiry tests. */
    java.io.File cacheFile() {
        java.io.File directory = cacheDirectory;
        if (directory == null || cacheIdentity.isEmpty()) return null;
        try {
            StringBuilder material = new StringBuilder(CACHE_VERSION).append('\n').append(cacheIdentity).append('\n').append(url);
            TreeMap<String, String> effectiveHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            effectiveHeaders.putAll(headers);
            effectiveHeaders.putIfAbsent("User-Agent", "LumaReceiver/1");
            for (Map.Entry<String, String> header : effectiveHeaders.entrySet()) {
                material.append('\n').append(header.getKey().toLowerCase(java.util.Locale.ROOT)).append(':').append(header.getValue());
            }
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(material.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder();
            for (int i = 0; i < 16; i++) name.append(String.format("%02x", digest[i]));
            return new java.io.File(directory, "v2-" + name + ".cap");
        } catch (Exception unavailable) { return null; }
    }

    /** Parsed files only, newest kept within {@link #CACHE_BYTES}; failures just skip caching. */
    private void store(byte[] body, String contentType) {
        java.io.File file = cacheFile();
        if (file == null || cues == null) return;
        java.io.File temporary = null;
        try {
            java.io.File directory = file.getParentFile();
            if (!directory.isDirectory() && !directory.mkdirs()) return;
            temporary = java.io.File.createTempFile(file.getName() + ".", ".tmp", directory);
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(temporary)) {
                out.write((contentType == null ? "" : contentType.replace('\n', ' ')).getBytes(StandardCharsets.UTF_8));
                out.write('\n'); out.write(body);
            }
            if (file.exists() && !file.delete() || !temporary.renameTo(file)) return;
            temporary = null;
            java.io.File[] files = directory.listFiles((dir, name) -> name.endsWith(".cap"));
            if (files == null) return;
            java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            long total = 0;
            for (java.io.File entry : files) { total += entry.length(); if (total > CACHE_BYTES) entry.delete(); }
        } catch (Exception ignored) { /* captions still play from memory */ }
        finally { if (temporary != null) temporary.delete(); }
    }

    String textAt(long positionMs) {
        List<CaptionParser.Cue> loaded = cues;
        return loaded == null ? "" : CaptionParser.textAt(loaded, positionMs);
    }
}

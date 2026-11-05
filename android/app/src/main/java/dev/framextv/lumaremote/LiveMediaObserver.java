package dev.framextv.lumaremote;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded, in-memory observations of likely media requests made by the visible
 * browsing WebView. Nothing is fetched here and no request headers are kept.
 */
final class LiveMediaObserver {
    static final long MAX_AGE_MILLIS = 5 * 60 * 1000L;
    private static final int MAX_CANDIDATES = 48;

    static final class Candidate {
        final String url, contentType;
        final Map<String, String> headers;
        final int confidence;
        final long observedAt;
        final int observations;

        Candidate(String url, String contentType, Map<String, String> headers, int confidence, long observedAt, int observations) {
            this.url = url;
            this.contentType = contentType;
            this.headers = headers;
            this.confidence = confidence;
            this.observedAt = observedAt;
            this.observations = observations;
        }
    }

    private static final class Kind {
        final String contentType;
        final int confidence;
        Kind(String contentType, int confidence) {
            this.contentType = contentType;
            this.confidence = confidence;
        }
    }

    private final LinkedHashMap<String, Candidate> candidates = new LinkedHashMap<>();
    private String pageUrl = "";
    private long generation = -1;

    synchronized void reset(String pageUrl, long generation) {
        this.pageUrl = pageUrl == null ? "" : pageUrl;
        this.generation = generation;
        candidates.clear();
    }

    synchronized void clear() {
        pageUrl = "";
        generation = -1;
        candidates.clear();
    }

    synchronized void observe(String currentPage, long currentGeneration, String method, boolean mainFrame,
            String requestUrl, Map<String, String> requestHeaders, long now) {
        if (mainFrame || !"GET".equalsIgnoreCase(method) || currentGeneration != generation
                || !Objects.equals(pageUrl, currentPage) || !MainActivity.supportsCapturePage(currentPage)) return;
        Kind kind = classify(requestUrl, requestHeaders);
        if (kind == null) return;
        Map<String, String> safeHeaders = MediaHeaders.observed(requestHeaders);
        if (safeHeaders == null) return;
        String safe;
        try {
            safe = NativeBackend.publicUrl(requestUrl);
            URI uri = new URI(safe);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return;
        } catch (Exception invalid) { return; }

        Candidate previous = candidates.remove(safe);
        int observations = previous == null ? 1 : Math.min(1000, previous.observations + 1);
        candidates.put(safe, new Candidate(safe, kind.contentType, safeHeaders,
                Math.max(kind.confidence, previous == null ? 0 : previous.confidence), now, observations));
        while (candidates.size() > MAX_CANDIDATES) {
            Iterator<String> oldest = candidates.keySet().iterator();
            if (!oldest.hasNext()) break;
            oldest.next(); oldest.remove();
        }
    }

    synchronized List<Candidate> snapshot(String currentPage, long currentGeneration, long now) {
        if (currentGeneration != generation || !Objects.equals(pageUrl, currentPage)) return new ArrayList<>();
        candidates.values().removeIf(candidate -> now - candidate.observedAt > MAX_AGE_MILLIS || candidate.observedAt > now + 1000);
        List<Candidate> result = new ArrayList<>(candidates.values());
        result.sort(Comparator
                .comparingInt((Candidate value) -> value.confidence + Math.min(5, value.observations) * 4).reversed()
                .thenComparing(Comparator.comparingLong((Candidate value) -> value.observedAt).reversed()));
        return result;
    }

    private static Kind classify(String raw, Map<String, String> headers) {
        if (raw == null || raw.isEmpty() || raw.length() > 8192) return null;
        String lower = raw.toLowerCase(Locale.ROOT);
        String path;
        try {
            path = new URI(raw).getPath();
            path = path == null ? "" : path.toLowerCase(Locale.ROOT);
        } catch (Exception invalid) { return null; }

        // Segment, subtitle and static-asset requests are never useful as the
        // receiver's top-level source, even if an ad uses a suggestive query.
        if (path.matches(".*\\.(?:m4s|ts|aac|m4a|mp3|vtt|srt|ass|ssa|ttml|js|css|json|png|jpe?g|gif|webp|svg|woff2?|ttf)(?:$|/).*")) return null;
        if (path.endsWith(".m3u8") || lower.contains(".m3u8?") || lower.contains("%2em3u8"))
            return new Kind("application/vnd.apple.mpegurl", 500);
        if (path.endsWith(".mpd") || lower.contains(".mpd?") || lower.contains("%2empd"))
            return new Kind("application/dash+xml", 490);
        if (path.endsWith(".mp4") || path.endsWith(".m4v") || lower.contains("%2emp4") || lower.contains("%2em4v"))
            return new Kind("video/mp4", 430);
        if (path.endsWith(".webm") || lower.contains("%2ewebm")) return new Kind("video/webm", 430);
        if (path.endsWith(".mkv") || lower.contains("%2emkv")) return new Kind("video/x-matroska", 430);

        String accept = header(headers, "Accept").toLowerCase(Locale.ROOT);
        if (accept.contains("application/vnd.apple.mpegurl") || accept.contains("application/x-mpegurl"))
            return new Kind("application/vnd.apple.mpegurl", 460);
        if (accept.contains("application/dash+xml")) return new Kind("application/dash+xml", 450);
        if (accept.contains("video/mp4")) return new Kind("video/mp4", 400);
        if (accept.contains("video/webm")) return new Kind("video/webm", 400);

        String destination = header(headers, "Sec-Fetch-Dest");
        String range = header(headers, "Range");
        if ("video".equalsIgnoreCase(destination) || range.matches("(?i)^bytes=[0-9]*-[0-9]*$"))
            return new Kind("", 360);
        String leaf = path.substring(path.lastIndexOf('/') + 1);
        if (leaf.matches(".*(?:master|playlist|manifest|stream|source|index).*")) return new Kind("", 300);
        return null;
    }

    private static String header(Map<String, String> headers, String expected) {
        if (headers == null) return "";
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (expected.equalsIgnoreCase(entry.getKey()) && entry.getValue() != null) return entry.getValue();
        }
        return "";
    }
}

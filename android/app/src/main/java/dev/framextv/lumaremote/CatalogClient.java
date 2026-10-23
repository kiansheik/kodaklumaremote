package dev.framextv.lumaremote;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Ordinary, fixed-provider catalog reads. No caller-supplied fetch URL or proxy. */
public final class CatalogClient {
    private static final String[] BASES = {"https://api.framextv.tech", "https://api.framextv.xyz", "https://api.framextv.cc"};
    private static final int MAX_BYTES = 2 * 1024 * 1024, DEADLINE_MS = 60000;
    public static final class CatalogException extends Exception {
        public final String code;
        private CatalogException(String code) { super(message(code)); this.code = code; }
    }
    static final class Response {
        final int status; final String body, contentType;
        Response(int status, String body) { this(status, body, "application/json"); }
        Response(int status, String body, String contentType) { this.status = status; this.body = body; this.contentType = contentType; }
    }
    interface Transport { Response get(String url, int remainingMs) throws Exception; }
    private static final class Fetched {
        final String base; final JSONObject data;
        Fetched(String base, JSONObject data) { this.base = base; this.data = data; }
    }
    private final Transport transport;
    public CatalogClient() { this(CatalogClient::get); }
    CatalogClient(Transport transport) { this.transport = transport; }
    private static CatalogException fail(String code) { return new CatalogException(code); }
    private static String message(String code) {
        switch (code) {
            case "CATALOG_INVALID_INPUT": return "Choose a valid movie, series or episode.";
            case "CATALOG_RESPONSE": return "The catalog returned an unsupported response. Try again.";
            case "SOURCE_UNAVAILABLE": return "No playable source is available for this title right now.";
            case "SOURCE_UNSUPPORTED": return "This source needs a playback method that this receiver does not support.";
            default: return "The catalog could not be reached. Check the phone connection and try again.";
        }
    }
    public JSONObject handle(String action, JSONObject params) throws CatalogException {
        switch (action) {
            case "list": return list(params);
            case "search": return search(params);
            case "details": return details(params);
            case "episodes": return episodes(params);
            case "resolve": return resolve(params);
            case "hydrate": return hydrate(params);
            default: throw fail("CATALOG_INVALID_INPUT");
        }
    }
    public JSONObject list(JSONObject params) throws CatalogException {
        fields(params, "kind", "page"); String kind = kind(params, false); int page = integer(params, "page", 1, 500, 1);
        try { return listing(read("/api/discover?type=" + kind + "&page=" + page, deadline()), kind, page); }
        catch (CatalogException error) { throw error; } catch (Exception error) { throw fail("CATALOG_RESPONSE"); }
    }
    public JSONObject search(JSONObject params) throws CatalogException {
        fields(params, "query", "kind", "page"); String kind = kind(params, true); int page = integer(params, "page", 1, 500, 1);
        Object raw = params.opt("query"); if (!(raw instanceof String)) throw fail("CATALOG_INVALID_INPUT");
        String query = ((String) raw).trim(); if (query.isEmpty() || query.length() > 120 || controls(query)) throw fail("CATALOG_INVALID_INPUT");
        try { return listing(read("/api/search?q=" + encode(query) + "&type=" + kind + "&page=" + page, deadline()), kind, page); }
        catch (CatalogException error) { throw error; } catch (Exception error) { throw fail("CATALOG_RESPONSE"); }
    }
    public JSONObject details(JSONObject params) throws CatalogException {
        fields(params, "kind", "id"); String kind = kind(params, false), id = id(params);
        try { return detail(read("/api/details?id=" + id + "&type=" + kind, deadline()), kind, id); }
        catch (CatalogException error) { throw error; } catch (Exception error) { throw fail("CATALOG_RESPONSE"); }
    }
    public JSONObject episodes(JSONObject params) throws CatalogException {
        fields(params, "id", "season"); String id = id(params); int season = integer(params, "season", 0, 1000, -1);
        try {
            JSONObject raw = read("/api/season?id=" + id + "&season=" + season, deadline()); success(raw);
            JSONArray values = raw.optJSONArray("episodes"); if (values == null || values.length() > 1000) throw fail("CATALOG_RESPONSE");
            JSONArray result = new JSONArray(); Set<Integer> seen = new HashSet<>();
            for (int i = 0; i < values.length(); i++) {
                JSONObject entry = values.optJSONObject(i); if (entry == null) continue;
                int number = sourceInt(first(entry, "number", "episode_number"), 1, 10000, -1); if (number < 1 || !seen.add(number)) continue;
                result.put(object("number", number, "title", text(first(entry, "name", "title"), 200, "Episode " + number),
                    "overview", text(entry.opt("overview"), 2000, ""), "still", image(first(entry, "still", "still_path"), "w500"),
                    "airDate", text(first(entry, "airDate", "air_date"), 32, ""), "runtime", nullableInt(entry.opt("runtime"), 1, 10000)));
            }
            return object("id", id, "season", season, "episodes", result);
        } catch (CatalogException error) { throw error; } catch (Exception error) { throw fail("CATALOG_RESPONSE"); }
    }
    /** Resolves fresh metadata only; the service still owns source-session authorization. */
    public JSONObject resolve(JSONObject params) throws CatalogException {
        fields(params, "kind", "id", "season", "episode"); String kind = kind(params, false), id = id(params);
        int season = 0, episode = 0;
        if ("tv".equals(kind)) { season = integer(params, "season", 0, 1000, -1); episode = integer(params, "episode", 1, 10000, -1); }
        else if (params.has("season") || params.has("episode")) throw fail("CATALOG_INVALID_INPUT");
        long deadline = deadline(); String suffix = "?type=" + kind + "&id=" + id + ("tv".equals(kind) ? "&season=" + season + "&episode=" + episode : "");
        try {
            JSONObject meta = detail(read("/api/details?id=" + id + "&type=" + kind, deadline), kind, id);
            Fetched fetched = readWithBase("/api/stream" + suffix, deadline); JSONObject response = fetched.data;
            JSONObject stream = response.optJSONObject("detail"); if (stream == null) stream = response;
            if (!stream.optBoolean("success", false)) throw fail("SOURCE_UNAVAILABLE");
            JSONArray sources = stream.optJSONArray("sources"); if (sources == null || sources.length() == 0) throw fail("SOURCE_UNAVAILABLE");
            String selected = null, contentType = null;
            for (int i = 0; i < Math.min(sources.length(), 50); i++) {
                JSONObject source = sources.optJSONObject(i); if (source == null || encrypted(source)) continue;
                String url = sourceUrl(source.opt("url"), fetched.base); if (url == null) continue;
                // A header-dependent raw source is not silently converted into a new proxy.
                JSONObject headers = source.optJSONObject("headers");
                if (headers != null && headers.length() > 0 && !alreadyDelivered(url)) continue;
                String type = mediaType(source.optString("type", ""), url);
                if (type != null) { selected = url; contentType = type; break; }
            }
            if (selected == null) throw fail("SOURCE_UNSUPPORTED");
            JSONArray tracks = new JSONArray(); Set<String> seen = new HashSet<>(); addTracks(tracks, seen, stream.optJSONArray("subtitles"), fetched.base);
            boolean complete = true;
            try { Fetched subtitles = readWithBase("/api/subtitles" + suffix, deadline); if (subtitles.data.optBoolean("success", false)) addTracks(tracks, seen, subtitles.data.optJSONArray("subtitles"), subtitles.base); else complete = false; }
            catch (CatalogException error) { complete = false; }
            String title = meta.getString("title"); if ("tv".equals(kind)) title = text(title + " · S" + season + " E" + episode, 200, "Video");
            JSONObject content = WatchHistory.contentFromCatalog(kind, id, "tv".equals(kind) ? season : null, "tv".equals(kind) ? episode : null, title);
            if (content == null) throw fail("CATALOG_RESPONSE");
            return object("url", selected, "title", title, "contentType", contentType, "tracks", tracks, "currentTime", 0,
                "autoSubtitles", false, "content", content, "subtitleCatalogComplete", complete);
        } catch (CatalogException error) { throw error; } catch (Exception error) { throw fail("CATALOG_RESPONSE"); }
    }
    /** Refreshes public metadata for an already-playing item without selecting or
     * exposing another media source. The caller remains responsible for binding
     * the result to the exact source it observed on the receiver. */
    public JSONObject hydrate(JSONObject params) throws CatalogException {
        fields(params, "kind", "id", "season", "episode"); String kind = kind(params, false), id = id(params);
        int season = 0, episode = 0;
        if ("tv".equals(kind)) { season = integer(params, "season", 0, 1000, -1); episode = integer(params, "episode", 1, 10000, -1); }
        else if (params.has("season") || params.has("episode")) throw fail("CATALOG_INVALID_INPUT");
        long deadline = deadline(); String suffix = "?type=" + kind + "&id=" + id + ("tv".equals(kind) ? "&season=" + season + "&episode=" + episode : "");
        try {
            JSONObject meta = detail(read("/api/details?id=" + id + "&type=" + kind, deadline), kind, id);
            JSONArray tracks = new JSONArray(); Set<String> seen = new HashSet<>(); boolean complete = true;
            try {
                Fetched subtitles = readWithBase("/api/subtitles" + suffix, deadline);
                if (subtitles.data.optBoolean("success", false)) addTracks(tracks, seen, subtitles.data.optJSONArray("subtitles"), subtitles.base);
                else complete = false;
            } catch (CatalogException error) { complete = false; }
            String title = meta.getString("title"); if ("tv".equals(kind)) title = text(title + " · S" + season + " E" + episode, 200, "Video");
            JSONObject content = WatchHistory.contentFromCatalog(kind, id, "tv".equals(kind) ? season : null, "tv".equals(kind) ? episode : null, title);
            if (content == null) throw fail("CATALOG_RESPONSE");
            return object("title", title, "tracks", tracks, "content", content, "subtitleCatalogComplete", complete);
        } catch (CatalogException error) { throw error; } catch (Exception error) { throw fail("CATALOG_RESPONSE"); }
    }
    private static JSONObject listing(JSONObject raw, String kind, int page) throws Exception {
        success(raw); JSONArray values = raw.optJSONArray("results"); if (values == null || values.length() > 200) throw fail("CATALOG_RESPONSE");
        JSONArray items = new JSONArray(); Set<String> seen = new HashSet<>();
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.optJSONObject(i); if (value == null) continue;
            String type = text(first(value, "type", "media_type"), 16, ""); if (!("movie".equals(type) || "tv".equals(type)) || !("all".equals(kind) || kind.equals(type))) continue;
            String id = sourceId(value.opt("id")); if (id == null || !seen.add(type + ":" + id)) continue;
            items.put(item(value, type, id));
        }
        return object("items", items, "page", sourceInt(raw.opt("page"), 1, 500, page),
            "totalPages", sourceInt(first(raw, "totalPages", "total_pages"), 0, 100000, 1),
            "totalResults", sourceInt(first(raw, "totalResults", "total_results"), 0, Integer.MAX_VALUE, items.length()));
    }
    private static JSONObject detail(JSONObject raw, String kind, String id) throws Exception {
        success(raw); if (!id.equals(sourceId(raw.opt("id"))) || !kind.equals(raw.optString("type"))) throw fail("CATALOG_RESPONSE");
        JSONObject result = item(raw, kind, id); JSONArray seasons = new JSONArray(), values = raw.optJSONArray("seasons"); Set<Integer> seen = new HashSet<>();
        if (values != null) {
            if (values.length() > 1001) throw fail("CATALOG_RESPONSE");
            for (int i = 0; i < values.length(); i++) {
                JSONObject value = values.optJSONObject(i); if (value == null) continue;
                int number = sourceInt(first(value, "number", "season_number"), 0, 1000, -1); if (number < 0 || !seen.add(number)) continue;
                seasons.put(object("number", number, "name", text(value.opt("name"), 200, number == 0 ? "Specials" : "Season " + number),
                    "episodeCount", sourceInt(first(value, "episodeCount", "episode_count"), 0, 10000, 0), "poster", image(first(value, "poster", "poster_path"), "w500")));
            }
        }
        result.put("seasons", seasons); result.put("runtime", nullableInt(raw.opt("runtime"), 1, 10000));
        result.put("numberOfSeasons", nullableInt(first(raw, "numberOfSeasons", "number_of_seasons"), 0, 1000));
        result.put("numberOfEpisodes", nullableInt(first(raw, "numberOfEpisodes", "number_of_episodes"), 0, 100000)); return result;
    }
    private static JSONObject item(JSONObject raw, String kind, String id) throws Exception {
        return object("id", id, "kind", kind, "title", text(first(raw, "title", "name"), 200, "Unknown title"),
            "poster", image(first(raw, "poster", "poster_path"), "w500"), "year", text(raw.opt("year"), 16, ""),
            "overview", text(raw.opt("overview"), 2000, ""), "rating", text(first(raw, "rating", "vote_average"), 16, ""));
    }
    private static void addTracks(JSONArray result, Set<String> seen, JSONArray values, String base) throws Exception {
        if (values == null) return;
        for (int i = 0; i < Math.min(values.length(), 128) && result.length() < 128; i++) {
            JSONObject value = values.optJSONObject(i); if (value == null) continue;
            String url = sourceUrl(value.opt("url"), base); if (url == null || !seen.add(url)) continue;
            String format = value.optString("format", "").toLowerCase(Locale.ROOT), type;
            switch (format) {
                case "srt": type = "application/x-subrip"; break;
                case "ass": type = "text/x-ass"; break;
                case "ssa": type = "text/x-ssa"; break;
                case "ttml": case "dfxp": type = "application/ttml+xml"; break;
                case "": case "vtt": type = "text/vtt"; break;
                default: continue;
            }
            result.put(object("id", result.length() + 1, "url", url, "contentType", type,
                "language", text(first(value, "language", "lang"), 64, ""), "label", text(first(value, "label", "name"), 200, "Subtitle"), "active", false));
        }
    }
    private static String sourceUrl(Object value, String base) {
        if (!(value instanceof String)) return null; String raw = (String) value;
        // The API can explicitly expose its own relative delivery route.
        if (raw.startsWith("/api/") && !raw.contains("\\")) raw = base + raw;
        // Provider file names contain literal spaces (about half of one sampled
        // episode's 63 tracks); encode them instead of silently dropping the track.
        raw = raw.trim().replace(" ", "%20");
        try { return NativeBackend.publicUrl(raw); } catch (Exception invalid) { return null; }
    }
    private static boolean alreadyDelivered(String url) {
        try { URI uri = new URI(url); String host = uri.getHost(); return uri.getPath().contains("/proxy") || host.endsWith(".workers.dev"); }
        catch (Exception invalid) { return false; }
    }
    private static String mediaType(String type, String url) {
        type = type.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        if ("hls".equals(type) || type.contains("mpegurl") || url.contains(".m3u8")) return "application/vnd.apple.mpegurl";
        if ("mp4".equals(type) || "video/mp4".equals(type) || url.contains(".mp4")) return "video/mp4";
        if ("webm".equals(type) || "video/webm".equals(type) || url.contains(".webm")) return "video/webm";
        if ("dash".equals(type) || "application/dash+xml".equals(type) || url.contains(".mpd")) return "application/dash+xml";
        return null;
    }
    private static boolean encrypted(JSONObject source) { return source.has("drm") && !source.isNull("drm") && !Boolean.FALSE.equals(source.opt("drm")) || source.has("keySystem") || source.has("licenseUrl"); }
    private JSONObject read(String path, long deadline) throws CatalogException { return readWithBase(path, deadline).data; }
    private Fetched readWithBase(String path, long deadline) throws CatalogException {
        for (int i = 0; i < BASES.length; i++) {
            long left = deadline - System.nanoTime() / 1000000L; if (left <= 0) throw fail("CATALOG_UNAVAILABLE");
            Response response;
            try { response = transport.get(BASES[i] + path, (int) Math.min(left, 20000)); }
            catch (Exception unavailable) { continue; }
            if (response.status >= 500 && response.status <= 599) continue;
            if (response.status < 200 || response.status >= 300) throw fail("CATALOG_UNAVAILABLE");
            if (response.body == null || response.body.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES || response.contentType == null || !response.contentType.toLowerCase(Locale.ROOT).contains("json")) throw fail("CATALOG_RESPONSE");
            try {
                JSONTokener tokener = new JSONTokener(response.body); Object value = tokener.nextValue();
                if (!(value instanceof JSONObject) || tokener.nextClean() != 0) throw fail("CATALOG_RESPONSE"); return new Fetched(BASES[i], (JSONObject) value);
            } catch (Exception malformed) { throw fail("CATALOG_RESPONSE"); }
        }
        throw fail("CATALOG_UNAVAILABLE");
    }
    private static Response get(String raw, int remainingMs) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(raw).openConnection(Proxy.NO_PROXY);
        java.util.Timer timeout = new java.util.Timer("catalog-deadline", true);
        timeout.schedule(new java.util.TimerTask() { @Override public void run() { connection.disconnect(); } }, remainingMs);
        long deadline = System.nanoTime() / 1000000L + remainingMs;
        try {
            connection.setConnectTimeout(Math.min(10000, remainingMs)); connection.setReadTimeout(Math.min(10000, remainingMs));
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false); connection.setRequestMethod("GET"); connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode(); if (status < 200 || status >= 300) return new Response(status, "");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (bytes.size() + count > MAX_BYTES || System.nanoTime() / 1000000L > deadline) throw new java.io.IOException("Bounded catalog read failed"); bytes.write(buffer, 0, count);
                }
                return new Response(status, new String(bytes.toByteArray(), StandardCharsets.UTF_8), connection.getContentType());
            }
        } finally { timeout.cancel(); connection.disconnect(); }
    }
    private static long deadline() { return System.nanoTime() / 1000000L + DEADLINE_MS; }
    private static void success(JSONObject raw) throws CatalogException { if (!raw.optBoolean("success", false)) throw fail("CATALOG_RESPONSE"); }
    private static void fields(JSONObject params, String... allowed) throws CatalogException {
        if (params == null) throw fail("CATALOG_INVALID_INPUT"); Set<String> names = new HashSet<>(); java.util.Collections.addAll(names, allowed);
        java.util.Iterator<String> keys = params.keys(); while (keys.hasNext()) if (!names.contains(keys.next())) throw fail("CATALOG_INVALID_INPUT");
    }
    private static String kind(JSONObject params, boolean all) throws CatalogException { Object raw = params.opt("kind"); if ("movie".equals(raw) || "tv".equals(raw) || all && "all".equals(raw)) return (String) raw; throw fail("CATALOG_INVALID_INPUT"); }
    private static String id(JSONObject params) throws CatalogException { Object raw = params.opt("id"); String value = raw instanceof String ? sourceId(raw) : null; if (value == null) throw fail("CATALOG_INVALID_INPUT"); return value; }
    private static String sourceId(Object raw) { String value = raw instanceof String || raw instanceof Number ? String.valueOf(raw) : ""; return value.matches("[1-9][0-9]{0,9}") ? value : null; }
    private static int integer(JSONObject params, String name, int min, int max, int fallback) throws CatalogException { if (!params.has(name) && fallback >= min) return fallback; Object raw = params.opt(name); if (!(raw instanceof Number)) throw fail("CATALOG_INVALID_INPUT"); double n = ((Number) raw).doubleValue(); if (!Double.isFinite(n) || n != Math.rint(n) || n < min || n > max) throw fail("CATALOG_INVALID_INPUT"); return (int) n; }
    private static int sourceInt(Object raw, int min, int max, int fallback) { try { double n = Double.parseDouble(String.valueOf(raw)); return Double.isFinite(n) && n == Math.rint(n) && n >= min && n <= max ? (int) n : fallback; } catch (Exception invalid) { return fallback; } }
    private static Object nullableInt(Object raw, int min, int max) { int n = sourceInt(raw, min, max, -1); return n < min ? JSONObject.NULL : n; }
    private static Object first(JSONObject raw, String a, String b) { return raw.has(a) && !raw.isNull(a) ? raw.opt(a) : raw.opt(b); }
    private static boolean controls(String value) { return value.matches("(?s).*[\\x00-\\x1f\\x7f].*"); }
    private static String text(Object raw, int max, String fallback) { if (!(raw instanceof String || raw instanceof Number)) return fallback; String value = String.valueOf(raw).replaceAll("[\\x00-\\x1f\\x7f]", " ").trim(); return value.isEmpty() ? fallback : value.substring(0, Math.min(max, value.length())); }
    private static Object image(Object raw, String size) { if (!(raw instanceof String)) return JSONObject.NULL; String value = (String) raw; if (value.matches("/[A-Za-z0-9_-]+\\.(?:jpg|jpeg|png|webp)")) value = "https://image.tmdb.org/t/p/" + size + value; return value.matches("https://image\\.tmdb\\.org/t/p/(?:w[0-9]{2,4}|original)/[A-Za-z0-9_-]+\\.(?:jpg|jpeg|png|webp)") ? value : JSONObject.NULL; }
    private static String encode(String value) throws Exception { return URLEncoder.encode(value, "UTF-8"); }
    private static JSONObject object(Object... fields) throws Exception { JSONObject result = new JSONObject(); for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]); return result; }
}

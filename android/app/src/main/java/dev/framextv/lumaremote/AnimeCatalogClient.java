package dev.framextv.lumaremote;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fixed-host bridge between frameXTV's AniList routes and the canonical TMDB library. */
final class AnimeCatalogClient {
    static final class AnimeException extends Exception {
        final String code;
        AnimeException(String code) { super(code); this.code = code; }
    }
    interface Transport { Response get(String url, int timeoutMs) throws Exception; }
    static final class Response {
        final int status; final String body, contentType;
        Response(int status, String body) { this(status, body, "application/json"); }
        Response(int status, String body, String contentType) { this.status = status; this.body = body; this.contentType = contentType; }
    }
    private static final String[] BASES = {"https://api.framextv.tech", "https://api.framextv.xyz", "https://api.framextv.cc"};
    private static final String MAPPING = "https://api.ani.zip/mappings?anilist_id=";
    private static final Pattern ANIME_PATH = Pattern.compile("^/watch/anime/([1-9][0-9]{0,9})(?:/([1-9][0-9]{0,3}))?/?$");
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final long DEADLINE_MS = 45_000;
    private final Transport transport;

    AnimeCatalogClient() { this(AnimeCatalogClient::get); }
    AnimeCatalogClient(Transport transport) { this.transport = transport; }

    /** Resolves only a trusted frameXTV anime page and returns its canonical TV identity. */
    JSONObject identify(String pageUrl, String title, String audioHint) throws AnimeException {
        int[] route = route(pageUrl);
        if (route == null) throw fail("SOURCE_UNSUPPORTED");
        JSONObject mapping = mapping(Integer.toString(route[0]), deadline());
        JSONObject episode = mapping.optJSONObject("episodes") == null ? null
                : mapping.optJSONObject("episodes").optJSONObject(Integer.toString(route[1]));
        String tmdb = sourceId(mapping.optJSONObject("mappings") == null ? null : mapping.optJSONObject("mappings").opt("themoviedb_id"));
        int season = integer(episode == null ? null : episode.opt("seasonNumber"), 0, 9999, -1);
        int number = integer(episode == null ? null : episode.opt("episodeNumber"), 1, 9999, -1);
        JSONObject content = WatchHistory.contentFromCatalog("tv", tmdb, season, number, title);
        if (content == null) throw fail("SOURCE_UNSUPPORTED");
        put(content, "animeId", Integer.toString(route[0]));
        put(content, "animeAudio", "dub".equals(audioHint) ? "dub" : "sub".equals(audioHint) ? "sub" : audioType(pageUrl));
        return content;
    }

    /** Verifies an AniList id exposed by the same-origin anime player against
     * the canonical TMDB route which may still be visible during SPA redirect. */
    JSONObject identify(JSONObject canonical, String animeId, int animeEpisode, String audioHint) throws AnimeException {
        if (canonical == null || !"tv".equals(canonical.optString("kind"))) throw fail("SOURCE_UNSUPPORTED");
        String tmdb = sourceId(canonical.opt("contentId")), provider = sourceId(animeId);
        int season = integer(canonical.opt("season"), 0, 9999, -1);
        int episode = integer(canonical.opt("episode"), 1, 9999, -1);
        if (tmdb == null || provider == null || season < 0 || episode < 1) throw fail("SOURCE_UNSUPPORTED");
        JSONObject mapping = mapping(provider, deadline());
        JSONObject ids = mapping.optJSONObject("mappings");
        if (!tmdb.equals(sourceId(ids == null ? null : ids.opt("themoviedb_id")))
                || mappedEpisode(mapping.optJSONObject("episodes"), season, episode) != animeEpisode) throw fail("CATALOG_RESPONSE");
        JSONObject content = WatchHistory.contentFromCatalog("tv", tmdb, season, episode, canonical.optString("title"));
        if (content == null) throw fail("CATALOG_RESPONSE");
        put(content, "animeId", provider);
        put(content, "animeAudio", "dub".equals(audioHint) ? "dub" : "sub");
        return content;
    }

    /** Resolves a canonical TV episode through its verified AniList series provenance. */
    JSONObject resolve(JSONObject selection) throws AnimeException {
        fields(selection, "kind", "id", "season", "episode", "animeId", "animeAudio");
        if (!"tv".equals(selection.opt("kind"))) throw fail("CATALOG_INVALID_INPUT");
        String tmdb = sourceId(selection.opt("id")), animeId = sourceId(selection.opt("animeId"));
        String animeAudio = "dub".equals(selection.optString("animeAudio")) ? "dub" : "sub";
        if (selection.has("animeAudio") && !("sub".equals(selection.opt("animeAudio")) || "dub".equals(selection.opt("animeAudio")))) throw fail("CATALOG_INVALID_INPUT");
        int season = integer(selection.opt("season"), 0, 9999, -1), episode = integer(selection.opt("episode"), 1, 9999, -1);
        if (tmdb == null || animeId == null || season < 0 || episode < 1) throw fail("CATALOG_INVALID_INPUT");
        long deadline = deadline(); JSONObject mapping = mapping(animeId, deadline);
        String mappedTmdb = sourceId(mapping.optJSONObject("mappings") == null ? null : mapping.optJSONObject("mappings").opt("themoviedb_id"));
        if (!tmdb.equals(mappedTmdb)) throw fail("CATALOG_RESPONSE");
        int animeEpisode = mappedEpisode(mapping.optJSONObject("episodes"), season, episode);
        JSONObject details = readAnime("/anime/api/details?id=" + animeId, deadline).data;
        if (!details.optBoolean("success") || !animeId.equals(sourceId(details.opt("anilistId")))) throw fail("CATALOG_RESPONSE");
        String resolvedAnimeId = animeId;
        if (animeEpisode < 1) {
            resolvedAnimeId = seasonAnimeId(details.optJSONArray("seasons"), season);
            if (resolvedAnimeId == null) throw fail("SOURCE_UNAVAILABLE");
            mapping = mapping(resolvedAnimeId, deadline);
            String relatedTmdb = sourceId(mapping.optJSONObject("mappings") == null ? null : mapping.optJSONObject("mappings").opt("themoviedb_id"));
            if (relatedTmdb != null && !tmdb.equals(relatedTmdb)) throw fail("CATALOG_RESPONSE");
            animeEpisode = mappedEpisode(mapping.optJSONObject("episodes"), season, episode);
            if (animeEpisode < 1) throw fail("SOURCE_UNAVAILABLE");
            details = readAnime("/anime/api/details?id=" + resolvedAnimeId, deadline).data;
            if (!details.optBoolean("success") || !resolvedAnimeId.equals(sourceId(details.opt("anilistId")))) throw fail("CATALOG_RESPONSE");
        }
        Fetched fetched = readAnime("/anime/api/stream?id=" + resolvedAnimeId + "&type=anime&sub_type=" + animeAudio + "&season=1&episode=" + animeEpisode, deadline);
        JSONObject stream = fetched.data;
        JSONObject streamMeta = stream.optJSONObject("meta");
        if (!stream.optBoolean("success") || !animeAudio.equals(stream.optString("audio")) || streamMeta == null
                || !resolvedAnimeId.equals(sourceId(streamMeta.opt("id")))) throw fail("CATALOG_RESPONSE");
        JSONArray sources = stream.optJSONArray("sources"); String selected = null, contentType = null;
        for (int i = 0; sources != null && i < Math.min(50, sources.length()); i++) {
            JSONObject source = sources.optJSONObject(i); if (source == null || encrypted(source)) continue;
            String url = sourceUrl(source.opt("url"), fetched.base); if (url == null) continue;
            JSONObject headers = source.optJSONObject("headers");
            if (headers != null && headers.length() > 0 && !alreadyDelivered(url)) continue;
            String type = mediaType(source.optString("type"), url);
            if (type != null) { selected = url; contentType = type; break; }
        }
        if (selected == null) throw fail("SOURCE_UNSUPPORTED");
        JSONArray tracks = new JSONArray(); addTracks(tracks, stream.optJSONArray("subtitles"), fetched.base);
        String show = clean(details.optString("title", "Series " + tmdb), 160);
        String title = clean(show + " · S" + season + " E" + episode, 200);
        JSONObject content = WatchHistory.contentFromCatalog("tv", tmdb, season, episode, title);
        if (content == null) throw fail("CATALOG_RESPONSE"); put(content, "animeId", resolvedAnimeId); put(content, "animeAudio", animeAudio);
        return object("url", selected, "title", title, "contentType", contentType, "tracks", tracks,
                "currentTime", 0, "autoSubtitles", false, "content", content, "subtitleCatalogComplete", true);
    }

    static int[] route(String value) {
        try {
            URI uri = new URI(value == null ? "" : value); String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                    || !(host.equalsIgnoreCase("framextv.cc") || host.equalsIgnoreCase("framextv.xyz"))
                    || uri.getRawUserInfo() != null || !(uri.getPort() == -1 || uri.getPort() == 443)
                    || uri.getRawPath().contains("%") || uri.getRawFragment() != null) return null;
            Matcher match = ANIME_PATH.matcher(uri.getRawPath()); if (!match.matches()) return null;
            int id = integer(match.group(1), 1, Integer.MAX_VALUE, -1), episode = integer(match.group(2), 1, 9999, -1);
            if (episode < 1 && uri.getRawQuery() != null) {
                Matcher query = Pattern.compile("(?:^|&)episode=([1-9][0-9]{0,3})(?:&|$)").matcher(uri.getRawQuery());
                if (query.find()) episode = integer(query.group(1), 1, 9999, -1);
            }
            return id > 0 && episode > 0 ? new int[] {id, episode} : null;
        } catch (Exception invalid) { return null; }
    }

    static String audioType(String value) {
        try {
            String query = new URI(value == null ? "" : value).getRawQuery();
            if (query != null) {
                Matcher match = Pattern.compile("(?:^|&)sub_type=(sub|dub)(?:&|$)").matcher(query);
                if (match.find()) return match.group(1);
            }
        } catch (Exception ignored) { }
        return "sub";
    }

    private JSONObject mapping(String animeId, long deadline) throws AnimeException {
        Response response;
        try { response = transport.get(MAPPING + animeId, remaining(deadline)); }
        catch (Exception unavailable) { throw fail("CATALOG_UNAVAILABLE"); }
        return json(response, false);
    }
    private Fetched readAnime(String path, long deadline) throws AnimeException {
        boolean unavailable = false;
        for (String base : BASES) {
            Response response;
            try { response = transport.get(base + path, remaining(deadline)); }
            catch (Exception failure) { continue; }
            if (response.status == 404 || response.status == 410) { unavailable = true; continue; }
            if (response.status >= 500 && response.status <= 599) continue;
            return new Fetched(base, json(response, true));
        }
        throw fail(unavailable ? "SOURCE_UNAVAILABLE" : "CATALOG_UNAVAILABLE");
    }
    private static JSONObject json(Response response, boolean success) throws AnimeException {
        if (response == null || response.status < 200 || response.status >= 300) throw fail("CATALOG_UNAVAILABLE");
        if (response.body == null || response.body.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES
                || response.contentType == null || !response.contentType.toLowerCase(Locale.ROOT).contains("json")) throw fail("CATALOG_RESPONSE");
        try {
            JSONTokener tokener = new JSONTokener(response.body); Object parsed = tokener.nextValue();
            if (!(parsed instanceof JSONObject) || tokener.nextClean() != 0 || success && !((JSONObject) parsed).optBoolean("success")) throw new Exception();
            return (JSONObject) parsed;
        } catch (Exception malformed) { throw fail("CATALOG_RESPONSE"); }
    }
    private static int mappedEpisode(JSONObject episodes, int season, int episode) throws AnimeException {
        if (episodes == null || episodes.length() > 5000) throw fail("CATALOG_RESPONSE");
        for (Iterator<String> keys = episodes.keys(); keys.hasNext();) {
            String key = keys.next(); JSONObject value = episodes.optJSONObject(key); if (value == null) continue;
            if (integer(value.opt("seasonNumber"), 0, 9999, -1) == season
                    && integer(value.opt("episodeNumber"), 1, 9999, -1) == episode) return integer(key, 1, 9999, -1);
        }
        return -1;
    }
    private static String seasonAnimeId(JSONArray seasons, int season) throws AnimeException {
        if (seasons == null || seasons.length() > 1001) throw fail("CATALOG_RESPONSE");
        String found = null;
        for (int i = 0; i < seasons.length(); i++) {
            JSONObject value = seasons.optJSONObject(i); if (value == null) continue;
            if (integer(value.opt("number"), 0, 9999, -1) != season) continue;
            String id = sourceId(value.opt("id"));
            if (id == null || found != null && !found.equals(id)) throw fail("CATALOG_RESPONSE");
            found = id;
        }
        return found;
    }
    private static void addTracks(JSONArray result, JSONArray values, String base) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; values != null && i < values.length() && result.length() < 128; i++) {
            JSONObject value = values.optJSONObject(i); if (value == null) continue;
            String url = sourceUrl(value.opt("url"), base); if (url == null || !seen.add(url)) continue;
            put(result, object("id", result.length() + 1, "url", url, "contentType", "text/vtt",
                    "language", clean(value.optString("language", value.optString("lang")), 64),
                    "label", clean(value.optString("label", "Subtitle"), 200), "active", false));
        }
    }
    private static String sourceUrl(Object raw, String base) {
        if (!(raw instanceof String)) return null; String value = ((String) raw).trim().replace(" ", "%20");
        if (value.startsWith("/anime/api/") && !value.contains("\\")) value = base + value;
        try { return NativeBackend.publicUrl(value); } catch (Exception invalid) { return null; }
    }
    private static boolean alreadyDelivered(String url) {
        try { URI uri = new URI(url); return uri.getPath().contains("/proxy") || uri.getHost().endsWith(".workers.dev"); }
        catch (Exception invalid) { return false; }
    }
    private static String mediaType(String declared, String url) {
        String type = declared.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        if ("hls".equals(type) || type.contains("mpegurl") || url.contains(".m3u8")) return "application/vnd.apple.mpegurl";
        if ("mp4".equals(type) || "video/mp4".equals(type) || url.contains(".mp4")) return "video/mp4";
        if ("webm".equals(type) || "video/webm".equals(type) || url.contains(".webm")) return "video/webm";
        if ("dash".equals(type) || "application/dash+xml".equals(type) || url.contains(".mpd")) return "application/dash+xml";
        return null;
    }
    private static boolean encrypted(JSONObject value) { return value.has("drm") && !value.isNull("drm") && !Boolean.FALSE.equals(value.opt("drm")) || value.has("keySystem") || value.has("licenseUrl"); }
    private static void fields(JSONObject value, String... allowed) throws AnimeException {
        if (value == null) throw fail("CATALOG_INVALID_INPUT"); Set<String> names = new HashSet<>(); java.util.Collections.addAll(names, allowed);
        for (Iterator<String> keys = value.keys(); keys.hasNext();) if (!names.contains(keys.next())) throw fail("CATALOG_INVALID_INPUT");
    }
    private static String sourceId(Object value) { String id = value instanceof String || value instanceof Number ? String.valueOf(value) : ""; return id.matches("[1-9][0-9]{0,9}") ? id : null; }
    private static int integer(Object value, int min, int max, int fallback) { try { double number = Double.parseDouble(String.valueOf(value)); return Double.isFinite(number) && number == Math.rint(number) && number >= min && number <= max ? (int) number : fallback; } catch (Exception invalid) { return fallback; } }
    private static String clean(String value, int max) { String result = value == null ? "" : value.replaceAll("[\\p{Cntrl}\\u007f-\\u009f\\u202a-\\u202e\\u2066-\\u2069]", " ").trim(); return result.substring(0, Math.min(max, result.length())); }
    private static int remaining(long deadline) throws AnimeException { long left = deadline - System.nanoTime() / 1_000_000L; if (left <= 0) throw fail("CATALOG_UNAVAILABLE"); return (int) Math.min(20_000, left); }
    private static long deadline() { return System.nanoTime() / 1_000_000L + DEADLINE_MS; }
    private static AnimeException fail(String code) { return new AnimeException(code); }
    private static JSONObject object(Object... fields) { JSONObject value = new JSONObject(); for (int i = 0; i < fields.length; i += 2) put(value, (String) fields[i], fields[i + 1]); return value; }
    private static void put(JSONObject value, String key, Object item) { try { value.put(key, item); } catch (Exception ignored) { } }
    private static void put(JSONArray value, Object item) { value.put(item); }
    private static final class Fetched { final String base; final JSONObject data; Fetched(String base, JSONObject data) { this.base = base; this.data = data; } }
    private static Response get(String raw, int timeoutMs) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(raw).openConnection(Proxy.NO_PROXY);
        try {
            connection.setConnectTimeout(Math.min(10_000, timeoutMs)); connection.setReadTimeout(Math.min(15_000, timeoutMs));
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false); connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode(); String contentType = connection.getContentType();
            if (status < 200 || status >= 300) return new Response(status, "", contentType);
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) >= 0) { if (bytes.size() + count > MAX_BYTES) throw new Exception(); bytes.write(buffer, 0, count); }
                return new Response(status, new String(bytes.toByteArray(), StandardCharsets.UTF_8), contentType);
            }
        } finally { connection.disconnect(); }
    }
}

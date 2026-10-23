package dev.framextv.lumareceiver;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The minimum of the phone's catalog client the projector needs to continue a
 * series on its own (auto-next with every phone off): find the next episode and
 * resolve a fresh playable source plus its subtitle catalog. Fixed provider
 * hosts only; no caller-supplied fetch URL. Worker threads only.
 */
final class Catalog {
    private static final String[] BASES = {"https://api.framextv.tech", "https://api.framextv.xyz", "https://api.framextv.cc"};
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    static final class Episode {
        final String id; final int season, episode;
        Episode(String id, int season, int episode) { this.id = id; this.season = season; this.episode = episode; }
    }

    /** The episode after (season, episode), or null when the series has no further episode. */
    Episode next(String id, int season, int episode) throws Exception {
        List<Integer> numbers = episodes(id, season);
        for (int number : numbers) if (number > episode) return new Episode(id, season, number);
        JSONObject details = read("/api/details?id=" + id + "&type=tv").data;
        JSONArray seasons = details.optJSONArray("seasons");
        int nextSeason = Integer.MAX_VALUE;
        for (int i = 0; seasons != null && i < seasons.length(); i++) {
            JSONObject value = seasons.optJSONObject(i);
            if (value == null) continue;
            int number = integer(first(value, "number", "season_number"), -1);
            int count = integer(first(value, "episodeCount", "episode_count"), 0);
            if (number > season && number > 0 && count > 0) nextSeason = Math.min(nextSeason, number);
        }
        if (nextSeason == Integer.MAX_VALUE) return null;
        List<Integer> first = episodes(id, nextSeason);
        return first.isEmpty() ? null : new Episode(id, nextSeason, first.get(0));
    }

    private List<Integer> episodes(String id, int season) throws Exception {
        JSONArray values = read("/api/season?id=" + id + "&season=" + season).data.optJSONArray("episodes");
        List<Integer> numbers = new ArrayList<>();
        for (int i = 0; values != null && i < values.length() && i < 1000; i++) {
            JSONObject entry = values.optJSONObject(i);
            int number = entry == null ? -1 : integer(first(entry, "number", "episode_number"), -1);
            if (number >= 1 && !numbers.contains(number)) numbers.add(number);
        }
        java.util.Collections.sort(numbers);
        return numbers;
    }

    /** A {@code player.open} media object for the episode, with its subtitle catalog in {@code tracks}. */
    JSONObject resolve(Episode target) throws Exception {
        String suffix = "?type=tv&id=" + target.id + "&season=" + target.season + "&episode=" + target.episode;
        JSONObject details = read("/api/details?id=" + target.id + "&type=tv").data;
        Fetched fetched = read("/api/stream" + suffix);
        JSONObject stream = fetched.data.optJSONObject("detail");
        if (stream == null) stream = fetched.data;
        if (!stream.optBoolean("success", false)) throw new ApiException("SOURCE_UNAVAILABLE", 404);
        JSONArray sources = stream.optJSONArray("sources");
        String url = null, type = null;
        for (int i = 0; sources != null && i < Math.min(sources.length(), 50) && url == null; i++) {
            JSONObject source = sources.optJSONObject(i);
            if (source == null || source.has("drm") && !source.isNull("drm") && !Boolean.FALSE.equals(source.opt("drm")) || source.has("keySystem")) continue;
            String candidate = absolute(source.optString("url"), fetched.base);
            if (candidate == null) continue;
            JSONObject headers = source.optJSONObject("headers");
            if (headers != null && headers.length() > 0 && !delivered(candidate)) continue;
            String kind = mediaType(source.optString("type", ""), candidate);
            if (kind != null) { url = candidate; type = kind; }
        }
        if (url == null) throw new ApiException("SOURCE_UNSUPPORTED", 404);
        JSONArray tracks = new JSONArray();
        addTracks(tracks, stream.optJSONArray("subtitles"), fetched.base);
        try {
            Fetched subtitles = read("/api/subtitles" + suffix);
            if (subtitles.data.optBoolean("success", false)) addTracks(tracks, subtitles.data.optJSONArray("subtitles"), subtitles.base);
        } catch (Exception optional) { /* playback without the extra subtitle catalog */ }
        String show = text(first(details, "title", "name"), "Series " + target.id);
        String title = show + " · S" + target.season + " E" + target.episode;
        JSONObject content = new JSONObject().put("kind", "tv").put("contentId", target.id).put("season", target.season)
            .put("episode", target.episode).put("title", title);
        return new JSONObject().put("url", url).put("contentType", type).put("title", title).put("content", content).put("tracks", tracks);
    }

    private static void addTracks(JSONArray result, JSONArray values, String base) throws Exception {
        for (int i = 0; values != null && i < values.length() && result.length() < 128; i++) {
            JSONObject value = values.optJSONObject(i);
            String url = value == null ? null : absolute(value.optString("url"), base);
            if (url == null) continue;
            boolean seen = false;
            for (int j = 0; j < result.length(); j++) if (url.equals(result.getJSONObject(j).optString("url"))) seen = true;
            if (seen) continue;
            result.put(new JSONObject().put("url", url).put("language", text(first(value, "language", "lang"), ""))
                .put("label", text(first(value, "label", "name"), "Subtitles")));
        }
    }

    private static String absolute(String raw, String base) {
        if (raw == null || raw.isEmpty()) return null;
        String value = raw.startsWith("/api/") && !raw.contains("\\") ? base + raw : raw;
        value = value.trim().replace(" ", "%20");
        try { return MediaRequest.httpUrl(value); } catch (ApiException invalid) { return null; }
    }

    private static boolean delivered(String url) {
        try { URI uri = new URI(url); return uri.getPath().contains("/proxy") || uri.getHost().endsWith(".workers.dev"); }
        catch (Exception invalid) { return false; }
    }

    static String mediaType(String declared, String url) {
        String type = declared.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        if ("hls".equals(type) || type.contains("mpegurl") || url.contains(".m3u8")) return MediaRequest.HLS;
        if ("mp4".equals(type) || "video/mp4".equals(type) || url.contains(".mp4")) return "video/mp4";
        if ("webm".equals(type) || "video/webm".equals(type) || url.contains(".webm")) return "video/webm";
        if ("dash".equals(type) || MediaRequest.DASH.equals(type) || url.contains(".mpd")) return MediaRequest.DASH;
        return null;
    }

    private static final class Fetched {
        final String base; final JSONObject data;
        Fetched(String base, JSONObject data) { this.base = base; this.data = data; }
    }

    private Fetched read(String path) throws Exception {
        for (String base : BASES) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(base + path).openConnection();
                connection.setConnectTimeout(10_000); connection.setReadTimeout(15_000);
                connection.setRequestProperty("Accept", "application/json");
                int status = connection.getResponseCode();
                if (status >= 500) continue;
                if (status != 200) throw new ApiException("CATALOG_UNAVAILABLE", 502);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = connection.getInputStream()) {
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = in.read(buffer)) >= 0) {
                        if (out.size() + count > MAX_BYTES) throw new ApiException("CATALOG_RESPONSE", 502);
                        out.write(buffer, 0, count);
                    }
                }
                Object value = new JSONTokener(new String(out.toByteArray(), StandardCharsets.UTF_8)).nextValue();
                if (!(value instanceof JSONObject) || !((JSONObject) value).optBoolean("success", true)) throw new ApiException("CATALOG_RESPONSE", 502);
                return new Fetched(base, (JSONObject) value);
            } catch (ApiException definite) { throw definite; }
            catch (Exception unreachable) { /* try the next host */ }
            finally { if (connection != null) connection.disconnect(); }
        }
        throw new ApiException("CATALOG_UNAVAILABLE", 502);
    }

    private static Object first(JSONObject raw, String a, String b) { return raw.has(a) && !raw.isNull(a) ? raw.opt(a) : raw.opt(b); }

    private static int integer(Object raw, int fallback) {
        try { double n = Double.parseDouble(String.valueOf(raw)); return n == Math.rint(n) ? (int) n : fallback; } catch (Exception invalid) { return fallback; }
    }

    private static String text(Object raw, String fallback) {
        if (!(raw instanceof String || raw instanceof Number)) return fallback;
        String value = String.valueOf(raw).replaceAll("[\\x00-\\x1f\\x7f\\u202a-\\u202e\\u2066-\\u2069]", " ").trim();
        return value.isEmpty() ? fallback : value.substring(0, Math.min(120, value.length()));
    }
}

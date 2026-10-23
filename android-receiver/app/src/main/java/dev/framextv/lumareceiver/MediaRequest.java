package dev.framextv.lumareceiver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Validated `player.open` payload. Pure Java + org.json so it is unit-testable. */
final class MediaRequest {
    static final String HLS = "application/vnd.apple.mpegurl", DASH = "application/dash+xml";
    static final String SRT = "application/x-subrip", VTT = "text/vtt";
    static final int MAX_SUBTITLES = 32;

    static final class Subtitle {
        final String url, mimeType, language, label; final boolean select;
        Subtitle(String url, String mimeType, String language, String label, boolean select) {
            this.url = url; this.mimeType = mimeType; this.language = language; this.label = label; this.select = select;
        }
    }

    final String url, contentType, title;
    final JSONObject content; // canonical identity or null
    final double resumeSeconds;
    final List<Subtitle> subtitles;
    final Map<String, String> headers;

    private MediaRequest(String url, String contentType, String title, JSONObject content, double resumeSeconds,
            List<Subtitle> subtitles, Map<String, String> headers) {
        this.url = url; this.contentType = contentType; this.title = title; this.content = content;
        this.resumeSeconds = resumeSeconds; this.subtitles = subtitles; this.headers = headers;
    }

    static MediaRequest parse(JSONObject media) throws ApiException {
        if (media == null) throw new ApiException("INVALID_MEDIA");
        String url = httpUrl(media.opt("url"));
        String type = contentType(media.optString("contentType", ""), url);
        JSONObject content = media.has("content") && !media.isNull("content") ? canonicalContent(media.optJSONObject("content")) : null;
        String title = media.has("title") ? label(media.opt("title"), 200) : "";
        if (title.isEmpty() && content != null) title = content.optString("title");
        double resume = 0;
        if (media.has("resumeSeconds")) resume = seconds(media.opt("resumeSeconds"));
        List<Subtitle> subtitles = new ArrayList<>();
        JSONArray list = media.optJSONArray("subtitles");
        if (media.has("subtitles") && list == null) throw new ApiException("INVALID_SUBTITLE");
        if (list != null) {
            if (list.length() > MAX_SUBTITLES) throw new ApiException("INVALID_SUBTITLE");
            for (int i = 0; i < list.length(); i++) subtitles.add(subtitle(list.optJSONObject(i)));
        }
        return new MediaRequest(url, type, title, content, resume, Collections.unmodifiableList(subtitles), headers(media.optJSONObject("headers")));
    }

    static Subtitle subtitle(JSONObject track) throws ApiException {
        if (track == null) throw new ApiException("INVALID_SUBTITLE");
        String url;
        try { url = httpUrl(track.opt("url")); } catch (ApiException invalid) { throw new ApiException("INVALID_SUBTITLE"); }
        String declared = track.optString("mimeType", "").toLowerCase(Locale.ROOT), path = URI.create(url).getPath().toLowerCase(Locale.ROOT);
        String mime;
        if (declared.equals(SRT) || declared.equals("text/srt") || declared.isEmpty() && path.endsWith(".srt")) mime = SRT;
        else if (declared.equals(VTT) || declared.isEmpty() && path.endsWith(".vtt")) mime = VTT;
        else if (declared.isEmpty()) mime = SRT; // The phone normalizes every selected track to UTF-8 SRT.
        else throw new ApiException("INVALID_SUBTITLE");
        String language = track.has("language") ? label(track.opt("language"), 32) : "";
        if (!language.isEmpty() && !language.matches("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*")) throw new ApiException("INVALID_SUBTITLE");
        if (track.has("select") && !(track.opt("select") instanceof Boolean)) throw new ApiException("INVALID_SUBTITLE");
        return new Subtitle(url, mime, language, track.has("label") ? label(track.opt("label"), 120) : "", track.optBoolean("select"));
    }

    /** http(s) only, no credentials/fragments; LAN hosts are allowed (phone-hosted subtitles). */
    static String httpUrl(Object value) throws ApiException {
        if (!(value instanceof String)) throw new ApiException("INVALID_MEDIA");
        String text = (String) value;
        if (text.isEmpty() || text.length() > 8192) throw new ApiException("INVALID_MEDIA");
        for (int i = 0; i < text.length(); i++) { char c = text.charAt(i); if (c <= 32 || c == 127 || c == '\\') throw new ApiException("INVALID_MEDIA"); }
        try {
            URI uri = new URI(text);
            String scheme = uri.getScheme();
            if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null || uri.getPort() > 65535) throw new ApiException("INVALID_MEDIA");
        } catch (ApiException rethrow) { throw rethrow; }
        catch (Exception invalid) { throw new ApiException("INVALID_MEDIA"); }
        return text;
    }

    static String contentType(String declared, String url) throws ApiException {
        String type = declared.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (type.equals("application/x-mpegurl") || type.equals("audio/mpegurl")) type = HLS;
        if (type.isEmpty()) {
            String path = URI.create(url).getPath().toLowerCase(Locale.ROOT);
            type = path.endsWith(".m3u8") ? HLS : path.endsWith(".mpd") ? DASH : path.endsWith(".webm") ? "video/webm"
                : path.endsWith(".mkv") ? "video/x-matroska" : "video/mp4";
        }
        if (!(type.equals(HLS) || type.equals(DASH) || type.equals("video/mp4") || type.equals("video/webm") || type.equals("video/x-matroska")))
            throw new ApiException("INVALID_MEDIA");
        return type;
    }

    /** Same canonical identity rules as the phone's WatchHistory.contentFromCatalog. */
    static JSONObject canonicalContent(JSONObject value) throws ApiException {
        try {
            if (value == null) throw new ApiException("INVALID_CONTENT");
            String kind = value.getString("kind"), id = value.getString("contentId");
            if (!("movie".equals(kind) || "tv".equals(kind)) || !id.matches("[1-9][0-9]{0,9}")) throw new ApiException("INVALID_CONTENT");
            boolean tv = "tv".equals(kind);
            Object season = value.opt("season"), episode = value.opt("episode");
            int s = 0, e = 0;
            if (tv) {
                s = integer(season, 0, 9999); e = integer(episode, 1, 9999);
            } else if (!(season == null || JSONObject.NULL.equals(season)) || !(episode == null || JSONObject.NULL.equals(episode))) {
                throw new ApiException("INVALID_CONTENT");
            }
            String key = "framextv:" + kind + ":" + id + (tv ? ":" + s + ":" + e : "");
            String watchUrl = "https://framextv.cc/watch/" + id + (tv ? "/" + s + "/" + e : "");
            if (value.has("key") && !key.equals(value.opt("key")) || value.has("watchUrl") && !watchUrl.equals(value.opt("watchUrl")))
                throw new ApiException("INVALID_CONTENT");
            JSONObject canonical = new JSONObject();
            canonical.put("key", key).put("kind", kind).put("contentId", id)
                .put("season", tv ? (Object) s : JSONObject.NULL).put("episode", tv ? (Object) e : JSONObject.NULL)
                .put("watchUrl", watchUrl).put("title", value.has("title") ? label(value.opt("title"), 200) : "");
            return canonical;
        } catch (Exception invalid) { throw new ApiException("INVALID_CONTENT"); }
    }

    static Map<String, String> headers(JSONObject value) throws ApiException {
        Map<String, String> result = new LinkedHashMap<>();
        if (value == null) return result;
        for (String name : new String[] {"Referer", "Origin", "User-Agent"}) {
            if (!value.has(name)) continue;
            String header = label(value.opt(name), 1024);
            if (header.isEmpty()) throw new ApiException("INVALID_MEDIA");
            result.put(name, header);
        }
        if (value.length() != result.size()) throw new ApiException("INVALID_MEDIA");
        return result;
    }

    static String label(Object value, int max) throws ApiException {
        if (!(value instanceof String)) throw new ApiException("INVALID_INPUT");
        String text = ((String) value).trim();
        if (text.length() > max) throw new ApiException("INVALID_INPUT");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 32 || c >= 127 && c <= 159 || c >= 0x202A && c <= 0x202E || c >= 0x2066 && c <= 0x2069) throw new ApiException("INVALID_INPUT");
        }
        return text;
    }

    static double seconds(Object value) throws ApiException {
        if (!(value instanceof Number)) throw new ApiException("INVALID_INPUT");
        double number = ((Number) value).doubleValue();
        if (Double.isNaN(number) || Double.isInfinite(number) || number < 0 || number > 17280000) throw new ApiException("INVALID_INPUT");
        return number;
    }

    static int integer(Object value, int min, int max) throws ApiException {
        if (!(value instanceof Number)) throw new ApiException("INVALID_INPUT");
        double number = ((Number) value).doubleValue();
        if (number != Math.floor(number) || number < min || number > max) throw new ApiException("INVALID_INPUT");
        return (int) number;
    }
}

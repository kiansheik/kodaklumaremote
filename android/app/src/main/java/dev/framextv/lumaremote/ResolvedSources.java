package dev.framextv.lumaremote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Resolved catalog sources, so Resume and replays skip the catalog's
 * details/stream/subtitles lookups. On-device cache only, newest titles kept;
 * every entry is checked with a one-byte request before reuse, and a source
 * that no longer answers falls back to a fresh lookup. Worker threads only.
 */
final class ResolvedSources {
    static final int MAX_ENTRIES = 60;
    static final long MAX_AGE_MS = 6L * 60 * 60 * 1000;
    private final File file;

    ResolvedSources(File directory) { file = new File(directory, "resolved-sources.json"); }

    /** The content key a catalog selection resolves to, or null when the selection is invalid. */
    static String key(JSONObject selection) {
        boolean tv = "tv".equals(selection.optString("kind"));
        Integer season = tv && selection.opt("season") instanceof Number ? selection.optInt("season") : null;
        Integer episode = tv && selection.opt("episode") instanceof Number ? selection.optInt("episode") : null;
        JSONObject content = WatchHistory.contentFromCatalog(selection.optString("kind"), String.valueOf(selection.opt("id")), season, episode, "Video");
        if (content == null) return null;
        String key = content.optString("key", null);
        Object anime = selection.opt("animeId");
        if (anime instanceof String && ((String) anime).matches("[1-9][0-9]{0,9}")) {
            String audio = "dub".equals(selection.optString("animeAudio")) ? "dub" : "sub";
            key += ":anime:" + anime + ":" + audio;
        }
        return key;
    }

    synchronized JSONObject get(String key, long now) {
        if (key == null) return null;
        JSONArray entries = read(), kept = new JSONArray();
        JSONObject result = null; boolean changed = false;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            String entryKey = entry == null ? "" : entry.optString("key");
            JSONObject media = entry == null ? null : entry.optJSONObject("media");
            boolean valid = !entryKey.isEmpty() && fresh(entry, now) && entryKey.equals(mediaKey(media));
            if (!valid || key.equals(entryKey) && result != null) { changed = true; continue; }
            kept.put(entry);
            if (key.equals(entryKey)) result = copy(media);
        }
        if (changed) write(kept);
        return result;
    }

    synchronized void put(String key, JSONObject media, long now) {
        if (key == null || media == null || now < 0 || !key.equals(mediaKey(media))) return;
        try {
            JSONArray kept = new JSONArray().put(new JSONObject().put("key", key).put("at", now).put("media", copy(media)));
            JSONArray entries = read();
            for (int i = 0; i < entries.length() && kept.length() < MAX_ENTRIES; i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry != null && !key.equals(entry.optString("key"))) kept.put(entry);
            }
            write(kept);
        } catch (Exception ignored) { /* the cache is an optimization only */ }
    }

    synchronized void forget(String key) {
        JSONArray kept = new JSONArray(), entries = read();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry != null && !String.valueOf(key).equals(entry.optString("key"))) kept.put(entry);
        }
        write(kept);
    }

    private static boolean fresh(JSONObject entry, long now) {
        if (entry == null || now < 0 || !(entry.opt("at") instanceof Number)) return false;
        double raw = ((Number) entry.opt("at")).doubleValue();
        if (!Double.isFinite(raw) || raw < 0 || raw != Math.floor(raw) || raw > Long.MAX_VALUE) return false;
        long at = (long) raw;
        return now >= at && now - at <= MAX_AGE_MS;
    }

    /** Canonical identity actually carried by the cached media, including anime edition. */
    private static String mediaKey(JSONObject media) {
        JSONObject content = media == null ? null : media.optJSONObject("content");
        if (content == null || !(content.opt("contentId") instanceof String)) return null;
        boolean tv = "tv".equals(content.optString("kind"));
        if (!tv && !"movie".equals(content.optString("kind"))) return null;
        Integer season = tv ? integer(content.opt("season"), 0, 9999) : null;
        Integer episode = tv ? integer(content.opt("episode"), 1, 9999) : null;
        if (tv && (season == null || episode == null) || !tv && (content.opt("season") != JSONObject.NULL || content.opt("episode") != JSONObject.NULL)) return null;
        JSONObject canonical = WatchHistory.contentFromCatalog(content.optString("kind"), content.optString("contentId"), season, episode, "Video");
        if (canonical == null || !canonical.optString("key").equals(content.optString("key"))
                || !canonical.optString("watchUrl").equals(content.optString("watchUrl"))) return null;
        String result = canonical.optString("key");
        if (content.has("animeId")) {
            String animeId = WatchHistory.animeId(content), audio = content.optString("animeAudio", "sub");
            if (animeId == null || !("sub".equals(audio) || "dub".equals(audio))) return null;
            result += ":anime:" + animeId + ":" + audio;
        } else if (content.has("animeAudio")) return null;
        return result;
    }

    private static Integer integer(Object value, int min, int max) {
        if (!(value instanceof Number)) return null;
        double number = ((Number) value).doubleValue();
        return Double.isFinite(number) && number == Math.rint(number) && number >= min && number <= max ? (int) number : null;
    }

    /** True when the source still answers; a stale provider link falls back to a fresh lookup. */
    static boolean reachable(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(NativeBackend.publicUrl(url)).openConnection();
            connection.setConnectTimeout(3000); connection.setReadTimeout(3000);
            connection.setRequestProperty("Range", "bytes=0-0");
            int status = connection.getResponseCode();
            return status == 200 || status == 206;
        } catch (Exception unreachable) { return false; }
        finally { if (connection != null) connection.disconnect(); }
    }

    private JSONArray read() {
        try { return file.isFile() ? new JSONArray(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)) : new JSONArray(); }
        catch (Exception corrupt) { return new JSONArray(); }
    }

    private void write(JSONArray entries) {
        File temporary = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            out.write(entries.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Exception failed) { temporary.delete(); return; }
        if (!temporary.renameTo(file)) temporary.delete();
    }

    private static JSONObject copy(JSONObject value) {
        try { return new JSONObject(value.toString()); } catch (Exception invalid) { return null; }
    }
}

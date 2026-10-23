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
    private final File file;

    ResolvedSources(File directory) { file = new File(directory, "resolved-sources.json"); }

    /** The content key a catalog selection resolves to, or null when the selection is invalid. */
    static String key(JSONObject selection) {
        boolean tv = "tv".equals(selection.optString("kind"));
        Integer season = tv && selection.opt("season") instanceof Number ? selection.optInt("season") : null;
        Integer episode = tv && selection.opt("episode") instanceof Number ? selection.optInt("episode") : null;
        JSONObject content = WatchHistory.contentFromCatalog(selection.optString("kind"), String.valueOf(selection.opt("id")), season, episode, "Video");
        return content == null ? null : content.optString("key", null);
    }

    synchronized JSONObject get(String key, long now) {
        if (key == null) return null;
        JSONArray entries = read();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null || !key.equals(entry.optString("key"))) continue;
            JSONObject media = entry.optJSONObject("media");
            return media != null ? copy(media) : null;
        }
        return null;
    }

    synchronized void put(String key, JSONObject media, long now) {
        if (key == null || media == null) return;
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

package dev.framextv.lumaremote;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** App-private progress keyed by content identity; playback URLs live only in RAM. */
public final class WatchHistory implements AutoCloseable {
    public interface ProgressListener { void onProgress(JSONObject record); }
    interface Store { String read() throws Exception; boolean write(String value) throws Exception; }
    private static final int MAX_ITEMS = 100, MAX_STORAGE = 256 * 1024;
    private static final double MAX_SECONDS = 17280000;
    private static final Pattern WATCH_PATH = Pattern.compile("^/watch(?:/(movie|tv))?/([0-9]{1,10})(?:/([0-9]{1,4})/([0-9]{1,4}))?/?$");
    private final Store store;
    private final LongSupplier wallClock, elapsedClock;
    private final Map<String, JSONObject> records = new LinkedHashMap<>();
    private final Map<String, Binding> bindings = new HashMap<>();
    private long sequence, lastWrite;
    private boolean dirty, readable = true, storageAvailable = true;
    private ProgressListener progressListener;
    private static final class Binding {
        final String source; final JSONObject content; final long sequence, lastStartedAt;
        final double requestedResume; final long resumeUntil;
        boolean resumePending, seenActive, paused;
        double lastPosition;
        Binding(String source, JSONObject content, long sequence, double resume, long now, long startedAt) {
            this.source = source; this.content = content; this.sequence = sequence; requestedResume = resume;
            lastStartedAt = startedAt;
            resumePending = resume > 0; resumeUntil = now + 30000;
        }
    }
    public WatchHistory(Context context) {
        this(preferences(context), System::currentTimeMillis, SystemClock::elapsedRealtime);
    }
    WatchHistory(Store store, LongSupplier wallClock, LongSupplier elapsedClock) {
        if (store == null || wallClock == null || elapsedClock == null) throw new IllegalArgumentException("History storage and clocks required");
        this.store = store; this.wallClock = wallClock; this.elapsedClock = elapsedClock; restore();
    }
    public synchronized void setProgressListener(ProgressListener listener) { progressListener = listener; }
    private static Store preferences(Context context) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences("luma_watch_history", Context.MODE_PRIVATE);
        return new Store() {
            @Override public String read() { return prefs.getString("history.v1", ""); }
            @Override public boolean write(String value) { return prefs.edit().putString("history.v1", value).commit(); }
        };
    }
    public static JSONObject contentFromPage(String pageUrl, String title) {
        try {
            if (pageUrl == null || pageUrl.length() > 2048) return null;
            URI page = new URI(pageUrl); String host = page.getHost();
            if (!"https".equals(page.getScheme()) || host == null || !(host.equalsIgnoreCase("framextv.cc") || host.equalsIgnoreCase("framextv.xyz"))
                    || page.getRawUserInfo() != null || !(page.getPort() == -1 || page.getPort() == 443) || page.getRawPath().contains("%")) return null;
            Matcher path = WATCH_PATH.matcher(page.getRawPath()); if (!path.matches()) return null;
            boolean tv = path.group(3) != null;
            if ("movie".equals(path.group(1)) && tv || "tv".equals(path.group(1)) && !tv) return null;
            return contentFromCatalog(tv ? "tv" : "movie", path.group(2), tv ? Integer.valueOf(path.group(3)) : null, tv ? Integer.valueOf(path.group(4)) : null, title);
        } catch (Exception invalid) { return null; }
    }
    public static JSONObject contentFromCatalog(String kind, String contentId, Integer season, Integer episode, String title) {
        try {
            if (!("movie".equals(kind) || "tv".equals(kind)) || contentId == null || !contentId.matches("[0-9]{1,10}")) return null;
            long parsed = Long.parseLong(contentId); if (parsed < 1) return null; String id = Long.toString(parsed);
            boolean tv = "tv".equals(kind);
            if (tv ? season == null || episode == null || season < 0 || season > 9999 || episode < 1 || episode > 9999 : season != null || episode != null) return null;
            String suffix = tv ? "/" + season + "/" + episode : "";
            return object("key", "framextv:" + kind + ":" + id + (tv ? ":" + season + ":" + episode : ""),
                    "kind", kind, "contentId", id, "season", tv ? season : JSONObject.NULL, "episode", tv ? episode : JSONObject.NULL,
                    "watchUrl", "https://framextv.cc/watch/" + id + suffix, "title", cleanTitle(title));
        } catch (Exception invalid) { return null; }
    }
    /** Called only after an accepted native handoff; failed launches create no record. */
    public synchronized boolean bind(String receiver, JSONObject media) {
        if (!receiver(receiver) || media == null) return false;
        JSONObject content = descriptor(media.optJSONObject("content")); String source;
        try { source = NativeBackend.publicUrl(media.opt("url")); } catch (Exception invalid) { return false; }
        if (content == null) return false;
        double resume = 0;
        if (media.has("resumeSeconds")) { if (!seconds(media.opt("resumeSeconds"))) return false; resume = ((Number) media.opt("resumeSeconds")).doubleValue(); }
        flush(); bindings.put(receiver, new Binding(source, content, ++sequence, resume, elapsedClock.getAsLong(), Math.max(0, wallClock.getAsLong()))); return true;
    }
    /** Ignores stale/unbound sources and preserves the final fresh point on stop. */
    public synchronized boolean observe(String receiver, JSONObject playback) {
        Binding bound = bindings.get(receiver);
        if (bound == null || playback == null || playback.optBoolean("stale")) return false;
        String source = playback.optString("sourceUrl", playback.optString("source", ""));
        if (!bound.source.equals(source)) { flush(); return false; }
        for (Binding other : bindings.values()) if (other != bound && other.sequence > bound.sequence && other.content.optString("key").equals(bound.content.optString("key"))) return false;
        Object rawActive = playback.opt("active"); if (!(rawActive instanceof Boolean)) return false;
        boolean active = (Boolean) rawActive;
        if (!seconds(playback.opt("positionSeconds")) || !seconds(playback.opt("durationSeconds"))) { if (!active) flush(); return false; }
        double position = ((Number) playback.opt("positionSeconds")).doubleValue(), duration = ((Number) playback.opt("durationSeconds")).doubleValue();
        if (duration > 0) position = Math.min(position, duration);
        if (!active && (!bound.seenActive || position < bound.lastPosition)) { flush(); return false; }
        long elapsed = elapsedClock.getAsLong();
        if (bound.resumePending) {
            if (position >= Math.max(0, bound.requestedResume - 5) || elapsed >= bound.resumeUntil) bound.resumePending = false;
            else return false;
        }
        if (active) bound.seenActive = true;
        boolean paused = playback.opt("paused") instanceof Boolean ? playback.optBoolean("paused") : playback.opt("playing") instanceof Boolean && !playback.optBoolean("playing");
        JSONObject entry = copy(bound.content); if (entry == null) return false;
        boolean completed = duration >= 60 && position >= duration * 0.95 && duration - position <= 30;
        put(entry, "positionSeconds", position); put(entry, "durationSeconds", duration); put(entry, "resumeSeconds", completed ? 0 : position);
        put(entry, "completed", completed); put(entry, "updatedAt", Math.max(0, wallClock.getAsLong()));
        put(entry, "lastStartedAt", bound.lastStartedAt); put(entry, "lastReceiver", receiver);
        String key = entry.optString("key"); boolean first = !records.containsKey(key), pauseChanged = bound.paused != paused;
        bound.paused = paused; bound.lastPosition = position; records.put(key, entry); dirty = true; trim();
        if (progressListener != null) try { progressListener.onProgress(copy(entry)); } catch (RuntimeException ignored) { /* Recent progress remains available if durable storage fails. */ }
        if (first || pauseChanged || !active || elapsed - lastWrite >= 15000) flush(); return true;
    }
    public synchronized JSONObject snapshot() { return object("version", 1, "items", items(), "storageAvailable", storageAvailable); }
    public synchronized JSONObject find(String watchUrlOrKey) {
        if (watchUrlOrKey == null) return null;
        JSONObject direct = records.get(watchUrlOrKey); if (direct != null) return copy(direct);
        JSONObject content = contentFromPage(watchUrlOrKey, ""); return content == null ? null : copy(records.get(content.optString("key")));
    }
    public synchronized void flush() {
        if (!dirty || !readable) return;
        String serialized = object("version", 1, "items", items()).toString();
        if (serialized.getBytes(StandardCharsets.UTF_8).length > MAX_STORAGE) { storageAvailable = false; return; }
        try { storageAvailable = store.write(serialized); if (storageAvailable) { dirty = false; lastWrite = elapsedClock.getAsLong(); } }
        catch (Exception unavailable) { storageAvailable = false; }
    }
    @Override public synchronized void close() { flush(); bindings.clear(); }
    private void restore() {
        try {
            String raw = store.read(); if (raw == null || raw.isEmpty()) return;
            if (raw.getBytes(StandardCharsets.UTF_8).length > MAX_STORAGE) throw new Exception();
            JSONObject saved = new JSONObject(raw); JSONArray items = saved.optJSONArray("items");
            if (!(saved.opt("version") instanceof Number) || ((Number) saved.opt("version")).doubleValue() != 1 || items == null || items.length() > MAX_ITEMS) throw new Exception();
            for (int i = 0; i < items.length(); i++) {
                JSONObject row = items.optJSONObject(i), content = descriptor(row);
                if (content == null || !seconds(row.opt("positionSeconds")) || !seconds(row.opt("durationSeconds")) || !receiver(row.optString("lastReceiver"))) throw new Exception();
                Object updated = row.opt("updatedAt");
                Object started = row.has("lastStartedAt") ? row.opt("lastStartedAt") : updated;
                if (!timestamp(updated) || !timestamp(started)) throw new Exception();
                double position = ((Number) row.opt("positionSeconds")).doubleValue(), duration = ((Number) row.opt("durationSeconds")).doubleValue();
                if (duration > 0) position = Math.min(position, duration);
                boolean completed = duration >= 60 && position >= duration * 0.95 && duration - position <= 30;
                put(content, "positionSeconds", position); put(content, "durationSeconds", duration); put(content, "resumeSeconds", completed ? 0 : position);
                put(content, "completed", completed); put(content, "updatedAt", ((Number) updated).longValue());
                put(content, "lastStartedAt", ((Number) started).longValue()); put(content, "lastReceiver", row.optString("lastReceiver"));
                records.put(content.optString("key"), content);
            }
        } catch (Exception malformed) { records.clear(); readable = storageAvailable = false; }
    }
    private JSONArray items() {
        List<JSONObject> sorted = new ArrayList<>(records.values()); sorted.sort(recency().reversed());
        JSONArray result = new JSONArray(); for (JSONObject entry : sorted) result.put(copy(entry)); return result;
    }
    private void trim() {
        while (records.size() > MAX_ITEMS) {
            String oldest = null; JSONObject value = null;
            for (Map.Entry<String, JSONObject> entry : records.entrySet()) if (oldest == null || recency().compare(entry.getValue(), value) < 0) { oldest = entry.getKey(); value = entry.getValue(); }
            records.remove(oldest);
        }
    }
    private static Comparator<JSONObject> recency() {
        return Comparator.comparingLong((JSONObject row) -> row.optLong("lastStartedAt", row.optLong("updatedAt"))).thenComparingLong(row -> row.optLong("updatedAt"));
    }
    private static JSONObject descriptor(JSONObject value) {
        if (value == null || !(value.opt("watchUrl") instanceof String)) return null;
        JSONObject parsed = contentFromPage(value.optString("watchUrl"), value.optString("title", ""));
        if (parsed == null || !parsed.optString("key").equals(value.optString("key")) || !parsed.optString("kind").equals(value.optString("kind"))
                || !parsed.optString("contentId").equals(value.optString("contentId"))) return null;
        if ("tv".equals(parsed.optString("kind"))) {
            if (!(value.opt("season") instanceof Number) || !(value.opt("episode") instanceof Number)
                    || ((Number) value.opt("season")).doubleValue() != parsed.optInt("season") || ((Number) value.opt("episode")).doubleValue() != parsed.optInt("episode")) return null;
        } else if (value.opt("season") != JSONObject.NULL || value.opt("episode") != JSONObject.NULL) return null;
        return parsed;
    }
    private static boolean receiver(String receiver) { return "luma".equals(receiver) || "philips".equals(receiver); }
    private static boolean seconds(Object value) { if (!(value instanceof Number)) return false; double number = ((Number) value).doubleValue(); return Double.isFinite(number) && number >= 0 && number <= MAX_SECONDS; }
    private static boolean timestamp(Object value) {
        if (!(value instanceof Number)) return false;
        double number = ((Number) value).doubleValue();
        return Double.isFinite(number) && number >= 0 && number <= Long.MAX_VALUE && number == Math.floor(number);
    }
    private static String cleanTitle(String title) {
        String result = title == null ? "" : title.replaceAll("[\\p{Cntrl}\\u007f-\\u009f\\u202a-\\u202e\\u2066-\\u2069]", " ").replaceAll("(?i)(?:[a-z][a-z0-9+.-]*://|www\\.)\\S+", "").trim();
        return result.substring(0, Math.min(200, result.length()));
    }
    private static JSONObject copy(JSONObject value) { try { return value == null ? null : new JSONObject(value.toString()); } catch (Exception invalid) { return null; } }
    private static JSONObject object(Object... fields) { JSONObject result = new JSONObject(); for (int i = 0; i < fields.length; i += 2) put(result, (String) fields[i], fields[i + 1]); return result; }
    private static void put(JSONObject value, String key, Object item) { try { value.put(key, item); } catch (Exception ignored) { } }
}

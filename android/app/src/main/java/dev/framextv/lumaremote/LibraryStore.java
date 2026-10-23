package dev.framextv.lumaremote;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Durable, app-private metadata/progress only. Stream URLs and authorization never enter SQLite. */
public final class LibraryStore implements AutoCloseable {
    public static final class LibraryException extends Exception {
        public final String code;
        private LibraryException(String code) { super("Offline library request failed"); this.code = code; }
    }
    interface Metadata {
        JSONObject details(String kind, String id) throws Exception;
        JSONObject episodes(String id, int season) throws Exception;
    }
    private static final long RETRY_MS = 5 * 60 * 1000, REFRESH_MS = 24 * 60 * 60 * 1000;
    private static final String ORDER = "last_started_at DESC,updated_at DESC";
    private final Object lock = new Object();
    private final SQLiteOpenHelper helper;
    private final Metadata metadata;
    private final LongSupplier clock;
    private final ThreadPoolExecutor enrichment;
    private SQLiteDatabase database;
    private boolean available = true, draining;
    private volatile boolean closed;
    public LibraryStore(Context context) {
        this(context, "luma_library.db", new Metadata() {
            private final CatalogClient client = new CatalogClient();
            @Override public JSONObject details(String kind, String id) throws Exception { return client.details(object("kind", kind, "id", id)); }
            @Override public JSONObject episodes(String id, int season) throws Exception { return client.episodes(object("id", id, "season", season)); }
        }, System::currentTimeMillis);
    }
    LibraryStore(Context context, String name, Metadata metadata, LongSupplier clock) {
        this.metadata = metadata; this.clock = clock;
        helper = new SQLiteOpenHelper(context.getApplicationContext(), name, null, 1) {
            @Override public void onCreate(SQLiteDatabase db) {
                db.execSQL("CREATE TABLE titles(kind TEXT NOT NULL,id TEXT NOT NULL,title TEXT NOT NULL,metadata_title TEXT NOT NULL DEFAULT '',metadata TEXT,metadata_at INTEGER NOT NULL DEFAULT 0,metadata_due INTEGER NOT NULL DEFAULT 0,revision INTEGER NOT NULL DEFAULT 0,last_started_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,PRIMARY KEY(kind,id))");
                db.execSQL("CREATE TABLE progress(content_key TEXT PRIMARY KEY,kind TEXT NOT NULL,id TEXT NOT NULL,season INTEGER,episode INTEGER,record TEXT NOT NULL,last_started_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)");
                db.execSQL("CREATE INDEX progress_title ON progress(kind,id,last_started_at DESC,updated_at DESC)");
                db.execSQL("CREATE TABLE seasons(id TEXT NOT NULL,number INTEGER NOT NULL,metadata TEXT NOT NULL,metadata_at INTEGER NOT NULL,PRIMARY KEY(id,number))");
            }
            @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { throw new SQLiteException("Unsupported library schema"); }
        };
        helper.setWriteAheadLoggingEnabled(true);
        try { database = helper.getWritableDatabase(); } catch (RuntimeException failure) { available = false; }
        enrichment = new ThreadPoolExecutor(1, 1, 20, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), runnable -> {
            Thread thread = new Thread(runnable, "Offline library metadata"); thread.setDaemon(true); return thread;
        });
        enrichment.allowCoreThreadTimeOut(true);
    }
    /** Imports only the real records still present in older recent history; idempotent and never erases newer SQLite progress. */
    public void migrate(JSONObject history) {
        if (history == null || history.optInt("version") != 1) return;
        JSONArray records = history.optJSONArray("items"); if (records == null || records.length() > 100) return;
        for (int i = 0; i < records.length(); i++) record(records.optJSONObject(i));
        schedule();
    }
    /** Called only by WatchHistory after source/freshness/ownership validation. Every accepted point is committed immediately. */
    public void record(JSONObject observed) {
        JSONObject row = progressRecord(observed); if (row == null) return;
        synchronized (lock) {
            if (closed || database == null) return;
            try {
                database.beginTransaction();
                String key = row.optString("key"), kind = row.optString("kind"), id = row.optString("contentId");
                JSONObject previous = oneProgress("content_key=?", new String[] {key});
                long started = row.optLong("lastStartedAt"), updated = row.optLong("updatedAt");
                if (previous != null && (started < previous.optLong("lastStartedAt") || started == previous.optLong("lastStartedAt") && updated < previous.optLong("updatedAt"))) {
                    database.setTransactionSuccessful(); return;
                }
                ContentValues progress = new ContentValues(); progress.put("content_key", key); progress.put("kind", kind); progress.put("id", id);
                if ("tv".equals(kind)) { progress.put("season", row.optInt("season")); progress.put("episode", row.optInt("episode")); }
                progress.put("record", row.toString()); progress.put("last_started_at", started); progress.put("updated_at", updated);
                if (database.insertWithOnConflict("progress", null, progress, SQLiteDatabase.CONFLICT_REPLACE) < 0) throw new SQLiteException();
                try (Cursor title = database.rawQuery("SELECT title,last_started_at,updated_at,revision FROM titles WHERE kind=? AND id=?", new String[] {kind, id})) {
                    ContentValues values = new ContentValues(); boolean exists = title.moveToFirst();
                    long oldStart = exists ? title.getLong(1) : 0, oldUpdated = exists ? title.getLong(2) : 0;
                    values.put("title", !exists || started >= oldStart ? row.optString("title") : title.getString(0));
                    values.put("last_started_at", Math.max(started, oldStart)); values.put("updated_at", Math.max(updated, oldUpdated));
                    if (previous == null) { values.put("metadata_due", 0); values.put("revision", exists ? title.getLong(3) + 1 : 1); }
                    if (exists) database.update("titles", values, "kind=? AND id=?", new String[] {kind, id});
                    else { values.put("kind", kind); values.put("id", id); if (database.insertOrThrow("titles", null, values) < 0) throw new SQLiteException(); }
                }
                database.setTransactionSuccessful(); available = true;
            } catch (RuntimeException failed) { available = false; }
            finally {
                try { if (database.inTransaction()) database.endTransaction(); }
                catch (RuntimeException failed) { available = false; }
            }
        }
        schedule();
    }
    /** Progress rows changed after {@code updatedAfter}, oldest first, for receiver sync. Read-only. */
    public JSONArray progressSince(long updatedAfter, int limit) {
        JSONArray result = new JSONArray();
        synchronized (lock) {
            if (closed || database == null) return result;
            try (Cursor rows = database.rawQuery("SELECT record FROM progress WHERE updated_at>? ORDER BY updated_at LIMIT ?",
                    new String[] {Long.toString(updatedAfter), Integer.toString(Math.max(1, Math.min(limit, 500)))})) {
                while (rows.moveToNext()) { JSONObject record = json(rows.getString(0)); if (record != null) result.put(record); }
            } catch (RuntimeException failed) { available = false; }
        }
        return result;
    }
    /** Offline, read-only and paginated; this method never schedules enrichment. */
    public JSONObject list(JSONObject params) throws LibraryException {
        fields(params, "offset", "limit", "query"); int offset = integer(params, "offset", 0, 1000000000, 0), limit = integer(params, "limit", 1, 100, 50);
        String query = params.has("query") ? string(params.opt("query"), 120) : ""; query = query.trim();
        synchronized (lock) {
            JSONArray items = new JSONArray(); long total = 0;
            if (!closed && database != null) try {
                String pattern = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                String where = query.isEmpty() ? "" : " WHERE title LIKE ? ESCAPE '\\' OR metadata_title LIKE ? ESCAPE '\\'";
                String[] search = query.isEmpty() ? new String[0] : new String[] {pattern, pattern};
                try (Cursor count = database.rawQuery("SELECT COUNT(*) FROM titles" + where, search)) { if (count.moveToFirst()) total = count.getLong(0); }
                List<String> args = new ArrayList<>(Arrays.asList(search)); args.add(Integer.toString(limit)); args.add(Integer.toString(offset));
                try (Cursor rows = database.rawQuery("SELECT kind,id,title,metadata,last_started_at,updated_at FROM titles" + where + " ORDER BY " + ORDER + ",kind,id LIMIT ? OFFSET ?", args.toArray(new String[0]))) {
                    while (rows.moveToNext()) items.put(title(rows));
                }
            } catch (RuntimeException failed) { available = false; }
            return object("version", 1, "storageAvailable", available && !closed, "items", items, "offset", offset, "limit", limit, "query", query,
                    "total", total, "hasMore", (long) offset + items.length() < total);
        }
    }
    /** Durable resume lookup by stable native content descriptor; no stream URL is accepted or returned. */
    public JSONObject findProgress(JSONObject content) {
        if (content == null) return null;
        try {
            String kind = kind(content.opt("kind")), id = id(content.opt("contentId"));
            JSONObject expected = WatchHistory.contentFromCatalog(kind, id, "tv".equals(kind) ? integer(content, "season", 0, 9999, -1) : null,
                    "tv".equals(kind) ? integer(content, "episode", 1, 9999, -1) : null, "");
            if (expected == null || !expected.optString("key").equals(content.optString("key"))) return null;
            synchronized (lock) { return closed || database == null ? null : oneProgress("content_key=?", new String[] {expected.optString("key")}); }
        } catch (Exception invalid) { return null; }
    }
    /** Returns only local cached metadata and saved episode progress. No network or player action. */
    public JSONObject details(JSONObject params) throws LibraryException {
        fields(params, "kind", "id"); String kind = kind(params.opt("kind")), id = id(params.opt("id"));
        synchronized (lock) {
            if (closed || database == null) throw fail("LIBRARY_UNAVAILABLE");
            try (Cursor rows = database.rawQuery("SELECT kind,id,title,metadata,last_started_at,updated_at FROM titles WHERE kind=? AND id=?", new String[] {kind, id})) {
                if (!rows.moveToFirst()) throw fail("LIBRARY_NOT_FOUND");
                JSONObject result = title(rows), cached = json(rows.getString(3));
                put(result, "version", 1); put(result, "storageAvailable", available); put(result, "movieProgress", JSONObject.NULL);
                put(result, "nextEpisode", JSONObject.NULL); JSONArray seasons = new JSONArray(); put(result, "seasons", seasons);
                if ("movie".equals(kind)) { put(result, "movieProgress", progress(oneProgress("kind=? AND id=?", new String[] {kind, id}))); return result; }
                TreeMap<Integer, JSONObject> seasonRows = new TreeMap<>(), catalogSeasons = new TreeMap<>();
                JSONArray declared = cached == null ? null : cached.optJSONArray("seasons");
                if (declared != null) for (int i = 0; i < declared.length(); i++) {
                    JSONObject season = declared.optJSONObject(i); if (season != null) { seasonRows.put(season.optInt("number"), copy(season)); catalogSeasons.put(season.optInt("number"), copy(season)); }
                }
                Map<Integer, TreeMap<Integer, JSONObject>> episodes = new TreeMap<>(), knownEpisodes = new TreeMap<>(); Set<Integer> complete = new HashSet<>();
                try (Cursor cache = database.rawQuery("SELECT number,metadata FROM seasons WHERE id=? ORDER BY number", new String[] {id})) {
                    while (cache.moveToNext()) {
                        int number = cache.getInt(0); JSONObject data = json(cache.getString(1)); if (data == null || data.optJSONArray("episodes") == null) continue;
                        complete.add(number); TreeMap<Integer, JSONObject> values = episodes.computeIfAbsent(number, ignored -> new TreeMap<>()), known = knownEpisodes.computeIfAbsent(number, ignored -> new TreeMap<>());
                        JSONArray entries = data.optJSONArray("episodes");
                        for (int i = 0; i < entries.length(); i++) { JSONObject episode = entries.optJSONObject(i); if (episode != null) { values.put(episode.optInt("number"), copy(episode)); known.put(episode.optInt("number"), copy(episode)); } }
                    }
                }
                try (Cursor watched = database.rawQuery("SELECT season,episode,record FROM progress WHERE kind='tv' AND id=? ORDER BY season,episode", new String[] {id})) {
                    while (watched.moveToNext()) {
                        int season = watched.getInt(0), episode = watched.getInt(1); TreeMap<Integer, JSONObject> values = episodes.computeIfAbsent(season, ignored -> new TreeMap<>());
                        JSONObject entry = values.get(episode); if (entry == null) { entry = episode(episode); values.put(episode, entry); }
                        put(entry, "progress", progress(json(watched.getString(2))));
                    }
                }
                for (Integer number : episodes.keySet()) if (!seasonRows.containsKey(number)) seasonRows.put(number, object("number", number, "name", number == 0 ? "Specials" : "Season " + number, "episodeCount", episodes.get(number).size(), "poster", JSONObject.NULL));
                for (Map.Entry<Integer, JSONObject> entry : seasonRows.entrySet()) {
                    int number = entry.getKey(); JSONObject season = entry.getValue(); JSONArray values = new JSONArray();
                    TreeMap<Integer, JSONObject> entries = episodes.get(number);
                    if (entries != null) for (JSONObject episode : entries.values()) { if (!episode.has("progress")) put(episode, "progress", JSONObject.NULL); values.put(episode); }
                    put(season, "episodes", values); put(season, "metadataAvailable", complete.contains(number)); seasons.put(season);
                }
                JSONObject latest = result.optJSONObject("latest");
                if (latest != null) {
                    int season = latest.optInt("season", -1), episode = latest.optInt("episode", -1);
                    JSONObject next = knownNext(id, season, episode, catalogSeasons, knownEpisodes, complete); if (next != null) put(result, "nextEpisode", next);
                }
                return result;
            } catch (LibraryException failed) { throw failed; }
            catch (RuntimeException failed) { available = false; throw fail("LIBRARY_UNAVAILABLE"); }
        }
    }
    /** Returns a next episode only when cached catalog metadata proves the exact
     * current episode and its successor. It never guesses across missing data. */
    public JSONObject nextEpisode(JSONObject params) throws LibraryException {
        fields(params, "kind", "id", "season", "episode");
        if (!"tv".equals(kind(params.opt("kind")))) throw fail("LIBRARY_INVALID_INPUT");
        String id = id(params.opt("id"));
        int currentSeason = integer(params, "season", 0, 9999, -1), currentEpisode = integer(params, "episode", 1, 9999, -1);
        synchronized (lock) {
            if (closed || database == null) throw fail("LIBRARY_UNAVAILABLE");
            try (Cursor title = database.rawQuery("SELECT metadata FROM titles WHERE kind='tv' AND id=?", new String[] {id})) {
                if (!title.moveToFirst()) throw fail("LIBRARY_NOT_FOUND");
                JSONObject cached = json(title.getString(0));
                TreeMap<Integer, JSONObject> declared = new TreeMap<>();
                JSONArray seasons = cached == null ? null : cached.optJSONArray("seasons");
                if (seasons != null) for (int i = 0; i < seasons.length(); i++) {
                    JSONObject season = seasons.optJSONObject(i); if (season != null) declared.put(season.optInt("number"), copy(season));
                }
                Map<Integer, TreeMap<Integer, JSONObject>> episodes = new TreeMap<>(); Set<Integer> complete = new HashSet<>();
                try (Cursor rows = database.rawQuery("SELECT number,metadata FROM seasons WHERE id=? ORDER BY number", new String[] {id})) {
                    while (rows.moveToNext()) {
                        int season = rows.getInt(0); JSONObject data = json(rows.getString(1)); JSONArray values = data == null ? null : data.optJSONArray("episodes");
                        if (values == null) continue;
                        complete.add(season); TreeMap<Integer, JSONObject> known = episodes.computeIfAbsent(season, ignored -> new TreeMap<>());
                        for (int i = 0; i < values.length(); i++) { JSONObject episode = values.optJSONObject(i); if (episode != null) known.put(episode.optInt("number"), copy(episode)); }
                    }
                }
                JSONObject next = knownNext(id, currentSeason, currentEpisode, declared, episodes, complete);
                TreeMap<Integer, JSONObject> current = episodes.get(currentSeason);
                boolean lookupComplete = cached != null && complete.contains(currentSeason) && current != null && current.containsKey(currentEpisode);
                if (lookupComplete && current.higherKey(currentEpisode) == null) {
                    Integer following = declared.containsKey(currentSeason) ? declared.higherKey(currentSeason) : null;
                    lookupComplete = following == null || complete.contains(following);
                }
                return object("version", 1, "lookupComplete", lookupComplete, "nextEpisode", next == null ? JSONObject.NULL : next);
            } catch (LibraryException failed) { throw failed; }
            catch (RuntimeException failed) { available = false; throw fail("LIBRARY_UNAVAILABLE"); }
        }
    }
    private JSONObject title(Cursor row) {
        String kind = row.getString(0), id = row.getString(1); JSONObject meta = json(row.getString(3));
        JSONObject latest = oneProgress("kind=? AND id=?", new String[] {kind, id}), summary = progress(latest);
        if (latest != null && summary != null) { put(summary, "season", latest.opt("season")); put(summary, "episode", latest.opt("episode")); }
        return object("kind", kind, "id", id, "title", meta == null ? clean(row.getString(2), 200) : meta.optString("title"), "overview", meta == null ? "" : meta.optString("overview"),
                "year", meta == null ? "" : meta.optString("year"), "poster", meta == null ? JSONObject.NULL : meta.opt("poster"), "metadataAvailable", meta != null,
                "lastStartedAt", row.getLong(4), "updatedAt", row.getLong(5), "latest", summary == null ? JSONObject.NULL : summary);
    }
    private JSONObject oneProgress(String where, String[] args) {
        try (Cursor row = database.rawQuery("SELECT record FROM progress WHERE " + where + " ORDER BY " + ORDER + " LIMIT 1", args)) { return row.moveToFirst() ? json(row.getString(0)) : null; }
    }
    private void schedule() {
        synchronized (lock) {
            if (closed || draining || database == null || metadata == null) return;
            draining = true;
            try { enrichment.execute(this::drain); } catch (RuntimeException rejected) { draining = false; }
        }
    }
    private void drain() {
        try {
            while (!closed && !Thread.currentThread().isInterrupted()) {
                String kind, id; long revision, at; JSONObject cached;
                synchronized (lock) {
                    if (closed || database == null) return;
                    try (Cursor next = database.rawQuery("SELECT kind,id,revision,metadata,metadata_at FROM titles WHERE metadata_due<=? ORDER BY metadata_due,last_started_at DESC LIMIT 1", new String[] {Long.toString(Math.max(0, clock.getAsLong()))})) {
                        if (!next.moveToFirst()) return;
                        kind = next.getString(0); id = next.getString(1); revision = next.getLong(2); cached = json(next.getString(3)); at = next.getLong(4);
                    }
                    ContentValues attempt = new ContentValues(); attempt.put("metadata_due", Math.max(0, clock.getAsLong()) + RETRY_MS); database.update("titles", attempt, "kind=? AND id=?", new String[] {kind, id});
                }
                boolean success = true;
                if (cached == null || clock.getAsLong() - at >= REFRESH_MS) {
                    try {
                        JSONObject fetched = metadataDetails(metadata.details(kind, id), kind, id);
                        synchronized (lock) {
                            if (closed) return;
                            ContentValues values = new ContentValues(); values.put("metadata", fetched.toString()); values.put("metadata_title", fetched.optString("title")); values.put("metadata_at", Math.max(0, clock.getAsLong()));
                            database.update("titles", values, "kind=? AND id=?", new String[] {kind, id}); cached = fetched;
                        }
                    } catch (Exception failed) { success = false; }
                }
                if ("tv".equals(kind) && !closed) {
                    Set<Integer> seen = new HashSet<>(); JSONObject latest;
                    synchronized (lock) {
                        try (Cursor rows = database.rawQuery("SELECT DISTINCT season FROM progress WHERE kind='tv' AND id=?", new String[] {id})) { while (rows.moveToNext()) seen.add(rows.getInt(0)); }
                        latest = oneProgress("kind='tv' AND id=?", new String[] {id});
                    }
                    for (Integer season : seen) { if (closed || Thread.currentThread().isInterrupted()) return; if (!enrichSeason(id, season)) success = false; }
                    // Follow a catalog-proven season boundary only. Never synthesize episode 1.
                    if (!closed && cached != null && latest != null) {
                        Integer following = null; int currentSeason = latest.optInt("season"), currentEpisode = latest.optInt("episode");
                        JSONArray declared = cached.optJSONArray("seasons");
                        if (declared != null) for (int i = 0; i < declared.length(); i++) { int number = declared.optJSONObject(i).optInt("number"); if (number > currentSeason && (following == null || number < following)) following = number; }
                        boolean atEnd = false;
                        synchronized (lock) {
                            JSONObject season = seasonCache(id, currentSeason); JSONArray entries = season == null ? null : season.optJSONArray("episodes"); int maximum = -1; boolean found = false;
                            if (entries != null) for (int i = 0; i < entries.length(); i++) { int number = entries.optJSONObject(i).optInt("number"); maximum = Math.max(maximum, number); if (number == currentEpisode) found = true; }
                            atEnd = found && maximum == currentEpisode;
                        }
                        if (atEnd && following != null && !enrichSeason(id, following)) success = false;
                    }
                }
                synchronized (lock) {
                    if (closed) return;
                    ContentValues next = new ContentValues(); next.put("metadata_due", Math.max(0, clock.getAsLong()) + (success ? REFRESH_MS : RETRY_MS));
                    database.update("titles", next, "kind=? AND id=? AND revision=?", new String[] {kind, id, Long.toString(revision)});
                }
            }
        } catch (RuntimeException failed) { synchronized (lock) { available = false; } }
        finally {
            synchronized (lock) {
                draining = false;
                // A last record can arrive between the empty selection and this
                // finally. Its durable due flag must not depend on another poll.
                if (!closed && database != null) try (Cursor due = database.rawQuery("SELECT 1 FROM titles WHERE metadata_due<=? LIMIT 1", new String[] {Long.toString(Math.max(0, clock.getAsLong()))})) {
                    if (due.moveToFirst()) schedule();
                } catch (RuntimeException failed) { available = false; }
            }
        }
    }
    private boolean enrichSeason(String id, int number) {
        if (number < 0 || number > 1000 || closed) return false;
        synchronized (lock) {
            try (Cursor row = database.rawQuery("SELECT metadata_at FROM seasons WHERE id=? AND number=?", new String[] {id, Integer.toString(number)})) {
                if (row.moveToFirst() && clock.getAsLong() - row.getLong(0) < REFRESH_MS) return true;
            }
        }
        try {
            JSONObject fetched = metadataEpisodes(metadata.episodes(id, number), id, number);
            synchronized (lock) {
                if (closed) return false;
                ContentValues values = new ContentValues(); values.put("id", id); values.put("number", number); values.put("metadata", fetched.toString()); values.put("metadata_at", Math.max(0, clock.getAsLong()));
                return database.insertWithOnConflict("seasons", null, values, SQLiteDatabase.CONFLICT_REPLACE) >= 0;
            }
        } catch (Exception failed) { return false; }
    }
    private JSONObject seasonCache(String id, int season) {
        try (Cursor rows = database.rawQuery("SELECT metadata FROM seasons WHERE id=? AND number=?", new String[] {id, Integer.toString(season)})) { return rows.moveToFirst() ? json(rows.getString(0)) : null; }
    }
    private static JSONObject knownNext(String id, int currentSeason, int currentEpisode, TreeMap<Integer, JSONObject> declared,
                                        Map<Integer, TreeMap<Integer, JSONObject>> episodes, Set<Integer> complete) {
        if (!complete.contains(currentSeason)) return null;
        TreeMap<Integer, JSONObject> current = episodes.get(currentSeason);
        if (current == null || !current.containsKey(currentEpisode)) return null;
        Integer number = current.higherKey(currentEpisode);
        if (number != null) return object("kind", "tv", "id", id, "season", currentSeason, "episode", number);
        Integer following = declared.containsKey(currentSeason) ? declared.higherKey(currentSeason) : null;
        TreeMap<Integer, JSONObject> values = following == null ? null : episodes.get(following);
        return following != null && complete.contains(following) && values != null && !values.isEmpty()
                ? object("kind", "tv", "id", id, "season", following, "episode", values.firstKey()) : null;
    }
    private static JSONObject progressRecord(JSONObject row) {
        if (row == null) return null;
        try {
            String kind = kind(row.opt("kind")), id = id(row.opt("contentId"));
            Integer season = "tv".equals(kind) ? integer(row, "season", 0, 9999, -1) : null, episode = "tv".equals(kind) ? integer(row, "episode", 1, 9999, -1) : null;
            JSONObject safe = WatchHistory.contentFromCatalog(kind, id, season, episode, row.optString("title"));
            if (safe == null || !safe.optString("key").equals(row.optString("key")) || !safe.optString("watchUrl").equals(row.optString("watchUrl"))) return null;
            double position = seconds(row.opt("positionSeconds")), duration = seconds(row.opt("durationSeconds")); if (duration > 0) position = Math.min(position, duration);
            long updated = time(row.opt("updatedAt")), started = row.has("lastStartedAt") ? time(row.opt("lastStartedAt")) : updated;
            String receiver = row.optString("lastReceiver"); if (!("philips".equals(receiver) || "luma".equals(receiver))) return null;
            boolean completed = duration >= 60 && position >= duration * .95 && duration - position <= 30;
            put(safe, "positionSeconds", position); put(safe, "durationSeconds", duration); put(safe, "resumeSeconds", completed ? 0 : position);
            put(safe, "completed", completed); put(safe, "updatedAt", updated); put(safe, "lastStartedAt", started); put(safe, "lastReceiver", receiver); return safe;
        } catch (Exception invalid) { return null; }
    }
    private static JSONObject progress(JSONObject record) {
        if (record == null) return null;
        return object("positionSeconds", record.opt("positionSeconds"), "durationSeconds", record.opt("durationSeconds"), "resumeSeconds", record.opt("resumeSeconds"),
                "completed", record.opt("completed"), "lastStartedAt", record.opt("lastStartedAt"), "updatedAt", record.opt("updatedAt"), "lastReceiver", record.opt("lastReceiver"));
    }
    private static JSONObject metadataDetails(JSONObject raw, String kind, String id) throws Exception {
        if (raw == null || !kind.equals(raw.optString("kind")) || !id.equals(raw.optString("id"))) throw new Exception();
        JSONObject safe = object("title", clean(string(raw.opt("title"), 200), 200), "overview", clean(raw.optString("overview"), 2000), "year", clean(raw.optString("year"), 16), "poster", image(raw.opt("poster")));
        JSONArray result = new JSONArray(), seasons = raw.optJSONArray("seasons");
        if (seasons != null) {
            if (seasons.length() > 1001) throw new Exception(); Set<Integer> seen = new HashSet<>();
            for (int i = 0; i < seasons.length(); i++) {
                JSONObject season = seasons.getJSONObject(i); int number = integer(season, "number", 0, 1000, -1); if (!seen.add(number)) throw new Exception();
                result.put(object("number", number, "name", clean(season.optString("name"), 200), "episodeCount", integer(season, "episodeCount", 0, 10000, 0), "poster", image(season.opt("poster"))));
            }
        }
        put(safe, "seasons", result); return safe;
    }
    private static JSONObject metadataEpisodes(JSONObject raw, String id, int season) throws Exception {
        if (raw == null || !id.equals(raw.optString("id")) || integer(raw, "season", 0, 1000, -1) != season) throw new Exception();
        JSONArray entries = raw.optJSONArray("episodes"); if (entries == null || entries.length() > 1000) throw new Exception();
        TreeMap<Integer, JSONObject> sorted = new TreeMap<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i); int number = integer(entry, "number", 1, 10000, -1); if (sorted.containsKey(number)) throw new Exception();
            JSONObject safe = episode(number); put(safe, "title", clean(entry.optString("title"), 200)); put(safe, "overview", clean(entry.optString("overview"), 2000));
            put(safe, "still", image(entry.opt("still"))); put(safe, "airDate", clean(entry.optString("airDate"), 32));
            if (entry.opt("runtime") instanceof Number) put(safe, "runtime", integer(entry, "runtime", 1, 10000, -1)); sorted.put(number, safe);
        }
        JSONArray result = new JSONArray(); for (JSONObject entry : sorted.values()) result.put(entry);
        return object("id", id, "season", season, "episodes", result);
    }
    private static JSONObject episode(int number) { return object("number", number, "title", "Episode " + number, "overview", "", "still", JSONObject.NULL, "airDate", "", "runtime", JSONObject.NULL); }
    private static Object image(Object value) { return value instanceof String && ((String) value).matches("https://image\\.tmdb\\.org/t/p/(?:w[0-9]{2,4}|original)/[A-Za-z0-9_.-]+\\.(?:jpg|jpeg|png|webp)") ? value : JSONObject.NULL; }
    private static String kind(Object value) throws LibraryException { if (!("movie".equals(value) || "tv".equals(value))) throw fail("LIBRARY_INVALID_INPUT"); return (String) value; }
    private static String id(Object value) throws LibraryException { if (!(value instanceof String) || !((String) value).matches("[1-9][0-9]{0,9}")) throw fail("LIBRARY_INVALID_INPUT"); return (String) value; }
    private static String string(Object value, int max) throws LibraryException { if (!(value instanceof String) || ((String) value).length() > max || ((String) value).matches("(?s).*[\\x00-\\x1f\\x7f].*")) throw fail("LIBRARY_INVALID_INPUT"); return (String) value; }
    private static int integer(JSONObject value, String key, int min, int max, int fallback) throws LibraryException {
        if (!value.has(key)) { if (fallback < min) throw fail("LIBRARY_INVALID_INPUT"); return fallback; }
        Object raw = value.opt(key); if (!(raw instanceof Number)) throw fail("LIBRARY_INVALID_INPUT"); double number = ((Number) raw).doubleValue();
        if (!Double.isFinite(number) || number != Math.floor(number) || number < min || number > max) throw fail("LIBRARY_INVALID_INPUT"); return (int) number;
    }
    private static void fields(JSONObject value, String... allowed) throws LibraryException {
        if (value == null) throw fail("LIBRARY_INVALID_INPUT"); Set<String> names = new HashSet<>(Arrays.asList(allowed));
        for (Iterator<String> keys = value.keys(); keys.hasNext();) if (!names.contains(keys.next())) throw fail("LIBRARY_INVALID_INPUT");
    }
    private static double seconds(Object raw) throws Exception { if (!(raw instanceof Number)) throw new Exception(); double number = ((Number) raw).doubleValue(); if (!Double.isFinite(number) || number < 0 || number > 17280000) throw new Exception(); return number; }
    private static long time(Object raw) throws Exception { if (!(raw instanceof Number)) throw new Exception(); double number = ((Number) raw).doubleValue(); if (!Double.isFinite(number) || number < 0 || number != Math.floor(number) || number > Long.MAX_VALUE) throw new Exception(); return ((Number) raw).longValue(); }
    private static String clean(String value, int max) { String safe = value == null ? "" : value.replaceAll("[\\p{Cntrl}\\u007f-\\u009f\\u202a-\\u202e\\u2066-\\u2069]", " ").replaceAll("(?i)(?:[a-z][a-z0-9+.-]*://|www\\.)\\S+", "").trim(); return safe.substring(0, Math.min(max, safe.length())); }
    private static JSONObject json(String value) { if (value == null) return null; try { return new JSONObject(value); } catch (Exception invalid) { return null; } }
    private static JSONObject copy(JSONObject value) { return value == null ? null : json(value.toString()); }
    private static LibraryException fail(String code) { return new LibraryException(code); }
    private static JSONObject object(Object... fields) { JSONObject value = new JSONObject(); for (int i = 0; i < fields.length; i += 2) put(value, (String) fields[i], fields[i + 1]); return value; }
    private static void put(JSONObject value, String key, Object item) { try { value.put(key, item == null ? JSONObject.NULL : item); } catch (Exception ignored) { } }
    @Override public void close() {
        synchronized (lock) { if (closed) return; closed = true; }
        enrichment.shutdownNow();
        synchronized (lock) { helper.close(); database = null; }
    }
}

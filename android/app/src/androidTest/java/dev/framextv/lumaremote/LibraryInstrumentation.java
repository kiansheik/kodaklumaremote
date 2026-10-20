package dev.framextv.lumaremote;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** App-private SQLite and fake metadata only; no receiver/catalog/CDN requests. */
public final class LibraryInstrumentation {
    private interface Check { void run() throws Exception; }
    private final Instrumentation runner;
    private int passed;
    private String phase = "setup";
    private LibraryInstrumentation(Instrumentation runner) { this.runner = runner; }
    public static void run(Instrumentation runner) { new LibraryInstrumentation(runner).execute(); }
    private static JSONObject object(Object... fields) throws Exception { JSONObject result = new JSONObject(); for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]); return result; }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static String databaseName() { return "library-fixture-" + UUID.randomUUID() + ".db"; }
    private static JSONObject content(String kind, String id, Integer season, Integer episode) { return WatchHistory.contentFromCatalog(kind, id, season, episode, "Saved " + kind + " " + id); }
    private static JSONObject record(String kind, String id, Integer season, Integer episode, double position, double duration, long started, long updated, String receiver) throws Exception {
        JSONObject result = content(kind, id, season, episode); result.put("positionSeconds", position).put("durationSeconds", duration).put("lastStartedAt", started).put("updatedAt", updated).put("lastReceiver", receiver);
        return result;
    }
    private static JSONObject movie(int id, long started) throws Exception { return record("movie", Integer.toString(id), null, null, 10, 1000, started, started, "philips"); }
    private static JSONObject select(String kind, String id) throws Exception { return object("kind", kind, "id", id); }
    private static JSONObject episodeProgress(JSONObject detail, int season, int episode) throws Exception {
        JSONArray seasons = detail.getJSONArray("seasons");
        for (int i = 0; i < seasons.length(); i++) if (seasons.getJSONObject(i).getInt("number") == season) {
            JSONArray entries = seasons.getJSONObject(i).getJSONArray("episodes");
            for (int j = 0; j < entries.length(); j++) if (entries.getJSONObject(j).getInt("number") == episode) return entries.getJSONObject(j).optJSONObject("progress");
        }
        return null;
    }
    private static final class FakeMetadata implements LibraryStore.Metadata {
        final AtomicInteger calls = new AtomicInteger(); volatile boolean failing;
        @Override public JSONObject details(String kind, String id) throws Exception {
            calls.incrementAndGet(); if (failing) throw new Exception("No external error retained");
            return object("kind", kind, "id", id, "title", "Cached title https://private.invalid/PRIVATE_METADATA_URL", "overview", "Cached synopsis", "year", "2025",
                    "poster", "https://image.tmdb.org/t/p/w500/fixture.jpg", "source", "PRIVATE_SOURCE_URL", "token", "PRIVATE_SESSION_TOKEN", "seasons", new JSONArray()
                    .put(object("number", 1, "name", "First season", "episodeCount", 2, "poster", JSONObject.NULL))
                    .put(object("number", 3, "name", "Following declared season", "episodeCount", 2, "poster", JSONObject.NULL)));
        }
        @Override public JSONObject episodes(String id, int season) throws Exception {
            calls.incrementAndGet(); if (failing) throw new Exception("No external error retained");
            int first = season == 3 ? 4 : 1, second = season == 3 ? 7 : 2;
            return object("id", id, "season", season, "source", "PRIVATE_SOURCE_URL", "episodes", new JSONArray()
                    .put(object("number", second, "title", "Second cached episode", "overview", "", "still", "https://image.tmdb.org/t/p/w500/still.jpg", "airDate", "2025-01-02", "runtime", 24, "headers", object("secret", "PRIVATE_SESSION_TOKEN")))
                    .put(object("number", first, "title", "First cached episode", "overview", "", "still", JSONObject.NULL, "airDate", "2025-01-01", "runtime", 23)));
        }
    }
    private static JSONObject awaitMetadata(LibraryStore library, String id, Integer nextSeason) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4); JSONObject detail;
        do {
            detail = library.details(select("tv", id));
            if (detail.optBoolean("metadataAvailable") && (nextSeason == null || detail.optJSONObject("nextEpisode") != null && detail.getJSONObject("nextEpisode").getInt("season") == nextSeason)) return detail;
            Thread.sleep(10);
        } while (System.nanoTime() < until);
        throw new AssertionError("Fake metadata did not become available");
    }
    private void check(String name, Check check) throws Exception { phase = name; check.run(); Bundle status = new Bundle(); status.putString("test", name); status.putInt("current", ++passed); runner.sendStatus(0, status); }
    private void execute() {
        Bundle result = new Bundle(); Context context = runner.getTargetContext();
        try {
            check("all titles survive recent-history eviction and resume after process restart", () -> {
                String name = databaseName();
                try {
                    try (LibraryStore library = new LibraryStore(context, name, null, () -> 1000000)) {
                        for (int i = 0; i < 180; i++) library.record(movie(1000 + i, 10000 + i));
                        require(library.list(new JSONObject()).getInt("total") == 180 && library.list(new JSONObject()).getJSONArray("items").length() == 50, "Library silently inherited a recent-history limit");
                        require(library.findProgress(content("movie", "1000", null, null)).getDouble("resumeSeconds") == 10, "Oldest title lost durable resume lookup");
                        library.migrate(object("version", 1, "items", new JSONArray().put(record("movie", "1000", null, null, 1, 1000, 10000, 9999, "philips"))));
                        require(library.findProgress(content("movie", "1000", null, null)).getDouble("positionSeconds") == 10, "Old recent-history migration overwrote newer durable progress");
                    }
                    try (LibraryStore restored = new LibraryStore(context, name, null, () -> 2000000)) {
                        require(restored.list(object("offset", 150, "limit", 100)).getJSONArray("items").length() == 30 && restored.list(new JSONObject()).getInt("total") == 180,
                                "Durable library did not survive reopen/pagination");
                        require(restored.findProgress(content("movie", "1000", null, null)).getDouble("resumeSeconds") == 10, "Progress beyond recent100 did not survive reopen");
                    }
                } finally { context.deleteDatabase(name); }
            });
            check("grouped series retains exact episodes and latest selection outranks older receiver polls", () -> {
                String name = databaseName();
                try (LibraryStore library = new LibraryStore(context, name, null, () -> 1000000)) {
                    library.record(record("tv", "42", 1, 1, 120, 1000, 1000, 5000, "philips"));
                    library.record(record("tv", "42", 1, 2, 300, 1000, 2000, 2001, "luma"));
                    library.record(record("tv", "42", 1, 1, 125, 1000, 1000, 6000, "philips"));
                    JSONObject list = library.list(new JSONObject()), detail = library.details(select("tv", "42"));
                    require(list.getInt("total") == 1 && list.getJSONArray("items").getJSONObject(0).getJSONObject("latest").getInt("episode") == 2, "Episode polls changed grouped latest identity");
                    require(episodeProgress(detail, 1, 1).getDouble("positionSeconds") == 125 && episodeProgress(detail, 1, 2).getDouble("positionSeconds") == 300
                            && "luma".equals(detail.getJSONObject("latest").getString("lastReceiver")), "Exact per-receiver episode progress was merged or dropped");
                    require(detail.isNull("nextEpisode") && !detail.getBoolean("metadataAvailable"), "Missing catalog guessed a next episode");
                } finally { context.deleteDatabase(name); }
            });
            check("offline search is literal and invalid query/selection cannot become a source fetch", () -> {
                String name = databaseName();
                try (LibraryStore library = new LibraryStore(context, name, null, () -> 1000000)) {
                    JSONObject named = movie(42, 1); named.put("title", "Percent% Under_score"); library.record(named); library.record(movie(43, 2));
                    require(library.list(object("query", "%")).getInt("total") == 1 && library.list(object("query", "_score")).getInt("total") == 1, "Search wildcards were not literal/escaped");
                    for (JSONObject invalid : new JSONObject[] {object("offset", -1), object("limit", 101), object("query", 1), object("query", "bad\nquery"), object("url", "https://private.invalid/PRIVATE_SOURCE_URL")}) {
                        try { library.list(invalid); throw new AssertionError("Invalid library query accepted"); } catch (LibraryStore.LibraryException expected) { require("LIBRARY_INVALID_INPUT".equals(expected.code), "Wrong query error"); }
                    }
                    try { library.details(object("kind", "movie", "id", 42)); throw new AssertionError("Numeric ID coerced"); } catch (LibraryStore.LibraryException expected) { }
                    try { library.details(select("movie", "99")); throw new AssertionError("Unseen title appeared"); } catch (LibraryStore.LibraryException expected) { require("LIBRARY_NOT_FOUND".equals(expected.code), "Wrong unseen title error"); }
                } finally { context.deleteDatabase(name); }
            });
            check("successful cached metadata remains offline and follows only declared season/episode order", () -> {
                String name = databaseName(); FakeMetadata metadata = new FakeMetadata();
                try {
                    try (LibraryStore library = new LibraryStore(context, name, metadata, () -> 1000000)) {
                        library.record(record("tv", "42", 3, 4, 1000, 1000, 500, 500, "philips"));
                        library.record(record("tv", "42", 1, 2, 1000, 1000, 2000, 2000, "luma"));
                        JSONObject detail = awaitMetadata(library, "42", 3), next = detail.getJSONObject("nextEpisode");
                        require(next.getInt("season") == 3 && next.getInt("episode") == 4 && episodeProgress(detail, 3, 4).getBoolean("completed"), "Known following selection was guessed or wrongly labeled unwatched");
                        require("Cached title".equals(detail.getString("title")) && detail.getString("overview").equals("Cached synopsis"), "Cached metadata was not projected or cleaned");
                        Thread.sleep(50); int calls = metadata.calls.get();
                        for (int i = 0; i < 5; i++) { library.list(object("query", "Cached")); library.details(select("tv", "42")); }
                        require(metadata.calls.get() == calls, "Offline library reads initiated metadata requests");
                    }
                    try (LibraryStore restored = new LibraryStore(context, name, null, () -> 2000000)) {
                        JSONObject detail = restored.details(select("tv", "42")); require(detail.getBoolean("metadataAvailable") && detail.getJSONObject("nextEpisode").getInt("episode") == 4,
                                "Offline metadata/known-next did not survive restart");
                        String serialized = stored(context, name); require(!serialized.contains("PRIVATE_SOURCE_URL") && !serialized.contains("PRIVATE_SESSION_TOKEN") && !serialized.contains("PRIVATE_METADATA_URL"), "Unknown metadata secrets or source URL were persisted");
                    }
                } finally { context.deleteDatabase(name); }
            });
            check("watched-only fallback rows cannot prove a next episode or a season boundary", () -> {
                String name = databaseName(); FakeMetadata metadata = new FakeMetadata();
                try (LibraryStore library = new LibraryStore(context, name, metadata, () -> 1000000)) {
                    library.record(record("tv", "42", 1, 99, 10, 1000, 1000, 1000, "philips"));
                    library.record(record("tv", "42", 1, 1, 20, 1000, 2000, 2000, "luma"));
                    JSONObject detail = awaitMetadata(library, "42", null); long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    while (detail.optJSONObject("nextEpisode") == null && System.nanoTime() < until) { Thread.sleep(10); detail = library.details(select("tv", "42")); }
                    require(detail.getJSONObject("nextEpisode").getInt("episode") == 2 && episodeProgress(detail, 1, 99) != null, "Watched fallback displaced actual next or vanished");
                    library.record(record("tv", "42", 1, 99, 30, 1000, 3000, 3000, "philips"));
                    require(library.details(select("tv", "42")).isNull("nextEpisode"), "Unlisted current episode proved a guessed next boundary");
                } finally { context.deleteDatabase(name); }
            });
            check("fresh authorized history feeds durable storage while metadata failure preserves progress", () -> {
                String name = databaseName(); FakeMetadata metadata = new FakeMetadata(); metadata.failing = true;
                try (LibraryStore library = new LibraryStore(context, name, metadata, () -> 1000000)) {
                    WatchHistory.Store recent = new WatchHistory.Store() { String data = ""; public String read() { return data; } public boolean write(String value) { data = value; return true; } };
                    WatchHistory history = new WatchHistory(recent, () -> 1000000, () -> 1000000); history.setProgressListener(library::record);
                    String source = "https://cdn.example/PRIVATE_SOURCE_URL?token=PRIVATE_SESSION_TOKEN";
                    history.bind("philips", object("content", content("tv", "42", 1, 1), "url", source));
                    require(library.list(new JSONObject()).getInt("total") == 0, "Unobserved handoff invented a watched record");
                    JSONObject playback = object("active", true, "source", source, "positionSeconds", 120, "durationSeconds", 1000, "paused", true);
                    history.observe("philips", new JSONObject(playback.toString()).put("source", "https://other.example/wrong.m3u8"));
                    history.observe("philips", new JSONObject(playback.toString()).put("stale", true)); require(library.list(new JSONObject()).getInt("total") == 0, "Stale/unbound source entered durable library");
                    history.observe("philips", playback); history.close();
                    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2); while (metadata.calls.get() == 0 && System.nanoTime() < until) Thread.sleep(10);
                    require(metadata.calls.get() > 0 && library.findProgress(content("tv", "42", 1, 1)).getDouble("resumeSeconds") == 120, "Metadata failure erased saved progress");
                    require(library.list(new JSONObject()).getBoolean("storageAvailable") && !library.details(select("tv", "42")).getBoolean("metadataAvailable"), "Metadata failure was confused with storage failure");
                    String serialized = stored(context, name); require(!serialized.contains("PRIVATE_SOURCE_URL") && !serialized.contains("PRIVATE_SESSION_TOKEN") && !serialized.contains("cdn.example"), "Playback source/token entered durable SQLite");
                } finally { context.deleteDatabase(name); }
            });
            result.putInt("passed", passed); result.putInt("failures", 0); runner.finish(Activity.RESULT_OK, result);
        } catch (Throwable failed) { result.putInt("passed", passed); result.putInt("failures", 1); result.putString("failure", phase + ": " + failed.getClass().getSimpleName() + " " + failed.getMessage()); runner.finish(Activity.RESULT_CANCELED, result); }
    }
    private static String stored(Context context, String name) {
        SQLiteDatabase database = SQLiteDatabase.openDatabase(context.getDatabasePath(name).getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        StringBuilder result = new StringBuilder();
        try {
            for (String query : new String[] {"SELECT title,metadata_title,metadata FROM titles", "SELECT record FROM progress", "SELECT metadata FROM seasons"}) {
                try (Cursor rows = database.rawQuery(query, null)) { while (rows.moveToNext()) for (int i = 0; i < rows.getColumnCount(); i++) if (!rows.isNull(i)) result.append(rows.getString(i)); }
            }
        } finally { database.close(); }
        return result.toString();
    }
}

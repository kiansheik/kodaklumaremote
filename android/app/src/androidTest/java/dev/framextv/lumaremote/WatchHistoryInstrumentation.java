package dev.framextv.lumaremote;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;

/** Deterministic app-private history tests; no receiver or external requests. */
public final class WatchHistoryInstrumentation {
    private interface Check { void run() throws Exception; }
    private static final class MemoryStore implements WatchHistory.Store {
        String value = ""; int writes; boolean available = true;
        @Override public String read() { return value; }
        @Override public boolean write(String next) { writes++; if (!available) return false; value = next; return true; }
    }
    private final Instrumentation runner;
    private int passed;
    private String phase = "setup";
    private WatchHistoryInstrumentation(Instrumentation runner) { this.runner = runner; }
    public static void run(Instrumentation runner) { new WatchHistoryInstrumentation(runner).execute(); }
    private static JSONObject object(Object... fields) throws Exception {
        JSONObject value = new JSONObject(); for (int i = 0; i < fields.length; i += 2) value.put((String) fields[i], fields[i + 1]); return value;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static JSONObject content(int episode) { return WatchHistory.contentFromCatalog("tv", "284833", 1, episode, "Series fixture"); }
    private static JSONObject media(JSONObject content, String source) throws Exception { return object("content", content, "url", source, "sourceToken", "DO_NOT_PERSIST", "cookie", "PRIVATE_COOKIE"); }
    private static JSONObject playback(String source, double position, double duration, boolean active, boolean paused) throws Exception {
        return object("sourceUrl", source, "positionSeconds", position, "durationSeconds", duration, "active", active, "paused", paused);
    }
    private void check(String name, Check check) throws Exception {
        phase = name; check.run(); Bundle status = new Bundle(); status.putString("test", name); status.putInt("current", ++passed); runner.sendStatus(0, status);
    }
    private void execute() {
        Bundle result = new Bundle();
        try {
            check("native watch paths provide stable typed identities without stream or title fallback", () -> {
                JSONObject canonical = WatchHistory.contentFromPage("https://framextv.cc/watch/284833/1/1?temporary=secret#part", "First title");
                JSONObject legacy = WatchHistory.contentFromPage("https://framextv.xyz/watch/tv/284833/1/1", "Different title");
                require(canonical != null && canonical.getString("key").equals(legacy.getString("key")) && "framextv:tv:284833:1:1".equals(canonical.getString("key")), "Same episode changed identity with host/title/query");
                require("https://framextv.cc/watch/284833/1/1".equals(canonical.getString("watchUrl")), "Canonical page retained temporary data");
                require(WatchHistory.contentFromCatalog("tv", "284833", 0, 1, "Special") != null, "Season zero was rejected");
                require("movie".equals(WatchHistory.contentFromPage("https://framextv.cc/watch/42", "Film").getString("kind")), "Movie route became a series");
                for (String invalid : new String[] {"http://framextv.cc/watch/42", "https://framextv.cc.attacker.example/watch/42", "https://user@framextv.cc/watch/42", "https://framextv.cc:8443/watch/42", "https://framextv.cc/watch/%34%32", "https://framextv.cc/watch/0", "https://framextv.cc/watch/42/1", "https://framextv.cc/watch/42/1/0"}) require(WatchHistory.contentFromPage(invalid, "Title") == null, "Invalid watch identity accepted");
                require(WatchHistory.contentFromCatalog("episode", "42", 1, 1, "Wrong kind") == null && WatchHistory.contentFromCatalog("movie", "42", 1, 1, "Wrong shape") == null, "Invalid typed identity accepted");
            });
            check("only fresh bound playback persists, with throttle, pause and final-position protection", () -> {
                MemoryStore store = new MemoryStore(); long[] wall = {1000000}, elapsed = {1000};
                WatchHistory history = new WatchHistory(store, () -> wall[0], () -> elapsed[0]); String source = "https://cdn.example/first.m3u8?token=EPHEMERAL";
                require(history.bind("philips", media(content(1), source)), "Valid history binding rejected");
                require(history.snapshot().getJSONArray("items").length() == 0, "An unobserved handoff created history");
                require(!history.observe("philips", playback("https://cdn.example/other.m3u8", 100, 300, true, false)), "Unbound source was accepted");
                JSONObject stale = playback(source, 100, 300, true, false).put("stale", true); require(!history.observe("philips", stale), "Stale state was accepted");
                require(history.observe("philips", playback(source, 120, 300, true, false)) && store.writes == 1, "First observed progress was not saved");
                elapsed[0] += 2000; wall[0] += 2000; history.observe("philips", playback(source, 122, 300, true, false)); require(store.writes == 1, "Every poll wrote preferences");
                history.observe("philips", playback(source, 123, 300, true, true)); require(store.writes == 2, "Pause did not flush progress");
                history.observe("philips", playback(source, 0, 0, false, false)); history.observe("philips", playback("", 0, 0, false, false));
                require(history.find(content(1).getString("key")).getDouble("positionSeconds") == 123, "Stop/default values erased final fresh progress");
                JSONObject invalid = playback(source, 1, 300, true, false).put("positionSeconds", "200"); require(!history.observe("philips", invalid), "A string position was coerced");
                require(!history.observe("philips", playback(source, -1, 300, true, false)) && !history.observe("philips", playback(source, 17280001, 300, true, false)), "Unbounded positions accepted");
                history.observe("philips", playback(source, 300, 300, false, false)); JSONObject ended = history.find(content(1).getString("key"));
                require(ended.getDouble("positionSeconds") == 300 && ended.getBoolean("completed") && ended.getDouble("resumeSeconds") == 0, "Near-end progress was deleted or failed to retain completion metadata"); history.close();
            });
            check("latest same-content handoff owns progress across receivers without merging episodes", () -> {
                MemoryStore store = new MemoryStore(); long[] clock = {100000}; WatchHistory history = new WatchHistory(store, () -> clock[0], () -> clock[0]);
                String tv = "https://cdn.example/tv.m3u8?token=one", projector = "https://different.example/projector.m3u8?token=two";
                history.bind("philips", media(content(1), tv)); history.observe("philips", playback(tv, 600, 1500, true, false));
                clock[0]++; history.bind("luma", media(content(1), projector)); history.observe("luma", playback(projector, 605, 1500, true, false));
                require(!history.observe("philips", playback(tv, 120, 1500, true, false)), "Old receiver overwrote newer handoff progress");
                history.observe("luma", playback(projector, 620, 1500, true, true));
                JSONObject shared = history.find("https://framextv.xyz/watch/284833/1/1");
                require(shared.getDouble("positionSeconds") == 620 && "luma".equals(shared.getString("lastReceiver")) && history.snapshot().getJSONArray("items").length() == 1, "Same episode was split by stream/receiver");
                clock[0]++; history.bind("philips", media(content(2), tv)); history.observe("philips", playback(tv, 5, 1400, true, false));
                require(history.snapshot().getJSONArray("items").length() == 2 && history.find(content(1).getString("key")).getInt("episode") == 1 && history.find(content(2).getString("key")).getInt("episode") == 2, "Episodes were merged or latest episode lost");
                long latestStart = clock[0]; clock[0] += 2000;
                history.observe("luma", playback(projector, 700, 1500, true, true));
                JSONArray recent = history.snapshot().getJSONArray("items");
                require(recent.getJSONObject(0).getInt("episode") == 2 && recent.getJSONObject(0).getLong("lastStartedAt") == latestStart
                        && recent.getJSONObject(1).getDouble("positionSeconds") == 700 && recent.getJSONObject(1).getLong("updatedAt") > latestStart,
                        "An older receiver poll displaced the latest selected episode or lost its progress");
                require(recent.getJSONObject(1).getLong("lastStartedAt") == 100001, "Polling changed a handoff start timestamp");
                history.flush(); WatchHistory restored = new WatchHistory(store, () -> clock[0], () -> clock[0]);
                require(restored.snapshot().getJSONArray("items").getJSONObject(0).getInt("episode") == 2, "Recency order changed after restart"); restored.close();
                clock[0]++; history.bind("philips", media(content(1), tv)); history.observe("philips", playback(tv, 701, 1500, true, false));
                require(history.snapshot().getJSONArray("items").getJSONObject(0).getInt("episode") == 1 && history.find(content(2).getString("key")) != null,
                        "Selecting an earlier episode did not promote it or deleted later episode progress"); history.close();
            });
            check("pending resume ignores pre-seek zero, accepts near target and expires without hiding real restart", () -> {
                MemoryStore store = new MemoryStore(); long[] clock = {100000}; WatchHistory history = new WatchHistory(store, () -> clock[0], () -> clock[0]); String source = "https://cdn.example/resume.m3u8";
                history.bind("philips", media(content(1), source)); history.observe("philips", playback(source, 400, 1500, true, true));
                history.bind("luma", media(content(1), source).put("resumeSeconds", 400)); clock[0] += 1000;
                require(!history.observe("luma", playback(source, 0, 1500, true, false)) && history.find(content(1).getString("key")).getDouble("positionSeconds") == 400, "Pre-seek zero clobbered resume progress");
                require(history.observe("luma", playback(source, 398, 1500, true, false)), "Near-target resume state was ignored");
                history.bind("philips", media(content(1), source).put("resumeSeconds", 398)); require(!history.observe("philips", playback(source, 0, 1500, true, false)), "Pending resume guard was missing");
                clock[0] += 30001; require(history.observe("philips", playback(source, 0, 1500, true, false)) && history.find(content(1).getString("key")).getDouble("positionSeconds") == 0, "Expired guard hid a genuine restart");
                require(!history.bind("luma", media(content(1), source).put("resumeSeconds", "400")), "Resume position was coerced"); history.close();
            });
            check("persistence is bounded and contains no playback credentials or temporary source URLs", () -> {
                MemoryStore store = new MemoryStore(); long[] clock = {100000}; WatchHistory history = new WatchHistory(store, () -> clock[0], () -> clock[0]);
                for (int i = 0; i < 105; i++) {
                    clock[0]++; JSONObject item = WatchHistory.contentFromCatalog("movie", Integer.toString(50000 + i), null, null, "Film " + i);
                    String stream = "https://cdn.example/secret-" + i + ".m3u8?signature=DO_NOT_PERSIST";
                    history.bind("philips", media(item, stream)); history.observe("philips", playback(stream, 10 + i, 1000, true, false));
                }
                history.flush(); require(history.snapshot().getJSONArray("items").length() == 100 && store.value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 256 * 1024, "History grew past its bounds");
                require(!store.value.contains("cdn.example") && !store.value.contains("signature") && !store.value.contains("DO_NOT_PERSIST") && !store.value.contains("PRIVATE_COOKIE"), "Ephemeral playback secrets persisted");
                WatchHistory restored = new WatchHistory(store, () -> clock[0], () -> clock[0]);
                require(restored.snapshot().getJSONArray("items").length() == 100 && restored.find("https://framextv.cc/watch/50000") == null && restored.find("https://framextv.cc/watch/50104").getDouble("positionSeconds") == 114, "Bounded progress did not survive restore");
                JSONArray leaked = restored.snapshot().getJSONArray("items"); leaked.getJSONObject(0).put("positionSeconds", 9999);
                require(restored.find("https://framextv.cc/watch/50104").getDouble("positionSeconds") == 114, "Caller mutated retained history"); history.close(); restored.close();
                JSONObject legacy = new JSONObject(store.value); JSONArray legacyItems = legacy.getJSONArray("items");
                for (int i = 0; i < legacyItems.length(); i++) legacyItems.getJSONObject(i).remove("lastStartedAt");
                store.value = legacy.toString(); WatchHistory migrated = new WatchHistory(store, () -> clock[0], () -> clock[0]);
                JSONObject latest = migrated.snapshot().getJSONArray("items").getJSONObject(0);
                require(migrated.snapshot().getBoolean("storageAvailable") && latest.getLong("lastStartedAt") == latest.getLong("updatedAt")
                        && latest.getString("contentId").equals("50104"), "Legacy records lost their fallback recency or progress"); migrated.close();
            });
            check("malformed or unavailable storage never silently overwrites existing data", () -> {
                MemoryStore corrupt = new MemoryStore(); corrupt.value = "{broken";
                WatchHistory protectedHistory = new WatchHistory(corrupt, () -> 100000, () -> 100000);
                String source = "https://cdn.example/progress.m3u8"; protectedHistory.bind("philips", media(content(1), source)); protectedHistory.observe("philips", playback(source, 100, 1000, true, false)); protectedHistory.flush();
                require(corrupt.writes == 0 && "{broken".equals(corrupt.value) && !protectedHistory.snapshot().getBoolean("storageAvailable"), "Corrupt existing history was overwritten");
                MemoryStore unavailable = new MemoryStore(); unavailable.available = false; WatchHistory retry = new WatchHistory(unavailable, () -> 100000, () -> 100000);
                retry.bind("philips", media(content(1), source)); retry.observe("philips", playback(source, 100, 1000, true, false));
                require(!retry.snapshot().getBoolean("storageAvailable") && retry.find(content(1).getString("key")) != null, "Storage failure erased in-memory progress");
                unavailable.available = true; retry.flush(); require(retry.snapshot().getBoolean("storageAvailable") && !unavailable.value.isEmpty(), "Storage recovery did not retry pending progress");
                for (Object invalidStart : new Object[] {-1, 1.5, "100000", JSONObject.NULL}) {
                    JSONObject invalidRecord = new JSONObject(unavailable.value); invalidRecord.getJSONArray("items").getJSONObject(0).put("lastStartedAt", invalidStart);
                    MemoryStore invalidTime = new MemoryStore(); invalidTime.value = invalidRecord.toString();
                    WatchHistory invalidHistory = new WatchHistory(invalidTime, () -> 100000, () -> 100000);
                    require(!invalidHistory.snapshot().getBoolean("storageAvailable") && invalidTime.writes == 0, "Invalid optional start timestamp was coerced or overwritten"); invalidHistory.close();
                }
                require(!retry.bind("philips", object("url", source, "title", "No stable metadata")), "Title/stream fallback created a content identity"); protectedHistory.close(); retry.close();
            });
            result.putInt("passed", passed); result.putInt("failures", 0); runner.finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putInt("passed", passed); result.putInt("failures", 1); result.putString("failure", phase + ": " + failure.getClass().getSimpleName() + ": " + failure.getMessage()); runner.finish(Activity.RESULT_CANCELED, result);
        }
    }
}

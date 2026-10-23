package dev.framextv.lumaremote;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Deterministic catalog contracts; never fetches a live title or controls a TV. */
public final class CatalogInstrumentation {
    private interface Check { void run() throws Exception; }
    private static JSONObject object(Object... fields) throws Exception { JSONObject result = new JSONObject(); for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]); return result; }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void rejects(String code, CatalogClient client, String action, JSONObject params) throws Exception {
        try { client.handle(action, params); throw new AssertionError("Expected fixed error"); }
        catch (CatalogClient.CatalogException expected) { require(code.equals(expected.code), "Wrong fixed catalog error"); }
    }
    private static CatalogClient.Response reply(JSONObject value) { return new CatalogClient.Response(200, value.toString()); }
    public static void run(Instrumentation instrumentation) {
        List<String> names = new ArrayList<>(); List<Check> checks = new ArrayList<>();
        names.add("typed selection cannot become an arbitrary provider request"); checks.add(() -> {
            List<String> requests = new ArrayList<>(); CatalogClient client = new CatalogClient((url, ms) -> { requests.add(url); return reply(object("success", true, "results", new JSONArray())); });
            rejects("CATALOG_INVALID_INPUT", client, "list", object("kind", "anime"));
            rejects("CATALOG_INVALID_INPUT", client, "details", object("kind", "movie", "id", "1&url=http://127.0.0.1"));
            rejects("CATALOG_INVALID_INPUT", client, "details", object("kind", "movie", "id", 1));
            rejects("CATALOG_INVALID_INPUT", client, "list", object("kind", "tv", "page", 1.5));
            rejects("CATALOG_INVALID_INPUT", client, "search", object("kind", "all", "query", "hello\nworld"));
            rejects("CATALOG_INVALID_INPUT", client, "resolve", object("kind", "movie", "id", "1", "season", 1));
            rejects("CATALOG_INVALID_INPUT", client, "resolve", object("kind", "tv", "id", "1", "season", 1, "episode", 1, "url", "https://media.example/a.mp4"));
            rejects("CATALOG_INVALID_INPUT", client, "hydrate", object("kind", "tv", "id", "1", "season", 1, "episode", 1, "url", "https://media.example/a.mp4"));
            require(requests.isEmpty(), "Invalid selection reached network");
            client.search(object("query", "a&b ? café", "kind", "all", "page", 2));
            require(requests.size() == 1 && requests.get(0).equals("https://api.framextv.tech/api/search?q=a%26b+%3F+caf%C3%A9&type=all&page=2"), "Search was not bounded/encoded");
        });
        names.add("catalog projection preserves identity pagination and safe posters"); checks.add(() -> {
            JSONArray results = new JSONArray().put(object("id", 42, "type", "movie", "title", "Film", "poster_path", "/safe.jpg", "overview", "Story", "year", 2024))
                .put(object("id", 42, "type", "movie", "title", "Duplicate"))
                .put(object("id", 43, "type", "tv", "title", "Series", "poster", "http://127.0.0.1/private"))
                .put(object("id", "1/2", "type", "movie", "title", "Bad ID"));
            CatalogClient client = new CatalogClient((url, ms) -> reply(object("success", true, "results", results, "page", 2, "total_pages", 6, "total_results", 102)));
            JSONObject result = client.search(object("query", "film", "kind", "all", "page", 2)); JSONArray items = result.getJSONArray("items");
            require(items.length() == 2 && "42".equals(items.getJSONObject(0).getString("id")) && "movie".equals(items.getJSONObject(0).getString("kind")), "Catalog identity changed");
            require(items.getJSONObject(0).getString("poster").equals("https://image.tmdb.org/t/p/w500/safe.jpg") && items.getJSONObject(1).isNull("poster"), "Unsafe poster exposed");
            require(result.getInt("page") == 2 && result.getInt("totalPages") == 6 && result.getInt("totalResults") == 102, "Pagination discarded");
        });
        names.add("series detail and special season episode contracts"); checks.add(() -> {
            List<String> requests = new ArrayList<>(); CatalogClient client = new CatalogClient((url, ms) -> {
                requests.add(url);
                if (url.contains("/api/details")) return reply(object("success", true, "id", 42, "type", "tv", "title", "Series", "number_of_seasons", 2, "seasons", new JSONArray()
                    .put(object("season_number", 0, "name", "Specials", "episode_count", 2)).put(object("number", 1, "episodeCount", 10))));
                return reply(object("success", true, "episodes", new JSONArray().put(object("episode_number", 1, "name", "Pilot", "air_date", "2024-01-01", "still_path", "/pilot.jpg"))));
            });
            JSONObject detail = client.details(object("kind", "tv", "id", "42"));
            require(detail.getInt("numberOfSeasons") == 2 && detail.getJSONArray("seasons").getJSONObject(0).getInt("number") == 0, "Specials lost");
            JSONObject result = client.episodes(object("id", "42", "season", 0));
            require(result.getInt("season") == 0 && result.getJSONArray("episodes").getJSONObject(0).getString("title").equals("Pilot"), "Episode projection failed");
            require(requests.get(1).equals("https://api.framextv.tech/api/season?id=42&season=0"), "Season selected wrong request");
        });
        names.add("fresh source and complete subtitle catalog retain native ownership"); checks.add(() -> {
            List<String> requests = new ArrayList<>(); final int[] generations = {0};
            CatalogClient client = new CatalogClient((url, ms) -> {
                requests.add(url);
                if (url.contains("/api/details")) return reply(object("success", true, "id", 42, "type", "tv", "title", "Series"));
                if (url.contains("/api/stream")) return reply(object("success", true, "sources", new JSONArray().put(object("url", "http://192.168.1.2/private.mp4", "type", "mp4"))
                    .put(object("url", "https://media.example/fresh-" + (++generations[0]) + ".m3u8", "type", "hls")), "subtitles", new JSONArray().put(object("url", "https://media.example/pt.srt", "language", "pt-BR", "label", "Português", "format", "srt"))));
                JSONArray tracks = new JSONArray().put(object("url", "https://media.example/pt.srt", "lang", "pt-BR", "format", "srt"));
                for (int i = 1; i < 23; i++) tracks.put(object("url", "https://media.example/sub-" + i + ".vtt", "language", "en", "format", "vtt"));
                tracks.put(object("url", "http://127.0.0.1/private", "language", "en", "format", "vtt"));
                return reply(object("success", true, "subtitles", tracks));
            });
            JSONObject selection = object("kind", "tv", "id", "42", "season", 0, "episode", 2);
            JSONObject first = client.resolve(selection), second = client.resolve(selection);
            require(!first.getString("url").equals(second.getString("url")), "Resolve cached an expiring source");
            require(first.getJSONArray("tracks").length() == 23 && !first.getBoolean("autoSubtitles") && first.getBoolean("subtitleCatalogComplete"), "Subtitle catalog or video-first choice changed");
            require(first.getJSONObject("content").getString("key").equals("framextv:tv:42:0:2") && first.getJSONObject("content").getString("watchUrl").equals("https://framextv.cc/watch/42/0/2"), "Stable episode identity wrong");
            for (String url : requests) require(url.startsWith("https://api.framextv.tech/api/"), "Media/subtitle was fetched by catalog client");
            require(requests.size() == 6, "Resolve was not a fresh bounded API operation");
        });
        names.add("active playback hydration never selects a replacement source"); checks.add(() -> {
            List<String> requests = new ArrayList<>(); CatalogClient client = new CatalogClient((url, ms) -> {
                requests.add(url);
                if (url.contains("/api/details")) return reply(object("success", true, "id", 42, "type", "tv", "title", "Shared series"));
                if (url.contains("/api/subtitles")) return reply(object("success", true, "subtitles", new JSONArray()
                    .put(object("url", "https://media.example/pt.vtt", "language", "pt-BR", "label", "Português", "format", "vtt"))));
                throw new AssertionError("Hydration attempted to resolve media");
            });
            JSONObject result = client.hydrate(object("kind", "tv", "id", "42", "season", 1, "episode", 3));
            require(result.getString("title").equals("Shared series · S1 E3"), "Hydrated title lost episode identity");
            require(result.getJSONObject("content").getString("key").equals("framextv:tv:42:1:3"), "Hydrated canonical identity changed");
            require(result.getJSONArray("tracks").length() == 1 && result.getBoolean("subtitleCatalogComplete"), "Hydrated subtitles were not retained");
            require(requests.size() == 2 && requests.stream().noneMatch(url -> url.contains("/api/stream")), "Hydration selected a replacement source");
            require(!result.has("url"), "Hydration exposed a media source");
        });
        names.add("failover is fixed and malformed source responses stay failures"); checks.add(() -> {
            List<String> requests = new ArrayList<>(); CatalogClient client = new CatalogClient((url, ms) -> { requests.add(url); return requests.size() == 1 ? new CatalogClient.Response(503, "") : reply(object("success", true, "results", new JSONArray())); });
            client.list(object("kind", "movie")); require(requests.size() == 2 && requests.get(1).startsWith("https://api.framextv.xyz/api/"), "Unbounded provider failover");
            CatalogClient redirect = new CatalogClient((url, ms) -> new CatalogClient.Response(302, "")); rejects("CATALOG_UNAVAILABLE", redirect, "list", object("kind", "movie"));
            CatalogClient malformed = new CatalogClient((url, ms) -> new CatalogClient.Response(200, "{\"success\":true,\"results\":[]} trailing")); rejects("CATALOG_RESPONSE", malformed, "list", object("kind", "movie"));
            CatalogClient source = new CatalogClient((url, ms) -> {
                if (url.contains("/api/details")) return reply(object("success", true, "id", 1, "type", "movie", "title", "Film"));
                return reply(object("success", true, "sources", new JSONArray().put(object("url", "https://media.example/a.mp4", "type", "mp4", "headers", object("Referer", "https://example.com/")))));
            });
            rejects("SOURCE_UNSUPPORTED", source, "resolve", object("kind", "movie", "id", "1"));
            CatalogClient unavailable = new CatalogClient((url, ms) -> reply(url.contains("/api/details")
                ? object("success", true, "id", 1, "type", "movie", "title", "Film")
                : object("success", false, "sources", new JSONArray())));
            rejects("SOURCE_UNAVAILABLE", unavailable, "resolve", object("kind", "movie", "id", "1"));
            CatalogClient relative = new CatalogClient((url, ms) -> {
                if (url.contains("/api/details")) return reply(object("success", true, "id", 1, "type", "movie", "title", "Film"));
                if (url.contains("/api/subtitles")) return reply(object("success", true, "subtitles", new JSONArray()));
                if (url.startsWith("https://api.framextv.tech/")) return new CatalogClient.Response(503, "");
                return reply(object("success", true, "sources", new JSONArray().put(object("url", "/api/proxy?fixture=1", "type", "mp4"))));
            });
            JSONObject resolved = relative.resolve(object("kind", "movie", "id", "1"));
            require(resolved.getString("url").equals("https://api.framextv.xyz/api/proxy?fixture=1"), "Relative source rebound to wrong failover origin");
            require(resolved.getJSONObject("content").isNull("season") && resolved.getJSONObject("content").getString("key").equals("framextv:movie:1"), "Movie history contract differs");
        });
        int passed = 0, failed = 0; String failure = "";
        for (int i = 0; i < checks.size(); i++) {
            Bundle progress = new Bundle(); progress.putString("class", CatalogInstrumentation.class.getName()); progress.putString("test", names.get(i)); progress.putInt("current", i + 1); instrumentation.sendStatus(1, progress);
            try { checks.get(i).run(); passed++; instrumentation.sendStatus(0, progress); }
            catch (Throwable error) { failed++; failure = names.get(i) + ": " + error.getClass().getSimpleName(); progress.putString("stack", failure); instrumentation.sendStatus(-2, progress); }
        }
        Bundle result = new Bundle(); result.putInt("tests", checks.size()); result.putInt("passed", passed); result.putInt("failures", failed); result.putString("failure", failure);
        instrumentation.finish(failed == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }
}

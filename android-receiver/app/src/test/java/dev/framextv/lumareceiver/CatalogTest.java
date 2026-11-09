package dev.framextv.lumareceiver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONObject;
import org.junit.Test;

public class CatalogTest {
    private Catalog fixture(java.util.List<String> paths) {
        return new Catalog(url -> {
            paths.add(url);
            if (url.contains("/api/details")) return new JSONObject().put("title", "Fixture");
            if (url.contains("/api/stream")) return new JSONObject().put("success", true).put("sources",
                new org.json.JSONArray().put(new JSONObject().put("url", "https://media.example/fixture.mp4").put("type", "video/mp4")));
            return new JSONObject().put("success", true).put("subtitles", new org.json.JSONArray());
        });
    }
    @Test public void movieHistoryResolvesCanonicalMovieWithoutEpisodeParameters() throws Exception {
        java.util.List<String> paths = new java.util.ArrayList<>();
        JSONObject media = fixture(paths).resolveContent(new JSONObject().put("kind", "movie").put("contentId", "42"));
        MediaRequest parsed = MediaRequest.parse(media);
        assertEquals("framextv:movie:42", parsed.content.getString("key"));
        org.junit.Assert.assertTrue(paths.stream().anyMatch(url -> url.contains("/api/stream?type=movie&id=42")));
        org.junit.Assert.assertFalse(paths.stream().anyMatch(url -> url.contains("season=")));
    }
    @Test public void episodeHistoryPreservesCanonicalSeasonAndEpisode() throws Exception {
        java.util.List<String> paths = new java.util.ArrayList<>();
        JSONObject media = fixture(paths).resolveContent(new JSONObject().put("kind", "tv").put("contentId", "42").put("season", 2).put("episode", 3));
        assertEquals("framextv:tv:42:2:3", MediaRequest.parse(media).content.getString("key"));
        org.junit.Assert.assertTrue(paths.stream().anyMatch(url -> url.contains("/api/stream?type=tv&id=42&season=2&episode=3")));
    }

    @Test public void unreachableSourcesAndExcludedUrlsFallThroughToAvailableProvider() throws Exception {
        java.util.List<String> checked=new java.util.ArrayList<>();
        Catalog catalog=new Catalog(url->{
            if(url.contains("/api/details"))return new JSONObject().put("title","Fixture");
            if(url.contains("/api/providers"))return new JSONObject().put("providers",new org.json.JSONArray()
                .put(new JSONObject().put("id","alternate").put("status","available")));
            if(url.contains("/api/stream"))return new JSONObject().put("success",true).put("provider","failed")
                .put("sources",new org.json.JSONArray().put(new JSONObject().put("url",url.contains("provider=alternate")
                    ?"https://media.example/good.mp4":"https://media.example/bad.mp4").put("type","mp4")));
            return new JSONObject().put("success",true);
        },(url,type,deadline)->{checked.add(url);return url.endsWith("good.mp4");},java.util.Collections.singleton("https://media.example/bad.mp4"));
        JSONObject media=catalog.resolveContent(new JSONObject().put("kind","movie").put("contentId","42"));
        assertEquals("https://media.example/good.mp4",media.getString("url"));
        assertEquals(java.util.Collections.singletonList("https://media.example/good.mp4"),checked);
    }

    @Test public void animeNextUsesCanonicalMappingEditionAndReleaseDate() throws Exception {
        JSONObject mapping = new JSONObject("{\"mappings\":{\"themoviedb_id\":\"321518\"},\"episodes\":{"
            + "\"2\":{\"seasonNumber\":1,\"episodeNumber\":2,\"airDate\":\"2026-10-14\"},"
            + "\"3\":{\"seasonNumber\":1,\"episodeNumber\":3,\"airDate\":\"2026-10-21\"},"
            + "\"4\":{\"seasonNumber\":1,\"episodeNumber\":4,\"airDate\":\"2026-10-28\"}}}");
        Catalog.Episode next = Catalog.nextAnime(mapping, "321518", 1, 2, "211778", "dub", "2026-10-23");
        assertEquals(3, next.animeEpisode); assertEquals(3, next.episode);
        assertEquals("211778", next.animeId); assertEquals("dub", next.animeAudio);
        assertNull(Catalog.nextAnime(mapping, "321518", 1, 3, "211778", "sub", "2026-10-23"));
    }
}

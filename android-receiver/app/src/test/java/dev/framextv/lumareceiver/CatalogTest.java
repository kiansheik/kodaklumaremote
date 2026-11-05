package dev.framextv.lumareceiver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONObject;
import org.junit.Test;

public class CatalogTest {
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

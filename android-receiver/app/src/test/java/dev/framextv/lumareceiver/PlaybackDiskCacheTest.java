package dev.framextv.lumareceiver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class PlaybackDiskCacheTest {
    @Test public void budgetPreservesFreeSpaceAndHasAHardCeiling() {
        assertEquals(0, PlaybackDiskCache.cacheBudgetBytes(0));
        assertEquals(0, PlaybackDiskCache.cacheBudgetBytes(600L * PlaybackDiskCache.MIB));
        assertEquals(1536L * PlaybackDiskCache.MIB,
            PlaybackDiskCache.cacheBudgetBytes(2L * PlaybackDiskCache.GIB));
        assertEquals(PlaybackDiskCache.MAX_CACHE_BYTES,
            PlaybackDiskCache.cacheBudgetBytes(20L * PlaybackDiskCache.GIB));
    }

    @Test public void onlyOpaqueLocalProgressiveMediaIsPrefetched() throws Exception {
        String id = "local:0123456789abcdef0123456789abcdef";
        assertTrue(PlaybackDiskCache.supportsPrefetch(MediaRequest.parse(new JSONObject(
            "{\"url\":\"http://192.168.1.5/movie.mkv\",\"mediaId\":\"" + id + "\"}"))));
        assertFalse(PlaybackDiskCache.supportsPrefetch(MediaRequest.parse(new JSONObject(
            "{\"url\":\"http://192.168.1.5/stream.m3u8\",\"mediaId\":\"" + id + "\"}"))));
        assertFalse(PlaybackDiskCache.supportsPrefetch(MediaRequest.parse(new JSONObject(
            "{\"url\":\"https://cdn.example/movie.mp4\"}"))));
    }

    @Test public void cacheKeysAreOpaqueAndSessionScoped() {
        String key = PlaybackDiskCache.resourceKey("local:0123456789abcdef0123456789abcdef", 42, "video");
        assertEquals("local:0123456789abcdef0123456789abcdef:2a:video", key);
        assertFalse(key.contains("http"));
    }

    @Test public void rollingWindowRetainsSomePastAndClampsAtTheEnd() {
        long content = 1000L * PlaybackDiskCache.MIB, window = 200L * PlaybackDiskCache.MIB;
        assertEquals(0, PlaybackDiskCache.rollingWindowStart(content, 0, 1000, window));
        assertEquals(475L * PlaybackDiskCache.MIB,
            PlaybackDiskCache.rollingWindowStart(content, 500, 1000, window));
        assertEquals(800L * PlaybackDiskCache.MIB,
            PlaybackDiskCache.rollingWindowStart(content, 1000, 1000, window));
    }

    @Test public void cachedAheadUsesLoadedContiguousBytesNotThePlannedWindow() {
        long content = 1000L * PlaybackDiskCache.MIB;
        assertEquals(30d, PlaybackDiskCache.cachedAheadSeconds(
            content, 1_000_000, 500_000, 30L * PlaybackDiskCache.MIB), 0.001d);
        assertEquals(0d, PlaybackDiskCache.cachedAheadSeconds(content, 1_000_000, 500_000, 0), 0d);
        assertEquals(10d, PlaybackDiskCache.cachedAheadSeconds(
            content, 1_000_000, 990_000, 200L * PlaybackDiskCache.MIB), 0.001d);
    }
}

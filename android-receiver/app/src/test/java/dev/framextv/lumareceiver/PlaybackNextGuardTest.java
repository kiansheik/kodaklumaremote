package dev.framextv.lumareceiver;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public final class PlaybackNextGuardTest {
    private static MediaRequest episode(int episode) throws Exception {
        return MediaRequest.parse(new JSONObject("{\"url\":\"https://media.example/shared.m3u8\","
            + "\"content\":{\"kind\":\"tv\",\"contentId\":\"42\",\"season\":2,\"episode\":" + episode + "}}"));
    }

    @Test public void staleNextCannotReplaceAReopenedOrDifferentEpisode() throws Exception {
        MediaRequest current = episode(3);
        assertTrue(PlaybackController.nextRequestMatches(7, current.url,
            "framextv:tv:42:2:3", 7, current));
        // Reopening the same URL/item advances the source generation, so an old
        // asynchronous lookup is still stale.
        assertFalse(PlaybackController.nextRequestMatches(7, current.url,
            "framextv:tv:42:2:3", 8, current));
        // Providers may reuse one stream URL across episodes; identity must also match.
        assertFalse(PlaybackController.nextRequestMatches(7, current.url,
            "framextv:tv:42:2:3", 7, episode(4)));
    }
}

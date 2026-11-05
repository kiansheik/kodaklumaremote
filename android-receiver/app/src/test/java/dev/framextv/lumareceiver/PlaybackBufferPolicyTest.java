package dev.framextv.lumareceiver;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PlaybackBufferPolicyTest {
    @Test public void startsFastButKeepsAUsefulHighBitrateTimeBuffer() {
        assertEquals(750, PlaybackController.PLAYBACK_BUFFER_MS);
        assertEquals(2_000, PlaybackController.REBUFFER_MS);
        assertEquals(8_000, PlaybackController.MIN_BUFFER_MS);
        assertEquals(20_000, PlaybackController.MAX_BUFFER_MS);
        assertTrue(PlaybackController.PRIORITIZE_BUFFER_TIME);
        assertTrue(PlaybackController.PLAYBACK_BUFFER_MS < PlaybackController.MIN_BUFFER_MS);
        assertTrue(PlaybackController.REBUFFER_MS < PlaybackController.MIN_BUFFER_MS);
        assertTrue(PlaybackController.MIN_BUFFER_MS < PlaybackController.MAX_BUFFER_MS);
    }
}

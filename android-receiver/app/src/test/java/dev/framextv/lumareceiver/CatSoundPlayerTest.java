package dev.framextv.lumareceiver;

import org.junit.Test;

import static org.junit.Assert.*;

public final class CatSoundPlayerTest {
    @Test public void recurringSoundsAreShortBoundedDeterministicAndFaded() {
        assertEquals(48_000, CatSoundPlayer.SAMPLE_RATE);
        assertEquals(0.85f, CatSoundPlayer.TRACK_GAIN, 0);
        for (int kind = 0; kind < 3; kind++) {
            short[] first = CatSoundPlayer.synthesize(kind, CatSoundPlayer.SAMPLE_RATE);
            short[] second = CatSoundPlayer.synthesize(kind, CatSoundPlayer.SAMPLE_RATE);
            assertArrayEquals(first, second);
            assertTrue(first.length >= CatSoundPlayer.SAMPLE_RATE * 0.12);
            assertTrue(first.length <= CatSoundPlayer.SAMPLE_RATE * 0.24);
            int peak = 0, nonzero = 0;
            for (short sample : first) {
                peak = Math.max(peak, Math.abs((int) sample));
                if (sample != 0) nonzero++;
            }
            assertTrue(nonzero > first.length / 2);
            assertTrue(peak <= Math.ceil(Short.MAX_VALUE * 0.24));
            assertEquals(0, first[0]);
            assertEquals(0, first[first.length - 1]);
        }
    }

    @Test public void attentionCallIsImmediateLengthBoundedAndHasTwoChirps() {
        short[] first = CatSoundPlayer.synthesizeAttention(CatSoundPlayer.SAMPLE_RATE);
        assertArrayEquals(first, CatSoundPlayer.synthesizeAttention(CatSoundPlayer.SAMPLE_RATE));
        assertEquals(Math.round(CatSoundPlayer.SAMPLE_RATE * 0.46f), first.length);
        int peak = 0, firstChirp = 0, gap = 0, secondChirp = 0;
        for (int i = 0; i < first.length; i++) {
            int level = Math.abs((int) first[i]);
            peak = Math.max(peak, level);
            double seconds = i / (double) CatSoundPlayer.SAMPLE_RATE;
            if (seconds < 0.19) firstChirp = Math.max(firstChirp, level);
            else if (seconds < 0.25) gap = Math.max(gap, level);
            else secondChirp = Math.max(secondChirp, level);
        }
        assertTrue(firstChirp > Short.MAX_VALUE * 0.18);
        assertEquals(0, gap);
        assertTrue(secondChirp > Short.MAX_VALUE * 0.18);
        assertTrue(peak <= Math.ceil(Short.MAX_VALUE * 0.26));
        assertEquals(0, first[0]);
        assertEquals(0, first[first.length - 1]);
    }

    @Test public void diagnosticsAreBoundedAndDoNotExposeAudioData() throws Exception {
        org.json.JSONObject status = CatSoundPlayer.diagnostics();
        assertEquals(4, status.length());
        assertTrue(status.getString("state").matches("not-run|initializing|pcm-stream|system-tone-fallback|failed"));
        assertEquals(48_000, status.getInt("sampleRate"));
        assertTrue(status.getInt("writtenSamples") >= 0);
        assertTrue(status.getInt("playedFrames") >= 0);
    }
}

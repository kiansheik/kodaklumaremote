package dev.framextv.lumareceiver;

import org.junit.Test;
import static org.junit.Assert.*;

public class NintendoPlayerLightsTest {
    @Test public void steadyPlayerPatternsKeepNeutralRumbleAndDoNotChangeReportMode() {
        assertEquals("010000014040000140403001", NintendoPlayerLights.reportHex(1, 0));
        assertEquals("010100014040000140403003", NintendoPlayerLights.reportHex(2, 1));
        assertEquals("010200014040000140403007", NintendoPlayerLights.reportHex(3, 2));
        assertEquals("010F0001404000014040300F", NintendoPlayerLights.reportHex(4, 15));
    }
    @Test public void invalidPlayerOrCounterIsRejected() {
        for (int player : new int[]{-1, 0, 5, 16}) {
            try { NintendoPlayerLights.reportHex(player, 0); fail(); } catch (IllegalArgumentException expected) { }
        }
        for (int counter : new int[]{-1, 16}) {
            try { NintendoPlayerLights.reportHex(1, counter); fail(); } catch (IllegalArgumentException expected) { }
        }
    }
}

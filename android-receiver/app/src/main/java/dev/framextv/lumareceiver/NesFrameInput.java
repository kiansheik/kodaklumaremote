package dev.framextv.lumareceiver;

import java.util.Arrays;

/** Makes overlapping physical presses visible to the NES at frame boundaries. */
final class NesFrameInput {
    private static final int MAX_PENDING = 8;
    private final int[] pending = new int[8];
    private int held, lastFrame;
    private long retriggers;

    synchronized void update(int nextHeld, int freshPresses) {
        nextHeld &= 255;
        int repeats = freshPresses & nextHeld & (held | lastFrame);
        for (int i = 0; i < pending.length; i++) {
            int bit = 1 << i;
            if ((nextHeld & bit) == 0) pending[i] = 0;
            else if ((repeats & bit) != 0 && pending[i] < MAX_PENDING) pending[i]++;
        }
        held = nextHeld;
    }

    synchronized int nextFrame() {
        int result = held;
        for (int i = 0; i < pending.length; i++) {
            int bit = 1 << i;
            // A released frame must be followed by a pressed frame before
            // another queued edge, even when presses arrive faster than video.
            if (pending[i] > 0 && (held & lastFrame & bit) != 0) {
                result &= ~bit;
                pending[i]--;
                retriggers++;
            }
        }
        lastFrame = result;
        return result;
    }

    synchronized void cancelRetriggers() { Arrays.fill(pending, 0); }
    synchronized long retriggerCount() { return retriggers; }
    synchronized void resetStatistics() { retriggers = 0; }

    synchronized void clear() {
        held = lastFrame = 0;
        Arrays.fill(pending, 0);
    }
}

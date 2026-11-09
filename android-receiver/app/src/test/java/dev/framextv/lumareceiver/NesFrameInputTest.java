package dev.framextv.lumareceiver;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class NesFrameInputTest {
    private static final int A = 1, B = 2, RIGHT = 128;

    @Test public void overlappingPressReleasesForOneFrameAndKeepsEitherHold() {
        NesFrameInput input = holdingB();
        input.update(B, B); // A trigger joins the held face button.
        frames(input, 0, B, B);
        input.update(B, 0); // Either physical binding releases.
        frames(input, B, B);
        input.update(0, 0); // The final binding releases.
        frames(input, 0, 0);
    }

    @Test public void triggerTapBetweenFramesStillRetriggersTheRemainingHold() {
        NesFrameInput input = holdingB();
        input.update(B, B);
        input.update(B, 0);
        frames(input, 0, B, B);
    }

    @Test public void queuedPressesHaveAPressedFrameBetweenReleases() {
        NesFrameInput input = holdingB();
        input.update(B, B);
        input.update(B, 0);
        input.update(B, B);
        frames(input, 0, B, 0, B, B);
    }

    @Test public void overlappingPressBeforeFirstFramePreservesInitialPress() {
        NesFrameInput input = new NesFrameInput();
        input.update(B, B);
        input.update(B, B);
        frames(input, B, 0, B, B);
    }

    @Test public void releaseAndRepressBetweenFramesIsVisible() {
        NesFrameInput input = holdingB();
        input.update(0, 0);
        input.update(B, B);
        frames(input, 0, B, B);
    }

    @Test public void finalReleaseCancelsQueuedRepresses() {
        NesFrameInput input = holdingB();
        input.update(B, B);
        input.update(B, B);
        input.update(0, 0);
        frames(input, 0, 0, 0);
        input.update(B, B);
        frames(input, B, B);
    }

    @Test public void finalReleaseDuringPulseDoesNotRestoreAReleasedButton() {
        NesFrameInput input = holdingB();
        input.update(B, B);
        frames(input, 0);
        input.update(0, 0);
        frames(input, 0, 0);
    }

    @Test public void retriggerLeavesOtherButtonsHeldAndExcludesAppActions() {
        NesFrameInput input = new NesFrameInput();
        input.update(A | B | RIGHT | 1024, A | B | RIGHT | 1024);
        frames(input, A | B | RIGHT);
        input.update(A | B | RIGHT | 512, B | 512);
        frames(input, A | RIGHT, A | B | RIGHT, A | B | RIGHT);
        input.update(A | B | RIGHT, A);
        frames(input, B | RIGHT, A | B | RIGHT);
    }

    @Test public void clearDropsHoldsAndQueuedEdgesForPauseOrNewGame() {
        NesFrameInput input = holdingB();
        input.update(B, B);
        input.clear();
        frames(input, 0, 0);
        input.update(B, B);
        frames(input, B, B);
    }

    @Test public void disconnectCanCancelPendingEdgesWithoutReleasingAnotherHold() {
        NesFrameInput input = holdingB();
        input.update(B, B);
        input.cancelRetriggers();
        frames(input, B, B);
        input.update(B, B);
        frames(input, 0);
        input.cancelRetriggers();
        frames(input, B, B);
    }

    @Test public void heldUpdatesNeverBecomeAutomaticRepeat() {
        NesFrameInput input = holdingB();
        for (int i = 0; i < 120; i++) {
            input.update(B, 0);
            assertEquals(B, input.nextFrame());
        }
    }

    @Test public void queuedEdgesAreBounded() {
        NesFrameInput input = holdingB();
        for (int i = 0; i < 100; i++) input.update(B, B);
        for (int i = 0; i < 8; i++) frames(input, 0, B);
        frames(input, B, B);
    }

    @Test public void diagnosticsCountOnlyFrameConsumedEdgesAndSurvivePauseClear() {
        NesFrameInput input = holdingB();
        input.update(B, B);
        assertEquals(0, input.retriggerCount());
        frames(input, 0);
        assertEquals(1, input.retriggerCount());
        frames(input, B, B);
        input.update(B, B);
        input.clear();
        frames(input, 0);
        assertEquals(1, input.retriggerCount());
        input.resetStatistics();
        assertEquals(0, input.retriggerCount());
    }

    private static NesFrameInput holdingB() {
        NesFrameInput input = new NesFrameInput();
        input.update(B, B);
        frames(input, B);
        return input;
    }

    private static void frames(NesFrameInput input, int... expected) {
        for (int value : expected) assertEquals(value, input.nextFrame());
    }
}

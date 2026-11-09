package dev.framextv.lumareceiver;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

/** Capture-session regressions independent of the Activity and Android event plumbing. */
public class ControllerCalibrationTest {
    private static final int SELECTED = 8, OTHER = 9;
    private static ControllerCalibration session() {
        Map<Integer, Float> neutral = new HashMap<>();
        Map<Integer, ControllerCalibration.Range> ranges = new HashMap<>();
        for (int axis : new int[]{0, 1, 11, 14, 15, 16, 17, 18}) {
            neutral.put(axis, 0f); ranges.put(axis, new ControllerCalibration.Range(-1, 1));
        }
        return new ControllerCalibration(SELECTED, ControllerProfile.defaults(), neutral, ranges);
    }
    private static ControllerCalibration buttons() {
        ControllerCalibration session = session();
        for (int i = 0; i < 4; i++) session.skip();
        assertEquals(ControllerProfile.Control.A, session.current().button);
        return session;
    }
    private static void motion(ControllerCalibration session, int device, long time, int axis, float value) {
        Map<Integer, Float> values = new HashMap<>(); values.put(axis, value);
        session.onMotion(device, values, time);
    }
    @Test public void stableStickRequiresNeutralReleaseBeforeAdvancing() {
        ControllerCalibration session = session();
        motion(session, SELECTED, 1000, 0, 1); session.tick(1109);
        assertEquals(0, session.mappedCount);
        session.tick(1110); assertEquals(1, session.mappedCount);
        assertTrue(session.waitingForRelease); assertEquals(0, session.index);
        motion(session, SELECTED, 1300, 0, 1); session.tick(2000);
        assertEquals(1, session.mappedCount); assertEquals(0, session.index);
        motion(session, OTHER, 2100, 0, 0); assertTrue(session.waitingForRelease);
        motion(session, SELECTED, 2200, 0, .3f); assertTrue(session.waitingForRelease);
        motion(session, SELECTED, 2300, 0, 0);
        assertFalse(session.waitingForRelease); assertEquals(1, session.index);
    }
    @Test public void anotherControllerCannotCaptureOrReleaseSelectedController() {
        ControllerCalibration session = buttons();
        session.onKey(OTHER, 96, true, 0); assertEquals(0, session.mappedCount);
        motion(session, OTHER, 0, 17, 1); session.tick(1000); assertEquals(0, session.mappedCount);
        session.onKey(SELECTED, 96, true, 0); assertEquals(1, session.mappedCount);
        session.onKey(OTHER, 96, false, 0); assertTrue(session.waitingForRelease);
        session.onKey(SELECTED, 96, false, 0); assertEquals(5, session.index);
    }
    @Test public void repeatAndHeldButtonCannotFillFollowingStep() {
        ControllerCalibration session = buttons();
        session.onKey(SELECTED, 96, true, 1); assertEquals(0, session.mappedCount);
        session.onKey(SELECTED, 96, false, 0);
        session.onKey(SELECTED, 96, true, 0); assertEquals(1, session.mappedCount);
        session.onKey(SELECTED, 96, true, 4); assertEquals(4, session.index);
        session.onKey(SELECTED, 97, true, 0); assertEquals(1, session.mappedCount);
        session.onKey(SELECTED, 96, false, 0); assertEquals(4, session.index);
        session.onKey(SELECTED, 97, false, 0); assertEquals(5, session.index);
    }
    @Test public void duplicateButtonAndStickKeepPriorAssignmentAndRetryCurrentStep() {
        ControllerCalibration session = buttons();
        session.onKey(SELECTED, 96, true, 0); session.onKey(SELECTED, 96, false, 0);
        session.onKey(SELECTED, 96, true, 0);
        assertTrue(session.retryAfterRelease); assertEquals(1, session.mappedCount);
        assertEquals(96, session.draft.buttons.get(ControllerProfile.Control.A).get(0).keyCode);
        assertTrue(session.draft.buttons.get(ControllerProfile.Control.B).isEmpty());
        session.onKey(SELECTED, 96, false, 0); assertEquals(5, session.index);
        session.onKey(SELECTED, 97, true, 0); assertEquals(2, session.mappedCount);

        session = session(); motion(session, SELECTED, 0, 0, 1); session.tick(120);
        motion(session, SELECTED, 200, 0, 0);
        motion(session, SELECTED, 300, 0, -1); session.tick(420);
        assertTrue(session.retryAfterRelease); assertEquals(1, session.draft.sticks.size());
        assertEquals(0, session.draft.sticks.get(ControllerProfile.StickAxis.LEFT_X).axis);
    }
    @Test public void diagonalAndBriefAxisExcursionsDoNotChooseAnArbitraryAxis() {
        ControllerCalibration session = session();
        Map<Integer, Float> diagonal = new HashMap<>(); diagonal.put(0, 1f); diagonal.put(1, .95f);
        session.onMotion(SELECTED, diagonal, 100); session.tick(500);
        assertEquals(0, session.mappedCount);
        diagonal.put(1, 0f); session.onMotion(SELECTED, diagonal, 600);
        motion(session, SELECTED, 650, 0, 0); session.tick(1000);
        assertEquals(0, session.mappedCount);
    }
    @Test public void skippedHomeAndCaptureNeedNoInventedHardwareBinding() {
        ControllerCalibration session = session();
        for (int i = 0; i < 20; i++) session.skip();
        assertEquals(ControllerProfile.Control.HOME, session.current().button);
        assertTrue(session.current().optional()); session.skip();
        assertEquals(ControllerProfile.Control.CAPTURE, session.current().button);
        assertTrue(session.current().optional()); session.skip();
        assertTrue(session.complete()); assertEquals(0, session.mappedCount);
        assertTrue(session.draft.buttons.get(ControllerProfile.Control.HOME).isEmpty());
        assertTrue(session.draft.buttons.get(ControllerProfile.Control.CAPTURE).isEmpty());
    }
    @Test public void draftingAndDiscardingSessionNeverMutatesExistingProfile() throws Exception {
        ControllerProfile existing = ControllerProfile.defaults();
        existing.bind(ControllerProfile.Control.A, ControllerProfile.Binding.key(401));
        existing.nes.put(ControllerProfile.Control.A, ControllerProfile.B);
        String saved = existing.toJson().toString();
        ControllerCalibration session = new ControllerCalibration(SELECTED, existing, new HashMap<>(), new HashMap<>());
        for (int i = 0; i < 4; i++) session.skip();
        session.onKey(SELECTED, 402, true, 0);
        assertEquals(saved, existing.toJson().toString());
        assertEquals(Integer.valueOf(ControllerProfile.B), session.draft.nes.get(ControllerProfile.Control.A));
    }
    @Test public void unknownKeyCannotCrashOrBecomeSavedBinding() {
        ControllerCalibration session = buttons();
        session.onKey(SELECTED, 0, true, 0);
        assertEquals(0, session.mappedCount); assertTrue(session.retryAfterRelease);
        assertTrue(session.draft.buttons.get(ControllerProfile.Control.A).isEmpty());
        session.onKey(SELECTED, 0, false, 0);
        assertEquals(4, session.index); assertFalse(session.waitingForRelease);
        session.onKey(SELECTED, 96, true, 0); assertEquals(1, session.mappedCount);
    }
    @Test public void unsupportedSmallAxisRangeCanBeReleasedAndSkipped() {
        Map<Integer, Float> neutral = new HashMap<>(); neutral.put(0, 0f);
        Map<Integer, ControllerCalibration.Range> ranges = new HashMap<>();
        ranges.put(0, new ControllerCalibration.Range(-.1f, .1f));
        ControllerCalibration session = new ControllerCalibration(SELECTED, ControllerProfile.defaults(), neutral, ranges);
        motion(session, SELECTED, 0, 0, .1f); session.tick(200);
        assertEquals(0, session.mappedCount); assertTrue(session.retryAfterRelease);
        assertTrue(session.draft.sticks.isEmpty());
        motion(session, SELECTED, 300, 0, 0); session.skip();
        assertEquals(1, session.index);
    }
    @Test public void focusLossNeedsFreshNeutralAxesAndKeepsCapturedStep() {
        ControllerCalibration session = session();
        motion(session, SELECTED, 0, 0, 1); session.tick(120);
        session.clearHeld();
        assertFalse(session.retryAfterRelease); // A valid capture still awaits release.
        session.onKey(SELECTED, 96, false, 0); assertTrue(session.waitingForRelease);
        session.onMotion(OTHER, session.neutral, 200); assertTrue(session.waitingForRelease);
        motion(session, SELECTED, 300, 0, 0); assertTrue(session.waitingForRelease);
        Map<Integer, Float> samples = new HashMap<>(session.neutral); samples.put(1, Float.NaN);
        session.onMotion(SELECTED, samples, 400); assertTrue(session.waitingForRelease);
        session.onMotion(SELECTED, session.neutral, 500);
        assertFalse(session.waitingForRelease); assertEquals(1, session.index);
        assertEquals(1, session.mappedCount);
    }
    @Test public void skipAfterFocusLossCannotBypassFreshReleaseEvidence() {
        ControllerCalibration session = session(); session.clearHeld(); session.skip();
        assertTrue(session.waitingForRelease); assertEquals(0, session.index);
        Map<Integer, Float> samples = new HashMap<>(session.neutral); samples.put(0, 1f);
        session.onMotion(SELECTED, samples, 200);
        assertTrue(session.waitingForRelease); assertEquals(0, session.index);
        session.onMotion(SELECTED, session.neutral, 300);
        assertEquals(1, session.index); assertEquals(0, session.mappedCount);
    }
    @Test public void focusLossDiscardsUnfinishedAxisCandidate() {
        ControllerCalibration session = session();
        motion(session, SELECTED, 0, 0, 1); session.clearHeld(); session.tick(500);
        assertEquals(0, session.mappedCount);
        session.onMotion(SELECTED, session.neutral, 600); session.tick(800);
        assertEquals(0, session.mappedCount); assertEquals(0, session.index);
        motion(session, SELECTED, 900, 0, 1); session.tick(1020);
        assertEquals(1, session.mappedCount);
    }
}

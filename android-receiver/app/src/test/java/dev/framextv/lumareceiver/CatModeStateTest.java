package dev.framextv.lumareceiver;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public final class CatModeStateTest {
    @Test public void sessionHasTwelveMinuteCapAndBoundedStatusSummary() throws Exception {
        CatModeState state = new CatModeState();
        state.start(false, 1_000);

        CatModeState.Snapshot started = state.snapshot(1_000);
        assertTrue(started.active);
        assertFalse(started.manual);
        assertFalse(started.sound);
        assertEquals(12 * 60 * 1000L, started.remainingMs);

        JSONObject summary = state.summary(1_001);
        assertEquals(5, summary.length());
        assertEquals(5, state.json(1_001).length());
        assertTrue(summary.getBoolean("active"));
        assertEquals(720, summary.getLong("remainingSeconds"));

        assertFalse(state.snapshot(1_000 + CatModeState.SESSION_MS).active);
        assertFalse(state.pointer(0.2, 0.3, 1_000 + CatModeState.SESSION_MS));
    }

    @Test public void pointerAndPounceRenewLeaseAndReleaseResumesAuto() {
        CatModeState state = new CatModeState();
        state.start(true, 10);
        assertTrue(state.pointer(0.25, 0.75, 100));
        assertTrue(state.snapshot(100 + CatModeState.MANUAL_LEASE_MS).manual);
        assertFalse(state.snapshot(101 + CatModeState.MANUAL_LEASE_MS).manual);

        assertTrue(state.pounce(2_000));
        CatModeState.Snapshot pounced = state.snapshot(2_001);
        assertTrue(pounced.manual);
        assertEquals(1, pounced.pounceId);
        assertEquals(0.25, pounced.x, 0.000001);
        assertEquals(0.75, pounced.y, 0.000001);

        assertTrue(state.release(2_002));
        assertFalse(state.snapshot(2_002).manual);
    }

    @Test public void stateDefensivelyClampsPointerAndStopIsIdempotent() {
        CatModeState state = new CatModeState();
        state.start(true, 0);
        assertTrue(state.pointer(-4, 8, 1));
        CatModeState.Snapshot value = state.snapshot(1);
        assertEquals(0, value.x, 0);
        assertEquals(1, value.y, 0);
        assertTrue(state.stop());
        assertFalse(state.stop());
        assertFalse(state.release(2));
        assertFalse(state.pounce(2));
    }
}

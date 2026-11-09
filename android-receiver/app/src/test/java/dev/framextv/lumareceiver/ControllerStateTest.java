package dev.framextv.lumareceiver;

import android.view.KeyEvent;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ControllerStateTest {
    @Test public void splitControllersAndDuplicateButtonsKeepIndependentOwnership() {
        ControllerState state = new ControllerState();
        assertEquals(ControllerState.A, state.key(1, 96, ControllerState.A, true));
        assertEquals(ControllerState.A | ControllerState.UP, state.key(2, 19, ControllerState.UP, true));
        state.key(2, 96, ControllerState.A, true);
        assertEquals(ControllerState.A | ControllerState.UP, state.key(1, 96, ControllerState.A, false));
        assertEquals(ControllerState.A | ControllerState.UP, state.remove(1));
        assertEquals(0, state.remove(2));
    }

    @Test public void twoFaceButtonsMappedToSameActionDoNotReleaseEachOther() {
        ControllerState state = new ControllerState();
        state.key(1, 96, ControllerState.A, true);
        state.key(1, 100, ControllerState.A, true);
        assertEquals(ControllerState.A, state.key(1, 96, ControllerState.A, false));
        assertEquals(0, state.key(1, 100, ControllerState.A, false));
    }

    @Test public void releasingDpadKeyPreservesHeldHatAndViceVersa() {
        ControllerState state = new ControllerState();
        state.axes(1, 0, 0, -1, 0, 0, 0);
        state.key(1, 21, ControllerState.LEFT, true);
        assertEquals(ControllerState.LEFT, state.key(1, 21, ControllerState.LEFT, false));
        state.key(1, 21, ControllerState.LEFT, true);
        assertEquals(ControllerState.LEFT, state.axes(1, 0, 0, 0, 0, 0, 0));
        assertEquals(0, state.key(1, 21, ControllerState.LEFT, false));
    }

    @Test public void stickHysteresisPreventsJitterButNeutralAlwaysReleases() {
        ControllerState state = new ControllerState();
        assertEquals(0, state.axes(1, 0.49f, -0.49f, 0, 0, 0, 0));
        assertEquals(ControllerState.RIGHT | ControllerState.UP, state.axes(1, 0.6f, -0.6f, 0, 0, 0, 0));
        assertEquals(ControllerState.RIGHT | ControllerState.UP, state.axes(1, 0.4f, -0.4f, 0, 0, 0, 0));
        assertEquals(0, state.axes(1, 0.2f, -0.2f, 0, 0, 0, 0));
        assertEquals(0, state.axes(1, Float.NaN, Float.POSITIVE_INFINITY, 0, 0, 0, 0));
    }

    @Test public void opposingDirectionsCancelAndDisconnectedHalfRestoresOther() {
        ControllerState state = new ControllerState();
        state.key(1, 21, ControllerState.LEFT, true);
        assertEquals(0, state.key(2, 22, ControllerState.RIGHT, true));
        assertEquals(ControllerState.LEFT, state.remove(2));
        state.clear();
        assertEquals(0, state.buttons());
    }

    @Test public void reportedHardwareDeadZoneIsRespected() {
        assertEquals(0, ControllerState.axis(0.6f, 0, 0.7f));
        assertEquals(1, ControllerState.axis(0.8f, 0, 0.7f));
        assertEquals(1, ControllerState.axis(0.6f, 1, 0.7f));
        assertEquals(0, ControllerState.axis(0.4f, 1, 0.7f));
    }

    @Test public void navigationAndNesMappingsDoNotTurnGameplayBIntoBack() {
        assertEquals(ControllerState.A, ControllerInput.nesButton(KeyEvent.KEYCODE_BUTTON_A));
        assertEquals(ControllerState.B, ControllerInput.nesButton(KeyEvent.KEYCODE_BUTTON_B));
        assertEquals(ControllerState.START, ControllerInput.nesButton(KeyEvent.KEYCODE_BUTTON_START));
        assertEquals(ControllerState.SELECT, ControllerInput.nesButton(KeyEvent.KEYCODE_BUTTON_SELECT));
        assertEquals(0, ControllerInput.nesButton(KeyEvent.KEYCODE_BACK));
        assertEquals(KeyEvent.KEYCODE_DPAD_CENTER, ControllerInput.navigationKey(KeyEvent.KEYCODE_BUTTON_A));
        assertEquals(KeyEvent.KEYCODE_BACK, ControllerInput.navigationKey(KeyEvent.KEYCODE_BUTTON_B));
        assertEquals(KeyEvent.KEYCODE_MENU, ControllerInput.navigationKey(KeyEvent.KEYCODE_BUTTON_X));
    }
}

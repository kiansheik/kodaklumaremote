package dev.framextv.lumareceiver;

import org.junit.Test;
import static org.junit.Assert.*;

public class HomeHoldStateTest {
    @Test public void shortHomeOnlyFiresOnRelease() {
        HomeHoldState state = new HomeHoldState();
        assertTrue(state.down(8, 0, 100)); assertFalse(state.fire(3099));
        assertTrue(state.up(8, false)); assertFalse(state.fire(4000));
    }
    @Test public void longHoldFiresOnceAndSuppressesShortHome() {
        HomeHoldState state = new HomeHoldState(); state.down(8, 0, 100);
        assertTrue(state.fire(3100)); assertFalse(state.fire(9000));
        assertFalse(state.up(8, false)); assertTrue(state.down(8, 0, 10000));
        assertTrue(state.fire(13000));
    }
    @Test public void cancelledReleaseAndDisconnectNeverPowerOff() {
        HomeHoldState state = new HomeHoldState(); state.down(8, 0, 0);
        assertFalse(state.up(8, true)); assertFalse(state.fire(4000));
        state.down(8, 0, 5000); state.cancel(8); assertFalse(state.fire(9000));
    }
    @Test public void repeatCannotArmAndOtherDeviceCannotRelease() {
        HomeHoldState state = new HomeHoldState(); assertFalse(state.down(8, 1, 0));
        assertFalse(state.fire(4000)); assertTrue(state.down(8, 0, 5000));
        assertFalse(state.down(9, 0, 5100)); assertFalse(state.up(9, false));
        state.cancel(9); assertTrue(state.fire(8000));
    }
    @Test public void calibratedHomeAndCaptureRemainDistinct() {
        ControllerProfile profile = ControllerProfile.defaults();
        profile.bind(ControllerProfile.Control.HOME, ControllerProfile.Binding.key(401));
        profile.bind(ControllerProfile.Control.CAPTURE, ControllerProfile.Binding.key(402));
        assertEquals(ControllerProfile.Control.HOME, ControllerSystemService.keyControl(profile, 401));
        assertEquals(ControllerProfile.Control.CAPTURE, ControllerSystemService.keyControl(profile, 402));
        assertNull(ControllerSystemService.keyControl(profile, 110));
    }
}

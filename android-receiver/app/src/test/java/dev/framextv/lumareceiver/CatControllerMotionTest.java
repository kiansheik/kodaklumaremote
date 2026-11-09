package dev.framextv.lumareceiver;
import org.junit.Test;
import static org.junit.Assert.*;

public class CatControllerMotionTest {
    @Test public void takeoverUsesRenderedPositionAndNeutralHoldsIt() {
        CatControllerMotion motion = new CatControllerMotion();
        motion.input(.5, 0); motion.advance(100, .2, .7, 1);
        assertEquals(.2, motion.x, 0);
        motion.advance(120, .9, .9, 1);
        assertEquals(.208, motion.x, .000001);
        motion.input(0, 0); motion.advance(10000, .1, .1, 1);
        assertEquals(.208, motion.x, .000001);
        assertTrue(motion.active);
    }
    @Test public void movementScalesWithTiltAndElapsedFrames() {
        CatControllerMotion fast = new CatControllerMotion(), slow = new CatControllerMotion();
        fast.input(1, 0); slow.input(.5, 0);
        fast.advance(0, .1, .5, 1); slow.advance(0, .1, .5, 1);
        for (int t=10; t<=400; t+=10) fast.advance(t, 0, 0, 1);
        for (int t=40; t<=400; t+=40) slow.advance(t, 0, 0, 1);
        assertEquals((fast.x-.1)/2, slow.x-.1, .000001);
    }
    @Test public void stalledFramesCannotTeleportAndDiagonalSpeedIsBounded() {
        CatControllerMotion motion = new CatControllerMotion();
        motion.input(1, 1); motion.advance(0, .5, .5, 1);
        motion.advance(10000, 0, 0, 1);
        assertEquals(.04, Math.hypot(motion.x-.5, motion.y-.5), .000001);
        motion.stop(); motion.advance(20000, 0, 0, 1);
        assertEquals(.04, Math.hypot(motion.x-.5, motion.y-.5), .000001);
    }
    @Test public void phonePointerTakesOverAndKeepsItsExistingLease() {
        CatModeState state = new CatModeState(); state.start(false, 0);
        state.controllerInput(1, 0); state.controllerFrame(10, .3, .4, 1);
        state.controllerInput(0, 0); state.controllerFrame(5000, .3, .4, 1);
        assertTrue(state.snapshot(5000).controller);
        assertTrue(state.snapshot(5000).manual);
        state.pointer(.7, .8, 5010);
        assertFalse(state.snapshot(5010).controller);
        assertEquals(.7, state.snapshot(5010).x, 0);
        assertFalse(state.snapshot(7000).manual);
    }
}

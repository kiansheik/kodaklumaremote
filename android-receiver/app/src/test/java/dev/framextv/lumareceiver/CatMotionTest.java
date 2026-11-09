package dev.framextv.lumareceiver;

import org.junit.Test;

import static org.junit.Assert.*;

public final class CatMotionTest {
    @Test public void seededAutonomousMotionIsDeterministicBoundedAndVaried() {
        CatMotion first = new CatMotion(42), second = new CatMotion(42);
        boolean sawDart = false, sawQuiver = false, sawHide = false, sawRest = false;
        for (long now = 0; now <= 120_000; now += 40) {
            CatMotion.Frame a = first.update(now, false, 0, 0, 0);
            CatMotion.Frame b = second.update(now, false, 0, 0, 0);
            assertEquals(a.x, b.x, 0);
            assertEquals(a.y, b.y, 0);
            assertEquals(a.visible, b.visible);
            assertEquals(a.phase, b.phase);
            assertTrue(a.x >= 0.035 && a.x <= 0.965);
            assertTrue(a.y >= 0.035 && a.y <= 0.965);
            sawDart |= a.phase == CatMotion.Phase.DART;
            sawQuiver |= a.phase == CatMotion.Phase.QUIVER;
            sawHide |= a.phase == CatMotion.Phase.HIDE && !a.visible;
            sawRest |= a.phase == CatMotion.Phase.REST;
        }
        assertTrue(sawDart);
        assertTrue(sawQuiver);
        assertTrue(sawHide);
        assertTrue(sawRest);
    }

    @Test public void manualPointerOwnsTargetUntilReleased() {
        CatMotion motion = new CatMotion(7);
        motion.update(0, false, 0, 0, 0);
        CatMotion.Frame left = motion.update(20, true, 0, 1, 0);
        assertEquals(0.035, left.x, 0.000001);
        assertEquals(0.965, left.y, 0.000001);
        CatMotion.Frame center = motion.update(40, true, 0.4, 0.6, 0);
        assertEquals(0.4, center.x, 0.000001);
        assertEquals(0.6, center.y, 0.000001);

        CatMotion.Frame resumed = motion.update(41, false, 0.4, 0.6, 0);
        assertEquals(0.4, resumed.x, 0.000001);
        assertEquals(0.6, resumed.y, 0.000001);
        assertEquals(CatMotion.Phase.DART, resumed.phase);
    }

    @Test public void newPounceProducesAVisibleCaptureRest() {
        CatMotion motion = new CatMotion(9);
        motion.update(0, false, 0, 0, 3);
        CatMotion.Frame caught = motion.update(10, false, 0, 0, 4);
        assertTrue(caught.visible);
        assertTrue(caught.caught);
        assertFalse(motion.update(900, false, 0, 0, 4).caught);
    }

    @Test public void largeFrameGapRebasesWithoutMultiFrameCatchup() {
        CatMotion motion = new CatMotion(11);
        motion.update(0, false, 0, 0, 0);
        CatMotion.Frame resumed = motion.update(9 * 60_000, false, 0, 0, 0);
        CatMotion.Frame next = motion.update(9 * 60_000 + 16, false, 0, 0, 0);
        assertEquals(CatMotion.Phase.DART, resumed.phase);
        assertEquals(CatMotion.Phase.DART, next.phase);
        assertTrue(Math.hypot(next.x - resumed.x, next.y - resumed.y) < 0.02);
    }
}

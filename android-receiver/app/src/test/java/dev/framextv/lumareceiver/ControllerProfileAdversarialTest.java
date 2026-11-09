package dev.framextv.lumareceiver;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

/** Hardware calibration remains separate from logical NES actions. */
public class ControllerProfileAdversarialTest {
    private static ControllerProfile.State state(ControllerProfile profile, Integer... keys) {
        return profile.evaluate(new HashSet<>(Arrays.asList(keys)), new HashMap<>());
    }
    private static Map<Integer, Float> axes(int axis, float value) {
        Map<Integer, Float> result = new HashMap<>(); result.put(axis, value); return result;
    }
    @Test public void arbitraryHardwareKeysAndNesRemapSurvivePersistenceIndependently() throws Exception {
        ControllerProfile profile = ControllerProfile.defaults();
        profile.bind(ControllerProfile.Control.A, ControllerProfile.Binding.key(401));
        profile.bind(ControllerProfile.Control.L, ControllerProfile.Binding.key(402));
        profile.nes.put(ControllerProfile.Control.A, ControllerProfile.B);
        profile.nes.put(ControllerProfile.Control.L, ControllerProfile.A);
        ControllerProfile restored = ControllerProfile.fromJson(profile.toJson());
        assertTrue(state(restored, 401).pressed(ControllerProfile.Control.A));
        assertFalse(state(restored, 96).pressed(ControllerProfile.Control.A));
        assertEquals(ControllerProfile.B, restored.nesButtons(state(restored, 401)));
        assertEquals(ControllerProfile.A, restored.nesButtons(state(restored, 402)));
    }
    @Test public void nonzeroCenteredInvertedAxisRejectsDriftAndNonFiniteSamples() {
        ControllerProfile profile = new ControllerProfile();
        profile.setStick(ControllerProfile.StickAxis.LEFT_X, new ControllerProfile.AxisBinding(32, .1f, -.9f, .2f));
        assertEquals(0, profile.nesButtons(profile.evaluate(Collections.emptySet(), axes(32, .2f))));
        assertEquals(ControllerProfile.RIGHT, profile.nesButtons(profile.evaluate(Collections.emptySet(), axes(32, -.9f))));
        assertEquals(ControllerProfile.LEFT, profile.nesButtons(profile.evaluate(Collections.emptySet(), axes(32, 1.1f))));
        assertEquals(0, profile.nesButtons(profile.evaluate(Collections.emptySet(), axes(32, Float.NaN))));
        assertEquals(0, profile.nesButtons(profile.evaluate(Collections.emptySet(), axes(32, Float.POSITIVE_INFINITY))));
        assertEquals(0, profile.nesButtons(profile.evaluate(Collections.emptySet(), Collections.emptyMap())));
    }
    @Test public void alternateKeyAndHatKeepDirectionUntilBothAreReleased() {
        ControllerProfile profile = ControllerProfile.defaults();
        Set<Integer> keys = new HashSet<>(); keys.add(21);
        assertEquals(ControllerProfile.LEFT, profile.nesButtons(profile.evaluate(keys, axes(15, -1))));
        keys.clear();
        assertEquals(ControllerProfile.LEFT, profile.nesButtons(profile.evaluate(keys, axes(15, -1))));
        assertEquals(0, profile.nesButtons(profile.evaluate(keys, axes(15, 0))));
    }
    @Test public void rightStickIsOptInAndOpposingDirectionsAreNeutralized() {
        ControllerProfile profile = ControllerProfile.defaults();
        Map<Integer, Float> values = axes(11, 1);
        assertEquals(0, profile.nesButtons(profile.evaluate(Collections.emptySet(), values)));
        profile.rightStickInNes = true;
        assertEquals(ControllerProfile.RIGHT, profile.nesButtons(profile.evaluate(Collections.emptySet(), values)));
        values.put(0, -1f);
        assertEquals(0, profile.nesButtons(profile.evaluate(Collections.emptySet(), values)));
        assertEquals(0, profile.nesButtons(state(profile, 19, 20, 21, 22)));
    }
    @Test public void unmappedMenuAndGameplayActionsRemainDistinct() {
        ControllerProfile profile = ControllerProfile.defaults();
        profile.nes.put(ControllerProfile.Control.L, ControllerProfile.NONE);
        assertEquals(0, profile.nesButtons(state(profile, 102)));
        assertEquals(ControllerProfile.MENU, profile.nesButtons(state(profile, 103)));
        profile.nes.put(ControllerProfile.Control.R, ControllerProfile.START);
        assertEquals(ControllerProfile.START, profile.nesButtons(state(profile, 103)));
        assertEquals(ControllerProfile.START | ControllerProfile.A, profile.nesButtons(state(profile, 103, 96)));
    }
}

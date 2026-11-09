package dev.framextv.lumareceiver;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.*;

public final class ControllerProfileTest {
    @Test public void checkpointBindingPersistsAndOnlyFiresOnFreshPhysicalPress() throws Exception {
        ControllerProfile profile = ControllerProfile.defaults();
        profile.nes.put(ControllerProfile.Control.RCLICK, ControllerProfile.LOAD_CHECKPOINT);
        profile = ControllerProfile.fromJson(profile.toJson());
        int held = 1 << ControllerProfile.Control.RCLICK.ordinal();
        assertTrue(profile.checkpointPressed(0, held));
        assertFalse(profile.checkpointPressed(held, held));
        assertFalse(profile.checkpointPressed(held, 0));
        assertEquals(0, profile.nesPresses(0, held));
        assertEquals(ControllerProfile.LOAD_CHECKPOINT,
            profile.nesButtons(profile.evaluate(Set.of(107), Map.of())));
    }
    @Test public void overlappingPhysicalBindingsPreserveHoldAndProduceAFrameEdge() {
        ControllerProfile profile = ControllerProfile.defaults();
        profile.nes.put(ControllerProfile.Control.ZR, ControllerProfile.B);
        NesFrameInput frames = new NesFrameInput();
        ControllerProfile.State first = profile.evaluate(Set.of(97), Map.of());
        frames.update(profile.nesButtons(first), profile.nesPresses(0, first.pressedBits));
        assertEquals(ControllerProfile.B, frames.nextFrame());
        ControllerProfile.State both = profile.evaluate(Set.of(97, 105), Map.of());
        frames.update(profile.nesButtons(both), profile.nesPresses(first.pressedBits, both.pressedBits));
        // A complete trigger tap between frames must still reach the emulator.
        frames.update(profile.nesButtons(first), profile.nesPresses(both.pressedBits, first.pressedBits));
        assertEquals(0, frames.nextFrame());
        assertEquals(ControllerProfile.B, frames.nextFrame());
        assertEquals(ControllerProfile.B, frames.nextFrame());
        assertEquals(1, frames.retriggerCount());
        frames.update(0, 0);
        assertEquals(0, frames.nextFrame());
    }

    @Test public void keyRepeatsAndAxisAliasDoNotCreateAnotherPhysicalPress() {
        ControllerProfile profile = ControllerProfile.defaults();
        profile.nes.put(ControllerProfile.Control.ZL, ControllerProfile.B);
        ControllerProfile.State key = profile.evaluate(Set.of(104), Map.of());
        ControllerProfile.State alias = profile.evaluate(Set.of(104), Map.of(17, 1f));
        ControllerProfile.State axisOnly = profile.evaluate(Set.of(), Map.of(17, 1f));
        assertEquals(ControllerProfile.B, profile.nesPresses(0, key.pressedBits));
        assertEquals(0, profile.nesPresses(key.pressedBits, key.pressedBits));
        assertEquals(0, profile.nesPresses(key.pressedBits, alias.pressedBits));
        assertEquals(0, profile.nesPresses(alias.pressedBits, axisOnly.pressedBits));
    }

    @Test public void menuAndVolumeNeverProduceNesPressEdges() {
        ControllerProfile profile = ControllerProfile.defaults();
        ControllerProfile.State state = profile.evaluate(Set.of(102, 103, 104, 105), Map.of());
        assertEquals(0, profile.nesPresses(0, state.pressedBits));
    }

    @Test public void savedHardwareAndNesMappingsRoundTripIndependently() throws Exception {
        ControllerProfile profile = ControllerProfile.defaults();
        profile.bind(ControllerProfile.Control.A, ControllerProfile.Binding.key(190));
        profile.bind(ControllerProfile.Control.ZL, ControllerProfile.Binding.axis(23, -1, 1));
        profile.setStick(ControllerProfile.StickAxis.RIGHT_X, new ControllerProfile.AxisBinding(12, .03f, -.97f, .2f));
        profile.nes.put(ControllerProfile.Control.A, ControllerProfile.B);
        profile.rightStickInNes = true;
        ControllerProfile restored = ControllerProfile.fromJson(new JSONObject(profile.toJson().toString()));
        ControllerProfile.State state = restored.evaluate(Set.of(190), Map.of(23, 1f, 12, -.97f));
        assertTrue(state.pressed(ControllerProfile.Control.A));
        assertTrue(state.pressed(ControllerProfile.Control.ZL));
        assertEquals(1f, state.axis(ControllerProfile.StickAxis.RIGHT_X), .001f);
        assertEquals(ControllerProfile.B | ControllerProfile.RIGHT | ControllerProfile.VOLUME_DOWN, restored.nesButtons(state));
        assertEquals(23, restored.buttons.get(ControllerProfile.Control.ZL).get(0).axis);
    }

    @Test public void draftEditsNeverMutateStoredOrLoadedProfile() {
        ControllerProfile committed = ControllerProfile.defaults(), draft = committed.copy();
        draft.bind(ControllerProfile.Control.A, ControllerProfile.Binding.key(250));
        draft.clear(ControllerProfile.Control.B);
        draft.sticks.clear(); draft.nes.put(ControllerProfile.Control.A, ControllerProfile.MENU);
        assertTrue(committed.evaluate(Set.of(96, 97), Map.of()).pressed(ControllerProfile.Control.A));
        assertTrue(committed.evaluate(Set.of(96, 97), Map.of()).pressed(ControllerProfile.Control.B));
        assertEquals(4, committed.sticks.size());
        assertEquals(Integer.valueOf(ControllerProfile.A), committed.nes.get(ControllerProfile.Control.A));
    }

    @Test public void reassigningAKeyOrAxisRemovesConflictingOwnership() throws Exception {
        ControllerProfile profile = ControllerProfile.defaults();
        profile.bind(ControllerProfile.Control.A, ControllerProfile.Binding.key(97));
        ControllerProfile.State state = profile.evaluate(Set.of(97), Map.of());
        assertTrue(state.pressed(ControllerProfile.Control.A));
        assertFalse(state.pressed(ControllerProfile.Control.B));
        profile.setStick(ControllerProfile.StickAxis.RIGHT_X, new ControllerProfile.AxisBinding(0, 0, 1, .16f));
        assertFalse(profile.sticks.containsKey(ControllerProfile.StickAxis.LEFT_X));
        ControllerProfile.fromJson(profile.toJson());
    }

    @Test public void corruptUnknownAndAmbiguousProfilesDoNotBecomePartialMappings() throws Exception {
        JSONObject json = ControllerProfile.defaults().toJson();
        json.getJSONObject("buttons").put("A", new JSONArray().put(new JSONObject().put("key", 97)));
        reject(json); // B already owns this key.
        json = ControllerProfile.defaults().toJson(); json.getJSONObject("nes").put("A", 3); reject(json);
        json = ControllerProfile.defaults().toJson(); json.getJSONObject("nes").put("A", "1"); reject(json);
        json = ControllerProfile.defaults().toJson(); json.getJSONObject("sticks").getJSONObject("LEFT_X").put("span", 0); reject(json);
        json = ControllerProfile.defaults().toJson(); json.getJSONObject("sticks").getJSONObject("LEFT_X").put("axis", 64); reject(json);
        json = ControllerProfile.defaults().toJson(); json.getJSONObject("buttons").getJSONArray("A").getJSONObject(0).put("key", 1024); reject(json);
        json = ControllerProfile.defaults().toJson(); json.put("version", 2); reject(json);
        json = ControllerProfile.defaults().toJson(); json.remove("nes"); reject(json);
    }

    @Test public void descriptorIdentitySurvivesTransientDeviceIdsAndModelFallbackIsExplicit() throws Exception {
        assertEquals("device:bluetooth-profile", ControllerProfileStore.identity("bluetooth-profile", 1406, 8201, "Pro Controller"));
        assertEquals("model:1406:8201", ControllerProfileStore.identity("", 1406, 8201, "Pro Controller"));
        assertEquals("name:Generic Gamepad", ControllerProfileStore.identity(null, 0, 0, "Generic Gamepad"));
        assertNotNull(ControllerProfileStore.decode(ControllerProfile.defaults().toJson().toString()));
        assertNull(ControllerProfileStore.decode("{bad"));
        assertNull(ControllerProfileStore.decode(" ".repeat(32_769)));
    }

    @Test public void perDeviceSnapshotsReleaseIndependentlyAndCancelOppositesAfterAggregation() {
        ControllerState state = new ControllerState();
        state.mapped(1, ControllerState.LEFT | ControllerState.A);
        assertEquals(ControllerState.A, state.mapped(2, ControllerState.RIGHT));
        assertEquals(ControllerState.LEFT | ControllerState.A, state.remove(2));
        assertEquals(0, state.mapped(1, 0));
    }

    @Test public void volumeAssignmentsRoundTripWithoutChangingNesButtonsOrMenu() throws Exception {
        ControllerProfile profile = ControllerProfile.fromJson(ControllerProfile.defaults().toJson());
        int both = profile.nesButtons(profile.evaluate(Set.of(104, 105, 96), Map.of()));
        assertEquals(ControllerProfile.VOLUME_DOWN | ControllerProfile.VOLUME_UP | ControllerProfile.A, both);
        assertEquals(ControllerProfile.A, both & 255);
        assertEquals(0, both & ControllerProfile.MENU);
        profile.nes.put(ControllerProfile.Control.ZL, ControllerProfile.A);
        profile.nes.put(ControllerProfile.Control.ZR, ControllerProfile.NONE);
        ControllerProfile restored = ControllerProfile.fromJson(profile.toJson());
        assertEquals(ControllerProfile.A, restored.nesButtons(restored.evaluate(Set.of(104, 105), Map.of())));
    }

    @Test public void oldUnusedTriggerAssignmentsStayUnusedAndInvalidVolumeCombinationsFail() throws Exception {
        JSONObject saved = ControllerProfile.defaults().toJson();
        saved.getJSONObject("nes").put("ZL", 0).put("ZR", 0);
        ControllerProfile restored = ControllerProfile.fromJson(saved);
        assertEquals(0, restored.nesButtons(restored.evaluate(Set.of(104, 105), Map.of())));
        for (int invalid : new int[]{1536, 4096, 513, -512}) {
            JSONObject json = ControllerProfile.defaults().toJson();
            json.getJSONObject("nes").put("ZL", invalid); reject(json);
        }
    }

    private static void reject(JSONObject json) throws Exception {
        try { ControllerProfile.fromJson(json); fail("Accepted invalid controller profile"); }
        catch (JSONException expected) { }
    }
}

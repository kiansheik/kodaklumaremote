package dev.framextv.lumareceiver;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ControllerPlayersTest {
    private static ControllerPlayers players(int count) {
        ControllerPlayers state = new ControllerPlayers();
        for (int i = 1; i <= count; i++) state.remember("id" + i, "Controller " + i, 1406, 8201);
        return state;
    }
    @Test public void autoAssignsFourAndLeavesAdditionalControllerUnassigned() {
        ControllerPlayers state = players(5);
        for (int i = 1; i <= 4; i++) assertEquals(i, state.player("id" + i));
        assertEquals(0, state.player("id5"));
    }
    @Test public void explicitAssignmentSwapsWithoutDuplicateSlots() {
        ControllerPlayers state = players(4);
        assertTrue(state.assign("id4", 1)); assertEquals(1, state.player("id4")); assertEquals(4, state.player("id1"));
    }
    @Test public void unassignedReplacementDisplacesOldOccupant() {
        ControllerPlayers state = players(5);
        assertTrue(state.assign("id5", 1)); assertEquals(0, state.player("id1")); assertEquals(1, state.player("id5"));
        state.remember("id1", "Reconnected", 1406, 8201); assertEquals(0, state.player("id1"));
    }
    @Test public void persistenceKeepsStableIdentityAndRememberedSlot() throws Exception {
        ControllerPlayers state = players(3); state.assign("id3", 1);
        ControllerPlayers restored = ControllerPlayers.fromJson(new JSONObject(state.toJson().toString()));
        restored.remember("id3", "Renamed", 1406, 8201);
        assertEquals(1, restored.player("id3")); assertEquals(3, restored.player("id1"));
        assertFalse(restored.assign("id3", 0)); assertFalse(restored.assign("missing", 2));
    }
    @Test public void storeBoundEvictsOnlyUnassignedControllers() {
        ControllerPlayers state = players(100); assertEquals(64, state.entries().size());
        for (int i = 1; i <= 4; i++) assertEquals(i, state.player("id" + i));
        assertEquals("id100", state.entries().get(63).identity);
    }
    @Test public void rejectsDuplicateSlotAndWrongNumericTypes() throws Exception {
        JSONObject json = players(2).toJson(); json.getJSONArray("entries").getJSONObject(1).put("player", 1);
        try { ControllerPlayers.fromJson(json); fail("duplicate slot"); } catch (JSONException expected) { }
        json = players(1).toJson(); json.getJSONArray("entries").getJSONObject(0).put("player", "1");
        try { ControllerPlayers.fromJson(json); fail("string slot"); } catch (JSONException expected) { }
    }
}

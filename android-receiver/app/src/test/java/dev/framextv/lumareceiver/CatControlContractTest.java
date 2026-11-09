package dev.framextv.lumareceiver;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public final class CatControlContractTest {
    @Test public void milliNormalizedCoordinatesConvertToUnitSpace() throws Exception {
        assertEquals(0, ControlServer.normalized(new JSONObject().put("x", 0), "x"), 0);
        assertEquals(0.5, ControlServer.normalized(new JSONObject().put("x", 500), "x"), 0);
        assertEquals(1, ControlServer.normalized(new JSONObject().put("x", 1000), "x"), 0);
    }

    @Test public void coordinatesMustBePresentBoundedIntegerNumbers() throws Exception {
        invalid(new JSONObject(), "x");
        invalid(new JSONObject().put("x", "500"), "x");
        invalid(new JSONObject().put("x", -1), "x");
        invalid(new JSONObject().put("x", 1001), "x");
        invalid(new JSONObject().put("x", 500.5), "x");
    }

    private static void invalid(JSONObject value, String key) throws Exception {
        try {
            ControlServer.normalized(value, key);
            fail("Expected INVALID_INPUT");
        } catch (ApiException expected) {
            assertEquals("INVALID_INPUT", expected.code);
        }
    }
}

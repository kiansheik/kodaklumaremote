package dev.framextv.lumareceiver;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.InputDevice;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

/** Separate preferences from hardware profiles; snapshots never expose device descriptors. */
final class ControllerPlayerStore {
    private static final Object LOCK = new Object();
    private final SharedPreferences preferences;
    ControllerPlayerStore(Context context) { preferences = context.getSharedPreferences("controller_players_v1", Context.MODE_PRIVATE); }
    int player(InputDevice device) {
        // Synthetic smoke-test input has no hardware identity and never reserves a slot.
        if (device == null || device.getId() < 0) return 1;
        if (!controller(device)) return 0;
        synchronized (LOCK) {
            ControllerPlayers state = load(); inventory(state);
            remember(state, device); persist(state); return state.player(identity(device));
        }
    }
    boolean assign(InputDevice device, int player) {
        if (!controller(device) || player < 1 || player > 4) return false;
        synchronized (LOCK) {
            ControllerPlayers state = load(); inventory(state); remember(state, device);
            return state.assign(identity(device), player) && persist(state);
        }
    }
    JSONObject snapshot() {
        synchronized (LOCK) {
            ControllerPlayers state = load(); Set<String> connected = inventory(state); persist(state);
            JSONObject result = new JSONObject(); JSONArray devices = new JSONArray(), slots = new JSONArray();
            try {
                for (ControllerPlayers.Entry entry : state.entries()) devices.put(new JSONObject().put("name", entry.name)
                    .put("player", entry.player).put("connected", connected.contains(entry.identity))
                    .put("vendorId", entry.vendorId).put("productId", entry.productId));
                for (int player = 1; player <= 4; player++) {
                    ControllerPlayers.Entry found = null;
                    for (ControllerPlayers.Entry entry : state.entries()) if (entry.player == player) found = entry;
                    slots.put(new JSONObject().put("player", player).put("assigned", found != null)
                        .put("connected", found != null && connected.contains(found.identity))
                        .put("name", found == null ? JSONObject.NULL : found.name));
                }
                result.put("devices", devices).put("slots", slots);
            } catch (JSONException impossible) { }
            return result;
        }
    }
    private ControllerPlayers load() {
        String encoded = preferences.getString("state", null);
        if (encoded != null && encoded.length() <= 131072) try { return ControllerPlayers.fromJson(new JSONObject(encoded)); }
        catch (JSONException | IllegalArgumentException invalid) { }
        return new ControllerPlayers();
    }
    private boolean persist(ControllerPlayers state) {
        try {
            String encoded = state.toJson().toString();
            return encoded.equals(preferences.getString("state", null)) || preferences.edit().putString("state", encoded).commit();
        } catch (JSONException invalid) { return false; }
    }
    private Set<String> inventory(ControllerPlayers state) {
        Set<String> connected = new HashSet<>();
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (controller(device)) { remember(state, device); connected.add(identity(device)); }
        }
        return connected;
    }
    private static boolean controller(InputDevice device) { return device != null && ControllerInput.isController(device.getSources(), device); }
    private static String identity(InputDevice device) { return ControllerProfileStore.identity(device); }
    private static void remember(ControllerPlayers state, InputDevice device) {
        String name = device.getName() == null ? "Controller" : device.getName();
        if (name.length() > 256) name = name.substring(0, 256);
        state.remember(identity(device), name, device.getVendorId(), device.getProductId());
    }
}

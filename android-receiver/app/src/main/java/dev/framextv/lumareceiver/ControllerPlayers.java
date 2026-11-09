package dev.framextv.lumareceiver;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** Remembered player assignments, independent of physical and emulator mappings. */
final class ControllerPlayers {
    static final int LIMIT = 64;
    static final class Entry {
        final String identity, name;
        final int vendorId, productId;
        int player;
        Entry(String identity, String name, int vendorId, int productId, int player) {
            this.identity = identity; this.name = name; this.vendorId = vendorId; this.productId = productId; this.player = player;
        }
    }
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    List<Entry> entries() { return new ArrayList<>(entries.values()); }
    int player(String identity) { Entry entry = entries.get(identity); return entry == null ? 0 : entry.player; }
    void remember(String identity, String name, int vendor, int product) {
        validate(identity, name, vendor, product, 0);
        Entry old = entries.get(identity);
        if (old != null) { entries.put(identity, new Entry(identity, name, vendor, product, old.player)); return; }
        if (entries.size() >= LIMIT) {
            for (Entry entry : new ArrayList<>(entries.values())) if (entry.player == 0) { entries.remove(entry.identity); break; }
        }
        int free = 0;
        for (int slot = 1; slot <= 4; slot++) if (occupant(slot) == null) { free = slot; break; }
        entries.put(identity, new Entry(identity, name, vendor, product, free));
    }
    boolean assign(String identity, int player) {
        if (player < 1 || player > 4 || !entries.containsKey(identity)) return false;
        Entry selected = entries.get(identity), previous = occupant(player);
        if (previous != null && previous != selected) previous.player = selected.player;
        selected.player = player; return true;
    }
    private Entry occupant(int player) {
        for (Entry entry : entries.values()) if (entry.player == player) return entry;
        return null;
    }
    JSONObject toJson() throws JSONException {
        JSONArray values = new JSONArray();
        for (Entry entry : entries.values()) values.put(new JSONObject().put("identity", entry.identity).put("name", entry.name)
            .put("vendorId", entry.vendorId).put("productId", entry.productId).put("player", entry.player));
        return new JSONObject().put("version", 1).put("entries", values);
    }
    static ControllerPlayers fromJson(JSONObject json) throws JSONException {
        if (json.length() != 2 || integer(json, "version") != 1) throw new JSONException("Invalid player store");
        JSONArray values = json.getJSONArray("entries");
        if (values.length() > LIMIT) throw new JSONException("Too many controllers");
        ControllerPlayers result = new ControllerPlayers(); Set<Integer> slots = new HashSet<>();
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.getJSONObject(i);
            if (value.length() != 5 || !(value.get("identity") instanceof String) || !(value.get("name") instanceof String))
                throw new JSONException("Invalid controller entry");
            String identity = value.getString("identity"), name = value.getString("name");
            int vendor = integer(value, "vendorId"), product = integer(value, "productId"), player = integer(value, "player");
            try { validate(identity, name, vendor, product, player); }
            catch (IllegalArgumentException invalid) { throw new JSONException("Invalid controller entry"); }
            if (result.entries.containsKey(identity) || player != 0 && !slots.add(player)) throw new JSONException("Duplicate player assignment");
            result.entries.put(identity, new Entry(identity, name, vendor, product, player));
        }
        return result;
    }
    private static int integer(JSONObject json, String key) throws JSONException {
        Object value = json.get(key);
        if (!(value instanceof Number) || ((Number) value).doubleValue() != ((Number) value).intValue()) throw new JSONException("Invalid integer");
        return ((Number) value).intValue();
    }
    private static void validate(String identity, String name, int vendor, int product, int player) {
        if (identity == null || identity.isEmpty() || identity.length() > 1024 || name == null || name.length() > 256
            || vendor < 0 || vendor > 65535 || product < 0 || product > 65535 || player < 0 || player > 4)
            throw new IllegalArgumentException("Invalid controller");
    }
}

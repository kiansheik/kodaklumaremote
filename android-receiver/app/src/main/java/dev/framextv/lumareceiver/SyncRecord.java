package dev.framextv.lumareceiver;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * One library row as exchanged between peers. Merge is last-writer-wins on
 * (updatedAt, origin, data) so every peer converges regardless of sync order,
 * repeats or direction: the merge is commutative, associative and idempotent.
 */
final class SyncRecord {
    static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9:._-]{0,199}");
    static final Pattern ORIGIN = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    static final int MAX_DATA = 32 * 1024;
    static final long MAX_FUTURE_SKEW = 24L * 60 * 60 * 1000;

    final String key, type, origin, data;
    final long updatedAt;
    final boolean deleted;

    SyncRecord(String key, String type, long updatedAt, String origin, boolean deleted, String data) {
        this.key = key; this.type = type; this.updatedAt = updatedAt; this.origin = origin; this.deleted = deleted; this.data = data;
    }

    static boolean type(String type) { return "progress".equals(type) || "title".equals(type) || "season".equals(type); }

    /** A title tombstone is the bounded command that clears every matching projector-side episode. */
    static String[] deletedTitleScope(SyncRecord record) {
        if (record == null || !record.deleted || !"title".equals(record.type)) return null;
        java.util.regex.Matcher match = Pattern.compile("^title:(movie|tv):([1-9][0-9]{0,9})$").matcher(record.key);
        return match.matches() ? new String[] {match.group(1), match.group(2)} : null;
    }

    static boolean belongsToTitle(SyncRecord record, String[] scope) {
        if (record == null || record.deleted || !"progress".equals(record.type) || scope == null || scope.length != 2) return false;
        try {
            JSONObject data = new JSONObject(record.data);
            return scope[0].equals(data.optString("kind")) && scope[1].equals(data.optString("contentId"));
        } catch (Exception invalid) { return false; }
    }

    /** A title tombstone also dominates progress received after its original
     * cascade when that progress is not newer than the deletion. */
    static boolean blockedByTitle(SyncRecord record, SyncRecord title) {
        if (record == null || record.deleted || !"progress".equals(record.type)
                || title == null || !title.deleted || !"title".equals(title.type)
                || title.updatedAt < record.updatedAt) return false;
        String[] scope = deletedTitleScope(title);
        return scope != null && belongsToTitle(record, scope);
    }

    static SyncRecord parse(JSONObject value, long now) throws ApiException {
        try {
            String key = value.getString("key"), type = value.getString("type"), origin = value.getString("origin");
            Object stamp = value.get("updatedAt");
            if (!KEY.matcher(key).matches() || !type(type) || !ORIGIN.matcher(origin).matches() || !(stamp instanceof Number)) throw new ApiException("INVALID_RECORD");
            long updatedAt = ((Number) stamp).longValue();
            if (updatedAt < 0 || ((Number) stamp).doubleValue() != updatedAt || updatedAt > now + MAX_FUTURE_SKEW) throw new ApiException("INVALID_RECORD");
            boolean deleted = value.optBoolean("deleted", false);
            String data = "{}";
            if (!deleted) {
                JSONObject object = value.getJSONObject("data");
                data = object.toString();
                if (data.getBytes(StandardCharsets.UTF_8).length > MAX_DATA) throw new ApiException("INVALID_RECORD");
            }
            return new SyncRecord(key, type, updatedAt, origin, deleted, data);
        } catch (ApiException rethrow) { throw rethrow; }
        catch (Exception invalid) { throw new ApiException("INVALID_RECORD"); }
    }

    JSONObject toJson(long seq) throws Exception {
        JSONObject value = new JSONObject().put("key", key).put("type", type).put("updatedAt", updatedAt)
            .put("origin", origin).put("seq", seq);
        if (deleted) value.put("deleted", true); else value.put("data", new JSONObject(data));
        return value;
    }

    /** True when {@code incoming} should replace {@code existing} (null means absent). */
    static boolean wins(SyncRecord incoming, SyncRecord existing) {
        if (existing == null) return true;
        if (incoming.updatedAt != existing.updatedAt) return incoming.updatedAt > existing.updatedAt;
        // An echo of identical content is not a change, whoever relays it.
        if (incoming.deleted == existing.deleted && incoming.data.equals(existing.data)) return false;
        int origin = incoming.origin.compareTo(existing.origin);
        if (origin != 0) return origin > 0;
        if (incoming.deleted != existing.deleted) return incoming.deleted;
        return incoming.data.compareTo(existing.data) > 0;
    }
}

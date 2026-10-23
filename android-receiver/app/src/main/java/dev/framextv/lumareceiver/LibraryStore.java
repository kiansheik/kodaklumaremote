package dev.framextv.lumareceiver;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * Projector-side viewing library. Every accepted change gets a local sequence
 * number, so a peer pulls with its last cursor and pushes its own changes;
 * {@link SyncRecord#wins} decides conflicts identically on every device.
 */
final class LibraryStore extends SQLiteOpenHelper {
    static final int MAX_PULL = 500;
    private final String origin;

    LibraryStore(Context context, String origin) {
        super(context, "library.db", null, 1);
        this.origin = origin;
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE records(key TEXT PRIMARY KEY,type TEXT NOT NULL,updated_at INTEGER NOT NULL,origin TEXT NOT NULL,deleted INTEGER NOT NULL,data TEXT NOT NULL,seq INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX records_seq ON records(seq)");
        db.execSQL("CREATE TABLE meta(name TEXT PRIMARY KEY,value INTEGER NOT NULL)");
        db.execSQL("INSERT INTO meta VALUES('seq',0)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int from, int to) { }

    String origin() { return origin; }

    synchronized long cursor() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT value FROM meta WHERE name='seq'", null)) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    /** Applies remote or local records; returns how many replaced local state. */
    synchronized int merge(List<SyncRecord> records) {
        SQLiteDatabase db = getWritableDatabase();
        int applied = 0;
        db.beginTransaction();
        try {
            long seq = cursor();
            for (SyncRecord incoming : records) {
                if (!SyncRecord.wins(incoming, read(db, incoming.key))) continue;
                ContentValues row = new ContentValues();
                row.put("key", incoming.key); row.put("type", incoming.type); row.put("updated_at", incoming.updatedAt);
                row.put("origin", incoming.origin); row.put("deleted", incoming.deleted ? 1 : 0); row.put("data", incoming.data);
                row.put("seq", ++seq);
                db.insertWithOnConflict("records", null, row, SQLiteDatabase.CONFLICT_REPLACE);
                applied++;
            }
            db.execSQL("UPDATE meta SET value=? WHERE name='seq'", new Object[] {seq});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return applied;
    }

    private static SyncRecord read(SQLiteDatabase db, String key) {
        try (Cursor c = db.rawQuery("SELECT key,type,updated_at,origin,deleted,data FROM records WHERE key=?", new String[] {key})) {
            return c.moveToFirst() ? new SyncRecord(c.getString(0), c.getString(1), c.getLong(2), c.getString(3), c.getInt(4) != 0, c.getString(5)) : null;
        }
    }

    synchronized JSONObject get(String key) throws Exception {
        SyncRecord record = read(getReadableDatabase(), key);
        return record == null || record.deleted ? null : new JSONObject(record.data);
    }

    synchronized JSONObject pull(long since, int limit) throws Exception {
        JSONArray list = new JSONArray();
        long last = since;
        int bounded = Math.max(1, Math.min(limit, MAX_PULL));
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT key,type,updated_at,origin,deleted,data,seq FROM records WHERE seq>? ORDER BY seq LIMIT ?",
                new String[] {Long.toString(since), Integer.toString(bounded + 1)})) {
            while (c.moveToNext() && list.length() < bounded) {
                SyncRecord record = new SyncRecord(c.getString(0), c.getString(1), c.getLong(2), c.getString(3), c.getInt(4) != 0, c.getString(5));
                last = c.getLong(6);
                list.put(record.toJson(last));
            }
            boolean more = c.getCount() > bounded;
            return new JSONObject().put("records", list).put("cursor", last).put("more", more).put("origin", origin);
        }
    }

    synchronized int count(String type) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM records WHERE type=? AND deleted=0", new String[] {type})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** Mirrors the phone's WatchHistory progress entry so both sides read the same shape. */
    void recordProgress(JSONObject content, double position, double duration, long lastStartedAt, boolean autoplayed) {
        try {
            long now = System.currentTimeMillis();
            if (duration > 0) position = Math.min(position, duration);
            boolean completed = duration >= 60 && position >= duration * 0.95 && duration - position <= 30;
            JSONObject data = new JSONObject(content.toString())
                .put("positionSeconds", position).put("durationSeconds", duration)
                .put("resumeSeconds", completed ? 0 : position).put("completed", completed)
                .put("updatedAt", now).put("lastStartedAt", lastStartedAt).put("lastReceiver", "luma");
            if (autoplayed) data.put("autoplayed", true);
            merge(java.util.Collections.singletonList(new SyncRecord(content.getString("key"), "progress", now, origin, false, data.toString())));
        } catch (Exception ignored) {
            android.util.Log.w("LumaLibrary", "PROGRESS_WRITE_FAILED");
        }
    }
}

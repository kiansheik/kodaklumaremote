package dev.framextv.lumareceiver;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Scrub thumbnails shared between phones through the projector: a phone that
 * generated frames for an episode uploads them, and any phone that later plays
 * the same episode downloads them instead of decoding again. Frames are small
 * JPEGs on disk for 48 hours, capped in total size.
 */
final class ThumbnailStore {
    static final long TTL_MS = 48L * 60 * 60 * 1000, CAP_BYTES = 96L * 1024 * 1024;
    static final int MAX_FRAME_BYTES = 96 * 1024, MAX_BATCH = 40;
    private final File root;
    // Frames of the episode currently being scrubbed, for instant nearest lookups.
    private String indexKey;
    private final java.util.TreeMap<Long, byte[]> index = new java.util.TreeMap<>();

    ThumbnailStore(Context context) {
        root = new File(context.getCacheDir(), "thumbnails");
        prune();
    }

    synchronized JSONObject list(String key) throws Exception {
        TreeSet<Long> positions = new TreeSet<>();
        File[] files = folder(key).listFiles();
        long now = System.currentTimeMillis();
        if (files != null) for (File file : files) {
            String name = file.getName();
            if (name.matches("[0-9]{1,6}\\.jpg") && now - file.lastModified() <= TTL_MS) positions.add(Long.parseLong(name.substring(0, name.length() - 4)));
        }
        return new JSONObject().put("positions", new JSONArray(positions));
    }

    synchronized JSONObject get(String key, JSONArray positions) throws Exception {
        if (positions == null || positions.length() > MAX_BATCH) throw new ApiException("INVALID_INPUT");
        JSONArray frames = new JSONArray();
        File folder = folder(key);
        for (int i = 0; i < positions.length(); i++) {
            long position = MediaRequest.integer(positions.opt(i), 0, 86399);
            File file = new File(folder, position + ".jpg");
            if (!file.isFile() || file.length() > MAX_FRAME_BYTES) continue;
            frames.put(new JSONObject().put("position", position)
                .put("image", android.util.Base64.encodeToString(Files.readAllBytes(file.toPath()), android.util.Base64.NO_WRAP)));
        }
        return new JSONObject().put("frames", frames);
    }

    synchronized JSONObject put(String key, JSONArray frames) throws Exception {
        if (frames == null || frames.length() > MAX_BATCH) throw new ApiException("INVALID_INPUT");
        File folder = folder(key);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new ApiException("STORAGE", 500);
        int stored = 0;
        for (int i = 0; i < frames.length(); i++) {
            JSONObject frame = frames.optJSONObject(i);
            if (frame == null) throw new ApiException("INVALID_INPUT");
            long position = MediaRequest.integer(frame.opt("position"), 0, 86399);
            byte[] jpeg;
            try { jpeg = android.util.Base64.decode(frame.optString("image"), android.util.Base64.DEFAULT); }
            catch (IllegalArgumentException invalid) { throw new ApiException("INVALID_INPUT"); }
            // JPEG only: SOI marker, bounded size.
            if (jpeg.length < 4 || jpeg.length > MAX_FRAME_BYTES || (jpeg[0] & 0xff) != 0xFF || (jpeg[1] & 0xff) != 0xD8) throw new ApiException("INVALID_INPUT");
            File target = new File(folder, position + ".jpg");
            if (target.isFile()) continue;
            File part = new File(folder, position + ".part");
            try (FileOutputStream out = new FileOutputStream(part)) { out.write(jpeg); }
            if (part.renameTo(target)) { stored++; if (key.equals(indexKey)) index.put(position, jpeg); } else part.delete();
        }
        folder.setLastModified(System.currentTimeMillis());
        if (stored > 0) prune();
        return new JSONObject().put("stored", stored);
    }

    /** Nearest stored frame within a minute, or null. Indexed in memory per episode. */
    synchronized byte[] nearest(String key, long position) {
        if (!key.equals(indexKey)) {
            indexKey = key; index.clear();
            File[] files = folder(key).listFiles();
            if (files != null) for (File file : files) {
                String name = file.getName();
                if (!name.matches("[0-9]{1,6}\\.jpg") || file.length() > MAX_FRAME_BYTES) continue;
                try { index.put(Long.parseLong(name.substring(0, name.length() - 4)), Files.readAllBytes(file.toPath())); } catch (Exception ignored) { }
            }
        }
        java.util.Map.Entry<Long, byte[]> below = index.floorEntry(position), above = index.ceilingEntry(position);
        java.util.Map.Entry<Long, byte[]> best = below == null ? above : above == null ? below
            : position - below.getKey() <= above.getKey() - position ? below : above;
        return best == null || Math.abs(best.getKey() - position) > 60 ? null : best.getValue();
    }

    private File folder(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder();
            for (int i = 0; i < 12; i++) name.append(String.format(Locale.ROOT, "%02x", digest[i]));
            return new File(root, name.toString());
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private void prune() {
        File[] items = root.listFiles();
        if (items == null) return;
        long now = System.currentTimeMillis();
        List<File> kept = new ArrayList<>();
        for (File item : items) if (now - item.lastModified() > TTL_MS) delete(item); else kept.add(item);
        Collections.sort(kept, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        long total = 0;
        for (File item : kept) {
            File[] files = item.listFiles();
            if (files != null) for (File file : files) total += file.length();
            if (total > CAP_BYTES) delete(item);
        }
    }

    private static void delete(File item) {
        File[] files = item.listFiles();
        if (files != null) for (File file : files) file.delete();
        item.delete();
    }
}

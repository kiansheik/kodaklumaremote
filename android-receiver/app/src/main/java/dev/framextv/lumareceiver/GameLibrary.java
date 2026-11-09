package dev.framextv.lumareceiver;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Private, content-addressed ROMs. Partial transfers never appear in the library. */
final class GameLibrary {
    static final int MAX_ROM = 8 * 1024 * 1024, CHUNK = 128 * 1024, MAX_GAMES = 256;
    static final long MAX_LIBRARY = 128L * 1024 * 1024;
    private final File root;
    private final Map<String, Upload> uploads = new HashMap<>();
    private static final class Upload {
        String owner, name; int total; long touched;
        Upload(String owner, String name, int total) {
            this.owner = owner; this.name = name; this.total = total; touched = System.currentTimeMillis();
        }
    }

    GameLibrary(File root) {
        this.root = root;
        root.mkdirs();
        File[] stale = root.listFiles((dir, name) -> name.endsWith(".part"));
        if (stale != null) for (File file : stale) file.delete();
        // Publishing requires both files. A crash between the two renames must
        // not leave uncounted ROM bytes occupying the private storage forever.
        File[] incomplete = root.listFiles((dir, name) -> name.matches("[a-f0-9]{64}\\.(nes|json)"));
        if (incomplete != null) for (File file : incomplete) {
            String name = file.getName(), suffix = name.endsWith(".nes") ? ".json" : ".nes";
            if (!new File(root, name.substring(0, 64) + suffix).isFile()) file.delete();
        }
    }

    static String id(String value) throws ApiException {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw new ApiException("INVALID_GAME_ID");
        return value;
    }
    private File rom(String value) throws ApiException { return new File(root, id(value) + ".nes"); }
    private File metadata(String value) throws ApiException { return new File(root, id(value) + ".json"); }
    synchronized File file(String value) throws Exception {
        File file = rom(value);
        if (!file.isFile() || !metadata(value).isFile()) throw new ApiException("GAME_NOT_FOUND", 404);
        return file;
    }
    synchronized File saves(String value) throws Exception {
        File saves = new File(root, id(value) + "-saves");
        if (!saves.isDirectory() && !saves.mkdirs()) throw new ApiException("GAME_STORAGE_FAILED", 507);
        return saves;
    }
    synchronized JSONObject entry(String value) throws Exception {
        File file = file(value);
        byte[] raw = read(metadata(value), 2048);
        JSONObject entry = new JSONObject(new String(raw, StandardCharsets.UTF_8));
        if (!value.equals(entry.optString("id")) || entry.optLong("sizeBytes") != file.length())
            throw new ApiException("GAME_STORAGE_FAILED", 500);
        return entry;
    }
    synchronized JSONObject list() throws Exception {
        List<JSONObject> items = new ArrayList<>();
        File[] files = root.listFiles((dir, name) -> name.matches("[a-f0-9]{64}\\.json"));
        if (files != null) for (File file : files) {
            try { items.add(entry(file.getName().substring(0, 64))); }
            catch (Exception invalid) { /* A crash before commit is never a playable game. */ }
        }
        items.sort(Comparator.comparing(item -> item.optString("name").toLowerCase(Locale.ROOT)));
        return new JSONObject().put("games", new JSONArray(items)).put("systems", new JSONArray().put("nes"))
            .put("maxRomBytes", MAX_ROM).put("chunkBytes", CHUNK);
    }
    synchronized byte[] chunk(String value, int offset, int length) throws Exception {
        File file = file(value);
        if (offset < 0 || length < 1 || length > CHUNK || offset >= file.length()) throw new ApiException("INVALID_GAME_RANGE");
        byte[] bytes = new byte[(int) Math.min(length, file.length() - offset)];
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) { input.seek(offset); input.readFully(bytes); }
        return bytes;
    }
    synchronized JSONObject put(String value, String name, String owner, int offset, int total, byte[] bytes) throws Exception {
        id(value);
        if (offset < 0 || total < 16 || total > MAX_ROM || bytes == null || bytes.length < 1
                || bytes.length > CHUNK || (long) offset + bytes.length > total) throw new ApiException("INVALID_GAME_RANGE");
        if (rom(value).isFile() && metadata(value).isFile()) {
            JSONObject game = entry(value);
            if (game.getLong("sizeBytes") != total) throw new ApiException("GAME_HASH_MISMATCH");
            return new JSONObject().put("received", total).put("complete", true).put("game", game);
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Upload>> iterator = uploads.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Upload> old = iterator.next();
            if (now - old.getValue().touched > 600_000) { new File(root, old.getKey() + ".part").delete(); iterator.remove(); }
        }
        Upload upload = uploads.get(value);
        File partial = new File(root, value + ".part");
        if (upload != null && !upload.owner.equals(owner)) throw new ApiException("GAME_UPLOAD_BUSY", 409);
        if (offset == 0) {
            JSONArray games = list().getJSONArray("games");
            long size = 0;
            for (int i = 0; i < games.length(); i++) size += games.getJSONObject(i).getLong("sizeBytes");
            for (Map.Entry<String, Upload> active : uploads.entrySet()) if (!active.getKey().equals(value)) size += active.getValue().total;
            int otherUploads = uploads.size() - (upload == null ? 0 : 1);
            if (games.length() + otherUploads + 1 > MAX_GAMES || size + total > MAX_LIBRARY
                    || upload == null && uploads.size() >= 4) throw new ApiException("GAME_LIBRARY_FULL", 507);
            if (root.getUsableSpace() < total + 16L * 1024 * 1024) throw new ApiException("GAME_STORAGE_FULL", 507);
            upload = new Upload(owner, cleanName(name), total);
            try (FileOutputStream output = new FileOutputStream(partial)) { output.getFD().sync(); }
            uploads.put(value, upload);
        }
        if (upload == null || upload.total != total || partial.length() != offset) throw new ApiException("GAME_UPLOAD_OFFSET", 409);
        upload.touched = now;
        try (FileOutputStream output = new FileOutputStream(partial, true)) { output.write(bytes); output.getFD().sync(); }
        int received = offset + bytes.length;
        if (received < total) return new JSONObject().put("received", received).put("complete", false);
        File stagedMetadata = new File(root, value + ".json.part");
        boolean romPublished = false, committed = false;
        try {
            byte[] data = read(partial, MAX_ROM);
            validate(data);
            if (!value.equals(hash(data))) throw new ApiException("GAME_HASH_MISMATCH");
            JSONObject game = new JSONObject().put("id", value).put("name", upload.name)
                .put("system", "nes").put("sizeBytes", total);
            try (FileOutputStream output = new FileOutputStream(stagedMetadata)) {
                output.write(game.toString().getBytes(StandardCharsets.UTF_8)); output.getFD().sync();
            }
            romPublished = partial.renameTo(rom(value));
            if (!romPublished || !stagedMetadata.renameTo(metadata(value))) throw new ApiException("GAME_STORAGE_FAILED", 507);
            committed = true;
            return new JSONObject().put("received", total).put("complete", true).put("game", game);
        } finally {
            partial.delete(); stagedMetadata.delete(); uploads.remove(value);
            if (romPublished && !committed) rom(value).delete();
        }
    }
    static String cleanName(String name) {
        if (name == null) return "NES game";
        String clean = name.replaceAll("[\\p{Cntrl}/\\\\]", " ").trim();
        if (clean.toLowerCase(Locale.ROOT).endsWith(".nes")) clean = clean.substring(0, clean.length() - 4).trim();
        return clean.isEmpty() ? "NES game" : clean.substring(0, Math.min(clean.length(), 120));
    }
    static void validate(byte[] bytes) throws ApiException {
        if (bytes.length < 16 || bytes.length > MAX_ROM || bytes[0] != 'N' || bytes[1] != 'E' || bytes[2] != 'S'
                || bytes[3] != 0x1a) throw new ApiException("INVALID_NES_ROM");
        int format = bytes[7] & 12;
        if (format != 0 && format != 8) throw new ApiException("INVALID_NES_ROM");
        if ((bytes[7] & 3) != 0) throw new ApiException("NES_CONSOLE_NOT_SUPPORTED");
        // The native engine normalizes only the iNES-compatible NES 2.0 subset.
        // Multi-region timing and the standard controller marker are harmless;
        // submappers, explicit RAM, PAL/Dendy and other peripherals are not.
        if (format == 8 && (bytes[8] != 0 || bytes[9] != 0 || bytes[10] != 0 || bytes[11] != 0
                || bytes[12] != 0 && bytes[12] != 2 || bytes[13] != 0 || bytes[14] != 0
                || bytes[15] != 0 && bytes[15] != 1))
            throw new ApiException("NES2_EXTENSION_NOT_SUPPORTED");
        int prg = bytes[4] & 255, chr = bytes[5] & 255;
        long expected = 16L + ((bytes[6] & 4) != 0 ? 512 : 0) + prg * 16384L + chr * 8192L;
        if (prg == 0 || expected != bytes.length) throw new ApiException("INVALID_NES_ROM");
    }
    static String hash(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder(64);
        for (byte b : digest) result.append(String.format(Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
    private static byte[] read(File file, int max) throws Exception {
        if (file.length() > max) throw new ApiException("GAME_STORAGE_FAILED");
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = input.read(buffer)) != -1) {
                if (output.size() + n > max) throw new ApiException("GAME_STORAGE_FAILED");
                output.write(buffer, 0, n);
            }
            return output.toByteArray();
        }
    }
}

package dev.framextv.lumaremote;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/** Private, content-addressed ROM storage. Imports finish before a game becomes visible. */
public final class GamesLibrary {
    public static final int MAX_ROM_BYTES = 8 * 1024 * 1024, MAX_GAMES = 256;
    public static final long MAX_LIBRARY_BYTES = 128L * 1024 * 1024;
    private final File directory;

    public static final class GameException extends IOException {
        public final String code;
        GameException(String code, String message) { super(message); this.code = code; }
    }

    public GamesLibrary(File directory) {
        this.directory = directory;
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) {
            String filename = file.getName();
            if (filename.matches("import-.*\\.tmp")) file.delete();
            else if (filename.matches("[a-f0-9]{64}\\.(?:nes|json)")) {
                String sibling = filename.substring(0, 64) + (filename.endsWith(".nes") ? ".json" : ".nes");
                if (!new File(directory, sibling).isFile()) file.delete();
            }
        }
    }
    static String id(String value) throws GameException {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw error("INVALID_GAME", "Choose a saved NES game.");
        return value;
    }
    static String name(String value) {
        String clean = value == null ? "NES game" : value.replaceAll("[\\p{Cntrl}\\u007f-\\u009f\\u202a-\\u202e\\u2066-\\u2069/\\\\]", " ").trim();
        clean = clean.replaceFirst("(?i)\\.nes$", "").replace('_', ' ').trim();
        return clean.isEmpty() ? "NES game" : clean.substring(0, Math.min(120, clean.length()));
    }
    static GameException error(String code, String message) { return new GameException(code, message); }
    static byte[] readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[16384]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (out.size() + count > limit) throw error("GAME_TOO_LARGE", "Choose an NES ROM smaller than 8 MiB.");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }
    public static void validate(byte[] bytes) throws GameException {
        if (bytes == null || bytes.length < 16 || bytes.length > MAX_ROM_BYTES
                || bytes[0] != 'N' || bytes[1] != 'E' || bytes[2] != 'S' || bytes[3] != 0x1a)
            throw error("INVALID_ROM", "Choose an uncompressed .nes ROM file.");
        int format = bytes[7] & 12;
        if (format != 0 && format != 8) throw error("INVALID_ROM", "This NES header format is not supported.");
        if ((bytes[7] & 3) != 0 || format == 8 && (bytes[8] != 0 || bytes[9] != 0 || bytes[10] != 0 || bytes[11] != 0
                || bytes[12] != 0 && bytes[12] != 2 || bytes[13] != 0 || bytes[14] != 0 || bytes[15] != 0 && bytes[15] != 1))
            throw error("INVALID_ROM", "This NES cartridge format is not supported yet.");
        long prg = (bytes[4] & 255) * 16384L, chr = (bytes[5] & 255) * 8192L;
        long required = 16 + ((bytes[6] & 4) != 0 ? 512 : 0) + prg + chr;
        if (prg == 0 || required != bytes.length || required > MAX_ROM_BYTES)
            throw error("INVALID_ROM", "This NES ROM is incomplete or has an invalid header.");
    }
    public static String sha256(byte[] bytes) throws IOException {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
            return hex.toString();
        } catch (Exception unavailable) { throw new IOException("SHA-256 unavailable", unavailable); }
    }
    public synchronized JSONObject add(InputStream input, String displayName, String expectedId) throws Exception {
        byte[] bytes = readBounded(input, MAX_ROM_BYTES);
        validate(bytes); String hash = sha256(bytes);
        if (expectedId != null && !id(expectedId).equals(hash)) throw error("GAME_HASH_MISMATCH", "The transferred ROM did not match. Sync again.");
        if (!directory.isDirectory() && !directory.mkdirs()) throw error("GAME_STORAGE", "Could not save the game on this phone.");
        File rom = new File(directory, hash + ".nes"), metadata = new File(directory, hash + ".json");
        if (rom.isFile() && metadata.isFile()) {
            try { bytes(hash); return metadata(hash); }
            catch (Exception damaged) { /* Reimporting the original repairs a damaged copy. */ }
        }
        File[] stored = directory.listFiles((dir, filename) -> filename.matches("[a-f0-9]{64}\\.nes"));
        long total = 0; int count = 0;
        if (stored != null) for (File existing : stored) if (!existing.equals(rom)) { total += existing.length(); count++; }
        if (count >= MAX_GAMES || total + bytes.length > MAX_LIBRARY_BYTES)
            throw error("GAME_LIBRARY_FULL", "The games library is full (256 games or 128 MiB).");
        JSONObject game = new JSONObject().put("id", hash).put("name", name(displayName)).put("system", "nes").put("sizeBytes", bytes.length);
        atomicWrite(rom, bytes);
        try { atomicWrite(metadata, game.toString().getBytes(StandardCharsets.UTF_8)); }
        catch (IOException failed) { rom.delete(); throw failed; }
        return game;
    }
    private static void atomicWrite(File destination, byte[] bytes) throws IOException {
        File temp = File.createTempFile("import-", ".tmp", destination.getParentFile());
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) { out.write(bytes); out.getFD().sync(); }
            if (!temp.renameTo(destination)) throw new IOException("Could not finish game import");
        } finally { temp.delete(); }
    }
    public synchronized JSONArray list() throws Exception {
        JSONArray result = new JSONArray(); File[] files = directory.listFiles((dir, filename) -> filename.matches("[a-f0-9]{64}\\.json"));
        if (files == null) return result;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            if (result.length() >= MAX_GAMES) break;
            try { result.put(metadata(file.getName().substring(0, 64))); }
            catch (Exception corrupt) { /* An interrupted/corrupt entry is never advertised or uploaded. */ }
        }
        return result;
    }
    public synchronized JSONObject metadata(String hash) throws Exception {
        id(hash); File rom = new File(directory, hash + ".nes"); JSONObject game;
        try (InputStream in = new FileInputStream(new File(directory, hash + ".json"))) {
            game = new JSONObject(new String(readBounded(in, 2048), StandardCharsets.UTF_8));
        }
        if (!hash.equals(game.optString("id")) || !"nes".equals(game.optString("system")) || !rom.isFile()
                || rom.length() < 16 || rom.length() > MAX_ROM_BYTES || rom.length() != game.optLong("sizeBytes"))
            throw error("INVALID_GAME", "The saved game is incomplete. Import it again.");
        return new JSONObject().put("id", hash).put("name", name(game.optString("name")))
                .put("system", "nes").put("sizeBytes", rom.length());
    }
    public synchronized byte[] bytes(String hash) throws Exception {
        metadata(hash); byte[] bytes;
        try (InputStream in = new FileInputStream(new File(directory, id(hash) + ".nes"))) { bytes = readBounded(in, MAX_ROM_BYTES); }
        validate(bytes);
        if (!hash.equals(sha256(bytes))) throw error("GAME_HASH_MISMATCH", "The saved ROM is damaged. Import it again.");
        return bytes;
    }
}

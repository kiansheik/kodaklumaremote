package dev.framextv.lumareceiver;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import org.json.JSONObject;

public class GameLibraryTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static byte[] rom(int banks) {
        byte[] bytes = new byte[16 + banks * 16384];
        bytes[0] = 'N'; bytes[1] = 'E'; bytes[2] = 'S'; bytes[3] = 26; bytes[4] = (byte) banks;
        return bytes;
    }
    private interface Failing { void run() throws Exception; }
    private static void rejects(String code, Failing work) throws Exception {
        try { work.run(); fail("accepted " + code); }
        catch (ApiException failure) { assertEquals(code, failure.code); }
    }
    @Test public void verifiesHashAndCommitsOnlyCompleteRomAcrossRestart() throws Exception {
        File dir = temp.newFolder(); GameLibrary library = new GameLibrary(dir);
        byte[] bytes = rom(16); String id = GameLibrary.hash(bytes);
        JSONObject part = library.put(id, "My game.nes", "phone", 0, bytes.length, Arrays.copyOf(bytes, GameLibrary.CHUNK));
        assertFalse(part.getBoolean("complete")); assertEquals(0, library.list().getJSONArray("games").length());
        for (int offset = GameLibrary.CHUNK; offset < bytes.length; offset += GameLibrary.CHUNK)
            library.put(id, "ignored", "phone", offset, bytes.length, Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + GameLibrary.CHUNK)));
        assertEquals("My game", library.entry(id).getString("name"));
        GameLibrary restarted = new GameLibrary(dir);
        assertEquals(1, restarted.list().getJSONArray("games").length());
        assertArrayEquals(Arrays.copyOfRange(bytes, 100, 300), restarted.chunk(id, 100, 200));
        assertTrue(restarted.put(id, "rename not allowed", "other", 0, bytes.length, new byte[]{1}).getBoolean("complete"));
        assertEquals("My game", restarted.entry(id).getString("name"));
    }
    @Test public void rejectsWrongHashWithoutPublishing() throws Exception {
        GameLibrary library = new GameLibrary(temp.newFolder()); byte[] bytes = rom(1);
        String wrong = GameLibrary.hash(new byte[]{1});
        rejects("GAME_HASH_MISMATCH", () -> library.put(wrong, "bad", "phone", 0, bytes.length, bytes));
        assertEquals(0, library.list().getJSONArray("games").length());
        rejects("GAME_NOT_FOUND", () -> library.file(wrong));
    }
    @Test public void handlesConcurrentClientsAndMissingOutOfOrderChunks() throws Exception {
        GameLibrary library = new GameLibrary(temp.newFolder()); byte[] bytes = rom(16); String id = GameLibrary.hash(bytes);
        byte[] chunk = Arrays.copyOf(bytes, 128);
        rejects("GAME_UPLOAD_OFFSET", () -> library.put(id, "a", "first", 128, bytes.length, chunk));
        library.put(id, "a", "first", 0, bytes.length, chunk);
        rejects("GAME_UPLOAD_BUSY", () -> library.put(id, "a", "second", 0, bytes.length, chunk));
        rejects("GAME_UPLOAD_OFFSET", () -> library.put(id, "a", "first", 256, bytes.length, chunk));
        library.put(id, "a", "first", 0, bytes.length, chunk); // retry starts cleanly
    }
    @Test public void rejectsPathsTruncationAndUnsupportedNes2ButAllowsCompatibleNes2() throws Exception {
        GameLibrary library = new GameLibrary(temp.newFolder());
        rejects("INVALID_GAME_ID", () -> library.file("../private"));
        byte[] bytes = rom(1);
        rejects("INVALID_NES_ROM", () -> GameLibrary.validate(Arrays.copyOf(bytes, bytes.length - 1)));
        bytes[7] = 8; GameLibrary.validate(bytes);
        bytes[9] = 1;
        rejects("NES2_EXTENSION_NOT_SUPPORTED", () -> GameLibrary.validate(bytes));
        bytes[9] = 0; bytes[7] = 1;
        rejects("NES_CONSOLE_NOT_SUPPORTED", () -> GameLibrary.validate(bytes));
        assertEquals("a b c", GameLibrary.cleanName("a/b\\c.nes"));
    }
    @Test public void restartDiscardsPartialAndRequiresNewUpload() throws Exception {
        File dir = temp.newFolder(); GameLibrary library = new GameLibrary(dir);
        byte[] bytes = rom(1); String id = GameLibrary.hash(bytes);
        library.put(id, "a", "phone", 0, bytes.length, Arrays.copyOf(bytes, 128));
        library = new GameLibrary(dir); final GameLibrary restored = library;
        assertFalse(new File(dir, id + ".part").exists());
        assertEquals(0, library.list().getJSONArray("games").length());
        rejects("GAME_UPLOAD_OFFSET", () -> restored.put(id, "a", "phone", 128, bytes.length, Arrays.copyOfRange(bytes, 128, bytes.length)));
        assertTrue(library.put(id, "a", "phone", 0, bytes.length, bytes).getBoolean("complete"));
        rejects("INVALID_GAME_RANGE", () -> restored.chunk(id, bytes.length, 1));
    }
    @Test public void boundsUploadSlotsAndChunkSize() throws Exception {
        GameLibrary library = new GameLibrary(temp.newFolder());
        for (int i = 0; i < 4; i++) {
            byte[] bytes = rom(1); bytes[16] = (byte) i;
            library.put(GameLibrary.hash(bytes), "a", "phone", 0, bytes.length, Arrays.copyOf(bytes, 128));
        }
        byte[] fifth = rom(1); fifth[16] = 99;
        rejects("GAME_LIBRARY_FULL", () -> library.put(GameLibrary.hash(fifth), "a", "phone", 0, fifth.length, Arrays.copyOf(fifth, 128)));
        byte[] large = rom(16);
        rejects("INVALID_GAME_RANGE", () -> library.put(GameLibrary.hash(large), "a", "phone", 0, large.length, new byte[GameLibrary.CHUNK + 1]));
    }
    @Test public void onlyNormalizesSupportedNes2SemanticsIncludingTheSuppliedHeaderShape() throws Exception {
        byte[] bytes = rom(1); bytes[7] = 8; bytes[12] = 2; bytes[15] = 1;
        GameLibrary.validate(bytes);
        for (int field : new int[]{8, 9, 10, 11, 12, 13, 14, 15}) {
            byte[] invalid = bytes.clone(); invalid[field] = (byte) (field == 8 ? 16 : field == 15 ? 2 : 1);
            rejects("NES2_EXTENSION_NOT_SUPPORTED", () -> GameLibrary.validate(invalid));
        }
        for (int format : new int[]{4, 12}) {
            byte[] invalid = bytes.clone(); invalid[7] = (byte) format;
            rejects("INVALID_NES_ROM", () -> GameLibrary.validate(invalid));
        }
    }
    @Test public void failedMetadataCommitRemovesPublishedRomAndTemporaryFiles() throws Exception {
        File dir = temp.newFolder(); GameLibrary library = new GameLibrary(dir);
        byte[] bytes = rom(1); String id = GameLibrary.hash(bytes);
        assertTrue(new File(dir, id + ".json").mkdir()); // Simulate a failed metadata publish.
        assertTrue(new File(dir, id + ".json/occupied").createNewFile());
        rejects("GAME_STORAGE_FAILED", () -> library.put(id, "a", "phone", 0, bytes.length, bytes));
        assertFalse(new File(dir, id + ".nes").exists());
        assertFalse(new File(dir, id + ".part").exists());
        assertFalse(new File(dir, id + ".json.part").exists());
        assertEquals(0, library.list().getJSONArray("games").length());
    }
    @Test public void restartCleansIncompleteCommitButRetainsCommittedGames() throws Exception {
        File dir = temp.newFolder(); GameLibrary library = new GameLibrary(dir);
        byte[] bytes = rom(1); String id = GameLibrary.hash(bytes);
        library.put(id, "a", "phone", 0, bytes.length, bytes);
        String orphan = GameLibrary.hash(new byte[]{42});
        try (FileOutputStream output = new FileOutputStream(new File(dir, orphan + ".nes"))) { output.write(bytes); }
        GameLibrary restarted = new GameLibrary(dir);
        assertFalse(new File(dir, orphan + ".nes").exists());
        assertEquals(1, restarted.list().getJSONArray("games").length());
    }
    @Test public void restartingTheLastReservedUploadDoesNotExceedEntryLimit() throws Exception {
        File dir = temp.newFolder(); GameLibrary library = new GameLibrary(dir);
        byte[] bytes = rom(1);
        // Populate already committed metadata/files directly to isolate the quota boundary.
        for (int i = 0; i < GameLibrary.MAX_GAMES - 1; i++) {
            String id = GameLibrary.hash(new byte[]{(byte) i});
            try (FileOutputStream output = new FileOutputStream(new File(dir, id + ".nes"))) { output.write(bytes); }
            JSONObject entry = new JSONObject().put("id", id).put("name", "game").put("system", "nes").put("sizeBytes", bytes.length);
            try (FileOutputStream output = new FileOutputStream(new File(dir, id + ".json"))) {
                output.write(entry.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        String id = GameLibrary.hash(bytes);
        library.put(id, "last", "phone", 0, bytes.length, Arrays.copyOf(bytes, 128));
        library.put(id, "retry", "phone", 0, bytes.length, Arrays.copyOf(bytes, 128));
        assertTrue(library.put(id, "last", "phone", 128, bytes.length, Arrays.copyOfRange(bytes, 128, bytes.length)).getBoolean("complete"));
        assertEquals(GameLibrary.MAX_GAMES, library.list().getJSONArray("games").length());
    }
}

package dev.framextv.lumaremote;

import org.json.JSONObject;
import java.io.*;
import java.nio.file.*;
import java.util.Arrays;

public final class GamesLibraryTest {
    static byte[] rom(int prg, int chr, int salt) {
        byte[] bytes = new byte[16 + prg * 16384 + chr * 8192];
        bytes[0] = 'N'; bytes[1] = 'E'; bytes[2] = 'S'; bytes[3] = 0x1a;
        bytes[4] = (byte) prg; bytes[5] = (byte) chr; Arrays.fill(bytes, 16, bytes.length, (byte) salt);
        return bytes;
    }
    interface Action { void run() throws Exception; }
    static void rejects(String code, Action action) throws Exception {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (GamesLibrary.GameException error) { check(code.equals(error.code), "Wrong rejection: " + error.code); }
    }
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]); root.mkdirs(); GamesLibrary library = new GamesLibrary(root);
        byte[] bytes = rom(2, 1, 1); String hash = GamesLibrary.sha256(bytes);
        JSONObject game = library.add(new ByteArrayInputStream(bytes), "super_mario_tupi.nes", null);
        check(game.getString("id").equals(hash), "Content identity");
        check(game.getString("name").equals("super mario tupi"), "Name normalization");
        check(Arrays.equals(bytes, library.bytes(hash)), "ROM bytes changed");
        library.add(new ByteArrayInputStream(bytes), "Duplicate.nes", hash);
        check(library.list().length() == 1, "Duplicate game added");
        check(new GamesLibrary(root).list().length() == 1, "Library did not persist");
        rejects("GAME_HASH_MISMATCH", () -> library.add(new ByteArrayInputStream(bytes), "Wrong", "0".repeat(64)));
        rejects("INVALID_GAME", () -> library.bytes("../outside"));
        rejects("INVALID_ROM", () -> library.add(new ByteArrayInputStream(Arrays.copyOf(bytes, bytes.length - 1)), "Truncated", null));
        rejects("INVALID_ROM", () -> GamesLibrary.validate(Arrays.copyOf(bytes, bytes.length + 1)));
        rejects("INVALID_ROM", () -> GamesLibrary.validate(new byte[16]));
        byte[] nes2 = bytes.clone(); nes2[7] = 8; nes2[12] = 2; nes2[15] = 1;
        GamesLibrary.validate(nes2);
        for (int index : new int[]{8, 9, 10, 11, 12, 13, 14, 15}) {
            byte[] unsupported = nes2.clone(); unsupported[index] = (byte) (index == 15 ? 2 : index == 8 ? 16 : 1);
            rejects("INVALID_ROM", () -> GamesLibrary.validate(unsupported));
        }
        byte[] trainer = new byte[bytes.length + 512]; System.arraycopy(bytes, 0, trainer, 0, 16);
        trainer[6] = 4; GamesLibrary.validate(trainer);
        byte[] damaged = bytes.clone(); damaged[20]++;
        Files.write(new File(root, hash + ".nes").toPath(), damaged);
        rejects("GAME_HASH_MISMATCH", () -> library.bytes(hash));
        library.add(new ByteArrayInputStream(bytes), "Repaired.nes", hash);
        check(Arrays.equals(library.bytes(hash), bytes), "Reimport did not repair damaged data");
        InputStream oversized = new InputStream() {
            int left = GamesLibrary.MAX_ROM_BYTES + 1;
            @Override public int read() { return left-- > 0 ? 0 : -1; }
            @Override public int read(byte[] output, int offset, int length) {
                if (left <= 0) return -1; int size = Math.min(length, left); left -= size; return size;
            }
        };
        rejects("GAME_TOO_LARGE", () -> library.add(oversized, "Too large", null));
        check(library.list().length() == 1, "Rejected input became visible");
        Files.write(new File(root, "import-interrupted.tmp").toPath(), new byte[]{1});
        Files.write(new File(root, "0".repeat(64) + ".nes").toPath(), bytes);
        Files.writeString(new File(root, "1".repeat(64) + ".json").toPath(), "{}");
        new GamesLibrary(root);
        check(!new File(root, "0".repeat(64) + ".nes").exists() && !new File(root, "1".repeat(64) + ".json").exists(), "Interrupted paired commit not cleaned");
        for (File file : root.listFiles()) check(!file.getName().endsWith(".tmp"), "Temporary file leaked");
        System.out.println("Games library: import, persistence, deduplication, exact headers, hash integrity and repair passed.");
    }
}

package dev.framextv.lumareceiver;

/** Nintendo output report 0x01 / subcommand 0x30; no input-mode or pairing changes. */
final class NintendoPlayerLights {
    static String reportHex(int player, int counter) {
        if (player < 1 || player > 4 || counter < 0 || counter > 15)
            throw new IllegalArgumentException("Invalid player light request");
        // Counter, neutral left/right rumble, player-light command, steady LEDs.
        // Nintendo player 1/2/3/4 patterns light the first 1/2/3/4 green indicators.
        byte[] report = { 1, (byte) counter, 0, 1, 0x40, 0x40, 0, 1, 0x40, 0x40,
            0x30, (byte) ((1 << player) - 1) };
        char[] hex = new char[report.length * 2];
        final char[] digits = "0123456789ABCDEF".toCharArray();
        for (int i = 0; i < report.length; i++) {
            hex[i * 2] = digits[(report[i] & 255) >>> 4]; hex[i * 2 + 1] = digits[report[i] & 15];
        }
        return new String(hex);
    }
}

package dev.framextv.lumaremote;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Pure JVM checks: synthetic QR symbols only, no camera or real pairing codes. */
public final class MatterQrDecoderTest {
    private static final String CODE = "MT:000000000000000000000";
    private static int passed;

    public static void main(String[] args) throws Exception {
        checkPayloadBoundary();
        checkStridedCrop();
        checkTruncatedPlane();
        checkRotations();
        checkInverted();
        checkDownsample();
        checkUnrelatedQr();
        System.out.println("Matter QR decoder: " + passed + " checks passed.");
    }

    private static void checkPayloadBoundary() {
        require(CODE.equals(MatterQrDecoder.setupCode("  " + CODE + "\n")), "trim setup payload");
        for (String value : new String[] {"https://example.test/", "MT:short", "MT:lowercase00000000", "MT:1234567890\nBAD", "MT:" + "0".repeat(1024)}) {
            require(MatterQrDecoder.setupCode(value) == null, "reject non-Matter/oversize payload");
        }
        require(MatterQrDecoder.setupCode(null) == null, "reject null payload"); passed++;
    }

    private static void checkStridedCrop() {
        int width = 5, height = 3, stride = 17, pixel = 2, offset = 4;
        ByteBuffer plane = ByteBuffer.allocate(offset + stride * height);
        Arrays.fill(plane.array(), (byte) 99);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) plane.put(offset + y * stride + x * pixel, (byte) (y * 10 + x));
        plane.position(offset);
        MatterQrDecoder.GraySource copied = MatterQrDecoder.copyPlane(plane.asReadOnlyBuffer(), stride, pixel, width, height, 1, 1, 3, 2);
        require(Arrays.equals(copied.getMatrix(), new byte[] {11, 12, 13, 21, 22, 23}), "row/pixel stride and crop");
        require(plane.position() == offset, "input buffer position preserved");
        MatterQrDecoder.GraySource rotated = copied.rotate();
        require(rotated.getWidth() == 2 && rotated.getHeight() == 3, "non-square rotation dimensions");
        require(Arrays.equals(rotated.getMatrix(), new byte[] {21, 11, 22, 12, 23, 13}), "non-square rotation pixels"); passed++;
    }

    private static void checkTruncatedPlane() {
        expectInvalid(() -> MatterQrDecoder.copyPlane(ByteBuffer.allocate(9), 4, 1, 4, 3, 0, 0, 4, 3));
        expectInvalid(() -> MatterQrDecoder.copyPlane(ByteBuffer.allocate(40), 4, 1, 4, 3, 3, 0, 2, 2));
        expectInvalid(() -> MatterQrDecoder.copyPlane(ByteBuffer.allocate(40), 0, 1, 4, 3, 0, 0, 4, 3)); passed++;
    }

    private static void checkRotations() throws Exception {
        MatterQrDecoder decoder = new MatterQrDecoder();
        MatterQrDecoder.GraySource source = symbol(CODE, 380, false);
        for (int rotation = 0; rotation < 4; rotation++) {
            ByteBuffer padded = padded(source, 2, 31, 7);
            int rowStride = source.getWidth() * 2 + 31;
            require(CODE.equals(decoder.decode(padded, rowStride, 2, source.getWidth(), source.getHeight(), 0, 0, source.getWidth(), source.getHeight())), "decode padded QR rotation " + rotation);
            source = source.rotate();
        }
        passed++;
    }

    private static void checkInverted() throws Exception {
        MatterQrDecoder.GraySource source = symbol(CODE, 400, true);
        require(CODE.equals(new MatterQrDecoder().decode(ByteBuffer.wrap(source.getMatrix()), source.getWidth(), 1,
                source.getWidth(), source.getHeight(), 0, 0, source.getWidth(), source.getHeight())), "inverted QR"); passed++;
    }

    private static void checkDownsample() throws Exception {
        MatterQrDecoder.GraySource source = symbol(CODE, 2048, false);
        ByteBuffer buffer = ByteBuffer.wrap(source.getMatrix());
        MatterQrDecoder.GraySource sampled = MatterQrDecoder.copyPlane(buffer, 2048, 1, 2048, 2048, 0, 0, 2048, 2048);
        require(sampled.getWidth() <= 960 && sampled.getHeight() <= 960, "bounded decode size");
        require(CODE.equals(new MatterQrDecoder().decode(buffer, 2048, 1, 2048, 2048, 0, 0, 2048, 2048)), "decode downsampled QR"); passed++;
    }

    private static void checkUnrelatedQr() throws Exception {
        MatterQrDecoder decoder = new MatterQrDecoder();
        MatterQrDecoder.GraySource source = symbol("https://example.test/not-a-setup-code", 300, false);
        require(decoder.decode(ByteBuffer.wrap(source.getMatrix()), 300, 1, 300, 300, 0, 0, 300, 300) == null, "reject unrelated QR");
        source = symbol(CODE, 300, false);
        require(CODE.equals(decoder.decode(ByteBuffer.wrap(source.getMatrix()), 300, 1, 300, 300, 0, 0, 300, 300)), "reader recovers after rejected QR"); passed++;
    }

    private static MatterQrDecoder.GraySource symbol(String value, int side, boolean invert) throws Exception {
        BitMatrix matrix = new QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, side, side);
        byte[] pixels = new byte[side * side];
        for (int y = 0; y < side; y++) for (int x = 0; x < side; x++) pixels[y * side + x] = (byte) ((matrix.get(x, y) ^ invert) ? 0 : 255);
        return new MatterQrDecoder.GraySource(pixels, side, side);
    }

    private static ByteBuffer padded(MatterQrDecoder.GraySource source, int pixelStride, int padding, int offset) {
        int rowStride = source.getWidth() * pixelStride + padding;
        ByteBuffer plane = ByteBuffer.allocate(offset + rowStride * source.getHeight());
        Arrays.fill(plane.array(), (byte) 111);
        for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++) {
            plane.put(offset + y * rowStride + x * pixelStride, source.getMatrix()[y * source.getWidth() + x]);
        }
        plane.position(offset); return plane.asReadOnlyBuffer();
    }

    private static void expectInvalid(Runnable action) {
        try { action.run(); throw new AssertionError("Expected invalid camera plane"); }
        catch (IllegalArgumentException expected) { }
    }
    private static void require(boolean value, String label) { if (!value) throw new AssertionError(label); }
}

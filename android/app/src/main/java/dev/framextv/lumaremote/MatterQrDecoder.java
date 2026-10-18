package dev.framextv.lumaremote;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.Map;

/** In-memory, row-stride-safe camera luminance decoding. Never retains image data. */
final class MatterQrDecoder {
    private static final int MAX_SIDE = 960;
    private final QRCodeReader reader = new QRCodeReader();
    private final Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);

    MatterQrDecoder() { hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE); }

    String decode(ByteBuffer plane, int rowStride, int pixelStride, int width, int height,
            int left, int top, int cropWidth, int cropHeight) {
        return decode(copyPlane(plane, rowStride, pixelStride, width, height, left, top, cropWidth, cropHeight));
    }

    String decode(GraySource source) {
        // QR finder patterns normally tolerate rotation. Explicit rotations also
        // cover camera sensor/display combinations and older detector edge cases.
        for (int rotation = 0; rotation < 4; rotation++) {
            String payload = read(source);
            if (payload == null) payload = read(source.invert());
            if (payload != null) return payload;
            source = source.rotate();
        }
        return null;
    }

    private String read(LuminanceSource source) {
        try {
            Result result = reader.decode(new BinaryBitmap(new HybridBinarizer(source)), hints);
            return setupCode(result.getText());
        } catch (ReaderException | IllegalArgumentException ignored) { return null; }
        finally { reader.reset(); }
    }

    /** The commissioner performs full Matter payload validation before pairing. */
    static String setupCode(String text) {
        if (text == null) return null;
        String value = text.trim();
        return value.length() >= 13 && value.length() <= 1024 && value.matches("MT:[0-9A-Z.\\-]+") ? value : null;
    }

    static GraySource copyPlane(ByteBuffer input, int rowStride, int pixelStride, int width, int height,
            int left, int top, int cropWidth, int cropHeight) {
        if (input == null || width < 1 || height < 1 || width > 8192 || height > 8192 || rowStride < 1 || pixelStride < 1
                || left < 0 || top < 0 || cropWidth < 1 || cropHeight < 1 || (long) left + cropWidth > width
                || (long) top + cropHeight > height) throw new IllegalArgumentException("Invalid camera plane");
        ByteBuffer plane = input.duplicate(); int offset = plane.position();
        long last = (long) offset + (long) (top + cropHeight - 1) * rowStride + (long) (left + cropWidth - 1) * pixelStride;
        if (last >= plane.limit()) throw new IllegalArgumentException("Truncated camera plane");
        int step = Math.max(1, (Math.max(cropWidth, cropHeight) + MAX_SIDE - 1) / MAX_SIDE);
        int sampledWidth = (cropWidth + step - 1) / step, sampledHeight = (cropHeight + step - 1) / step;
        byte[] luminance = new byte[sampledWidth * sampledHeight];
        for (int y = 0; y < sampledHeight; y++) {
            int sourceRow = offset + (top + y * step) * rowStride + left * pixelStride;
            for (int x = 0; x < sampledWidth; x++) luminance[y * sampledWidth + x] = plane.get(sourceRow + x * step * pixelStride);
        }
        return new GraySource(luminance, sampledWidth, sampledHeight);
    }

    static final class GraySource extends LuminanceSource {
        private final byte[] luminance;
        GraySource(byte[] luminance, int width, int height) { super(width, height); this.luminance = luminance; }
        @Override public byte[] getRow(int y, byte[] row) {
            if (y < 0 || y >= getHeight()) throw new IllegalArgumentException("Invalid camera row");
            if (row == null || row.length < getWidth()) row = new byte[getWidth()];
            System.arraycopy(luminance, y * getWidth(), row, 0, getWidth()); return row;
        }
        @Override public byte[] getMatrix() { return luminance; }
        GraySource rotate() {
            int width = getWidth(), height = getHeight(); byte[] rotated = new byte[luminance.length];
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) rotated[x * height + height - 1 - y] = luminance[y * width + x];
            return new GraySource(rotated, height, width);
        }
    }
}

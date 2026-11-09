package dev.framextv.lumareceiver;

import java.io.IOException;

/** Small JNI boundary; accessed only under the owning NesGameView's core lock. */
final class NesNative {
    static { System.loadLibrary("luma_nes"); }
    static native long create(byte[] rom) throws IOException;
    static native int step(long handle, int buttons, int[] pixels, short[] audio) throws IOException;
    static native byte[] save(long handle, boolean battery) throws IOException;
    static native void restore(long handle, byte[] state, boolean battery) throws IOException;
    static native void destroy(long handle);
    private NesNative() { }
}

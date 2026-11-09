package dev.framextv.lumareceiver;

/** One long action per physical hold, with no short action after firing/cancellation. */
final class HomeHoldState {
    static final long HOLD_MS = 3000;
    private int device = -1;
    private long started;
    private boolean fired;
    boolean down(int id, int repeat, long now) {
        if (device != -1 || repeat != 0) return false;
        device = id; started = now; fired = false; return true;
    }
    boolean fire(long now) {
        if (device == -1 || fired || now - started < HOLD_MS) return false;
        fired = true; return true;
    }
    boolean up(int id, boolean cancelled) {
        if (device != id || device == -1) return false;
        boolean shortPress = !fired && !cancelled; device = -1; fired = false; return shortPress;
    }
    void cancel(int id) { if (device == id) { device = -1; fired = false; } }
    int device() { return device; }
}

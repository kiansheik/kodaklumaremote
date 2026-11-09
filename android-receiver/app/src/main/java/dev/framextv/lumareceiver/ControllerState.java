package dev.framextv.lumareceiver;

import java.util.HashMap;
import java.util.Map;

/** Pure input state: independent keys/axes and devices cannot release one another. */
final class ControllerState {
    static final int A = 1, B = 2, SELECT = 4, START = 8;
    static final int UP = 16, DOWN = 32, LEFT = 64, RIGHT = 128;
    static final int DIRECTIONS = UP | DOWN | LEFT | RIGHT;
    private static final class Device {
        final Map<Integer, Integer> keys = new HashMap<>();
        int x, y, hatX, hatY, mapped;
    }
    private final Map<Integer, Device> devices = new HashMap<>();

    int key(int deviceId, int keyCode, int bit, boolean down) {
        Device device = devices.computeIfAbsent(deviceId, ignored -> new Device());
        if (down) device.keys.put(keyCode, bit); else device.keys.remove(keyCode);
        return buttons();
    }

    int axes(int deviceId, float x, float y, float hatX, float hatY, float flatX, float flatY) {
        Device device = devices.computeIfAbsent(deviceId, ignored -> new Device());
        device.x = axis(x, device.x, flatX);
        device.y = axis(y, device.y, flatY);
        device.hatX = axis(hatX, device.hatX, 0);
        device.hatY = axis(hatY, device.hatY, 0);
        return buttons();
    }

    int remove(int deviceId) { devices.remove(deviceId); return buttons(); }
    void clear() { devices.clear(); }

    int mapped(int deviceId, int buttons) {
        devices.computeIfAbsent(deviceId, ignored -> new Device()).mapped = buttons;
        return buttons();
    }

    int buttons() {
        int result = 0;
        for (Device device : devices.values()) {
            for (int bits : device.keys.values()) result |= bits;
            result |= device.mapped | direction(device.x, device.y) | direction(device.hatX, device.hatY);
        }
        return neutralize(result);
    }

    static int neutralize(int result) {
        // NES games do not expect physically impossible opposing directions.
        if ((result & (LEFT | RIGHT)) == (LEFT | RIGHT)) result &= ~(LEFT | RIGHT);
        if ((result & (UP | DOWN)) == (UP | DOWN)) result &= ~(UP | DOWN);
        return result;
    }

    private static int direction(int x, int y) {
        return (x < 0 ? LEFT : x > 0 ? RIGHT : 0) | (y < 0 ? UP : y > 0 ? DOWN : 0);
    }

    static int axis(float value, int previous, float flat) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return 0;
        float enter = Math.max(0.5f, Math.min(0.9f, flat));
        float leave = Math.max(0.25f, enter - 0.2f);
        if (value >= enter) return 1;
        if (value <= -enter) return -1;
        if (previous == 1 && value > leave) return 1;
        if (previous == -1 && value < -leave) return -1;
        return 0;
    }
}

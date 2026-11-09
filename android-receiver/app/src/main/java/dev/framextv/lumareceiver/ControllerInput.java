package dev.framextv.lumareceiver;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Activity-local input; optional player-light output is isolated in ControllerLeds. */
final class ControllerInput implements InputManager.InputDeviceListener {
    static void prepareNavigation(android.app.Activity activity, int action) {
        if (action != KeyEvent.ACTION_DOWN || !activity.getWindow().getDecorView().isInTouchMode()) return;
        android.view.View focused = activity.getCurrentFocus();
        (focused == null ? activity.getWindow().getDecorView() : focused).requestFocusFromTouch();
    }
    interface Listener {
        void onButtonsChanged(int buttons);
        default void onAnalogInput(float x, float y) { }
        default void onGameInput(int heldButtons, int freshPresses) { onButtonsChanged(heldButtons); }
        default void onRetriggersCancelled() { }
        void onNavigationKey(int keyCode, int action, int repeatCount);
        default void onMenuRequested() { }
        default void onLoadCheckpointRequested() { }
    }
    private final Listener listener;
    private final InputManager manager;
    private final android.media.AudioManager audio;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ControllerState state = new ControllerState();
    private final ControllerProfileStore profiles;
    private final ControllerPlayerStore players;
    private final ReceiverApp app;
    private final Map<Integer, DeviceState> devices = new HashMap<>();
    private static final class DeviceState {
        final ControllerProfile profile;
        final int player;
        final Set<Integer> keys = new HashSet<>();
        final Map<Integer, Float> axes = new HashMap<>();
        int logical, lx, ly, rx, ry, volumeActions;
        boolean menu, awaitingNeutral;
        DeviceState(ControllerProfile profile, int player) { this.profile = profile; this.player = player; }
    }
    private boolean gameMode, started;
    private int buttons, directions, repeatCount;
    private int homeDevice = -1;
    private long generation;
    private final Runnable repeat = new Runnable() {
        @Override public void run() {
            if (gameMode || directions == 0 || !started) return;
            long activeGeneration = generation;
            emitDirections(directions, KeyEvent.ACTION_DOWN, ++repeatCount);
            if (generation == activeGeneration && !gameMode && directions != 0 && started) handler.postDelayed(this, 110);
        }
    };

    ControllerInput(Context context, Listener listener) {
        this.listener = listener;
        manager = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
        audio = (android.media.AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        profiles = new ControllerProfileStore(context);
        players = new ControllerPlayerStore(context);
        app = ReceiverApp.get(context);
    }

    void setGameMode(boolean enabled) {
        if (gameMode == enabled) return;
        // A button used to resume must be released before it becomes an in-game action.
        for (DeviceState device : devices.values()) device.awaitingNeutral = true;
        gameMode = enabled; generation++; release();
    }
    void start() {
        if (started) return;
        clear(); // Reload saved profiles after returning from Controller Setup.
        started = true;
        if (manager != null) manager.registerInputDeviceListener(this, handler);
    }
    void stop() {
        started = false;
        if (manager != null) manager.unregisterInputDeviceListener(this);
        clear();
    }
    void close() { stop(); }

    void clear() {
        generation++;
        devices.clear();
        release();
    }
    private void release() {
        if (homeDevice != -1) { app.controllerHome.cancel(homeDevice); homeDevice = -1; }
        handler.removeCallbacks(repeat);
        state.clear();
        update(0);
        listener.onRetriggersCancelled();
        listener.onAnalogInput(0, 0);
    }

    boolean onKey(KeyEvent event) {
        if (!started || !isController(event.getSource(), event.getDevice())) return false;
        if (event.getAction() != KeyEvent.ACTION_DOWN && event.getAction() != KeyEvent.ACTION_UP) return false;
        int key = event.getKeyCode();
        DeviceState device = device(event.getDeviceId(), event.getDevice());
        boolean mapped = false;
        for (java.util.List<ControllerProfile.Binding> bindings : device.profile.buttons.values())
            for (ControllerProfile.Binding binding : bindings) if (binding.keyCode == key) mapped = true;
        // A skipped controller input must not fall through to Android's raw
        // Back/Menu/D-pad handling and bypass the saved hardware mapping.
        // Non-controller remotes returned above and retain their normal behavior.
        if (!mapped) return true;
        if (ControllerSystemService.keyControl(device.profile, key) == ControllerProfile.Control.HOME) {
            homeDevice = event.getAction() == KeyEvent.ACTION_DOWN ? event.getDeviceId() : -1;
            app.controllerHome.key(event);
            return true;
        }
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN && !event.isCanceled();
        // Do not inherit a held button's repeats after an Activity/focus transition.
        if (down && event.getRepeatCount() > 0 && !device.keys.contains(key)) return true;
        if (down) device.keys.add(key); else device.keys.remove(key);
        process(event.getDeviceId(), device);
        return true;
    }

    boolean onMotion(MotionEvent event) {
        if (!started || event.getActionMasked() != MotionEvent.ACTION_MOVE
            || (event.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) return false;
        InputDevice input = event.getDevice();
        DeviceState device = device(event.getDeviceId(), input);
        if (input != null) for (InputDevice.MotionRange range : input.getMotionRanges()) {
            if ((range.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
                device.axes.put(range.getAxis(), event.getAxisValue(range.getAxis()));
        }
        process(event.getDeviceId(), device);
        return true;
    }

    private DeviceState device(int id, InputDevice input) {
        return devices.computeIfAbsent(id, ignored -> {
            DeviceState device = new DeviceState(profiles.load(input), players.player(input));
            if (input != null && input.getVendorId() == 0x057e && input.getProductId() == 0x2009)
                app.controllerLeds.request(input, device.player);
            return device;
        });
    }

    private void process(int id, DeviceState device) {
        long activeGeneration = generation;
        ControllerProfile.State logical = device.profile.evaluate(device.keys, device.axes);
        int previousLeft = ControllerProfile.stickDirections(device.lx, device.ly);
        int previousRight = ControllerProfile.stickDirections(device.rx, device.ry);
        device.lx = ControllerState.axis(logical.axis(ControllerProfile.StickAxis.LEFT_X), device.lx, 0);
        device.ly = ControllerState.axis(logical.axis(ControllerProfile.StickAxis.LEFT_Y), device.ly, 0);
        device.rx = ControllerState.axis(logical.axis(ControllerProfile.StickAxis.RIGHT_X), device.rx, 0);
        device.ry = ControllerState.axis(logical.axis(ControllerProfile.StickAxis.RIGHT_Y), device.ry, 0);
        if (device.awaitingNeutral) {
            if (logical.pressedBits != 0 || device.lx != 0 || device.ly != 0 || device.rx != 0 || device.ry != 0) return;
            device.awaitingNeutral = false; device.logical = 0; device.menu = false; device.volumeActions = 0;
        }
        int sticks = ControllerProfile.stickDirections(device.lx, device.ly);
        if (!gameMode || device.profile.rightStickInNes) sticks |= ControllerProfile.stickDirections(device.rx, device.ry);
        if (gameMode) {
            int mapped = device.profile.nesButtonsRaw(logical, sticks);
            int freshPresses = device.profile.nesPresses(device.logical, logical.pressedBits)
                | (ControllerProfile.stickDirections(device.lx, device.ly) & ~previousLeft);
            if (device.profile.rightStickInNes)
                freshPresses |= ControllerProfile.stickDirections(device.rx, device.ry) & ~previousRight;
            int volumeActions = mapped & (ControllerProfile.VOLUME_DOWN | ControllerProfile.VOLUME_UP);
            int newVolumeActions = volumeActions & ~device.volumeActions;
            device.volumeActions = volumeActions;
            boolean menu = (mapped & ControllerProfile.MENU) != 0, requestMenu = menu && !device.menu;
            boolean requestHome = logical.pressed(ControllerProfile.Control.HOME)
                && (device.logical & (1 << ControllerProfile.Control.HOME.ordinal())) == 0
                && device.profile.nes.get(ControllerProfile.Control.HOME) == ControllerProfile.NONE;
            boolean requestCheckpoint = device.player == 1
                && device.profile.checkpointPressed(device.logical, logical.pressedBits);
            device.menu = menu; device.logical = logical.pressedBits;
            // Other player slots can still navigate/pause; this NES frontend has one port.
            int held = state.mapped(id, device.player == 1 ? mapped & 255 : 0);
            update(held, device.player == 1 ? freshPresses & held : 0);
            if (generation != activeGeneration || !started || devices.get(id) != device) return;
            if (requestHome) {
                listener.onNavigationKey(KeyEvent.KEYCODE_HOME, KeyEvent.ACTION_DOWN, 0);
                listener.onNavigationKey(KeyEvent.KEYCODE_HOME, KeyEvent.ACTION_UP, 0);
                return;
            }
            // Higher action bits never enter the NES controller mask. Holding a
            // button changes volume once; release is required for another step.
            if (newVolumeActions == ControllerProfile.VOLUME_DOWN) adjustMediaVolume(false);
            else if (newVolumeActions == ControllerProfile.VOLUME_UP) adjustMediaVolume(true);
            if (requestCheckpoint) { listener.onLoadCheckpointRequested(); return; }
            if (requestMenu) listener.onMenuRequested();
            return;
        }
        float ax = logical.axis(ControllerProfile.StickAxis.LEFT_X), ay = logical.axis(ControllerProfile.StickAxis.LEFT_Y);
        float bx = logical.axis(ControllerProfile.StickAxis.RIGHT_X), by = logical.axis(ControllerProfile.StickAxis.RIGHT_Y);
        if (Math.hypot(bx, by) > Math.hypot(ax, ay)) { ax = bx; ay = by; }
        if (logical.pressed(ControllerProfile.Control.LEFT)) ax = -1;
        if (logical.pressed(ControllerProfile.Control.RIGHT)) ax = 1;
        if (logical.pressed(ControllerProfile.Control.UP)) ay = -1;
        if (logical.pressed(ControllerProfile.Control.DOWN)) ay = 1;
        listener.onAnalogInput(ax, ay);
        int mask = sticks;
        if (logical.pressed(ControllerProfile.Control.UP)) mask |= ControllerState.UP;
        if (logical.pressed(ControllerProfile.Control.DOWN)) mask |= ControllerState.DOWN;
        if (logical.pressed(ControllerProfile.Control.LEFT)) mask |= ControllerState.LEFT;
        if (logical.pressed(ControllerProfile.Control.RIGHT)) mask |= ControllerState.RIGHT;
        int pressed = logical.pressedBits & ~device.logical;
        device.logical = logical.pressedBits;
        update(state.mapped(id, mask));
        if (generation != activeGeneration || !started || devices.get(id) != device) return;
        for (ControllerProfile.Control control : ControllerProfile.Control.values()) {
            if ((pressed & (1 << control.ordinal())) == 0) continue;
            int mapped = navigationKey(control);
            if (mapped == 0) continue;
            // System volume keys are normally handled before Activity dispatch. Our
            // translated controller events must adjust the media stream directly.
            if (mapped == KeyEvent.KEYCODE_VOLUME_DOWN || mapped == KeyEvent.KEYCODE_VOLUME_UP) {
                adjustMediaVolume(mapped == KeyEvent.KEYCODE_VOLUME_UP);
                continue;
            }
            // Clicks finish immediately, before a callback can move focus or open another Activity.
            listener.onNavigationKey(mapped, KeyEvent.ACTION_DOWN, 0);
            listener.onNavigationKey(mapped, KeyEvent.ACTION_UP, 0);
            if (generation != activeGeneration || gameMode || device.awaitingNeutral || !started || devices.get(id) != device) return;
        }
    }

    private void adjustMediaVolume(boolean increase) {
        if (audio != null) audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
            increase ? android.media.AudioManager.ADJUST_RAISE : android.media.AudioManager.ADJUST_LOWER,
            android.media.AudioManager.FLAG_SHOW_UI);
    }

    private void update(int value) {
        update(value, 0);
    }
    private void update(int value, int freshPresses) {
        long activeGeneration = generation;
        if (buttons != value || freshPresses != 0) {
            buttons = value;
            if (gameMode) listener.onGameInput(value, freshPresses);
            else listener.onButtonsChanged(value);
        }
        if (generation != activeGeneration) return;
        int next = gameMode ? 0 : value & ControllerState.DIRECTIONS;
        if (next == directions) return;
        int previous = directions;
        directions = next;
        handler.removeCallbacks(repeat);
        emitDirections(previous & ~next, KeyEvent.ACTION_UP, 0);
        if (generation != activeGeneration) return;
        emitDirections(next & ~previous, KeyEvent.ACTION_DOWN, 0);
        if (generation != activeGeneration) return;
        repeatCount = 0;
        if (next != 0 && started) handler.postDelayed(repeat, 340);
    }

    private void emitDirections(int mask, int action, int repeat) {
        long activeGeneration = generation;
        if ((mask & ControllerState.UP) != 0) listener.onNavigationKey(KeyEvent.KEYCODE_DPAD_UP, action, repeat);
        if (generation != activeGeneration) return;
        if ((mask & ControllerState.DOWN) != 0) listener.onNavigationKey(KeyEvent.KEYCODE_DPAD_DOWN, action, repeat);
        if (generation != activeGeneration) return;
        if ((mask & ControllerState.LEFT) != 0) listener.onNavigationKey(KeyEvent.KEYCODE_DPAD_LEFT, action, repeat);
        if (generation != activeGeneration) return;
        if ((mask & ControllerState.RIGHT) != 0) listener.onNavigationKey(KeyEvent.KEYCODE_DPAD_RIGHT, action, repeat);
    }

    static int nesButton(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_BUTTON_Y: return ControllerState.A;
            case KeyEvent.KEYCODE_BUTTON_B: case KeyEvent.KEYCODE_BUTTON_X: return ControllerState.B;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return ControllerState.SELECT;
            case KeyEvent.KEYCODE_BUTTON_START: return ControllerState.START;
            case KeyEvent.KEYCODE_DPAD_UP: return ControllerState.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return ControllerState.DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return ControllerState.LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return ControllerState.RIGHT;
            default: return 0;
        }
    }

    static int navigationKey(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_BUTTON_START: return KeyEvent.KEYCODE_DPAD_CENTER;
            case KeyEvent.KEYCODE_BUTTON_B: return KeyEvent.KEYCODE_BACK;
            case KeyEvent.KEYCODE_BUTTON_X: case KeyEvent.KEYCODE_BUTTON_SELECT: return KeyEvent.KEYCODE_MENU;
            default: return 0;
        }
    }

    static int navigationKey(ControllerProfile.Control control) {
        switch (control) {
            case A: case Y: case LCLICK: return KeyEvent.KEYCODE_DPAD_CENTER;
            case B: return KeyEvent.KEYCODE_BACK;
            case X: case MINUS: case RCLICK: return KeyEvent.KEYCODE_MENU;
            case PLUS: return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            case L: return KeyEvent.KEYCODE_MEDIA_REWIND;
            case R: return KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
            case ZL: return KeyEvent.KEYCODE_VOLUME_DOWN;
            case ZR: return KeyEvent.KEYCODE_VOLUME_UP;
            case HOME: return KeyEvent.KEYCODE_HOME;
            default: return 0;
        }
    }

    static boolean isController(int source, InputDevice device) {
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            || device != null && (device.supportsSource(InputDevice.SOURCE_GAMEPAD)
                || device.supportsSource(InputDevice.SOURCE_JOYSTICK));
    }

    @Override public void onInputDeviceAdded(int deviceId) { }
    @Override public void onInputDeviceChanged(int deviceId) { onInputDeviceRemoved(deviceId); }
    @Override public void onInputDeviceRemoved(int deviceId) {
        if (homeDevice == deviceId) { app.controllerHome.cancel(deviceId); homeDevice = -1; }
        generation++;
        devices.remove(deviceId);
        update(state.remove(deviceId));
        listener.onRetriggersCancelled();
        listener.onAnalogInput(0, 0);
    }

    static JSONArray devices() {
        JSONArray result = new JSONArray();
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null || !isController(device.getSources(), device)) continue;
            try { result.put(new JSONObject().put("id", id).put("name", device.getName())
                .put("vendorId", device.getVendorId()).put("productId", device.getProductId())); }
            catch (org.json.JSONException ignored) { }
        }
        return result;
    }

    static boolean openSettings(Context context) {
        try {
            context.startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            try {
                context.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (ActivityNotFoundException | SecurityException missing) { return false; }
        }
    }
}

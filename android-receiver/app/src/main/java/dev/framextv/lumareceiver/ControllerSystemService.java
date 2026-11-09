package dev.framextv.lumareceiver;

import android.accessibilityservice.AccessibilityService;
import android.hardware.input.InputManager;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

/** Opt-in button filtering and local accessibility actions; analog motion stays with Android. */
public final class ControllerSystemService extends AccessibilityService implements InputManager.InputDeviceListener {
    private static ControllerSystemService active;
    private ReceiverApp app;
    private ControllerProfileStore profiles;
    private final Set<Long> consumed = new HashSet<>();
    private long mappedPresses;
    private int lastMappedKey;

    @Override protected void onServiceConnected() {
        app = ReceiverApp.get(this); profiles = new ControllerProfileStore(this); active = this;
        app.ensureListening("accessibility");
        app.projectorKeys.probe();
        ((InputManager)getSystemService(INPUT_SERVICE)).registerInputDeviceListener(this, null);
    }
    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (app == null) return false;
        InputDevice device = event.getDevice();
        // Ignore injected remote keys, keyboards, and controllers not explicitly calibrated.
        if (device == null || !ControllerInput.isController(event.getSource(), device) || !profiles.isSaved(device)) return false;
        long identity = ((long)event.getDeviceId() << 32) | (event.getKeyCode() & 0xffffffffL);
        boolean up = event.getAction() == KeyEvent.ACTION_UP;
        if (up && consumed.remove(identity)) return true;
        if (event.getAction() != KeyEvent.ACTION_DOWN && !up) return false;
        ControllerProfile profile = profiles.load(device);
        ControllerProfile.Control control = keyControl(profile, event.getKeyCode());
        // Calibration/test screens must see the actual Home button too.
        if (control == ControllerProfile.Control.HOME && !(app.foregroundActivity instanceof ControllerSetupActivity)) {
            app.controllerHome.key(event); return true;
        }
        if (app.foregroundActivity != null && app.foregroundActivity.hasWindowFocus()) return false;
        int mapped = control == null ? 0 : ControllerInput.navigationKey(control);
        if (control == ControllerProfile.Control.UP) mapped = KeyEvent.KEYCODE_DPAD_UP;
        if (control == ControllerProfile.Control.DOWN) mapped = KeyEvent.KEYCODE_DPAD_DOWN;
        if (control == ControllerProfile.Control.LEFT) mapped = KeyEvent.KEYCODE_DPAD_LEFT;
        if (control == ControllerProfile.Control.RIGHT) mapped = KeyEvent.KEYCODE_DPAD_RIGHT;
        if (mapped == 0 || up) return false;
        if (event.isCanceled()) return consumed.remove(identity);
        if (event.getRepeatCount() > 0) {
            if (!consumed.contains(identity)) return false;
            if (mapped != KeyEvent.KEYCODE_VOLUME_DOWN && mapped != KeyEvent.KEYCODE_VOLUME_UP
                && ControllerNavigation.direction(mapped) == 0) return true;
        }
        boolean sent = false;
        // Preserve the firmware's working event injection once it is available.
        if (app.projectorKeys.ready()) sent = app.projectorKeys.send(mapped, null);
        if (!sent) sent = remoteKey(mapped);
        if (!sent) { app.projectorKeys.probe(); return consumed.contains(identity); }
        consumed.add(identity); mappedPresses++; lastMappedKey = mapped;
        return true;
    }
    /** Android 9 has no global D-pad action: navigate its exposed UI focus tree. */
    static boolean remoteKey(int key) {
        ControllerSystemService service = active;
        if (service == null) return false;
        if (key == KeyEvent.KEYCODE_BACK) return service.performGlobalAction(GLOBAL_ACTION_BACK);
        android.view.accessibility.AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return false;
        android.view.accessibility.AccessibilityNodeInfo focused = null, next = null;
        try {
            focused = root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused == null) focused = root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER)
                return focused != null && focused.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
            int direction = ControllerNavigation.direction(key);
            if (direction == 0) return false;
            next = (focused == null ? root : focused).focusSearch(direction);
            if (next != null && next.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)) return true;
            return false;
        } finally {
            if (next != null) next.recycle();
            if (focused != null) focused.recycle();
            root.recycle();
        }
    }

    static ControllerProfile.Control keyControl(ControllerProfile profile, int key) {
        for (ControllerProfile.Control control : ControllerProfile.Control.values())
            for (ControllerProfile.Binding binding : profile.buttons.get(control))
                if (binding.keyCode == key) return control;
        return null;
    }
    static JSONObject status(ReceiverApp app) throws Exception {
        return new JSONObject().put("enabled", active != null).put("transport", app.projectorKeys.state())
            .put("accessibilitySettingsAvailable", accessibilityIntent(app) != null)
            .put("androidTvSettingsAvailable", AccessibilityPanel.tvSettings(app) != null)
            .put("mappedPresses", active == null ? 0 : active.mappedPresses)
            .put("lastMappedKey", active == null ? 0 : active.lastMappedKey)
            .put("directNavigation", active != null && (active.getServiceInfo().getCapabilities() & android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) != 0)
            .put("analogRemapping", false).put("power", app.controllerHome.status());
    }
    private static android.content.Intent accessibilityIntent(android.content.Context context) {
        return AccessibilityPanel.direct(context);
    }
    static String openSettings(android.content.Context context) { return AccessibilityPanel.open(context); }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { /* Screen nodes are queried only for an explicit remote action; never stored or sent. */ }
    @Override public void onInterrupt() { consumed.clear(); }
    @Override public void onDestroy() {
        if (active == this) active = null;
        if (app != null) for (int id : InputDevice.getDeviceIds()) app.controllerHome.cancel(id);
        ((InputManager)getSystemService(INPUT_SERVICE)).unregisterInputDeviceListener(this);
        consumed.clear(); super.onDestroy();
    }
    @Override public void onInputDeviceAdded(int id) { }
    @Override public void onInputDeviceChanged(int id) { onInputDeviceRemoved(id); }
    @Override public void onInputDeviceRemoved(int id) {
        consumed.removeIf(key -> (int)(key >> 32) == id); app.controllerHome.cancel(id);
    }
}

package dev.framextv.lumareceiver;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.InputDevice;
import android.view.MotionEvent;
import org.json.JSONException;
import org.json.JSONObject;

/** Exact device descriptors win; a model copy handles a Bluetooth descriptor change. */
final class ControllerProfileStore {
    private final SharedPreferences preferences;
    ControllerProfileStore(Context context) { preferences = context.getSharedPreferences("controller_profiles_v1", Context.MODE_PRIVATE); }

    ControllerProfile load(InputDevice device) {
        ControllerProfile saved = read(device);
        return saved == null ? defaults(device) : saved;
    }
    boolean isSaved(InputDevice device) { return read(device) != null; }
    boolean save(InputDevice device, ControllerProfile profile) {
        if (device == null) return false;
        try {
            String encoded = profile.toJson().toString();
            ControllerProfile.fromJson(new JSONObject(encoded));
            return preferences.edit().putString(identity(device), encoded).putString(modelIdentity(device), encoded).commit();
        } catch (JSONException | IllegalArgumentException invalid) { return false; }
    }
    void reset(InputDevice device) {
        if (device != null) preferences.edit().remove(identity(device)).remove(modelIdentity(device)).apply();
    }
    private ControllerProfile read(InputDevice device) {
        if (device == null) return null;
        String exact = preferences.getString(identity(device), null);
        return decode(exact == null ? preferences.getString(modelIdentity(device), null) : exact);
    }
    static ControllerProfile decode(String encoded) {
        if (encoded == null || encoded.length() > 32_768) return null;
        try { return ControllerProfile.fromJson(new JSONObject(encoded)); }
        catch (JSONException | IllegalArgumentException invalid) { return null; }
    }
    static String identity(InputDevice device) {
        if (device == null) return "none";
        return identity(device.getDescriptor(), device.getVendorId(), device.getProductId(), device.getName());
    }
    static String identity(String descriptor, int vendor, int product, String name) {
        return descriptor == null || descriptor.trim().isEmpty() ? modelIdentity(vendor, product, name) : "device:" + descriptor;
    }
    private static String modelIdentity(InputDevice device) {
        return modelIdentity(device.getVendorId(), device.getProductId(), device.getName());
    }
    private static String modelIdentity(int vendor, int product, String name) {
        return vendor != 0 || product != 0 ? "model:" + vendor + ":" + product : "name:" + (name == null ? "unknown" : name);
    }
    private static ControllerProfile defaults(InputDevice device) {
        ControllerProfile profile = ControllerProfile.defaults();
        if (device == null) return profile;
        if (device.getMotionRange(MotionEvent.AXIS_Z) == null && device.getMotionRange(MotionEvent.AXIS_RX) != null)
            profile.sticks.put(ControllerProfile.StickAxis.RIGHT_X, new ControllerProfile.AxisBinding(MotionEvent.AXIS_RX, 0, 1, .16f));
        if (device.getMotionRange(MotionEvent.AXIS_RZ) == null && device.getMotionRange(MotionEvent.AXIS_RY) != null)
            profile.sticks.put(ControllerProfile.StickAxis.RIGHT_Y, new ControllerProfile.AxisBinding(MotionEvent.AXIS_RY, 0, 1, .16f));
        calibrateDefaultTrigger(profile, device, ControllerProfile.Control.ZL, MotionEvent.AXIS_LTRIGGER);
        calibrateDefaultTrigger(profile, device, ControllerProfile.Control.ZR, MotionEvent.AXIS_RTRIGGER);
        return profile;
    }
    private static void calibrateDefaultTrigger(ControllerProfile profile, InputDevice device, ControllerProfile.Control control, int axis) {
        InputDevice.MotionRange range = device.getMotionRange(axis);
        if (range == null) return;
        try {
            profile.buttons.get(control).removeIf(binding -> binding.axis == axis);
            profile.buttons.get(control).add(ControllerProfile.Binding.axis(axis, range.getMin(), range.getMax()));
        } catch (IllegalArgumentException unsupportedRange) { /* Guided calibration can capture unusual hardware. */ }
    }
}

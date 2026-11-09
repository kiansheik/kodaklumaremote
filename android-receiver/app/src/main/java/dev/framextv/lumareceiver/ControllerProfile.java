package dev.framextv.lumareceiver;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure hardware calibration and independent, system-specific game bindings. */
final class ControllerProfile {
    enum Control { A, B, X, Y, L, R, ZL, ZR, PLUS, MINUS, LCLICK, RCLICK, UP, DOWN, LEFT, RIGHT, HOME, CAPTURE }
    enum StickAxis { LEFT_X, LEFT_Y, RIGHT_X, RIGHT_Y }
    static final int NONE = 0, A = 1, B = 2, SELECT = 4, START = 8,
        UP = 16, DOWN = 32, LEFT = 64, RIGHT = 128, MENU = 256,
        VOLUME_DOWN = 512, VOLUME_UP = 1024, LOAD_CHECKPOINT = 2048;
    final EnumMap<Control, List<Binding>> buttons = new EnumMap<>(Control.class);
    final EnumMap<StickAxis, AxisBinding> sticks = new EnumMap<>(StickAxis.class);
    final EnumMap<Control, Integer> nes = new EnumMap<>(Control.class);
    boolean rightStickInNes;

    ControllerProfile() {
        for (Control control : Control.values()) { buttons.put(control, new ArrayList<>()); nes.put(control, NONE); }
    }

    static final class Binding {
        final int keyCode, axis, sign;
        final float center, span;
        private Binding(int keyCode, int axis, int sign, float center, float span) {
            if (keyCode >= 0) {
                if (keyCode < 1 || keyCode > 512) throw new IllegalArgumentException("Invalid controller key");
            } else validateAxis(axis, sign, center, span, 0);
            this.keyCode = keyCode; this.axis = axis; this.sign = sign; this.center = center; this.span = span;
        }
        static Binding key(int keyCode) { return new Binding(keyCode, -1, 1, 0, 1); }
        static Binding axis(int axis, float center, float pressed) {
            return new Binding(-1, axis, pressed >= center ? 1 : -1, center, Math.abs(pressed - center));
        }
        boolean sameInput(Binding other) {
            return keyCode >= 0 ? keyCode == other.keyCode : other.keyCode < 0 && axis == other.axis && sign == other.sign;
        }
        boolean pressed(Set<Integer> keys, Map<Integer, Float> axes) {
            if (keyCode >= 0) return keys.contains(keyCode);
            Float value = axes.get(axis);
            return value != null && finite(value) && (value - center) * sign / span >= .5f;
        }
        JSONObject toJson() throws JSONException {
            return keyCode >= 0 ? new JSONObject().put("key", keyCode) :
                new JSONObject().put("axis", axis).put("sign", sign).put("center", center).put("span", span);
        }
        static Binding fromJson(JSONObject json) throws JSONException {
            if (json.has("key")) {
                if (json.length() != 1) throw new JSONException("Ambiguous key binding");
                return key(integer(json, "key"));
            }
            if (json.length() != 4) throw new JSONException("Invalid axis button");
            return new Binding(-1, integer(json, "axis"), integer(json, "sign"), number(json, "center"), number(json, "span"));
        }
    }

    static final class AxisBinding {
        final int axis, sign;
        final float center, span, deadZone;
        /** full is the measured raw value while moving right or down. */
        AxisBinding(int axis, float center, float full, float deadZone) {
            this(axis, full >= center ? 1 : -1, center, Math.abs(full - center), deadZone);
        }
        private AxisBinding(int axis, int sign, float center, float span, float deadZone) {
            validateAxis(axis, sign, center, span, deadZone);
            this.axis = axis; this.sign = sign; this.center = center; this.span = span; this.deadZone = deadZone;
        }
        float normalize(float raw) {
            if (!finite(raw)) return 0;
            float value = Math.max(-1, Math.min(1, (raw - center) * sign / span));
            return Math.abs(value) <= deadZone ? 0 : value;
        }
        JSONObject toJson() throws JSONException {
            return new JSONObject().put("axis", axis).put("sign", sign).put("center", center)
                .put("span", span).put("deadZone", deadZone);
        }
        static AxisBinding fromJson(JSONObject json) throws JSONException {
            if (json.length() != 5) throw new JSONException("Invalid stick binding");
            return new AxisBinding(integer(json, "axis"), integer(json, "sign"), number(json, "center"),
                number(json, "span"), number(json, "deadZone"));
        }
    }

    static final class State {
        final int pressedBits;
        private final EnumMap<StickAxis, Float> axes;
        State(int pressedBits, EnumMap<StickAxis, Float> axes) { this.pressedBits = pressedBits; this.axes = axes; }
        boolean pressed(Control control) { return (pressedBits & (1 << control.ordinal())) != 0; }
        float axis(StickAxis axis) { Float value = axes.get(axis); return value == null ? 0 : value; }
    }

    static ControllerProfile defaults() {
        ControllerProfile profile = new ControllerProfile();
        // Android public KeyEvent constants; numeric values keep the model JVM-only.
        int[] keys = {96, 97, 99, 100, 102, 103, 104, 105, 108, 109, 106, 107, 19, 20, 21, 22, 110, 120};
        Control[] controls = Control.values();
        for (int i = 0; i < controls.length; i++) profile.buttons.get(controls[i]).add(Binding.key(keys[i]));
        profile.buttons.get(Control.LEFT).add(Binding.axis(15, 0, -1));
        profile.buttons.get(Control.RIGHT).add(Binding.axis(15, 0, 1));
        profile.buttons.get(Control.UP).add(Binding.axis(16, 0, -1));
        profile.buttons.get(Control.DOWN).add(Binding.axis(16, 0, 1));
        profile.buttons.get(Control.ZL).add(Binding.axis(17, 0, 1));
        profile.buttons.get(Control.ZR).add(Binding.axis(18, 0, 1));
        profile.sticks.put(StickAxis.LEFT_X, new AxisBinding(0, 0, 1, .16f));
        profile.sticks.put(StickAxis.LEFT_Y, new AxisBinding(1, 0, 1, .16f));
        profile.sticks.put(StickAxis.RIGHT_X, new AxisBinding(11, 0, 1, .16f));
        profile.sticks.put(StickAxis.RIGHT_Y, new AxisBinding(14, 0, 1, .16f));
        profile.nes.put(Control.A, A); profile.nes.put(Control.Y, A);
        profile.nes.put(Control.B, B); profile.nes.put(Control.X, B);
        profile.nes.put(Control.PLUS, START); profile.nes.put(Control.MINUS, SELECT);
        profile.nes.put(Control.UP, UP); profile.nes.put(Control.DOWN, DOWN);
        profile.nes.put(Control.LEFT, LEFT); profile.nes.put(Control.RIGHT, RIGHT);
        profile.nes.put(Control.L, MENU); profile.nes.put(Control.R, MENU);
        profile.nes.put(Control.ZL, VOLUME_DOWN); profile.nes.put(Control.ZR, VOLUME_UP);
        return profile;
    }

    ControllerProfile copy() {
        ControllerProfile result = new ControllerProfile();
        for (Control control : Control.values()) result.buttons.put(control, new ArrayList<>(buttons.get(control)));
        result.sticks.putAll(sticks); result.nes.putAll(nes); result.rightStickInNes = rightStickInNes;
        return result;
    }

    /** Reassign a physical input, removing earlier ownership to avoid accidental fan-out. */
    void bind(Control control, Binding binding) {
        for (List<Binding> bindings : buttons.values()) bindings.removeIf(binding::sameInput);
        buttons.get(control).clear(); buttons.get(control).add(binding);
    }
    void clear(Control control) { buttons.get(control).clear(); }
    void setStick(StickAxis stick, AxisBinding binding) {
        sticks.entrySet().removeIf(entry -> entry.getValue().axis == binding.axis);
        sticks.put(stick, binding);
    }

    State evaluate(Set<Integer> keys, Map<Integer, Float> axes) {
        int pressed = 0;
        for (Control control : Control.values()) for (Binding binding : buttons.get(control)) {
            if (binding.pressed(keys, axes)) { pressed |= 1 << control.ordinal(); break; }
        }
        EnumMap<StickAxis, Float> normalized = new EnumMap<>(StickAxis.class);
        for (Map.Entry<StickAxis, AxisBinding> entry : sticks.entrySet()) {
            Float raw = axes.get(entry.getValue().axis);
            normalized.put(entry.getKey(), raw == null ? 0 : entry.getValue().normalize(raw));
        }
        return new State(pressed, normalized);
    }

    int nesButtons(State state) {
        int directions = stickDirections(state.axis(StickAxis.LEFT_X), state.axis(StickAxis.LEFT_Y));
        if (rightStickInNes) directions |= stickDirections(state.axis(StickAxis.RIGHT_X), state.axis(StickAxis.RIGHT_Y));
        return nesButtons(state, directions);
    }
    int nesButtons(State state, int stickDirections) {
        return ControllerState.neutralize(nesButtonsRaw(state, stickDirections));
    }
    int nesButtonsRaw(State state, int stickDirections) {
        int result = stickDirections & ControllerState.DIRECTIONS;
        for (Control control : Control.values()) if (state.pressed(control)) result |= nes.get(control);
        return result;
    }
    static int stickDirections(float x, float y) {
        return (x <= -.5f ? LEFT : x >= .5f ? RIGHT : 0) | (y <= -.5f ? UP : y >= .5f ? DOWN : 0);
    }

    /** New physical-control presses, before duplicate NES assignments are merged. */
    int nesPresses(int previousLogical, int currentLogical) {
        int rising = currentLogical & ~previousLogical, result = 0;
        for (Control control : Control.values())
            if ((rising & (1 << control.ordinal())) != 0) result |= nes.get(control);
        return result & 255; // Menu and media actions are not emulator buttons.
    }

    boolean checkpointPressed(int previousLogical, int currentLogical) {
        int rising = currentLogical & ~previousLogical;
        for (Control control : Control.values())
            if ((rising & (1 << control.ordinal())) != 0 && nes.get(control) == LOAD_CHECKPOINT) return true;
        return false;
    }

    JSONObject toJson() throws JSONException {
        JSONObject buttonJson = new JSONObject(), axisJson = new JSONObject(), nesJson = new JSONObject();
        for (Control control : Control.values()) {
            JSONArray bindings = new JSONArray();
            for (Binding binding : buttons.get(control)) bindings.put(binding.toJson());
            buttonJson.put(control.name(), bindings); nesJson.put(control.name(), nes.get(control));
        }
        for (Map.Entry<StickAxis, AxisBinding> entry : sticks.entrySet()) axisJson.put(entry.getKey().name(), entry.getValue().toJson());
        return new JSONObject().put("version", 1).put("buttons", buttonJson).put("sticks", axisJson)
            .put("nes", nesJson).put("rightStickInNes", rightStickInNes);
    }

    static ControllerProfile fromJson(JSONObject json) throws JSONException {
        try {
            if (json.length() != 5 || integer(json, "version") != 1) throw new JSONException("Unsupported controller profile");
            JSONObject buttonJson = json.getJSONObject("buttons"), axisJson = json.getJSONObject("sticks"), nesJson = json.getJSONObject("nes");
            if (buttonJson.length() != Control.values().length || nesJson.length() != Control.values().length ||
                axisJson.length() > StickAxis.values().length || !(json.get("rightStickInNes") instanceof Boolean))
                throw new JSONException("Incomplete controller profile");
            ControllerProfile result = new ControllerProfile();
            List<Binding> assigned = new ArrayList<>();
            for (Control control : Control.values()) {
                JSONArray bindings = buttonJson.getJSONArray(control.name());
                if (bindings.length() > 3) throw new JSONException("Too many button bindings");
                for (int i = 0; i < bindings.length(); i++) {
                    Binding binding = Binding.fromJson(bindings.getJSONObject(i));
                    for (Binding previous : assigned) if (binding.sameInput(previous)) throw new JSONException("Duplicate hardware binding");
                    assigned.add(binding); result.buttons.get(control).add(binding);
                }
                int action = integer(nesJson, control.name());
                if (action < 0 || action > LOAD_CHECKPOINT || (action != 0 && (action & (action - 1)) != 0)) throw new JSONException("Invalid NES action");
                result.nes.put(control, action);
            }
            Set<Integer> axes = new HashSet<>();
            for (String key : iterableKeys(axisJson)) {
                StickAxis stick = StickAxis.valueOf(key);
                AxisBinding binding = AxisBinding.fromJson(axisJson.getJSONObject(key));
                if (!axes.add(binding.axis)) throw new JSONException("Duplicate stick axis");
                result.sticks.put(stick, binding);
            }
            result.rightStickInNes = json.getBoolean("rightStickInNes");
            return result;
        } catch (IllegalArgumentException invalid) { throw new JSONException(invalid.getMessage()); }
    }

    private static List<String> iterableKeys(JSONObject json) {
        List<String> keys = new ArrayList<>(); json.keys().forEachRemaining(keys::add); return keys;
    }
    private static int integer(JSONObject json, String key) throws JSONException {
        Object value = json.get(key);
        if (!(value instanceof Number) || ((Number) value).doubleValue() != ((Number) value).intValue())
            throw new JSONException("Invalid integer: " + key);
        return ((Number) value).intValue();
    }
    private static float number(JSONObject json, String key) throws JSONException {
        Object value = json.get(key);
        if (!(value instanceof Number)) throw new JSONException("Invalid number: " + key);
        float result = ((Number) value).floatValue();
        if (!finite(result)) throw new JSONException("Non-finite calibration");
        return result;
    }
    private static boolean finite(float value) { return !Float.isNaN(value) && !Float.isInfinite(value); }
    private static void validateAxis(int axis, int sign, float center, float span, float deadZone) {
        if (axis < 0 || axis > 63 || (sign != -1 && sign != 1) || !finite(center) || Math.abs(center) > 4 ||
            !finite(span) || span < .15f || span > 4 || !finite(deadZone) || deadZone < 0 || deadZone >= .5f)
            throw new IllegalArgumentException("Invalid axis calibration");
    }
}

package dev.framextv.lumareceiver;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A draft-only capture session. Saving is an explicit action outside this class. */
final class ControllerCalibration {
    static final class Range {
        final float min, max;
        Range(float min, float max) { this.min = min; this.max = max; }
    }
    static final class Step {
        final ControllerProfile.Control button;
        final ControllerProfile.StickAxis stick;
        final String title, instruction;
        Step(ControllerProfile.StickAxis stick, String title, String instruction) {
            this.stick = stick; this.button = null; this.title = title; this.instruction = instruction;
        }
        Step(ControllerProfile.Control button, String title, String instruction) {
            this.button = button; this.stick = null; this.title = title; this.instruction = instruction;
        }
        boolean optional() { return button == ControllerProfile.Control.HOME || button == ControllerProfile.Control.CAPTURE; }
        String target() { return stick == null ? button.name() : stick.name(); }
    }
    static final Step[] STEPS = {
        new Step(ControllerProfile.StickAxis.LEFT_X, "Left stick →", "Move the LEFT stick fully RIGHT and hold briefly."),
        new Step(ControllerProfile.StickAxis.LEFT_Y, "Left stick ↓", "Move the LEFT stick fully DOWN and hold briefly."),
        new Step(ControllerProfile.StickAxis.RIGHT_X, "Right stick →", "Move the RIGHT stick fully RIGHT and hold briefly."),
        new Step(ControllerProfile.StickAxis.RIGHT_Y, "Right stick ↓", "Move the RIGHT stick fully DOWN and hold briefly."),
        new Step(ControllerProfile.Control.A, "A", "Press A, the RIGHT face button."),
        new Step(ControllerProfile.Control.B, "B", "Press B, the BOTTOM face button."),
        new Step(ControllerProfile.Control.X, "X", "Press X, the TOP face button."),
        new Step(ControllerProfile.Control.Y, "Y", "Press Y, the LEFT face button."),
        new Step(ControllerProfile.Control.L, "L", "Press L, the LEFT upper shoulder button."),
        new Step(ControllerProfile.Control.R, "R", "Press R, the RIGHT upper shoulder button."),
        new Step(ControllerProfile.Control.ZL, "ZL", "Press ZL, the LEFT rear trigger."),
        new Step(ControllerProfile.Control.ZR, "ZR", "Press ZR, the RIGHT rear trigger."),
        new Step(ControllerProfile.Control.PLUS, "+", "Press the + button."),
        new Step(ControllerProfile.Control.MINUS, "−", "Press the − button."),
        new Step(ControllerProfile.Control.LCLICK, "Left stick click", "Push the LEFT stick straight IN until it clicks."),
        new Step(ControllerProfile.Control.RCLICK, "Right stick click", "Push the RIGHT stick straight IN until it clicks."),
        new Step(ControllerProfile.Control.UP, "D-pad ↑", "Press UP on the cross-shaped D-pad."),
        new Step(ControllerProfile.Control.DOWN, "D-pad ↓", "Press DOWN on the D-pad."),
        new Step(ControllerProfile.Control.LEFT, "D-pad ←", "Press LEFT on the D-pad."),
        new Step(ControllerProfile.Control.RIGHT, "D-pad →", "Press RIGHT on the D-pad."),
        new Step(ControllerProfile.Control.HOME, "Home (optional)", "Press Home, or use Skip if Android opens its Home screen."),
        new Step(ControllerProfile.Control.CAPTURE, "Capture (optional)", "Press Capture, or use Skip if Android handles it itself.")
    };
    final int deviceId;
    final ControllerProfile draft;
    final Map<Integer, Float> neutral = new HashMap<>();
    final Map<Integer, Range> ranges = new HashMap<>();
    private final Map<Integer, Float> axes = new HashMap<>();
    private final Set<Integer> keys = new HashSet<>();
    private final Set<Integer> axesAwaitingSample = new HashSet<>();
    int index, mappedCount;
    boolean waitingForRelease, retryAfterRelease;
    String message = "";
    private int candidateAxis = -1;
    private float candidateFull;
    private long candidateSince;

    ControllerCalibration(int deviceId, ControllerProfile existing, Map<Integer, Float> neutral,
                          Map<Integer, Range> ranges) {
        this.deviceId = deviceId; this.draft = existing.copy();
        for (ControllerProfile.Control control : ControllerProfile.Control.values()) draft.clear(control);
        draft.sticks.clear();
        this.neutral.putAll(neutral); this.axes.putAll(neutral); this.ranges.putAll(ranges);
    }
    Step current() { return index < STEPS.length ? STEPS[index] : null; }
    boolean complete() { return index >= STEPS.length; }

    void onKey(int sourceDeviceId, int key, boolean down, int repeat) {
        if (sourceDeviceId != deviceId || complete()) return;
        if (down) keys.add(key); else keys.remove(key);
        if (waitingForRelease) { releaseIfNeutral(); return; }
        if (!down || repeat != 0 || current().button == null) return;
        if (key < 1 || key > 512) {
            reject("Android did not report a usable button code. Release it, then retry or skip this control."); return;
        }
        ControllerProfile.Binding binding = ControllerProfile.Binding.key(key);
        String conflict = conflict(binding);
        if (conflict != null) { reject(conflict); return; }
        draft.bind(current().button, binding); captured();
    }
    void onMotion(int sourceDeviceId, Map<Integer, Float> values, long now) {
        if (sourceDeviceId != deviceId || complete()) return;
        for (Map.Entry<Integer, Float> entry : values.entrySet()) {
            Float value = entry.getValue();
            if (value != null && !Float.isNaN(value) && !Float.isInfinite(value)) {
                axes.put(entry.getKey(), value); axesAwaitingSample.remove(entry.getKey());
            }
        }
        if (waitingForRelease) { releaseIfNeutral(); return; }
        chooseAxis(now); tick(now);
    }
    void tick(long now) {
        if (complete() || waitingForRelease || candidateAxis < 0 || now - candidateSince < 110) return;
        float value = value(axes, candidateAxis), center = value(neutral, candidateAxis);
        if (Math.abs(normalized(candidateAxis, value - center)) < .55f) { candidateAxis = -1; return; }
        try {
            if (current().stick != null) {
                for (Map.Entry<ControllerProfile.StickAxis, ControllerProfile.AxisBinding> entry : draft.sticks.entrySet()) {
                    if (entry.getValue().axis == candidateAxis) { reject("That axis already belongs to " + label(entry.getKey().name()) + "."); return; }
                }
                draft.setStick(current().stick, new ControllerProfile.AxisBinding(candidateAxis, center, candidateFull, .16f));
            } else {
                ControllerProfile.Binding binding = ControllerProfile.Binding.axis(candidateAxis, center, candidateFull);
                String conflict = conflict(binding);
                if (conflict != null) { reject(conflict); return; }
                draft.bind(current().button, binding);
            }
        } catch (IllegalArgumentException invalidRange) {
            reject("This axis has an unsupported range. Center the sticks, then restart calibration or skip this control."); return;
        }
        captured();
    }
    void skip() {
        if (complete()) return;
        if (waitingForRelease && !retryAfterRelease) return;
        candidateAxis = -1; message = "Skipped " + current().title + ". Release and center all controls.";
        if (allNeutral()) { waitingForRelease = false; retryAfterRelease = false; index++; }
        else { waitingForRelease = true; retryAfterRelease = false; }
    }
    void clearHeld() {
        if (complete()) return;
        keys.clear(); candidateAxis = -1;
        // OS-owned buttons can lose their key-up outside this Activity, but
        // focus loss must never fabricate stick release from the old baseline.
        axesAwaitingSample.addAll(ranges.keySet());
        if (!waitingForRelease) retryAfterRelease = true;
        waitingForRelease = true;
        message = "Move, then center both sticks and release all buttons to continue.";
    }
    private void chooseAxis(long now) {
        int best = -1; float magnitude = 0, runner = 0, full = 0;
        for (Map.Entry<Integer, Range> entry : ranges.entrySet()) {
            int axis = entry.getKey();
            if (current().stick != null && (axis == 15 || axis == 16 || axis == 17 || axis == 18 || axis == 22 || axis == 23)) continue;
            if (current().button != null) {
                boolean stick = false;
                for (ControllerProfile.AxisBinding binding : draft.sticks.values()) if (binding.axis == axis) stick = true;
                if (stick) continue;
            }
            float delta = value(axes, axis) - value(neutral, axis);
            float strength = Math.abs(normalized(axis, delta));
            if (strength > magnitude) { runner = magnitude; magnitude = strength; best = axis; full = delta > 0 ? entry.getValue().max : entry.getValue().min; }
            else runner = Math.max(runner, strength);
        }
        if (magnitude < .55f || magnitude - runner < .14f) { candidateAxis = -1; return; }
        if (candidateAxis != best || Math.signum(candidateFull - value(neutral, best)) != Math.signum(full - value(neutral, best))) {
            candidateAxis = best; candidateFull = full; candidateSince = now;
        }
    }
    private String conflict(ControllerProfile.Binding binding) {
        for (Map.Entry<ControllerProfile.Control, List<ControllerProfile.Binding>> entry : draft.buttons.entrySet()) {
            for (ControllerProfile.Binding other : entry.getValue()) {
                boolean same = binding.keyCode >= 0 ? binding.keyCode == other.keyCode
                    : other.keyCode < 0 && binding.axis == other.axis && binding.sign == other.sign;
                if (same) return "That input already belongs to " + label(entry.getKey().name()) + ". Try the highlighted control.";
            }
        }
        return null;
    }
    private void reject(String reason) {
        message = reason; waitingForRelease = true; retryAfterRelease = true; candidateAxis = -1;
    }
    private void captured() {
        mappedCount++; message = "Captured " + current().title + ". Release and center all controls.";
        waitingForRelease = true; retryAfterRelease = false; candidateAxis = -1;
    }
    private void releaseIfNeutral() {
        if (!allNeutral()) return;
        waitingForRelease = false; candidateAxis = -1;
        if (!retryAfterRelease) index++;
        retryAfterRelease = false;
        if (!complete()) message = "";
    }
    private boolean allNeutral() {
        if (!keys.isEmpty() || !axesAwaitingSample.isEmpty()) return false;
        for (int axis : ranges.keySet())
            if (Math.abs(normalized(axis, value(axes, axis) - value(neutral, axis))) > .22f) return false;
        return true;
    }
    private float normalized(int axis, float delta) {
        Range range = ranges.get(axis); if (range == null) return 0;
        float center = value(neutral, axis), extent = delta >= 0 ? range.max - center : center - range.min;
        return extent > .001f ? delta / extent : 0;
    }
    private static float value(Map<Integer, Float> map, int key) { Float value = map.get(key); return value == null ? 0 : value; }
    private static String label(String name) { return name.replace('_', ' ').toLowerCase(java.util.Locale.ROOT); }
}

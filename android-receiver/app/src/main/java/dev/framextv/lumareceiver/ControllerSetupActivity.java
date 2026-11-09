package dev.framextv.lumareceiver;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Physical input diagnostics and calibration, entirely on the receiver. */
public final class ControllerSetupActivity extends Activity implements InputManager.InputDeviceListener {
    private ReceiverApp app;
    private ControllerProfileStore store;
    private ControllerPlayerStore players;
    private InputManager manager;
    private ControllerProfile profile, assignmentDraft;
    private ControllerCalibration calibration;
    private ControllerInput navigation;
    private FrameLayout root;
    private LinearLayout card;
    private ControllerDiagramView diagram;
    private TextView deviceText, inputText, stepTitle, stepBody, stepProgress;
    private TextView playerLights;
    private Button skipButton;
    private int selectedId = -1, lastStep = -1;
    private String selectedName = "No controller connected", selectedIdentity = "", phase = "live", lastInput = "Waiting for controller input";
    private boolean resumed, lastRelease, saved, pendingBegin;
    private int reviewBits, reviewDirections;
    private long testExitSince;
    private String testReturn = "overview";
    private final Set<Integer> heldKeys = new HashSet<>();
    private final Map<Integer, Float> rawAxes = new HashMap<>();
    private final ArrayDeque<String> trace = new ArrayDeque<>();
    private long lastMotionTrace;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            if (calibration != null && "calibrating".equals(phase)) {
                calibration.tick(SystemClock.uptimeMillis()); updateCalibration();
            }
            if ("live".equals(phase)) checkTestExit();
            if ("players".equals(phase) && playerLights != null) playerLights.setText(playerLightsText());
            root.postDelayed(this, 60);
        }
    };

    static void show(Context context) {
        context.startActivity(new Intent(context, ControllerSetupActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        app = ReceiverApp.get(this); store = new ControllerProfileStore(this); players = new ControllerPlayerStore(this);
        manager = (InputManager) getSystemService(Context.INPUT_SERVICE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        root = new FrameLayout(this); root.setBackgroundColor(Color.rgb(8, 29, 28)); setContentView(root);
        navigation = new ControllerInput(this, new ControllerInput.Listener() {
            @Override public void onButtonsChanged(int buttons) { }
            @Override public void onNavigationKey(int key, int action, int repeat) { navigate(key, action, repeat); }
        });
        app.catMode.stop();
        if (app.playback.active()) app.playback.setPlaying(false);
        app.setControllerSetupUi(this);
        InputDevice first = null;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (isController(device)) { if (first == null || device.getName().toLowerCase(Locale.ROOT).contains("pro controller")) first = device; }
        }
        if (first != null) select(first);
        showLive();
    }
    @Override protected void onResume() {
        super.onResume(); resumed = true; navigation.start();
        if (manager != null) manager.registerInputDeviceListener(this, null);
        root.removeCallbacks(tick); root.post(tick); updateDeviceLabel();
    }
    @Override protected void onPause() {
        resumed = false; root.removeCallbacks(tick); navigation.stop();
        if (manager != null) manager.unregisterInputDeviceListener(this);
        releaseInputs(); super.onPause();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused && root != null) releaseInputs();
    }
    @Override protected void onDestroy() {
        root.removeCallbacks(tick); navigation.close(); app.clearControllerSetupUi(this); super.onDestroy();
    }
    private void releaseInputs() {
        heldKeys.clear();
        if (navigation != null) navigation.clear();
        pendingBegin = false; testExitSince = 0; reviewBits = 0; reviewDirections = 0;
        if (calibration != null && !calibration.complete()) calibration.clearHeld();
        rawAxes.clear(); updateDiagram();
    }
    private boolean connected() {
        InputDevice device = InputDevice.getDevice(selectedId);
        return device != null && selectedIdentity.equals(ControllerProfileStore.identity(device));
    }
    private void select(InputDevice device) {
        selectedId = device.getId(); selectedName = device.getName(); selectedIdentity = ControllerProfileStore.identity(device);
        profile = store.load(device); saved = store.isSaved(device); calibration = null; assignmentDraft = null;
        heldKeys.clear(); rawAxes.clear(); trace.clear(); lastInput = "Waiting for controller input";
    }
    private void layout(String heading, String description) {
        playerLights = null;
        root.removeAllViews(); stepTitle = null; stepBody = null; stepProgress = null; skipButton = null;
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(26), dp(15), dp(26), dp(12)); root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        TextView title = text(heading, 27, Color.WHITE); title.setTypeface(null, Typeface.BOLD); page.addView(title);
        deviceText = text("", 14, Color.rgb(170, 209, 194)); deviceText.setPadding(0, dp(3), 0, dp(5)); page.addView(deviceText); updateDeviceLabel();
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.HORIZONTAL);
        page.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        diagram = new ControllerDiagramView(this); body.addView(diagram, new LinearLayout.LayoutParams(0, -1, 1.55f));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -1, 1); right.setMargins(dp(16), 0, 0, 0); body.addView(scroll, right);
        card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(3), dp(4), dp(3), dp(8)); scroll.addView(card);
        if (description != null && !description.isEmpty()) {
            TextView intro = text(description, 15, Color.rgb(198, 220, 212)); intro.setPadding(0, 0, 0, dp(9)); card.addView(intro);
        }
        inputText = text(lastInput, 13, Color.rgb(139, 183, 170)); inputText.setMaxLines(2);
        inputText.setPadding(0, dp(5), 0, 0); page.addView(inputText, new LinearLayout.LayoutParams(-1, dp(39)));
        updateDiagram();
    }
    private void showLive() {
        phase = "overview"; calibration = null; assignmentDraft = null; pendingBegin = false;
        if (navigation != null) navigation.clear();
        if (connected()) { profile = store.load(InputDevice.getDevice(selectedId)); saved = store.isSaved(InputDevice.getDevice(selectedId)); }
        layout("Controller setup", "Learn every physical control, then choose what it does in each game system. Use your controller, projector remote or phone.");
        TextView status = text(saved ? "Saved hardware mapping" : "Default mapping · calibrate to match the printed Nintendo labels", 16, Color.rgb(255, 220, 127));
        status.setPadding(0, 0, 0, dp(10)); card.addView(status);
        Button begin = button("Calibrate every control", this::showPreparation); begin.setEnabled(connected());
        Button test = button("Test all controls", () -> showTest("overview")); test.setEnabled(connected());
        Button nes = button("NES button assignments", this::showAssignments); nes.setEnabled(connected());
        Button player = button("Player assignment", this::showPlayers); player.setEnabled(connected());
        button("Receiver controls", this::showReceiverControls);
        button("Controller across apps", this::showSystemControls);
        button("Choose / pair controller", this::showDevices);
        button("Done", this::finish);
        if (connected()) begin.requestFocusFromTouch();
        else card.getChildAt(Math.max(0, card.getChildCount() - 2)).requestFocusFromTouch();
    }
    private void showPlayers() {
        if (!connected()) { showDevices(); return; }
        phase = "players";
        layout("Player assignment", "Player slots are saved for future multiplayer. NES currently supports player 1.");
        InputDevice device = InputDevice.getDevice(selectedId);
        int current = players.player(device);
        JSONArray slots = null;
        try { slots = players.snapshot().optJSONArray("slots"); } catch (Exception ignored) { }
        playerLights = text(playerLightsText(), 16, Color.rgb(198, 220, 212));
        playerLights.setPadding(0, 0, 0, dp(10)); card.addView(playerLights);
        Button chosen = null;
        for (int slot = 1; slot <= 4; slot++) {
            final int selectedSlot = slot;
            String occupant = "";
            if (slots != null) for (int i = 0; i < slots.length(); i++) {
                JSONObject entry = slots.optJSONObject(i);
                if (entry != null && entry.optInt("player") == slot && entry.optBoolean("assigned")) {
                    occupant = entry.optString("name", "Controller") + (entry.optBoolean("connected") ? "" : " (offline)"); break;
                }
            }
            String label = "Player " + slot + (slot == current ? "  ·  this controller" : occupant.isEmpty() ? "  ·  available" : "  ·  " + occupant);
            Button row = button(label, () -> {
                if (!connected()) { showDevices(); return; }
                if (!players.assign(InputDevice.getDevice(selectedId), selectedSlot)) {
                    Toast.makeText(this, "Could not save the player assignment. Try again.", Toast.LENGTH_LONG).show(); return;
                }
                app.controllerLeds.request(InputDevice.getDevice(selectedId), selectedSlot);
                navigation.clear(); showPlayers();
                Toast.makeText(this, "Assigned to player " + selectedSlot, Toast.LENGTH_SHORT).show();
            });
            if (slot == current) chosen = row;
            else if (chosen == null && current == 0) chosen = row;
        }
        TextView reassign = text("An occupied slot's controller takes your old slot, or becomes unassigned if you had none.", 13, Color.rgb(166, 198, 186));
        reassign.setPadding(0, dp(8), 0, dp(8)); card.addView(reassign);
        button("Back", this::showLive);
        if (chosen != null) chosen.requestFocusFromTouch();
    }
    private String playerLightsText() {
        String state = app.controllerLeds.status().optString("state");
        if ("notRequested".equals(state)) return "Choosing a player also requests steady player lights on the controller.";
        if ("openingHidHost".equals(state)) return "Setting controller player lights…";
        if ("sentUnconfirmed".equals(state)) return "Player-light command sent. Check the controller for steady lights.";
        return "Player assignment is saved. Controller lights: " + app.controllerLeds.status().optString("detail", "unavailable");
    }
    private void showTest(String returnTo) {
        testReturn = returnTo; phase = "live"; testExitSince = 0; navigation.clear();
        layout("Test every control", "Press buttons and move both sticks. Every input stays in this test, so you can check simultaneous presses safely.");
        card.addView(text("Hold + and − together for 1 second to leave this test.", 22, Color.rgb(255, 220, 127)));
        TextView note = text("Home and Capture may be reserved by Android. Unmapped buttons still appear in the raw input line below.", 16, Color.rgb(198, 220, 212)); note.setPadding(0, dp(14), 0, dp(14)); card.addView(note);
        button("Back", this::leaveTest).requestFocusFromTouch();
        card.addView(text("The projector or phone remote also works here.", 14, Color.rgb(166, 198, 186)));
    }
    private void leaveTest() { testExitSince = 0; if ("review".equals(testReturn) && calibration != null) showReview(); else showLive(); }
    private void checkTestExit() {
        ControllerProfile display = calibration == null ? profile : calibration.draft;
        if (display == null) return;
        ControllerProfile.State current = display.evaluate(heldKeys, rawAxes);
        boolean both = current.pressed(ControllerProfile.Control.PLUS) && current.pressed(ControllerProfile.Control.MINUS)
            || heldKeys.contains(KeyEvent.KEYCODE_BUTTON_START) && heldKeys.contains(KeyEvent.KEYCODE_BUTTON_SELECT);
        if (!both) { testExitSince = 0; return; }
        long now = SystemClock.uptimeMillis(); if (testExitSince == 0) testExitSince = now;
        if (now - testExitSince >= 1000) leaveTest();
    }
    private void showPreparation() {
        if (!connected()) { showDevices(); return; }
        phase = "prepare";
        layout("Calibrate controller", "We will learn the physical controls first. NES assignments are configured separately.");
        card.addView(text("Center both sticks and release every button.", 22, Color.WHITE));
        TextView note = text("Keep the controller still, then choose Begin. Follow the yellow highlight, one control at a time. The projector or phone remote can skip controls Android does not expose.", 16, Color.rgb(198, 220, 212));
        note.setPadding(0, dp(12), 0, dp(12)); card.addView(note);
        button("Begin calibration", this::beginCalibration).requestFocusFromTouch();
        button("Cancel", this::showLive);
        button("Reset saved hardware mapping…", this::showReset);
    }
    private Map<Integer, ControllerCalibration.Range> ranges(InputDevice device) {
        Map<Integer, ControllerCalibration.Range> result = new HashMap<>();
        if (device != null) for (InputDevice.MotionRange range : device.getMotionRanges()) {
            if ((range.getSource() & InputDevice.SOURCE_CLASS_JOYSTICK) != 0 && range.getMax() > range.getMin())
                result.put(range.getAxis(), new ControllerCalibration.Range(range.getMin(), range.getMax()));
        }
        return result;
    }
    private void beginCalibration() {
        if (!connected()) { showDevices(); return; }
        if (!heldKeys.isEmpty()) { pendingBegin = true; logInput("Release every controller button to begin calibration."); return; }
        pendingBegin = false;
        for (Map.Entry<Integer, ControllerCalibration.Range> entry : ranges(InputDevice.getDevice(selectedId)).entrySet()) {
            int axis = entry.getKey();
            if (axis == MotionEvent.AXIS_LTRIGGER || axis == MotionEvent.AXIS_RTRIGGER || axis == MotionEvent.AXIS_GAS || axis == MotionEvent.AXIS_BRAKE) continue;
            Float value = rawAxes.get(axis);
            if (value != null && entry.getValue().min < 0 && entry.getValue().max > 0 && Math.abs(value) > .25f) {
                pendingBegin = true; logInput("Center both sticks and release the D-pad to begin calibration."); return;
            }
        }
        Map<Integer, ControllerCalibration.Range> ranges = ranges(InputDevice.getDevice(selectedId));
        Map<Integer, Float> neutral = new HashMap<>();
        for (Map.Entry<Integer, ControllerCalibration.Range> entry : ranges.entrySet()) {
            int axis = entry.getKey();
            float fallback = axis == MotionEvent.AXIS_LTRIGGER || axis == MotionEvent.AXIS_RTRIGGER || axis == MotionEvent.AXIS_GAS || axis == MotionEvent.AXIS_BRAKE ? entry.getValue().min : 0;
            Float observed = rawAxes.get(axis); neutral.put(axis, observed == null ? fallback : observed);
        }
        calibration = new ControllerCalibration(selectedId, profile, neutral, ranges); phase = "calibrating";
        lastStep = -1; navigation.clear(); showCalibration();
    }
    private void showCalibration() {
        layout("Calibrate controller", null);
        stepProgress = text("", 14, Color.rgb(170, 209, 194)); card.addView(stepProgress);
        stepTitle = text("", 28, Color.rgb(255, 220, 127)); stepTitle.setTypeface(null, Typeface.BOLD); stepTitle.setPadding(0, dp(7), 0, dp(7)); card.addView(stepTitle);
        stepBody = text("", 19, Color.WHITE); stepBody.setMinHeight(dp(108)); card.addView(stepBody);
        skipButton = button("Skip this control", () -> { calibration.skip(); updateCalibration(); }); skipButton.requestFocusFromTouch();
        button("Cancel calibration", this::showCancel);
        TextView help = text("Choose Skip or Cancel with the projector / phone remote. Controller presses are recorded here, including + and B.", 13, Color.rgb(166, 198, 186));
        help.setPadding(0, dp(8), 0, 0); card.addView(help); updateCalibration();
    }
    private void updateCalibration() {
        if (calibration == null || !"calibrating".equals(phase)) return;
        if (calibration.complete()) { showReview(); return; }
        ControllerCalibration.Step step = calibration.current();
        stepProgress.setText("CONTROL " + (calibration.index + 1) + " OF " + ControllerCalibration.STEPS.length + "  ·  " + calibration.mappedCount + " mapped");
        stepTitle.setText(calibration.waitingForRelease ? "Release controls" : step.title);
        String message = calibration.waitingForRelease ? calibration.message : step.instruction;
        if (!connected()) message = "Controller disconnected. Cancel, reconnect, then choose it again to restart calibration.";
        else if (calibration.waitingForRelease && message.isEmpty()) message = "Release buttons, then move and center both sticks to continue.";
        else if (step.optional() && !calibration.waitingForRelease) message += " Android may reserve this button; skipping is normal.";
        stepBody.setText(message);
        skipButton.setEnabled(connected() && (!calibration.waitingForRelease || calibration.retryAfterRelease));
        if (lastStep != calibration.index || lastRelease != calibration.waitingForRelease) {
            lastStep = calibration.index; lastRelease = calibration.waitingForRelease; updateDiagram();
        }
    }
    private void showReview() {
        phase = "review";
        ControllerProfile.State held = calibration.draft.evaluate(heldKeys, rawAxes);
        reviewBits = held.pressedBits; reviewDirections = reviewDirections(held);
        layout("Review controller mapping", "Nothing changes until you save. Your calibrated A selects and B goes back. Choose Test to check every input.");
        card.addView(text(calibration.mappedCount + " of " + ControllerCalibration.STEPS.length + " controls mapped", 23, Color.rgb(255, 220, 127)));
        TextView note = text("Skipped controls remain unmapped. Home and Capture may be reserved by Android. Both calibrated sticks will navigate the receiver; NES uses the left stick unless you enable the right one.", 16, Color.rgb(198, 220, 212)); note.setPadding(0, dp(12), 0, dp(12)); card.addView(note);
        Button save = button("Save hardware mapping", () -> {
            if (!connected()) return;
            if (!store.save(InputDevice.getDevice(selectedId), calibration.draft)) {
                Toast.makeText(this, "Could not save this mapping. Your previous mapping is unchanged.", Toast.LENGTH_LONG).show(); return;
            }
            profile = calibration.draft.copy(); saved = true; calibration = null;
            Toast.makeText(this, "Controller mapping saved", Toast.LENGTH_SHORT).show(); showLive();
        }); save.setEnabled(calibration.mappedCount > 0 && connected()); save.requestFocusFromTouch();
        button("Test new mapping", () -> showTest("review"));
        button("Start calibration again", () -> { calibration = null; showPreparation(); });
        button("Discard changes", this::showLive);
    }
    private void showCancel() {
        if (calibration != null && !calibration.complete()) calibration.clearHeld();
        phase = "cancel"; layout("Discard calibration?", "Your previously saved controller mapping will stay as it is.");
        button("Continue calibration", () -> { phase = "calibrating"; showCalibration(); }).requestFocusFromTouch();
        button("Discard and return", this::showLive);
    }
    private void showReset() {
        phase = "reset"; layout("Reset hardware mapping?", "Restore Android's default button and stick mapping for this controller. NES assignments are also reset.");
        button("Keep current mapping", this::showLive).requestFocusFromTouch();
        button("Reset to defaults", () -> {
            if (connected()) store.reset(InputDevice.getDevice(selectedId)); showLive();
        });
    }
    private void showReceiverControls() {
        phase = "receiver"; layout("Receiver controls", "The saved physical mapping follows this controller throughout the receiver.");
        TextView controls = text("D-pad / either stick  ·  Navigate\nA / left stick click  ·  Select\nB  ·  Back\nX / − / right stick click  ·  Menu\n+  ·  Play / pause, or select when idle\nL / R  ·  Seek backward / forward\nZL / ZR  ·  Volume down / up", 18, Color.WHITE);
        controls.setLineSpacing(dp(6), 1); card.addView(controls);
        TextView note = text("Home and Capture may be handled by Android. Games use their own system assignments.", 14, Color.rgb(166, 198, 186)); note.setPadding(0, dp(12), 0, dp(12)); card.addView(note);
        button("Back to controller test", this::showLive).requestFocusFromTouch();
    }
    private void showSystemControls() {
        phase = "system";
        layout("Controller across apps", "Enable LUMA Controller across apps in Android Accessibility settings. Buttons use your saved calibration; analog sticks and D-pad hats keep Android's mappings outside LUMA.");
        try {
            org.json.JSONObject state = ControllerSystemService.status(app);
            card.addView(text("Across-app service: " + (state.optBoolean("enabled") ? "On" : "Off"), 19, Color.WHITE));
            card.addView(text(state.optBoolean("directNavigation") ? "Direct phone navigation: ready"
                : "For direct phone navigation, turn LUMA Controller off and on in Accessibility.", 16, Color.WHITE));
        } catch (Exception ignored) { }
        card.addView(text("Tap Home to return here. Hold Home for 3 seconds to put the controller to sleep, then send the projector power command. Release early to cancel power off.\n\nA selects · B goes back · X opens Menu · + plays/pauses · L/R seek · ZL/ZR change volume. Phone navigation uses screen controls locally; they are never stored or sent to the phone.", 16, Color.WHITE));
        button("Open Accessibility settings", () -> {
            String destination = ControllerSystemService.openSettings(this);
            if ("unavailable".equals(destination)) Toast.makeText(this,
                "Accessibility settings are unavailable on this firmware", Toast.LENGTH_LONG).show();
        }).requestFocusFromTouch();
        button("Back to controller test", this::showLive);
    }
    private static final ControllerProfile.Control[] ASSIGNABLE = {
        ControllerProfile.Control.A, ControllerProfile.Control.B, ControllerProfile.Control.X, ControllerProfile.Control.Y,
        ControllerProfile.Control.L, ControllerProfile.Control.R, ControllerProfile.Control.ZL, ControllerProfile.Control.ZR,
        ControllerProfile.Control.PLUS, ControllerProfile.Control.MINUS, ControllerProfile.Control.LCLICK, ControllerProfile.Control.RCLICK,
        ControllerProfile.Control.CAPTURE
    };
    private static final int[] NES_ACTIONS = {0, 1, 2, 4, 8, 256, 512, 1024, 2048};
    private static String actionName(int bit) {
        switch (bit) { case 1: return "NES A"; case 2: return "NES B"; case 4: return "Select"; case 8: return "Start"; case 256: return "Game menu"; case 512: return "Volume down"; case 1024: return "Volume up"; case 2048: return "Load latest checkpoint"; default: return "Unused"; }
    }
    private static String controlName(ControllerProfile.Control control) {
        switch (control) { case PLUS: return "+"; case MINUS: return "−"; case LCLICK: return "Left stick click"; case RCLICK: return "Right stick click"; case CAPTURE: return "Capture"; default: return control.name(); }
    }
    private void showAssignments() {
        if (!connected()) { showDevices(); return; }
        phase = "nes"; assignmentDraft = profile.copy(); renderAssignments();
    }
    private void renderAssignments() {
        layout("NES button assignments", "Select a row to cycle its NES action. These assignments do not change receiver navigation.");
        card.addView(text("D-pad + left stick  →  NES directions", 15, Color.rgb(255, 220, 127)));
        button("Use ZL / ZR for volume", () -> {
            assignmentDraft.nes.put(ControllerProfile.Control.ZL, ControllerProfile.VOLUME_DOWN);
            assignmentDraft.nes.put(ControllerProfile.Control.ZR, ControllerProfile.VOLUME_UP);
            renderAssignments();
            Toast.makeText(this, "ZL / ZR set to volume. Choose Save NES assignments to keep them.", Toast.LENGTH_LONG).show();
        });
        for (ControllerProfile.Control control : ASSIGNABLE) {
            int bit = assignmentDraft.nes.containsKey(control) ? assignmentDraft.nes.get(control) : 0;
            Button row = button(controlName(control) + "  →  " + actionName(bit), () -> {});
            row.setOnClickListener(view -> {
                int value = assignmentDraft.nes.containsKey(control) ? assignmentDraft.nes.get(control) : 0, index = 0;
                for (int i = 0; i < NES_ACTIONS.length; i++) if (NES_ACTIONS[i] == value) index = i;
                int next = NES_ACTIONS[(index + 1) % NES_ACTIONS.length];
                assignmentDraft.nes.put(control, next); row.setText(controlName(control) + "  →  " + actionName(next));
            });
        }
        Button rightStick = button("Right stick in NES: " + (assignmentDraft.rightStickInNes ? "On" : "Off"), () -> {});
        rightStick.setOnClickListener(view -> { assignmentDraft.rightStickInNes = !assignmentDraft.rightStickInNes; rightStick.setText("Right stick in NES: " + (assignmentDraft.rightStickInNes ? "On" : "Off")); });
        button("Save NES assignments", () -> {
            if (!connected()) return;
            if (!store.save(InputDevice.getDevice(selectedId), assignmentDraft)) {
                Toast.makeText(this, "Could not save NES assignments. Try again.", Toast.LENGTH_LONG).show(); return;
            }
            profile = assignmentDraft.copy();
            Toast.makeText(this, "NES assignments saved", Toast.LENGTH_SHORT).show(); showLive();
        });
        button("Discard changes", this::showLive);
        card.getChildAt(1).requestFocusFromTouch();
    }
    private void showDevices() {
        phase = "devices"; calibration = null; assignmentDraft = null;
        layout("Choose a controller", "Pair using Android Bluetooth settings. Calibration only listens to the controller selected here.");
        View first = null;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id); if (!isController(device)) continue;
            Button row = button(device.getName() + (id == selectedId ? "  ·  selected" : ""), () -> { select(device); showLive(); });
            if (first == null) first = row;
        }
        if (first == null) card.addView(text("No game controller detected yet.", 19, Color.WHITE));
        Button pair = button("Open Bluetooth settings", () -> {
            if (!ControllerInput.openSettings(this)) Toast.makeText(this, "Bluetooth settings are unavailable", Toast.LENGTH_LONG).show();
        });
        button("Refresh controllers", this::showDevices);
        button("Back", this::showLive); (first == null ? pair : first).requestFocusFromTouch();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (isController(event.getDevice()) || (event.getSource() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) {
            if (event.getDeviceId() != selectedId || !connected()) { noteOtherController(event.getDevice()); return true; }
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN && !event.isCanceled();
            if (down) heldKeys.add(event.getKeyCode()); else heldKeys.remove(event.getKeyCode());
            if (event.getRepeatCount() == 0) logInput(KeyEvent.keyCodeToString(event.getKeyCode()) + (down ? "  pressed" : "  released"));
            if (calibration != null && "calibrating".equals(phase)) {
                calibration.onKey(event.getDeviceId(), event.getKeyCode(), down, event.getRepeatCount()); updateCalibration();
            } else if ("review".equals(phase)) navigateReview();
            else if (!"live".equals(phase)) navigation.onKey(event);
            if (pendingBegin && "prepare".equals(phase) && !down && heldKeys.isEmpty()) beginCalibration();
            updateDiagram(); return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                goBack();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) return super.dispatchGenericMotionEvent(event);
        if (event.getDeviceId() != selectedId || !connected()) { noteOtherController(event.getDevice()); return true; }
        if (event.getActionMasked() != MotionEvent.ACTION_MOVE) return true;
        InputDevice device = event.getDevice(); boolean changed = false; StringBuilder values = new StringBuilder();
        for (InputDevice.MotionRange range : device.getMotionRanges()) {
            if ((range.getSource() & InputDevice.SOURCE_CLASS_JOYSTICK) == 0) continue;
            int axis = range.getAxis(); float value = event.getAxisValue(axis);
            Float previous = rawAxes.put(axis, value); if (previous == null || Math.abs(previous - value) > .08f) changed = true;
            if (Math.abs(value) > .12f && values.length() < 160) {
                if (values.length() > 0) values.append("  ·  ");
                values.append(MotionEvent.axisToString(axis).replace("AXIS_", "")).append(String.format(Locale.ROOT, " %+.2f", value));
            }
        }
        long now = SystemClock.uptimeMillis();
        if (changed && now - lastMotionTrace > 100) { lastMotionTrace = now; logInput(values.length() == 0 ? "Sticks centered" : values.toString()); }
        if (calibration != null && "calibrating".equals(phase)) { calibration.onMotion(event.getDeviceId(), rawAxes, now); updateCalibration(); }
        else if ("review".equals(phase)) navigateReview();
        else if (!"live".equals(phase)) navigation.onMotion(event);
        if (pendingBegin && "prepare".equals(phase) && heldKeys.isEmpty()) beginCalibration();
        updateDiagram(); return true;
    }
    private void navigate(int key, int action, int repeat) {
        ControllerInput.prepareNavigation(this, action);
        if (ControllerNavigation.direction(key) != 0) {
            ControllerNavigation.handle(this, key, action, repeat); return;
        }
        if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) key = KeyEvent.KEYCODE_DPAD_CENTER;
        if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_MENU) {
            if (action == KeyEvent.ACTION_DOWN && repeat == 0) goBack(); return;
        }
        if (key == KeyEvent.KEYCODE_HOME) {
            if (action == KeyEvent.ACTION_DOWN && repeat == 0) {
                app.playback.stop(); app.catMode.stop(); finish(); MainActivity.bringToFront(this);
            }
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (!super.dispatchKeyEvent(new KeyEvent(now, now, action, key, repeat)))
            ControllerNavigation.handle(this, key, action, repeat);
    }
    private void goBack() {
        pendingBegin = false;
        if ("calibrating".equals(phase)) showCancel();
        else if ("cancel".equals(phase)) { phase = "calibrating"; showCalibration(); }
        else if ("live".equals(phase)) leaveTest();
        else if ("overview".equals(phase)) finish();
        else if ("review".equals(phase)) showCancel();
        else showLive();
    }
    private void clickNavigation(int key) { navigate(key, KeyEvent.ACTION_DOWN, 0); navigate(key, KeyEvent.ACTION_UP, 0); }
    private void navigateReview() {
        if (calibration == null) return;
        ControllerProfile.State state = calibration.draft.evaluate(heldKeys, rawAxes);
        int pressed = state.pressedBits & ~reviewBits; reviewBits = state.pressedBits;
        if ((pressed & (1 << ControllerProfile.Control.A.ordinal() | 1 << ControllerProfile.Control.PLUS.ordinal() | 1 << ControllerProfile.Control.LCLICK.ordinal())) != 0) {
            clickNavigation(KeyEvent.KEYCODE_DPAD_CENTER); return;
        }
        if ((pressed & (1 << ControllerProfile.Control.B.ordinal())) != 0) { goBack(); return; }
        int directions = reviewDirections(state);
        int changed = directions & ~reviewDirections; reviewDirections = directions;
        if ((changed & ControllerProfile.UP) != 0) clickNavigation(KeyEvent.KEYCODE_DPAD_UP);
        if ((changed & ControllerProfile.DOWN) != 0) clickNavigation(KeyEvent.KEYCODE_DPAD_DOWN);
        if ((changed & ControllerProfile.LEFT) != 0) clickNavigation(KeyEvent.KEYCODE_DPAD_LEFT);
        if ((changed & ControllerProfile.RIGHT) != 0) clickNavigation(KeyEvent.KEYCODE_DPAD_RIGHT);
    }
    private int reviewDirections(ControllerProfile.State state) {
        int directions = ControllerProfile.stickDirections(state.axis(ControllerProfile.StickAxis.LEFT_X), state.axis(ControllerProfile.StickAxis.LEFT_Y))
            | ControllerProfile.stickDirections(state.axis(ControllerProfile.StickAxis.RIGHT_X), state.axis(ControllerProfile.StickAxis.RIGHT_Y));
        if (state.pressed(ControllerProfile.Control.UP)) directions |= ControllerProfile.UP;
        if (state.pressed(ControllerProfile.Control.DOWN)) directions |= ControllerProfile.DOWN;
        if (state.pressed(ControllerProfile.Control.LEFT)) directions |= ControllerProfile.LEFT;
        if (state.pressed(ControllerProfile.Control.RIGHT)) directions |= ControllerProfile.RIGHT;
        return directions;
    }
    private void noteOtherController(InputDevice device) {
        String name = device == null ? "Another controller" : device.getName();
        logInput(name + " ignored. Choose it explicitly to change controllers.");
    }
    private void logInput(String value) {
        lastInput = value; if (inputText != null) inputText.setText(value);
        if (trace.isEmpty() || !value.equals(trace.peekLast())) { trace.addLast(value); while (trace.size() > 20) trace.removeFirst(); }
    }
    private void updateDiagram() {
        if (diagram == null) return;
        ControllerProfile display = calibration == null ? profile : calibration.draft;
        String target = calibration != null && "calibrating".equals(phase) && calibration.current() != null ? calibration.current().target() : "";
        diagram.update(display, display == null ? null : display.evaluate(heldKeys, rawAxes), target, saved);
    }
    private void updateDeviceLabel() {
        int player = connected() ? players.player(InputDevice.getDevice(selectedId)) : 0;
        if (deviceText != null) deviceText.setText(selectedName + (selectedId < 0 ? "" : connected() ? "  ·  connected  ·  " + (player == 0 ? "Unassigned" : "Player " + player) : "  ·  disconnected")
            + ("live".equals(phase) || "calibrating".equals(phase) ? "  |  All controller inputs stay in this test" : "  |  Controller, remote or phone navigation"));
    }
    private static boolean isController(InputDevice device) {
        return device != null && (device.supportsSource(InputDevice.SOURCE_GAMEPAD) || device.supportsSource(InputDevice.SOURCE_JOYSTICK));
    }
    @Override public void onInputDeviceAdded(int id) {
        InputDevice added = InputDevice.getDevice(id);
        if (added != null && !connected() && selectedIdentity.equals(ControllerProfileStore.identity(added))) {
            boolean unfinished = calibration != null; select(added); showLive();
            if (unfinished) Toast.makeText(this, "Controller reconnected. Restart calibration; your saved mapping is unchanged.", Toast.LENGTH_LONG).show();
        } else { updateDeviceLabel(); if ("devices".equals(phase)) showDevices(); }
    }
    @Override public void onInputDeviceChanged(int id) {
        if (id == selectedId) { releaseInputs(); updateDeviceLabel(); }
    }
    @Override public void onInputDeviceRemoved(int id) {
        if (id == selectedId) { releaseInputs(); logInput("Controller disconnected. Reconnect it, or choose another controller."); updateDeviceLabel(); updateCalibration(); }
        else if ("devices".equals(phase)) showDevices();
    }
    JSONObject snapshot() {
        JSONObject result = new JSONObject();
        try {
            result.put("active", resumed).put("phase", phase).put("deviceId", selectedId).put("deviceName", selectedName)
                .put("connected", connected()).put("calibrated", saved).put("profileSaved", saved).put("lastInput", lastInput)
                .put("player", connected() ? players.player(InputDevice.getDevice(selectedId)) : JSONObject.NULL)
                .put("ledStatus", app.controllerLeds.status()).put("navigation", ControllerNavigation.snapshot(this))
                .put("profile", profile == null ? JSONObject.NULL : profile.toJson())
                .put("mappedCount", calibration == null ? countMappings(profile) : calibration.mappedCount)
                .put("currentTarget", calibration == null || calibration.current() == null ? JSONObject.NULL : calibration.current().target())
                .put("step", calibration == null ? 0 : calibration.index + 1).put("totalSteps", ControllerCalibration.STEPS.length)
                .put("waitingForRelease", calibration != null && calibration.waitingForRelease).put("trace", new JSONArray(trace));
            JSONObject axes = new JSONObject(); for (Map.Entry<Integer, Float> entry : rawAxes.entrySet()) axes.put(MotionEvent.axisToString(entry.getKey()), entry.getValue()); result.put("axes", axes);
            JSONArray keys = new JSONArray(); for (int key : heldKeys) keys.put(KeyEvent.keyCodeToString(key)); result.put("keys", keys);
        } catch (Exception ignored) { }
        return result;
    }
    private int countMappings(ControllerProfile value) {
        if (value == null) return 0; int count = value.sticks.size();
        for (java.util.List<ControllerProfile.Binding> bindings : value.buttons.values()) if (!bindings.isEmpty()) count++; return count;
    }
    Bitmap captureSnapshot() {
        if (root.getWidth() <= 0 || root.getHeight() <= 0) return null;
        float scale = Math.min(1f, 1280f / root.getWidth());
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, (int) (root.getWidth() * scale)), Math.max(1, (int) (root.getHeight() * scale)), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap); canvas.scale(scale, scale); root.draw(canvas); return bitmap;
    }
    private TextView text(String value, int size, int color) {
        TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(color); return text;
    }
    private Button button(String label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setTextSize(16); button.setTextColor(Color.WHITE);
        button.setBackgroundResource(R.drawable.launcher_tile); button.setPadding(dp(14), dp(7), dp(14), dp(7)); button.setMinHeight(dp(43)); button.setMinimumHeight(dp(43));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(0, dp(4), 0, dp(4)); card.addView(button, params);
        button.setOnClickListener(view -> action.run()); return button;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}

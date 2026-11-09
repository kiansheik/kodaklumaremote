package dev.framextv.lumaremote;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;

/** Activity-owned lifecycle and latest-value coalescing for the opt-in orientation pointer. */
final class AirMouseController implements SensorEventListener {
    interface Callback {
        void success(JSONObject value);
        void failure(String code, String message);
    }
    interface Requester { void request(String method, JSONObject params, Callback callback); }
    interface Listener { void changed(JSONObject status, boolean calibratedNow); }

    private static final long FRAME_MS = 55; // Bound foreground LAN pointer traffic to about 18 Hz.
    private static final long CAT_KEEPALIVE_MS = 500;
    private static final int DEFAULT_WIDTH = 1280, DEFAULT_HEIGHT = 720;

    private final SensorManager sensors;
    private final Sensor sensor;
    private final String sensorName;
    private final Requester requester;
    private final Listener listener;
    private final AirMouseFilter filter = new AirMouseFilter();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable delayedFlush = this::flush;
    private boolean foreground, usable, active, starting, writeInFlight, movePending, flushScheduled;
    private boolean clickPending, catModeActive;
    private Callback clickCallback;
    private int width = DEFAULT_WIDTH, height = DEFAULT_HEIGHT;
    private int pointerX = DEFAULT_WIDTH / 2, pointerY = DEFAULT_HEIGHT / 2;
    private int pendingX = pointerX, pendingY = pointerY;
    private long generation, lastWriteAt, lastCatPointerAt;
    private String inactiveState = "off";

    AirMouseController(Context context, Requester requester, Listener listener) {
        this.requester = requester;
        this.listener = listener;
        sensors = (SensorManager) context.getApplicationContext().getSystemService(Context.SENSOR_SERVICE);
        Sensor selected = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        String name = "game-rotation-vector";
        if (selected == null && sensors != null) {
            selected = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            name = "rotation-vector";
        }
        sensor = selected;
        sensorName = selected == null ? "" : name;
    }

    JSONObject status() {
        try {
            String state = active ? filter.isCalibrated() ? "ready" : "calibrating" : inactiveState;
            return new JSONObject().put("available", sensor != null).put("active", active)
                    .put("calibrated", active && filter.isCalibrated()).put("state", state)
                    .put("sensor", sensorName).put("catMode", catModeActive);
        } catch (Exception ignored) { return new JSONObject(); }
    }

    void setForeground(boolean value) {
        foreground = value;
        if (!foreground && (active || starting)) stop("off", true);
    }

    void setUsable(boolean value) {
        usable = value;
        if (!usable && (active || starting)) stop("disconnected", true);
    }

    /** Cat Mode stays autonomous while Air Mouse is off and follows this filtered pointer while on. */
    void setCatModeActive(boolean value) {
        if (catModeActive == value) return;
        boolean release = catModeActive && active;
        catModeActive = value;
        movePending = false;
        if (release) releaseCatControl();
        if (catModeActive && active && filter.isCalibrated()) {
            pendingX = pointerX; pendingY = pointerY; movePending = true; flush();
        }
        changed(false);
    }

    void start(Callback callback) {
        if (active) { callback.success(status()); return; }
        if (starting) { callback.failure("AIR_MOUSE_NOT_READY", "The air mouse is already starting."); return; }
        if (sensor == null) {
            inactiveState = "unavailable"; changed(false);
            callback.failure("AIR_MOUSE_UNAVAILABLE", "This phone does not expose a supported motion sensor."); return;
        }
        if (!foreground || !usable) {
            inactiveState = "disconnected"; changed(false);
            callback.failure("NOT_CONNECTED", "Connect to the projector before starting the air mouse."); return;
        }
        long epoch = ++generation;
        starting = true;
        requester.request("luma.airMouseInfo", new JSONObject(), new Callback() {
            @Override public void success(JSONObject value) {
                if (epoch != generation || !starting || !foreground || !usable) {
                    if (epoch == generation) starting = false;
                    callback.failure("AIR_MOUSE_NOT_READY", "Air-mouse startup was cancelled.");
                    return;
                }
                starting = false;
                int reportedWidth = value.optInt("width"), reportedHeight = value.optInt("height");
                width = reportedWidth >= 160 && reportedWidth <= 8192 ? reportedWidth : DEFAULT_WIDTH;
                height = reportedHeight >= 90 && reportedHeight <= 8192 ? reportedHeight : DEFAULT_HEIGHT;
                pointerX = pendingX = width / 2; pointerY = pendingY = height / 2;
                active = true; inactiveState = "off"; writeInFlight = movePending = false;
                clickPending = false; lastWriteAt = lastCatPointerAt = 0;
                filter.beginCalibration();
                if (!sensors.registerListener(AirMouseController.this, sensor, SensorManager.SENSOR_DELAY_GAME)) {
                    stop("unavailable", true);
                    callback.failure("AIR_MOUSE_UNAVAILABLE", "This phone could not start its motion sensor."); return;
                }
                changed(false); callback.success(status());
            }
            @Override public void failure(String code, String message) {
                if (epoch != generation || !starting) {
                    callback.failure("AIR_MOUSE_NOT_READY", "Air-mouse startup was cancelled.");
                    return;
                }
                starting = false;
                callback.failure(code, message);
            }
        });
    }

    void recenter(Callback callback) {
        if (!active || !usable) { callback.failure("AIR_MOUSE_NOT_READY", "Start the air mouse while the projector is connected."); return; }
        movePending = false; filter.beginCalibration(); changed(false); callback.success(status());
    }

    void click(Callback callback) {
        if (!active || !filter.isCalibrated() || !usable) { callback.failure("AIR_MOUSE_NOT_READY", "Wait for air-mouse calibration before clicking."); return; }
        if (clickPending || clickCallback != null) { callback.failure("BUSY", "Wait for the current click to finish."); return; }
        clickPending = true; clickCallback = callback; movePending = false; flush();
    }

    void stop(Callback callback) { stop("off", true); callback.success(status()); }

    void close() { stop("off", false); }

    private void stop(String state, boolean notify) {
        boolean releaseCat = active && catModeActive;
        generation++; active = false; starting = false; inactiveState = state;
        if (sensors != null) sensors.unregisterListener(this);
        filter.stop(); movePending = false; writeInFlight = false; clickPending = false;
        lastCatPointerAt = 0;
        Callback cancelledClick = clickCallback; clickCallback = null;
        flushScheduled = false; main.removeCallbacks(delayedFlush);
        if (cancelledClick != null) cancelledClick.failure("AIR_MOUSE_NOT_READY", "Air-mouse click was cancelled.");
        if (releaseCat) releaseCatControl();
        if (notify) changed(false);
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (!active || !foreground || !usable || event.sensor != sensor) return;
        AirMouseFilter.Result result = filter.accept(event.timestamp, event.values);
        if (result.calibratedNow) {
            pointerX = pendingX = scale(result.x, width); pointerY = pendingY = scale(result.y, height);
            movePending = true; changed(true); flush();
        } else if (result.moved && !clickPending) {
            pendingX = scale(result.x, width); pendingY = scale(result.y, height);
            movePending = true; flush();
        } else if (catModeActive && filter.isCalibrated() && !clickPending
                && SystemClock.elapsedRealtime() - lastCatPointerAt >= CAT_KEEPALIVE_MS) {
            // Manual control is a short lease on the receiver. A quiet, held
            // phone refreshes it; a paused/dead phone naturally returns to auto.
            pendingX = pointerX; pendingY = pointerY; movePending = true; flush();
        }
    }

    @Override public void onAccuracyChanged(Sensor changed, int accuracy) { }

    private static int scale(int coordinate, int extent) {
        return Math.max(0, Math.min(extent, (int) Math.round((double) coordinate * extent / AirMouseFilter.PAD_SIZE)));
    }

    private void flush() {
        flushScheduled = false;
        if (!active || !foreground || !usable || writeInFlight) return;
        boolean click;
        int action;
        if (clickPending) { clickPending = false; click = true; action = 0; }
        else if (movePending) { click = false; action = -1; }
        else return;
        long wait = action == -1 ? FRAME_MS - (SystemClock.elapsedRealtime() - lastWriteAt) : 0;
        if (wait > 0) {
            if (!flushScheduled) { flushScheduled = true; main.postDelayed(delayedFlush, wait); }
            return;
        }
        if (action == -1) { pointerX = pendingX; pointerY = pendingY; movePending = false; }
        int x = pointerX, y = pointerY;
        long epoch = generation;
        writeInFlight = true; lastWriteAt = SystemClock.elapsedRealtime();
        try {
            boolean cat = catModeActive;
            JSONObject params;
            String method;
            if (cat) {
                params = new JSONObject().put("x", normalized(x, width)).put("y", normalized(y, height));
                method = click ? "receiver.catPounce" : "receiver.catPointer";
                lastCatPointerAt = SystemClock.elapsedRealtime();
            } else {
                params = new JSONObject().put("x", x).put("y", y).put("action", action);
                method = click ? "luma.airMouseClick" : "luma.airMouse";
            }
            requester.request(method, params, new Callback() {
                @Override public void success(JSONObject value) {
                    if (!active || epoch != generation) return;
                    writeInFlight = false;
                    if (click) {
                        Callback completed = clickCallback; clickCallback = null;
                        if (completed != null) completed.success(status());
                    }
                    flush();
                }
                @Override public void failure(String code, String message) {
                    if (!active || epoch != generation) return;
                    writeInFlight = false;
                    if (click) {
                        Callback failed = clickCallback; clickCallback = null;
                        if (failed != null) failed.failure(code, message);
                    }
                    // Cat Mode is an optional receiver-side target. A dropped
                    // toy sample must not tear down a healthy Air Mouse session;
                    // the next filtered sample can retry.
                    if (cat) { flush(); return; }
                    stop("error", true);
                }
            });
        } catch (Exception invalid) { writeInFlight = false; stop("error", true); }
    }

    private static int normalized(int value, int extent) {
        return Math.max(0, Math.min(1000, (int) Math.round((double) value * 1000 / Math.max(1, extent))));
    }

    private void releaseCatControl() {
        try {
            requester.request("receiver.catRelease", new JSONObject(), new Callback() {
                @Override public void success(JSONObject value) { }
                @Override public void failure(String code, String message) { }
            });
        } catch (RuntimeException ignored) { }
    }

    private void changed(boolean calibratedNow) { listener.changed(status(), calibratedNow); }
}

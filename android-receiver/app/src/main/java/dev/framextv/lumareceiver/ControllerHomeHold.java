package dev.framextv.lumareceiver;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.widget.Toast;
import org.json.JSONObject;

final class ControllerHomeHold {
    private final ReceiverApp app;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final HomeHoldState hold = new HomeHoldState();
    private HidHostTransport transport;
    private boolean pending;
    private int generation;
    private String state = "idle";
    private final Runnable fire = () -> { if (hold.fire(SystemClock.uptimeMillis())) powerOff(hold.device()); };

    ControllerHomeHold(ReceiverApp app) { this.app = app; }
    void key(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && !event.isCanceled()) {
            if (hold.down(event.getDeviceId(), event.getRepeatCount(), SystemClock.uptimeMillis())) {
                Toast.makeText(app, "Hold Home for 3 seconds to turn off controller and projector", Toast.LENGTH_SHORT).show();
                handler.postDelayed(fire, HomeHoldState.HOLD_MS);
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            int device = hold.device();
            if (hold.up(event.getDeviceId(), event.isCanceled())) {
                app.stopGame(); app.closeGames(); app.catMode.stop(); app.playback.stop(); MainActivity.bringToFront(app);
            }
            if (device == event.getDeviceId()) handler.removeCallbacks(fire);
        }
    }
    void cancel(int device) { if (hold.device() == device) { hold.cancel(device); handler.removeCallbacks(fire); } }
    private void powerOff(int deviceId) {
        if (pending) return;
        InputDevice device = InputDevice.getDevice(deviceId);
        if (device == null || device.getVendorId() != 0x057e || device.getProductId() != 0x2009) {
            state = "unsupportedController"; return;
        }
        pending = true; int request = ++generation; state = "saving";
        app.stopGame(); app.closeGames(); app.catMode.stop(); app.playback.setPlaying(false);
        app.controllerLeds.close();
        state = "controllerSleepRequested";
        transport = new HidHostTransport(app, (result, detail) -> handler.post(() -> {
            if (!pending || request != generation) return;
            if (!"sentUnconfirmed".equals(result)) { finish("controllerSleepFailed"); return; }
            state = "controllerSleepSent";
            handler.postDelayed(() -> {
                if (!pending || request != generation) return;
                boolean queued = app.projectorKeys.send(KeyEvent.KEYCODE_POWER,
                    sent -> handler.post(() -> { if (pending && request == generation) finish(sent ? "projectorPowerSent" : "projectorPowerFailed"); }));
                if (!queued) finish("projectorPowerFailed");
            }, 500);
        }), "controller-sleep");
        transport.start(() -> "010000014040000140400600"); // Output 01, neutral rumble, HCI 06 00 (sleep).
        handler.postDelayed(() -> { if (pending && request == generation) finish("timeout"); }, 5000);
    }
    private void finish(String result) {
        pending = false; state = result;
        if (transport != null) { transport.close(); transport = null; }
        if (!"projectorPowerSent".equals(result))
            Toast.makeText(app, "Could not complete power off. Use the projector remote.", Toast.LENGTH_LONG).show();
    }
    JSONObject status() throws Exception { return new JSONObject().put("state", state).put("pending", pending).put("holdMs", HomeHoldState.HOLD_MS); }
}

package dev.framextv.lumareceiver;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.json.JSONObject;

/** Optional Android 9 HID-host output. Failure never changes controller input or pairing. */
final class ControllerLeds {
    private static final int HID_HOST = 4;
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;
    private BluetoothProfile proxy;
    private HidHostTransport transport;
    private int generation, counter, player;
    private String state = "notRequested", detail = "No light command requested";
    private boolean pending;
    private long requestedAt;

    ControllerLeds(Context context) { this.context = context.getApplicationContext(); }

    synchronized JSONObject request(InputDevice selected, int player) {
        if (pending) {
            if (player >= 1 && player <= 4 && selected != null && selected.getVendorId() == 0x057e
                && selected.getProductId() == 0x2009 && Build.VERSION.SDK_INT <= 30) this.player = player;
            return status();
        }
        this.player = player;
        if (player < 1 || player > 4) return outcome("invalidPlayer", "Choose player 1 to 4");
        if (selected == null || selected.getVendorId() != 0x057e || selected.getProductId() != 0x2009)
            return outcome("unsupportedController", "This light command is only for a Nintendo Pro Controller");
        if (Build.VERSION.SDK_INT > 30)
            return outcome("unsupportedAndroid", "This legacy HID-host route is limited to Android 11 and earlier");
        requestedAt = System.currentTimeMillis();
        int request = ++generation;
        pending = true; state = "openingHidHost"; detail = "Checking the existing Bluetooth HID connection";
        handler.post(() -> open(request));
        handler.postDelayed(() -> finish(request, "timeout", "The Bluetooth HID service did not complete the request"), 3000);
        return status();
    }

    private void open(int request) {
        synchronized (this) { if (!pending || generation != request) return; }
        try {
            adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) { finish(request, "bluetoothUnavailable", "Bluetooth is unavailable or off"); return; }
            boolean accepted = adapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
                @Override public void onServiceConnected(int profile, BluetoothProfile connected) {
                    synchronized (ControllerLeds.this) {
                        if (!pending || generation != request) { closeProxy(connected); return; }
                        proxy = connected;
                    }
                    send(request, connected);
                }
                @Override public void onServiceDisconnected(int profile) {
                    finish(request, "serviceDisconnected", "The Bluetooth HID service disconnected");
                }
            }, HID_HOST);
            if (!accepted) finish(request, "hidHostUnavailable", "The projector does not expose the Bluetooth HID host service");
        } catch (SecurityException denied) { finish(request, "permissionDenied", "Bluetooth access was denied"); }
        catch (RuntimeException unsupported) { finish(request, "hidHostUnavailable", "The Bluetooth HID host service is unavailable"); }
    }

    private void send(int request, BluetoothProfile connected) {
        try {
            BluetoothDevice target = null;
            for (BluetoothDevice device : connected.getConnectedDevices()) {
                if (!"Pro Controller".equalsIgnoreCase(device.getName())) continue;
                if (target != null) { finish(request, "ambiguous", "More than one Pro Controller is connected; no lights were changed"); return; }
                target = device;
            }
            if (target == null) { finish(request, "notConnected", "No connected Bluetooth Pro Controller was found"); return; }
            // AOSP9's host stack expects ASCII hexadecimal including the HID report ID.
            // No hidden-API policy is bypassed; an OEM restriction is reported normally.
            Method sendData = connected.getClass().getMethod("sendData", BluetoothDevice.class, String.class);
            boolean accepted = Boolean.TRUE.equals(sendData.invoke(connected, target,
                NintendoPlayerLights.reportHex(player, counter++ & 15)));
            finish(request, accepted ? "sentUnconfirmed" : "rejected", accepted
                ? "Bluetooth accepted the steady-player-light report; check the controller lights"
                : "Bluetooth rejected the player-light report");
        } catch (NoSuchMethodException | IllegalAccessException blocked) {
            if (Build.VERSION.SDK_INT == 28) openBinder(request);
            else finish(request, "hiddenApiUnavailable", "This firmware blocks or omits HID output access");
        } catch (InvocationTargetException failed) {
            finish(request, failed.getCause() instanceof SecurityException ? "permissionDenied" : "sendFailed", "The firmware did not accept HID output");
        } catch (SecurityException denied) { finish(request, "permissionDenied", "Bluetooth access was denied"); }
        catch (RuntimeException failed) { finish(request, "sendFailed", "The Bluetooth output request failed"); }
    }

    private synchronized void openBinder(int request) {
        if (!pending || generation != request) return;
        state = "openingHidBinder"; detail = "Trying the exported Android 9 HID service";
        BluetoothProfile previous = proxy; proxy = null; closeProxy(previous);
        transport = new HidHostTransport(context, (next, message) -> finish(request, next, message));
        transport.start(() -> {
            synchronized (ControllerLeds.this) { return NintendoPlayerLights.reportHex(player, counter++ & 15); }
        });
    }

    private synchronized void finish(int request, String state, String detail) {
        if (!pending || generation != request) return;
        pending = false; this.state = state; this.detail = detail;
        BluetoothProfile previous = proxy; proxy = null; closeProxy(previous);
        HidHostTransport previousTransport = transport; transport = null;
        if (previousTransport != null) previousTransport.close();
    }
    private void closeProxy(BluetoothProfile value) {
        if (value != null && adapter != null) try { adapter.closeProfileProxy(HID_HOST, value); }
        catch (RuntimeException ignored) { }
    }
    private JSONObject outcome(String state, String detail) {
        this.state = state; this.detail = detail; return status();
    }
    synchronized JSONObject status() {
        JSONObject result = new JSONObject();
        try { result.put("state", state).put("detail", detail).put("player", player)
            .put("pending", pending).put("requestedAt", requestedAt).put("confirmed", false); }
        catch (org.json.JSONException impossible) { }
        return result;
    }
    synchronized void close() {
        generation++; pending = false; handler.removeCallbacksAndMessages(null);
        BluetoothProfile previous = proxy; proxy = null; closeProxy(previous);
        HidHostTransport previousTransport = transport; transport = null;
        if (previousTransport != null) previousTransport.close();
    }
}

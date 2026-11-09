package dev.framextv.lumareceiver;

import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import java.util.List;

/** Android 9 AOSP HID-host IPC via public Binder APIs; service permissions still apply. */
final class HidHostTransport implements ServiceConnection {
    interface Callback { void result(String state, String detail); }
    private static final String DESCRIPTOR = "android.bluetooth.IBluetoothHidHost";
    // Verified against android-9.0.0_r1 system/bt/binder/android/bluetooth/IBluetoothHidHost.aidl.
    private static final int CONNECTED_DEVICES = IBinder.FIRST_CALL_TRANSACTION + 2;
    private static final int SEND_DATA = IBinder.FIRST_CALL_TRANSACTION + 12;
    private final Context context;
    private final Callback callback;
    private final String reportName;
    private boolean bound, finished;
    private java.util.function.Supplier<String> report;

    HidHostTransport(Context context, Callback callback) { this(context, callback, "player-light"); }
    HidHostTransport(Context context, Callback callback, String reportName) {
        this.context = context; this.callback = callback; this.reportName = reportName;
    }

    void start(java.util.function.Supplier<String> reportHex) {
        if (Build.VERSION.SDK_INT != 28) { complete("unsupportedAndroid", "Direct HID transport is verified only for Android 9"); return; }
        report = reportHex;
        try {
            Intent serviceIntent = new Intent(DESCRIPTOR).setPackage("com.android.bluetooth");
            List<ResolveInfo> matches = context.getPackageManager().queryIntentServices(serviceIntent, 0);
            ServiceInfo target = null;
            for (ResolveInfo match : matches) {
                ServiceInfo service = match.serviceInfo;
                if (service == null || !service.exported || !service.enabled || service.applicationInfo == null
                    || (service.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0
                    || !"com.android.bluetooth".equals(service.packageName)) continue;
                if (target != null) { complete("ambiguousService", "More than one system HID host service matched"); return; }
                target = service;
            }
            if (target == null) { complete("hidHostUnavailable", "No exported system HID host service is available"); return; }
            if (target.permission != null && context.checkSelfPermission(target.permission) != PackageManager.PERMISSION_GRANTED) {
                complete("permissionDenied", "The HID host service requires an unavailable permission"); return;
            }
            serviceIntent.setComponent(new ComponentName(target.packageName, target.name));
            bound = context.bindService(serviceIntent, this, Context.BIND_AUTO_CREATE);
            if (!bound) complete("hidHostUnavailable", "The system HID host service could not be bound");
        } catch (SecurityException denied) { complete("permissionDenied", "Binding the HID host service was denied"); }
        catch (RuntimeException failed) { complete("hidHostUnavailable", "The system HID host service is unavailable"); }
    }

    @Override public void onServiceConnected(ComponentName name, IBinder service) {
        synchronized (this) { if (finished) return; }
        // Binder calls never block the projector's UI or its timeout handler.
        new Thread(() -> send(service), "Pro Controller lights").start();
    }

    private void send(IBinder service) {
        try {
            if (!DESCRIPTOR.equals(service.getInterfaceDescriptor())) { complete("wrongService", "The service is not an Android HID host"); return; }
            BluetoothDevice target = null;
            for (BluetoothDevice device : connectedDevices(service)) {
                if (device == null || !"Pro Controller".equalsIgnoreCase(device.getName())) continue;
                if (target != null) { complete("ambiguous", "More than one Pro Controller is connected; no lights were changed"); return; }
                target = device;
            }
            if (target == null) { complete("notConnected", "No connected Bluetooth Pro Controller was found"); return; }
            synchronized (this) { if (finished) return; }
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeInt(1); target.writeToParcel(data, 0); data.writeString(report.get());
                if (!service.transact(SEND_DATA, data, reply, 0)) { complete("transactionUnavailable", "The firmware does not implement the Android 9 HID output transaction"); return; }
                reply.readException();
                boolean accepted = reply.readInt() != 0;
                complete(accepted ? "sentUnconfirmed" : "rejected", accepted
                    ? "Bluetooth accepted the " + reportName + " report through its HID service"
                    : "Bluetooth rejected the " + reportName + " report");
            } finally { reply.recycle(); data.recycle(); }
        } catch (SecurityException denied) { complete("permissionDenied", "The HID service denied the Bluetooth output request"); }
        catch (RemoteException | RuntimeException failed) { complete("sendFailed", "The HID service did not complete the output request"); }
    }

    private List<BluetoothDevice> connectedDevices(IBinder service) throws RemoteException {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (!service.transact(CONNECTED_DEVICES, data, reply, 0)) throw new RemoteException("Unsupported device inventory transaction");
            reply.readException();
            List<BluetoothDevice> devices = reply.createTypedArrayList(BluetoothDevice.CREATOR);
            return devices == null ? java.util.Collections.emptyList() : devices;
        } finally { reply.recycle(); data.recycle(); }
    }
    private void complete(String state, String detail) {
        synchronized (this) {
            if (finished) return;
            finished = true;
        }
        callback.result(state, detail);
        synchronized (this) { unbind(); }
    }
    synchronized void close() { finished = true; unbind(); }
    private void unbind() {
        if (!bound) return;
        bound = false;
        try { context.unbindService(this); } catch (IllegalArgumentException ignored) { }
    }
    @Override public void onServiceDisconnected(ComponentName name) { complete("serviceDisconnected", "The HID service disconnected"); }
    @Override public void onBindingDied(ComponentName name) { complete("serviceDisconnected", "The HID service binding ended"); }
    @Override public void onNullBinding(ComponentName name) { complete("hidHostUnavailable", "The HID host returned no service"); }
}

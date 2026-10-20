package dev.framextv.lumaremote;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Looper;
import android.util.Base64;
import chip.devicecontroller.ChipClusters;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.json.JSONObject;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Isolated emulator checks. No pairing codes, TV controls or receiver writes. */
public final class MatterInstrumentation {
    private static final String KEY = "fixture-matter-secret", SECRET = "fixture-fabric-value";
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void passed(Instrumentation runner, int count, String name) {
        Bundle progress = new Bundle(); progress.putInt("current", count); progress.putString("test", name); runner.sendStatus(0, progress);
    }
    private static void field(MatterControllerAdapter adapter, String name, Object value) throws Exception {
        Field field = MatterControllerAdapter.class.getDeclaredField(name); field.setAccessible(true); field.set(adapter, value);
    }
    private static JSONObject call(MatterControllerAdapter adapter, String method, JSONObject args, String expectedError) throws Exception {
        CountDownLatch complete = new CountDownLatch(1); AtomicReference<JSONObject> result = new AtomicReference<>(); AtomicReference<String> error = new AtomicReference<>();
        adapter.request(method, args, new MatterControllerAdapter.Callback() {
            public void success(JSONObject value) { require(Looper.myLooper() == Looper.getMainLooper(), "Callback was not on main thread"); result.set(value); complete.countDown(); }
            public void failure(String code, String text) { require(Looper.myLooper() == Looper.getMainLooper(), "Failure was not on main thread"); error.set(code); complete.countDown(); }
        });
        require(complete.await(10, TimeUnit.SECONDS), "Adapter callback timed out");
        require(expectedError == null ? error.get() == null : expectedError.equals(error.get()), "Unexpected adapter outcome");
        return result.get();
    }
    private static final class PowerCluster implements MatterControllerAdapter.PowerOnCluster {
        List<Long> commands = Arrays.asList(0L, 1L, 2L);
        final List<String> calls = new ArrayList<>(); boolean failCapabilities;
        ChipClusters.DefaultClusterCallback on;
        ChipClusters.BooleanAttributeCallback readback;
        public void readAcceptedCommands(ChipClusters.OnOffCluster.AcceptedCommandListAttributeCallback callback) {
            calls.add("capabilities");
            if (failCapabilities) callback.onError(new IllegalStateException("owned read failure")); else callback.onSuccess(commands);
        }
        public void on(ChipClusters.DefaultClusterCallback callback) { calls.add("on"); on = callback; }
        public void readOnOff(ChipClusters.BooleanAttributeCallback callback) { calls.add("readback"); readback = callback; }
    }
    private static final class PowerReceipt implements MatterControllerAdapter.PowerOnResult {
        boolean current = true; int accepted, acknowledgements; Boolean onOff; String error; List<Long> capabilities;
        public boolean current() { return current; }
        public void capabilities(List<Long> commands) { capabilities = new ArrayList<>(commands); }
        public void acknowledged() { acknowledgements++; }
        public void accepted(Boolean value) { accepted++; onOff = value; }
        public void rejected(String code) { error = code; }
    }
    private static void powerOnProtocol() {
        PowerCluster unsupported = new PowerCluster(); unsupported.commands = Arrays.asList(0L, 2L);
        PowerReceipt denied = new PowerReceipt(); MatterControllerAdapter.requestPowerOn(unsupported, denied);
        require(unsupported.calls.equals(Arrays.asList("capabilities")) && "MATTER_UNSUPPORTED".equals(denied.error)
                && denied.accepted == 0 && !denied.capabilities.contains(1L), "On bypassed a fresh command list without discrete On support");
        PowerCluster missing = new PowerCluster(); missing.failCapabilities = true;
        PowerReceipt unavailable = new PowerReceipt(); MatterControllerAdapter.requestPowerOn(missing, unavailable);
        require(missing.on == null && "MATTER_UNAVAILABLE".equals(unavailable.error) && unavailable.capabilities.isEmpty(), "Unreadable capabilities still invoked power On");
        PowerCluster supported = new PowerCluster(); PowerReceipt observed = new PowerReceipt();
        MatterControllerAdapter.requestPowerOn(supported, observed);
        require(supported.calls.equals(Arrays.asList("capabilities", "on")) && observed.accepted == 0, "Discrete On claimed success before command ACK");
        supported.on.onSuccess();
        require(supported.calls.equals(Arrays.asList("capabilities", "on", "readback")) && observed.accepted == 0 && observed.acknowledgements == 1, "Power state was fabricated from command ACK or the known ACK was not retained");
        supported.readback.onSuccess(true); supported.on.onSuccess(); supported.readback.onSuccess(false);
        require(observed.accepted == 1 && Boolean.TRUE.equals(observed.onOff) && supported.calls.size() == 3 && observed.error == null, "Successful readback was lost or duplicate callbacks replayed On/readback");
        PowerCluster noReadback = new PowerCluster(); PowerReceipt unknown = new PowerReceipt();
        MatterControllerAdapter.requestPowerOn(noReadback, unknown); noReadback.on.onSuccess(); noReadback.readback.onError(new IllegalStateException("owned readback failure"));
        require(unknown.accepted == 1 && unknown.onOff == null && unknown.error == null && noReadback.calls.size() == 3, "Readback failure discarded the real ACK or invented powered state");
        PowerCluster stillOff = new PowerCluster(); PowerReceipt off = new PowerReceipt();
        MatterControllerAdapter.requestPowerOn(stillOff, off); stillOff.on.onSuccess(); stillOff.readback.onSuccess(false);
        require(off.accepted == 1 && Boolean.FALSE.equals(off.onOff), "Observed Off was coerced into powered On");
        PowerCluster rejected = new PowerCluster(); PowerReceipt failure = new PowerReceipt();
        MatterControllerAdapter.requestPowerOn(rejected, failure); rejected.on.onError(new IllegalStateException("owned command failure"));
        require(failure.accepted == 0 && "MATTER_REJECTED".equals(failure.error) && rejected.calls.equals(Arrays.asList("capabilities", "on")), "Failed or unknown On automatically retried or reached readback");
        PowerCluster cancelled = new PowerCluster(); PowerReceipt stale = new PowerReceipt();
        MatterControllerAdapter.requestPowerOn(cancelled, stale); stale.current = false; cancelled.on.onSuccess();
        require(stale.accepted == 0 && cancelled.readback == null && cancelled.calls.size() == 2, "Cancelled power operation started a later read or reported success");
    }
    private static void powerOnWatchdog(MatterControllerAdapter adapter) throws Exception {
        // Exercise the real request watchdog with an owned silent-read fixture.
        // No native pointer is acquired; nulling the controller prevents the fake
        // pointer marker from ever reaching JNI when its late callback releases.
        Field controller = MatterControllerAdapter.class.getDeclaredField("controller"); controller.setAccessible(true);
        Object nativeController = controller.get(adapter); controller.set(adapter, null);
        try {
            Field generation = MatterControllerAdapter.class.getDeclaredField("generation"); generation.setAccessible(true);
            Class<?> requestType = Class.forName("dev.framextv.lumaremote.MatterControllerAdapter$Request");
            java.lang.reflect.Constructor<?> constructor = requestType.getDeclaredConstructor(MatterControllerAdapter.class, MatterControllerAdapter.Callback.class, long.class, long.class); constructor.setAccessible(true);
            Field pointer = requestType.getDeclaredField("pointer"), acknowledged = requestType.getDeclaredField("powerOnAcknowledged"); pointer.setAccessible(true); acknowledged.setAccessible(true);
            CountDownLatch completed = new CountDownLatch(1); AtomicReference<JSONObject> receipt = new AtomicReference<>(); AtomicReference<String> failure = new AtomicReference<>();
            java.util.concurrent.atomic.AtomicInteger completions = new java.util.concurrent.atomic.AtomicInteger();
            MatterControllerAdapter.Callback callback = new MatterControllerAdapter.Callback() {
                public void success(JSONObject value) { require(Looper.myLooper() == Looper.getMainLooper(), "Watchdog receipt was not on main thread"); receipt.set(value); completions.incrementAndGet(); completed.countDown(); }
                public void failure(String code, String message) { failure.set(code); completions.incrementAndGet(); completed.countDown(); }
            };
            Object request = constructor.newInstance(adapter, callback, generation.getLong(adapter), 200L);
            pointer.setLong(request, 0x1234L); acknowledged.setBoolean(request, true);
            require(completed.await(3, TimeUnit.SECONDS), "Known On ACK was stranded by a silent readback");
            require(failure.get() == null && receipt.get() != null && receipt.get().getBoolean("accepted") && !receipt.get().getBoolean("powerStateVerified")
                    && receipt.get().isNull("onOff"), "Known On ACK became an unknown timeout or invented power state");
            require(pointer.getLong(request) == 0x1234L, "Watchdog released a pointer while its SDK read could still be outstanding");
            java.lang.reflect.Method guard = MatterControllerAdapter.class.getDeclaredMethod("guard", requestType); guard.setAccessible(true);
            require(Boolean.FALSE.equals(guard.invoke(adapter, request)) && pointer.getLong(request) == 0, "Late callback failed to release the completed request pointer");
            java.lang.reflect.Method success = requestType.getDeclaredMethod("success", JSONObject.class); success.setAccessible(true); success.invoke(request, new JSONObject());
            require(completions.get() == 1, "Late callback completed the acknowledged power request twice");
            CountDownLatch cancelled = new CountDownLatch(1); AtomicReference<String> cancellation = new AtomicReference<>();
            MatterControllerAdapter.Callback cancelledCallback = new MatterControllerAdapter.Callback() {
                public void success(JSONObject value) { cancellation.set("UNEXPECTED_SUCCESS"); cancelled.countDown(); }
                public void failure(String code, String message) { cancellation.set(code); cancelled.countDown(); }
            };
            Object stale = constructor.newInstance(adapter, cancelledCallback, generation.getLong(adapter) - 1, 200L);
            acknowledged.setBoolean(stale, true);
            require(cancelled.await(3, TimeUnit.SECONDS) && "CANCELLED".equals(cancellation.get()), "Cancellation was replaced by a stale acknowledged-On success");
        } finally { controller.set(adapter, nativeController); }
    }
    public static void run(Instrumentation runner) {
        int checks = 0; boolean stagedNode = false; Bundle result = new Bundle(); MatterControllerAdapter adapter = null;
        Context context = runner.getTargetContext();
        SharedPreferences encrypted = context.getSharedPreferences("matter-secure-fabric", Context.MODE_PRIVATE);
        try {
            require(context.getPackageName().endsWith(".receiverfixture"), "Use the isolated receiver fixture package");
            require(context.getSharedPreferences("matter-controller", Context.MODE_PRIVATE).getLong("node", 0) == 0, "Fixture unexpectedly has a paired node");
            MatterControllerAdapter.EncryptedStorage storage = new MatterControllerAdapter.EncryptedStorage(context);
            storage.set(KEY, SECRET);
            String first = encrypted.getString(KEY, "");
            require(!first.contains(SECRET), "Fabric value was stored in plaintext");
            require(SECRET.equals(new MatterControllerAdapter.EncryptedStorage(context).get(KEY)), "Encrypted fabric did not survive a new store");
            storage.set(KEY, SECRET);
            require(!first.equals(encrypted.getString(KEY, "")), "Fabric envelope reused its nonce");
            passed(runner, ++checks, "fabric values persist through fresh stores using authenticated Keystore encryption");

            String intact = encrypted.getString(KEY, ""); String[] fields = intact.split(":", 2);
            byte[] damaged = Base64.decode(fields[1], Base64.NO_WRAP); damaged[damaged.length - 1] ^= 1;
            encrypted.edit().putString(KEY, fields[0] + ":" + Base64.encodeToString(damaged, Base64.NO_WRAP)).commit();
            try { storage.get(KEY); throw new AssertionError("Modified fabric accepted"); } catch (IllegalStateException expected) { }
            try { new MatterControllerAdapter.EncryptedStorage(context); throw new AssertionError("Modified fabric reached native initialization"); } catch (IllegalStateException expected) { }
            require(encrypted.contains(KEY), "Storage silently deleted an unreadable fabric value");
            encrypted.edit().putString(KEY, intact).commit(); require(SECRET.equals(storage.get(KEY)), "Valid storage could not be restored");
            passed(runner, ++checks, "modified fabric ciphertext fails closed without clearing stored state");

            String payload = "MT:FIXTURE-QR-ONLY";
            String image = MatterControllerAdapter.qrPng(payload);
            require(image.startsWith("data:image/png;base64,"), "Share QR did not use a PNG data URL");
            byte[] bytes = Base64.decode(image.substring(image.indexOf(',') + 1), Base64.NO_WRAP);
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length); int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
            bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
            String decoded = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(bitmap.getWidth(), bitmap.getHeight(), pixels)))).getText();
            bitmap.recycle(); require(payload.equals(decoded), "Share QR did not encode the actual payload");
            require(!context.getSharedPreferences("matter-controller", Context.MODE_PRIVATE).getAll().toString().contains(payload), "Share QR was persisted");
            passed(runner, ++checks, "share PNG decodes to its transient QR payload");

            CountDownLatch initialized = new CountDownLatch(1);
            adapter = new MatterControllerAdapter(context, state -> { if (state.optBoolean("available")) initialized.countDown(); });
            require(initialized.await(20, TimeUnit.SECONDS), "Current JNI controller or production trust assets failed initialization");
            require(adapter.snapshot().getBoolean("available") && !adapter.snapshot().getBoolean("paired"), "Fresh controller state was incorrect");
            require(!adapter.snapshot().getJSONObject("capabilities").getBoolean("powerOn"), "Fresh controller advertised unobserved power On");
            passed(runner, ++checks, "current JNI controller and production attestation roots initialize on Android");

            call(adapter, "matter.pair", new JSONObject().put("setupCode", "invalid fixture"), "INVALID_INPUT");
            call(adapter, "matter.powerOff", new JSONObject().put("confirmed", false), "INVALID_INPUT");
            call(adapter, "matter.powerOff", new JSONObject().put("confirmed", "true"), "INVALID_INPUT");
            call(adapter, "matter.powerOn", new JSONObject().put("confirmed", true), "INVALID_INPUT");
            call(adapter, "matter.powerOn", new JSONObject().put("on", true), "INVALID_INPUT");
            call(adapter, "matter.powerOn", new JSONObject(), "MATTER_UNSUPPORTED");
            field(adapter, "powerEndpoint", 1); field(adapter, "powerOnSupported", true);
            call(adapter, "matter.powerOn", new JSONObject(), "MATTER_NOT_PAIRED");
            field(adapter, "powerEndpoint", -1); field(adapter, "powerOnSupported", false);
            powerOnProtocol();
            powerOnWatchdog(adapter);
            call(adapter, "matter.forget", new JSONObject().put("confirmed", "true"), "INVALID_INPUT");
            call(adapter, "matter.key", new JSONObject().put("key", "unsupported"), "INVALID_INPUT");
            require(!adapter.snapshot().getBoolean("pairing"), "Invalid input started commissioning");
            passed(runner, ++checks, "invalid hardware requests stay inert; discrete On requires fresh support, ACK and truthful optional readback");

            field(adapter, "pairing", true); field(adapter, "pairingNode", 0x7fff1234L);
            call(adapter, "matter.disconnect", new JSONObject(), null);
            JSONObject paused = call(adapter, "matter.status", new JSONObject().put("background", true), null);
            require(paused.getBoolean("paused") && !paused.getBoolean("connected"), "Background status resumed a disconnected controller");
            require(SECRET.equals(storage.get(KEY)), "Disconnect removed encrypted fabric state");
            JSONObject resumed = call(adapter, "matter.status", new JSONObject().put("reconnect", true), null);
            require(!resumed.getBoolean("paused"), "Explicit reconnect did not release logical pause");
            adapter.close();
            CountDownLatch recreated = new CountDownLatch(1);
            adapter = new MatterControllerAdapter(context, state -> { if (state.optBoolean("available")) recreated.countDown(); });
            require(recreated.await(20, TimeUnit.SECONDS), "Service recreation failed to reuse the process-wide controller");
            require(SECRET.equals(storage.get(KEY)), "Service recreation replaced encrypted fabric storage");
            passed(runner, ++checks, "disconnect and service recreation preserve storage while reconnect is explicit");

            Map<String, ?> lumaBefore = new HashMap<>(context.getSharedPreferences("luma_settings", Context.MODE_PRIVATE).getAll());
            SharedPreferences saved = context.getSharedPreferences("matter-controller", Context.MODE_PRIVATE);
            require(saved.edit().putLong("node", 0x7fff1234L).commit(), "Could not prepare the isolated stale-node fixture");
            stagedNode = true;
            field(adapter, "nodeId", 0x7fff1234L); field(adapter, "available", false);
            field(adapter, "currentVolume", 42); field(adapter, "currentInput", 3);
            field(adapter, "powerOnSupported", true);
            call(adapter, "matter.forget", new JSONObject().put("confirmed", true), "MATTER_UNAVAILABLE");
            require(saved.contains("node"), "Failed TV removal erased the saved node");
            call(adapter, "matter.forget", new JSONObject().put("confirmed", true).put("localOnly", "true"), "INVALID_INPUT");
            require(saved.contains("node"), "Non-boolean local-only confirmation erased the saved node");
            JSONObject forgotten = call(adapter, "matter.forget", new JSONObject().put("confirmed", true).put("localOnly", true), null);
            require(!forgotten.getBoolean("paired") && "LOCAL_ONLY".equals(forgotten.getString("resetReason")) && !forgotten.getBoolean("tvFabricRemoved"), "Local recovery falsely claimed TV fabric removal");
            require(forgotten.has("volume") && forgotten.isNull("volume") && forgotten.has("currentInput") && forgotten.isNull("currentInput"), "Local recovery retained stale volume or input state");
            require(!forgotten.getJSONObject("capabilities").getBoolean("powerOn"), "Forgotten TV retained stale power On support");
            require(!saved.contains("node") && SECRET.equals(storage.get(KEY)), "Local recovery changed controller identity or retained the node");
            require(lumaBefore.equals(context.getSharedPreferences("luma_settings", Context.MODE_PRIVATE).getAll()), "Local TV recovery changed LUMA or Kodi setup");
            field(adapter, "available", true);
            call(adapter, "matter.pair", new JSONObject().put("setupCode", "invalid fixture"), "INVALID_INPUT");
            passed(runner, ++checks, "explicit offline local recovery clears only the TV binding without claiming remote revocation");

            result.putInt("passed", checks); result.putInt("failures", 0); runner.finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putInt("passed", checks); result.putInt("failures", 1); result.putString("failure", error.getClass().getSimpleName()); runner.finish(Activity.RESULT_CANCELED, result);
        } finally {
            if (adapter != null) adapter.close(); encrypted.edit().remove(KEY).commit();
            if (stagedNode) context.getSharedPreferences("matter-controller", Context.MODE_PRIVATE).edit().remove("node").commit();
        }
    }
}

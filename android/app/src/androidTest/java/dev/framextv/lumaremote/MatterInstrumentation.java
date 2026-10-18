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
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.json.JSONObject;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
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
            passed(runner, ++checks, "current JNI controller and production attestation roots initialize on Android");

            call(adapter, "matter.pair", new JSONObject().put("setupCode", "invalid fixture"), "INVALID_INPUT");
            call(adapter, "matter.powerOff", new JSONObject().put("confirmed", false), "INVALID_INPUT");
            call(adapter, "matter.powerOff", new JSONObject().put("confirmed", "true"), "INVALID_INPUT");
            call(adapter, "matter.forget", new JSONObject().put("confirmed", "true"), "INVALID_INPUT");
            call(adapter, "matter.key", new JSONObject().put("key", "unsupported"), "INVALID_INPUT");
            require(!adapter.snapshot().getBoolean("pairing"), "Invalid input started commissioning");
            passed(runner, ++checks, "invalid pairing and unconfirmed hardware requests never start commissioning");

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
            call(adapter, "matter.forget", new JSONObject().put("confirmed", true), "MATTER_UNAVAILABLE");
            require(saved.contains("node"), "Failed TV removal erased the saved node");
            call(adapter, "matter.forget", new JSONObject().put("confirmed", true).put("localOnly", "true"), "INVALID_INPUT");
            require(saved.contains("node"), "Non-boolean local-only confirmation erased the saved node");
            JSONObject forgotten = call(adapter, "matter.forget", new JSONObject().put("confirmed", true).put("localOnly", true), null);
            require(!forgotten.getBoolean("paired") && "LOCAL_ONLY".equals(forgotten.getString("resetReason")) && !forgotten.getBoolean("tvFabricRemoved"), "Local recovery falsely claimed TV fabric removal");
            require(forgotten.has("volume") && forgotten.isNull("volume") && forgotten.has("currentInput") && forgotten.isNull("currentInput"), "Local recovery retained stale volume or input state");
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

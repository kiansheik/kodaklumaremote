package dev.framextv.lumaremote;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Base64;
import org.json.JSONObject;
import java.security.KeyStore;

/** Emulator-only saved configuration checks; no receiver or network access. */
public final class SettingsInstrumentation {
    private static final String USER = "fixture-user", PASSWORD = "fixture-secret-value";
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static JSONObject credentials() throws Exception {
        return new JSONObject().put("username", USER).put("password", PASSWORD).put("port", 8080);
    }
    private static void passed(Instrumentation runner, int count, String name) {
        Bundle progress = new Bundle(); progress.putInt("current", count); progress.putString("test", name); runner.sendStatus(0, progress);
    }
    public static void run(Instrumentation runner) {
        SettingsStore store = new SettingsStore(runner.getTargetContext()); Bundle result = new Bundle(); int checks = 0;
        try {
            store.clear("all");
            store.saveReceiver("192.168.1.50", "Fixture LUMA"); store.saveKodi(credentials());
            SettingsStore restored = new SettingsStore(runner.getTargetContext());
            require(restored.metadata().getBoolean("hasReceiver") && restored.metadata().getBoolean("hasKodi"), "Saved configuration missing");
            require("192.168.1.50".equals(restored.readReceiver().getString("host")), "Receiver cache did not survive a new store");
            require(USER.equals(restored.readKodi().getString("username")) && PASSWORD.equals(restored.readKodi().getString("password")), "Credentials did not round-trip");
            passed(runner, ++checks, "saved receiver and encrypted credentials survive a new store instance");

            SharedPreferences raw = runner.getTargetContext().getSharedPreferences("luma_settings", Context.MODE_PRIVATE);
            String first = raw.getString("kodi.encrypted.v1", "");
            require(!first.contains(USER) && !first.contains(PASSWORD), "Plaintext credential persisted");
            KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
            require(keys.containsAlias("luma.kodi.credentials.v1"), "Keystore key missing");
            store.saveKodi(credentials()); require(!first.equals(raw.getString("kodi.encrypted.v1", "")), "Encryption reused its envelope");
            passed(runner, ++checks, "credentials use Keystore with a fresh authenticated encryption envelope");

            JSONObject damaged = new JSONObject(raw.getString("kodi.encrypted.v1", ""));
            byte[] bytes = Base64.decode(damaged.getString("data"), Base64.NO_WRAP); bytes[bytes.length - 1] ^= 1;
            damaged.put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)); raw.edit().putString("kodi.encrypted.v1", damaged.toString()).commit();
            try { restored.readKodi(); throw new AssertionError("Modified credentials were accepted"); }
            catch (SettingsStore.SettingsException expected) { require(expected.code.equals("STORAGE_UNAVAILABLE"), "Unexpected storage failure"); }
            store.saveKodi(credentials()); require(PASSWORD.equals(restored.readKodi().getString("password")), "Storage could not recover after replacing invalid data");
            passed(runner, ++checks, "modified encrypted storage fails closed and can be replaced");

            store.clear("receiver"); require(!store.metadata().getBoolean("hasReceiver") && store.metadata().getBoolean("hasKodi"), "Receiver reset erased credentials");
            store.saveReceiver("192.168.1.50", "Fixture LUMA"); store.clear("kodi");
            require(store.metadata().getBoolean("hasReceiver") && !store.metadata().getBoolean("hasKodi") && store.readKodi() == null, "Kodi reset erased receiver or retained credentials");
            keys.load(null); require(!keys.containsAlias("luma.kodi.credentials.v1"), "Kodi reset retained key");
            passed(runner, ++checks, "reset scopes preserve unrelated setup and remove the credential key");

            try { store.saveReceiver("8.8.8.8", "Invalid"); throw new AssertionError("Public receiver cached"); }
            catch (SettingsStore.SettingsException expected) { require(expected.code.equals("INVALID_INPUT"), "Wrong invalid-receiver error"); }
            try { store.saveKodi(credentials().put("port", 0)); throw new AssertionError("Invalid login persisted"); }
            catch (SettingsStore.SettingsException expected) { require(expected.code.equals("INVALID_INPUT"), "Wrong invalid-login error"); }
            store.clear("all"); require(!store.metadata().getBoolean("hasReceiver") && !store.metadata().getBoolean("hasKodi"), "Reset all retained setup");
            passed(runner, ++checks, "invalid settings do not replace saved setup and reset all clears it");
            result.putInt("passed", checks); result.putInt("failures", 0); runner.finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putInt("passed", checks); result.putInt("failures", 1); result.putString("failure", error.getClass().getSimpleName()); runner.finish(Activity.RESULT_CANCELED, result);
        } finally { try { store.clear("all"); } catch (Exception ignored) { } }
    }
}

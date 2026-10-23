package dev.framextv.lumaremote;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.security.KeyStore;
import javax.crypto.KeyGenerator;

/** Emulator-only saved configuration checks; no receiver or network access. */
public final class SettingsInstrumentation {
    private static final String TOKEN = "fixture_receiver_token_0123456789", OTHER = "fixture_receiver_token_abcdefghij";
    private static final String PAIRING = "receiverPairing.encrypted.v1", PAIRING_ALIAS = "luma.receiver.pairing.v1";
    private static final String LEGACY_KODI = "kodi.encrypted.v1", LEGACY_KODI_ALIAS = "luma.kodi.credentials.v1";
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void passed(Instrumentation runner, int count, String name) {
        Bundle progress = new Bundle(); progress.putInt("current", count); progress.putString("test", name); runner.sendStatus(0, progress);
    }
    public static void run(Instrumentation runner) {
        SettingsStore store = new SettingsStore(runner.getTargetContext()); Bundle result = new Bundle(); int checks = 0;
        try {
            store.clear("all");
            store.saveReceiver("192.168.1.50", "Fixture LUMA"); store.savePairing(TOKEN);
            SettingsStore restored = new SettingsStore(runner.getTargetContext());
            require(restored.metadata().getBoolean("hasReceiver") && restored.metadata().getBoolean("hasPairing"), "Saved configuration missing");
            require(!restored.metadata().has("hasKodi"), "Metadata still reports Kodi credentials");
            require("192.168.1.50".equals(restored.readReceiver().getString("host")), "Receiver cache did not survive a new store");
            require(TOKEN.equals(restored.readPairing().getString("token")), "Pairing token did not round-trip");
            passed(runner, ++checks, "saved receiver and encrypted pairing token survive a new store instance");

            SharedPreferences raw = runner.getTargetContext().getSharedPreferences("luma_settings", Context.MODE_PRIVATE);
            String first = raw.getString(PAIRING, "");
            require(!first.isEmpty() && !first.contains(TOKEN), "Plaintext pairing token persisted");
            KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
            require(keys.containsAlias(PAIRING_ALIAS), "Keystore key missing");
            store.savePairing(TOKEN); require(!first.equals(raw.getString(PAIRING, "")), "Encryption reused its envelope");
            passed(runner, ++checks, "pairing token uses Keystore with a fresh authenticated encryption envelope");

            JSONObject damaged = new JSONObject(raw.getString(PAIRING, ""));
            byte[] bytes = Base64.decode(damaged.getString("data"), Base64.NO_WRAP); bytes[bytes.length - 1] ^= 1;
            damaged.put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)); raw.edit().putString(PAIRING, damaged.toString()).commit();
            try { restored.readPairing(); throw new AssertionError("Modified pairing was accepted"); }
            catch (SettingsStore.SettingsException expected) { require(expected.code.equals("STORAGE_UNAVAILABLE"), "Unexpected storage failure"); }
            store.savePairing(OTHER); require(OTHER.equals(restored.readPairing().getString("token")), "Storage could not recover after replacing invalid data");
            passed(runner, ++checks, "modified encrypted storage fails closed and can be replaced");

            store.clear("receiver"); require(!store.metadata().getBoolean("hasReceiver") && store.metadata().getBoolean("hasPairing"), "Receiver reset erased pairing");
            store.saveReceiver("192.168.1.50", "Fixture LUMA"); store.clear("pairing");
            require(store.metadata().getBoolean("hasReceiver") && !store.metadata().getBoolean("hasPairing") && store.readPairing() == null, "Pairing reset erased receiver or retained token");
            keys.load(null); require(!keys.containsAlias(PAIRING_ALIAS), "Pairing reset retained key");
            passed(runner, ++checks, "reset scopes preserve unrelated setup and remove the pairing key");

            try { store.saveReceiver("8.8.8.8", "Invalid"); throw new AssertionError("Public receiver cached"); }
            catch (SettingsStore.SettingsException expected) { require(expected.code.equals("INVALID_INPUT"), "Wrong invalid-receiver error"); }
            store.savePairing(TOKEN);
            for (String invalid : new String[] {"short", TOKEN + "\r\nInjected", "token with spaces 0123456789"}) {
                try { store.savePairing(invalid); throw new AssertionError("Invalid token persisted"); }
                catch (SettingsStore.SettingsException expected) { require(expected.code.equals("INVALID_INPUT"), "Wrong invalid-token error"); }
            }
            require(TOKEN.equals(store.readPairing().getString("token")), "Invalid token replaced the saved pairing");
            try { store.clear("kodi"); throw new AssertionError("Removed Kodi reset scope accepted"); }
            catch (SettingsStore.SettingsException expected) { require(expected.code.equals("INVALID_INPUT"), "Wrong invalid-scope error"); }
            store.clear("all"); require(!store.metadata().getBoolean("hasReceiver") && !store.metadata().getBoolean("hasPairing"), "Reset all retained setup");
            passed(runner, ++checks, "invalid settings do not replace saved setup and reset all clears it");

            // Simulate 0.4.8 Kodi storage: an encrypted envelope plus its Keystore key.
            raw.edit().putString(LEGACY_KODI, "{\"version\":1,\"iv\":\"AAAAAAAAAAAAAAAA\",\"data\":\"AAAA\"}").commit();
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(LEGACY_KODI_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
            generator.generateKey();
            keys.load(null); require(keys.containsAlias(LEGACY_KODI_ALIAS) && raw.contains(LEGACY_KODI), "Legacy fixture not seeded");
            store = new SettingsStore(runner.getTargetContext());
            keys.load(null);
            require(!raw.contains(LEGACY_KODI) && !keys.containsAlias(LEGACY_KODI_ALIAS), "Legacy Kodi credentials survived construction");
            require(!store.metadata().getBoolean("hasPairing"), "Legacy Kodi credentials became a pairing");
            passed(runner, ++checks, "legacy Kodi credentials and key are deleted on construction");
            result.putInt("passed", checks); result.putInt("failures", 0); runner.finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putInt("passed", checks); result.putInt("failures", 1); result.putString("failure", error.getClass().getSimpleName() + ": " + error.getMessage()); runner.finish(Activity.RESULT_CANCELED, result);
        } finally { try { store.clear("all"); } catch (Exception ignored) { } }
    }
}

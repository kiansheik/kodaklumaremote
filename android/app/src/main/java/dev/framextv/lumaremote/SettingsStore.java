package dev.framextv.lumaremote;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Saved projector address plus the app-private, Keystore-encrypted LUMA Receiver pairing token. */
public final class SettingsStore {
    private static final String KEY_ALIAS = "luma.receiver.pairing.v1";
    private static final String RECEIVER = "receiver", PAIRING = "receiverPairing.encrypted.v1";
    // Kodi credentials saved by 0.4.8 and earlier; deleted on first use, never read.
    private static final String LEGACY_KODI = "kodi.encrypted.v1", LEGACY_KODI_ALIAS = "luma.kodi.credentials.v1";
    private final SharedPreferences preferences;

    public static final class SettingsException extends Exception {
        public final String code;
        private SettingsException(String code) {
            super(code.equals("INVALID_INPUT") ? "Check the configuration details."
                : "Saved settings could not be accessed. Open Settings to reset or enter them again.");
            this.code = code;
        }
    }

    public SettingsStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences("luma_settings", Context.MODE_PRIVATE);
        forgetLegacyKodi();
    }

    private void forgetLegacyKodi() {
        try {
            if (preferences.contains(LEGACY_KODI)) preferences.edit().remove(LEGACY_KODI).commit();
            KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
            if (keys.containsAlias(LEGACY_KODI_ALIAS)) keys.deleteEntry(LEGACY_KODI_ALIAS);
        } catch (Exception ignored) { /* Best effort: the old secret is never read again. */ }
    }

    public synchronized JSONObject metadata() {
        return object("hasReceiver", readReceiver() != null, "hasPairing", preferences.contains(PAIRING));
    }

    public synchronized JSONObject readReceiver() {
        try {
            String saved = preferences.getString(RECEIVER, null);
            if (saved == null || saved.length() > 2048) return null;
            JSONObject value = new JSONObject(saved);
            String host = value.getString("host");
            return privateHost(host) ? object("host", host, "name", clean(value.optString("name"))) : null;
        } catch (Exception ignored) { return null; }
    }

    public synchronized void saveReceiver(String host, String name) throws SettingsException {
        if (!privateHost(host)) throw new SettingsException("INVALID_INPUT");
        if (!preferences.edit().putString(RECEIVER, object("host", host, "name", clean(name)).toString()).commit()) {
            throw new SettingsException("STORAGE_UNAVAILABLE");
        }
    }

    public synchronized JSONObject readPairing() throws SettingsException {
        try {
            String saved = preferences.getString(PAIRING, null);
            if (saved == null) return null;
            if (saved.length() > 16384) throw new Exception();
            JSONObject envelope = new JSONObject(saved);
            if (envelope.getInt("version") != 1) throw new Exception();
            byte[] iv = Base64.decode(envelope.getString("iv"), Base64.NO_WRAP);
            byte[] ciphertext = Base64.decode(envelope.getString("data"), Base64.NO_WRAP);
            if (iv.length != 12 || ciphertext.length > 8192) throw new Exception();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, iv));
            String token = new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
            if (!validToken(token)) throw new Exception();
            return object("token", token);
        } catch (Exception ignored) { throw new SettingsException("STORAGE_UNAVAILABLE"); }
    }

    public synchronized void savePairing(String token) throws SettingsException {
        if (!validToken(token)) throw new SettingsException("INVALID_INPUT");
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(true));
            byte[] ciphertext = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            JSONObject envelope = object("version", 1, "iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP),
                "data", Base64.encodeToString(ciphertext, Base64.NO_WRAP));
            if (!preferences.edit().putString(PAIRING, envelope.toString()).commit()) throw new Exception();
        } catch (Exception ignored) { throw new SettingsException("STORAGE_UNAVAILABLE"); }
    }

    public synchronized void clear(String scope) throws SettingsException {
        if (scope == null || !scope.equals("all") && !scope.equals("pairing") && !scope.equals("receiver")) throw new SettingsException("INVALID_INPUT");
        try {
            SharedPreferences.Editor edit = preferences.edit();
            if (scope.equals("all") || scope.equals("receiver")) edit.remove(RECEIVER);
            if (scope.equals("all") || scope.equals("pairing")) edit.remove(PAIRING);
            if (!edit.commit()) throw new Exception();
            if (scope.equals("all") || scope.equals("pairing")) {
                KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
                if (keys.containsAlias(KEY_ALIAS)) keys.deleteEntry(KEY_ALIAS);
            }
        } catch (Exception ignored) { throw new SettingsException("STORAGE_UNAVAILABLE"); }
    }

    private SecretKey key(boolean create) throws Exception {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
        if (keys.containsAlias(KEY_ALIAS)) return (SecretKey) keys.getKey(KEY_ALIAS, null);
        if (!create) throw new Exception();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }

    private static boolean validToken(String token) { return token != null && token.matches("[A-Za-z0-9_-]{20,200}"); }
    private static boolean privateHost(String host) {
        if (host == null || !host.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) return false;
        String[] parts = host.split("\\."); int[] bytes = new int[4];
        for (int i = 0; i < 4; i++) { bytes[i] = Integer.parseInt(parts[i]); if (bytes[i] > 255) return false; }
        return bytes[0] == 10 || bytes[0] == 172 && bytes[1] >= 16 && bytes[1] <= 31 || bytes[0] == 192 && bytes[1] == 168;
    }
    private static String clean(String value) {
        String result = value == null ? "" : value.replaceAll("[\\p{Cntrl}\\u007f-\\u009f]", " ")
            .replaceAll("(?i)(?:[a-z][a-z0-9+.-]*://|www\\.)\\S+", "").trim();
        return result.substring(0, Math.min(200, result.length()));
    }
    private static JSONObject object(Object... values) {
        JSONObject result = new JSONObject();
        try { for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]); }
        catch (Exception ignored) { }
        return result;
    }
}

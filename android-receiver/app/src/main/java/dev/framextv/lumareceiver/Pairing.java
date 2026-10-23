package dev.framextv.lumareceiver;

import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * PIN pairing: a client asks to pair, the projector shows a 6-digit code, the
 * user types it on the client, and the client receives a bearer token. Only
 * SHA-256 token hashes are stored.
 */
final class Pairing {
    interface Listener { void onPairingChanged(String code); }

    static final long WINDOW_MS = 120_000;
    static final int MAX_ATTEMPTS = 5, MAX_CLIENTS = 16;
    private final SharedPreferences preferences;
    private final SecureRandom random = new SecureRandom();
    private final Listener listener;
    private String pairingId, code, pendingName;
    private long expires;
    private int attempts;

    Pairing(SharedPreferences preferences, Listener listener) { this.preferences = preferences; this.listener = listener; }

    synchronized JSONObject begin(String clientName) throws Exception {
        pairingId = token(12); code = String.format(java.util.Locale.ROOT, "%06d", random.nextInt(1_000_000));
        pendingName = clientName; attempts = 0; expires = SystemClock.elapsedRealtime() + WINDOW_MS;
        listener.onPairingChanged(code);
        return new JSONObject().put("pairingId", pairingId).put("expiresInSeconds", WINDOW_MS / 1000);
    }

    synchronized String activeCode() {
        if (code != null && SystemClock.elapsedRealtime() >= expires) clear();
        return code;
    }

    synchronized JSONObject complete(String id, String attempt) throws Exception {
        if (activeCode() == null || !MessageDigest.isEqual(id.getBytes(StandardCharsets.UTF_8), pairingId.getBytes(StandardCharsets.UTF_8)))
            throw new ApiException("PAIRING_EXPIRED", 403);
        if (!MessageDigest.isEqual(attempt.getBytes(StandardCharsets.UTF_8), code.getBytes(StandardCharsets.UTF_8))) {
            if (++attempts >= MAX_ATTEMPTS) clear();
            throw new ApiException("PAIRING_CODE_WRONG", 403);
        }
        String token = token(32), clientId = token(9);
        JSONArray clients = clients();
        while (clients.length() >= MAX_CLIENTS) clients.remove(0);
        clients.put(new JSONObject().put("id", clientId).put("name", pendingName).put("hash", hash(token))
            .put("createdAt", System.currentTimeMillis()));
        preferences.edit().putString("clients", clients.toString()).apply();
        clear();
        return new JSONObject().put("token", token).put("clientId", clientId);
    }

    private void clear() {
        boolean changed = code != null;
        pairingId = code = pendingName = null; attempts = 0; expires = 0;
        if (changed) listener.onPairingChanged(null);
    }

    synchronized void cancel() { clear(); }

    /** Returns the client id for a valid bearer header, else null. */
    synchronized String authorize(String header) {
        if (header == null || !header.startsWith("Bearer ")) return null;
        String presented = hash(header.substring(7).trim());
        JSONArray clients = clients();
        for (int i = 0; i < clients.length(); i++) {
            JSONObject client = clients.optJSONObject(i);
            if (client != null && MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), client.optString("hash").getBytes(StandardCharsets.UTF_8)))
                return client.optString("id");
        }
        return null;
    }

    synchronized JSONArray publicClients() throws Exception {
        JSONArray clients = clients(), result = new JSONArray();
        for (int i = 0; i < clients.length(); i++) {
            JSONObject client = clients.getJSONObject(i);
            result.put(new JSONObject().put("id", client.optString("id")).put("name", client.optString("name"))
                .put("createdAt", client.optLong("createdAt")));
        }
        return result;
    }

    synchronized boolean revoke(String clientId) {
        JSONArray clients = clients(), kept = new JSONArray();
        for (int i = 0; i < clients.length(); i++) if (!clientId.equals(clients.optJSONObject(i).optString("id"))) kept.put(clients.opt(i));
        preferences.edit().putString("clients", kept.toString()).apply();
        return kept.length() != clients.length();
    }

    synchronized int clientCount() { return clients().length(); }

    private JSONArray clients() {
        try { return new JSONArray(preferences.getString("clients", "[]")); } catch (Exception corrupt) { return new JSONArray(); }
    }

    private String token(int bytes) {
        byte[] value = new byte[bytes]; random.nextBytes(value);
        return Base64.encodeToString(value, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", b));
            return out.toString();
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}

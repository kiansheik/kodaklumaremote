package dev.framextv.lumaremote;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Separate phone-local JointSPACE pairing. It never submits or clears a TV field. */
public final class PhilipsTextController implements AutoCloseable {
    public static final class TextException extends Exception {
        public final String code;
        public final boolean uncertain;
        TextException(String code) { this(code, false); }
        TextException(String code, boolean uncertain) { super(code); this.code = code; this.uncertain = uncertain; }
    }
    interface Store { JSONObject read() throws TextException; void save(JSONObject value) throws TextException; }
    interface Transport extends AutoCloseable {
        JSONObject request(String path, JSONObject body, String user, String password, boolean jsonRequired, long deadline) throws TextException;
        String certificatePin();
        void close();
    }
    interface Factory { Transport create(String host, String pin) throws TextException; }
    interface Clock { long now(); void sleep(long millis) throws InterruptedException; }
    private static final Clock REAL_CLOCK = new Clock() {
        public long now() { return SystemClock.elapsedRealtime(); }
        public void sleep(long millis) throws InterruptedException { Thread.sleep(millis); }
    };
    private static final String VENDOR_KEY = "ZmVay1EQVFOaZhwQ4Kv81ypLAZNczV9sG4KkseXWn1NEk6cXmPKO/MCa9sryslvLCFMnNe4Z4CPXzToowvhHvA==";
    private final Store store;
    private final Factory factory;
    private final Clock clock;
    private final Object lock = new Object();
    private JSONObject credentials, pending, lastTyping;
    private volatile Transport transport;
    private long pendingUntil;
    private long operationRevision;
    private boolean busy, closed;
    private String error;

    public PhilipsTextController(Context context) { this(new EncryptedStore(context), HttpTransport::new, REAL_CLOCK); }
    PhilipsTextController(Store store, Factory factory, Clock clock) {
        this.store = store; this.factory = factory; this.clock = clock;
        try { credentials = store.read(); if (credentials != null) validateCredentials(credentials); }
        catch (Exception failure) { credentials = null; error = "TEXT_STORAGE"; }
    }
    public JSONObject status(String currentHost) {
        synchronized (lock) {
            long now = clock.now();
            boolean permissionSaved = credentials != null;
            boolean pairing = pending != null && now < pendingUntil;
            boolean ready = !closed && credentials != null && credentials.optString("host").equals(currentHost) && credentials.optBoolean("unicode");
            // Saved permission survives a temporarily unresolved Matter address.
            // Expiry is a visible status, not a mutation that can outlive a grant.
            String visibleError = !busy && pending != null && !pairing ? "TEXT_PAIRING_EXPIRED" : error;
            return object("textPermissionSaved", permissionSaved, "typingAvailable", ready, "typingBusy", busy, "textPairing", pairing,
                    "textPairingExpiresInSeconds", pairing ? Math.max(1, (pendingUntil - now + 999) / 1000) : 0,
                    "textError", visibleError == null ? JSONObject.NULL : visibleError, "lastTyping", lastTyping == null ? JSONObject.NULL : copy(lastTyping));
        }
    }
    private long begin() throws TextException {
        synchronized (lock) { if (closed) throw new TextException("TEXT_UNREACHABLE"); if (busy) throw new TextException("TEXT_BUSY"); busy = true; error = null; return ++operationRevision; }
    }
    private void check(long revision) throws TextException { synchronized (lock) { if (closed || operationRevision != revision) throw new TextException("TEXT_CANCELLED"); } }
    private TextException normalized(TextException failure, long revision) {
        synchronized (lock) { return closed || operationRevision != revision ? new TextException("TEXT_CANCELLED", failure.uncertain) : failure; }
    }
    private void finish(TextException failure) { synchronized (lock) { busy = false; if (failure != null) error = failure.code; } }
    public JSONObject pairRequest(String host) throws TextException {
        if (!MsxServer.privateAddress(host)) throw new TextException("TEXT_ADDRESS_UNAVAILABLE");
        final long revision = begin(); TextException failure = null;
        try {
            synchronized (lock) {
                // JointSPACE credentials are bound to the TV address that issued
                // them. DHCP can move the same paired Matter TV to a new private
                // address, so allow a fresh PIN exchange for that resolved peer.
                // Keep the old credential until the replacement grant succeeds.
                if (credentials != null && host.equals(credentials.optString("host"))) throw new TextException("TEXT_ALREADY_PAIRED");
                if (pending != null && clock.now() < pendingUntil) throw new TextException("TEXT_PAIRING_PENDING");
                pending = null; pendingUntil = 0;
            }
            Transport owned = replaceTransport(host, "", revision);
            check(revision); JSONObject system = owned.request("system", null, null, null, true, clock.now() + 15000); check(revision);
            JSONObject features = system.optJSONObject("featuring"), json = features == null ? null : features.optJSONObject("jsonfeatures");
            JSONArray inputs = json == null ? null : json.optJSONArray("inputkey");
            boolean unicode = false;
            if (inputs != null) for (int i = 0; i < inputs.length(); i++) if ("unicode".equals(inputs.optString(i))) unicode = true;
            if (!unicode) throw new TextException("TEXT_UNSUPPORTED");
            String id = secret(16);
            JSONObject device = object("device_name", "LUMA phone controller", "device_os", "Android", "type", "native", "id", id,
                    "app_id", "dev.framextv.lumaremote", "app_name", "LUMA Remote");
            JSONObject access = object("scope", new JSONArray().put("read").put("write").put("control"));
            if (features != null) put(access, "featuring", features);
            check(revision); JSONObject result = owned.request("pair/request", object("access", access, "device", device), null, null, true, clock.now() + 15000); check(revision);
            if (!"SUCCESS".equals(result.optString("error_id"))) throw new TextException("TEXT_REJECTED");
            Object timestamp = result.opt("timestamp"), key = result.opt("auth_key");
            if (!(timestamp instanceof String || timestamp instanceof Number) || !String.valueOf(timestamp).matches("[0-9]{1,20}") || !validSecret(key)) throw new TextException("TEXT_REJECTED");
            int seconds = result.optInt("timeout", 60); if (seconds < 1 || seconds > 600) seconds = 60;
            String pin = owned.certificatePin(); if (!pin.matches("[a-f0-9]{64}")) throw new TextException("TEXT_REJECTED");
            synchronized (lock) {
                check(revision);
                pending = object("host", host, "device", device, "timestamp", timestamp, "auth_key", key, "pin", pin, "unicode", true);
                pendingUntil = clock.now() + seconds * 1000L;
            }
            return object("pairing", true, "expiresInSeconds", seconds);
        } catch (TextException caught) { failure = normalized(caught, revision); throw failure; }
        finally { finish(failure); }
    }
    public void pairGrant(String host, String pin) throws TextException {
        if (pin == null || !pin.matches("[0-9]{4}")) throw new TextException("TEXT_INVALID_PIN");
        final long revision = begin(); TextException failure = null;
        try {
            JSONObject request; long expires;
            synchronized (lock) { request = pending == null ? null : copy(pending); expires = pendingUntil; }
            if (request == null) throw new TextException("TEXT_PAIRING_REQUIRED");
            if (clock.now() >= expires) throw new TextException("TEXT_PAIRING_EXPIRED");
            if (!request.optString("host").equals(host)) throw new TextException("TEXT_ADDRESS_UNAVAILABLE");
            Transport owned = replaceTransport(host, request.optString("pin"), revision);
            JSONObject device = request.optJSONObject("device");
            JSONObject auth = object("auth_appId", "1", "auth_timestamp", request.opt("timestamp"),
                    "auth_signature", signature(String.valueOf(request.opt("timestamp")), pin), "pin", pin);
            check(revision); JSONObject result = owned.request("pair/grant", object("auth", auth, "device", device), device.optString("id"), request.optString("auth_key"), true, Math.min(expires, clock.now() + 15000)); check(revision);
            if (!"SUCCESS".equals(result.optString("error_id"))) throw new TextException("TEXT_REJECTED");
            JSONObject saved = object("host", host, "id", device.optString("id"), "auth_key", request.optString("auth_key"), "pin", request.optString("pin"), "unicode", true);
            validateCredentials(saved);
            synchronized (lock) { check(revision); store.save(saved); credentials = saved; pending = null; pendingUntil = 0; }
        } catch (TextException caught) { failure = normalized(caught, revision); throw failure; }
        finally { finish(failure); }
    }
    /** Only the native server's current authority is accepted, never UI-provided text. */
    public JSONObject typeAddress(String host, String startupUrl) throws TextException {
        return typeFixedValue(host, startupAuthority(startupUrl), "msx", 65000);
    }
    /** Fixed browser entry selected from two native-owned choices, never a caller URL. */
    public JSONObject typeBrowserAddress(String host, String startupUrl, String address) throws TextException {
        final String value;
        if ("stable".equals(address)) value = launcherUrl(startupUrl);
        else if ("direct".equals(address)) value = browserSetupUrl(startupUrl);
        else throw new TextException("TEXT_INVALID_INPUT");
        return typeFixedValue(host, value, "browser", 90000);
    }
    /** An accepted browser request is not proof that the TV loaded the receiver. */
    public JSONObject launchBrowser(String host, String startupUrl) throws TextException {
        final String url = launcherUrl(startupUrl);
        final long revision = begin(); TextException failure = null;
        try {
            JSONObject saved; synchronized (lock) { saved = credentials == null ? null : copy(credentials); }
            if (saved == null) throw new TextException("TEXT_PAIRING_REQUIRED");
            if (!saved.optString("host").equals(host)) throw new TextException("TEXT_ADDRESS_UNAVAILABLE");
            Transport owned = replaceTransport(host, saved.optString("pin"), revision);
            long deadline = clock.now() + 30000;
            check(revision);
            JSONObject system = owned.request("system", null, saved.optString("id"), saved.optString("auth_key"), true, Math.min(deadline, clock.now() + 15000));
            check(revision);
            JSONObject features = system.optJSONObject("featuring"), json = features == null ? null : features.optJSONObject("jsonfeatures");
            JSONArray activities = json == null ? null : json.optJSONArray("activities");
            boolean browser = false;
            if (activities != null) for (int i = 0; i < activities.length(); i++) if ("browser".equals(activities.opt(i))) browser = true;
            if (!browser) throw new TextException("TEXT_BROWSER_UNSUPPORTED");
            if (clock.now() >= deadline) throw new TextException("TEXT_TIMEOUT");
            check(revision);
            owned.request("activities/browser", object("url", url), saved.optString("id"), saved.optString("auth_key"), false, deadline);
            check(revision);
            return object("accepted", true, "pageLoadVerified", false);
        } catch (TextException caught) { failure = normalized(caught, revision); throw failure; }
        catch (RuntimeException unexpected) { failure = normalized(new TextException("TEXT_REJECTED"), revision); throw failure; }
        finally { finish(failure); }
    }
    private JSONObject typeFixedValue(String host, String value, String mode, long timeoutMs) throws TextException {
        final long revision = begin(); TextException failure = null; int acknowledged = 0; boolean inFlight = false;
        final int total = value.codePointCount(0, value.length());
        synchronized (lock) { lastTyping = object("textEntryMode", mode, "totalChars", total, "acknowledgedChars", 0, "partialInput", false, "textAppliedVerified", false, "error", JSONObject.NULL); }
        try {
            JSONObject saved; synchronized (lock) { saved = credentials == null ? null : copy(credentials); }
            if (saved == null) throw new TextException("TEXT_PAIRING_REQUIRED");
            if (!saved.optString("host").equals(host)) throw new TextException("TEXT_ADDRESS_UNAVAILABLE");
            if (!saved.optBoolean("unicode")) throw new TextException("TEXT_UNSUPPORTED");
            Transport owned = replaceTransport(host, saved.optString("pin"), revision);
            long deadline = clock.now() + timeoutMs;
            for (int offset = 0; offset < value.length();) {
                check(revision);
                if (acknowledged > 0) { if (deadline - clock.now() <= 150) throw new TextException("TEXT_TIMEOUT"); clock.sleep(150); }
                check(revision);
                if (clock.now() >= deadline) throw new TextException("TEXT_TIMEOUT");
                int code = value.codePointAt(offset); String character = new String(Character.toChars(code));
                inFlight = true;
                owned.request("input/key", object("unicode", character), saved.optString("id"), saved.optString("auth_key"), false, deadline);
                inFlight = false; acknowledged++; offset += Character.charCount(code);
                check(revision);
            }
            return object("accepted", true, "textEntryMode", mode, "totalChars", total, "acknowledgedChars", acknowledged, "textAppliedVerified", false);
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); failure = normalized(new TextException("TEXT_TIMEOUT", inFlight), revision); throw failure; }
        catch (TextException caught) { failure = normalized(caught, revision); throw failure; }
        catch (RuntimeException unexpected) { failure = new TextException("TEXT_REJECTED", inFlight); throw failure; }
        finally {
            synchronized (lock) { lastTyping = object("textEntryMode", mode, "totalChars", total, "acknowledgedChars", acknowledged, "partialInput", failure != null && (acknowledged > 0 || inFlight && failure.uncertain),
                    "textAppliedVerified", false, "error", failure == null ? JSONObject.NULL : failure.code); }
            finish(failure);
        }
    }
    static String startupAuthority(String value) throws TextException {
        try {
            URI url = new URI(value);
            if (!"http".equals(url.getScheme()) || !MsxServer.privateAddress(url.getHost()) || url.getPort() < 1024 || url.getPort() > 65535 || url.getUserInfo() != null
                    || !(url.getRawPath() == null || url.getRawPath().isEmpty()) || url.getRawQuery() != null || url.getRawFragment() != null) throw new Exception();
            return url.getRawAuthority();
        } catch (Exception invalid) { throw new TextException("TEXT_INVALID_INPUT"); }
    }
    static String browserSetupUrl(String startupUrl) throws TextException {
        return "http://" + startupAuthority(startupUrl) + "/tv/connect.html";
    }
    /** Fixed GitHub Pages speed dial. The TV page collects and remembers local phones. */
    static final String LAUNCHER = "https://kiansheik.io/kodaklumaremote/tv.html";
    static String launcherUrl(String startupUrl) throws TextException {
        startupAuthority(startupUrl); // The listener must already have a valid private origin.
        return LAUNCHER;
    }
    static boolean fixedLauncherUrl(String value) {
        return LAUNCHER.equals(value);
    }
    static boolean fixedBrowserSetupUrl(String value) {
        if (value == null) return false;
        try {
            URI receiver = new URI(value);
            return "/tv/connect.html".equals(receiver.getRawPath()) && receiver.getRawQuery() == null && receiver.getRawFragment() == null
                    && value.equals(browserSetupUrl("http://" + receiver.getRawAuthority()));
        } catch (Exception invalid) { return false; }
    }
    static String signature(String timestamp, String pin) throws TextException {
        try { Mac mac = Mac.getInstance("HmacSHA1"); mac.init(new SecretKeySpec(Base64.decode(VENDOR_KEY, Base64.DEFAULT), "HmacSHA1")); return Base64.encodeToString(mac.doFinal((timestamp + pin).getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP); }
        catch (Exception invalid) { throw new TextException("TEXT_REJECTED"); }
    }
    private static boolean validSecret(Object value) { return value instanceof String && !((String) value).isEmpty() && ((String) value).length() <= 512 && !((String) value).matches("(?s).*[\\p{Cntrl}].*"); }
    private static void validateCredentials(JSONObject value) throws TextException {
        if (!MsxServer.privateAddress(value.optString("host")) || !value.optString("id").matches("[a-f0-9]{32}") || !validSecret(value.opt("auth_key"))
                || !value.optString("pin").matches("[a-f0-9]{64}") || !Boolean.TRUE.equals(value.opt("unicode"))) throw new TextException("TEXT_STORAGE");
    }
    private Transport replaceTransport(String host, String pin, long revision) throws TextException {
        closeTransport(); Transport next = factory.create(host, pin);
        synchronized (lock) { if (closed || operationRevision != revision) { next.close(); throw new TextException("TEXT_CANCELLED"); } transport = next; return next; }
    }
    private void closeTransport() { Transport previous; synchronized (lock) { previous = transport; transport = null; } if (previous != null) previous.close(); }
    public void cancel() { synchronized (lock) { operationRevision++; pending = null; pendingUntil = 0; } closeTransport(); }
    public void close() { synchronized (lock) { closed = true; } cancel(); }
    private static String secret(int bytes) { byte[] value = new byte[bytes]; new SecureRandom().nextBytes(value); return hex(value); }
    private static String hex(byte[] bytes) { StringBuilder value = new StringBuilder(); for (byte b : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return value.toString(); }
    private static JSONObject object(Object... values) { JSONObject result = new JSONObject(); for (int i = 0; i < values.length; i += 2) put(result, (String) values[i], values[i + 1]); return result; }
    private static void put(JSONObject target, String key, Object value) { try { target.put(key, value); } catch (Exception invalid) { throw new IllegalStateException(); } }
    private static JSONObject copy(JSONObject value) { try { return new JSONObject(value.toString()); } catch (Exception invalid) { throw new IllegalStateException(); } }

    static final class EncryptedStore implements Store {
        private static final String ALIAS = "luma.philips.text.v1";
        private final SharedPreferences preferences;
        EncryptedStore(Context context) { preferences = context.getApplicationContext().getSharedPreferences("philips-text", Context.MODE_PRIVATE); }
        public JSONObject read() throws TextException {
            try {
                String stored = preferences.getString("credentials", null); if (stored == null) return null;
                if (stored.length() > 8192) throw new Exception(); JSONObject envelope = new JSONObject(stored);
                byte[] iv = Base64.decode(envelope.getString("iv"), Base64.NO_WRAP), data = Base64.decode(envelope.getString("data"), Base64.NO_WRAP);
                if (envelope.optInt("v") != 1 || iv.length != 12 || data.length > 4096) throw new Exception();
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, iv));
                JSONObject value = new JSONObject(new String(cipher.doFinal(data), StandardCharsets.UTF_8)); validateCredentials(value); return value;
            } catch (Exception invalid) { throw new TextException("TEXT_STORAGE"); }
        }
        public void save(JSONObject value) throws TextException {
            validateCredentials(value);
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key(true));
                JSONObject envelope = object("v", 1, "iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP), "data", Base64.encodeToString(cipher.doFinal(value.toString().getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP));
                if (!preferences.edit().putString("credentials", envelope.toString()).commit()) throw new Exception();
            } catch (Exception invalid) { throw new TextException("TEXT_STORAGE"); }
        }
        private SecretKey key(boolean create) throws Exception {
            KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
            if (keys.containsAlias(ALIAS)) return (SecretKey) keys.getKey(ALIAS, null);
            if (!create) throw new Exception(); KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).setRandomizedEncryptionRequired(true).build()); return generator.generateKey();
        }
    }

    /** Every TLS override belongs to this one explicit private TV connection. */
    static final class HttpTransport implements Transport {
        private final String host;
        private final SSLContext tls;
        private volatile String pin;
        private volatile HttpsURLConnection active;
        private volatile boolean closed;
        private Map<String, String> challenge;
        private int nonceCount;
        HttpTransport(String host, String pin) throws TextException {
            if (!MsxServer.privateAddress(host) || !(pin.isEmpty() || pin.matches("[a-f0-9]{64}"))) throw new TextException("TEXT_ADDRESS_UNAVAILABLE");
            this.host = host; this.pin = pin;
            try {
                tls = SSLContext.getInstance("TLS"); tls.init(null, new TrustManager[] {new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] chain, String type) throws CertificateException { throw new CertificateException(); }
                    public void checkServerTrusted(X509Certificate[] chain, String type) throws CertificateException {
                        try { if (chain == null || chain.length == 0) throw new CertificateException();
                            HttpTransport.this.pin = pinnedCertificateHash(chain[0].getEncoded(), HttpTransport.this.pin);
                        } catch (Exception invalid) { throw new CertificateException("TEXT_CERTIFICATE_CHANGED"); }
                    }
                }}, new SecureRandom());
            } catch (Exception invalid) { throw new TextException("TEXT_REJECTED"); }
        }
        public String certificatePin() { return pin; }
        public synchronized void close() { closed = true; HttpsURLConnection connection = active; if (connection != null) connection.disconnect(); }
        public JSONObject request(String path, JSONObject body, String user, String password, boolean jsonRequired, long deadline) throws TextException {
            if (!java.util.Arrays.asList("system", "pair/request", "pair/grant", "input/key", "activities/browser").contains(path)) throw new TextException("TEXT_INVALID_INPUT");
            if ("activities/browser".equals(path) && (body == null || body.length() != 1 || !(body.opt("url") instanceof String) || !(fixedBrowserSetupUrl(body.optString("url")) || fixedLauncherUrl(body.optString("url"))))) throw new TextException("TEXT_INVALID_INPUT");
            byte[] bytes = body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8);
            for (int attempt = 0; attempt < 2; attempt++) {
                HttpsURLConnection connection = null; Timer timer = new Timer("Philips request deadline", true); boolean writing = false;
                try {
                    if (closed) throw new TextException("TEXT_UNREACHABLE");
                    long remaining = deadline - SystemClock.elapsedRealtime(); if (remaining <= 0) throw new TextException("TEXT_TIMEOUT");
                    connection = (HttpsURLConnection) new URL("https://" + host + ":1926/6/" + path).openConnection(Proxy.NO_PROXY);
                    synchronized (this) { if (closed) { connection.disconnect(); throw new TextException("TEXT_UNREACHABLE"); } active = connection; }
                    final HttpsURLConnection owned = connection; timer.schedule(new TimerTask() { public void run() { owned.disconnect(); } }, remaining);
                    connection.setSSLSocketFactory(tls.getSocketFactory()); connection.setHostnameVerifier((name, session) -> host.equals(name));
                    connection.setInstanceFollowRedirects(false); connection.setConnectTimeout((int) Math.min(8000, remaining)); connection.setReadTimeout((int) Math.min(8000, remaining));
                    connection.setRequestProperty("Accept", "application/json"); connection.setRequestProperty("Content-Type", "application/json");
                    String method = bytes == null ? "GET" : "POST";
                    if (user != null && challenge != null) connection.setRequestProperty("Authorization", digestAuthorization(challenge, user, password, method, "/6/" + path, secret(16), ++nonceCount));
                    if (bytes != null) { connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setFixedLengthStreamingMode(bytes.length); writing = true; try (OutputStream out = connection.getOutputStream()) { out.write(bytes); } }
                    int status = connection.getResponseCode();
                    if (status == 401) {
                        if (user == null || attempt != 0) throw new TextException("TEXT_AUTH_REQUIRED");
                        challenge = parseChallenge(connection.getHeaderField("WWW-Authenticate")); nonceCount = 0; continue;
                    }
                    if (status != 200) throw new TextException("TEXT_REJECTED");
                    byte[] raw; try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[4096]; int count;
                        while ((count = input.read(buffer)) >= 0) { if (output.size() + count > 1048576 || SystemClock.elapsedRealtime() >= deadline) throw new TextException("TEXT_TIMEOUT", writing); output.write(buffer, 0, count); }
                        raw = output.toByteArray();
                    }
                    JSONObject result;
                    try { JSONTokener parser = new JSONTokener(new String(raw, StandardCharsets.UTF_8)); Object value = parser.nextValue(); if (!(value instanceof JSONObject) || parser.nextClean() != 0) throw new Exception(); result = (JSONObject) value; }
                    catch (Exception invalid) { if (!jsonRequired) return new JSONObject(); throw new TextException("TEXT_REJECTED", writing); }
                    if (result.has("error_id") && !"SUCCESS".equals(result.optString("error_id"))) throw new TextException("TEXT_REJECTED");
                    return result;
                } catch (TextException fixed) { throw fixed; }
                catch (SSLHandshakeException certificate) { throw new TextException("TEXT_CERTIFICATE_CHANGED"); }
                catch (SocketTimeoutException timeout) { throw new TextException("TEXT_TIMEOUT", writing); }
                catch (Exception unavailable) { throw new TextException(SystemClock.elapsedRealtime() >= deadline ? "TEXT_TIMEOUT" : "TEXT_UNREACHABLE", writing); }
                finally { timer.cancel(); if (connection != null) connection.disconnect(); active = null; }
            }
            throw new TextException("TEXT_AUTH_REQUIRED");
        }
    }
    static String certificateHash(byte[] certificate) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(certificate)); }
    static String pinnedCertificateHash(byte[] certificate, String expected) throws Exception {
        String actual = certificateHash(certificate);
        if (!expected.isEmpty() && !expected.equals(actual)) throw new CertificateException("TEXT_CERTIFICATE_CHANGED");
        return actual;
    }
    static Map<String, String> parseChallenge(String header) throws TextException {
        if (header == null || header.length() > 8192 || !header.startsWith("Digest ")) throw new TextException("TEXT_AUTH_REQUIRED");
        Map<String, String> result = new LinkedHashMap<>();
        Matcher field = Pattern.compile("\\s*([A-Za-z][A-Za-z0-9_-]*)=(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([^,\\s]+))\\s*(?:,|$)").matcher(header.substring(7));
        int position = 0;
        while (field.find()) {
            if (field.start() != position) throw new TextException("TEXT_AUTH_REQUIRED");
            String key = field.group(1).toLowerCase(java.util.Locale.ROOT), value = field.group(2) == null ? field.group(3) : field.group(2).replaceAll("\\\\(.)", "$1");
            if (value.length() > 2048 || value.matches("(?s).*[\\p{Cntrl}].*") || result.put(key, value) != null) throw new TextException("TEXT_AUTH_REQUIRED");
            position = field.end();
        }
        if (position != header.length() - 7 || !result.containsKey("realm") || !result.containsKey("nonce") || result.get("nonce").isEmpty()) throw new TextException("TEXT_AUTH_REQUIRED");
        return result;
    }
    static String digestAuthorization(Map<String, String> challenge, String user, String password, String method, String uri, String cnonce, int count) throws TextException {
        try {
            String algorithm = challenge.containsKey("algorithm") ? challenge.get("algorithm") : "MD5";
            if (!"MD5".equalsIgnoreCase(algorithm) && !"SHA-256".equalsIgnoreCase(algorithm)) throw new TextException("TEXT_AUTH_REQUIRED");
            String qop = challenge.get("qop"), realm = challenge.get("realm"), nonce = challenge.get("nonce");
            if (qop != null && !java.util.Arrays.asList(qop.toLowerCase(java.util.Locale.ROOT).split("\\s*,\\s*")).contains("auth")) throw new TextException("TEXT_AUTH_REQUIRED");
            String digest = algorithm.toUpperCase(java.util.Locale.ROOT), nc = String.format(java.util.Locale.ROOT, "%08x", count);
            String a1 = hash(digest, user + ":" + realm + ":" + password), a2 = hash(digest, method + ":" + uri);
            String response = hash(digest, a1 + ":" + nonce + ":" + (qop == null ? "" : nc + ":" + cnonce + ":auth:") + a2);
            String value = "Digest username=" + quote(user) + ", realm=" + quote(realm) + ", nonce=" + quote(nonce) + ", uri=" + quote(uri) + ", response=" + quote(response) + ", algorithm=" + algorithm;
            if (qop != null) value += ", qop=auth, nc=" + nc + ", cnonce=" + quote(cnonce);
            if (challenge.containsKey("opaque")) value += ", opaque=" + quote(challenge.get("opaque"));
            return value;
        } catch (TextException fixed) { throw fixed; }
        catch (Exception invalid) { throw new TextException("TEXT_AUTH_REQUIRED"); }
    }
    private static String quote(String value) throws TextException { if (value == null || value.matches("(?s).*[\\p{Cntrl}].*")) throw new TextException("TEXT_AUTH_REQUIRED"); return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    private static String hash(String algorithm, String value) throws Exception { return hex(MessageDigest.getInstance(algorithm).digest(value.getBytes(StandardCharsets.UTF_8))); }
}

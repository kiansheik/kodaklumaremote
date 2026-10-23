package dev.framextv.lumaremote;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Native LAN transport for the APK-owned control page. Never attach this object
 * to the browsing WebView. Call handle on one background executor. */
public final class NativeBackend {
    public interface SourceGuard { boolean isCurrent(String token); }
    public interface PreparedSubtitleResolver {
        String resolve(String id, String sourceUrl, String subtitleUrl, String receiverHost) throws BackendException;
    }
    public static final class BackendException extends Exception {
        public final String code;
        private BackendException(String code, String message) { super(message); this.code = code; }
    }
    private static final int TCP_TIMEOUT = 2500, RECEIVER_CALL_TIMEOUT = 5000, RECEIVER_PORT = 4191, MAX_RECEIVER_REPLY = 1024 * 1024, MAX_APPS_BYTES = 16 * 1024 * 1024;
    static final String RECEIVER_PACKAGE = "dev.framextv.lumareceiver";
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+");
    private static final Pattern LUMA_MOVIE_ID = Pattern.compile("^v1:(framextv:movie:([1-9][0-9]{0,9}))$");
    private static final Pattern LUMA_EPISODE_ID = Pattern.compile("^v1:(framextv:tv:([1-9][0-9]{0,9}):(0|[1-9][0-9]{0,3}):([1-9][0-9]{0,3}))$");
    private static final Map<String, Integer> KEYS = new LinkedHashMap<>();
    static {
        KEYS.put("up", 19); KEYS.put("down", 20); KEYS.put("left", 21); KEYS.put("right", 22);
        KEYS.put("enter", 23); KEYS.put("back", 4); KEYS.put("home", 3); KEYS.put("menu", 82);
        KEYS.put("volume-up", 24); KEYS.put("volume-down", 25); KEYS.put("mute", 164);
    }
    private final Context context;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor();
    private volatile SourceGuard sourceGuard;
    private volatile PreparedSubtitleResolver subtitleResolver;
    private volatile boolean closed;
    private volatile Socket luma;
    private volatile DatagramSocket discovering;
    private volatile HttpURLConnection activeHttp;
    private String host, deviceName, token, activeToken;
    private long receiverDeadline;
    private int receiverPort = RECEIVER_PORT;
    private Map<String, String> apps;

    public NativeBackend(Context context) { this.context = context.getApplicationContext(); }
    public void setSourceGuard(SourceGuard guard) { sourceGuard = guard; }
    public void setPreparedSubtitleResolver(PreparedSubtitleResolver resolver) { subtitleResolver = resolver; }
    /** Service worker only: forgetting the receiver pairing does not close projector controls. */
    public synchronized void clearReceiverConfiguration() { token = null; }

    /** Read on the service's network queue; never includes credentials. */
    public synchronized JSONObject connectionSnapshot() {
        try { return object("connected", !closed && luma != null && !luma.isClosed(),
                "configured", !closed && token != null,
                "host", host == null ? "" : host, "name", deviceName == null ? "" : deviceName); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    static BackendException fail(String code) {
        String message;
        switch (code) {
            case "INVALID_INPUT": message = "The requested control action is not valid."; break;
            case "INVALID_HOST": message = "Enter the projector’s private IPv4 address."; break;
            case "NOT_CONNECTED": message = "Connect to the projector first."; break;
            case "TIMEOUT": message = "The projector did not reply in time. Reconnect and try again."; break;
            case "INVALID_RESPONSE": message = "The projector returned an unsupported response. Reconnect and try again."; break;
            case "PAIRING_REQUIRED": message = "The projector requires pairing. Password pairing is not supported by this proof of concept."; break;
            case "PAIRING_UNKNOWN": message = "The projector pairing requirement could not be verified. No control commands were sent."; break;
            case "INVALID_TEXT": message = "Send a single line of up to 1,000 characters."; break;
            case "APP_NOT_AVAILABLE": message = "That app was not found in the projector’s current app list."; break;
            case "RECEIVER_CONFIG": message = "Pair this phone with LUMA Receiver on the projector first."; break;
            case "RECEIVER_AUTH": message = "LUMA Receiver no longer recognizes this phone. Pair it again."; break;
            case "RECEIVER_UNAVAILABLE": message = "Could not reach LUMA Receiver. Check that the projector is on and on your home network."; break;
            case "RECEIVER_RESPONSE": message = "LUMA Receiver did not accept this control command."; break;
            case "RECEIVER_REJECTED": message = "LUMA Receiver rejected this request before applying it. Try casting again."; break;
            case "PAIRING_CODE_WRONG": message = "That code does not match the one on the projector. Check it and try again."; break;
            case "PAIRING_EXPIRED": message = "The pairing code expired. Show a new code on the projector."; break;
            case "NO_VIDEO": message = "Nothing is playing on the projector. Start a video first."; break;
            case "SOURCE_CHANGED": message = "The video source changed. Choose Cast again before controlling this video."; break;
            case "INVALID_MEDIA": message = "The page does not expose a supported public HTTP media source."; break;
            case "INVALID_SUBTITLE": message = "Choose an available subtitle track or turn subtitles off."; break;
            case "SUBTITLE_DOWNLOAD": message = "The subtitle could not be downloaded. Choose another language or try again."; break;
            case "SUBTITLE_FORMAT": message = "This subtitle format is not supported. Choose another subtitle."; break;
            case "SUBTITLE_INVALID": message = "This subtitle file has no valid timed captions. Choose another subtitle."; break;
            case "SUBTITLE_TOO_LARGE": message = "This subtitle file is too large. Choose another subtitle."; break;
            case "SUBTITLE_EXPIRED": message = "The prepared subtitle is no longer available. Choose it again."; break;
            case "SUBTITLE_DELIVERY": message = "The projector could not retrieve the prepared subtitles. Check that the phone and projector share the same Wi-Fi."; break;
            case "CANNOT_SEEK": message = "This video cannot seek."; break;
            default: code = "CONNECTION_FAILED"; message = "Could not reach the projector. Check its power, Wi-Fi and IP address.";
        }
        return new BackendException(code, message);
    }
    private static JSONObject object(Object... fields) throws Exception {
        JSONObject result = new JSONObject();
        for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]);
        return result;
    }
    private static String text(JSONObject data, String key, int max) throws BackendException {
        Object value = data.opt(key);
        if (!(value instanceof String) || ((String) value).isEmpty() || ((String) value).length() > max) throw fail("INVALID_INPUT");
        return (String) value;
    }
    private static int integer(Object value, int min, int max) throws BackendException {
        if (!(value instanceof Number)) throw fail("INVALID_INPUT");
        double number = ((Number) value).doubleValue();
        if (Double.isNaN(number) || Double.isInfinite(number) || number != Math.floor(number) || number < min || number > max) throw fail("INVALID_INPUT");
        return (int) number;
    }
    private static byte[] addressBytes(String value) throws BackendException {
        if (value == null || !value.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) throw fail("INVALID_HOST");
        String[] parts = value.split("\\."); byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) { int part = Integer.parseInt(parts[i]); if (part > 255) throw fail("INVALID_HOST"); bytes[i] = (byte) part; }
        return bytes;
    }
    private static boolean privateAddress(String value) {
        try {
            byte[] bytes = addressBytes(value); int a = bytes[0] & 255, b = bytes[1] & 255;
            return a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168);
        } catch (Exception ignored) { return false; }
    }
    private static InetAddress numericAddress(String value) throws Exception {
        if (!privateAddress(value)) throw fail("INVALID_HOST");
        return InetAddress.getByAddress(addressBytes(value));
    }
    private static String decode(byte[] bytes) throws Exception {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static String label(String value, int max) {
        String clean = value.replaceAll("[\\p{Cntrl}\\u007f-\\u009f\\u202a-\\u202e\\u2066-\\u2069]", " ")
                .replaceAll("(?i)(?:[a-z][a-z0-9+.-]*://|www\\.)\\S+", "").trim();
        return clean.substring(0, Math.min(max, clean.length()));
    }
    private void guard() throws BackendException {
        if (activeToken != null && (sourceGuard == null || !sourceGuard.isCurrent(activeToken))) throw fail("SOURCE_CHANGED");
        if (closed) throw fail("NOT_CONNECTED");
    }

    public synchronized JSONObject handle(String method, JSONObject params) throws BackendException {
        if (closed) throw fail("NOT_CONNECTED");
        if (params == null) params = new JSONObject();
        activeToken = params.has("sourceToken") ? text(params, "sourceToken", 200) : null;
        try {
            if ("device.discover".equals(method)) return object("devices", discover());
            if ("device.connect".equals(method)) return connectDevice(text(params, "host", 64));
            if ("device.disconnect".equals(method)) {
                closeLuma(); host = null; token = null; return object("connected", false);
            }
            if (method != null && method.startsWith("luma.")) return lumaAction(method.substring(5), params);
            if (method != null && method.startsWith("receiver.")) return receiverAction(method.substring(9), params);
            throw fail("INVALID_INPUT");
        } catch (BackendException failure) {
            if ("RECEIVER_AUTH".equals(failure.code)) token = null;
            throw failure;
        }
        catch (Exception ignored) { throw fail("CONNECTION_FAILED"); }
        finally { activeToken = null; receiverDeadline = 0; }
    }

    private void requireHost() throws BackendException { if (!privateAddress(host)) throw fail("NOT_CONNECTED"); }
    private void requireLuma() throws BackendException { requireHost(); if (luma == null || luma.isClosed()) throw fail("NOT_CONNECTED"); }
    private void closeLuma() { Socket old = luma; luma = null; apps = null; if (old != null) try { old.close(); } catch (Exception ignored) {} }
    private JSONObject connectDevice(String selected) throws Exception {
        if (!privateAddress(selected)) throw fail("INVALID_HOST");
        closeLuma();
        if (!selected.equals(host)) { token = null; receiverPort = RECEIVER_PORT; }
        host = selected;
        try {
            Socket socket = new Socket(); luma = socket;
            socket.connect(new InetSocketAddress(numericAddress(host), 8121), TCP_TIMEOUT);
            socket.setTcpNoDelay(true); socket.setSoTimeout(TCP_TIMEOUT);
            String features = tcpText("getFeatures\r\nframeXTV Remote\r\n\r\n");
            if (!features.startsWith("server_features:") || features.substring(16).trim().isEmpty()) throw fail("INVALID_RESPONSE");
            String name = "EShare projector", payload = features.substring(16).trim();
            Matcher fields = Pattern.compile("(?:^|\\s)([A-Za-z][A-Za-z0-9_]*)=").matcher(payload);
            List<String> keys = new ArrayList<>(); List<Integer> starts = new ArrayList<>(), ends = new ArrayList<>();
            while (fields.find()) { keys.add(fields.group(1)); starts.add(fields.start()); ends.add(fields.end()); }
            Map<String, String> values = new LinkedHashMap<>();
            for (int i = 0; i < keys.size(); i++) values.put(keys.get(i), payload.substring(ends.get(i), i + 1 < keys.size() ? starts.get(i + 1) : payload.length()).trim());
            if (values.containsKey("ap") && !values.get("ap").isEmpty()) name = values.get("ap");
            else if (values.containsKey("m") && !values.get("m").isEmpty()) name = values.get("m");
            deviceName = label(name, 200); if (deviceName.isEmpty()) throw fail("INVALID_RESPONSE");
            String pairing;
            try { pairing = tcpText("GETPASSWORDCONFIG\r\neshare\r\n\r\n"); } catch (Exception ignored) { throw fail("PAIRING_UNKNOWN"); }
            if (pairing.isEmpty()) throw fail("PAIRING_UNKNOWN");
            if (pairing.toLowerCase(Locale.ROOT).contains("password")) throw fail("PAIRING_REQUIRED");
            if (!pairing.matches("(?i)configed\\s+none")) throw fail("PAIRING_UNKNOWN");
            return object("connected", true, "name", deviceName, "device", object("name", deviceName));
        } catch (Exception failure) {
            closeLuma(); host = null;
            if (failure instanceof BackendException) throw failure;
            throw fail(failure instanceof SocketTimeoutException ? "TIMEOUT" : "CONNECTION_FAILED");
        }
    }
    private void tcpWrite(String command, boolean mutation) throws Exception {
        requireLuma(); if (mutation) guard();
        try { luma.getOutputStream().write(command.getBytes(StandardCharsets.UTF_8)); luma.getOutputStream().flush(); }
        catch (Exception ignored) { closeLuma(); throw fail("CONNECTION_FAILED"); }
    }
    private String tcpText(String command) throws Exception {
        tcpWrite(command, false);
        Socket socket = luma; ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] chunk = new byte[4096];
        long deadline = SystemClock.elapsedRealtime() + TCP_TIMEOUT;
        try {
            while (true) {
                socket.setSoTimeout((int) Math.max(1, Math.min(out.size() == 0 ? TCP_TIMEOUT : 100, deadline - SystemClock.elapsedRealtime())));
                int count;
                try { count = socket.getInputStream().read(chunk); }
                catch (SocketTimeoutException timeout) { if (out.size() > 0) break; throw timeout; }
                if (count < 0) throw fail("INVALID_RESPONSE");
                if (out.size() + count > 16384) throw fail("INVALID_RESPONSE");
                out.write(chunk, 0, count);
                if (SystemClock.elapsedRealtime() >= deadline) throw fail("TIMEOUT");
            }
            String reply = decode(out.toByteArray());
            if (reply.matches("(?s).*[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f].*")) throw fail("INVALID_RESPONSE");
            return reply.trim();
        } catch (Exception failure) { closeLuma(); if (failure instanceof BackendException) throw failure; throw fail(failure instanceof SocketTimeoutException ? "TIMEOUT" : "INVALID_RESPONSE"); }
    }
    private JSONArray getApps() throws Exception {
        requireLuma(); apps = null; tcpWrite("GETAPPINFOSEVNET\r\n1\r\n\r\n", false);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] chunk = new byte[8192]; int expected = -1;
            long deadline = SystemClock.elapsedRealtime() + TCP_TIMEOUT;
            while (expected < 0 || out.size() < expected) {
                long remaining = deadline - SystemClock.elapsedRealtime(); if (remaining <= 0) throw fail("TIMEOUT");
                luma.setSoTimeout((int) remaining);
                int count = luma.getInputStream().read(chunk); if (count < 0) throw fail("INVALID_RESPONSE");
                if (out.size() + count > MAX_APPS_BYTES) throw fail("INVALID_RESPONSE"); out.write(chunk, 0, count);
                if (expected < 0 && out.size() >= 4) { expected = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).getInt(); if (expected < 4 || expected > MAX_APPS_BYTES) throw fail("INVALID_RESPONSE"); }
                if (expected >= 0 && out.size() > expected) throw fail("INVALID_RESPONSE");
            }
            ByteBuffer frame = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN); frame.getInt();
            Map<String, String> parsed = new LinkedHashMap<>(); JSONArray result = new JSONArray();
            while (frame.hasRemaining()) {
                if (parsed.size() >= 200) throw fail("INVALID_RESPONSE");
                byte[] nameBytes = appField(frame, 1024), packageBytes = appField(frame, 255);
                skipField(frame); skipField(frame); skipField(frame);
                String name = label(decode(nameBytes), 200), packageName = decode(packageBytes);
                if (name.isEmpty() || !PACKAGE.matcher(packageName).matches() || parsed.containsKey(packageName)) throw fail("INVALID_RESPONSE");
                parsed.put(packageName, name); result.put(object("packageName", packageName, "label", name));
            }
            apps = parsed; return result;
        } catch (Exception failure) { closeLuma(); if (failure instanceof BackendException) throw failure; throw fail(failure instanceof SocketTimeoutException ? "TIMEOUT" : "INVALID_RESPONSE"); }
    }
    private static byte[] appField(ByteBuffer frame, int max) throws Exception {
        if (frame.remaining() < 4) throw fail("INVALID_RESPONSE"); int size = frame.getInt();
        if (size < 0 || size > max || size > frame.remaining()) throw fail("INVALID_RESPONSE");
        byte[] value = new byte[size]; frame.get(value); return value;
    }
    private static void skipField(ByteBuffer frame) throws Exception {
        if (frame.remaining() < 4) throw fail("INVALID_RESPONSE"); int size = frame.getInt();
        if (size < 0 || size > frame.remaining()) throw fail("INVALID_RESPONSE"); frame.position(frame.position() + size);
    }
    private JSONObject lumaAction(String action, JSONObject params) throws Exception {
        requireLuma();
        if ("apps".equals(action)) return object("apps", getApps());
        if ("status".equals(action)) {
            JSONObject result = object("connected", true, "device", object("name", deviceName));
            String reply = tcpText("MediaControl\r\ngetVolume\r\n\r\n");
            Matcher numbers = Pattern.compile("\\d+").matcher(reply);
            List<Integer> values = new ArrayList<>(); while (numbers.find() && values.size() < 3) try { values.add(Integer.parseInt(numbers.group())); } catch (Exception ignored) { values.add(-1); }
            if (!reply.matches("(?s).*-[\\s]*[0-9].*") && values.size() == 2 && values.get(0) >= 0 && values.get(1) > 0 && values.get(1) <= 1000000 && values.get(0) <= values.get(1)) result.put("volume", object("current", values.get(0), "max", values.get(1)));
            return result;
        }
        if ("setVolume".equals(action)) {
            JSONObject fresh = lumaAction("status", new JSONObject()).optJSONObject("volume");
            if (fresh == null) throw fail("INVALID_RESPONSE");
            int maximum = fresh.getInt("max"), value = integer(params.opt("value"), 0, maximum);
            int level = Math.max(0, Math.min(30, (int) Math.round((double) value * 30 / maximum)));
            if (params.has("expectedSourceUrl")) {
                requireCurrent(params);
            }
            tcpWrite("MediaControl\r\nsetVolume " + level + "\r\n\r\n", true);
            return lumaAction("status", new JSONObject());
        }
        if ("powerOff".equals(action)) {
            if (!Boolean.TRUE.equals(params.opt("confirmed"))) throw fail("INVALID_INPUT");
            // Original Kodak app's confirmed power dialog sends this exact EShare key.
            // The receiver decides standby/shutdown behavior; this is a send receipt only.
            tcpWrite("KEYEVENT\r\n26\r\n\r\n", true);
        } else if ("key".equals(action)) {
            String named = text(params, "key", 32);
            Integer key = KEYS.get(named); if (key == null) throw fail("INVALID_INPUT");
            if (params.has("expectedSourceUrl") && (named.equals("volume-up") || named.equals("volume-down") || named.equals("mute"))) {
                requireCurrent(params);
            }
            tcpWrite("KEYEVENT\r\n" + key + "\r\n\r\n", true);
        } else if ("text".equals(action)) {
            Object value = params.opt("text");
            if (!(value instanceof String) || ((String) value).isEmpty() || ((String) value).length() > 1000 || ((String) value).getBytes(StandardCharsets.UTF_8).length > 4096 || ((String) value).matches("(?s).*[\\x00-\\x1f\\x7f].*")) throw fail("INVALID_TEXT");
            tcpWrite("CONTENT\r\n1\r\n0 " + value + "\r\n\r\n", true);
        } else if ("launchReceiver".equals(action) || "launchApp".equals(action)) {
            String packageName = "launchReceiver".equals(action) ? RECEIVER_PACKAGE : text(params, "packageName", 255);
            if (!PACKAGE.matcher(packageName).matches()) throw fail("INVALID_INPUT");
            if (apps == null) getApps(); if (!apps.containsKey(packageName)) throw fail("APP_NOT_AVAILABLE");
            tcpWrite("STARTAPPEVNET\r\n" + packageName + "\r\n\r\n", true);
        } else throw fail("INVALID_INPUT");
        return object("accepted", true);
    }

    private JSONArray discover() throws Exception {
        JSONArray result = new JSONArray(); Map<String, JSONObject> found = new LinkedHashMap<>();
        List<InetAddress> broadcasts = new ArrayList<>();
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nic.isUp() || nic.isLoopback() || !nic.getName().matches("(?:wlan|wifi|eth|en)[A-Za-z0-9_]*")) continue;
            for (InterfaceAddress item : nic.getInterfaceAddresses()) if (item.getAddress() instanceof Inet4Address && privateAddress(item.getAddress().getHostAddress()) && item.getBroadcast() != null && privateAddress(item.getBroadcast().getHostAddress()) && broadcasts.size() < 8) broadcasts.add(item.getBroadcast());
        }
        if (broadcasts.isEmpty()) return result;
        WifiManager manager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = manager == null ? null : manager.createMulticastLock("framextv-discovery");
        DatagramSocket socket = new DatagramSocket(null); discovering = socket;
        try {
            if (lock != null) { lock.setReferenceCounted(false); lock.acquire(); }
            socket.setReuseAddress(true); socket.bind(new InetSocketAddress(48689)); socket.setBroadcast(true);
            byte[] request = new byte[50]; ByteBuffer.wrap(request).order(ByteOrder.BIG_ENDIAN).putInt(12, 13);
            byte[] command = "FindECloudBox".getBytes(StandardCharsets.US_ASCII); System.arraycopy(command, 0, request, 20, command.length);
            long start = SystemClock.elapsedRealtime(), nextSend = start;
            while (!closed && SystemClock.elapsedRealtime() - start < 2500 && found.size() < 16) {
                long now = SystemClock.elapsedRealtime();
                if (now >= nextSend && nextSend - start <= 1400) { for (InetAddress broadcast : broadcasts) socket.send(new DatagramPacket(request, request.length, broadcast, 48689)); nextSend += 700; }
                socket.setSoTimeout(100); byte[] bytes = new byte[16385]; DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
                try { socket.receive(packet); } catch (SocketTimeoutException ignored) { continue; }
                String source = packet.getAddress().getHostAddress(); if (!privateAddress(source) || packet.getLength() > 16384) continue;
                String reply; try { byte[] body = new byte[packet.getLength()]; System.arraycopy(bytes, 0, body, 0, body.length); reply = decode(body).replaceAll("[\\x00\\r\\n]+$", ""); } catch (Exception ignored) { continue; }
                if (reply.matches("(?s).*[\\x00-\\x1f\\x7f-\\x9f].*")) continue;
                String[] fields = reply.split(":", -1);
                if (fields.length < 4 || fields.length > 16 || !fields[0].matches("ECloudBox[0-9]{0,6}") || fields[1].trim().isEmpty() || fields[1].length() > 120 || !"8121".equals(fields[2]) || !fields[3].matches("[0-9]{1,5}") || Integer.parseInt(fields[3]) > 65535) continue;
                found.put(source, object("host", source, "name", fields[1].trim(), "port", 8121));
            }
        } finally { discovering = null; socket.close(); if (lock != null && lock.isHeld()) lock.release(); }
        for (JSONObject device : found.values()) result.put(device); return result;
    }

    static String publicUrl(Object value) throws BackendException {
        if (!(value instanceof String)) throw fail("INVALID_MEDIA"); String raw = (String) value;
        if (raw.isEmpty() || raw.length() > 8192 || raw.matches("(?s).*[\\x00-\\x20\\x7f|\\\\#].*")) throw fail("INVALID_MEDIA");
        try {
            URI uri = new URI(raw); String protocol = uri.getScheme(), name = uri.getHost();
            if (!("http".equals(protocol) || "https".equals(protocol)) || uri.getRawUserInfo() != null || name == null || uri.getFragment() != null || uri.getPort() > 65535) throw fail("INVALID_MEDIA");
            name = name.toLowerCase(Locale.ROOT).replaceAll("^\\[|]$", "");
            if (name.matches("[0-9.]+")) {
                byte[] bytes = addressBytes(name); int a = bytes[0] & 255, b = bytes[1] & 255, c = bytes[2] & 255;
                if (a == 0 || a == 10 || a == 127 || a >= 224 || (a == 100 && b >= 64 && b <= 127) || (a == 169 && b == 254) || (a == 172 && b >= 16 && b <= 31) || (a == 192 && (b == 168 || (b == 0 && (c == 0 || c == 2)) || (b == 88 && c == 99))) || (a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100))) || (a == 203 && b == 0 && c == 113)) throw fail("INVALID_MEDIA");
            } else if (name.contains(":")) {
                String[] parts = name.split(":"); int first = Integer.parseInt(parts[0], 16), second = parts.length > 1 && !parts[1].isEmpty() ? Integer.parseInt(parts[1], 16) : 0;
                if (first < 0x2000 || first >= 0x4000 || first == 0x2002 || first == 0x3fff || (first == 0x2001 && (second < 0x200 || second == 0xdb8))) throw fail("INVALID_MEDIA");
            } else {
                String domain = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
                if (domain.length() > 253 || !domain.contains(".") || domain.substring(domain.lastIndexOf('.') + 1).matches("[0-9]+") || domain.matches("(?s).*(?:^|\\.)(?:localhost|local|localdomain|lan|home|internal|arpa)$")) throw fail("INVALID_MEDIA");
                for (String part : domain.split("\\.", -1)) if (!part.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) throw fail("INVALID_MEDIA");
            }
            return uri.toASCIIString();
        } catch (Exception ignored) { throw fail("INVALID_MEDIA"); }
    }
    // ---- LUMA Receiver (android-receiver/) LAN control API ----
    // One public operation may need two receiver calls; they share one budget so
    // a stalled projector cannot hold the service queue for longer than this.
    private JSONObject receiverInfo() throws Exception { return call("GET", "info", null, false); }
    private JSONObject call(String method, JSONObject params, boolean mutation) throws Exception { return call("POST", method, params, mutation); }
    private JSONObject call(String verb, String method, JSONObject params, boolean mutation) throws Exception {
        requireHost();
        boolean open = "info".equals(method) || method.startsWith("pair.");
        if (!open && token == null) throw fail("RECEIVER_CONFIG");
        if (receiverDeadline == 0) receiverDeadline = SystemClock.elapsedRealtime() + RECEIVER_CALL_TIMEOUT;
        long remaining = receiverDeadline - SystemClock.elapsedRealtime();
        if (remaining <= 0) throw fail("RECEIVER_UNAVAILABLE");
        HttpURLConnection connection = null; ScheduledFuture<?> deadline = null;
        int status;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            connection = (HttpURLConnection) new URL("http", host, receiverPort, "/api/v1/" + method).openConnection(Proxy.NO_PROXY); activeHttp = connection;
            final HttpURLConnection owned = connection; deadline = deadlines.schedule(owned::disconnect, remaining, TimeUnit.MILLISECONDS);
            connection.setConnectTimeout((int) remaining); connection.setReadTimeout((int) remaining); connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(verb); connection.setUseCaches(false);
            if (!open) connection.setRequestProperty("Authorization", "Bearer " + token);
            if ("POST".equals(verb)) {
                byte[] body = (params == null ? new JSONObject() : params).toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream stream = connection.getOutputStream()) { if (mutation) guard(); stream.write(body); }
            }
            status = connection.getResponseCode();
            if (connection.getContentLength() > MAX_RECEIVER_REPLY) throw fail("RECEIVER_RESPONSE");
            byte[] chunk = new byte[8192];
            try (InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                int count; while (stream != null && (count = stream.read(chunk)) >= 0) { if (out.size() + count > MAX_RECEIVER_REPLY) throw fail("RECEIVER_RESPONSE"); out.write(chunk, 0, count); }
            }
        } catch (BackendException failure) { android.util.Log.i("LumaReceiver", method + " " + failure.code); throw failure; }
        catch (Exception unreachable) { android.util.Log.i("LumaReceiver", method + " RECEIVER_UNAVAILABLE"); throw fail("RECEIVER_UNAVAILABLE"); }
        finally { if (deadline != null) deadline.cancel(false); if (connection != null) connection.disconnect(); activeHttp = null; }
        JSONObject reply;
        try {
            JSONTokener json = new JSONTokener(decode(out.toByteArray())); Object value = json.nextValue();
            if (!(value instanceof JSONObject) || json.nextClean() != 0) throw new Exception();
            reply = (JSONObject) value;
        } catch (Exception invalid) { android.util.Log.i("LumaReceiver", method + " MALFORMED_JSON"); throw fail("RECEIVER_RESPONSE"); }
        if (reply.optBoolean("ok") && status == 200) {
            if ("info".equals(method)) return reply.optJSONObject("result") == null ? fail0() : reply.getJSONObject("result");
            JSONObject result = reply.optJSONObject("result"); if (result == null) throw fail("RECEIVER_RESPONSE");
            return result;
        }
        JSONObject error = reply.optJSONObject("error"); String code = error == null ? "" : error.optString("code");
        // Fixed method + code only: never log receiver URLs or tokens.
        android.util.Log.i("LumaReceiver", method + " " + (code.matches("[A-Z_]{1,40}") ? code : "HTTP_" + status));
        if ("UNAUTHORIZED".equals(code)) throw fail("RECEIVER_AUTH");
        if ("INVALID_CONTENT".equals(code)) throw fail("INVALID_MEDIA");
        switch (code) {
            case "SOURCE_CHANGED": case "NO_VIDEO": case "CANNOT_SEEK": case "INVALID_SUBTITLE": case "INVALID_MEDIA":
            case "PAIRING_CODE_WRONG": case "PAIRING_EXPIRED":
            case "SUBTITLE_DOWNLOAD": case "SUBTITLE_INVALID": case "SUBTITLE_TOO_LARGE": throw fail(code);
            default:
                // The receiver validates every request before acting, so any other 4xx
                // was rejected without effect; 5xx/unknown outcomes stay ambiguous.
                throw fail(status >= 400 && status < 500 ? "RECEIVER_REJECTED" : "RECEIVER_RESPONSE");
        }
    }
    private static JSONObject fail0() throws BackendException { throw fail("RECEIVER_RESPONSE"); }
    private static JSONObject validatedLumaContent(JSONObject media) {
        try {
            JSONObject value = media == null ? null : media.optJSONObject("content");
            if (value == null || !(value.opt("kind") instanceof String) || !(value.opt("contentId") instanceof String)
                    || !(value.opt("key") instanceof String) || !(value.opt("watchUrl") instanceof String)
                    || !(value.opt("title") instanceof String) || !value.has("season") || !value.has("episode")) return null;
            String kind = value.getString("kind"), id = value.getString("contentId"), title = value.getString("title");
            Integer season = null, episode = null;
            if ("tv".equals(kind)) {
                season = integer(value.opt("season"), 0, 9999); episode = integer(value.opt("episode"), 1, 9999);
            } else if (!"movie".equals(kind) || !value.isNull("season") || !value.isNull("episode")) return null;
            JSONObject safe = WatchHistory.contentFromCatalog(kind, id, season, episode, title);
            if (safe == null || !safe.optString("key").equals(value.getString("key"))
                    || !safe.optString("watchUrl").equals(value.getString("watchUrl"))
                    || !safe.optString("title").equals(title)) return null;
            return safe;
        } catch (Exception invalid) { return null; }
    }
    /** Canonical identity reported by the receiver, re-derived exactly like a fresh catalog item. */
    private static JSONObject receiverContent(JSONObject value) {
        try {
            if (value == null) return null;
            String kind = value.getString("kind");
            Integer season = "tv".equals(kind) ? integer(value.opt("season"), 0, 9999) : null;
            Integer episode = "tv".equals(kind) ? integer(value.opt("episode"), 1, 9999) : null;
            String title = value.optString("title", "");
            if (!title.equals(label(title, 200))) return null;
            JSONObject safe = WatchHistory.contentFromCatalog(kind, value.getString("contentId"), season, episode, title);
            return safe != null && safe.optString("key").equals(value.optString("key")) ? safe : null;
        } catch (Exception invalid) { return null; }
    }
    private static String contentKey(Object raw) throws BackendException {
        if (!(raw instanceof String) || !LUMA_MOVIE_ID.matcher("v1:" + raw).matches() && !LUMA_EPISODE_ID.matcher("v1:" + raw).matches()) throw fail("INVALID_INPUT");
        return (String) raw;
    }
    /** Copies validated source guards; the receiver applies them atomically with the write. */
    private static JSONObject guarded(JSONObject params, JSONObject command) throws Exception {
        if (params.has("expectedSourceUrl")) command.put("expectedSourceUrl", publicUrl(params.opt("expectedSourceUrl")));
        if (params.has("expectedContentKey")) command.put("expectedContentKey", contentKey(params.opt("expectedContentKey")));
        return command;
    }
    private JSONObject playback() throws Exception {
        JSONObject data = call("player.status", new JSONObject(), false);
        if (!(data.opt("active") instanceof Boolean)) throw fail("RECEIVER_RESPONSE");
        if (!data.getBoolean("active")) {
            JSONObject idle = object("active", false, "playing", false);
            if (data.opt("error") instanceof String) idle.put("error", label(data.getString("error"), 64));
            return idle;
        }
        Object position = data.opt("positionSeconds"), duration = data.opt("durationSeconds");
        if (!(position instanceof Number) || !(duration instanceof Number) || !(data.opt("canSeek") instanceof Boolean) || !(data.opt("sourceUrl") instanceof String)) throw fail("RECEIVER_RESPONSE");
        JSONObject result = object("active", true, "playing", data.optBoolean("playing"), "paused", data.optBoolean("paused"),
            "positionSeconds", Math.max(0, ((Number) position).doubleValue()), "durationSeconds", Math.max(0, ((Number) duration).doubleValue()),
            "canSeek", data.getBoolean("canSeek"), "sourceUrl", data.getString("sourceUrl"),
            "state", label(data.optString("state", ""), 16), "revision", data.optLong("revision"));
        JSONObject content = receiverContent(data.optJSONObject("content"));
        if (content != null) result.put("content", content);
        return result;
    }
    private void requireCurrent(JSONObject params) throws Exception {
        JSONObject current = playback();
        if (!current.getBoolean("active")) throw fail("NO_VIDEO");
        if (params.has("expectedSourceUrl") && !publicUrl(params.opt("expectedSourceUrl")).equals(current.getString("sourceUrl"))) throw fail("SOURCE_CHANGED");
        if (params.has("expectedContentKey") && !contentKey(params.opt("expectedContentKey")).equals(current.optJSONObject("content") == null ? "" : current.getJSONObject("content").optString("key"))) throw fail("SOURCE_CHANGED");
    }
    private JSONObject subtitles(JSONObject params) throws Exception {
        JSONObject data = call("player.subtitles", guarded(params, new JSONObject()), false);
        JSONArray list = data.optJSONArray("tracks"); if (list == null || list.length() > 128 || !(data.opt("enabled") instanceof Boolean)) throw fail("RECEIVER_RESPONSE");
        if (!data.optBoolean("active")) return object("active", false, "enabled", false, "currentIndex", JSONObject.NULL, "tracks", new JSONArray());
        JSONArray tracks = new JSONArray(); List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject entry = list.optJSONObject(i); if (entry == null) throw fail("RECEIVER_RESPONSE");
            int index = integer(entry.opt("index"), 0, 1024); if (indices.contains(index)) throw fail("RECEIVER_RESPONSE"); indices.add(index);
            tracks.put(object("index", index, "name", label(entry.optString("name", ""), 200), "language", label(entry.optString("language", ""), 64)));
        }
        Object current = data.opt("currentIndex") instanceof Number && indices.contains(((Number) data.opt("currentIndex")).intValue()) ? ((Number) data.opt("currentIndex")).intValue() : JSONObject.NULL;
        return object("active", true, "enabled", data.getBoolean("enabled"), "currentIndex", current, "tracks", tracks);
    }
    /** Optional fixture port (tests only); the shipped UI never sends one, so 4191 is used. */
    private void receiverPort(JSONObject params) throws BackendException {
        if (params.has("port")) receiverPort = integer(params.opt("port"), 1024, 65535);
    }
    private JSONObject receiverAction(String action, JSONObject params) throws Exception {
        if ("configure".equals(action) || "pairBegin".equals(action) || "pairComplete".equals(action)) receiverPort(params);
        if ("configure".equals(action)) {
            requireHost();
            String saved = text(params, "token", 200);
            if (!saved.matches("[A-Za-z0-9_-]{20,200}")) throw fail("RECEIVER_CONFIG");
            token = saved; return object("configured", true);
        }
        if ("pairBegin".equals(action)) {
            JSONObject info = receiverInfo();
            if (!"lumareceiver".equals(info.optString("app"))) throw fail("RECEIVER_RESPONSE");
            String name = label("LUMA Remote · " + android.os.Build.MODEL, 60);
            JSONObject begun = call("pair.begin", object("clientName", name), true);
            return object("pairingId", text(begun, "pairingId", 64), "expiresInSeconds", begun.optInt("expiresInSeconds", 120));
        }
        if ("pairComplete".equals(action)) {
            String id = text(params, "pairingId", 64), code = text(params, "code", 12).replaceAll("\\s", "");
            if (!id.matches("[A-Za-z0-9_-]{8,64}") || !code.matches("[0-9]{6}")) throw fail("PAIRING_CODE_WRONG");
            JSONObject paired = call("pair.complete", object("pairingId", id, "code", code), true);
            String issued = text(paired, "token", 200); if (!issued.matches("[A-Za-z0-9_-]{20,200}")) throw fail("RECEIVER_RESPONSE");
            token = issued;
            // The service persists this token encrypted and strips it before replying to the page.
            return object("configured", true, "token", issued);
        }
        if ("connect".equals(action)) {
            JSONObject info = receiverInfo();
            if (!"lumareceiver".equals(info.optString("app"))) throw fail("RECEIVER_RESPONSE");
            playback(); // proves the saved token
            return object("ready", true, "name", "LUMA Receiver", "version", label(info.optString("versionName"), 32),
                "receiverId", label(info.optString("receiverId"), 64));
        }
        if ("open".equals(action)) {
            JSONObject media = params.optJSONObject("media"); if (media == null) throw fail("INVALID_MEDIA");
            String url = publicUrl(media.opt("url")), type = media.optString("contentType", "").split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (!(type.equals("video/mp4") || type.equals("video/webm") || type.equals("application/vnd.apple.mpegurl") || type.equals("application/x-mpegurl") || type.equals("application/dash+xml"))) throw fail("INVALID_MEDIA");
            if (params.has("expectedSourceUrl") && !url.equals(publicUrl(params.opt("expectedSourceUrl")))) throw fail("SOURCE_CHANGED");
            JSONObject content = validatedLumaContent(media);
            if (params.has("expectedContentKey") && (content == null || !contentKey(params.opt("expectedContentKey")).equals(content.optString("key")))) throw fail("SOURCE_CHANGED");
            if (params.has("onlyIfInactive") && !(params.opt("onlyIfInactive") instanceof Boolean)) throw fail("INVALID_INPUT");
            boolean hasCurrentSource = params.has("expectedCurrentSourceUrl"), hasCurrentKey = params.has("expectedCurrentContentKey");
            if (hasCurrentSource != hasCurrentKey || params.optBoolean("onlyIfInactive") && hasCurrentSource) throw fail("INVALID_INPUT");
            JSONObject request = object("url", url, "contentType", type);
            if (content != null) request.put("content", content).put("title", label(content.optString("title"), 200));
            if (media.opt("resumeSeconds") instanceof Number && ((Number) media.opt("resumeSeconds")).doubleValue() > 0)
                request.put("resumeSeconds", Math.min(17280000, ((Number) media.opt("resumeSeconds")).doubleValue()));
            JSONObject command = object("media", request, "onlyIfInactive", params.optBoolean("onlyIfInactive"));
            if (hasCurrentSource) command.put("expectedCurrentSourceUrl", publicUrl(params.opt("expectedCurrentSourceUrl")))
                .put("expectedCurrentContentKey", contentKey(params.opt("expectedCurrentContentKey")));
            call("player.open", command, true);
            return object("accepted", true);
        }
        if ("status".equals(action)) return playback();
        if ("getSubtitles".equals(action)) return subtitles(params);
        if ("addSubtitle".equals(action)) {
            JSONObject track = params.optJSONObject("track"); if (track == null) throw fail("INVALID_SUBTITLE");
            String url = publicUrl(track.opt("url"));
            if (params.has("preparedId")) {
                String id = text(params, "preparedId", 128);
                if (!id.matches("[A-Za-z0-9_-]{16,128}") || subtitleResolver == null || !params.has("expectedSourceUrl")) throw fail("INVALID_SUBTITLE");
                // Only a service-owned ticket can resolve to a private, receiver-pinned URL.
                url = subtitleResolver.resolve(id, publicUrl(params.opt("expectedSourceUrl")), url, host);
            }
            // Type, language and label are hints; the receiver sniffs and decodes the file itself.
            String hint = params.has("preparedId") ? "application/x-subrip" : track.optString("contentType", "").split(";", 2)[0].trim();
            JSONObject subtitle = object("url", url, "mimeType", hint.matches("[a-z0-9.+-]{1,40}/[a-z0-9.+-]{1,60}") ? hint : "", "select", true);
            String language = track.optString("language", "");
            if (language.matches("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*")) subtitle.put("language", language);
            String name = label(track.optString("label", track.optString("name", "")), 120);
            if (!name.isEmpty()) subtitle.put("label", name);
            if (((String) subtitle.get("mimeType")).isEmpty()) subtitle.remove("mimeType");
            call("player.addSubtitle", guarded(params, object("track", subtitle)), true);
            return object("accepted", true);
        }
        if ("setSubtitle".equals(action)) {
            Object index = params.opt("index");
            if (!"off".equals(index)) integer(index, 0, 1024);
            call("player.setSubtitle", guarded(params, object("index", index)), true);
            return object("accepted", true);
        }
        if ("seek".equals(action)) {
            Object raw = params.opt("position"); if (!(raw instanceof Number)) throw fail("INVALID_INPUT"); double value = ((Number) raw).doubleValue();
            if (Double.isNaN(value) || Double.isInfinite(value) || value < 0 || value > 86399) throw fail("INVALID_INPUT");
            call("player.seek", guarded(params, object("position", value)), true);
            return object("accepted", true);
        }
        if ("pause".equals(action) || "resume".equals(action) || "stop".equals(action)) {
            call("player." + action, guarded(params, new JSONObject()), true);
            return object("accepted", true);
        }
        if ("scrub".equals(action)) {
            if (!(params.opt("active") instanceof Boolean)) throw fail("INVALID_INPUT");
            JSONObject command = object("active", params.getBoolean("active"));
            if (params.getBoolean("active")) {
                Object position = params.opt("positionSeconds");
                if (!(position instanceof Number) || ((Number) position).doubleValue() < 0 || ((Number) position).doubleValue() > 86399) throw fail("INVALID_INPUT");
                command.put("positionSeconds", ((Number) position).doubleValue());
                if (params.opt("durationSeconds") instanceof Number) command.put("durationSeconds", Math.max(0, Math.min(86399, ((Number) params.opt("durationSeconds")).doubleValue())));
                Object image = params.opt("image");
                if (image instanceof String && ((String) image).startsWith("data:image/jpeg;base64,") && ((String) image).length() <= 200_000) command.put("image", image);
            }
            return call("player.scrub", command, false);
        }
        if ("display".equals(action)) {
            JSONObject changes = new JSONObject();
            for (String key : new String[] {"brightness", "contrast", "gamma", "subtitleScale"}) if (params.opt(key) instanceof Number) changes.put(key, params.get(key));
            if ("box".equals(params.opt("subtitleStyle")) || "outline".equals(params.opt("subtitleStyle"))) changes.put("subtitleStyle", params.get("subtitleStyle"));
            if (Boolean.TRUE.equals(params.opt("reset"))) changes.put("reset", true);
            return changes.length() == 0 ? call("display.get", new JSONObject(), false) : call("display.set", changes, true);
        }
        if ("libraryPull".equals(action)) {
            return call("library.pull", object("since", integer(params.opt("since"), 0, Integer.MAX_VALUE), "limit", 200), false);
        }
        if ("libraryPush".equals(action)) {
            JSONArray records = params.optJSONArray("records"); if (records == null || records.length() > 200) throw fail("INVALID_INPUT");
            return call("library.push", object("records", records), true);
        }
        throw fail("INVALID_INPUT");
    }

    public void close() {
        closed = true; closeLuma(); DatagramSocket udp = discovering; if (udp != null) udp.close();
        HttpURLConnection http = activeHttp; if (http != null) http.disconnect(); deadlines.shutdownNow();
        token = null;
    }
}

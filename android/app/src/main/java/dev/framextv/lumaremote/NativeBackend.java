package dev.framextv.lumaremote;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
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
    public static final class BackendException extends Exception {
        public final String code;
        private BackendException(String code, String message) { super(message); this.code = code; }
    }
    private static final int TCP_TIMEOUT = 2500, MAX_REPLY = 65536, MAX_APPS_BYTES = 16 * 1024 * 1024;
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+");
    private static final Map<String, Integer> KEYS = new LinkedHashMap<>();
    static {
        KEYS.put("up", 19); KEYS.put("down", 20); KEYS.put("left", 21); KEYS.put("right", 22);
        KEYS.put("enter", 23); KEYS.put("back", 4); KEYS.put("home", 3); KEYS.put("menu", 82);
        KEYS.put("volume-up", 24); KEYS.put("volume-down", 25); KEYS.put("mute", 164);
    }
    private final Context context;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor();
    private volatile SourceGuard sourceGuard;
    private volatile boolean closed;
    private volatile Socket luma;
    private volatile DatagramSocket discovering;
    private volatile HttpURLConnection activeHttp;
    private String host, deviceName, username, password, activeToken;
    private int kodiPort = 8080, rpcId;
    private Map<String, String> apps;

    public NativeBackend(Context context) { this.context = context.getApplicationContext(); }
    public void setSourceGuard(SourceGuard guard) { sourceGuard = guard; }
    /** Service worker only: forgetting Kodi does not close projector controls. */
    public synchronized void clearKodiConfiguration() { username = password = null; }

    /** Read on the service's network queue; never includes credentials. */
    public synchronized JSONObject connectionSnapshot() {
        try { return object("connected", !closed && luma != null && !luma.isClosed(),
                "configured", !closed && username != null && password != null,
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
            case "KODI_CONFIG": message = "Enter the username, password and HTTP port configured in Kodi."; break;
            case "KODI_AUTH": message = "Kodi rejected the username or password."; break;
            case "KODI_UNAVAILABLE": message = "Could not reach Kodi. Open Kodi and check HTTP control and your home network."; break;
            case "KODI_RESPONSE": message = "Kodi did not accept this control command."; break;
            case "NO_VIDEO": message = "Kodi has no active video. Start a video first."; break;
            case "SOURCE_CHANGED": message = "The video source changed. Choose Cast again before controlling this video."; break;
            case "INVALID_MEDIA": message = "The page does not expose a supported public HTTP media source."; break;
            case "INVALID_SUBTITLE": message = "Choose an available subtitle track or turn subtitles off."; break;
            case "CANNOT_SEEK": message = "Kodi reports that this video cannot seek."; break;
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
                closeLuma(); host = null; username = password = null; return object("connected", false);
            }
            if ("kodi.configure".equals(method)) {
                requireHost();
                String user, secret; int port;
                try { user = text(params, "username", 256); secret = text(params, "password", 256); port = params.has("port") ? integer(params.opt("port"), 1, 65535) : 8080; }
                catch (BackendException ignored) { throw fail("KODI_CONFIG"); }
                if (user.matches("(?s).*[:\\r\\n\\x00].*") || secret.matches("(?s).*[\\r\\n\\x00].*")) throw fail("KODI_CONFIG");
                kodiPort = port;
                username = user; password = secret; return object("configured", true);
            }
            if (method != null && method.startsWith("luma.")) return lumaAction(method.substring(5), params);
            if (method != null && method.startsWith("kodi.")) return kodiAction(method.substring(5), params);
            throw fail("INVALID_INPUT");
        } catch (BackendException failure) {
            if ("KODI_AUTH".equals(failure.code)) username = password = null;
            throw failure;
        }
        catch (Exception ignored) { throw fail("CONNECTION_FAILED"); }
        finally { activeToken = null; }
    }

    private void requireHost() throws BackendException { if (!privateAddress(host)) throw fail("NOT_CONNECTED"); }
    private void requireLuma() throws BackendException { requireHost(); if (luma == null || luma.isClosed()) throw fail("NOT_CONNECTED"); }
    private void closeLuma() { Socket old = luma; luma = null; apps = null; if (old != null) try { old.close(); } catch (Exception ignored) {} }
    private JSONObject connectDevice(String selected) throws Exception {
        if (!privateAddress(selected)) throw fail("INVALID_HOST");
        closeLuma();
        if (!selected.equals(host)) username = password = null;
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
                Integer player = player(); if (player == null) throw fail("NO_VIDEO"); expectedSource(player, params);
            }
            tcpWrite("MediaControl\r\nsetVolume " + level + "\r\n\r\n", true);
            return lumaAction("status", new JSONObject());
        }
        if ("key".equals(action)) {
            String named = text(params, "key", 32);
            Integer key = KEYS.get(named); if (key == null) throw fail("INVALID_INPUT");
            if (params.has("expectedSourceUrl") && (named.equals("volume-up") || named.equals("volume-down") || named.equals("mute"))) {
                Integer player = player(); if (player == null) throw fail("NO_VIDEO"); expectedSource(player, params);
            }
            tcpWrite("KEYEVENT\r\n" + key + "\r\n\r\n", true);
        } else if ("text".equals(action)) {
            Object value = params.opt("text");
            if (!(value instanceof String) || ((String) value).isEmpty() || ((String) value).length() > 1000 || ((String) value).getBytes(StandardCharsets.UTF_8).length > 4096 || ((String) value).matches("(?s).*[\\x00-\\x1f\\x7f].*")) throw fail("INVALID_TEXT");
            tcpWrite("CONTENT\r\n1\r\n0 " + value + "\r\n\r\n", true);
        } else if ("launchKodi".equals(action) || "launchApp".equals(action)) {
            String packageName = "launchKodi".equals(action) ? "org.xbmc.kodi" : text(params, "packageName", 255);
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
    private Object rpc(String method, JSONObject params, boolean mutation) throws Exception {
        requireHost(); if (username == null || password == null) throw fail("KODI_CONFIG");
        HttpURLConnection connection = null; ScheduledFuture<?> deadline = null;
        try {
            connection = (HttpURLConnection) new URL("http", host, kodiPort, "/jsonrpc").openConnection(Proxy.NO_PROXY); activeHttp = connection;
            final HttpURLConnection owned = connection; deadline = deadlines.schedule(owned::disconnect, 5, TimeUnit.SECONDS);
            connection.setConnectTimeout(5000); connection.setReadTimeout(5000); connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setUseCaches(false);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Basic " + Base64.encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
            int id = ++rpcId; byte[] body = object("jsonrpc", "2.0", "id", id, "method", method, "params", params).toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream stream = connection.getOutputStream()) { if (mutation) guard(); stream.write(body); }
            int status = connection.getResponseCode(); if (status == 401 || status == 403) throw fail("KODI_AUTH"); if (status != 200) throw fail("KODI_RESPONSE");
            if (connection.getContentLength() > MAX_REPLY) throw fail("KODI_RESPONSE");
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] chunk = new byte[4096];
            try (InputStream stream = connection.getInputStream()) { int count; while ((count = stream.read(chunk)) >= 0) { if (out.size() + count > MAX_REPLY) throw fail("KODI_RESPONSE"); out.write(chunk, 0, count); } }
            JSONObject reply = new JSONObject(decode(out.toByteArray()));
            Object replyId = reply.opt("id");
            if (!"2.0".equals(reply.opt("jsonrpc")) || !(replyId instanceof Number) || ((Number) replyId).doubleValue() != id || !reply.has("result") || reply.has("error")) throw fail("KODI_RESPONSE");
            return reply.get("result");
        } catch (BackendException failure) { throw failure; }
        catch (Exception ignored) { throw fail("KODI_UNAVAILABLE"); }
        finally { if (deadline != null) deadline.cancel(false); if (connection != null) connection.disconnect(); activeHttp = null; }
    }
    private JSONObject rpcObject(String method, JSONObject params) throws Exception {
        Object result = rpc(method, params, false); if (!(result instanceof JSONObject)) throw fail("KODI_RESPONSE"); return (JSONObject) result;
    }
    private Integer player() throws Exception {
        Object result = rpc("Player.GetActivePlayers", new JSONObject(), false); if (!(result instanceof JSONArray)) throw fail("KODI_RESPONSE");
        JSONArray list = (JSONArray) result; if (list.length() > 32) throw fail("KODI_RESPONSE");
        for (int i = 0; i < list.length(); i++) { JSONObject item = list.optJSONObject(i); if (item != null && "video".equals(item.optString("type"))) return integer(item.opt("playerid"), 0, 1024); }
        return null;
    }
    private String currentSource(int player) throws Exception {
        JSONObject item = rpcObject("Player.GetItem", object("playerid", player, "properties", new JSONArray().put("file"))).optJSONObject("item");
        if (item == null || !(item.opt("file") instanceof String) || item.optString("file").isEmpty() || item.optString("file").length() > 8192) throw fail("KODI_RESPONSE"); return item.getString("file");
    }
    private void expectedSource(int player, JSONObject params) throws Exception {
        if (params.has("expectedSourceUrl") && !publicUrl(params.opt("expectedSourceUrl")).equals(currentSource(player))) throw fail("SOURCE_CHANGED");
    }
    private static int seconds(JSONObject time) throws Exception {
        if (time == null) throw fail("KODI_RESPONSE");
        return integer(time.opt("hours"), 0, 10000) * 3600 + integer(time.opt("minutes"), 0, 59) * 60 + integer(time.opt("seconds"), 0, 59);
    }
    private JSONObject playback(Integer player) throws Exception {
        if (player == null) return object("active", false, "playing", false);
        JSONObject data = rpcObject("Player.GetProperties", object("playerid", player, "properties", new JSONArray().put("speed").put("time").put("totaltime").put("canseek")));
        if (!(data.opt("speed") instanceof Number) || !(data.opt("canseek") instanceof Boolean)) throw fail("KODI_RESPONSE");
        return object("active", true, "playing", data.getDouble("speed") != 0, "positionSeconds", seconds(data.optJSONObject("time")), "durationSeconds", seconds(data.optJSONObject("totaltime")), "canSeek", data.getBoolean("canseek"), "sourceUrl", currentSource(player));
    }
    private JSONObject subtitles(Integer player) throws Exception {
        if (player == null) return object("active", false, "enabled", false, "currentIndex", JSONObject.NULL, "tracks", new JSONArray());
        JSONObject data = rpcObject("Player.GetProperties", object("playerid", player, "properties", new JSONArray().put("subtitles").put("currentsubtitle").put("subtitleenabled")));
        JSONArray list = data.optJSONArray("subtitles"); if (list == null || list.length() > 128 || !(data.opt("subtitleenabled") instanceof Boolean)) throw fail("KODI_RESPONSE");
        JSONArray tracks = new JSONArray(); List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject entry = list.optJSONObject(i); if (entry == null) throw fail("KODI_RESPONSE");
            int index = integer(entry.opt("index"), 0, 1024); if (indices.contains(index)) throw fail("KODI_RESPONSE"); indices.add(index);
            tracks.put(object("index", index, "name", label(entry.optString("name", ""), 200), "language", label(entry.optString("language", ""), 64)));
        }
        JSONObject selected = data.optJSONObject("currentsubtitle"); Object current = JSONObject.NULL;
        if (selected != null && selected.opt("index") instanceof Number) {
            double index = ((Number) selected.opt("index")).doubleValue();
            if (index >= 0 && index <= 1024 && index == Math.floor(index) && indices.contains((int) index)) current = (int) index;
        }
        return object("active", true, "enabled", data.getBoolean("subtitleenabled"), "currentIndex", current, "tracks", tracks);
    }
    private JSONObject kodiAction(String action, JSONObject params) throws Exception {
        if ("connect".equals(action)) {
            if (!"pong".equals(rpc("JSONRPC.Ping", new JSONObject(), false))) throw fail("KODI_RESPONSE");
            JSONObject data = rpcObject("Application.GetProperties", object("properties", new JSONArray().put("name").put("version")));
            JSONObject version = data.optJSONObject("version"); if (!"Kodi".equals(data.optString("name")) || version == null) throw fail("KODI_RESPONSE");
            return object("ready", true, "name", "Kodi", "version", integer(version.opt("major"), 0, 1000) + "." + integer(version.opt("minor"), 0, 1000));
        }
        if ("open".equals(action)) {
            JSONObject media = params.optJSONObject("media"); if (media == null) throw fail("INVALID_MEDIA");
            String url = publicUrl(media.opt("url")), type = media.optString("contentType", "").split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (!(type.equals("video/mp4") || type.equals("video/webm") || type.equals("application/vnd.apple.mpegurl") || type.equals("application/x-mpegurl") || type.equals("application/dash+xml"))) throw fail("INVALID_MEDIA");
            if (params.has("expectedSourceUrl") && !url.equals(publicUrl(params.opt("expectedSourceUrl")))) throw fail("SOURCE_CHANGED");
            if (params.has("onlyIfInactive") && !(params.opt("onlyIfInactive") instanceof Boolean)) throw fail("INVALID_INPUT");
            if (params.optBoolean("onlyIfInactive") && player() != null) throw fail("SOURCE_CHANGED");
            rpc("Player.Open", object("item", object("file", url)), true); return object("accepted", true);
        }
        Integer player = player();
        if ("status".equals(action)) return playback(player);
        if ("getSubtitles".equals(action)) { if (player != null) expectedSource(player, params); return subtitles(player); }
        if (player == null) throw fail("NO_VIDEO");
        if ("addSubtitle".equals(action)) {
            JSONObject track = params.optJSONObject("track"); if (track == null) throw fail("INVALID_SUBTITLE");
            String url = publicUrl(track.opt("url")); expectedSource(player, params);
            rpc("Player.AddSubtitle", object("playerid", player, "subtitle", url), true); return object("accepted", true);
        }
        if ("setSubtitle".equals(action)) {
            Object index = params.opt("index"); JSONObject command = object("playerid", player);
            if ("off".equals(index)) command.put("subtitle", "off");
            else {
                int selected = integer(index, 0, 1024); JSONArray tracks = subtitles(player).getJSONArray("tracks"); boolean found = false;
                for (int i = 0; i < tracks.length(); i++) if (tracks.getJSONObject(i).getInt("index") == selected) found = true;
                if (!found) throw fail("INVALID_SUBTITLE"); command.put("subtitle", selected).put("enable", true);
            }
            expectedSource(player, params); rpc("Player.SetSubtitle", command, true); return object("accepted", true);
        }
        if ("seek".equals(action)) {
            Object raw = params.opt("position"); if (!(raw instanceof Number)) throw fail("INVALID_INPUT"); double value = ((Number) raw).doubleValue();
            if (Double.isNaN(value) || Double.isInfinite(value) || value < 0 || value > 86399) throw fail("INVALID_INPUT");
            JSONObject current = playback(player); if (!current.getBoolean("canSeek")) throw fail("CANNOT_SEEK");
            int target = (int) Math.floor(Math.min(value, current.getInt("durationSeconds") > 0 ? current.getInt("durationSeconds") : value));
            expectedSource(player, params); rpc("Player.Seek", object("playerid", player, "value", object("time", object("hours", target / 3600, "minutes", target % 3600 / 60, "seconds", target % 60, "milliseconds", 0))), true);
        } else if ("pause".equals(action) || "resume".equals(action)) {
            expectedSource(player, params); rpc("Player.PlayPause", object("playerid", player, "play", "resume".equals(action)), true);
        } else if ("stop".equals(action)) {
            expectedSource(player, params); rpc("Player.Stop", object("playerid", player), true);
        } else throw fail("INVALID_INPUT");
        return object("accepted", true);
    }

    public void close() {
        closed = true; closeLuma(); DatagramSocket udp = discovering; if (udp != null) udp.close();
        HttpURLConnection http = activeHttp; if (http != null) http.disconnect(); deadlines.shutdownNow();
        username = password = null;
    }
}

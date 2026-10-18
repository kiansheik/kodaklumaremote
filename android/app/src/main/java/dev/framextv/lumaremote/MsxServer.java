package dev.framextv.lumaremote;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Phone-hosted control/receiver assets only. The TV fetches all media itself.
 * Never expose this class to the browsing WebView or publish its capability URL. */
public final class MsxServer implements AutoCloseable {
    public static final class MsxException extends Exception {
        public final String code;
        MsxException(String code) { super(message(code)); this.code = code; }
        private static String message(String code) {
            switch (code) {
                case "MSX_NETWORK": return "Connect this phone to the same Wi-Fi as the TV.";
                case "MSX_NOT_CONNECTED": return "Open the Media Station X receiver on the TV and approve it here.";
                case "MSX_PAIRING": return "The TV pairing request expired or changed. Start setup again.";
                case "MSX_TIMEOUT": return "The TV did not confirm this command. Check its screen before trying again.";
                case "MSX_REJECTED": return "The TV could not complete this control command.";
                case "SOURCE_CHANGED": return "The video changed. Cast again before controlling this video.";
                case "INVALID_MEDIA": return "Choose a supported public HTTP video or subtitle source.";
                default: return "The requested TV control is not valid.";
            }
        }
    }
    private static final int DEFAULT_PORT = 4178, MAX_BODY = 128 * 1024, MAX_HEADER = 8192;
    private static final long PAIRING_MS = 5 * 60 * 1000, FRESH_MS = 12000;
    private static final Set<String> ASSETS = new HashSet<>(Arrays.asList(
            "receiver.html", "receiver.js", "receiver-core.js", "receiver.css", "hls.min.js", "tvx-plugin.min.js"));
    private final Context context;
    private final SharedPreferences preferences;
    private final Object lock = new Object();
    private final SecureRandom random = new SecureRandom();
    private final Map<Long, Pending> commands = new LinkedHashMap<>();
    private final Set<Socket> clients = new HashSet<>();
    private ServerSocket listener;
    private ThreadPoolExecutor workers;
    private String host = "", token, approvedHost, approvedClient, pendingHost, pendingClient, sessionId;
    private int port;
    private long pairingUntil, lastSeen, lastState, sequence;
    private JSONObject playback = new JSONObject();
    private JSONObject lastCommand = new JSONObject();
    private boolean approved;
    private volatile NativeBackend.SourceGuard sourceGuard;

    private static final class Pending {
        final long id;
        final String session;
        final JSONObject command;
        final String sourceToken;
        boolean delivered, done, ok;
        String error;
        Pending(long id, String session, JSONObject command, String sourceToken) { this.id = id; this.session = session; this.command = command; this.sourceToken = sourceToken; }
    }
    private static final class HttpFailure extends Exception {
        final int status;
        HttpFailure(int status) { this.status = status; }
    }
    private static final class Request {
        String method, target;
        Map<String, String> headers = new HashMap<>();
        byte[] body;
    }

    public MsxServer(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences("msx_receiver", Context.MODE_PRIVATE);
        token = preferences.getString("capability", "");
        if (!token.matches("[A-Za-z0-9_-]{32}")) {
            token = secret(); preferences.edit().putString("capability", token).commit();
        }
        approvedHost = preferences.getString("peer", ""); approvedClient = preferences.getString("client", "");
        if (!privateAddress(approvedHost) || !clientId(approvedClient)) approvedHost = approvedClient = "";
        port = preferences.getInt("port", DEFAULT_PORT);
        if (port < 1024 || port > 65535) port = DEFAULT_PORT;
    }

    public boolean hasSavedReceiver() { synchronized (lock) { return !approvedClient.isEmpty(); } }
    public void setSourceGuard(NativeBackend.SourceGuard guard) { sourceGuard = guard; }

    /** Idempotent on a private active Wi-Fi/Ethernet interface; never binds 0.0.0.0. */
    public JSONObject start() throws MsxException {
        InetAddress address = activeAddress();
        synchronized (lock) {
            if (listener != null && !listener.isClosed() && host.equals(address.getHostAddress())) {
                // Explicit setup can reopen an expired window without changing the
                // stable address or disturbing an already approved receiver.
                if (approvedClient.isEmpty() && !pairingOpen()) {
                    clearSession(); pendingHost = pendingClient = null;
                    pairingUntil = SystemClock.elapsedRealtime() + PAIRING_MS;
                }
                return snapshot();
            }
            stopLocked(); host = address.getHostAddress();
            ServerSocket socket = null;
            List<Integer> candidates = new ArrayList<>(); candidates.add(port);
            if (port != DEFAULT_PORT) candidates.add(DEFAULT_PORT); candidates.add(0);
            for (int candidate : candidates) {
                try { socket = new ServerSocket(); socket.setReuseAddress(true); socket.bind(new InetSocketAddress(address, candidate), 8); break; }
                catch (IOException failure) { if (socket != null) try { socket.close(); } catch (IOException ignored) { } socket = null; }
            }
            if (socket == null) throw new MsxException("MSX_NETWORK");
            listener = socket; port = socket.getLocalPort(); preferences.edit().putInt("port", port).commit();
            if (approvedClient.isEmpty()) pairingUntil = SystemClock.elapsedRealtime() + PAIRING_MS;
            workers = new ThreadPoolExecutor(2, 4, 20, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), runnable -> {
                Thread thread = new Thread(runnable, "MSX request"); thread.setDaemon(true); return thread;
            });
            final ServerSocket owned = socket; final ThreadPoolExecutor ownedWorkers = workers;
            Thread accept = new Thread(() -> accept(owned, ownedWorkers), "MSX listener"); accept.setDaemon(true); accept.start();
            return snapshot();
        }
    }

    public JSONObject status() { synchronized (lock) { return snapshot(); } }

    public JSONObject confirmPairing(String requestedHost) throws MsxException {
        synchronized (lock) {
            if (!approvedClient.isEmpty() || !pairingOpen() || pendingClient == null || !pendingHost.equals(requestedHost) || sessionId == null) throw new MsxException("MSX_PAIRING");
            approvedHost = pendingHost; approvedClient = pendingClient; approved = true;
            preferences.edit().putString("peer", approvedHost).putString("client", approvedClient).commit();
            pairingUntil = 0; lastSeen = SystemClock.elapsedRealtime();
            return snapshot();
        }
    }

    /** Reset setup, not TV playback. A new capability prevents old pages controlling this phone. */
    public JSONObject reset() {
        synchronized (lock) {
            stopLocked(); token = secret(); approvedHost = approvedClient = "";
            preferences.edit().clear().putString("capability", token).putInt("port", port).commit();
            return snapshot();
        }
    }

    public JSONObject disconnect() { synchronized (lock) { stopLocked(); return snapshot(); } }
    @Override public void close() { synchronized (lock) { stopLocked(); } }

    /** A timeout is an unknown outcome: the caller must not automatically resend. */
    public JSONObject command(String method, JSONObject params) throws MsxException {
        JSONObject safe = validateCommand(method, params == null ? new JSONObject() : params);
        synchronized (lock) {
            boolean opening = "open".equals(method) || "load".equals(method);
            String sourceToken = params == null ? "" : params.optString("sourceToken", "");
            if (opening && (sourceToken.isEmpty() || sourceGuard == null || !sourceGuard.isCurrent(sourceToken))) throw new MsxException("SOURCE_CHANGED");
            long now = SystemClock.elapsedRealtime();
            if (listener == null || !approved || sessionId == null || now - lastSeen > FRESH_MS) throw new MsxException("MSX_NOT_CONNECTED");
            if (commands.size() >= 8) throw new MsxException("MSX_REJECTED");
            if (params != null && params.has("expectedSourceUrl")) {
                String expected = publicUrl(params.opt("expectedSourceUrl"));
                if (!"open".equals(method) && !"load".equals(method) && !expected.equals(playback.optString("source", ""))) throw new MsxException("SOURCE_CHANGED");
            }
            long id = ++sequence;
            Pending pending = new Pending(id, sessionId, object("id", id, "method", "load".equals(method) ? "open" : method, "params", safe), opening ? sourceToken : null);
            commands.put(id, pending); long deadline = now + (opening ? 45000 : 10000);
            while (!pending.done && sessionId != null && sessionId.equals(pending.session) && SystemClock.elapsedRealtime() < deadline) {
                try { lock.wait(Math.max(1, deadline - SystemClock.elapsedRealtime())); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            }
            if (!pending.done) {
                // Once delivered the outcome remains unknown until the TV acknowledges.
                if (!pending.delivered) commands.remove(id);
                throw new MsxException("MSX_TIMEOUT");
            }
            commands.remove(id);
            if (!pending.ok) throw new MsxException("SOURCE_CHANGED".equals(pending.error) ? "SOURCE_CHANGED" : "MSX_REJECTED");
            JSONObject result = snapshot(); put(result, "accepted", true); put(result, "id", id); return result;
        }
    }

    private JSONObject snapshot() {
        long now = SystemClock.elapsedRealtime(); boolean running = listener != null && !listener.isClosed();
        JSONObject result = object("running", running, "connected", running && approved && sessionId != null && lastState > 0 && now - lastState <= FRESH_MS,
                "configured", !approvedClient.isEmpty(), "name", "Philips TV", "host", approvedHost,
                "startupUrl", running ? origin() : "", "port", port,
                "awaitingApproval", pendingClient != null && !approved && pairingOpen(),
                "pendingHost", pendingClient != null && !approved && pairingOpen() ? pendingHost : "",
                "pairingOpen", running && pairingOpen(), "playback", copy(playback), "lastCommand", copy(lastCommand));
        for (Pending pending : commands.values()) if (!pending.done) { put(result, "pendingCommand", object("id", pending.id, "method", pending.command.optString("method"), "delivered", pending.delivered)); break; }
        return result;
    }
    private boolean pairingOpen() { return approvedClient.isEmpty() && SystemClock.elapsedRealtime() < pairingUntil; }
    private String origin() { return "http://" + host + ":" + port; }
    private String secret() { byte[] bytes = new byte[24]; random.nextBytes(bytes); return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING); }
    private void stopLocked() {
        if (listener != null) try { listener.close(); } catch (IOException ignored) { }
        listener = null; if (workers != null) workers.shutdownNow(); workers = null;
        for (Socket socket : clients) try { socket.close(); } catch (IOException ignored) { }
        clients.clear();
        clearSession(); pendingHost = pendingClient = null; pairingUntil = 0;
    }
    private void clearSession() {
        sessionId = null; approved = false; lastSeen = lastState = 0; playback = new JSONObject();
        commands.clear(); lastCommand = new JSONObject(); lock.notifyAll();
    }
    private InetAddress activeAddress() throws MsxException {
        try {
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = manager == null ? null : manager.getActiveNetwork();
            NetworkCapabilities capabilities = network == null ? null : manager.getNetworkCapabilities(network);
            if (capabilities == null || !(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) throw new MsxException("MSX_NETWORK");
            LinkProperties properties = manager.getLinkProperties(network);
            if (properties != null) for (LinkAddress link : properties.getLinkAddresses()) {
                InetAddress address = link.getAddress();
                if (address instanceof Inet4Address && privateAddress(address.getHostAddress())) return address;
            }
        } catch (MsxException failure) { throw failure; }
        catch (Exception ignored) { }
        throw new MsxException("MSX_NETWORK");
    }

    private void accept(ServerSocket owned, ThreadPoolExecutor pool) {
        while (!owned.isClosed()) {
            Socket socket = null;
            try {
                socket = owned.accept(); socket.setSoTimeout(3500);
                if (!privateAddress(socket.getInetAddress().getHostAddress())) { socket.close(); continue; }
                synchronized (lock) { if (listener != owned) { socket.close(); continue; } clients.add(socket); }
                final Socket accepted = socket; pool.execute(() -> serve(accepted, owned));
            } catch (RejectedExecutionException rejected) { if (socket != null) { synchronized (lock) { clients.remove(socket); } try { socket.close(); } catch (IOException ignored) { } } }
            catch (IOException ignored) { if (socket != null) try { socket.close(); } catch (IOException ignoredAgain) { } }
        }
    }

    private void serve(Socket socket, ServerSocket owner) {
        try {
            Socket accepted = socket;
            Request request = readRequest(accepted.getInputStream());
            String peer = accepted.getInetAddress().getHostAddress();
            synchronized (lock) { if (listener != owner) throw new HttpFailure(503); }
            URI target;
            try { target = new URI(request.target); } catch (Exception failure) { throw new HttpFailure(400); }
            if (target.isAbsolute() || target.getFragment() != null || target.getRawPath() == null || target.getRawPath().contains("%")) throw new HttpFailure(400);
            String path = target.getRawPath(); Map<String, String> query = query(target.getRawQuery());
            String requestedHost = request.headers.get("host");
            synchronized (lock) { if (!((host + ":" + port).equals(requestedHost))) throw new HttpFailure(403); }
            if ("/msx/start.json".equals(path) && "GET".equals(request.method)) {
                requireBootstrapOrigin(request.headers.get("origin"));
                JSONObject config;
                synchronized (lock) {
                    if (!approvedHost.isEmpty()) { if (!approvedHost.equals(peer)) throw new HttpFailure(403); }
                    else {
                        if (!pairingOpen() || (pendingHost != null && !pendingHost.equals(peer))) throw new HttpFailure(403);
                        pendingHost = peer;
                    }
                    config = object("name", "LUMA Remote", "version", "0.3.0", "parameter", "content:" + origin() + "/" + token + "/home.json", "welcome", "content");
                }
                respond(accepted, 200, "application/json", config.toString().getBytes(StandardCharsets.UTF_8), true); return;
            }
            String relative;
            synchronized (lock) {
                String prefix = "/" + token + "/";
                if (!path.startsWith(prefix)) throw new HttpFailure(404);
                if ((!approvedHost.isEmpty() && !approvedHost.equals(peer)) || (approvedHost.isEmpty() && (!pairingOpen() || pendingHost == null || !pendingHost.equals(peer)))) throw new HttpFailure(403);
                relative = path.substring(prefix.length());
            }
            if ("GET".equals(request.method) && "home.json".equals(relative)) {
                requireBootstrapOrigin(request.headers.get("origin"));
                JSONObject home;
                synchronized (lock) {
                    home = object("type", "pages", "headline", "LUMA Remote", "template", object("type", "separate", "layout", "0,0,12,4", "icon", "play-circle-outline", "color", "msx-glass"),
                            "items", new JSONArray().put(object("title", "Connect this TV", "playerLabel", "LUMA Remote", "description", "Open the receiver, then approve this TV on your phone.", "action", "video:plugin:" + origin() + "/" + token + "/receiver.html")));
                }
                respond(accepted, 200, "application/json", home.toString().getBytes(StandardCharsets.UTF_8), true); return;
            }
            if ("GET".equals(request.method) && ASSETS.contains(relative)) {
                byte[] bytes;
                try (InputStream input = context.getAssets().open("msx/" + relative)) { bytes = readAsset(input); }
                String type = relative.endsWith(".html") ? "text/html; charset=utf-8" : relative.endsWith(".css") ? "text/css; charset=utf-8" : "text/javascript; charset=utf-8";
                respond(accepted, 200, type, bytes, false); return;
            }
            String origin = request.headers.get("origin");
            synchronized (lock) { if (origin != null && !origin.equals(origin())) throw new HttpFailure(403); }
            JSONObject body = request.body.length == 0 ? new JSONObject() : parseObject(request.body);
            JSONObject result;
            synchronized (lock) {
                if ("hello".equals(relative) && "GET".equals(request.method)) result = hello(peer, query);
                else if ("poll".equals(relative) && "GET".equals(request.method)) result = poll(peer, query);
                else if ("state".equals(relative) && "POST".equals(request.method)) {
                    requireSession(peer, body.optString("client", ""), body.optString("sessionId", ""), true);
                    try { playback = validatePlayback(body.optJSONObject("playback")); } catch (MsxException invalid) { throw new HttpFailure(400); }
                    lastSeen = lastState = SystemClock.elapsedRealtime(); result = object("ok", true);
                } else if ("ack".equals(relative) && "POST".equals(request.method)) {
                    requireSession(peer, body.optString("client", ""), body.optString("sessionId", ""), true);
                    Object rawId = body.opt("id"), ok = body.opt("ok");
                    if (!(rawId instanceof Number) || !(ok instanceof Boolean)) throw new HttpFailure(400);
                    double numeric = ((Number) rawId).doubleValue(); if (!finite(numeric) || numeric < 1 || numeric != Math.floor(numeric) || numeric > sequence) throw new HttpFailure(400);
                    Pending pending = commands.get(((Number) rawId).longValue());
                    if (pending != null && pending.delivered && !pending.done && pending.session.equals(sessionId)) {
                        pending.done = true; pending.ok = (Boolean) ok;
                        pending.error = body.optString("error", "");
                        if (!pending.error.matches("[A-Z_]{0,64}")) pending.error = "RECEIVER_ERROR";
                        lastCommand = object("id", pending.id, "method", pending.command.optString("method"), "ok", pending.ok, "error", pending.error);
                        commands.remove(pending.id); lock.notifyAll();
                    }
                    lastSeen = SystemClock.elapsedRealtime(); result = object("ok", true);
                } else throw new HttpFailure(404);
            }
            respond(accepted, 200, "application/json", result.toString().getBytes(StandardCharsets.UTF_8), false);
        } catch (HttpFailure failure) { try { respond(socket, failure.status, "application/json", "{\"error\":\"REQUEST_REJECTED\"}".getBytes(StandardCharsets.UTF_8), false); } catch (IOException ignored) { } }
        catch (Exception ignored) { try { respond(socket, 400, "application/json", "{\"error\":\"REQUEST_REJECTED\"}".getBytes(StandardCharsets.UTF_8), false); } catch (IOException ignoredAgain) { } }
        finally { synchronized (lock) { clients.remove(socket); } try { socket.close(); } catch (IOException ignored) { } }
    }

    private JSONObject hello(String peer, Map<String, String> query) throws HttpFailure {
        String client = query.get("client"); if (!clientId(client)) throw new HttpFailure(400);
        if (!approvedClient.isEmpty()) { if (!approvedHost.equals(peer) || !approvedClient.equals(client)) throw new HttpFailure(403); }
        else {
            if (!pairingOpen() || !peer.equals(pendingHost) || (pendingClient != null && !pendingClient.equals(client))) throw new HttpFailure(403);
            pendingClient = client;
        }
        clearSession(); sessionId = secret(); approved = approvedClient.equals(client) && approvedHost.equals(peer); lastSeen = SystemClock.elapsedRealtime();
        return object("sessionId", sessionId, "approved", approved);
    }
    private JSONObject poll(String peer, Map<String, String> query) throws HttpFailure {
        requireSession(peer, query.get("client"), query.get("sessionId"), false);
        String raw = query.get("after"); long after;
        try { if (raw == null || !raw.matches("[0-9]{1,16}")) throw new NumberFormatException(); after = Long.parseLong(raw); } catch (NumberFormatException invalid) { throw new HttpFailure(400); }
        JSONArray outgoing = new JSONArray();
        if (approved) for (Iterator<Pending> iterator = commands.values().iterator(); iterator.hasNext();) {
            Pending pending = iterator.next();
            if (!pending.done && !pending.delivered && pending.id > after && pending.session.equals(sessionId)) {
                String expected = pending.command.optJSONObject("params").optString("expectedSourceUrl", "");
                if ((pending.sourceToken != null && (sourceGuard == null || !sourceGuard.isCurrent(pending.sourceToken))) || (!expected.isEmpty() && !expected.equals(playback.optString("source", "")))) {
                    pending.done = true; pending.error = "SOURCE_CHANGED"; pending.ok = false;
                    lastCommand = object("id", pending.id, "method", pending.command.optString("method"), "ok", false, "error", "SOURCE_CHANGED"); iterator.remove(); lock.notifyAll();
                } else { outgoing.put(pending.command); pending.delivered = true; }
            }
        }
        lastSeen = SystemClock.elapsedRealtime(); return object("approved", approved, "commands", outgoing);
    }
    private void requireSession(String peer, String client, String session, boolean approval) throws HttpFailure {
        if (sessionId == null || !sessionId.equals(session) || !(approved ? approvedClient : pendingClient).equals(client) || !(approved ? approvedHost : pendingHost).equals(peer) || (!approved && !pairingOpen()) || (approval && !approved)) throw new HttpFailure(403);
    }

    static JSONObject validateCommand(String method, JSONObject params) throws MsxException {
        JSONObject safe = new JSONObject();
        if ("open".equals(method) || "load".equals(method)) {
            put(safe, "source", publicUrl(params.opt("source"))); put(safe, "title", label(params.optString("title", "Video"), 256));
            String mime = params.optString("mimeType", ""); if (mime.length() > 100 || !mime.matches("[A-Za-z0-9.+/-]*")) throw new MsxException("INVALID_INPUT"); put(safe, "mimeType", mime);
            JSONArray tracks = params.optJSONArray("subtitles"); if (tracks == null) tracks = new JSONArray(); if (tracks.length() > 128) throw new MsxException("INVALID_INPUT");
            JSONArray subtitles = new JSONArray();
            for (int i = 0; i < tracks.length(); i++) {
                JSONObject track = tracks.optJSONObject(i); if (track == null) throw new MsxException("INVALID_INPUT");
                subtitles.put(object("url", publicUrl(track.opt("url")), "language", language(track.optString("language", "")), "label", label(track.optString("label", ""), 100)));
            }
            put(safe, "subtitles", subtitles); put(safe, "preferredLanguage", language(params.optString("preferredLanguage", "pt-BR"))); return safe;
        }
        if (params.has("expectedSourceUrl")) put(safe, "expectedSourceUrl", publicUrl(params.opt("expectedSourceUrl")));
        if ("play".equals(method) || "pause".equals(method) || "stop".equals(method)) return safe;
        if ("playPause".equals(method)) { if (!(params.opt("play") instanceof Boolean)) throw new MsxException("INVALID_INPUT"); put(safe, "play", params.opt("play")); return safe; }
        if ("seek".equals(method)) { put(safe, "seconds", number(params.opt("seconds"), 0, 17280000)); return safe; }
        if ("volume".equals(method)) { put(safe, "value", number(params.opt("value"), 0, 100)); return safe; }
        if ("subtitle".equals(method)) {
            double index = number(params.opt("index"), -1, 127); if (index != Math.floor(index) || !(params.opt("enabled") instanceof Boolean)) throw new MsxException("INVALID_INPUT");
            put(safe, "index", (int) index); put(safe, "enabled", params.opt("enabled")); return safe;
        }
        throw new MsxException("INVALID_INPUT");
    }
    static JSONObject validatePlayback(JSONObject state) throws MsxException {
        if (state == null || !(state.opt("active") instanceof Boolean)) throw new MsxException("INVALID_INPUT");
        boolean active = state.optBoolean("active"); String source = state.optString("source", "");
        if (active || !source.isEmpty()) source = publicUrl(source);
        JSONObject safe = object("active", active, "source", source, "title", label(state.optString("title", ""), 256),
                "paused", state.optBoolean("paused", true), "positionSeconds", number(state.opt("positionSeconds"), 0, 17280000),
                "durationSeconds", number(state.opt("durationSeconds"), 0, 17280000), "canSeek", state.optBoolean("canSeek", false));
        JSONObject volume = state.optJSONObject("volume");
        if (volume != null) put(safe, "volume", object("current", number(volume.opt("current"), 0, 100), "max", 100));
        JSONArray tracks = state.optJSONArray("tracks"); if (tracks == null) tracks = new JSONArray(); if (tracks.length() > 128) throw new MsxException("INVALID_INPUT");
        JSONArray cleanTracks = new JSONArray(); Set<Integer> indices = new HashSet<>();
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject track = tracks.optJSONObject(i); if (track == null) throw new MsxException("INVALID_INPUT");
            double index = number(track.opt("index"), 0, 127); if (index != Math.floor(index) || !indices.add((int) index)) throw new MsxException("INVALID_INPUT");
            cleanTracks.put(object("index", (int) index, "language", language(track.optString("language", "")), "label", label(track.optString("label", ""), 100)));
        }
        double selected = number(state.opt("selectedIndex"), -1, 127); if (selected != Math.floor(selected) || (selected >= 0 && !indices.contains((int) selected))) throw new MsxException("INVALID_INPUT");
        put(safe, "tracks", cleanTracks); put(safe, "selectedIndex", (int) selected); put(safe, "subtitlesEnabled", state.optBoolean("subtitlesEnabled", false));
        if (state.has("error")) { String error = state.optString("error", ""); if (!error.matches("[A-Z_]{0,64}")) throw new MsxException("INVALID_INPUT"); put(safe, "error", error); }
        return safe;
    }
    private static String publicUrl(Object value) throws MsxException {
        try { return NativeBackend.publicUrl(value); } catch (Exception invalid) { throw new MsxException("INVALID_MEDIA"); }
    }
    private static String language(String value) throws MsxException { if (!value.matches("[A-Za-z0-9_-]{0,35}")) throw new MsxException("INVALID_INPUT"); return value; }
    private static String label(String value, int max) { String result = value.replaceAll("[\\p{Cntrl}\\u007f-\\u009f\\u202a-\\u202e\\u2066-\\u2069]", " ").replaceAll("(?i)(?:[a-z][a-z0-9+.-]*://|www\\.)\\S+", "").trim(); return result.substring(0, Math.min(max, result.length())); }
    private static boolean finite(double value) { return !Double.isNaN(value) && !Double.isInfinite(value); }
    private static double number(Object value, double min, double max) throws MsxException { if (!(value instanceof Number)) throw new MsxException("INVALID_INPUT"); double number = ((Number) value).doubleValue(); if (!finite(number) || number < min || number > max) throw new MsxException("INVALID_INPUT"); return number; }
    static boolean privateAddress(String value) {
        if (value == null || !value.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) return false;
        String[] parts = value.split("\\."); int[] bytes = new int[4];
        for (int i = 0; i < 4; i++) { bytes[i] = Integer.parseInt(parts[i]); if (bytes[i] > 255) return false; }
        return bytes[0] == 10 || (bytes[0] == 172 && bytes[1] >= 16 && bytes[1] <= 31) || (bytes[0] == 192 && bytes[1] == 168);
    }
    private static boolean clientId(String value) { return value != null && value.matches("[A-Za-z0-9_-]{16,128}"); }
    private void requireBootstrapOrigin(String value) throws HttpFailure {
        if (value == null || "null".equals(value) || value.equals(origin()) || value.matches("https?://(?:www\\.)?msx\\.benzac\\.de")) return;
        throw new HttpFailure(403);
    }
    private static JSONObject object(Object... values) { JSONObject object = new JSONObject(); for (int i = 0; i < values.length; i += 2) put(object, (String) values[i], values[i + 1]); return object; }
    private static void put(JSONObject object, String key, Object value) { try { object.put(key, value); } catch (Exception ignored) { } }
    private static JSONObject copy(JSONObject object) { try { return new JSONObject(object.toString()); } catch (Exception ignored) { return new JSONObject(); } }

    private static Request readRequest(InputStream input) throws Exception {
        Request request = new Request(); long deadline = SystemClock.elapsedRealtime() + 4000;
        String first = line(input, deadline, 4096); String[] parts = first.split(" ", -1);
        if (parts.length != 3 || !("GET".equals(parts[0]) || "POST".equals(parts[0])) || !parts[1].startsWith("/") || !("HTTP/1.1".equals(parts[2]) || "HTTP/1.0".equals(parts[2]))) throw new HttpFailure(400);
        request.method = parts[0]; request.target = parts[1]; int size = first.length() + 2;
        while (true) {
            String header = line(input, deadline, MAX_HEADER); size += header.length() + 2; if (size > MAX_HEADER) throw new HttpFailure(431); if (header.isEmpty()) break;
            int separator = header.indexOf(':'); if (separator < 1) throw new HttpFailure(400);
            String name = header.substring(0, separator).toLowerCase(Locale.ROOT), value = header.substring(separator + 1).trim();
            if (!name.matches("[a-z0-9-]+") || request.headers.put(name, value) != null || value.matches("(?s).*[\\x00-\\x1f\\x7f].*")) throw new HttpFailure(400);
        }
        if (request.headers.containsKey("transfer-encoding")) throw new HttpFailure(400);
        String rawLength = request.headers.get("content-length"); int length = 0;
        if (rawLength != null) { if (!rawLength.matches("[0-9]{1,6}")) throw new HttpFailure(400); length = Integer.parseInt(rawLength); }
        if (length > MAX_BODY) throw new HttpFailure(413); if ("GET".equals(request.method) && length > 0) throw new HttpFailure(400);
        String contentType = request.headers.get("content-type");
        if ("POST".equals(request.method) && !"application/json".equals((contentType == null ? "" : contentType).split(";", 2)[0].trim().toLowerCase(Locale.ROOT))) throw new HttpFailure(415);
        request.body = new byte[length]; int offset = 0;
        while (offset < length) { if (SystemClock.elapsedRealtime() > deadline) throw new HttpFailure(408); int read = input.read(request.body, offset, length - offset); if (read < 0) throw new HttpFailure(400); offset += read; }
        return request;
    }
    private static String line(InputStream input, long deadline, int max) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (bytes.size() <= max) {
            if (SystemClock.elapsedRealtime() > deadline) throw new HttpFailure(408);
            int value = input.read(); if (value < 0) throw new HttpFailure(400);
            if (value == '\n') { byte[] data = bytes.toByteArray(); if (data.length == 0 || data[data.length - 1] != '\r') throw new HttpFailure(400); return new String(data, 0, data.length - 1, StandardCharsets.US_ASCII); }
            if (value > 127 || value == 0) throw new HttpFailure(400); bytes.write(value);
        }
        throw new HttpFailure(431);
    }
    private static Map<String, String> query(String raw) throws HttpFailure {
        Map<String, String> result = new HashMap<>(); if (raw == null || raw.isEmpty()) return result;
        if (raw.length() > 2048) throw new HttpFailure(400);
        try {
            for (String pair : raw.split("&", -1)) { String[] parts = pair.split("=", 2); String key = URLDecoder.decode(parts[0], "UTF-8"), value = parts.length == 2 ? URLDecoder.decode(parts[1], "UTF-8") : ""; if (result.put(key, value) != null) throw new HttpFailure(400); }
        } catch (IllegalArgumentException | java.io.UnsupportedEncodingException invalid) { throw new HttpFailure(400); }
        return result;
    }
    private static JSONObject parseObject(byte[] bytes) throws HttpFailure {
        try { JSONTokener parser = new JSONTokener(new String(bytes, StandardCharsets.UTF_8)); Object value = parser.nextValue(); if (!(value instanceof JSONObject) || parser.nextClean() != 0) throw new HttpFailure(400); return (JSONObject) value; }
        catch (Exception invalid) { throw new HttpFailure(400); }
    }
    private static byte[] readAsset(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] bytes = new byte[8192]; int size;
        while ((size = input.read(bytes)) >= 0) { if (out.size() + size > 2 * 1024 * 1024) throw new IOException(); out.write(bytes, 0, size); } return out.toByteArray();
    }
    private static void respond(Socket socket, int status, String type, byte[] body, boolean bootstrapCors) throws IOException {
        String headers = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Error") + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n" + (bootstrapCors ? "Access-Control-Allow-Origin: *\r\n" : "") + "\r\n";
        OutputStream output = socket.getOutputStream(); output.write(headers.getBytes(StandardCharsets.US_ASCII)); output.write(body); output.flush();
    }
}

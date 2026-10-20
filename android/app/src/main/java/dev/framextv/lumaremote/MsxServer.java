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
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
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
import java.util.function.BooleanSupplier;

/** Phone-hosted control/receiver assets only. The TV fetches all media itself.
 * Never expose this class to the browsing WebView or publish its capability URL. */
public final class MsxServer implements AutoCloseable {
    /** Only native catalog metadata and freshly resolved media enter this boundary. */
    public interface Library {
        JSONObject metadata(String action, JSONObject params) throws LibraryException;
        JSONObject resolve(JSONObject selection, BooleanSupplier current) throws LibraryException;
        void onReady(JSONObject media, boolean resumeApplied);
    }
    public static final class LibraryException extends Exception {
        public final String code;
        public LibraryException(String code) { super("TV library request failed"); this.code = TVCatalogBridge.errorCode(code); }
    }
    private static final Set<String> LOGGED_PROTOCOL_STAGES = new HashSet<>();
    private static final Set<String> CLIENT_ERROR_CODES = new HashSet<>(Arrays.asList(
            "STATE_READ_FAILED", "STATUS_RENDER_FAILED", "NETWORK_UNAVAILABLE", "HTTP_REJECTED", "INVALID_RESPONSE", "CHANNEL_FAILED"));
    private static final Set<String> INPUT_EVENT_CODES = new HashSet<>(Arrays.asList(
            "none", "key-enter", "key-arrow", "key-back", "key-media", "pointer-background", "pointer-arrow"));
    private static final Set<String> POINTER_LOCK_ERRORS = new HashSet<>(Arrays.asList("none", "not-allowed", "unsupported", "event-error", "other"));
    private static final Set<String> ACK_METHODS = new HashSet<>(Arrays.asList(
            "open", "play", "pause", "stop", "playPause", "seek", "volume", "subtitle", "ping", "set-label", "reload"));
    private static final Set<String> ACK_ERROR_CODES = new HashSet<>(Arrays.asList(
            "PLAYBACK_REJECTED", "PLAYBACK_TIMEOUT", "MEDIA_FAILED", "HLS_UNSUPPORTED", "SUBTITLE_FAILED",
            "SUBTITLE_TOO_LARGE", "INVALID_SUBTITLE", "INVALID_SUBTITLE_INDEX", "INVALID_SUBTITLES", "INVALID_SOURCE",
            "SUPERSEDED", "SOURCE_CHANGED", "INVALID_VOLUME", "VOLUME_UNSUPPORTED", "SEEK_UNAVAILABLE",
            "NO_ACTIVE_VIDEO", "PLAYBACK_STARTING", "UNKNOWN_COMMAND", "INVALID_COMMAND", "COMMAND_FAILED", "CONTROL_UNAVAILABLE", "RELOAD_BLOCKED"));
    public static final class MsxException extends Exception {
        public final String code;
        MsxException(String code) { super(message(code)); this.code = code; }
        private static String message(String code) {
            switch (code) {
                case "MSX_NETWORK": return "Connect this phone to the same Wi-Fi as the TV.";
                case "MSX_NOT_CONNECTED": return "Open the LUMA receiver page on the TV and approve it here.";
                case "MSX_PAIRING": return "The TV pairing request expired or changed. Start setup again.";
                case "MSX_TIMEOUT": return "The TV did not confirm this command. Check its screen before trying again.";
                case "MSX_REJECTED": return "The TV could not complete this control command.";
                case "CONTROL_UNAVAILABLE": return "Refresh the TV receiver to enable these controls.";
                case "RELOAD_BLOCKED": return "Receiver reload is unavailable during playback or a pending command.";
                case "SUBTITLE_FAILED": return "Subtitles could not load. The current video has not been reopened.";
                case "SOURCE_CHANGED": return "The video changed. Cast again before controlling this video.";
                case "INVALID_MEDIA": return "Choose a supported public HTTP video or subtitle source.";
                default: return "The requested TV control is not valid.";
            }
        }
    }
    private static final int DEFAULT_PORT = 4178, MAX_BODY = 128 * 1024, MAX_HEADER = 8192;
    private static final long PAIRING_MS = 5 * 60 * 1000, FRESH_MS = 12000;
    private static final String SAMPLE_SOURCE = "https://media.w3.org/2010/05/sintel/trailer.mp4";
    private static final Set<String> ASSETS = new HashSet<>(Arrays.asList(
            "receiver.html", "receiver.js", "receiver-core.js", "receiver.css", "hls.min.js", "tvx-plugin.min.js"));
    private final Context context;
    private final SharedPreferences preferences;
    private final String assetRevision;
    private final byte[] diagnosticSample;
    private final TvSubtitleBridge subtitleBridge;
    private final Set<String> loggedSampleRequests = new HashSet<>();
    private final Object lock = new Object();
    private final SecureRandom random = new SecureRandom();
    private final Map<Long, Pending> commands = new LinkedHashMap<>();
    private final Set<Socket> clients = new HashSet<>();
    private ServerSocket listener;
    private ThreadPoolExecutor workers;
    private String host = "", token, approvedHost, approvedClient, pendingHost, pendingClient, sessionId;
    private int port;
    private long pairingUntil, lastSeen, lastState, sequence;
    private long connections, tlsRequests, bootstrapRequests, bootstrapAccepted, lastRequestAt;
    // Accepted counts are object-lifetime totals; lastState remains current-session only.
    private long helloAccepted, pollAccepted, stateAccepted, lastHelloAt, lastPollAt;
    private int lastStatus;
    private String lastReason = "NONE", lastOrigin = "", lastClientError = "NONE";
    private JSONObject playback = new JSONObject();
    private JSONObject lastCommand = new JSONObject();
    private boolean approved;
    private String sampleToken;
    private long sampleUntil;
    private SubtitleContext subtitleContext;
    private long subtitleSelection;
    private volatile NativeBackend.SourceGuard sourceGuard;
    private volatile Library library;
    private final TVCatalogBridge catalogBridge = new TVCatalogBridge();
    private LibraryGrant libraryGrant;
    private static final class LibraryGrant {
        final String token, peer, session, command;
        final long until;
        final BooleanSupplier current;
        LibraryGrant(String token, String peer, String session, JSONObject command, BooleanSupplier current) {
            this.token = token; this.peer = peer; this.session = session; this.command = command.toString(); this.current = current;
            until = SystemClock.elapsedRealtime() + 50000;
        }
    }
    private static final class SubtitleContext {
        final String source, peer, session; final JSONArray tracks; final long commandId;
        boolean accepted;
        SubtitleContext(String source, String peer, String session, JSONArray tracks, long commandId) {
            this.source = source; this.peer = peer; this.session = session; this.tracks = tracks; this.commandId = commandId;
        }
    }
    private static final class PreparedSelection {
        final SubtitleContext context; final long request; final int index; final String url;
        PreparedSelection(SubtitleContext context, long request, int index, String url) { this.context = context; this.request = request; this.index = index; this.url = url; }
    }

    private static final class Pending {
        final long id;
        final String session;
        final JSONObject command;
        final String sourceToken;
        final BooleanSupplier current;
        boolean delivered, done, ok;
        String error;
        Pending(long id, String session, JSONObject command, String sourceToken, BooleanSupplier current) { this.id = id; this.session = session; this.command = command; this.sourceToken = sourceToken; this.current = current; }
    }
    private static final class HttpFailure extends Exception {
        final int status;
        final String reason;
        HttpFailure(int status) { this(status, "REQUEST_REJECTED"); }
        HttpFailure(int status, String reason) { this.status = status; this.reason = reason; }
    }
    private static final class Request {
        String method, target;
        Map<String, String> headers = new HashMap<>();
        byte[] body;
    }

    public MsxServer(Context context) { this(context, new TvSubtitleBridge(NativeBackend::publicUrl)); }
    MsxServer(Context context, TvSubtitleBridge subtitleBridge) {
        if (subtitleBridge == null) throw new IllegalArgumentException("Subtitle bridge required");
        this.context = context.getApplicationContext();
        this.subtitleBridge = subtitleBridge;
        preferences = this.context.getSharedPreferences("msx_receiver", Context.MODE_PRIVATE);
        assetRevision = readAssetRevision();
        diagnosticSample = readDiagnosticSample();
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
    public void setLibrary(Library library) { synchronized (lock) { this.library = library; libraryGrant = null; catalogBridge.cancel(); } }

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
    @Override public void close() { synchronized (lock) { stopLocked(); } subtitleBridge.close(); catalogBridge.close(); }

    /** A fixed public sample has its own short-lived authorization, never a capture token. */
    public JSONObject sample() throws MsxException {
        final String authorization = secret();
        synchronized (lock) { sampleToken = authorization; sampleUntil = SystemClock.elapsedRealtime() + 50000; }
        try {
            String source;
            synchronized (lock) { String owned = ownedSampleUrl(); source = owned == null ? SAMPLE_SOURCE : owned; }
            return command("open", object("source", source, "title", "Sintel trailer — playback test", "mimeType", "video/mp4",
                    "subtitles", new JSONArray(), "autoSubtitles", false, "sourceToken", authorization));
        } finally { synchronized (lock) { if (authorization.equals(sampleToken)) { sampleToken = null; sampleUntil = 0; } } }
    }

    private boolean sourceCurrent(String authorization, JSONObject params) {
        if (authorization == null || authorization.isEmpty()) return false;
        LibraryGrant grant = libraryGrant;
        if (grant != null && authorization.equals(grant.token)) return grant.until > SystemClock.elapsedRealtime()
                && libraryCurrent(grant.peer, grant.session) && grant.current.getAsBoolean() && grant.command.equals(params.toString());
        String source = params.optString("source"), owned = ownedSampleUrl();
        boolean ownedSource = owned != null && owned.equals(source);
        if (authorization.equals(sampleToken) && SystemClock.elapsedRealtime() < sampleUntil && (SAMPLE_SOURCE.equals(source) || ownedSource)) return true;
        return !ownedSource && sourceGuard != null && sourceGuard.isCurrent(authorization);
    }

    /** A timeout is an unknown outcome: the caller must not automatically resend. */
    public JSONObject command(String method, JSONObject params) throws MsxException {
        return command(method, params, null);
    }
    private JSONObject command(String method, JSONObject params, BooleanSupplier current) throws MsxException {
        final JSONObject safe;
        synchronized (lock) {
            safe = validateCommand(method, params == null ? new JSONObject() : params, ownedSampleUrl());
            if ("subtitle".equals(method) && !safe.optBoolean("enabled")) subtitleSelection++;
        }
        PreparedSelection selected = null;
        if ("subtitle".equals(method) && safe.optBoolean("enabled")) {
            selected = prepareSubtitleSelection(safe.optInt("index", -1), safe.has("expectedSourceUrl") ? safe.optString("expectedSourceUrl") : null, null, null);
            put(safe, "url", selected.url);
        }
        synchronized (lock) {
            if (current != null && !current.getAsBoolean()) throw new MsxException("SOURCE_CHANGED");
            if (selected != null && !subtitleRequestCurrent(selected.context, selected.request)) throw new MsxException("SOURCE_CHANGED");
            boolean opening = "open".equals(method) || "load".equals(method);
            String sourceToken = params == null ? "" : params.optString("sourceToken", "");
            if (opening && !sourceCurrent(sourceToken, safe)) throw new MsxException("SOURCE_CHANGED");
            long now = SystemClock.elapsedRealtime();
            if (listener == null || !approved || sessionId == null || now - lastSeen > FRESH_MS) throw new MsxException("MSX_NOT_CONNECTED");
            if (current == null && (opening || "stop".equals(method))) { libraryGrant = null; catalogBridge.cancel(); }
            if ("reload".equals(method) && (playback.optBoolean("active") || !commands.isEmpty())) throw new MsxException("RELOAD_BLOCKED");
            if ("ping".equals(method) || "set-label".equals(method) || "reload".equals(method)) {
                JSONObject debug = playback.optJSONObject("receiverDebug");
                if (debug == null || !debug.optBoolean("remoteCommands") || ("reload".equals(method) && !debug.optBoolean("remoteReload"))) throw new MsxException("CONTROL_UNAVAILABLE");
            }
            if (commands.size() >= 8) throw new MsxException("MSX_REJECTED");
            if (params != null && params.has("expectedSourceUrl")) {
                String expected = mediaUrl(params.opt("expectedSourceUrl"), ownedSampleUrl());
                if (!"open".equals(method) && !"load".equals(method) && !expected.equals(playback.optString("source", ""))) throw new MsxException("SOURCE_CHANGED");
            }
            long id = ++sequence;
            if (opening || "stop".equals(method)) {
                clearSubtitleContext();
                if (opening) subtitleContext = new SubtitleContext(safe.optString("source"), approvedHost, sessionId, safe.optJSONArray("subtitles"), id);
            }
            Pending pending = new Pending(id, sessionId, object("id", id, "method", "load".equals(method) ? "open" : method, "params", safe), opening ? sourceToken : null, current);
            commands.put(id, pending); long deadline = now + (opening ? 45000 : 10000);
            while (!pending.done && sessionId != null && sessionId.equals(pending.session) && SystemClock.elapsedRealtime() < deadline && (current == null || current.getAsBoolean())) {
                try { lock.wait(Math.max(1, Math.min(current == null ? 45000 : 250, deadline - SystemClock.elapsedRealtime()))); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            }
            if (!pending.done) {
                // Once delivered the outcome remains unknown until the TV acknowledges.
                if (!pending.delivered) commands.remove(id);
                if (current != null && !current.getAsBoolean()) throw new MsxException("SOURCE_CHANGED");
                throw new MsxException("MSX_TIMEOUT");
            }
            commands.remove(id);
            if (current != null && !current.getAsBoolean()) throw new MsxException("SOURCE_CHANGED");
            if (!pending.ok) throw new MsxException(Arrays.asList("SOURCE_CHANGED", "CONTROL_UNAVAILABLE", "RELOAD_BLOCKED", "SUBTITLE_FAILED").contains(pending.error) ? pending.error : "MSX_REJECTED");
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
                "pairingOpen", running && pairingOpen(), "playback", copy(playback), "lastCommand", copy(lastCommand),
                "diagnostics", object("connections", connections, "tlsRequests", tlsRequests, "bootstrapRequests", bootstrapRequests,
                        "bootstrapAccepted", bootstrapAccepted, "lastStatus", lastStatus, "lastReason", lastReason,
                        "lastOrigin", lastOrigin, "lastRequestMsAgo", age(now, lastRequestAt),
                        "helloAccepted", helloAccepted, "pollAccepted", pollAccepted, "stateAccepted", stateAccepted,
                        "lastHelloMsAgo", age(now, lastHelloAt), "lastPollMsAgo", age(now, lastPollAt), "lastStateMsAgo", age(now, lastState),
                        "sessionPresent", sessionId != null, "sessionApproved", sessionId != null && approved,
                        "lastClientError", lastClientError, "assetRevision", assetRevision == null ? JSONObject.NULL : assetRevision,
                        "sampleTransport", diagnosticSample == null ? "public" : "lan-fixture"));
        for (Pending pending : commands.values()) if (!pending.done) { put(result, "pendingCommand", object("id", pending.id, "method", pending.command.optString("method"), "delivered", pending.delivered)); break; }
        return result;
    }
    private static long age(long now, long timestamp) { return timestamp == 0 ? -1 : Math.max(0, now - timestamp); }
    private boolean pairingOpen() { return approvedClient.isEmpty() && SystemClock.elapsedRealtime() < pairingUntil; }
    private String origin() { return "http://" + host + ":" + port; }
    private String ownedSampleUrl() { return diagnosticSample != null && listener != null && !listener.isClosed() ? origin() + "/" + token + "/diagnostic-sintel.mp4" : null; }
    private String secret() { byte[] bytes = new byte[24]; random.nextBytes(bytes); return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING); }
    private void stopLocked() {
        if (listener != null) try { listener.close(); } catch (IOException ignored) { }
        listener = null; if (workers != null) workers.shutdownNow(); workers = null;
        for (Socket socket : clients) try { socket.close(); } catch (IOException ignored) { }
        clients.clear();
        clearSession(); pendingHost = pendingClient = null; pairingUntil = 0;
    }
    private void clearSession() {
        libraryGrant = null; catalogBridge.cancel();
        sessionId = null; approved = false; lastSeen = lastState = 0; playback = new JSONObject();
        sampleToken = null; sampleUntil = 0;
        clearSubtitleContext();
        commands.clear(); lastCommand = new JSONObject(); lock.notifyAll();
    }
    private void clearSubtitleContext() { subtitleSelection++; subtitleContext = null; subtitleBridge.revokeAll(); }
    private boolean subtitleRequestCurrent(SubtitleContext context, long request) { synchronized (lock) { return request == subtitleSelection && subtitleCurrent(context); } }
    private PreparedSelection prepareSubtitleSelection(int index, String expected, String peer, String session) throws MsxException {
        final SubtitleContext captured; final long request; final String original;
        synchronized (lock) {
            captured = subtitleContext;
            if (!subtitleCurrent(captured) || expected != null && !captured.source.equals(expected)
                    || peer != null && !captured.peer.equals(peer) || session != null && !captured.session.equals(session)) throw new MsxException("SOURCE_CHANGED");
            if (index < 0 || index >= captured.tracks.length()) throw new MsxException("INVALID_INPUT");
            original = captured.tracks.optJSONObject(index).optString("url"); request = ++subtitleSelection;
        }
        try {
            TvSubtitleBridge.Prepared prepared = subtitleBridge.prepare(captured.source, original, captured.peer, () -> subtitleRequestCurrent(captured, request));
            synchronized (lock) {
                if (!subtitleRequestCurrent(captured, request)) throw new MsxException("SOURCE_CHANGED");
                return new PreparedSelection(captured, request, index, origin() + "/" + token + "/subtitles/" + prepared.id + ".srt");
            }
        } catch (TvSubtitleBridge.BridgeException failure) { throw new MsxException("SOURCE_CHANGED".equals(failure.code) ? "SOURCE_CHANGED" : "SUBTITLE_FAILED"); }
    }
    private boolean subtitleCurrent(SubtitleContext context) {
        synchronized (lock) {
            if (context == null || !context.accepted || context != subtitleContext || listener == null || !approved || !context.session.equals(sessionId)
                    || !context.peer.equals(approvedHost) || !playback.optBoolean("active") || !context.source.equals(playback.optString("source"))
                    || lastState == 0 || SystemClock.elapsedRealtime() - lastState > FRESH_MS) return false;
            return true;
        }
    }
    private boolean libraryCurrent(String peer, String session) {
        synchronized (lock) {
            return listener != null && !listener.isClosed() && approved && peer.equals(approvedHost) && session.equals(sessionId)
                    && lastSeen > 0 && SystemClock.elapsedRealtime() - lastSeen <= FRESH_MS;
        }
    }
    private TVCatalogBridge.Driver libraryDriver(Library captured, String peer, String session) {
        return new TVCatalogBridge.Driver() {
            @Override public JSONObject resolve(JSONObject selection, BooleanSupplier current) throws LibraryException {
                if (!current.getAsBoolean() || library != captured) throw new LibraryException("SOURCE_CHANGED");
                return captured.resolve(selection, current);
            }
            @Override public void open(JSONObject media, BooleanSupplier current) throws MsxException, LibraryException {
                JSONObject params = object("source", media.opt("url"), "title", media.optString("title", "Video"),
                        "mimeType", media.optString("contentType", ""), "subtitles", media.optJSONArray("tracks"), "autoSubtitles", false);
                final LibraryGrant grant;
                synchronized (lock) {
                    if (!current.getAsBoolean() || library != captured) throw new MsxException("SOURCE_CHANGED");
                    JSONObject content = media.optJSONObject("content");
                    if (content == null || WatchHistory.contentFromPage(content.optString("watchUrl"), content.optString("title")) == null) throw new LibraryException("CATALOG_RESPONSE");
                    JSONObject safe = validateCommand("open", params);
                    grant = new LibraryGrant(secret(), peer, session, safe, current); libraryGrant = grant; put(params, "sourceToken", grant.token);
                }
                try { command("open", params, current); }
                finally { synchronized (lock) { if (libraryGrant == grant) libraryGrant = null; } }
            }
            @Override public void seek(JSONObject media, double seconds, BooleanSupplier current) throws Exception {
                String source = media.optString("url"); final long baseline;
                synchronized (lock) {
                    if (!current.getAsBoolean() || library != captured) throw new MsxException("SOURCE_CHANGED");
                    baseline = stateAccepted;
                }
                command("seek", object("seconds", seconds, "expectedSourceUrl", source), current);
                long until = SystemClock.elapsedRealtime() + 10000;
                synchronized (lock) {
                    while (current.getAsBoolean() && library == captured && SystemClock.elapsedRealtime() < until) {
                        double position = playback.optDouble("positionSeconds", -1);
                        if (stateAccepted > baseline && lastState > 0 && SystemClock.elapsedRealtime() - lastState <= FRESH_MS
                                && playback.optBoolean("active") && source.equals(playback.optString("source"))
                                && position >= Math.max(0, seconds - 5) && position <= seconds + 15) return;
                        lock.wait(100);
                    }
                }
                throw new LibraryException("RESUME_FAILED");
            }
            @Override public void ready(JSONObject media, boolean resumed, BooleanSupplier current) throws MsxException {
                synchronized (lock) {
                    if (!current.getAsBoolean() || library != captured || !media.optString("url").equals(playback.optString("source"))
                            || !playback.optBoolean("active") || lastState == 0 || SystemClock.elapsedRealtime() - lastState > FRESH_MS) throw new MsxException("SOURCE_CHANGED");
                    if (playback.opt("error") instanceof String && !playback.optString("error").isEmpty()) throw new MsxException("MSX_REJECTED");
                    captured.onReady(copy(media), resumed);
                }
            }
        };
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
                synchronized (lock) { connections++; }
                if (!privateAddress(socket.getInetAddress().getHostAddress())) { socket.close(); continue; }
                synchronized (lock) { if (listener != owned) { socket.close(); continue; } clients.add(socket); }
                final Socket accepted = socket; pool.execute(() -> serve(accepted, owned));
            } catch (RejectedExecutionException rejected) { if (socket != null) { synchronized (lock) { clients.remove(socket); } try { socket.close(); } catch (IOException ignored) { } } }
            catch (IOException ignored) { if (socket != null) try { socket.close(); } catch (IOException ignoredAgain) { } }
        }
    }

    private void serve(Socket socket, ServerSocket owner) {
        Request request = null;
        try {
            Socket accepted = socket;
            PushbackInputStream input = new PushbackInputStream(accepted.getInputStream(), 1);
            int first = input.read();
            if (first == 22) {
                synchronized (lock) { tlsRequests++; }
                throw new HttpFailure(400, "HTTPS_REQUEST");
            }
            if (first < 0) throw new HttpFailure(400);
            input.unread(first); request = readRequest(input);
            String peer = accepted.getInetAddress().getHostAddress();
            synchronized (lock) { if (listener != owner) throw new HttpFailure(503); }
            URI target;
            try { target = new URI(request.target); } catch (Exception failure) { throw new HttpFailure(400); }
            if (target.isAbsolute() || target.getFragment() != null || target.getRawPath() == null || target.getRawPath().contains("%")) throw new HttpFailure(400);
            String path = target.getRawPath(); Map<String, String> query = query(target.getRawQuery());
            String requestedHost = request.headers.get("host");
            synchronized (lock) { if (!((host + ":" + port).equals(requestedHost))) throw new HttpFailure(403, "HOST_REJECTED"); }
            if ("/msx/connect.html".equals(path) && "GET".equals(request.method)) {
                if (target.getRawQuery() != null) throw new HttpFailure(400);
                String receiver;
                synchronized (lock) { admitBootstrap(peer, request); receiver = "/" + token + "/receiver.html"; bootstrapAccepted++; }
                record(302, "CONNECT_READY", request.headers.get("origin"));
                respond(accepted, 302, "text/html; charset=utf-8", new byte[0], false, "Location: " + receiver + "\r\n"); return;
            }
            if ("/msx/start.json".equals(path) && ("GET".equals(request.method) || "OPTIONS".equals(request.method))) {
                JSONObject config;
                synchronized (lock) {
                    admitBootstrap(peer, request);
                    config = object("name", "LUMA Remote", "version", "0.3.1", "parameter", "content:" + origin() + "/" + token + "/home.json", "welcome", "content");
                }
                if ("OPTIONS".equals(request.method)) { preflight(accepted, request); return; }
                synchronized (lock) { bootstrapAccepted++; }
                record(200, "BOOTSTRAP_READY", request.headers.get("origin"));
                respond(accepted, 200, "application/json", config.toString().getBytes(StandardCharsets.UTF_8), true); return;
            }
            String relative;
            synchronized (lock) {
                String prefix = "/" + token + "/";
                if (!path.startsWith(prefix)) throw new HttpFailure(404);
                relative = path.substring(prefix.length());
                if (approvedHost.isEmpty() && !pairingOpen()) throw new HttpFailure(403, "PAIRING_EXPIRED");
                // A retained receiver already holds the private capability. An
                // explicitly reopened setup window may admit its hello again;
                // media/control still waits for this phone's separate approval.
                if (approvedHost.isEmpty() && pendingHost == null && "GET".equals(request.method) && "hello".equals(relative)
                        && query.size() == 1 && clientId(query.get("client"))) {
                    String suppliedOrigin = request.headers.get("origin");
                    if (suppliedOrigin != null && !suppliedOrigin.equals(origin())) throw new HttpFailure(403, "ORIGIN_REJECTED");
                    pendingHost = peer;
                }
                if ((!approvedHost.isEmpty() && !approvedHost.equals(peer)) || (approvedHost.isEmpty() && (pendingHost == null || !pendingHost.equals(peer)))) throw new HttpFailure(403, "PEER_REJECTED");
            }
            if ("diagnostic-sintel.mp4".equals(relative)) {
                synchronized (lock) {
                    if (approvedHost.isEmpty() || !approvedHost.equals(peer)) throw new HttpFailure(403, "PEER_REJECTED");
                    String suppliedOrigin = request.headers.get("origin");
                    if (suppliedOrigin != null && !suppliedOrigin.equals(origin())) throw new HttpFailure(403, "ORIGIN_REJECTED");
                }
                if (!("GET".equals(request.method) || "HEAD".equals(request.method)) || diagnosticSample == null) throw new HttpFailure(404);
                if (target.getRawQuery() != null) throw new HttpFailure(400);
                serveDiagnosticSample(accepted, request); return;
            }
            if (relative.matches("subtitles/[a-f0-9]{64}\\.srt")) {
                if (!("GET".equals(request.method) || "HEAD".equals(request.method))) throw new HttpFailure(404);
                synchronized (lock) {
                    if (approvedHost.isEmpty() || !approvedHost.equals(peer)) throw new HttpFailure(403, "PEER_REJECTED");
                    String suppliedOrigin = request.headers.get("origin");
                    if (suppliedOrigin != null && !suppliedOrigin.equals(origin())) throw new HttpFailure(403, "ORIGIN_REJECTED");
                }
                if (target.getRawQuery() != null) throw new HttpFailure(400);
                byte[] bytes;
                try { bytes = subtitleBridge.read(relative.substring("subtitles/".length(), relative.length() - 4), peer, "HEAD".equals(request.method)); }
                catch (TvSubtitleBridge.BridgeException unavailable) { throw new HttpFailure(403); }
                respondTextSubtitle(accepted, bytes, "HEAD".equals(request.method));
                Log.i("MsxReceiver", "SUBTITLE_REQUEST transport=lan method=" + request.method + " status=200");
                return;
            }
            if ("HEAD".equals(request.method)) throw new HttpFailure(404);
            if (("GET".equals(request.method) || "OPTIONS".equals(request.method)) && "home.json".equals(relative)) {
                requireBootstrapOrigin(request.headers.get("origin"));
                if ("OPTIONS".equals(request.method)) { preflight(accepted, request); return; }
                JSONObject home;
                synchronized (lock) {
                    home = object("type", "pages", "headline", "LUMA Remote", "template", object("type", "separate", "layout", "0,0,12,4", "icon", "play-circle-outline", "color", "msx-glass"),
                            "items", new JSONArray().put(object("title", "Connect this TV", "playerLabel", "LUMA Remote", "description", "Open the receiver, then approve this TV on your phone.", "action", "video:plugin:" + origin() + "/" + token + "/receiver.html")));
                }
                record(200, "HOME_READY", request.headers.get("origin"));
                respond(accepted, 200, "application/json", home.toString().getBytes(StandardCharsets.UTF_8), true); return;
            }
            if ("GET".equals(request.method) && ASSETS.contains(relative)) {
                byte[] bytes;
                try (InputStream asset = context.getAssets().open("msx/" + relative)) { bytes = readAsset(asset); }
                String type = relative.endsWith(".html") ? "text/html; charset=utf-8" : relative.endsWith(".css") ? "text/css; charset=utf-8" : "text/javascript; charset=utf-8";
                record(200, "ASSET_READY", request.headers.get("origin"));
                respond(accepted, 200, type, bytes, false); return;
            }
            String origin = request.headers.get("origin");
            synchronized (lock) { if (origin != null && !origin.equals(origin())) throw new HttpFailure(403, "ORIGIN_REJECTED"); }
            JSONObject body = request.body.length == 0 ? new JSONObject() : parseObject(request.body);
            if ("library".equals(relative) && "POST".equals(request.method)) {
                if (target.getRawQuery() != null || body.length() != 4 || !(body.opt("client") instanceof String)
                        || !(body.opt("sessionId") instanceof String) || !(body.opt("action") instanceof String)
                        || !(body.opt("params") instanceof JSONObject)) throw new HttpFailure(400);
                String requestedSession = body.optString("sessionId"), action = body.optString("action");
                final Library captured;
                synchronized (lock) {
                    requireSession(peer, body.optString("client"), requestedSession, true);
                    if (!libraryCurrent(peer, requestedSession)) throw new HttpFailure(409);
                    captured = library;
                }
                try {
                    if (captured == null) throw new LibraryException("LIBRARY_UNAVAILABLE");
                    JSONObject params = TVCatalogBridge.parameters(action, body.optJSONObject("params")), response;
                    if ("playStatus".equals(action)) response = catalogBridge.status(params.optString("jobId"));
                    else if ("play".equals(action)) {
                        synchronized (lock) {
                            if (!libraryCurrent(peer, requestedSession) || captured != library) throw new HttpFailure(409);
                            libraryGrant = null;
                            response = catalogBridge.play(params, () -> library == captured && libraryCurrent(peer, requestedSession), libraryDriver(captured, peer, requestedSession));
                        }
                    } else response = captured.metadata(action, params);
                    synchronized (lock) { if (!libraryCurrent(peer, requestedSession) || captured != library) throw new HttpFailure(409); }
                    if (response == null) throw new LibraryException("CATALOG_RESPONSE");
                    byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
                    if (bytes.length > 2 * 1024 * 1024) throw new LibraryException("CATALOG_RESPONSE");
                    record(200, "LIBRARY_READY", origin);
                    respond(accepted, 200, "application/json", bytes, false);
                } catch (LibraryException failure) {
                    int status = "CATALOG_INVALID_INPUT".equals(failure.code) ? 400 : "LIBRARY_UNAVAILABLE".equals(failure.code) ? 503 : 502;
                    record(status, "LIBRARY_REJECTED", origin);
                    respond(accepted, status, "application/json", object("errorCode", failure.code).toString().getBytes(StandardCharsets.UTF_8), false);
                }
                return;
            }
            if ("subtitle-prepare".equals(relative) && "POST".equals(request.method)) {
                if (target.getRawQuery() != null || body.length() != 4 || !(body.opt("client") instanceof String)
                        || !(body.opt("sessionId") instanceof String) || !(body.opt("expectedSourceUrl") instanceof String)) throw new HttpFailure(400);
                double index;
                try { index = number(body.opt("index"), 0, 127); } catch (MsxException invalid) { throw new HttpFailure(400); }
                if (index != Math.floor(index)) throw new HttpFailure(400);
                String requestedSession = body.optString("sessionId");
                synchronized (lock) { requireSession(peer, body.optString("client"), requestedSession, true); }
                PreparedSelection prepared;
                try { prepared = prepareSubtitleSelection((int) index, body.optString("expectedSourceUrl"), peer, requestedSession); }
                catch (MsxException invalid) { throw new HttpFailure("SOURCE_CHANGED".equals(invalid.code) ? 409 : "INVALID_INPUT".equals(invalid.code) ? 400 : 502); }
                JSONObject response = object("url", prepared.url, "index", prepared.index, "source", prepared.context.source);
                record(200, "SUBTITLE_PREPARED", origin);
                respond(accepted, 200, "application/json", response.toString().getBytes(StandardCharsets.UTF_8), false); return;
            }
            JSONObject result; String readyReason;
            synchronized (lock) {
                if ("diagnostic".equals(relative) && "GET".equals(request.method)) {
                    String code = query.get("code");
                    if (query.size() != 1 || !CLIENT_ERROR_CODES.contains(code)) throw new HttpFailure(400);
                    if (!code.equals(lastClientError)) Log.i("MsxReceiver", "CLIENT_DIAGNOSTIC " + code);
                    lastClientError = code; result = object("ok", true); readyReason = "CLIENT_DIAGNOSTIC";
                } else if ("hello".equals(relative) && "GET".equals(request.method)) { result = hello(peer, query); readyReason = "HELLO_READY"; }
                else if ("poll".equals(relative) && "GET".equals(request.method)) { result = poll(peer, query); readyReason = "POLL_READY"; }
                else if ("state".equals(relative) && "POST".equals(request.method)) {
                    requireSession(peer, body.optString("client", ""), body.optString("sessionId", ""), true);
                    try { playback = validatePlayback(body.optJSONObject("playback"), ownedSampleUrl()); } catch (MsxException invalid) { throw new HttpFailure(400); }
                    if (subtitleContext != null && subtitleContext.accepted && !subtitleContext.source.equals(playback.optString("source"))) clearSubtitleContext();
                    lastSeen = lastState = SystemClock.elapsedRealtime(); stateAccepted++; logFirstSuccess("STATE_READY", stateAccepted);
                    lock.notifyAll();
                    result = object("ok", true); readyReason = "STATE_READY";
                } else if ("ack".equals(relative) && "POST".equals(request.method)) {
                    requireSession(peer, body.optString("client", ""), body.optString("sessionId", ""), true);
                    Object rawId = body.opt("id"), ok = body.opt("ok");
                    if (!(rawId instanceof Number) || !(ok instanceof Boolean)) throw new HttpFailure(400);
                    double numeric = ((Number) rawId).doubleValue(); if (!finite(numeric) || numeric < 1 || numeric != Math.floor(numeric) || numeric > sequence) throw new HttpFailure(400);
                    Pending pending = commands.get(((Number) rawId).longValue());
                    if (pending != null && pending.delivered && !pending.done && pending.session.equals(sessionId)) {
                        pending.done = true; pending.ok = (Boolean) ok;
                        if (pending.ok && "open".equals(pending.command.optString("method")) && subtitleContext != null && subtitleContext.commandId == pending.id) subtitleContext.accepted = true;
                        pending.error = body.optString("error", "");
                        if (!pending.error.matches("[A-Z_]{0,64}")) pending.error = "RECEIVER_ERROR";
                        if (!pending.ok) logFailedAck(pending.command.optString("method"), pending.error);
                        lastCommand = object("id", pending.id, "method", pending.command.optString("method"), "ok", pending.ok, "error", pending.error);
                        commands.remove(pending.id); lock.notifyAll();
                    }
                    lastSeen = SystemClock.elapsedRealtime(); result = object("ok", true); readyReason = "ACK_READY";
                } else throw new HttpFailure(404);
            }
            record(200, readyReason, request.headers.get("origin"));
            respond(accepted, 200, "application/json", result.toString().getBytes(StandardCharsets.UTF_8), false);
        } catch (HttpFailure failure) {
            String origin = request == null ? null : request.headers.get("origin"); record(failure.status, failure.reason, origin);
            try { respond(socket, failure.status, "application/json", request != null && "HEAD".equals(request.method) ? new byte[0] : "{\"error\":\"REQUEST_REJECTED\"}".getBytes(StandardCharsets.UTF_8), origin != null && allowedBootstrapOrigin(origin)); } catch (IOException ignored) { }
        } catch (Exception ignored) {
            String origin = request == null ? null : request.headers.get("origin"); record(400, "REQUEST_REJECTED", origin);
            try { respond(socket, 400, "application/json", "{\"error\":\"REQUEST_REJECTED\"}".getBytes(StandardCharsets.UTF_8), origin != null && allowedBootstrapOrigin(origin)); } catch (IOException ignoredAgain) { }
        }
        finally { synchronized (lock) { clients.remove(socket); } try { socket.close(); } catch (IOException ignored) { } }
    }

    private JSONObject hello(String peer, Map<String, String> query) throws HttpFailure {
        String client = query.get("client"); if (!clientId(client)) throw new HttpFailure(400);
        if (!approvedClient.isEmpty()) { if (!approvedHost.equals(peer) || !approvedClient.equals(client)) throw new HttpFailure(403); }
        else {
            if (!pairingOpen() || !peer.equals(pendingHost) || (pendingClient != null && !pendingClient.equals(client))) throw new HttpFailure(403);
            pendingClient = client;
        }
        clearSession(); sessionId = secret(); approved = approvedClient.equals(client) && approvedHost.equals(peer);
        lastSeen = lastHelloAt = SystemClock.elapsedRealtime(); helloAccepted++; logFirstSuccess("HELLO_READY", helloAccepted);
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
                if ((pending.current != null && !pending.current.getAsBoolean()) || (pending.sourceToken != null && !sourceCurrent(pending.sourceToken, pending.command.optJSONObject("params"))) || (!expected.isEmpty() && !expected.equals(playback.optString("source", "")))) {
                    pending.done = true; pending.error = "SOURCE_CHANGED"; pending.ok = false;
                    lastCommand = object("id", pending.id, "method", pending.command.optString("method"), "ok", false, "error", "SOURCE_CHANGED"); iterator.remove(); lock.notifyAll();
                } else { outgoing.put(pending.command); pending.delivered = true; }
            }
        }
        lastSeen = lastPollAt = SystemClock.elapsedRealtime(); pollAccepted++; logFirstSuccess("POLL_READY", pollAccepted);
        return object("approved", approved, "commands", outgoing);
    }
    private void requireSession(String peer, String client, String session, boolean approval) throws HttpFailure {
        if (sessionId == null || !sessionId.equals(session) || !(approved ? approvedClient : pendingClient).equals(client) || !(approved ? approvedHost : pendingHost).equals(peer) || (!approved && !pairingOpen()) || (approval && !approved)) throw new HttpFailure(403);
    }

    static JSONObject validateCommand(String method, JSONObject params) throws MsxException {
        return validateCommand(method, params, null);
    }
    private static JSONObject validateCommand(String method, JSONObject params, String ownedSource) throws MsxException {
        JSONObject safe = new JSONObject();
        if ("ping".equals(method) || "reload".equals(method)) {
            if (params.length() != 0) throw new MsxException("INVALID_INPUT");
            return safe;
        }
        if ("set-label".equals(method)) {
            if (params.length() != 1 || !(params.opt("label") instanceof String) || !"LUMA debug channel ready".equals(params.opt("label"))) throw new MsxException("INVALID_INPUT");
            put(safe, "label", "LUMA debug channel ready"); return safe;
        }
        if ("open".equals(method) || "load".equals(method)) {
            put(safe, "source", mediaUrl(params.opt("source"), ownedSource)); put(safe, "title", label(params.optString("title", "Video"), 256));
            String mime = params.optString("mimeType", ""); if (mime.length() > 100 || !mime.matches("[A-Za-z0-9.+/-]*")) throw new MsxException("INVALID_INPUT"); put(safe, "mimeType", mime);
            if (params.has("autoSubtitles")) {
                if (!(params.opt("autoSubtitles") instanceof Boolean)) throw new MsxException("INVALID_INPUT");
                put(safe, "autoSubtitles", params.opt("autoSubtitles"));
            }
            JSONArray tracks = params.optJSONArray("subtitles"); if (tracks == null) tracks = new JSONArray(); if (tracks.length() > 128) throw new MsxException("INVALID_INPUT");
            JSONArray subtitles = new JSONArray();
            for (int i = 0; i < tracks.length(); i++) {
                JSONObject track = tracks.optJSONObject(i); if (track == null) throw new MsxException("INVALID_INPUT");
                subtitles.put(object("url", publicUrl(track.opt("url")), "language", language(track.optString("language", "")), "label", label(track.optString("label", ""), 100)));
            }
            put(safe, "subtitles", subtitles); put(safe, "preferredLanguage", language(params.optString("preferredLanguage", "pt-BR"))); return safe;
        }
        if (params.has("expectedSourceUrl")) put(safe, "expectedSourceUrl", mediaUrl(params.opt("expectedSourceUrl"), ownedSource));
        if ("play".equals(method) || "pause".equals(method) || "stop".equals(method)) return safe;
        if ("playPause".equals(method)) { if (!(params.opt("play") instanceof Boolean)) throw new MsxException("INVALID_INPUT"); put(safe, "play", params.opt("play")); return safe; }
        if ("seek".equals(method)) { put(safe, "seconds", number(params.opt("seconds"), 0, 17280000)); return safe; }
        if ("volume".equals(method)) { put(safe, "value", number(params.opt("value"), 0, 100)); return safe; }
        if ("subtitle".equals(method)) {
            if (params.has("url")) throw new MsxException("INVALID_INPUT");
            double index = number(params.opt("index"), -1, 127); if (index != Math.floor(index) || !(params.opt("enabled") instanceof Boolean)) throw new MsxException("INVALID_INPUT");
            put(safe, "index", (int) index); put(safe, "enabled", params.opt("enabled")); return safe;
        }
        throw new MsxException("INVALID_INPUT");
    }
    static JSONObject validatePlayback(JSONObject state) throws MsxException {
        return validatePlayback(state, null);
    }
    private static JSONObject validatePlayback(JSONObject state, String ownedSource) throws MsxException {
        if (state == null || !(state.opt("active") instanceof Boolean)) throw new MsxException("INVALID_INPUT");
        boolean active = state.optBoolean("active"); String source = state.optString("source", "");
        if (active || !source.isEmpty()) source = mediaUrl(source, ownedSource);
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
        if (state.has("error")) {
            Object error = state.opt("error");
            // createPlayer.state() uses JSON null for healthy playback. Android
            // optString would coerce that sentinel to the invalid text "null".
            if (error == JSONObject.NULL) put(safe, "error", JSONObject.NULL);
            else {
                if (!(error instanceof String) || !((String) error).matches("[A-Z_]{0,64}")) throw new MsxException("INVALID_INPUT");
                put(safe, "error", error);
            }
        }
        if (state.has("subtitleError")) {
            Object error = state.opt("subtitleError");
            if (error != JSONObject.NULL && !"SUBTITLE_FAILED".equals(error)) throw new MsxException("INVALID_INPUT");
            put(safe, "subtitleError", error);
        }
        if (state.has("mediaDiagnostics")) {
            JSONObject reported = state.optJSONObject("mediaDiagnostics");
            if (reported == null) throw new MsxException("INVALID_INPUT");
            JSONObject clean = new JSONObject();
            String[] names = {"errorCode", "networkState", "readyState"};
            int[] maxima = {4, 3, 4};
            for (int i = 0; i < names.length; i++) {
                if (!reported.has(names[i])) throw new MsxException("INVALID_INPUT");
                Object raw = reported.opt(names[i]);
                if (raw == JSONObject.NULL) put(clean, names[i], JSONObject.NULL);
                else {
                    double value = number(raw, 0, maxima[i]);
                    if (value != Math.floor(value)) throw new MsxException("INVALID_INPUT");
                    put(clean, names[i], (int) value);
                }
            }
            put(safe, "mediaDiagnostics", clean);
        }
        if (state.has("receiverDebug")) {
            JSONObject reported = state.optJSONObject("receiverDebug");
            if (reported == null || !reported.has("revision") || !(reported.opt("remoteReload") instanceof Boolean)
                    || !(reported.opt("remoteCommands") instanceof Boolean)) throw new MsxException("INVALID_INPUT");
            Object revision = reported.opt("revision");
            if (revision != JSONObject.NULL && (!(revision instanceof String) || !((String) revision).matches("[0-9a-f]{64}"))) throw new MsxException("INVALID_INPUT");
            double uptime = number(reported.opt("uptimeSeconds"), 0, 315360000), pings = number(reported.opt("pingCount"), 0, 1000000000);
            if (pings != Math.floor(pings)) throw new MsxException("INVALID_INPUT");
            JSONObject clean = object("revision", revision, "uptimeSeconds", uptime, "pingCount", (long) pings,
                    "remoteReload", reported.opt("remoteReload"), "remoteCommands", reported.opt("remoteCommands"));
            if (reported.has("inputCount")) {
                double count = number(reported.opt("inputCount"), 0, 1000000000);
                if (count != Math.floor(count)) throw new MsxException("INVALID_INPUT");
                put(clean, "inputCount", (long) count);
            }
            if (reported.has("lastInput")) {
                Object event = reported.opt("lastInput");
                if (!(event instanceof String) || !INPUT_EVENT_CODES.contains(event)) throw new MsxException("INVALID_INPUT");
                put(clean, "lastInput", event);
            }
            for (String property : new String[] {"pointerLockSupported", "pointerLocked"}) {
                if (reported.has(property)) {
                    if (!(reported.opt(property) instanceof Boolean)) throw new MsxException("INVALID_INPUT");
                    put(clean, property, reported.opt(property));
                }
            }
            if (reported.has("pointerMoveCount")) {
                double count = number(reported.opt("pointerMoveCount"), 0, 1000000000);
                if (count != Math.floor(count)) throw new MsxException("INVALID_INPUT");
                put(clean, "pointerMoveCount", (long) count);
            }
            if (reported.has("pointerLockError")) {
                Object error = reported.opt("pointerLockError");
                if (!(error instanceof String) || !POINTER_LOCK_ERRORS.contains(error)) throw new MsxException("INVALID_INPUT");
                put(clean, "pointerLockError", error);
            }
            put(safe, "receiverDebug", clean);
        }
        return safe;
    }
    private String readAssetRevision() {
        try (InputStream input = context.getAssets().open("msx/revision.json")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); int next;
            while ((next = input.read()) >= 0) { if (bytes.size() >= 256) return null; bytes.write(next); }
            Object revision = parseObject(bytes.toByteArray()).opt("revision");
            return revision instanceof String && ((String) revision).matches("[0-9a-f]{64}") ? (String) revision : null;
        } catch (Exception ignored) { return null; }
    }
    private byte[] readDiagnosticSample() {
        try (InputStream input = context.getAssets().open("msx/diagnostic-sintel.mp4")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) >= 0) {
                if (bytes.size() + count > 5 * 1024 * 1024) return null;
                bytes.write(buffer, 0, count);
            }
            return bytes.size() == 0 ? null : bytes.toByteArray();
        } catch (IOException ignored) { return null; }
    }
    private void serveDiagnosticSample(Socket socket, Request request) throws IOException {
        int size = diagnosticSample.length, start = 0, end = size - 1, status = 200;
        String range = request.headers.get("range");
        if (range != null) {
            try {
                java.util.regex.Matcher match = java.util.regex.Pattern.compile("(?i)bytes=([0-9]{0,16})-([0-9]{0,16})").matcher(range);
                if (!match.matches() || match.group(1).isEmpty() && match.group(2).isEmpty()) throw new IllegalArgumentException();
                if (match.group(1).isEmpty()) {
                    long suffix = Long.parseLong(match.group(2));
                    if (suffix < 1) throw new IllegalArgumentException();
                    start = (int) Math.max(0L, size - suffix);
                } else {
                    long first = Long.parseLong(match.group(1));
                    long last = match.group(2).isEmpty() ? size - 1 : Long.parseLong(match.group(2));
                    if (first >= size || last < first) throw new IllegalArgumentException();
                    start = (int) first; end = (int) Math.min(last, size - 1);
                }
                status = 206;
            } catch (IllegalArgumentException invalid) {
                respond(socket, 416, "video/mp4", new byte[0], false, "Accept-Ranges: bytes\r\nContent-Range: bytes */" + size + "\r\n"); return;
            }
        }
        int length = end - start + 1;
        String headers = "HTTP/1.1 " + status + (status == 206 ? " Partial Content" : " OK")
                + "\r\nContent-Type: video/mp4\r\nContent-Length: " + length + "\r\nAccept-Ranges: bytes\r\n"
                + (status == 206 ? "Content-Range: bytes " + start + "-" + end + "/" + size + "\r\n" : "")
                + "Connection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n\r\n";
        synchronized (lock) {
            // Request admission only, not proof of a completed media transfer.
            if (loggedSampleRequests.add(request.method + ":" + status)) Log.i("MsxReceiver", "SAMPLE_REQUEST transport=lan method=" + request.method + " status=" + status);
        }
        try {
            OutputStream output = socket.getOutputStream(); output.write(headers.getBytes(StandardCharsets.US_ASCII));
            if ("GET".equals(request.method)) output.write(diagnosticSample, start, length);
            output.flush();
        } catch (IOException disconnected) {
            // Media clients can abort a large response after inspecting it.
            // An admitted response must not become a second HTTP 400 response.
        }
    }
    private static String publicUrl(Object value) throws MsxException {
        try { return NativeBackend.publicUrl(value); } catch (Exception invalid) { throw new MsxException("INVALID_MEDIA"); }
    }
    private static String mediaUrl(Object value, String ownedSource) throws MsxException {
        return ownedSource != null && value instanceof String && ownedSource.equals(value) ? ownedSource : publicUrl(value);
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
    /** Caller holds lock; both public entry points claim only the requesting private peer. */
    private void admitBootstrap(String peer, Request request) throws HttpFailure {
        bootstrapRequests++;
        requireBootstrapOrigin(request.headers.get("origin"));
        if (!approvedHost.isEmpty()) { if (!approvedHost.equals(peer)) throw new HttpFailure(403, "PEER_REJECTED"); }
        else {
            if (!pairingOpen()) throw new HttpFailure(403, "PAIRING_EXPIRED");
            if (pendingHost != null && !pendingHost.equals(peer)) throw new HttpFailure(403, "PEER_REJECTED");
            if ("GET".equals(request.method)) pendingHost = peer;
        }
    }
    private void requireBootstrapOrigin(String value) throws HttpFailure {
        if (!allowedBootstrapOrigin(value)) throw new HttpFailure(403, "ORIGIN_REJECTED");
    }
    private boolean allowedBootstrapOrigin(String value) {
        return value == null || "null".equals(value) || value.equals(origin()) || value.matches("https?://(?:www\\.)?msx\\.benzac\\.de");
    }
    private void preflight(Socket socket, Request request) throws Exception {
        String origin = request.headers.get("origin");
        if (origin == null || !"GET".equals(request.headers.get("access-control-request-method"))) throw new HttpFailure(400, "PREFLIGHT_REJECTED");
        requireBootstrapOrigin(origin);
        String requested = request.headers.get("access-control-request-headers");
        if (requested != null) for (String header : requested.split(",", -1)) {
            String name = header.trim().toLowerCase(Locale.ROOT);
            if (!("accept".equals(name) || "content-type".equals(name) || "x-requested-with".equals(name))) throw new HttpFailure(400, "PREFLIGHT_REJECTED");
        }
        String privateNetwork = request.headers.get("access-control-request-private-network");
        if (privateNetwork != null && !"true".equalsIgnoreCase(privateNetwork)) throw new HttpFailure(400, "PREFLIGHT_REJECTED");
        String headers = "Access-Control-Allow-Methods: GET, OPTIONS\r\nAccess-Control-Allow-Headers: Accept, Content-Type, X-Requested-With\r\n" +
                (privateNetwork == null ? "" : "Access-Control-Allow-Private-Network: true\r\n");
        record(200, "PREFLIGHT_READY", origin);
        respond(socket, 200, "application/json", "{}".getBytes(StandardCharsets.UTF_8), true, headers);
    }
    private static void logFirstSuccess(String stage, long count) {
        if (count != 1) return;
        synchronized (LOGGED_PROTOCOL_STAGES) {
            if (LOGGED_PROTOCOL_STAGES.add(stage)) Log.i("MsxReceiver", stage + " status=200");
        }
    }
    private void logFailedAck(String method, String code) {
        // Called only when a delivered pending command is completed for the
        // first time. Neither caller-provided error text nor command data logs.
        if (!ACK_METHODS.contains(method)) return;
        String safeCode = ACK_ERROR_CODES.contains(code) ? code : "RECEIVER_ERROR";
        JSONObject diagnostics = playback.optJSONObject("mediaDiagnostics");
        Log.i("MsxReceiver", "ACK_FAILED method=" + method + " code=" + safeCode
                + " errorCode=" + diagnosticNumber(diagnostics, "errorCode")
                + " networkState=" + diagnosticNumber(diagnostics, "networkState")
                + " readyState=" + diagnosticNumber(diagnostics, "readyState"));
    }
    private static String diagnosticNumber(JSONObject diagnostics, String name) {
        // Only the projected, validated playback object is passed here.
        Object value = diagnostics == null ? null : diagnostics.opt(name);
        return value instanceof Number ? Integer.toString(((Number) value).intValue()) : "unknown";
    }
    private void record(int status, String reason, String origin) {
        String sanitized = "";
        if ("null".equals(origin)) sanitized = "null";
        else if (origin != null) {
            try {
                URI parsed = new URI(origin);
                if (("http".equals(parsed.getScheme()) || "https".equals(parsed.getScheme())) && parsed.getHost() != null) sanitized = parsed.getScheme() + "://" + parsed.getHost().toLowerCase(Locale.ROOT);
                else sanitized = "invalid";
            } catch (Exception ignored) { sanitized = "invalid"; }
        }
        synchronized (lock) { lastStatus = status; lastReason = reason; lastOrigin = sanitized; lastRequestAt = SystemClock.elapsedRealtime(); }
        if (status >= 400 || "BOOTSTRAP_READY".equals(reason) || "PREFLIGHT_READY".equals(reason)) {
            // Log only fixed event codes and a known origin class; never request
            // targets, private addresses, capabilities, source URLs or bodies.
            String originClass = "";
            if (origin != null && origin.matches("https?://(?:www\\.)?msx\\.benzac\\.de")) originClass = sanitized;
            else if (origin != null) originClass = "null".equals(origin) ? "null" : "other";
            Log.i("MsxReceiver", reason + " status=" + status + " origin=" + originClass);
        }
    }
    private static JSONObject object(Object... values) { JSONObject object = new JSONObject(); for (int i = 0; i < values.length; i += 2) put(object, (String) values[i], values[i + 1]); return object; }
    private static void put(JSONObject object, String key, Object value) { try { object.put(key, value); } catch (Exception ignored) { } }
    private static JSONObject copy(JSONObject object) { try { return new JSONObject(object.toString()); } catch (Exception ignored) { return new JSONObject(); } }

    private static Request readRequest(InputStream input) throws Exception {
        Request request = new Request(); long deadline = SystemClock.elapsedRealtime() + 4000;
        String first = line(input, deadline, 4096); String[] parts = first.split(" ", -1);
        if (parts.length != 3 || !("GET".equals(parts[0]) || "HEAD".equals(parts[0]) || "POST".equals(parts[0]) || "OPTIONS".equals(parts[0])) || !parts[1].startsWith("/") || !("HTTP/1.1".equals(parts[2]) || "HTTP/1.0".equals(parts[2]))) throw new HttpFailure(400);
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
        if (length > MAX_BODY) throw new HttpFailure(413); if (!"POST".equals(request.method) && length > 0) throw new HttpFailure(400);
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
        respond(socket, status, type, body, bootstrapCors, "");
    }
    private static void respondTextSubtitle(Socket socket, byte[] bytes, boolean head) throws IOException {
        String headers = "HTTP/1.1 200 OK\r\nContent-Type: application/x-subrip; charset=utf-8\r\nContent-Length: " + bytes.length
                + "\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n\r\n";
        OutputStream output = socket.getOutputStream(); output.write(headers.getBytes(StandardCharsets.US_ASCII)); if (!head) output.write(bytes); output.flush();
    }
    private static void respond(Socket socket, int status, String type, byte[] body, boolean bootstrapCors, String extraHeaders) throws IOException {
        String headers = "HTTP/1.1 " + status + (status == 200 ? " OK" : status == 302 ? " Found" : " Error") + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n" + (bootstrapCors ? "Access-Control-Allow-Origin: *\r\n" : "") + extraHeaders + "\r\n";
        OutputStream output = socket.getOutputStream(); output.write(headers.getBytes(StandardCharsets.US_ASCII)); output.write(body); output.flush();
    }
}

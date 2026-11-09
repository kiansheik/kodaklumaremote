package dev.framextv.lumaremote;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Missing-only ROM replication, isolated from playback and navigation queues. */
public final class GamesManager implements AutoCloseable {
    public interface EndpointSource { NativeBackend.Endpoint current(); }
    public interface Listener { void changed(JSONObject state); }
    private static final int CHUNK_BYTES = 131072;
    private final Context context;
    private final GamesLibrary library;
    private final EndpointSource endpoints;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(4), r -> new Thread(r, "LumaGames"));
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean syncQueued = new AtomicBoolean();
    private volatile boolean connected, closed;
    private volatile long epoch, lastAttempt;
    private String connectionIdentity = "";
    private long remoteEpoch = -1;
    private volatile HttpURLConnection active;
    private JSONObject state = new JSONObject();
    private final Set<String> remoteIds = new HashSet<>();

    public GamesManager(Context context, EndpointSource endpoints, Listener listener) {
        this.context = context.getApplicationContext(); this.endpoints = endpoints; this.listener = listener;
        library = new GamesLibrary(new File(context.getFilesDir(), "games"));
    }
    public void connectionChanged(boolean ready) {
        connectionChanged(ready, "default");
    }
    public void connectionChanged(boolean ready, String identity) {
        if (closed) return;
        if (connected != ready || !connectionIdentity.equals(identity)) {
            connected = ready; connectionIdentity = identity; epoch++;
            if (ready) lastAttempt = 0;
            HttpURLConnection old = active; if (old != null) old.disconnect();
            // Clear confirmations immediately; the worker alone owns remoteIds.
            synchronized (this) {
                try {
                    JSONObject next = snapshot(); JSONArray games = next.optJSONArray("games");
                    if (games != null) for (int i = 0; i < games.length(); i++) games.getJSONObject(i).put("onProjector", false);
                    next.put("connected", ready).put("status", ready ? "waiting" : "offline").put("syncing", syncQueued.get())
                        .put("message", ready ? "Checking the projector games library…" : "Saved on this phone. Connect to LUMA to sync.");
                    next.remove("errorCode"); state = next;
                } catch (Exception ignored) { }
            }
            main.post(() -> { if (!closed) listener.changed(snapshot()); });
        }
        if (ready && (lastAttempt == 0 || SystemClock.elapsedRealtime() - lastAttempt > 60_000)) scheduleSync(null);
    }
    private synchronized JSONObject snapshot() {
        try { return new JSONObject(state.toString()); } catch (Exception ignored) { return new JSONObject(); }
    }
    private void update(String status, String message, String error) throws Exception {
        JSONArray games = library.list();
        for (int i = 0; i < games.length(); i++) {
            JSONObject game = games.getJSONObject(i);
            game.put("onProjector", connected && remoteEpoch == epoch && remoteIds.contains(game.getString("id")));
        }
        JSONObject next = new JSONObject().put("games", games).put("status", status).put("message", message)
                .put("connected", connected).put("syncing", syncQueued.get());
        if (error != null) next.put("errorCode", error);
        synchronized (this) { state = next; }
        main.post(() -> { if (!closed) listener.changed(snapshot()); });
    }
    public void request(String method, JSONObject params, ConnectionService.Callback callback) {
        if ("sync".equals(method)) { scheduleSync(callback); return; }
        if ("list".equals(method) && syncQueued.get()) {
            if (callback != null) main.post(() -> { if (!closed) callback.success(snapshot()); });
            return;
        }
        submit(() -> {
            if ("list".equals(method)) {
                JSONObject previous = snapshot();
                update(connected ? previous.optString("status", "ready") : "offline",
                        connected ? previous.optString("message", "Games sync when the projector connects.") : "Saved on this phone. Connect to LUMA to sync.", previous.optString("errorCode", null));
                return snapshot();
            }
            NativeBackend.Endpoint endpoint = requireEndpoint(); long session = epoch;
            if ("play".equals(method)) {
                String hash = GamesLibrary.id(params.optString("id"));
                JSONObject game = library.metadata(hash);
                // The receiver may have been reset or changed since the last sync.
                Set<String> present = remoteGames(call(endpoint, session, "games.list", new JSONObject())).keySet();
                if (!present.contains(hash)) upload(endpoint, session, game);
                call(endpoint, session, "games.play", new JSONObject().put("id", hash));
                remoteIds.clear(); remoteIds.addAll(present); remoteIds.add(hash); remoteEpoch = session;
                update("ready", "Game sent to the projector. Check the projector screen.", null); return snapshot();
            }
            if ("show".equals(method) || "stop".equals(method) || "controllers".equals(method))
                return call(endpoint, session, "games." + method, new JSONObject());
            throw GamesLibrary.error("INVALID_GAME", "That games action is unavailable.");
        }, callback);
    }
    public void importRom(Uri uri, ConnectionService.Callback callback) {
        submit(() -> {
            if (uri == null || !("content".equals(uri.getScheme()) || "file".equals(uri.getScheme())))
                throw GamesLibrary.error("INVALID_ROM", "Choose an NES file from this phone.");
            String name = uri.getLastPathSegment();
            if ("content".equals(uri.getScheme())) {
                try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (column >= 0) name = cursor.getString(column);
                        column = cursor.getColumnIndex(OpenableColumns.SIZE);
                        if (column >= 0 && !cursor.isNull(column) && cursor.getLong(column) > GamesLibrary.MAX_ROM_BYTES)
                            throw GamesLibrary.error("GAME_TOO_LARGE", "Choose an NES ROM smaller than 8 MiB.");
                    }
                }
            }
            try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("No ROM stream");
                library.add(input, name, null);
            }
            update(connected ? "ready" : "offline", connected ? "Game saved. Syncing with the projector…" : "Game saved on this phone. It will sync when LUMA connects.", null);
            if (connected) scheduleSync(null);
            return snapshot();
        }, callback);
    }
    private interface Task { JSONObject run() throws Exception; }
    private void submit(Task task, ConnectionService.Callback callback) {
        if (closed) { fail(callback, "NOT_CONNECTED", "The games library is closing. Reopen the app."); return; }
        try { worker.execute(() -> {
            try {
                JSONObject result = task.run();
                if (callback != null) main.post(() -> { if (!closed) callback.success(result); });
            } catch (Exception error) { failure(error, callback); }
        }); } catch (RejectedExecutionException busy) { fail(callback, "BUSY", "The games library is busy. Try again in a moment."); }
    }
    private void failure(Exception error, ConnectionService.Callback callback) {
        String code = error instanceof GamesLibrary.GameException ? ((GamesLibrary.GameException) error).code : "GAME_STORAGE";
        String message = error instanceof GamesLibrary.GameException ? error.getMessage() : "Could not read or save this game. Try again.";
        try { update(connected ? "error" : "offline", message, code); } catch (Exception ignored) { }
        fail(callback, code, message);
    }
    private void fail(ConnectionService.Callback callback, String code, String message) {
        if (callback != null) main.post(() -> { if (!closed) callback.failure(code, message); });
    }
    private void scheduleSync(ConnectionService.Callback callback) {
        if (!syncQueued.compareAndSet(false, true)) {
            if (callback != null) main.post(() -> callback.success(snapshot()));
            return;
        }
        lastAttempt = SystemClock.elapsedRealtime();
        if (closed) { syncQueued.set(false); return; }
        try { worker.execute(() -> {
            try {
                NativeBackend.Endpoint endpoint = requireEndpoint(); long session = epoch;
                update("syncing", "Syncing saved games…", null);
                Map<String, JSONObject> remote = remoteGames(call(endpoint, session, "games.list", new JSONObject()));
                remoteIds.clear(); remoteIds.addAll(remote.keySet());
                remoteEpoch = session;
                JSONArray local = library.list(); Set<String> localIds = new HashSet<>();
                for (int i = 0; i < local.length(); i++) {
                    String hash = local.getJSONObject(i).getString("id");
                    try { library.bytes(hash); localIds.add(hash); }
                    catch (GamesLibrary.GameException damaged) {
                        // A known-good projector copy can repair local storage without deleting either library.
                        if (!remote.containsKey(hash)) throw damaged;
                    }
                }
                for (int i = 0; i < local.length(); i++) {
                    JSONObject game = local.getJSONObject(i);
                    if (localIds.contains(game.getString("id")) && !remote.containsKey(game.getString("id"))) { upload(endpoint, session, game); remoteIds.add(game.getString("id")); }
                }
                for (JSONObject game : remote.values()) if (!localIds.contains(game.getString("id"))) download(endpoint, session, game);
                syncQueued.set(false); update("ready", "Games are saved on this phone and the projector.", null);
                if (callback != null) main.post(() -> { if (!closed) callback.success(snapshot()); });
            } catch (Exception error) { syncQueued.set(false); failure(error, callback); }
        }); } catch (RejectedExecutionException busy) { syncQueued.set(false); fail(callback, "BUSY", "The games library is busy. Try again in a moment."); }
    }
    private NativeBackend.Endpoint requireEndpoint() throws GamesLibrary.GameException {
        NativeBackend.Endpoint endpoint = endpoints.current();
        if (!connected || closed || endpoint == null) throw GamesLibrary.error("NOT_CONNECTED", "Connect to LUMA Receiver to sync or play games.");
        return endpoint;
    }
    private void guard(NativeBackend.Endpoint endpoint, long session) throws GamesLibrary.GameException {
        NativeBackend.Endpoint now = requireEndpoint();
        if (session != epoch || !endpoint.host.equals(now.host) || endpoint.port != now.port || !endpoint.token.equals(now.token))
            throw GamesLibrary.error("NOT_CONNECTED", "The projector connection changed. Sync again after reconnecting.");
    }
    private Map<String, JSONObject> remoteGames(JSONObject reply) throws Exception {
        JSONArray games = reply.optJSONArray("games");
        if (games == null || games.length() > GamesLibrary.MAX_GAMES) throw responseError();
        Map<String, JSONObject> result = new LinkedHashMap<>(); long total = 0;
        for (int i = 0; i < games.length(); i++) {
            JSONObject game = games.getJSONObject(i); String hash = GamesLibrary.id(game.optString("id"));
            long size = exactSize(game, "sizeBytes", 16, GamesLibrary.MAX_ROM_BYTES);
            if (!"nes".equals(game.optString("system")) || result.containsKey(hash)) throw responseError();
            total += size; if (total > GamesLibrary.MAX_LIBRARY_BYTES) throw responseError();
            result.put(hash, new JSONObject().put("id", hash).put("name", GamesLibrary.name(game.optString("name"))).put("system", "nes").put("sizeBytes", size));
        }
        return result;
    }
    private static long exactSize(JSONObject value, String key, long min, long max) throws GamesLibrary.GameException {
        Object raw = value.opt(key);
        if (!(raw instanceof Number)) throw responseError();
        double number = ((Number) raw).doubleValue();
        if (!Double.isFinite(number) || number != Math.floor(number) || number < min || number > max) throw responseError();
        return (long) number;
    }
    private void upload(NativeBackend.Endpoint endpoint, long session, JSONObject game) throws Exception {
        String hash = game.getString("id"); byte[] bytes = library.bytes(hash);
        for (int offset = 0; offset < bytes.length; offset += CHUNK_BYTES) {
            int length = Math.min(CHUNK_BYTES, bytes.length - offset);
            JSONObject reply = call(endpoint, session, "games.put", new JSONObject().put("id", hash).put("name", game.getString("name"))
                    .put("offset", offset).put("totalSize", bytes.length).put("data", Base64.encodeToString(bytes, offset, length, Base64.NO_WRAP)));
            if (Boolean.TRUE.equals(reply.opt("complete")) && exactSize(reply, "received", 0, bytes.length) == bytes.length) return;
            if (exactSize(reply, "received", 0, bytes.length) != offset + length || !(reply.opt("complete") instanceof Boolean)
                    || reply.getBoolean("complete") != (offset + length == bytes.length)) throw responseError();
        }
    }
    private void download(NativeBackend.Endpoint endpoint, long session, JSONObject game) throws Exception {
        String hash = game.getString("id"); int size = game.getInt("sizeBytes"); ByteArrayOutputStream bytes = new ByteArrayOutputStream(size);
        for (int offset = 0; offset < size; offset += CHUNK_BYTES) {
            int length = Math.min(CHUNK_BYTES, size - offset);
            JSONObject reply = call(endpoint, session, "games.get", new JSONObject().put("id", hash).put("offset", offset).put("length", length));
            String data = reply.optString("data");
            if (!hash.equals(reply.optString("id")) || exactSize(reply, "offset", 0, size) != offset
                    || exactSize(reply, "totalSize", 16, GamesLibrary.MAX_ROM_BYTES) != size || data.length() > ((CHUNK_BYTES + 2) / 3) * 4) throw responseError();
            byte[] chunk; try { chunk = Base64.decode(data, Base64.NO_WRAP); } catch (IllegalArgumentException invalid) { throw responseError(); }
            if (chunk.length != length) throw responseError(); bytes.write(chunk);
        }
        guard(endpoint, session);
        library.add(new ByteArrayInputStream(bytes.toByteArray()), game.getString("name"), hash);
    }
    private static GamesLibrary.GameException responseError() { return GamesLibrary.error("RECEIVER_RESPONSE", "The projector returned an invalid games response. Update the receiver and try again."); }
    private JSONObject call(NativeBackend.Endpoint endpoint, long session, String method, JSONObject body) throws Exception {
        guard(endpoint, session); HttpURLConnection connection = null; ScheduledFuture<?> deadline = null;
        try {
            connection = (HttpURLConnection) new URL("http", endpoint.host, endpoint.port, "/api/v1/" + method).openConnection(Proxy.NO_PROXY);
            active = connection; final HttpURLConnection owned = connection;
            deadline = deadlines.schedule(owned::disconnect, 8000, TimeUnit.MILLISECONDS);
            connection.setRequestMethod("POST"); connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
            connection.setConnectTimeout(3000); connection.setReadTimeout(5000);
            connection.setRequestProperty("Authorization", "Bearer " + endpoint.token);
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true); connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) { guard(endpoint, session); out.write(bytes); }
            int status = connection.getResponseCode(); byte[] reply;
            if (status == 401) throw GamesLibrary.error("RECEIVER_AUTH", "LUMA Receiver no longer recognizes this phone. Pair it again.");
            try (InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                if (input == null) throw responseError();
                reply = GamesLibrary.readBounded(input, 512 * 1024);
            }
            guard(endpoint, session);
            JSONTokener parser = new JSONTokener(new String(reply, StandardCharsets.UTF_8)); Object parsed = parser.nextValue();
            if (!(parsed instanceof JSONObject) || parser.nextClean() != 0) throw responseError();
            JSONObject envelope = (JSONObject) parsed;
            if (status != 200 || !envelope.optBoolean("ok") || envelope.optJSONObject("result") == null) {
                String code = envelope.optJSONObject("error") == null ? "" : envelope.getJSONObject("error").optString("code");
                if ("GAME_LIBRARY_FULL".equals(code)) throw GamesLibrary.error(code, "The projector games library is full.");
                if ("UNSUPPORTED_MAPPER".equals(code) || "GAME_UNSUPPORTED".equals(code)) throw GamesLibrary.error(code, "This NES cartridge is not supported by the current emulator.");
                if (status == 404 || "UNKNOWN_METHOD".equals(code)) throw GamesLibrary.error("GAMES_UNAVAILABLE", "Update LUMA Receiver to use Games.");
                throw responseError();
            }
            return envelope.getJSONObject("result");
        } catch (GamesLibrary.GameException known) { throw known; }
        catch (Exception unavailable) { throw GamesLibrary.error("RECEIVER_UNAVAILABLE", "Games sync paused. Reconnect to LUMA and try again; saved games are kept."); }
        finally { if (deadline != null) deadline.cancel(false); if (connection != null) connection.disconnect(); active = null; }
    }
    @Override public void close() {
        closed = true; epoch++; HttpURLConnection old = active; if (old != null) old.disconnect();
        worker.shutdownNow(); deadlines.shutdownNow(); main.removeCallbacksAndMessages(null);
    }
}

package dev.framextv.lumaremote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

/** Owns the in-memory LAN session independently of the browsing Activity. */
public final class ConnectionService extends Service {
    public interface Callback { void success(JSONObject data); void failure(String code, String message); }
    public interface Listener { void onStateChanged(JSONObject snapshot); }
    public final class LocalBinder extends Binder { public ConnectionService getService() { return ConnectionService.this; } }
    public static final String ACTION_OPEN_REMOTE = "dev.framextv.lumaremote.OPEN_REMOTE";
    private static final String START = "dev.framextv.lumaremote.CONNECTING";
    private static final String PLAY = "dev.framextv.lumaremote.PLAY";
    private static final String PAUSE = "dev.framextv.lumaremote.PAUSE";
    private static final String STOP = "dev.framextv.lumaremote.STOP";
    private static final String DISCONNECT = "dev.framextv.lumaremote.DISCONNECT";
    private static final String CHANNEL = "projector_connection";
    private static final int NOTIFICATION = 41;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LocalBinder binder = new LocalBinder();
    private final Object stateLock = new Object();
    private final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();
    private final CopyOnWriteArraySet<Work> pending = new CopyOnWriteArraySet<>();
    private final ThreadPoolExecutor network = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32));
    private NativeBackend backend;
    private SettingsStore settings;
    private volatile JSONObject candidateKodi;
    private volatile long candidateGeneration = -1;
    private volatile long generation;
    private volatile boolean destroyed;
    private MediaSession mediaSession;
    private PendingIntent sessionActivity;
    private VolumeProvider volumeProvider;
    private JSONObject state = new JSONObject();
    private volatile String authorizedToken;
    private volatile String mediaRevision = UUID.randomUUID().toString();
    private boolean foreground, pollQueued, startRequested, autoConnectPaused;
    private long lastHeartbeat;
    private String notice = "Connect to a projector";

    @Override public void onCreate() {
        super.onCreate();
        backend = newBackend();
        settings = new SettingsStore(getApplicationContext());
        synchronized (stateLock) {
            put(state, "connection", object("connected", false, "configured", false, "host", "", "name", ""));
            put(state, "settings", object("hasReceiver", false, "hasKodi", false));
            put(state, "autoConnectPaused", false);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Projector connection", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Controls for the connected projector and your selected Kodi video");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        mediaSession = new MediaSession(this, "LUMA receiver controls");
        Intent open = new Intent(this, MainActivity.class).setAction(ACTION_OPEN_REMOTE).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        sessionActivity = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        mediaSession.setSessionActivity(sessionActivity);
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { mediaAction("kodi.resume"); }
            @Override public void onPause() { mediaAction("kodi.pause"); }
            @Override public void onStop() { mediaAction("kodi.stop"); }
            @Override public void onCustomAction(String action, Bundle extras) {
                if (extras == null || !mediaRevision.equals(extras.getString("revision"))) return;
                if (STOP.equals(action)) mediaAction("kodi.stop");
                else if (DISCONNECT.equals(action)) request("device.disconnect", new JSONObject(), null);
            }
        }, main);
        volumeProvider = new VolumeProvider(VolumeProvider.VOLUME_CONTROL_RELATIVE, 30, 0) {
            @Override public void onAdjustVolume(int direction) {
                if (direction == AudioManager.ADJUST_RAISE) mediaAction("volume-up");
                else if (direction == AudioManager.ADJUST_LOWER) mediaAction("volume-down");
                else if (direction == AudioManager.ADJUST_MUTE || direction == AudioManager.ADJUST_TOGGLE_MUTE) mediaAction("mute");
            }
        };
        mediaSession.setPlaybackToRemote(volumeProvider);
        updatePresentation();
        enqueue(work -> {
            JSONObject metadata = settings.metadata();
            synchronized (stateLock) { if (!work.current()) return; put(state, "settings", metadata); }
            work.post(this::publish);
        }, null);
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (START.equals(action)) { if (startRequested && !foreground) promote(); else if (!startRequested) stopSelf(startId); }
        else if (DISCONNECT.equals(action)) request("device.disconnect", new JSONObject(), null);
        else if (intent != null && mediaRevision.equals(intent.getStringExtra("revision"))) {
            if (PLAY.equals(action)) mediaAction("kodi.resume");
            else if (PAUSE.equals(action)) mediaAction("kodi.pause");
            else if (STOP.equals(action)) mediaAction("kodi.stop");
        }
        if (!foreground && !START.equals(action)) stopSelf(startId);
        return START_NOT_STICKY;
    }
    public MediaSession.Token getMediaSessionToken() { return mediaSession.getSessionToken(); }
    public void authorizeToken(String token) { authorizedToken = token != null && token.length() <= 200 ? token : null; }
    public void revokeToken(String token) { if (token != null && token.equals(authorizedToken)) authorizedToken = null; }
    public void revokeTokens() { authorizedToken = null; }
    public void addListener(Listener listener) { if (listener != null) { listeners.add(listener); main.post(() -> { if (listeners.contains(listener)) listener.onStateChanged(snapshot()); }); } }
    public void removeListener(Listener listener) { listeners.remove(listener); }
    public JSONObject snapshot() { synchronized (stateLock) { return copy(state); } }
    public void allowAutoConnect() {
        main.post(() -> { if (!destroyed) { autoConnectPaused = false; synchronized (stateLock) { put(state, "autoConnectPaused", false); } publish(); } });
    }
    private NativeBackend newBackend() {
        NativeBackend transport = new NativeBackend(getApplicationContext());
        transport.setSourceGuard(token -> token != null && token.equals(authorizedToken));
        return transport;
    }
    private interface NetworkAction { void run(Work work); }
    private final class Work implements Runnable {
        final long session = generation;
        final NativeBackend transport = backend;
        final NetworkAction action;
        final Callback callback;
        boolean finished;
        Work(NetworkAction action, Callback callback) { this.action = action; this.callback = callback; }
        boolean current() { return !destroyed && session == generation; }
        void post(Runnable update) { main.post(() -> { if (current()) update.run(); else cancel(); }); }
        void complete(JSONObject result, String code, String message) {
            if (finished) return; finished = true; pending.remove(this);
            if (callback != null) { if (code == null) callback.success(copy(result)); else callback.failure(code, message); }
        }
        void cancel() { complete(null, "NOT_CONNECTED", "Disconnected from the projector. Connect again to send controls."); }
        @Override public void run() {
            if (!current()) { main.post(this::cancel); return; }
            try { action.run(this); }
            catch (RuntimeException ignored) { post(() -> complete(null, "CONNECTION_FAILED", "Could not complete the control. Try again.")); }
            finally { if (callback == null) main.post(() -> { if (!finished) { finished = true; pending.remove(this); } }); }
        }
    }

    /** Requests and listeners finish on the main thread. No Activity is retained. */
    public void request(String method, JSONObject params, Callback callback) {
        JSONObject args = params == null ? new JSONObject() : copy(params);
        main.post(() -> {
            if (destroyed) { reject(callback, "NOT_CONNECTED", "Connect to the projector first."); return; }
            if ("device.disconnect".equals(method)) { disconnect(callback); return; }
            boolean auto = "app.settings.autoConnect".equals(method), rescan = "app.settings.rescan".equals(method);
            if (auto && autoConnectPaused) { if (callback != null) callback.success(object("connected", connected(), "paused", true, "devices", new JSONArray())); return; }
            if (rescan || "app.settings.reset".equals(method) && ("all".equals(args.optString("scope")) || "receiver".equals(args.optString("scope")))) disconnect(null);
            if ("device.connect".equals(method) || rescan) { autoConnectPaused = false; synchronized (stateLock) { put(state, "autoConnectPaused", false); } }
            if ("device.connect".equals(method) || auto || rescan) {
                notice = "Connecting to projector…";
                startRequested = true;
                try {
                    Intent start = new Intent(this, ConnectionService.class).setAction(START);
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(start); else startService(start);
                    promote();
                } catch (RuntimeException ignored) { demote(); reject(callback, "BACKGROUND_START", "Open the app to connect to the projector."); return; }
            }
            if ("kodi.open".equals(method)) {
                mediaRevision = UUID.randomUUID().toString();
                synchronized (stateLock) { state.remove("remote"); }
                publish();
            }
            if (!enqueue(work -> perform(work, method, args), callback) && "device.connect".equals(method) && !connected()) demote();
        });
    }
    private void disconnect(Callback callback) {
        revokeTokens(); generation++; mediaRevision = UUID.randomUUID().toString();
        candidateKodi = null; candidateGeneration = -1; autoConnectPaused = true;
        NativeBackend old = backend; backend = null;
        if (old != null) old.close();
        for (Work work : pending) work.cancel();
        network.getQueue().clear(); pollQueued = false; lastHeartbeat = 0;
        synchronized (stateLock) {
            JSONObject metadata = state.optJSONObject("settings");
            state = object("connection", object("connected", false, "configured", false, "host", "", "name", ""), "settings", metadata == null ? new JSONObject() : metadata, "autoConnectPaused", true);
        }
        notice = "Disconnected"; demote(); publish();
        if (callback != null) callback.success(object("connected", false));
    }
    private boolean enqueue(NetworkAction action, Callback callback) {
        if (backend == null) backend = newBackend();
        Work work = new Work(action, callback); pending.add(work);
        try { network.execute(work); return true; }
        catch (RejectedExecutionException ignored) { pending.remove(work); reject(callback, "BUSY", "Please wait for the current controls to finish."); return false; }
    }
    private void perform(Work work, String method, JSONObject args) {
        try {
            JSONObject result;
            boolean connecting = "device.connect".equals(method) || "app.settings.autoConnect".equals(method) || "app.settings.rescan".equals(method);
            if ("app.settings.reset".equals(method)) {
                String scope = args.optString("scope", ""); settings.clear(scope);
                if (scope.equals("kodi") || scope.equals("all")) { work.transport.clearKodiConfiguration(); candidateKodi = null; candidateGeneration = -1; }
                result = object("settings", settings.metadata());
            } else if ("app.rememberRemote".equals(method)) {
                result = rememberRemote(work, args);
            } else if ("app.settings.rescan".equals(method) || "app.settings.autoConnect".equals(method)) {
                result = autoConnect(work, "app.settings.rescan".equals(method));
            } else if ("device.connect".equals(method)) result = connectReceiver(work, args.optString("host"));
            else result = work.transport.handle(method, args);
            if ("kodi.configure".equals(method) && work.current()) {
                candidateKodi = object("username", args.optString("username"), "password", args.optString("password"), "port", args.optInt("port", 8080)); candidateGeneration = work.session;
            }
            if ("kodi.connect".equals(method) && work.current() && candidateKodi != null && candidateGeneration == work.session) {
                settings.saveKodi(candidateKodi); candidateKodi = null; candidateGeneration = -1;
            }
            JSONObject connection = work.transport.connectionSnapshot();
            synchronized (stateLock) {
                if (!work.current()) return;
                JSONObject old = state.optJSONObject("connection");
                if (connecting && old != null && !old.optString("host").equals(connection.optString("host"))) {
                    state.remove("remote"); state.remove("playback");
                }
                put(state, "connection", connection);
                put(state, "settings", settings.metadata());
                if ("kodi.status".equals(method)) put(state, "playback", copy(result));
                if (("luma.status".equals(method) || "luma.setVolume".equals(method)) && result.optJSONObject("volume") != null) put(state, "volume", copy(result.optJSONObject("volume")));
                if ("kodi.getSubtitles".equals(method)) observeSubtitles(result);
                if ("app.settings.reset".equals(method)) state.remove("playback");
            }
            work.post(() -> {
                if (connecting && connected()) { notice = "Connected to projector"; lastHeartbeat = 0; schedulePoll(0); }
                else if (connecting) { notice = "No unique projector was found yet."; demote(); }
                publish();
                work.complete(result, null, null);
                if (method != null && (method.startsWith("kodi.") || method.startsWith("luma.")) && !method.equals("kodi.status") && !method.equals("luma.status")) schedulePoll(250);
            });
        } catch (NativeBackend.BackendException failure) {
            if ("KODI_AUTH".equals(failure.code) && work.current()) { candidateKodi = null; candidateGeneration = -1; }
            synchronized (stateLock) {
                if (!work.current()) return;
                put(state, "connection", work.transport.connectionSnapshot());
                put(state, "settings", settings.metadata());
                if (method != null && method.startsWith("kodi.") && !method.equals("kodi.configure")) state.remove("playback");
            }
            work.post(() -> {
                notice = failure.getMessage();
                if ("device.connect".equals(method) || !connected()) demote();
                publish(); work.complete(null, failure.code, failure.getMessage());
            });
        } catch (SettingsStore.SettingsException failure) {
            synchronized (stateLock) {
                if (!work.current()) return;
                put(state, "connection", work.transport.connectionSnapshot()); put(state, "settings", settings.metadata());
            }
            work.post(() -> { notice = failure.getMessage(); if (!connected()) demote(); publish(); work.complete(null, failure.code, failure.getMessage()); });
        } catch (RuntimeException ignored) {
            work.post(() -> { notice = "Could not complete the control. Try again."; publish(); work.complete(null, "CONNECTION_FAILED", notice); });
        }
    }
    private JSONObject connectReceiver(Work work, String host) throws NativeBackend.BackendException, SettingsStore.SettingsException {
        JSONObject current = work.transport.connectionSnapshot(), result;
        if (current.optBoolean("connected") && host.equals(current.optString("host"))) return object("connected", true, "name", current.optString("name"), "device", object("name", current.optString("name")));
        result = work.transport.handle("device.connect", object("host", host));
        if (!work.current()) return result;
        settings.saveReceiver(host, result.optString("name"));
        JSONObject saved = settings.readKodi();
        if (saved != null && work.current()) {
            work.transport.handle("kodi.configure", saved); candidateKodi = null; candidateGeneration = -1;
        }
        return result;
    }
    private JSONObject autoConnect(Work work, boolean rescan) throws NativeBackend.BackendException, SettingsStore.SettingsException {
        JSONObject current = work.transport.connectionSnapshot();
        if (!rescan && current.optBoolean("connected")) return object("connected", true, "name", current.optString("name"), "devices", new JSONArray());
        if (rescan) settings.clear("receiver");
        JSONObject saved = rescan ? null : settings.readReceiver();
        if (saved != null) {
            try { return connectReceiver(work, saved.optString("host")); }
            catch (NativeBackend.BackendException ignored) { if (!work.current()) throw ignored; settings.clear("receiver"); }
        }
        JSONObject discovered = work.transport.handle("device.discover", new JSONObject());
        JSONArray devices = discovered.optJSONArray("devices"); if (devices == null) devices = new JSONArray();
        if (devices.length() == 1 && work.current()) {
            JSONObject result = connectReceiver(work, devices.optJSONObject(0).optString("host")); put(result, "devices", devices); return result;
        }
        return object("connected", false, "devices", devices);
    }
    public boolean rememberHandoff(JSONObject media, JSONObject result) {
        long session = generation;
        JSONObject remote;
        try { remote = remoteData(media, result, media == null ? null : media.optJSONArray("tracks")); }
        catch (NativeBackend.BackendException ignored) { return false; }
        main.post(() -> {
            if (destroyed || session != generation || !connected()) return;
            mediaRevision = UUID.randomUUID().toString();
            synchronized (stateLock) { put(state, "remote", remote); }
            publish(); schedulePoll(0);
        });
        return true;
    }
    private JSONObject rememberRemote(Work work, JSONObject args) throws NativeBackend.BackendException {
        String expected = NativeBackend.publicUrl(args.opt("expectedSourceUrl"));
        JSONObject cached = snapshot().optJSONObject("remote"), proposed = args.optJSONObject("remote");
        JSONObject media = cached == null ? null : cached.optJSONObject("media");
        if (media == null || proposed == null || !expected.equals(media.optString("url"))
                || proposed.optJSONObject("media") == null || !expected.equals(NativeBackend.publicUrl(proposed.optJSONObject("media").opt("url")))) throw NativeBackend.fail("SOURCE_CHANGED");
        JSONObject current = work.transport.handle("kodi.status", new JSONObject());
        if (!current.optBoolean("active") || !expected.equals(current.optString("sourceUrl"))) throw NativeBackend.fail("SOURCE_CHANGED");
        JSONObject updated = remoteData(media, proposed, cached.optJSONArray("catalog"));
        synchronized (stateLock) {
            if (!work.current() || !expected.equals(rememberedSource())) throw NativeBackend.fail("SOURCE_CHANGED");
            put(state, "remote", updated); put(state, "playback", current);
        }
        // Alias updates belong to the same handoff. Preserve its notification
        // revision, so a subtitle choice does not invalidate playback buttons.
        return object("accepted", true);
    }
    private static Object subtitleIndex(Object value) throws NativeBackend.BackendException {
        if (value == null || value == JSONObject.NULL) return JSONObject.NULL;
        if (!(value instanceof Number)) throw NativeBackend.fail("INVALID_SUBTITLE");
        double number = ((Number) value).doubleValue();
        if (Double.isNaN(number) || Double.isInfinite(number) || number != Math.floor(number) || number < 0 || number > 1024) throw NativeBackend.fail("INVALID_SUBTITLE");
        return (int) number;
    }
    private static JSONObject remoteData(JSONObject media, JSONObject result, JSONArray sources) throws NativeBackend.BackendException {
        if (media == null || result == null) throw NativeBackend.fail("INVALID_SUBTITLE");
        String url = NativeBackend.publicUrl(media.opt("url"));
        JSONArray loaded = result.optJSONArray("tracks"), bindings = result.optJSONArray("catalog");
        if (result.has("tracks") && loaded == null || result.has("catalog") && bindings == null
                || loaded != null && loaded.length() > 128 || bindings != null && bindings.length() > 128 || sources != null && sources.length() > 128) throw NativeBackend.fail("INVALID_SUBTITLE");
        JSONArray tracks = new JSONArray(); Set<Integer> indices = new HashSet<>();
        if (loaded != null) for (int i = 0; i < loaded.length(); i++) {
            JSONObject item = loaded.optJSONObject(i); if (item == null) throw NativeBackend.fail("INVALID_SUBTITLE");
            Object raw = subtitleIndex(item.opt("index"));
            if (!(raw instanceof Integer) || !indices.add((Integer) raw)) throw NativeBackend.fail("INVALID_SUBTITLE");
            tracks.put(object("index", raw, "language", clean(item.optString("language"), 64), "label", clean(item.optString("label"), 200)));
        }
        Map<String, JSONObject> catalog = new LinkedHashMap<>();
        if (sources != null) for (int i = 0; i < sources.length(); i++) {
            JSONObject item = sources.optJSONObject(i); if (item == null) throw NativeBackend.fail("INVALID_SUBTITLE");
            String link = NativeBackend.publicUrl(item.opt("url"));
            Object index = subtitleIndex(item.opt("index"));
            if (index instanceof Integer && !indices.contains((Integer) index)) index = JSONObject.NULL;
            if (!catalog.containsKey(link)) catalog.put(link, object("url", link, "language", clean(item.optString("language"), 64), "label", clean(item.optString("label"), 200), "index", index));
        }
        Set<String> seen = new HashSet<>();
        if (bindings != null) for (int i = 0; i < bindings.length(); i++) {
            JSONObject item = bindings.optJSONObject(i); if (item == null) throw NativeBackend.fail("INVALID_SUBTITLE");
            String link = NativeBackend.publicUrl(item.opt("url"));
            if (!catalog.containsKey(link) || !seen.add(link)) throw NativeBackend.fail("INVALID_SUBTITLE");
            Object index = subtitleIndex(item.opt("index"));
            if (index instanceof Integer && !indices.contains((Integer) index)) throw NativeBackend.fail("INVALID_SUBTITLE");
            put(catalog.get(link), "index", index);
        }
        JSONArray entries = new JSONArray(); Set<Integer> bound = new HashSet<>();
        for (JSONObject item : catalog.values()) {
            Object index = item.opt("index");
            if (index instanceof Integer && (!indices.contains((Integer) index) || !bound.add((Integer) index))) throw NativeBackend.fail("INVALID_SUBTITLE");
            entries.put(item);
        }
        Object selected = subtitleIndex(result.opt("selectedIndex"));
        boolean enabled = result.optBoolean("subtitlesEnabled");
        if (selected instanceof Integer && !indices.contains((Integer) selected) || enabled && selected == JSONObject.NULL) throw NativeBackend.fail("INVALID_SUBTITLE");
        JSONObject normalized = object("media", object("url", url, "contentType", clean(media.optString("contentType"), 100), "title", clean(media.optString("title"), 200)),
                "tracks", tracks, "catalog", entries, "selectedIndex", selected, "subtitlesEnabled", enabled);
        if (normalized.toString().getBytes(StandardCharsets.UTF_8).length > 192 * 1024) throw NativeBackend.fail("INVALID_SUBTITLE");
        return normalized;
    }
    private void observeSubtitles(JSONObject observed) {
        JSONObject remote = state.optJSONObject("remote"), playback = state.optJSONObject("playback");
        if (remote == null || playback == null || !matches(remote, playback)) return;
        put(remote, "selectedIndex", observed.opt("currentIndex"));
        put(remote, "subtitlesEnabled", observed.optBoolean("enabled"));
    }
    private boolean connected() { return snapshot().optJSONObject("connection").optBoolean("connected"); }
    private boolean configured() { return snapshot().optJSONObject("connection").optBoolean("configured"); }
    private static boolean matches(JSONObject remote, JSONObject playback) {
        JSONObject media = remote == null ? null : remote.optJSONObject("media");
        return media != null && playback != null && playback.optBoolean("active") && !media.optString("url").isEmpty() && media.optString("url").equals(playback.optString("sourceUrl"));
    }
    private String rememberedSource() {
        JSONObject remote = snapshot().optJSONObject("remote"), media = remote == null ? null : remote.optJSONObject("media");
        return media == null ? "" : media.optString("url");
    }
    /** Never retarget a stale notification to another controller's new video. */
    private void mediaAction(String method) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> mediaAction(method)); return; }
        String expected = rememberedSource();
        String revision = mediaRevision;
        if (expected.isEmpty() || !connected() || !configured()) return;
        enqueue(work -> {
            try {
                JSONObject current = work.transport.handle("kodi.status", new JSONObject());
                synchronized (stateLock) { if (!work.current()) return; put(state, "playback", current); }
                if (!revision.equals(mediaRevision) || !expected.equals(rememberedSource()) || !current.optBoolean("active") || !expected.equals(current.optString("sourceUrl"))) {
                    work.post(() -> { notice = "Kodi is playing a different video. Open the app to choose it."; publish(); }); return;
                }
                JSONObject args = object("expectedSourceUrl", expected);
                if (method.startsWith("volume-") || method.equals("mute")) {
                    put(args, "key", method); work.transport.handle("luma.key", args);
                } else work.transport.handle(method, args);
                JSONObject observed = work.transport.handle("kodi.status", new JSONObject());
                synchronized (stateLock) { if (!work.current()) return; put(state, "playback", observed); put(state, "connection", work.transport.connectionSnapshot()); }
                work.post(this::publish);
            } catch (NativeBackend.BackendException failure) {
                if ("KODI_AUTH".equals(failure.code) && work.current()) { candidateKodi = null; candidateGeneration = -1; }
                synchronized (stateLock) { if (!work.current()) return; put(state, "connection", work.transport.connectionSnapshot()); state.remove("playback"); }
                work.post(() -> { notice = failure.getMessage(); publish(); if (!connected()) demote(); });
            }
        }, null);
    }
    private final Runnable poll = () -> {
        if (destroyed || !foreground || !connected() || pollQueued) return;
        pollQueued = true;
        boolean queued = enqueue(work -> {
            try {
                if (configured()) {
                    try {
                        JSONObject current = work.transport.handle("kodi.status", new JSONObject());
                        synchronized (stateLock) { if (!work.current()) return; put(state, "playback", current); }
                    } catch (NativeBackend.BackendException failure) {
                        if ("KODI_AUTH".equals(failure.code) && work.current()) { candidateKodi = null; candidateGeneration = -1; }
                        synchronized (stateLock) { if (!work.current()) return; state.remove("playback"); }
                    }
                }
                if (!work.current()) return;
                long now = SystemClock.elapsedRealtime();
                if (now - lastHeartbeat >= 15000 || lastHeartbeat == 0) {
                    try {
                        JSONObject result = work.transport.handle("luma.status", new JSONObject());
                        synchronized (stateLock) { if (!work.current()) return; if (result.optJSONObject("volume") != null) put(state, "volume", result.optJSONObject("volume")); }
                    } catch (NativeBackend.BackendException failure) { work.post(() -> notice = failure.getMessage()); }
                    work.post(() -> lastHeartbeat = now);
                }
                synchronized (stateLock) { if (!work.current()) return; put(state, "connection", work.transport.connectionSnapshot()); }
            } finally {
                work.post(() -> { pollQueued = false; if (!connected()) demote(); publish(); schedulePoll(4000); });
            }
        }, null);
        if (!queued) { pollQueued = false; schedulePoll(4000); }
    };
    private void schedulePoll(long delay) {
        main.removeCallbacks(poll);
        if (foreground && connected()) main.postDelayed(poll, delay);
    }
    private void promote() {
        if (destroyed) return;
        Notification notification = notification();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(NOTIFICATION, notification);
        foreground = true;
    }
    private void demote() {
        main.removeCallbacks(poll);
        foreground = false; startRequested = false;
        removeForeground();
        stopSelf();
    }
    private void publish() {
        if (destroyed) return;
        updatePresentation();
        JSONObject observed = snapshot();
        for (Listener listener : listeners) listener.onStateChanged(copy(observed));
    }
    private void updatePresentation() {
        if (mediaSession == null) return;
        JSONObject all = snapshot(), connection = all.optJSONObject("connection"), remote = all.optJSONObject("remote"), playback = all.optJSONObject("playback");
        boolean verified = connection != null && connection.optBoolean("connected") && matches(remote, playback);
        long actions = verified ? PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_STOP : 0;
        int status = verified ? (playback.optBoolean("playing") ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED) : PlaybackState.STATE_NONE;
        long position = verified ? Math.max(0, playback.optLong("positionSeconds")) * 1000 : 0;
        PlaybackState.Builder controls = new PlaybackState.Builder().setActions(actions).setState(status, position,
                verified && playback.optBoolean("playing") ? 1f : 0f, SystemClock.elapsedRealtime());
        if (verified) {
            Bundle revision = new Bundle(); revision.putString("revision", mediaRevision);
            controls.addCustomAction(new PlaybackState.CustomAction.Builder(STOP, "Stop video", android.R.drawable.ic_menu_close_clear_cancel).setExtras(revision).build());
            controls.addCustomAction(new PlaybackState.CustomAction.Builder(DISCONNECT, "Disconnect", android.R.drawable.ic_menu_close_clear_cancel).setExtras(revision).build());
        }
        mediaSession.setPlaybackState(controls.build());
        String title = verified ? clean(remote.optJSONObject("media").optString("title"), 200) : "LUMA Remote";
        if (title.isEmpty()) title = "Kodi video";
        MediaMetadata.Builder metadata = new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, connection == null ? "Projector" : clean(connection.optString("name"), 200));
        if (verified) metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, playback.optLong("durationSeconds") * 1000);
        mediaSession.setMetadata(metadata.build());
        mediaSession.setActive(verified);
        JSONObject volume = all.optJSONObject("volume");
        if (volume != null && volume.optInt("max") > 0) volumeProvider.setCurrentVolume(Math.max(0, Math.min(30,
                (int) Math.round(volume.optDouble("current") * 30 / volume.optDouble("max")))));
        if (foreground) getSystemService(NotificationManager.class).notify(NOTIFICATION, notification());
    }
    private Notification notification() {
        JSONObject all = snapshot(), connection = all.optJSONObject("connection"), remote = all.optJSONObject("remote"), playback = all.optJSONObject("playback");
        boolean verified = connection != null && connection.optBoolean("connected") && matches(remote, playback);
        String name = connection == null ? "Projector" : clean(connection.optString("name"), 200);
        if (name.isEmpty()) name = "Projector";
        String title = verified ? clean(remote.optJSONObject("media").optString("title"), 200) : "LUMA Remote";
        if (title.isEmpty()) title = "Kodi video";
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        builder.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(title).setContentText(verified ? name : clean(notice, 200))
                .setContentIntent(sessionActivity).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
                .setCategory(verified ? Notification.CATEGORY_TRANSPORT : Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PUBLIC);
        if (verified) {
            Notification.MediaStyle style = new Notification.MediaStyle().setMediaSession(mediaSession.getSessionToken());
            boolean playing = playback.optBoolean("playing");
            builder.addAction(new Notification.Action.Builder(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                    playing ? "Pause" : "Play", pending(playing ? PAUSE : PLAY, 1)).build());
            builder.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Stop video", pending(STOP, 2)).build());
            style.setShowActionsInCompactView(0, 1);
            builder.setStyle(style);
        }
        builder.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", pending(DISCONNECT, 3)).build());
        return builder.build();
    }
    private PendingIntent pending(String action, int requestCode) {
        Intent intent = new Intent(this, ConnectionService.class).setAction(action);
        if (!DISCONNECT.equals(action)) intent.setData(Uri.fromParts("luma-control", mediaRevision + ":" + action, null)).putExtra("revision", mediaRevision);
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    @Override public void onDestroy() {
        destroyed = true; generation++; revokeTokens(); main.removeCallbacksAndMessages(null); listeners.clear();
        for (Work work : pending) work.cancel();
        if (backend != null) backend.close(); network.shutdownNow();
        if (mediaSession != null) { mediaSession.setActive(false); mediaSession.release(); }
        if (foreground) removeForeground();
        super.onDestroy();
    }
    private void removeForeground() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
    }
    private static void reject(Callback callback, String code, String message) { if (callback != null) callback.failure(code, message); }
    private static JSONObject copy(JSONObject value) { try { return value == null ? new JSONObject() : new JSONObject(value.toString()); } catch (Exception ignored) { return new JSONObject(); } }
    private static void put(JSONObject target, String key, Object value) { try { target.put(key, value == null ? JSONObject.NULL : value); } catch (Exception ignored) {} }
    private static JSONObject object(Object... fields) { JSONObject result = new JSONObject(); for (int i = 0; i < fields.length; i += 2) put(result, (String) fields[i], fields[i + 1]); return result; }
    private static String clean(String text, int max) {
        String value = text == null ? "" : text.replaceAll("[\\p{Cntrl}\\u007f-\\u009f\\u202a-\\u202e\\u2066-\\u2069]", " ").replaceAll("(?i)(?:[a-z][a-z0-9+.-]*://|www\\.)\\S+", "").trim();
        return value.substring(0, Math.min(max, value.length()));
    }
}

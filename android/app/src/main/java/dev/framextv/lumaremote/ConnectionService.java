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
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
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
    public static final String ACTION_PLAY_NEXT = "dev.framextv.lumaremote.PLAY_NEXT";
    private static final String START = "dev.framextv.lumaremote.CONNECTING";
    private static final String PLAY = "dev.framextv.lumaremote.PLAY";
    private static final String PAUSE = "dev.framextv.lumaremote.PAUSE";
    private static final String VOLUME_DOWN = "dev.framextv.lumaremote.VOLUME_DOWN";
    private static final String VOLUME_UP = "dev.framextv.lumaremote.VOLUME_UP";
    private static final String STOP = "dev.framextv.lumaremote.STOP";
    private static final String NEXT = "dev.framextv.lumaremote.NEXT";
    private static final String DISCONNECT = "dev.framextv.lumaremote.DISCONNECT";
    private static final String CHANNEL = "projector_connection";
    private static final int NOTIFICATION = 41, MEDIA_NOTIFICATION = 42;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LocalBinder binder = new LocalBinder();
    private final Object stateLock = new Object();
    private final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();
    private final CopyOnWriteArraySet<Work> pending = new CopyOnWriteArraySet<>();
    private final ThreadPoolExecutor network = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32));
    private final ThreadPoolExecutor televisionNetwork = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16));
    private final ThreadPoolExecutor subtitleNetwork = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(4));
    private final ThreadPoolExecutor textNetwork = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1));
    private final ThreadPoolExecutor catalogNetwork = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(4));
    private final ThreadPoolExecutor libraryReads = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8));
    private SubtitleDelivery subtitleDelivery;
    private WatchHistory watchHistory;
    private LibraryStore libraryStore;
    private CatalogClient catalogClient;
    private WebContentResolver catalogWebResolver;
    private volatile String subtitleSource = "";
    private final java.util.concurrent.atomic.AtomicLong subtitleGeneration = new java.util.concurrent.atomic.AtomicLong();
    private NativeBackend backend;
    private MsxServer television;
    private PhilipsTextController televisionText;
    private volatile boolean textRequestPending;
    private volatile long textGeneration;
    private MatterControllerAdapter matter;
    private boolean matterPollQueued;
    private volatile String selectedTarget = "luma";
    private boolean televisionRunning, televisionStarting, televisionRestoreQueued;
    private volatile long televisionGeneration;
    private String televisionSource = "";
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
    private volatile String authorizedTarget = "luma";
    private JSONObject preparedRemote;
    private JSONObject televisionMedia;
    private String preparedToken;
    private long preparedGeneration = -1;
    private volatile String mediaRevision = UUID.randomUUID().toString();
    private boolean foreground, pollQueued, startRequested, autoConnectPaused;
    private long lastHeartbeat;
    private String notice = "Connect to a projector";
    private String lastPresentation = "";
    private String lastNotificationSignature;
    private long lastNotificationPost;
    private boolean notificationFlushQueued;
    private final Runnable notificationFlush = this::flushNotifications;
    private static final long VOLUME_COOLDOWN = 2000;
    // Protected by stateLock; all timers and presentation updates use main.
    private JSONObject confirmedVolume;
    private Integer queuedVolume;
    private long volumeVersion, volumeEpoch, volumeDue;
    private boolean volumeInFlight;
    private String volumeSource = "", volumeRevision = "", volumeWriteToken;
    private long volumeWriteVersion, volumeWriteEpoch, volumeWriteGeneration;
    private PowerManager.WakeLock volumeWakeLock;
    private final Runnable volumeFlush = this::flushQueuedVolume;

    @Override public void onCreate() {
        super.onCreate();
        watchHistory = new WatchHistory(getApplicationContext());
        libraryStore = new LibraryStore(getApplicationContext());
        libraryStore.migrate(watchHistory.snapshot());
        watchHistory.setProgressListener(libraryStore::record);
        catalogClient = new CatalogClient();
        catalogWebResolver = new WebContentResolver(getApplicationContext());
        subtitleDelivery = new SubtitleDelivery(NativeBackend::publicUrl);
        backend = newBackend();
        settings = new SettingsStore(getApplicationContext());
        television = new MsxServer(getApplicationContext());
        television.setLibrary(new MsxServer.Library() {
            @Override public JSONObject metadata(String action, JSONObject params) throws MsxServer.LibraryException {
                if (destroyed) throw new MsxServer.LibraryException("CANCELLED");
                if ("history".equals(action)) {
                    if (params.length() != 0) throw new MsxServer.LibraryException("CATALOG_INVALID_INPUT");
                    return watchHistory.snapshot();
                }
                try { return catalogClient.handle(action, params); }
                catch (CatalogClient.CatalogException error) { throw new MsxServer.LibraryException(error.code); }
            }
            @Override public JSONObject resolve(JSONObject selection, java.util.function.BooleanSupplier current) throws MsxServer.LibraryException {
                return resolveCatalogMedia(selection, current);
            }
            @Override public void onReady(JSONObject media, boolean resumeApplied) { rememberWatchHandoff(media, "philips", resumeApplied); }
        });
        televisionText = new PhilipsTextController(getApplicationContext());
        matter = new MatterControllerAdapter(getApplicationContext(), value -> main.post(() -> {
            if (destroyed) return;
            updateTelevisionState(); scheduleMatterPoll();
            JSONObject current = matter.snapshot(); String error = current.optString("errorCode");
            boolean failedPairing = !current.optBoolean("paired") && !current.optBoolean("pairing")
                    && ("MATTER_PAIRING".equals(error) || "MATTER_TIMEOUT".equals(error) || "MATTER_STORAGE".equals(error));
            if (foreground && !connected() && (failedPairing || current.optBoolean("paused"))) demote();
            publish();
        }));
        television.setSourceGuard(token -> "philips".equals(authorizedTarget) && token != null && token.equals(authorizedToken));
        selectedTarget = getSharedPreferences("receiver-selection", MODE_PRIVATE).getString("target", "luma");
        if (!"philips".equals(selectedTarget)) selectedTarget = "luma";
        synchronized (stateLock) {
            put(state, "connection", object("connected", false, "configured", false, "host", "", "name", ""));
            put(state, "settings", object("hasReceiver", false, "hasKodi", false));
            put(state, "autoConnectPaused", false);
            put(state, "selectedTarget", selectedTarget);
            put(state, "receivers", object("philips", televisionSnapshot()));
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
            @Override public void onSkipToNext() { openNextEpisode(); }
            @Override public void onCustomAction(String action, Bundle extras) {
                if (extras == null || !mediaRevision.equals(extras.getString("revision"))) return;
                if (VOLUME_DOWN.equals(action)) mediaAction("volume-down");
                else if (VOLUME_UP.equals(action)) mediaAction("volume-up");
                else if (STOP.equals(action)) mediaAction("kodi.stop");
                else if (DISCONNECT.equals(action)) disconnectSelected();
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
        else if (intent != null && mediaRevision.equals(intent.getStringExtra("revision"))) {
            if (DISCONNECT.equals(action)) disconnectSelected();
            if (PLAY.equals(action)) mediaAction("kodi.resume");
            else if (PAUSE.equals(action)) mediaAction("kodi.pause");
            else if (VOLUME_DOWN.equals(action)) mediaAction("volume-down");
            else if (VOLUME_UP.equals(action)) mediaAction("volume-up");
            else if (STOP.equals(action)) mediaAction("kodi.stop");
            else if (NEXT.equals(action)) openNextEpisode();
        }
        if (!foreground && !START.equals(action)) stopSelf(startId);
        return START_NOT_STICKY;
    }
    public MediaSession.Token getMediaSessionToken() { return mediaSession.getSessionToken(); }
    public boolean nextLaunchCurrent(String revision) {
        JSONObject all = presentationSnapshot(), remote = all.optJSONObject("remote");
        return revision != null && revision.equals(mediaRevision) && verifiedPlayback(all)
                && remote != null && remote.optJSONObject("nextEpisode") != null;
    }
    public void authorizeToken(String token) { authorizeToken(token, "luma"); }
    public void authorizeToken(String token, String target) {
        synchronized (stateLock) {
            if (token == null || !token.equals(authorizedToken)) clearPreparedHandoff();
            authorizedTarget = "philips".equals(target) ? "philips" : "luma";
            authorizedToken = token != null && token.length() <= 200 ? token : null;
        }
    }
    public void revokeToken(String token) { synchronized (stateLock) { if (token != null && token.equals(authorizedToken)) { authorizedToken = null; clearPreparedHandoff(); } } }
    public void revokeTokens() { synchronized (stateLock) { authorizedToken = null; clearPreparedHandoff(); } }
    private void clearPreparedHandoff() { preparedRemote = null; preparedToken = null; preparedGeneration = -1; }
    /** Retains the declared catalog if the browsing Activity closes during Open.
     * It is not published until this exact authorized source is submitted. */
    public boolean prepareHandoff(JSONObject media, String token) {
        JSONObject remote;
        try { remote = remoteData(media, new JSONObject(), media == null ? null : media.optJSONArray("tracks")); }
        catch (NativeBackend.BackendException ignored) { return false; }
        synchronized (stateLock) {
            if (destroyed || token == null || !token.equals(authorizedToken) || !"luma".equals(authorizedTarget)) return false;
            preparedRemote = remote; preparedToken = token; preparedGeneration = generation;
        }
        return true;
    }
    public void addListener(Listener listener) { if (listener != null) { listeners.add(listener); main.post(() -> { if (listeners.contains(listener)) listener.onStateChanged(snapshot()); }); } }
    public void removeListener(Listener listener) { listeners.remove(listener); }
    public JSONObject snapshot() { synchronized (stateLock) { return copy(state); } }
    /** Stable identity comes from our native browser URL, never the stream URL. */
    public JSONObject attachWatchMetadata(JSONObject media, String pageUrl) {
        JSONObject result = copy(media);
        JSONObject content = WatchHistory.contentFromPage(pageUrl, result.optString("title"));
        result.remove("content"); result.remove("resumeSeconds");
        if (content != null) {
            put(result, "content", content);
            JSONObject saved = libraryStore == null ? null : libraryStore.findProgress(content);
            if (saved == null) saved = watchHistory.find(content.optString("key"));
            put(result, "resumeSeconds", saved == null ? 0 : saved.optDouble("resumeSeconds", 0));
        }
        return result;
    }
    /** Retain controls for every accepted handoff, but record progress only when
     * a requested resume was applied and the observed source still matches. */
    public void rememberWatchHandoff(JSONObject media, String target, boolean resumeApplied) {
        if (media == null) return;
        JSONObject owned = copy(media);
        main.post(() -> {
            if (destroyed) return;
            String receiver = "philips".equals(target) ? "philips" : "luma";
            JSONObject playback = "philips".equals(receiver) ? television.status().optJSONObject("playback") : snapshot().optJSONObject("playback");
            if (playback == null || !playback.optBoolean("active") || !owned.optString("url").equals(playback.optString("sourceUrl", playback.optString("source")))) return;
            JSONObject content = validatedContent(owned);
            synchronized (stateLock) {
                if ("philips".equals(receiver)) televisionMedia = systemMedia(owned, content);
                else {
                    JSONObject remote = state.optJSONObject("remote"), current = remote == null ? null : remote.optJSONObject("media");
                    if (current != null && owned.optString("url").equals(current.optString("url")) && content != null) put(current, "content", content);
                    if (remote != null) remote.remove("nextEpisode");
                }
            }
            if (!(owned.optDouble("resumeSeconds", 0) > 0 && !resumeApplied) && watchHistory.bind(receiver, owned)) watchHistory.observe(receiver, playback);
            publish();
            if (content == null || !"tv".equals(content.optString("kind"))) return;
            refreshNextEpisode(receiver, owned.optString("url"), content, 0);
        });
    }
    private void refreshNextEpisode(String receiver, String source, JSONObject content, int attempt) {
        JSONObject selection = object("kind", "tv", "id", content.optString("contentId"), "season", content.optInt("season"), "episode", content.optInt("episode"));
        requestLibrary("watch.nextEpisode", selection, new Callback() {
            @Override public void success(JSONObject result) {
                if (!result.optBoolean("lookupComplete") && attempt < 12) {
                    main.postDelayed(() -> refreshNextEpisode(receiver, source, content, attempt + 1), 1000); return;
                }
                retainNextEpisode(receiver, source, content, result.optJSONObject("nextEpisode"));
            }
            @Override public void failure(String code, String message) { retainNextEpisode(receiver, source, content, null); }
        });
    }
    private void retainNextEpisode(String receiver, String source, JSONObject content, JSONObject next) {
        JSONObject safe = null;
        if (next != null && "tv".equals(next.optString("kind")) && content.optString("contentId").equals(next.optString("id"))) {
            int season = next.optInt("season", -1), episode = next.optInt("episode", -1);
            if (season >= 0 && season <= 9999 && episode > 0 && episode <= 9999
                    && (season > content.optInt("season") || season == content.optInt("season") && episode > content.optInt("episode"))) safe = copy(next);
        }
        synchronized (stateLock) {
            JSONObject playback = "philips".equals(receiver) ? television.status().optJSONObject("playback") : state.optJSONObject("playback");
            String observed = playback == null ? "" : playback.optString("sourceUrl", playback.optString("source"));
            if (!source.equals(observed)) return;
            if ("philips".equals(receiver)) {
                if (televisionMedia == null || !source.equals(televisionMedia.optString("url"))) return;
                put(televisionMedia, "nextEpisode", safe == null ? JSONObject.NULL : safe);
            } else {
                JSONObject remote = state.optJSONObject("remote"), media = remote == null ? null : remote.optJSONObject("media");
                if (media == null || !source.equals(media.optString("url"))) return;
                put(remote, "nextEpisode", safe == null ? JSONObject.NULL : safe);
            }
        }
        publish();
    }
    public void chooseCatalog(JSONObject params, java.util.function.BooleanSupplier current, Callback callback) {
        requestCatalog("catalog.resolve", params, current, callback);
    }
    private void requestLibrary(String method, JSONObject params, Callback callback) {
        JSONObject owned = copy(params);
        try {
            libraryReads.execute(() -> {
                JSONObject result = null; String failure = null;
                try {
                    if (destroyed || libraryStore == null) throw new IllegalStateException();
                    result = "watch.library".equals(method) ? libraryStore.list(owned)
                            : "watch.libraryDetails".equals(method) ? libraryStore.details(owned) : libraryStore.nextEpisode(owned);
                } catch (LibraryStore.LibraryException invalid) { failure = invalid.code; }
                catch (RuntimeException unavailable) { failure = "LIBRARY_UNAVAILABLE"; }
                JSONObject response = result; String code = failure;
                main.post(() -> {
                    if (destroyed) { reject(callback, "NOT_CONNECTED", "Open the app to view your library."); return; }
                    if (code != null) reject(callback, code, "The saved library could not load. Playback has not changed.");
                    else if (callback != null) callback.success(response);
                });
            });
        } catch (RejectedExecutionException busy) { reject(callback, "BUSY", "Wait for the current library view."); }
    }
    private void requestCatalog(String method, JSONObject params, Callback callback) {
        requestCatalog(method, params, () -> true, callback);
    }
    private void requestCatalog(String method, JSONObject params, java.util.function.BooleanSupplier current, Callback callback) {
        String action = method.substring("catalog.".length());
        if (!java.util.Arrays.asList("list", "search", "details", "episodes", "resolve").contains(action)) {
            reject(callback, "CATALOG_INVALID_INPUT", "Choose a catalog action."); return;
        }
        JSONObject owned = copy(params);
        try {
            catalogNetwork.execute(() -> {
                JSONObject result = null; String failure = null;
                try {
                    if (!catalogCurrent(current)) throw new MsxServer.LibraryException("CANCELLED");
                    result = "resolve".equals(action) ? resolveCatalogMedia(owned, current) : catalogClient.handle(action, owned);
                    if (!catalogCurrent(current)) throw new MsxServer.LibraryException("CANCELLED");
                } catch (MsxServer.LibraryException error) { failure = error.code; }
                catch (CatalogClient.CatalogException error) { failure = error.code; }
                catch (RuntimeException error) { failure = "CATALOG_RESPONSE"; }
                JSONObject response = result; String code = failure;
                main.post(() -> {
                    if (!catalogCurrent(current)) { reject(callback, "CANCELLED", "Content selection changed."); return; }
                    if (code != null) reject(callback, code, "Could not prepare this title. Choose another source or try again.");
                    else if (callback != null) callback.success(response);
                });
            });
        } catch (RejectedExecutionException busy) { reject(callback, "BUSY", "Wait for the current catalog request."); }
    }
    private boolean catalogCurrent(java.util.function.BooleanSupplier current) {
        try { return !destroyed && current != null && current.getAsBoolean(); }
        catch (RuntimeException cancelled) { return false; }
    }
    private JSONObject resolveCatalogMedia(JSONObject selection, java.util.function.BooleanSupplier current) throws MsxServer.LibraryException {
        JSONObject params = copy(selection);
        if (params.has("resume") && !(params.opt("resume") instanceof Boolean)) throw new MsxServer.LibraryException("CATALOG_INVALID_INPUT");
        boolean resume = params.optBoolean("resume", true); params.remove("resume");
        if (!catalogCurrent(current)) throw new MsxServer.LibraryException("CANCELLED");
        JSONObject media;
        try { media = catalogClient.handle("resolve", params); }
        catch (CatalogClient.CatalogException error) {
            if ("CATALOG_INVALID_INPUT".equals(error.code)) throw new MsxServer.LibraryException(error.code);
            if (!catalogCurrent(current)) throw new MsxServer.LibraryException("CANCELLED");
            try { media = catalogWebResolver.resolve(params, () -> catalogCurrent(current)); }
            catch (WebContentResolver.ResolveException failure) { throw new MsxServer.LibraryException(failure.code); }
        }
        if (!catalogCurrent(current)) throw new MsxServer.LibraryException("CANCELLED");
        JSONObject content = media.optJSONObject("content");
        if (content == null) throw new MsxServer.LibraryException("SOURCE_UNSUPPORTED");
        media = attachWatchMetadata(media, content.optString("watchUrl"));
        if (!resume) put(media, "resumeSeconds", 0);
        return media;
    }
    public void restoreReceivers() {
        main.post(() -> {
            if (!destroyed && matter.snapshot().optBoolean("paired")) request("matter.status", object("reconnect", true), null);
            if (destroyed || televisionRunning || televisionRestoreQueued || !television.hasSavedReceiver()) return;
            televisionRestoreQueued = true;
            request("msx.start", new JSONObject(), new Callback() {
                @Override public void success(JSONObject result) { televisionRestoreQueued = false; }
                @Override public void failure(String code, String message) { televisionRestoreQueued = false; }
            });
        });
    }
    public void allowAutoConnect() {
        main.post(() -> { if (!destroyed) { autoConnectPaused = false; synchronized (stateLock) { put(state, "autoConnectPaused", false); } publish(); } });
    }
    private NativeBackend newBackend() {
        NativeBackend transport = new NativeBackend(getApplicationContext());
        transport.setSourceGuard(token -> token != null && ("luma".equals(authorizedTarget) && token.equals(authorizedToken) || currentVolumeWrite(token)));
        transport.setPreparedSubtitleResolver((id, source, original, peer) -> {
            if (!"luma".equals(selectedTarget)) throw NativeBackend.fail("SOURCE_CHANGED");
            try { return subtitleDelivery.resolve(id, source, original, peer).url; }
            catch (SubtitleDelivery.DeliveryException error) { throw NativeBackend.fail("SUBTITLE_EXPIRED"); }
        });
        return transport;
    }
    private interface NetworkAction { void run(Work work); }
    private final class Work implements Runnable {
        final long session = generation;
        final NativeBackend transport = backend;
        final NetworkAction action;
        final Callback callback;
        final PowerManager.WakeLock wakeLock;
        boolean finished;
        Work(NetworkAction action, Callback callback, boolean userMediaAction) {
            this.action = action; this.callback = callback;
            // The system holds the callback awake, not the asynchronous LAN work.
            // Only a requested control needs CPU time; playback and polling do not.
            wakeLock = userMediaAction ? getSystemService(PowerManager.class)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LumaRemote:MediaControl") : null;
            if (wakeLock != null) { wakeLock.setReferenceCounted(false); wakeLock.acquire(30000); }
        }
        synchronized void releaseWakeLock() { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); }
        boolean current() { return !destroyed && session == generation; }
        void post(Runnable update) { main.post(() -> { if (current()) update.run(); else cancel(); }); }
        void complete(JSONObject result, String code, String message) {
            if (finished) return; finished = true; pending.remove(this);
            if (callback != null) { if (code == null) callback.success(copy(result)); else callback.failure(code, message); }
        }
        void cancel() { releaseWakeLock(); complete(null, "NOT_CONNECTED", "Disconnected from the projector. Connect again to send controls."); }
        @Override public void run() {
            if (!current()) { main.post(this::cancel); return; }
            try { action.run(this); }
            catch (RuntimeException ignored) { post(() -> complete(null, "CONNECTION_FAILED", "Could not complete the control. Try again.")); }
            finally {
                releaseWakeLock();
                if (callback == null) main.post(() -> { if (!finished) { finished = true; pending.remove(this); } });
            }
        }
    }

    /** Requests and listeners finish on the main thread. No Activity is retained. */
    public void request(String method, JSONObject params, Callback callback) {
        JSONObject args = params == null ? new JSONObject() : copy(params);
        main.post(() -> {
            if (destroyed) { reject(callback, "NOT_CONNECTED", "Connect to the projector first."); return; }
            if ("app.selectTarget".equals(method)) {
                String target = args.optString("target");
                if (!"luma".equals(target) && !"philips".equals(target)) { reject(callback, "INVALID_REQUEST", "Choose a receiver."); return; }
                if (!target.equals(selectedTarget)) { cancelQueuedVolume(); cancelTelevisionText(); mediaRevision = UUID.randomUUID().toString(); }
                selectedTarget = target;
                getSharedPreferences("receiver-selection", MODE_PRIVATE).edit().putString("target", target).apply();
                synchronized (stateLock) { put(state, "selectedTarget", selectedTarget); }
                publish();
                if (callback != null) callback.success(snapshot());
                return;
            }
            if (method != null && method.startsWith("msx.")) { requestTelevision(method, args, callback); return; }
            if (method != null && method.startsWith("matter.")) { requestMatter(method, args, callback); return; }
            if ("watch.history".equals(method)) {
                if (args.length() != 0) { reject(callback, "INVALID_REQUEST", "Open viewing history without extra fields."); return; }
                if (callback != null) callback.success(watchHistory.snapshot());
                return;
            }
            if ("watch.library".equals(method) || "watch.libraryDetails".equals(method) || "watch.nextEpisode".equals(method)) { requestLibrary(method, args, callback); return; }
            if (method != null && method.startsWith("catalog.")) { requestCatalog(method, args, callback); return; }
            if ("device.disconnect".equals(method)) { disconnect(callback); return; }
            if ("kodi.prepareSubtitle".equals(method) || "kodi.subtitleDelivery".equals(method)) {
                requestSubtitle(method, args, callback); return;
            }
            if ("device.connect".equals(method) || "kodi.configure".equals(method) || "app.settings.reset".equals(method)) clearSubtitleDelivery();
            if ("luma.queueVolume".equals(method)) { queueVolume(args, callback); return; }
            if ("device.connect".equals(method) || "luma.setVolume".equals(method)) cancelQueuedVolume();
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
                clearSubtitleDelivery();
                cancelQueuedVolume();
                mediaRevision = UUID.randomUUID().toString();
                synchronized (stateLock) {
                    JSONObject media = args.optJSONObject("media"), remembered = preparedRemote == null ? null : preparedRemote.optJSONObject("media");
                    if (preparedGeneration == generation && preparedToken != null && preparedToken.equals(authorizedToken)
                            && preparedToken.equals(args.optString("sourceToken")) && media != null && remembered != null
                            && remembered.optString("url").equals(media.optString("url"))) put(state, "remote", copy(preparedRemote));
                    else state.remove("remote");
                }
                publish();
            }
            if (!enqueue(work -> perform(work, method, args), callback) && "device.connect".equals(method) && !connected()) demote();
        });
    }
    private void disconnect(Callback callback) {
        clearSubtitleDelivery();
        cancelQueuedVolume();
        if ("luma".equals(authorizedTarget)) revokeTokens();
        generation++; mediaRevision = UUID.randomUUID().toString();
        candidateKodi = null; candidateGeneration = -1; autoConnectPaused = true;
        confirmedVolume = null;
        NativeBackend old = backend; backend = null;
        if (old != null) old.close();
        for (Work work : pending) work.cancel();
        network.getQueue().clear(); pollQueued = false; lastHeartbeat = 0;
        synchronized (stateLock) {
            JSONObject metadata = state.optJSONObject("settings");
            state = object("connection", object("connected", false, "configured", false, "host", "", "name", ""), "settings", metadata == null ? new JSONObject() : metadata, "autoConnectPaused", true);
            put(state, "selectedTarget", selectedTarget);
            put(state, "receivers", object("philips", televisionSnapshot()));
        }
        notice = "Disconnected"; demote(); publish();
        if (callback != null) callback.success(object("connected", false));
    }
    private static String subtitleFailure(String code) {
        if ("SUBTITLE_FETCH".equals(code)) return "SUBTITLE_DOWNLOAD";
        if ("SUBTITLE_UNAVAILABLE".equals(code)) return "SUBTITLE_EXPIRED";
        if ("SUBTITLE_NETWORK".equals(code)) return "SUBTITLE_DELIVERY";
        return code;
    }
    private void clearSubtitleDelivery() {
        subtitleGeneration.incrementAndGet();
        subtitleSource = "";
        if (subtitleDelivery != null) subtitleDelivery.revokeAll();
    }
    private void observeSubtitleSource(JSONObject playback) {
        if (!subtitleSource.isEmpty() && (playback == null || !playback.optBoolean("active") || !subtitleSource.equals(playback.optString("sourceUrl")))) clearSubtitleDelivery();
    }
    private void requestSubtitle(String method, JSONObject args, Callback callback) {
        try {
            JSONObject track = args.optJSONObject("track");
            if (track == null) throw NativeBackend.fail("INVALID_SUBTITLE");
            final String source = NativeBackend.publicUrl(args.opt("expectedSourceUrl")), original = NativeBackend.publicUrl(track.opt("url"));
            final String peer, revision = mediaRevision, token = args.has("sourceToken") ? args.optString("sourceToken") : null;
            final long session = generation, subtitleEpoch;
            synchronized (stateLock) {
                JSONObject connection = state.optJSONObject("connection"), remote = state.optJSONObject("remote");
                if (!"luma".equals(selectedTarget)) throw NativeBackend.fail("SOURCE_CHANGED");
                if (connection == null || !connection.optBoolean("configured")) throw NativeBackend.fail("KODI_CONFIG");
                if (!source.equals(rememberedSource()) || !matches(remote, state.optJSONObject("playback"))) throw NativeBackend.fail("SOURCE_CHANGED");
                if (token != null && (!token.equals(authorizedToken) || !"luma".equals(authorizedTarget))) throw NativeBackend.fail("SOURCE_CHANGED");
                boolean declared = false; JSONArray catalog = remote.optJSONArray("catalog");
                if (catalog != null) for (int i = 0; i < catalog.length(); i++) {
                    JSONObject entry = catalog.optJSONObject(i);
                    if (entry != null && original.equals(entry.optString("url"))) { declared = true; break; }
                }
                if (!declared) throw NativeBackend.fail("INVALID_SUBTITLE");
                peer = connection.optString("host");
                if ("kodi.prepareSubtitle".equals(method)) {
                    if (!subtitleSource.isEmpty() && !source.equals(subtitleSource)) clearSubtitleDelivery();
                    subtitleSource = source;
                }
                subtitleEpoch = subtitleGeneration.get();
            }
            java.util.function.BooleanSupplier current = () -> !destroyed && session == generation && subtitleEpoch == subtitleGeneration.get() && revision.equals(mediaRevision)
                    && (token == null || token.equals(authorizedToken));
            if ("kodi.subtitleDelivery".equals(method)) {
                String id = args.optString("preparedId");
                subtitleDelivery.resolve(id, source, original, peer);
                if (!current.getAsBoolean()) throw NativeBackend.fail("SOURCE_CHANGED");
                if (callback != null) callback.success(object("downloaded", subtitleDelivery.downloaded(id)));
                return;
            }
            // A slow public subtitle endpoint must not hold the Kodi control
            // queue or consume its five-second RPC budget.
            try {
                subtitleNetwork.execute(() -> {
                    try {
                        SubtitleDelivery.Prepared prepared = subtitleDelivery.prepare(source, original, peer, current);
                        main.post(() -> {
                            if (!current.getAsBoolean() || !"luma".equals(selectedTarget)) { reject(callback, "SOURCE_CHANGED", "The selected video changed."); return; }
                            if (callback != null) callback.success(object("preparedId", prepared.id, "format", prepared.format,
                                    "encoding", prepared.encoding, "cueCount", prepared.cueCount, "discardedCueCount", prepared.discardedCueCount));
                        });
                    } catch (SubtitleDelivery.DeliveryException error) {
                        NativeBackend.BackendException failure = NativeBackend.fail(subtitleFailure(error.code));
                        main.post(() -> reject(callback, failure.code, failure.getMessage()));
                    }
                });
            } catch (RejectedExecutionException ignored) { throw NativeBackend.fail("BUSY"); }
        } catch (NativeBackend.BackendException error) { reject(callback, error.code, error.getMessage()); }
        catch (SubtitleDelivery.DeliveryException error) {
            NativeBackend.BackendException failure = NativeBackend.fail(subtitleFailure(error.code)); reject(callback, failure.code, failure.getMessage());
        }
    }
    private boolean enqueue(NetworkAction action, Callback callback) {
        return enqueue(action, callback, false);
    }
    private boolean enqueue(NetworkAction action, Callback callback, boolean userMediaAction) {
        if (backend == null) backend = newBackend();
        Work work = new Work(action, callback, userMediaAction); pending.add(work);
        try { network.execute(work); return true; }
        catch (RejectedExecutionException ignored) { work.releaseWakeLock(); pending.remove(work); reject(callback, "BUSY", "Please wait for the current controls to finish."); return false; }
    }
    /** Local projection shared by UI and system buttons. No network work per tap. */
    private void queueVolume(JSONObject args, Callback callback) {
        try {
            JSONObject projected;
            synchronized (stateLock) {
                JSONObject connection = state.optJSONObject("connection"), volume = state.optJSONObject("volume");
                if (!"luma".equals(selectedTarget) || connection == null || !connection.optBoolean("connected")) throw NativeBackend.fail("NOT_CONNECTED");
                if (volume == null || volume.optInt("max") <= 0) throw NativeBackend.fail("INVALID_RESPONSE");
                Object raw = args.opt("value");
                if (!(raw instanceof Number)) throw NativeBackend.fail("INVALID_INPUT");
                double value = ((Number) raw).doubleValue(); int maximum = volume.optInt("max");
                if (!Double.isFinite(value) || value != Math.floor(value) || value < 0 || value > maximum) throw NativeBackend.fail("INVALID_INPUT");
                String source = args.has("expectedSourceUrl") ? NativeBackend.publicUrl(args.opt("expectedSourceUrl")) : "";
                if (!source.isEmpty() && (!source.equals(rememberedSource()) || !matches(state.optJSONObject("remote"), state.optJSONObject("playback")))) throw NativeBackend.fail("SOURCE_CHANGED");
                if (queuedVolume == null && (int) value == volume.optInt("current")) {
                    if (callback != null) callback.success(object("volume", copy(volume), "volumePending", false, "queued", false));
                    return;
                }
                if (queuedVolume == null) { volumeSource = source; volumeRevision = mediaRevision; }
                else if (!source.isEmpty()) volumeSource = source;
                queuedVolume = (int) value; volumeVersion++; volumeDue = SystemClock.elapsedRealtime() + VOLUME_COOLDOWN;
                projected = object("current", queuedVolume, "max", maximum, "pending", true);
                put(state, "volume", projected); put(state, "volumePending", true);
                if (volumeWakeLock == null) {
                    volumeWakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LumaRemote:VolumeCooldown");
                    volumeWakeLock.setReferenceCounted(false);
                }
                volumeWakeLock.acquire(30000);
            }
            main.removeCallbacks(volumeFlush); main.postDelayed(volumeFlush, VOLUME_COOLDOWN); publish();
            if (callback != null) callback.success(object("volume", copy(projected), "volumePending", true, "queued", true));
        } catch (NativeBackend.BackendException failure) { reject(callback, failure.code, failure.getMessage()); }
    }
    private boolean currentVolumeWrite(String token) {
        synchronized (stateLock) {
            return !destroyed && token.equals(volumeWriteToken) && queuedVolume != null && "luma".equals(selectedTarget)
                    && volumeWriteGeneration == generation && volumeWriteEpoch == volumeEpoch && volumeWriteVersion == volumeVersion
                    && volumeRevision.equals(mediaRevision);
        }
    }
    private void observeVolume(JSONObject observed) {
        synchronized (stateLock) {
            if (observed == null) return;
            confirmedVolume = object("current", observed.optInt("current"), "max", observed.optInt("max"));
            JSONObject projected = state.optJSONObject("volume");
            if (queuedVolume != null && projected != null && projected.optInt("max") != confirmedVolume.optInt("max")) cancelQueuedVolume();
            if (queuedVolume == null) { put(state, "volume", copy(confirmedVolume)); put(state, "volumePending", false); }
        }
    }
    private void cancelQueuedVolume() {
        synchronized (stateLock) {
            volumeEpoch++; queuedVolume = null; volumeSource = ""; volumeRevision = "";
            volumeWriteToken = null; volumeInFlight = false;
            if (confirmedVolume != null) put(state, "volume", copy(confirmedVolume));
            put(state, "volumePending", false);
            if (volumeWakeLock != null && volumeWakeLock.isHeld()) volumeWakeLock.release();
        }
        main.removeCallbacks(volumeFlush);
    }
    private void flushQueuedVolume() {
        final int value; final long version, epoch; final String source, token;
        synchronized (stateLock) {
            if (destroyed || queuedVolume == null || volumeInFlight) return;
            if (!"luma".equals(selectedTarget) || !volumeRevision.equals(mediaRevision)) { cancelQueuedVolume(); publish(); return; }
            long remaining = volumeDue - SystemClock.elapsedRealtime();
            if (remaining > 0) { main.postDelayed(volumeFlush, remaining); return; }
            value = queuedVolume; version = volumeVersion; epoch = volumeEpoch; source = volumeSource;
            token = UUID.randomUUID().toString(); volumeWriteToken = token; volumeInFlight = true;
            volumeWriteVersion = version; volumeWriteEpoch = epoch; volumeWriteGeneration = generation;
        }
        boolean accepted = enqueue(work -> {
            JSONObject observed = null; NativeBackend.BackendException error = null;
            try {
                if (work.current() && currentVolumeWrite(token)) {
                    JSONObject args = object("value", value, "sourceToken", token);
                    if (!source.isEmpty()) put(args, "expectedSourceUrl", source);
                    observed = work.transport.handle("luma.setVolume", args);
                }
            } catch (NativeBackend.BackendException failure) { error = failure; }
            finally {
                JSONObject result = observed; NativeBackend.BackendException failure = error;
                JSONObject connection = work.transport.connectionSnapshot();
                main.post(() -> finishQueuedVolume(token, version, epoch, result, failure, connection));
            }
        }, null, true);
        if (!accepted) finishQueuedVolume(token, version, epoch, null, NativeBackend.fail("CONNECTION_FAILED"), null);
    }
    private void finishQueuedVolume(String token, long version, long epoch, JSONObject result, NativeBackend.BackendException failure, JSONObject connection) {
        synchronized (stateLock) {
            if (destroyed || !token.equals(volumeWriteToken)) return;
            volumeInFlight = false; volumeWriteToken = null;
            if (connection != null) put(state, "connection", copy(connection));
            if (result != null) observeVolume(result.optJSONObject("volume"));
            if (volumeEpoch == epoch && version == volumeVersion) {
                queuedVolume = null; volumeSource = ""; volumeRevision = "";
                if (confirmedVolume != null) put(state, "volume", copy(confirmedVolume));
                put(state, "volumePending", false);
                if (volumeWakeLock != null && volumeWakeLock.isHeld()) volumeWakeLock.release();
                if (failure != null) { observeKodiFailure(failure); notice = failure.getMessage(); }
            } else if (volumeEpoch == epoch && queuedVolume != null) {
                main.removeCallbacks(volumeFlush); main.postDelayed(volumeFlush, Math.max(0, volumeDue - SystemClock.elapsedRealtime()));
            }
        }
        publish();
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
                    cancelQueuedVolume(); confirmedVolume = null; state.remove("volume");
                    state.remove("remote"); state.remove("playback");
                }
                put(state, "connection", connection);
                put(state, "settings", settings.metadata());
                if ("kodi.status".equals(method)) { put(state, "playback", copy(result)); adoptObservedPlayback(result); }
                if (("luma.status".equals(method) || "luma.setVolume".equals(method)) && result.optJSONObject("volume") != null) {
                    observeVolume(result.optJSONObject("volume")); put(result, "volume", copy(state.optJSONObject("volume"))); put(result, "volumePending", queuedVolume != null);
                }
                if ("kodi.getSubtitles".equals(method)) observeSubtitles(result);
                if ("app.settings.reset".equals(method)) state.remove("playback");
            }
            work.post(() -> {
                if ("luma.status".equals(method) || "luma.setVolume".equals(method)) {
                    synchronized (stateLock) { put(result, "volume", copy(state.optJSONObject("volume"))); put(result, "volumePending", queuedVolume != null); }
                }
                if ("luma.powerOff".equals(method)) {
                    work.complete(result, null, null);
                    disconnect(null);
                    notice = "Power command sent"; publish();
                    return;
                }
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
                if (method != null && method.startsWith("kodi.") && !method.equals("kodi.configure")) observeKodiFailure(failure);
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
    private void disconnectSelected() {
        if ("philips".equals(selectedTarget) && matter.snapshot().optBoolean("paired")) request("matter.disconnect", new JSONObject(), null);
        request("philips".equals(selectedTarget) ? "msx.disconnect" : "device.disconnect", new JSONObject(), null);
    }
    private void requestMatter(String method, JSONObject args, Callback callback) {
        if (!java.util.Arrays.asList("matter.status", "matter.pair", "matter.key", "matter.volume", "matter.input",
                "matter.powerOff", "matter.powerOn", "matter.openWindow", "matter.forget", "matter.disconnect").contains(method)) {
            reject(callback, "INVALID_REQUEST", "Unknown TV control."); return;
        }
        boolean needsConnection = "matter.pair".equals(method) || !"matter.disconnect".equals(method) && matter.snapshot().optBoolean("paired") && (!matter.snapshot().optBoolean("paused") || args.optBoolean("reconnect"));
        if (needsConnection) {
            if ("philips".equals(selectedTarget)) notice = "Connecting to TV…";
            startRequested = true;
            try {
                Intent start = new Intent(this, ConnectionService.class).setAction(START);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(start); else startService(start);
                promote();
            } catch (RuntimeException unavailable) {
                reject(callback, "BACKGROUND_START", "Open the app to connect to the TV."); return;
            }
        }
        matter.request(method, args, new MatterControllerAdapter.Callback() {
            @Override public void success(JSONObject result) {
                if (destroyed) return;
                if ("philips".equals(selectedTarget)) notice = matter.snapshot().optBoolean("connected") ? "TV remote connected" : "Reconnecting to TV…";
                updateTelevisionState(); publish();
                scheduleMatterPoll();
                if (!connected() && (!matter.snapshot().optBoolean("paired") || matter.snapshot().optBoolean("paused"))) demote();
                if (callback != null) callback.success(result);
            }
            @Override public void failure(String code, String message) {
                if (destroyed) return;
                updateTelevisionState(); publish();
                scheduleMatterPoll();
                if (!connected() && (!matter.snapshot().optBoolean("paired") || matter.snapshot().optBoolean("paused"))) demote();
                reject(callback, code, message);
            }
        });
    }
    private final Runnable matterPoll = () -> {
        if (destroyed || matterPollQueued || !foreground || !matter.snapshot().optBoolean("paired") || matter.snapshot().optBoolean("pairing") || matter.snapshot().optBoolean("paused")) return;
        matterPollQueued = true;
        request("matter.status", object("background", true), new Callback() {
            @Override public void success(JSONObject result) { matterPollQueued = false; scheduleMatterPoll(); }
            @Override public void failure(String code, String message) { matterPollQueued = false; scheduleMatterPoll(); }
        });
    };
    private void scheduleMatterPoll() {
        main.removeCallbacks(matterPoll);
        if (!destroyed && foreground && matter.snapshot().optBoolean("paired") && !matter.snapshot().optBoolean("paused")) main.postDelayed(matterPoll, 15000);
    }
    private JSONObject televisionSnapshot() {
        JSONObject retained;
        synchronized (stateLock) { retained = copy(televisionMedia); }
        JSONObject current = withTextState(television.status());
        JSONObject playback = current.optJSONObject("playback");
        if (playback != null && playback.optBoolean("active") && retained != null && retained.optString("url").equals(playback.optString("source"))) {
            if (retained.optJSONObject("content") != null) put(playback, "content", retained.optJSONObject("content"));
            if (retained.has("nextEpisode")) put(playback, "nextEpisode", retained.opt("nextEpisode"));
        }
        if (matter != null) put(current, "matter", matter.snapshot());
        return current;
    }
    private JSONObject withTextState(JSONObject current) {
        String browserUrl = "";
        if (current.optBoolean("running")) {
            try { browserUrl = PhilipsTextController.browserSetupUrl(current.optString("startupUrl")); }
            catch (PhilipsTextController.TextException invalid) { /* No setup URL without a valid current native origin. */ }
        }
        put(current, "browserSetupUrl", browserUrl);
        if (televisionText == null) return current;
        JSONObject typing = televisionText.status(matter == null ? "" : matter.currentPrivateAddress());
        for (java.util.Iterator<String> keys = typing.keys(); keys.hasNext();) { String key = keys.next(); put(current, key, typing.opt(key)); }
        if (textRequestPending) put(current, "typingBusy", true);
        return current;
    }
    private void cancelTelevisionText() { textGeneration++; if (televisionText != null) televisionText.cancel(); }
    private void requestTelevisionText(String method, JSONObject args, Callback callback) {
        boolean grant = "msx.textPairGrant".equals(method);
        if (!"philips".equals(selectedTarget) || (grant ? args.length() != 1 || !(args.opt("pin") instanceof String) || !args.optString("pin").matches("[0-9]{4}") : args.length() != 0)) {
            reject(callback, grant ? "TEXT_INVALID_PIN" : "TEXT_INVALID_INPUT", "Check the TV text setup details."); return;
        }
        if (textRequestPending) { reject(callback, "TEXT_BUSY", "Wait for the current text request."); return; }
        if (!television.status().optBoolean("running")) { reject(callback, "MSX_NOT_CONNECTED", "Start TV setup first."); return; }
        textRequestPending = true; updateTelevisionState(); publish();
        final long revision = televisionGeneration;
        final long textRevision = textGeneration;
        matter.resolvePrivateAddress(new MatterControllerAdapter.Callback() {
            public void failure(String code, String message) { textRequestPending = false; updateTelevisionState(); publish(); reject(callback, "TEXT_ADDRESS_UNAVAILABLE", "Reconnect the paired TV before text setup."); }
            public void success(JSONObject address) {
                if (destroyed || revision != televisionGeneration || textRevision != textGeneration || !"philips".equals(selectedTarget)) { textRequestPending = false; updateTelevisionState(); publish(); reject(callback, "TEXT_CANCELLED", "TV text setup changed."); return; }
                final String host = address.optString("host");
                try {
                    textNetwork.execute(() -> {
                        JSONObject result = null; String error = null;
                        try {
                            if (destroyed || revision != televisionGeneration || textRevision != textGeneration || !"philips".equals(selectedTarget)) throw new PhilipsTextController.TextException("TEXT_CANCELLED");
                            if ("msx.textPairRequest".equals(method)) result = televisionText.pairRequest(host);
                            else if (grant) televisionText.pairGrant(host, args.optString("pin"));
                            else if ("msx.launchBrowser".equals(method)) result = televisionText.launchBrowser(host, television.status().optString("startupUrl"));
                            else if ("msx.typeBrowserAddress".equals(method)) result = televisionText.typeBrowserAddress(host, television.status().optString("startupUrl"));
                            else result = televisionText.typeAddress(host, television.status().optString("startupUrl"));
                        } catch (PhilipsTextController.TextException failure) { error = failure.code; }
                        catch (Exception invalid) { error = "TEXT_REJECTED"; }
                        final JSONObject reply = result; final String failure = error;
                        main.post(() -> {
                            textRequestPending = false;
                            if (destroyed || revision != televisionGeneration || textRevision != textGeneration) { updateTelevisionState(); publish(); reject(callback, "TEXT_CANCELLED", "TV text setup changed."); return; }
                            updateTelevisionState(); publish();
                            if (failure != null) reject(callback, failure, "msx.launchBrowser".equals(method)
                                    ? "Check the TV and receiver status before requesting another browser launch."
                                    : "Check TV text setup and its focused field before trying again.");
                            else if (callback != null) {
                                JSONObject snapshot = television.status();
                                if (reply != null) for (java.util.Iterator<String> keys = reply.keys(); keys.hasNext();) { String key = keys.next(); put(snapshot, key, reply.opt(key)); }
                                callback.success(withTextState(snapshot));
                            }
                        });
                    });
                } catch (RejectedExecutionException busy) { textRequestPending = false; updateTelevisionState(); publish(); reject(callback, "TEXT_BUSY", "Wait for the current text request."); }
            }
        });
    }
    private void requestTelevision(String method, JSONObject args, Callback callback) {
        if (java.util.Arrays.asList("msx.textPairRequest", "msx.textPairGrant", "msx.typeAddress", "msx.typeBrowserAddress", "msx.launchBrowser").contains(method)) { requestTelevisionText(method, args, callback); return; }
        if (!java.util.Arrays.asList("msx.start", "msx.status", "msx.confirm", "msx.open", "msx.sample", "msx.playPause",
                "msx.seek", "msx.volume", "msx.subtitle", "msx.stop", "msx.disconnect", "msx.reset", "msx.debugPing", "msx.debugLabel", "msx.reloadReceiver").contains(method)) {
            reject(callback, "INVALID_REQUEST", "Unknown television control."); return;
        }
        final boolean debugControl = "msx.debugPing".equals(method) || "msx.debugLabel".equals(method) || "msx.reloadReceiver".equals(method);
        if (debugControl && (args.length() != 0 || !"philips".equals(selectedTarget))) {
            reject(callback, "INVALID_REQUEST", "Select Philips TV and use the fixed receiver control."); return;
        }
        if ("msx.reloadReceiver".equals(method)) {
            JSONObject current = television.status();
            JSONObject observed = current.optJSONObject("playback");
            if ((observed != null && observed.optBoolean("active")) || current.has("pendingCommand")
                    || televisionNetwork.getActiveCount() != 0 || !televisionNetwork.getQueue().isEmpty()) {
                reject(callback, "RELOAD_BLOCKED", "Wait until playback and pending receiver commands finish."); return;
            }
        }
        if ("msx.sample".equals(method) && (args.length() != 0 || !"philips".equals(selectedTarget))) {
            reject(callback, "INVALID_REQUEST", "Select Philips TV and use the fixed playback test."); return;
        }
        // Reads and disconnect must remain available while a slow TV Open awaits
        // its receipt. They never share Kodi's queue or wait behind TV playback.
        if ("msx.status".equals(method)) {
            updateTelevisionState();
            if (callback != null) callback.success(withTextState(television.status()));
            return;
        }
        if ("msx.disconnect".equals(method)) {
            televisionGeneration++;
            cancelTelevisionText();
            television.disconnect(); televisionRunning = false;
            televisionNetwork.getQueue().clear();
            cancelQueuedVolume();
            mediaRevision = UUID.randomUUID().toString();
            updateTelevisionState(); publish();
            if (!connected()) demote();
            if (callback != null) callback.success(withTextState(television.status()));
            return;
        }
        if ("msx.start".equals(method)) {
            televisionStarting = true;
            startRequested = true;
            try {
                Intent start = new Intent(this, ConnectionService.class).setAction(START);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(start); else startService(start);
                notice = "Open the LUMA receiver page on Philips TV";
                promote();
            } catch (RuntimeException ignored) { televisionStarting = false; reject(callback, "BACKGROUND_START", "Open the app to connect the TV."); return; }
        }
        if ("msx.reset".equals(method)) cancelTelevisionText();
        if ("msx.open".equals(method)) {
            String token = args.optString("sourceToken");
            if (token.isEmpty() || !token.equals(authorizedToken)) { reject(callback, "SOURCE_CHANGED", "Choose the video again."); return; }
            cancelQueuedVolume();
            mediaRevision = UUID.randomUUID().toString();
        }
        if ("msx.sample".equals(method)) { cancelQueuedVolume(); mediaRevision = UUID.randomUUID().toString(); }
        final long session = televisionGeneration;
        final String debugRevision = mediaRevision;
        try {
            televisionNetwork.execute(() -> {
                JSONObject result = null; String code = null;
                try {
                    if (destroyed || session != televisionGeneration) throw new IllegalStateException();
                    if (debugControl && (!"philips".equals(selectedTarget) || !debugRevision.equals(mediaRevision))) throw new MsxServer.MsxException("SOURCE_CHANGED");
                    if ("msx.start".equals(method)) result = television.start();
                    else if ("msx.status".equals(method)) result = television.status();
                    else if ("msx.confirm".equals(method)) result = television.confirmPairing(args.optString("host"));
                    else if ("msx.disconnect".equals(method)) { television.disconnect(); result = television.status(); }
                    else if ("msx.reset".equals(method)) { television.reset(); result = television.status(); }
                    else if ("msx.sample".equals(method)) result = television.sample();
                    else if ("msx.debugPing".equals(method)) result = television.command("ping", new JSONObject());
                    else if ("msx.debugLabel".equals(method)) result = television.command("set-label", object("label", "LUMA debug channel ready"));
                    else if ("msx.reloadReceiver".equals(method)) result = television.command("reload", new JSONObject());
                    else result = television.command(method.substring(4), args);
                } catch (MsxServer.MsxException failure) { code = failure.code; }
                catch (Exception ignored) { code = "MSX_UNAVAILABLE"; }
                final JSONObject response = result; final String error = code;
                main.post(() -> {
                    if (destroyed || session != televisionGeneration) { reject(callback, "NOT_CONNECTED", "The TV connection changed."); return; }
                    if ("msx.start".equals(method)) { televisionStarting = false; televisionRunning = error == null; }
                    if ("msx.disconnect".equals(method) || "msx.reset".equals(method)) {
                        televisionRunning = false; televisionGeneration++;
                        cancelQueuedVolume();
                        mediaRevision = UUID.randomUUID().toString();
                    }
                    updateTelevisionState();
                    publish();
                    if (televisionRunning) scheduleTelevisionPoll();
                    else if (!connected()) demote();
                    if (error == null) { if (callback != null) callback.success(withTextState(response == null ? television.status() : response)); }
                    else reject(callback, error, "Could not complete the TV control. Check the LUMA receiver page and try again.");
                });
            });
        } catch (RejectedExecutionException ignored) {
            if ("msx.start".equals(method)) { televisionStarting = false; if (!connected()) demote(); }
            reject(callback, "BUSY", "Wait for the current TV control to finish.");
        }
    }
    private void updateTelevisionState() {
        JSONObject current = televisionSnapshot(), playback = current.optJSONObject("playback");
        if (watchHistory != null && playback != null && current.optBoolean("connected")) watchHistory.observe("philips", playback);
        String source = playback != null && playback.optBoolean("active") ? playback.optString("source") : "";
        if (!source.equals(televisionSource)) {
            televisionSource = source;
            if ("philips".equals(selectedTarget)) mediaRevision = UUID.randomUUID().toString();
        }
        synchronized (stateLock) { put(state, "receivers", object("philips", current)); }
    }
    private final Runnable televisionPoll = () -> {
        if (destroyed || !televisionRunning) return;
        updateTelevisionState(); publish(); scheduleTelevisionPoll();
    };
    private void scheduleTelevisionPoll() {
        main.removeCallbacks(televisionPoll);
        if (televisionRunning && !destroyed) main.postDelayed(televisionPoll, 2500);
    }
    /** A target switch changes the displayed system remote, never either transport. */
    private JSONObject presentationSnapshot() {
        JSONObject all = snapshot();
        if (!"philips".equals(selectedTarget)) return all;
        JSONObject tv = televisionSnapshot(), observed = tv.optJSONObject("playback"), hardware = tv.optJSONObject("matter");
        boolean ready = tv.optBoolean("connected") || hardware != null && hardware.optBoolean("connected");
        put(all, "connection", object("connected", ready, "configured", ready, "name", "Philips TV"));
        if (observed == null) { all.remove("playback"); all.remove("remote"); all.remove("volume"); if (hardware != null && hardware.optBoolean("connected")) put(all, "volume", hardware.optJSONObject("volume")); return all; }
        JSONObject playback = copy(observed);
        put(playback, "sourceUrl", observed.optString("source"));
        put(playback, "playing", !observed.optBoolean("paused", true));
        put(all, "playback", playback);
        JSONObject media = object("url", observed.optString("source"), "title", observed.optString("title", "TV video"));
        if (observed.optJSONObject("content") != null) put(media, "content", observed.optJSONObject("content"));
        JSONObject projected = object("media", media, "tracks", observed.optJSONArray("tracks"), "catalog", observed.optJSONArray("tracks"));
        if (observed.has("nextEpisode")) put(projected, "nextEpisode", observed.opt("nextEpisode"));
        put(all, "remote", projected);
        put(all, "volume", observed.optJSONObject("volume"));
        if (hardware != null && hardware.optBoolean("connected") && hardware.optJSONObject("volume") != null) put(all, "volume", hardware.optJSONObject("volume"));
        return all;
    }
    private void televisionMediaAction(String method) {
        final String revision = mediaRevision;
        if ("volume-down".equals(method) || "volume-up".equals(method)) {
            JSONObject hardware = matter.snapshot(), volume = hardware.optJSONObject("volume");
            if (hardware.optBoolean("connected") && volume != null) {
                int current = volume.optInt("current"), min = volume.optInt("min", 0), max = volume.optInt("max", 254);
                int next = Math.max(min, Math.min(max, current + ("volume-up".equals(method) ? 1 : -1)));
                if (next != current) request("matter.volume", object("value", next), null);
                return;
            }
        }
        JSONObject current = television.status(), playback = current.optJSONObject("playback");
        if (!current.optBoolean("connected") || playback == null || !playback.optBoolean("active")) return;
        final String source = playback.optString("source");
        String action; JSONObject args = object("expectedSourceUrl", source);
        if ("kodi.resume".equals(method) || "kodi.pause".equals(method)) {
            action = "playPause"; put(args, "play", "kodi.resume".equals(method));
        } else if ("kodi.stop".equals(method)) action = "stop";
        else if ("volume-down".equals(method) || "volume-up".equals(method)) {
            JSONObject volume = playback.optJSONObject("volume"); if (volume == null) return;
            action = "volume"; put(args, "value", Math.max(0, Math.min(100, volume.optInt("current") + ("volume-up".equals(method) ? 5 : -5))));
        } else return;
        final String command = action;
        try {
            televisionNetwork.execute(() -> {
                if (destroyed || !"philips".equals(selectedTarget) || !revision.equals(mediaRevision)) return;
                try { television.command(command, args); }
                catch (Exception ignored) { /* Fresh state removes controls when the TV is unavailable. */ }
                main.post(() -> { if (!destroyed) { updateTelevisionState(); publish(); } });
            });
        } catch (RejectedExecutionException ignored) { }
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
        String token = authorizedToken;
        JSONObject remote;
        try { remote = remoteData(media, result, media == null ? null : media.optJSONArray("tracks")); }
        catch (NativeBackend.BackendException ignored) { return false; }
        main.post(() -> {
            if (destroyed || session != generation || !java.util.Objects.equals(token, authorizedToken) || !configured()) return;
            synchronized (stateLock) {
                JSONObject mediaData = remote.optJSONObject("media"), prepared = preparedRemote == null ? null : preparedRemote.optJSONObject("media");
                boolean completingPrepared = token != null && token.equals(preparedToken) && preparedGeneration == generation
                        && prepared != null && mediaData != null && prepared.optString("url").equals(mediaData.optString("url"))
                        && mediaData.optString("url").equals(rememberedSource());
                if (!completingPrepared) { cancelQueuedVolume(); mediaRevision = UUID.randomUUID().toString(); }
                put(state, "remote", remote);
            }
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
            if (index instanceof Integer && item.has("preparedId")) put(catalog.get(link), "preparedId", preparedSubtitleId(item));
        }
        Set<String> seen = new HashSet<>();
        if (bindings != null) for (int i = 0; i < bindings.length(); i++) {
            JSONObject item = bindings.optJSONObject(i); if (item == null) throw NativeBackend.fail("INVALID_SUBTITLE");
            String link = NativeBackend.publicUrl(item.opt("url"));
            if (!catalog.containsKey(link) || !seen.add(link)) throw NativeBackend.fail("INVALID_SUBTITLE");
            Object index = subtitleIndex(item.opt("index"));
            if (index instanceof Integer && !indices.contains((Integer) index)) throw NativeBackend.fail("INVALID_SUBTITLE");
            put(catalog.get(link), "index", index);
            catalog.get(link).remove("preparedId");
            if (index instanceof Integer && item.has("preparedId")) put(catalog.get(link), "preparedId", preparedSubtitleId(item));
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
        JSONObject safeMedia = object("url", url, "contentType", clean(media.optString("contentType"), 100), "title", clean(media.optString("title"), 200));
        JSONObject content = validatedContent(media); if (content != null) put(safeMedia, "content", content);
        JSONObject normalized = object("media", safeMedia,
                "tracks", tracks, "catalog", entries, "selectedIndex", selected, "subtitlesEnabled", enabled);
        if (normalized.toString().getBytes(StandardCharsets.UTF_8).length > 192 * 1024) throw NativeBackend.fail("INVALID_SUBTITLE");
        return normalized;
    }
    private static JSONObject validatedContent(JSONObject media) {
        JSONObject content = media == null ? null : media.optJSONObject("content");
        if (content == null) return null;
        JSONObject safe = WatchHistory.contentFromCatalog(content.optString("kind"), content.optString("contentId"),
                "tv".equals(content.optString("kind")) ? Integer.valueOf(content.optInt("season", -1)) : null,
                "tv".equals(content.optString("kind")) ? Integer.valueOf(content.optInt("episode", -1)) : null, content.optString("title"));
        return safe != null && safe.optString("key").equals(content.optString("key"))
                && safe.optString("watchUrl").equals(content.optString("watchUrl")) ? safe : null;
    }
    private static JSONObject systemMedia(JSONObject media, JSONObject content) {
        JSONObject safe = object("url", media.optString("url"), "contentType", clean(media.optString("contentType"), 100), "title", clean(media.optString("title"), 200));
        if (content != null) put(safe, "content", content);
        return safe;
    }
    private static String preparedSubtitleId(JSONObject item) throws NativeBackend.BackendException {
        Object id = item.opt("preparedId");
        if (!(id instanceof String) || !((String) id).matches("[A-Za-z0-9_-]{16,128}")) throw NativeBackend.fail("INVALID_SUBTITLE");
        return (String) id;
    }
    private void observeSubtitles(JSONObject observed) {
        JSONObject remote = state.optJSONObject("remote"), playback = state.optJSONObject("playback");
        if (remote == null || playback == null || !matches(remote, playback)) return;
        put(remote, "selectedIndex", observed.opt("currentIndex"));
        put(remote, "subtitlesEnabled", observed.optBoolean("enabled"));
    }
    /** A failed HTTP read says nothing about whether receiver playback stopped. */
    private void observeKodiFailure(NativeBackend.BackendException failure) {
        synchronized (stateLock) {
            if ("KODI_AUTH".equals(failure.code) || "KODI_CONFIG".equals(failure.code)
                    || "NO_VIDEO".equals(failure.code) || "SOURCE_CHANGED".equals(failure.code)) {
                clearSubtitleDelivery();
                state.remove("playback");
            } else if ("KODI_UNAVAILABLE".equals(failure.code) || "KODI_RESPONSE".equals(failure.code)) {
                JSONObject playback = state.optJSONObject("playback");
                if (matches(state.optJSONObject("remote"), playback)) {
                    put(playback, "stale", true); put(playback, "errorCode", failure.code);
                }
            }
        }
    }
    /** Retry only a fast failed observation, never a write or a full timeout. */
    private JSONObject readMediaPlayback(Work work) throws NativeBackend.BackendException {
        long started = SystemClock.elapsedRealtime();
        try { return work.transport.handle("kodi.status", new JSONObject()); }
        catch (NativeBackend.BackendException failure) {
            if (!work.current() || SystemClock.elapsedRealtime() - started >= 1000
                    || !("KODI_UNAVAILABLE".equals(failure.code) || "KODI_RESPONSE".equals(failure.code))) throw failure;
            SystemClock.sleep(150);
            if (!work.current()) throw NativeBackend.fail("NOT_CONNECTED");
            return work.transport.handle("kodi.status", new JSONObject());
        }
    }
    private boolean connected() { return snapshot().optJSONObject("connection").optBoolean("connected"); }
    private boolean configured() { return snapshot().optJSONObject("connection").optBoolean("configured"); }
    private boolean hasKodiPlayback() {
        JSONObject all = snapshot(), connection = all.optJSONObject("connection");
        return connection != null && connection.optBoolean("configured") && matches(all.optJSONObject("remote"), all.optJSONObject("playback"));
    }
    private boolean needsKodiPolling() { return connected() || hasKodiPlayback(); }
    /** A fresh service may attach controls to video already playing on Kodi.
     * A remembered source or an in-progress handoff always keeps its own scope. */
    private void adoptObservedPlayback(JSONObject current) {
        if (watchHistory != null) watchHistory.observe("luma", current);
        observeSubtitleSource(current);
        synchronized (stateLock) {
            JSONObject connection = state.optJSONObject("connection");
            if (authorizedToken != null || state.optJSONObject("remote") != null || connection == null
                    || !connection.optBoolean("connected") || !connection.optBoolean("configured") || !current.optBoolean("active")) return;
            String source;
            try { source = NativeBackend.publicUrl(current.opt("sourceUrl")); }
            catch (NativeBackend.BackendException ignored) { return; }
            put(state, "remote", object("media", object("url", source, "contentType", "", "title", "Kodi video"),
                    "tracks", new JSONArray(), "catalog", new JSONArray(), "selectedIndex", JSONObject.NULL, "subtitlesEnabled", false));
            cancelQueuedVolume();
            mediaRevision = UUID.randomUUID().toString();
        }
    }
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
        if ("philips".equals(selectedTarget)) { televisionMediaAction(method); return; }
        String expected = rememberedSource();
        String revision = mediaRevision;
        if (expected.isEmpty() || !configured()) return;
        if ("volume-down".equals(method) || "volume-up".equals(method)) {
            JSONObject volume = snapshot().optJSONObject("volume");
            if (volume != null && volume.optInt("max") > 0) queueVolume(object("value", Math.max(0, Math.min(volume.optInt("max"),
                    volume.optInt("current") + ("volume-up".equals(method) ? 1 : -1))), "expectedSourceUrl", expected), null);
            return;
        }
        if ("mute".equals(method)) cancelQueuedVolume();
        if ((method.startsWith("volume-") || method.equals("mute")) && !connected()) return;
        enqueue(work -> {
            try {
                JSONObject current = readMediaPlayback(work);
                synchronized (stateLock) { if (!work.current()) return; put(state, "playback", current); }
                if (!revision.equals(mediaRevision) || !expected.equals(rememberedSource()) || !current.optBoolean("active") || !expected.equals(current.optString("sourceUrl"))) {
                    work.post(() -> { notice = "Kodi is playing a different video. Open the app to choose it."; publish(); }); return;
                }
                JSONObject args = object("expectedSourceUrl", expected);
                JSONObject projector = null;
                if (method.startsWith("volume-") || method.equals("mute")) {
                    put(args, "key", method); work.transport.handle("luma.key", args);
                    projector = work.transport.handle("luma.status", new JSONObject());
                } else work.transport.handle(method, args);
                JSONObject observed = readMediaPlayback(work);
                synchronized (stateLock) {
                    if (!work.current()) return;
                    put(state, "playback", observed); put(state, "connection", work.transport.connectionSnapshot());
                    if (projector != null && projector.optJSONObject("volume") != null) observeVolume(projector.optJSONObject("volume"));
                }
                work.post(this::publish);
            } catch (NativeBackend.BackendException failure) {
                if ("KODI_AUTH".equals(failure.code) && work.current()) { candidateKodi = null; candidateGeneration = -1; }
                synchronized (stateLock) { if (!work.current()) return; put(state, "connection", work.transport.connectionSnapshot()); observeKodiFailure(failure); }
                work.post(() -> { notice = failure.getMessage(); publish(); if (!connected()) demote(); else schedulePoll(1000); });
            }
        }, null, true);
    }
    private void openNextEpisode() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::openNextEpisode); return; }
        JSONObject all = presentationSnapshot(), remote = all.optJSONObject("remote");
        if (!verifiedPlayback(all) || remote == null || remote.optJSONObject("nextEpisode") == null) return;
        try {
            startActivity(new Intent(this, MainActivity.class).setAction(ACTION_PLAY_NEXT)
                    .putExtra("revision", mediaRevision)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } catch (RuntimeException ignored) { notice = "Open the app to play the next episode."; publish(); }
    }
    private final Runnable poll = () -> {
        if (destroyed || !foreground || !needsKodiPolling() || pollQueued) return;
        pollQueued = true;
        boolean queued = enqueue(work -> {
            try {
                if (configured()) {
                    try {
                        JSONObject current = work.transport.handle("kodi.status", new JSONObject());
                        synchronized (stateLock) { if (!work.current()) return; put(state, "playback", current); adoptObservedPlayback(current); }
                    } catch (NativeBackend.BackendException failure) {
                        if ("KODI_AUTH".equals(failure.code) && work.current()) { candidateKodi = null; candidateGeneration = -1; }
                        synchronized (stateLock) { if (!work.current()) return; observeKodiFailure(failure); }
                    }
                }
                if (!work.current()) return;
                long now = SystemClock.elapsedRealtime();
                if (connected() && (now - lastHeartbeat >= 15000 || lastHeartbeat == 0)) {
                    try {
                        JSONObject result = work.transport.handle("luma.status", new JSONObject());
                        synchronized (stateLock) { if (!work.current()) return; if (result.optJSONObject("volume") != null) observeVolume(result.optJSONObject("volume")); }
                    } catch (NativeBackend.BackendException failure) { work.post(() -> notice = failure.getMessage()); }
                    work.post(() -> lastHeartbeat = now);
                }
                synchronized (stateLock) { if (!work.current()) return; put(state, "connection", work.transport.connectionSnapshot()); }
            } finally {
                work.post(() -> { pollQueued = false; if (!needsKodiPolling()) demote(); publish(); schedulePoll(4000); });
            }
        }, null);
        if (!queued) { pollQueued = false; schedulePoll(4000); }
    };
    private void schedulePoll(long delay) {
        main.removeCallbacks(poll);
        if (foreground && needsKodiPolling()) main.postDelayed(poll, delay);
    }
    private void promote() {
        if (destroyed) return;
        if (!foreground) {
            JSONObject all; String revision;
            synchronized (stateLock) { all = presentationSnapshot(); revision = mediaRevision; }
            Notification notification = notification(false, all, revision);
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(NOTIFICATION, notification);
            foreground = true; lastNotificationPost = SystemClock.elapsedRealtime(); lastNotificationSignature = null;
        }
    }
    private void demote() {
        if (hasKodiPlayback()) { schedulePoll(1000); return; }
        if (televisionRunning || televisionStarting || matter != null && (matter.snapshot().optBoolean("paired") && !matter.snapshot().optBoolean("paused") || matter.snapshot().optBoolean("pairing"))) { main.removeCallbacks(poll); return; }
        main.removeCallbacks(poll);
        cancelNotificationFlush();
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
        JSONObject all = presentationSnapshot(), connection = all.optJSONObject("connection"), remote = all.optJSONObject("remote"), playback = all.optJSONObject("playback");
        boolean verified = verifiedPlayback(all);
        boolean hasNext = verified && remote != null && remote.optJSONObject("nextEpisode") != null;
        long actions = verified ? PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
                | (hasNext ? PlaybackState.ACTION_SKIP_TO_NEXT : 0) : 0;
        int status = verified ? (playback.optBoolean("playing") ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED) : PlaybackState.STATE_NONE;
        long position = verified ? Math.max(0, playback.optLong("positionSeconds")) * 1000 : 0;
        PlaybackState.Builder controls = new PlaybackState.Builder().setActions(actions).setState(status, position,
                verified && playback.optBoolean("playing") && !playback.optBoolean("stale") ? 1f : 0f, SystemClock.elapsedRealtime());
        if (verified) {
            Bundle revision = new Bundle(); revision.putString("revision", mediaRevision);
            if (connection.optBoolean("connected")) {
                controls.addCustomAction(new PlaybackState.CustomAction.Builder(VOLUME_DOWN, "Volume −", R.drawable.ic_volume_down).setExtras(revision).build());
                controls.addCustomAction(new PlaybackState.CustomAction.Builder(VOLUME_UP, "Volume +", R.drawable.ic_volume_up).setExtras(revision).build());
            }
            controls.addCustomAction(new PlaybackState.CustomAction.Builder(STOP, "Stop video", R.drawable.ic_stop).setExtras(revision).build());
            controls.addCustomAction(new PlaybackState.CustomAction.Builder(DISCONNECT, "Disconnect", R.drawable.ic_disconnect).setExtras(revision).build());
        }
        mediaSession.setPlaybackState(controls.build());
        String title = verified ? clean(remote.optJSONObject("media").optString("title"), 200) : "LUMA Remote";
        if (title.isEmpty()) title = "Kodi video";
        MediaMetadata.Builder metadata = new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, playbackSummary(all));
        if (verified) metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, playback.optLong("durationSeconds") * 1000);
        mediaSession.setMetadata(metadata.build());
        mediaSession.setActive(verified);
        int trackCount = remote == null || remote.optJSONArray("tracks") == null ? 0 : remote.optJSONArray("tracks").length();
        int catalogCount = remote == null || remote.optJSONArray("catalog") == null ? 0 : remote.optJSONArray("catalog").length();
        String presentation = "foreground=" + foreground + " connected=" + (connection != null && connection.optBoolean("connected"))
                + " verified=" + verified + " tracks=" + trackCount + " catalog=" + catalogCount + " sessionActive=" + mediaSession.isActive();
        if (!presentation.equals(lastPresentation)) { lastPresentation = presentation; Log.i("LumaControls", presentation); }
        JSONObject volume = all.optJSONObject("volume");
        if (volume != null && volume.optInt("max") > 0) volumeProvider.setCurrentVolume(Math.max(0, Math.min(30,
                (int) Math.round(volume.optDouble("current") * 30 / volume.optDouble("max")))));
        if (foreground) queueNotificationFlush();
        else getSystemService(NotificationManager.class).cancel(MEDIA_NOTIFICATION);
    }
    private String notificationSignature(JSONObject all, String revision) {
        JSONObject connection = all.optJSONObject("connection"), remote = all.optJSONObject("remote"), playback = all.optJSONObject("playback");
        boolean verified = verifiedPlayback(all);
        String title = verified ? clean(remote.optJSONObject("media").optString("title"), 200) : "LUMA Remote";
        if (title.isEmpty()) title = "Kodi video";
        return object("revision", revision, "verified", verified, "playing", playback != null && playback.optBoolean("playing"),
                "title", title, "projector", playbackSummary(all), "hardwareConnected", connection != null && connection.optBoolean("connected"),
                "hasNext", remote != null && remote.optJSONObject("nextEpisode") != null, "notice", clean(notice, 200), "foreground", foreground).toString();
    }
    private void queueNotificationFlush() {
        if (destroyed || !foreground || notificationFlushQueued) return;
        notificationFlushQueued = true;
        main.postDelayed(notificationFlush, Math.max(0, 1000 - (SystemClock.elapsedRealtime() - lastNotificationPost)));
    }
    private void flushNotifications() {
        notificationFlushQueued = false;
        if (destroyed || !foreground) return;
        JSONObject all; String revision;
        synchronized (stateLock) { all = presentationSnapshot(); revision = mediaRevision; }
        String signature = notificationSignature(all, revision);
        if (signature.equals(lastNotificationSignature)) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastNotificationPost < 1000) { queueNotificationFlush(); return; }
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.notify(NOTIFICATION, notification(false, all, revision));
        JSONObject connection = all.optJSONObject("connection");
        if (verifiedPlayback(all)) notifications.notify(MEDIA_NOTIFICATION, notification(true, all, revision));
        else notifications.cancel(MEDIA_NOTIFICATION);
        lastNotificationSignature = signature; lastNotificationPost = now;
    }
    private void cancelNotificationFlush() {
        main.removeCallbacks(notificationFlush); notificationFlushQueued = false; lastNotificationSignature = null;
    }
    private Notification notification(boolean mediaCard, JSONObject all, String revision) {
        JSONObject connection = all.optJSONObject("connection"), remote = all.optJSONObject("remote"), playback = all.optJSONObject("playback");
        boolean verified = verifiedPlayback(all);
        String title = verified ? clean(remote.optJSONObject("media").optString("title"), 200) : "LUMA Remote";
        if (title.isEmpty()) title = "Kodi video";
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        builder.setSmallIcon(R.drawable.ic_projector).setColor(getColor(R.color.projector_accent))
                .setContentTitle(title).setContentText(verified ? playbackSummary(all) : clean(notice, 200))
                .setContentIntent(sessionActivity).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
                .setCategory(mediaCard ? Notification.CATEGORY_TRANSPORT : Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PUBLIC);
        if (verified) {
            boolean playing = playback.optBoolean("playing");
            boolean hardwareConnected = connection.optBoolean("connected");
            if (hardwareConnected) builder.addAction(new Notification.Action.Builder(R.drawable.ic_volume_down, "Volume −", pending(VOLUME_DOWN, 4, revision)).build());
            builder.addAction(new Notification.Action.Builder(playing ? R.drawable.ic_pause : R.drawable.ic_play,
                    playing ? "Pause" : "Play", pending(playing ? PAUSE : PLAY, 1, revision)).build());
            boolean hasNext = remote != null && remote.optJSONObject("nextEpisode") != null;
            if (hasNext) builder.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next, "Next episode", pending(NEXT, 6, revision)).build());
            if (hardwareConnected) builder.addAction(new Notification.Action.Builder(R.drawable.ic_volume_up, "Volume +", pending(VOLUME_UP, 5, revision)).build());
            if (mediaCard) {
                // MediaStyle renders at most five expanded actions reliably. The
                // session still exposes standard Stop when Next occupies its slot.
                if (!hasNext) builder.addAction(new Notification.Action.Builder(R.drawable.ic_stop, "Stop video", pending(STOP, 2, revision)).build());
                builder.addAction(new Notification.Action.Builder(R.drawable.ic_disconnect, "Disconnect", pending(DISCONNECT, 3, revision)).build());
                builder.setStyle(new Notification.MediaStyle().setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(hasNext ? hardwareConnected ? new int[] {0, 1, 2} : new int[] {0, 1}
                                : hardwareConnected ? new int[] {0, 1, 2} : new int[] {0}));
            }
        } else {
            builder.addAction(new Notification.Action.Builder(R.drawable.ic_disconnect, "Disconnect", pending(DISCONNECT, 3, revision)).build());
        }
        return builder.build();
    }
    private static String projectorSummary(JSONObject all) {
        JSONObject connection = all.optJSONObject("connection"), volume = all.optJSONObject("volume");
        String name = connection == null ? "Projector" : clean(connection.optString("name"), 120);
        if (name.isEmpty()) name = "Projector";
        if (volume == null) return name;
        int current = volume.optInt("current", -1), max = volume.optInt("max", 0);
        return current >= 0 && max > 0 && current <= max ? name + " · Volume " + current + "/" + max : name;
    }
    private static String playbackSummary(JSONObject all) {
        JSONObject playback = all.optJSONObject("playback");
        return playback != null && playback.optBoolean("stale") ? "Checking Kodi connection · Tap a control to retry" : projectorSummary(all);
    }
    private static boolean verifiedPlayback(JSONObject all) {
        JSONObject connection = all.optJSONObject("connection");
        return connection != null && connection.optBoolean("philips".equals(all.optString("selectedTarget")) ? "connected" : "configured")
                && matches(all.optJSONObject("remote"), all.optJSONObject("playback"));
    }
    private PendingIntent pending(String action, int requestCode, String revision) {
        Intent intent = new Intent(this, ConnectionService.class).setAction(action);
        intent.setData(Uri.fromParts("luma-control", revision + ":" + action, null)).putExtra("revision", revision);
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    @Override public void onDestroy() {
        if (watchHistory != null) watchHistory.close();
        if (libraryStore != null) libraryStore.close();
        libraryReads.shutdownNow();
        if (catalogWebResolver != null) catalogWebResolver.close();
        catalogNetwork.shutdownNow();
        clearSubtitleDelivery();
        if (subtitleDelivery != null) subtitleDelivery.close();
        subtitleNetwork.shutdownNow();
        cancelQueuedVolume();
        cancelNotificationFlush();
        destroyed = true; generation++; revokeTokens(); main.removeCallbacksAndMessages(null); listeners.clear();
        for (Work work : pending) work.cancel();
        if (backend != null) backend.close(); network.shutdownNow();
        if (television != null) television.close(); televisionNetwork.shutdownNow();
        if (televisionText != null) televisionText.close(); textNetwork.shutdownNow();
        if (matter != null) matter.close();
        if (mediaSession != null) { mediaSession.setActive(false); mediaSession.release(); }
        getSystemService(NotificationManager.class).cancel(MEDIA_NOTIFICATION);
        if (foreground) removeForeground();
        super.onDestroy();
    }
    private void removeForeground() {
        getSystemService(NotificationManager.class).cancel(MEDIA_NOTIFICATION);
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

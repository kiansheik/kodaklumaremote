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
    private static final String BACK_30 = "dev.framextv.lumaremote.BACK_30";
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
    private ResolvedSources resolvedSources;
    private ThumbnailStrip scrubPreviews;
    private String scrubPreviewSource = "", scrubPreviewContentKey = "";
    private double scrubPreviewDuration;
    private String hydrationSource = "", hydrationContentKey = "", hydrationRevision = "";
    private String canonicalTransitionSource = "";
    private long canonicalTransitionUntil;
    private boolean hydrationInFlight, hydrationComplete, hydrationApplied;
    private long hydrationRetryAt;
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
    private volatile long lastLibrarySync, lastThumbnailSync;
    private volatile String sharedThumbnailKey = "";
    private final java.util.Set<Long> sharedThumbnails = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    /** Receiver calls that neither change nor describe playback: no extra poll, no stale marking.
     * receiver.status describes playback, so its failures are observed; it is only exempt from the re-poll. */
    private static final java.util.Set<String> SIDE_CHANNEL = new java.util.HashSet<>(java.util.Arrays.asList(
        "receiver.configure", "receiver.pairBegin", "receiver.pairComplete", "receiver.scrub", "receiver.display", "receiver.libraryPull", "receiver.libraryPush",
        "receiver.thumbsList", "receiver.thumbsGet", "receiver.thumbsPut", "receiver.settings", "receiver.rate"));
    private static final long LIBRARY_SYNC_MS = 60_000;
    private volatile long generation;
    private volatile boolean destroyed;
    private MediaSession mediaSession;
    private PendingIntent sessionActivity;
    private VolumeProvider volumeProvider;
    private JSONObject state = new JSONObject();
    private volatile String authorizedToken;
    private volatile String authorizedTarget = "luma";
    private volatile boolean handoffInProgress;
    private JSONObject preparedRemote;
    private JSONObject televisionMedia;
    private String preparedToken;
    private long preparedGeneration = -1;
    private volatile String mediaRevision = UUID.randomUUID().toString();
    private volatile SystemNextScope systemNextScope;
    private long systemNextAttempt;
    private boolean foreground, pollQueued, startRequested, autoConnectPaused;
    private long lastHeartbeat;
    private String notice = "Connect to a projector";
    private String lastPresentation = "";
    private String lastNotificationSignature;
    private long lastNotificationPost;
    private boolean notificationFlushQueued;
    private final Runnable notificationFlush = this::flushNotifications;
    private static final long VOLUME_COOLDOWN = 2000;
    private static final long OBSERVED_HYDRATION_RETRY = 60000;
    private static final long CANONICAL_TRANSITION_GRACE = 12000;
    private static final int NEXT_EPISODE_RETRIES = 75;
    private static final long NEXT_EPISODE_RETRY_MS = 2000;
    // Protected by stateLock; all timers and presentation updates use main.
    private JSONObject confirmedVolume;
    private Integer queuedVolume;
    private long volumeVersion, volumeEpoch, volumeDue;
    private boolean volumeInFlight;
    private String volumeSource = "", volumeRevision = "", volumeWriteToken;
    private long volumeWriteVersion, volumeWriteEpoch, volumeWriteGeneration;
    private PowerManager.WakeLock volumeWakeLock;
    private final Runnable volumeFlush = this::flushQueuedVolume;

    private static final class SystemNextScope {
        final long attempt, session;
        final String revision, source, contentKey, targetKey, writeToken;
        final JSONObject selection;
        final PowerManager.WakeLock wakeLock;
        SystemNextScope(long attempt, long session, String revision, String source, String contentKey,
                String targetKey, String writeToken, JSONObject selection, PowerManager.WakeLock wakeLock) {
            this.attempt = attempt; this.session = session; this.revision = revision; this.source = source;
            this.contentKey = contentKey; this.targetKey = targetKey; this.writeToken = writeToken;
            this.selection = selection; this.wakeLock = wakeLock;
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        watchHistory = new WatchHistory(getApplicationContext());
        libraryStore = new LibraryStore(getApplicationContext());
        libraryStore.migrate(watchHistory.snapshot());
        watchHistory.setProgressListener(libraryStore::record);
        catalogClient = new CatalogClient();
        catalogWebResolver = new WebContentResolver(getApplicationContext());
        resolvedSources = new ResolvedSources(getCacheDir());
        scrubPreviews = new ThumbnailStrip(getCacheDir());
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
            put(state, "settings", object("hasReceiver", false, "hasPairing", false));
            put(state, "autoConnectPaused", false);
            put(state, "selectedTarget", selectedTarget);
            put(state, "receivers", object("philips", televisionSnapshot()));
        }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Projector connection", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Controls for the connected projector and its current video");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        mediaSession = new MediaSession(this, "LUMA receiver controls");
        Intent open = new Intent(this, MainActivity.class).setAction(ACTION_OPEN_REMOTE).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        sessionActivity = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        mediaSession.setSessionActivity(sessionActivity);
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { mediaAction("receiver.resume"); }
            @Override public void onPause() { mediaAction("receiver.pause"); }
            @Override public void onStop() { mediaAction("receiver.stop"); }
            @Override public void onRewind() { mediaAction(BACK_30); }
            @Override public void onSkipToNext() { openNextEpisode(); }
            @Override public void onCustomAction(String action, Bundle extras) {
                if (extras == null || !mediaRevision.equals(extras.getString("revision"))) return;
                if (VOLUME_DOWN.equals(action)) mediaAction("volume-down");
                else if (VOLUME_UP.equals(action)) mediaAction("volume-up");
                else if (BACK_30.equals(action)) mediaAction(BACK_30);
                else if (STOP.equals(action)) mediaAction("receiver.stop");
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
            if (PLAY.equals(action)) mediaAction("receiver.resume");
            else if (PAUSE.equals(action)) mediaAction("receiver.pause");
            else if (VOLUME_DOWN.equals(action)) mediaAction("volume-down");
            else if (VOLUME_UP.equals(action)) mediaAction("volume-up");
            else if (BACK_30.equals(action)) mediaAction(BACK_30);
            else if (STOP.equals(action)) mediaAction("receiver.stop");
            else if (NEXT.equals(action)) openNextEpisode();
        }
        if (!foreground && !START.equals(action)) stopSelf(startId);
        return START_NOT_STICKY;
    }
    public MediaSession.Token getMediaSessionToken() { return mediaSession.getSessionToken(); }
    public boolean nextLaunchCurrent(String revision) {
        JSONObject all = presentationSnapshot(), remote = all.optJSONObject("remote");
        return systemNextScope == null && revision != null && revision.equals(mediaRevision) && verifiedPlayback(all)
                && remote != null && remote.optJSONObject("nextEpisode") != null;
    }
    public void authorizeToken(String token) { authorizeToken(token, "luma"); }
    public void authorizeToken(String token, String target) {
        synchronized (stateLock) {
            if (token == null || !token.equals(authorizedToken)) clearPreparedHandoff();
            authorizedTarget = "philips".equals(target) ? "philips" : "luma";
            authorizedToken = token != null && token.length() <= 200 ? token : null;
            handoffInProgress = authorizedToken != null;
        }
    }
    public void completeHandoff(String token) {
        main.post(() -> { synchronized (stateLock) {
            if (token != null && token.equals(authorizedToken)) { handoffInProgress = false; clearPreparedHandoff(); }
        } });
    }
    public void revokeToken(String token) { synchronized (stateLock) { if (token != null && token.equals(authorizedToken)) { authorizedToken = null; handoffInProgress = false; clearPreparedHandoff(); } } }
    public void revokeTokens() { synchronized (stateLock) { authorizedToken = null; handoffInProgress = false; clearPreparedHandoff(); } }
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
    /** Retain controls for every accepted handoff. An unconfirmed resume binds
     * from zero so the receiver's observed position, rather than stale saved
     * progress, becomes authoritative immediately. */
    public void rememberWatchHandoff(JSONObject media, String target, boolean resumeApplied) {
        if (media == null) return;
        JSONObject owned = copy(media);
        main.post(() -> {
            if (destroyed) return;
            String receiver = "philips".equals(target) ? "philips" : "luma";
            JSONObject playback = "philips".equals(receiver) ? television.status().optJSONObject("playback") : snapshot().optJSONObject("playback");
            JSONObject content = validatedContent(owned);
            if (playback == null || !playback.optBoolean("active") || !owned.optString("url").equals(playback.optString("sourceUrl", playback.optString("source")))) return;
            // The receiver reports canonical identity only for the exact item it opened and must
            // match it exactly. Philips/MSX does not expose content tags; its
            // authorized handoff token plus exact observed source owns the
            // canonical selection captured by this app.
            if (content != null && !"philips".equals(receiver)) {
                JSONObject observedContent = validatedContent(object("content", playback.optJSONObject("content")));
                if (observedContent == null || !content.optString("key").equals(observedContent.optString("key"))) return;
            }
            synchronized (stateLock) {
                if ("philips".equals(receiver)) televisionMedia = systemMedia(owned, content);
                else {
                    JSONObject remote = state.optJSONObject("remote"), current = remote == null ? null : remote.optJSONObject("media");
                    if (current != null && owned.optString("url").equals(current.optString("url")) && content != null) put(current, "content", content);
                    if (remote != null) remote.remove("nextEpisode");
                }
            }
            JSONObject binding = copy(owned);
            if (binding.optDouble("resumeSeconds", 0) > 0 && !resumeApplied) put(binding, "resumeSeconds", 0);
            if (watchHistory.bind(receiver, binding)) watchHistory.observe(receiver, playback);
            publish();
            if (content == null || !"tv".equals(content.optString("kind"))) return;
            refreshNextEpisode(receiver, owned.optString("url"), content, mediaRevision, 0);
        });
    }
    private void refreshNextEpisode(String receiver, String source, JSONObject content, String revision, int attempt) {
        if (!nextEpisodeScopeCurrent(receiver, source, content, revision)) return;
        JSONObject selection = object("kind", "tv", "id", content.optString("contentId"), "season", content.optInt("season"), "episode", content.optInt("episode"));
        requestLibrary("watch.nextEpisode", selection, new Callback() {
            @Override public void success(JSONObject result) {
                if (!result.optBoolean("lookupComplete") && attempt < NEXT_EPISODE_RETRIES) {
                    main.postDelayed(() -> refreshNextEpisode(receiver, source, content, revision, attempt + 1), NEXT_EPISODE_RETRY_MS); return;
                }
                retainNextEpisode(receiver, source, content, revision, result.optJSONObject("nextEpisode"));
            }
            @Override public void failure(String code, String message) {
                if (attempt < NEXT_EPISODE_RETRIES && nextEpisodeScopeCurrent(receiver, source, content, revision)) {
                    main.postDelayed(() -> refreshNextEpisode(receiver, source, content, revision, attempt + 1), NEXT_EPISODE_RETRY_MS);
                } else retainNextEpisode(receiver, source, content, revision, null);
            }
        });
    }
    private boolean nextEpisodeScopeCurrent(String receiver, String source, JSONObject content, String revision) {
        synchronized (stateLock) {
            if (destroyed || !revision.equals(mediaRevision)) return false;
            JSONObject playback = "philips".equals(receiver) ? television.status().optJSONObject("playback") : state.optJSONObject("playback");
            String observed = playback == null ? "" : playback.optString("sourceUrl", playback.optString("source"));
            JSONObject media;
            if ("philips".equals(receiver)) media = televisionMedia;
            else {
                JSONObject remote = state.optJSONObject("remote");
                media = remote == null ? null : remote.optJSONObject("media");
            }
            JSONObject current = validatedContent(media);
            return source.equals(observed) && media != null && source.equals(media.optString("url")) && current != null
                    && content.optString("key").equals(current.optString("key"));
        }
    }
    private void retainNextEpisode(String receiver, String source, JSONObject content, String revision, JSONObject next) {
        JSONObject safe = null;
        if (next != null && "tv".equals(next.optString("kind")) && content.optString("contentId").equals(next.optString("id"))) {
            int season = next.optInt("season", -1), episode = next.optInt("episode", -1);
            if (season >= 0 && season <= 9999 && episode > 0 && episode <= 9999
                    && (season > content.optInt("season") || season == content.optInt("season") && episode > content.optInt("episode"))) safe = copy(next);
        }
        synchronized (stateLock) {
            if (!revision.equals(mediaRevision)) return;
            JSONObject playback = "philips".equals(receiver) ? television.status().optJSONObject("playback") : state.optJSONObject("playback");
            String observed = playback == null ? "" : playback.optString("sourceUrl", playback.optString("source"));
            if (!source.equals(observed)) return;
            if ("philips".equals(receiver)) {
                if (televisionMedia == null || !source.equals(televisionMedia.optString("url"))) return;
                JSONObject currentContent = validatedContent(televisionMedia);
                if (currentContent == null || !content.optString("key").equals(currentContent.optString("key"))) return;
                put(televisionMedia, "nextEpisode", safe == null ? JSONObject.NULL : safe);
            } else {
                JSONObject remote = state.optJSONObject("remote"), media = remote == null ? null : remote.optJSONObject("media");
                if (media == null || !source.equals(media.optString("url"))) return;
                JSONObject currentContent = validatedContent(media);
                if (currentContent == null || !content.optString("key").equals(currentContent.optString("key"))) return;
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
    private void requestScrubPreview(JSONObject args, Callback callback) {
        try {
            if (args.length() != 3 || !(args.opt("expectedSourceUrl") instanceof String)
                    || !(args.opt("contentKey") instanceof String) || !(args.opt("position") instanceof Number)) {
                throw NativeBackend.fail("INVALID_INPUT");
            }
            JSONObject all = presentationSnapshot(), remote = all.optJSONObject("remote"), playback = all.optJSONObject("playback");
            JSONObject media = remote == null ? null : remote.optJSONObject("media"), content = validatedContent(media);
            String expected = NativeBackend.publicUrl(args.opt("expectedSourceUrl")), contentKey = args.optString("contentKey");
            double position = ((Number) args.opt("position")).doubleValue();
            if (!verifiedPlayback(all) || playback == null || !playback.optBoolean("canSeek") || content == null
                    || !expected.equals(media.optString("url")) || !contentKey.equals(content.optString("key"))
                    || !Double.isFinite(position)) throw NativeBackend.fail("SOURCE_CHANGED");
            syncScrubPreviews(all);
            if (!expected.equals(scrubPreviewSource) || !contentKey.equals(scrubPreviewContentKey)) throw NativeBackend.fail("SOURCE_CHANGED");
            // Local lookup only: frames are generated in the background since the cast,
            // and the area being scrubbed is moved to the front of that queue.
            scrubPreviews.prioritize(position);
            ThumbnailStrip.Frame frame = scrubPreviews.nearest(expected, position);
            if (callback == null) return;
            if (frame == null) callback.success(object("available", false, "pending", !scrubPreviews.unsupported()));
            else callback.success(object("available", true, "dataUri", "data:image/jpeg;base64," + android.util.Base64.encodeToString(frame.jpeg, android.util.Base64.NO_WRAP),
                "positionSeconds", frame.positionSeconds, "intervalSeconds", frame.intervalSeconds, "generated", frame.done, "total", frame.total));
        } catch (NativeBackend.BackendException error) { reject(callback, error.code, error.getMessage()); }
    }
    private final java.util.concurrent.ExecutorService previewIo = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.ExecutorService scrubIo = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicReference<JSONObject> pendingScrub = new java.util.concurrent.atomic.AtomicReference<>();
    /**
     * Scrub mirroring bypasses the serialized network queue (where a thumbnail upload
     * could delay it) and coalesces to the newest position.
     */
    private void mirrorScrub(JSONObject args, Callback callback) {
        try {
            JSONObject command = NativeBackend.scrubCommand(args);
            NativeBackend transport = backend;
            NativeBackend.Endpoint endpoint = transport == null ? null : transport.receiverEndpoint();
            if (endpoint == null) throw NativeBackend.fail("RECEIVER_CONFIG");
            if (pendingScrub.getAndSet(command) == null) scrubIo.execute(() -> {
                JSONObject next;
                while ((next = pendingScrub.getAndSet(null)) != null) NativeBackend.quickPost(endpoint, "player.scrub", next);
            });
            if (callback != null) callback.success(object("accepted", true));
        } catch (Exception failure) {
            String code = failure instanceof NativeBackend.BackendException ? ((NativeBackend.BackendException) failure).code : "INVALID_INPUT";
            reject(callback, code, "Scrub preview unavailable.");
        }
    }
    private final java.util.concurrent.atomic.AtomicReference<JSONObject> pendingTelevisionScrub = new java.util.concurrent.atomic.AtomicReference<>();
    /** Philips scrub overlay: latest position wins; the nearest local frame rides along to the TV page. */
    private void mirrorTelevisionScrub(JSONObject args, Callback callback) {
        try {
            JSONObject command = NativeBackend.scrubCommand(args);
            if (pendingTelevisionScrub.getAndSet(command) == null) scrubIo.execute(() -> {
                JSONObject next;
                while ((next = pendingTelevisionScrub.getAndSet(null)) != null) {
                    boolean active = next.optBoolean("active");
                    double position = next.optDouble("positionSeconds", 0);
                    byte[] jpeg = null;
                    if (active) {
                        JSONObject playback = television.status().optJSONObject("playback");
                        String source = playback == null ? "" : playback.optString("source");
                        ThumbnailStrip.Frame frame = source.isEmpty() ? null : scrubPreviews.nearest(source, position);
                        if (frame != null) jpeg = frame.jpeg;
                    }
                    television.scrub(active, position, next.optDouble("durationSeconds", 0), jpeg);
                }
            });
            if (callback != null) callback.success(object("accepted", true));
        } catch (Exception failure) {
            String code = failure instanceof NativeBackend.BackendException ? ((NativeBackend.BackendException) failure).code : "INVALID_INPUT";
            reject(callback, code, "Scrub preview unavailable.");
        }
    }

    // ---- Philips auto-play: the same behaviour LUMA Receiver runs on the projector ----
    static final long TV_UP_NEXT_MS = 10_000;
    static final int TV_MAX_UNATTENDED = 3;
    private boolean televisionUpNext, televisionAutoplayed;
    private int televisionUnattended;
    private String televisionEndedSource = "";
    private long televisionInputs = -1;
    private final Runnable televisionNext = this::startTelevisionNext;
    private android.content.SharedPreferences televisionPreferences() { return getSharedPreferences("philips-playback", MODE_PRIVATE); }
    boolean televisionAutoNext() { return televisionPreferences().getBoolean("autoNext", false); }
    private void televisionSettings(JSONObject args, Callback callback) {
        if (args.length() > 1 || args.length() == 1 && !(args.opt("autoNext") instanceof Boolean)) { reject(callback, "INVALID_INPUT", "Choose auto-play on or off."); return; }
        if (args.has("autoNext")) {
            televisionPreferences().edit().putBoolean("autoNext", args.optBoolean("autoNext")).apply();
            if (!args.optBoolean("autoNext")) cancelTelevisionUpNext();
            publish();
        }
        if (callback != null) callback.success(object("autoNext", televisionAutoNext()));
    }
    private void cancelTelevisionUpNext() {
        if (!televisionUpNext) return;
        televisionUpNext = false; main.removeCallbacks(televisionNext);
        sendTelevisionNotice("Auto-play cancelled.", 3);
    }
    /** A command from a person: clears the unattended count and remembers where they were. */
    private void noteTelevisionInteraction() {
        televisionUnattended = 0; televisionAutoplayed = false;
        cancelTelevisionUpNext();
        JSONObject playback = television.status().optJSONObject("playback"), media;
        synchronized (stateLock) { media = copy(televisionMedia); }
        JSONObject content = validatedContent(media);
        if (playback == null || content == null || !playback.optString("source").equals(media.optString("url"))) return;
        JSONObject last = object("content", content, "positionSeconds", playback.optDouble("positionSeconds", 0), "at", System.currentTimeMillis());
        televisionPreferences().edit().putString("lastInteraction", last.toString()).apply();
    }
    private JSONObject televisionLastInteraction() {
        try { return new JSONObject(televisionPreferences().getString("lastInteraction", "{}")); } catch (Exception corrupt) { return new JSONObject(); }
    }
    private void sendTelevisionNotice(String text, int seconds) {
        try { televisionNetwork.execute(() -> { try { television.command("notice", object("text", text, "seconds", seconds)); } catch (Exception ignored) { } }); }
        catch (RejectedExecutionException busy) { /* notices are best effort */ }
    }
    private long televisionDisplayRestoredAt;
    private void rememberTelevisionDisplay(JSONObject args) {
        JSONObject saved;
        try { saved = new JSONObject(televisionPreferences().getString("display", "{}")); } catch (Exception corrupt) { saved = new JSONObject(); }
        for (String name : new String[] {"subtitleScale", "brightness", "contrast"}) if (args.opt(name) instanceof Number) put(saved, name, args.optDouble(name));
        televisionPreferences().edit().putString("display", saved.toString()).apply();
    }
    /** Re-sends saved display settings when the TV page reports different ones (at most every 30 s). */
    private void restoreTelevisionDisplay(JSONObject playback) {
        JSONObject reported = playback.optJSONObject("display"), saved;
        if (reported == null || SystemClock.elapsedRealtime() - televisionDisplayRestoredAt < 30_000) return;
        try { saved = new JSONObject(televisionPreferences().getString("display", "{}")); } catch (Exception corrupt) { return; }
        boolean differs = false;
        for (java.util.Iterator<String> names = saved.keys(); names.hasNext();) {
            String name = names.next();
            if (Math.abs(saved.optDouble(name, 1) - reported.optDouble(name, 1)) > 0.001) differs = true;
        }
        if (!differs) return;
        televisionDisplayRestoredAt = SystemClock.elapsedRealtime();
        try { televisionNetwork.execute(() -> { try { television.command("display", saved); } catch (Exception ignored) { } }); }
        catch (RejectedExecutionException busy) { /* tried again on a later refresh */ }
    }
    /** Main thread, on every TV state refresh. */
    private void observeTelevisionPlayback(JSONObject playback) {
        if (playback == null) return;
        restoreTelevisionDisplay(playback);
        JSONObject debug = playback.optJSONObject("receiverDebug");
        long inputs = debug == null ? -1 : debug.optLong("inputCount", -1);
        boolean pressed = inputs >= 0 && televisionInputs >= 0 && inputs != televisionInputs;
        televisionInputs = inputs;
        if (pressed) noteTelevisionInteraction();
        String source = playback.optString("source");
        if (!playback.optBoolean("ended")) { if (!source.equals(televisionEndedSource)) televisionEndedSource = ""; return; }
        if (source.isEmpty() || source.equals(televisionEndedSource)) return;
        televisionEndedSource = source;
        JSONObject media;
        synchronized (stateLock) { media = copy(televisionMedia); }
        JSONObject content = validatedContent(media);
        if (!televisionAutoNext() || content == null || !"tv".equals(content.optString("kind")) || !source.equals(media.optString("url"))) return;
        if (televisionUnattended >= TV_MAX_UNATTENDED) { sendTelevisionNotice("Auto-play paused: no one has touched the remote for a while.", 8); return; }
        televisionUpNext = true;
        sendTelevisionNotice("Next episode in 10 seconds · press Back to cancel", 10);
        main.postDelayed(televisionNext, TV_UP_NEXT_MS);
        publish();
    }
    private void startTelevisionNext() {
        if (!televisionUpNext || destroyed) return;
        televisionUpNext = false;
        JSONObject media, playback = television.status().optJSONObject("playback");
        synchronized (stateLock) { media = copy(televisionMedia); }
        JSONObject content = validatedContent(media), next = media == null ? null : media.optJSONObject("nextEpisode");
        if (content == null || next == null || playback == null || !playback.optBoolean("ended")) {
            if (content != null && next == null) sendTelevisionNotice("That was the last episode.", 6);
            publish(); return;
        }
        // Keep the language that was on, else the one last used for this series.
        String language = "";
        JSONArray tracks = playback.optJSONArray("tracks");
        int selected = playback.optInt("selectedIndex", -1);
        for (int i = 0; tracks != null && playback.optBoolean("subtitlesEnabled") && i < tracks.length(); i++) {
            JSONObject track = tracks.optJSONObject(i);
            if (track != null && track.optInt("index", -2) == selected) language = track.optString("language");
        }
        if (language.isEmpty()) language = rememberedCaptionLanguage(content);
        JSONObject selection = object("kind", "tv", "id", content.optString("contentId"), "season", next.optInt("season"), "episode", next.optInt("episode"), "resume", true);
        final String wanted = language;
        try {
            JSONObject job = television.playCatalog(selection);
            televisionAutoplayed = true; televisionUnattended++;
            if (!wanted.isEmpty()) awaitTelevisionJob(job.optString("jobId"), wanted, SystemClock.elapsedRealtime() + 180_000);
        } catch (MsxServer.LibraryException failure) { sendTelevisionNotice("Couldn't load the next episode.", 6); }
        publish();
    }
    /** After an auto-played episode opens, turn on the same subtitle language. */
    private void awaitTelevisionJob(String jobId, String language, long deadline) {
        main.postDelayed(() -> {
            if (destroyed) return;
            String state;
            try { state = television.catalogStatus(jobId).optString("state"); } catch (MsxServer.LibraryException gone) { return; }
            if (!"ready".equals(state)) { if (!"error".equals(state) && !"cancelled".equals(state) && SystemClock.elapsedRealtime() < deadline) awaitTelevisionJob(jobId, language, deadline); return; }
            JSONObject playback = television.status().optJSONObject("playback");
            JSONArray tracks = playback == null ? null : playback.optJSONArray("tracks");
            int index = captionIndex(tracks, language);
            if (index < 0) return;
            JSONObject args = object("index", index, "enabled", true, "expectedSourceUrl", playback.optString("source"));
            try { televisionNetwork.execute(() -> { try { television.command("subtitle", args); } catch (Exception ignored) { } updateTelevisionStateLater(); }); }
            catch (RejectedExecutionException busy) { /* the person can still choose subtitles */ }
        }, 1000);
    }
    private void updateTelevisionStateLater() { main.post(() -> { if (!destroyed) { updateTelevisionState(); publish(); } }); }
    /** Exact language first, then the same base language ("pt-BR" → "pt"). */
    static int captionIndex(JSONArray tracks, String language) {
        if (tracks == null || language == null || language.isEmpty()) return -1;
        String wanted = language.toLowerCase(java.util.Locale.ROOT).replace('_', '-'), base = wanted.split("-", 2)[0];
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < tracks.length(); i++) {
                JSONObject track = tracks.optJSONObject(i);
                String candidate = track == null ? "" : track.optString("language").toLowerCase(java.util.Locale.ROOT).replace('_', '-');
                if (pass == 0 ? candidate.equals(wanted) : !candidate.isEmpty() && candidate.split("-", 2)[0].equals(base)) return track.optInt("index", -1);
            }
        }
        return -1;
    }
    /** The phone page's per-title subtitle choice (shared with app.captionPreferences). */
    private String rememberedCaptionLanguage(JSONObject content) {
        try {
            JSONObject all = new JSONObject(getSharedPreferences("caption-preferences", MODE_PRIVATE).getString("value", "{}"));
            JSONObject entry = all.optJSONObject("framextv:" + content.optString("kind") + ":" + content.optString("contentId"));
            return entry == null ? "" : entry.optString("language");
        } catch (Exception corrupt) { return ""; }
    }

    /** Bulk frames for the page's local cache so sweeps render instantly; disk reads happen off the main thread. */
    private void requestScrubBatch(JSONObject args, Callback callback) {
        try {
            if (!(args.opt("expectedSourceUrl") instanceof String) || !(args.opt("contentKey") instanceof String)
                    || !(args.opt("around") instanceof Number) || !(args.opt("have") instanceof JSONArray)) throw NativeBackend.fail("INVALID_INPUT");
            JSONObject all = presentationSnapshot();
            syncScrubPreviews(all);
            String expected = NativeBackend.publicUrl(args.opt("expectedSourceUrl")), key = args.optString("contentKey");
            if (!expected.equals(scrubPreviewSource) || !key.equals(scrubPreviewContentKey)) throw NativeBackend.fail("SOURCE_CHANGED");
            JSONArray have = args.optJSONArray("have");
            if (have.length() > 2400) throw NativeBackend.fail("INVALID_INPUT");
            java.util.Set<Long> known = new java.util.HashSet<>();
            for (int i = 0; i < have.length(); i++) known.add(have.optLong(i, -1));
            double around = ((Number) args.opt("around")).doubleValue();
            int limit = Math.max(1, Math.min(48, args.optInt("limit", 48)));
            ThumbnailStrip strip = scrubPreviews;
            previewIo.execute(() -> {
                JSONArray frames = new JSONArray();
                java.util.List<Long> positions = strip.positionsNear(expected, around, known, limit);
                for (long position : positions) {
                    byte[] jpeg = strip.frameBytes(key, position);
                    if (jpeg != null) frames.put(object("position", position, "dataUri", "data:image/jpeg;base64," + android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)));
                }
                boolean more = strip.positionsNear(expected, around, known, limit + 1).size() > positions.size();
                main.post(() -> { if (callback != null) callback.success(object("frames", frames, "more", more)); });
            });
        } catch (NativeBackend.BackendException error) { reject(callback, error.code, error.getMessage()); }
        catch (RejectedExecutionException busy) { reject(callback, "BUSY", "Previews are busy."); }
    }
    private void syncScrubPreviews(JSONObject all) {
        JSONObject remote = all == null ? null : all.optJSONObject("remote"), playback = all == null ? null : all.optJSONObject("playback");
        JSONObject media = remote == null ? null : remote.optJSONObject("media"), content = validatedContent(media);
        double duration = playback == null ? 0 : playback.optDouble("durationSeconds", 0);
        if (!verifiedPlayback(all) || playback == null || !playback.optBoolean("canSeek") || content == null || !(duration > 0)) {
            clearScrubPreviews(); return;
        }
        String source = media.optString("url"), key = content.optString("key");
        if (source.equals(scrubPreviewSource) && key.equals(scrubPreviewContentKey) && duration == scrubPreviewDuration) return;
        scrubPreviews.bind(source, media.optString("contentType"), key, duration);
        scrubPreviewSource = source; scrubPreviewContentKey = key; scrubPreviewDuration = duration;
    }
    private void clearScrubPreviews() {
        if (scrubPreviewSource.isEmpty() && scrubPreviewContentKey.isEmpty()) return;
        scrubPreviewSource = scrubPreviewContentKey = ""; scrubPreviewDuration = 0;
        if (scrubPreviews != null) scrubPreviews.clear();
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
        // A title played recently resolves from the on-phone cache when its source still answers.
        String cacheKey = ResolvedSources.key(params);
        JSONObject media = resolvedSources.get(cacheKey, System.currentTimeMillis());
        if (media != null && !ResolvedSources.reachable(media.optString("url"))) { resolvedSources.forget(cacheKey); media = null; }
        if (media == null) media = resolveFresh(params, current);
        else Log.i("LumaCatalog", "SOURCE_CACHED");
        if (!catalogCurrent(current)) throw new MsxServer.LibraryException("CANCELLED");
        JSONObject content = media.optJSONObject("content");
        if (content == null) throw new MsxServer.LibraryException("SOURCE_UNSUPPORTED");
        media = attachWatchMetadata(media, content.optString("watchUrl"));
        if (!resume) put(media, "resumeSeconds", 0);
        return media;
    }
    private JSONObject resolveFresh(JSONObject params, java.util.function.BooleanSupplier current) throws MsxServer.LibraryException {
        JSONObject media;
        try { media = catalogClient.handle("resolve", params); }
        catch (CatalogClient.CatalogException error) {
            if ("CATALOG_INVALID_INPUT".equals(error.code)) throw new MsxServer.LibraryException(error.code);
            if (!catalogCurrent(current)) throw new MsxServer.LibraryException("CANCELLED");
            try { media = catalogWebResolver.resolve(params, () -> catalogCurrent(current)); }
            catch (WebContentResolver.ResolveException failure) { throw new MsxServer.LibraryException(failure.code); }
        }
        JSONObject content = media.optJSONObject("content");
        if (content != null) resolvedSources.put(content.optString("key", null), media, System.currentTimeMillis());
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
        transport.setSourceGuard(token -> token != null && ("luma".equals(authorizedTarget) && token.equals(authorizedToken)
                || currentVolumeWrite(token) || currentSystemNextWrite(token)));
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
                if (!target.equals(selectedTarget)) { cancelSystemNext(); cancelQueuedVolume(); cancelTelevisionText(); mediaRevision = UUID.randomUUID().toString(); }
                selectedTarget = target;
                getSharedPreferences("receiver-selection", MODE_PRIVATE).edit().putString("target", target).apply();
                synchronized (stateLock) { put(state, "selectedTarget", selectedTarget); }
                publish();
                if (callback != null) callback.success(snapshot());
                return;
            }
            if ("msx.scrub".equals(method)) { mirrorTelevisionScrub(args, callback); return; }
            if ("msx.settings".equals(method)) { televisionSettings(args, callback); return; }
            if (method != null && method.startsWith("msx.")) { requestTelevision(method, args, callback); return; }
            if (method != null && method.startsWith("matter.")) { requestMatter(method, args, callback); return; }
            if ("watch.history".equals(method)) {
                if (args.length() != 0) { reject(callback, "INVALID_REQUEST", "Open viewing history without extra fields."); return; }
                if (callback != null) callback.success(watchHistory.snapshot());
                return;
            }
            if ("watch.library".equals(method) || "watch.libraryDetails".equals(method) || "watch.nextEpisode".equals(method)) { requestLibrary(method, args, callback); return; }
            if (method != null && method.startsWith("catalog.")) { requestCatalog(method, args, callback); return; }
            if ("preview.frame".equals(method)) { requestScrubPreview(args, callback); return; }
            if ("preview.batch".equals(method)) { requestScrubBatch(args, callback); return; }
            if ("receiver.scrub".equals(method)) { mirrorScrub(args, callback); return; }
            if ("device.disconnect".equals(method)) { disconnect(callback); return; }
            if ("receiver.prepareSubtitle".equals(method) || "receiver.subtitleDelivery".equals(method)) {
                requestSubtitle(method, args, callback); return;
            }
            if ("device.connect".equals(method) || "receiver.pairComplete".equals(method) || "app.settings.reset".equals(method)) clearSubtitleDelivery();
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
            if ("receiver.open".equals(method)) {
                clearSubtitleDelivery();
                cancelQueuedVolume();
                mediaRevision = UUID.randomUUID().toString();
                synchronized (stateLock) {
                    clearCanonicalTransitionLocked();
                    resetObservedHydrationLocked();
                    JSONObject media = args.optJSONObject("media"), remembered = preparedRemote == null ? null : preparedRemote.optJSONObject("media");
                    if (preparedGeneration == generation && preparedToken != null && preparedToken.equals(authorizedToken)
                            && preparedToken.equals(args.optString("sourceToken")) && sameMediaIdentity(remembered, media)) put(state, "remote", copy(preparedRemote));
                    else state.remove("remote");
                }
                publish();
            }
            if (!enqueue(work -> perform(work, method, args), callback) && "device.connect".equals(method) && !connected()) demote();
        });
    }
    private void disconnect(Callback callback) {
        cancelSystemNext();
        clearSubtitleDelivery();
        cancelQueuedVolume();
        if ("luma".equals(authorizedTarget)) revokeTokens();
        generation++; mediaRevision = UUID.randomUUID().toString();
        autoConnectPaused = true; lastLibrarySync = 0;
        confirmedVolume = null;
        NativeBackend old = backend; backend = null;
        if (old != null) old.close();
        for (Work work : pending) work.cancel();
        network.getQueue().clear(); pollQueued = false; lastHeartbeat = 0;
        synchronized (stateLock) {
            clearCanonicalTransitionLocked();
            resetObservedHydrationLocked();
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
                if (connection == null || !connection.optBoolean("configured")) throw NativeBackend.fail("RECEIVER_CONFIG");
                if (!source.equals(rememberedSource()) || !matches(remote, state.optJSONObject("playback"))) throw NativeBackend.fail("SOURCE_CHANGED");
                if (token != null && (!token.equals(authorizedToken) || !"luma".equals(authorizedTarget))) throw NativeBackend.fail("SOURCE_CHANGED");
                boolean declared = false; JSONArray catalog = remote.optJSONArray("catalog");
                if (catalog != null) for (int i = 0; i < catalog.length(); i++) {
                    JSONObject entry = catalog.optJSONObject(i);
                    if (entry != null && original.equals(entry.optString("url"))) { declared = true; break; }
                }
                if (!declared) throw NativeBackend.fail("INVALID_SUBTITLE");
                peer = connection.optString("host");
                if ("receiver.prepareSubtitle".equals(method)) {
                    if (!subtitleSource.isEmpty() && !source.equals(subtitleSource)) clearSubtitleDelivery();
                    subtitleSource = source;
                }
                subtitleEpoch = subtitleGeneration.get();
            }
            java.util.function.BooleanSupplier current = () -> !destroyed && session == generation && subtitleEpoch == subtitleGeneration.get() && revision.equals(mediaRevision)
                    && (token == null || token.equals(authorizedToken));
            if ("receiver.subtitleDelivery".equals(method)) {
                String id = args.optString("preparedId");
                subtitleDelivery.resolve(id, source, original, peer);
                if (!current.getAsBoolean()) throw NativeBackend.fail("SOURCE_CHANGED");
                if (callback != null) callback.success(object("downloaded", subtitleDelivery.downloaded(id)));
                return;
            }
            // A slow public subtitle endpoint must not hold the receiver control
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
                if (failure != null) { observeReceiverFailure(failure); notice = failure.getMessage(); }
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
                if (scope.equals("pairing") || scope.equals("all")) work.transport.clearReceiverConfiguration();
                result = object("settings", settings.metadata());
            } else if ("app.rememberRemote".equals(method)) {
                result = rememberRemote(work, args);
            } else if ("app.settings.rescan".equals(method) || "app.settings.autoConnect".equals(method)) {
                result = autoConnect(work, "app.settings.rescan".equals(method));
            } else if ("device.connect".equals(method)) result = connectNative(work, args.optString("host"), settings.readPairing());
            else result = work.transport.handle(method, args);
            if ("receiver.pairComplete".equals(method)) {
                // Persist the issued token immediately (the code proved user intent) and
                // never hand it to the WebView.
                String issued = result.optString("token");
                result.remove("token");
                if (work.current()) settings.savePairing(issued);
            }
            JSONObject connection = work.transport.connectionSnapshot();
            synchronized (stateLock) {
                if (!work.current()) return;
                JSONObject old = state.optJSONObject("connection");
                if (connecting && old != null && !old.optString("host").equals(connection.optString("host"))) {
                    cancelQueuedVolume(); confirmedVolume = null; state.remove("volume");
                    resetObservedHydrationLocked();
                    state.remove("remote"); state.remove("playback");
                }
                put(state, "connection", connection);
                put(state, "settings", settings.metadata());
                if ("receiver.status".equals(method)) put(state, "playback", copy(result));
                if (("luma.status".equals(method) || "luma.setVolume".equals(method)) && result.optJSONObject("volume") != null) {
                    observeVolume(result.optJSONObject("volume")); put(result, "volume", copy(state.optJSONObject("volume"))); put(result, "volumePending", queuedVolume != null);
                }
                if ("receiver.getSubtitles".equals(method)) observeSubtitles(result);
                if ("app.settings.reset".equals(method)) { resetObservedHydrationLocked(); state.remove("playback"); }
            }
            if ("receiver.status".equals(method)) adoptObservedPlayback(result);
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
                if (method != null && (method.startsWith("receiver.") || method.startsWith("luma.")) && !SIDE_CHANNEL.contains(method) && !method.equals("receiver.status") && !method.equals("luma.status")) schedulePoll(250);
            });
        } catch (NativeBackend.BackendException failure) {
            JSONObject failedConnection = work.transport.connectionSnapshot();
            boolean nativeLost = method != null && method.startsWith("luma.") && !failedConnection.optBoolean("nativeConnected");
            if (nativeLost) cancelQueuedVolume();
            synchronized (stateLock) {
                if (!work.current()) return;
                if (nativeLost) { confirmedVolume = null; state.remove("volume"); }
                put(state, "connection", failedConnection);
                put(state, "settings", settings.metadata());
                if (method != null && method.startsWith("receiver.") && !SIDE_CHANNEL.contains(method)) observeReceiverFailure(failure);
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
        if (playback != null && (playback.optBoolean("active") || playback.optBoolean("ended")) && retained != null && retained.optString("url").equals(playback.optString("source"))) {
            if (retained.optJSONObject("content") != null) put(playback, "content", retained.optJSONObject("content"));
            if (retained.has("nextEpisode")) put(playback, "nextEpisode", retained.opt("nextEpisode"));
        }
        if (playback != null) {
            put(playback, "autoNext", televisionAutoNext()); put(playback, "upNext", televisionUpNext); put(playback, "autoplayed", televisionAutoplayed);
            JSONObject last = televisionLastInteraction();
            if (last.length() > 0) put(playback, "lastInteraction", last);
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
        boolean browserAddress = "msx.typeBrowserAddress".equals(method);
        boolean validBrowserAddress = browserAddress && args.length() == 1 && ("stable".equals(args.optString("address")) || "direct".equals(args.optString("address")));
        if (!"philips".equals(selectedTarget) || (grant ? args.length() != 1 || !(args.opt("pin") instanceof String) || !args.optString("pin").matches("[0-9]{4}") : browserAddress ? !validBrowserAddress : args.length() != 0)) {
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
                            else if ("msx.typeBrowserAddress".equals(method)) result = televisionText.typeBrowserAddress(host, television.status().optString("startupUrl"), args.optString("address"));
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
                "msx.seek", "msx.volume", "msx.subtitle", "msx.stop", "msx.disconnect", "msx.reset", "msx.debugPing", "msx.debugLabel", "msx.reloadReceiver",
                "msx.display", "msx.rate").contains(method)) {
            reject(callback, "INVALID_REQUEST", "Unknown television control."); return;
        }
        // A person using the remote resets auto-play's unattended count, as on LUMA Receiver.
        if (java.util.Arrays.asList("msx.open", "msx.playPause", "msx.seek", "msx.volume", "msx.subtitle", "msx.stop", "msx.display").contains(method)) noteTelevisionInteraction();
        // The phone keeps the TV's display settings too, so a new page origin or cleared TV storage gets them back.
        if ("msx.display".equals(method)) rememberTelevisionDisplay(args);
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
        // its receipt. They never share the LUMA queue or wait behind TV playback.
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
        if (playback != null && current.optBoolean("connected")) observeTelevisionPlayback(playback);
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
        if ("receiver.resume".equals(method) || "receiver.pause".equals(method)) {
            action = "playPause"; put(args, "play", "receiver.resume".equals(method));
        } else if (BACK_30.equals(method)) {
            if (!canRewind(playback)) return;
            action = "seek"; put(args, "seconds", rewindPosition(playback));
        } else if ("receiver.stop".equals(method)) action = "stop";
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
    private JSONObject connectNative(Work work, String host, JSONObject pairing) throws NativeBackend.BackendException, SettingsStore.SettingsException {
        JSONObject result = work.transport.handle("device.connect", object("host", host));
        if (!work.current()) return result;
        settings.saveReceiver(host, result.optString("name"));
        if (pairing != null && work.current()) {
            JSONObject configured = copy(pairing); put(configured, "host", host);
            work.transport.handle("receiver.configure", configured);
        }
        return result;
    }
    /** The receiver has its own boot-time LAN service and does not depend on
     * EShare's much later navigation socket. A saved pairing can therefore make
     * casting ready as soon as the projector has Wi-Fi and port 4191. */
    private JSONObject connectDirectReceiver(Work work, String host, JSONObject pairing) throws NativeBackend.BackendException, SettingsStore.SettingsException {
        JSONObject configured = copy(pairing); put(configured, "host", host);
        work.transport.handle("receiver.configure", configured);
        JSONObject ready = work.transport.handle("receiver.connect", new JSONObject());
        if (!work.current()) return ready;
        String name = ready.optString("name", "LUMA Receiver");
        settings.saveReceiver(host, name);
        return object("connected", true, "nativeConnected", false, "receiverConnected", true,
                "name", name, "device", object("name", name));
    }
    private JSONObject connectReceiver(Work work, String host) throws NativeBackend.BackendException, SettingsStore.SettingsException {
        JSONObject current = work.transport.connectionSnapshot();
        if (current.optBoolean("nativeConnected") && host.equals(current.optString("host")))
            return object("connected", true, "nativeConnected", true, "receiverConnected", current.optBoolean("receiverConnected"),
                    "name", current.optString("name"), "device", object("name", current.optString("name")));
        JSONObject pairing = settings.readPairing();
        if (pairing != null) {
            try { return connectDirectReceiver(work, host, pairing); }
            catch (NativeBackend.BackendException directFailure) {
                if (!work.current() || !"RECEIVER_UNAVAILABLE".equals(directFailure.code)) throw directFailure;
            }
        }
        return connectNative(work, host, pairing);
    }
    private JSONObject autoConnect(Work work, boolean rescan) throws NativeBackend.BackendException, SettingsStore.SettingsException {
        JSONObject current = work.transport.connectionSnapshot();
        if (!rescan && current.optBoolean("nativeConnected")) return object("connected", true, "nativeConnected", true,
                "receiverConnected", current.optBoolean("receiverConnected"), "name", current.optString("name"), "devices", new JSONArray());
        if (rescan) settings.clear("receiver");
        JSONObject saved = rescan ? null : settings.readReceiver();
        if (saved != null) {
            // A failed attempt (projector booting, Wi-Fi rejoining, the old socket
            // after a crash) must not forget the projector: keep it and retry.
            if (current.optBoolean("receiverConnected") && saved.optString("host").equals(current.optString("host"))) {
                try { return connectNative(work, saved.optString("host"), settings.readPairing()); }
                catch (NativeBackend.BackendException nativeUnavailable) {
                    if (!work.current()) throw nativeUnavailable;
                    // Direct casting remains ready. A later foreground retry can
                    // attach navigation without delaying this successful route.
                    return object("connected", true, "nativeConnected", false, "receiverConnected", true,
                            "name", current.optString("name"), "devices", new JSONArray());
                }
            }
            try { return connectReceiver(work, saved.optString("host")); }
            catch (NativeBackend.BackendException ignored) { if (!work.current()) throw ignored; }
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
                        && sameMediaIdentity(prepared, mediaData)
                        && mediaData.optString("url").equals(rememberedSource());
                if (!completingPrepared) { cancelQueuedVolume(); mediaRevision = UUID.randomUUID().toString(); }
                resetObservedHydrationLocked();
                put(state, "remote", remote);
            }
            publish(); schedulePoll(0);
        });
        return true;
    }
    private JSONObject rememberRemote(Work work, JSONObject args) throws NativeBackend.BackendException {
        String expected = NativeBackend.publicUrl(args.opt("expectedSourceUrl"));
        JSONObject cached = snapshot().optJSONObject("remote"), proposed = args.optJSONObject("remote");
        JSONObject media = cached == null ? null : cached.optJSONObject("media"), proposedMedia = proposed == null ? null : proposed.optJSONObject("media");
        if (media == null || proposed == null || !expected.equals(media.optString("url"))
                || proposedMedia == null || !expected.equals(NativeBackend.publicUrl(proposedMedia.opt("url")))) throw NativeBackend.fail("SOURCE_CHANGED");
        JSONObject expectedContent = validatedContent(media), proposedContent = validatedContent(proposedMedia);
        if (expectedContent == null != (proposedContent == null)
                || expectedContent != null && !expectedContent.optString("key").equals(proposedContent.optString("key"))) throw NativeBackend.fail("SOURCE_CHANGED");
        if (args.has("expectedContentKey") && (expectedContent == null
                || !expectedContent.optString("key").equals(args.optString("expectedContentKey")))) throw NativeBackend.fail("SOURCE_CHANGED");
        JSONObject current = work.transport.handle("receiver.status", new JSONObject());
        if (!matches(cached, current)) throw NativeBackend.fail("SOURCE_CHANGED");
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
    private static boolean sameMediaIdentity(JSONObject first, JSONObject second) {
        if (first == null || second == null || !first.optString("url").equals(second.optString("url"))) return false;
        JSONObject left = validatedContent(first), right = validatedContent(second);
        return left == null ? right == null : right != null && left.optString("key").equals(right.optString("key"));
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
    private void observeReceiverFailure(NativeBackend.BackendException failure) {
        synchronized (stateLock) {
            if ("RECEIVER_AUTH".equals(failure.code) || "RECEIVER_CONFIG".equals(failure.code)
                    || "NO_VIDEO".equals(failure.code) || "SOURCE_CHANGED".equals(failure.code)) {
                clearSubtitleDelivery();
                resetObservedHydrationLocked();
                state.remove("playback");
            } else if ("RECEIVER_UNAVAILABLE".equals(failure.code) || "RECEIVER_RESPONSE".equals(failure.code)) {
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
        try { return work.transport.handle("receiver.status", new JSONObject()); }
        catch (NativeBackend.BackendException failure) {
            if (!work.current() || SystemClock.elapsedRealtime() - started >= 1000
                    || !("RECEIVER_UNAVAILABLE".equals(failure.code) || "RECEIVER_RESPONSE".equals(failure.code))) throw failure;
            SystemClock.sleep(150);
            if (!work.current()) throw NativeBackend.fail("NOT_CONNECTED");
            return work.transport.handle("receiver.status", new JSONObject());
        }
    }
    private boolean connected() { return snapshot().optJSONObject("connection").optBoolean("connected"); }
    private boolean configured() { return snapshot().optJSONObject("connection").optBoolean("configured"); }
    private boolean hasReceiverPlayback() {
        JSONObject all = snapshot(), connection = all.optJSONObject("connection");
        return connection != null && connection.optBoolean("configured") && matches(all.optJSONObject("remote"), all.optJSONObject("playback"));
    }
    private boolean hasReceiverCandidatePlayback() {
        synchronized (stateLock) {
            JSONObject connection = state.optJSONObject("connection"), remote = state.optJSONObject("remote"), playback = state.optJSONObject("playback");
            JSONObject media = remote == null ? null : remote.optJSONObject("media");
            if (connection == null || !connection.optBoolean("configured") || media == null || playback == null
                    || !playback.optBoolean("active") || media.optString("url").isEmpty()) return false;
            String source = playback.optString("sourceUrl");
            if (media.optString("url").equals(source)) return true;
            return validatedContent(media) != null && source.equals(canonicalTransitionSource)
                    && SystemClock.elapsedRealtime() < canonicalTransitionUntil;
        }
    }
    private boolean needsReceiverPolling() { return connected() || hasReceiverCandidatePlayback(); }
    /** Strict add-on metadata may attach fresh playback or atomically move an
     * observed remote to the receiver's later canonical item. URL/title heuristics never
     * create library identity, and an in-progress handoff keeps its own scope. */
    private void adoptObservedPlayback(JSONObject current) {
        observeSubtitleSource(current);
        JSONObject bindMedia = null, hydrateContent = null;
        String hydrateSource = null, hydrateRevision = null;
        boolean observeCurrent = true;
        synchronized (stateLock) {
            JSONObject connection = state.optJSONObject("connection");
            JSONObject remote = state.optJSONObject("remote"), media = remote == null ? null : remote.optJSONObject("media");
            JSONObject previousContent = validatedContent(media);
            if (!current.optBoolean("active")) {
                clearCanonicalTransitionLocked();
                resetObservedHydrationLocked();
            } else {
                String source;
                try { source = NativeBackend.publicUrl(current.opt("sourceUrl")); }
                catch (NativeBackend.BackendException ignored) { source = ""; observeCurrent = false; }
                JSONObject content = validatedContent(object("content", current.optJSONObject("content")));
                boolean sourceChanged = media != null && !source.equals(media.optString("url"));
                boolean contentChanged = content != null && previousContent != null
                        && !content.optString("key").equals(previousContent.optString("key"));
                if (previousContent != null && content == null && sourceChanged && !source.isEmpty()) {
                    if (canonicalTransitionSource.isEmpty()) {
                        canonicalTransitionSource = source;
                        canonicalTransitionUntil = SystemClock.elapsedRealtime() + CANONICAL_TRANSITION_GRACE;
                    }
                } else if (content != null || source.isEmpty()) clearCanonicalTransitionLocked();
                // A reusable stream URL cannot prove that the receiver is still on the
                // old canonical item. During tag startup, fail closed until the
                // add-on identity appears; legacy URL-only remotes remain usable.
                if (previousContent != null && (content == null || contentChanged)) observeCurrent = false;
                boolean continuingCanonicalSession = media != null && previousContent != null;
                boolean mayAdopt = !source.isEmpty() && !handoffInProgress && connection != null
                        && connection.optBoolean("configured")
                        && (connection.optBoolean("connected") || continuingCanonicalSession);
                boolean canonicalTransition = content != null && (remote == null || sourceChanged
                        || previousContent == null || contentChanged);
                boolean adopted = false;
                if (mayAdopt && (remote == null || canonicalTransition)) {
                    JSONObject selectedMedia = object("url", source, "contentType", "",
                            "title", content == null ? "Projector video" : content.optString("title"));
                    if (content != null) put(selectedMedia, "content", content);
                    put(state, "remote", object("media", selectedMedia, "tracks", new JSONArray(), "catalog", new JSONArray(),
                            "selectedIndex", JSONObject.NULL, "subtitlesEnabled", false));
                    // Subtitle delivery reuse is source-URL based. A canonical
                    // item change can reuse that URL, so revoke old bytes/tickets
                    // before authorizing any tracks for the newly observed item.
                    clearSubtitleDelivery();
                    cancelQueuedVolume();
                    mediaRevision = UUID.randomUUID().toString();
                    clearCanonicalTransitionLocked();
                    adopted = true;
                    remote = state.optJSONObject("remote");
                    media = remote == null ? null : remote.optJSONObject("media");
                    previousContent = validatedContent(media);
                } else if (sourceChanged) {
                    resetObservedHydrationLocked();
                }
                if (content == null) {
                    if (adopted) resetObservedHydrationLocked();
                } else {
                    JSONObject selected = state.optJSONObject("remote"), selectedMedia = selected == null ? null : selected.optJSONObject("media");
                    JSONObject selectedContent = validatedContent(selectedMedia);
                    if (selectedMedia != null && source.equals(selectedMedia.optString("url")) && selectedContent != null
                            && content.optString("key").equals(selectedContent.optString("key"))) {
                        if (adopted) {
                            bindMedia = copy(selectedMedia);
                            resetObservedHydrationLocked();
                            hydrationSource = source;
                            hydrationContentKey = content.optString("key");
                            hydrationRevision = mediaRevision;
                        }
                        if (source.equals(hydrationSource) && content.optString("key").equals(hydrationContentKey)
                                && mediaRevision.equals(hydrationRevision)) {
                            hydrateSource = source;
                            hydrateContent = copy(content);
                            hydrateRevision = mediaRevision;
                        }
                    }
                }
            }
        }
        if (bindMedia != null && watchHistory != null && watchHistory.bind("luma", bindMedia)) watchHistory.observe("luma", current);
        else if (observeCurrent && watchHistory != null) watchHistory.observe("luma", current);
        if (hydrateContent != null) scheduleObservedHydration(hydrateSource, hydrateContent, hydrateRevision);
    }
    private void resetObservedHydrationLocked() {
        hydrationSource = hydrationContentKey = hydrationRevision = "";
        hydrationInFlight = hydrationComplete = hydrationApplied = false;
        hydrationRetryAt = 0;
    }
    private void clearCanonicalTransitionLocked() {
        canonicalTransitionSource = "";
        canonicalTransitionUntil = 0;
    }
    private void scheduleObservedHydration(String source, JSONObject content, String revision) {
        final String key = content.optString("key");
        synchronized (stateLock) {
            long now = SystemClock.elapsedRealtime();
            if (destroyed || hydrationInFlight || hydrationComplete || now < hydrationRetryAt
                    || !source.equals(hydrationSource) || !key.equals(hydrationContentKey)
                    || !revision.equals(hydrationRevision) || !revision.equals(mediaRevision)) return;
            hydrationInFlight = true;
        }
        JSONObject selection = object("kind", content.optString("kind"), "id", content.optString("contentId"));
        if ("tv".equals(content.optString("kind"))) {
            put(selection, "season", content.optInt("season"));
            put(selection, "episode", content.optInt("episode"));
        }
        try {
            catalogNetwork.execute(() -> {
                JSONObject result = null; String failure = null;
                try { result = catalogClient.handle("hydrate", selection); }
                catch (CatalogClient.CatalogException unavailable) { failure = unavailable.code; }
                catch (RuntimeException unavailable) { failure = "CATALOG_UNAVAILABLE"; }
                JSONObject hydrated = result; String error = failure;
                main.post(() -> finishObservedHydration(source, key, revision, hydrated, error));
            });
        } catch (RejectedExecutionException busy) {
            synchronized (stateLock) {
                if (source.equals(hydrationSource) && key.equals(hydrationContentKey) && revision.equals(hydrationRevision)) {
                    hydrationInFlight = false;
                    hydrationRetryAt = SystemClock.elapsedRealtime() + OBSERVED_HYDRATION_RETRY;
                }
            }
        }
    }
    private void finishObservedHydration(String source, String key, String revision, JSONObject result, String failure) {
        JSONObject nextContent = null;
        boolean refreshNext = false;
        synchronized (stateLock) {
            if (!source.equals(hydrationSource) || !key.equals(hydrationContentKey) || !revision.equals(hydrationRevision)) return;
            hydrationInFlight = false;
            if (destroyed || !revision.equals(mediaRevision)) return;
            JSONObject playback = state.optJSONObject("playback"), remote = state.optJSONObject("remote"), media = remote == null ? null : remote.optJSONObject("media");
            JSONObject currentContent = validatedContent(media);
            if (failure != null || playback == null || !playback.optBoolean("active") || !source.equals(playback.optString("sourceUrl"))
                    || media == null || !source.equals(media.optString("url")) || currentContent == null || !key.equals(currentContent.optString("key"))) {
                hydrationRetryAt = SystemClock.elapsedRealtime() + OBSERVED_HYDRATION_RETRY;
                return;
            }
            JSONObject content = validatedContent(object("content", result == null ? null : result.optJSONObject("content")));
            JSONArray tracks = result == null ? null : result.optJSONArray("tracks");
            if (content == null || !key.equals(content.optString("key")) || tracks == null || tracks.length() > 128) {
                hydrationRetryAt = SystemClock.elapsedRealtime() + OBSERVED_HYDRATION_RETRY;
                return;
            }
            try {
                JSONObject hydratedMedia = object("url", source, "contentType", clean(media.optString("contentType"), 100),
                        "title", clean(result.optString("title", content.optString("title")), 200), "content", content);
                JSONObject updated = hydratedRemote(hydratedMedia, remote, tracks);
                if (remote.has("nextEpisode")) put(updated, "nextEpisode", remote.opt("nextEpisode"));
                put(state, "remote", updated);
            } catch (NativeBackend.BackendException invalid) {
                hydrationRetryAt = SystemClock.elapsedRealtime() + OBSERVED_HYDRATION_RETRY;
                return;
            }
            refreshNext = !hydrationApplied && "tv".equals(content.optString("kind"));
            hydrationApplied = true;
            hydrationComplete = result.optBoolean("subtitleCatalogComplete");
            hydrationRetryAt = hydrationComplete ? 0 : SystemClock.elapsedRealtime() + OBSERVED_HYDRATION_RETRY;
            nextContent = copy(content);
        }
        publish();
        if (refreshNext && nextContent != null) refreshNextEpisode("luma", source, nextContent, revision, 0);
    }
    private static JSONObject hydratedRemote(JSONObject media, JSONObject current, JSONArray sources) throws NativeBackend.BackendException {
        Set<String> available = new HashSet<>();
        for (int i = 0; i < sources.length(); i++) {
            JSONObject source = sources.optJSONObject(i);
            if (source == null) throw NativeBackend.fail("INVALID_SUBTITLE");
            available.add(NativeBackend.publicUrl(source.opt("url")));
        }
        JSONArray bindings = new JSONArray(), oldCatalog = current.optJSONArray("catalog");
        if (oldCatalog != null) for (int i = 0; i < oldCatalog.length(); i++) {
            JSONObject binding = oldCatalog.optJSONObject(i);
            if (binding != null && available.contains(binding.optString("url"))) bindings.put(copy(binding));
        }
        JSONObject observed = object("tracks", current.optJSONArray("tracks") == null ? new JSONArray() : current.optJSONArray("tracks"),
                "catalog", bindings, "selectedIndex", current.has("selectedIndex") ? current.opt("selectedIndex") : JSONObject.NULL,
                "subtitlesEnabled", current.optBoolean("subtitlesEnabled"));
        return remoteData(media, observed, sources);
    }
    private static boolean matches(JSONObject remote, JSONObject playback) {
        JSONObject media = remote == null ? null : remote.optJSONObject("media");
        if (media == null || playback == null || !playback.optBoolean("active") || media.optString("url").isEmpty()
                || !media.optString("url").equals(playback.optString("sourceUrl"))) return false;
        JSONObject expected = validatedContent(media);
        if (expected == null) return true;
        JSONObject observed = validatedContent(object("content", playback.optJSONObject("content")));
        return observed != null && expected.optString("key").equals(observed.optString("key"));
    }
    private String rememberedSource() {
        JSONObject remote = snapshot().optJSONObject("remote"), media = remote == null ? null : remote.optJSONObject("media");
        return media == null ? "" : media.optString("url");
    }
    /** Never retarget a stale notification to another controller's new video. */
    private void mediaAction(String method) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> mediaAction(method)); return; }
        if ("philips".equals(selectedTarget)) { televisionMediaAction(method); return; }
        JSONObject scopedState = snapshot(), scopedRemote = scopedState.optJSONObject("remote");
        JSONObject scopedMedia = scopedRemote == null ? null : scopedRemote.optJSONObject("media");
        JSONObject scopedContent = validatedContent(scopedMedia);
        String expected = scopedMedia == null ? "" : scopedMedia.optString("url");
        String revision = mediaRevision;
        if (expected.isEmpty() || !configured()) return;
        if ("volume-down".equals(method) || "volume-up".equals(method)) {
            if (!hardwareConnected(presentationSnapshot())) return;
            JSONObject volume = scopedState.optJSONObject("volume");
            if (volume != null && volume.optInt("max") > 0) queueVolume(object("value", Math.max(0, Math.min(volume.optInt("max"),
                    volume.optInt("current") + ("volume-up".equals(method) ? 1 : -1))), "expectedSourceUrl", expected), null);
            return;
        }
        if ("mute".equals(method)) cancelQueuedVolume();
        if ((method.startsWith("volume-") || method.equals("mute")) && !hardwareConnected(presentationSnapshot())) return;
        enqueue(work -> {
            try {
                JSONObject current = readMediaPlayback(work);
                synchronized (stateLock) { if (!work.current()) return; put(state, "playback", current); }
                if (!revision.equals(mediaRevision) || !expected.equals(rememberedSource()) || !matches(scopedRemote, current)) {
                    work.post(() -> { notice = "The projector is playing a different video. Open the app to choose it."; publish(); }); return;
                }
                JSONObject args = object("expectedSourceUrl", expected);
                if (scopedContent != null) put(args, "expectedContentKey", scopedContent.optString("key"));
                JSONObject projector = null;
                if (method.startsWith("volume-") || method.equals("mute")) {
                    put(args, "key", method); work.transport.handle("luma.key", args);
                    projector = work.transport.handle("luma.status", new JSONObject());
                } else if (BACK_30.equals(method)) {
                    if (!canRewind(current)) { work.post(this::publish); return; }
                    put(args, "position", rewindPosition(current)); work.transport.handle("receiver.seek", args);
                } else work.transport.handle(method, args);
                JSONObject observed = readMediaPlayback(work);
                synchronized (stateLock) {
                    if (!work.current()) return;
                    put(state, "playback", observed); put(state, "connection", work.transport.connectionSnapshot());
                    if (projector != null && projector.optJSONObject("volume") != null) observeVolume(projector.optJSONObject("volume"));
                }
                work.post(this::publish);
            } catch (NativeBackend.BackendException failure) {
                synchronized (stateLock) { if (!work.current()) return; put(state, "connection", work.transport.connectionSnapshot()); observeReceiverFailure(failure); }
                work.post(() -> { notice = failure.getMessage(); publish(); if (!connected()) demote(); else schedulePoll(1000); });
            }
        }, null, true);
    }
    private void openNextEpisode() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::openNextEpisode); return; }
        SystemNextScope scope = captureSystemNext();
        if (scope == null) return;
        notice = "Preparing the next episode…"; publish();
        boolean accepted = enqueue(work -> {
            try {
                JSONObject observed = readMediaPlayback(work);
                work.post(() -> {
                    synchronized (stateLock) { put(state, "playback", copy(observed)); }
                    adoptObservedPlayback(observed);
                    if (!systemNextScopeCurrent(scope, observed)) {
                        failSystemNext(scope, "The projector changed videos. Next episode was not started."); return;
                    }
                    publish(); resolveSystemNext(scope);
                });
            } catch (NativeBackend.BackendException failure) {
                work.post(() -> failSystemNext(scope, "Could not verify projector playback. Try Next again."));
            }
        }, null, true);
        if (!accepted) failSystemNext(scope, "Wait for the current projector control, then try Next again.");
    }
    private SystemNextScope captureSystemNext() {
        synchronized (stateLock) {
            JSONObject connection = state.optJSONObject("connection"), remote = state.optJSONObject("remote"), playback = state.optJSONObject("playback");
            JSONObject media = remote == null ? null : remote.optJSONObject("media"), content = validatedContent(media);
            JSONObject next = remote == null ? null : remote.optJSONObject("nextEpisode"), target = nextContent(next);
            if (destroyed || !"luma".equals(selectedTarget) || connection == null || !connection.optBoolean("configured")
                    || media == null || content == null || target == null || !matches(remote, playback)
                    || !content.optString("contentId").equals(target.optString("contentId"))
                    || target.optInt("season") < content.optInt("season")
                    || target.optInt("season") == content.optInt("season")
                            && target.optInt("episode") <= content.optInt("episode")) return null;
            SystemNextScope active = systemNextScope;
            if (active != null && active.revision.equals(mediaRevision) && active.targetKey.equals(target.optString("key"))) return null;
            if (active != null) {
                systemNextScope = null;
                releaseSystemNextWake(active);
            }
            JSONObject selection = object("kind", "tv", "id", target.optString("contentId"), "season", target.optInt("season"),
                    "episode", target.optInt("episode"), "resume", true);
            PowerManager.WakeLock wakeLock = getSystemService(PowerManager.class)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LumaRemote:SystemNext");
            // Catalog selection can use the fixed provider and its bounded web
            // fallback before the receiver verification/open sequence begins.
            wakeLock.setReferenceCounted(false); wakeLock.acquire(150000);
            SystemNextScope scope = new SystemNextScope(++systemNextAttempt, generation, mediaRevision, media.optString("url"),
                    content.optString("key"), target.optString("key"), UUID.randomUUID().toString(), selection, wakeLock);
            systemNextScope = scope;
            return scope;
        }
    }
    private static JSONObject nextContent(JSONObject next) {
        if (next == null || !"tv".equals(next.optString("kind"))) return null;
        return WatchHistory.contentFromCatalog("tv", next.optString("id"), Integer.valueOf(next.optInt("season", -1)),
                Integer.valueOf(next.optInt("episode", -1)), next.optString("title"));
    }
    private boolean systemNextScopeCurrent(SystemNextScope scope, JSONObject observed) {
        synchronized (stateLock) {
            if (scope == null || systemNextScope != scope || destroyed || scope.session != generation
                    || !scope.revision.equals(mediaRevision) || !"luma".equals(selectedTarget)) return false;
            JSONObject connection = state.optJSONObject("connection"), remote = state.optJSONObject("remote"), playback = state.optJSONObject("playback");
            JSONObject media = remote == null ? null : remote.optJSONObject("media"), content = validatedContent(media);
            JSONObject target = nextContent(remote == null ? null : remote.optJSONObject("nextEpisode"));
            if (connection == null || !connection.optBoolean("configured") || media == null || content == null || target == null
                    || !scope.source.equals(media.optString("url")) || !scope.contentKey.equals(content.optString("key"))
                    || !scope.targetKey.equals(target.optString("key")) || !matches(remote, playback)) return false;
            return observed == null || playbackMatches(observed, scope.source, scope.contentKey);
        }
    }
    private boolean systemNextAttemptCurrent(SystemNextScope scope) {
        synchronized (stateLock) {
            return scope != null && systemNextScope == scope && !destroyed && scope.session == generation
                    && scope.revision.equals(mediaRevision) && "luma".equals(selectedTarget);
        }
    }
    private boolean currentSystemNextWrite(String token) {
        SystemNextScope scope = systemNextScope;
        return token != null && scope != null && token.equals(scope.writeToken) && systemNextScopeCurrent(scope, null);
    }
    private static boolean playbackMatches(JSONObject playback, String source, String contentKey) {
        if (playback == null || !playback.optBoolean("active") || !source.equals(playback.optString("sourceUrl"))) return false;
        JSONObject content = validatedContent(object("content", playback.optJSONObject("content")));
        return content != null && contentKey.equals(content.optString("key"));
    }
    private void resolveSystemNext(SystemNextScope scope) {
        chooseCatalog(scope.selection, () -> systemNextScopeCurrent(scope, null), new Callback() {
            @Override public void success(JSONObject media) {
                try {
                    JSONObject content = validatedContent(media);
                    if (!systemNextScopeCurrent(scope, null) || content == null || !scope.targetKey.equals(content.optString("key"))) {
                        throw NativeBackend.fail("SOURCE_CHANGED");
                    }
                    JSONObject prepared = remoteData(media, new JSONObject(), media.optJSONArray("tracks"));
                    launchSystemNext(scope, copy(media), prepared);
                } catch (NativeBackend.BackendException invalid) {
                    failSystemNext(scope, "The projector changed videos. Next episode was not started.");
                }
            }
            @Override public void failure(String code, String message) {
                failSystemNext(scope, "Could not prepare the next episode. Current playback was not changed.");
            }
        });
    }
    private void launchSystemNext(SystemNextScope scope, JSONObject media, JSONObject prepared) {
        JSONObject targetContent = validatedContent(media);
        if (targetContent == null || !scope.targetKey.equals(targetContent.optString("key")) || media.optString("url").isEmpty()) {
            failSystemNext(scope, "Could not prepare the next episode. Current playback was not changed."); return;
        }
        final String targetSource = media.optString("url"), targetKey = targetContent.optString("key");
        boolean accepted = enqueue(work -> {
            try {
                JSONObject before = readMediaPlayback(work);
                if (!systemNextScopeCurrent(scope, before)) throw NativeBackend.fail("SOURCE_CHANGED");
                JSONObject args = object("media", media, "expectedSourceUrl", targetSource,
                        "expectedContentKey", targetKey, "expectedCurrentSourceUrl", scope.source,
                        "expectedCurrentContentKey", scope.contentKey, "sourceToken", scope.writeToken);
                NativeBackend.BackendException openFailure = null;
                try { work.transport.handle("receiver.open", args); }
                catch (NativeBackend.BackendException failure) {
                    if ("SOURCE_CHANGED".equals(failure.code)) throw failure;
                    openFailure = failure;
                }
                JSONObject observed = awaitSystemNextPlayback(work, scope, targetSource, targetKey,
                        openFailure == null ? 8000 : 2500);
                if (openFailure != null && observed == null) throw openFailure;
                boolean resumeApplied = media.optDouble("resumeSeconds", 0) < 1;
                if (observed != null && !resumeApplied) {
                    double requestedResume = media.optDouble("resumeSeconds");
                    try {
                        work.transport.handle("receiver.seek", object("position", requestedResume,
                                "expectedSourceUrl", targetSource, "expectedContentKey", targetKey,
                                "sourceToken", scope.writeToken));
                        JSONObject afterSeek = awaitSystemNextPlayback(work, scope, targetSource, targetKey, 1500);
                        if (afterSeek != null) {
                            observed = afterSeek;
                            double applied = afterSeek.optDouble("positionSeconds", -1);
                            resumeApplied = applied >= Math.max(0, requestedResume - 5) && applied <= requestedResume + 20;
                        }
                    } catch (NativeBackend.BackendException ignored) { /* Accepted playback remains usable from its beginning. */ }
                }
                JSONObject finalObserved = observed; boolean finalResumeApplied = resumeApplied;
                work.post(() -> finishSystemNext(scope, media, prepared, finalObserved, finalResumeApplied));
            } catch (NativeBackend.BackendException failure) {
                work.post(() -> failSystemNext(scope, "Could not start the next episode. Current playback may have changed; refresh before retrying."));
            }
        }, null, true);
        if (!accepted) failSystemNext(scope, "Wait for the current projector control, then try Next again.");
    }
    private JSONObject awaitSystemNextPlayback(Work work, SystemNextScope scope, String source, String key, long timeoutMs)
            throws NativeBackend.BackendException {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        do {
            if (!work.current() || !systemNextAttemptCurrent(scope)) throw NativeBackend.fail("SOURCE_CHANGED");
            try {
                JSONObject observed = work.transport.handle("receiver.status", new JSONObject());
                if (playbackMatches(observed, source, key)) return observed;
            } catch (NativeBackend.BackendException failure) {
                if ("RECEIVER_AUTH".equals(failure.code) || "RECEIVER_CONFIG".equals(failure.code)) throw failure;
            }
            if (SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(150);
        } while (SystemClock.elapsedRealtime() < deadline);
        return null;
    }
    private void finishSystemNext(SystemNextScope scope, JSONObject media, JSONObject prepared, JSONObject observed, boolean resumeApplied) {
        JSONObject targetContent = validatedContent(media);
        String revision;
        synchronized (stateLock) {
            if (targetContent == null || scope == null || systemNextScope != scope || destroyed || scope.session != generation
                    || !scope.revision.equals(mediaRevision) || !"luma".equals(selectedTarget)) {
                releaseSystemNextWake(scope); schedulePoll(0); return;
            }
            systemNextScope = null;
            revision = UUID.randomUUID().toString(); mediaRevision = revision;
            clearCanonicalTransitionLocked(); resetObservedHydrationLocked();
            put(state, "remote", copy(prepared));
            if (playbackMatches(observed, media.optString("url"), targetContent.optString("key"))) put(state, "playback", copy(observed));
            else state.remove("playback");
        }
        clearSubtitleDelivery(); cancelQueuedVolume();
        releaseSystemNextWake(scope);
        notice = resumeApplied ? "Next episode started" : "Next episode started at the beginning";
        publish(); schedulePoll(0);
        if (observed != null) rememberWatchHandoff(media, "luma", resumeApplied);
        else awaitSystemNextReady(media, revision, resumeApplied, 0);
    }
    private void awaitSystemNextReady(JSONObject media, String revision, boolean resumeApplied, int attempt) {
        if (destroyed || !revision.equals(mediaRevision) || attempt > 24) return;
        JSONObject all = presentationSnapshot(), remote = all.optJSONObject("remote"), playback = all.optJSONObject("playback");
        if (sameMediaIdentity(media, remote == null ? null : remote.optJSONObject("media")) && matches(remote, playback)) {
            rememberWatchHandoff(media, "luma", resumeApplied); return;
        }
        main.postDelayed(() -> awaitSystemNextReady(media, revision, resumeApplied, attempt + 1), 500);
    }
    private void failSystemNext(SystemNextScope scope, String message) {
        boolean current;
        synchronized (stateLock) {
            current = systemNextScope == scope;
            if (current) systemNextScope = null;
        }
        releaseSystemNextWake(scope);
        if (!current) return;
        notice = message; publish(); schedulePoll(0);
    }
    private static void releaseSystemNextWake(SystemNextScope scope) {
        if (scope != null && scope.wakeLock != null && scope.wakeLock.isHeld()) scope.wakeLock.release();
    }
    private void cancelSystemNext() {
        SystemNextScope scope;
        synchronized (stateLock) { scope = systemNextScope; systemNextScope = null; }
        releaseSystemNextWake(scope);
    }
    private final Runnable poll = () -> {
        if (destroyed || !foreground || !needsReceiverPolling() || pollQueued) return;
        pollQueued = true;
        boolean queued = enqueue(work -> {
            try {
                if (configured()) {
                    try {
                        JSONObject current = work.transport.handle("receiver.status", new JSONObject());
                        synchronized (stateLock) { if (!work.current()) return; put(state, "playback", current); }
                        adoptObservedPlayback(current);
                        // Show fresh playback (and enable controls) before any background syncing.
                        work.post(this::publish);
                    } catch (NativeBackend.BackendException failure) {
                        synchronized (stateLock) { if (!work.current()) return; observeReceiverFailure(failure); }
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
                work.post(this::publish);
                if (configured() && work.current()) {
                    try {
                        if (lastLibrarySync == 0 || SystemClock.elapsedRealtime() - lastLibrarySync >= LIBRARY_SYNC_MS) syncLibrary(work);
                        syncThumbnails(work);
                    } catch (RuntimeException ignored) { /* background sync never blocks controls */ }
                }
            } finally {
                work.post(() -> { pollQueued = false; if (!needsReceiverPolling()) demote(); publish(); schedulePoll(POLL_MS); });
            }
        }, null);
        if (!queued) { pollQueued = false; schedulePoll(POLL_MS); }
    };
    /** LUMA Receiver answers locally in a few milliseconds, so a tighter poll costs little. */
    private static final long POLL_MS = 2000;
    /**
     * Two-way Library sync with the projector, which records progress itself even
     * when every phone is off. Pull uses the receiver's change cursor; push sends
     * this phone's newer progress. The receiver resolves conflicts last-writer-wins,
     * so repeated or crossing syncs converge and stop echoing after one round.
     */
    private void syncLibrary(Work work) {
        if (libraryStore == null) return;
        lastLibrarySync = SystemClock.elapsedRealtime();
        android.content.SharedPreferences sync = getSharedPreferences("library-sync", MODE_PRIVATE);
        String origin = sync.getString("origin", null);
        if (origin == null) { origin = "phone-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20); sync.edit().putString("origin", origin).apply(); }
        try {
            String receiver = null;
            long since = 0;
            for (int page = 0; page < 5 && work.current(); page++) {
                JSONObject pulled = work.transport.handle("receiver.libraryPull", object("since", (int) Math.min(Integer.MAX_VALUE, since)));
                String reported = pulled.optString("origin");
                if (!reported.matches("[A-Za-z0-9_-]{8,64}")) return;
                if (receiver == null) {
                    receiver = reported;
                    long saved = sync.getLong("cursor:" + receiver, 0);
                    // The first page doubles as identity discovery; restart from the saved cursor.
                    if (saved != 0) { since = saved; continue; }
                } else if (!receiver.equals(reported)) return;
                applyPulled(pulled);
                since = pulled.optLong("cursor", since);
                sync.edit().putLong("cursor:" + receiver, since).apply();
                if (!pulled.optBoolean("more")) break;
            }
            if (receiver == null || !work.current()) return;
            String pushedKey = "pushed:" + receiver;
            long pushed = sync.getLong(pushedKey, 0), newest = pushed;
            JSONArray local = libraryStore.progressSince(pushed, 200), records = new JSONArray();
            for (int i = 0; i < local.length(); i++) {
                JSONObject record = local.optJSONObject(i);
                long updated = record == null ? 0 : record.optLong("updatedAt");
                if (record == null || updated <= 0) continue;
                newest = Math.max(newest, updated);
                records.put(object("key", record.optString("key"), "type", "progress", "updatedAt", updated, "origin", origin, "data", record));
            }
            if (records.length() > 0) {
                work.transport.handle("receiver.libraryPush", object("records", records));
                sync.edit().putLong(pushedKey, newest).apply();
            }
        } catch (NativeBackend.BackendException | RuntimeException failure) {
            android.util.Log.i("LumaLibrarySync", failure instanceof NativeBackend.BackendException ? ((NativeBackend.BackendException) failure).code : "SYNC_FAILED");
        }
    }
    /**
     * Shares scrub thumbnails through the projector: frames another phone already
     * generated for this episode are downloaded (so this phone skips decoding them),
     * and frames this phone generated are uploaded for the others.
     */
    private void syncThumbnails(Work work) {
        String key = scrubPreviewContentKey;
        if (key == null || key.isEmpty() || scrubPreviews == null) return;
        if (!key.equals(sharedThumbnailKey)) { sharedThumbnailKey = key; sharedThumbnails.clear(); lastThumbnailSync = 0; }
        long now = SystemClock.elapsedRealtime();
        if (lastThumbnailSync != 0 && now - lastThumbnailSync < 5_000) return;
        lastThumbnailSync = now;
        try {
            JSONArray remote = work.transport.handle("receiver.thumbsList", object("contentKey", key)).optJSONArray("positions");
            java.util.Set<Long> local = new java.util.HashSet<>(scrubPreviews.positions(key));
            JSONArray wanted = new JSONArray();
            for (int i = 0; remote != null && i < remote.length(); i++) {
                long position = remote.optLong(i, -1);
                if (position < 0) continue;
                sharedThumbnails.add(position);
                if (!local.contains(position) && wanted.length() < 40) wanted.put(position);
            }
            for (int start = 0; start < wanted.length() && work.current(); start += 40) {
                JSONArray batch = new JSONArray();
                for (int i = start; i < Math.min(wanted.length(), start + 40); i++) batch.put(wanted.get(i));
                JSONArray frames = work.transport.handle("receiver.thumbsGet", object("contentKey", key, "positions", batch)).optJSONArray("frames");
                for (int i = 0; frames != null && i < frames.length(); i++) {
                    JSONObject frame = frames.optJSONObject(i);
                    if (frame == null) continue;
                    try { scrubPreviews.accept(key, frame.optLong("position", -1), android.util.Base64.decode(frame.optString("image"), android.util.Base64.DEFAULT)); }
                    catch (IllegalArgumentException invalid) { /* skip one bad frame */ }
                }
            }
            JSONArray upload = new JSONArray();
            for (long position : scrubPreviews.positions(key)) {
                if (upload.length() >= 40) break;
                if (sharedThumbnails.contains(position)) continue;
                byte[] jpeg = scrubPreviews.frameBytes(key, position);
                if (jpeg == null) continue;
                upload.put(object("position", position, "image", android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)));
            }
            for (int start = 0; start < upload.length() && work.current(); start += 20) {
                JSONArray batch = new JSONArray();
                for (int i = start; i < Math.min(upload.length(), start + 20); i++) batch.put(upload.get(i));
                work.transport.handle("receiver.thumbsPut", object("contentKey", key, "frames", batch));
                for (int i = 0; i < batch.length(); i++) sharedThumbnails.add(batch.getJSONObject(i).getLong("position"));
            }
        } catch (NativeBackend.BackendException | org.json.JSONException failure) {
            android.util.Log.i("LumaThumbnails", "SHARE_FAILED");
        }
    }
    private void applyPulled(JSONObject pulled) {
        JSONArray records = pulled.optJSONArray("records");
        if (records == null) return;
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.optJSONObject(i);
            // LibraryStore.record revalidates canonical identity and keeps newer local progress.
            if (record != null && "progress".equals(record.optString("type")) && !record.optBoolean("deleted")) libraryStore.record(record.optJSONObject("data"));
        }
    }
    private void schedulePoll(long delay) {
        main.removeCallbacks(poll);
        if (foreground && needsReceiverPolling()) main.postDelayed(poll, delay);
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
        if (hasReceiverCandidatePlayback()) { schedulePoll(1000); return; }
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
        syncScrubPreviews(all);
        boolean verified = verifiedPlayback(all);
        boolean canRewind = verified && canRewind(playback);
        boolean hasNext = verified && "luma".equals(all.optString("selectedTarget")) && systemNextScope == null
                && remote != null && remote.optJSONObject("nextEpisode") != null;
        long actions = verified ? PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
                | (canRewind ? PlaybackState.ACTION_REWIND : 0)
                | (hasNext ? PlaybackState.ACTION_SKIP_TO_NEXT : 0) : 0;
        int status = verified ? (playback.optBoolean("playing") ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED) : PlaybackState.STATE_NONE;
        long position = verified ? Math.max(0, playback.optLong("positionSeconds")) * 1000 : 0;
        PlaybackState.Builder controls = new PlaybackState.Builder().setActions(actions).setState(status, position,
                verified && playback.optBoolean("playing") && !playback.optBoolean("stale") ? 1f : 0f, SystemClock.elapsedRealtime());
        if (verified) {
            Bundle revision = new Bundle(); revision.putString("revision", mediaRevision);
            if (canRewind) controls.addCustomAction(new PlaybackState.CustomAction.Builder(BACK_30,
                    "Back 30 seconds", android.R.drawable.ic_media_rew).setExtras(revision).build());
            if (hardwareConnected(all)) {
                controls.addCustomAction(new PlaybackState.CustomAction.Builder(VOLUME_DOWN, "Volume −", R.drawable.ic_volume_down).setExtras(revision).build());
                controls.addCustomAction(new PlaybackState.CustomAction.Builder(VOLUME_UP, "Volume +", R.drawable.ic_volume_up).setExtras(revision).build());
            }
            controls.addCustomAction(new PlaybackState.CustomAction.Builder(STOP, "Stop video", R.drawable.ic_stop).setExtras(revision).build());
            controls.addCustomAction(new PlaybackState.CustomAction.Builder(DISCONNECT, "Disconnect", R.drawable.ic_disconnect).setExtras(revision).build());
        }
        mediaSession.setPlaybackState(controls.build());
        String title = verified ? clean(remote.optJSONObject("media").optString("title"), 200) : "LUMA Remote";
        if (title.isEmpty()) title = "Projector video";
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
        if (title.isEmpty()) title = "Projector video";
        return object("revision", revision, "verified", verified, "playing", playback != null && playback.optBoolean("playing"),
                "title", title, "projector", playbackSummary(all), "hardwareConnected", hardwareConnected(all),
                "canRewind", verified && canRewind(playback), "hasNext", remote != null && remote.optJSONObject("nextEpisode") != null,
                "notice", clean(notice, 200), "foreground", foreground).toString();
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
        if (title.isEmpty()) title = "Projector video";
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        builder.setSmallIcon(R.drawable.ic_projector).setColor(getColor(R.color.projector_accent))
                .setContentTitle(title).setContentText(verified ? playbackSummary(all) : clean(notice, 200))
                .setContentIntent(sessionActivity).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
                .setCategory(mediaCard ? Notification.CATEGORY_TRANSPORT : Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PUBLIC);
        if (verified) {
            boolean playing = playback.optBoolean("playing");
            boolean hardwareConnected = hardwareConnected(all);
            boolean canRewind = canRewind(playback);
            if (!mediaCard && hardwareConnected) builder.addAction(new Notification.Action.Builder(R.drawable.ic_volume_down, "Volume −", pending(VOLUME_DOWN, 4, revision)).build());
            if (canRewind) builder.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_rew, "Back 30 seconds", pending(BACK_30, 7, revision)).build());
            builder.addAction(new Notification.Action.Builder(playing ? R.drawable.ic_pause : R.drawable.ic_play,
                    playing ? "Pause" : "Play", pending(playing ? PAUSE : PLAY, 1, revision)).build());
            boolean hasNext = remote != null && remote.optJSONObject("nextEpisode") != null;
            if (hasNext) builder.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next, "Next episode", pending(NEXT, 6, revision)).build());
            if (!mediaCard && hardwareConnected) builder.addAction(new Notification.Action.Builder(R.drawable.ic_volume_up, "Volume +", pending(VOLUME_UP, 5, revision)).build());
            if (mediaCard) {
                // Keep MediaStyle playback-first and below Android's reliable
                // five-action limit. Volume remains on the ordinary notification
                // and through the session's remote VolumeProvider/custom actions.
                if (!hasNext) builder.addAction(new Notification.Action.Builder(R.drawable.ic_stop, "Stop video", pending(STOP, 2, revision)).build());
                builder.addAction(new Notification.Action.Builder(R.drawable.ic_disconnect, "Disconnect", pending(DISCONNECT, 3, revision)).build());
                builder.setStyle(new Notification.MediaStyle().setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(canRewind ? new int[] {0, 1, 2} : hasNext ? new int[] {0, 1} : new int[] {0}));
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
        return playback != null && playback.optBoolean("stale") ? "Checking LUMA Receiver · Tap a control to retry" : projectorSummary(all);
    }
    private static boolean verifiedPlayback(JSONObject all) {
        JSONObject connection = all.optJSONObject("connection");
        return connection != null && connection.optBoolean("philips".equals(all.optString("selectedTarget")) ? "connected" : "configured")
                && matches(all.optJSONObject("remote"), all.optJSONObject("playback"));
    }
    private static boolean hardwareConnected(JSONObject all) {
        JSONObject connection = all.optJSONObject("connection");
        if (connection == null) return false;
        if ("philips".equals(all.optString("selectedTarget"))) return connection.optBoolean("connected");
        // Direct receiver readiness keeps casting and playback controls alive,
        // but hardware keys require EShare's independent native socket.
        return connection.has("nativeConnected") ? connection.optBoolean("nativeConnected") : connection.optBoolean("connected");
    }
    private static boolean canRewind(JSONObject playback) {
        return playback != null && playback.optBoolean("active") && playback.optBoolean("canSeek")
                && playback.optDouble("durationSeconds", 0) > 0 && playback.optDouble("positionSeconds", 0) > 0;
    }
    private static long rewindPosition(JSONObject playback) {
        return Math.max(0, (long) Math.floor(playback.optDouble("positionSeconds", 0)) - 30);
    }
    private PendingIntent pending(String action, int requestCode, String revision) {
        if (NEXT.equals(action)) {
            Intent activity = new Intent(this, MainActivity.class).setAction(ACTION_PLAY_NEXT)
                    .setData(Uri.fromParts("luma-control", revision + ":" + action, null))
                    .putExtra("revision", revision)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            return PendingIntent.getActivity(this, requestCode, activity,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        Intent intent = new Intent(this, ConnectionService.class).setAction(action);
        intent.setData(Uri.fromParts("luma-control", revision + ":" + action, null)).putExtra("revision", revision);
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    @Override public void onDestroy() {
        cancelSystemNext();
        clearScrubPreviews();
        if (scrubPreviews != null) scrubPreviews.close();
        previewIo.shutdownNow();
        scrubIo.shutdownNow();
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

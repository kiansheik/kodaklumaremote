package dev.framextv.lumareceiver;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the single ExoPlayer. All player access happens on the main looper;
 * HTTP threads go through {@link ReceiverApp#onMain}. Clients follow state by
 * long-polling {@link #revision}; position is interpolated client-side.
 */
final class PlaybackController implements Player.Listener {
    interface Ui { void onPlaybackChanged(); void bringToFront(); void onDisplayChanged(); void onNotice(String text); }

    static final long PROGRESS_INTERVAL_MS = 10_000;
    private final Context context;
    private final LibraryStore library;
    private final DisplaySettings display;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Object revisionLock = new Object();
    /** Receiver-rendered caption tracks, exposed at stable indices from EXTERNAL_BASE. */
    static final int EXTERNAL_BASE = 512;
    private final List<ExternalCaptions> externals = new ArrayList<>();
    private final ExecutorService captionLoader = Executors.newFixedThreadPool(2);
    private int selectedExternal = -1;
    /** Same-language files queued by auto-next; the earliest one that loads is shown. */
    private final List<ExternalCaptions> fallbackCaptions = new ArrayList<>();
    private ExoPlayer player;
    private MediaRequest current;
    // ---- auto-next and "who was really watching" ----
    static final int MAX_UNATTENDED = 3;
    static final long UP_NEXT_MS = 10_000;
    private final Catalog catalog = new Catalog();
    private final android.content.SharedPreferences settings;
    private boolean currentAutoplayed, upNextPending;
    private int unattended;
    private final Runnable upNext = () -> { upNextPending = false; startNext(true); };
    private Ui ui;
    private long revision, lastStartedAt;
    private String error;
    private boolean seenPlaying;

    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            recordProgress();
            if (player != null && player.isPlaying()) main.postDelayed(this, PROGRESS_INTERVAL_MS);
        }
    };

    PlaybackController(Context context, LibraryStore library, DisplaySettings display) {
        this.context = context.getApplicationContext(); this.library = library; this.display = display;
        settings = this.context.getSharedPreferences("receiver", Context.MODE_PRIVATE);
    }

    boolean autoNext() { return settings.getBoolean("autoNext", false); }
    void setAutoNext(boolean enabled) { settings.edit().putBoolean("autoNext", enabled).apply(); if (!enabled) cancelUpNext(); bump(); }

    /**
     * A command from a person (phone or projector remote). Clears the unattended
     * counter and records where they last were, so after falling asleep during
     * auto-play the real last episode is still known.
     */
    void noteInteraction() {
        unattended = 0; currentAutoplayed = false;
        cancelUpNext();
        if (current == null || current.content == null || player == null) return;
        try {
            JSONObject last = new JSONObject().put("content", new JSONObject(current.content.toString()))
                .put("positionSeconds", player.getCurrentPosition() / 1000.0).put("at", System.currentTimeMillis());
            settings.edit().putString("lastInteraction", last.toString()).apply();
        } catch (Exception ignored) { }
    }

    private JSONObject lastInteraction() {
        try { return new JSONObject(settings.getString("lastInteraction", "{}")); } catch (Exception corrupt) { return new JSONObject(); }
    }

    void cancelUpNext() { if (upNextPending) { upNextPending = false; main.removeCallbacks(upNext); bump(); } }

    /** Main thread. Resolves and opens the following episode on a worker; reports through status. */
    void startNext(boolean automatic) {
        if (current == null || current.content == null || !"tv".equals(current.content.optString("kind"))) return;
        JSONObject content = current.content;
        String language = externalSelected() ? externals.get(selectedExternal).language : settings.getString("captionLanguage", "");
        captionLoader.execute(() -> {
            try {
                JSONObject media = resolveNext(content, language);
                main.post(() -> {
                    try {
                        if (media == null) { if (ui != null) ui.onNotice("That was the last episode."); return; }
                        MediaRequest request = MediaRequest.parse(media);
                        open(request, false, null, null);
                        fallbackCaptions.addAll(externals);
                        currentAutoplayed = automatic;
                        if (automatic) unattended++;
                    } catch (Exception failure) { android.util.Log.w("LumaNext", "OPEN_FAILED"); }
                });
            } catch (Exception failure) {
                android.util.Log.w("LumaNext", "RESOLVE_FAILED " + failure.getClass().getSimpleName());
                main.post(() -> { if (ui != null) ui.onNotice("Couldn't load the next episode."); });
            }
        });
    }

    /** Worker thread: the next episode's media, resuming unfinished progress and keeping the caption language. */
    JSONObject resolveNext(JSONObject content, String language) throws Exception {
        Catalog.Episode next = catalog.next(content.getString("contentId"), content.getInt("season"), content.getInt("episode"));
        if (next == null) return null;
        JSONObject media = catalog.resolve(next);
        JSONObject saved = library.get("framextv:tv:" + next.id + ":" + next.season + ":" + next.episode);
        if (saved != null && !saved.optBoolean("completed") && saved.optDouble("resumeSeconds", 0) > 5) media.put("resumeSeconds", saved.optDouble("resumeSeconds"));
        JSONArray subtitles = captionCandidates(media.optJSONArray("tracks"), language);
        if (subtitles.length() > 0) media.put("subtitles", subtitles);
        media.remove("tracks");
        return media;
    }

    /** Up to three tracks in the language: exact code first, then the same base language ("pt-BR" → "pt"). */
    static JSONArray captionCandidates(JSONArray tracks, String language) throws Exception {
        JSONArray result = new JSONArray();
        if (tracks == null || language == null || language.isEmpty()) return result;
        String base = language.split("-", 2)[0];
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < tracks.length() && result.length() < 3; i++) {
                JSONObject track = tracks.optJSONObject(i);
                String candidate = track == null ? "" : track.optString("language");
                boolean match = pass == 0 ? language.equalsIgnoreCase(candidate)
                    : !language.equalsIgnoreCase(candidate) && base.equalsIgnoreCase(candidate.split("-", 2)[0]);
                if (match) result.put(new JSONObject().put("url", track.getString("url"))
                    .put("language", candidate).put("label", track.optString("label")).put("select", true));
            }
        }
        return result;
    }

    /** Main thread: picture and subtitle style live in the UI (colour matrix, caption overlay). */
    void applyDisplay() { if (ui != null) ui.onDisplayChanged(); }

    void setUi(Ui ui) { this.ui = ui; }

    ExoPlayer player() {
        if (player == null) {
            DefaultRenderersFactory renderers = new DefaultRenderersFactory(context).setEnableDecoderFallback(true);
            // The projector has little RAM; the default buffer held ~70 s of 1080p
            // (about 50 MB). Keep 15-30 s and at most 24 MB.
            DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 30_000, 1_500, 3_000)
                .setTargetBufferBytes(24 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build();
            player = new ExoPlayer.Builder(context, renderers).setLoadControl(load)
                .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build();
            player.addListener(this);
            player.addAnalyticsListener(new androidx.media3.exoplayer.analytics.AnalyticsListener() {
                @Override public void onDroppedVideoFrames(EventTime time, int count, long elapsedMs) { droppedFrames += count; }
            });
        }
        return player;
    }

    long revision() { synchronized (revisionLock) { return revision; } }

    private void bump() {
        synchronized (revisionLock) { revision++; revisionLock.notifyAll(); }
        if (ui != null) ui.onPlaybackChanged();
    }

    /** Blocks an HTTP thread until the revision passes {@code known} or the timeout ends. */
    void awaitChange(long known, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (revisionLock) {
            while (revision <= known) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) return;
                revisionLock.wait(left);
            }
        }
    }

    boolean active() {
        if (current == null || player == null) return false;
        int state = player.getPlaybackState();
        return state == Player.STATE_BUFFERING || state == Player.STATE_READY;
    }

    /**
     * Guards run on the main thread together with the write, so a second phone
     * cannot change videos between the check and the open (Kodi could not offer this).
     */
    JSONObject open(MediaRequest request, boolean onlyIfInactive, String currentUrl, String currentKey) throws Exception {
        if (onlyIfInactive && active()) throw new ApiException("SOURCE_CHANGED", 409);
        if (currentUrl != null && (current == null || !current.url.equals(currentUrl)
                || current.content == null || !current.content.optString("key").equals(currentKey))) throw new ApiException("SOURCE_CHANGED", 409);
        recordProgress();
        current = request; error = null; seenPlaying = false;
        externals.clear(); selectedExternal = -1; fallbackCaptions.clear();
        lastStartedAt = System.currentTimeMillis();
        ExoPlayer exo = player();
        exo.setTrackSelectionParameters(exo.getTrackSelectionParameters().buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).clearOverrides().build());
        exo.setPlaybackSpeed(1f);
        prepare((long) (request.resumeSeconds * 1000));
        for (MediaRequest.Subtitle subtitle : request.subtitles) addExternal(subtitle);
        exo.setPlayWhenReady(true);
        // Casting must surface the receiver even after its screen was closed with Back.
        if (ui != null) ui.bringToFront(); else MainActivity.bringToFront(context);
        bump();
        return new JSONObject().put("accepted", true).put("revision", revision());
    }

    private void prepare(long positionMs) {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
            .setUserAgent(current.headers.containsKey("User-Agent") ? current.headers.get("User-Agent") : "LumaReceiver/1")
            .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(current.headers);
        String mime = MediaRequest.HLS.equals(current.contentType) ? MimeTypes.APPLICATION_M3U8
            : MediaRequest.DASH.equals(current.contentType) ? MimeTypes.APPLICATION_MPD : null;
        MediaItem item = new MediaItem.Builder().setUri(current.url).setMimeType(mime)
            .setMediaMetadata(new MediaMetadata.Builder().setTitle(current.title).build()).build();
        ExoPlayer exo = player();
        exo.setMediaSource(new DefaultMediaSourceFactory(http).createMediaSource(item), Math.max(0, positionMs));
        exo.prepare();
    }

    void expect(JSONObject params) throws ApiException {
        if (current == null) throw new ApiException("NO_VIDEO", 409);
        if (params.has("expectedSourceUrl") && !current.url.equals(params.opt("expectedSourceUrl"))) throw new ApiException("SOURCE_CHANGED", 409);
        if (params.has("expectedContentKey")) {
            Object key = params.opt("expectedContentKey");
            if (current.content == null || !current.content.optString("key").equals(key)) throw new ApiException("SOURCE_CHANGED", 409);
        }
    }

    void setPlaying(boolean playing) { player().setPlayWhenReady(playing); }

    /** For player.next: the current item may be ended, so only the canonical key is checked. */
    void expectContent(Object key) throws ApiException {
        if (current == null || current.content == null || !current.content.optString("key").equals(key)) throw new ApiException("SOURCE_CHANGED", 409);
    }

    void setRate(float rate) throws ApiException {
        if (current == null || player == null) throw new ApiException("NOT_PLAYING", 409);
        player.setPlaybackSpeed(rate);
        bump();
    }

    void seek(double seconds) throws ApiException {
        ExoPlayer exo = player();
        // Seekability is unknown until the media is loaded; ExoPlayer applies an early
        // seek once it is. Only a loaded, unseekable stream (live) is refused.
        if (exo.getPlaybackState() == Player.STATE_READY && !exo.isCurrentMediaItemSeekable()) throw new ApiException("CANNOT_SEEK", 409);
        long target = (long) (seconds * 1000), duration = exo.getDuration();
        if (duration != C.TIME_UNSET) target = Math.min(target, Math.max(0, duration - 1000));
        exo.seekTo(Math.max(0, target));
    }

    void seekBy(long deltaMs) {
        if (player == null || current == null || !player.isCurrentMediaItemSeekable()) return;
        long target = Math.max(0, player.getCurrentPosition() + deltaMs), duration = player.getDuration();
        if (duration != C.TIME_UNSET) target = Math.min(target, Math.max(0, duration - 1000));
        player.seekTo(target);
    }

    void stop() {
        recordProgress();
        main.removeCallbacks(progressTick);
        current = null; error = null; externals.clear(); selectedExternal = -1; fallbackCaptions.clear();
        cancelUpNext();
        if (player != null) { player.stop(); player.clearMediaItems(); }
        bump();
    }

    /**
     * Main thread: registers a caption file the receiver downloads and draws
     * itself. Nothing in the media pipeline changes, so playback never rebuffers.
     */
    ExternalCaptions addExternal(MediaRequest.Subtitle subtitle) throws ApiException {
        if (externals.size() >= MediaRequest.MAX_SUBTITLES) throw new ApiException("INVALID_SUBTITLE");
        ExternalCaptions captions = new ExternalCaptions(subtitle, current == null ? java.util.Collections.emptyMap() : current.headers);
        externals.add(captions);
        // Selection waits for a usable file so a failed download cannot hide the current language.
        captionLoader.execute(() -> {
            captions.load();
            main.post(() -> {
                int ordinal = externals.indexOf(captions);
                // Among auto-next fallbacks keep the earliest file that loaded, whatever order they finish in.
                boolean earlierFallbackShown = fallbackCaptions.contains(captions) && externalSelected()
                    && selectedExternal < ordinal && fallbackCaptions.contains(externals.get(selectedExternal));
                if (captions.selectOnLoad && captions.error == null && ordinal >= 0 && !earlierFallbackShown) selectExternal(ordinal);
                bump();
            });
        });
        bump();
        return captions;
    }

    int externalIndex(ExternalCaptions captions) { return EXTERNAL_BASE + externals.indexOf(captions); }

    /** Main thread: drops a track whose file proved unusable so a rejected add leaves no trace. */
    void discardExternal(ExternalCaptions captions) {
        int ordinal = externals.indexOf(captions);
        // Only the newest entry can go without shifting other tracks' stable indices.
        if (ordinal < 0 || ordinal != externals.size() - 1) return;
        externals.remove(ordinal);
        if (selectedExternal == ordinal) selectedExternal = -1;
        bump();
    }

    private void selectExternal(int ordinal) {
        selectedExternal = ordinal;
        // Remembered so auto-play continues in the language last turned on.
        String language = externals.get(ordinal).language;
        if (!language.isEmpty()) settings.edit().putString("captionLanguage", language).apply();
        // Hide ExoPlayer's own text so only the selected file is drawn.
        if (player != null) player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
    }

    boolean externalSelected() { return selectedExternal >= 0 && selectedExternal < externals.size(); }

    /** Main thread, about ten times a second while a receiver-rendered track is selected. */
    String externalText() {
        if (!externalSelected() || player == null) return "";
        return externals.get(selectedExternal).textAt(player.getCurrentPosition());
    }

    JSONObject tracks(int type) throws Exception {
        JSONArray list = new JSONArray();
        Object currentIndex = JSONObject.NULL;
        boolean disabled = player != null && player.getTrackSelectionParameters().disabledTrackTypes.contains(type);
        if (player != null && current != null) {
            int index = 0;
            for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
                if (group.getType() != type) continue;
                for (int i = 0; i < group.length; i++, index++) {
                    Format format = group.getTrackFormat(i);
                    String name = format.label != null ? format.label : format.language != null ? format.language : "Track " + (index + 1);
                    list.put(new JSONObject().put("index", index).put("name", name)
                        .put("language", format.language == null ? "" : format.language)
                        .put("supported", group.isTrackSupported(i)));
                    if (group.isTrackSelected(i) && currentIndex == JSONObject.NULL) currentIndex = index;
                }
            }
        }
        boolean enabled = !disabled && currentIndex != JSONObject.NULL;
        if (type == C.TRACK_TYPE_TEXT && current != null) {
            for (int i = 0; i < externals.size(); i++) {
                ExternalCaptions captions = externals.get(i);
                list.put(new JSONObject().put("index", EXTERNAL_BASE + i).put("name", captions.name(i + 1))
                    .put("language", captions.language).put("supported", captions.error == null)
                    .put("loading", captions.loading()).put("external", true));
            }
            if (externalSelected()) { currentIndex = EXTERNAL_BASE + selectedExternal; enabled = true; }
        }
        return new JSONObject().put("active", active()).put("enabled", enabled)
            .put("currentIndex", currentIndex).put("tracks", list);
    }

    void selectTrack(int type, Object index) throws ApiException {
        ExoPlayer exo = player();
        TrackSelectionParameters.Builder params = exo.getTrackSelectionParameters().buildUpon();
        if ("off".equals(index)) {
            if (type != C.TRACK_TYPE_TEXT) throw new ApiException("INVALID_INPUT");
            selectedExternal = -1; fallbackCaptions.clear();
            settings.edit().putString("captionLanguage", "").apply();
            exo.setTrackSelectionParameters(params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
            bump();
            return;
        }
        int wanted = MediaRequest.integer(index, 0, 1024), seen = 0;
        if (type == C.TRACK_TYPE_TEXT && wanted >= EXTERNAL_BASE) {
            if (wanted - EXTERNAL_BASE >= externals.size()) throw new ApiException("INVALID_SUBTITLE");
            selectExternal(wanted - EXTERNAL_BASE);
            bump();
            return;
        }
        if (type == C.TRACK_TYPE_TEXT) selectedExternal = -1;
        for (Tracks.Group group : exo.getCurrentTracks().getGroups()) {
            if (group.getType() != type) continue;
            if (wanted < seen + group.length) {
                exo.setTrackSelectionParameters(params.setTrackTypeDisabled(type, false)
                    .setOverrideForType(new TrackSelectionOverride(group.getMediaTrackGroup(), wanted - seen)).build());
                bump();
                return;
            }
            seen += group.length;
        }
        throw new ApiException(type == C.TRACK_TYPE_TEXT ? "INVALID_SUBTITLE" : "INVALID_INPUT");
    }

    JSONObject status() throws Exception {
        JSONObject result = new JSONObject().put("revision", revision()).put("active", active());
        if (current == null || player == null) {
            return withSettings(result.put("playing", false).put("paused", false).put("state", "idle"));
        }
        int state = player.getPlaybackState();
        long duration = player.getDuration();
        result.put("playing", active() && player.getPlayWhenReady()).put("paused", !player.getPlayWhenReady())
            .put("state", error != null ? "error" : state == Player.STATE_BUFFERING ? "buffering" : state == Player.STATE_READY ? "ready"
                : state == Player.STATE_ENDED ? "ended" : "idle")
            .put("positionSeconds", player.getCurrentPosition() / 1000.0)
            .put("rate", player.getPlaybackParameters().speed)
            .put("durationSeconds", duration == C.TIME_UNSET ? 0 : duration / 1000.0)
            .put("bufferedSeconds", player.getBufferedPosition() / 1000.0)
            .put("canSeek", player.isCurrentMediaItemSeekable())
            .put("sourceUrl", current.url).put("title", current.title)
            .put("sampledAt", System.currentTimeMillis());
        if (current.content != null) result.put("content", new JSONObject(current.content.toString()));
        if (error != null) result.put("error", error);
        result.put("ended", state == Player.STATE_ENDED).put("autoplayed", currentAutoplayed).put("upNext", upNextPending);
        return withSettings(result);
    }

    private JSONObject withSettings(JSONObject result) throws Exception {
        JSONObject last = lastInteraction();
        if (last.has("content")) result.put("lastInteraction", last);
        return result.put("autoNext", autoNext());
    }

    String title() { return current == null ? null : current.title; }

    String contentKey() { return current == null || current.content == null ? null : current.content.optString("key"); }

    private int droppedFrames;

    @Override public void onRenderedFirstFrame() { droppedFrames = 0; }

    /** One-line state for the diagnostics log; never includes URLs. */
    String diagnosticSummary() {
        if (player == null || current == null) return "player=idle";
        Format video = player.getVideoFormat();
        return String.format(java.util.Locale.ROOT, "state=%d playing=%b pos=%.0f buffered=%.0f video=%s dropped=%d captions=%d/%d",
            player.getPlaybackState(), player.isPlaying(), player.getCurrentPosition() / 1000.0,
            (player.getBufferedPosition() - player.getCurrentPosition()) / 1000.0,
            video == null ? "-" : video.width + "x" + video.height + "@" + (video.bitrate > 0 ? video.bitrate / 1000 + "k" : "?"),
            droppedFrames, selectedExternal, externals.size());
    }

    private void recordProgress() {
        if (current == null || current.content == null || player == null || !seenPlaying) return;
        long duration = player.getDuration();
        if (duration == C.TIME_UNSET || duration <= 0) return;
        JSONObject content = current.content;
        double position = player.getCurrentPosition() / 1000.0, total = duration / 1000.0;
        long started = lastStartedAt; boolean auto = currentAutoplayed;
        io.execute(() -> library.recordProgress(content, position, total, started, auto));
    }

    @Override public void onIsPlayingChanged(boolean isPlaying) {
        main.removeCallbacks(progressTick);
        if (isPlaying) { seenPlaying = true; main.postDelayed(progressTick, PROGRESS_INTERVAL_MS); }
        else recordProgress();
        bump();
    }

    @Override public void onPlaybackStateChanged(int state) {
        if (state == Player.STATE_ENDED) {
            recordProgress();
            boolean series = current != null && current.content != null && "tv".equals(current.content.optString("kind"));
            if (series && autoNext() && unattended < MAX_UNATTENDED && !upNextPending) {
                upNextPending = true;
                main.postDelayed(upNext, UP_NEXT_MS);
                if (ui != null) ui.onNotice("Next episode in 10 seconds · press Back to cancel");
            } else if (series && autoNext() && ui != null) ui.onNotice("Auto-play paused: no one has touched the remote for a while.");
        }
        bump();
    }

    @Override public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) { bump(); }

    @Override public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) bump();
    }

    @Override public void onTracksChanged(Tracks tracks) { bump(); }

    @Override public void onPlayerError(PlaybackException failure) {
        error = failure.getErrorCodeName();
        android.util.Log.w("LumaPlayer", "PLAYER_ERROR " + error);
        recordProgress();
        bump();
    }
}

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
    interface Ui { void onPlaybackChanged(); void bringToFront(); void onDisplayChanged(); }

    static final long PROGRESS_INTERVAL_MS = 10_000;
    private final Context context;
    private final LibraryStore library;
    private final DisplaySettings display;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Object revisionLock = new Object();
    private final List<MediaRequest.Subtitle> addedSubtitles = new ArrayList<>();
    private ExoPlayer player;
    private MediaRequest current;
    private Ui ui;
    private long revision, lastStartedAt;
    private String error, pendingTextId;
    private boolean seenPlaying, effectsApplied;
    private int subtitleSerial;

    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            recordProgress();
            if (player != null && player.isPlaying()) main.postDelayed(this, PROGRESS_INTERVAL_MS);
        }
    };

    PlaybackController(Context context, LibraryStore library, DisplaySettings display) {
        this.context = context.getApplicationContext(); this.library = library; this.display = display;
    }

    /** Main thread: push picture effects to the player and subtitle style to the UI. */
    void applyDisplay() {
        if (player != null && (display.pictureAdjusted() || effectsApplied)) {
            player.setVideoEffects(display.effects());
            effectsApplied = display.pictureAdjusted();
        }
        if (ui != null) ui.onDisplayChanged();
    }

    void setUi(Ui ui) { this.ui = ui; }

    ExoPlayer player() {
        if (player == null) {
            DefaultRenderersFactory renderers = new DefaultRenderersFactory(context).setEnableDecoderFallback(true);
            player = new ExoPlayer.Builder(context, renderers)
                .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build();
            player.addListener(this);
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
        current = request; error = null; seenPlaying = false; pendingTextId = null;
        addedSubtitles.clear(); addedSubtitles.addAll(request.subtitles);
        lastStartedAt = System.currentTimeMillis();
        ExoPlayer exo = player();
        // Text stays enabled; preferring the first requested language picks it on load.
        TrackSelectionParameters.Builder params = exo.getTrackSelectionParameters().buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).clearOverrides();
        for (MediaRequest.Subtitle subtitle : request.subtitles) {
            if (subtitle.select && !subtitle.language.isEmpty()) { params.setPreferredTextLanguage(subtitle.language); break; }
        }
        exo.setTrackSelectionParameters(params.build());
        prepare((long) (request.resumeSeconds * 1000));
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
        List<MediaItem.SubtitleConfiguration> subtitles = new ArrayList<>();
        int n = 0;
        for (MediaRequest.Subtitle subtitle : addedSubtitles) {
            subtitles.add(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.url))
                .setId("luma-sub-" + (n++)).setMimeType(subtitle.mimeType)
                .setLanguage(subtitle.language.isEmpty() ? null : subtitle.language)
                .setLabel(subtitle.label.isEmpty() ? null : subtitle.label)
                .setSelectionFlags(subtitle.select ? C.SELECTION_FLAG_DEFAULT : 0).build());
        }
        String mime = MediaRequest.HLS.equals(current.contentType) ? MimeTypes.APPLICATION_M3U8
            : MediaRequest.DASH.equals(current.contentType) ? MimeTypes.APPLICATION_MPD : null;
        MediaItem item = new MediaItem.Builder().setUri(current.url).setMimeType(mime)
            .setSubtitleConfigurations(subtitles)
            .setMediaMetadata(new MediaMetadata.Builder().setTitle(current.title).build()).build();
        ExoPlayer exo = player();
        if (display.pictureAdjusted() || effectsApplied) { exo.setVideoEffects(display.effects()); effectsApplied = display.pictureAdjusted(); }
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

    void seek(double seconds) throws ApiException {
        ExoPlayer exo = player();
        if (!exo.isCurrentMediaItemSeekable()) throw new ApiException("CANNOT_SEEK", 409);
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
        current = null; error = null; addedSubtitles.clear(); pendingTextId = null;
        if (player != null) { player.stop(); player.clearMediaItems(); }
        bump();
    }

    /** Re-prepares at the current position with one more side-loaded subtitle. */
    JSONObject addSubtitle(MediaRequest.Subtitle subtitle) throws Exception {
        if (addedSubtitles.size() >= MediaRequest.MAX_SUBTITLES) throw new ApiException("INVALID_SUBTITLE");
        addedSubtitles.add(subtitle);
        boolean playing = player().getPlayWhenReady();
        if (subtitle.select) pendingTextId = "luma-sub-" + (addedSubtitles.size() - 1);
        prepare(player().getCurrentPosition());
        player().setPlayWhenReady(playing);
        bump();
        return new JSONObject().put("accepted", true).put("subtitleSerial", ++subtitleSerial);
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
        return new JSONObject().put("active", active()).put("enabled", !disabled && currentIndex != JSONObject.NULL)
            .put("currentIndex", currentIndex).put("tracks", list);
    }

    void selectTrack(int type, Object index) throws ApiException {
        ExoPlayer exo = player();
        TrackSelectionParameters.Builder params = exo.getTrackSelectionParameters().buildUpon();
        if ("off".equals(index)) {
            if (type != C.TRACK_TYPE_TEXT) throw new ApiException("INVALID_INPUT");
            exo.setTrackSelectionParameters(params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
            bump();
            return;
        }
        int wanted = MediaRequest.integer(index, 0, 1024), seen = 0;
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
            return result.put("playing", false).put("paused", false).put("state", "idle");
        }
        int state = player.getPlaybackState();
        long duration = player.getDuration();
        result.put("playing", active() && player.getPlayWhenReady()).put("paused", !player.getPlayWhenReady())
            .put("state", error != null ? "error" : state == Player.STATE_BUFFERING ? "buffering" : state == Player.STATE_READY ? "ready"
                : state == Player.STATE_ENDED ? "ended" : "idle")
            .put("positionSeconds", player.getCurrentPosition() / 1000.0)
            .put("durationSeconds", duration == C.TIME_UNSET ? 0 : duration / 1000.0)
            .put("bufferedSeconds", player.getBufferedPosition() / 1000.0)
            .put("canSeek", player.isCurrentMediaItemSeekable())
            .put("sourceUrl", current.url).put("title", current.title)
            .put("sampledAt", System.currentTimeMillis());
        if (current.content != null) result.put("content", new JSONObject(current.content.toString()));
        if (error != null) result.put("error", error);
        return result;
    }

    String title() { return current == null ? null : current.title; }

    private void recordProgress() {
        if (current == null || current.content == null || player == null || !seenPlaying) return;
        long duration = player.getDuration();
        if (duration == C.TIME_UNSET || duration <= 0) return;
        JSONObject content = current.content;
        double position = player.getCurrentPosition() / 1000.0, total = duration / 1000.0;
        long started = lastStartedAt;
        io.execute(() -> library.recordProgress(content, position, total, started));
    }

    @Override public void onIsPlayingChanged(boolean isPlaying) {
        main.removeCallbacks(progressTick);
        if (isPlaying) { seenPlaying = true; main.postDelayed(progressTick, PROGRESS_INTERVAL_MS); }
        else recordProgress();
        bump();
    }

    @Override public void onPlaybackStateChanged(int state) {
        if (state == Player.STATE_ENDED) recordProgress();
        bump();
    }

    @Override public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) { bump(); }

    @Override public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) bump();
    }

    @Override public void onTracksChanged(Tracks tracks) {
        if (pendingTextId != null) {
            int index = 0;
            for (Tracks.Group group : tracks.getGroups()) {
                if (group.getType() != C.TRACK_TYPE_TEXT) continue;
                for (int i = 0; i < group.length; i++, index++) {
                    String id = group.getTrackFormat(i).id;
                    if (id != null && id.endsWith(pendingTextId)) {
                        pendingTextId = null;
                        try { selectTrack(C.TRACK_TYPE_TEXT, index); } catch (ApiException ignored) { }
                        return;
                    }
                }
            }
        }
        bump();
    }

    @Override public void onPlayerError(PlaybackException failure) {
        error = failure.getErrorCodeName();
        android.util.Log.w("LumaPlayer", "PLAYER_ERROR " + error);
        recordProgress();
        bump();
    }
}

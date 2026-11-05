package dev.framextv.lumareceiver;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;

import androidx.media3.common.C;
import androidx.media3.common.PriorityTaskManager;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.PriorityDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheSpan;
import androidx.media3.datasource.cache.CacheWriter;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Current-title disk cache for progressive local media.
 *
 * ExoPlayer keeps only its small RAM window. A lower-priority CacheWriter fills
 * app-private disk independently, including while paused. ExoPlayer registers
 * {@link C#PRIORITY_PLAYBACK} only while loading, which preempts this worker's
 * {@link C#PRIORITY_DOWNLOAD} reads. Playback is read-only with respect to this
 * cache so the worker's explicit storage budget cannot be exceeded.
 */
final class PlaybackDiskCache {
    static final long MIB = 1024L * 1024L;
    static final long GIB = 1024L * MIB;
    static final long MIN_RESERVE_BYTES = 512L * MIB;
    static final long MIN_CACHE_BYTES = 128L * MIB;
    static final long MAX_CACHE_BYTES = 8L * GIB;

    static final class Source {
        final DataSource.Factory factory;
        final String cacheKey;
        Source(DataSource.Factory factory, String cacheKey) { this.factory = factory; this.cacheKey = cacheKey; }
    }

    private static final class Resource {
        final String kind, url, key;
        final DataSource.Factory upstream;
        final Map<String, String> headers;
        long length = C.LENGTH_UNSET;

        Resource(String kind, String url, String key, DataSource.Factory upstream, Map<String, String> headers) {
            this.kind = kind; this.url = url; this.key = key; this.upstream = upstream; this.headers = headers;
        }
    }

    private static final class Session {
        final long generation;
        final MediaRequest request;
        final List<Resource> resources = new ArrayList<>();
        boolean scheduled, stopped, restartRequested;
        long cachedBytes, targetBytes, contentBytes = C.LENGTH_UNSET;
        long positionMs, durationMs = C.TIME_UNSET, windowStartBytes, windowEndBytes, videoLength = C.LENGTH_UNSET;
        boolean rolling;
        String state = "waiting";

        Session(long generation, MediaRequest request) {
            this.generation = generation; this.request = request;
            positionMs = Math.max(0, (long) (request.resumeSeconds * 1000));
        }
    }

    private static final class StorageChoice {
        final File root;
        final String label;
        final boolean removable;
        final long usable, budget;

        StorageChoice(File root, String label, boolean removable) {
            this.root = root; this.label = label; this.removable = removable;
            usable = root.getUsableSpace(); budget = cacheBudgetBytes(usable);
        }
    }

    private final PriorityTaskManager priorities = new PriorityTaskManager();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final SimpleCache cache;
    private final long limitBytes;
    private final long usableBytesAtStart;
    private final String storage;
    private long generation;
    private Session session;
    private CacheWriter writer;
    private String inactiveState;

    PlaybackDiskCache(Context context) {
        SimpleCache opened = null;
        long limit = 0;
        long usable = 0;
        String selectedStorage = "none";
        String state = "off";
        try {
            StandaloneDatabaseProvider database = new StandaloneDatabaseProvider(context.getApplicationContext());
            List<StorageChoice> choices = storageChoices(context);
            // Process death cannot run teardown. Sweep residue from every
            // currently mounted candidate, including a card added/removed
            // since the prior process, before choosing the roomiest surface.
            for (StorageChoice choice : choices) SimpleCache.delete(new File(choice.root, "playback-media"), database);
            StorageChoice selected = null;
            for (StorageChoice choice : choices) {
                StorageChoice refreshed = new StorageChoice(choice.root, choice.label, choice.removable);
                if (selected == null || refreshed.budget > selected.budget
                        || refreshed.budget == selected.budget && refreshed.removable && !selected.removable) selected = refreshed;
            }
            if (selected == null) throw new IOException("No cache storage");
            File directory = new File(selected.root, "playback-media");
            limit = selected.budget; usable = selected.usable; selectedStorage = selected.label;
            if (limit >= MIN_CACHE_BYTES) {
                opened = new SimpleCache(directory, new NoOpCacheEvictor(), database);
                opened.checkInitialization();
            } else state = "low-storage";
        } catch (Exception failure) {
            android.util.Log.w("LumaDiskCache", "CACHE_DISABLED " + failure.getClass().getSimpleName());
            if (opened != null) opened.release();
            opened = null; limit = 0; state = "error";
        }
        cache = opened; limitBytes = limit; usableBytesAtStart = usable; storage = selectedStorage; inactiveState = state;
    }

    private static List<StorageChoice> storageChoices(Context context) {
        Map<String, StorageChoice> unique = new LinkedHashMap<>();
        File internal = context.getCacheDir();
        unique.put(internal.getAbsolutePath(), new StorageChoice(internal, "internal", false));
        File[] external = context.getExternalCacheDirs();
        if (external != null) for (File root : external) {
            if (root == null || !Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(root))) continue;
            boolean removable;
            try { removable = Environment.isExternalStorageRemovable(root); }
            catch (RuntimeException unavailable) { removable = false; }
            String path;
            try { path = root.getCanonicalPath(); } catch (IOException unresolved) { path = root.getAbsolutePath(); }
            unique.putIfAbsent(path, new StorageChoice(root, removable ? "sd-card" : "external", removable));
        }
        return new ArrayList<>(unique.values());
    }

    PriorityTaskManager priorities() { return priorities; }

    static long cacheBudgetBytes(long usableBytes) {
        if (usableBytes <= 0) return 0;
        long reserve = Math.max(MIN_RESERVE_BYTES, usableBytes / 10);
        long available = Math.max(0, usableBytes - reserve);
        return available < MIN_CACHE_BYTES ? 0 : Math.min(MAX_CACHE_BYTES, available);
    }

    static boolean supportsPrefetch(MediaRequest request) {
        return request != null && !request.mediaId.isEmpty()
            && !MediaRequest.HLS.equals(request.contentType) && !MediaRequest.DASH.equals(request.contentType);
    }

    static String resourceKey(String mediaId, long generation, String kind) {
        return mediaId + ":" + Long.toHexString(generation) + ":" + kind;
    }

    static long rollingWindowStart(long contentLength, long positionMs, long durationMs, long windowBytes) {
        if (contentLength <= 0 || windowBytes <= 0 || durationMs <= 0) return 0;
        long currentByte = (long) Math.min(contentLength,
            Math.max(0, contentLength * Math.min(1d, (double) positionMs / durationMs)));
        long retainedBehind = Math.min(64L * MIB, windowBytes / 8);
        long start = Math.max(0, currentByte - retainedBehind);
        return Math.max(0, Math.min(start, contentLength - Math.min(contentLength, windowBytes)));
    }

    static double cachedAheadSeconds(long videoLength, long durationMs, long positionMs, long contiguousBytes) {
        if (videoLength <= 0 || durationMs <= 0 || contiguousBytes <= 0) return 0;
        long currentByte = (long) Math.min(videoLength,
            Math.max(0, videoLength * Math.min(1d, (double) Math.max(0, positionMs) / durationMs)));
        long available = Math.max(0, videoLength - currentByte);
        long cached = Math.min(available, contiguousBytes);
        return Math.max(0, (double) cached / videoLength * durationMs / 1000d);
    }

    private long contiguousVideoBytes(Session active) {
        if (cache == null || active.durationMs <= 0) return 0;
        Resource video = null;
        for (Resource resource : active.resources) if ("video".equals(resource.kind)) { video = resource; break; }
        if (video == null || video.length <= 0 || video.length == C.LENGTH_UNSET) return 0;
        long currentByte = (long) Math.min(video.length,
            Math.max(0, video.length * Math.min(1d, (double) active.positionMs / active.durationMs)));
        long available = Math.max(0, video.length - currentByte);
        if (available == 0) return 0;
        return Math.max(0, cache.getCachedLength(video.key, currentByte, available));
    }

    synchronized void begin(MediaRequest request) {
        Session old = session;
        generation++;
        cancelWriterLocked();
        session = cache != null && supportsPrefetch(request) ? new Session(generation, request) : null;
        inactiveState = cache == null ? inactiveState : supportsPrefetch(request) ? "waiting" : "off";
        notifyAll();
        if (old != null) io.execute(() -> clear(old));
    }

    synchronized Source source(MediaRequest request, DataSource.Factory upstream, String url, String kind) {
        Session active = session;
        if (cache == null || active == null || active.request != request) return new Source(upstream, null);
        String key = resourceKey(request.mediaId, active.generation, kind);
        active.resources.add(new Resource(kind, url, key, upstream, request.headers));
        CacheDataSource.Factory factory = new CacheDataSource.Factory()
            .setCache(cache).setUpstreamDataSourceFactory(upstream)
            // The bounded background writer owns all disk writes. Playback can
            // always bypass a locked/missing span instead of waiting on it.
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
        return new Source(factory, key);
    }

    synchronized void start() {
        Session active = session;
        if (cache == null || active == null || active.scheduled || active.resources.isEmpty()) return;
        active.scheduled = true;
        io.execute(() -> fill(active));
    }

    synchronized void updatePosition(long positionMs, long durationMs, boolean discontinuity) {
        Session active = session;
        if (active == null) return;
        active.positionMs = Math.max(0, positionMs);
        if (durationMs > 0 && durationMs != C.TIME_UNSET) active.durationMs = durationMs;
        if (discontinuity && active.rolling && writer != null) {
            active.restartRequested = true;
            cancelWriterLocked();
        }
        notifyAll();
    }

    synchronized void end() {
        Session old = session;
        generation++;
        long endingGeneration = generation;
        session = null;
        cancelWriterLocked();
        inactiveState = cache == null ? inactiveState : old == null ? "off" : "clearing";
        notifyAll();
        if (old != null) io.execute(() -> {
            clear(old);
            synchronized (PlaybackDiskCache.this) {
                if (session == null && generation == endingGeneration) inactiveState = "off";
            }
        });
    }

    synchronized void playbackFailed() {
        if (session == null) return;
        session.stopped = true;
        cancelWriterLocked();
        session.state = "error";
        notifyAll();
    }

    private void cancelWriterLocked() { if (writer != null) writer.cancel(); }

    private boolean current(Session candidate) {
        synchronized (this) { return session == candidate && generation == candidate.generation && !candidate.stopped; }
    }

    private boolean awaitDownloadTurn(Session active) throws InterruptedException {
        while (current(active)) {
            if (priorities.proceedNonBlocking(C.PRIORITY_DOWNLOAD)) return true;
            Thread.sleep(100);
        }
        return false;
    }

    private long probeLength(Session active, Resource resource) throws InterruptedException {
        while (current(active)) {
            if (!awaitDownloadTurn(active)) return C.LENGTH_UNSET;
            DataSource source = new PriorityDataSource(resource.upstream.createDataSource(), priorities, C.PRIORITY_DOWNLOAD);
            try {
                DataSpec head = new DataSpec.Builder().setUri(Uri.parse(resource.url))
                    .setHttpMethod(DataSpec.HTTP_METHOD_HEAD).setHttpRequestHeaders(resource.headers).setKey(resource.key).build();
                long length = source.open(head);
                if (length > 0) return length;
                for (Map.Entry<String, List<String>> header : source.getResponseHeaders().entrySet()) {
                    if (!"Content-Length".equalsIgnoreCase(header.getKey()) || header.getValue().isEmpty()) continue;
                    try { long parsed = Long.parseLong(header.getValue().get(0)); if (parsed > 0) return parsed; }
                    catch (NumberFormatException ignored) { }
                }
                return C.LENGTH_UNSET;
            } catch (PriorityTaskManager.PriorityTooLowException preempted) {
                // Playback needed the network. Retry after its RAM window fills.
            } catch (IOException unavailable) {
                return C.LENGTH_UNSET;
            } finally {
                try { source.close(); } catch (IOException ignored) { }
            }
        }
        return C.LENGTH_UNSET;
    }

    private long cachedBytes(Session active) {
        if (cache == null) return 0;
        long total = 0;
        for (Resource resource : active.resources) {
            for (CacheSpan span : cache.getCachedSpans(resource.key)) total += Math.max(0, span.length);
        }
        return total;
    }

    private void retainWindow(Resource resource, long start, long end) {
        if (cache == null) return;
        for (CacheSpan span : new ArrayList<>(cache.getCachedSpans(resource.key))) {
            if (span.position + span.length <= start || span.position >= end) {
                try { cache.removeSpan(span); }
                catch (RuntimeException failure) { android.util.Log.w("LumaDiskCache", "TRIM_FAILED"); }
            }
        }
    }

    private boolean cacheResource(Session active, Resource resource, long position, long length)
            throws IOException, InterruptedException {
        DataSpec spec = new DataSpec.Builder().setUri(Uri.parse(resource.url)).setHttpRequestHeaders(resource.headers)
            .setKey(resource.key).setPosition(position).setLength(length)
            .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build();
        while (current(active)) {
            if (!awaitDownloadTurn(active)) return false;
            long baseBytes = cachedBytes(active);
            long[] observedBytes = { baseBytes };
            CacheDataSource data = new CacheDataSource.Factory().setCache(cache)
                .setUpstreamDataSourceFactory(resource.upstream)
                .setUpstreamPriorityTaskManager(priorities).setUpstreamPriority(C.PRIORITY_DOWNLOAD)
                .createDataSourceForDownloading();
            CacheWriter local = new CacheWriter(data, spec, null, (requestLength, bytesCached, newBytesCached) -> {
                synchronized (PlaybackDiskCache.this) {
                    if (session == active) {
                        observedBytes[0] = Math.min(limitBytes, observedBytes[0] + Math.max(0, newBytesCached));
                        active.cachedBytes = observedBytes[0];
                    }
                }
            });
            synchronized (this) {
                if (!current(active)) return false;
                writer = local; active.state = "filling";
            }
            try {
                local.cache();
                synchronized (this) {
                    if (session == active) active.cachedBytes = Math.min(limitBytes, cachedBytes(active));
                }
                return current(active);
            } catch (PriorityTaskManager.PriorityTooLowException preempted) {
                // A fresh writer skips spans already committed by the prior
                // attempt, so playback can interrupt as often as necessary.
            } catch (InterruptedIOException canceled) {
                synchronized (this) {
                    if (!current(active)) return false;
                    if (active.restartRequested) {
                        active.restartRequested = false;
                        return true;
                    }
                }
                throw canceled;
            } finally {
                synchronized (this) { if (writer == local) writer = null; }
            }
        }
        return false;
    }

    private void fill(Session active) {
        priorities.add(C.PRIORITY_DOWNLOAD);
        try {
            List<Resource> resources;
            synchronized (this) {
                if (!current(active)) return;
                resources = new ArrayList<>(active.resources);
                // The small companion audio finishes first, preventing a video
                // cache hit from ever outrunning its sound.
                resources.sort(Comparator.comparingInt(resource -> "audio".equals(resource.kind) ? 0 : 1));
            }
            long content = 0;
            boolean allKnown = true;
            for (Resource resource : resources) {
                resource.length = probeLength(active, resource);
                if (!current(active)) return;
                if (resource.length == C.LENGTH_UNSET) allKnown = false; else content += resource.length;
            }
            synchronized (this) {
                if (!current(active)) return;
                active.contentBytes = allKnown ? content : C.LENGTH_UNSET;
                active.targetBytes = allKnown ? Math.min(limitBytes, content) : limitBytes;
            }
            Resource audio = null, video = null;
            for (Resource resource : resources) {
                if ("audio".equals(resource.kind)) audio = resource;
                else if ("video".equals(resource.kind)) video = resource;
            }
            long remaining = limitBytes;
            boolean everythingCached = true;
            if (audio != null) {
                // An incomplete companion cannot replace the source track. On
                // very low storage, reserve the bounded space for video.
                if (audio.length == C.LENGTH_UNSET || audio.length > remaining) everythingCached = false;
                else {
                    if (!cacheResource(active, audio, 0, audio.length)) return;
                    remaining = Math.max(0, limitBytes - cachedBytes(active));
                }
            }
            if (video == null || remaining <= 0) {
                synchronized (this) { if (current(active)) active.state = everythingCached ? "complete" : "limited"; }
                return;
            }
            if (video.length != C.LENGTH_UNSET && video.length <= remaining) {
                if (!cacheResource(active, video, 0, video.length)) return;
                synchronized (this) {
                    if (current(active)) {
                        active.cachedBytes = Math.min(limitBytes, cachedBytes(active));
                        active.state = everythingCached ? "complete" : "limited";
                    }
                }
                return;
            }
            everythingCached = false;
            if (video.length == C.LENGTH_UNSET) {
                if (!cacheResource(active, video, 0, remaining)) return;
                synchronized (this) { if (current(active)) active.state = "limited"; }
                return;
            }

            // The title is larger than safe free space. Keep a rolling range
            // around and ahead of playback, retaining a small amount behind for
            // ordinary back-seeks. Five-MiB cache fragments can straddle the
            // boundary, so leave one fragment of headroom under the hard limit.
            long windowBytes = Math.max(1, remaining - 8L * MIB);
            synchronized (this) {
                if (!current(active)) return;
                active.rolling = true; active.videoLength = video.length;
            }
            while (current(active)) {
                long start, end;
                synchronized (this) {
                    start = rollingWindowStart(video.length, active.positionMs, active.durationMs, windowBytes);
                    end = Math.min(video.length, start + windowBytes);
                    active.windowStartBytes = start; active.windowEndBytes = end;
                }
                retainWindow(video, start, end);
                synchronized (this) { if (current(active)) active.cachedBytes = Math.min(limitBytes, cachedBytes(active)); }
                if (!cacheResource(active, video, start, end - start)) return;
                synchronized (this) {
                    if (!current(active)) return;
                    active.cachedBytes = Math.min(limitBytes, cachedBytes(active));
                    active.state = "limited";
                    while (current(active)
                            && start == rollingWindowStart(video.length, active.positionMs, active.durationMs, windowBytes)) {
                        wait();
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException failure) {
            boolean relevant;
            synchronized (this) {
                relevant = current(active);
                if (relevant) active.state = "error";
            }
            if (relevant) android.util.Log.w("LumaDiskCache", "PREFETCH_STOPPED " + failure.getClass().getSimpleName());
        } finally {
            synchronized (this) { if (session == active) writer = null; }
            priorities.remove(C.PRIORITY_DOWNLOAD);
        }
    }

    private void clear(Session old) {
        if (cache == null) return;
        for (Resource resource : old.resources) {
            try { cache.removeResource(resource.key); }
            catch (RuntimeException failure) { android.util.Log.w("LumaDiskCache", "CLEAR_FAILED"); }
        }
    }

    synchronized JSONObject appendStatus(JSONObject result) throws Exception {
        Session active = session;
        result.put("diskCacheState", active == null ? inactiveState : active.state)
            .put("diskCacheLimitBytes", limitBytes).put("diskCacheUsableBytesAtStart", usableBytesAtStart)
            .put("diskCacheStorage", storage);
        if (active != null) {
            result.put("diskCachedBytes", active.cachedBytes).put("diskCacheTargetBytes", active.targetBytes)
                .put("diskCacheComplete", "complete".equals(active.state));
            if (active.contentBytes != C.LENGTH_UNSET) result.put("diskCacheContentBytes", active.contentBytes);
            if (active.rolling) {
                result.put("diskCacheWindowStartBytes", active.windowStartBytes)
                    .put("diskCacheWindowEndBytes", active.windowEndBytes);
            }
            Resource video = null;
            for (Resource resource : active.resources) if ("video".equals(resource.kind)) { video = resource; break; }
            if (video != null && video.length > 0 && active.durationMs > 0)
                result.put("diskCacheAheadSeconds", cachedAheadSeconds(
                    video.length, active.durationMs, active.positionMs, contiguousVideoBytes(active)));
        }
        return result;
    }

    synchronized String diagnosticSummary() {
        Session active = session;
        if (active == null) return inactiveState;
        return active.state + " " + active.cachedBytes / MIB + "/" + active.targetBytes / MIB + "MiB";
    }
}

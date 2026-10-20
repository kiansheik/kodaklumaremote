package dev.framextv.lumaremote;

import android.os.SystemClock;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Latest-selection jobs only. No URL is accepted from a receiver, and Open is never retried. */
public final class TVCatalogBridge implements AutoCloseable {
    interface Driver {
        JSONObject resolve(JSONObject selection, BooleanSupplier current) throws Exception;
        void open(JSONObject media, BooleanSupplier current) throws Exception;
        void seek(JSONObject media, double seconds, BooleanSupplier current) throws Exception;
        void ready(JSONObject media, boolean resumed, BooleanSupplier current) throws Exception;
    }
    private static final Set<String> ERRORS = new HashSet<>(Arrays.asList(
            "CATALOG_INVALID_INPUT", "CATALOG_UNAVAILABLE", "CATALOG_RESPONSE", "SOURCE_UNAVAILABLE", "SOURCE_UNSUPPORTED",
            "SOURCE_CHANGED", "MSX_NOT_CONNECTED", "MSX_TIMEOUT", "MSX_REJECTED", "RESUME_FAILED", "LIBRARY_UNAVAILABLE",
            "RESOLVE_TIMEOUT", "RESOLVE_FAILED", "RESOLVE_CANCELLED", "CANCELLED", "TIMEOUT"));
    private static final long JOB_MS = 180000;
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final ThreadPoolExecutor workers;
    private volatile Job currentJob;
    private volatile boolean closed;
    private static final class Job {
        final String id = UUID.randomUUID().toString().replace("-", "");
        final long deadline = SystemClock.elapsedRealtime() + JOB_MS;
        final BooleanSupplier session;
        volatile String state = "resolving", error;
        volatile Double resume;
        volatile FutureTask<Void> task;
        Job(BooleanSupplier session) { this.session = session; }
    }
    public TVCatalogBridge() {
        workers = new ThreadPoolExecutor(2, 2, 20, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), runnable -> {
            Thread thread = new Thread(runnable, "TV catalog play"); thread.setDaemon(true); return thread;
        });
        workers.allowCoreThreadTimeOut(true);
    }
    static String errorCode(String code) { return ERRORS.contains(code) ? code : "CATALOG_UNAVAILABLE"; }
    static JSONObject parameters(String action, JSONObject params) throws MsxServer.LibraryException {
        if (params == null) throw invalid();
        JSONObject safe = new JSONObject();
        switch (action) {
            case "list": case "search":
                fields(params, "search".equals(action) ? new String[] {"kind", "page", "query"} : new String[] {"kind", "page"});
                String kind = text(params, "kind");
                if (!("movie".equals(kind) || "tv".equals(kind) || "search".equals(action) && "all".equals(kind))) throw invalid();
                put(safe, "kind", kind); put(safe, "page", params.has("page") ? integer(params, "page", 1, 500) : 1);
                if ("search".equals(action)) {
                    String query = text(params, "query").trim();
                    if (query.isEmpty() || query.length() > 120 || query.matches("(?s).*[\\x00-\\x1f\\x7f].*")) throw invalid();
                    put(safe, "query", query);
                }
                return safe;
            case "details":
                fields(params, "kind", "id"); selection(params, safe, false); return safe;
            case "episodes":
                fields(params, "id", "season"); put(safe, "id", id(params)); put(safe, "season", integer(params, "season", 0, 1000)); return safe;
            case "history":
                fields(params); return safe;
            case "play":
                fields(params, "kind", "id", "season", "episode", "resume"); selection(params, safe, true);
                if (params.has("resume") && !(params.opt("resume") instanceof Boolean)) throw invalid();
                put(safe, "resume", !params.has("resume") || params.optBoolean("resume")); return safe;
            case "playStatus":
                fields(params, "jobId"); String job = text(params, "jobId");
                if (!job.matches("[a-f0-9]{32}")) throw invalid(); put(safe, "jobId", job); return safe;
            default: throw invalid();
        }
    }
    private static void selection(JSONObject params, JSONObject safe, boolean episode) throws MsxServer.LibraryException {
        String kind = text(params, "kind"); if (!("movie".equals(kind) || "tv".equals(kind))) throw invalid();
        put(safe, "kind", kind); put(safe, "id", id(params));
        if (episode && "tv".equals(kind)) {
            put(safe, "season", integer(params, "season", 0, 1000)); put(safe, "episode", integer(params, "episode", 1, 10000));
        } else if (params.has("season") || params.has("episode")) throw invalid();
    }
    private static String id(JSONObject params) throws MsxServer.LibraryException {
        String value = text(params, "id"); if (!value.matches("[0-9]{1,10}")) throw invalid();
        try { long parsed = Long.parseLong(value); if (parsed < 1) throw invalid(); return Long.toString(parsed); }
        catch (NumberFormatException invalid) { throw invalid(); }
    }
    private static String text(JSONObject params, String field) throws MsxServer.LibraryException {
        Object value = params.opt(field); if (!(value instanceof String)) throw invalid(); return (String) value;
    }
    private static int integer(JSONObject params, String field, int min, int max) throws MsxServer.LibraryException {
        Object value = params.opt(field); if (!(value instanceof Number)) throw invalid();
        double number = ((Number) value).doubleValue(); if (!Double.isFinite(number) || number != Math.floor(number) || number < min || number > max) throw invalid();
        return (int) number;
    }
    private static void fields(JSONObject params, String... allowed) throws MsxServer.LibraryException {
        Set<String> names = new HashSet<>(Arrays.asList(allowed));
        for (Iterator<String> it = params.keys(); it.hasNext();) if (!names.contains(it.next())) throw invalid();
    }
    private static MsxServer.LibraryException invalid() { return new MsxServer.LibraryException("CATALOG_INVALID_INPUT"); }

    JSONObject play(JSONObject selection, BooleanSupplier session, Driver driver) throws MsxServer.LibraryException {
        final Job job = new Job(session);
        final JSONObject safe = parameters("play", selection);
        synchronized (jobs) {
            if (closed) throw new MsxServer.LibraryException("LIBRARY_UNAVAILABLE");
            cancelCurrent(); currentJob = job; jobs.put(job.id, job);
            while (jobs.size() > 8) jobs.remove(jobs.keySet().iterator().next());
            job.task = new FutureTask<>(() -> { run(job, safe, driver); return null; });
            workers.purge();
            try { workers.execute(job.task); }
            catch (java.util.concurrent.RejectedExecutionException unavailable) { job.state = "error"; job.error = "LIBRARY_UNAVAILABLE"; }
        }
        // A worker can already advance; the initial receipt still identifies the one accepted job.
        return object("accepted", true, "jobId", job.id, "state", "resolving");
    }
    JSONObject status(String id) throws MsxServer.LibraryException {
        Job job; synchronized (jobs) { job = jobs.get(id); }
        if (job == null) throw invalid();
        if (!terminal(job.state) && !current(job)) { job.state = "cancelled"; job.error = null; }
        JSONObject result = object("jobId", job.id, "state", job.state);
        if (job.error != null) put(result, "errorCode", job.error);
        if (job.resume != null) put(result, "resumeSeconds", job.resume);
        return result;
    }
    private void run(Job job, JSONObject selection, Driver driver) {
        final BooleanSupplier valid = () -> current(job);
        try {
            ensure(job); JSONObject media = driver.resolve(copy(selection), valid); ensure(job);
            if (media == null) throw new MsxServer.LibraryException("SOURCE_UNAVAILABLE");
            media = copy(media);
            JSONObject declared = media.optJSONObject("content");
            JSONObject identity = WatchHistory.contentFromCatalog(selection.optString("kind"), selection.optString("id"),
                    selection.has("season") ? selection.optInt("season") : null, selection.has("episode") ? selection.optInt("episode") : null,
                    declared == null ? media.optString("title", "Video") : declared.optString("title", media.optString("title", "Video")));
            if (identity == null || declared == null || !identity.optString("key").equals(declared.optString("key"))
                    || !identity.optString("watchUrl").equals(declared.optString("watchUrl"))) throw new MsxServer.LibraryException("CATALOG_RESPONSE");
            put(media, "content", identity);
            double resume = 0;
            if (selection.optBoolean("resume") && media.has("resumeSeconds")) {
                Object raw = media.opt("resumeSeconds");
                if (!(raw instanceof Number) || !Double.isFinite(((Number) raw).doubleValue()) || ((Number) raw).doubleValue() < 0 || ((Number) raw).doubleValue() > 17280000) throw new MsxServer.LibraryException("CATALOG_RESPONSE");
                resume = ((Number) raw).doubleValue();
            }
            put(media, "resumeSeconds", resume); job.resume = resume;
            phase(job, "opening"); driver.open(media, valid); ensure(job);
            if (resume > 0) {
                phase(job, "resuming");
                try { driver.seek(media, resume, valid); }
                catch (Exception failed) { ensure(job); throw new MsxServer.LibraryException("RESUME_FAILED"); }
                ensure(job);
            }
            driver.ready(media, resume > 0, valid); phase(job, "ready");
        } catch (Exception failed) {
            if (!current(job) || failed instanceof InterruptedException) { job.error = null; job.state = "cancelled"; }
            else {
                job.error = failed instanceof MsxServer.LibraryException ? errorCode(((MsxServer.LibraryException) failed).code)
                        : failed instanceof MsxServer.MsxException ? errorCode(((MsxServer.MsxException) failed).code) : "CATALOG_UNAVAILABLE";
                job.state = "error";
            }
        }
    }
    private boolean current(Job job) {
        return !closed && currentJob == job && !"cancelled".equals(job.state) && SystemClock.elapsedRealtime() < job.deadline && job.session.getAsBoolean();
    }
    private void ensure(Job job) throws InterruptedException { if (Thread.currentThread().isInterrupted() || !current(job)) throw new InterruptedException(); }
    private void phase(Job job, String state) throws InterruptedException {
        ensure(job);
        synchronized (jobs) {
            if (closed || currentJob != job || "cancelled".equals(job.state)) throw new InterruptedException();
            job.state = state;
        }
    }
    private static boolean terminal(String state) { return "ready".equals(state) || "error".equals(state) || "cancelled".equals(state); }
    private void cancelCurrent() {
        Job old = currentJob;
        if (old != null && !terminal(old.state)) { old.state = "cancelled"; old.error = null; if (old.task != null) old.task.cancel(true); }
    }
    void cancel() { synchronized (jobs) { cancelCurrent(); currentJob = null; jobs.clear(); workers.purge(); } }
    @Override public void close() { closed = true; cancel(); workers.shutdownNow(); }
    private static JSONObject copy(JSONObject source) { try { return new JSONObject(source.toString()); } catch (Exception invalid) { return new JSONObject(); } }
    private static JSONObject object(Object... fields) { JSONObject value = new JSONObject(); for (int i = 0; i < fields.length; i += 2) put(value, (String) fields[i], fields[i + 1]); return value; }
    private static void put(JSONObject value, String key, Object item) { try { value.put(key, item); } catch (Exception ignored) { } }
}

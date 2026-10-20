package dev.framextv.lumaremote;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.http.SslError;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Uses the ordinary site player to expose its declared, unencrypted source. */
public final class WebContentResolver implements AutoCloseable {
    public static final class ResolveException extends Exception {
        public final String code;
        private ResolveException(String code) { super(message(code)); this.code = code; }
    }
    interface Page {
        void load(String url);
        String url();
        void evaluate(String script, ValueCallback<String> callback);
        void destroy();
    }
    interface PageFactory { Page create(JSONObject content, java.util.function.Consumer<String> failure); }
    private static final int MAX_CAPTURE = 256 * 1024, MAX_TRACKS = 128;
    private static final long TIMEOUT_MS = 60000;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicLong generation = new AtomicLong();
    private final PageFactory factory;
    private final String fixtureScript;
    private volatile boolean closed;
    private Job active; // Main thread only.
    private static final class Job {
        final long generation, deadline; final JSONObject content; final BooleanSupplier current; final String script;
        final CountDownLatch ready = new CountDownLatch(1); final AtomicBoolean done = new AtomicBoolean();
        volatile JSONObject result; volatile String error;
        Page page; boolean evaluating;
        Job(long generation, JSONObject content, BooleanSupplier current, String script) {
            this.generation = generation; this.content = content; this.current = current; this.script = script;
            deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        }
    }
    public WebContentResolver(Context context) { this.context = context.getApplicationContext(); factory = this::page; fixtureScript = null; }
    WebContentResolver(PageFactory factory, String script) { context = null; this.factory = factory; fixtureScript = script; }
    /** Worker only. Neither selection nor the caller can supply a navigation URL. */
    public JSONObject resolve(JSONObject selection, BooleanSupplier current) throws ResolveException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw fail("SOURCE_UNAVAILABLE");
        JSONObject content = selection(selection);
        if (closed || current == null || !current(current)) throw fail("CANCELLED");
        final String capture;
        try { capture = fixtureScript == null ? captureAsset() : fixtureScript; } catch (Exception missing) { throw fail("SOURCE_UNAVAILABLE"); }
        String script = capture + "\n;(function(){var r=LumaCapture.capture();if(r&&r.ok&&r.media){LumaCapture.pauseMatchingSource(r.media.url);}return r;}());";
        Job job = new Job(generation.incrementAndGet(), content, current, script);
        main.post(() -> start(job));
        try {
            while (!job.ready.await(100, TimeUnit.MILLISECONDS)) {
                if (!valid(job)) finish(job, null, "CANCELLED");
                else if (SystemClock.elapsedRealtime() >= job.deadline) finish(job, null, "TIMEOUT");
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); finish(job, null, "CANCELLED"); }
        if (job.error != null) throw fail(job.error);
        if (!valid(job)) throw fail("CANCELLED");
        try { return new JSONObject(job.result.toString()); } catch (Exception invalid) { throw fail("SOURCE_UNAVAILABLE"); }
    }
    private void start(Job job) {
        if (!valid(job) || job.done.get()) { finish(job, null, "CANCELLED"); return; }
        if (active != null) { Job previous = active; dispose(previous); finish(previous, null, "CANCELLED"); }
        active = job;
        try {
            job.page = factory.create(job.content, error -> finish(job, null, error));
            job.page.load(job.content.optString("watchUrl"));
            main.postDelayed(() -> poll(job), 1000);
        } catch (Exception unavailable) { finish(job, null, "SOURCE_UNAVAILABLE"); }
    }
    private void poll(Job job) {
        if (job.done.get()) return;
        if (!valid(job)) { finish(job, null, "CANCELLED"); return; }
        if (SystemClock.elapsedRealtime() >= job.deadline) { finish(job, null, "TIMEOUT"); return; }
        // Keep a deadline/cancellation tick alive even if a renderer never calls back.
        main.postDelayed(() -> poll(job), 1000);
        if (job.evaluating || job.page == null) return;
        String url = job.page.url();
        if (url == null || url.equals("about:blank")) return;
        if (!sameContent(url, job.content)) { finish(job, null, "SOURCE_CHANGED"); return; }
        job.evaluating = true;
        try {
            job.page.evaluate(job.script, value -> {
                job.evaluating = false;
                if (job.done.get()) return;
                if (!valid(job)) { finish(job, null, "CANCELLED"); return; }
                if (!sameContent(job.page.url(), job.content)) { finish(job, null, "SOURCE_CHANGED"); return; }
                if (value == null || value.length() > MAX_CAPTURE) { finish(job, null, "SOURCE_UNSUPPORTED"); return; }
                try {
                    JSONObject captured = new JSONObject(value);
                    if (!Boolean.TRUE.equals(captured.opt("ok"))) {
                        if ("DRM_UNSUPPORTED".equals(captured.optString("code"))) finish(job, null, "SOURCE_UNSUPPORTED");
                        return;
                    }
                    finish(job, project(captured, job.content), null);
                } catch (Exception invalid) { finish(job, null, "SOURCE_UNSUPPORTED"); }
            });
        } catch (Exception unavailable) { job.evaluating = false; finish(job, null, "SOURCE_UNAVAILABLE"); }
    }
    private boolean valid(Job job) { return !closed && generation.get() == job.generation && current(job.current); }
    private static boolean current(BooleanSupplier current) { try { return current.getAsBoolean(); } catch (Exception cancelled) { return false; } }
    private void finish(Job job, JSONObject result, String error) {
        if (!job.done.compareAndSet(false, true)) return;
        job.result = result; job.error = error; job.ready.countDown();
        main.post(() -> dispose(job));
    }
    private void dispose(Job job) {
        if (job.page != null) { try { job.page.destroy(); } catch (Exception ignored) { } job.page = null; }
        if (active == job) active = null;
    }
    @Override public void close() {
        closed = true; generation.incrementAndGet();
        main.post(() -> { if (active != null) { Job job = active; finish(job, null, "CANCELLED"); dispose(job); } });
    }
    private String captureAsset() throws Exception {
        try (InputStream input = context.getAssets().open("capture.js"); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) >= 0) { if (bytes.size() + count > MAX_CAPTURE) throw new Exception(); bytes.write(buffer, 0, count); }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    private Page page(JSONObject content, java.util.function.Consumer<String> failure) {
        WebView view = new WebView(context);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false); settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false); settings.setSupportMultipleWindows(false);
        if (Build.VERSION.SDK_INT >= 26) settings.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false);
        view.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView v, boolean dialog, boolean gesture, android.os.Message result) { return false; }
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
        });
        view.setDownloadListener((url, userAgent, disposition, mimeType, length) -> { });
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (request.isForMainFrame()) return !sameContent(url, content);
                try { return !"https".equals(new URI(NativeBackend.publicUrl(url)).getScheme()); } catch (Exception invalid) { return true; }
            }
            @Override public void onPageStarted(WebView v, String url, Bitmap icon) { if (!sameContent(url, content)) failure.accept("SOURCE_CHANGED"); }
            @Override public void onReceivedError(WebView v, WebResourceRequest request, WebResourceError error) { if (request.isForMainFrame()) failure.accept("SOURCE_UNAVAILABLE"); }
            @Override public void onReceivedSslError(WebView v, SslErrorHandler handler, SslError error) {
                handler.cancel();
                if (sameContent(error.getUrl(), content)) failure.accept("SOURCE_UNAVAILABLE");
            }
        });
        // Give the ordinary responsive page a real layout without displaying it.
        view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 1080, 1920);
        return new Page() {
            @Override public void load(String url) { view.loadUrl(url); }
            @Override public String url() { return view.getUrl(); }
            @Override public void evaluate(String script, ValueCallback<String> callback) { view.evaluateJavascript(script, callback); }
            @Override public void destroy() { view.stopLoading(); view.loadUrl("about:blank"); view.destroy(); }
        };
    }
    static JSONObject selection(JSONObject value) throws ResolveException {
        try {
            if (value == null) throw new Exception();
            java.util.Iterator<String> keys = value.keys();
            while (keys.hasNext()) { String key = keys.next(); if (!(key.equals("kind") || key.equals("id") || key.equals("season") || key.equals("episode"))) throw new Exception(); }
            Object rawKind = value.opt("kind"), rawId = value.opt("id");
            if (!(rawId instanceof String) || !((String) rawId).matches("[1-9][0-9]{0,9}")) throw new Exception();
            boolean tv = "tv".equals(rawKind); if (!tv && !"movie".equals(rawKind)) throw new Exception();
            Integer season = tv ? number(value.opt("season"), 0, 9999) : null, episode = tv ? number(value.opt("episode"), 1, 9999) : null;
            if (!tv && (value.has("season") || value.has("episode"))) throw new Exception();
            JSONObject result = WatchHistory.contentFromCatalog((String) rawKind, (String) rawId, season, episode, ""); if (result == null) throw new Exception(); return result;
        } catch (Exception invalid) { throw fail("SOURCE_UNSUPPORTED"); }
    }
    static boolean sameContent(String url, JSONObject content) {
        try {
            URI uri = new URI(url); if (uri.getRawQuery() != null || uri.getRawFragment() != null) return false;
            JSONObject parsed = WatchHistory.contentFromPage(url, ""); return parsed != null && parsed.optString("key").equals(content.optString("key"));
        } catch (Exception invalid) { return false; }
    }
    static JSONObject project(JSONObject captured, JSONObject content) throws ResolveException {
        try {
            if (!Boolean.TRUE.equals(captured.opt("ok"))) throw new Exception(); JSONObject media = captured.getJSONObject("media");
            String url = NativeBackend.publicUrl(media.opt("url")), type = media.optString("contentType", "").toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
            if (!(type.matches("video/(?:mp4|webm)") || type.equals("application/vnd.apple.mpegurl") || type.equals("application/x-mpegurl") || type.equals("application/dash+xml"))) throw new Exception();
            String title = text(media.optString("title", "Video"), 200); JSONArray tracks = new JSONArray(), raw = media.optJSONArray("tracks"); Set<String> seen = new HashSet<>();
            if (raw != null) for (int i = 0; i < Math.min(raw.length(), MAX_TRACKS); i++) {
                JSONObject track = raw.optJSONObject(i); if (track == null) continue;
                String trackUrl; try { trackUrl = NativeBackend.publicUrl(track.opt("url")); } catch (Exception invalid) { continue; }
                String format = track.optString("contentType", "text/vtt").toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
                if (!format.matches("(?:text/(?:vtt|srt|x-ass|x-ssa|ass|ssa|xml)|application/(?:x-subrip|srt|x-ass|x-ssa|ttml\\+xml|xml))") || !seen.add(trackUrl)) continue;
                tracks.put(object("id", tracks.length() + 1, "url", trackUrl, "contentType", format, "language", text(track.optString("language", ""), 64), "label", text(track.optString("label", "Subtitle"), 200), "active", false));
            }
            boolean tv = "tv".equals(content.optString("kind"));
            JSONObject identity = WatchHistory.contentFromCatalog(content.getString("kind"), content.getString("contentId"), tv ? content.getInt("season") : null, tv ? content.getInt("episode") : null, title);
            JSONObject result = object("url", url, "contentType", type, "title", title, "tracks", tracks, "content", identity, "currentTime", 0, "autoSubtitles", false);
            Object duration = media.opt("duration"); if (duration instanceof Number) { double seconds = ((Number) duration).doubleValue(); if (Double.isFinite(seconds) && seconds > 0 && seconds <= 17280000) result.put("duration", seconds); }
            return result;
        } catch (Exception invalid) { throw fail("SOURCE_UNSUPPORTED"); }
    }
    private static int number(Object raw, int min, int max) throws Exception { if (!(raw instanceof Number)) throw new Exception(); double number = ((Number) raw).doubleValue(); if (!Double.isFinite(number) || number != Math.rint(number) || number < min || number > max) throw new Exception(); return (int) number; }
    private static String text(String value, int max) { String clean = value.replaceAll("[\\p{Cntrl}\\u007f-\\u009f\\u202a-\\u202e\\u2066-\\u2069]", " ").trim(); return clean.substring(0, Math.min(max, clean.length())); }
    private static ResolveException fail(String code) { return new ResolveException(code); }
    private static String message(String code) {
        switch (code) {
            case "CANCELLED": return "This video selection was cancelled.";
            case "SOURCE_CHANGED": return "The title changed before its source was ready. Choose it again.";
            case "TIMEOUT": return "The site did not provide a playable video in time. Try again.";
            case "SOURCE_UNSUPPORTED": return "This player did not expose a supported video source.";
            default: return "The video page could not be loaded. Check the phone connection and try again.";
        }
    }
    private static JSONObject object(Object... fields) throws Exception { JSONObject result = new JSONObject(); for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]); return result; }
}

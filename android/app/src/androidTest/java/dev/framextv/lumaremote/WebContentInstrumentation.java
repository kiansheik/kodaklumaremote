package dev.framextv.lumaremote;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.webkit.ValueCallback;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Default lifecycle/DTO fixtures never make a network request. Live is explicit. */
public final class WebContentInstrumentation {
    private interface Check { void run() throws Exception; }
    private static JSONObject object(Object... fields) throws Exception { JSONObject result = new JSONObject(); for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]); return result; }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static JSONObject selected() throws Exception { return object("kind", "tv", "id", "42", "season", 0, "episode", 2); }
    private static JSONObject captured() throws Exception {
        JSONArray tracks = new JSONArray();
        for (int i = 0; i < 23; i++) tracks.put(object("url", "https://media.example/" + i + ".srt", "contentType", "application/x-subrip", "language", i == 0 ? "pt-BR" : "en", "label", "Subtitle", "active", true));
        tracks.put(object("url", "http://127.0.0.1/private", "contentType", "text/vtt"));
        return object("ok", true, "media", object("url", "https://media.example/fresh.m3u8", "contentType", "application/vnd.apple.mpegurl", "title", "Series S0 E2", "currentTime", 123, "duration", 1500, "tracks", tracks));
    }
    private static String resolveResult(WebContentResolver resolver, JSONObject selection, AtomicBoolean current) {
        try { return resolver.resolve(selection, current::get).getJSONObject("content").getString("key"); }
        catch (WebContentResolver.ResolveException error) { return error.code; } catch (Exception error) { return "FIXTURE_ERROR"; }
    }
    public static void run(Instrumentation instrumentation, Bundle args) {
        if ("web-content-live".equals(args.getString("mode"))) { live(instrumentation); return; }
        List<String> names = new ArrayList<>(); List<Check> checks = new ArrayList<>();
        names.add("canonical selection and navigation boundaries"); checks.add(() -> {
            JSONObject content = WebContentResolver.selection(selected());
            require(content.getString("watchUrl").equals("https://framextv.cc/watch/42/0/2"), "Noncanonical initial page");
            require(WebContentResolver.sameContent("https://framextv.xyz/watch/42/0/2", content), "Existing trusted host alias lost");
            for (String bad : new String[]{"https://example.com/watch/42/0/2", "https://framextv.cc/watch/43/0/2", "http://framextv.cc/watch/42/0/2", "https://framextv.cc/watch/42/0/2?url=http://127.0.0.1", "https://framextv.cc/watch/42/0/2#x", "https://user@framextv.cc/watch/42/0/2"}) require(!WebContentResolver.sameContent(bad, content), "Untrusted navigation allowed");
            for (JSONObject bad : new JSONObject[]{object("kind", "movie", "id", "42", "url", "https://framextv.cc/watch/42"), object("kind", "tv", "id", "42", "season", 0, "episode", 0), object("kind", "movie", "id", 42)}) {
                try { WebContentResolver.selection(bad); throw new AssertionError("Bad selection admitted"); } catch (WebContentResolver.ResolveException expected) { require(expected.code.equals("SOURCE_UNSUPPORTED"), "Unsafe selection error"); }
            }
        });
        names.add("declared source projection keeps subtitles and rejects private media"); checks.add(() -> {
            JSONObject result = WebContentResolver.project(captured(), WebContentResolver.selection(selected()));
            require(result.getJSONArray("tracks").length() == 23 && result.getInt("currentTime") == 0 && !result.getBoolean("autoSubtitles"), "Native video-first metadata lost");
            require(result.getJSONObject("content").getString("key").equals("framextv:tv:42:0:2") && !result.getJSONArray("tracks").getJSONObject(0).getBoolean("active"), "Untrusted resume/active caption carried over");
            JSONObject bad = captured(); bad.getJSONObject("media").put("url", "http://192.168.1.2/private.mp4");
            try { WebContentResolver.project(bad, WebContentResolver.selection(selected())); throw new AssertionError("Private source admitted"); } catch (WebContentResolver.ResolveException expected) { require(expected.code.equals("SOURCE_UNSUPPORTED"), "Unsafe projection error"); }
        });
        names.add("superseded renderer is destroyed before a fresh selection"); checks.add(() -> {
            ExecutorService executor = Executors.newFixedThreadPool(2); AtomicInteger created = new AtomicInteger(), alive = new AtomicInteger(), maxAlive = new AtomicInteger(), destroyed = new AtomicInteger(); CountDownLatch firstLoad = new CountDownLatch(1);
            AtomicBoolean firstCurrent = new AtomicBoolean(true), nextCurrent = new AtomicBoolean(true); AtomicReference<ValueCallback<String>> stale = new AtomicReference<>();
            WebContentResolver resolver = new WebContentResolver((content, failure) -> {
                int instance = created.incrementAndGet(); maxAlive.accumulateAndGet(alive.incrementAndGet(), Math::max);
                return new WebContentResolver.Page() {
                    String url; boolean closed;
                    public void load(String value) { url = value; if (instance == 1) firstLoad.countDown(); }
                    public String url() { return url; }
                    public void evaluate(String script, ValueCallback<String> callback) {
                        require(script.contains("LumaCapture.pauseMatchingSource(r.media.url)"), "Captured phone video was not paused");
                        if (instance == 1) stale.set(callback); else try { callback.onReceiveValue(captured().toString()); } catch (Exception error) { throw new RuntimeException(error); }
                    }
                    public void destroy() { require(!closed, "Renderer destroyed twice"); closed = true; alive.decrementAndGet(); destroyed.incrementAndGet(); }
                };
            }, "var LumaCapture={};");
            try {
                Future<String> first = executor.submit(() -> resolveResult(resolver, selected(), firstCurrent)); require(firstLoad.await(2, TimeUnit.SECONDS), "First page was not created");
                // Allow an outstanding JS callback so stale completion is tested too.
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3); while (stale.get() == null && System.nanoTime() < until) Thread.sleep(20);
                require(stale.get() != null, "No outstanding first capture");
                Future<String> second = executor.submit(() -> resolveResult(resolver, selected(), nextCurrent));
                require(first.get(3, TimeUnit.SECONDS).equals("CANCELLED") && second.get(4, TimeUnit.SECONDS).equals("framextv:tv:42:0:2"), "Supersession returned stale media");
                instrumentation.runOnMainSync(() -> stale.get().onReceiveValue("{\"ok\":false}")); instrumentation.runOnMainSync(() -> { });
                require(created.get() == 2 && destroyed.get() == 2 && alive.get() == 0 && maxAlive.get() == 1, "Concurrent or retained renderer");
            } finally { resolver.close(); executor.shutdownNow(); }
        });
        names.add("current-token cancellation and close release a waiting page"); checks.add(() -> {
            ExecutorService executor = Executors.newSingleThreadExecutor(); AtomicBoolean current = new AtomicBoolean(true); AtomicInteger destroyed = new AtomicInteger(); CountDownLatch loaded = new CountDownLatch(1);
            WebContentResolver resolver = new WebContentResolver((content, failure) -> new WebContentResolver.Page() {
                String url; public void load(String value) { url = value; loaded.countDown(); } public String url() { return url; }
                public void evaluate(String script, ValueCallback<String> callback) { /* Deliberately never responds. */ }
                public void destroy() { destroyed.incrementAndGet(); }
            }, "var LumaCapture={};");
            try {
                Future<String> result = executor.submit(() -> resolveResult(resolver, selected(), current)); require(loaded.await(2, TimeUnit.SECONDS), "Page not loaded"); current.set(false);
                require(result.get(2, TimeUnit.SECONDS).equals("CANCELLED"), "Cancelled token kept waiting for renderer");
                instrumentation.runOnMainSync(() -> { }); require(destroyed.get() == 1, "Cancelled renderer retained"); resolver.close();
                require(resolveResult(resolver, selected(), new AtomicBoolean(true)).equals("CANCELLED"), "Closed resolver started another page");
            } finally { resolver.close(); executor.shutdownNow(); }
        });
        int passed = 0, failed = 0; String failure = "";
        for (int i = 0; i < checks.size(); i++) {
            Bundle progress = new Bundle(); progress.putString("class", WebContentInstrumentation.class.getName()); progress.putString("test", names.get(i)); progress.putInt("current", i + 1); instrumentation.sendStatus(1, progress);
            try { checks.get(i).run(); passed++; instrumentation.sendStatus(0, progress); }
            catch (Throwable error) { failed++; failure = names.get(i) + ": " + error.getClass().getSimpleName(); progress.putString("stack", failure); instrumentation.sendStatus(-2, progress); }
        }
        Bundle result = new Bundle(); result.putInt("tests", checks.size()); result.putInt("passed", passed); result.putInt("failures", failed); result.putString("failure", failure);
        instrumentation.finish(failed == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }
    private static void live(Instrumentation instrumentation) {
        Bundle result = new Bundle(); WebContentResolver resolver = new WebContentResolver(instrumentation.getTargetContext());
        try {
            JSONObject media = resolver.resolve(object("kind", "tv", "id", "284833", "season", 1, "episode", 1), () -> true);
            require(media.getJSONArray("tracks").length() > 0 && "framextv:tv:284833:1:1".equals(media.getJSONObject("content").getString("key")), "Fresh declared source lacked expected identity/subtitles");
            result.putInt("tests", 1); result.putInt("passed", 1); result.putInt("failures", 0); result.putInt("trackCount", media.getJSONArray("tracks").length()); result.putString("contentType", media.optString("contentType"));
            resolver.close(); instrumentation.finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putInt("tests", 1); result.putInt("passed", 0); result.putInt("failures", 1); result.putString("failure", error instanceof WebContentResolver.ResolveException ? ((WebContentResolver.ResolveException) error).code : error.getClass().getSimpleName());
            resolver.close(); instrumentation.finish(Activity.RESULT_CANCELED, result);
        }
    }
}

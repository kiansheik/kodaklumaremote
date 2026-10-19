package dev.framextv.lumaremote;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Owned emulator UI only. Browsing uses about:blank or intercepted synthetic
 * HTTPS pages; capture exercises production validation without public requests. */
public class UiSmokeInstrumentation extends Instrumentation {
    private static final String CAPTURE_XYZ = "https://framextv.xyz/watch/native-capture";
    private static final String CAPTURE_REDIRECT = "https://framextv.xyz/watch/native-redirect";
    private static final String CAPTURE_CC = "https://framextv.cc/watch/native-capture";
    private static final String CAPTURE_SOURCE = "https://media.example/fixture.m3u8";
    private MainActivity activity;
    private WebView controls, browser;
    private int checks;
    @Override public void onCreate(Bundle args) { start(); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private Object field(String name) throws Exception {
        Field value = MainActivity.class.getDeclaredField(name); value.setAccessible(true); return value.get(activity);
    }
    private void setField(String name, Object value) throws Exception {
        Field target = MainActivity.class.getDeclaredField(name); target.setAccessible(true); target.set(activity, value);
    }
    private String javascript(WebView view, String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> result = new AtomicReference<>();
        runOnMainSync(() -> view.evaluateJavascript(script, value -> { result.set(value); done.countDown(); }));
        require(done.await(5, TimeUnit.SECONDS), "WebView script callback unavailable"); return result.get();
    }
    private void waitFor(WebView view, String expression) throws Exception {
        long end = SystemClock.elapsedRealtime() + 8000;
        while (SystemClock.elapsedRealtime() < end) {
            if ("true".equals(javascript(view, expression))) return;
            Thread.sleep(80);
        }
        throw new AssertionError("Packaged WebView did not become ready");
    }
    private JSONObject app(String id, String method, String token) throws Exception {
        JSONObject request = new JSONObject().put("id", id).put("method", method).put("params", new JSONObject().put("sourceToken", token));
        javascript(controls, "window.LumaNative.postMessage(" + JSONObject.quote(request.toString()) + ");true");
        waitFor(controls, "!!window.__uiSmokeReplies[" + JSONObject.quote(id) + "]");
        return new JSONObject(javascript(controls, "window.__uiSmokeReplies[" + JSONObject.quote(id) + "]"));
    }
    private String begin(String id, String captureToken) throws Exception {
        JSONObject reply = app(id, "app.beginHandoff", captureToken); require(reply.getBoolean("ok"), "Handoff attempt was not authorized");
        return reply.getJSONObject("result").getString("sourceToken");
    }
    private NativeBackend.SourceGuard sourceGuard() throws Exception {
        ConnectionService service = (ConnectionService) field("connectionService");
        Field ownedBackend = ConnectionService.class.getDeclaredField("backend"); ownedBackend.setAccessible(true);
        NativeBackend backend = (NativeBackend) ownedBackend.get(service); Field guard = NativeBackend.class.getDeclaredField("sourceGuard"); guard.setAccessible(true);
        return (NativeBackend.SourceGuard) guard.get(backend);
    }
    private void waitForService() throws Exception {
        long end = SystemClock.elapsedRealtime() + 8000;
        AtomicReference<ConnectionService> bound = new AtomicReference<>();
        while (SystemClock.elapsedRealtime() < end) {
            runOnMainSync(() -> {
                try { bound.set((ConnectionService) field("connectionService")); }
                catch (Exception error) { throw new RuntimeException(error); }
            });
            if (bound.get() != null) return;
            Thread.sleep(80);
        }
        throw new AssertionError("Activity did not bind the connection service");
    }
    private void passed(String name) {
        checks++; Bundle status = new Bundle(); status.putString("test", name); status.putInt("current", checks); sendStatus(0, status);
    }
    private void captureOriginBoundaries() throws Exception {
        Method supports = MainActivity.class.getDeclaredMethod("supportsCapturePage", String.class); supports.setAccessible(true);
        for (String url : new String[] { CAPTURE_XYZ, CAPTURE_CC, "https://framextv.cc:443/watch/episode", "HTTPS://FRAMEXTV.CC/watch/episode" }) {
            require(Boolean.TRUE.equals(supports.invoke(null, url)), "Supported HTTPS capture origin was rejected");
        }
        for (String url : new String[] { null, "", "about:blank", "http://framextv.xyz/watch/episode", "https://www.framextv.cc/watch/episode",
                "https://framextv.cc.attacker.example/watch/episode", "https://attackerframextv.cc/watch/episode", "https://framextv.cc@attacker.example/watch/episode",
                "https://user@framextv.cc/watch/episode", "https://framextv.cc:444/watch/episode", "https://framextv.cc./watch/episode", "https:///framextv.cc/watch/episode" }) {
            require(Boolean.FALSE.equals(supports.invoke(null, url)), "Unsupported or ambiguous capture origin was accepted");
        }
    }
    private void installCaptureFixture() {
        runOnMainSync(() -> {
            WebViewClient original = browser.getWebViewClient();
            require(original != null, "Production browsing client missing");
            browser.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return original.shouldOverrideUrlLoading(view, request); }
                @Override public void onPageStarted(WebView view, String url, Bitmap icon) { original.onPageStarted(view, url, icon); }
                @Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) { original.doUpdateVisitedHistory(view, url, reload); }
                @Override public void onPageFinished(WebView view, String url) { original.onPageFinished(view, url); }
                @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    String url = request.getUrl().toString(); String html = "";
                    if (CAPTURE_REDIRECT.equals(url)) html = "<!doctype html><title>Redirect fixture</title><script>location.replace('" + CAPTURE_CC + "')</script>";
                    else if (CAPTURE_XYZ.equals(url) || CAPTURE_CC.equals(url)) html = "<!doctype html><meta name='viewport' content='width=device-width'><title>Native capture fixture</title>"
                            + "<style>html,body{margin:0}video{display:block;width:280px;height:160px}</style>"
                            + "<video preload='none'><source src='" + CAPTURE_SOURCE + "' type='application/vnd.apple.mpegurl'>"
                            + "<track kind='subtitles' src='https://captions.example/pt.vtt' srclang='pt-BR' label='Português (Brasil)'>"
                            + "<track kind='subtitles' src='https://captions.example/en.vtt' srclang='en' label='English'></video>";
                    // Every resource is intercepted. No site, media or subtitle
                    // request may leave this explicitly synthetic WebView.
                    return new WebResourceResponse("text/html", "UTF-8", 200, "OK", Collections.singletonMap("Cache-Control", "no-store"),
                            new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
                }
            });
        });
    }
    private void loadCapturePage(String url) {
        runOnMainSync(() -> {
            try { Method show = MainActivity.class.getDeclaredMethod("showBrowser", boolean.class); show.setAccessible(true); show.invoke(activity, true); }
            catch (Exception error) { throw new RuntimeException(error); }
            browser.loadUrl(url);
        });
    }
    private JSONObject capturedFixture(String id) throws Exception {
        JSONObject response = app(id, "app.capture", "");
        require(response.getBoolean("ok"), "Production HTTPS capture failed: " + response.optJSONObject("error"));
        JSONObject media = response.getJSONObject("result"); JSONArray tracks = media.getJSONArray("tracks");
        require(CAPTURE_SOURCE.equals(media.getString("url")) && tracks.length() == 2, "Native capture lost declared source or subtitle catalog");
        require("pt-BR".equals(tracks.getJSONObject(0).getString("language")) && "en".equals(tracks.getJSONObject(1).getString("language")), "Declared subtitle languages were not retained");
        require(!media.getString("sourceToken").isEmpty(), "Native capture did not create its source token");
        require("\"undefined\"".equals(javascript(browser, "typeof window.LumaNative")), "Native bridge leaked into the capture fixture");
        return media;
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            require(Build.VERSION.SDK_INT >= 26 && (Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator") || Build.MODEL.contains("sdk_gphone")), "UI fixture requires an API26+ emulator");
            new SettingsStore(getTargetContext()).clear("all");
            Intent launch = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.invalid/"));
            launch.putExtra("dev.framextv.lumaremote.TEST_NO_AUTO_CONNECT", true);
            launch.setClassName(getTargetContext(), MainActivity.class.getName()); launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity = (MainActivity) startActivitySync(launch);
            runOnMainSync(() -> {
                controls = activity.getWindow().getDecorView().findViewWithTag("luma-controls");
                browser = activity.getWindow().getDecorView().findViewWithTag("luma-browser");
                require(controls != null && browser != null, "Expected isolated WebViews were not created");
                browser.stopLoading(); browser.loadUrl("about:blank");
                try { Method show = MainActivity.class.getDeclaredMethod("showBrowser", boolean.class); show.setAccessible(true); show.invoke(activity, false); }
                catch (Exception error) { throw new RuntimeException(error); }
            });
            waitFor(controls, "document.readyState==='complete'&&typeof window.LumaRPC?.receive==='function'&&document.querySelector('#tab-remote')!==null");
            waitForService();
            waitFor(browser, "location.href==='about:blank'"); Thread.sleep(250);
            require("\"function\"".equals(javascript(controls, "typeof window.LumaNative?.postMessage")), "Local native bridge is missing");
            require("\"undefined\"".equals(javascript(browser, "typeof window.LumaNative")), "Native bridge leaked into browsing WebView");
            passed("packaged startup and bridge isolation");

            JSONObject geometry = new JSONObject(javascript(controls, "({width:innerWidth,height:innerHeight,scrollWidth:document.documentElement.scrollWidth,scrollHeight:document.documentElement.scrollHeight,clipped:[...document.querySelectorAll('button,input')].filter(e=>e.getClientRects().length&&!e.closest('[hidden]')).filter(e=>{const r=e.getBoundingClientRect();return r.left<-.5||r.right>innerWidth+.5||r.top<-.5||r.bottom>innerHeight+.5}).length})"));
            require(geometry.getInt("width") > 0 && geometry.getInt("height") > 0, "WebView viewport was not laid out");
            require(geometry.getInt("scrollWidth") <= geometry.getInt("width") + 1 && geometry.getInt("scrollHeight") <= geometry.getInt("height") + 1 && geometry.getInt("clipped") == 0, "Packaged startup UI requires scrolling or clips controls");
            result.putString("viewport", geometry.toString()); passed("actual emulator startup fits without scrolling");

            javascript(controls, "window.__uiSmokeReplies={};window.__uiOriginalRpc=window.LumaRPC;window.LumaRPC={receive:r=>{window.__uiSmokeReplies[r.id]=r;window.__uiOriginalRpc.receive(r)}};true");
            runOnMainSync(() -> {
                try {
                    long generation = ((AtomicLong) field("pageGeneration")).get();
                    setField("capturedGeneration", generation); setField("capturedToken", "capture-fixture");
                    setField("capturedMedia", new JSONObject().put("url", "https://media.example/fixture.m3u8").put("sourceToken", "capture-fixture"));
                    ((ConnectionService) field("connectionService")).authorizeToken("capture-fixture");
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            NativeBackend.SourceGuard guard = sourceGuard();
            String first = begin("ui-first", "capture-fixture"); require(!first.equals("capture-fixture") && guard.isCurrent(first), "Attempt token was not renewed");
            require("capture-fixture".equals(((JSONObject) field("capturedMedia")).getString("sourceToken")), "Capture identity was mutated");
            app("ui-cancel-first", "app.cancelHandoff", first); require(!guard.isCurrent(first), "Cancelled attempt remains authorized");
            String retry = begin("ui-retry", "capture-fixture"); require(!retry.equals(first) && guard.isCurrent(retry), "Retry did not receive fresh authorization");
            app("ui-old-cancel", "app.cancelHandoff", first); require(guard.isCurrent(retry), "Old cancellation revoked the retry");
            JSONObject invalid = app("ui-invalid", "app.beginHandoff", "not-the-capture"); require(!invalid.getBoolean("ok") && "SOURCE_CHANGED".equals(invalid.getJSONObject("error").getString("code")), "Unknown capture identity accepted");
            String newest = begin("ui-newest", "capture-fixture"); require(!guard.isCurrent(retry) && guard.isCurrent(newest), "New attempt did not replace previous authorization");
            passed("attempt renewal cancellation retry and old-cancel isolation");

            // Reproduce the exact counter collision that occurs in fresh Activity
            // instances. Tokens must also carry an independent instance namespace.
            runOnMainSync(() -> {
                try {
                    ((AtomicLong) field("pageGeneration")).set(77);
                    ((AtomicLong) field("captureSequence")).set(11);
                    setField("capturedGeneration", 77L);
                    setField("capturedMedia", new JSONObject().put("url", "https://media.example/fixture.m3u8").put("sourceToken", "recreated-capture"));
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            String priorInstance = begin("ui-prior-instance", "recreated-capture");
            NativeBackend.SourceGuard priorGuard = guard;
            Instrumentation.ActivityMonitor recreated = addMonitor(MainActivity.class.getName(), null, false);
            runOnMainSync(() -> activity.recreate());
            Activity replacement = waitForMonitorWithTimeout(recreated, 8000); removeMonitor(recreated);
            require(replacement instanceof MainActivity && replacement != activity, "Activity was not recreated");
            activity = (MainActivity) replacement;
            runOnMainSync(() -> {
                controls = activity.getWindow().getDecorView().findViewWithTag("luma-controls");
                browser = activity.getWindow().getDecorView().findViewWithTag("luma-browser");
                browser.stopLoading(); browser.loadUrl("about:blank");
            });
            waitFor(controls, "document.readyState==='complete'&&typeof window.LumaRPC?.receive==='function'");
            waitForService(); waitFor(browser, "location.href==='about:blank'"); Thread.sleep(250);
            javascript(controls, "window.__uiSmokeReplies={};window.__uiOriginalRpc=window.LumaRPC;window.LumaRPC={receive:r=>{window.__uiSmokeReplies[r.id]=r;window.__uiOriginalRpc.receive(r)}};true");
            runOnMainSync(() -> {
                try {
                    ((AtomicLong) field("pageGeneration")).set(77);
                    ((AtomicLong) field("captureSequence")).set(11);
                    setField("capturedGeneration", 77L);
                    setField("capturedMedia", new JSONObject().put("url", "https://media.example/fixture.m3u8").put("sourceToken", "recreated-capture"));
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            newest = begin("ui-recreated-instance", "recreated-capture"); guard = sourceGuard();
            require(!newest.equals(priorInstance) && guard.isCurrent(newest) && !guard.isCurrent(priorInstance) && !priorGuard.isCurrent(priorInstance), "Counter reuse revived an old Activity's authorization");
            passed("Activity recreation cannot reuse a previous instance handoff token");

            javascript(browser, "history.pushState({},'', '#owned-ui-smoke');true");
            long end = SystemClock.elapsedRealtime() + 3000; while (guard.isCurrent(newest) && SystemClock.elapsedRealtime() < end) Thread.sleep(50);
            require(!guard.isCurrent(newest), "Browser history navigation did not revoke authorization");
            JSONObject changed = app("ui-changed", "app.beginHandoff", "capture-fixture"); require(!changed.getBoolean("ok"), "Navigation retained the stale capture");
            passed("same-document navigation revokes handoff");

            captureOriginBoundaries();
            passed("capture accepts only the exact supported HTTPS origins and default port");

            installCaptureFixture(); loadCapturePage(CAPTURE_REDIRECT);
            waitFor(browser, "document.readyState==='complete'&&location.href===" + JSONObject.quote(CAPTURE_CC) + "&&innerHeight>0&&document.querySelector('video')?.getBoundingClientRect().width>0");
            AtomicReference<String> nativeUrl = new AtomicReference<>(); runOnMainSync(() -> nativeUrl.set(browser.getUrl()));
            require(CAPTURE_CC.equals(nativeUrl.get()), "Native browser URL did not follow the HTTPS fixture redirect");
            JSONObject redirectedMedia = capturedFixture("ui-redirect-capture");
            String redirectedJob = begin("ui-redirect-begin", redirectedMedia.getString("sourceToken")); NativeBackend.SourceGuard redirectGuard = sourceGuard();
            require(redirectGuard.isCurrent(redirectedJob), "Redirected capture could not authorize the handoff");
            loadCapturePage(CAPTURE_XYZ);
            waitFor(browser, "document.readyState==='complete'&&location.href===" + JSONObject.quote(CAPTURE_XYZ) + "&&innerHeight>0&&document.querySelector('video')?.getBoundingClientRect().width>0");
            require(!redirectGuard.isCurrent(redirectedJob), "HTTPS navigation retained the redirected source authorization");
            JSONObject directMedia = capturedFixture("ui-direct-capture");
            String directJob = begin("ui-direct-begin", directMedia.getString("sourceToken")); NativeBackend.SourceGuard directGuard = sourceGuard();
            javascript(browser, "history.pushState({},'', '#new-source');true");
            end = SystemClock.elapsedRealtime() + 3000; while (directGuard.isCurrent(directJob) && SystemClock.elapsedRealtime() < end) Thread.sleep(50);
            require(!directGuard.isCurrent(directJob), "Capture-page history retained its handoff authorization");
            passed("production capture follows intercepted HTTPS redirect, retains declared tracks and invalidates changed pages without a browsing bridge");

            Bitmap screenshot = getUiAutomation().takeScreenshot();
            if (screenshot != null) {
                File output = new File(getTargetContext().getExternalFilesDir(null), "mobile-ui-smoke.png");
                try (FileOutputStream stream = new FileOutputStream(output)) { screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream); }
                screenshot.recycle(); result.putString("screenshot", output.getAbsolutePath());
            }
            result.putInt("passed", checks); result.putInt("failures", 0); finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putInt("passed", checks); result.putInt("failures", 1); result.putString("failure", error.getClass().getSimpleName() + ": " + error.getMessage()); finish(Activity.RESULT_CANCELED, result);
        } finally { if (activity != null) runOnMainSync(() -> activity.finish()); }
    }
}

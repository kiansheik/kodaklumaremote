package dev.framextv.lumaremote;

import android.app.Activity;
import android.content.Intent;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Separate WebViews keep the native bridge entirely outside untrusted sites. */
public final class MainActivity extends Activity {
    private static final String LOCAL_ORIGIN = "https://luma.invalid";
    private static final String START_URL = "https://framextv.xyz/watch/284833/1/1";
    private static final int SCAN_MATTER = 6031;
    private String pendingMatterScan;
    private WebView controls, browser;
    private EditText address;
    private LinearLayout browserPanel;
    private ProgressBar progress;
    private Button castButton;
    private ConnectionService connectionService;
    private UpdateManager updateManager;
    private boolean serviceBound;
    private boolean resumed, foregroundStarted, autoConnectPending, notificationAsked;
    private boolean autoConnectEnabled = true;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ConnectionService.Listener serviceListener = snapshot -> {
        updateCastTarget(snapshot);
        event("onConnectionState", snapshot);
        scheduleAutoConnect(5000);
    };
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            connectionService = ((ConnectionService.LocalBinder) binder).getService();
            if (foregroundStarted) connectionService.allowAutoConnect();
            connectionService.addListener(serviceListener);
            if (autoConnectEnabled && foregroundStarted) connectionService.restoreReceivers();
            event("onConnectionState", connectionService.snapshot());
            scheduleAutoConnect(0);
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            connectionService = null;
            event("onConnectionState", null);
        }
    };
    private final AtomicLong pageGeneration = new AtomicLong();
    private final AtomicLong captureSequence = new AtomicLong();
    private final String captureSession = java.util.UUID.randomUUID().toString();
    private volatile String capturedToken;
    private volatile long capturedGeneration = -1;
    private String lastNavigationUrl;
    private JSONObject capturedMedia;
    private boolean localReady;
    private boolean browserVisible;
    private volatile boolean destroyed;
    private final Runnable autoConnect = () -> {
        if (!autoConnectEnabled || !resumed || destroyed || autoConnectPending || connectionService == null) return;
        JSONObject snapshot = connectionService.snapshot();
        JSONObject connection = snapshot.optJSONObject("connection");
        if (snapshot.optBoolean("autoConnectPaused") || connection != null && connection.optBoolean("connected")) return;
        autoConnectPending = true;
        ensureNotificationPermission();
        connectionService.request("app.settings.autoConnect", new JSONObject(), new ConnectionService.Callback() {
            @Override public void success(JSONObject result) {
                autoConnectPending = false;
                if (result.optJSONArray("devices") != null && result.optJSONArray("devices").length() > 1) event("onDiscoveryResult", result);
                scheduleAutoConnect(5000);
            }
            @Override public void failure(String code, String message) {
                autoConnectPending = false;
                scheduleAutoConnect(5000);
            }
        });
    };

    private void scheduleAutoConnect(long delay) {
        main.removeCallbacks(autoConnect);
        if (resumed && !destroyed && !autoConnectPending && connectionService != null) main.postDelayed(autoConnect, delay);
    }

    private void ensureNotificationPermission() {
        if (!notificationAsked && Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationAsked = true;
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 41);
        }
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            autoConnectEnabled = !getIntent().getBooleanExtra("dev.framextv.lumaremote.TEST_NO_AUTO_CONNECT", false);
        }
        if (autoConnectEnabled) updateManager = new UpdateManager(this);
        getWindow().setStatusBarColor(Color.rgb(15, 26, 31));
        getWindow().setNavigationBarColor(Color.rgb(15, 26, 31));
        serviceBound = bindService(new Intent(this, ConnectionService.class), serviceConnection, BIND_AUTO_CREATE);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(15, 26, 31));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        controls = new WebView(this);
        configureControls();
        root.addView(controls, new FrameLayout.LayoutParams(-1, -1));
        browserPanel = new LinearLayout(this);
        browserPanel.setOrientation(LinearLayout.VERTICAL);
        browserPanel.setBackgroundColor(Color.rgb(15, 26, 31));
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setPadding(dp(6), dp(6), dp(6), dp(6));
        Button back = button("‹", toolbar);
        back.setContentDescription("Back in website");
        back.setOnClickListener(v -> { if (browser.canGoBack()) browser.goBack(); });
        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextColor(Color.WHITE);
        address.setTextSize(13);
        address.setInputType(17); // URI, no autocorrect or password handling.
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        toolbar.addView(address, new LinearLayout.LayoutParams(0, dp(48), 1));
        address.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_GO) { navigate(address.getText().toString()); return true; }
            return false;
        });
        Button go = button("Go", toolbar);
        go.setOnClickListener(v -> navigate(address.getText().toString()));
        browserPanel.addView(toolbar);
        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(dp(8), 0, dp(8), dp(4));
        Button remote = button("Remote", actions);
        remote.setOnClickListener(v -> showBrowser(false));
        castButton = button("Cast to LUMA", actions);
        castButton.setOnClickListener(v -> capture(null));
        browserPanel.addView(actions);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        browserPanel.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
        browser = new WebView(this);
        configureBrowser();
        browserPanel.addView(browser, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(browserPanel, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        showBrowser(false);
        controls.loadUrl(LOCAL_ORIGIN + "/assets/index.html");
        String shared = sharedUrl(getIntent());
        browser.loadUrl(shared == null ? START_URL : shared);
        if (shared != null) showBrowser(true);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void updateCastTarget(JSONObject snapshot) {
        if (castButton != null) castButton.setText("philips".equals(snapshot.optString("selectedTarget")) ? "Cast to Philips" : "Cast to LUMA");
    }
    private Button button(String title, LinearLayout parent) {
        Button result = new Button(this);
        result.setText(title);
        result.setTextSize(13);
        result.setAllCaps(false);
        parent.addView(result, new LinearLayout.LayoutParams(-2, dp(48)));
        return result;
    }

    private void commonSettings(WebView view) {
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (Build.VERSION.SDK_INT >= 26) settings.setSafeBrowsingEnabled(true);
    }

    private void configureControls() {
        commonSettings(controls);
        controls.setTag("luma-controls");
        controls.setBackgroundColor(Color.rgb(15, 26, 31));
        controls.getSettings().setDomStorageEnabled(false);
        controls.addJavascriptInterface(new Bridge(), "LumaNative");
        controls.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                try {
                    String asset;
                    String mime;
                    if (url.equals(LOCAL_ORIGIN + "/assets/index.html")) { asset = "index.html"; mime = "text/html"; }
                    else if (url.equals(LOCAL_ORIGIN + "/assets/app.js")) { asset = "app.js"; mime = "text/javascript"; }
                    else return emptyResponse();
                    HashMap<String,String> headers = new HashMap<>();
                    headers.put("Cache-Control", "no-store");
                    headers.put("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; connect-src 'none'; img-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'");
                    return new WebResourceResponse(mime, "UTF-8", 200, "OK", headers, getAssets().open(asset));
                } catch (Exception ignored) { return emptyResponse(); }
            }
            @Override public void onPageFinished(WebView view, String url) {
                localReady = true;
                if (capturedMedia != null) event("onCapturedMedia", capturedMedia);
            }
        });
    }

    private WebResourceResponse emptyResponse() {
        return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
    }

    private void configureBrowser() {
        commonSettings(browser);
        browser.setTag("luma-browser");
        browser.getSettings().setDomStorageEnabled(true);
        browser.getSettings().setMediaPlaybackRequiresUserGesture(true);
        // No JavaScript interface, file access, network interception or copied cookies.
        browser.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                return !url.equals("about:blank") && !isHttps(url);
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                invalidateSource(url);
                address.setText(url);
            }
            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
                if (lastNavigationUrl == null || !lastNavigationUrl.equals(url)) invalidateSource(url);
            }
            @Override public void onPageFinished(WebView view, String url) { address.setText(url); }
        });
        browser.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) {
                progress.setProgress(value);
                progress.setVisibility(value == 100 ? View.GONE : View.VISIBLE);
            }
        });
    }

    private void invalidateSource(String url) {
        lastNavigationUrl = url;
        pageGeneration.incrementAndGet();
        if (connectionService != null) connectionService.revokeToken(capturedToken);
        capturedToken = null;
        capturedGeneration = -1;
        capturedMedia = null;
        event("onSourceChanged", null);
    }

    private boolean isHttps(String value) {
        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                && uri.getUserInfo() == null;
        } catch (Exception ignored) { return false; }
    }

    private String sharedUrl(Intent intent) {
        if (intent == null) return null;
        String data = intent.getDataString();
        if (data != null && data.length() <= 8192 && isHttps(data)) return data;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null && text.length() <= 16384) {
                java.util.regex.Matcher match = java.util.regex.Pattern.compile("https://[^\\s<>]+").matcher(text);
                if (match.find()) {
                    String candidate = match.group();
                    if (candidate.length() <= 8192 && isHttps(candidate)) return candidate;
                }
            }
        }
        return null;
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (ConnectionService.ACTION_OPEN_REMOTE.equals(intent.getAction())) {
            showBrowser(false);
            event("onOpenRemote", null);
            return;
        }
        String url = sharedUrl(intent);
        if (url != null) navigate(url);
    }

    private void navigate(String value) {
        String url = value.trim();
        if (!url.contains("://")) url = "https://" + url;
        if (!isHttps(url)) { Toast.makeText(this, "Enter an HTTPS website address.", Toast.LENGTH_SHORT).show(); return; }
        browser.loadUrl(url);
        showBrowser(true);
    }

    private void showBrowser(boolean visible) {
        browserVisible = visible;
        browserPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
        controls.setVisibility(visible ? View.GONE : View.VISIBLE);
    }

    private void capture(String requestId) {
        if (connectionService != null) connectionService.revokeToken(capturedToken);
        capturedToken = null;
        final long generation = pageGeneration.get();
        final long attempt = captureSequence.incrementAndGet();
        final String url = browser.getUrl();
        try {
            URI uri = new URI(url == null ? "" : url);
            if (!"https".equals(uri.getScheme()) || !"framextv.xyz".equals(uri.getHost())
                || uri.getUserInfo() != null || uri.getPort() != -1 && uri.getPort() != 443) {
                throw new Exception("unsupported origin");
            }
            String script;
            try (InputStream input = getAssets().open("capture.js")) {
                script = new String(readBounded(input, 256 * 1024), StandardCharsets.UTF_8);
            }
            browser.evaluateJavascript(script, value -> {
                if (destroyed) return;
                if (generation != pageGeneration.get() || attempt != captureSequence.get() || !url.equals(browser.getUrl())) {
                    captureFailure(requestId, "The page changed. Choose the video again."); return;
                }
                try {
                    if (value == null || value.length() > 256 * 1024) throw new Exception();
                    JSONObject data = new JSONObject(value);
                    if (!data.optBoolean("ok")) {
                        captureFailure(requestId, "Open a playable episode, then tap Cast again."); return;
                    }
                    JSONObject media = data.getJSONObject("media");
                    int trackCount = media.optJSONArray("tracks") == null ? 0 : media.optJSONArray("tracks").length();
                    String kind = media.optString("contentType", "").toLowerCase(java.util.Locale.ROOT);
                    android.util.Log.i("LumaHandoff", "captured tracks=" + trackCount + " format="
                        + (kind.contains("mpegurl") ? "hls" : kind.contains("dash") ? "dash" : kind.startsWith("video/") ? "video" : "unknown"));
                    capturedToken = captureSession + ":" + generation + ":" + attempt;
                    capturedGeneration = generation;
                    media.put("sourceToken", capturedToken);
                    capturedMedia = media;
                    showBrowser(false);
                    if (requestId != null) reply(requestId, media, null, null);
                    else event("onCapturedMedia", media);
                } catch (Exception ignored) { captureFailure(requestId, "This player did not expose a supported video link."); }
            });
        } catch (Exception ignored) {
            captureFailure(requestId, "Video capture currently supports frameXTV HTTPS pages.");
        }
    }

    private void captureFailure(String id, String message) {
        if (id != null) reply(id, null, "SOURCE_UNAVAILABLE", message);
        else Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private byte[] readBounded(InputStream input, int limit) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int size;
        while ((size = input.read(buffer)) != -1) {
            if (out.size() + size > limit) throw new Exception("asset too large");
            out.write(buffer, 0, size);
        }
        return out.toByteArray();
    }

    private final class Bridge {
        @JavascriptInterface public void postMessage(String message) {
            if (message == null || message.length() > 256 * 1024 || destroyed) return;
            try {
                JSONObject request = new JSONObject(message);
                String id = request.getString("id");
                String method = request.getString("method");
                JSONObject params = request.optJSONObject("params");
                if (params == null) params = new JSONObject();
                if (id.length() > 80 || method.length() > 80) return;
                final JSONObject args = params;
                if (method.startsWith("app.")) {
                    runOnUiThread(() -> appRequest(id, method, args));
                    return;
                }
                runOnUiThread(() -> {
                    if (connectionService == null) { reply(id, null, "BUSY", "The remote is starting. Try again in a moment."); return; }
                    if (method.equals("device.connect") || method.equals("msx.start") || method.equals("matter.pair")) ensureNotificationPermission();
                    connectionService.request(method, args, new ConnectionService.Callback() {
                        @Override public void success(JSONObject result) { reply(id, result, null, null); }
                        @Override public void failure(String code, String message) { requestFailure(id, code, message); }
                    });
                });
            } catch (Exception ignored) { /* Invalid untrusted message is never executed. */ }
        }
    }

    private void appRequest(String id, String method, JSONObject params) {
        try {
            if (method.equals("app.ready")) {
                JSONObject result = connectionService == null ? new JSONObject() : connectionService.snapshot();
                result.put("browserUrl", browser.getUrl());
                if (capturedMedia != null) result.put("source", capturedMedia);
                reply(id, result, null, null);
            } else if (method.equals("app.scanMatterCode")) {
                if (pendingMatterScan != null) { reply(id, null, "MATTER_BUSY", "A scan is already open."); return; }
                pendingMatterScan = id;
                try { startActivityForResult(new Intent(this, ScanMatterActivity.class), SCAN_MATTER); }
                catch (RuntimeException unavailable) { pendingMatterScan = null; reply(id, null, "CAMERA_DENIED", "Enter the setup code instead."); }
            } else if (method.equals("app.checkUpdates")) {
                if (updateManager == null) { reply(id, null, "BUSY", "Update checking is unavailable in this test session."); return; }
                updateManager.checkNow();
                reply(id, new JSONObject().put("accepted", true), null, null);
            } else if (method.startsWith("app.settings.") || method.equals("app.rememberRemote") || method.equals("app.selectTarget")) {
                if (connectionService == null) { reply(id, null, "BUSY", "The remote is starting. Try again in a moment."); return; }
                connectionService.request(method, params, new ConnectionService.Callback() {
                    @Override public void success(JSONObject result) { reply(id, result, null, null); }
                    @Override public void failure(String code, String message) { requestFailure(id, code, message); }
                });
            } else if (method.equals("app.handoffStatus")) {
                String stage = params.optString("stage", "");
                if (!java.util.Arrays.asList("captured", "setup", "launching", "waiting", "opening", "subtitles", "selecting", "remote", "ready", "error", "cancelled").contains(stage)) {
                    reply(id, null, "INVALID_REQUEST", "Unknown casting stage."); return;
                }
                String code = params.optString("code", "");
                android.util.Log.i("LumaHandoff", stage + (code.matches("[A-Z_]{1,40}") ? " " + code : ""));
                reply(id, new JSONObject().put("accepted", true), null, null);
            } else if (method.equals("app.browse")) {
                String url = params.optString("url", "");
                if (!url.isEmpty()) navigate(url); else showBrowser(true);
                reply(id, new JSONObject().put("accepted", true), null, null);
            } else if (method.equals("app.capture")) capture(id);
            else if (method.equals("app.beginHandoff")) {
                String token = params.optString("sourceToken", "");
                if (connectionService == null || capturedMedia == null || capturedGeneration != pageGeneration.get()
                    || !token.equals(capturedMedia.optString("sourceToken", ""))) {
                    reply(id, null, "SOURCE_CHANGED", "The page changed. Choose the video again."); return;
                }
                // Keep the capture identity stable, but authorize each attempt separately.
                capturedToken = captureSession + ":" + capturedGeneration + ":" + captureSequence.incrementAndGet();
                connectionService.authorizeToken(capturedToken, params.optString("target", "luma"));
                if (!"philips".equals(params.optString("target", "luma"))
                        && !connectionService.prepareHandoff(capturedMedia, capturedToken)) {
                    connectionService.revokeToken(capturedToken);
                    capturedToken = null;
                    reply(id, null, "INVALID_MEDIA", "Choose the video again in Browse."); return;
                }
                reply(id, new JSONObject().put("sourceToken", capturedToken), null, null);
            }
            else if (method.equals("app.cancelHandoff")) {
                String token = params.optString("sourceToken", "");
                if (connectionService != null) connectionService.revokeToken(token);
                if (token.equals(capturedToken)) capturedToken = null;
                reply(id, new JSONObject().put("accepted", true), null, null);
            }
            else if (method.equals("app.handoffReady")) {
                String token = params.optString("sourceToken", "");
                if (!token.equals(capturedToken) || capturedGeneration != pageGeneration.get() || capturedMedia == null) {
                    reply(id, null, "SOURCE_CHANGED", "The page changed. The projector is playing independently."); return;
                }
                if (!"philips".equals(params.optString("target")) && connectionService != null && params.optJSONObject("remote") != null) {
                    if (!connectionService.rememberHandoff(capturedMedia, params.getJSONObject("remote"))) {
                        android.util.Log.i("LumaHandoff", "remote metadata rejected INVALID_SUBTITLE");
                        reply(id, null, "INVALID_SUBTITLE", "Could not retain the subtitle catalog. The projector is playing independently."); return;
                    }
                    android.util.Log.i("LumaHandoff", "remote metadata accepted");
                }
                String script;
                try (InputStream input = getAssets().open("capture.js")) {
                    script = new String(readBounded(input, 256 * 1024), StandardCharsets.UTF_8);
                }
                browser.evaluateJavascript(script + ";LumaCapture.pauseMatchingSource(" + JSONObject.quote(capturedMedia.getString("url")) + ");", value ->
                    reply(id, new JSONObject(), null, null));
            }
            else if (method.equals("app.showRemote")) { showBrowser(false); reply(id, new JSONObject().put("accepted", true), null, null); }
            else reply(id, null, "INVALID_REQUEST", "Unknown app action.");
        } catch (Exception ignored) { reply(id, null, "REQUEST_FAILED", "Could not complete the app action."); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != SCAN_MATTER) return;
        String id = pendingMatterScan; pendingMatterScan = null;
        if (id == null || destroyed) return;
        String code = resultCode == RESULT_OK && data != null ? data.getStringExtra(ScanMatterActivity.EXTRA_SETUP_CODE) : null;
        if (code != null && code.startsWith("MT:") && code.length() <= 256) {
            try { reply(id, new JSONObject().put("setupCode", code), null, null); }
            catch (Exception ignored) { reply(id, null, "MATTER_CODE", "Scan a Matter setup QR code."); }
        } else if (data != null && data.getBooleanExtra(ScanMatterActivity.EXTRA_MANUAL_ENTRY, false)) {
            reply(id, new JSONObject(), null, null);
        } else reply(id, null, "MATTER_SCAN_CANCELLED", "Scanning cancelled.");
    }

    private void reply(String id, JSONObject result, String code, String message) {
        try {
            JSONObject response = new JSONObject().put("id", id).put("ok", code == null);
            if (code == null) response.put("result", result == null ? new JSONObject() : result);
            else response.put("error", new JSONObject().put("code", code).put("message", message));
            runOnUiThread(() -> { if (!destroyed) controls.evaluateJavascript("window.LumaRPC&&window.LumaRPC.receive(" + response.toString() + ")", null); });
        } catch (Exception ignored) { }
    }

    private void requestFailure(String id, String code, String message) {
        android.util.Log.i("LumaHandoff", "native " + (code != null && code.matches("[A-Z_]{1,40}") ? code : "REQUEST_FAILED"));
        reply(id, null, code, message);
    }

    private void event(String method, JSONObject data) {
        if (!localReady || destroyed) return;
        String value = data == null ? "null" : data.toString();
        controls.evaluateJavascript("window.LumaApp&&typeof window.LumaApp." + method + "==='function'&&window.LumaApp." + method + "(" + value + ")", null);
    }

    @Override public void onBackPressed() {
        if (browserVisible && browser.canGoBack()) browser.goBack();
        else if (browserVisible) showBrowser(false);
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (updateManager != null) updateManager.close();
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        if (connectionService != null) {
            connectionService.revokeToken(capturedToken);
            connectionService.removeListener(serviceListener);
        }
        if (serviceBound) unbindService(serviceConnection);
        browser.destroy();
        controls.destroy();
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        if (updateManager != null) updateManager.onResume();
        resumed = true;
        if (connectionService != null) event("onConnectionState", connectionService.snapshot());
        if (autoConnectEnabled && connectionService != null) connectionService.restoreReceivers();
        scheduleAutoConnect(0);
    }

    @Override protected void onStart() {
        super.onStart();
        foregroundStarted = true;
        if (connectionService != null) {
            connectionService.allowAutoConnect();
            if (autoConnectEnabled) connectionService.restoreReceivers();
        }
    }

    @Override protected void onPause() {
        if (updateManager != null) updateManager.onPause();
        resumed = false;
        main.removeCallbacks(autoConnect);
        super.onPause();
    }

    @Override protected void onStop() {
        foregroundStarted = false;
        super.onStop();
    }
}

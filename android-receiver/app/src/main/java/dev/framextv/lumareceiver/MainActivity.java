package dev.framextv.lumareceiver;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.InputDevice;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;


import androidx.media3.common.Player;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.ui.PlayerView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

/** Full-screen player plus an idle screen with address, pairing code and update prompt. */
public final class MainActivity extends Activity implements PlaybackController.Ui, Updater.Ui {
    private ReceiverApp app;
    private PlayerView playerView;
    private View brightnessOverlay;
    private SubtitleOverlay captions;
    private final Player.Listener cueListener = new Player.Listener() {
        @Override public void onCues(CueGroup group) {
            if (app.playback.externalSelected()) return; // receiver-rendered track owns the overlay
            StringBuilder text = new StringBuilder();
            for (Cue cue : group.cues) if (cue.text != null) { if (text.length() > 0) text.append('\n'); text.append(cue.text); }
            captions.setText(text);
        }
    };
    private View idle;
    private CatToyView catToy;
    private TextView status, pairingCode, updatePrompt, footer, notice, scrubTime, appsHeading;
    private View homeSetup, gamesOpen, controllersOpen;
    private ControllerInput controllerInput;
    private boolean dispatchingController;
    private GridLayout appsGrid;
    private List<LauncherCatalog.Entry> launcherEntries = Collections.emptyList();
    private View scrub;
    private android.widget.ImageView scrubImage;
    private android.widget.ProgressBar scrubBar;
    private final Runnable hideScrub = () -> scrub.setVisibility(View.GONE);
    private long backArmedUntil;
    private final Runnable hideNotice = () -> notice.setVisibility(View.GONE);
    private boolean externalShown;
    private boolean resumed;
    /** Draws receiver-rendered captions from the player clock; cheap when nothing changes. */
    private final Runnable captionTick = new Runnable() {
        @Override public void run() {
            boolean external = app.playback.externalSelected();
            if (external) captions.setText(app.playback.externalText());
            else if (externalShown) captions.setText("");
            externalShown = external;
            captions.postDelayed(this, 100);
        }
    };

    static void bringToFront(Context context) {
        context.startActivity(new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.main);
        app = ReceiverApp.get(this);
        playerView = findViewById(R.id.player);
        brightnessOverlay = new View(this);
        brightnessOverlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        brightnessOverlay.setVisibility(View.GONE);
        FrameLayout playerOverlay = playerView.getOverlayFrameLayout();
        if (playerOverlay == null) throw new IllegalStateException("Player overlay is unavailable");
        playerOverlay.addView(brightnessOverlay, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        idle = findViewById(R.id.idle);
        catToy = findViewById(R.id.cat_toy);
        catToy.attach(app.catMode);
        status = findViewById(R.id.idle_status);
        pairingCode = findViewById(R.id.pairing_code);
        updatePrompt = findViewById(R.id.update_prompt);
        homeSetup = findViewById(R.id.home_setup);
        gamesOpen = findViewById(R.id.games_open);
        controllersOpen = findViewById(R.id.controllers_open);
        findViewById(R.id.history_open).setOnClickListener(view -> HistoryActivity.show(this));
        gamesOpen.setOnClickListener(view -> GamesActivity.show(this, null));
        findViewById(R.id.cat_open).setOnClickListener(view -> {
            app.playback.noteInteraction();
            if (app.playback.active()) app.playback.setPlaying(false);
            app.catMode.start(true, SystemClock.elapsedRealtime());
            showNotice("Cat Mode · Stick / D-pad moves · A pounces · B exits", 5000);
        });
        findViewById(R.id.accessibility_open).setOnClickListener(view -> {
            String destination = ControllerSystemService.openSettings(this);
            if ("unavailable".equals(destination)) showNotice("Accessibility settings are unavailable");
        });
        controllersOpen.setOnClickListener(view -> ControllerSetupActivity.show(this));
        controllerInput = new ControllerInput(this, new ControllerInput.Listener() {
            @Override public void onButtonsChanged(int buttons) {
            }
            @Override public void onAnalogInput(float x, float y) {
                app.catMode.controllerInput(x, y);
            }
            @Override public void onNavigationKey(int key, int action, int repeat) {
                ControllerInput.prepareNavigation(MainActivity.this, action);
                CatModeState.Snapshot cat = app.catMode.snapshot(SystemClock.elapsedRealtime());
                if (cat.active && ControllerNavigation.direction(key) != 0) {
                    return;
                }
                if (app.playback.title() == null && ControllerNavigation.direction(key) != 0) {
                    ControllerNavigation.handle(MainActivity.this, key, action, repeat); return;
                }
                long now = SystemClock.uptimeMillis();
                dispatchingController = true;
                try {
                    if (!dispatchKeyEvent(new KeyEvent(now, now, action, key, repeat, 0,
                            android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)))
                        ControllerNavigation.handle(MainActivity.this,
                            key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ? KeyEvent.KEYCODE_DPAD_CENTER : key, action, repeat);
                } finally { dispatchingController = false; }
            }
        });
        appsHeading = findViewById(R.id.apps_heading);
        appsGrid = findViewById(R.id.apps_grid);
        homeSetup.setOnClickListener(view -> openHomeSettings("button"));
        footer = findViewById(R.id.footer);
        notice = findViewById(R.id.notice);
        scrub = findViewById(R.id.scrub);
        scrubImage = findViewById(R.id.scrub_image);
        scrubTime = findViewById(R.id.scrub_time);
        scrubBar = findViewById(R.id.scrub_bar);
        playerView.setPlayer(app.playback.player());
        captions = findViewById(R.id.captions);
        if (playerView.getSubtitleView() != null) playerView.getSubtitleView().setVisibility(View.GONE);
        app.playback.player().addListener(cueListener);
        app.playback.setUi(this);
        app.updater.attach(this, app.playback);
        app.setPairingUi(code -> render());
        app.setNoticeUi(this::showNotice);
        app.setScrubUi(this::onScrub);
        app.setSnapshotUi(this::snapshot);
        app.setControllerNavigationUi(() -> ControllerNavigation.snapshot(this));
        app.catMode.setListener(() -> runOnUiThread(this::renderCatMode));
        onDisplayChanged();
        ReceiverApp.startReceiverService(this);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        controllerInput.start();
        refreshLauncher();
        render();
        renderCatMode();
        captions.removeCallbacks(captionTick);
        captions.post(captionTick);
        resetLauncherFocus();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Android may reserve the controller Home button and deliver a launcher Intent.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            app.catMode.stop();
            app.playback.stop();
            render();
        }
        resetLauncherFocus();
    }

    @Override protected void onPause() {
        resumed = false;
        controllerInput.stop();
        catToy.pauseAnimation();
        captions.removeCallbacks(captionTick);
        super.onPause();
    }

    @Override protected void onStop() {
        // Nothing is visible to watch; keep position and let the phone resume.
        if (app.playback.active()) app.playback.setPlaying(false);
        super.onStop();
    }

    @Override protected void onDestroy() {
        controllerInput.close();
        app.playback.setUi(null);
        app.updater.attach(null, app.playback);
        app.setPairingUi(null);
        app.setNoticeUi(null);
        app.setScrubUi(null);
        app.setSnapshotUi(null);
        app.setControllerNavigationUi(null);
        app.catMode.setListener(null);
        catToy.close();
        app.playback.player().removeListener(cueListener);
        playerView.setPlayer(null);
        super.onDestroy();
    }

    @Override public void onPlaybackChanged() { render(); }
    @Override public void onNotice(String text) { showNotice(text, 6000); }

    private void onScrub(boolean active, double position, double duration, android.graphics.Bitmap frame) {
        scrub.removeCallbacks(hideScrub);
        if (!active) { scrub.setVisibility(View.GONE); return; }
        if (scrub.getVisibility() != View.VISIBLE) scrubImage.setVisibility(View.GONE); // new drag: no stale frame
        if (duration <= 0) {
            long known = app.playback.player().getDuration();
            duration = known > 0 ? known / 1000.0 : 0;
        }
        scrubTime.setText(clock(position) + (duration > 0 ? "  /  " + clock(duration) : ""));
        scrubBar.setProgress(duration > 0 ? (int) Math.round(Math.min(1, position / duration) * 1000) : 0);
        if (frame != null) { scrubImage.setImageBitmap(frame); scrubImage.setVisibility(View.VISIBLE); }
        scrub.setVisibility(View.VISIBLE);
        // A phone that disappears mid-drag must not leave the overlay up.
        scrub.postDelayed(hideScrub, 4000);
    }

    private void showSeek() {
        long position = app.playback.player().getCurrentPosition();
        String key = app.playback.contentKey();
        byte[] jpeg = key == null ? null : app.thumbnails.nearest(key, position / 1000);
        onScrub(true, position / 1000.0, 0, jpeg == null ? null : android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length));
        scrub.removeCallbacks(hideScrub);
        scrub.postDelayed(hideScrub, 1500);
    }

    private android.graphics.Bitmap snapshot() {
        if (captions.getWidth() == 0) return null;
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(captions.getWidth(), captions.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        canvas.drawColor(0xFF3A5A40); // stand-in for video so the outline and panel are visible
        captions.draw(canvas);
        return bitmap;
    }

    static String clock(double seconds) {
        long total = Math.max(0, (long) seconds), h = total / 3600, m = total % 3600 / 60, s = total % 60;
        return h > 0 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(java.util.Locale.ROOT, "%d:%02d", m, s);
    }
    @Override public void bringToFront() { bringToFront(this); }
    @Override public void onUpdateChanged() { render(); }

    private void refreshLauncher() {
        launcherEntries = app.launcher.list();
        appsGrid.removeAllViews();
        for (LauncherCatalog.Entry entry : launcherEntries) appsGrid.addView(appTile(entry), appTileParams());
        appsHeading.setText(launcherEntries.isEmpty() ? "No launchable apps found" : "All apps  ·  " + launcherEntries.size());
    }

    private void resetLauncherFocus() {
        if (app.playback.title() != null || app.catMode.snapshot(SystemClock.elapsedRealtime()).active) return;
        appsGrid.post(() -> {
            if (!resumed || !idle.isShown() || app.catMode.snapshot(SystemClock.elapsedRealtime()).active) return;
            View first = appsGrid.getChildCount() > 0 ? appsGrid.getChildAt(0) : gamesOpen;
            first.requestFocusFromTouch();
            ((android.widget.ScrollView)findViewById(R.id.apps_scroll)).scrollTo(0, 0);
        });
    }

    private void moveCat(int key) {
        CatModeState.Snapshot cat = app.catMode.snapshot(SystemClock.elapsedRealtime());
        double dx = key == KeyEvent.KEYCODE_DPAD_LEFT ? -.035 : key == KeyEvent.KEYCODE_DPAD_RIGHT ? .035 : 0;
        double dy = key == KeyEvent.KEYCODE_DPAD_UP ? -.035 : key == KeyEvent.KEYCODE_DPAD_DOWN ? .035 : 0;
        app.catMode.pointer(cat.x + dx, cat.y + dy, SystemClock.elapsedRealtime());
    }

    private View appTile(LauncherCatalog.Entry entry) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(10), dp(10), dp(10), dp(8));
        tile.setBackgroundResource(R.drawable.launcher_tile);
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setContentDescription("Open " + entry.label);
        tile.setElevation(dp(2));
        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImageDrawable(app.launcher.icon(entry));
        tile.addView(icon, new LinearLayout.LayoutParams(dp(66), dp(66)));
        TextView label = new TextView(this);
        label.setText(entry.label);
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = dp(7);
        tile.addView(label, labelParams);
        tile.setOnFocusChangeListener((view, focused) -> view.setElevation(dp(focused ? 10 : 2)));
        tile.setOnClickListener(view -> {
            try {
                app.launcher.launch(entry.packageName);
                showNotice("Opening " + entry.label + "…");
            } catch (ApiException failure) { showNotice("Could not open " + entry.label); }
        });
        return tile;
    }

    private GridLayout.LayoutParams appTileParams() {
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = dp(132);
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
        params.setGravity(Gravity.FILL);
        params.setMargins(dp(7), dp(7), dp(7), dp(7));
        return params;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override public void onDisplayChanged() {
        captions.setStyle(app.display.subtitleScale(), app.display.subtitleBox());
        float brightness = app.display.brightness();
        if (brightness == 0f) {
            brightnessOverlay.setVisibility(View.GONE);
            brightnessOverlay.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            return;
        }
        // Never move decoded video away from the projector's proven SurfaceView.
        // A translucent white/black view is composited above that surface and
        // cannot replace the decoder output with a black TextureView frame.
        int alpha = Math.round(255f * Math.min(DisplaySettings.MAX_BRIGHTNESS, Math.abs(brightness)));
        brightnessOverlay.setBackgroundColor(brightness > 0
            ? android.graphics.Color.argb(alpha, 255, 255, 255)
            : android.graphics.Color.argb(alpha, 0, 0, 0));
        brightnessOverlay.setVisibility(View.VISIBLE);
    }

    private void render() {
        boolean media = app.playback.title() != null;
        idle.setVisibility(media ? View.GONE : View.VISIBLE);
        String code = app.pairing.activeCode();
        pairingCode.setVisibility(code == null ? View.GONE : View.VISIBLE);
        pairingCode.setText(code == null ? "" : code.substring(0, 3) + " " + code.substring(3));
        int clients = app.pairing.clientCount();
        status.setText(code != null ? "Enter this code on your phone to pair it"
            : (clients == 0 ? "Not paired yet. Open LUMA Remote on your phone and add this projector."
                : "Ready. Open an app below or choose something to watch on your phone.")
            + "\n" + address() + ":" + ControlServer.PORT + "  ·  " + clients + (clients == 1 ? " paired device" : " paired devices"));
        String prompt = null, footerText = "";
        try {
            org.json.JSONObject update = app.updater.status();
            String state = update.optString("state");
            org.json.JSONObject available = update.optJSONObject("available");
            String version = available == null ? "" : available.optString("versionName");
            if ("ready".equals(state) && "ask".equals(update.optString("policy")))
                prompt = "Update " + version + " is ready. Press OK to install, or Back to skip it.";
            else if ("needsPermission".equals(state)) prompt = "Allow LUMA Receiver to install updates, then come back.";
            else if ("downloading".equals(state)) prompt = "Downloading update " + version + "…";
            else if ("awaitingConfirmation".equals(state) || "installing".equals(state)) prompt = "Installing update " + version + "…";
            else if ("failed".equals(state)) prompt = "Update failed (" + update.optString("error") + ").";
            footerText = "v" + update.optString("versionName") + "  ·  updates: " + update.optString("policy")
                + "  ·  library: " + app.library.count("progress") + " items";
        } catch (Exception ignored) { }
        long okPresses = app.okPressCount();
        boolean defaultHome = app.isDefaultHome();
        homeSetup.setVisibility(!defaultHome && code == null ? View.VISIBLE : View.GONE);
        footer.setText(footerText + (footerText.isEmpty() ? "" : "\n") + (defaultHome
            ? "Projector Home is LUMA Receiver  ·  OK received: " + okPresses + "  ·  Menu: change Home app"
            : "Choose the Home button above, or press Menu, to make this the projector Home  ·  OK received: " + okPresses));
        updatePrompt.setVisibility(prompt == null ? View.GONE : View.VISIBLE);
        updatePrompt.setText(prompt == null ? "" : prompt);
    }

    private void renderCatMode() {
        boolean active = app.catMode.snapshot(SystemClock.elapsedRealtime()).active;
        catToy.setVisibility(active ? View.VISIBLE : View.GONE);
        if (active && resumed) {
            scrub.setVisibility(View.GONE);
            catToy.resumeAnimation();
        } else if (!active) {
            catToy.endSession();
        } else {
            catToy.pauseAnimation();
        }
    }

    private void openHomeSettings(String source) {
        app.noteHomeSettingsOpen(source);
        try { startActivity(new Intent(Settings.ACTION_HOME_SETTINGS)); }
        catch (ActivityNotFoundException | SecurityException unavailable) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void showNotice(String text) { showNotice(text, 3000); }

    private void showNotice(String text, int durationMs) {
        notice.setText(text);
        notice.setVisibility(View.VISIBLE);
        notice.removeCallbacks(hideNotice);
        notice.postDelayed(hideNotice, durationMs);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused && controllerInput != null) controllerInput.clear();
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        return controllerInput != null && controllerInput.onMotion(event) || super.dispatchGenericMotionEvent(event);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (!dispatchingController && controllerInput != null && controllerInput.onKey(event)) return true;
        // Logical Plus behaves as OK outside playback, and as play/pause in it.
        if (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && app.playback.title() == null)
            event = new KeyEvent(event.getDownTime(), event.getEventTime(), event.getAction(),
                KeyEvent.KEYCODE_DPAD_CENTER, event.getRepeatCount(), event.getMetaState(),
                event.getDeviceId(), event.getScanCode(), event.getFlags(), event.getSource());
        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                app.catMode.stop(); app.playback.stop(); render(); resetLauncherFocus();
            }
            return true;
        }
        CatModeState.Snapshot cat = app.catMode.snapshot(SystemClock.elapsedRealtime());
        if (cat.active) {
            int catKey = event.getKeyCode();
            boolean catControl = catKey == KeyEvent.KEYCODE_BACK || catKey == KeyEvent.KEYCODE_DPAD_CENTER
                || catKey == KeyEvent.KEYCODE_ENTER || catKey == KeyEvent.KEYCODE_DPAD_LEFT
                || catKey == KeyEvent.KEYCODE_DPAD_RIGHT || catKey == KeyEvent.KEYCODE_DPAD_UP
                || catKey == KeyEvent.KEYCODE_DPAD_DOWN || catKey == KeyEvent.KEYCODE_MENU;
            if (!catControl) return super.dispatchKeyEvent(event);
            if (event.getAction() == KeyEvent.ACTION_DOWN && ControllerNavigation.direction(catKey) != 0) {
                moveCat(catKey);
            } else if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (catKey == KeyEvent.KEYCODE_BACK) {
                    app.catMode.stop();
                    resetLauncherFocus();
                    showNotice("Cat Mode ended");
                } else if (catKey == KeyEvent.KEYCODE_DPAD_CENTER || catKey == KeyEvent.KEYCODE_ENTER) {
                    app.catMode.pounce(SystemClock.elapsedRealtime(), false);
                }
            }
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event);
        int key = event.getKeyCode();
        boolean initialOk = isInitialOkPress(event.getAction(), key, event.getRepeatCount());
        long okCount = initialOk ? app.noteOkPress() : -1;
        app.playback.noteInteraction(); // also cancels a pending "Up next"
        boolean media = app.playback.title() != null;
        if (!media) {
            boolean promptShown = updatePrompt.getVisibility() == View.VISIBLE && updatePrompt.getText().toString().startsWith("Update ");
            if (promptShown && (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER)) { app.updater.install(); return true; }
            if (promptShown && key == KeyEvent.KEYCODE_BACK) { app.updater.skipAvailable(); return true; }
            if (key == KeyEvent.KEYCODE_BACK && app.pairing.activeCode() != null) { app.pairing.cancel(); return true; }
            if (key == KeyEvent.KEYCODE_MENU) { openHomeSettings("menu"); return true; }
            View focused = getCurrentFocus();
            if (initialOk && idle.hasFocus() && focused != null && focused.isShown() && focused.isClickable())
                return ControllerNavigation.handle(this, key, event.getAction(), event.getRepeatCount());
            if (app.pairing.activeCode() == null && !app.isDefaultHome() && initialOk) {
                openHomeSettings("ok"); return true;
            }
            if (app.pairing.activeCode() == null && app.isDefaultHome() && initialOk) {
                if (appsGrid.getChildCount() > 0) appsGrid.getChildAt(0).requestFocusFromTouch();
                else showNotice("No launchable apps found.");
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        boolean controls = playerView.isControllerFullyVisible();
        switch (key) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (event.getRepeatCount() == 0) {
                    app.playback.setPlaying(!app.playback.player().getPlayWhenReady());
                    playerView.showController();
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                app.playback.seekBy(-10_000); showSeek(); return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                app.playback.seekBy(30_000); showSeek(); return true;
            case KeyEvent.KEYCODE_MENU:
                if (event.getRepeatCount() == 0) {
                    if (controls) playerView.hideController(); else playerView.showController();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (controls) { playerView.hideController(); return true; }
                if (SystemClock.elapsedRealtime() < backArmedUntil) { app.playback.stop(); return true; }
                backArmedUntil = SystemClock.elapsedRealtime() + 3000;
                showNotice("Press Back again to stop");
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (!controls) { app.playback.seekBy(-10_000); showSeek(); return true; }
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!controls) { app.playback.seekBy(30_000); showSeek(); return true; }
                break;
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!controls) {
                    float scale = app.display.stepSubtitle(key == KeyEvent.KEYCODE_DPAD_UP ? 1 : -1);
                    onDisplayChanged();
                    showNotice("Subtitles " + Math.round(scale * 100) + "%");
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                if (!controls) {
                    boolean playing = app.playback.player().getPlayWhenReady();
                    app.playback.setPlaying(!playing);
                    playerView.showController();
                    return true;
                }
                break;
            default: break;
        }
        return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event);
    }

    static boolean isInitialOkPress(int action, int key, int repeatCount) {
        return action == KeyEvent.ACTION_DOWN && repeatCount == 0
            && (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER);
    }

    private static String address() {
        try {
            for (NetworkInterface face : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!face.isUp() || face.isLoopback()) continue;
                for (InetAddress address : Collections.list(face.getInetAddresses()))
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) return address.getHostAddress();
            }
        } catch (Exception ignored) { }
        return "no network";
    }
}

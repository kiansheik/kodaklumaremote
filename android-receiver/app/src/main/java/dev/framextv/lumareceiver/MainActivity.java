package dev.framextv.lumareceiver;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;


import androidx.media3.common.Player;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.ui.PlayerView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/** Full-screen player plus an idle screen with address, pairing code and update prompt. */
public final class MainActivity extends Activity implements PlaybackController.Ui, Updater.Ui {
    private ReceiverApp app;
    private PlayerView playerView;
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
    private TextView status, pairingCode, updatePrompt, footer, notice, scrubTime;
    private View scrub;
    private android.widget.ImageView scrubImage;
    private android.widget.ProgressBar scrubBar;
    private final Runnable hideScrub = () -> scrub.setVisibility(View.GONE);
    private long backArmedUntil;
    private final Runnable hideNotice = () -> notice.setVisibility(View.GONE);
    private boolean externalShown;
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
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.main);
        app = ReceiverApp.get(this);
        playerView = findViewById(R.id.player);
        idle = findViewById(R.id.idle);
        status = findViewById(R.id.idle_status);
        pairingCode = findViewById(R.id.pairing_code);
        updatePrompt = findViewById(R.id.update_prompt);
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
        onDisplayChanged();
        ReceiverApp.startReceiverService(this);
    }

    @Override protected void onResume() { super.onResume(); render(); captions.removeCallbacks(captionTick); captions.post(captionTick); }

    @Override protected void onPause() { captions.removeCallbacks(captionTick); super.onPause(); }

    @Override protected void onStop() {
        // Nothing is visible to watch; keep position and let the phone resume.
        if (app.playback.active()) app.playback.setPlaying(false);
        super.onStop();
    }

    @Override protected void onDestroy() {
        app.playback.setUi(null);
        app.updater.attach(null, app.playback);
        app.setPairingUi(null);
        app.setNoticeUi(null);
        app.setScrubUi(null);
        app.setSnapshotUi(null);
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

    @Override public void onDisplayChanged() {
        captions.setStyle(app.display.subtitleScale(), app.display.subtitleBox());
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
                : "Ready. Choose something to watch on your phone.")
            + "\n" + address() + ":" + ControlServer.PORT + "  ·  " + clients + (clients == 1 ? " paired device" : " paired devices"));
        String prompt = null;
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
            footer.setText("v" + update.optString("versionName") + "  ·  updates: " + update.optString("policy")
                + "  ·  library: " + app.library.count("progress") + " items");
        } catch (Exception ignored) { }
        updatePrompt.setVisibility(prompt == null ? View.GONE : View.VISIBLE);
        updatePrompt.setText(prompt == null ? "" : prompt);
    }

    private void showNotice(String text) { showNotice(text, 3000); }

    private void showNotice(String text, int durationMs) {
        notice.setText(text);
        notice.setVisibility(View.VISIBLE);
        notice.removeCallbacks(hideNotice);
        notice.postDelayed(hideNotice, durationMs);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event);
        int key = event.getKeyCode();
        app.playback.noteInteraction(); // also cancels a pending "Up next"
        boolean media = app.playback.title() != null;
        if (!media) {
            boolean promptShown = updatePrompt.getVisibility() == View.VISIBLE && updatePrompt.getText().toString().startsWith("Update ");
            if (promptShown && (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER)) { app.updater.install(); return true; }
            if (promptShown && key == KeyEvent.KEYCODE_BACK) { app.updater.skipAvailable(); return true; }
            if (key == KeyEvent.KEYCODE_BACK && app.pairing.activeCode() != null) { app.pairing.cancel(); return true; }
            return super.dispatchKeyEvent(event);
        }
        boolean controls = playerView.isControllerFullyVisible();
        switch (key) {
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

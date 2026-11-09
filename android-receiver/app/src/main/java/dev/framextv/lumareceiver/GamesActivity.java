package dev.framextv.lumareceiver;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Receiver-owned gameplay: video, audio and saves never depend on a connected phone. */
public final class GamesActivity extends Activity {
    private ReceiverApp app;
    private FrameLayout root;
    private LinearLayout panel;
    private NesGameView game;
    private ControllerInput input;
    private String gameId, gameName, lastError;
    private boolean menu, resumed, focused;
    private TextView controllerStatus;
    private int lastButtons;
    private final Runnable refreshControllers = new Runnable() {
        @Override public void run() {
            if (controllerStatus == null || !resumed) return;
            controllerStatus.setText(controllerText());
            root.postDelayed(this, 500);
        }
    };

    static void show(Context context, String id) {
        Intent intent = new Intent(context, GamesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
            | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (id != null) intent.putExtra("gameId", id);
        context.startActivity(intent);
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        app = ReceiverApp.get(this);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK); setContentView(root);
        input = new ControllerInput(this, new ControllerInput.Listener() {
            @Override public void onButtonsChanged(int mask) {
                lastButtons = mask;
                if (game != null && !menu) game.setButtons(mask);
                if (controllerStatus != null) controllerStatus.setText(controllerText());
            }
            @Override public void onNavigationKey(int key, int action, int repeat) {
                ControllerInput.prepareNavigation(GamesActivity.this, action);
                if (ControllerNavigation.direction(key) != 0) {
                    ControllerNavigation.handle(GamesActivity.this, key, action, repeat); return;
                }
                long now = SystemClock.uptimeMillis();
                if (!handleNavigation(new KeyEvent(now, now, action, key, repeat)))
                    ControllerNavigation.handle(GamesActivity.this,
                        key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ? KeyEvent.KEYCODE_DPAD_CENTER : key, action, repeat);
            }
            @Override public void onGameInput(int mask, int freshPresses) {
                lastButtons = mask;
                if (game != null && !menu) game.setInput(mask, freshPresses);
            }
            @Override public void onRetriggersCancelled() { if (game != null) game.cancelRetriggers(); }
            @Override public void onMenuRequested() { if (game != null) showMenu(); }
            @Override public void onLoadCheckpointRequested() { if (game != null && !menu) loadCheckpoint(null); }
        });
        app.setGamesUi(this);
        openIntent(getIntent());
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); openIntent(intent);
    }
    private void openIntent(Intent intent) {
        app.playback.noteInteraction();
        String id = intent.getStringExtra("gameId");
        if (id != null) startGame(id);
        else if (game != null) showMenu();
        else showLibrary();
    }
    @Override protected void onResume() {
        super.onResume(); resumed = true; input.start();
        if (game != null && !menu && focused) game.resume();
        if (game == null && controllerStatus == null) showLibrary();
        root.removeCallbacks(refreshControllers); root.post(refreshControllers);
    }
    @Override protected void onPause() {
        resumed = false; input.stop(); root.removeCallbacks(refreshControllers);
        if (game != null) game.pause();
        super.onPause();
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus); focused = hasFocus;
        if (input == null) return;
        if (!hasFocus) { input.clear(); if (game != null) game.pause(); }
        else if (game != null && resumed && !menu) game.resume();
    }
    @Override protected void onDestroy() {
        app.setGamesUi(null); root.removeCallbacks(refreshControllers); input.close();
        if (game != null) { game.setListener(null); game.release(); game = null; }
        super.onDestroy();
    }
    JSONObject gameStatus() throws Exception {
        return new JSONObject().put("active", game != null).put("id", gameId == null ? JSONObject.NULL : gameId)
            .put("paused", game != null && !game.isRunning()).put("frames", game == null ? 0 : game.frameCount())
            .put("retriggeredPresses", game == null ? 0 : game.retriggerCount())
            .put("checkpointCount", game == null ? 0 : game.checkpoints().size())
            .put("checkpointLoads", game == null ? 0 : game.checkpointLoadCount())
            .put("error", lastError == null ? JSONObject.NULL : lastError);
    }
    void refreshLibrary() { if (game == null && controllerStatus == null) showLibrary(); }

    private LinearLayout page(String title, String subtitle) {
        controllerStatus = null; root.removeCallbacks(refreshControllers);
        if (panel != null) root.removeView(panel);
        panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(36), dp(24), dp(36), dp(24)); panel.setBackgroundColor(Color.rgb(10, 38, 36));
        root.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        TextView heading = text(title, 30); heading.setTypeface(null, 1); panel.addView(heading);
        TextView description = text(subtitle, 17); description.setPadding(0, dp(8), 0, dp(12)); panel.addView(description);
        return panel;
    }
    private TextView text(String value, int size) {
        TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(Color.WHITE); return text;
    }
    private Button button(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setTextSize(18);
        button.setTextColor(Color.WHITE); button.setBackgroundResource(R.drawable.launcher_tile);
        button.setPadding(dp(20), dp(8), dp(20), dp(8)); button.setMinHeight(dp(48));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(0, dp(5), dp(8), dp(5));
        parent.addView(button, params); button.setOnClickListener(view -> action.run()); return button;
    }
    private LinearLayout scrolling(LinearLayout page) {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); scroll.addView(content); return content;
    }
    private void showLibrary() {
        menu = false; input.setGameMode(false);
        LinearLayout page = page("Games", "NES · Games and saves stay on this projector. Import .nes files in the phone app, then connect to sync.");
        LinearLayout list = scrolling(page);
        View first = null;
        try {
            JSONArray games = app.games.list().getJSONArray("games");
            for (int i = 0; i < games.length(); i++) {
                JSONObject entry = games.getJSONObject(i); String id = entry.getString("id");
                Button row = button(list, entry.getString("name") + "   ·   NES", () -> startGame(id));
                if (first == null) first = row;
            }
            if (games.length() == 0) list.addView(text("Your games will appear here after syncing from the phone.", 21));
        } catch (Exception failure) { list.addView(text("The game library could not be read. Try opening Games again.", 20)); }
        if (lastError != null) list.addView(text(lastError, 18));
        Button controllers = button(page, "Controllers", this::showControllers);
        button(page, "Emulator info", this::showAbout);
        button(page, "Back to receiver", this::finish);
        (first == null ? controllers : first).requestFocusFromTouch();
    }
    private void startGame(String id) {
        stopRuntime(); lastError = null;
        try {
            JSONObject entry = app.games.entry(id);
            app.playback.noteInteraction();
            app.catMode.stop();
            if (app.playback.active()) app.playback.setPlaying(false);
            game = new NesGameView(this);
            final NesGameView opened = game;
            game.setListener(new NesGameView.Listener() {
                @Override public void onError(String message) {
                    if (game != opened) return;
                    lastError = "Game could not continue: " + message;
                    stopRuntime(); showLibrary();
                }
                @Override public void onWarning(String message) {
                    if (game == opened) Toast.makeText(GamesActivity.this, message, Toast.LENGTH_LONG).show();
                }
            });
            game.load(app.games.file(id), app.games.saves(id));
            gameId = id; gameName = entry.getString("name"); menu = false;
            controllerStatus = null; root.removeCallbacks(refreshControllers);
            root.removeAllViews(); panel = null;
            root.addView(game, new FrameLayout.LayoutParams(-1, -1));
            input.setGameMode(true);
            if (resumed && focused) game.resume();
            Toast.makeText(this, "Controller mappings and game-menu button: Controllers → NES buttons", Toast.LENGTH_LONG).show();
        } catch (Exception | LinkageError failure) {
            lastError = "Could not open this NES game: " + safeError(failure);
            stopRuntime(); showLibrary();
        }
    }
    private static String safeError(Throwable failure) {
        String value = failure.getMessage();
        if (value == null || value.length() > 180 || value.contains("/")) return failure.getClass().getSimpleName();
        return value;
    }
    private void stopRuntime() {
        if (input != null) { input.clear(); input.setGameMode(false); }
        if (game != null) { game.setListener(null); game.release(); root.removeView(game); game = null; }
        gameId = null; gameName = null;
    }
    void stopGame() { stopRuntime(); showLibrary(); }
    private void continueGame() {
        if (game == null) { showLibrary(); return; }
        controllerStatus = null; root.removeCallbacks(refreshControllers);
        if (panel != null) { root.removeView(panel); panel = null; }
        menu = false; input.setGameMode(true);
        if (resumed && focused) game.resume();
    }
    private void showMenu() {
        if (game == null) { showLibrary(); return; }
        menu = true; input.setGameMode(false); game.pause();
        LinearLayout page = page(gameName, "Paused · Progress is saved automatically when you leave.");
        LinearLayout actions = scrolling(page);
        button(actions, "Resume", this::continueGame).requestFocusFromTouch();
        button(actions, "Save checkpoint", () -> {
            try { game.saveState(); Toast.makeText(this, "Checkpoint saved", Toast.LENGTH_SHORT).show(); showMenu(); }
            catch (Exception failure) { Toast.makeText(this, "Could not save checkpoint", Toast.LENGTH_LONG).show(); }
        });
        Button restore = button(actions, "Load checkpoint", () -> loadCheckpoint(null));
        Button history = button(actions, "Checkpoint history", this::showCheckpoints);
        try { boolean available = !game.checkpoints().isEmpty(); restore.setEnabled(available); history.setEnabled(available); }
        catch (java.io.IOException failure) { Toast.makeText(this, "Cannot read checkpoint history", Toast.LENGTH_LONG).show(); }
        button(actions, "Controllers", this::showControllers);
        button(actions, "Return to game library", this::stopGame);
    }
    private void loadCheckpoint(Long id) {
        if (game == null) return;
        game.pause(); // Stop and flush audio before restoring the native state.
        try {
            if (id == null) game.loadState(); else game.loadState(id);
            // Require neutral controls so the quick-load press cannot repeat or leak into gameplay.
            input.setGameMode(false);
            continueGame();
            Toast.makeText(this, "Checkpoint loaded", Toast.LENGTH_SHORT).show();
        } catch (java.io.IOException failure) {
            Toast.makeText(this, "Could not load checkpoint: " + failure.getMessage(), Toast.LENGTH_LONG).show();
            if (!menu && resumed && focused) game.resume();
        }
    }
    private void showCheckpoints() {
        LinearLayout page = page("Checkpoint history", "Five most recent saves · Newest first. Loading keeps the history intact. The controller shortcut loads the newest save.");
        LinearLayout actions = scrolling(page);
        View first = null;
        try {
            java.util.List<CheckpointHistory.Entry> entries = game.checkpoints();
            java.text.DateFormat dates = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.MEDIUM);
            for (int i = 0; i < entries.size(); i++) {
                CheckpointHistory.Entry entry = entries.get(i);
                Button row = button(actions, (i == 0 ? "Latest" : "Previous " + i) + " · "
                    + dates.format(new java.util.Date(entry.savedAt)), () -> loadCheckpoint(entry.id));
                if (first == null) first = row;
            }
        } catch (java.io.IOException failure) { actions.addView(text("Cannot read checkpoint history", 18)); }
        Button back = button(page, "Back to game menu", this::showMenu);
        (first == null ? back : first).requestFocusFromTouch();
    }
    private void showControllers() {
        if (game != null) { menu = true; game.pause(); }
        input.setGameMode(false);
        if (game != null) showMenu();
        ControllerSetupActivity.show(this);
    }
    private void showAbout() {
        LinearLayout page = page("NES emulator", "QuickNES · Runs locally on the projector. SNES and GBA are not included in this version.");
        LinearLayout body = scrolling(page);
        String license;
        try (java.io.InputStream stream = getAssets().open("licenses/quicknes.txt");
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
            license = bytes.toString("UTF-8");
        } catch (Exception unavailable) { license = "QuickNES is licensed under the GNU Lesser General Public License, version 2.1 or later. See the application source for notices and the complete engine source."; }
        TextView text = text(license, 16); text.setTextIsSelectable(true); body.addView(text);
        button(page, "Back to games", this::showLibrary).requestFocusFromTouch();
    }
    private String controllerText() {
        JSONArray devices = ControllerInput.devices(); StringBuilder text = new StringBuilder();
        if (devices.length() == 0) text.append("No game controller detected yet.");
        for (int i = 0; i < devices.length(); i++) text.append(devices.optJSONObject(i).optString("name", "Controller")).append('\n');
        if (lastButtons != 0) text.append("\nDirection input: ").append(lastButtons);
        return text.toString();
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        return input != null && input.onMotion(event) || super.dispatchGenericMotionEvent(event);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (input != null && input.onKey(event)) return true;
        int key = event.getKeyCode();
        if (game != null && key == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) { if (menu) continueGame(); else showMenu(); }
            return true;
        }
        return handleNavigation(event);
    }
    private boolean handleNavigation(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                app.playback.stop(); app.catMode.stop(); finish(); MainActivity.bringToFront(this);
            }
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_MENU && game != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (menu) continueGame(); else showMenu();
            }
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            event = new KeyEvent(event.getDownTime(), event.getEventTime(), event.getAction(),
                KeyEvent.KEYCODE_DPAD_CENTER, event.getRepeatCount());
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (controllerStatus != null) { if (game != null) showMenu(); else showLibrary(); }
                else if (game != null) { if (menu) continueGame(); else showMenu(); }
                else finish();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}

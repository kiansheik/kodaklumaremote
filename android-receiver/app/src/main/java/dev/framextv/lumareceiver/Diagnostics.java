package dev.framextv.lumareceiver;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A small rolling log written every 15 s. A system freeze leaves no app crash
 * report, so the previous session's tail is kept across reboots and served by
 * `app.diagnostics` to show memory and playback state just before it.
 */
final class Diagnostics {
    private static final long INTERVAL_MS = 15_000;
    private static final int KEEP_LINES = 240;
    private final Context context;
    private final File current, previous;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<String> lines = new ArrayList<>();
    private PlaybackController playback;

    Diagnostics(Context context) {
        this.context = context.getApplicationContext();
        current = new File(context.getFilesDir(), "diagnostics.log");
        previous = new File(context.getFilesDir(), "diagnostics.previous.log");
        if (current.exists()) { previous.delete(); current.renameTo(previous); }
    }

    void start(PlaybackController playback) {
        this.playback = playback;
        note("start");
        main.postDelayed(new Runnable() { @Override public void run() { note("tick"); main.postDelayed(this, INTERVAL_MS); } }, INTERVAL_MS);
    }

    synchronized void note(String event) {
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        ((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(memory);
        Runtime runtime = Runtime.getRuntime();
        String line = String.format(Locale.ROOT, "%tFT%<tT uptime=%ds %s availMB=%d low=%b heapMB=%d/%d %s",
            System.currentTimeMillis(), SystemClock.elapsedRealtime() / 1000, event, memory.availMem >> 20, memory.lowMemory,
            (runtime.totalMemory() - runtime.freeMemory()) >> 20, runtime.maxMemory() >> 20,
            playback == null ? "" : playback.diagnosticSummary());
        lines.add(line);
        while (lines.size() > KEEP_LINES) lines.remove(0);
        try (FileWriter out = new FileWriter(current, true)) { out.write(line + "\n"); } catch (Exception ignored) { }
        if (current.length() > 256 * 1024) {
            try { Files.write(current.toPath(), String.join("\n", lines).concat("\n").getBytes(StandardCharsets.UTF_8)); } catch (Exception ignored) { }
        }
    }

    synchronized JSONArray tail(boolean previousSession, int count) {
        JSONArray result = new JSONArray();
        try {
            List<String> all = previousSession ? (previous.exists() ? Files.readAllLines(previous.toPath()) : new ArrayList<>()) : new ArrayList<>(lines);
            for (int i = Math.max(0, all.size() - count); i < all.size(); i++) result.put(all.get(i));
        } catch (Exception ignored) { }
        return result;
    }
}

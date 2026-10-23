package dev.framextv.lumareceiver;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Two update sources share one verified install path:
 *  - the public release feed (policy auto / ask / off), and
 *  - the dev channel, where a paired computer supplies an APK URL + SHA-256.
 * Android 9 always shows its own install confirmation for sideloaded apps.
 */
final class Updater {
    interface Ui { void onUpdateChanged(); }

    static final String FEED_URL = "https://kiansheik.io/kodaklumaremote/receiver-update.json";
    static final long MAX_APK = 64L * 1024 * 1024, CHECK_EVERY_MS = 6L * 60 * 60 * 1000;
    private final Context context;
    private final SharedPreferences preferences;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Ui ui;
    private PlaybackController playback;
    private volatile String state = "idle", error;
    private volatile JSONObject available;
    private volatile long downloaded, total;
    private volatile File verified;
    private volatile boolean installWhenIdle;

    Updater(Context context, SharedPreferences preferences) { this.context = context.getApplicationContext(); this.preferences = preferences; }

    void attach(Ui ui, PlaybackController playback) { this.ui = ui; this.playback = playback; }

    String policy() { return preferences.getString("updatePolicy", "ask"); }

    void setPolicy(String policy) throws ApiException {
        if (!("auto".equals(policy) || "ask".equals(policy) || "off".equals(policy))) throw new ApiException("INVALID_INPUT");
        preferences.edit().putString("updatePolicy", policy).apply();
        changed();
    }

    void schedule() {
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (!"off".equals(policy())) check();
                main.postDelayed(this, CHECK_EVERY_MS);
            }
        }, 30_000);
    }

    JSONObject status() throws Exception {
        JSONObject result = new JSONObject().put("policy", policy()).put("state", state)
            .put("versionName", installed().versionName).put("versionCode", versionCode(installed()))
            .put("downloadedBytes", downloaded).put("totalBytes", total)
            .put("canInstall", Build.VERSION.SDK_INT < 26 || context.getPackageManager().canRequestPackageInstalls());
        if (available != null) result.put("available", available);
        if (error != null) result.put("error", error);
        return result;
    }

    /** Public feed check; downloads in the background unless policy is off. */
    void check() {
        worker.execute(() -> {
            try {
                set("checking", null);
                JSONObject release = new JSONObject(new String(fetchSmall(FEED_URL), StandardCharsets.UTF_8));
                if (!context.getPackageName().equals(release.optString("packageName"))) throw new ApiException("UPDATE_FEED_INVALID");
                long code = release.getLong("versionCode");
                if (code <= versionCode(installed()) || release.optString("versionName").equals(preferences.getString("skippedVersion", ""))) {
                    available = null; set("idle", null); return;
                }
                available = new JSONObject().put("versionName", release.getString("versionName")).put("versionCode", code)
                    .put("notes", release.optString("notes", "")).put("source", "release");
                if ("off".equals(policy())) { set("available", null); return; }
                download(release.getString("apkUrl"), release.getString("sha256"), release.optLong("sizeBytes", 0), false);
                installWhenIdle = "auto".equals(policy());
                set("ready", null);
                if (installWhenIdle) installIfIdle();
            } catch (Exception failure) { set("idle", code(failure, "UPDATE_CHECK_FAILED")); }
        });
    }

    /** Dev channel: install exactly this APK now (same version allowed for rebuilds). */
    void installFrom(String url, String sha256, long size) throws ApiException {
        if (!sha256.matches("[0-9a-f]{64}")) throw new ApiException("INVALID_INPUT");
        worker.execute(() -> {
            try {
                available = new JSONObject().put("source", "dev");
                download(url, sha256, size, true);
                installWhenIdle = false;
                main.post(this::install);
            } catch (Exception failure) { set("failed", code(failure, "UPDATE_DOWNLOAD_FAILED")); }
        });
    }

    void skipAvailable() {
        if (available != null && "release".equals(available.optString("source")))
            preferences.edit().putString("skippedVersion", available.optString("versionName")).apply();
        available = null; verified = null; installWhenIdle = false;
        set("idle", null);
    }

    void installIfIdle() { main.post(() -> { if (installWhenIdle && (playback == null || !playback.active())) install(); }); }

    /** Main thread: hand the verified APK to PackageInstaller. */
    void install() {
        File apk = verified;
        if (apk == null) { set("idle", "UPDATE_NOT_READY"); return; }
        if (Build.VERSION.SDK_INT >= 26 && !context.getPackageManager().canRequestPackageInstalls()) {
            set("needsPermission", null);
            context.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        installWhenIdle = false;
        worker.execute(() -> {
            try {
                PackageInstaller installer = context.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(context.getPackageName());
                params.setSize(apk.length());
                int id = installer.createSession(params);
                try (PackageInstaller.Session session = installer.openSession(id)) {
                    try (OutputStream out = session.openWrite("receiver.apk", 0, apk.length()); InputStream in = new FileInputStream(apk)) {
                        byte[] buffer = new byte[64 * 1024]; int count;
                        while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
                        session.fsync(out);
                    }
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    PendingIntent callback = PendingIntent.getBroadcast(context, id, new Intent(context, InstallStatusReceiver.class), flags);
                    set("installing", null);
                    session.commit(callback.getIntentSender());
                }
            } catch (Exception failure) { set("failed", code(failure, "UPDATE_INSTALL_FAILED")); }
        });
    }

    void onInstallStatus(int status, Intent confirm) {
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION && confirm != null) {
            set("awaitingConfirmation", null);
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            set("installed", null);
        } else {
            set("failed", "UPDATE_INSTALL_STATUS_" + status);
        }
    }

    private void download(String url, String sha256, long expectedSize, boolean allowSameVersion) throws Exception {
        MediaRequest.httpUrl(url);
        File dir = new File(context.getCacheDir(), "updates");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new ApiException("UPDATE_STORAGE");
        File part = new File(dir, "download.part"), target = new File(dir, "verified.apk");
        verified = null; target.delete();
        set("downloading", null);
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15_000); connection.setReadTimeout(30_000);
        try {
            if (connection.getResponseCode() != 200) throw new ApiException("UPDATE_DOWNLOAD_HTTP_" + connection.getResponseCode());
            long length = connection.getContentLengthLong();
            if (length > MAX_APK || expectedSize > 0 && length >= 0 && length != expectedSize) throw new ApiException("UPDATE_SIZE");
            total = length > 0 ? length : expectedSize; downloaded = 0;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[64 * 1024]; int count;
                while ((count = in.read(buffer)) >= 0) {
                    downloaded += count;
                    if (downloaded > MAX_APK) throw new ApiException("UPDATE_SIZE");
                    digest.update(buffer, 0, count); out.write(buffer, 0, count);
                }
            }
            if (!hex(digest.digest()).equals(sha256.toLowerCase(Locale.ROOT))) throw new ApiException("UPDATE_HASH_MISMATCH");
        } finally { connection.disconnect(); }
        set("verifying", null);
        PackageInfo archive = context.getPackageManager().getPackageArchiveInfo(part.getAbsolutePath(), 0);
        if (archive == null || !context.getPackageName().equals(archive.packageName)) throw new ApiException("UPDATE_WRONG_PACKAGE");
        long installedCode = versionCode(installed()), archiveCode = versionCode(archive);
        if (archiveCode < installedCode || !allowSameVersion && archiveCode == installedCode) throw new ApiException("UPDATE_NOT_NEWER");
        if (!part.renameTo(target)) throw new ApiException("UPDATE_STORAGE");
        available.put("versionName", archive.versionName).put("versionCode", archiveCode);
        verified = target;
    }

    private static byte[] fetchSmall(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10_000); connection.setReadTimeout(10_000);
        try {
            if (connection.getResponseCode() != 200) throw new ApiException("UPDATE_FEED_UNAVAILABLE");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = in.read(buffer)) >= 0) { out.write(buffer, 0, count); if (out.size() > 64 * 1024) throw new ApiException("UPDATE_FEED_INVALID"); }
            }
            return out.toByteArray();
        } finally { connection.disconnect(); }
    }

    private PackageInfo installed() {
        try { return context.getPackageManager().getPackageInfo(context.getPackageName(), 0); }
        catch (PackageManager.NameNotFoundException impossible) { throw new IllegalStateException(impossible); }
    }

    @SuppressWarnings("deprecation")
    static long versionCode(PackageInfo info) { return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode; }

    private void set(String newState, String newError) {
        state = newState; error = newError;
        android.util.Log.i("LumaUpdate", newState + (newError == null ? "" : " " + newError));
        changed();
    }

    private void changed() { Ui target = ui; if (target != null) main.post(target::onUpdateChanged); }

    private static String code(Exception failure, String fallback) { return failure instanceof ApiException ? ((ApiException) failure).code : fallback; }

    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b));
        return out.toString();
    }
}

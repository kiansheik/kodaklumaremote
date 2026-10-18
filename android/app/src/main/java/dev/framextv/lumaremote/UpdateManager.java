package dev.framextv.lumaremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.Toast;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Optional, foreground-only update offers. Android always owns installation. */
public final class UpdateManager {
    static final String MANIFEST_URL = "https://kiansheik.io/kodaklumaremote/update.json";
    static final long MAX_APK_BYTES = 64L * 1024 * 1024;
    private static final long CHECK_INTERVAL = 5L * 60 * 1000;
    private static final long LATER_INTERVAL = 24L * 60 * 60 * 1000;
    private final Activity activity;
    private final Context context;
    private final URL manifestUrl;
    private final boolean fixture;
    private final SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile HttpURLConnection activeConnection;
    private volatile boolean closed;
    private boolean foreground, busy, awaitingPermission;
    private Release pending;
    private File verified;
    private AlertDialog dialog;

    public static final class UpdateException extends Exception {
        public final String code;
        UpdateException(String code) { super(code); this.code = code; }
    }
    static final class Release {
        final String packageName, versionName, apkUrl, sha256, notes, releasedAt;
        final int versionCode, minSdk;
        final long sizeBytes;
        Release(String packageName, String versionName, int versionCode, int minSdk, String apkUrl,
                String sha256, long sizeBytes, String notes, String releasedAt) {
            this.packageName = packageName; this.versionName = versionName; this.versionCode = versionCode;
            this.minSdk = minSdk; this.apkUrl = apkUrl; this.sha256 = sha256; this.sizeBytes = sizeBytes;
            this.notes = notes; this.releasedAt = releasedAt;
        }
    }

    public UpdateManager(Activity activity) {
        this(activity, defaultUrl(), false);
    }
    private UpdateManager(Activity activity, URL manifestUrl, boolean fixture) {
        this.activity = activity; context = activity.getApplicationContext();
        this.manifestUrl = manifestUrl; this.fixture = fixture;
        preferences = context.getSharedPreferences("luma_updates", Context.MODE_PRIVATE);
    }
    private static URL defaultUrl() {
        try { return new URL(MANIFEST_URL); } catch (Exception impossible) { throw new IllegalStateException(); }
    }
    /** No Intent/JS override exists. Only explicit debuggable emulator tests use HTTP. */
    static UpdateManager forEmulatorTest(Activity activity, URL fixtureUrl) throws UpdateException {
        requireEmulatorFixture(activity, fixtureUrl);
        return new UpdateManager(activity, fixtureUrl, true);
    }
    static void requireEmulatorFixture(Context context, URL url) throws UpdateException {
        boolean debug = (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        boolean emulator = Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator")
            || Build.MODEL.contains("Emulator") || Build.MODEL.contains("Android SDK") || Build.HARDWARE.equals("ranchu") || Build.HARDWARE.equals("goldfish");
        if (!debug || !emulator || !fixtureUrl(url)) throw new UpdateException("UPDATE_FIXTURE_FORBIDDEN");
    }
    private static boolean fixtureUrl(URL url) {
        return "http".equals(url.getProtocol()) && ("10.0.2.2".equals(url.getHost()) || "127.0.0.1".equals(url.getHost()))
            && url.getUserInfo() == null && url.getRef() == null && url.getPort() > 0;
    }

    public void onResume() {
        foreground = true;
        if (closed || busy) return;
        if (awaitingPermission) {
            awaitingPermission = false;
            if (pending != null && verified != null) offerContinue();
            return;
        }
        if (pending != null && verified != null) { offerContinue(); return; }
        check(false);
    }
    public void onPause() { foreground = false; dismiss(); }
    public void close() {
        closed = true; foreground = false; dismiss();
        HttpURLConnection connection = activeConnection; if (connection != null) connection.disconnect();
        worker.shutdownNow(); main.removeCallbacksAndMessages(null);
    }
    public void checkNow() { check(true); }
    private void check(boolean manual) {
        if (closed || !foreground) return;
        if (busy) { if (manual) toast("An update check or download is already running."); return; }
        long now = System.currentTimeMillis(), last = preferences.getLong("lastCheck", 0);
        if (!manual && last > 0 && now >= last && now - last < CHECK_INTERVAL) return;
        preferences.edit().putLong("lastCheck", now).apply();
        busy = true;
        worker.execute(() -> {
            Release release = null; String failure = null;
            try {
                release = readRelease();
                if (!isNewer(context, release)) release = null;
            } catch (Exception error) { failure = fixedCode(error); }
            Release found = release; String problem = failure;
            main.post(() -> {
                busy = false;
                if (closed || !foreground) return;
                if (problem != null) { if (manual) notifyFailure(problem); return; }
                if (found == null) { if (manual) toast("LUMA is up to date."); return; }
                long later = preferences.getLong("laterAt", 0);
                if (!manual && found.versionCode == preferences.getInt("laterVersion", 0)
                        && now >= later && now - later < LATER_INTERVAL) return;
                offer(found);
            });
        });
    }
    private void offer(Release release) {
        if (!foreground || closed) return;
        dismiss();
        String description = release.notes.isEmpty() ? "A new version of LUMA is available." : release.notes;
        dialog = new AlertDialog.Builder(activity).setTitle("LUMA " + release.versionName + " is available")
            .setMessage(description).setNegativeButton("Later", (view, which) -> {
                preferences.edit().putInt("laterVersion", release.versionCode).putLong("laterAt", System.currentTimeMillis()).apply();
            }).setPositiveButton("Update", (view, which) -> download(release)).create();
        dialog.setOnCancelListener(view -> preferences.edit().putInt("laterVersion", release.versionCode).putLong("laterAt", System.currentTimeMillis()).apply());
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
    }
    private void download(Release release) {
        if (closed || busy || !foreground) return;
        busy = true; pending = release; toast("Downloading update…");
        worker.execute(() -> {
            File result = null; String failure = null;
            try { result = downloadVerified(release); } catch (Exception error) { failure = fixedCode(error); }
            File file = result; String problem = failure;
            main.post(() -> {
                busy = false;
                if (closed) return;
                if (problem != null) { pending = null; verified = null; if (foreground) notifyFailure(problem); return; }
                verified = file;
                if (foreground) requestInstall();
            });
        });
    }
    private void offerContinue() {
        if (!foreground || closed || pending == null || verified == null) return;
        dismiss();
        dialog = new AlertDialog.Builder(activity).setTitle("Update ready")
            .setMessage("Continue to Android’s installer for LUMA " + pending.versionName + ".")
            .setNegativeButton("Later", (view, which) -> { pending = null; verified = null; })
            .setPositiveButton("Continue", (view, which) -> requestInstall()).create();
        dialog.show(); dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
    }
    private void requestInstall() {
        if (!foreground || closed || busy || pending == null || verified == null) return;
        if (Build.VERSION.SDK_INT >= 26 && !context.getPackageManager().canRequestPackageInstalls()) {
            dismiss();
            dialog = new AlertDialog.Builder(activity).setTitle("Allow LUMA updates")
                .setMessage("Android needs your permission to install updates from LUMA. Return here afterward to continue.")
                .setNegativeButton("Later", (view, which) -> { pending = null; verified = null; })
                .setPositiveButton("Open settings", (view, which) -> {
                    awaitingPermission = true;
                    try { activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.getPackageName()))); }
                    catch (RuntimeException unavailable) { awaitingPermission = false; notifyFailure("UPDATE_INSTALLER_UNAVAILABLE"); }
                }).create();
            dialog.show(); dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus(); return;
        }
        busy = true; Release selected = pending; File file = verified;
        worker.execute(() -> {
            Intent install = null; String failure = null;
            try {
                if (!isNewer(context, selected)) throw new UpdateException("UPDATE_NOT_NEWER");
                verifyArchive(context, file, selected);
                Uri uri = UpdateApkProvider.expose(context, file, selected.sha256, selected.sizeBytes);
                install = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                install.setClipData(ClipData.newRawUri("LUMA update", uri));
            } catch (Exception error) { failure = fixedCode(error); }
            Intent intent = install; String problem = failure;
            main.post(() -> {
                busy = false;
                if (closed || !foreground) return;
                if (problem != null) { pending = null; verified = null; notifyFailure(problem); return; }
                pending = null; verified = null;
                try { activity.startActivity(intent); }
                catch (RuntimeException unavailable) { UpdateApkProvider.revoke(context); notifyFailure("UPDATE_INSTALLER_UNAVAILABLE"); }
            });
        });
    }
    private void dismiss() { if (dialog != null) { dialog.dismiss(); dialog = null; } }
    private void toast(String text) { if (!closed && foreground) Toast.makeText(activity, text, Toast.LENGTH_LONG).show(); }
    private void notifyFailure(String code) {
        String message = "Could not check or download the update. Try again later.";
        if (code.equals("UPDATE_UNTRUSTED")) message = "This update does not match the installed app’s signature. Use the original installation source.";
        else if (code.equals("UPDATE_INCOMPATIBLE")) message = "This update requires a newer Android version.";
        else if (code.equals("UPDATE_INVALID_APK") || code.equals("UPDATE_HASH") || code.equals("UPDATE_SIZE")) message = "The update could not be verified. It was not opened.";
        else if (code.equals("UPDATE_INSTALLER_UNAVAILABLE")) message = "Android’s update installer is unavailable. You can install from the project’s download page.";
        toast(message);
    }

    Release readRelease() throws Exception {
        byte[] bytes;
        HttpURLConnection connection = open(manifestUrl, false, SystemClock.elapsedRealtime() + 10000);
        try (InputStream input = connection.getInputStream()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] chunk = new byte[4096]; int read;
            long end = SystemClock.elapsedRealtime() + 10000;
            while ((read = input.read(chunk)) >= 0) {
                if (closed || Thread.currentThread().isInterrupted() || SystemClock.elapsedRealtime() > end) throw new UpdateException("UPDATE_TIMEOUT");
                if (output.size() + read > 32768) throw new UpdateException("UPDATE_MANIFEST"); output.write(chunk, 0, read);
            }
            bytes = output.toByteArray();
        } finally { connection.disconnect(); activeConnection = null; }
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        return parseRelease(text, context.getPackageName(), fixture);
    }
    static Release parseRelease(String text, String expectedPackage, boolean fixture) throws Exception {
        JSONTokener tokens = new JSONTokener(text); Object parsed = tokens.nextValue();
        if (!(parsed instanceof JSONObject) || tokens.nextClean() != 0) throw new UpdateException("UPDATE_MANIFEST");
        JSONObject data = (JSONObject) parsed;
        if (number(data, "schemaVersion", 1, 1) != 1) throw new UpdateException("UPDATE_MANIFEST");
        String pkg = string(data, "packageName", 200), version = string(data, "versionName", 32);
        if (!expectedPackage.equals(pkg) || !version.matches("[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}")) throw new UpdateException("UPDATE_MANIFEST");
        int code = (int) number(data, "versionCode", 1, Integer.MAX_VALUE), minSdk = (int) number(data, "minSdk", 23, 1000);
        String apk = string(data, "apkUrl", 2048), hash = string(data, "sha256", 64), notes = string(data, "notes", 2000), released = string(data, "releasedAt", 40);
        if (!hash.matches("[0-9a-f]{64}") || !validDate(released)) throw new UpdateException("UPDATE_MANIFEST");
        URL url = new URI(apk).toURL();
        if (!(fixture && fixtureUrl(url)) && !releaseUrl(url, version)) throw new UpdateException("UPDATE_URL");
        return new Release(pkg, version, code, minSdk, apk, hash, number(data, "sizeBytes", 1, MAX_APK_BYTES), notes, released);
    }
    private static boolean validDate(String value) {
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{3})?Z")) return false;
        SimpleDateFormat format = new SimpleDateFormat(value.contains(".") ? "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" : "yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC")); format.setLenient(false); ParsePosition position = new ParsePosition(0);
        return format.parse(value, position) != null && position.getIndex() == value.length();
    }
    private static String string(JSONObject data, String key, int max) throws Exception {
        Object value = data.get(key);
        if (!(value instanceof String) || ((String) value).length() > max || ((String) value).matches("(?s).*[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f].*")) throw new UpdateException("UPDATE_MANIFEST");
        return (String) value;
    }
    private static long number(JSONObject data, String key, long min, long max) throws Exception {
        Object raw = data.get(key); if (!(raw instanceof Number)) throw new UpdateException("UPDATE_MANIFEST");
        double value = ((Number) raw).doubleValue();
        if (Double.isNaN(value) || Double.isInfinite(value) || value != Math.floor(value) || value < min || value > max) throw new UpdateException("UPDATE_MANIFEST");
        return ((Number) raw).longValue();
    }
    static boolean releaseUrl(URL url, String version) {
        return "https".equals(url.getProtocol()) && "github.com".equals(url.getHost()) && (url.getPort() == -1 || url.getPort() == 443)
            && url.getUserInfo() == null && url.getQuery() == null && url.getRef() == null
            && ("/kiansheik/kodaklumaremote/releases/download/v" + version + "/luma-remote-" + version + ".apk").equals(url.getPath());
    }
    private boolean permitted(URL url, boolean apk, boolean initial) {
        if (fixture && fixtureUrl(url)) return true;
        if (!apk) return MANIFEST_URL.equals(url.toString());
        if (initial) return url.getPath().matches("/kiansheik/kodaklumaremote/releases/download/v([0-9]+\\.[0-9]+\\.[0-9]+)/luma-remote-\\1\\.apk")
                && "https".equals(url.getProtocol()) && "github.com".equals(url.getHost()) && url.getUserInfo() == null && url.getQuery() == null && url.getRef() == null
                && (url.getPort() == -1 || url.getPort() == 443);
        return "https".equals(url.getProtocol()) && ("release-assets.githubusercontent.com".equals(url.getHost()) || "objects.githubusercontent.com".equals(url.getHost()))
            && (url.getPort() == -1 || url.getPort() == 443) && url.getUserInfo() == null && url.getRef() == null
            && url.getPath().startsWith("/github-production-release-asset/");
    }
    private HttpURLConnection open(URL start, boolean apk, long end) throws Exception {
        URL url = start;
        for (int redirects = 0; redirects <= 3; redirects++) {
            if (closed || Thread.currentThread().isInterrupted() || SystemClock.elapsedRealtime() >= end) throw new UpdateException("UPDATE_TIMEOUT");
            if (!permitted(url, apk, redirects == 0)) throw new UpdateException("UPDATE_URL");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection(); activeConnection = connection;
            connection.setConnectTimeout((int) Math.min(5000, Math.max(1, end - SystemClock.elapsedRealtime())));
            connection.setReadTimeout((int) Math.min(5000, Math.max(1, end - SystemClock.elapsedRealtime())));
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
            connection.setRequestProperty("Accept", apk ? "application/octet-stream" : "application/json");
            int status;
            try { status = connection.getResponseCode(); }
            catch (Exception failure) {
                connection.disconnect(); if (activeConnection == connection) activeConnection = null;
                throw failure;
            }
            if (status == 200) return connection;
            String location = connection.getHeaderField("Location"); connection.disconnect(); activeConnection = null;
            if (!apk || redirects == 3 || !(status == 301 || status == 302 || status == 303 || status == 307 || status == 308) || location == null) throw new UpdateException("UPDATE_HTTP");
            url = new URL(url, location);
        }
        throw new UpdateException("UPDATE_HTTP");
    }
    File downloadVerified(Release release) throws Exception {
        return downloadVerified(release, true);
    }
    File downloadForEmulatorTest(Release release) throws Exception {
        if (!fixture) throw new UpdateException("UPDATE_FIXTURE_FORBIDDEN");
        requireEmulatorFixture(context, manifestUrl);
        return downloadVerified(release, false);
    }
    private File downloadVerified(Release release, boolean requireUpgrade) throws Exception {
        if (requireUpgrade && !isNewer(context, release)) throw new UpdateException("UPDATE_NOT_NEWER");
        if (release.minSdk > Build.VERSION.SDK_INT) throw new UpdateException("UPDATE_INCOMPATIBLE");
        URL downloadUrl = new URL(release.apkUrl);
        if (!(fixture && fixtureUrl(downloadUrl)) && !releaseUrl(downloadUrl, release.versionName)) throw new UpdateException("UPDATE_URL");
        File directory = new File(context.getCacheDir(), "updates");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new UpdateException("UPDATE_STORAGE");
        UpdateApkProvider.revoke(context);
        File partial = new File(directory, "candidate.part"), file = new File(directory, "verified.apk");
        if (partial.exists() && !partial.delete() || file.exists() && !file.delete()) throw new UpdateException("UPDATE_STORAGE");
        long end = SystemClock.elapsedRealtime() + 120000; HttpURLConnection connection = null;
        try {
            connection = open(downloadUrl, true, end);
            long length = connection.getContentLength(); if (length >= 0 && length != release.sizeBytes) throw new UpdateException("UPDATE_SIZE");
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(partial)) {
                byte[] chunk = new byte[16384]; long count = 0; int read;
                while ((read = input.read(chunk)) >= 0) {
                    if (closed || Thread.currentThread().isInterrupted() || SystemClock.elapsedRealtime() > end) throw new UpdateException("UPDATE_TIMEOUT");
                    count += read; if (count > release.sizeBytes || count > MAX_APK_BYTES) throw new UpdateException("UPDATE_SIZE"); output.write(chunk, 0, read);
                }
                if (count != release.sizeBytes) throw new UpdateException("UPDATE_SIZE"); output.getFD().sync();
            }
            verifyArchive(context, partial, release);
            if (!partial.renameTo(file)) throw new UpdateException("UPDATE_STORAGE");
            return file;
        } finally {
            if (connection != null) connection.disconnect(); activeConnection = null;
            if (partial.exists()) partial.delete();
        }
    }
    static long versionCode(PackageInfo info) { return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode; }
    static boolean isNewer(Context context, Release release) throws Exception {
        if (release.minSdk > Build.VERSION.SDK_INT) throw new UpdateException("UPDATE_INCOMPATIBLE");
        return release.versionCode > versionCode(context.getPackageManager().getPackageInfo(context.getPackageName(), 0));
    }
    static void verifyArchive(Context context, File file, Release release) throws Exception {
        if (!file.isFile() || file.length() != release.sizeBytes || file.length() > MAX_APK_BYTES) throw new UpdateException("UPDATE_SIZE");
        if (!release.sha256.equals(sha256(file))) throw new UpdateException("UPDATE_HASH");
        PackageManager manager = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo archive = manager.getPackageArchiveInfo(file.getAbsolutePath(), flags), installed = manager.getPackageInfo(context.getPackageName(), flags);
        if (archive == null || !context.getPackageName().equals(archive.packageName) || !release.packageName.equals(archive.packageName)
                || versionCode(archive) != release.versionCode || !release.versionName.equals(archive.versionName)) throw new UpdateException("UPDATE_INVALID_APK");
        if (release.minSdk > Build.VERSION.SDK_INT || archive.applicationInfo == null) throw new UpdateException("UPDATE_INCOMPATIBLE");
        // On API23 Android's installer checks the binary minimum; the public
        // archive minSdkVersion field is available only from API24 onward.
        if (Build.VERSION.SDK_INT >= 24 && (archive.applicationInfo.minSdkVersion != release.minSdk
                || archive.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT)) throw new UpdateException("UPDATE_INCOMPATIBLE");
        requireSameSigners(installed, archive);
    }
    static void requireSameSigners(PackageInfo installed, PackageInfo archive) throws Exception {
        Set<String> current = signerHashes(installed), downloaded = signerHashes(archive);
        if (current.isEmpty() || !current.equals(downloaded)) throw new UpdateException("UPDATE_UNTRUSTED");
    }
    private static Set<String> signerHashes(PackageInfo info) throws Exception {
        Signature[] signatures = Build.VERSION.SDK_INT >= 28 ? (info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners()) : info.signatures;
        Set<String> result = new HashSet<>();
        if (signatures != null) for (Signature signature : signatures) result.add(hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));
        return result;
    }
    static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) { byte[] bytes = new byte[16384]; int count; while ((count = input.read(bytes)) >= 0) digest.update(bytes, 0, count); }
        return hex(digest.digest());
    }
    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2); for (byte value : bytes) text.append(String.format(Locale.ROOT, "%02x", value & 255)); return text.toString();
    }
    private static String fixedCode(Exception error) { return error instanceof UpdateException ? ((UpdateException) error).code : "UPDATE_FAILED"; }
}

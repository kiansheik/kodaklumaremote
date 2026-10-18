package dev.framextv.lumaremote;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.WebView;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.net.URL;

/** Debug emulator HTTP/APK fixtures only. Never starts an installer or changes setup. */
public final class UpdateInstrumentation {
    private final Instrumentation runner;
    private final Bundle args;
    private Activity activity;
    private UpdateManager manager;
    private int checks;
    private UpdateInstrumentation(Instrumentation runner, Bundle args) { this.runner = runner; this.args = args; }
    public static void run(Instrumentation runner, Bundle args) { new UpdateInstrumentation(runner, args).execute(); }
    private static void require(boolean condition) { if (!condition) throw new AssertionError("UPDATE_FIXTURE_ASSERTION"); }
    private interface Action { void run() throws Exception; }
    private void rejected(String code, Action action) throws Exception {
        try { action.run(); throw new AssertionError("UPDATE_FIXTURE_EXPECTED_REJECTION"); }
        catch (UpdateManager.UpdateException expected) { require(code.equals(expected.code)); }
    }
    private void passed(String name) {
        checks++; Bundle progress = new Bundle(); progress.putString("test", name); progress.putInt("current", checks); runner.sendStatus(0, progress);
    }
    private JSONObject descriptor(PackageInfo installed, String url) throws Exception {
        return new JSONObject().put("schemaVersion", 1).put("packageName", installed.packageName)
            .put("versionCode", UpdateManager.versionCode(installed)).put("versionName", installed.versionName)
            .put("minSdk", 23).put("apkUrl", url).put("sha256", args.getString("sha256"))
            .put("sizeBytes", Long.parseLong(args.getString("sizeBytes")))
            .put("releasedAt", "2026-10-18T12:00:00.000Z").put("notes", "Emulator update fixture.");
    }
    private UpdateManager.Release parse(JSONObject data, boolean fixture) throws Exception {
        return UpdateManager.parseRelease(data.toString(), runner.getTargetContext().getPackageName(), fixture);
    }
    private JSONObject copy(JSONObject data, String key, Object value) throws Exception { return new JSONObject(data.toString()).put(key, value); }
    private void execute() {
        Bundle result = new Bundle();
        try {
            URL endpoint = new URL(args.getString("manifestUrl"));
            UpdateManager.requireEmulatorFixture(runner.getTargetContext(), endpoint);
            require("10.0.2.2".equals(endpoint.getHost()));
            JSONObject savedBefore = new SettingsStore(runner.getTargetContext()).metadata();
            Intent launch = new Intent(runner.getTargetContext(), MainActivity.class).setAction(Intent.ACTION_VIEW).setData(Uri.parse("https://example.invalid/"))
                .putExtra("dev.framextv.lumaremote.TEST_NO_AUTO_CONNECT", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity = runner.startActivitySync(launch);
            runner.runOnMainSync(() -> {
                WebView browser = activity.getWindow().getDecorView().findViewWithTag("luma-browser"); browser.stopLoading(); browser.loadUrl("about:blank");
            });
            manager = UpdateManager.forEmulatorTest(activity, endpoint);
            PackageManager packages = runner.getTargetContext().getPackageManager();
            int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo installed = packages.getPackageInfo(runner.getTargetContext().getPackageName(), flags);
            String asset = "http://10.0.2.2:" + endpoint.getPort() + "/update.apk";
            JSONObject valid = descriptor(installed, asset);
            String publicAsset = "https://github.com/kiansheik/kodaklumaremote/releases/download/v" + installed.versionName + "/luma-remote-" + installed.versionName + ".apk";
            parse(copy(valid, "apkUrl", publicAsset), false);
            for (String denied : new String[] {"http://github.com/kiansheik/kodaklumaremote/releases/download/v1.0.0/luma-remote-1.0.0.apk", "https://github.com/other/repo/releases/download/v1.0.0/a.apk", "https://github.com@evil.example/kiansheik/kodaklumaremote/releases/download/v1.0.0/a.apk", publicAsset + "?token=ignored", publicAsset + "#fragment"}) {
                rejected("UPDATE_URL", () -> parse(copy(valid, "apkUrl", denied), false));
            }
            rejected("UPDATE_MANIFEST", () -> parse(copy(valid, "sha256", "A".repeat(64)), true));
            rejected("UPDATE_MANIFEST", () -> parse(copy(valid, "versionCode", "6"), true));
            rejected("UPDATE_MANIFEST", () -> parse(copy(valid, "sizeBytes", UpdateManager.MAX_APK_BYTES + 1), true));
            rejected("UPDATE_MANIFEST", () -> parse(copy(valid, "releasedAt", "2026-02-30T12:00:00Z"), true));
            rejected("UPDATE_FIXTURE_FORBIDDEN", () -> UpdateManager.forEmulatorTest(activity, new URL("http://192.168.1.2:8080/update.json")));
            passed("strict update schema, repository URL allowlist and emulator-only fixture overrides");

            UpdateManager.Release release = manager.readRelease();
            require(release.versionCode == UpdateManager.versionCode(installed) && !UpdateManager.isNewer(runner.getTargetContext(), release));
            require(UpdateManager.isNewer(runner.getTargetContext(), parse(copy(valid, "versionCode", release.versionCode + 1), true)));
            rejected("UPDATE_INCOMPATIBLE", () -> UpdateManager.isNewer(runner.getTargetContext(), parse(copy(valid, "minSdk", Build.VERSION.SDK_INT + 1), true)));
            rejected("UPDATE_NOT_NEWER", () -> manager.downloadVerified(release));
            passed("real HTTP manifest and upgrade, downgrade and Android compatibility policy");

            File downloaded = manager.downloadForEmulatorTest(release);
            UpdateManager.verifyArchive(runner.getTargetContext(), downloaded, release);
            require(downloaded.length() == release.sizeBytes && UpdateManager.sha256(downloaded).equals(release.sha256));
            passed("bounded HTTP APK download verifies real archive identity, size, digest and installed signers");

            rejected("UPDATE_SIZE", () -> UpdateManager.verifyArchive(runner.getTargetContext(), downloaded, parse(copy(valid, "sizeBytes", release.sizeBytes + 1), true)));
            rejected("UPDATE_HASH", () -> UpdateManager.verifyArchive(runner.getTargetContext(), downloaded, parse(copy(valid, "sha256", "0".repeat(64)), true)));
            UpdateManager.Release wrongPackage = new UpdateManager.Release("example.wrong", release.versionName, release.versionCode, release.minSdk, release.apkUrl, release.sha256, release.sizeBytes, release.notes, release.releasedAt);
            rejected("UPDATE_INVALID_APK", () -> UpdateManager.verifyArchive(runner.getTargetContext(), downloaded, wrongPackage));
            rejected("UPDATE_INVALID_APK", () -> UpdateManager.verifyArchive(runner.getTargetContext(), downloaded, parse(copy(valid, "versionCode", release.versionCode + 1), true)));
            rejected("UPDATE_INVALID_APK", () -> UpdateManager.verifyArchive(runner.getTargetContext(), downloaded, parse(copy(valid, "versionName", "999.0.0"), true)));
            if (Build.VERSION.SDK_INT >= 24) rejected("UPDATE_INCOMPATIBLE", () -> UpdateManager.verifyArchive(runner.getTargetContext(), downloaded, parse(copy(valid, "minSdk", 24), true)));
            rejected("UPDATE_UNTRUSTED", () -> UpdateManager.requireSameSigners(installed, new PackageInfo()));
            passed("mismatched digest, size, package, version, archive minimum and absent signing identity fail closed");

            Uri uri = UpdateApkProvider.expose(runner.getTargetContext(), downloaded, release.sha256, release.sizeBytes);
            try (ParcelFileDescriptor descriptor = runner.getTargetContext().getContentResolver().openFileDescriptor(uri, "r"); FileInputStream input = new FileInputStream(descriptor.getFileDescriptor())) {
                require(input.read() == 'P' && input.read() == 'K');
            }
            try (Cursor info = runner.getTargetContext().getContentResolver().query(uri, new String[] {OpenableColumns.SIZE, "_data"}, null, null, null)) {
                require(info != null && info.moveToFirst() && info.getLong(0) == release.sizeBytes && info.isNull(1));
            }
            for (String mode : new String[] {"w", "rw"}) {
                boolean refused = false;
                try (ParcelFileDescriptor ignored = runner.getTargetContext().getContentResolver().openFileDescriptor(uri, mode)) { }
                catch (java.io.FileNotFoundException | SecurityException expected) { refused = true; }
                require(refused);
            }
            UpdateApkProvider.revoke(runner.getTargetContext());
            boolean revoked = false;
            try (ParcelFileDescriptor ignored = runner.getTargetContext().getContentResolver().openFileDescriptor(uri, "r")) { }
            catch (java.io.FileNotFoundException | SecurityException expected) { revoked = true; }
            require(revoked);
            passed("temporary APK provider grants only verified read access and revokes without exposing file paths");

            UpdateManager oversized = UpdateManager.forEmulatorTest(activity, new URL(endpoint, "/oversized.json"));
            try { rejected("UPDATE_MANIFEST", oversized::readRelease); } finally { oversized.close(); }
            UpdateManager.Release redirected = parse(copy(valid, "apkUrl", "http://10.0.2.2:" + endpoint.getPort() + "/redirect.apk"), true);
            rejected("UPDATE_URL", () -> manager.downloadForEmulatorTest(redirected));
            JSONObject savedAfter = new SettingsStore(runner.getTargetContext()).metadata();
            require(savedBefore.toString().equals(savedAfter.toString()));
            passed("oversized manifests and untrusted redirects fail without installer, network controls or settings reset");
            result.putInt("passed", checks); result.putInt("failures", 0); runner.finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putInt("passed", checks); result.putInt("failures", 1);
            result.putString("failure", error instanceof UpdateManager.UpdateException ? ((UpdateManager.UpdateException) error).code : error.getClass().getSimpleName());
            runner.finish(Activity.RESULT_CANCELED, result);
        } finally {
            if (manager != null) manager.close(); UpdateApkProvider.revoke(runner.getTargetContext());
            if (activity != null) runner.runOnMainSync(activity::finish);
        }
    }
}

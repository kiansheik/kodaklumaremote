package dev.framextv.lumaremote;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Bundle;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;

/** No Activity or network access. Only explicit emulator seed mode writes data. */
public final class SigningMigrationInstrumentation {
    private static final String PACKAGE = "dev.framextv.lumaremote";
    private static final String MARKER = "signing-migration-fixture-v1";
    private static final byte[] MARKER_BYTES = "LUMA signing migration fixture v1".getBytes(StandardCharsets.UTF_8);
    private static final String USER = "signing-fixture-user", PASSWORD = "signing-fixture-secret";

    private static void require(boolean value) { if (!value) throw new AssertionError(); }
    private static boolean emulator() {
        String hardware = Build.HARDWARE == null ? "" : Build.HARDWARE.toLowerCase(Locale.ROOT);
        String product = Build.PRODUCT == null ? "" : Build.PRODUCT.toLowerCase(Locale.ROOT);
        return (hardware.equals("ranchu") || hardware.equals("goldfish"))
            && (product.startsWith("sdk_") || product.equals("google_sdk") || product.contains("emulator"));
    }
    private static String signer(Context context) throws Exception {
        PackageManager manager = context.getPackageManager();
        Signature[] certificates;
        if (Build.VERSION.SDK_INT >= 28) {
            PackageInfo info = manager.getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES);
            require(info.signingInfo != null);
            certificates = info.signingInfo.getApkContentsSigners();
        } else {
            certificates = manager.getPackageInfo(PACKAGE, PackageManager.GET_SIGNATURES).signatures;
        }
        require(certificates != null && certificates.length == 1);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificates[0].toByteArray());
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
    private static boolean marker(Context context) throws Exception {
        // getFilesDir() may create its directory; check mode must not do that.
        File file = new File(context.getApplicationInfo().dataDir, "files/" + MARKER);
        if (!file.isFile() || file.length() != MARKER_BYTES.length) return false;
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[MARKER_BYTES.length]; int offset = 0, count;
            while (offset < bytes.length && (count = input.read(bytes, offset, bytes.length - offset)) > 0) offset += count;
            return offset == bytes.length && input.read() == -1 && Arrays.equals(bytes, MARKER_BYTES);
        }
    }
    public static void run(Instrumentation runner, Bundle args) {
        Bundle result = new Bundle(); String stage = "arguments";
        try {
            Context context = runner.getTargetContext();
            require(PACKAGE.equals(context.getPackageName()));
            boolean seed = "migration-seed".equals(args.getString("mode"));
            require(seed || "migration-check".equals(args.getString("mode")));
            if (seed) {
                stage = "emulator_guard";
                require(Build.VERSION.SDK_INT >= 33 && emulator() && "true".equals(args.getString("confirmEmulatorSeed")));
                stage = "seed_settings";
                SettingsStore store = new SettingsStore(context);
                store.saveReceiver("10.0.2.2", "Signing migration fixture");
                store.saveKodi(new JSONObject().put("username", USER).put("password", PASSWORD).put("port", 8080));
                stage = "seed_marker";
                try (FileOutputStream output = context.openFileOutput(MARKER, Context.MODE_PRIVATE)) { output.write(MARKER_BYTES); }
            }
            stage = "signer";
            String current = signer(context);
            if (!seed) {
                String expected = args.getString("expectedSignerSha256", "").replace(":", "").toLowerCase(Locale.ROOT);
                require(expected.matches("[a-f0-9]{64}") && expected.equals(current));
            }
            result.putString("currentSignerSha256", current);
            stage = "settings_decryption";
            SettingsStore restored = new SettingsStore(context);
            JSONObject metadata = restored.metadata(), credentials = restored.readKodi(), receiver = restored.readReceiver();
            boolean hasReceiver = metadata.getBoolean("hasReceiver"), hasKodi = metadata.getBoolean("hasKodi");
            require(hasReceiver == (receiver != null) && hasKodi == (credentials != null));
            result.putBoolean("hasReceiver", hasReceiver); result.putBoolean("hasKodi", hasKodi);
            result.putBoolean("decrypted", credentials != null);
            stage = "private_marker";
            boolean present = marker(context);
            result.putBoolean("markerPresent", present);
            boolean fixture = seed || "true".equals(args.getString("expectFixture"));
            result.putBoolean("fixtureChecked", fixture);
            if (fixture) {
                stage = "fixture_preservation";
                require(present && hasReceiver && hasKodi && "10.0.2.2".equals(receiver.getString("host"))
                    && "Signing migration fixture".equals(receiver.getString("name"))
                    && USER.equals(credentials.getString("username")) && PASSWORD.equals(credentials.getString("password"))
                    && credentials.getInt("port") == 8080);
                result.putBoolean("fixtureMatched", true);
            }
            result.putString("migrationMode", seed ? "seed" : "check");
            result.putInt("passed", 1); result.putInt("failures", 0);
            runner.finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putInt("passed", 0); result.putInt("failures", 1);
            result.putString("failure", stage + ":" + error.getClass().getSimpleName());
            runner.finish(Activity.RESULT_CANCELED, result);
        }
    }
}

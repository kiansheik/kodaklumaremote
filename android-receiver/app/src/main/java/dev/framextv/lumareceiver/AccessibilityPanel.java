package dev.framextv.lumareceiver;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import org.json.JSONArray;
import org.json.JSONObject;

/** Firmware-specific settings entry points, always subject to Android export/permission checks. */
final class AccessibilityPanel {
    static Intent direct(Context context) {
        PackageManager pm = context.getPackageManager();
        for (String action : new String[]{"android.settings.ACCESSIBILITY_DETAILS_SETTINGS", android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS}) {
            Intent intent = new Intent(action);
            if (action.endsWith("DETAILS_SETTINGS")) intent.putExtra("android.intent.extra.COMPONENT_NAME",
                new android.content.ComponentName(context, ControllerSystemService.class));
            if (intent.resolveActivity(pm) != null) return intent;
        }
        for (PackageInfo pkg : pm.getInstalledPackages(PackageManager.GET_ACTIVITIES)) {
            if (pkg.activities == null || (pkg.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0) continue;
            for (ActivityInfo activity : pkg.activities) {
                if (activity.name.toLowerCase(java.util.Locale.ROOT).contains("accessibility") && callable(context, activity))
                    return new Intent().setClassName(pkg.packageName, activity.name);
            }
        }
        return null;
    }
    private static boolean callable(Context context, ActivityInfo activity) {
        return activity.exported && activity.enabled && activity.applicationInfo.enabled
            && (activity.permission == null || context.checkSelfPermission(activity.permission) == PackageManager.PERMISSION_GRANTED);
    }
    static Intent tvSettings(Context context) {
        android.content.ComponentName name = new android.content.ComponentName(
            "com.android.tv.settings", "com.android.tv.settings.MainSettings");
        try {
            if (callable(context, context.getPackageManager().getActivityInfo(name, 0)))
                return new Intent().setComponent(name);
        } catch (PackageManager.NameNotFoundException unavailable) { }
        return null;
    }
    static String open(Context context) {
        Intent intent = direct(context);
        String destination = "accessibility";
        if (intent == null) {
            intent = tvSettings(context);
            destination = "androidTvSettings";
        }
        // Full Android Settings is distinct from Kodak's restricted Droid Settings app.
        if (intent == null) {
            intent = context.getPackageManager().getLaunchIntentForPackage("com.android.settings");
            destination = "androidSettings";
        }
        if (intent == null) {
            intent = new Intent(android.provider.Settings.ACTION_SETTINGS);
            destination = "generalSettings";
        }
        if (intent.resolveActivity(context.getPackageManager()) == null)
            intent = context.getPackageManager().getLaunchIntentForPackage("com.droidlogic.tv.settings");
        if (intent == null) return "unavailable";
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            if (!"accessibility".equals(destination)) android.widget.Toast.makeText(context,
                "Select Accessibility, then LUMA Controller across apps", android.widget.Toast.LENGTH_LONG).show();
            return destination;
        }
        catch (android.content.ActivityNotFoundException | SecurityException unavailable) { return "unavailable"; }
    }
    /** Bounded, paired-client diagnostics: system settings component names only. */
    static JSONObject diagnostics(Context context) throws Exception {
        JSONArray packages = new JSONArray();
        PackageManager pm = context.getPackageManager();
        for (PackageInfo pkg : pm.getInstalledPackages(PackageManager.GET_ACTIVITIES | PackageManager.GET_META_DATA | PackageManager.MATCH_DISABLED_COMPONENTS)) {
            if (!pkg.packageName.toLowerCase(java.util.Locale.ROOT).contains("settings") || packages.length() >= 12) continue;
            JSONArray activities = new JSONArray();
            if (pkg.activities != null) for (ActivityInfo a : pkg.activities) {
                if (activities.length() >= 120) break;
                JSONObject item = new JSONObject().put("name", a.name).put("exported", a.exported)
                    .put("enabled", a.enabled).put("callable", callable(context, a));
                if (a.metaData != null) item.put("fragment", a.metaData.getString("com.android.settings.FRAGMENT_CLASS", ""));
                activities.put(item);
            }
            packages.put(new JSONObject().put("package", pkg.packageName).put("enabled", pkg.applicationInfo.enabled)
                .put("activities", activities));
        }
        return new JSONObject().put("packages", packages);
    }
}

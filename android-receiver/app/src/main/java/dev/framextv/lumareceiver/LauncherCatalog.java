package dev.framextv.lumareceiver;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Android's launchable-app inventory, shared by the Home screen and paired LAN clients. */
final class LauncherCatalog {
    static final int MAX_APPS = 200;
    private static final int ICON_PIXELS = 96;
    private static final int MAX_ICON_BYTES = 64 * 1024;
    private static final int MAX_TOTAL_ICON_BYTES = 2 * 1024 * 1024;
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+");

    static final class Entry {
        final String packageName, activityName, label;
        final boolean leanback;

        Entry(String packageName, String activityName, String label, boolean leanback) {
            this.packageName = packageName;
            this.activityName = activityName;
            this.label = label;
            this.leanback = leanback;
        }
    }

    private final Context context;
    private final PackageManager packages;

    LauncherCatalog(Context context) {
        this.context = context.getApplicationContext();
        this.packages = context.getPackageManager();
    }

    /** Fresh on every call so installs and removals appear as soon as Home resumes. */
    List<Entry> list() {
        List<Entry> candidates = new ArrayList<>();
        query(Intent.CATEGORY_LEANBACK_LAUNCHER, true, candidates);
        query(Intent.CATEGORY_LAUNCHER, false, candidates);
        return select(candidates, context.getPackageName());
    }

    private void query(String category, boolean leanback, List<Entry> out) {
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(category);
        List<ResolveInfo> resolved;
        try { resolved = packages.queryIntentActivities(intent, 0); }
        catch (RuntimeException unavailable) { return; }
        if (resolved == null) return;
        for (ResolveInfo info : resolved) {
            ActivityInfo activity = info.activityInfo;
            if (activity == null || activity.applicationInfo == null || !activity.enabled
                    || !activity.applicationInfo.enabled || !activity.exported) continue;
            CharSequence loaded;
            try { loaded = info.loadLabel(packages); }
            catch (RuntimeException failure) { loaded = null; }
            String label = safeLabel(loaded == null ? activity.packageName : loaded.toString());
            out.add(new Entry(activity.packageName, activity.name, label, leanback));
        }
    }

    /** Leanback is the TV-optimized activity when a package exposes both variants. */
    static List<Entry> select(List<Entry> candidates, String ownPackage) {
        Map<String, Entry> selected = new LinkedHashMap<>();
        for (Entry candidate : candidates) {
            if (candidate == null || !validPackage(candidate.packageName)
                    || candidate.packageName.equals(ownPackage) || candidate.activityName == null
                    || candidate.activityName.isEmpty()) continue;
            Entry clean = new Entry(candidate.packageName, candidate.activityName,
                safeLabel(candidate.label), candidate.leanback);
            Entry prior = selected.get(clean.packageName);
            if (prior == null || clean.leanback && !prior.leanback) selected.put(clean.packageName, clean);
        }
        List<Entry> result = new ArrayList<>(selected.values());
        result.sort(Comparator.comparing((Entry entry) -> entry.label.toLowerCase(Locale.ROOT))
            .thenComparing(entry -> entry.packageName));
        if (result.size() > MAX_APPS) return new ArrayList<>(result.subList(0, MAX_APPS));
        return result;
    }

    static boolean validPackage(String value) {
        return value != null && value.length() <= 255 && PACKAGE.matcher(value).matches();
    }

    static String safeLabel(String value) {
        if (value == null) return "Unnamed app";
        StringBuilder clean = new StringBuilder();
        for (int index = 0; index < value.length() && clean.length() < 100; index++) {
            char character = value.charAt(index);
            if (character >= 0x20 && character != 0x7f) clean.append(character);
        }
        String label = clean.toString().trim();
        return label.isEmpty() ? "Unnamed app" : label;
    }

    Drawable icon(Entry entry) {
        try { return packages.getActivityIcon(new ComponentName(entry.packageName, entry.activityName)); }
        catch (PackageManager.NameNotFoundException | RuntimeException missing) {
            try { return packages.getApplicationIcon(entry.packageName); }
            catch (PackageManager.NameNotFoundException | RuntimeException alsoMissing) {
                return packages.getDefaultActivityIcon();
            }
        }
    }

    void launch(String packageName) throws ApiException {
        if (!validPackage(packageName)) throw new ApiException("INVALID_INPUT");
        Entry target = null;
        for (Entry entry : list()) if (entry.packageName.equals(packageName)) { target = entry; break; }
        if (target == null) throw new ApiException("APP_NOT_AVAILABLE", 404);
        Intent launch = new Intent(Intent.ACTION_MAIN)
            .addCategory(target.leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER)
            .setComponent(new ComponentName(target.packageName, target.activityName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { context.startActivity(launch); }
        catch (ActivityNotFoundException | SecurityException failure) { throw new ApiException("APP_NOT_AVAILABLE", 404); }
        catch (RuntimeException failure) { throw new ApiException("APP_LAUNCH_FAILED", 409); }
    }

    JSONObject json() throws Exception {
        JSONArray apps = new JSONArray();
        int iconBytes = 0;
        for (Entry entry : list()) {
            JSONObject item = new JSONObject().put("packageName", entry.packageName).put("label", entry.label);
            byte[] icon = png(entry);
            if (icon != null && icon.length <= MAX_ICON_BYTES && iconBytes + icon.length <= MAX_TOTAL_ICON_BYTES) {
                iconBytes += icon.length;
                item.put("icon", "data:image/png;base64," + Base64.encodeToString(icon, Base64.NO_WRAP));
            }
            apps.put(item);
        }
        return new JSONObject().put("apps", apps);
    }

    private byte[] png(Entry entry) {
        Bitmap bitmap = null;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            Drawable drawable = icon(entry);
            bitmap = Bitmap.createBitmap(ICON_PIXELS, ICON_PIXELS, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            int inset = 6;
            int width = drawable.getIntrinsicWidth(), height = drawable.getIntrinsicHeight();
            if (width > 0 && height > 0) {
                float scale = Math.min((ICON_PIXELS - inset * 2f) / width, (ICON_PIXELS - inset * 2f) / height);
                int drawnWidth = Math.max(1, Math.round(width * scale)), drawnHeight = Math.max(1, Math.round(height * scale));
                int left = (ICON_PIXELS - drawnWidth) / 2, top = (ICON_PIXELS - drawnHeight) / 2;
                drawable.setBounds(left, top, left + drawnWidth, top + drawnHeight);
            } else drawable.setBounds(inset, inset, ICON_PIXELS - inset, ICON_PIXELS - inset);
            drawable.draw(canvas);
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) return null;
            return output.toByteArray();
        } catch (RuntimeException failure) { return null; }
        finally { if (bitmap != null) bitmap.recycle(); }
    }
}

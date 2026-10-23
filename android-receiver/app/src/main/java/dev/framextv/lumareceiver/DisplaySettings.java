package dev.framextv.lumareceiver;

import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * Persisted picture and subtitle preferences. Brightness is composited above
 * the video as a light/dark tint so playback always keeps the projector's
 * working direct hardware surface. Media3 GL effects had no visible result and
 * moving video to a colour-filtered TextureView produced a black frame.
 */
final class DisplaySettings {
    static final float MIN_SUBTITLE = 0.5f, MAX_SUBTITLE = 2.5f, SUBTITLE_STEP = 0.1f;
    static final float MIN_BRIGHTNESS = -0.3f, MAX_BRIGHTNESS = 0.3f;
    private final SharedPreferences preferences;

    DisplaySettings(SharedPreferences preferences) { this.preferences = preferences; }

    float brightness() { return Math.max(MIN_BRIGHTNESS, Math.min(MAX_BRIGHTNESS, preferences.getFloat("brightness", 0f))); }
    float contrast() { return preferences.getFloat("contrast", 0f); }
    float gamma() { return preferences.getFloat("gamma", 1f); }
    float subtitleScale() { return preferences.getFloat("subtitleScale", 1f); }
    boolean subtitleBox() { return preferences.getBoolean("subtitleBox", false); }

    JSONObject toJson() throws Exception {
        return new JSONObject().put("brightness", brightness()).put("contrast", contrast()).put("gamma", gamma())
            .put("subtitleScale", subtitleScale()).put("subtitleStyle", subtitleBox() ? "box" : "outline");
    }

    /** Applies only the fields present; validates all before writing any. */
    void update(JSONObject params) throws ApiException {
        SharedPreferences.Editor edit = preferences.edit();
        if (params.has("brightness")) edit.putFloat("brightness", range(params.opt("brightness"), MIN_BRIGHTNESS, MAX_BRIGHTNESS));
        if (params.has("contrast")) edit.putFloat("contrast", range(params.opt("contrast"), -0.6f, 0.6f));
        if (params.has("gamma")) edit.putFloat("gamma", range(params.opt("gamma"), 0.4f, 1.6f));
        if (params.has("subtitleScale")) edit.putFloat("subtitleScale", range(params.opt("subtitleScale"), MIN_SUBTITLE, MAX_SUBTITLE));
        if (params.has("subtitleStyle")) {
            Object style = params.opt("subtitleStyle");
            if (!"box".equals(style) && !"outline".equals(style)) throw new ApiException("INVALID_INPUT");
            edit.putBoolean("subtitleBox", "box".equals(style));
        }
        if (params.optBoolean("reset")) edit.remove("brightness").remove("contrast").remove("gamma").remove("subtitleScale").remove("subtitleBox");
        edit.apply();
    }

    float stepSubtitle(int direction) {
        float next = Math.round((subtitleScale() + direction * SUBTITLE_STEP) * 10) / 10f;
        next = Math.max(MIN_SUBTITLE, Math.min(MAX_SUBTITLE, next));
        preferences.edit().putFloat("subtitleScale", next).apply();
        return next;
    }

    private static float range(Object value, float min, float max) throws ApiException {
        if (!(value instanceof Number)) throw new ApiException("INVALID_INPUT");
        float number = ((Number) value).floatValue();
        if (Float.isNaN(number) || number < min || number > max) throw new ApiException("INVALID_INPUT");
        return number;
    }
}

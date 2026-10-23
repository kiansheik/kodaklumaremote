package dev.framextv.lumareceiver;

import android.content.SharedPreferences;
import android.graphics.Color;

import androidx.media3.common.Effect;
import androidx.media3.effect.Brightness;
import androidx.media3.effect.Contrast;
import androidx.media3.effect.SingleColorLut;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persisted picture and subtitle preferences. Picture adjustments run through
 * Media3's GL effects pipeline, which costs GPU time on this projector, so the
 * effect list stays empty (direct hardware surface) until something changes.
 */
final class DisplaySettings {
    static final float MIN_SUBTITLE = 0.5f, MAX_SUBTITLE = 2.5f, SUBTITLE_STEP = 0.1f;
    private static final int LUT_SIZE = 17;
    private final SharedPreferences preferences;

    DisplaySettings(SharedPreferences preferences) { this.preferences = preferences; }

    float brightness() { return preferences.getFloat("brightness", 0f); }
    float contrast() { return preferences.getFloat("contrast", 0f); }
    float gamma() { return preferences.getFloat("gamma", 1f); }
    float subtitleScale() { return preferences.getFloat("subtitleScale", 1f); }
    boolean subtitleBox() { return preferences.getBoolean("subtitleBox", false); }

    boolean pictureAdjusted() { return brightness() != 0f || contrast() != 0f || gamma() != 1f; }

    JSONObject toJson() throws Exception {
        return new JSONObject().put("brightness", brightness()).put("contrast", contrast()).put("gamma", gamma())
            .put("subtitleScale", subtitleScale()).put("subtitleStyle", subtitleBox() ? "box" : "outline");
    }

    /** Applies only the fields present; validates all before writing any. */
    void update(JSONObject params) throws ApiException {
        SharedPreferences.Editor edit = preferences.edit();
        if (params.has("brightness")) edit.putFloat("brightness", range(params.opt("brightness"), -0.6f, 0.6f));
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

    List<Effect> effects() {
        if (!pictureAdjusted()) return Collections.emptyList();
        List<Effect> effects = new ArrayList<>();
        if (gamma() != 1f) effects.add(gammaLut(gamma()));
        if (brightness() != 0f) effects.add(new Brightness(brightness()));
        if (contrast() != 0f) effects.add(new Contrast(contrast()));
        return effects;
    }

    /** Per-channel power curve; gamma below 1 lifts shadows without greying blacks. */
    private static SingleColorLut gammaLut(float gamma) {
        int[] curve = new int[LUT_SIZE];
        for (int i = 0; i < LUT_SIZE; i++) curve[i] = Math.round((float) Math.pow(i / (LUT_SIZE - 1f), gamma) * 255f);
        int[][][] cube = new int[LUT_SIZE][LUT_SIZE][LUT_SIZE]; // [red][green][blue]
        for (int r = 0; r < LUT_SIZE; r++)
            for (int g = 0; g < LUT_SIZE; g++)
                for (int b = 0; b < LUT_SIZE; b++) cube[r][g][b] = Color.rgb(curve[r], curve[g], curve[b]);
        return SingleColorLut.createFromCube(cube);
    }

    private static float range(Object value, float min, float max) throws ApiException {
        if (!(value instanceof Number)) throw new ApiException("INVALID_INPUT");
        float number = ((Number) value).floatValue();
        if (Float.isNaN(number) || number < min || number > max) throw new ApiException("INVALID_INPUT");
        return number;
    }
}

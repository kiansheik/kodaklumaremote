package dev.framextv.lumareceiver;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;

/**
 * House caption style: outlined white text, wrapped inside the centre half of
 * the screen, on a rounded and nearly transparent panel. Replaces Media3's
 * SubtitleView, which only offers rectangular per-line backgrounds.
 */
public final class SubtitleOverlay extends View {
    static final float WIDTH_FRACTION = 0.5f, BOTTOM_FRACTION = 0.07f, TEXT_FRACTION = 0.0533f;
    private final TextPaint fill = new TextPaint(Paint.ANTI_ALIAS_FLAG), stroke = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private CharSequence text = "";
    private float scale = 1f;
    private boolean box;
    private StaticLayout fillLayout, strokeLayout;

    public SubtitleOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
        fill.setColor(Color.WHITE);
        stroke.setColor(Color.BLACK);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
    }

    void setText(CharSequence value) {
        CharSequence next = value == null ? "" : value;
        if (next.toString().equals(text.toString())) return;
        text = next; fillLayout = strokeLayout = null; invalidate();
    }

    void setStyle(float scale, boolean box) {
        this.scale = scale; this.box = box; fillLayout = strokeLayout = null; invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { fillLayout = strokeLayout = null; }

    @Override protected void onDraw(Canvas canvas) {
        if (text.length() == 0 || getWidth() == 0) return;
        if (fillLayout == null) {
            float size = getHeight() * TEXT_FRACTION * scale;
            fill.setTextSize(size); stroke.setTextSize(size);
            stroke.setStrokeWidth(Math.max(2f, size * 0.12f));
            int width = (int) (getWidth() * WIDTH_FRACTION);
            fillLayout = layout(fill, width); strokeLayout = layout(stroke, width);
        }
        float pad = fill.getTextSize() * 0.45f, radius = fill.getTextSize() * 0.6f;
        float textWidth = 0;
        for (int i = 0; i < fillLayout.getLineCount(); i++) textWidth = Math.max(textWidth, fillLayout.getLineWidth(i));
        float left = (getWidth() - fillLayout.getWidth()) / 2f;
        float top = getHeight() * (1 - BOTTOM_FRACTION) - fillLayout.getHeight();
        float centre = getWidth() / 2f;
        bounds.set(centre - textWidth / 2f - pad, top - pad * 0.6f, centre + textWidth / 2f + pad, top + fillLayout.getHeight() + pad * 0.6f);
        panel.setColor(box ? 0xB0000000 : 0x2E000000);
        canvas.drawRoundRect(bounds, radius, radius, panel);
        canvas.save();
        canvas.translate(left, top);
        if (!box) strokeLayout.draw(canvas);
        fillLayout.draw(canvas);
        canvas.restore();
    }

    @SuppressWarnings("deprecation")
    private StaticLayout layout(TextPaint paint, int width) {
        return new StaticLayout(text, paint, width, Layout.Alignment.ALIGN_CENTER, 1.08f, 0f, false);
    }
}

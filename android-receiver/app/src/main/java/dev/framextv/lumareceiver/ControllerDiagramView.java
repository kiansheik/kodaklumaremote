package dev.framextv.lumareceiver;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Resolution-independent Pro Controller diagram. No artwork or controller SDK required. */
final class ControllerDiagramView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ControllerProfile.State state;
    private ControllerProfile profile;
    private String target = "";
    private boolean calibrated;
    private static final int INK = Color.rgb(232, 246, 242), MUTED = Color.rgb(153, 183, 178);
    private static final int ACTIVE = Color.rgb(104, 230, 180), TARGET = Color.rgb(255, 217, 114);
    ControllerDiagramView(Context context) { super(context); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
    void update(ControllerProfile profile, ControllerProfile.State state, String target, boolean calibrated) {
        this.profile = profile; this.state = state; this.target = target == null ? "" : target;
        this.calibrated = calibrated; invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = Math.min(getWidth() / 760f, getHeight() / 470f);
        canvas.save(); canvas.translate((getWidth() - 760 * scale) / 2, (getHeight() - 470 * scale) / 2); canvas.scale(scale, scale);
        Path body = new Path();
        body.moveTo(161, 99); body.cubicTo(106, 101, 90, 145, 73, 217);
        body.lineTo(42, 359); body.cubicTo(23, 437, 95, 452, 139, 399);
        body.lineTo(208, 318); body.cubicTo(304, 336, 455, 336, 552, 318);
        body.lineTo(621, 399); body.cubicTo(665, 452, 737, 437, 718, 359);
        body.lineTo(687, 217); body.cubicTo(670, 145, 654, 101, 599, 99); body.close();
        paint.setStyle(Paint.Style.FILL); paint.setColor(Color.rgb(28, 61, 57)); canvas.drawPath(body, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(3); paint.setColor(Color.rgb(76, 111, 103)); canvas.drawPath(body, paint); paint.setStyle(Paint.Style.FILL);
        shoulder(canvas, ControllerProfile.Control.ZL, "ZL", 118, 20, 135, 33);
        shoulder(canvas, ControllerProfile.Control.ZR, "ZR", 507, 20, 135, 33);
        shoulder(canvas, ControllerProfile.Control.L, "L", 118, 62, 135, 31);
        shoulder(canvas, ControllerProfile.Control.R, "R", 507, 62, 135, 31);
        stick(canvas, true, 205, 173);
        stick(canvas, false, 477, 284);
        button(canvas, ControllerProfile.Control.X, "X", 586, 135, 27);
        button(canvas, ControllerProfile.Control.Y, "Y", 540, 181, 27);
        button(canvas, ControllerProfile.Control.A, "A", 632, 181, 27);
        button(canvas, ControllerProfile.Control.B, "B", 586, 227, 27);
        button(canvas, ControllerProfile.Control.MINUS, "−", 318, 132, 20);
        button(canvas, ControllerProfile.Control.PLUS, "+", 441, 132, 20);
        button(canvas, ControllerProfile.Control.CAPTURE, "□", 323, 214, 19);
        button(canvas, ControllerProfile.Control.HOME, "⌂", 430, 214, 19);
        dpad(canvas, ControllerProfile.Control.UP, "↑", 263, 264);
        dpad(canvas, ControllerProfile.Control.DOWN, "↓", 263, 330);
        dpad(canvas, ControllerProfile.Control.LEFT, "←", 230, 297);
        dpad(canvas, ControllerProfile.Control.RIGHT, "→", 296, 297);
        label(canvas, "CAPTURE", 323, 249, 11, MUTED); label(canvas, "HOME", 430, 249, 11, MUTED);
        label(canvas, "D-PAD", 263, 367, 13, MUTED);
        label(canvas, "LEFT STICK / CLICK", 205, 246, 13, MUTED);
        label(canvas, "RIGHT STICK / CLICK", 477, 363, 13, MUTED);
        dot(canvas, 226, 450, ACTIVE); labelLeft(canvas, "Held", 240, 455, 14, INK);
        dot(canvas, 334, 450, TARGET); labelLeft(canvas, "Calibrate", 348, 455, 14, INK);
        dot(canvas, 490, 450, Color.rgb(74, 100, 94)); labelLeft(canvas, "Unmapped", 504, 455, 14, MUTED);
        canvas.restore();
    }
    private void stick(Canvas c, boolean left, float x, float y) {
        ControllerProfile.StickAxis ax = left ? ControllerProfile.StickAxis.LEFT_X : ControllerProfile.StickAxis.RIGHT_X;
        ControllerProfile.StickAxis ay = left ? ControllerProfile.StickAxis.LEFT_Y : ControllerProfile.StickAxis.RIGHT_Y;
        ControllerProfile.Control click = left ? ControllerProfile.Control.LCLICK : ControllerProfile.Control.RCLICK;
        boolean requested = target.equals(ax.name()) || target.equals(ay.name()) || target.equals(click.name());
        float vx = state == null ? 0 : state.axis(ax), vy = state == null ? 0 : state.axis(ay);
        paint.setColor(Color.rgb(11, 35, 32)); c.drawCircle(x, y, 53, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(requested ? 5 : 2); paint.setColor(requested ? TARGET : Color.rgb(77, 112, 104)); c.drawCircle(x, y, 53, paint);
        paint.setStrokeWidth(1); paint.setColor(Color.rgb(53, 86, 79)); c.drawLine(x - 43, y, x + 43, y, paint); c.drawLine(x, y - 43, x, y + 43, paint); paint.setStyle(Paint.Style.FILL);
        boolean active = state != null && state.pressed(click);
        paint.setColor(active ? ACTIVE : Color.rgb(65, 103, 92)); c.drawCircle(x + vx * 24, y + vy * 24, 29, paint);
        label(c, active ? "●" : left ? "L" : "R", x + vx * 24, y + vy * 24 + 7, 19, active ? Color.rgb(12, 46, 35) : INK);
        if (requested && !target.equals(click.name())) label(c, target.endsWith("_X") ? "→" : "↓", x + 68, y + 8, 28, TARGET);
    }
    private void button(Canvas c, ControllerProfile.Control control, String name, float x, float y, float radius) {
        boolean active = state != null && state.pressed(control), requested = target.equals(control.name());
        paint.setColor(active ? ACTIVE : Color.rgb(13, 36, 33)); c.drawCircle(x, y, radius, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(requested ? 4 : 2); paint.setColor(requested ? TARGET : known(control) ? Color.rgb(121, 157, 147) : Color.rgb(74, 100, 94)); c.drawCircle(x, y, radius, paint); paint.setStyle(Paint.Style.FILL);
        label(c, name, x, y + 8, radius > 22 ? 24 : 21, active ? Color.rgb(12, 46, 35) : known(control) ? INK : MUTED);
    }
    private void shoulder(Canvas c, ControllerProfile.Control control, String name, float x, float y, float w, float h) {
        boolean active = state != null && state.pressed(control), requested = target.equals(control.name());
        paint.setColor(active ? ACTIVE : Color.rgb(25, 57, 50)); c.drawRoundRect(new RectF(x, y, x + w, y + h), 10, 10, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(requested ? 4 : 2); paint.setColor(requested ? TARGET : known(control) ? Color.rgb(121, 157, 147) : Color.rgb(74, 100, 94)); c.drawRoundRect(new RectF(x, y, x + w, y + h), 10, 10, paint); paint.setStyle(Paint.Style.FILL);
        label(c, name, x + w / 2, y + h / 2 + 7, 21, active ? Color.rgb(12, 46, 35) : INK);
    }
    private void dpad(Canvas c, ControllerProfile.Control control, String name, float x, float y) {
        boolean active = state != null && state.pressed(control), requested = target.equals(control.name());
        paint.setColor(active ? ACTIVE : Color.rgb(13, 36, 33)); c.drawRoundRect(new RectF(x - 18, y - 18, x + 18, y + 18), 5, 5, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(requested ? 4 : 1); paint.setColor(requested ? TARGET : known(control) ? Color.rgb(121, 157, 147) : Color.rgb(74, 100, 94)); c.drawRoundRect(new RectF(x - 18, y - 18, x + 18, y + 18), 5, 5, paint); paint.setStyle(Paint.Style.FILL);
        label(c, name, x, y + 8, 24, active ? Color.rgb(12, 46, 35) : INK);
    }
    private boolean known(ControllerProfile.Control control) { return profile != null && profile.buttons.containsKey(control) && !profile.buttons.get(control).isEmpty(); }
    private void dot(Canvas c, float x, float y, int color) { paint.setColor(color); c.drawCircle(x, y, 5, paint); }
    private void label(Canvas c, String text, float x, float y, float size, int color) { paint.setTextAlign(Paint.Align.CENTER); drawText(c, text, x, y, size, color); }
    private void labelLeft(Canvas c, String text, float x, float y, float size, int color) { paint.setTextAlign(Paint.Align.LEFT); drawText(c, text, x, y, size, color); }
    private void drawText(Canvas c, String text, float x, float y, float size, int color) { paint.setColor(color); paint.setTextSize(size); paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0)); c.drawText(text, x, y, paint); }
}

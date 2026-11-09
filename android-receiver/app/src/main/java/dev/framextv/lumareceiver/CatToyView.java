package dev.framextv.lumareceiver;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayDeque;
import java.util.Random;

/** Full-screen, high-contrast cat toy driven by autonomous prey motion or the phone pointer. */
public final class CatToyView extends View {
    private static final int BACKGROUND = Color.rgb(4, 11, 22);
    private static final int CYAN = Color.rgb(53, 217, 255);
    private static final int YELLOW_GREEN = Color.rgb(216, 255, 69);
    private static final int TRAIL_LIMIT = 10;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final ArrayDeque<PointF> trail = new ArrayDeque<>();
    private final Random soundSchedule = new Random(0xCA7F00D);
    private final CatSoundPlayer sounds = new CatSoundPlayer();
    private CatModeState state;
    private CatMotion motion;
    private boolean running, wasActive;
    private long nextSoundAt, lastTrailAt;
    private double renderedX = .5, renderedY = .5;
    private double lastTrailX = -1, lastTrailY = -1;

    public CatToyView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setBackgroundColor(BACKGROUND);
        setFocusable(true);
        setContentDescription("Cat Mode. Press Back to exit.");
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
    }

    void attach(CatModeState value) { state = value; }

    void resumeAnimation() {
        running = true;
        postInvalidateOnAnimation();
    }

    void pauseAnimation() {
        running = false;
        if (state != null) state.stopController();
        nextSoundAt = 0;
        sounds.stop();
    }

    /** A stopped session must make the next start a fresh visual/audio cue. */
    void endSession() {
        pauseAnimation();
        wasActive = false;
        motion = null;
        trail.clear();
    }

    void close() {
        running = false;
        sounds.close();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = SystemClock.elapsedRealtime();
        if (state != null) state.controllerFrame(now, renderedX, renderedY, (double)getWidth() / Math.max(1, getHeight()));
        CatModeState.Snapshot session = state == null ? null : state.snapshot(now);
        if (session == null || !session.active) {
            endSession();
            return;
        }
        if (!wasActive || motion == null) resetSession(now, session.sound);
        wasActive = true;

        CatMotion.Frame frame = motion.update(now, session.manual, session.x, session.y, session.pounceId);
        renderedX = frame.x; renderedY = frame.y;
        if (frame.visible) remember(frame, now);
        drawTrail(canvas, frame.style);
        if (frame.visible) drawTarget(canvas, frame, now);
        drawSessionHint(canvas, session);
        scheduleSound(session, frame, now);
        if (running) postInvalidateOnAnimation();
    }

    private void resetSession(long now, boolean sound) {
        motion = new CatMotion(now ^ 0xCA7100L);
        trail.clear();
        lastTrailX = lastTrailY = -1;
        lastTrailAt = 0;
        if (sound) sounds.playAttention();
        nextSoundAt = sound ? now + 4_000 + soundSchedule.nextInt(6_001) : 0;
    }

    private void remember(CatMotion.Frame frame, long now) {
        double distance = Math.hypot(frame.x - lastTrailX, frame.y - lastTrailY);
        if (lastTrailX >= 0 && distance < 0.006 && now - lastTrailAt < 55) return;
        trail.addLast(new PointF((float) frame.x, (float) frame.y));
        while (trail.size() > TRAIL_LIMIT) trail.removeFirst();
        lastTrailX = frame.x;
        lastTrailY = frame.y;
        lastTrailAt = now;
    }

    private void drawTrail(Canvas canvas, int style) {
        if (trail.isEmpty()) return;
        float radius = Math.min(getWidth(), getHeight()) * 0.010f;
        int index = 0, size = trail.size();
        for (PointF point : trail) {
            float progress = (index + 1f) / size;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(style == 1 ? YELLOW_GREEN : CYAN);
            paint.setAlpha(Math.round(12 + progress * 58));
            canvas.drawCircle(point.x * getWidth(), point.y * getHeight(), radius * progress, paint);
            index++;
        }
        paint.setAlpha(255);
    }

    private void drawTarget(Canvas canvas, CatMotion.Frame frame, long now) {
        float x = (float) frame.x * getWidth(), y = (float) frame.y * getHeight();
        float size = Math.max(dp(18), Math.min(getWidth(), getHeight()) * 0.047f);
        float pulse = frame.caught ? 0.78f : 0.94f + 0.06f * (float) Math.sin(now / 115.0);
        size *= pulse;
        paint.setAlpha(255);
        stroke.setAlpha(255);
        switch (frame.style) {
            case 1: drawMouse(canvas, x, y, size); break;
            case 2: drawBug(canvas, x, y, size); break;
            default: drawMoth(canvas, x, y, size); break;
        }
        if (frame.caught) {
            float expansion = (float) ((now % 850L) / 850.0);
            stroke.setColor(YELLOW_GREEN);
            stroke.setAlpha(Math.round(180 * (1 - expansion)));
            stroke.setStrokeWidth(Math.max(dp(2), size * 0.08f));
            canvas.drawCircle(x, y, size * (0.6f + expansion * 1.5f), stroke);
            stroke.setAlpha(255);
        }
    }

    private void drawMoth(Canvas canvas, float x, float y, float size) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(CYAN);
        canvas.save();
        canvas.rotate(-18, x, y);
        canvas.drawOval(x - size * 0.72f, y - size * 0.28f, x - size * 0.04f, y + size * 0.28f, paint);
        canvas.drawOval(x + size * 0.04f, y - size * 0.28f, x + size * 0.72f, y + size * 0.28f, paint);
        paint.setColor(YELLOW_GREEN);
        canvas.drawOval(x - size * 0.12f, y - size * 0.43f, x + size * 0.12f, y + size * 0.43f, paint);
        canvas.restore();
    }

    private void drawMouse(Canvas canvas, float x, float y, float size) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(YELLOW_GREEN);
        canvas.drawOval(x - size * 0.52f, y - size * 0.32f, x + size * 0.45f, y + size * 0.32f, paint);
        canvas.drawCircle(x + size * 0.42f, y - size * 0.06f, size * 0.26f, paint);
        paint.setColor(CYAN);
        canvas.drawCircle(x + size * 0.50f, y - size * 0.12f, size * 0.055f, paint);
        stroke.setColor(YELLOW_GREEN);
        stroke.setStrokeWidth(Math.max(dp(2), size * 0.10f));
        path.reset();
        path.moveTo(x - size * 0.48f, y + size * 0.03f);
        path.cubicTo(x - size * 0.92f, y - size * 0.05f, x - size * 0.84f, y - size * 0.56f,
            x - size * 1.08f, y - size * 0.48f);
        canvas.drawPath(path, stroke);
    }

    private void drawBug(Canvas canvas, float x, float y, float size) {
        stroke.setColor(YELLOW_GREEN);
        stroke.setStrokeWidth(Math.max(dp(2), size * 0.075f));
        for (int side = -1; side <= 1; side += 2) {
            for (int row = -1; row <= 1; row++) {
                float offset = row * size * 0.20f;
                canvas.drawLine(x + side * size * 0.20f, y + offset,
                    x + side * size * 0.62f, y + offset + side * row * size * 0.10f, stroke);
            }
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(CYAN);
        canvas.drawOval(x - size * 0.30f, y - size * 0.48f, x + size * 0.30f, y + size * 0.48f, paint);
        paint.setColor(YELLOW_GREEN);
        canvas.drawCircle(x, y - size * 0.35f, size * 0.16f, paint);
    }

    private void drawSessionHint(Canvas canvas, CatModeState.Snapshot session) {
        long seconds = (session.remainingMs + 999) / 1000;
        String time = String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        paint.setAlpha(92);
        paint.setTextSize(dp(14));
        canvas.drawText("CAT MODE  ·  " + (session.controller ? "CONTROLLER" : session.manual ? "AIR MOUSE" : "AUTO") + "  ·  " + time
            + "  ·  BACK TO EXIT", dp(18), dp(28), paint);
        paint.setAlpha(255);
    }

    private void scheduleSound(CatModeState.Snapshot session, CatMotion.Frame frame, long now) {
        if (!session.sound) {
            nextSoundAt = 0;
            return;
        }
        if (nextSoundAt == 0) nextSoundAt = now + 4_000 + soundSchedule.nextInt(6_001);
        if (now < nextSoundAt) return;
        sounds.play(frame.style);
        nextSoundAt = now + 4_000 + soundSchedule.nextInt(6_001);
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
}

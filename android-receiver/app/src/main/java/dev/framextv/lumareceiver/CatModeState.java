package dev.framextv.lumareceiver;

import org.json.JSONObject;

/** Process-wide Cat Mode session and its short-lived manual pointer lease. */
final class CatModeState {
    static final long SESSION_MS = 12 * 60 * 1000L;
    static final long MANUAL_LEASE_MS = 1_500L;

    static final class Snapshot {
        final boolean active, manual, sound;
        boolean controller;
        final double x, y;
        final long remainingMs, pounceId;

        Snapshot(boolean active, boolean manual, boolean sound, double x, double y,
                long remainingMs, long pounceId) {
            this.active = active;
            this.manual = manual;
            this.sound = sound;
            this.x = x;
            this.y = y;
            this.remainingMs = remainingMs;
            this.pounceId = pounceId;
        }
    }

    private final CatControllerMotion controller = new CatControllerMotion();
    private boolean active, manual, sound = true;
    private double x = 0.5, y = 0.5;
    private long endsAt, lastPointerAt, pounceId;
    private Runnable listener;

    void setListener(Runnable value) {
        synchronized (this) { listener = value; }
    }

    void start(boolean withSound, long now) {
        Runnable changed;
        synchronized (this) {
            active = true;
            controller.reset();
            manual = false;
            sound = withSound;
            endsAt = now + SESSION_MS;
            lastPointerAt = 0;
            changed = listener;
        }
        notifyListener(changed);
    }

    boolean stop() {
        Runnable changed;
        synchronized (this) {
            if (!active) return false;
            active = false;
            controller.reset();
            manual = false;
            changed = listener;
        }
        notifyListener(changed);
        return true;
    }

    boolean pointer(double nextX, double nextY, long now) {
        Runnable changed = null;
        boolean accepted;
        synchronized (this) {
            if (expireLocked(now)) changed = listener;
            accepted = active;
            if (accepted) {
                controller.reset();
                x = clamp(nextX);
                y = clamp(nextY);
                manual = true;
                lastPointerAt = now;
            }
        }
        notifyListener(changed);
        return accepted;
    }

    boolean release(long now) {
        Runnable changed = null;
        boolean accepted;
        synchronized (this) {
            if (expireLocked(now)) changed = listener;
            accepted = active;
            if (accepted && manual) {
                controller.reset();
                manual = false;
                lastPointerAt = 0;
            }
        }
        notifyListener(changed);
        return accepted;
    }

    boolean pounce(long now) {
        return pounce(now, true);
    }

    boolean pounce(long now, boolean renewManualLease) {
        Runnable changed = null;
        boolean accepted;
        synchronized (this) {
            if (expireLocked(now)) changed = listener;
            accepted = active;
            if (accepted) {
                pounceId++;
                if (renewManualLease) {
                    manual = true;
                    lastPointerAt = now;
                }
            }
        }
        notifyListener(changed);
        return accepted;
    }

    synchronized void controllerInput(double horizontal, double vertical) {
        if (active) controller.input(horizontal, vertical);
    }
    synchronized void stopController() { controller.stop(); }
    synchronized void controllerFrame(long now, double renderedX, double renderedY, double aspect) {
        if (!active) return;
        controller.advance(now, renderedX, renderedY, aspect);
        if (controller.active) { x = controller.x; y = controller.y; manual = true; }
    }

    Snapshot snapshot(long now) {
        Runnable changed = null;
        Snapshot result;
        synchronized (this) {
            if (expireLocked(now)) changed = listener;
            if (manual && !controller.active && now - lastPointerAt > MANUAL_LEASE_MS) manual = false;
            result = new Snapshot(active, active && manual, sound, x, y,
                active ? Math.max(0, endsAt - now) : 0, pounceId);
            result.controller = active && controller.active;
        }
        notifyListener(changed);
        return result;
    }

    JSONObject json(long now) throws Exception {
        return summary(now);
    }

    JSONObject summary(long now) throws Exception {
        Snapshot value = snapshot(now);
        return new JSONObject()
            .put("active", value.active)
            .put("manual", value.manual).put("controller", value.controller)
            .put("sound", value.sound)
            .put("remainingSeconds", (value.remainingMs + 999) / 1000);
    }

    private boolean expireLocked(long now) {
        if (!active || now < endsAt) return false;
        active = false;
        manual = false;
        return true;
    }

    private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }

    private static void notifyListener(Runnable target) {
        if (target != null) target.run();
    }
}

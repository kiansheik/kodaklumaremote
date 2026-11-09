package dev.framextv.lumareceiver;

import java.util.Random;

/** Deterministic normalized prey motion, independent of Android drawing APIs. */
final class CatMotion {
    enum Phase { DART, QUIVER, HIDE, REST }

    static final class Frame {
        final double x, y;
        final boolean visible, caught;
        final int style;
        final Phase phase;

        Frame(double x, double y, boolean visible, boolean caught, int style, Phase phase) {
            this.x = x;
            this.y = y;
            this.visible = visible;
            this.caught = caught;
            this.style = style;
            this.phase = phase;
        }
    }

    private static final double MARGIN = 0.035;
    private final Random random;
    private boolean initialized, wasManual, pounceInitialized, edgeTarget;
    private long phaseStart, phaseEnd, caughtUntil, observedPounce;
    private double x = 0.5, y = 0.5, fromX = x, fromY = y, targetX = x, targetY = y, arc;
    private Phase phase = Phase.REST;

    CatMotion(long seed) { random = new Random(seed); }

    Frame update(long now, boolean manual, double pointerX, double pointerY, long pounceId) {
        if (!pounceInitialized) {
            observedPounce = pounceId;
            pounceInitialized = true;
        } else if (pounceId != observedPounce) {
            observedPounce = pounceId;
            if (manual) { x = clamp(pointerX); y = clamp(pointerY); }
            caughtUntil = now + 850;
        }

        if (!initialized) {
            initialized = true;
            beginDart(now);
        }

        if (manual) {
            x = clamp(pointerX);
            y = clamp(pointerY);
            wasManual = true;
            return frame(now, true, Phase.QUIVER);
        }

        if (wasManual) {
            wasManual = false;
            beginDart(now);
        }

        if (now < caughtUntil) return frame(now, true, Phase.REST);
        advance(now);
        boolean visible = phase != Phase.HIDE;
        if (phase == Phase.DART) {
            double progress = unit(now, phaseStart, phaseEnd);
            double eased = progress * progress * (3 - 2 * progress);
            double dx = targetX - fromX, dy = targetY - fromY;
            double length = Math.max(0.001, Math.hypot(dx, dy));
            double bend = Math.sin(Math.PI * progress) * arc;
            x = clamp(fromX + dx * eased - dy / length * bend);
            y = clamp(fromY + dy * eased + dx / length * bend);
        } else if (phase == Phase.QUIVER) {
            double seconds = (now - phaseStart) / 1000.0;
            x = clamp(targetX + Math.sin(seconds * 31) * 0.004);
            y = clamp(targetY + Math.cos(seconds * 37) * 0.003);
        } else {
            x = targetX;
            y = targetY;
        }
        return frame(now, visible, phase);
    }

    private Frame frame(long now, boolean visible, Phase shownPhase) {
        return new Frame(x, y, visible, now < caughtUntil, (int) ((now / 35_000L) % 3), shownPhase);
    }

    private void advance(long now) {
        int guard = 0;
        while (now >= phaseEnd && guard++ < 8) {
            x = targetX;
            y = targetY;
            switch (phase) {
                case DART:
                    if (edgeTarget) beginPhase(Phase.HIDE, phaseEnd, 450 + random.nextInt(1_050));
                    else if (random.nextDouble() < 0.68) beginPhase(Phase.QUIVER, phaseEnd, 220 + random.nextInt(850));
                    else beginPhase(Phase.REST, phaseEnd, 350 + random.nextInt(1_000));
                    break;
                case QUIVER:
                case HIDE:
                case REST:
                    beginDart(phaseEnd);
                    break;
            }
        }
        // A long Activity pause can skip hundreds of phases. Rebase once
        // instead of consuming eight stale transitions on every visible frame.
        if (now >= phaseEnd) beginDart(now);
    }

    private void beginDart(long now) {
        fromX = x;
        fromY = y;
        edgeTarget = random.nextDouble() < 0.24;
        if (edgeTarget) {
            int edge = random.nextInt(4);
            targetX = edge == 0 ? MARGIN : edge == 1 ? 1 - MARGIN : between(0.08, 0.92);
            targetY = edge == 2 ? MARGIN : edge == 3 ? 1 - MARGIN : between(0.08, 0.92);
        } else {
            do {
                targetX = between(0.08, 0.92);
                targetY = between(0.08, 0.92);
            } while (Math.hypot(targetX - fromX, targetY - fromY) < 0.18);
        }
        arc = between(-0.105, 0.105);
        beginPhase(Phase.DART, now, 480 + random.nextInt(850));
    }

    private void beginPhase(Phase next, long start, long duration) {
        phase = next;
        phaseStart = start;
        phaseEnd = start + duration;
    }

    private double between(double low, double high) { return low + random.nextDouble() * (high - low); }

    private static double unit(long value, long start, long end) {
        if (end <= start) return 1;
        return Math.max(0, Math.min(1, (value - start) / (double) (end - start)));
    }

    private static double clamp(double value) { return Math.max(MARGIN, Math.min(1 - MARGIN, value)); }
}

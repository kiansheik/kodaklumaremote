package dev.framextv.lumareceiver;

/** Frame-driven stick motion. Neutral holds position until another input takes over. */
final class CatControllerMotion {
    boolean active;
    double x, y, vx, vy;
    private long lastFrame = -1;
    void input(double horizontal, double vertical) {
        vx = Double.isFinite(horizontal) ? horizontal : 0;
        vy = Double.isFinite(vertical) ? vertical : 0;
        double length = Math.hypot(vx, vy);
        if (length > 1) { vx /= length; vy /= length; }
    }
    void advance(long now, double renderedX, double renderedY, double aspect) {
        if (!active && (vx != 0 || vy != 0)) {
            active = true; x = renderedX; y = renderedY; lastFrame = now;
        }
        if (!active) return;
        double seconds = lastFrame < 0 ? 0 : Math.max(0, Math.min(50, now - lastFrame)) / 1000.0;
        lastFrame = now;
        x = clamp(x + vx * .8 * seconds / Math.max(1, aspect));
        y = clamp(y + vy * .8 * seconds);
    }
    void stop() { vx = vy = 0; lastFrame = -1; }
    void reset() { stop(); active = false; }
    private static double clamp(double value) { return Math.max(.035, Math.min(.965, value)); }
}

package dev.framextv.lumaremote;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure motion math for the opt-in air mouse. Android sensor lifecycle and EShare
 * transport deliberately live outside this class so calibration and filtering
 * stay deterministic and host-JVM testable.
 *
 * <p>Inputs use Android rotation-vector order: x, y, z and optional w. Outputs
 * are bounded coordinates in a virtual touch pad. The transport sends hover
 * frames at {@link Result#x}/{@link Result#y}; {@link Result#dx}/{@link
 * Result#dy} are also available for a receiver that needs relative motion.</p>
 */
public final class AirMouseFilter {
    public enum Sensitivity { LOW, NORMAL, HIGH }

    public static final int PAD_SIZE = 1000;
    public static final int PAD_CENTER = PAD_SIZE / 2;

    public static final class Result {
        public final boolean accepted;
        public final boolean calibrating;
        public final boolean calibratedNow;
        public final boolean moved;
        public final int x, y, dx, dy;

        private Result(boolean accepted, boolean calibrating, boolean calibratedNow,
                boolean moved, int x, int y, int dx, int dy) {
            this.accepted = accepted;
            this.calibrating = calibrating;
            this.calibratedNow = calibratedNow;
            this.moved = moved;
            this.x = x;
            this.y = y;
            this.dx = dx;
            this.dy = dy;
        }
    }

    private static final long CALIBRATION_WINDOW_NS = 300_000_000L;
    private static final long CALIBRATION_RETRY_NS = 2_000_000_000L;
    private static final long MAX_SAMPLE_GAP_NS = 150_000_000L;
    private static final int MIN_CALIBRATION_SAMPLES = 12;
    private static final double MAX_CALIBRATION_RMS_RAD = Math.toRadians(1.25);
    private static final double MAX_RELATIVE_ANGLE_RAD = Math.toRadians(80);
    private static final double DEAD_ZONE_DEGREES = 0.25;
    private static final double DEAD_ZONE_ENTER_DEGREES = 0.35;
    private static final double DEAD_ZONE_EXIT_DEGREES = 0.15;
    private static final int PAD_TRAVEL = 450;
    private static final int MAX_STEP = 72;

    private final List<double[]> calibration = new ArrayList<>();
    private final AxisGate horizontalGate = new AxisGate();
    private final AxisGate verticalGate = new AxisGate();
    private final OneEuro horizontalFilter = new OneEuro();
    private final OneEuro verticalFilter = new OneEuro();
    // Physical Samsung/projector acceptance found NORMAL comfortable but too
    // wide for edge-to-edge wrist aiming. HIGH keeps the same dead zone and
    // smoothing while reducing the required sweep from 26°/16° to 20°/12°.
    private Sensitivity sensitivity = Sensitivity.HIGH;
    private boolean invertVertical;
    private boolean calibrating;
    private boolean calibrated;
    private long calibrationStart;
    private long lastTimestamp;
    private double[] calibrationHemisphere;
    private double[] reference;
    private int lastX = PAD_CENTER, lastY = PAD_CENTER;

    public void beginCalibration() {
        calibration.clear();
        calibrationHemisphere = null;
        calibrationStart = 0;
        lastTimestamp = 0;
        calibrating = true;
        calibrated = false;
        reference = null;
        lastX = PAD_CENTER;
        lastY = PAD_CENTER;
        horizontalGate.reset();
        verticalGate.reset();
        horizontalFilter.clear();
        verticalFilter.clear();
    }

    public void stop() {
        calibration.clear();
        calibrationHemisphere = null;
        calibrating = false;
        calibrated = false;
        reference = null;
        lastTimestamp = 0;
        lastX = PAD_CENTER;
        lastY = PAD_CENTER;
        horizontalGate.reset();
        verticalGate.reset();
        horizontalFilter.clear();
        verticalFilter.clear();
    }

    public boolean isCalibrating() { return calibrating; }
    public boolean isCalibrated() { return calibrated; }

    public void setSensitivity(Sensitivity value) {
        sensitivity = value == null ? Sensitivity.NORMAL : value;
    }

    public Sensitivity sensitivity() { return sensitivity; }

    public void setInvertVertical(boolean value) { invertVertical = value; }

    /** Accept one fused rotation-vector sample. Invalid and out-of-order samples are ignored. */
    public Result accept(long timestampNanos, float[] rotationVector) {
        double[] current = quaternion(rotationVector);
        if (timestampNanos <= 0 || current == null || lastTimestamp != 0 && timestampNanos <= lastTimestamp) {
            return result(false, false, false, 0, 0);
        }
        if (calibrating) return calibrate(timestampNanos, current);
        if (!calibrated || reference == null) return result(false, false, false, 0, 0);

        long gap = lastTimestamp == 0 ? 0 : timestampNanos - lastTimestamp;
        lastTimestamp = timestampNanos;
        double[] relative = multiply(conjugate(reference), current);
        if (relative[0] < 0) negate(relative);
        double vectorLength = Math.sqrt(relative[1] * relative[1]
                + relative[2] * relative[2] + relative[3] * relative[3]);
        double angle = 2 * Math.atan2(vectorLength, clamp(relative[0], -1, 1));
        if (!Double.isFinite(angle) || angle > MAX_RELATIVE_ANGLE_RAD) {
            return result(false, false, false, 0, 0);
        }
        double scale = vectorLength < 1e-9 ? 2 : angle / vectorLength;
        // The phone's top edge is the pointer axis. Rotation around screen Z is
        // horizontal aim; rotation around screen X is vertical aim. Roll around
        // the pointer axis is intentionally ignored.
        double horizontal = Math.toDegrees(-relative[3] * scale);
        double vertical = Math.toDegrees(-relative[1] * scale) * (invertVertical ? -1 : 1);
        if (!Double.isFinite(horizontal) || !Double.isFinite(vertical)) {
            return result(false, false, false, 0, 0);
        }

        double gatedHorizontal = horizontalGate.apply(horizontal);
        double gatedVertical = verticalGate.apply(vertical);
        if (gap > MAX_SAMPLE_GAP_NS) {
            horizontalFilter.restart(gatedHorizontal, timestampNanos);
            verticalFilter.restart(gatedVertical, timestampNanos);
            return result(true, false, false, 0, 0);
        }

        double smoothHorizontal;
        double smoothVertical;
        if (Math.abs(horizontal) <= DEAD_ZONE_EXIT_DEGREES
                && Math.abs(vertical) <= DEAD_ZONE_EXIT_DEGREES) {
            // Returning to the calibrated pose must deterministically return to
            // the anchor instead of leaving a low-pass tail near the centre.
            horizontalFilter.restart(0, timestampNanos);
            verticalFilter.restart(0, timestampNanos);
            smoothHorizontal = 0;
            smoothVertical = 0;
        } else {
            smoothHorizontal = horizontalFilter.filter(gatedHorizontal, timestampNanos);
            smoothVertical = verticalFilter.filter(gatedVertical, timestampNanos);
        }

        double horizontalRange = sensitivity == Sensitivity.LOW ? 34
                : sensitivity == Sensitivity.HIGH ? 20 : 26;
        double verticalRange = sensitivity == Sensitivity.LOW ? 21
                : sensitivity == Sensitivity.HIGH ? 12 : 16;
        int targetX = boundedCoordinate(response(smoothHorizontal / horizontalRange));
        int targetY = boundedCoordinate(response(smoothVertical / verticalRange));
        int dx = boundedStep(targetX - lastX);
        int dy = boundedStep(targetY - lastY);
        if (Math.abs(dx) < 1 && Math.abs(dy) < 1) return result(true, false, false, 0, 0);
        lastX += dx;
        lastY += dy;
        return result(true, false, true, dx, dy);
    }

    private Result calibrate(long timestampNanos, double[] current) {
        if (calibrationStart == 0) {
            calibrationStart = timestampNanos;
            calibrationHemisphere = current.clone();
        }
        if (dot(calibrationHemisphere, current) < 0) negate(current);
        calibration.add(current);
        if (calibration.size() > 256) calibration.remove(0);
        lastTimestamp = timestampNanos;
        long elapsed = timestampNanos - calibrationStart;
        if (elapsed < CALIBRATION_WINDOW_NS || calibration.size() < MIN_CALIBRATION_SAMPLES) {
            return result(true, true, false, 0, 0);
        }

        double[] average = normalizedAverage(calibration);
        double squared = 0;
        for (double[] sample : calibration) {
            double cosine = Math.abs(dot(average, sample));
            double difference = 2 * Math.acos(clamp(cosine, -1, 1));
            squared += difference * difference;
        }
        double rms = Math.sqrt(squared / calibration.size());
        if (rms <= MAX_CALIBRATION_RMS_RAD) {
            reference = average;
            calibrating = false;
            calibrated = true;
            calibration.clear();
            horizontalFilter.restart(0, timestampNanos);
            verticalFilter.restart(0, timestampNanos);
            return result(true, false, true, 0, 0);
        }
        if (elapsed >= CALIBRATION_RETRY_NS) {
            calibration.clear();
            calibration.add(current);
            calibrationHemisphere = current.clone();
            calibrationStart = timestampNanos;
        }
        return result(true, true, false, 0, 0);
    }

    private Result result(boolean accepted, boolean stillCalibrating, boolean changed,
            int dx, int dy) {
        boolean calibratedNow = changed && calibrating == false && calibrated
                && dx == 0 && dy == 0 && lastX == PAD_CENTER && lastY == PAD_CENTER;
        boolean moved = changed && (dx != 0 || dy != 0);
        return new Result(accepted, stillCalibrating, calibratedNow, moved,
                lastX, lastY, dx, dy);
    }

    private static int boundedCoordinate(double normalized) {
        return (int) Math.round(clamp(PAD_CENTER + PAD_TRAVEL * normalized, 0, PAD_SIZE));
    }

    private static int boundedStep(int value) {
        return Math.max(-MAX_STEP, Math.min(MAX_STEP, value));
    }

    private static double response(double value) {
        double bounded = clamp(value, -1, 1);
        return 0.70 * bounded + 0.30 * bounded * bounded * bounded;
    }

    private static double[] quaternion(float[] values) {
        if (values == null || values.length < 3) return null;
        double x = values[0], y = values[1], z = values[2];
        double w = values.length >= 4 ? values[3]
                : Math.sqrt(Math.max(0, 1 - x * x - y * y - z * z));
        if (!Double.isFinite(w) || !Double.isFinite(x) || !Double.isFinite(y)
                || !Double.isFinite(z)) return null;
        double length = Math.sqrt(w * w + x * x + y * y + z * z);
        if (!(length > 1e-8)) return null;
        return new double[] { w / length, x / length, y / length, z / length };
    }

    private static double[] normalizedAverage(List<double[]> samples) {
        double[] sum = new double[4];
        double[] first = samples.get(0);
        for (double[] sample : samples) {
            double direction = dot(first, sample) < 0 ? -1 : 1;
            for (int i = 0; i < 4; i++) sum[i] += direction * sample[i];
        }
        double length = Math.sqrt(dot(sum, sum));
        for (int i = 0; i < 4; i++) sum[i] /= length;
        return sum;
    }

    private static double[] conjugate(double[] value) {
        return new double[] { value[0], -value[1], -value[2], -value[3] };
    }

    private static double[] multiply(double[] a, double[] b) {
        return new double[] {
            a[0] * b[0] - a[1] * b[1] - a[2] * b[2] - a[3] * b[3],
            a[0] * b[1] + a[1] * b[0] + a[2] * b[3] - a[3] * b[2],
            a[0] * b[2] - a[1] * b[3] + a[2] * b[0] + a[3] * b[1],
            a[0] * b[3] + a[1] * b[2] - a[2] * b[1] + a[3] * b[0]
        };
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
    }

    private static void negate(double[] value) {
        for (int i = 0; i < value.length; i++) value[i] = -value[i];
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class AxisGate {
        private boolean active;
        void reset() { active = false; }
        double apply(double value) {
            double magnitude = Math.abs(value);
            if (active) {
                if (magnitude < DEAD_ZONE_EXIT_DEGREES) active = false;
            } else if (magnitude > DEAD_ZONE_ENTER_DEGREES) active = true;
            if (!active) return 0;
            return Math.copySign(Math.max(0, magnitude - DEAD_ZONE_DEGREES), value);
        }
    }

    /** One Euro filter with angles expressed in degrees. */
    private static final class OneEuro {
        private static final double MIN_CUTOFF = 1.4;
        private static final double BETA = 0.04;
        private static final double DERIVATIVE_CUTOFF = 1.0;
        private boolean ready;
        private long timestamp;
        private double raw, filtered, derivative;

        void clear() { ready = false; timestamp = 0; raw = filtered = derivative = 0; }
        void restart(double value, long time) {
            ready = true;
            timestamp = time;
            raw = filtered = value;
            derivative = 0;
        }
        double filter(double value, long time) {
            if (!ready || time <= timestamp) { restart(value, time); return value; }
            double seconds = (time - timestamp) / 1_000_000_000d;
            double derivativeAlpha = alpha(DERIVATIVE_CUTOFF, seconds);
            derivative = derivativeAlpha * ((value - raw) / seconds)
                    + (1 - derivativeAlpha) * derivative;
            double cutoff = MIN_CUTOFF + BETA * Math.abs(derivative);
            double valueAlpha = alpha(cutoff, seconds);
            filtered = valueAlpha * value + (1 - valueAlpha) * filtered;
            raw = value;
            timestamp = time;
            return filtered;
        }
        private static double alpha(double cutoff, double seconds) {
            double tau = 1 / (2 * Math.PI * cutoff);
            return 1 / (1 + tau / seconds);
        }
    }
}

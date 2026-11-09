package dev.framextv.lumaremote;

/** Host-JVM deterministic checks for air-mouse calibration and motion math. */
public final class AirMouseFilterTest {
    private static final long STEP = 20_000_000L;

    private static float[] rotation(double x, double y, double z, double degrees) {
        double half = Math.toRadians(degrees) / 2;
        double sine = Math.sin(half);
        return new float[] { (float) (x * sine), (float) (y * sine),
                (float) (z * sine), (float) Math.cos(half) };
    }

    private static long calibrate(AirMouseFilter filter, float[] pose, long start) {
        filter.beginCalibration();
        AirMouseFilter.Result last = null;
        boolean calibratedNow = false;
        for (int i = 0; i < 20; i++) {
            last = filter.accept(start + i * STEP, pose);
            calibratedNow |= last.calibratedNow;
        }
        check(filter.isCalibrated() && last != null && calibratedNow,
                "Stable pose should calibrate");
        check(last.x == AirMouseFilter.PAD_CENTER && last.y == AirMouseFilter.PAD_CENTER,
                "Calibration should anchor the virtual pad centre");
        return start + 20 * STEP;
    }

    private static AirMouseFilter.Result settle(AirMouseFilter filter, long start, float[] pose) {
        AirMouseFilter.Result last = null;
        for (int i = 0; i < 30; i++) last = filter.accept(start + i * STEP, pose);
        return last;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        long time = 1_000_000_000L;

        AirMouseFilter stable = new AirMouseFilter();
        check(stable.sensitivity() == AirMouseFilter.Sensitivity.HIGH,
                "Physical default should use the more responsive preset");
        time = calibrate(stable, rotation(0, 0, 1, 0), time);
        AirMouseFilter.Result noise = settle(stable, time, rotation(0, 0, 1, 0.2));
        check(!noise.moved && noise.x == AirMouseFilter.PAD_CENTER,
                "Sub-dead-zone tremor should not move the pointer");

        AirMouseFilter signs = new AirMouseFilter();
        signs.beginCalibration();
        AirMouseFilter.Result signResult = null;
        boolean signCalibrated = false;
        for (int i = 0; i < 20; i++) {
            float[] identity = i % 2 == 0 ? new float[] { 0, 0, 0, 1 }
                    : new float[] { 0, 0, 0, -1 };
            signResult = signs.accept(time + i * STEP, identity);
            signCalibrated |= signResult.calibratedNow;
        }
        check(signResult != null && signCalibrated,
                "Quaternion q/-q samples must share one calibration hemisphere");
        AirMouseFilter.Result samePose = signs.accept(time + 20 * STEP,
                new float[] { 0, 0, 0, -1 });
        check(!samePose.moved, "Quaternion sign transition must not jump");

        AirMouseFilter horizontal = new AirMouseFilter();
        time = calibrate(horizontal, rotation(0, 0, 1, 0), time + 1_000_000_000L);
        AirMouseFilter.Result left = settle(horizontal, time, rotation(0, 0, 1, 12));
        check(left.x < AirMouseFilter.PAD_CENTER && left.y == AirMouseFilter.PAD_CENTER,
                "Positive Z aim should move monotonically left without vertical crosstalk");
        int firstLeft = left.x;
        AirMouseFilter.Result fartherLeft = settle(horizontal, time + 31 * STEP,
                rotation(0, 0, 1, 20));
        check(fartherLeft.x < firstLeft, "A larger yaw should move farther in the same direction");
        AirMouseFilter.Result returned = settle(horizontal, time + 62 * STEP,
                rotation(0, 0, 1, 0));
        check(returned.x == AirMouseFilter.PAD_CENTER,
                "Returning to the calibrated pose should return to the anchor");

        AirMouseFilter vertical = new AirMouseFilter();
        time = calibrate(vertical, rotation(0, 0, 1, 0), time + 2_000_000_000L);
        AirMouseFilter.Result up = settle(vertical, time, rotation(1, 0, 0, 8));
        check(up.y < AirMouseFilter.PAD_CENTER && up.x == AirMouseFilter.PAD_CENTER,
                "Positive X pitch should move up without horizontal crosstalk");
        vertical.setInvertVertical(true);
        AirMouseFilter.Result inverted = settle(vertical, time + 31 * STEP,
                rotation(1, 0, 0, 8));
        check(inverted.y > AirMouseFilter.PAD_CENTER, "Vertical inversion should reverse pitch");

        AirMouseFilter bounded = new AirMouseFilter();
        time = calibrate(bounded, rotation(0, 0, 1, 0), time + 2_000_000_000L);
        AirMouseFilter.Result firstSweep = bounded.accept(time, rotation(0, 0, 1, 70));
        check(Math.abs(firstSweep.dx) <= 72 && Math.abs(firstSweep.dy) <= 72,
                "A fast sweep must remain step bounded");
        AirMouseFilter.Result invalid = bounded.accept(time + STEP,
                new float[] { Float.NaN, 0, 0, 1 });
        check(!invalid.accepted && !invalid.moved, "NaN samples must be rejected");
        AirMouseFilter.Result old = bounded.accept(time - STEP, rotation(0, 0, 1, 5));
        check(!old.accepted && !old.moved, "Out-of-order timestamps must be rejected");
        AirMouseFilter.Result gap = bounded.accept(time + 500_000_000L,
                rotation(0, 0, 1, 30));
        check(gap.accepted && !gap.moved, "A long sensor gap must reset filtering without a jump");

        AirMouseFilter low = new AirMouseFilter();
        AirMouseFilter high = new AirMouseFilter();
        time = calibrate(low, rotation(0, 0, 1, 0), time + 3_000_000_000L);
        long highTime = calibrate(high, rotation(0, 0, 1, 0), time + 1_000_000_000L);
        low.setSensitivity(AirMouseFilter.Sensitivity.LOW);
        high.setSensitivity(AirMouseFilter.Sensitivity.HIGH);
        AirMouseFilter.Result lowMove = settle(low, time, rotation(0, 0, 1, 10));
        AirMouseFilter.Result highMove = settle(high, highTime, rotation(0, 0, 1, 10));
        check(Math.abs(highMove.x - AirMouseFilter.PAD_CENTER)
                        > Math.abs(lowMove.x - AirMouseFilter.PAD_CENTER),
                "High sensitivity should produce more travel for the same aim");

        stable.stop();
        check(!stable.isCalibrated() && !stable.isCalibrating(),
                "Stopping must discard calibration");
        System.out.println("Air mouse filter checks passed.");
    }
}

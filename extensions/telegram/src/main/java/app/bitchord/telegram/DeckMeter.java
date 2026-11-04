package app.bitchord.telegram;

/**
 * Stereo peak/RMS metering adapted from DJM-Rec MeterCalculator.h, revision
 * 2f36d11b837f5d627740c77f3acc9b130c24b04f. Copyright (c) 2026 P2GR, MIT. See
 * licenses/AUTOMIX-COMPONENTS.txt. Measures decoded, EQ-processed audio before deck faders; it is
 * not a measurement of Android's mixed output. Audio callback methods allocate nothing.
 */
final class DeckMeter {
    private double leftSum, rightSum;
    private float left, right;
    private int frames;
    private volatile float leftPeak = -60, rightPeak = -60, leftRms = -60, rightRms = -60;
    private volatile long updated, clipping;

    void begin() {
        leftSum = rightSum = 0;
        left = right = 0;
        frames = 0;
    }

    void frame(float l, float r) {
        if (!Float.isFinite(l)) l = 0;
        if (!Float.isFinite(r)) r = 0;
        left = Math.max(left, Math.abs(l));
        right = Math.max(right, Math.abs(r));
        leftSum += (double) l * l;
        rightSum += (double) r * r;
        frames++;
    }

    static float db(double magnitude) {
        return magnitude > 0 ? (float) Math.max(-60, Math.min(0, 20 * Math.log10(magnitude))) : -60;
    }

    void end(long now) {
        if (frames == 0) return;
        leftPeak = db(left);
        rightPeak = db(right);
        leftRms = db(Math.sqrt(leftSum / frames));
        rightRms = db(Math.sqrt(rightSum / frames));
        if (left >= .966f || right >= .966f) clipping = now;
        updated = now;
    }

    float[] reading(long now) {
        if (updated == 0 || now - updated > 500_000_000L) return null;
        return new float[] {
            leftPeak,
            rightPeak,
            leftRms,
            rightRms,
            clipping != 0 && now - clipping <= 1_000_000_000L ? 1 : 0
        };
    }
}

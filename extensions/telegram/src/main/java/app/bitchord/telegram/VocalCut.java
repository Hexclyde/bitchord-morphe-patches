/*
 * Sing: turns the lead vocal down so you can sing over the song.
 *
 * Ported from Auris (https://github.com/OreoMuncher45/Auris), files
 * app/src/main/java/com/music/bitchord/playback/VocalCutDsp.kt and VocalCutOriginal.kt,
 * by OreoMuncher45 and the BitChord contributors. Auris is a fork of BitChord by kushagra
 * (https://github.com/kushagrasinghx/BitChord). Copyright (C) 2026 BitChord.
 *
 * Licensed under the GNU General Public License v3.0. Changed for this patch: translated from
 * Kotlin to Java, the two engines merged into one class, and the 16-bit per-frame path removed
 * (BitChord's audio chain hands over 32-bit float blocks).
 */
package app.bitchord.telegram;

/**
 * Two of Auris's three vocal removers. {@link #VOICE_GATE} is Auris's current one: only the 150
 * Hz–10 kHz band is cut, drum hits ease the cut off for a moment, the sides are lifted a little to
 * make up the lost energy, and a pitch detector leaves passages without a singer alone. {@link
 * #CENTRE} is Auris's first: the whole centre channel, with makeup gain. It's the cleaner of the
 * two on older recordings and takes the kick and snare with the voice on modern ones.
 *
 * <p>Pure arithmetic on interleaved stereo floats, so it's tested directly. At no cut it writes
 * nothing, and the block leaves exactly as it came.
 */
final class VocalCut {
    static final int VOICE_GATE = 0, CENTRE = 1;
    static final double LOW_CUTOFF_HZ = 150, HIGH_CUTOFF_HZ = 10_000, FILTER_Q = 0.70710678;
    static final double ENV_FAST_MS = 1, ENV_SLOW_MS = 60;
    static final double BYPASS_ATTACK_MS = 0.5, BYPASS_RELEASE_MS = 25;
    static final float TRANSIENT_RATIO = 3f, TRANSIENT_SLOPE = 0.5f, NOISE_FLOOR = 0.004f;
    static final double SIDE_MAKEUP_DB = 1.5;
    static final float LIMIT_KNEE = 0.9f;
    static final int VAD_DECIM = 4, VAD_WIN = 256, VAD_MIN_LAG = 18, VAD_MAX_LAG = 176;
    static final int VAD_HOP = 256;
    static final double VAD_ATTACK_MS = 50, VAD_RELEASE_MS = 400, VAD_VOICED_FLOOR = 0.35;

    int method = VOICE_GATE;
    int sampleRate;

    // Low split, top split and the mid bandpass: two biquads each side, coefficients b0 b1 b2 a1
    // a2.
    private final float[] low = new float[5], top = new float[5], high = new float[5];
    private final float[][] state = new float[12][4];
    private float envFast, envSlow, bypass, voiceGate = 1f;
    private float envFastA, envSlowA, bypassAttackA, bypassReleaseA, vadAttackA, vadReleaseA;
    private float makeup = 1f;
    private final float[] vadRing = new float[VAD_WIN + VAD_MAX_LAG];
    private int vadPos, vadDecimCount, vadHopCount, vadFilled;
    private float outL, outR;

    VocalCut(int sampleRate) {
        configure(sampleRate);
    }

    void configure(int rate) {
        sampleRate = Math.max(8_000, rate);
        double sr = sampleRate;
        biquad(low, LOW_CUTOFF_HZ / sr, false);
        biquad(top, HIGH_CUTOFF_HZ / sr, false);
        biquad(high, LOW_CUTOFF_HZ / sr, true);
        envFastA = (float) timeConst(ENV_FAST_MS, sr);
        envSlowA = (float) timeConst(ENV_SLOW_MS, sr);
        bypassAttackA = (float) timeConst(BYPASS_ATTACK_MS, sr);
        bypassReleaseA = (float) timeConst(BYPASS_RELEASE_MS, sr);
        double hop = VAD_HOP / sr;
        vadAttackA = (float) (1 - Math.exp(-hop / (VAD_ATTACK_MS / 1000)));
        vadReleaseA = (float) (1 - Math.exp(-hop / (VAD_RELEASE_MS / 1000)));
        makeup = (float) Math.pow(10, SIDE_MAKEUP_DB / 20);
        reset();
    }

    private static void biquad(float[] k, double normalized, boolean highpass) {
        double w0 = 2 * Math.PI * Math.max(0.0001, Math.min(0.4999, normalized));
        double cos = Math.cos(w0), alpha = Math.sin(w0) / (2 * FILTER_Q), a0 = 1 + alpha;
        double edge = highpass ? (1 + cos) / 2 : (1 - cos) / 2;
        k[0] = (float) (edge / a0);
        k[1] = (float) ((highpass ? -(1 + cos) : 1 - cos) / a0);
        k[2] = (float) (edge / a0);
        k[3] = (float) (-2 * cos / a0);
        k[4] = (float) ((1 - alpha) / a0);
    }

    private static double timeConst(double ms, double sr) {
        return 1 - Math.exp(-1 / (ms / 1000 * sr));
    }

    void reset() {
        for (float[] s : state) java.util.Arrays.fill(s, 0f);
        envFast = envSlow = bypass = 0f;
        // A fresh stream cuts until it's shown to be instrumental, never the other way round.
        voiceGate = 1f;
        java.util.Arrays.fill(vadRing, 0f);
        vadPos = vadDecimCount = vadHopCount = vadFilled = 0;
    }

    /** Interleaved stereo samples in place; {@code cut} from 0 (off) to 1 (all of it). */
    void process(float[] samples, int sampleCount, float cut) {
        boolean active = cut > 0.001f;
        float c = Math.max(0f, Math.min(1f, cut));
        if (method == CENTRE) {
            if (!active) return;
            float gain = 1f / (1f - 0.5f * c);
            for (int i = 0; i + 1 < sampleCount; i += 2) {
                float mid = (samples[i] + samples[i + 1]) * 0.5f;
                samples[i] = (samples[i] - c * mid) * gain;
                samples[i + 1] = (samples[i + 1] - c * mid) * gain;
            }
            return;
        }
        for (int i = 0; i + 1 < sampleCount; i += 2) {
            step(samples[i], samples[i + 1], active ? c : 0f, active);
            if (active) {
                samples[i] = outL;
                samples[i + 1] = outR;
            }
        }
    }

    private static float filter(float x, float[] k, float[] st) {
        float y = k[0] * x + k[1] * st[0] + k[2] * st[1] - k[3] * st[2] - k[4] * st[3];
        st[1] = st[0];
        st[0] = x;
        st[3] = st[2];
        st[2] = y;
        return y;
    }

    private float twice(float x, float[] k, int first) {
        return filter(filter(x, k, state[first]), k, state[first + 1]);
    }

    private static float softLimit(float x) {
        float a = Math.abs(x);
        if (a <= LIMIT_KNEE) return x;
        float over = (a - LIMIT_KNEE) / (1f - LIMIT_KNEE);
        return Math.signum(x) * (LIMIT_KNEE + (1f - LIMIT_KNEE) * (float) Math.tanh(over));
    }

    private void step(float l, float r, float cut, boolean active) {
        float lowL = twice(l, low, 0), lowR = twice(r, low, 2);
        float topL = twice(l, top, 4), topR = twice(r, top, 6);
        float highL = l - topL, highR = r - topR;
        float midL = twice(topL, high, 8), midR = twice(topR, high, 10);
        float midMid = (midL + midR) * 0.5f;

        // Drum hits spike and vowels glide: ease the cut off in proportion, not as a switch.
        float mag = Math.abs(midMid);
        envFast += envFastA * (mag - envFast);
        envSlow += envSlowA * (mag - envSlow);
        float ratio = envSlow > NOISE_FLOOR ? envFast / envSlow : 0f;
        float target = Math.max(0f, Math.min(1f, (ratio - TRANSIENT_RATIO) * TRANSIENT_SLOPE));
        bypass += (target > bypass ? bypassAttackA : bypassReleaseA) * (target - bypass);

        voice(midMid);

        if (!active || voiceGate < 0.05f) {
            outL = l;
            outR = r;
            return;
        }
        float effCut = cut * (1f - bypass) * voiceGate;
        float comp = makeup - 1f;
        outL = softLimit(lowL + highL + (midL - effCut * midMid) + comp * (midL - midMid));
        outR = softLimit(lowR + highR + (midR - effCut * midMid) + comp * (midR - midMid));
    }

    /** Is someone singing: the autocorrelation peak of the mid band over 68–666 Hz. */
    private void voice(float midMid) {
        if (++vadDecimCount < VAD_DECIM) return;
        vadDecimCount = 0;
        int size = vadRing.length;
        vadRing[vadPos] = midMid;
        vadPos = (vadPos + 1) % size;
        if (vadFilled < size) vadFilled++;
        if (++vadHopCount < VAD_HOP / VAD_DECIM || vadFilled < size) return;
        vadHopCount = 0;
        double r0 = 0;
        for (int i = 0; i < VAD_WIN; i++) {
            double x = vadRing[(vadPos - 1 - i + size * 2) % size];
            r0 += x * x;
        }
        if (r0 < 1e-9) return;
        double peak = 0;
        for (int lag = VAD_MIN_LAG; lag <= VAD_MAX_LAG; lag++) {
            double sum = 0;
            for (int i = 0; i < VAD_WIN; i++)
                sum +=
                        (double) vadRing[(vadPos - 1 - i + size * 2) % size]
                                * vadRing[(vadPos - 1 - i - lag + size * 3) % size];
            peak = Math.max(peak, sum / r0);
        }
        float target =
                (float)
                        Math.max(
                                0, Math.min(1, (peak - VAD_VOICED_FLOOR) / (1 - VAD_VOICED_FLOOR)));
        voiceGate += (target > voiceGate ? vadAttackA : vadReleaseA) * (target - voiceGate);
    }
}

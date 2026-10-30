package app.bitchord.telegram;

import java.util.Locale;

/**
 * The musical key and tempo of a stretch of audio, for DJ mode: chroma against the
 * Krumhansl-Kessler key profiles for the key, and the autocorrelation of an onset curve for the
 * tempo. Works on mono audio at {@link #RATE}.
 */
final class KeyTempo {
    private KeyTempo() {}

    static final int RATE = 11_025;
    static final int MIN_SECONDS = 15;

    static final String[] NOTES = {"C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B"};
    private static final double[] MAJOR = {
        6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88
    };
    private static final double[] MINOR = {
        6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17
    };

    /** What was found: the key as a pitch class and mode, and the tempo. */
    static final class Result {
        /** 0 for C up to 11 for B; -1 when no key stood out. */
        int pitchClass = -1;

        boolean minor;
        double keyConfidence;

        /** 0 when no tempo stood out. */
        double bpm;

        double tempoConfidence;

        /** "A minor", or null. */
        String keyName() {
            return pitchClass < 0 ? null : NOTES[pitchClass] + (minor ? " minor" : " major");
        }

        /** "8A", the Camelot wheel position, or null. */
        String camelot() {
            return pitchClass < 0 ? null : KeyTempo.camelot(pitchClass, minor);
        }
    }

    /** Camelot position of a key: C major is 8B, A minor 8A. */
    static String camelot(int pitchClass, boolean minor) {
        int major = minor ? (pitchClass + 3) % 12 : pitchClass;
        int fifths = (major * 7) % 12;
        return ((fifths + 7) % 12 + 1) + (minor ? "A" : "B");
    }

    /**
     * How well two Camelot positions mix: 0 for the same key, 1 a step round the wheel or the
     * relative key, 2 two steps, 2.5 a step and a mode change, 4 anything else. Null is 4.
     */
    static double keyDistance(String a, String b) {
        int[] pa = camelotParts(a), pb = camelotParts(b);
        if (pa == null || pb == null) return 4;
        int steps = Math.abs(pa[0] - pb[0]);
        steps = Math.min(steps, 12 - steps);
        boolean sameMode = pa[1] == pb[1];
        if (steps == 0) return sameMode ? 0 : 1;
        if (steps == 1) return sameMode ? 1 : 2.5;
        if (steps == 2 && sameMode) return 2;
        return 4;
    }

    private static int[] camelotParts(String code) {
        if (code == null || code.length() < 2) return null;
        char mode = Character.toUpperCase(code.charAt(code.length() - 1));
        if (mode != 'A' && mode != 'B') return null;
        try {
            int number = Integer.parseInt(code.substring(0, code.length() - 1).trim());
            if (number < 1 || number > 12) return null;
            return new int[] {number, mode == 'A' ? 0 : 1};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Reads a key written any usual way, such as BitChord's "C major", "Am", "F# min", "Bb" or a
     * Camelot code "8A", into a Camelot position. Null when it isn't one.
     */
    static String camelotOf(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty()) return null;
        if (camelotParts(t) != null) return t.toUpperCase(Locale.ROOT);
        String s = t.replace('♯', '#').replace('♭', 'b');
        int pc;
        switch (Character.toUpperCase(s.charAt(0))) {
            case 'C':
                pc = 0;
                break;
            case 'D':
                pc = 2;
                break;
            case 'E':
                pc = 4;
                break;
            case 'F':
                pc = 5;
                break;
            case 'G':
                pc = 7;
                break;
            case 'A':
                pc = 9;
                break;
            case 'B':
                pc = 11;
                break;
            default:
                return null;
        }
        int at = 1;
        if (at < s.length() && s.charAt(at) == '#') {
            pc = (pc + 1) % 12;
            at++;
        } else if (at < s.length() && s.charAt(at) == 'b') {
            pc = (pc + 11) % 12;
            at++;
        }
        String rest = s.substring(at).trim().toLowerCase(Locale.ROOT);
        boolean minor = rest.startsWith("m") && !rest.startsWith("maj");
        if (!rest.isEmpty() && !minor && !rest.startsWith("maj")) return null;
        return camelot(pc, minor);
    }

    /** How far apart two tempos are in octaves, allowing one to be double the other. */
    static double tempoDistance(double a, double b) {
        if (a <= 0 || b <= 0) return 1;
        double r = Math.abs(Math.log(b / a) / Math.log(2));
        return Math.min(r, Math.min(Math.abs(r - 1), Math.abs(r + 1)));
    }

    // ── Analysis ─────────────────────────────────────────────────────────────

    /** Analyses {@code count} mono samples at {@link #RATE}. */
    static Result analyse(float[] mono, int count) {
        Result result = new Result();
        if (mono == null || count < RATE * 5) return result;
        chroma(mono, count, result);
        tempo(mono, count, result);
        return result;
    }

    private static final int KEY_FRAME = 4_096, KEY_HOP = 2_048;

    private static void chroma(float[] mono, int count, Result result) {
        double[] re = new double[KEY_FRAME], im = new double[KEY_FRAME];
        double[] window = hann(KEY_FRAME);
        double[] chroma = new double[12];
        int[] pitchClass = new int[KEY_FRAME / 2 + 1];
        for (int k = 0; k <= KEY_FRAME / 2; k++) {
            double f = (double) k * RATE / KEY_FRAME;
            pitchClass[k] =
                    f >= 55 && f <= 2_000
                            ? Math.floorMod((int) Math.round(12 * log2(f / 440)) + 69, 12)
                            : -1;
        }
        for (int start = 0; start + KEY_FRAME <= count; start += KEY_HOP) {
            for (int i = 0; i < KEY_FRAME; i++) {
                re[i] = mono[start + i] * window[i];
                im[i] = 0;
            }
            fft(re, im);
            for (int k = 1; k <= KEY_FRAME / 2; k++) {
                int pc = pitchClass[k];
                if (pc < 0) continue;
                chroma[pc] += Math.sqrt(re[k] * re[k] + im[k] * im[k]);
            }
        }
        double best = -2, second = -2;
        int bestPc = -1;
        boolean bestMinor = false;
        for (int root = 0; root < 12; root++)
            for (int mode = 0; mode < 2; mode++) {
                double[] profile = mode == 0 ? MAJOR : MINOR;
                double score = correlation(chroma, profile, root);
                if (score > best) {
                    second = best;
                    best = score;
                    bestPc = root;
                    bestMinor = mode == 1;
                } else if (score > second) second = score;
            }
        if (best <= 0.2) return;
        result.pitchClass = bestPc;
        result.minor = bestMinor;
        result.keyConfidence = Math.max(0, Math.min(1, (best - second) / Math.max(1e-9, best) * 4));
    }

    /** Pearson correlation of the chroma with a key profile rotated to {@code root}. */
    private static double correlation(double[] chroma, double[] profile, int root) {
        double mc = 0, mp = 0;
        for (int i = 0; i < 12; i++) {
            mc += chroma[i];
            mp += profile[i];
        }
        mc /= 12;
        mp /= 12;
        double num = 0, dc = 0, dp = 0;
        for (int i = 0; i < 12; i++) {
            double c = chroma[i] - mc, p = profile[Math.floorMod(i - root, 12)] - mp;
            num += c * p;
            dc += c * c;
            dp += p * p;
        }
        return dc <= 0 || dp <= 0 ? -1 : num / Math.sqrt(dc * dp);
    }

    private static final int ONSET_FRAME = 1_024, ONSET_HOP = 256;
    static final double MIN_BPM = 55, MAX_BPM = 215;

    private static void tempo(float[] mono, int count, Result result) {
        int frames = (count - ONSET_FRAME) / ONSET_HOP;
        if (frames < 200) return;
        double fps = (double) RATE / ONSET_HOP;
        double[] re = new double[ONSET_FRAME], im = new double[ONSET_FRAME];
        double[] window = hann(ONSET_FRAME);
        int bins = ONSET_FRAME / 2;
        double[] previous = new double[bins];
        double[] onset = new double[frames];
        for (int f = 0; f < frames; f++) {
            int start = f * ONSET_HOP;
            for (int i = 0; i < ONSET_FRAME; i++) {
                re[i] = mono[start + i] * window[i];
                im[i] = 0;
            }
            fft(re, im);
            double flux = 0;
            for (int k = 1; k < bins; k++) {
                double level = Math.log1p(Math.sqrt(re[k] * re[k] + im[k] * im[k]) * 100);
                double rise = level - previous[k];
                if (rise > 0) flux += rise;
                previous[k] = level;
            }
            onset[f] = flux;
        }
        // Take out the slow trend, keep the rises.
        int half = (int) Math.round(fps / 2);
        double[] clean = new double[frames];
        double sum = 0;
        int n = 0;
        for (int f = 0; f < frames; f++) {
            int from = Math.max(0, f - half), to = Math.min(frames - 1, f + half);
            if (f == 0) {
                for (int i = from; i <= to; i++) sum += onset[i];
                n = to - from + 1;
            } else {
                if (f - half - 1 >= 0) {
                    sum -= onset[f - half - 1];
                    n--;
                }
                if (f + half < frames) {
                    sum += onset[f + half];
                    n++;
                }
            }
            clean[f] = Math.max(0, onset[f] - sum / Math.max(1, n));
        }
        int minLag = (int) Math.floor(fps * 60 / MAX_BPM),
                maxLag = (int) Math.ceil(fps * 60 / MIN_BPM);
        maxLag = Math.min(maxLag, frames / 3);
        if (maxLag <= minLag) return;
        double[] ac = new double[maxLag * 3 + 2];
        for (int lag = 0; lag < ac.length; lag++) {
            double s = 0;
            for (int f = 0; f + lag < frames; f++) s += clean[f] * clean[f + lag];
            ac[lag] = s;
        }
        if (ac[0] <= 0) return;
        for (int lag = 1; lag < ac.length; lag++) ac[lag] /= ac[0];
        ac[0] = 1;
        double bestScore = -1, bestLag = 0;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double bpm = fps * 60 / lag;
            double prior = Math.exp(-0.5 * Math.pow(log2(bpm / 115), 2) / (0.9 * 0.9));
            // The beat rarely falls on a whole number of curve samples, so each multiple is
            // taken at its peak nearby.
            double support = near(ac, lag) + 0.5 * near(ac, 2 * lag) + 0.3 * near(ac, 3 * lag);
            double score = support * prior;
            if (score > bestScore) {
                bestScore = score;
                bestLag = lag;
            }
        }
        if (bestLag <= 0) return;
        // A finer lag between samples of the curve.
        int l = (int) bestLag;
        if (l > 1 && l + 1 < ac.length) {
            double a = ac[l - 1], b = ac[l], c = ac[l + 1];
            double d = a - 2 * b + c;
            if (d < 0) bestLag = l + 0.5 * (a - c) / d;
        }
        result.bpm = Math.round(fps * 60 / bestLag * 10) / 10.0;
        result.tempoConfidence = Math.max(0, Math.min(1, ac[l]));
    }

    private static double near(double[] ac, int lag) {
        double best = 0;
        for (int l = Math.max(1, lag - 1); l <= Math.min(ac.length - 1, lag + 1); l++)
            best = Math.max(best, ac[l]);
        return best;
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }

    private static double[] hann(int n) {
        double[] w = new double[n];
        for (int i = 0; i < n; i++) w[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / n);
        return w;
    }

    /** In-place radix-2 FFT; the length is a power of two. */
    static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2 * Math.PI / len;
            double wr = Math.cos(angle), wi = Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int j = 0; j < len / 2; j++) {
                    int a = i + j, b = i + j + len / 2;
                    double tr = re[b] * cr - im[b] * ci, ti = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                    double ncr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = ncr;
                }
            }
        }
    }
}

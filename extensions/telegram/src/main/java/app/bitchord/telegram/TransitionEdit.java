package app.bitchord.telegram;

import org.json.JSONArray;
import org.json.JSONObject;

/** One directed track pair; timeline values are seconds and EQ values are dB. */
final class TransitionEdit {
    static final int LINEAR = 0, POWER = 1, SMOOTH = 2, CUSTOM = 3;
    static final double MIN_GAP = .001;
    double overlap = 8, outgoingTrim, incomingCue;
    int curve = POWER;
    final double[][] levels = {{1, .75, .35, 0}, {0, .35, .75, 1}};
    final double[][] times = {{0, 1d / 3, 2d / 3, 1}, {0, 1d / 3, 2d / 3, 1}};
    final double[][] eq = {{0, 0, 0}, {0, 0, 0}};
    final int[] filter = {0, 0};
    final double[] cutoff = {300, 300};

    TransitionEdit copy() {
        return decode(encode());
    }

    void validate() {
        require(overlap, .5, 30);
        require(outgoingTrim, 0, 120);
        require(incomingCue, 0, 3600);
        if (curve < LINEAR || curve > CUSTOM)
            throw new IllegalArgumentException("Invalid fade curve");
        for (int side = 0; side < 2; side++) {
            if (times[side][0] != 0 || times[side][3] != 1)
                throw new IllegalArgumentException("Fade endpoints must bound the transition");
            for (int i = 0; i < 4; i++) {
                require(times[side][i], 0, 1);
                require(levels[side][i], 0, 1);
                if (i > 0 && times[side][i] - times[side][i - 1] + 1e-9 < MIN_GAP)
                    throw new IllegalArgumentException("Fade points must remain ordered");
            }
            for (double gain : eq[side]) require(gain, -12, 12);
            require(cutoff[side], 20, 20000);
            if (filter[side] < 0 || filter[side] > 2)
                throw new IllegalArgumentException("Invalid filter");
        }
    }

    double start(double duration) {
        return Math.max(0, duration - outgoingTrim - overlap);
    }

    double gain(int side, double progress) {
        double p = Math.max(0, Math.min(1, progress));
        if (curve == LINEAR) return side == 1 ? p : 1 - p;
        if (curve == POWER)
            return side == 1 ? Math.sin(p * Math.PI / 2) : Math.cos(p * Math.PI / 2);
        if (curve == SMOOTH) {
            double smooth = p * p * (3 - 2 * p);
            return side == 1 ? smooth : 1 - smooth;
        }
        for (int i = 1; i < 4; i++)
            if (p <= times[side][i]) {
                double t = (p - times[side][i - 1]) / (times[side][i] - times[side][i - 1]);
                return levels[side][i - 1] + t * (levels[side][i] - levels[side][i - 1]);
            }
        return levels[side][3];
    }

    float[] cutoffs(int side, double progress) {
        double p = Math.max(0, Math.min(1, progress));
        double amount = side == 0 ? p : 1 - p;
        float lp = 20000, hp = 0;
        if (filter[side] == 1)
            lp = (float) Math.exp(Math.log(20000) + amount * Math.log(cutoff[side] / 20000));
        if (filter[side] == 2)
            hp =
                    (float)
                            (Math.exp(Math.log(20) + amount * Math.log((cutoff[side] + 20) / 20))
                                    - 20);
        return new float[] {lp, hp};
    }

    String encode() {
        validate();
        try {
            JSONObject value =
                    new JSONObject()
                            .put("version", 1)
                            .put("overlap", overlap)
                            .put("trim", outgoingTrim)
                            .put("cue", incomingCue)
                            .put("curve", curve);
            value.put("levels", new JSONArray(levels))
                    .put("times", new JSONArray(times))
                    .put("eq", new JSONArray(eq));
            value.put("filter", new JSONArray(filter)).put("cutoff", new JSONArray(cutoff));
            return value.toString();
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Could not store transition", invalid);
        }
    }

    static TransitionEdit decode(String json) {
        try {
            JSONObject value = new JSONObject(json);
            if (value.getInt("version") != 1)
                throw new IllegalArgumentException("Unsupported transition version");
            TransitionEdit edit = new TransitionEdit();
            edit.overlap = value.getDouble("overlap");
            edit.outgoingTrim = value.getDouble("trim");
            edit.incomingCue = value.getDouble("cue");
            edit.curve = value.getInt("curve");
            for (int side = 0; side < 2; side++) {
                for (int i = 0; i < 4; i++) {
                    edit.levels[side][i] =
                            value.getJSONArray("levels").getJSONArray(side).getDouble(i);
                    edit.times[side][i] =
                            value.getJSONArray("times").getJSONArray(side).getDouble(i);
                }
                for (int i = 0; i < 3; i++)
                    edit.eq[side][i] = value.getJSONArray("eq").getJSONArray(side).getDouble(i);
                edit.filter[side] = value.getJSONArray("filter").getInt(side);
                edit.cutoff[side] = value.getJSONArray("cutoff").getDouble(side);
            }
            edit.validate();
            return edit;
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid saved transition", invalid);
        }
    }

    private static void require(double value, double min, double max) {
        if (!Double.isFinite(value) || value < min || value > max)
            throw new IllegalArgumentException("Transition value is outside its range");
    }

    /** Complementary bands preserve the original signal at zero EQ. State belongs to one deck. */
    static final class Equalizer {
        final double[] low, upper, gains = new double[3];
        final double aLow, aHigh, headroom;

        Equalizer(int rate, int channels, double[] db) {
            low = new double[channels];
            upper = new double[channels];
            aLow = 1 - Math.exp(-2 * Math.PI * 250 / rate);
            aHigh = 1 - Math.exp(-2 * Math.PI * 4000 / rate);
            double peak = 1;
            for (int i = 0; i < 3; i++) {
                gains[i] = Math.pow(10, db[i] / 20);
                peak = Math.max(peak, gains[i]);
            }
            headroom = 1 / peak;
        }

        float sample(float sample, int c) {
            low[c] += aLow * (sample - low[c]);
            upper[c] += aHigh * (sample - upper[c]);
            return (float)
                    ((gains[0] * low[c]
                                    + gains[1] * (upper[c] - low[c])
                                    + gains[2] * (sample - upper[c]))
                            * headroom);
        }

        void process(float[] samples, int frames, int channels) {
            for (int frame = 0; frame < frames; frame++)
                for (int c = 0; c < channels; c++) {
                    int i = frame * channels + c;
                    samples[i] = sample(samples[i], c);
                }
        }
    }
}

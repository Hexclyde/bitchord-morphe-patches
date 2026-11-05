package app.bitchord.telegram;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;

/** One directed track pair; timeline values are seconds and EQ values are dB. */
final class TransitionEdit {
    static final int LINEAR = 0, POWER = 1, SMOOTH = 2, CUSTOM = 3, DJ = 4;
    static final int FADE = 0, RISE = 1, BLEND = 2, WAVE = 3, MELT = 4, SLAM = 5;
    static final double MAX_OVERLAP = 120;
    static final double MIN_GAP = .001;
    double overlap = 8, outgoingTrim, incomingCue;
    int curve = POWER;
    int preset = FADE;
    int bars;
    String recording;
    double faderSharpness = 1, filterDepth = 1;
    boolean automaticTiming;

    /** The listener chose the tempo; rates native AutoMix derived stay implicit. */
    boolean tempoExplicit;

    final double[] volume = {1, 1};
    final int[] deckCurve = {-1, -1};
    final double[] gridOffset = {0, 0}, tempoScale = {1, 1};
    final double[] manualBpm = {0, 0}, overlapRate = {1, 1};
    final double[][] cues = {{-1, -1, -1, -1}, {-1, -1, -1, -1}};
    final double[][] levels = {{1, .75, .35, 0}, {0, .35, .75, 1}};
    final double[][] times = {{0, 1d / 3, 2d / 3, 1}, {0, 1d / 3, 2d / 3, 1}};
    final double[][] eq = {{0, 0, 0}, {0, 0, 0}};
    final int[] filter = {0, 0};
    final double[] cutoff = {300, 300};

    TransitionEdit copy() {
        return decode(encode());
    }

    void validate() {
        require(overlap, .5, MAX_OVERLAP);
        require(outgoingTrim, 0, 120);
        require(incomingCue, 0, 3600);
        if (curve < LINEAR || curve > DJ) throw new IllegalArgumentException("Invalid fade curve");
        require(faderSharpness, 1, 10);
        require(filterDepth, 0, 1);
        if (recording != null && !recording.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid recording identity");
        if (preset < -1
                || preset > SLAM
                || (bars != 0 && bars != 2 && bars != 4 && bars != 8 && bars != 16))
            throw new IllegalArgumentException("Invalid transition preset or bar count");
        for (int side = 0; side < 2; side++) {
            require(volume[side], 0, 1);
            if (deckCurve[side] < -1 || deckCurve[side] > DJ)
                throw new IllegalArgumentException("Invalid deck curve");
            require(gridOffset[side], -8, 8);
            if (manualBpm[side] != 0) require(manualBpm[side], 30, 300);
            require(overlapRate[side], .5, 2);
            if (tempoScale[side] != .5 && tempoScale[side] != 1 && tempoScale[side] != 2)
                throw new IllegalArgumentException("Invalid beat grid scale");
            for (double cue : cues[side]) require(cue, -1, 86400);
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
        return Math.max(0, duration - outgoingTrim - sourceSpan(0));
    }

    double sourceSpan(int side) {
        return overlap * overlapRate[side];
    }

    double incomingAt(double progress) {
        return incomingCue + sourceSpan(1) * Math.max(0, Math.min(1, progress));
    }

    double baseBpm(int side, double measured) {
        double value = manualBpm[side] == 0 ? measured : manualBpm[side];
        return Double.isFinite(value) && value >= 30 && value <= 300 ? value : Double.NaN;
    }

    double overlapBpm(int side, double measured) {
        return baseBpm(side, measured) * overlapRate[side];
    }

    void setOverlapBpm(int side, double target, double measured) {
        double rate = target / baseBpm(side, measured);
        require(rate, .5, 2);
        overlapRate[side] = rate;
        tempoExplicit = true;
    }

    boolean canMatchTempo(int side, double outgoing, double incoming) {
        double[] measured = {outgoing, incoming};
        double rate = overlapBpm(1 - side, measured[1 - side]) / baseBpm(side, measured[side]);
        return Double.isFinite(rate) && rate >= .5 && rate <= 2;
    }

    void matchTempo(int side, double outgoing, double incoming) {
        double[] measured = {outgoing, incoming};
        setOverlapBpm(side, overlapBpm(1 - side, measured[1 - side]), measured[side]);
    }

    void resetOverlapRates() {
        Arrays.fill(overlapRate, 1);
        tempoExplicit = true;
    }

    boolean hasTempoOverrides() {
        return manualBpm[0] != 0 || manualBpm[1] != 0 || overlapRate[0] != 1 || overlapRate[1] != 1;
    }

    void copyTempoFrom(TransitionEdit other) {
        System.arraycopy(other.manualBpm, 0, manualBpm, 0, 2);
        System.arraycopy(other.overlapRate, 0, overlapRate, 0, 2);
        tempoExplicit = other.tempoExplicit;
    }

    /** Our presets describe real fader/filter moves; none changes either deck's tempo. */
    void preset(int value) {
        if (value < FADE || value > SLAM) throw new IllegalArgumentException("Unknown preset");
        preset = value;
        curve = value == FADE ? POWER : CUSTOM;
        for (int deck = 0; deck < 2; deck++) {
            volume[deck] = 1;
            deckCurve[deck] = -1;
            for (int band = 0; band < 3; band++) eq[deck][band] = 0;
            filter[deck] = 0;
            cutoff[deck] = 300;
            times[deck][0] = 0;
            times[deck][3] = 1;
            levels[deck][0] = deck == 0 ? 1 : 0;
            levels[deck][3] = deck == 0 ? 0 : 1;
        }
        double outA = .33, outB = .67, inA = .33, inB = .67;
        double[] out = {.75, .35}, in = {.35, .75};
        if (value == RISE) {
            outA = .55;
            outB = .88;
            inA = .2;
            inB = .65;
            out = new double[] {1, .55};
            in = new double[] {.5, 1};
            filter[1] = 2;
            cutoff[1] = 1500;
        } else if (value == BLEND) {
            outA = .55;
            outB = .82;
            inA = .18;
            inB = .45;
            out = new double[] {1, .6};
            in = new double[] {.6, 1};
            eq[0][0] = -6;
            eq[1][0] = -6;
        } else if (value == WAVE) {
            outA = .35;
            outB = .65;
            inA = .35;
            inB = .65;
            out = new double[] {.45, .8};
            in = new double[] {.8, .45};
        } else if (value == MELT) {
            outA = .2;
            outB = .65;
            inA = .35;
            inB = .8;
            out = new double[] {.85, .25};
            in = new double[] {.25, .85};
            filter[0] = 1;
            cutoff[0] = 350;
        } else if (value == SLAM) {
            outA = .49;
            outB = .51;
            inA = .49;
            inB = .51;
            out = new double[] {1, 0};
            in = new double[] {0, 1};
        }
        times[0][1] = outA;
        times[0][2] = outB;
        times[1][1] = inA;
        times[1][2] = inB;
        levels[0][1] = out[0];
        levels[0][2] = out[1];
        levels[1][1] = in[0];
        levels[1][2] = in[1];
    }

    static double secondsForBars(int bars, double bpm) {
        if ((bars != 2 && bars != 4 && bars != 8 && bars != 16)
                || !Double.isFinite(bpm)
                || bpm < 15
                || bpm > 600) throw new IllegalArgumentException("A measured tempo is required");
        return bars * 240d / bpm;
    }

    static double snap(double seconds, double bpm, double firstBeat) {
        if (!Double.isFinite(bpm) || bpm < 30 || bpm > 300) return seconds;
        double beat = 60 / bpm;
        return firstBeat + Math.rint((seconds - firstBeat) / beat) * beat;
    }

    MixTools.Grid grid(int side, double bpm, double firstBeat) {
        return new MixTools.Grid(firstBeat + gridOffset[side], bpm * tempoScale[side]);
    }

    /**
     * SideDeck dj_controller.dart grid controls, GPL-3.0-or-later,
     * bfd58fdf2246eab4a814fb550a77ee50258c8533.
     */
    void nudgeGrid(int side, double seconds) {
        gridOffset[side] = Math.max(-8, Math.min(8, gridOffset[side] + seconds));
    }

    double gain(int side, double progress) {
        return volume[side] * baseGain(side, progress);
    }

    double baseGain(int side, double progress) {
        int curve = deckCurve[side] < 0 ? this.curve : deckCurve[side];
        double p = Math.max(0, Math.min(1, progress));
        if (curve == LINEAR) return side == 1 ? p : 1 - p;
        if (curve == POWER)
            return side == 1 ? Math.sin(p * Math.PI / 2) : Math.cos(p * Math.PI / 2);
        if (curve == DJ) return MixTools.crossfader(side, p, faderSharpness);
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
        double amount = (side == 0 ? p : 1 - p) * filterDepth;
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
            value.put("preset", preset)
                    .put("bars", bars)
                    .put("volume", new JSONArray(volume))
                    .put("deckCurve", new JSONArray(deckCurve));
            value.put("recording", recording)
                    .put("sharpness", faderSharpness)
                    .put("filterDepth", filterDepth)
                    .put("automaticTiming", automaticTiming)
                    .put("tempoExplicit", tempoExplicit)
                    .put("gridOffset", new JSONArray(gridOffset))
                    .put("tempoScale", new JSONArray(tempoScale))
                    .put("manualBpm", new JSONArray(manualBpm))
                    .put("overlapRate", new JSONArray(overlapRate))
                    .put("cues", new JSONArray(cues));
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
            edit.preset = value.optInt("preset", edit.curve == POWER ? FADE : -1);
            edit.bars = value.optInt("bars", 0);
            edit.recording = value.isNull("recording") ? null : value.optString("recording", null);
            edit.faderSharpness = value.optDouble("sharpness", 1);
            edit.filterDepth = value.has("filterDepth") ? value.getDouble("filterDepth") : 1;
            edit.automaticTiming = value.optBoolean("automaticTiming", false);
            for (int side = 0; side < 2; side++) {
                if (value.has("volume"))
                    edit.volume[side] = value.getJSONArray("volume").getDouble(side);
                if (value.has("deckCurve"))
                    edit.deckCurve[side] = value.getJSONArray("deckCurve").getInt(side);
                if (value.has("gridOffset"))
                    edit.gridOffset[side] = value.getJSONArray("gridOffset").getDouble(side);
                if (value.has("tempoScale"))
                    edit.tempoScale[side] = value.getJSONArray("tempoScale").getDouble(side);
                if (value.has("manualBpm"))
                    edit.manualBpm[side] = value.getJSONArray("manualBpm").getDouble(side);
                if (value.has("overlapRate"))
                    edit.overlapRate[side] = value.getJSONArray("overlapRate").getDouble(side);
                if (value.has("cues"))
                    for (int i = 0; i < 4; i++)
                        edit.cues[side][i] =
                                value.getJSONArray("cues").getJSONArray(side).getDouble(i);
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
            // Records written before native rates were captured: any tempo value was chosen.
            edit.tempoExplicit =
                    value.has("tempoExplicit")
                            ? value.getBoolean("tempoExplicit")
                            : edit.automaticTiming || edit.hasTempoOverrides();
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

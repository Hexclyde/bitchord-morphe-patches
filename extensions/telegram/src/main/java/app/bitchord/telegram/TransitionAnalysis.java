package app.bitchord.telegram;

import java.util.*;

/**
 * Read-only view of measured host results. Absent evidence stays absent, including zero defaults.
 */
final class TransitionAnalysis {
    static final double BEAT_CONFIDENCE = .5, KEY_CONFIDENCE = .6;

    static final class Snapshot {
        boolean ready;
        String phase, key;
        double duration,
                bpm = Double.NaN,
                beatConfidence = Double.NaN,
                firstBeat = Double.NaN,
                keyConfidence = Double.NaN,
                introStart = Double.NaN,
                introEnd = Double.NaN,
                outroStart = Double.NaN,
                outroEnd = Double.NaN,
                vocalProbability = Double.NaN;
        double[][] energy = new double[0][];

        boolean beatGrid() {
            return ready
                    && bpm > 0
                    && beatConfidence >= BEAT_CONFIDENCE
                    && Double.isFinite(firstBeat)
                    && firstBeat >= 0
                    && firstBeat < duration;
        }

        boolean reliableKey() {
            return ready && key != null && keyConfidence >= KEY_CONFIDENCE;
        }

        String state() {
            if (ready) return Strings.t("Ready");
            switch (phase) {
                case "disabled":
                    return Strings.t("Enable Automix in Playback settings first.");
                case "analyzing":
                case "queued":
                    return Strings.t("Analyzing selected audio…");
                case "waiting":
                    return Strings.t("Waiting to analyze");
                case "failed":
                    return Strings.t("Could not read this track's audio.");
                case "ready":
                    return Strings.t("Analysis result could not be read");
                case "memory":
                    return Strings.t("Waiting for memory");
                case "memory_limit":
                    return Strings.t("Not enough memory to analyze this track. Tap Retry.");
                case "thermal":
                    return Strings.t("Waiting for device to cool down");
                default:
                    return Strings.t("Not available");
            }
        }

        /** One short word for the track header; the full reason comes from state(). */
        String badge() {
            if (ready) return Strings.t("Ready");
            switch (phase) {
                case "analyzing":
                case "queued":
                case "waiting":
                case "memory":
                case "thermal":
                    return Strings.t("Analyzing…");
                default:
                    return Strings.t("Unavailable");
            }
        }

        String tempo() {
            if (!(bpm > 0)) return state();
            return String.format(Strings.current(), "%.1f bpm", bpm)
                    + (beatGrid() ? "" : " · " + Strings.t("Low confidence"));
        }

        String keyLabel() {
            if (key == null) return Strings.t("Not available");
            return key
                    + " · "
                    + percent(keyConfidence)
                    + " · "
                    + Strings.t(reliableKey() ? "Estimated" : "Low confidence");
        }

        String signature() {
            return ready
                    + "|"
                    + phase
                    + "|"
                    + bpm
                    + "|"
                    + beatConfidence
                    + "|"
                    + firstBeat
                    + "|"
                    + key
                    + "|"
                    + keyConfidence
                    + "|"
                    + introStart
                    + "|"
                    + introEnd
                    + "|"
                    + outroStart
                    + "|"
                    + outroEnd
                    + "|"
                    + vocalProbability
                    + "|"
                    + Arrays.deepHashCode(energy);
        }
    }

    static Snapshot read(TransitionRuntime.Pair pair, int side) {
        Snapshot result = new Snapshot();
        result.duration = side == 0 ? pair.outgoingDuration : pair.incomingDuration;
        result.phase = SourceAutomix.analysisPhase(side == 0 ? pair.outgoingId : pair.incomingId);
        Object analysis = TransitionRuntime.analysis(pair, side);
        result.ready = "ready".equals(value(analysis, "status"));
        if (!result.ready) return result;
        result.bpm = number(analysis, "bpm");
        result.beatConfidence = confidence(analysis, "beatConfidence");
        result.firstBeat = number(analysis, "firstBeat");
        Object key = value(analysis, "key");
        if (key instanceof String && KeyTempo.camelotOf((String) key) != null)
            result.key = (String) key;
        result.keyConfidence = confidence(analysis, "keyConfidence");
        double start = number(analysis, "audibleStartTime"), end = number(analysis, "introEndTime");
        if (interval(start, end, result.duration)) {
            result.introStart = start;
            result.introEnd = end;
        }
        start = number(analysis, "outroStartTime");
        end = number(analysis, "contentEndTime");
        if (interval(start, end, result.duration)) {
            result.outroStart = start;
            result.outroEnd = end;
        }
        Object curve = value(analysis, "energyCurve");
        if (curve instanceof List) {
            List<?> samples = (List<?>) curve;
            ArrayList<double[]> valid = new ArrayList<>();
            int count = Math.min(512, samples.size());
            for (int i = 0; i < count; i++) {
                Object sample =
                        samples.get(count <= 1 ? 0 : i * (samples.size() - 1) / (count - 1));
                double at = number(sample, "time"), energy = number(sample, "energy");
                if (at >= 0
                        && at <= result.duration
                        && Double.isFinite(energy)
                        && (valid.isEmpty() || at > valid.get(valid.size() - 1)[0]))
                    valid.add(new double[] {at, energy});
            }
            if (valid.size() > 1) result.energy = valid.toArray(new double[0][]);
        }
        // A default zero is not evidence that the track is instrumental. Require a real mask.
        Object vocals = value(analysis, "vocalActivityMask");
        if (vocals instanceof List && !((List<?>) vocals).isEmpty()) {
            boolean valid = true;
            for (Object sample : (List<?>) vocals) {
                double probability =
                        sample instanceof Number ? ((Number) sample).doubleValue() : Double.NaN;
                if (!(probability >= 0 && probability <= 1)) {
                    valid = false;
                    break;
                }
            }
            if (valid) result.vocalProbability = confidence(analysis, "vocalProbability");
        }
        return result;
    }

    private static boolean interval(double start, double end, double duration) {
        return start >= 0 && end > start && end <= duration && Double.isFinite(end);
    }

    private static Object value(Object object, String name) {
        try {
            return TransitionRuntime.field(object, name);
        } catch (Exception unavailable) {
            return null;
        }
    }

    private static double number(Object object, String name) {
        Object value = value(object, name);
        double number = value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
        return Double.isFinite(number) ? number : Double.NaN;
    }

    private static double confidence(Object object, String name) {
        double value = number(object, name);
        return value >= 0 && value <= 1 ? value : Double.NaN;
    }

    static String percent(double value) {
        return Double.isFinite(value)
                ? java.text.NumberFormat.getPercentInstance(Strings.current()).format(value)
                : Strings.t("Not available");
    }
}

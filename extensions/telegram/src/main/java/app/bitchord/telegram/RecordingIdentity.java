package app.bitchord.telegram;

/** Recording evidence is separate from advertised codec/quality and never a probability score. */
final class RecordingIdentity {
    static final double MAX_DURATION_DIFFERENCE = 0.75, MAX_OFFSET_DRIFT = 0.080;
    static final int MIN_SECONDS = 10;

    static final class Window {
        final Fingerprint fingerprint;
        final double start, seconds;

        Window(float[] mono, int rate, double start) {
            this.fingerprint = Fingerprint.of(mono, mono.length, rate);
            this.start = start;
            this.seconds = mono.length / (double) rate;
        }
    }

    static Double match(
            Window[] reference,
            Window[] candidate,
            double referenceDuration,
            double candidateDuration) {
        if (reference == null
                || candidate == null
                || reference.length < 3
                || candidate.length != reference.length
                || !Double.isFinite(referenceDuration)
                || !Double.isFinite(candidateDuration)
                || referenceDuration <= 0
                || candidateDuration <= 0
                || Math.abs(referenceDuration - candidateDuration) > MAX_DURATION_DIFFERENCE)
            return null;
        double sum = 0, min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < reference.length; i++) {
            Window a = reference[i], b = candidate[i];
            if (a == null
                    || b == null
                    || a.seconds < MIN_SECONDS
                    || b.seconds < MIN_SECONDS
                    || a.fingerprint.size() < 100
                    || b.fingerprint.size() < 100) return null;
            Fingerprint.Match found = Fingerprint.match(a.fingerprint, b.fingerprint);
            if (!found.same()) return null;
            double offset = b.start - a.start - found.offset;
            min = Math.min(min, offset);
            max = Math.max(max, offset);
            sum += offset;
        }
        return max - min > MAX_OFFSET_DRIFT ? null : sum / reference.length;
    }
}

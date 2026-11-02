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

    /** Whole-track selection does not supply an offset for a running player's swap. */
    static boolean matchesPrepared(
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
            return false;
        for (int i = 0; i < reference.length; i++)
            if (reference[i] == null
                    || candidate[i] == null
                    || !Double.isFinite(reference[i].start)
                    || !Double.isFinite(candidate[i].start)) return false;
        if (match(reference, candidate, referenceDuration, candidateDuration) != null) return true;

        // The container durations anchor the rate difference; never fit a slope to the excerpts.
        double rateDifference = candidateDuration / referenceDuration - 1;
        if (!Double.isFinite(rateDifference)) return false;
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < reference.length; i++) {
            Window a = reference[i], b = candidate[i];
            if (a.seconds < MIN_SECONDS
                    || b.seconds < MIN_SECONDS
                    || a.fingerprint.size() < 100
                    || b.fingerprint.size() < 100) return false;
            Fingerprint.Match found = Fingerprint.match(a.fingerprint, b.fingerprint);
            if (!found.same()) return false;
            double residual = b.start - a.start - found.offset - a.start * rateDifference;
            if (!Double.isFinite(residual)) return false;
            min = Math.min(min, residual);
            max = Math.max(max, residual);
        }
        return max - min <= MAX_OFFSET_DRIFT;
    }

    /** Anonymous measurements explain a refusal without logging track names or source addresses. */
    static String describe(
            Window[] reference,
            Window[] candidate,
            double referenceDuration,
            double candidateDuration) {
        if (reference == null || candidate == null || reference.length != candidate.length)
            return "audio windows unavailable";
        StringBuilder text = new StringBuilder();
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        double normalizedMin = Double.POSITIVE_INFINITY, normalizedMax = Double.NEGATIVE_INFINITY;
        double rateDifference = candidateDuration / referenceDuration - 1;
        for (int i = 0; i < reference.length; i++) {
            Window a = reference[i], b = candidate[i];
            if (a == null || b == null) return "audio windows incomplete";
            Fingerprint.Match found = Fingerprint.match(a.fingerprint, b.fingerprint);
            double offset = b.start - a.start - found.offset;
            double residual = offset - a.start * rateDifference;
            if (i > 0) text.append("; ");
            text.append(
                    String.format(
                            java.util.Locale.US,
                            "window %d: %d/%d hashes, offset %+.0f ms",
                            i + 1,
                            found.score,
                            found.size,
                            offset * 1000));
            min = Math.min(min, offset);
            max = Math.max(max, offset);
            normalizedMin = Math.min(normalizedMin, residual);
            normalizedMax = Math.max(normalizedMax, residual);
        }
        return text.append(
                        String.format(
                                java.util.Locale.US,
                                "; offset span %.0f ms, duration-normalized span %.0f ms; limit"
                                        + " %.0f ms",
                                (max - min) * 1000,
                                (normalizedMax - normalizedMin) * 1000,
                                MAX_OFFSET_DRIFT * 1000))
                .toString();
    }
}

package app.bitchord.telegram;

import java.util.Arrays;

/**
 * Tempo-neutral DJ maths. Phrase/grid portions adapted from DJ Sugar's phrasealign.cpp
 * (2e1f03eda5e273ba7d732d3620246bad5891e1be), GPL-2.0-or-later; crossfader from Mixxx's
 * enginexfader.cpp (bc49961fd3e5a46cd46588090c652a0d7ec44e18), Copyright (C) 2001-2026 Mixxx
 * Development Team, GPL-2.0-or-later. Java adaptation distributed under GPL-3.0-or-later. See
 * licenses/AUTOMIX-COMPONENTS.txt. All times remain in the recording's own seconds.
 */
final class MixTools {
    private MixTools() {}

    static final class Grid {
        final double first, interval;
        private final double[] beats;

        Grid(double first, double bpm) {
            this.first = Double.isFinite(first) ? first : 0;
            interval = Double.isFinite(bpm) && bpm >= 15 && bpm <= 600 ? 60 / bpm : 0;
            beats = new double[0];
        }

        Grid(double[] times) {
            double[] sorted = times.clone();
            Arrays.sort(sorted);
            int n = 0;
            for (double t : sorted) {
                if (!Double.isFinite(t) || t < 0) continue;
                if (n == 0 || t - sorted[n - 1] >= .0001) sorted[n++] = t;
            }
            beats = n >= 2 ? Arrays.copyOf(sorted, n) : new double[0];
            first = n >= 2 ? beats[0] : 0;
            interval = n >= 2 ? (beats[n - 1] - first) / (n - 1) : 0;
        }

        boolean valid() {
            return interval > 0;
        }

        double time(double beat) {
            if (!valid()) return Double.NaN;
            if (beats.length == 0) return first + beat * interval;
            int last = beats.length - 1;
            int i = Math.max(0, Math.min(last - 1, (int) Math.floor(beat)));
            return beats[i] + (beat - i) * (beats[i + 1] - beats[i]);
        }

        double at(double seconds) {
            if (!valid()) return Double.NaN;
            if (beats.length == 0) return (seconds - first) / interval;
            int found = Arrays.binarySearch(beats, seconds);
            if (found >= 0) return found;
            int i = Math.max(0, Math.min(beats.length - 2, -found - 2));
            return i + (seconds - beats[i]) / (beats[i + 1] - beats[i]);
        }

        double snap(double seconds) {
            return valid() ? time(Math.round(at(seconds))) : seconds;
        }

        double jump(double seconds, int count) {
            return valid() ? time(at(seconds) + count) : seconds;
        }

        double beatSeconds(double seconds) {
            if (beats.length == 0) return interval;
            int centre = Math.max(0, Math.min(beats.length - 1, (int) Math.round(at(seconds))));
            int to = Math.min(beats.length - 1, Math.max(centre + 4, 8));
            int from = Math.max(0, to - 8);
            double[] lengths = new double[to - from];
            for (int i = from; i < to; i++) lengths[i - from] = beats[i + 1] - beats[i];
            Arrays.sort(lengths);
            int mid = lengths.length / 2;
            return lengths.length % 2 == 1 ? lengths[mid] : (lengths[mid - 1] + lengths[mid]) / 2;
        }
    }

    static final class Plan {
        final double start, end, cue;
        final int bars;

        Plan(double start, double end, double cue, int bars) {
            this.start = start;
            this.end = end;
            this.cue = cue;
            this.bars = bars;
        }
    }

    /** DJ Sugar planUnmatched: phrase end, then fit the incoming intro without rate matching. */
    static Plan unmatched(
            Grid outgoing,
            Grid incoming,
            double limit,
            double earliest,
            double bodyStart,
            int bars) {
        if (!outgoing.valid()
                || !Double.isFinite(limit)
                || !Double.isFinite(earliest)
                || !Double.isFinite(bodyStart)
                || earliest < 0
                || bars < 1
                || bars > 16) return null;
        double startBeat =
                Math.floor((outgoing.at(limit) - bars * 4 + .005 / outgoing.interval) / 32) * 32;
        if (startBeat < 0) return null;
        double end = outgoing.time(startBeat + bars * 4);
        double begin = outgoing.time(startBeat);
        double cue = earliest;
        int fitBars = bars;
        if (bodyStart >= earliest) {
            double entry = bodyStart;
            if (incoming.valid()) {
                double beat = incoming.at(bodyStart);
                double phrase = Math.max(0, Math.round(beat / 32) * 32);
                double phraseTime = incoming.time(phrase);
                double entryBeat =
                        phraseTime >= bodyStart - 2 && phraseTime <= bodyStart + 1
                                ? phrase
                                : Math.max(0, Math.round(beat / 4) * 4);
                entry = incoming.time(entryBeat);
            }
            double intro = Math.max(0, entry - earliest);
            if (intro < end - begin) {
                fitBars = 0;
                while (fitBars < bars
                        && end - outgoing.time(startBeat + bars * 4 - (fitBars + 1) * 4)
                                <= intro + 1e-6) fitBars++;
                fitBars = Math.max(1, fitBars);
                begin = outgoing.time(startBeat + bars * 4 - fitBars * 4);
            }
            cue = Math.max(earliest, entry - (end - begin));
        }
        if (begin < 0 || end <= begin || cue < 0) return null;
        return new Plan(begin, end, cue, fitBars);
    }

    /** Mixxx constant-power crossfader, with its calibrated slow-fade/fast-cut transform. */
    static double crossfader(int side, double progress, double transform) {
        double p = Math.max(0, Math.min(1, progress));
        double power = Math.pow(.5, 1 / transform);
        double position = (p * 2 - 1) * power;
        double left = Math.max(0, 1 - Math.pow(Math.max(0, position + power), transform));
        double right = Math.max(0, 1 - Math.pow(Math.max(0, power - position), transform));
        if (left > right) right = 1 - left;
        else left = 1 - right;
        double norm = Math.hypot(left, right);
        return Math.max(0, Math.min(1, (side == 0 ? left : right) / norm));
    }
}

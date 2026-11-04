package app.bitchord.telegram;

import java.util.ArrayList;
import java.util.List;

/** Stable occurrence ordering by measured harmonic and tempo compatibility. */
final class DjOrder {
    static final int STEADY = 0, BUILD_UP = 1, WIND_DOWN = 2;

    private DjOrder() {}

    static List<Integer> order(
            DjMode.Info current, List<DjMode.Info> songs, List<Boolean> pinned, int mode) {
        if (songs.size() != pinned.size())
            throw new IllegalArgumentException("Queue anchors differ");
        List<Integer> result = new ArrayList<>();
        DjMode.Info previous = current;
        int start = 0;
        while (start < songs.size()) {
            if (Boolean.TRUE.equals(pinned.get(start))) {
                result.add(start);
                previous = songs.get(start++);
                continue;
            }
            int end = start;
            while (end < songs.size() && !Boolean.TRUE.equals(pinned.get(end))) end++;
            List<Integer> remaining = new ArrayList<>();
            for (int i = start; i < end; i++) remaining.add(i);
            while (!remaining.isEmpty()) {
                int best = 0;
                double bestCost = Double.POSITIVE_INFINITY;
                for (int i = 0; i < remaining.size(); i++) {
                    double value = cost(previous, songs.get(remaining.get(i)), mode);
                    if (value < bestCost) {
                        bestCost = value;
                        best = i;
                    }
                }
                int chosen = remaining.remove(best);
                result.add(chosen);
                if (known(songs.get(chosen))) previous = songs.get(chosen);
            }
            start = end;
        }
        return result;
    }

    private static boolean known(DjMode.Info song) {
        return song != null && Double.isFinite(song.bpm) && song.bpm > 0;
    }

    private static double cost(DjMode.Info from, DjMode.Info to, int mode) {
        if (!known(to)) return 1000;
        if (!known(from)) return 0;
        double key =
                from.camelot == null || to.camelot == null
                        ? 8
                        : KeyTempo.keyDistance(from.camelot, to.camelot) * 4;
        double tempo = KeyTempo.tempoDistance(from.bpm, to.bpm) * 12;
        if (mode == BUILD_UP && to.bpm < from.bpm) tempo += 4;
        if (mode == WIND_DOWN && to.bpm > from.bpm) tempo += 4;
        return key + tempo;
    }

    static double blend(DjMode.Info from, DjMode.Info to, double normal, double maximum) {
        if (!known(from)
                || !known(to)
                || from.camelot == null
                || to.camelot == null
                || KeyTempo.keyDistance(from.camelot, to.camelot) > 1
                || KeyTempo.tempoDistance(from.bpm, to.bpm) > .04) return normal;
        return Math.min(maximum, Math.max(normal, 32 * 60 / from.bpm));
    }
}

package app.bitchord.telegram;

import java.text.Normalizer;
import java.util.*;

/** SongMirror's weighted metadata confidence, with native recording checks applied separately. */
final class ImportConfidence {
    static String normalized(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
    }

    static double similarity(String a, String b) {
        a = normalized(a);
        b = normalized(b);
        if (a.isEmpty() || b.isEmpty()) return 0;
        String[] left = a.split(" "), right = b.split(" ");
        Arrays.sort(left);
        Arrays.sort(right);
        String x = String.join(" ", left), y = String.join(" ", right);
        return Math.max(ratio(x, y), jaro(a, b));
    }

    static double ratio(String a, String b) {
        int[] previous = new int[b.length() + 1], next = new int[b.length() + 1];
        for (int i = 0; i < a.length(); i++) {
            for (int j = 0; j < b.length(); j++)
                next[j + 1] =
                        a.charAt(i) == b.charAt(j)
                                ? previous[j] + 1
                                : Math.max(previous[j + 1], next[j]);
            int[] swap = previous;
            previous = next;
            next = swap;
            Arrays.fill(next, 0);
        }
        return a.length() + b.length() == 0
                ? 1
                : 2d * previous[b.length()] / (a.length() + b.length());
    }

    static double jaro(String a, String b) {
        if (a.equals(b)) return 1;
        if (a.isEmpty() || b.isEmpty()) return 0;
        int window = Math.max(0, Math.max(a.length(), b.length()) / 2 - 1), matched = 0;
        boolean[] first = new boolean[a.length()], second = new boolean[b.length()];
        for (int i = 0; i < a.length(); i++)
            for (int j = Math.max(0, i - window); j < Math.min(b.length(), i + window + 1); j++)
                if (!second[j] && a.charAt(i) == b.charAt(j)) {
                    first[i] = true;
                    second[j] = true;
                    matched++;
                    break;
                }
        if (matched == 0) return 0;
        int at = 0, swaps = 0;
        for (int i = 0; i < a.length(); i++)
            if (first[i]) {
                while (!second[at]) at++;
                if (a.charAt(i) != b.charAt(at)) swaps++;
                at++;
            }
        double score =
                (matched / (double) a.length()
                                + matched / (double) b.length()
                                + (matched - swaps / 2d) / matched)
                        / 3;
        int prefix = 0;
        while (prefix < Math.min(4, Math.min(a.length(), b.length()))
                && a.charAt(prefix) == b.charAt(prefix)) prefix++;
        return score > .7 ? score + prefix * .1 * (1 - score) : score;
    }

    static double score(PlaylistImport.Track source, Object song) {
        double title = similarity(source.title, MixedSongCodec.text(song, "title")),
                artist = similarity(source.artist, MixedSongCodec.text(song, "artist"));
        long candidate = 0;
        try {
            candidate = PlaylistImport.duration(MixedSongCodec.text(song, "durationText"), false);
        } catch (Exception ignored) {
        }
        double duration =
                source.duration > 0 && candidate > 0
                        ? Math.max(0, 1 - Math.abs(source.duration - candidate) / 10000d)
                        : .5;
        return .45 * title + .35 * artist + .20 * duration;
    }
}

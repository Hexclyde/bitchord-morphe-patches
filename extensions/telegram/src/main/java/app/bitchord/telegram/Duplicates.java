package app.bitchord.telegram;

import org.json.JSONObject;

import java.util.*;

/**
 * One copy of each song in search results. The same song posted twice, or in two zips, shows once,
 * as its best-sounding copy: lossless over lossy, then the higher bit depth, sample rate and
 * bitrate.
 */
final class Duplicates {
    private Duplicates() {}

    /** Format details already read for a file, or null. */
    interface Headers {
        JSONObject get(String id);
    }

    /** Lengths this close are the same recording; a file without one is never merged. */
    static final int SLACK_SEC = 2;

    static List<JSONObject> best(List<JSONObject> rows, Headers headers) {
        List<JSONObject> kept = new ArrayList<>();
        List<String> songs = new ArrayList<>();
        for (JSONObject row : rows) {
            String song = song(row);
            int seconds = row.optInt("duration");
            int same = -1;
            if (seconds > 0)
                for (int i = 0; i < kept.size(); i++) {
                    int other = kept.get(i).optInt("duration");
                    if (songs.get(i).equals(song)
                            && other > 0
                            && Math.abs(other - seconds) <= SLACK_SEC) {
                        same = i;
                        break;
                    }
                }
            if (same < 0) {
                kept.add(row);
                songs.add(song);
            } else if (compare(row, kept.get(same), headers) > 0) kept.set(same, row);
        }
        return kept;
    }

    static String song(JSONObject row) {
        return String.join(" ", TrackNames.words(row.optString("title")))
                + "|"
                + String.join(" ", TrackNames.words(row.optString("artist")));
    }

    /** Positive when {@code a} sounds better than {@code b}. */
    static int compare(JSONObject a, JSONObject b, Headers headers) {
        long[] x = rank(a, headers), y = rank(b, headers);
        for (int i = 0; i < x.length; i++) if (x[i] != y[i]) return Long.compare(x[i], y[i]);
        return 0;
    }

    /** {lossless, bit depth, sample rate, kbps}. */
    static long[] rank(JSONObject row, Headers headers) {
        JSONObject header = headers == null ? null : headers.get(row.optString("id"));
        if (header == null) header = row.optJSONObject("header");
        String codec = header != null ? header.optString("codec") : "";
        String format = DolbyHeader.dolby(codec) ? codec : row.optString("format");
        int bits = header != null ? header.optInt("bits") : row.optInt("bits");
        int rate = header != null ? header.optInt("rate") : row.optInt("rate");
        String quality =
                TrackDetails.quality(
                        format,
                        row.optString("mime"),
                        row.optLong("size"),
                        row.optInt("duration"),
                        bits);
        return new long[] {
            quality.contains("LOSSLESS") ? 1 : 0,
            bits,
            rate,
            TrackDetails.kbps(row.optLong("size"), row.optInt("duration"))
        };
    }
}

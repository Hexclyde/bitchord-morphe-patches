package app.bitchord.telegram;

import com.music.bitchord.data.NerdStats.Snapshot;
import com.music.bitchord.data.TelemetryProvenance;

import kotlin.Pair;
import kotlin.jvm.functions.Function0;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Extends the host's existing, track-scoped telemetry; performs no I/O in Compose. */
public final class PlaybackStats {
    private PlaybackStats() {}

    // R8 removed the provenance field, although the host still supplies its constructor
    // argument. Keep it by snapshot identity, never by a loose "last source" value.
    private static final Map<Snapshot, TelemetryProvenance> provenance =
            Collections.synchronizedMap(new WeakHashMap<Snapshot, TelemetryProvenance>());

    public static void capture(Snapshot snapshot, TelemetryProvenance origin) {
        if (snapshot == null) return;
        synchronized (provenance) {
            if (provenance.size() >= 64) provenance.clear();
            provenance.put(snapshot, origin == null ? TelemetryProvenance.UNKNOWN : origin);
        }
    }

    /**
     * Reuses the host's rendition-keyed asynchronous size/duration measurement. Never falls through
     * to an older YouTube bitrate after a lossless upgrade.
     */
    public static Pair<Integer, TelemetryProvenance> measureLossless(Function0<?> measure) {
        if (measure == null) return null;
        Object value = measure.invoke();
        if (!(value instanceof Integer) || ((Integer) value) <= 0) return null;
        return new Pair<>((Integer) value, TelemetryProvenance.MEASURED);
    }

    public static String decorate(String original, Snapshot snapshot) {
        if (snapshot == null) return original;
        StringBuilder format = new StringBuilder();
        if (original != null)
            for (String part : original.split(" · ")) {
                // Bitrate moves to the source line. Retain the host's localized format labels.
                if (part.matches("[0-9]+ kbps")) continue;
                if (!part.isEmpty()) {
                    if (format.length() > 0) format.append(" · ");
                    format.append(part);
                }
            }
        String source = sourceLabel(sourceOf(snapshot.sourceName, snapshot.mimeType));
        String rate =
                bitrateLabel(snapshot.bitrateKbps, snapshot.mimeType, provenance.get(snapshot));
        return (format.length() == 0 ? "" : format + "\n") + source + " · " + rate;
    }

    /**
     * YouTube never sends lossless or Dolby audio. When BitChord labels one "YouTube", it's a
     * better copy that replaced the stream, played again from BitChord's song cache, which keeps
     * the song's YouTube source: the Telegram copy, when Telegram is set up.
     */
    static String sourceOf(String source, String mime) {
        if (source == null || !source.trim().equalsIgnoreCase("YouTube") || !notFromYouTube(mime))
            return source;
        return TelegramBridge.instance != null
                ? Strings.t("Unofficial Telegram (saved copy)")
                : Strings.t("Saved copy");
    }

    static boolean notFromYouTube(String mime) {
        if (mime == null) return false;
        switch (mime.toLowerCase(java.util.Locale.ROOT)) {
            case "audio/flac":
            case "audio/x-flac":
            case "audio/alac":
            case "audio/raw":
            case "audio/wav":
            case "audio/x-wav":
            case "audio/ac3":
            case "audio/eac3":
            case "audio/eac3-joc":
            case "audio/ac4":
            case "audio/true-hd":
                return true;
            default:
                return false;
        }
    }

    static String sourceLabel(String source) {
        if (source == null) return "Unknown source";
        // Addon display names may contain arbitrary whitespace; keep exactly two lines.
        String clean = source.replaceAll("[\\p{Cc}\\p{Cf}\\s]+", " ").trim();
        if (clean.isEmpty()) return "Unknown source";
        int length = clean.codePointCount(0, clean.length());
        return length > 40 ? clean.substring(0, clean.offsetByCodePoints(0, 39)) + "…" : clean;
    }

    static String bitrateLabel(Integer kbps, String mime, TelemetryProvenance origin) {
        if (kbps == null || kbps <= 0) return "Bitrate unavailable";
        boolean pcm =
                "audio/raw".equals(mime) || "audio/wav".equals(mime) || "audio/x-wav".equals(mime);
        if (pcm) return kbps + " kbps PCM";
        if (origin == TelemetryProvenance.MEASURED) return "≈" + kbps + " kbps avg";
        if (origin == TelemetryProvenance.DERIVED) return "≈" + kbps + " kbps estimated";
        return kbps + " kbps";
    }
}

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

    private static final ThreadLocal<Observation> observation = new ThreadLocal<>();

    private static final class Observation {
        final androidx.media3.common.Player player;
        final androidx.media3.common.MediaItem item;
        final android.net.Uri uri;
        final Integer bitrate;

        Observation(androidx.media3.common.Player player) throws Exception {
            this.player = player;
            item = player.getCurrentMediaItem();
            uri =
                    item == null || item.localConfiguration == null
                            ? null
                            : SourceFirst.audibleUri(item.localConfiguration.uri);
            // Invoke the public interface: ExoPlayerImpl itself may be package-private.
            Object format =
                    Class.forName("androidx.media3.exoplayer.ExoPlayer")
                            .getMethod("getAudioFormat")
                            .invoke(player);
            Integer rate = null;
            if (format != null) {
                for (String field : new String[] {"averageBitrate", "bitrate"}) {
                    int bits = format.getClass().getField(field).getInt(format);
                    if (bits > 0) {
                        rate = (int) (((long) bits + 500) / 1000);
                        break;
                    }
                }
            }
            bitrate = rate;
        }
    }

    /** Runs at the beginning of the host's synchronous, generation-checked publication. */
    public static void observe(androidx.media3.common.Player player) {
        observation.remove();
        try {
            observation.set(new Observation(player));
        } catch (Exception unavailable) {
            /* Unbound metadata is not evidence of a source switch. */
        }
    }

    /** Every host panel receives this same immutable snapshot, before StateFlow publication. */
    public static Snapshot active(Snapshot snapshot) {
        Observation seen = observation.get();
        observation.remove();
        boolean stable = false;
        AudioOrigin.Source source = null;
        Integer bitrate = null;
        if (seen != null) {
            try {
                androidx.media3.common.MediaItem current = seen.player.getCurrentMediaItem();
                android.net.Uri uri =
                        current == null || current.localConfiguration == null
                                ? null
                                : SourceFirst.audibleUri(current.localConfiguration.uri);
                stable = current == seen.item && java.util.Objects.equals(uri, seen.uri);
                if (stable) {
                    bitrate = seen.bitrate;
                    source = AudioOrigin.activeSource(current);
                }
            } catch (Exception unavailable) {
                stable = false;
            }
        }
        return bound(snapshot, bitrate, source, stable);
    }

    /** Only the cache/file record for this rendition may supply advertised encoded metadata. */
    static Snapshot bound(
            Snapshot s, Integer encodedRate, AudioOrigin.Source source, boolean stable) {
        if (s == null) return null;
        if (!stable) {
            encodedRate = null;
            source = null;
        }
        boolean matches = source != null && compatible(s.mimeType, source.codec);
        TelemetryProvenance origin = provenance.get(s);
        Integer rate = null;
        if (encodedRate != null) {
            rate = encodedRate;
            origin = TelemetryProvenance.AUTHORITATIVE;
        } else if (stable && origin == TelemetryProvenance.MEASURED) {
            // The worker and its cache are keyed by the read-only audible URI.
            rate = s.bitrateKbps;
        } else if (matches && source.kbps != null && source.kbps > 0) {
            rate = source.kbps;
            origin = TelemetryProvenance.DERIVED;
        }
        if (rate == null) origin = TelemetryProvenance.UNKNOWN;
        boolean encodedDepth =
                "audio/flac".equals(s.mimeType)
                        || "audio/x-flac".equals(s.mimeType)
                        || "audio/alac".equals(s.mimeType)
                        || (s.mimeType != null && s.mimeType.startsWith("audio/dsd"));
        Integer bits =
                encodedDepth && matches && source.bits != null && source.bits > 0
                        ? source.bits
                        : null;
        if ("audio/raw".equals(s.mimeType)
                || "audio/wav".equals(s.mimeType)
                || "audio/x-wav".equals(s.mimeType)) bits = s.bitDepth;
        Snapshot clean =
                new Snapshot(
                        s.mimeType,
                        rate,
                        s.sampleRateHz,
                        s.channels,
                        bits,
                        null,
                        source == null ? "Unknown source" : source.name,
                        s.container,
                        origin,
                        s.pcmDataRateKbps,
                        null);
        capture(clean, origin);
        return clean;
    }

    static boolean compatible(String mime, String codec) {
        if (mime == null || codec == null) return false;
        String m = mime.toLowerCase(java.util.Locale.ROOT);
        String c = codec.toLowerCase(java.util.Locale.ROOT);
        if (c.equals("mp3")) c = "mpeg";
        if (c.equals("aac")) return m.equals("audio/mp4a-latm") || m.equals("audio/aac");
        return m.equals("audio/" + c) || m.equals("audio/x-" + c);
    }

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

    /** Codec alone cannot identify the provider of a cached copy. */
    static String sourceOf(String source, String mime) {
        if (source != null && source.trim().equalsIgnoreCase("YouTube") && notFromYouTube(mime))
            return "Unknown source";
        return source;
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

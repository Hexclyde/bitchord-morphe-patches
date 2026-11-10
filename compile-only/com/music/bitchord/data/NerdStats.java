package com.music.bitchord.data;

/** Public fields verified in the optimized BitChord 1.9 APK. Never packaged. */
public final class NerdStats {
    /** The format the chosen stream declared for a song, or null. */
    public static com.music.bitchord.data.sources.StreamFormat declaredFormat(String mediaId) {
        return null;
    }

    public static void onSourceStream(
            String mediaId,
            com.music.bitchord.data.sources.StreamFormat format,
            String sourceName) {}

    public static void recordSource(String mediaId, String sourceName) {}

    public static final class Snapshot {
        public final String mimeType, sourceName, container;
        public final Integer bitrateKbps, sampleRateHz, channels, bitDepth, pcmDataRateKbps;
        public final com.music.bitchord.data.sources.StreamFormat claimed;

        public Snapshot(String mime, Integer bitrate, String source) {
            this(
                    mime,
                    bitrate,
                    null,
                    null,
                    null,
                    null,
                    source,
                    null,
                    TelemetryProvenance.UNKNOWN,
                    null,
                    null);
        }

        public Snapshot(
                String mime,
                Integer bitrate,
                Integer rate,
                Integer channels,
                Integer depth,
                com.music.bitchord.data.sources.StreamFormat claimed,
                String source,
                String container,
                TelemetryProvenance provenance,
                Integer pcmRate,
                String mediaId) {
            mimeType = mime;
            bitrateKbps = bitrate;
            sourceName = source;
            sampleRateHz = rate;
            this.channels = channels;
            bitDepth = depth;
            this.claimed = claimed;
            this.container = container;
            pcmDataRateKbps = pcmRate;
        }
    }
}

package com.music.bitchord.data.sources;

public final class StreamRequest {
    public final com.music.bitchord.data.settings.DownloadQuality quality;

    public StreamRequest(com.music.bitchord.data.settings.DownloadQuality quality) {
        this.quality = quality;
    }

    /** The host's request kinds; the tier decides what a source offers. */
    public static final class Lossless {
        public static final Lossless INSTANCE = new Lossless();
    }

    public static final class Best {
        public static final Best INSTANCE = new Best();
    }
}

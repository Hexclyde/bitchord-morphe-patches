package com.music.bitchord.data.sources;

public final class StreamRequest {
    public final com.music.bitchord.data.settings.DownloadQuality quality;

    public StreamRequest(com.music.bitchord.data.settings.DownloadQuality quality) {
        this.quality = quality;
    }
}

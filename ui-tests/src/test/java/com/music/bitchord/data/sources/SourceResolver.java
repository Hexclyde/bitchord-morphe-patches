package com.music.bitchord.data.sources;

public final class SourceResolver {
    public static StreamRequest requestForDownload(
            com.music.bitchord.data.settings.DownloadQuality quality) {
        return new StreamRequest(quality);
    }
}

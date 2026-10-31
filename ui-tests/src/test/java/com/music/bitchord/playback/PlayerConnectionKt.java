package com.music.bitchord.playback;

public final class PlayerConnectionKt {
    public static com.music.bitchord.data.model.Song toSong(androidx.media3.common.MediaItem item) {
        return new com.music.bitchord.data.model.Song(item.mediaId, "Track", "Artist", "3:00");
    }
}

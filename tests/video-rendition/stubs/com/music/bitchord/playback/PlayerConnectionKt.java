package com.music.bitchord.playback;

import androidx.media3.common.MediaItem;

import com.music.bitchord.data.model.Song;

/** BitChord's conversion: a rendition survives it with its own identity and video flags only. */
public final class PlayerConnectionKt {
    public static MediaItem toMediaItem(Song song) {
        return new MediaItem(song);
    }

    public static Song toSong(MediaItem item) {
        Song song = (Song) item.song;
        return new Song(
                song.videoId,
                song.title,
                song.artist,
                null,
                song.durationText,
                null,
                null,
                null,
                null,
                song.isVideo,
                song.isVideoOrigin,
                null,
                com.music.bitchord.data.model.QueueTier.CONTEXT,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null);
    }
}

package com.music.bitchord.playback;

import android.os.Bundle;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;

public final class PlayerConnectionKt {
    /** Reads the extras BitChord 1.9 writes; items without them keep the fixed three-minute row. */
    public static com.music.bitchord.data.model.Song toSong(MediaItem item) {
        Bundle extras = item.mediaMetadata.extras;
        String duration = extras == null ? null : extras.getString("bitchord.durationText");
        return new com.music.bitchord.data.model.Song(
                item.mediaId, "Track", "Artist", duration == null ? "3:00" : duration);
    }

    /** The item BitChord 1.9 builds for a song: its id, location and the queue extras. */
    public static MediaItem toMediaItem(com.music.bitchord.data.model.Song song) {
        Bundle extras = new Bundle();
        extras.putString("bitchord.queueTier", song.queueTier.name());
        if (song.queueEntryId != null) extras.putString("bitchord.queueEntryId", song.queueEntryId);
        if (song.localUri != null) extras.putString("bitchord.localUri", song.localUri);
        if (song.durationText != null) extras.putString("bitchord.durationText", song.durationText);
        return new MediaItem.Builder()
                .setMediaId(song.videoId)
                .setUri(song.localUri != null ? song.localUri : song.videoId)
                .setMediaMetadata(
                        new MediaMetadata.Builder()
                                .setTitle(song.title)
                                .setArtist(song.artist)
                                .setExtras(extras)
                                .build())
                .build();
    }
}

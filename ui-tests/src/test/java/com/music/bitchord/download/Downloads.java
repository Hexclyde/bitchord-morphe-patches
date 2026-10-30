package com.music.bitchord.download;

import android.content.Context;

import com.music.bitchord.data.model.Song;

import java.util.ArrayList;
import java.util.List;

/** Stand-in for BitChord's download queue in the UI tests: a Kotlin object with an enqueue. */
public final class Downloads {
    public static final Downloads INSTANCE = new Downloads();
    public final List<String> queued = new ArrayList<>();

    private Downloads() {}

    public void enqueue(Context context, Song song, String from) {
        queued.add(from + ": " + song.videoId);
    }
}

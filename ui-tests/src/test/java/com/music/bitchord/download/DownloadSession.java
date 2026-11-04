package com.music.bitchord.download;

import com.music.bitchord.data.model.Song;

import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

import java.util.ArrayList;
import java.util.List;

/** The native session keeps full request metadata after a failed or queued transfer. */
public final class DownloadSession {
    public static final MutableStateFlow<State> _state = StateFlowKt.MutableStateFlow(new State());

    public static final class State {
        public final List<Item> items = new ArrayList<>();
    }

    public static final class Item {
        public final String videoId;
        public final Song song;
        public final String from;

        public Item(Song song, String from) {
            this.videoId = song.videoId;
            this.song = song;
            this.from = from;
        }
    }
}

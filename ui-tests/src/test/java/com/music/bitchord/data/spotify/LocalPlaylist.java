package com.music.bitchord.data.spotify;

import java.util.List;

public final class LocalPlaylist {
    public final String id, title;
    public final List<Object> songs;

    public LocalPlaylist(String id, String title, List<Object> songs) {
        this.id = id;
        this.title = title;
        this.songs = songs;
    }
}

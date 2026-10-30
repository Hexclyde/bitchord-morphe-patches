package com.music.bitchord.data.model;

/** Stand-in for BitChord's Song in the UI tests: the fields the patches read. */
public final class Song {
    public final String videoId, title, artist, durationText;

    public Song(String videoId, String title, String artist, String durationText) {
        this.videoId = videoId;
        this.title = title;
        this.artist = artist;
        this.durationText = durationText;
    }
}

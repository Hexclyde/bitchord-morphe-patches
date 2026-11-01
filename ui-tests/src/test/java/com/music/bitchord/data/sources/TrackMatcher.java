package com.music.bitchord.data.sources;

import com.music.bitchord.data.model.Song;

import java.util.List;

/** Recording matcher interface fixture; integration checks qualify the real host matcher. */
public final class TrackMatcher {
    public static final class Target {
        final String title, artist;

        public Target() {
            this("", "", null, null, null, false);
        }

        public Target(
                String title,
                String artist,
                Integer duration,
                String album,
                Boolean explicit,
                boolean video) {
            this.title = title;
            this.artist = artist;
        }
    }

    public static Song best(List<Song> songs, Target target) {
        for (Song song : songs)
            if (song.title.equalsIgnoreCase(target.title)
                    && song.artist.equalsIgnoreCase(target.artist)) return song;
        return null;
    }
}

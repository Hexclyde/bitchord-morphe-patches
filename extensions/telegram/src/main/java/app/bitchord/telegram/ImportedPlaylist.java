package app.bitchord.telegram;

import java.util.List;

final class ImportedPlaylist {
    final String title;
    final List<Object> songs;
    final int unavailable;

    ImportedPlaylist(String title, List<Object> songs, int unavailable) {
        this.title = title;
        this.songs = songs;
        this.unavailable = unavailable;
    }
}

package app.bitchord.soulseek;

final class SoulseekTrack {
    final String id, path;
    final long size;

    SoulseekTrack(String id, long size) {
        this(id, size, "peer/folder/song.flac");
    }

    SoulseekTrack(String id, long size, String path) {
        this.id = id;
        this.size = size;
        this.path = path;
    }
}

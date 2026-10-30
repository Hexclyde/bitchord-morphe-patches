package app.bitchord.soulseek;

final class SoulseekTrack {
    final String id, path;
    final long size;

    SoulseekTrack(String id, long size) {
        this.id = id;
        this.size = size;
        path = "peer/folder/song.flac";
    }
}

package androidx.media3.common;

public final class MediaItem {
    public final Object song;
    public final String mediaId = null;
    public final MediaMetadata mediaMetadata = null;
    public final LocalConfiguration localConfiguration = null;

    public MediaItem(Object song) {
        this.song = song;
    }

    public static final class LocalConfiguration {
        public final android.net.Uri uri = null;
    }
}

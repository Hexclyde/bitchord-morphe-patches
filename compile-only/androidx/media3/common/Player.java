package androidx.media3.common;

/** Compile-only host signatures; verified against every patched APK. */
public interface Player {
    MediaItem getCurrentMediaItem();

    boolean isPlaying();

    long getDuration();

    void replaceMediaItem(int index, MediaItem mediaItem);

    void seekTo(int mediaItemIndex, long positionMs);

    void prepare();
}

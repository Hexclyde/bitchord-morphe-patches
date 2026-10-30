package androidx.media3.common;

/** Compile-only host signatures; verified against every patched APK. */
public interface Player {
    int STATE_BUFFERING = 2;
    int STATE_READY = 3;
    int STATE_ENDED = 4;
    int MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED = 3;

    /** The events the patches listen to; Media3 gives each a default body. */
    interface Listener {
        default void onMediaItemTransition(MediaItem mediaItem, int reason) {}

        default void onIsPlayingChanged(boolean isPlaying) {}

        default void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {}
    }

    MediaItem getCurrentMediaItem();

    boolean isPlaying();

    boolean getPlayWhenReady();

    long getDuration();

    long getCurrentPosition();

    void replaceMediaItem(int index, MediaItem mediaItem);

    void seekTo(int mediaItemIndex, long positionMs);

    void prepare();

    int getCurrentMediaItemIndex();

    int getMediaItemCount();

    MediaItem getMediaItemAt(int index);

    void moveMediaItems(int fromIndex, int toIndex, int newIndex);

    void addListener(Listener listener);

    android.os.Looper getApplicationLooper();

    PlaybackParameters getPlaybackParameters();

    int getPlaybackState();

    void setPlaybackParameters(PlaybackParameters playbackParameters);

    void pause();

    float getVolume();

    void setVolume(float volume);
}

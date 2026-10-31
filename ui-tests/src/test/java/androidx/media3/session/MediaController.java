package androidx.media3.session;

/** Only the controller entry exercised by the overlay; checked against the host APK. */
public class MediaController {
    public androidx.media3.common.MediaItem item;

    public androidx.media3.common.MediaItem getCurrentMediaItem() {
        return item;
    }
}

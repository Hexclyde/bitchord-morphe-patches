package com.music.bitchord.playback;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;

/** Compile-only host signature; the method is added by the patch. Never packaged. */
public final class PlaybackService {
    public static android.media.MediaDataSource bitchordAutomixCached(android.net.Uri uri) {
        return null;
    }

    public static androidx.media3.datasource.DataSource bitchordAutomixSource() {
        return null;
    }

    public static androidx.media3.datasource.DataSpec bitchordAutomixResolve(
            Object service, android.net.Uri uri) {
        return null;
    }

    public static androidx.media3.datasource.DataSpec bitchordAutomixResolveWith(
            Object resolver, androidx.media3.datasource.DataSpec spec) {
        return null;
    }

    public static String bitchordAutomixKey(android.net.Uri uri) {
        return null;
    }

    public static float bitchordAutomixSpeed() {
        return 1f;
    }

    public static boolean bitchordAutomixStopped() {
        return false;
    }

    public static boolean bitchordAutomixEnabled() {
        return false;
    }

    public static void bitchordAutomixRestore(Object analyzer, String key) {}

    /** Starts BitChord's two-player version swap; false when it can't run right now. */
    public static boolean bitchordSmoothSwap(
            Object service, Player player, int index, MediaItem target) {
        return false;
    }
}

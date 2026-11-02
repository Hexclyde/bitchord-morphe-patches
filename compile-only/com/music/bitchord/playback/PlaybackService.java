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

    public static kotlin.coroutines.CoroutineContext bitchordPreparedContext(
            String token, ThreadLocal<?> local) {
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

    public static Object bitchordDspDecode(
            android.media.MediaDataSource source,
            double endSeconds,
            kotlin.jvm.functions.Function0<Boolean> abort,
            kotlin.jvm.functions.Function4<
                            java.nio.ByteBuffer,
                            android.media.MediaCodec.BufferInfo,
                            Integer,
                            Integer,
                            kotlin.Unit>
                    onBuffer) {
        return null;
    }

    public static float[] bitchordDspMono(
            java.nio.ByteBuffer buffer, android.media.MediaCodec.BufferInfo info, int channels) {
        return null;
    }

    public static Object bitchordDspFeatures(float[] samples, double duration) {
        return null;
    }

    public static Object bitchordDspResult(Object features, String id, double actualDuration) {
        return null;
    }

    public static boolean bitchordAutomixRealMix() {
        return false;
    }

    /** Starts BitChord's two-player version swap; false when it can't run right now. */
    public static boolean bitchordSmoothSwap(
            Object service, Player player, int index, MediaItem target) {
        return false;
    }
}

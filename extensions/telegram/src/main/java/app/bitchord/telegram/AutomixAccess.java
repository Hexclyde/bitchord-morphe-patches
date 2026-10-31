package app.bitchord.telegram;

import android.net.Uri;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;

import com.music.bitchord.playback.PlaybackService;

/** Host bridges are linked against the optimized APK, without reflective interface guesses. */
final class AutomixAccess {
    static android.media.MediaDataSource cached(Uri uri) {
        return PlaybackService.bitchordAutomixCached(uri);
    }

    static DataSource source() {
        return PlaybackService.bitchordAutomixSource();
    }

    static DataSpec resolve(Object service, Uri uri) {
        return PlaybackService.bitchordAutomixResolve(service, uri);
    }

    static DataSpec resolveWith(Object resolver, DataSpec spec) {
        return PlaybackService.bitchordAutomixResolveWith(resolver, spec);
    }

    static String key(Uri uri) {
        return PlaybackService.bitchordAutomixKey(uri);
    }

    static float speed() {
        return PlaybackService.bitchordAutomixSpeed();
    }

    static boolean stopped() {
        return PlaybackService.bitchordAutomixStopped();
    }

    static boolean enabled() {
        return PlaybackService.bitchordAutomixEnabled();
    }

    static void restore(Object analyzer, String key) {
        PlaybackService.bitchordAutomixRestore(analyzer, key);
    }
}

package app.bitchord.telegram;

import android.net.Uri;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;

import com.music.bitchord.playback.PlaybackService;

import java.io.IOException;

/** Host bridges are linked against the optimized APK, without reflective interface guesses. */
final class AutomixAccess {
    static android.media.MediaDataSource cached(Uri uri) {
        return PlaybackService.bitchordAutomixCached(uri);
    }

    static DataSource source() {
        return PlaybackService.bitchordAutomixSource();
    }

    static kotlin.coroutines.CoroutineContext context(String token, ThreadLocal<?> local)
            throws IOException {
        try {
            kotlin.coroutines.CoroutineContext context =
                    PlaybackService.bitchordPreparedContext(token, local);
            if (context == null)
                throw new IOException("Background preparation context is unavailable");
            return context;
        } catch (LinkageError changed) {
            throw new IOException("Background preparation context is unavailable", changed);
        }
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

    static String mixLabel(String original) {
        if (PlaybackService.bitchordAutomixRealMix()) return original;
        Object owner = SourceAutomix.owner();
        if (owner instanceof android.content.Context) {
            android.content.Context context = (android.content.Context) owner;
            int id =
                    context.getResources()
                            .getIdentifier("crossfade", "string", context.getPackageName());
            if (id == 0)
                id =
                        context.getResources()
                                .getIdentifier("crossfade", "string", "com.music.bitchord");
            if (id != 0) return context.getString(id);
        }
        return original;
    }
}

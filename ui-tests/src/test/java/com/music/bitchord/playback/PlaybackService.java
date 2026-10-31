package com.music.bitchord.playback;

import android.net.Uri;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;

import java.util.function.Function;

/** Test stand-in for the APK-verified host bridges. */
public final class PlaybackService {
    public static android.media.MediaDataSource bitchordAutomixCached(Uri uri) {
        return null;
    }

    public static DataSource.Factory source;
    public static Function<Uri, DataSpec> resolver;
    public static float selectedSpeed = 1f;
    public static boolean stopped;
    public static String keySuffix = "";

    public static DataSource bitchordAutomixSource() {
        return source == null ? null : source.createDataSource();
    }

    public static DataSpec bitchordAutomixResolve(Object service, Uri uri) {
        return resolver == null ? new DataSpec(uri) : resolver.apply(uri);
    }

    public static DataSpec bitchordAutomixResolveWith(Object resolver, DataSpec spec) {
        return spec;
    }

    public static String bitchordAutomixKey(Uri uri) {
        return uri.toString() + keySuffix;
    }

    public static float bitchordAutomixSpeed() {
        return selectedSpeed;
    }

    public static boolean bitchordAutomixStopped() {
        return stopped;
    }

    public static boolean bitchordAutomixEnabled() {
        return true;
    }

    public static void bitchordAutomixRestore(Object analyzer, String key) {}
}

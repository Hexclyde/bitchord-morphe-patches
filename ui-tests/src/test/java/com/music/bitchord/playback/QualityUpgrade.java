package com.music.bitchord.playback;

import android.net.Uri;

/** Test stand-in for the APK-verified parked-stream lookup. */
public final class QualityUpgrade {
    public static volatile boolean parked = true;

    public static Object forcedStream(Uri uri) {
        return parked ? new Object() : null;
    }
}

package com.music.bitchord.download;

import com.google.android.gms.tasks.zzr;

import kotlin.coroutines.jvm.internal.SuspendLambda;
import kotlin.jvm.functions.Function3;

/** Route ABI from the final BitChord 1.9 release APK. */
public final class Downloads {
    public static boolean rejectConstructor;

    public static final class Route {
        public final String extension, mimeType, describe, downloadFormat;
        public final zzr offlineHls;
        public final SuspendLambda write;

        public Route(
                String extension,
                String mimeType,
                String describe,
                String downloadFormat,
                zzr offlineHls,
                Function3<?, ?, ?, ?> write,
                int mask) {
            if (rejectConstructor) throw new IllegalStateException("Fixture constructor failure");
            if (mask != 0) throw new AssertionError("Explicit route requires zero default mask");
            this.extension = extension;
            this.mimeType = mimeType;
            this.describe = describe;
            this.downloadFormat = downloadFormat;
            this.offlineHls = offlineHls;
            this.write = (SuspendLambda) write;
        }
    }
}

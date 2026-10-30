package com.music.bitchord.download;

import com.google.android.gms.tasks.zzr;

import kotlin.coroutines.jvm.internal.SuspendLambda;
import kotlin.jvm.functions.Function3;

public final class Downloads {
    public static boolean rejectConstructor;

    public static final class Route {
        public final String extension, mimeType, describe, downloadFormat;
        public final zzr offlineHls;
        public final SuspendLambda write;
        private final boolean taggable;

        public Route(
                String extension,
                String mimeType,
                String describe,
                String downloadFormat,
                zzr offlineHls,
                Function3<?, ?, ?, ?> write) {
            if (rejectConstructor) throw new IllegalStateException("Fixture constructor failure");
            this.extension = extension;
            this.mimeType = mimeType;
            this.describe = describe;
            this.downloadFormat = downloadFormat;
            this.offlineHls = offlineHls;
            this.write = (SuspendLambda) write;
            this.taggable = true;
        }

        public boolean taggable() {
            return taggable;
        }
    }
}

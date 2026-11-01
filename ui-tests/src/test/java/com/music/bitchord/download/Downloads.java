package com.music.bitchord.download;

import android.content.Context;

import com.music.bitchord.data.model.Song;

import java.util.ArrayList;
import java.util.List;

/** Stand-in for BitChord's download queue in the UI tests: a Kotlin object with an enqueue. */
public final class Downloads {
    public static final Downloads INSTANCE = new Downloads();
    public static final List<String> queued = new ArrayList<>();

    public static final kotlinx.coroutines.flow.MutableStateFlow<java.util.Map<String, String>>
            _saved =
                    kotlinx.coroutines.flow.StateFlowKt.MutableStateFlow(new java.util.HashMap<>());
    public static int scheduledCalls, scheduledMode;
    public static kotlin.coroutines.CoroutineContext scheduledContext;

    public java.io.Serializable downloadNow$app_prodRelease(
            Context context,
            java.util.ArrayList<Song> songs,
            String from,
            kotlin.coroutines.jvm.internal.ContinuationImpl continuation) {
        scheduledCalls++;
        scheduledContext = continuation.getContext();
        if (scheduledMode == 2)
            return kotlin.coroutines.intrinsics.CoroutineSingletons.COROUTINE_SUSPENDED;
        java.util.Set<String> result = new java.util.HashSet<>();
        if (scheduledMode == 0) for (Song song : songs) result.add(song.videoId);
        return (java.io.Serializable) result;
    }

    private Downloads() {}

    public static void enqueue(Context context, Song song, String from) {
        queued.add(from + ": " + song.videoId);
    }
}

package com.music.bitchord.data.sources;

/** Exact source contract used by the scheduled catalogue resolver. */
public interface MusicSource {
    String getConfigId();

    SourceKind getKind();

    Object search(
            String query,
            int limit,
            boolean waitForAll,
            StreamRequest request,
            kotlin.coroutines.Continuation<Object> step);
}

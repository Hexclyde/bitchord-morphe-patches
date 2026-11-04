package com.music.bitchord.data.sources;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SourceResolver {
    public static final SourceResolver INSTANCE = new SourceResolver();

    /** What a test's providers have, by request kind: "Lossless" and "Best". */
    public static final Map<String, Object> offers = new ConcurrentHashMap<>();

    public static volatile Object request = StreamRequest.Lossless.INSTANCE;
    public static volatile long delayMs;
    public static final List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static StreamRequest requestForDownload(
            com.music.bitchord.data.settings.DownloadQuality quality) {
        return new StreamRequest(quality);
    }

    public static Object requestForNow() {
        return request;
    }

    public Object substituteForYouTube(
            TrackMatcher.Target target, kotlin.coroutines.jvm.internal.ContinuationImpl step)
            throws InterruptedException {
        calls.add("substitute");
        Thread.sleep(delayMs);
        return offers.get(request.getClass().getSimpleName());
    }

    public Object forDownloadOfRow(
            String configId,
            String trackId,
            String sourceQuality,
            Object request,
            kotlin.coroutines.jvm.internal.ContinuationImpl continuation) {
        calls.add(
                "row:"
                        + configId
                        + ":"
                        + trackId
                        + ":"
                        + sourceQuality
                        + ":"
                        + request.getClass().getSimpleName());
        return offers.get("row");
    }

    public static Object bestAcross$app_prodRelease$default(
            SourceResolver self,
            List<?> sources,
            TrackMatcher.Target target,
            Object asked,
            boolean a,
            int b,
            Object c,
            Long d,
            Object e,
            kotlin.coroutines.jvm.internal.ContinuationImpl step,
            int mask) {
        calls.add("best:" + asked.getClass().getSimpleName() + ":" + mask + ":" + sources);
        return offers.get(asked.getClass().getSimpleName());
    }
}

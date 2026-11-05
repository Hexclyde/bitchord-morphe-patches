package com.music.bitchord.playback;

import android.net.Uri;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;

import java.util.function.Function;

/** Test stand-in for the APK-verified host bridges. */
public final class PlaybackService {
    public interface WaveDecoder {
        Object decode(
                android.media.MediaDataSource source,
                double from,
                double to,
                kotlin.jvm.functions.Function0<Boolean> abort,
                kotlin.jvm.functions.Function4<
                                java.nio.ByteBuffer,
                                android.media.MediaCodec.BufferInfo,
                                Integer,
                                Integer,
                                kotlin.Unit>
                        onBuffer);
    }

    public static WaveDecoder waveDecoder;

    public static Object bitchordWaveDecode(
            android.media.MediaDataSource source,
            double from,
            double to,
            kotlin.jvm.functions.Function0<Boolean> abort,
            kotlin.jvm.functions.Function4<
                            java.nio.ByteBuffer,
                            android.media.MediaCodec.BufferInfo,
                            Integer,
                            Integer,
                            kotlin.Unit>
                    onBuffer) {
        return waveDecoder == null ? null : waveDecoder.decode(source, from, to, abort, onBuffer);
    }

    public static boolean realMix;

    public static boolean bitchordAutomixRealMix() {
        return realMix;
    }

    public interface DspDecoder {
        Object decode(
                android.media.MediaDataSource source,
                double end,
                kotlin.jvm.functions.Function0<Boolean> abort,
                kotlin.jvm.functions.Function4<
                                java.nio.ByteBuffer,
                                android.media.MediaCodec.BufferInfo,
                                Integer,
                                Integer,
                                kotlin.Unit>
                        chunks);
    }

    public static DspDecoder dspDecoder;
    public static java.util.function.BiFunction<float[], Double, Object> dspFeatures;

    public static Object bitchordDspDecode(
            android.media.MediaDataSource source,
            double end,
            kotlin.jvm.functions.Function0<Boolean> abort,
            kotlin.jvm.functions.Function4<
                            java.nio.ByteBuffer,
                            android.media.MediaCodec.BufferInfo,
                            Integer,
                            Integer,
                            kotlin.Unit>
                    chunks) {
        return dspDecoder == null ? null : dspDecoder.decode(source, end, abort, chunks);
    }

    public static float[] bitchordDspMono(
            java.nio.ByteBuffer buffer, android.media.MediaCodec.BufferInfo info, int channels) {
        java.nio.ByteBuffer pcm = buffer.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN);
        pcm.position(info.offset);
        pcm.limit(info.offset + info.size);
        float[] mono = new float[info.size / (channels * 2)];
        for (int i = 0; i < mono.length; i++) {
            float sum = 0;
            for (int c = 0; c < channels; c++) sum += pcm.getShort() / 32768f;
            mono[i] = sum / channels;
        }
        return mono;
    }

    public static Object bitchordDspFeatures(float[] mono, double seconds) {
        return dspFeatures == null ? null : dspFeatures.apply(mono, seconds);
    }

    public static Object bitchordDspResult(Object features, String id, double seconds) {
        return features == null ? null : new com.music.bitchord.playback.smart.TrackAnalysis(id);
    }

    public static Function<Uri, android.media.MediaDataSource> cached;

    public static android.media.MediaDataSource bitchordAutomixCached(Uri uri) {
        return cached == null ? null : cached.apply(uri);
    }

    public static DataSource.Factory source;
    public static Function<Uri, DataSpec> resolver;
    public static float selectedSpeed = 1f;
    public static boolean stopped;
    public static String keySuffix = "";

    public static DataSource bitchordAutomixSource() {
        return source == null ? null : source.createDataSource();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static kotlin.coroutines.CoroutineContext bitchordPreparedContext(
            String token, ThreadLocal<?> local) {
        return new kotlinx.coroutines.internal.ThreadLocalElement(token, local);
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

    public static java.util.function.BiConsumer<Object, String> restorer;

    public static void bitchordAutomixRestore(Object analyzer, String key) {
        if (restorer != null) restorer.accept(analyzer, key);
    }
}

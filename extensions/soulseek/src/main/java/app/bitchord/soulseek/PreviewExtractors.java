package app.bitchord.soulseek;

import app.bitchord.telegram.PatchStrings;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Locale;

/** Adapts decoded PCM to the exact Media3 interfaces retained by the host's optimiser. */
final class PreviewExtractors {
    private final SoulseekPreview.Session session;
    volatile String description = "";

    PreviewExtractors(SoulseekPreview.Session session) {
        this.session = session;
    }

    Object factory() throws Exception {
        Class<?> type = Class.forName("androidx.media3.extractor.ExtractorsFactory");
        return Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] {type},
                (proxy, method, args) -> {
                    if (!method.getName().equals("createExtractors"))
                        return objectMethod(proxy, method.getName(), args);
                    Class<?> extractor = Class.forName("androidx.media3.extractor.Extractor");
                    Object result = Array.newInstance(extractor, 1);
                    Array.set(result, 0, new Decoder().proxy(extractor));
                    return result;
                });
    }

    // Replaying a bounded preview avoids depending on container seek indexes or unreceived tails.
    static int framesToSkip(long timeUs, long requestedUs, int sampleRate, int frames) {
        if (requestedUs <= timeUs) return 0;
        return (int)
                Math.min(frames, ((requestedUs - timeUs) * sampleRate + 999_999L) / 1_000_000L);
    }

    static int framesBeforeLimit(long timeUs, int sampleRate, int frames) {
        return (int) Math.min(frames, Math.max(0, 30_000_000L - timeUs) * sampleRate / 1_000_000L);
    }

    static Object seekMap(long duration, boolean estimated) throws Exception {
        Class<?> type = Class.forName("androidx.media3.extractor.SeekMap");
        return Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] {type},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getDurationUs":
                            return duration;
                        case "isSeekable":
                            return true;
                        case "isEstimated":
                            return estimated;
                        case "getSeekPoints":
                            Class<?> pointType =
                                    Class.forName("androidx.media3.extractor.SeekPoint");
                            Object point =
                                    pointType
                                            .getConstructor(long.class, long.class)
                                            .newInstance(
                                                    Math.max(0, Math.min((long) args[0], duration)),
                                                    0L);
                            return Class.forName("androidx.media3.extractor.SeekMap$SeekPoints")
                                    .getConstructor(pointType, pointType)
                                    .newInstance(point, point);
                        default:
                            return objectMethod(proxy, method.getName(), args);
                    }
                });
    }

    private static Object objectMethod(Object proxy, String name, Object[] args) {
        if (name.equals("equals")) return proxy == args[0];
        if (name.equals("hashCode")) return System.identityHashCode(proxy);
        if (name.equals("toString")) return "Streamseek audio extractor";
        return null;
    }

    private final class Decoder {
        NativePreviewAudio input;
        long handle, requestedUs;
        Object output, track;
        Class<?> outputType, trackType;
        int channels, sampleRate;
        boolean end;

        Object proxy(Class<?> type) {
            return Proxy.newProxyInstance(
                    type.getClassLoader(),
                    new Class<?>[] {type},
                    (proxy, method, args) -> {
                        try {
                            switch (method.getName()) {
                                case "getUnderlyingImplementation":
                                    return proxy;
                                case "getSniffFailureDetails":
                                    return java.util.Collections.emptyList();
                                case "sniff":
                                    return true;
                                case "init":
                                    output = args[0];
                                    return null;
                                case "seek":
                                    requestedUs = Math.max(0, (long) args[1]);
                                    end = false;
                                    if (handle != 0) release();
                                    return null;
                                case "read":
                                    return read();
                                case "release":
                                    release();
                                    return null;
                                default:
                                    return objectMethod(proxy, method.getName(), args);
                            }
                        } catch (InvocationTargetException failed) {
                            throw failed.getCause();
                        }
                    });
        }

        private void start() throws Exception {
            try {
                input = new NativePreviewAudio(session);
                handle = NativePreviewAudio.open(input);
                if (handle == 0) throw new IOException("Could not open preview decoder");
                long[] info = NativePreviewAudio.info(handle);
                sampleRate = (int) info[0];
                channels = (int) info[1];
                String codec = NativePreviewAudio.codec(handle).toUpperCase(Locale.ROOT);
                description =
                        PatchStrings.f(
                                "Decoded: {0} · {1} Hz · {2} channels", codec, info[0], info[1]);
                Class<?> builderType = Class.forName("androidx.media3.common.Format$Builder");
                Object builder = builderType.getConstructor().newInstance();
                builderType.getField("sampleMimeType").set(builder, "audio/raw");
                builderType.getField("sampleRate").setInt(builder, (int) info[0]);
                builderType.getField("channelCount").setInt(builder, channels);
                builderType.getField("pcmEncoding").setInt(builder, 0x30000000);
                Object format = builderType.getMethod("build").invoke(builder);
                outputType = Class.forName("androidx.media3.extractor.ExtractorOutput");
                trackType = Class.forName("androidx.media3.extractor.TrackOutput");
                track = outputType.getMethod("track", int.class, int.class).invoke(output, 0, 1);
                trackType
                        .getMethod("format", Class.forName("androidx.media3.common.Format"))
                        .invoke(track, format);
                outputType.getMethod("endTracks").invoke(output);
                long duration = info[3] > 0 ? Math.min(30_000_000L, info[3]) : 30_000_000L;
                Class<?> seekType = Class.forName("androidx.media3.extractor.SeekMap");
                outputType
                        .getMethod("seekMap", seekType)
                        .invoke(output, seekMap(duration, info[3] <= 0));

            } catch (Exception | LinkageError failed) {
                release();
                throw new IOException(
                        "This file could not be decoded. Some containers need their end buffered"
                                + " first, or use an unsupported codec.",
                        failed);
            }
        }

        private int read() throws Exception {
            session.check();
            if (end) return -1;
            if (handle == 0) start();
            byte[] pcm = NativePreviewAudio.next(handle);
            if (pcm == null) {
                end = true;
                return -1;
            }
            long time = Math.max(0, NativePreviewAudio.time(handle));
            if (time >= 30_000_000L) {
                end = true;
                return -1;
            }
            if (pcm.length == 0) return 0;
            int skip = framesToSkip(time, requestedUs, sampleRate, pcm.length / (channels * 4));
            if (skip == pcm.length / (channels * 4)) return 0;
            if (skip > 0) {
                pcm = java.util.Arrays.copyOfRange(pcm, skip * channels * 4, pcm.length);
                time += skip * 1_000_000L / sampleRate;
            }
            int allowed = framesBeforeLimit(time, sampleRate, pcm.length / (channels * 4));
            if (allowed == 0) {
                end = true;
                return -1;
            }
            if (allowed < pcm.length / (channels * 4)) {
                pcm = java.util.Arrays.copyOf(pcm, allowed * channels * 4);
                end = true;
            }
            Class<?> bytesType = Class.forName("androidx.media3.common.util.ParsableByteArray");
            Object bytes = bytesType.getConstructor(byte[].class).newInstance((Object) pcm);
            trackType
                    .getMethod("sampleData", bytesType, int.class, int.class)
                    .invoke(track, bytes, pcm.length, 0);
            trackType
                    .getMethod(
                            "sampleMetadata",
                            long.class,
                            int.class,
                            int.class,
                            int.class,
                            Class.forName("androidx.media3.extractor.TrackOutput$CryptoData"))
                    .invoke(track, time, 1, pcm.length, 0, null);
            return 0;
        }

        private void release() {
            if (handle != 0) {
                NativePreviewAudio.close(handle);
                handle = 0;
            }
            if (input != null) {
                input.close();
                input = null;
            }
        }
    }
}

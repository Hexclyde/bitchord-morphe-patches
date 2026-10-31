package app.bitchord.telegram;

import android.media.MediaCodec;
import android.media.MediaDataSource;
import android.net.Uri;

import androidx.media3.datasource.DataSpec;

import kotlin.Pair;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function4;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Verifies a resolver's automatic substitutions before their bytes enter the player's cache. */
public final class SourcePreference {
    private SourcePreference() {}

    private static final ThreadLocal<Boolean> verifying = new ThreadLocal<>();
    private static final Map<String, Boolean> decisions = new ConcurrentHashMap<>();
    private static final Map<String, Double> offsets = new ConcurrentHashMap<>();
    private static final Map<String, double[]> pendingOffsets = new ConcurrentHashMap<>();
    private static final long BUDGET = 45_000_000_000L;

    /**
     * Preserve relative provider order and enabled-source filtering, keeping YouTube as fallback.
     */
    public static ArrayList<Object> rank(ArrayList<?> sources) {
        ArrayList<Object> result = new ArrayList<>(), youtube = new ArrayList<>();
        for (Object source : sources) {
            boolean fallback = false;
            try {
                Method method = source.getClass().getMethod("getKind");
                method.setAccessible(true);
                Object kind = method.invoke(source);
                fallback = kind instanceof Enum && ((Enum<?>) kind).name().equals("YOUTUBE");
            } catch (Exception ignored) {
            }
            (fallback ? youtube : result).add(source);
        }
        result.addAll(youtube);
        return result;
    }

    /**
     * Equal measured format is enough to prefer an alternative; the resolver still verifies
     * identity.
     */
    public static Boolean equalUpgrade(Object callback, Object provider, Object stream) {
        if (stream == null
                || !stream.getClass()
                        .getName()
                        .equals("com.music.bitchord.data.sources.SourceStream")) return null;
        try {
            Object playing = SourceAutomix.field(callback, "f$0");
            Object target = SourceAutomix.field(callback, "f$1");
            if (playing == null
                    || target == null
                    || !target.getClass().getName().endsWith("TrackMatcher$Target")) return null;
            Object candidate = SourceAutomix.field(stream, "format");
            Object codec = SourceAutomix.field(candidate, "codec"),
                    other = SourceAutomix.field(playing, "codec");
            Object rate = SourceAutomix.field(candidate, "kbps"),
                    previous = SourceAutomix.field(playing, "kbps");
            if (!(codec instanceof String)
                    || !codec.equals(other)
                    || !(rate instanceof Number)
                    || ((Number) rate).intValue() <= 0
                    || !rate.equals(previous)) return null;
            for (String field : new String[] {"bitDepth", "sampleRateHz"})
                if (!Objects.equals(
                        SourceAutomix.field(candidate, field), SourceAutomix.field(playing, field)))
                    return null;
            return Boolean.TRUE;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static DataSpec resolve(Object resolver, DataSpec requested) throws IOException {
        return guard(resolver, requested, AutomixAccess.resolveWith(resolver, requested));
    }

    public static DataSpec guard(Object resolver, DataSpec requested, DataSpec resolved)
            throws IOException {
        return SourceAutomix.ownsResolver(resolver) ? guard(requested, resolved) : resolved;
    }

    public static DataSpec guard(DataSpec requested, DataSpec resolved) throws IOException {
        if (Boolean.TRUE.equals(verifying.get()) || requested == null || resolved == null)
            return resolved;
        Uri original = requested.uri, candidate = resolved.uri;
        if (original == null
                || candidate == null
                || !original.isHierarchical()
                || original.equals(candidate)) return resolved;
        String video = original.getQueryParameter("v");
        boolean source = "source".equals(original.getAuthority());
        String mediaId =
                video != null
                        ? video
                        : source
                                ? "src:"
                                        + original.getQueryParameter("s")
                                        + "::"
                                        + original.getQueryParameter("t")
                                : null;
        // Explicitly picked rows and direct file/HTTP playback are already the requested recording.
        if (video == null && (!source || original.getQueryParameter("mix") != null))
            return resolved;
        if ("1".equals(original.getQueryParameter("automix_opus"))
                || "1".equals(original.getQueryParameter("direct_youtube"))) return resolved;
        String host = candidate.getHost();
        if (video != null
                && host != null
                && (host.equals("googlevideo.com") || host.endsWith(".googlevideo.com")))
            return resolved;
        Uri played = mediaId == null ? null : SourceAutomix.observed(mediaId);
        String key =
                original.toString()
                        + "\n"
                        + candidate.toString()
                        + "\n"
                        + (played == null ? "" : played.toString());
        Boolean known = decisions.get(key);
        if (Boolean.TRUE.equals(known)) {
            Double offset = offsets.get(key);
            if (mediaId != null && offset != null)
                pendingOffsets.put(
                        mediaId, new double[] {offset * 1000, System.currentTimeMillis()});
            return resolved;
        }
        if (Boolean.FALSE.equals(known))
            throw new IOException("Alternative audio did not verify as the same recording");
        verifying.set(true);
        SourceAutomix.enterQuiet();
        boolean conclusive = false;
        try {
            Uri reference =
                    video != null
                            ? Uri.parse(
                                    "bitchord://watch?v=" + Uri.encode(video) + "&automix_opus=1")
                            : original.buildUpon().appendQueryParameter("mix", "any").build();
            if (played != null && !played.equals(original)) reference = played;
            DataSpec expected = SourceAutomix.resolve(reference);
            if (expected.uri.equals(candidate)) {
                decisions.put(key, true);
                return resolved;
            }
            long deadline = System.nanoTime() + BUDGET;
            double durationA = duration(expected, deadline),
                    durationB = duration(resolved, deadline);
            if (!(durationA >= RecordingIdentity.MIN_SECONDS
                    && durationB >= RecordingIdentity.MIN_SECONDS))
                throw new IOException("Not enough audio to verify an automatic source replacement");
            if (Math.abs(durationA - durationB) > RecordingIdentity.MAX_DURATION_DIFFERENCE) {
                decisions.put(key, false);
                conclusive = true;
                throw new IOException("Alternative recording has a different duration");
            }
            RecordingIdentity.Window[] a = new RecordingIdentity.Window[3],
                    b = new RecordingIdentity.Window[3];
            double[] points = {0, Math.max(0, durationA / 2 - 8), Math.max(0, durationA - 17)};
            for (int i = 0; i < points.length; i++) {
                a[i] = window(expected, points[i], Math.min(points[i] + 16, durationA), deadline);
                b[i] = window(resolved, points[i], Math.min(points[i] + 16, durationB), deadline);
                if (a[i] == null
                        || b[i] == null
                        || a[i].seconds < RecordingIdentity.MIN_SECONDS
                        || b[i].seconds < RecordingIdentity.MIN_SECONDS
                        || a[i].fingerprint.size() < 100
                        || b[i].fingerprint.size() < 100)
                    throw new IOException(
                            "Recording verification is waiting for enough measurable audio");
            }
            Double offset = RecordingIdentity.match(a, b, durationA, durationB);
            // A mismatched/inconclusive replacement must leave the original playback intact.
            boolean same = offset != null;
            if (decisions.size() > 256) {
                decisions.clear();
                offsets.clear();
                pendingOffsets.clear();
            }
            decisions.put(key, same);
            conclusive = true;
            if (!same)
                throw new IOException("Alternative audio failed fingerprint/timeline verification");
            // Only an upgrade measured against the actually selected old URI supplies a swap
            // offset.
            if (played != null && !played.equals(original)) {
                offsets.put(key, offset);
                pendingOffsets.put(
                        mediaId, new double[] {offset * 1000, System.currentTimeMillis()});
            }
            HostLog.d("Automatic source replacement verified across three audio windows");
            return resolved;
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Could not verify automatic source replacement", error);
        } finally {
            if (!conclusive && !Boolean.TRUE.equals(decisions.get(key))) decisions.remove(key);
            verifying.remove();
            SourceAutomix.leaveQuiet();
        }
    }

    /** A verified relative time shift for a same-recording upgrade, consumed by the host swap. */
    public static Long alignedOffset(Object service, Object sourceSong, Object targetSong) {
        try {
            Object source = SourceAutomix.field(sourceSong, "videoId"),
                    target = SourceAutomix.field(targetSong, "videoId");
            if (!(source instanceof String) || !source.equals(target)) return null;
            double[] found = pendingOffsets.remove(source);
            if (found == null || System.currentTimeMillis() - found[1] > 60_000) return null;
            return Math.round(found[0]);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static double duration(DataSpec spec, long deadline) throws Exception {
        try (MediaDataSource source = new SourceAudio(spec, deadline)) {
            Method method =
                    Class.forName("com.music.bitchord.playback.smart.AudioDecoder")
                            .getDeclaredMethod("containerDurationSeconds", MediaDataSource.class);
            method.setAccessible(true);
            Object found = method.invoke(null, source);
            return found instanceof Number ? ((Number) found).doubleValue() : 0;
        }
    }

    private static RecordingIdentity.Window window(
            DataSpec spec, double from, double until, long deadline) throws Exception {
        Class<?> decoder = Class.forName("com.music.bitchord.playback.smart.AudioDecoder");
        Method mono =
                decoder.getDeclaredMethod(
                        "toMono", ByteBuffer.class, MediaCodec.BufferInfo.class, int.class);
        mono.setAccessible(true);
        ArrayList<float[]> chunks = new ArrayList<>();
        int[] count = {0};
        Function4<ByteBuffer, MediaCodec.BufferInfo, Integer, Integer, Unit> tap =
                (bytes, info, channels, rate) -> {
                    try {
                        float[] chunk = (float[]) mono.invoke(null, bytes, info, channels);
                        count[0] += chunk.length;
                        if (count[0] > 192000 * 24)
                            throw new IllegalStateException("Audio verification window exceeded");
                        chunks.add(chunk);
                        return Unit.INSTANCE;
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                };
        Method raw =
                decoder.getDeclaredMethod(
                        "decodeRaw",
                        MediaDataSource.class,
                        double.class,
                        double.class,
                        Function0.class,
                        Function4.class);
        raw.setAccessible(true);
        try (MediaDataSource source = new SourceAudio(spec, deadline)) {
            Pair<?, ?> timing =
                    (Pair<?, ?>)
                            raw.invoke(
                                    null,
                                    source,
                                    from,
                                    until,
                                    (Function0<Boolean>) () -> System.nanoTime() > deadline,
                                    tap);
            if (timing == null || count[0] == 0) return null;
            float[] pcm = new float[count[0]];
            int at = 0;
            for (float[] chunk : chunks) {
                System.arraycopy(chunk, 0, pcm, at, chunk.length);
                at += chunk.length;
            }
            return new RecordingIdentity.Window(
                    pcm,
                    ((Number) timing.getFirst()).intValue(),
                    ((Number) timing.getSecond()).doubleValue());
        }
    }
}

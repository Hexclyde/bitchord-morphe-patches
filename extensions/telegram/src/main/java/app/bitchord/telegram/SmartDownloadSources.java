package app.bitchord.telegram;

import android.content.Context;

import java.lang.reflect.*;
import java.util.*;

/** Resolves a listening-history entry through the enabled download catalogue. */
final class SmartDownloadSources {
    private SmartDownloadSources() {}

    static Object call(Object owner, String name, Object... arguments) throws Exception {
        Class<?> type = owner instanceof Class ? (Class<?>) owner : owner.getClass();
        Method found = null;
        for (Method method : type.getMethods())
            if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
                if (found != null)
                    throw new ReflectiveOperationException("Ambiguous source interface");
                found = method;
            }
        if (found == null) throw new NoSuchMethodException(name);
        found.setAccessible(true);
        return found.invoke(Modifier.isStatic(found.getModifiers()) ? null : owner, arguments);
    }

    static Object resolve(SmartDownloads.Track track) throws Exception {
        if (track.title.isEmpty()
                || track.artist.isEmpty()
                || track.title.length() > 512
                || track.artist.length() > 512) return null;
        Class<?> registry = Class.forName("com.music.bitchord.data.sources.SourceRegistry");
        PlaylistImport.Track reference =
                new PlaylistImport.Track(
                        track.title,
                        track.artist,
                        track.album == null ? "" : track.album,
                        null,
                        null,
                        0);
        Object qualitySetting = qualitySetting();
        Object request =
                call(
                        Class.forName("com.music.bitchord.data.sources.SourceResolver"),
                        "requestForDownload",
                        qualitySetting);
        Object best = null;
        int quality = -1;
        boolean lookupFailed = false;
        for (Object source : (List<?>) call(registry, "activeForDownload")) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            String sourceId =
                    String.valueOf(
                            Class.forName("com.music.bitchord.data.sources.MusicSource")
                                    .getMethod("getConfigId")
                                    .invoke(source));
            try {
                List<?> songs = search(source, track.artist + " " + track.title, request);
                List<Object> candidates = new ArrayList<>();
                for (Object song : songs) {
                    String id = MixedSongCodec.text(song, "videoId");
                    boolean youtube =
                            VideoSwitch.youTubeId(id)
                                    && "YOUTUBE"
                                            .equals(
                                                    String.valueOf(
                                                            Class.forName(
                                                                            "com.music.bitchord.data.sources.MusicSource")
                                                                    .getMethod("getKind")
                                                                    .invoke(source)));
                    if ((!youtube && !id.startsWith("src:" + sourceId + "::"))
                            || id.length() > 8192
                            || MixedSongCodec.text(song, "title").length() > 512
                            || MixedSongCodec.text(song, "artist").length() > 512) continue;
                    if (ImportConfidence.similarity(track.title, MixedSongCodec.text(song, "title"))
                                    < .95
                            || ImportConfidence.similarity(
                                            track.artist, MixedSongCodec.text(song, "artist"))
                                    < .90) continue;
                    candidates.add(song);
                }
                while (!candidates.isEmpty()) {
                    Object match = PlaylistImport.best(reference, candidates);
                    if (match == null) break;
                    candidates.remove(match);
                    int rank =
                            VideoSwitch.youTubeId(MixedSongCodec.text(match, "videoId"))
                                    ? 0
                                    : rank(match, String.valueOf(qualitySetting));
                    if (rank > quality) {
                        best = match;
                        quality = rank;
                    }
                }
            } catch (InterruptedException cancelled) {
                throw cancelled;
            } catch (Exception unavailable) {
                lookupFailed = true;
                HostLog.d("A download source could not be searched");
            }
        }
        if (best != null && (quality > 0 || !lookupFailed)) return best;
        if (!lookupFailed && VideoSwitch.youTubeId(track.id)) {
            for (Object source : (List<?>) call(registry, "activeForDownload"))
                if ("YOUTUBE"
                        .equals(
                                String.valueOf(
                                        Class.forName("com.music.bitchord.data.sources.MusicSource")
                                                .getMethod("getKind")
                                                .invoke(source))))
                    return MixedSongCodec.decode(track.song());
        }
        // Cross-source upgrades must have passed the host recording matcher above.
        return null;
    }

    private static Object qualitySetting() throws Exception {
        Field field =
                Class.forName("com.music.bitchord.data.settings.AppSettings")
                        .getDeclaredField("downloadQuality");
        field.setAccessible(true);
        return Class.forName("kotlinx.coroutines.flow.StateFlow")
                .getMethod("getValue")
                .invoke(field.get(null));
    }

    static int rank(Object song, String setting) {
        String quality = MixedSongCodec.text(song, "sourceQuality").toUpperCase(Locale.ROOT);
        boolean low = quality.contains("LOW") || quality.contains("128");
        boolean high = quality.contains("HIGH") || quality.contains("320");
        boolean lossless =
                quality.contains("LOSSLESS")
                        || quality.matches(
                                ".*\\b(FLAC|ALAC|WAV|AIFF|APE|WAVPACK|TTA|TAK|DSD|DSF|DFF)\\b.*");
        if ("STANDARD".equals(setting)) return low ? 40 : -1;
        if ("HIGH".equals(setting)) return high ? 40 : -1;
        return lossless
                ? 30 + ("true".equals(MixedSongCodec.text(song, "sourceHiRes")) ? 5 : 0)
                : -1;
    }

    @SuppressWarnings("unchecked")
    private static List<?> search(Object source, String query, Object qualityRequest)
            throws Exception {
        Class<?> contract = Class.forName("com.music.bitchord.data.sources.MusicSource");
        Method method = null;
        for (Method candidate : contract.getMethods())
            if (candidate.getName().equals("search") && candidate.getParameterCount() == 5)
                method = candidate;
        if (method == null) throw new NoSuchMethodException("Source search unavailable");
        Method request = method;
        Object result =
                VideoSwitch.await(
                        step -> request.invoke(source, query, 12, true, qualityRequest, step));
        if (!(result instanceof List))
            throw new java.io.IOException("Source search did not complete");
        return (List<?>) result;
    }

    static void prepare(Context app, Object song) throws Exception {
        String id = MixedSongCodec.text(song, "videoId");
        if (id.startsWith("src:")) {
            int separator = id.indexOf("::");
            if (separator < 5) throw new IllegalArgumentException("Invalid selected source");
            String sourceId = id.substring(4, separator);
            Object config =
                    call(
                            Class.forName("com.music.bitchord.data.sources.SourceRegistry"),
                            "config",
                            sourceId);
            if (config == null
                    || !Boolean.TRUE.equals(call(config, "getEnabled"))
                    || !Boolean.TRUE.equals(call(config, "getAllowDownloads")))
                throw new IllegalStateException("Source no longer permits downloads");
        }
        DownloadSelection.keep(app, song);
    }
}

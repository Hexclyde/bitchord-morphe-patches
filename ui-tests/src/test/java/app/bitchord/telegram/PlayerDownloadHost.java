package app.bitchord.telegram;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.media3.common.MediaItem;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.intrinsics.CoroutineSingletons;
import kotlin.coroutines.jvm.internal.SuspendLambda;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;
import kotlin.jvm.functions.Function3;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Uses the host's matching, probing and transfer routes on its download dispatcher. */
final class PlayerDownloadHost {
    static final String DOWNLOADS = "com.music.bitchord.download.Downloads";
    static final String SOURCES = "com.music.bitchord.data.sources.";
    final Context context;
    final CoroutineContext coroutine;

    PlayerDownloadHost(Context context, Object continuation) {
        this.context = context;
        this.coroutine =
                continuation instanceof Continuation
                        ? ((Continuation<?>) continuation).getContext()
                        : EmptyCoroutineContext.INSTANCE;
    }

    static Object field(Object owner, String name) throws ReflectiveOperationException {
        Class<?> type = owner instanceof Class ? (Class<?>) owner : owner.getClass();
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(owner instanceof Class ? null : owner);
    }

    static Object call(Object owner, String name, Object... args) throws Exception {
        Class<?> type = owner instanceof Class ? (Class<?>) owner : owner.getClass();
        Method found = null;
        for (Method m : type.getDeclaredMethods())
            if (m.getName().equals(name) && m.getParameterTypes().length == args.length) {
                boolean valid = true;
                for (int i = 0; i < args.length; i++)
                    if (args[i] != null && !boxed(m.getParameterTypes()[i]).isInstance(args[i]))
                        valid = false;
                if (valid) {
                    if (found != null) throw new IOException("Ambiguous download interface");
                    found = m;
                }
            }
        if (found == null) {
            for (Method m : type.getMethods())
                if (m.getName().equals(name) && m.getParameterTypes().length == args.length) {
                    found = m;
                    break;
                }
        }
        if (found == null) throw new NoSuchMethodException(name);
        found.setAccessible(true);
        try {
            Object target =
                    Modifier.isStatic(found.getModifiers())
                            ? null
                            : owner instanceof Class ? type.getField("INSTANCE").get(null) : owner;
            return found.invoke(target, args);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw failure;
        }
    }

    /**
     * Arguments for {@code SourceResolver.matchAndStream}. 1.9 ends at the drift bar; 1.9.1 adds a
     * runtime lookup and a file-length check, which a download leaves at their defaults.
     */
    static Object[] matchArguments(
            Class<?> resolver, Object source, Object target, Object best, Object step)
            throws IOException {
        for (Method m : resolver.getDeclaredMethods()) {
            if (!m.getName().equals("matchAndStream")) continue;
            Class<?>[] types = m.getParameterTypes();
            if (types.length < 8
                    || types[3] != boolean.class
                    || types[4] != boolean.class
                    || types[5] != boolean.class
                    || types[6] != int.class) continue;
            if (types.length == 8)
                return new Object[] {source, target, best, true, true, true, 3, step};
            if (types.length == 10 && types[7] == Function1.class && types[8] == boolean.class)
                return new Object[] {source, target, best, true, true, true, 3, null, false, step};
        }
        throw new IOException("Download sources use an unknown matching interface");
    }

    static Class<?> boxed(Class<?> type) {
        if (type == int.class) return Integer.class;
        if (type == boolean.class) return Boolean.class;
        if (type == long.class) return Long.class;
        return type;
    }

    static Object singleton(String name) throws Exception {
        return Class.forName(name).getField("INSTANCE").get(null);
    }

    static Object toSong(MediaItem item) throws Exception {
        return call(
                Class.forName("com.music.bitchord.playback.PlayerConnectionKt"), "toSong", item);
    }

    static void enqueue(Context context, Object song) throws Exception {
        call(Class.forName(DOWNLOADS), "enqueue", context, song, Strings.t("Downloads"));
    }

    static Object flow(String name) throws Exception {
        return call(field(Class.forName(DOWNLOADS), name), "getValue");
    }

    static Object active(String id) throws Exception {
        return ((Map<?, ?>) flow("_active")).get(id);
    }

    static boolean saved(String id) throws Exception {
        return ((Map<?, ?>) flow("_saved")).containsKey(id);
    }

    static Object currentStream(MediaItem item, Uri uri) {
        try {
            Object stream =
                    uri == null
                            ? null
                            : call(
                                    Class.forName("com.music.bitchord.playback.QualityUpgrade"),
                                    "forcedStream",
                                    uri);
            if (stream != null) return stream;
            return call(
                    Class.forName("com.music.bitchord.playback.StreamChoice"), "of", item.mediaId);
        } catch (Exception absent) {
            return null;
        }
    }

    static CoroutineContext.Key<?> jobKey(Class<?> jobType, Class<?> keyType)
            throws ReflectiveOperationException {
        Field selected;
        try {
            selected = jobType.getField("Key");
            if (!Modifier.isStatic(selected.getModifiers()))
                throw new IllegalStateException("Download coroutine key changed");
        } catch (NoSuchFieldException shrunk) {
            if (!CoroutineContext.Key.class.isAssignableFrom(keyType))
                throw new IllegalStateException("Download coroutine key changed");
            selected = null;
            for (Field field : keyType.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || field.getType() != keyType)
                    continue;
                if (selected != null)
                    throw new IllegalStateException("Ambiguous download coroutine key");
                selected = field;
            }
            if (selected == null) throw new IllegalStateException("Download coroutine key changed");
        }
        selected.setAccessible(true);
        Object value = selected.get(null);
        if (!(value instanceof CoroutineContext.Key))
            throw new IllegalStateException("Download coroutine key changed");
        return (CoroutineContext.Key<?>) value;
    }

    void check() throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        Class<?> jobType = Class.forName("kotlinx.coroutines.Job");
        Object key = jobKey(jobType, Class.forName("kotlinx.coroutines.Job$Key"));
        Object job = coroutine.get((CoroutineContext.Key) key);
        if (job != null && !Boolean.TRUE.equals(call(job, "isActive")))
            throw new CancellationException();
    }

    interface Suspended {
        Object invoke(Continuation<?> continuation) throws Exception;
    }

    Object await(Suspended action) throws Exception {
        check();
        Class<?> jobType = Class.forName("kotlinx.coroutines.Job");
        Object key = jobKey(jobType, Class.forName("kotlinx.coroutines.Job$Key"));
        Object parent = coroutine.get((CoroutineContext.Key) key);
        Object child =
                Class.forName("kotlinx.coroutines.JobImpl")
                        .getConstructor(jobType)
                        .newInstance(parent);
        CoroutineContext worker = coroutine.plus((CoroutineContext) child);
        AtomicReference<Object> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Continuation<Object> complete =
                new Continuation<Object>() {
                    public CoroutineContext getContext() {
                        return worker;
                    }

                    public void resumeWith(Object value) {
                        result.set(value);
                        done.countDown();
                    }
                };
        try {
            Object value = action.invoke(new VideoSwitch.Step(complete));
            if (value == CoroutineSingletons.COROUTINE_SUSPENDED) {
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(100);
                while (!done.await(100, TimeUnit.MILLISECONDS)) {
                    check();
                    if (System.nanoTime() > until)
                        throw new IOException("Download source timed out");
                }
                value = result.get();
            }
            check();
            if (value != null && value.getClass().getName().equals("kotlin.Result"))
                value = field(value, "value");
            if (value != null && value.getClass().getName().equals("kotlin.Result$Failure")) {
                Object failure = field(value, "exception");
                if (failure instanceof Exception) throw (Exception) failure;
                throw new IOException("Download source failed");
            }
            return value;
        } finally {
            jobType.getMethod("cancel", CancellationException.class)
                    .invoke(child, new CancellationException("Download lookup completed"));
        }
    }

    Object kept(Object stream, int seconds) throws Exception {
        if (stream == null) return null;
        return await(
                step ->
                        call(
                                singleton(DOWNLOADS),
                                "keepable",
                                stream,
                                seconds > 0 ? seconds : null,
                                step));
    }

    Object route(Object song, Object kept) throws Exception {
        return call(Class.forName(DOWNLOADS), "sourceRoute", context, song, kept);
    }

    static boolean permitted(Object config) throws Exception {
        return config != null
                && Boolean.TRUE.equals(call(config, "getEnabled"))
                && Boolean.TRUE.equals(call(config, "getAllowDownloads"));
    }

    static boolean youtube(Object source) throws Exception {
        return "YOUTUBE".equals(String.valueOf(call(source, "getKind")));
    }

    static final class Candidate {
        final Object route;
        final boolean lossless;
        final int bits, rate, kbps;

        Candidate(Object route, boolean lossless, int bits, int rate, int kbps) {
            this.route = route;
            this.lossless = lossless;
            this.bits = bits;
            this.rate = rate;
            this.kbps = kbps;
        }
    }

    static int compare(Candidate a, Candidate b) {
        int n = Boolean.compare(a.lossless, b.lossless);
        if (n == 0 && a.lossless) n = Integer.compare(a.bits, b.bits);
        if (n == 0 && a.lossless) n = Integer.compare(a.rate, b.rate);
        if (n == 0) n = Integer.compare(a.kbps, b.kbps);
        return n;
    }

    static int number(Object owner, String name) {
        try {
            Object value = field(owner, name);
            return value instanceof Number ? Math.max(0, ((Number) value).intValue()) : 0;
        } catch (Exception absent) {
            return 0;
        }
    }

    Candidate candidate(Object song, Object stream, int seconds) throws Exception {
        Object kept = kept(stream, seconds);
        if (kept == null) return null;
        Object route = route(song, kept);
        if (route == null) return null;
        Object format = field(field(kept, "stream"), "format");
        boolean lossless = Boolean.TRUE.equals(call(format, "isLossless"));
        return new Candidate(
                route,
                lossless,
                number(format, "bitDepth"),
                number(format, "sampleRateHz"),
                number(format, "kbps"));
    }

    static boolean sameDuration(int expected, int actual) {
        return expected > 0 && actual > 0 && Math.abs((long) expected - actual) <= 3;
    }

    interface Choices {
        Candidate current() throws Exception;

        List<Candidate> alternatives() throws Exception;

        Candidate youtube() throws Exception;
    }

    static Object choose(Choices choices) throws Exception {
        Candidate exact;
        try {
            exact = choices.current();
        } catch (CancellationException | InterruptedException cancelled) {
            throw cancelled;
        } catch (Exception unavailable) {
            exact = null;
        }
        if (exact != null) return exact.route;
        List<Candidate> alternatives = choices.alternatives();
        Candidate best = null;
        for (Candidate candidate : alternatives)
            if (candidate != null && (best == null || compare(candidate, best) > 0))
                best = candidate;
        if (best != null) return best.route;
        Candidate last = choices.youtube();
        return last == null ? null : last.route;
    }

    Object select(PlayerDownload.Request request) throws Exception {
        check();
        Object song = MixedSongCodec.decode(request.song);
        Object chosen =
                choose(
                        new Choices() {
                            public Candidate current() throws Exception {
                                return PlayerDownloadHost.this.current(request, song);
                            }

                            public List<Candidate> alternatives() throws Exception {
                                return alternativesFor(request, song);
                            }

                            public Candidate youtube() throws Exception {
                                return youtubeFor(request);
                            }
                        });
        return chosen == null ? failedRoute() : chosen;
    }

    Candidate current(PlayerDownload.Request request, Object song) throws Exception {
        Class<?> registry = Class.forName(SOURCES + "SourceRegistry");
        Object current = request.stream;
        if (current != null) {
            String id = String.valueOf(field(current, "sourceConfigId"));
            Object config = call(registry, "config", id);
            if (permitted(config)
                    && !youtube(config)
                    && (request.source.isEmpty() || request.source.equals(id))) {
                try {
                    Candidate exact = candidate(song, current, request.seconds);
                    if (exact != null) return exact;
                } catch (CancellationException | InterruptedException cancelled) {
                    throw cancelled;
                } catch (Exception unavailable) {
                    check();
                }
            }
        }
        if (request.local != null) {
            Candidate exact = local(request.local, 0);
            if (exact != null) return exact;
        }
        String key = request.song.optString("videoId");
        int separator = key.startsWith("src:") ? key.indexOf("::", 4) : -1;
        if (separator <= 4 || separator + 2 >= key.length()) return null;
        String id = key.substring(4, separator), track = key.substring(separator + 2);
        Object config = call(registry, "config", id);
        if (!permitted(config)
                || youtube(config)
                || (!request.source.isEmpty() && !request.source.equals(id))) return null;
        Object best = Class.forName(SOURCES + "StreamRequest$Best").getField("INSTANCE").get(null);
        Object row =
                await(
                        step ->
                                call(
                                        singleton(SOURCES + "SourceResolver"),
                                        "forDownloadOfRow",
                                        id,
                                        track,
                                        request.song.optString("sourceQuality", null),
                                        best,
                                        step));
        if (row == null || !id.equals(field(row, "sourceConfigId"))) return null;
        int length = number(row, "durationSec");
        if (length > 0 && request.seconds > 0 && !sameDuration(request.seconds, length))
            return null;
        return candidate(song, row, request.seconds);
    }

    List<Candidate> alternativesFor(PlayerDownload.Request request, Object song) throws Exception {
        Class<?> registry = Class.forName(SOURCES + "SourceRegistry");
        List<Candidate> candidates = new ArrayList<>();
        boolean unresolved = false;
        if (request.seconds > 0) {
            Object target =
                    Class.forName(SOURCES + "TrackMatcher$Target")
                            .getConstructor(
                                    String.class,
                                    String.class,
                                    Integer.class,
                                    String.class,
                                    Boolean.class,
                                    boolean.class)
                            .newInstance(
                                    request.song.optString("title"),
                                    request.song.optString("artist"),
                                    request.seconds,
                                    request.song.optString("albumName", null),
                                    request.song.has("isExplicit")
                                            ? request.song.getBoolean("isExplicit")
                                            : null,
                                    false);
            Object best =
                    Class.forName(SOURCES + "StreamRequest$Best").getField("INSTANCE").get(null);
            List<?> sources = (List<?>) call(registry, "activeForDownload");
            ExecutorService lookups =
                    Executors.newFixedThreadPool(
                            4,
                            action -> {
                                Thread worker = new Thread(action, "bitchord-player-download");
                                worker.setDaemon(true);
                                return worker;
                            });
            List<Future<Candidate>> pending = new ArrayList<>();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            try {
                for (Object source : sources) {
                    check();
                    if (youtube(source)) continue;
                    pending.add(
                            lookups.submit(
                                    () -> {
                                        Object stream =
                                                await(
                                                        step ->
                                                                call(
                                                                        singleton(
                                                                                SOURCES
                                                                                        + "SourceResolver"),
                                                                        "matchAndStream",
                                                                        matchArguments(
                                                                                Class.forName(
                                                                                        SOURCES
                                                                                                + "SourceResolver"),
                                                                                source,
                                                                                target,
                                                                                best,
                                                                                step)));
                                        if (stream == null) return null;
                                        int length = number(stream, "durationSec");
                                        if (length > 0 && !sameDuration(request.seconds, length))
                                            return null;
                                        return candidate(song, stream, request.seconds);
                                    }));
                }
                while (!pending.isEmpty() && System.nanoTime() < until) {
                    check();
                    boolean completed = false;
                    for (Iterator<Future<Candidate>> i = pending.iterator(); i.hasNext(); ) {
                        Future<Candidate> lookup = i.next();
                        if (!lookup.isDone()) continue;
                        completed = true;
                        i.remove();
                        try {
                            Candidate candidate = lookup.get();
                            if (candidate != null) candidates.add(candidate);
                        } catch (ExecutionException unavailable) {
                            if (unavailable.getCause() instanceof CancellationException)
                                throw (CancellationException) unavailable.getCause();
                            unresolved = true;
                        }
                    }
                    if (!completed && !pending.isEmpty()) Thread.sleep(50);
                }
                if (!pending.isEmpty()) unresolved = true;
            } finally {
                for (Future<?> lookup : pending) lookup.cancel(true);
                lookups.shutdownNow();
            }
            try {
                localMatches(request, candidates);
            } catch (CancellationException | InterruptedException cancelled) {
                throw cancelled;
            } catch (Exception unavailable) {
                check();
                unresolved = true;
            }
        }
        if (unresolved && candidates.isEmpty())
            throw new IOException("Download sources could not be checked");
        return candidates;
    }

    Candidate youtubeFor(PlayerDownload.Request request) throws Exception {
        check();
        String id = request.song.optString("videoId");
        Object youtube =
                call(
                        Class.forName(SOURCES + "SourceRegistry"),
                        "config",
                        YouTubePolicy.youtubeId());
        if (!YouTubePolicy.playbackBlocked()
                && permitted(youtube)
                && id.matches("[A-Za-z0-9_-]{11}")) {
            Object quality =
                    Enum.valueOf(
                            (Class)
                                    Class.forName(
                                            "com.music.bitchord.data.settings.DownloadQuality"),
                            "LOSSLESS");
            org.json.JSONObject metadata = new org.json.JSONObject(request.song.toString());
            if (request.seconds > 0)
                metadata.put(
                        "durationText",
                        (request.seconds / 60)
                                + ":"
                                + String.format(
                                        java.util.Locale.ROOT, "%02d", request.seconds % 60));
            Object original = MixedSongCodec.decode(metadata);
            return new Candidate(
                    await(
                            step ->
                                    call(
                                            Class.forName(DOWNLOADS),
                                            "access$routeFor",
                                            context,
                                            original,
                                            quality,
                                            step)),
                    false,
                    0,
                    0,
                    0);
        }
        return null;
    }

    void localMatches(PlayerDownload.Request request, List<Candidate> candidates) throws Exception {
        Class<?> library = Class.forName("com.music.bitchord.data.LocalMediaRepository");
        if (!Boolean.TRUE.equals(call(library, "hasStoragePermission", context))) return;
        Object songs = await(step -> call(library, "getLocalMusic", context, step));
        if (!(songs instanceof List)) return;
        for (Object song : (List<?>) songs) {
            check();
            String uri = MixedSongCodec.text(song, "localUri");
            if (uri.isEmpty()) continue;
            int seconds = SourceFallback.seconds(MixedSongCodec.text(song, "durationText"));
            SourceFallback.Row row =
                    new SourceFallback.Row(
                            MixedSongCodec.text(song, "title"),
                            MixedSongCodec.text(song, "artist"),
                            seconds,
                            uri);
            String match =
                    SourceFallback.match(
                            Collections.singletonList(row),
                            request.song.optString("title"),
                            request.song.optString("artist"),
                            request.seconds,
                            value -> true,
                            Collections.emptySet());
            if (match == null) continue;
            Candidate candidate = local(Uri.parse(match), request.seconds);
            if (candidate != null) candidates.add(candidate);
        }
    }

    Candidate local(Uri uri, int expectedSeconds) {
        try {
            String mime = context.getContentResolver().getType(uri);
            android.media.MediaMetadataRetriever reader =
                    new android.media.MediaMetadataRetriever();
            try {
                reader.setDataSource(context, uri);
                if (mime == null)
                    mime =
                            reader.extractMetadata(
                                    android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
                String bitrate =
                        reader.extractMetadata(
                                android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE);
                String actualDuration =
                        reader.extractMetadata(
                                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
                int duration =
                        actualDuration == null
                                ? 0
                                : (int) Math.round(Long.parseLong(actualDuration) / 1000.0);
                if (expectedSeconds > 0 && !sameDuration(expectedSeconds, duration)) return null;
                String format = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
                if (!format.startsWith("audio/") && !format.equals("application/ogg")) return null;
                int rate = 0, bits = 0;
                String codec = "";
                android.media.MediaExtractor extractor = new android.media.MediaExtractor();
                try {
                    extractor.setDataSource(context, uri, null);
                    for (int i = 0; i < extractor.getTrackCount(); i++) {
                        android.media.MediaFormat audio = extractor.getTrackFormat(i);
                        String candidate = audio.getString(android.media.MediaFormat.KEY_MIME);
                        if (candidate == null || !candidate.startsWith("audio/")) continue;
                        codec = candidate.toLowerCase(Locale.ROOT);
                        if (audio.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE))
                            rate = audio.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE);
                        if (audio.containsKey("bits-per-sample"))
                            bits = audio.getInteger("bits-per-sample");
                        if (audio.containsKey(android.media.MediaFormat.KEY_PCM_ENCODING)) {
                            int pcm = audio.getInteger(android.media.MediaFormat.KEY_PCM_ENCODING);
                            if (pcm == 2) bits = 16;
                            else if (pcm == 3) bits = 8;
                            else if (pcm == 4 || pcm == 0x30000000) bits = 32;
                            else if (pcm == 0x20000000) bits = 24;
                        }
                        break;
                    }
                } finally {
                    extractor.release();
                }
                String extension =
                        format.contains("flac")
                                ? "flac"
                                : format.contains("wav")
                                        ? "wav"
                                        : format.contains("mpeg")
                                                ? "mp3"
                                                : format.contains("ogg")
                                                        ? "ogg"
                                                        : format.contains("mp4")
                                                                        || codec.contains("alac")
                                                                ? "m4a"
                                                                : null;
                if (extension == null) return null;
                boolean lossless =
                        codec.equals("audio/flac")
                                || codec.equals("audio/raw")
                                || codec.equals("audio/alac");
                Object route =
                        newRoute(
                                extension,
                                mime,
                                Strings.t("This device"),
                                new LocalWriter(context, uri));
                AudioOrigin.localRoute(route, uri.toString());
                return new Candidate(
                        route,
                        lossless,
                        bits,
                        rate,
                        bitrate == null ? 0 : Integer.parseInt(bitrate) / 1000);
            } finally {
                reader.release();
            }
        } catch (Exception unreadable) {
            return null;
        }
    }

    static Object newRoute(String extension, String mime, String description, Object writer)
            throws Exception {
        Class<?> type = Class.forName(DOWNLOADS + "$Route");
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            Class<?>[] p = constructor.getParameterTypes();
            if ((p.length != 6 && p.length != 7)
                    || p[0] != String.class
                    || p[1] != String.class
                    || p[2] != String.class
                    || p[3] != String.class
                    || !Function3.class.isAssignableFrom(p[5])) continue;
            constructor.setAccessible(true);
            Object[] args =
                    p.length == 6
                            ? new Object[] {extension, mime, description, null, null, writer}
                            : new Object[] {extension, mime, description, null, null, writer, 0};
            return constructor.newInstance(args);
        }
        throw new IOException("Download route changed");
    }

    static Object failedRoute() {
        try {
            return newRoute(
                    "bin",
                    "application/octet-stream",
                    Strings.t("No audio track found"),
                    new FailureWriter());
        } catch (Exception impossible) {
            throw new IllegalStateException(Strings.t("Download action failed. Try again."));
        }
    }

    static final class FailureWriter extends SuspendLambda
            implements Function3<OutputStream, Function2<Long, Long, ?>, Continuation<?>, Object> {
        FailureWriter() {
            super(3, null);
        }

        public Object invoke(
                OutputStream sink,
                Function2<Long, Long, ?> progress,
                Continuation<?> continuation) {
            throw new IllegalStateException(Strings.t("No audio track found"));
        }

        protected Object invokeSuspend(Object value) {
            throw new IllegalStateException();
        }
    }

    static final class LocalWriter extends SuspendLambda
            implements Function3<OutputStream, Function2<Long, Long, ?>, Continuation<?>, Object> {
        final Context context;
        final Uri uri;

        LocalWriter(Context context, Uri uri) {
            super(3, null);
            this.context = context.getApplicationContext();
            this.uri = uri;
        }

        public Object invoke(
                OutputStream sink,
                Function2<Long, Long, ?> progress,
                Continuation<?> continuation) {
            try (ParcelFileDescriptor fd =
                    context.getContentResolver().openFileDescriptor(uri, "r")) {
                if (fd == null) throw new FileNotFoundException();
                long total = fd.getStatSize(), written = 0;
                PlayerDownloadHost host = new PlayerDownloadHost(context, continuation);
                try (InputStream input =
                        new ParcelFileDescriptor.AutoCloseInputStream(
                                ParcelFileDescriptor.dup(fd.getFileDescriptor()))) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        host.check();
                        sink.write(buffer, 0, count);
                        written += count;
                        if (total > 0) progress.invoke(written, total);
                    }
                }
                return Unit.INSTANCE;
            } catch (Exception failure) {
                throw new IllegalStateException(
                        Strings.t("Download action failed. Try again."), failure);
            }
        }

        protected Object invokeSuspend(Object value) {
            throw new IllegalStateException();
        }
    }
}

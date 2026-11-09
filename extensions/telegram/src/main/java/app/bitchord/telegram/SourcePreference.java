package app.bitchord.telegram;

import android.media.MediaCodec;
import android.media.MediaDataSource;
import android.net.Uri;
import android.os.Process;

import androidx.media3.datasource.DataSpec;

import kotlin.Unit;
import kotlin.coroutines.CoroutineContext;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function4;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/** Verifies a resolver's automatic substitutions before their bytes enter the player's cache. */
public final class SourcePreference {
    private SourcePreference() {}

    private enum CheckReason {
        UNCLASSIFIED,
        PASSED,
        DURATION_INSUFFICIENT,
        DURATION_MISMATCH,
        IDENTITY_MISMATCH
    }

    private static final ThreadLocal<Boolean> verifying = new ThreadLocal<>();
    private static final Map<String, Boolean> decisions = new ConcurrentHashMap<>();
    private static final Map<String, Double> offsets = new ConcurrentHashMap<>();
    private static final Map<String, double[]> pendingOffsets = new ConcurrentHashMap<>();
    static java.util.function.LongSupplier retryClock = System::nanoTime;
    private static final long BUDGET = 45_000_000_000L;

    /**
     * An upgrade is checked while the old copy keeps playing, on its way to BitChord's audition,
     * which then gets 25 seconds of its own and continues from here. Nothing is heard waiting.
     */
    private static final long UPGRADE_BUDGET = 60_000_000_000L;

    /** How long windows already decoded stay usable, and for how many upgrades at once. */
    private static final long KEPT_NANOS = 600_000_000_000L;

    private static final int KEPT = 8, WINDOWS = 3;
    private static final String STRICT = "strict\n", PREPARED = "prepared-v1\n";

    /** A check made before anything plays: who may stop it, and whether anyone is waiting. */
    private static final class Ahead {
        final BooleanSupplier cancelled;
        final boolean background, prepared, approvedRead;
        final Uri original, candidate;

        Ahead(
                BooleanSupplier cancelled,
                boolean background,
                boolean prepared,
                Uri original,
                Uri candidate) {
            this(cancelled, background, prepared, original, candidate, false);
        }

        Ahead(
                BooleanSupplier cancelled,
                boolean background,
                boolean prepared,
                Uri original,
                Uri candidate,
                boolean approvedRead) {
            this.cancelled = cancelled;
            this.background = background;
            this.prepared = prepared;
            this.original = original;
            this.candidate = candidate;
            this.approvedRead = approvedRead;
        }

        boolean applies(Uri requested, Uri resolved) {
            return original.equals(requested) && candidate.equals(resolved);
        }
    }

    private static final ThreadLocal<Ahead> ahead = new ThreadLocal<>();
    private static final ThreadLocal<String> warming = new ThreadLocal<>();
    private static final Map<String, PreparedRead> reads = new LinkedHashMap<>();

    /** Streams checked ahead for a request: its player may be looking at another reference. */
    private static final Set<String> verifiedAhead =
            Collections.newSetFromMap(
                    new LinkedHashMap<String, Boolean>() {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                            return size() > 256;
                        }
                    });

    private static final Set<String> verifiedPrepared =
            Collections.newSetFromMap(
                    new LinkedHashMap<String, Boolean>() {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                            return size() > 256;
                        }
                    });

    private static String aheadKey(Uri original, Uri candidate) {
        return SourcePreparation.withoutEntry(original) + "\n" + candidate;
    }

    /**
     * Runs the same check {@link #guard} would, before the request is played. Throws what the guard
     * would throw, and {@link SourceAudio.Stopped} when cancelled, with its windows kept.
     */
    static void verifyAhead(
            DataSpec requested, DataSpec resolved, BooleanSupplier cancelled, boolean background)
            throws IOException {
        Ahead previous = ahead.get();
        ahead.set(new Ahead(cancelled, background, false, requested.uri, resolved.uri));
        try {
            guard(requested, resolved);
            trustAhead(requested, resolved);
        } finally {
            restoreAhead(previous);
        }
    }

    /** This stream was found to be this request's recording, here or in an earlier session. */
    static void trustAhead(DataSpec requested, DataSpec resolved) {
        synchronized (verifiedAhead) {
            verifiedAhead.add(aheadKey(requested.uri, resolved.uri));
        }
    }

    /** Whether a check made ahead found this stream to be another recording. */
    static boolean rejected(DataSpec requested, DataSpec resolved) {
        return Boolean.FALSE.equals(decisions.get(decisionKey(requested.uri, resolved.uri, null)));
    }

    /** A complete recording can be selected before playback without certifying a live swap. */
    static void verifyPrepared(
            DataSpec requested, DataSpec resolved, BooleanSupplier cancelled, boolean background)
            throws IOException {
        Ahead previous = ahead.get();
        ahead.set(new Ahead(cancelled, background, true, requested.uri, resolved.uri));
        try {
            guard(requested, resolved);
            trustPrepared(requested, resolved);
        } catch (IOException failed) {
            if (rejectedPrepared(requested, resolved))
                synchronized (verifiedPrepared) {
                    verifiedPrepared.remove(aheadKey(requested.uri, resolved.uri));
                }
            throw failed;
        } finally {
            restoreAhead(previous);
        }
    }

    /** A manifest is checked through its assembled file, then read through its playback request. */
    static void verifyPrepared(
            DataSpec requested,
            DataSpec playback,
            DataSpec checked,
            BooleanSupplier cancelled,
            boolean background)
            throws IOException {
        try {
            verifyPrepared(requested, checked, cancelled, background);
        } catch (IOException failed) {
            if (!(failed instanceof SourceAudio.Stopped)
                    && !(failed instanceof InterruptedIOException)
                    && rejectedPrepared(requested, checked)) {
                decisions.put(decisionKey(requested.uri, playback.uri, null, true), false);
                synchronized (verifiedPrepared) {
                    verifiedPrepared.remove(aheadKey(requested.uri, playback.uri));
                }
            }
            throw failed;
        }
        decisions.remove(decisionKey(requested.uri, playback.uri, null, true), false);
        trustPrepared(requested, playback);
    }

    private static void restoreAhead(Ahead previous) {
        if (previous == null) ahead.remove();
        else ahead.set(previous);
    }

    static void trustPrepared(DataSpec requested, DataSpec resolved) {
        synchronized (verifiedPrepared) {
            verifiedPrepared.add(aheadKey(requested.uri, resolved.uri));
        }
    }

    static boolean rejectedPrepared(DataSpec requested, DataSpec resolved) {
        return Boolean.FALSE.equals(
                decisions.get(decisionKey(requested.uri, resolved.uri, null, true)));
    }

    /** Permission belongs only to this already approved cache read and its coroutine. */
    static final class PreparedRead implements AutoCloseable {
        private final String token = UUID.randomUUID().toString(), previous;
        private final long until = System.nanoTime() + BUDGET;
        private final Thread owner = Thread.currentThread();
        private final Ahead scope;
        private final CoroutineContext context;
        private volatile boolean closed;

        private PreparedRead(DataSpec requested, DataSpec resolved) throws IOException {
            synchronized (verifiedPrepared) {
                if (!verifiedPrepared.contains(aheadKey(requested.uri, resolved.uri)))
                    throw new IOException("The cache read has no preparation proof");
            }
            scope =
                    new Ahead(
                            () -> closed || System.nanoTime() > until,
                            true,
                            true,
                            requested.uri,
                            resolved.uri,
                            true);
            context = AutomixAccess.context(token, warming);
            if (context == null) throw new IOException("The cache read context is unavailable");
            synchronized (reads) {
                reads.values().removeIf(read -> read.closed || System.nanoTime() > read.until);
                if (reads.size() >= KEPT) throw new IOException("Too many prepared cache reads");
                reads.put(token, this);
            }
            previous = warming.get();
            warming.set(token);
        }

        CoroutineContext context() {
            return context;
        }

        @Override
        public void close() {
            closed = true;
            synchronized (reads) {
                reads.remove(token);
            }
            if (Thread.currentThread() == owner && token.equals(warming.get())) {
                if (previous == null) warming.remove();
                else warming.set(previous);
            }
        }
    }

    static PreparedRead preparedRead(DataSpec requested, DataSpec resolved) throws IOException {
        return new PreparedRead(requested, resolved);
    }

    private static Ahead scope(Uri original, Uri candidate) throws IOException {
        Ahead direct = ahead.get();
        if (direct != null) return direct.applies(original, candidate) ? direct : null;
        String token = warming.get();
        if (token == null) return null;
        synchronized (reads) {
            PreparedRead read = reads.get(token);
            if (read == null || read.closed || System.nanoTime() > read.until)
                throw new SourceAudio.Stopped(SourceAudio.StopReason.READ_PERMISSION);
            return read.scope.applies(original, candidate) ? read.scope : null;
        }
    }

    private static String decisionKey(Uri original, Uri candidate, Uri played) {
        return decisionKey(original, candidate, played, false);
    }

    private static String decisionKey(Uri original, Uri candidate, Uri played, boolean prepared) {
        original = SourcePreparation.withoutEntry(original);
        played = SourcePreparation.withoutEntry(played);
        return (prepared ? PREPARED : STRICT)
                + original.toString()
                + "\n"
                + candidate.toString()
                + "\n"
                + (played == null || played.equals(original) ? "" : played.toString());
    }

    /** One verification, surviving a caller that ran out of time. */
    private static final class Attempt {
        final ReentrantLock lock = new ReentrantLock();
        final long created = System.nanoTime();
        final String id = UUID.randomUUID().toString();
        String context;
        volatile long retryAt;
        int failures;
        int users;
        DataSpec expected;
        double durationA = -1, durationB = -1;

        // What the last report says about lengths and the window being read; measurements only.
        double lengthA = -1, lengthB = -1;
        // The window being read: side, 1-based number and range in milliseconds; null when none.
        String side;
        String probeA = "UNKNOWN", probeB = "UNKNOWN";
        int window, tries;
        long fromMillis, untilMillis;
        long advertisedA = -1, advertisedB = -1;
        long limitMillis = -1;
        boolean fellBack;
        final RecordingIdentity.Window[] a = new RecordingIdentity.Window[WINDOWS],
                b = new RecordingIdentity.Window[WINDOWS];

        int decoded() {
            int count = 0;
            for (int i = 0; i < WINDOWS; i++) {
                if (a[i] != null) count++;
                if (b[i] != null) count++;
            }
            return count;
        }
    }

    private static final Map<String, Attempt> attempts = new LinkedHashMap<>();

    /** Policies compare the same measured windows differently; they never share a verdict. */
    private static String attemptKey(String key) {
        if (key.startsWith(STRICT)) return key.substring(STRICT.length());
        if (key.startsWith(PREPARED)) return key.substring(PREPARED.length());
        return key;
    }

    /**
     * How long until a preparation check of this pair may be tried again after a failure that
     * proved nothing: 0 when it may run now, -1 when the pair was found to be another recording.
     */
    static long retryAfterMillis(DataSpec requested, DataSpec resolved) {
        if (rejectedPrepared(requested, resolved)) return -1;
        synchronized (attempts) {
            Attempt found =
                    attempts.get(attemptKey(decisionKey(requested.uri, resolved.uri, null, true)));
            long wait = found == null ? 0 : found.retryAt - retryClock.getAsLong();
            return wait <= 0 ? 0 : (wait + 999_999) / 1_000_000;
        }
    }

    private static Attempt attempt(String key) throws IOException {
        key = attemptKey(key);
        synchronized (attempts) {
            Attempt found = attempts.get(key);
            if (found != null
                    && found.users == 0
                    && System.nanoTime() - found.created > KEPT_NANOS) {
                attempts.remove(key);
                found = null;
            }
            if (found == null) {
                if (attempts.size() >= KEPT) {
                    java.util.Iterator<Attempt> entries = attempts.values().iterator();
                    while (entries.hasNext()) {
                        if (entries.next().users == 0) {
                            entries.remove();
                            break;
                        }
                    }
                }
                if (attempts.size() >= KEPT)
                    throw new IOException("Recording verification queue is full");
                found = new Attempt();
                attempts.put(key, found);
            }
            found.users++;
            return found;
        }
    }

    private static void releaseAttempt(String key, Attempt attempt) {
        synchronized (attempts) {
            attempt.users--;
        }
    }

    private static String identity(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
    }

    /** One copy being verified, read through a single source for all of its windows. */
    private static final class Copy {
        final DataSpec spec;
        final Uri local;
        final long deadline;
        final BooleanSupplier stale;
        final boolean background, upgrade;
        SourceAudio audio;
        String probe = "UNKNOWN";

        Copy(
                DataSpec spec,
                Uri local,
                long deadline,
                BooleanSupplier stale,
                boolean background,
                boolean upgrade) {
            this.background = background;
            this.upgrade = upgrade;
            this.spec = spec;
            this.local = local;
            this.deadline = deadline;
            this.stale = stale;
        }

        MediaDataSource open() throws IOException {
            if (audio == null)
                audio =
                        local != null
                                ? SourceAudio.whole(local, deadline, stale)
                                : background
                                        ? SourceAudio.preparation(spec, deadline, stale)
                                        : upgrade
                                                ? SourceAudio.verification(spec, deadline, stale)
                                                : new SourceAudio(spec, deadline, stale);
            return audio.view();
        }

        /** A read error was swallowed by the extractor and looked like the end of the file. */
        void intact() throws IOException {
            if (audio != null) {
                audio.rethrowFailure();
                audio.idle();
            }
        }

        /** After each extractor: its read errors were swallowed, and the connection can rest. */
        void finished(boolean gotNothing) throws IOException {
            intact();
            SourceAudio.checkLeased();
            if (System.nanoTime() > deadline)
                throw new SourceAudio.Stopped(SourceAudio.StopReason.DEADLINE);
            if (stale.getAsBoolean())
                throw new SourceAudio.Stopped(SourceAudio.StopReason.CANCELLED);
        }

        boolean expired() {
            return System.nanoTime() > deadline || stale.getAsBoolean();
        }

        void close() {
            if (audio == null) return;
            try {
                audio.close();
            } catch (IOException ignored) {
                // Only the connection, and the answer doesn't depend on it.
            }
        }

        String summary() {
            return audio == null
                    ? "-"
                    : (local != null ? "local, " : "")
                            + audio.opens()
                            + " requests, "
                            + audio.spent() / 1024
                            + " kB";
        }
    }

    private static volatile Method parkedLookup;

    /** Whether BitChord still has this upgrade's stream parked: a failed track forgets it. */
    private static boolean parked(Uri marked) {
        try {
            Method lookup = parkedLookup;
            if (lookup == null)
                parkedLookup =
                        lookup =
                                Class.forName("com.music.bitchord.playback.QualityUpgrade")
                                        .getMethod("forcedStream", Uri.class);
            return lookup.invoke(null, marked) != null;
        } catch (Exception unavailable) {
            return true;
        }
    }

    /**
     * A marked song URI only ever comes from a mid-song upgrade: the old copy is still playing, and
     * BitChord has the stream parked by its id. A source row's marked URI is resolved without one.
     */
    private static boolean upgrade(Uri original, String video) {
        String marker = original.getQueryParameter("q");
        return video != null && marker != null && marker.startsWith("hifi");
    }

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
        Ahead asked = scope(original, candidate);
        boolean prepared = asked != null && asked.prepared;
        if (prepared && asked.cancelled.getAsBoolean())
            throw new SourceAudio.Stopped(SourceAudio.StopReason.CANCELLED);
        if (prepared && asked.approvedRead) {
            synchronized (verifiedPrepared) {
                if (!verifiedPrepared.contains(aheadKey(original, candidate)))
                    throw new IOException(
                            "The cache read preparation proof is no longer available");
            }
            return resolved;
        }
        if (asked == null) {
            synchronized (verifiedAhead) {
                if (verifiedAhead.contains(aheadKey(original, candidate))) return resolved;
            }
            if (SourceFirst.isPreparedUri(original))
                synchronized (verifiedPrepared) {
                    if (verifiedPrepared.contains(aheadKey(original, candidate))) return resolved;
                }
        }
        if (video != null && YouTubePolicy.ready() && YouTubePolicy.playbackBlocked()) {
            // Initial playback uses the provider's metadata match without a YouTube audio
            // reference.
            if (asked == null && !upgrade(original, video)) return resolved;
            if (YouTubePolicy.catalogueBlocked())
                throw new IOException("Recording comparison needs YouTube Music enabled");
        }
        Uri played = mediaId == null || asked != null ? null : SourceAutomix.observed(mediaId);
        String key = decisionKey(original, candidate, played, prepared);
        Boolean known = decisions.get(key);
        if (known != null) return settled(known, key, mediaId, resolved, prepared);
        boolean upgrade = upgrade(original, video);
        long budget = upgrade ? UPGRADE_BUDGET : BUDGET;
        long deadline = System.nanoTime() + budget;
        BooleanSupplier cancelled =
                asked != null ? asked.cancelled : upgrade ? () -> !parked(original) : () -> false;
        Attempt attempt = attempt(key);
        // The shared permit precedes the per-recording lock, including analyzer re-entry.
        try (SourceAudio.Decoding decoding =
                asked != null && asked.background
                        ? SourceAudio.decodePreparation(deadline, cancelled)
                        : upgrade && asked == null
                                ? SourceAudio.decodeUpgrade(deadline, cancelled)
                                : SourceAudio.decodeVerification(deadline, cancelled)) {
            while (true) {
                decoding.check();
                if (attempt.lock.tryLock(
                        Math.min(deadline - System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(50)),
                        TimeUnit.NANOSECONDS)) break;
            }
            int priority = Process.getThreadPriority(Process.myTid());
            try {
                decoding.check();
                // Whoever held the lock may have answered this already.
                known = decisions.get(key);
                if (known != null) return settled(known, key, mediaId, resolved, prepared);
                if (attempt.retryAt > retryClock.getAsLong())
                    throw new IOException("Recording verification retry is deferred");
                attempt.context =
                        "track="
                                + (mediaId != null && mediaId.matches("[A-Za-z0-9_-]{11}")
                                        ? mediaId
                                        : identity(String.valueOf(mediaId)))
                                + " candidate="
                                + identity(candidate.toString())
                                + " pair="
                                + identity(key)
                                + " policy="
                                + (prepared ? "prepared" : "strict")
                                + " attempt="
                                + attempt.id;
                // Everything this starts, the extractor's and decoder's work included, yields to
                // the
                // player that's being listened to.
                if (asked != null ? asked.background : upgrade)
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                return verify(
                        attempt,
                        key,
                        mediaId,
                        video,
                        upgrade,
                        deadline - System.nanoTime(),
                        original,
                        played,
                        candidate,
                        resolved,
                        asked);
            } finally {
                Process.setThreadPriority(priority);
                attempt.lock.unlock();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Recording verification interrupted");
        } finally {
            releaseAttempt(key, attempt);
        }
    }

    private static DataSpec settled(
            boolean same, String key, String mediaId, DataSpec resolved, boolean prepared)
            throws IOException {
        if (!same) throw new IOException("Alternative audio did not verify as the same recording");
        Double offset = prepared ? null : offsets.get(key);
        if (mediaId != null && offset != null)
            pendingOffsets.put(mediaId, new double[] {offset * 1000, System.currentTimeMillis()});
        return resolved;
    }

    private static DataSpec verify(
            Attempt attempt,
            String key,
            String mediaId,
            String video,
            boolean upgrade,
            long budget,
            Uri original,
            Uri played,
            Uri candidate,
            DataSpec resolved,
            Ahead asked)
            throws IOException {
        verifying.set(true);
        SourceAutomix.enterQuiet();
        boolean conclusive = false;
        long started = System.nanoTime();
        boolean prepared = asked != null && asked.prepared;
        BooleanSupplier stale =
                asked != null ? asked.cancelled : upgrade ? () -> !parked(original) : () -> false;
        String stage = "reference";
        attempt.side = null;
        attempt.advertisedA = advertised(original, played);
        attempt.advertisedB = advertised(candidate);
        CheckReason reason = CheckReason.UNCLASSIFIED;
        Copy expectedCopy = null, resolvedCopy = null;
        try {
            Uri reference =
                    video != null
                            ? Uri.parse(
                                    "bitchord://watch?v=" + Uri.encode(video) + "&automix_opus=1")
                            : original.buildUpon().appendQueryParameter("mix", "any").build();
            if (played != null && !played.equals(original)) reference = played;
            // A whole copy already on the phone needs no connection, least of all one competing
            // with the player for the same stream.
            MediaDataSource whole =
                    attempt.expected == null ? AutomixAccess.cached(reference) : null;
            boolean local = whole != null;
            if (whole != null) whole.close();
            if (!local && attempt.expected == null) {
                attempt.expected = SourceAutomix.resolve(reference);
                if (attempt.expected.uri.equals(candidate)) {
                    decisions.put(key, true);
                    conclusive = true;
                    return resolved;
                }
            }
            long deadline = System.nanoTime() + budget;
            boolean background = asked != null && asked.background,
                    upgradeRead = upgrade && asked == null;
            expectedCopy =
                    new Copy(
                            attempt.expected,
                            local ? reference : null,
                            deadline,
                            stale,
                            background,
                            upgradeRead);
            DataSpec file = wholeFile(resolved);
            resolvedCopy = new Copy(file, null, deadline, stale, background, upgradeRead);
            stage = "length";
            if (attempt.durationA < 0) {
                attempt.durationA = duration(expectedCopy);
                attempt.probeA = expectedCopy.probe;
            }
            if (attempt.durationB < 0) {
                attempt.durationB = duration(resolvedCopy);
                attempt.probeB = resolvedCopy.probe;
            }
            double durationA = attempt.durationA, durationB = attempt.durationB;
            attempt.advertisedB = advertised(candidate);
            attempt.lengthA = durationA;
            attempt.lengthB = durationB;
            double limit =
                    prepared
                            ? RecordingIdentity.MAX_PREPARED_DURATION_DIFFERENCE
                            : RecordingIdentity.MAX_DURATION_DIFFERENCE;
            attempt.limitMillis = Math.round(limit * 1000);
            if (!(durationA >= RecordingIdentity.MIN_SECONDS
                    && durationB >= RecordingIdentity.MIN_SECONDS)) {
                if (attempt.durationA < RecordingIdentity.MIN_SECONDS) attempt.durationA = -1;
                if (attempt.durationB < RecordingIdentity.MIN_SECONDS) attempt.durationB = -1;
                reason = CheckReason.DURATION_INSUFFICIENT;
                throw new IOException("Not enough audio to verify an automatic source replacement");
            }
            if (Math.abs(durationA - durationB) > limit) {
                decisions.put(key, false);
                conclusive = true;
                reason = CheckReason.DURATION_MISMATCH;
                throw new IOException("Alternative recording has a different duration");
            }
            double[] points = {0, Math.max(0, durationA / 2 - 8), Math.max(0, durationA - 17)};
            for (int i = 0; i < points.length; i++) {
                stage = "window " + (i + 1);
                double until = Math.min(points[i] + 16, durationA);
                if (attempt.a[i] == null) {
                    try {
                        attempt.a[i] =
                                measured(
                                        attempt,
                                        "reference",
                                        i + 1,
                                        expectedCopy,
                                        points[i],
                                        until);
                    } catch (IOException failed) {
                        // A cached copy can vanish between two reads; the same stream is still
                        // there to read from the network, and its windows are comparable.
                        if (expectedCopy.local == null || !fallbackWorthy(failed)) throw failed;
                        expectedCopy.close();
                        attempt.expected = SourceAutomix.resolve(reference);
                        attempt.fellBack = true;
                        expectedCopy =
                                new Copy(
                                        attempt.expected,
                                        null,
                                        deadline,
                                        stale,
                                        background,
                                        upgradeRead);
                        // The windows read so far came from the cached copy. They stay only if the
                        // network rendition has the same length; otherwise they are dropped and
                        // the next attempt measures it afresh.
                        double network = duration(expectedCopy);
                        attempt.probeA = expectedCopy.probe;
                        if (!(Math.abs(network - durationA) <= RENDITION_TOLERANCE)) {
                            java.util.Arrays.fill(attempt.a, null);
                            attempt.durationA = -1;
                            throw new IOException("Audio rendition changed during analysis");
                        }
                        attempt.a[i] =
                                measured(
                                        attempt,
                                        "reference",
                                        i + 1,
                                        expectedCopy,
                                        points[i],
                                        until);
                    }
                }
                if (attempt.b[i] == null) {
                    until = Math.min(points[i] + 16, durationB);
                    attempt.b[i] =
                            measured(attempt, "candidate", i + 1, resolvedCopy, points[i], until);
                }
            }
            attempt.side = null;
            stage = "comparison";
            Double offset =
                    prepared
                            ? null
                            : RecordingIdentity.match(attempt.a, attempt.b, durationA, durationB);
            HostLog.d(
                    "Recording check policy "
                            + (prepared ? "prepared" : "strict")
                            + ": "
                            + RecordingIdentity.describe(
                                    attempt.a, attempt.b, durationA, durationB));
            // A mismatched/inconclusive replacement must leave the original playback intact.
            boolean same =
                    prepared
                            ? RecordingIdentity.matchesPrepared(
                                    attempt.a, attempt.b, durationA, durationB)
                            : offset != null;
            if (decisions.size() > 256) {
                String domain = prepared ? PREPARED : STRICT;
                int count = 0;
                for (String remembered : decisions.keySet())
                    if (remembered.startsWith(domain)) count++;
                if (count > 256) {
                    decisions.keySet().removeIf(remembered -> remembered.startsWith(domain));
                    if (!prepared) {
                        offsets.clear();
                        pendingOffsets.clear();
                    }
                }
            }
            decisions.put(key, same);
            conclusive = true;
            if (!same) {
                reason = CheckReason.IDENTITY_MISMATCH;
                throw new IOException("Alternative audio failed fingerprint/timeline verification");
            }
            // Only an upgrade measured against the actually selected old URI supplies a swap
            // offset.
            if (!prepared && played != null && !played.equals(original)) {
                offsets.put(key, offset);
                pendingOffsets.put(
                        mediaId, new double[] {offset * 1000, System.currentTimeMillis()});
            }
            HostLog.d("Automatic source replacement verified across three audio windows");
            if (!prepared)
                HostLog.d(
                        String.format(
                                Locale.US,
                                "Recording check measured a %+.0f ms shift; %s",
                                offset * 1000,
                                played != null && !played.equals(original)
                                        ? "the swap starts from it"
                                        : "not used: measured against YouTube, not what's"
                                                + " playing"));
            report(
                    "passed",
                    stage,
                    started,
                    attempt,
                    expectedCopy,
                    resolvedCopy,
                    null,
                    CheckReason.PASSED);
            return resolved;
        } catch (IOException error) {
            report("stopped", stage, started, attempt, expectedCopy, resolvedCopy, error, reason);
            // Completed windows belong to this exact pair; a partial read never becomes a window.
            if (!conclusive && !(error instanceof SourceAudio.Stopped)) {
                attempt.failures = Math.min(attempt.failures + 1, 5);
                attempt.retryAt =
                        retryClock.getAsLong()
                                + TimeUnit.MILLISECONDS.toNanos(
                                        Math.min(8000, 250L << attempt.failures));
            }
            throw error;
        } catch (Exception error) {
            report("stopped", stage, started, attempt, expectedCopy, resolvedCopy, error, reason);
            attempt.retryAt = retryClock.getAsLong() + TimeUnit.SECONDS.toNanos(1);
            throw new IOException("Could not verify automatic source replacement", error);
        } finally {
            if (expectedCopy != null) expectedCopy.close();
            if (resolvedCopy != null) resolvedCopy.close();
            if (!conclusive && !Boolean.TRUE.equals(decisions.get(key))) decisions.remove(key);
            verifying.remove();
            SourceAutomix.leaveQuiet();
        }
    }

    /** Stages and amounts only: never an address or a name. */
    private static void report(
            String outcome,
            String stage,
            long started,
            Attempt attempt,
            Copy expected,
            Copy resolved,
            Exception error,
            CheckReason reason) {
        HostLog.d(
                String.format(
                        Locale.US,
                        "Recording check %s at %s after %d ms, %d of %d windows; reference %s;"
                                + " candidate %s%s%s; %s",
                        outcome,
                        stage,
                        (System.nanoTime() - started) / 1_000_000,
                        attempt.decoded(),
                        WINDOWS * 2,
                        expected == null ? "-" : expected.summary(),
                        resolved == null ? "-" : resolved.summary(),
                        (error == null ? "" : "; " + error.getClass().getSimpleName())
                                + "; reason="
                                + (reason == CheckReason.UNCLASSIFIED
                                        ? diagnosticError(error)
                                        : reason.name()),
                        detail(attempt, error),
                        attempt.context));
    }

    /**
     * Machine-readable fields, always all present, -1 where unknown: measured lengths, their
     * difference and the limit, the lengths the providers advertised, how the lengths were found,
     * and the window that failed. Numbers and fixed words only.
     */
    private static String detail(Attempt attempt, Throwable error) {
        boolean known = attempt.lengthA > 0 && attempt.lengthB > 0;
        StringBuilder text = new StringBuilder();
        if (error != null) text.append("; cause=").append(diagnosticCause(error));
        if (error instanceof Unmeasurable) {
            Unmeasurable empty = (Unmeasurable) error;
            text.append("; decodedSamples=")
                    .append(empty.samples)
                    .append("; decodedRate=")
                    .append(empty.rate)
                    .append("; firstTimestampUs=")
                    .append(empty.firstTimestamp)
                    .append("; lastTimestampUs=")
                    .append(empty.lastTimestamp);
        }
        text.append(
                String.format(
                        Locale.US,
                        "; measuredReferenceMs=%d; measuredCandidateMs=%d; differenceMs=%d;"
                                + " limitMs=%d; advertisedReferenceMs=%d; advertisedCandidateMs=%d;"
                                + " probe=%s",
                        millis(attempt.lengthA),
                        millis(attempt.lengthB),
                        known ? Math.round(Math.abs(attempt.lengthA - attempt.lengthB) * 1000) : -1,
                        attempt.limitMillis,
                        attempt.advertisedA,
                        attempt.advertisedB,
                        probe(attempt)));
        text.append("; referenceProbe=")
                .append(attempt.probeA)
                .append("; candidateProbe=")
                .append(attempt.probeB);
        boolean failed = error != null && attempt.side != null;
        text.append(
                String.format(
                        Locale.US,
                        "; failingSide=%s; window=%d; fromMs=%d; untilMs=%d; try=%d",
                        failed ? attempt.side : "none",
                        failed ? attempt.window : -1,
                        failed ? attempt.fromMillis : -1,
                        failed ? attempt.untilMillis : -1,
                        failed ? attempt.tries : -1));
        if (attempt.fellBack) text.append("; reference fell back to a network copy");
        return text.toString();
    }

    private static long millis(double seconds) {
        return seconds > 0 ? Math.round(seconds * 1000) : -1;
    }

    /**
     * How the lengths were found. They come from the host decoder's containerDurationSeconds, which
     * gives a value only for a length read from timestamps or the stream itself and nothing for a
     * guess from the bitrate, so no further read is made to say more.
     */
    private static String probe(Attempt attempt) {
        if (attempt.lengthA < 0 || attempt.lengthB < 0) return "NONE";
        int exact = (attempt.lengthA > 0 ? 1 : 0) + (attempt.lengthB > 0 ? 1 : 0);
        return exact == 2
                ? "CONTAINER_EXACT"
                : exact == 1 ? "CONTAINER_PARTIAL" : "CONTAINER_UNKNOWN";
    }

    private static final Map<String, Long> advertisedFiles = new LinkedHashMap<>();

    static void noteAdvertised(String url, long seconds) {
        if (url == null) return;
        synchronized (advertisedFiles) {
            String key = identity(url);
            advertisedFiles.remove(key);
            if (seconds > 0 && seconds <= 86_400) advertisedFiles.put(key, seconds * 1000);
            while (advertisedFiles.size() > 128)
                advertisedFiles.remove(advertisedFiles.keySet().iterator().next());
        }
    }

    /** The length a provider advertised in a request's {@code d} (seconds), or -1. */
    static long advertised(Uri... uris) {
        for (Uri uri : uris) {
            try {
                if (uri == null || !uri.isHierarchical()) continue;
                synchronized (advertisedFiles) {
                    Long found = advertisedFiles.get(identity(uri.toString()));
                    if (found != null) return found;
                }
                String seconds = uri.getQueryParameter("d");
                if (seconds == null) continue;
                long found = Long.parseLong(seconds);
                if (found > 0 && found <= 86_400) return found * 1000;
            } catch (RuntimeException unusable) {
                // Not a number: treated as unknown.
            }
        }
        return -1;
    }

    static String diagnosticError(Throwable error) {
        if (error instanceof Unmeasurable) return ((Unmeasurable) error).reason;
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof SourceAudio.Stopped)
                return "STOP_" + ((SourceAudio.Stopped) current).reason.name();
            if (current.getClass().getSimpleName().equals("InvalidResponseCodeException")
                    || current.getClass()
                            .getSimpleName()
                            .equals("HttpDataSource$InvalidResponseCodeException")) {
                try {
                    int status = current.getClass().getField("responseCode").getInt(current);
                    if (status >= 100 && status <= 599) return "HTTP_" + status;
                } catch (ReflectiveOperationException unavailable) {
                }
            }
            if (current instanceof java.net.SocketTimeoutException) return "NETWORK_TIMEOUT";
            if (current instanceof java.net.UnknownHostException) return "DNS_FAILURE";
            if (current instanceof java.net.ConnectException) return "CONNECT_FAILURE";
            if (current instanceof javax.net.ssl.SSLException) return "TLS_FAILURE";
            if (current instanceof InterruptedIOException) return "IO_INTERRUPTED";
            if (current instanceof java.io.EOFException) return "UNEXPECTED_EOF";
            if (current instanceof java.io.FileNotFoundException) return "FILE_MISSING";
            if (current instanceof MediaCodec.CodecException) return "DECODE_FAILURE";
            if (current.getClass() == IOException.class) {
                // Fixed texts of this package's own readers; any other text stays unreported.
                String text = current.getMessage();
                if ("The cached copy has gone".equals(text)) return "CACHE_GONE";
                if ("Audio source returned no data".equals(text)) return "SOURCE_NO_DATA";
                if ("Audio connection is closed".equals(text)) return "CONNECTION_CLOSED";
                if ("Audio rendition changed during analysis".equals(text))
                    return "RENDITION_CHANGED";
                if ("Playback source router is not ready".equals(text))
                    return "SOURCE_ROUTER_UNAVAILABLE";
            }
            Throwable cause = current.getCause();
            if (cause == current) break;
            current = cause;
        }
        return error == null
                ? "NONE"
                : error instanceof IOException ? "IO_UNCLASSIFIED" : "INTERNAL_UNCLASSIFIED";
    }

    /** The decoder finished without a read failing, but produced nothing to compare. */
    private static final class Unmeasurable extends IOException {
        final String reason;
        final int samples, rate;
        final long firstTimestamp, lastTimestamp;

        Unmeasurable() {
            this("DECODE_INSUFFICIENT_AUDIO", -1, -1, -1, -1, null);
        }

        Unmeasurable(
                String reason,
                int samples,
                int rate,
                long firstTimestamp,
                long lastTimestamp,
                Throwable cause) {
            super("Recording verification is waiting for enough measurable audio", cause);
            this.reason = reason;
            this.samples = samples;
            this.rate = rate;
            this.firstTimestamp = firstTimestamp;
            this.lastTimestamp = lastTimestamp;
        }
    }

    private static RecordingIdentity.Window measurable(RecordingIdentity.Window window)
            throws IOException {
        if (window == null
                || window.seconds < RecordingIdentity.MIN_SECONDS
                || window.fingerprint.size() < 100) throw new Unmeasurable();
        return window;
    }

    private static final int WINDOW_TRIES = 2;

    /** How far a network rendition's length may differ from the cached copy's it replaces. */
    private static final double RENDITION_TOLERANCE = 0.05;

    static volatile long windowRetryMillis = 250;

    /** A window read, complete or not at all; a failed one is tried once more if that can help. */
    private static RecordingIdentity.Window measured(
            Attempt attempt, String side, int number, Copy copy, double from, double until)
            throws Exception {
        attempt.side = side;
        attempt.window = number;
        attempt.fromMillis = Math.round(from * 1000);
        attempt.untilMillis = Math.round(until * 1000);
        for (int tries = 1; ; tries++) {
            attempt.tries = tries;
            try {
                return measurable(window(side + " " + number, copy, from, until));
            } catch (IOException failed) {
                if (tries >= WINDOW_TRIES || !transientFailure(failed) || copy.expired())
                    throw failed;
                pause(copy);
            }
        }
    }

    private static void pause(Copy copy) throws IOException {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(windowRetryMillis);
        try {
            while (System.nanoTime() < until && !copy.expired())
                Thread.sleep(Math.max(1, Math.min(50, windowRetryMillis)));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Recording verification interrupted");
        }
    }

    /** Whether reading the same bytes again could succeed. */
    static boolean transientFailure(IOException error) {
        if (error instanceof SourceAudio.Stopped || error instanceof Unmeasurable) return false;
        String reason = diagnosticError(error);
        if (reason.startsWith("HTTP_")) {
            int status = Integer.parseInt(reason.substring(5));
            return status == 408 || status == 429 || status >= 500;
        }
        switch (reason) {
            case "FILE_MISSING":
            case "RENDITION_CHANGED":
            case "DECODE_FAILURE":
            case "CACHE_GONE":
            case "IO_INTERRUPTED":
                return false;
            default:
                return true;
        }
    }

    /** A cached reference that cannot be read on is replaced by the same stream's network copy. */
    private static boolean fallbackWorthy(IOException error) {
        return !(error instanceof SourceAudio.Stopped) && !(error instanceof Unmeasurable);
    }

    /** Type names along the cause chain; never a message, an address or a name. */
    static String diagnosticCause(Throwable error) {
        StringBuilder chain = new StringBuilder();
        Throwable current = error;
        for (int depth = 0; current != null && depth < 4; depth++) {
            String name = current.getClass().getSimpleName();
            if (depth > 0) chain.append('>');
            chain.append(name.isEmpty() ? "other" : name);
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return chain.toString();
    }

    /** A verified relative time shift for a same-recording upgrade, consumed by the host swap. */
    public static Long alignedOffset(Object service, Object sourceSong, Object targetSong) {
        try {
            Object source = SourceAutomix.field(sourceSong, "videoId"),
                    target = SourceAutomix.field(targetSong, "videoId");
            if (!(source instanceof String) || !source.equals(target)) return null;
            return verifiedOffset((String) source);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static Long peekVerifiedOffset(String mediaId) {
        double[] found = mediaId == null ? null : pendingOffsets.get(mediaId);
        if (found == null || System.currentTimeMillis() - found[1] > 60_000) return null;
        return Math.round(found[0]);
    }

    /** The verified shift for {@code mediaId}'s upgrade, in milliseconds, once; or null. */
    public static Long verifiedOffset(String mediaId) {
        double[] found = mediaId == null ? null : pendingOffsets.remove(mediaId);
        if (found == null || System.currentTimeMillis() - found[1] > 60_000) return null;
        return Math.round(found[0]);
    }

    /**
     * The file a request is a range of: the caller may only want a part, but this is about all of
     * it. By reflection, because the host keeps this method and has lost DataSpec.Builder's
     * setters.
     */
    static DataSpec wholeFile(DataSpec spec) throws ReflectiveOperationException {
        return (DataSpec)
                DataSpec.class
                        .getMethod("subrange", long.class, long.class)
                        .invoke(spec, -spec.position, -1L);
    }

    private static double duration(Copy copy) throws Exception {
        Object found;
        SourceAudio.takeDurationProbe();
        try (MediaDataSource source = copy.open()) {
            Method method =
                    Class.forName("com.music.bitchord.playback.smart.AudioDecoder")
                            .getDeclaredMethod("containerDurationSeconds", MediaDataSource.class);
            method.setAccessible(true);
            found = method.invoke(null, source);
        } finally {
            copy.probe = SourceAudio.takeDurationProbe();
        }
        copy.finished(!(found instanceof Number));
        return found instanceof Number ? ((Number) found).doubleValue() : 0;
    }

    private static RecordingIdentity.Window window(
            String name, Copy copy, double from, double until) throws Exception {
        long begun = System.nanoTime();
        long[] first = {0};
        long sourceBefore = copy.audio == null ? 0 : copy.audio.sourceNanos();
        Class<?> decoder = Class.forName("com.music.bitchord.playback.smart.AudioDecoder");
        Method mono =
                decoder.getDeclaredMethod(
                        "toMono", ByteBuffer.class, MediaCodec.BufferInfo.class, int.class);
        mono.setAccessible(true);
        float[][] samples = {null};
        SourceAudio.JavaMemory[] allocation = {null};
        int[] sampleRate = {0};
        int[] count = {0};
        long[] timestamps = {Long.MIN_VALUE, Long.MIN_VALUE};
        Exception[] callbackFailure = {null};
        String[] callbackReason = {"DECODE_CALLBACK_FAILURE"};
        Function4<ByteBuffer, MediaCodec.BufferInfo, Integer, Integer, Unit> tap =
                (bytes, info, channels, rate) -> {
                    if (first[0] == 0) first[0] = System.nanoTime();
                    try {
                        if (timestamps[0] == Long.MIN_VALUE)
                            timestamps[0] = info.presentationTimeUs;
                        timestamps[1] = info.presentationTimeUs;
                        if (allocation[0] == null) {
                            sampleRate[0] = rate;
                            allocation[0] =
                                    SourceAudio.reserveJava(verificationBytes(rate, until - from));
                            if (allocation[0] == null) {
                                callbackReason[0] = "MEMORY_ADMISSION";
                                throw new IllegalStateException(
                                        "Audio verification is waiting for memory");
                            }
                            samples[0] = new float[(int) Math.ceil(rate * (until - from)) + 65536];
                            allocation[0].materialized((long) samples[0].length * Float.BYTES);
                        }
                        if (rate != sampleRate[0])
                            throw new IllegalStateException(
                                    "Audio verification sample rate changed");
                        float[] chunk = (float[]) mono.invoke(null, bytes, info, channels);
                        if (chunk.length > samples[0].length - count[0])
                            throw new IllegalStateException("Audio verification window exceeded");
                        System.arraycopy(chunk, 0, samples[0], count[0], chunk.length);
                        count[0] += chunk.length;
                        return Unit.INSTANCE;
                    } catch (Exception error) {
                        callbackFailure[0] = error;
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
        try {
            Object timing;
            try (MediaDataSource source = copy.open()) {
                timing =
                        raw.invoke(
                                null, source, from, until, (Function0<Boolean>) copy::expired, tap);
            }
            long decoded = System.nanoTime();
            // A decode that ended without a stop is complete even if playback wants the device
            // now; its window is kept and the stop is noticed at the next read.
            if (timing == null || count[0] == 0) {
                copy.finished(true);
                throw new Unmeasurable(
                        callbackFailure[0] != null
                                ? callbackReason[0]
                                : count[0] == 0 ? "DECODE_NO_AUDIO" : "DECODE_INCOMPLETE_AUDIO",
                        count[0],
                        sampleRate[0],
                        timestamps[0] == Long.MIN_VALUE ? -1 : timestamps[0],
                        timestamps[1] == Long.MIN_VALUE ? -1 : timestamps[1],
                        callbackFailure[0]);
            }
            copy.intact();
            RecordingIdentity.Window window =
                    new RecordingIdentity.Window(
                            samples[0],
                            count[0],
                            ((Number) pairValue(timing, "first")).intValue(),
                            ((Number) pairValue(timing, "second")).doubleValue());
            long done = System.nanoTime();
            HostLog.d(
                    String.format(
                            Locale.US,
                            "Recording %s: to first audio %d ms, decode %d ms, source reads %d ms,"
                                    + " fingerprint %d ms, %.1f s of audio",
                            name,
                            (first[0] - begun) / 1_000_000,
                            (decoded - first[0]) / 1_000_000,
                            ((copy.audio == null ? 0 : copy.audio.sourceNanos()) - sourceBefore)
                                    / 1_000_000,
                            (done - decoded) / 1_000_000,
                            window.seconds));
            return window;
        } finally {
            if (allocation[0] != null) allocation[0].close();
        }
    }

    static long verificationBytes(int rate, double seconds) {
        if (rate <= 0 || rate > 768000 || !Double.isFinite(seconds) || seconds <= 0 || seconds > 24)
            return -1;
        // One mono window, one decoder frame, source blocks and fingerprint work.
        double bytes = (Math.ceil(rate * seconds) + 65536) * 4 + (12L << 20);
        return bytes >= Long.MAX_VALUE ? -1 : (long) bytes;
    }

    static Object pairValue(Object pair, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field field = pair.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(pair);
    }
}

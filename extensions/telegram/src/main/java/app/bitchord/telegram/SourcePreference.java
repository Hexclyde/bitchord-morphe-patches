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
        DataSpec expected;
        double durationA = -1, durationB = -1;
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

    private static final Map<String, Attempt> attempts =
            new LinkedHashMap<String, Attempt>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Attempt> eldest) {
                    return size() > KEPT;
                }
            };

    private static Attempt attempt(String key) {
        synchronized (attempts) {
            Attempt found = attempts.get(key);
            if (found == null
                    || (System.nanoTime() - found.created > KEPT_NANOS && !found.lock.isLocked())) {
                found = new Attempt();
                attempts.put(key, found);
            }
            return found;
        }
    }

    private static void forget(String key) {
        synchronized (attempts) {
            attempts.remove(key);
        }
    }

    /** One copy being verified, read through a single source for all of its windows. */
    private static final class Copy {
        final DataSpec spec;
        final Uri local;
        final long deadline;
        final BooleanSupplier stale;
        SourceAudio audio;

        Copy(DataSpec spec, Uri local, long deadline, BooleanSupplier stale) {
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
                                : new SourceAudio(spec, deadline, stale);
            return audio.view();
        }

        /** After each extractor: its read errors were swallowed, and the connection can rest. */
        void finished(boolean gotNothing) throws IOException {
            if (audio != null) {
                audio.rethrowFailure();
                audio.idle();
            }
            // What did arrive is kept, however late: the next attempt needn't read it again.
            if (gotNothing) {
                if (System.nanoTime() > deadline)
                    throw new SourceAudio.Stopped(SourceAudio.StopReason.DEADLINE);
                if (stale.getAsBoolean())
                    throw new SourceAudio.Stopped(SourceAudio.StopReason.CANCELLED);
            }
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
        try (SourceAudio.Decoding decoding = SourceAudio.decode(deadline, cancelled)) {
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
            expectedCopy = new Copy(attempt.expected, local ? reference : null, deadline, stale);
            DataSpec file = wholeFile(resolved);
            resolvedCopy = new Copy(file, null, deadline, stale);
            stage = "length";
            if (attempt.durationA < 0) attempt.durationA = duration(expectedCopy);
            if (attempt.durationB < 0) attempt.durationB = duration(resolvedCopy);
            double durationA = attempt.durationA, durationB = attempt.durationB;
            if (!(durationA >= RecordingIdentity.MIN_SECONDS
                    && durationB >= RecordingIdentity.MIN_SECONDS)) {
                reason = CheckReason.DURATION_INSUFFICIENT;
                throw new IOException("Not enough audio to verify an automatic source replacement");
            }
            if (Math.abs(durationA - durationB) > RecordingIdentity.MAX_DURATION_DIFFERENCE) {
                decisions.put(key, false);
                conclusive = true;
                reason = CheckReason.DURATION_MISMATCH;
                throw new IOException("Alternative recording has a different duration");
            }
            double[] points = {0, Math.max(0, durationA / 2 - 8), Math.max(0, durationA - 17)};
            for (int i = 0; i < points.length; i++) {
                stage = "window " + (i + 1);
                if (attempt.a[i] == null)
                    attempt.a[i] =
                            measurable(
                                    window(
                                            "reference " + (i + 1),
                                            expectedCopy,
                                            points[i],
                                            Math.min(points[i] + 16, durationA)));
                if (attempt.b[i] == null)
                    attempt.b[i] =
                            measurable(
                                    window(
                                            "candidate " + (i + 1),
                                            resolvedCopy,
                                            points[i],
                                            Math.min(points[i] + 16, durationB)));
            }
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
            // Time ran out or nobody's waiting: what was decoded is still good for the next caller.
            if (!(error instanceof SourceAudio.Stopped)) forget(key);
            throw error;
        } catch (Exception error) {
            report("stopped", stage, started, attempt, expectedCopy, resolvedCopy, error, reason);
            forget(key);
            throw new IOException("Could not verify automatic source replacement", error);
        } finally {
            if (expectedCopy != null) expectedCopy.close();
            if (resolvedCopy != null) resolvedCopy.close();
            if (conclusive) forget(key);
            else if (!Boolean.TRUE.equals(decisions.get(key))) decisions.remove(key);
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
                                + " candidate %s%s",
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
                                        : reason.name())));
    }

    static String diagnosticError(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof SourceAudio.Stopped)
                return "STOP_" + ((SourceAudio.Stopped) current).reason.name();
            if (current instanceof java.net.SocketTimeoutException) return "NETWORK_TIMEOUT";
            if (current instanceof java.net.UnknownHostException) return "DNS_FAILURE";
            if (current instanceof java.net.ConnectException) return "CONNECT_FAILURE";
            if (current instanceof javax.net.ssl.SSLException) return "TLS_FAILURE";
            if (current instanceof InterruptedIOException) return "IO_INTERRUPTED";
            if (current instanceof java.io.EOFException) return "UNEXPECTED_EOF";
            if (current instanceof java.io.FileNotFoundException) return "FILE_MISSING";
            if (current instanceof MediaCodec.CodecException) return "DECODE_FAILURE";
            Throwable cause = current.getCause();
            if (cause == current) break;
            current = cause;
        }
        return error == null
                ? "NONE"
                : error instanceof IOException ? "IO_UNCLASSIFIED" : "INTERNAL_UNCLASSIFIED";
    }

    private static RecordingIdentity.Window measurable(RecordingIdentity.Window window)
            throws IOException {
        if (window == null
                || window.seconds < RecordingIdentity.MIN_SECONDS
                || window.fingerprint.size() < 100)
            throw new IOException("Recording verification is waiting for enough measurable audio");
        return window;
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
        try (MediaDataSource source = copy.open()) {
            Method method =
                    Class.forName("com.music.bitchord.playback.smart.AudioDecoder")
                            .getDeclaredMethod("containerDurationSeconds", MediaDataSource.class);
            method.setAccessible(true);
            found = method.invoke(null, source);
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
        ArrayList<float[]> chunks = new ArrayList<>();
        int[] count = {0};
        Function4<ByteBuffer, MediaCodec.BufferInfo, Integer, Integer, Unit> tap =
                (bytes, info, channels, rate) -> {
                    if (first[0] == 0) first[0] = System.nanoTime();
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
        Object timing;
        try (MediaDataSource source = copy.open()) {
            timing = raw.invoke(null, source, from, until, (Function0<Boolean>) copy::expired, tap);
        }
        long decoded = System.nanoTime();
        copy.finished(timing == null || count[0] == 0);
        if (timing == null || count[0] == 0) return null;
        float[] pcm = new float[count[0]];
        int at = 0;
        for (float[] chunk : chunks) {
            System.arraycopy(chunk, 0, pcm, at, chunk.length);
            at += chunk.length;
        }
        RecordingIdentity.Window window =
                new RecordingIdentity.Window(
                        pcm,
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
    }

    static Object pairValue(Object pair, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field field = pair.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(pair);
    }
}

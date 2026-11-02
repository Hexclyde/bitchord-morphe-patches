package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.net.Uri;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import com.music.bitchord.data.sources.SourceStream;
import com.music.bitchord.data.sources.TrackMatcher;
import com.music.bitchord.playback.PlaybackService;
import com.music.bitchord.playback.QualityUpgrade;
import com.music.bitchord.playback.smart.AudioDecoder;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class SourcePreferenceTest {
    enum Kind {
        YOUTUBE,
        ADDON,
        LOCAL
    }

    public static class Source {
        Kind kind;

        Source(Kind kind) {
            this.kind = kind;
        }

        public Kind getKind() {
            return kind;
        }
    }

    static class Format {
        String codec = "AAC";
        Integer kbps = 256, bitDepth = null, sampleRateHz = 44100;
    }

    static class Callback {
        Object f$0 = new Format(), f$1 = new TrackMatcher.Target();
    }

    static class Song {
        String videoId;

        Song(String id) {
            videoId = id;
        }
    }

    @Test
    public void enabledAlternativesKeepTheirRelativeOrderAheadOfYouTube() {
        Object youtube = new Source(Kind.YOUTUBE),
                telegram = new Source(Kind.ADDON),
                local = new Source(Kind.LOCAL);
        ArrayList<Object> original = new ArrayList<>(Arrays.asList(youtube, telegram, local));
        assertEquals(Arrays.asList(telegram, local, youtube), SourcePreference.rank(original));
        assertEquals(Arrays.asList(youtube, telegram, local), original);
    }

    @Test
    public void equalKnownFormatCanReplaceYouTubeButDowngradesAreNotNewlyAccepted() {
        Callback callback = new Callback();
        Format candidate = new Format();
        SourceStream stream = new SourceStream(candidate);
        assertEquals(Boolean.TRUE, SourcePreference.equalUpgrade(callback, null, stream));
        candidate.kbps = 128;
        assertNull(SourcePreference.equalUpgrade(callback, null, stream));
        candidate.kbps = 256;
        candidate.codec = "MP3";
        assertNull(SourcePreference.equalUpgrade(callback, null, stream));
        candidate.codec = "AAC";
        candidate.kbps = null;
        assertNull(SourcePreference.equalUpgrade(callback, null, stream));
        assertNull(SourcePreference.equalUpgrade(callback, null, new Object()));
    }

    @Test
    public void swapOffsetsAreScopedConsumedAndExpire() throws Exception {
        java.lang.reflect.Field field = SourcePreference.class.getDeclaredField("pendingOffsets");
        field.setAccessible(true);
        Map<String, double[]> offsets = (Map<String, double[]>) field.get(null);
        offsets.clear();
        offsets.put("a", new double[] {250, System.currentTimeMillis()});
        assertNull(SourcePreference.alignedOffset(null, new Song("a"), new Song("b")));
        assertEquals(
                Long.valueOf(250),
                SourcePreference.alignedOffset(null, new Song("a"), new Song("a")));
        assertNull(SourcePreference.alignedOffset(null, new Song("a"), new Song("a")));
        offsets.put("a", new double[] {250, System.currentTimeMillis() - 61_000});
        assertNull(SourcePreference.alignedOffset(null, new Song("a"), new Song("a")));
    }

    @Test
    public void explicitFilesAndPickedRowsDoNotRequireAYouTubeLookup() throws Exception {
        for (String uri :
                new String[] {
                    "file:///a.flac",
                    "content://music/42",
                    "bitchord://source?s=telegram-native&t=12_34&mix=stereo"
                }) {
            DataSpec request = new DataSpec(Uri.parse(uri)),
                    resolved = new DataSpec(Uri.parse("bctg://audio/12_34"));
            assertSame(resolved, SourcePreference.guard(request, resolved));
        }
    }

    static final class ShrunkPair {
        private final Object first = 48000;
        private final Object second = 16.0;
    }

    @Test
    public void decoderTimingUsesFieldsWhenHostPairGettersHaveBeenRemoved() throws Exception {
        ShrunkPair timing = new ShrunkPair();
        assertEquals(48000, ((Number) SourcePreference.pairValue(timing, "first")).intValue());
        assertEquals(
                16.0, ((Number) SourcePreference.pairValue(timing, "second")).doubleValue(), 0);
        kotlin.Pair<Integer, Double> unshrunk = new kotlin.Pair<>(44100, 8.0);
        assertEquals(44100, ((Number) SourcePreference.pairValue(unshrunk, "first")).intValue());
        assertEquals(
                8.0, ((Number) SourcePreference.pairValue(unshrunk, "second")).doubleValue(), 0);
    }

    // ── A whole verification, over the native read pattern ──────────────────

    static final String PLAYED = "bitchord://watch?v=song",
            MARKED = "bitchord://watch?v=song&q=hifi-1",
            REFERENCE = "https://yt.invalid/reference",
            CANDIDATE = "https://telegram.bitchord.invalid/tag/audio/1_2";

    final Map<String, byte[]> files = new ConcurrentHashMap<>();
    final Map<String, AtomicInteger> opens = new ConcurrentHashMap<>();
    final AtomicInteger resolves = new AtomicInteger();
    java.util.function.Supplier<SourceAudio.AnalysisFormat> originalAnalysisFormats;

    class Remote implements DataSource {
        byte[] file;
        int position, end;
        Uri uri;

        public void addTransferListener(TransferListener ignored) {}

        public long open(DataSpec spec) throws IOException {
            uri = spec.uri;
            opens.computeIfAbsent(uri.toString(), k -> new AtomicInteger()).incrementAndGet();
            file = files.get(uri.toString());
            if (file == null) throw new IOException("Not found");
            position = (int) spec.position;
            end =
                    spec.length == androidx.media3.common.C.LENGTH_UNSET
                            ? file.length
                            : (int) Math.min(file.length, spec.position + spec.length);
            return end - position;
        }

        public int read(byte[] b, int o, int n) {
            if (position >= end) return -1;
            int count = Math.min(n, end - position);
            System.arraycopy(file, position, b, o, count);
            position += count;
            return count;
        }

        public Uri getUri() {
            return uri;
        }

        public Map<String, List<String>> getResponseHeaders() {
            return Collections.emptyMap();
        }

        public void close() {}
    }

    static void clear(String field) throws Exception {
        java.lang.reflect.Field f = SourcePreference.class.getDeclaredField(field);
        f.setAccessible(true);
        ((Map<?, ?>) f.get(null)).clear();
    }

    @Before
    public void network() throws Exception {
        originalAnalysisFormats = SourceAudio.analysisFormats;
        SourceAudio.analysisFormats = () -> SourceAutomixTest.format(48000, 120_000_000);
        for (String field : new String[] {"decisions", "offsets", "pendingOffsets", "attempts"})
            clear(field);
        for (String name : new String[] {"verifiedAhead", "verifiedPrepared"}) {
            java.lang.reflect.Field trusted = SourcePreference.class.getDeclaredField(name);
            trusted.setAccessible(true);
            ((Set<?>) trusted.get(null)).clear();
        }
        files.clear();
        opens.clear();
        resolves.set(0);
        AudioDecoder.windows.clear();
        AudioDecoder.corrupt = false;
        AudioDecoder.afterWindow = null;
        QualityUpgrade.parked = true;
        PlaybackService.stopped = false;
        PlaybackService.cached = null;
        PlaybackService.source = Remote::new;
        PlaybackService.resolver =
                uri -> {
                    resolves.incrementAndGet();
                    return new DataSpec(Uri.parse(REFERENCE));
                };
        SourceAutomix.service(this);
        java.lang.reflect.Field seen = SourceAutomix.class.getDeclaredField("observed");
        seen.setAccessible(true);
        ((Map<String, Uri>) seen.get(null)).put("song", Uri.parse(PLAYED));
        files.put(REFERENCE, AudioDecoder.file(91, 0, 180, 0, true));
        files.put(CANDIDATE, AudioDecoder.file(91, 0, 180, 250, false));
    }

    @After
    public void restoreAnalysisFormats() {
        SourceAudio.analysisFormats = originalAnalysisFormats;
    }

    DataSpec verify() throws IOException {
        DataSpec resolved = new DataSpec(Uri.parse(CANDIDATE));
        assertSame(resolved, SourcePreference.guard(new DataSpec(Uri.parse(MARKED)), resolved));
        return resolved;
    }

    @Test
    public void verificationAndAutomixCannotDecodeAtTheSameTime() throws Exception {
        CountDownLatch verifying = new CountDownLatch(1), finish = new CountDownLatch(1);
        CountDownLatch analyzing = new CountDownLatch(1);
        AtomicInteger windows = new AtomicInteger();
        AudioDecoder.afterWindow =
                done -> {
                    if (windows.getAndIncrement() != 0) return;
                    verifying.countDown();
                    try {
                        if (!finish.await(10, TimeUnit.SECONDS))
                            throw new AssertionError("Verification was not released");
                    } catch (InterruptedException interrupted) {
                        throw new AssertionError(interrupted);
                    }
                };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        SourceAutomixTest.Callback callback = new SourceAutomixTest.Callback();
        callback.f$0.trackAnalyzer.executor.background = executor;
        callback.f$0.trackAnalyzer.probe = id -> analyzing.countDown();
        try {
            Future<?> checked =
                    executor.submit(
                            () -> {
                                verify();
                                return null;
                            });
            assertTrue(verifying.await(10, TimeUnit.SECONDS));
            SourceAutomix.request(
                    callback,
                    new androidx.media3.common.MediaItem.Builder()
                            .setMediaId("other-recording")
                            .setUri("content://music/other")
                            .build(),
                    120000L);
            assertFalse(
                    "Analyzer must wait for the verification PCM to be released",
                    analyzing.await(200, TimeUnit.MILLISECONDS));
            finish.countDown();
            checked.get(30, TimeUnit.SECONDS);
            assertTrue(analyzing.await(10, TimeUnit.SECONDS));
        } finally {
            finish.countDown();
            AudioDecoder.afterWindow = null;
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    public void queuedVerificationDoesNotDeadlockNestedAnalysisVerification() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch owned = new CountDownLatch(1), nested = new CountDownLatch(1);
        CountDownLatch competitor = new CountDownLatch(1);
        Future<?> owner =
                executor.submit(
                        () -> {
                            try (SourceAudio.Decoding permit =
                                    SourceAudio.decode(
                                            System.nanoTime() + TimeUnit.SECONDS.toNanos(20),
                                            () -> false)) {
                                owned.countDown();
                                assertTrue(nested.await(10, TimeUnit.SECONDS));
                                verify();
                            }
                            return null;
                        });
        Future<?> waiter = null;
        try {
            assertTrue(owned.await(5, TimeUnit.SECONDS));
            waiter =
                    executor.submit(
                            () -> {
                                competitor.countDown();
                                verify();
                                return null;
                            });
            assertTrue(competitor.await(5, TimeUnit.SECONDS));
            try {
                waiter.get(200, TimeUnit.MILLISECONDS);
                fail("The other decoder is admitted");
            } catch (TimeoutException expected) {
            }
            nested.countDown();
            owner.get(15, TimeUnit.SECONDS);
            waiter.get(5, TimeUnit.SECONDS);
            assertEquals(
                    "Nested verification ran once and the waiter reused it",
                    6,
                    AudioDecoder.windows.size());
        } finally {
            nested.countDown();
            owner.cancel(true);
            if (waiter != null) waiter.cancel(true);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    public void aCancelledVerificationLeavesAdmissionWithoutReadingAudio() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean cancelled =
                new java.util.concurrent.atomic.AtomicBoolean();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch waiting = new CountDownLatch(1);
        try (SourceAudio.Decoding permit =
                SourceAudio.decode(System.nanoTime() + TimeUnit.SECONDS.toNanos(10), () -> false)) {
            Future<?> checked =
                    executor.submit(
                            () -> {
                                waiting.countDown();
                                try {
                                    SourcePreference.verifyPrepared(
                                            plain(),
                                            new DataSpec(Uri.parse(CANDIDATE)),
                                            cancelled::get,
                                            true);
                                    fail("Cancelled work must not decode");
                                } catch (SourceAudio.Stopped expected) {
                                }
                                return null;
                            });
            assertTrue(waiting.await(5, TimeUnit.SECONDS));
            try {
                checked.get(150, TimeUnit.MILLISECONDS);
                fail("The other decoder is admitted");
            } catch (TimeoutException expected) {
            }
            cancelled.set(true);
            checked.get(2, TimeUnit.SECONDS);
            assertTrue(AudioDecoder.windows.isEmpty());
            assertTrue("No source was opened while waiting", opens.isEmpty());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void coroutineResolutionCompletesBeforeAnalysisOwnsAdmission() throws Exception {
        ExecutorService resolver = Executors.newSingleThreadExecutor();
        ExecutorService analysis = Executors.newSingleThreadExecutor();
        SourceAutomixTest.Callback callback = new SourceAutomixTest.Callback();
        callback.f$0.trackAnalyzer.executor.background = analysis;
        CountDownLatch analyzed = new CountDownLatch(1);
        callback.f$0.trackAnalyzer.probe = id -> analyzed.countDown();
        PlaybackService.resolver =
                uri -> {
                    if ("content".equals(uri.getScheme())) {
                        try {
                            resolver.submit(
                                            () -> {
                                                verify();
                                                return null;
                                            })
                                    .get(15, TimeUnit.SECONDS);
                        } catch (Exception failure) {
                            throw new IllegalStateException(failure);
                        }
                    }
                    return new DataSpec(Uri.parse(REFERENCE));
                };
        try {
            SourceAutomix.request(
                    callback,
                    new androidx.media3.common.MediaItem.Builder()
                            .setMediaId("resolved-off-thread")
                            .setUri("content://music/resolve-first")
                            .build(),
                    120000L);
            assertTrue(
                    "A resolver coroutine must not wait on its owning analyzer",
                    analyzed.await(20, TimeUnit.SECONDS));
            assertEquals(6, AudioDecoder.windows.size());
        } finally {
            SourceAutomix.release(callback.f$0);
            analysis.shutdownNow();
            resolver.shutdownNow();
            assertTrue(analysis.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(resolver.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    void assertRefused(String words) {
        try {
            verify();
            fail("A recording that isn't the same must not be accepted");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(words));
        }
    }

    int opensOf(String uri) {
        AtomicInteger count = opens.get(uri);
        return count == null ? 0 : count.get();
    }

    @Test
    public void aVerifiedUpgradeTakesAFewRequestsNotOnePerExtractorRead() throws Exception {
        verify();
        assertFalse("Bytes reach the decoder intact", AudioDecoder.corrupt);
        assertEquals(
                "Three windows each side: " + AudioDecoder.windows, 6, AudioDecoder.windows.size());
        assertTrue("Reference requests: " + opensOf(REFERENCE), opensOf(REFERENCE) <= 8);
        assertTrue("Candidate requests: " + opensOf(CANDIDATE), opensOf(CANDIDATE) <= 8);
        int before = opensOf(REFERENCE) + opensOf(CANDIDATE);
        verify();
        assertEquals("The answer is kept", before, opensOf(REFERENCE) + opensOf(CANDIDATE));
    }

    @Test
    public void theVerifiedOffsetSaysWhereTheNewCopyHasTheSameMoment() throws Exception {
        verify();
        Long offset = SourcePreference.alignedOffset(null, new Song("song"), new Song("song"));
        assertNotNull(offset);
        assertTrue("A copy 250 ms later: " + offset, Math.abs(offset - 250) < 60);
    }

    @Test
    public void aCopyTwoSecondsLongerIsRefusedAndStaysRefused() throws Exception {
        files.put(CANDIDATE, AudioDecoder.file(91, 0, 182, 0, false));
        assertRefused("different duration");
        assertTrue("Length alone decides: " + AudioDecoder.windows, AudioDecoder.windows.isEmpty());
        int before = opensOf(CANDIDATE);
        assertRefused("did not verify");
        assertEquals(before, opensOf(CANDIDATE));
    }

    @Test
    public void anotherRecordingOfTheSameLengthIsRefused() throws Exception {
        files.put(CANDIDATE, AudioDecoder.file(817, 0, 180, 0, false));
        assertRefused("fingerprint");
    }

    @Test
    public void aMatchingIntroAndOutroCannotHideAChangedMiddle() throws Exception {
        files.put(CANDIDATE, AudioDecoder.file(91, 817, 180, 0, false));
        assertRefused("fingerprint");
    }

    @Test
    public void aSecondAttemptContinuesWhereTheLastRanOutOfTime() throws Exception {
        AudioDecoder.afterWindow =
                done -> {
                    if (done == 3) PlaybackService.stopped = true;
                };
        try {
            verify();
            fail("It was stopped");
        } catch (IOException expected) {
            assertFalse(expected.getMessage(), expected.getMessage().contains("fingerprint"));
        }
        PlaybackService.stopped = false;
        AudioDecoder.afterWindow = null;
        int soFar = AudioDecoder.windows.size();
        verify();
        assertEquals(
                "Windows already decoded aren't decoded again: " + AudioDecoder.windows,
                6,
                AudioDecoder.windows.size());
        assertTrue(soFar >= 3);
    }

    @Test
    public void aSecondCallerWaitsForTheVerificationAlreadyRunning() throws Exception {
        CountDownLatch running = new CountDownLatch(1), second = new CountDownLatch(1);
        AudioDecoder.afterWindow =
                done -> {
                    running.countDown();
                    try {
                        second.await(200, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ignored) {
                    }
                };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> verify());
            assertTrue(running.await(30, TimeUnit.SECONDS));
            Future<?> other = pool.submit(() -> verify());
            first.get(60, TimeUnit.SECONDS);
            other.get(60, TimeUnit.SECONDS);
        } finally {
            second.countDown();
            pool.shutdownNow();
        }
        assertEquals("One run for both: " + AudioDecoder.windows, 6, AudioDecoder.windows.size());
    }

    @Test
    public void verificationStopsOnceTheUpgradeHasBeenForgotten() throws Exception {
        AudioDecoder.afterWindow = done -> QualityUpgrade.parked = false;
        try {
            verify();
            fail("Nothing is waiting for this answer any more");
        } catch (IOException expected) {
            assertFalse(expected.getMessage(), expected.getMessage().contains("fingerprint"));
        }
        assertTrue("Stopped at once: " + AudioDecoder.windows, AudioDecoder.windows.size() <= 2);
    }

    @Test
    public void aFailedReadIsReportedNotTakenForTooLittleAudio() throws Exception {
        long[] moved = {0};
        PlaybackService.source =
                () -> {
                    Remote remote = new Remote();
                    return new DataSource() {
                        public void addTransferListener(TransferListener ignored) {}

                        public long open(DataSpec spec) throws IOException {
                            return remote.open(spec);
                        }

                        public int read(byte[] b, int o, int n) throws IOException {
                            boolean candidate = remote.uri.toString().equals(CANDIDATE);
                            if (candidate && moved[0] > 400_000)
                                throw new IOException("Connection lost");
                            int got = remote.read(b, o, n);
                            if (candidate) moved[0] += Math.max(0, got);
                            return got;
                        }

                        public Uri getUri() {
                            return remote.getUri();
                        }

                        public Map<String, List<String>> getResponseHeaders() {
                            return Collections.emptyMap();
                        }

                        public void close() {}
                    };
                };
        try {
            verify();
            fail("A window that didn't arrive can't verify anything");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Connection lost"));
        }
        PlaybackService.source = Remote::new;
        verify();
    }

    @Test
    public void aCompleteLocalCopyOfTheReferenceNeedsNoRequestOrResolve() throws Exception {
        byte[] whole = files.remove(REFERENCE);
        PlaybackService.cached =
                uri ->
                        new android.media.MediaDataSource() {
                            public int readAt(long at, byte[] out, int offset, int size) {
                                if (at >= whole.length) return -1;
                                int n = (int) Math.min(size, whole.length - at);
                                System.arraycopy(whole, (int) at, out, offset, n);
                                return n;
                            }

                            public long getSize() {
                                return whole.length;
                            }

                            public void close() {}
                        };
        verify();
        assertEquals(0, opensOf(REFERENCE));
        assertEquals(0, resolves.get());
        assertEquals(6, AudioDecoder.windows.size());
    }

    @Test
    public void verifyingAnUpgradeYieldsToThePlayerAndPutsThePriorityBack() throws Exception {
        int before = android.os.Process.getThreadPriority(android.os.Process.myTid());
        int[] during = {before};
        AudioDecoder.afterWindow =
                done ->
                        during[0] =
                                android.os.Process.getThreadPriority(android.os.Process.myTid());
        verify();
        assertEquals(android.os.Process.THREAD_PRIORITY_BACKGROUND, during[0]);
        assertEquals(before, android.os.Process.getThreadPriority(android.os.Process.myTid()));
    }

    @Test
    public void aVerifiedOffsetIsTakenOnce() throws Exception {
        verify();
        assertNotNull(SourcePreference.verifiedOffset("song"));
        assertNull(SourcePreference.verifiedOffset("song"));
        assertNull(SourcePreference.verifiedOffset(null));
        verify();
        assertNotNull(
                "A later open of the proved stream offers it again",
                SourcePreference.verifiedOffset("song"));
    }

    @Test
    public void aSourceRowHasNoParkedStreamToBeForgotten() throws Exception {
        QualityUpgrade.parked = false;
        DataSpec resolved = new DataSpec(Uri.parse(CANDIDATE));
        assertSame(
                resolved,
                SourcePreference.guard(
                        new DataSpec(Uri.parse("bitchord://source?s=addon&t=7&q=hifi-1")),
                        resolved));
        assertEquals(6, AudioDecoder.windows.size());
    }

    /** CacheWriter's first open is bounded, and a failure makes it read the whole file. */
    @Test
    public void aBoundedFirstOpenStillVerifiesTheWholeFile() throws Exception {
        DataSpec requested = new DataSpec(Uri.parse(MARKED)).subrange(0, 65536),
                resolved = new DataSpec(Uri.parse(CANDIDATE)).subrange(0, 65536);
        assertEquals(65536, resolved.length);
        assertSame(resolved, SourcePreference.guard(requested, resolved));
        assertEquals(6, AudioDecoder.windows.size());
    }

    /** The player reopens part-way through after a seek. */
    @Test
    public void anOpenPartWayThroughStillVerifiesFromTheStartOfTheFile() throws Exception {
        DataSpec requested = new DataSpec(Uri.parse(MARKED)).subrange(1_000_000),
                resolved = new DataSpec(Uri.parse(CANDIDATE)).subrange(1_000_000);
        assertEquals(1_000_000, resolved.position);
        assertSame(resolved, SourcePreference.guard(requested, resolved));
        assertEquals(6, AudioDecoder.windows.size());
        assertFalse(AudioDecoder.corrupt);
    }

    /** What BitChord's own cached reader does: it reopens the cache on every jump. */
    class CachedCopy extends android.media.MediaDataSource {
        final byte[] whole;
        final AtomicInteger jumps;
        long next = -1;

        CachedCopy(byte[] whole, AtomicInteger jumps) {
            this.whole = whole;
            this.jumps = jumps;
        }

        public int readAt(long at, byte[] out, int offset, int size) {
            if (at != next) jumps.incrementAndGet();
            if (at >= whole.length) return -1;
            int n = (int) Math.min(size, whole.length - at);
            System.arraycopy(whole, (int) at, out, offset, n);
            next = at + n;
            return n;
        }

        public long getSize() {
            return whole.length;
        }

        public void close() {}
    }

    @Test
    public void aLocalReferenceIsWalkedInBlocksNotOneReopenPerRead() throws Exception {
        byte[] whole = files.remove(REFERENCE);
        AtomicInteger jumps = new AtomicInteger();
        PlaybackService.cached = uri -> new CachedCopy(whole, jumps);
        verify();
        assertEquals(0, opensOf(REFERENCE));
        assertTrue("Reopens of the cache: " + jumps.get(), jumps.get() <= 16);
        assertFalse(AudioDecoder.corrupt);
    }

    /** The cached reader never checks the time, so a seek through it can't be left to run. */
    @Test
    public void aLocalReferenceStillStopsPartWayThroughASeek() throws Exception {
        byte[] whole = files.remove(REFERENCE);
        int[] reads = {0};
        PlaybackService.cached =
                uri ->
                        new CachedCopy(whole, new AtomicInteger()) {
                            @Override
                            public int readAt(long at, byte[] out, int offset, int size) {
                                if (++reads[0] == 12) PlaybackService.stopped = true;
                                return super.readAt(at, out, offset, size);
                            }
                        };
        AudioDecoder.windows.clear();
        try {
            verify();
            fail("Stopped");
        } catch (IOException expected) {
            assertTrue(expected.getClass().getName(), expected instanceof SourceAudio.Stopped);
        }
        assertTrue("Reads after the stop: " + reads[0], reads[0] <= 13);
    }

    @Test
    public void theWholeFileKeepsEverythingButTheRange() throws Exception {
        DataSpec range =
                new DataSpec.Builder()
                        .setUri(Uri.parse(CANDIDATE))
                        .setPosition(4096)
                        .setLength(65536)
                        .setHttpRequestHeaders(Collections.singletonMap("a", "b"))
                        .build();
        DataSpec file = SourcePreference.wholeFile(range);
        assertEquals(0, file.position);
        assertEquals(androidx.media3.common.C.LENGTH_UNSET, file.length);
        assertEquals(range.uri, file.uri);
        assertEquals(range.httpRequestHeaders, file.httpRequestHeaders);
        DataSpec already = new DataSpec(Uri.parse(CANDIDATE));
        assertSame(already, SourcePreference.wholeFile(already));
    }

    // ── Checking ahead ──────────────────────────────────────────────────────

    DataSpec plain() {
        return new DataSpec(Uri.parse(MARKED));
    }

    @Test
    public void aCheckMadeAheadAnswersThePlayersOwnOpen() throws Exception {
        SourcePreference.verifyAhead(
                plain(), new DataSpec(Uri.parse(CANDIDATE)), () -> false, true);
        assertEquals(6, AudioDecoder.windows.size());
        int before = opensOf(CANDIDATE) + opensOf(REFERENCE);
        DataSpec resolved = new DataSpec(Uri.parse(CANDIDATE));
        assertSame(resolved, SourcePreference.guard(plain(), resolved));
        assertEquals("Nothing is read again", before, opensOf(CANDIDATE) + opensOf(REFERENCE));
    }

    /** The player may be looking at another reference by the time it opens this. */
    @Test
    public void aCheckAheadIsAgainstYouTubeWhateverIsObserved() throws Exception {
        int resolvesBefore = resolves.get();
        SourcePreference.verifyAhead(
                plain(), new DataSpec(Uri.parse(CANDIDATE)), () -> false, true);
        assertEquals(resolvesBefore + 1, resolves.get());
        assertEquals("No swap shift from it", null, SourcePreference.verifiedOffset("song"));
    }

    @Test
    public void aCheckAheadIsNotStoppedByNothingBeingParkedYet() throws Exception {
        QualityUpgrade.parked = false;
        SourcePreference.verifyAhead(
                plain(), new DataSpec(Uri.parse(CANDIDATE)), () -> false, true);
        assertEquals(6, AudioDecoder.windows.size());
    }

    @Test
    public void aCheckAheadStopsWhenCancelledAndKeepsWhatItDecoded() throws Exception {
        boolean[] cancelled = {false};
        AudioDecoder.afterWindow = done -> cancelled[0] = done == 3;
        try {
            SourcePreference.verifyAhead(
                    plain(), new DataSpec(Uri.parse(CANDIDATE)), () -> cancelled[0], true);
            fail("Cancelled");
        } catch (SourceAudio.Stopped expected) {
        }
        assertTrue(AudioDecoder.windows.size() <= 4);
        assertFalse(SourcePreference.rejected(plain(), new DataSpec(Uri.parse(CANDIDATE))));
        AudioDecoder.afterWindow = null;
        SourcePreference.verifyAhead(
                plain(), new DataSpec(Uri.parse(CANDIDATE)), () -> false, true);
        assertEquals("Continued, not restarted", 6, AudioDecoder.windows.size());
    }

    @Test
    public void aRejectionMadeAheadIsReadableAndStillRefusesThePlayer() throws Exception {
        files.put(CANDIDATE, AudioDecoder.file(817, 0, 180, 0, false));
        DataSpec resolved = new DataSpec(Uri.parse(CANDIDATE));
        assertFalse(SourcePreference.rejected(plain(), resolved));
        try {
            SourcePreference.verifyAhead(plain(), resolved, () -> false, false);
            fail();
        } catch (IOException expected) {
        }
        assertTrue(SourcePreference.rejected(plain(), resolved));
        java.lang.reflect.Field seen = SourceAutomix.class.getDeclaredField("observed");
        seen.setAccessible(true);
        ((Map<String, Uri>) seen.get(null)).remove("song");
        try {
            SourcePreference.guard(plain(), resolved);
            fail("Still not the same recording");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("did not verify"));
        }
    }

    @Test
    public void aWrongLengthAheadIsStillRefused() throws Exception {
        files.put(CANDIDATE, AudioDecoder.file(91, 0, 182, 0, false));
        DataSpec resolved = new DataSpec(Uri.parse(CANDIDATE));
        try {
            SourcePreference.verifyAhead(plain(), resolved, () -> false, true);
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("different duration"));
        }
        assertTrue(SourcePreference.rejected(plain(), resolved));
    }

    @Test
    public void anUndecidedCheckIsNotARejection() throws Exception {
        files.remove(CANDIDATE);
        DataSpec resolved = new DataSpec(Uri.parse(CANDIDATE));
        try {
            SourcePreference.verifyAhead(plain(), resolved, () -> false, true);
            fail();
        } catch (IOException expected) {
        }
        assertFalse(SourcePreference.rejected(plain(), resolved));
    }

    @Test
    public void aCheckAheadYieldsOnlyWhenNobodyIsWaiting() throws Exception {
        int before = android.os.Process.getThreadPriority(android.os.Process.myTid());
        int[] during = {before};
        AudioDecoder.afterWindow =
                done ->
                        during[0] =
                                android.os.Process.getThreadPriority(android.os.Process.myTid());
        SourcePreference.verifyAhead(
                plain(), new DataSpec(Uri.parse(CANDIDATE)), () -> false, false);
        assertEquals("Someone is waiting", before, during[0]);
        clear("decisions");
        clear("attempts");
        AudioDecoder.windows.clear();
        SourcePreference.verifyAhead(
                plain(), new DataSpec(Uri.parse(CANDIDATE)), () -> false, true);
        assertEquals(android.os.Process.THREAD_PRIORITY_BACKGROUND, during[0]);
        assertEquals(before, android.os.Process.getThreadPriority(android.os.Process.myTid()));
    }

    @Test
    public void somethingTrustedFromAnEarlierCheckNeedsNoReading() throws Exception {
        DataSpec resolved = new DataSpec(Uri.parse(CANDIDATE));
        SourcePreference.trustAhead(plain(), resolved);
        assertSame(resolved, SourcePreference.guard(plain(), resolved));
        assertEquals(0, opensOf(CANDIDATE));
    }

    @Test
    public void verificationIgnoresOnlyTheSourceNeutralEntryTag() throws Exception {
        java.lang.reflect.Method key =
                SourcePreference.class.getDeclaredMethod(
                        "decisionKey", Uri.class, Uri.class, Uri.class);
        key.setAccessible(true);
        Uri a = Uri.parse(MARKED + "&bc_entry=one"), b = Uri.parse(MARKED + "&bc_entry=two");
        assertEquals(
                key.invoke(null, a, Uri.parse(CANDIDATE), null),
                key.invoke(null, b, Uri.parse(CANDIDATE), null));
        assertNotEquals(
                key.invoke(null, a, Uri.parse(CANDIDATE), null),
                key.invoke(
                        null,
                        Uri.parse(PLAYED + "&q=hifiDifferent&bc_entry=one"),
                        Uri.parse(CANDIDATE),
                        null));
        SourcePreference.trustAhead(new DataSpec(a), new DataSpec(Uri.parse(CANDIDATE)));
        assertNotNull(SourcePreference.guard(new DataSpec(b), new DataSpec(Uri.parse(CANDIDATE))));
        assertEquals(
                "Previously verified stream needs no second reference fetch", 0, resolves.get());
    }

    private String profileKey(DataSpec request, DataSpec resolved, Uri played, boolean prepared)
            throws Exception {
        java.lang.reflect.Method key =
                SourcePreference.class.getDeclaredMethod(
                        "decisionKey", Uri.class, Uri.class, Uri.class, boolean.class);
        key.setAccessible(true);
        return (String) key.invoke(null, request.uri, resolved.uri, played, prepared);
    }

    private void driftEvidence(DataSpec request, Uri played, boolean prepared) throws Exception {
        String cacheKey = profileKey(request, new DataSpec(Uri.parse(CANDIDATE)), played, prepared);
        java.lang.reflect.Method find =
                SourcePreference.class.getDeclaredMethod("attempt", String.class);
        find.setAccessible(true);
        Object attempt = find.invoke(null, cacheKey);
        for (String name : new String[] {"durationA", "durationB", "expected"}) {
            java.lang.reflect.Field field = attempt.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(
                    attempt,
                    name.equals("durationA")
                            ? 180.0
                            : name.equals("durationB")
                                    ? 180.132791
                                    : new DataSpec(Uri.parse(REFERENCE)));
        }
        java.lang.reflect.Method tune =
                AudioDecoder.class.getDeclaredMethod("tune", long.class, int.class);
        tune.setAccessible(true);
        float[] whole = (float[]) tune.invoke(null, 91L, 180);
        double[] starts = {0, 82, 163}, shifts = {0, .0855, .16};
        for (String name : new String[] {"a", "b"}) {
            java.lang.reflect.Field field = attempt.getClass().getDeclaredField(name);
            field.setAccessible(true);
            RecordingIdentity.Window[] windows = (RecordingIdentity.Window[]) field.get(attempt);
            for (int i = 0; i < starts.length; i++) {
                int from = (int) (starts[i] * AudioDecoder.RATE);
                float[] audio = Arrays.copyOfRange(whole, from, from + 16 * AudioDecoder.RATE);
                windows[i] =
                        new RecordingIdentity.Window(
                                audio,
                                AudioDecoder.RATE,
                                starts[i] + (name.equals("a") ? 0 : shifts[i]));
            }
        }
    }

    @Test
    public void preparationCanApproveASameRecordingWithSmallDurationNormalizedDrift()
            throws Exception {
        DataSpec request = plain(), resolved = new DataSpec(Uri.parse(CANDIDATE));
        driftEvidence(request, null, true);
        SourcePreference.verifyPrepared(request, resolved, () -> false, true);
        assertFalse(SourcePreference.rejectedPrepared(request, resolved));
        assertNull(SourcePreference.verifiedOffset("song"));
        assertTrue("Resumed evidence was used", AudioDecoder.windows.isEmpty());
    }

    @FunctionalInterface
    interface Checked {
        void run() throws Exception;
    }

    private void refused(Checked check) throws Exception {
        try {
            check.run();
            fail("Preparation approval must not bypass this strict check");
        } catch (IOException expected) {
        }
    }

    @Test
    public void strictRejectionCannotPoisonPreparationAndPreparationCannotApproveALegacySwap()
            throws Exception {
        DataSpec request = plain(), resolved = new DataSpec(Uri.parse(CANDIDATE));
        driftEvidence(request, null, false);
        refused(() -> SourcePreference.verifyAhead(request, resolved, () -> false, true));
        assertTrue(SourcePreference.rejected(request, resolved));
        assertFalse(SourcePreference.rejectedPrepared(request, resolved));

        driftEvidence(request, null, true);
        SourcePreference.verifyPrepared(request, resolved, () -> false, true);
        assertTrue(
                "Strict rejection survives preparation",
                SourcePreference.rejected(request, resolved));
        assertFalse(SourcePreference.rejectedPrepared(request, resolved));

        driftEvidence(request, Uri.parse(PLAYED), false);
        refused(() -> SourcePreference.guard(request, resolved));
        assertNull(SourcePreference.verifiedOffset("song"));
        assertTrue("Each purpose used its own retained windows", AudioDecoder.windows.isEmpty());
    }

    @Test
    public void preparationDoesNotPublishOrEraseStrictSwapOffsets() throws Exception {
        DataSpec request = plain(), resolved = new DataSpec(Uri.parse(CANDIDATE));
        SourcePreference.verifyPrepared(request, resolved, () -> false, true);
        assertNull(SourcePreference.verifiedOffset("song"));
        int decoded = AudioDecoder.windows.size();

        verify();
        assertTrue(
                "The unbound legacy request must run its strict check",
                AudioDecoder.windows.size() > decoded);
        SourcePreference.verifyPrepared(request, resolved, () -> false, true);
        Long offset = SourcePreference.verifiedOffset("song");
        assertNotNull(offset);
        assertTrue(
                "Only the strict swap published this offset: " + offset,
                Math.abs(offset - 250) < 60);
    }

    private void strictRejection(DataSpec request, DataSpec resolved) throws Exception {
        java.lang.reflect.Field field = SourcePreference.class.getDeclaredField("decisions");
        field.setAccessible(true);
        ((Map<String, Boolean>) field.get(null))
                .put(profileKey(request, resolved, Uri.parse(PLAYED), false), false);
    }

    @Test
    public void preparationTrustRequiresTheExactBoundRendition() throws Exception {
        SourcePreparationTest fixture = new SourcePreparationTest();
        fixture.setup();
        java.lang.reflect.Field active = SourceFirst.class.getDeclaredField("active");
        active.setAccessible(true);
        Object previous = active.get(null);
        try {
            active.set(null, fixture.preparation);
            Uri original =
                    SourcePreparationTest.song("song")
                            .buildUpon()
                            .appendQueryParameter("bc_entry", "bound")
                            .build();
            Uri marked = fixture.preparation.route(new Object(), new DataSpec(original)).uri;
            DataSpec request = new DataSpec(marked), resolved = new DataSpec(Uri.parse(CANDIDATE));
            SourcePreference.trustPrepared(request, resolved);
            strictRejection(request, resolved);

            assertTrue(SourceFirst.isPreparedUri(marked));
            assertFalse(SourceFirst.isPreparedUri(original));
            assertSame(
                    "Only the exact chosen rendition consumes preparation trust",
                    resolved,
                    SourcePreference.guard(request, resolved));

            for (Uri unbound :
                    Arrays.asList(
                            original,
                            SourcePreparation.withoutEntry(marked)
                                    .buildUpon()
                                    .appendQueryParameter("bc_entry", "other")
                                    .build(),
                            Uri.parse(MARKED))) {
                DataSpec other = new DataSpec(unbound);
                SourcePreference.trustPrepared(other, resolved);
                strictRejection(other, resolved);
                assertFalse(SourceFirst.isPreparedUri(unbound));
                refused(() -> SourcePreference.guard(other, resolved));
            }
            fixture.preparation.switched(false);
            assertSame(
                    "Disabling preparation does not change an already selected file",
                    resolved,
                    SourcePreference.guard(request, resolved));
            fixture.preparation.failed("song", original);
            assertFalse(SourceFirst.isPreparedUri(marked));
            refused(() -> SourcePreference.guard(request, resolved));
            assertEquals("The boundary check performs no decoding", 0, AudioDecoder.windows.size());
        } finally {
            active.set(null, previous);
            fixture.teardown();
        }
    }

    private void dispatched(kotlin.coroutines.CoroutineContext context, Checked check)
            throws Exception {
        try {
            kotlinx.coroutines.BuildersKt.runBlocking(
                    context,
                    (scope, continuation) -> {
                        try {
                            check.run();
                            return kotlin.Unit.INSTANCE;
                        } catch (Exception error) {
                            throw new IllegalStateException(error);
                        }
                    });
        } catch (IllegalStateException wrapped) {
            if (wrapped.getCause() instanceof Exception) throw (Exception) wrapped.getCause();
            throw wrapped;
        }
    }

    @Test
    public void aTrustedPreparedCacheReadNeedsNoNewDecodeAfterRestart() throws Exception {
        DataSpec request = plain(), resolved = new DataSpec(Uri.parse(CANDIDATE));
        SourcePreference.trustPrepared(request, resolved);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        kotlinx.coroutines.ExecutorCoroutineDispatcher dispatcher =
                kotlinx.coroutines.ExecutorsKt.from(executor);
        try (SourcePreference.PreparedRead read =
                SourcePreference.preparedRead(request, resolved)) {
            assertSame(resolved, SourcePreference.guard(request, resolved));
            dispatched(
                    read.context().plus(dispatcher),
                    () -> assertSame(resolved, SourcePreference.guard(request, resolved)));
            assertTrue(
                    "A persisted preparation proof must not decode the six windows again",
                    AudioDecoder.windows.isEmpty());
            assertEquals(0, opensOf(REFERENCE));
            assertEquals(0, opensOf(CANDIDATE));
            assertEquals(0, resolves.get());
            assertNull(SourcePreference.verifiedOffset("song"));
        } finally {
            dispatcher.close();
            executor.shutdownNow();
        }
    }

    @Test
    public void aFreshPreparationStillChecksAudioEvenWhenTheFileWasTrusted() throws Exception {
        DataSpec request = plain(), resolved = new DataSpec(Uri.parse(CANDIDATE));
        SourcePreference.trustPrepared(request, resolved);
        SourcePreference.verifyPrepared(request, resolved, () -> false, true);
        assertEquals("Trust alone is not a fresh verification", 6, AudioDecoder.windows.size());
        assertNull(SourcePreference.verifiedOffset("song"));
    }

    @Test
    public void anApprovedCacheReadCannotBorrowAnotherPairOrStrictSwap() throws Exception {
        DataSpec request = plain(), resolved = new DataSpec(Uri.parse(CANDIDATE));
        DataSpec otherEntry = new DataSpec(Uri.parse(MARKED + "&bc_entry=other"));
        DataSpec otherFile = new DataSpec(Uri.parse(CANDIDATE + "?file=other"));
        SourcePreference.trustPrepared(request, resolved);
        strictRejection(request, resolved);
        strictRejection(otherEntry, resolved);
        strictRejection(request, otherFile);
        driftEvidence(request, null, false);
        try (SourcePreference.PreparedRead read =
                SourcePreference.preparedRead(request, resolved)) {
            assertSame(resolved, SourcePreference.guard(request, resolved));
            refused(() -> SourcePreference.guard(otherEntry, resolved));
            refused(() -> SourcePreference.guard(request, otherFile));
            refused(() -> SourcePreference.verifyAhead(request, resolved, () -> false, true));
            assertSame(resolved, SourcePreference.guard(request, resolved));
        }
        refused(() -> SourcePreference.guard(request, resolved));
        assertTrue(AudioDecoder.windows.isEmpty());
        assertNull(SourcePreference.verifiedOffset("song"));
    }

    @Test
    public void anApprovedCacheReadStopsWhenItsProofIsRevoked() throws Exception {
        DataSpec request = plain(), resolved = new DataSpec(Uri.parse(CANDIDATE));
        SourcePreference.trustPrepared(request, resolved);
        try (SourcePreference.PreparedRead read =
                SourcePreference.preparedRead(request, resolved)) {
            java.lang.reflect.Field trusted =
                    SourcePreference.class.getDeclaredField("verifiedPrepared");
            trusted.setAccessible(true);
            ((Set<?>) trusted.get(null)).clear();
            refused(() -> SourcePreference.guard(request, resolved));
        }
        assertTrue("Revocation must not start new decoding", AudioDecoder.windows.isEmpty());
        assertNull(SourcePreference.verifiedOffset("song"));
    }

    @Test
    public void aNewPreparationRejectionRevokesThePreviousCacheApproval() throws Exception {
        DataSpec request = plain(), resolved = new DataSpec(Uri.parse(CANDIDATE));
        SourcePreference.trustPrepared(request, resolved);
        try (SourcePreference.PreparedRead read =
                SourcePreference.preparedRead(request, resolved)) {
            files.put(CANDIDATE, AudioDecoder.file(817, 0, 180, 0, false));
            refused(() -> SourcePreference.verifyPrepared(request, resolved, () -> false, true));
            int decoded = AudioDecoder.windows.size();
            assertEquals(6, decoded);
            refused(() -> SourcePreference.guard(request, resolved));
            assertEquals(
                    "A rejected proof cannot start another decode",
                    decoded,
                    AudioDecoder.windows.size());
        }
        refused(() -> SourcePreference.preparedRead(request, resolved));
        assertNull(SourcePreference.verifiedOffset("song"));
    }

    @Test
    public void aCancelledFreshPreparationKeepsItsEarlierCacheProof() throws Exception {
        DataSpec request = plain(), resolved = new DataSpec(Uri.parse(CANDIDATE));
        SourcePreference.trustPrepared(request, resolved);
        try (SourcePreference.PreparedRead read =
                SourcePreference.preparedRead(request, resolved)) {
            refused(() -> SourcePreference.verifyPrepared(request, resolved, () -> true, true));
            assertFalse(SourcePreference.rejectedPrepared(request, resolved));
            assertSame(resolved, SourcePreference.guard(request, resolved));
            assertTrue(AudioDecoder.windows.isEmpty());
        }
        assertNull(SourcePreference.verifiedOffset("song"));
    }

    @Test
    public void anApprovedCacheReadStopsWhenItsScopeExpires() throws Exception {
        DataSpec request = plain(), resolved = new DataSpec(Uri.parse(CANDIDATE));
        SourcePreference.trustPrepared(request, resolved);
        try (SourcePreference.PreparedRead read =
                SourcePreference.preparedRead(request, resolved)) {
            java.lang.reflect.Field until = read.getClass().getDeclaredField("until");
            until.setAccessible(true);
            until.setLong(read, System.nanoTime() - 1);
            refused(() -> SourcePreference.guard(request, resolved));
        }
        assertTrue(AudioDecoder.windows.isEmpty());
        assertNull(SourcePreference.verifiedOffset("song"));
    }

    @Test
    public void preparedCacheScopeRequiresProofAndIsExactAcrossCoroutineDispatch()
            throws Exception {
        DataSpec request = plain(), resolved = new DataSpec(Uri.parse(CANDIDATE));
        refused(() -> SourcePreference.preparedRead(request, resolved));
        driftEvidence(request, null, true);
        SourcePreference.verifyPrepared(request, resolved, () -> false, true);
        strictRejection(request, resolved);
        DataSpec otherEntry = new DataSpec(Uri.parse(MARKED + "&bc_entry=other"));
        DataSpec otherFile = new DataSpec(Uri.parse(CANDIDATE + "?file=other"));
        strictRejection(otherEntry, resolved);
        strictRejection(request, otherFile);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        kotlinx.coroutines.ExecutorCoroutineDispatcher dispatcher =
                kotlinx.coroutines.ExecutorsKt.from(executor);
        try {
            SourcePreference.PreparedRead read = SourcePreference.preparedRead(request, resolved);
            try {
                assertFalse(SourceFirst.isPreparedUri(request.uri));
                assertSame(
                        "Synchronous warm entry uses its verified purpose",
                        resolved,
                        SourcePreference.guard(request, resolved));
                dispatched(
                        read.context().plus(dispatcher),
                        () -> {
                            assertSame(resolved, SourcePreference.guard(request, resolved));
                            refused(() -> SourcePreference.guard(otherEntry, resolved));
                            refused(() -> SourcePreference.guard(request, otherFile));
                        });
                dispatched(
                        dispatcher, () -> refused(() -> SourcePreference.guard(request, resolved)));
            } finally {
                read.close();
            }
            refused(() -> SourcePreference.guard(request, resolved));
            dispatched(
                    read.context().plus(dispatcher),
                    () -> refused(() -> SourcePreference.guard(request, resolved)));
            assertNull(SourcePreference.verifiedOffset("song"));
        } finally {
            dispatcher.close();
            executor.shutdownNow();
        }
    }

    @Test
    public void preparedCacheContextRestoresAfterAnExceptionAndNestedStrictChecks()
            throws Exception {
        DataSpec request = plain(), resolved = new DataSpec(Uri.parse(CANDIDATE));
        driftEvidence(request, null, true);
        SourcePreference.verifyPrepared(request, resolved, () -> false, true);
        driftEvidence(request, null, false);
        strictRejection(request, resolved);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        kotlinx.coroutines.ExecutorCoroutineDispatcher dispatcher =
                kotlinx.coroutines.ExecutorsKt.from(executor);
        try (SourcePreference.PreparedRead read =
                SourcePreference.preparedRead(request, resolved)) {
            dispatched(
                    read.context().plus(dispatcher),
                    () -> {
                        refused(
                                () ->
                                        SourcePreference.verifyAhead(
                                                request, resolved, () -> false, true));
                        assertSame(
                                "The inner strict context was restored",
                                resolved,
                                SourcePreference.guard(request, resolved));
                    });
            try {
                dispatched(
                        read.context().plus(dispatcher),
                        () -> {
                            throw new IOException("Stopped test read");
                        });
                fail("The test read must stop");
            } catch (IOException expected) {
                assertEquals("Stopped test read", expected.getMessage());
            }
            dispatched(dispatcher, () -> refused(() -> SourcePreference.guard(request, resolved)));
        } finally {
            dispatcher.close();
            executor.shutdownNow();
        }
        refused(() -> SourcePreference.guard(request, resolved));
    }

    @Test
    public void anAssembledManifestRejectionRevokesOnlyItsPlaybackApproval() throws Exception {
        DataSpec request = plain(),
                playback = new DataSpec(Uri.parse(CANDIDATE)),
                checked = new DataSpec(Uri.parse("file:///cache/rejected-manifest.flac")),
                otherFile = new DataSpec(Uri.parse(CANDIDATE + "?other-file=1")),
                otherRequest = new DataSpec(Uri.parse(MARKED + "&other-song=1"));
        files.put(checked.uri.toString(), AudioDecoder.file(817, 0, 180, 0, false));
        SourcePreference.trustPrepared(request, playback);
        SourcePreference.trustPrepared(request, otherFile);
        SourcePreference.trustPrepared(otherRequest, playback);
        strictRejection(request, playback);
        try (SourcePreference.PreparedRead read =
                SourcePreference.preparedRead(request, playback)) {
            refused(
                    () ->
                            SourcePreference.verifyPrepared(
                                    request, playback, checked, () -> false, true));
            assertTrue(SourcePreference.rejectedPrepared(request, checked));
            assertTrue(
                    "The native fallback asks about the playback URI",
                    SourcePreference.rejectedPrepared(request, playback));
            int decoded = AudioDecoder.windows.size();
            refused(() -> SourcePreference.guard(request, playback));
            assertEquals(
                    "A revoked cache scope cannot decode again",
                    decoded,
                    AudioDecoder.windows.size());
        }
        refused(() -> SourcePreference.preparedRead(request, playback));
        try (SourcePreference.PreparedRead read =
                SourcePreference.preparedRead(request, otherFile)) {
            assertSame(otherFile, SourcePreference.guard(request, otherFile));
        }
        try (SourcePreference.PreparedRead read =
                SourcePreference.preparedRead(otherRequest, playback)) {
            assertSame(playback, SourcePreference.guard(otherRequest, playback));
        }
        int decoded = AudioDecoder.windows.size();
        refused(() -> SourcePreference.guard(request, playback));
        assertEquals("The strict rejection is retained", decoded, AudioDecoder.windows.size());
        assertNull(SourcePreference.verifiedOffset("song"));
    }

    @Test
    public void aSuccessfulAssembledManifestDoesNotApproveAStrictSwap() throws Exception {
        DataSpec request = plain(),
                playback = new DataSpec(Uri.parse(CANDIDATE)),
                checked = new DataSpec(Uri.parse("file:///cache/verified-manifest.flac"));
        files.put(checked.uri.toString(), AudioDecoder.file(91, 0, 180, 250, false));
        strictRejection(request, playback);
        SourcePreference.verifyPrepared(request, playback, checked, () -> false, true);
        assertFalse(SourcePreference.rejectedPrepared(request, playback));
        int decoded = AudioDecoder.windows.size();
        assertEquals(6, decoded);
        try (SourcePreference.PreparedRead read =
                SourcePreference.preparedRead(request, playback)) {
            assertSame(playback, SourcePreference.guard(request, playback));
        }
        assertEquals(
                "The exact assembled proof authorizes only the cache read",
                decoded,
                AudioDecoder.windows.size());
        refused(() -> SourcePreference.guard(request, playback));
        assertEquals(
                "Prepared success cannot replace a strict rejection",
                decoded,
                AudioDecoder.windows.size());
        assertNull(SourcePreference.verifiedOffset("song"));
    }

    @Test
    public void interruptedManifestVerificationDoesNotRejectOrApproveAPlaybackAlias()
            throws Exception {
        DataSpec request = plain(),
                playback = new DataSpec(Uri.parse(CANDIDATE)),
                missing = new DataSpec(Uri.parse("file:///cache/missing-manifest.flac"));
        refused(
                () ->
                        SourcePreference.verifyPrepared(
                                request, playback, missing, () -> false, true));
        assertFalse(SourcePreference.rejectedPrepared(request, playback));
        refused(() -> SourcePreference.preparedRead(request, playback));
        SourcePreference.trustPrepared(request, playback);
        refused(
                () ->
                        SourcePreference.verifyPrepared(
                                request, playback, missing, () -> true, true));
        assertFalse(SourcePreference.rejectedPrepared(request, playback));
        try (SourcePreference.PreparedRead read =
                SourcePreference.preparedRead(request, playback)) {
            assertSame(playback, SourcePreference.guard(request, playback));
        }
        assertNull(SourcePreference.verifiedOffset("song"));
    }

    @Test
    public void aCancelledManifestCheckCannotPropagateAnEarlierFileRejection() throws Exception {
        DataSpec request = plain(),
                playback = new DataSpec(Uri.parse(CANDIDATE)),
                checked =
                        new DataSpec(Uri.parse("file:///cache/previously-rejected-manifest.flac"));
        files.put(checked.uri.toString(), AudioDecoder.file(817, 0, 180, 0, false));
        refused(() -> SourcePreference.verifyPrepared(request, checked, () -> false, true));
        assertTrue(SourcePreference.rejectedPrepared(request, checked));
        SourcePreference.trustPrepared(request, playback);
        refused(
                () ->
                        SourcePreference.verifyPrepared(
                                request, playback, checked, () -> true, true));
        assertFalse(SourcePreference.rejectedPrepared(request, playback));
        try (SourcePreference.PreparedRead read =
                SourcePreference.preparedRead(request, playback)) {
            assertSame(playback, SourcePreference.guard(request, playback));
        }
    }

    @Test
    public void aRejectedAssembledManifestAllowsPreparationToTryTheNextCandidate()
            throws Exception {
        SourcePreparationTest fixture = new SourcePreparationTest();
        fixture.host =
                fixture.new Fake() {
                    DataSpec playback(Object stream) {
                        return new DataSpec(
                                Uri.parse(
                                        "https://addon.invalid/manifest/"
                                                + String.valueOf(stream).substring(5)
                                                + ".m3u8"));
                    }

                    DataSpec assembled(Object stream) {
                        return new DataSpec(
                                Uri.parse(
                                        "file:///cache/"
                                                + String.valueOf(stream).substring(5)
                                                + ".flac"));
                    }

                    @Override
                    public void verify(
                            Uri marked,
                            Object stream,
                            java.util.function.BooleanSupplier cancelled,
                            boolean background)
                            throws IOException {
                        verified.add(String.valueOf(stream));
                        SourcePreference.verifyPrepared(
                                new DataSpec(marked),
                                playback(stream),
                                assembled(stream),
                                cancelled,
                                background);
                    }

                    @Override
                    public boolean rejected(Uri marked, Object stream) {
                        return SourcePreference.rejectedPrepared(
                                new DataSpec(marked), playback(stream));
                    }
                };
        fixture.host.manifest = true;
        fixture.host.stableIdentity = false;
        fixture.host.provider = "manifest-source";
        fixture.preparation = new SourcePreparation(fixture.host, 10_000);
        files.put("file:///cache/song_0.flac", AudioDecoder.file(817, 0, 180, 0, false));
        files.put("file:///cache/song_1.flac", AudioDecoder.file(91, 0, 180, 250, false));
        try {
            fixture.preparation.playing(true);
            fixture.queue("song");
            fixture.idle();
            assertEquals(Arrays.asList("tg://song_0", "tg://song_1"), fixture.host.verified);
            assertEquals("tg://song_1", fixture.host.parked.get("song"));
            assertTrue(fixture.preparation.holds("song|180", "src:x::song_0"));
            assertFalse(fixture.preparation.holds("song|180", "src:x::song_1"));
            assertFalse(
                    "Manifest index identity cannot persist a recording proof",
                    fixture.host.savedText.contains("verified3"));
            assertNull(SourcePreference.verifiedOffset("song"));
        } finally {
            fixture.preparation.close();
        }
    }

    @Test
    public void aNewExactAssemblyCanReplaceAnEarlierAliasRejection() throws Exception {
        DataSpec request = plain(),
                playback = new DataSpec(Uri.parse(CANDIDATE)),
                first = new DataSpec(Uri.parse("file:///cache/earlier-manifest.flac")),
                fresh = new DataSpec(Uri.parse("file:///cache/fresh-manifest.flac"));
        files.put(first.uri.toString(), AudioDecoder.file(817, 0, 180, 0, false));
        files.put(fresh.uri.toString(), AudioDecoder.file(91, 0, 180, 250, false));
        strictRejection(request, playback);
        refused(() -> SourcePreference.verifyPrepared(request, playback, first, () -> false, true));
        assertTrue(SourcePreference.rejectedPrepared(request, playback));
        SourcePreference.verifyPrepared(request, playback, fresh, () -> false, true);
        assertFalse(
                "New measured audio replaces the earlier alias rejection",
                SourcePreference.rejectedPrepared(request, playback));
        try (SourcePreference.PreparedRead read =
                SourcePreference.preparedRead(request, playback)) {
            assertSame(playback, SourcePreference.guard(request, playback));
        }
        int decoded = AudioDecoder.windows.size();
        refused(() -> SourcePreference.guard(request, playback));
        assertEquals(decoded, AudioDecoder.windows.size());
        assertNull(SourcePreference.verifiedOffset("song"));
    }

    @Test
    public void aClosedCacheCoroutineCannotStartANewDecodeButOrdinaryStrictChecksCan()
            throws Exception {
        DataSpec request = plain(), resolved = new DataSpec(Uri.parse(CANDIDATE));
        SourcePreference.trustPrepared(request, resolved);
        SourcePreference.PreparedRead read = SourcePreference.preparedRead(request, resolved);
        read.close();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        kotlinx.coroutines.ExecutorCoroutineDispatcher dispatcher =
                kotlinx.coroutines.ExecutorsKt.from(executor);
        try {
            dispatched(
                    read.context().plus(dispatcher),
                    () -> {
                        IOException stopped = null;
                        try {
                            SourcePreference.guard(request, resolved);
                        } catch (IOException failure) {
                            stopped = failure;
                        }
                        assertEquals(
                                "A closed coroutine must not start decoding",
                                0,
                                AudioDecoder.windows.size());
                        assertTrue(
                                "The revoked context must stop explicitly",
                                stopped instanceof SourceAudio.Stopped);
                    });
            assertEquals(0, resolves.get());
            assertEquals(0, opensOf(REFERENCE));
            assertEquals(0, opensOf(CANDIDATE));
            assertSame(
                    "A caller without the closed context still runs the ordinary strict check",
                    resolved,
                    SourcePreference.guard(request, resolved));
            assertEquals(6, AudioDecoder.windows.size());
        } finally {
            dispatcher.close();
            executor.shutdownNow();
        }
    }

    @Test
    public void anExpiredAndPrunedCacheContextCannotFallBackToDecoding() throws Exception {
        DataSpec request = plain(),
                resolved = new DataSpec(Uri.parse(CANDIDATE)),
                other = new DataSpec(Uri.parse(MARKED + "&different-entry=1"));
        SourcePreference.trustPrepared(request, resolved);
        SourcePreference.trustPrepared(other, resolved);
        SourcePreference.PreparedRead expired = SourcePreference.preparedRead(request, resolved);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        kotlinx.coroutines.ExecutorCoroutineDispatcher dispatcher =
                kotlinx.coroutines.ExecutorsKt.from(executor);
        try {
            java.lang.reflect.Field until = expired.getClass().getDeclaredField("until");
            until.setAccessible(true);
            until.setLong(expired, System.nanoTime() - 1);
            try (SourcePreference.PreparedRead fresh =
                    SourcePreference.preparedRead(other, resolved)) {
                dispatched(
                        fresh.context().plus(dispatcher),
                        () -> assertSame(resolved, SourcePreference.guard(other, resolved)));
            }
            dispatched(
                    expired.context().plus(dispatcher),
                    () -> {
                        IOException stopped = null;
                        try {
                            SourcePreference.guard(request, resolved);
                        } catch (IOException failure) {
                            stopped = failure;
                        }
                        assertEquals(
                                "Pruning an expired grant must not allow another decode",
                                0,
                                AudioDecoder.windows.size());
                        assertTrue(stopped instanceof SourceAudio.Stopped);
                    });
            assertEquals(0, resolves.get());
            SourcePreference.verifyPrepared(request, resolved, () -> false, true);
            assertEquals(
                    "An explicit fresh verification remains available",
                    6,
                    AudioDecoder.windows.size());
        } finally {
            expired.close();
            dispatcher.close();
            executor.shutdownNow();
        }
    }
}

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
    public void refreshedFileMetadataReportsAdvertisedDurationWithoutARequest() {
        Uri file = Uri.parse("https://telegram.bitchord.invalid/test/audio/recording");
        SourcePreference.noteAdvertised(file.toString(), 253);
        assertEquals(253000, SourcePreference.advertised(file));
        SourcePreference.noteAdvertised(file.toString(), 254);
        assertEquals(254000, SourcePreference.advertised(file));
        SourcePreference.noteAdvertised(file.toString(), 0);
        assertEquals(-1, SourcePreference.advertised(file));
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
    final Map<String, AtomicInteger> midTrackFailures = new ConcurrentHashMap<>();
    java.util.function.Supplier<IOException> midTrackError =
            () ->
                    new IOException(
                            "Reset by https://name:secret@example.invalid/file?token=private");
    java.util.function.Supplier<SourceAudio.AnalysisFormat> originalAnalysisFormats;
    java.util.function.LongSupplier originalHeapMaximum, originalHeapUsed;
    SourceAudio.Backend originalDecoder;

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
            AtomicInteger failing = midTrackFailures.get(uri.toString());
            if (spec.position >= 1_000_000 && failing != null && failing.getAndDecrement() > 0)
                throw midTrackError.get();
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

    private long retryNow;

    private void retryReady() {
        retryNow += TimeUnit.SECONDS.toNanos(10);
    }

    @Before
    public void network() throws Exception {
        PlaybackPriority.reset();
        retryNow = System.nanoTime();
        SourcePreference.retryClock = () -> retryNow;
        originalAnalysisFormats = SourceAudio.analysisFormats;
        originalDecoder = SourceAudio.decoder;
        SyntheticDecoder.reset();
        AudioDecoder.platform = false;
        originalHeapMaximum = SourceAutomix.heapMaximum;
        originalHeapUsed = SourceAutomix.heapUsed;
        // Concurrency checks need enough headroom to enter the mocked analyzer.
        SourceAutomix.heapMaximum = () -> 512L << 20;
        SourceAutomix.heapUsed = () -> 32L << 20;
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
        midTrackFailures.clear();
        resolves.set(0);
        SourcePreference.windowRetryMillis = 1;
        AudioDecoder.windows.clear();
        AudioDecoder.corrupt = false;
        AudioDecoder.afterWindow = null;
        AudioDecoder.seekStall = null;
        AudioDecoder.seeksEntered.set(0);
        AudioDecoder.buffersDelivered.set(0);
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
        java.util.concurrent.CountDownLatch stall = AudioDecoder.seekStall;
        AudioDecoder.seekStall = null;
        if (stall != null) stall.countDown();
        SourceAudio.analysisFormats = originalAnalysisFormats;
        SourceAudio.decoder = originalDecoder;
        AudioDecoder.platform = false;
        SyntheticDecoder.reset();
        SourceAutomix.heapMaximum = originalHeapMaximum;
        SourceAutomix.heapUsed = originalHeapUsed;
    }

    DataSpec verify() throws IOException {
        DataSpec resolved = new DataSpec(Uri.parse(CANDIDATE));
        assertSame(resolved, SourcePreference.guard(new DataSpec(Uri.parse(MARKED)), resolved));
        return resolved;
    }

    String analysisState(String id) {
        long maximum = SourceAutomix.heapMaximum.getAsLong();
        long used = SourceAutomix.heapUsed.getAsLong();
        return "phase="
                + SourceAutomix.analysisPhase(id)
                + ", heap="
                + used
                + "/"
                + maximum
                + ", analysis available="
                + SourceAudio.analysisAvailable(maximum, used);
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
            boolean completed = analyzing.await(10, TimeUnit.SECONDS);
            assertTrue(analysisState("other-recording"), completed);
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
    public void aStalledPlatformSeekEndsOnCancellationAndFreesTheDecoderPermit() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean cancelled =
                new java.util.concurrent.atomic.AtomicBoolean();
        CountDownLatch stall = new CountDownLatch(1);
        AudioDecoder.seekStall = stall;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> checked =
                    executor.submit(
                            () -> {
                                SourcePreference.verifyPrepared(
                                        plain(),
                                        new DataSpec(Uri.parse(CANDIDATE)),
                                        cancelled::get,
                                        true);
                                return null;
                            });
            long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (AudioDecoder.seeksEntered.get() == 0 && System.nanoTime() < limit)
                Thread.sleep(10);
            assertEquals("The seek was entered", 1, AudioDecoder.seeksEntered.get());
            int before = AudioDecoder.buffersDelivered.get();
            cancelled.set(true);
            try {
                checked.get(3, TimeUnit.SECONDS);
                fail("Cancelled verification must not succeed");
            } catch (ExecutionException stopped) {
                assertTrue(
                        String.valueOf(stopped.getCause()),
                        stopped.getCause() instanceof SourceAudio.Stopped);
            } catch (TimeoutException stuck) {
                fail("Cancellation did not end verification inside the stalled seek");
            }
            Future<Boolean> admitted =
                    executor.submit(
                            () -> {
                                try (SourceAudio.Decoding permit =
                                        SourceAudio.decode(
                                                System.nanoTime() + TimeUnit.SECONDS.toNanos(3),
                                                () -> false)) {
                                    return true;
                                }
                            });
            assertTrue("Another decoder gets the permit", admitted.get(4, TimeUnit.SECONDS));
            stall.countDown();
            Thread.sleep(200);
            assertEquals(
                    "No audio is delivered after cancellation",
                    before,
                    AudioDecoder.buffersDelivered.get());
            assertEquals(
                    "Every opened file is closed once",
                    SyntheticDecoder.opened.get(),
                    SyntheticDecoder.closed.get());
            assertEquals(0, SyntheticDecoder.misuse.get());
            assertFalse(
                    SourcePreference.rejectedPrepared(plain(), new DataSpec(Uri.parse(CANDIDATE))));
            try {
                SourcePreference.preparedRead(plain(), new DataSpec(Uri.parse(CANDIDATE))).close();
                fail("A stopped verification must not be trusted");
            } catch (IOException untrusted) {
            }
        } finally {
            stall.countDown();
            AudioDecoder.seekStall = null;
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    public void aPlatformExtractorSeekCannotBeStoppedWhichIsWhyItIsNotUsed() throws Exception {
        AudioDecoder.platform = true;
        java.util.concurrent.atomic.AtomicBoolean cancelled =
                new java.util.concurrent.atomic.AtomicBoolean();
        CountDownLatch stall = new CountDownLatch(1);
        AudioDecoder.seekStall = stall;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> checked =
                    executor.submit(
                            () -> {
                                SourcePreference.verifyPrepared(
                                        plain(),
                                        new DataSpec(Uri.parse(CANDIDATE)),
                                        cancelled::get,
                                        true);
                                return null;
                            });
            long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (AudioDecoder.seeksEntered.get() == 0 && System.nanoTime() < limit)
                Thread.sleep(10);
            cancelled.set(true);
            try {
                checked.get(500, TimeUnit.MILLISECONDS);
                fail("The platform call does not look at cancellation");
            } catch (TimeoutException expected) {
            }
            stall.countDown();
            try {
                checked.get(5, TimeUnit.SECONDS);
                fail("Cancelled verification must not succeed");
            } catch (ExecutionException stopped) {
                assertTrue(stopped.getCause() instanceof SourceAudio.Stopped);
            }
        } finally {
            stall.countDown();
            AudioDecoder.seekStall = null;
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    public void aLengthGuessedFromTheBitrateNeverRefusesACopy() throws Exception {
        SyntheticDecoder.durationMethod = 2;
        files.put(CANDIDATE, AudioDecoder.file(91, 0, 183, 0, false));
        DataSpec resolved = new DataSpec(Uri.parse(CANDIDATE));
        try {
            SourcePreference.guard(plain(), resolved);
            fail("A guessed length cannot confirm a copy");
        } catch (IOException inconclusive) {
            assertFalse(
                    inconclusive.getMessage(),
                    inconclusive.getMessage().contains("different duration"));
        }
        assertFalse(SourcePreference.rejected(plain(), resolved));
        assertTrue(AudioDecoder.windows.isEmpty());
        SyntheticDecoder.durationMethod = 0;
        retryReady();
        files.put(CANDIDATE, AudioDecoder.file(91, 0, 180, 250, false));
        assertSame(resolved, SourcePreference.guard(plain(), resolved));
        assertEquals(6, AudioDecoder.windows.size());
        assertEquals(SyntheticDecoder.opened.get(), SyntheticDecoder.closed.get());
        assertEquals(0, SyntheticDecoder.misuse.get());
    }

    @Test
    public void framesOffThePlaybackClockLeaveNeitherProofNorRefusal() throws Exception {
        SyntheticDecoder.unqualified = true;
        DataSpec resolved = new DataSpec(Uri.parse(CANDIDATE));
        try {
            SourcePreference.guard(plain(), resolved);
            fail("Nothing can be compared off the playback clock");
        } catch (IOException inconclusive) {
            assertFalse(
                    inconclusive.getMessage(),
                    inconclusive.getMessage().contains("different duration"));
            assertFalse(
                    inconclusive.getMessage(), inconclusive.getMessage().contains("fingerprint"));
        }
        try {
            SourcePreference.verifyPrepared(plain(), resolved, () -> false, true);
            fail();
        } catch (IOException inconclusive) {
        }
        assertFalse(SourcePreference.rejected(plain(), resolved));
        assertFalse(SourcePreference.rejectedPrepared(plain(), resolved));
        try {
            SourcePreference.preparedRead(plain(), resolved).close();
            fail("There is no preparation proof");
        } catch (IOException untrusted) {
        }
        assertTrue(AudioDecoder.windows.isEmpty());
        assertEquals(SyntheticDecoder.opened.get(), SyntheticDecoder.closed.get());
        SyntheticDecoder.unqualified = false;
        retryReady();
        files.put(CANDIDATE, AudioDecoder.file(91, 0, 180, 250, false));
        assertSame(resolved, SourcePreference.guard(plain(), resolved));
        assertEquals(6, AudioDecoder.windows.size());
    }

    @Test
    public void aMissingLibraryEndsVerificationWithoutAnAnswer() throws Exception {
        SourceAudio.decoder =
                new SyntheticDecoder() {
                    @Override
                    public long open(Object input) throws IOException {
                        throw new IOException("The audio decoder is not available");
                    }
                };
        DataSpec resolved = new DataSpec(Uri.parse(CANDIDATE));
        try {
            SourcePreference.guard(plain(), resolved);
            fail("Nothing can be measured without the decoder");
        } catch (IOException unavailable) {
            assertFalse(unavailable.getMessage(), unavailable.getMessage().contains("fingerprint"));
        }
        assertFalse(SourcePreference.rejected(plain(), resolved));
        assertTrue(AudioDecoder.windows.isEmpty());
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
            boolean completed = analyzed.await(20, TimeUnit.SECONDS);
            assertTrue(
                    "A resolver coroutine must not wait on its owning analyzer: "
                            + analysisState("resolved-off-thread"),
                    completed);
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
        retryReady();
        verify();
        assertEquals(
                "A window finished before the stop is kept: " + AudioDecoder.windows,
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
        int requests = opensOf(CANDIDATE);
        try {
            verify();
            fail("An immediate retry must wait");
        } catch (IOException deferred) {
            assertTrue(deferred.getMessage().contains("deferred"));
        }
        assertEquals(requests, opensOf(CANDIDATE));
        retryReady();
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
    public void foregroundPreparationCanVerifyWhileItsPlayerIsBuffering() throws Exception {
        PlaybackPriority.playbackUrgent(30000);
        SourcePreference.verifyAhead(
                plain(), new DataSpec(Uri.parse(CANDIDATE)), () -> false, false);
        assertEquals(6, AudioDecoder.windows.size());
        assertFalse(SourcePreference.rejected(plain(), new DataSpec(Uri.parse(CANDIDATE))));
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
        assertEquals("The window finished before the stop is kept", 6, AudioDecoder.windows.size());
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
        evidence(request, played, prepared, 180.0, 180.132791, 0, .0855, .16);
    }

    /** Three complete windows of one recording per side, the candidate's timeline shifted. */
    private void evidence(
            DataSpec request,
            Uri played,
            boolean prepared,
            double lengthA,
            double lengthB,
            double... shifts)
            throws Exception {
        String cacheKey = profileKey(request, new DataSpec(Uri.parse(CANDIDATE)), played, prepared);
        java.lang.reflect.Method find =
                SourcePreference.class.getDeclaredMethod("attempt", String.class);
        find.setAccessible(true);
        Object attempt = find.invoke(null, cacheKey);
        java.lang.reflect.Field users = attempt.getClass().getDeclaredField("users");
        users.setAccessible(true);
        users.setInt(attempt, 0);
        for (String name : new String[] {"durationA", "durationB", "expected"}) {
            java.lang.reflect.Field field = attempt.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(
                    attempt,
                    name.equals("durationA")
                            ? lengthA
                            : name.equals("durationB")
                                    ? lengthB
                                    : new DataSpec(Uri.parse(REFERENCE)));
        }
        java.lang.reflect.Method tune =
                AudioDecoder.class.getDeclaredMethod("tune", long.class, int.class);
        tune.setAccessible(true);
        float[] whole = (float[]) tune.invoke(null, 91L, 180);
        double[] starts = {0, 82, 163};
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
        AudioDecoder.prepareRecording(91, 180);
        AudioDecoder.prepareRecording(817, 180);
        assertTrue("Fixture preparation does not decode windows", AudioDecoder.windows.isEmpty());
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

    private String failureReport(byte[] candidate) throws Exception {
        if (candidate == null) files.remove(CANDIDATE);
        else files.put(CANDIDATE, candidate);
        List<String> reports = new ArrayList<>();
        java.util.function.Consumer<String> previous = HostLog.observer;
        HostLog.observer =
                line -> {
                    if (line.startsWith("Recording check stopped at")) reports.add(line);
                };
        try {
            SourcePreference.verifyAhead(
                    plain(), new DataSpec(Uri.parse(CANDIDATE)), () -> false, true);
            fail("The fixture copy must not verify");
        } catch (IOException expected) {
        } finally {
            HostLog.observer = previous;
        }
        assertEquals(1, reports.size());
        return reports.get(0);
    }

    @Test
    public void durationRejectionHasAFixedDiagnosticReason() throws Exception {
        String report = failureReport(AudioDecoder.file(91, 0, 182, 0, false));
        assertTrue(report, report.contains("reason=DURATION_MISMATCH"));
        assertTrue(SourcePreference.rejected(plain(), new DataSpec(Uri.parse(CANDIDATE))));
        assertFalse(report, report.contains(CANDIDATE) || report.contains(REFERENCE));
    }

    @Test
    public void fingerprintRejectionHasAFixedDiagnosticReason() throws Exception {
        String report = failureReport(AudioDecoder.file(817, 0, 180, 0, false));
        assertTrue(report, report.contains("reason=IDENTITY_MISMATCH"));
        assertTrue(SourcePreference.rejected(plain(), new DataSpec(Uri.parse(CANDIDATE))));
    }

    @Test
    public void inconclusiveIoIsDistinctFromPolicyRejection() throws Exception {
        String report = failureReport(null);
        assertTrue(report, report.contains("reason=IO_UNCLASSIFIED"));
        assertFalse(SourcePreference.rejected(plain(), new DataSpec(Uri.parse(CANDIDATE))));
        assertFalse(report, report.contains("Not found"));
    }

    @Test
    public void verificationMemoryDenialSurvivesTheDecoderCallbackBoundary() throws Exception {
        List<SourceAudio.JavaMemory> held = new ArrayList<>();
        try {
            for (int i = 0; i < 16; i++) {
                SourceAudio.JavaMemory grant = SourceAudio.reserveJava(1L << 30, 0, 1);
                assertNotNull(grant);
                held.add(grant);
            }
            String report = failureReport(AudioDecoder.file(91, 0, 180, 0, false));
            assertTrue(report, report.contains("reason=MEMORY_ADMISSION"));
            assertTrue(report, report.contains("decodedSamples=0"));
            assertTrue(report, report.contains("decodedRate=44100"));
            assertFalse(SourcePreference.rejected(plain(), new DataSpec(Uri.parse(CANDIDATE))));
        } finally {
            for (SourceAudio.JavaMemory grant : held) grant.close();
        }
    }

    @Test
    public void emptyDecodeReportsSamplesWithoutRejectingTheRecording() throws Exception {
        SyntheticDecoder.malformedFrame = 0;
        String report = failureReport(AudioDecoder.file(91, 0, 180, 0, false));
        assertTrue(report, report.contains("reason=DECODE_NO_AUDIO"));
        assertTrue(report, report.contains("decodedSamples=0"));
        assertTrue(report, report.contains("decodedRate=0"));
        assertFalse(SourcePreference.rejected(plain(), new DataSpec(Uri.parse(CANDIDATE))));
    }

    public static class HttpDataSource$InvalidResponseCodeException extends IOException {
        public final int responseCode;

        HttpDataSource$InvalidResponseCodeException(int status) {
            super("private request details");
            responseCode = status;
        }
    }

    @Test
    public void shrunkHostHttpFailureRetainsItsStatus() {
        assertEquals(
                "HTTP_429",
                SourcePreference.diagnosticError(
                        new HttpDataSource$InvalidResponseCodeException(429)));
        assertEquals(
                "HTTP_403",
                SourcePreference.diagnosticError(
                        new HttpDataSource$InvalidResponseCodeException(403)));
        assertFalse(
                SourcePreference.transientFailure(
                        new HttpDataSource$InvalidResponseCodeException(403)));
    }

    @Test
    public void diagnosticCategoriesNeverIncludeExceptionMessages() {
        String secret = "https://name:private@example.invalid/file?token=private";
        Throwable[] failures = {
            new java.net.SocketTimeoutException(secret), new java.net.UnknownHostException(secret),
            new java.net.ConnectException(secret), new javax.net.ssl.SSLException(secret),
            new java.io.InterruptedIOException(secret), new java.io.EOFException(secret),
            new java.io.FileNotFoundException(secret), new IOException(secret),
            new IllegalStateException(secret)
        };
        String[] reasons = {
            "NETWORK_TIMEOUT",
            "DNS_FAILURE",
            "CONNECT_FAILURE",
            "TLS_FAILURE",
            "IO_INTERRUPTED",
            "UNEXPECTED_EOF",
            "FILE_MISSING",
            "IO_UNCLASSIFIED",
            "INTERNAL_UNCLASSIFIED"
        };
        for (int at = 0; at < failures.length; at++)
            assertEquals(reasons[at], SourcePreference.diagnosticError(failures[at]));
        assertEquals(
                "DNS_FAILURE",
                SourcePreference.diagnosticError(
                        new IOException(secret, new java.net.UnknownHostException(secret))));
        assertEquals(
                "STOP_CANCELLED",
                SourcePreference.diagnosticError(
                        new SourceAudio.Stopped(SourceAudio.StopReason.CANCELLED)));
        assertEquals("NONE", SourcePreference.diagnosticError(null));
    }

    private void youtubeOff(String mode) {
        YouTubePolicy.use(
                new YouTubePolicy.Registry() {
                    public YouTubePolicy.Row youtube() {
                        return new YouTubePolicy.Row("youtube", false);
                    }

                    public void setEnabled(String id, boolean enabled) {}

                    public String origin(DataSpec spec) {
                        return null;
                    }

                    public String active(androidx.media3.common.MediaItem item) {
                        return null;
                    }

                    public void dropChoices() {}
                });
        YouTubePolicy.init(org.robolectric.RuntimeEnvironment.getApplication());
        YouTubePolicy.loaded();
        YouTubePolicy.disable("youtube", mode);
    }

    @Test
    public void youtubeOffUsesNativeProviderMatchWithoutReadingReference() throws Exception {
        for (String mode : new String[] {YouTubePolicy.PLAYBACK_ONLY, YouTubePolicy.FULL}) {
            youtubeOff(mode);
            try {
                files.clear();
                DataSpec resolved = new DataSpec(Uri.parse(CANDIDATE));
                assertSame(
                        resolved,
                        SourcePreference.guard(
                                new DataSpec(Uri.parse("bitchord://watch?v=song")), resolved));
                assertEquals(0, resolves.get());
                assertTrue(opens.isEmpty());
            } finally {
                YouTubePolicy.reset();
            }
        }
    }

    @Test
    public void youtubeOffMatchDoesNotBecomeAReusableFingerprintProof() throws Exception {
        youtubeOff(YouTubePolicy.PLAYBACK_ONLY);
        try {
            DataSpec resolved = new DataSpec(Uri.parse(CANDIDATE));
            assertSame(
                    resolved,
                    SourcePreference.guard(
                            new DataSpec(Uri.parse("bitchord://watch?v=song")), resolved));
            YouTubePolicy.reset();
            files.put(CANDIDATE, AudioDecoder.file(999, 0, 180, 0, false));
            refused(
                    () ->
                            SourcePreference.guard(
                                    new DataSpec(Uri.parse("bitchord://watch?v=song")), resolved));
            assertTrue(resolves.get() > 0);
        } finally {
            YouTubePolicy.reset();
        }
    }

    @Test
    public void fullDisableDoesNotFetchReferenceForUnverifiedPreparation() throws Exception {
        youtubeOff(YouTubePolicy.FULL);
        try {
            DataSpec resolved = new DataSpec(Uri.parse(CANDIDATE));
            refused(() -> SourcePreference.verifyPrepared(plain(), resolved, () -> false, true));
            assertEquals(0, resolves.get());
            assertTrue(opens.isEmpty());
        } finally {
            YouTubePolicy.reset();
        }
    }

    @Test
    public void youtubeOffDoesNotApproveAMidSongUpgradeFromMetadataAlone() throws Exception {
        youtubeOff(YouTubePolicy.PLAYBACK_ONLY);
        try {
            files.put(CANDIDATE, AudioDecoder.file(999, 0, 180, 0, false));
            DataSpec upgraded = new DataSpec(Uri.parse(MARKED));
            refused(() -> SourcePreference.guard(upgraded, new DataSpec(Uri.parse(CANDIDATE))));
            assertTrue(resolves.get() > 0);
        } finally {
            YouTubePolicy.reset();
        }
    }

    @Test
    public void activeVerificationCannotBeEvictedByOtherCandidates() throws Exception {
        java.lang.reflect.Method get =
                SourcePreference.class.getDeclaredMethod("attempt", String.class);
        get.setAccessible(true);
        Object first = get.invoke(null, "first");
        for (int i = 1; i < 8; i++) get.invoke(null, "other-" + i);
        try {
            get.invoke(null, "ninth");
            fail("The active queue is bounded");
        } catch (java.lang.reflect.InvocationTargetException expected) {
            assertTrue(expected.getCause() instanceof IOException);
        }
        assertSame(first, get.invoke(null, "first"));
    }

    @Test
    public void auditionOffsetPeekDoesNotConsumeHandoverProof() throws Exception {
        verify();
        Long first = SourcePreference.peekVerifiedOffset("song");
        assertNotNull(first);
        assertEquals(first, SourcePreference.peekVerifiedOffset("song"));
        assertEquals(first, SourcePreference.verifiedOffset("song"));
        assertNull(SourcePreference.peekVerifiedOffset("song"));
    }

    @Test
    public void policiesReuseWindowsButEvaluateTheirOwnVerdict() throws Exception {
        SourcePreference.verifyPrepared(
                plain(), new DataSpec(Uri.parse(CANDIDATE)), () -> false, true);
        int measured = AudioDecoder.windows.size();
        assertEquals(6, measured);
        SourcePreference.verifyAhead(
                plain(), new DataSpec(Uri.parse(CANDIDATE)), () -> false, true);
        assertEquals(
                "Strict policy reuses measurements, not the prepared verdict",
                measured,
                AudioDecoder.windows.size());
    }

    // ── Padded copies, measured lengths and window failures ─────────────────

    private DataSpec padded(int n) {
        return new DataSpec(Uri.parse("bitchord://watch?v=song&q=hifi-pad" + n));
    }

    private boolean preparedVerdict(DataSpec request, DataSpec resolved) {
        try {
            SourcePreference.verifyPrepared(request, resolved, () -> false, true);
            return true;
        } catch (IOException refused) {
            return false;
        }
    }

    @Test
    public void paddingBoundariesDecidePreparedButNeverAStrictSwap() throws Exception {
        DataSpec resolved = new DataSpec(Uri.parse(CANDIDATE));
        // length difference, per-window offsets, whether preparation accepts
        Object[][] cases = {
            {.75, new double[] {.25, .25, .25}, true},
            {.751, new double[] {.25, .25, .25}, true},
            {2.0, new double[] {2, 2, 2}, true},
            {3.0, new double[] {2, 2, 2}, true},
            {3.001, new double[] {2, 2, 2}, false},
            {2.0, new double[] {3.0, 3.0, 3.0}, true},
            {2.0, new double[] {3.001, 3.001, 3.001}, false},
            {-2.0, new double[] {-2, -2, -2}, true},
            {2.0, new double[] {2, 2.0795, 2}, true},
            {2.0, new double[] {2, 2.081, 2}, false},
            {2.0, new double[] {2, 2.5, 3}, false},
        };
        for (int i = 0; i < cases.length; i++) {
            DataSpec request = padded(i);
            double difference = (Double) cases[i][0];
            evidence(request, null, true, 180, 180 + difference, (double[]) cases[i][1]);
            assertEquals("case " + i, cases[i][2], preparedVerdict(request, resolved));
            assertEquals(
                    "case " + i,
                    !(Boolean) cases[i][2],
                    SourcePreference.rejectedPrepared(request, resolved));
        }
        assertTrue("Evidence was complete; nothing decoded", AudioDecoder.windows.isEmpty());
    }

    @Test
    public void strictBoundaryStaysAtSevenHundredFiftyMilliseconds() throws Exception {
        DataSpec resolved = new DataSpec(Uri.parse(CANDIDATE));
        DataSpec exact = padded(1), over = padded(2);
        evidence(exact, null, false, 180, 180.75, .25, .25, .25);
        SourcePreference.verifyAhead(exact, resolved, () -> false, true);
        evidence(over, null, false, 180, 180.751, .25, .25, .25);
        refused(() -> SourcePreference.verifyAhead(over, resolved, () -> false, true));
        assertTrue(SourcePreference.rejected(over, resolved));
    }

    @Test
    public void aTwoSecondPaddedCopyVerifiesForPreparationAndStaysRefusedForASwap()
            throws Exception {
        files.put(CANDIDATE, AudioDecoder.file(91, 0, 182, 2000, false));
        DataSpec resolved = new DataSpec(Uri.parse(CANDIDATE));
        SourcePreference.verifyPrepared(plain(), resolved, () -> false, true);
        assertFalse(SourcePreference.rejectedPrepared(plain(), resolved));
        assertEquals(6, AudioDecoder.windows.size());
        assertNull("Preparation supplies no swap offset", SourcePreference.verifiedOffset("song"));
        refused(() -> SourcePreference.verifyAhead(plain(), resolved, () -> false, true));
        assertTrue(SourcePreference.rejected(plain(), resolved));
        assertEquals("The strict verdict reuses the windows", 6, AudioDecoder.windows.size());
    }

    @Test
    public void aPaddedCopyWithAnEditedMiddleOrAnotherRecordingIsStillRefusedForPreparation()
            throws Exception {
        DataSpec resolved = new DataSpec(Uri.parse(CANDIDATE));
        files.put(CANDIDATE, AudioDecoder.file(91, 817, 182, 2000, false));
        refused(() -> SourcePreference.verifyPrepared(plain(), resolved, () -> false, true));
        assertTrue(SourcePreference.rejectedPrepared(plain(), resolved));
        for (String field : new String[] {"decisions", "attempts"}) clear(field);
        files.put(CANDIDATE, AudioDecoder.file(817, 0, 182, 2000, false));
        refused(() -> SourcePreference.verifyPrepared(plain(), resolved, () -> false, true));
        assertTrue(SourcePreference.rejectedPrepared(plain(), resolved));
    }

    @Test
    public void aCopyBeyondThePreparedBoundIsRefusedByLengthWithoutDecoding() throws Exception {
        files.put(CANDIDATE, AudioDecoder.file(91, 0, 184, 2000, false));
        DataSpec resolved = new DataSpec(Uri.parse(CANDIDATE));
        refused(() -> SourcePreference.verifyPrepared(plain(), resolved, () -> false, true));
        assertTrue(SourcePreference.rejectedPrepared(plain(), resolved));
        assertTrue(AudioDecoder.windows.isEmpty());
    }

    private IOException thrown;

    /** The reports a check writes; the exception it ends with is kept in {@code thrown}. */
    private List<String> reports(Checked run) throws Exception {
        List<String> found = new ArrayList<>();
        java.util.function.Consumer<String> previous = HostLog.observer;
        HostLog.observer =
                line -> {
                    if (line.startsWith("Recording check ")) found.add(line);
                };
        thrown = null;
        try {
            run.run();
        } catch (IOException failed) {
            thrown = failed;
        } finally {
            HostLog.observer = previous;
        }
        return found;
    }

    private String lastReport(List<String> found) {
        assertFalse(found.isEmpty());
        return found.get(found.size() - 1);
    }

    private void assertSafe(String report) {
        for (String hidden :
                new String[] {"secret", "token", "private", "example.invalid", "name:", "https://"})
            assertFalse(report + " leaked " + hidden, report.contains(hidden));
        assertFalse(report, report.contains(CANDIDATE) || report.contains(REFERENCE));
    }

    @Test
    public void aLengthMismatchReportsTheMeasuredLengthsTheDifferenceAndTheProbeMethod()
            throws Exception {
        files.put(CANDIDATE, AudioDecoder.file(91, 0, 182, 0, false));
        String report =
                lastReport(
                        reports(
                                () ->
                                        SourcePreference.verifyAhead(
                                                plain(),
                                                new DataSpec(Uri.parse(CANDIDATE)),
                                                () -> false,
                                                true)));
        assertTrue(report, report.contains("reason=DURATION_MISMATCH"));
        assertTrue(
                report,
                report.contains(
                        "measuredReferenceMs=180000; measuredCandidateMs=182000;"
                                + " differenceMs=2000; limitMs=750;"
                                + " advertisedReferenceMs=-1; advertisedCandidateMs=-1;"
                                + " probe=CONTAINER_EXACT"));
        assertTrue(report, report.contains("failingSide=none; window=-1; fromMs=-1; untilMs=-1"));
        assertSafe(report);
    }

    @Test
    public void aPreparedLengthMismatchNamesTheWiderPreparedLimit() throws Exception {
        files.put(CANDIDATE, AudioDecoder.file(91, 0, 184, 0, false));
        String report =
                lastReport(
                        reports(
                                () ->
                                        SourcePreference.verifyPrepared(
                                                plain(),
                                                new DataSpec(Uri.parse(CANDIDATE)),
                                                () -> false,
                                                true)));
        assertTrue(report, report.contains("differenceMs=4000; limitMs=3000;"));
    }

    @Test
    public void aGuessedLengthIsReportedAsEstimatedAndNeverAsADifference() throws Exception {
        SyntheticDecoder.durationMethod = 2;
        files.put(CANDIDATE, AudioDecoder.file(91, 0, 183, 0, false));
        String report =
                lastReport(
                        reports(
                                () ->
                                        SourcePreference.verifyPrepared(
                                                plain(),
                                                new DataSpec(Uri.parse(CANDIDATE)),
                                                () -> false,
                                                true)));
        assertTrue(report, report.contains("reason=DURATION_INSUFFICIENT"));
        assertTrue(report, report.contains("probe=CONTAINER_UNKNOWN"));
        assertTrue(report, report.contains("measuredReferenceMs=-1; measuredCandidateMs=-1;"));
        assertTrue(report, report.contains("differenceMs=-1;"));
    }

    @Test
    public void aFailedWindowNamesTheSideTheWindowAndItsRangeWithoutLeakingTheCause()
            throws Exception {
        midTrackFailures.put(CANDIDATE, new AtomicInteger(100));
        DataSpec resolved = new DataSpec(Uri.parse(CANDIDATE));
        String report =
                lastReport(
                        reports(
                                () ->
                                        SourcePreference.verifyPrepared(
                                                plain(), resolved, () -> false, true)));
        assertTrue(report, report.contains("reason=IO_UNCLASSIFIED"));
        assertTrue(
                report,
                report.contains(
                        "failingSide=candidate; window=2; fromMs=82000; untilMs=98000; try=2"));
        assertTrue(report, report.contains("3 of 6 windows"));
        assertTrue(report, report.contains("cause=IOException"));
        assertSafe(report);
        assertFalse(SourcePreference.rejectedPrepared(plain(), resolved));
        assertEquals("reference a1, candidate b1, reference a2", 3, AudioDecoder.windows.size());
    }

    @Test
    public void aPassingCheckReportsTheMeasuredAndAdvertisedLengthsWithoutExtraReads()
            throws Exception {
        List<String> found =
                reports(
                        () ->
                                SourcePreference.verifyPrepared(
                                        new DataSpec(Uri.parse(MARKED + "&d=180")),
                                        new DataSpec(Uri.parse(CANDIDATE)),
                                        () -> false,
                                        true));
        assertNull(thrown);
        String report = lastReport(found);
        assertTrue(report, report.startsWith("Recording check passed"));
        assertTrue(report, report.contains("advertisedReferenceMs=180000;"));
        assertTrue(report, report.contains("advertisedCandidateMs=-1;"));
        assertTrue(report, report.contains("probe=CONTAINER_EXACT"));
        assertTrue(report, report.contains("measuredReferenceMs=180000;"));
        assertEquals("Lengths and windows only: no probe read", 6, AudioDecoder.windows.size());
        assertSafe(report);
    }

    @Test
    public void anUnusableAdvertisedLengthIsUnknown() {
        assertEquals(-1, SourcePreference.advertised(Uri.parse("bitchord://watch?v=a&d=x")));
        assertEquals(-1, SourcePreference.advertised(Uri.parse("bitchord://watch?v=a&d=0")));
        assertEquals(-1, SourcePreference.advertised(Uri.parse("src:telegram::1"), null));
        assertEquals(215000, SourcePreference.advertised(Uri.parse("bitchord://w?d=215")));
    }

    @Test
    public void aFailedReferenceWindowIsAttributedToTheReference() throws Exception {
        midTrackFailures.put(REFERENCE, new AtomicInteger(100));
        String report =
                lastReport(
                        reports(
                                () ->
                                        SourcePreference.verifyPrepared(
                                                plain(),
                                                new DataSpec(Uri.parse(CANDIDATE)),
                                                () -> false,
                                                true)));
        // The reference is walked through in order, so its first reopen past 1 MB is window 3.
        assertTrue(
                report,
                report.contains(
                        "failingSide=reference; window=3; fromMs=163000; untilMs=179000; try=2"));
        assertTrue(report, report.contains("4 of 6 windows"));
    }

    @Test
    public void completedWindowsSurviveATransientFailureAndOnlyTheMissingOnesAreReadLater()
            throws Exception {
        midTrackFailures.put(CANDIDATE, new AtomicInteger(100));
        DataSpec resolved = new DataSpec(Uri.parse(CANDIDATE));
        refused(() -> SourcePreference.verifyPrepared(plain(), resolved, () -> false, true));
        assertEquals(3, AudioDecoder.windows.size());
        long wait = SourcePreference.retryAfterMillis(plain(), resolved);
        assertTrue("Backoff is bounded: " + wait, wait > 0 && wait <= 8000);
        refused(() -> SourcePreference.verifyPrepared(plain(), resolved, () -> false, true));
        assertEquals("Nothing is read during the backoff", 3, AudioDecoder.windows.size());
        midTrackFailures.clear();
        retryReady();
        assertEquals(0, SourcePreference.retryAfterMillis(plain(), resolved));
        SourcePreference.verifyPrepared(plain(), resolved, () -> false, true);
        assertEquals("Only b2, a3 and b3 were read", 6, AudioDecoder.windows.size());
        assertFalse(SourcePreference.rejectedPrepared(plain(), resolved));
    }

    @Test
    public void oneTransientWindowFailureIsRetriedInPlace() throws Exception {
        midTrackFailures.put(REFERENCE, new AtomicInteger(1));
        List<String> found =
                reports(
                        () ->
                                SourcePreference.verifyPrepared(
                                        plain(),
                                        new DataSpec(Uri.parse(CANDIDATE)),
                                        () -> false,
                                        true));
        assertNull(thrown);
        assertTrue(lastReport(found), lastReport(found).startsWith("Recording check passed"));
        assertEquals(6, AudioDecoder.windows.size());
        assertEquals(
                0, SourcePreference.retryAfterMillis(plain(), new DataSpec(Uri.parse(CANDIDATE))));
    }

    @Test
    public void aMissingFileIsNotRetriedInPlace() throws Exception {
        midTrackFailures.put(CANDIDATE, new AtomicInteger(100));
        midTrackError =
                () -> new java.io.FileNotFoundException("https://name:secret@example.invalid/a");
        String report =
                lastReport(
                        reports(
                                () ->
                                        SourcePreference.verifyPrepared(
                                                plain(),
                                                new DataSpec(Uri.parse(CANDIDATE)),
                                                () -> false,
                                                true)));
        assertTrue(report, report.contains("reason=FILE_MISSING"));
        assertTrue(report, report.contains("fromMs=82000; untilMs=98000; try=1"));
        assertSafe(report);
    }

    @Test
    public void aCachedReferenceThatGoesAwayMidwayIsReadFromTheNetworkCopyInstead()
            throws Exception {
        byte[] whole = files.get(REFERENCE);
        AtomicInteger calls = new AtomicInteger();
        PlaybackService.cached =
                uri ->
                        calls.incrementAndGet() > 3
                                ? null
                                : new CachedCopy(whole, new AtomicInteger());
        List<String> found = reports(this::verify);
        assertNull(thrown);
        assertEquals(6, AudioDecoder.windows.size());
        assertTrue("The network copy was used", opensOf(REFERENCE) > 0);
        assertTrue(lastReport(found), lastReport(found).contains("reference fell back"));
        assertFalse(AudioDecoder.corrupt);
    }

    @Test
    public void aNetworkRenditionOfAnotherLengthDropsTheWindowsOfTheCachedCopy() throws Exception {
        byte[] whole = files.get(REFERENCE);
        AtomicInteger calls = new AtomicInteger();
        PlaybackService.cached =
                uri -> {
                    if (calls.incrementAndGet() <= 3)
                        return new CachedCopy(whole, new AtomicInteger());
                    files.put(REFERENCE, AudioDecoder.file(91, 0, 190, 0, true));
                    return null;
                };
        List<String> found = reports(this::verify);
        assertNotNull(thrown);
        String report = lastReport(found);
        assertTrue(report, report.contains("reason=RENDITION_CHANGED"));
        assertTrue(report, report.contains("reference fell back"));
        retryNow += TimeUnit.SECONDS.toNanos(30);
        found = reports(this::verify);
        // The old 180 s length was not kept: the rendition is measured again and now differs.
        assertTrue(lastReport(found), lastReport(found).contains("measuredReferenceMs=190000;"));
        assertTrue(lastReport(found), lastReport(found).contains("reason=DURATION_MISMATCH"));
    }

    @Test
    public void priorityStoppingAPreparedCheckKeepsItsWindowsAndWritesNoVerdictOrBackoff()
            throws Exception {
        DataSpec resolved = new DataSpec(Uri.parse(CANDIDATE));
        AutoCloseable[] lease = {null};
        AudioDecoder.afterWindow =
                done -> {
                    if (done != 2 || lease[0] != null) return;
                    lease[0] = PlaybackPriority.upgrade("other-upgrade");
                };
        String report =
                lastReport(
                        reports(
                                () ->
                                        SourcePreference.verifyPrepared(
                                                plain(), resolved, () -> false, true)));
        assertTrue(String.valueOf(thrown), thrown instanceof SourceAudio.Stopped);
        assertTrue(report, report.contains("reason=STOP_"));
        assertFalse(SourcePreference.rejectedPrepared(plain(), resolved));
        assertEquals(
                "Waiting for priority is not a failure",
                0,
                SourcePreference.retryAfterMillis(plain(), resolved));
        int kept = AudioDecoder.windows.size();
        lease[0].close();
        PlaybackPriority.reset();
        AudioDecoder.afterWindow = null;
        SourcePreference.verifyPrepared(plain(), resolved, () -> false, true);
        assertEquals("Only the windows not yet measured", 6, AudioDecoder.windows.size());
        assertTrue(kept >= 2 && kept < 6);
    }

    @Test
    public void aRejectedPairHasNoRetry() throws Exception {
        files.put(CANDIDATE, AudioDecoder.file(817, 0, 180, 0, false));
        DataSpec resolved = new DataSpec(Uri.parse(CANDIDATE));
        refused(() -> SourcePreference.verifyPrepared(plain(), resolved, () -> false, true));
        assertEquals(-1, SourcePreference.retryAfterMillis(plain(), resolved));
    }

    @Test
    public void knownInternalFailuresGetFixedCodesAndNeverTheirText() {
        String[][] known = {
            {"The cached copy has gone", "CACHE_GONE"},
            {"Audio source returned no data", "SOURCE_NO_DATA"},
            {"Audio connection is closed", "CONNECTION_CLOSED"},
            {"Audio rendition changed during analysis", "RENDITION_CHANGED"},
            {"Playback source router is not ready", "SOURCE_ROUTER_UNAVAILABLE"},
        };
        for (String[] pair : known)
            assertEquals(pair[1], SourcePreference.diagnosticError(new IOException(pair[0])));
        assertEquals(
                "IO_UNCLASSIFIED",
                SourcePreference.diagnosticError(new IOException("The cached copy has gone: x")));
        assertEquals(
                "IOException",
                SourcePreference.diagnosticCause(
                        new IOException("https://a:b@c.invalid/?token=1")));
        assertEquals(
                "IOException>SocketTimeoutException",
                SourcePreference.diagnosticCause(
                        new IOException("x", new java.net.SocketTimeoutException("y"))));
        assertEquals(
                "IOException>other",
                SourcePreference.diagnosticCause(new IOException("x", new Exception("secret") {})));
    }
}

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
        for (String field : new String[] {"decisions", "offsets", "pendingOffsets", "attempts"})
            clear(field);
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

    DataSpec verify() throws IOException {
        DataSpec resolved = new DataSpec(Uri.parse(CANDIDATE));
        assertSame(resolved, SourcePreference.guard(new DataSpec(Uri.parse(MARKED)), resolved));
        return resolved;
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
}

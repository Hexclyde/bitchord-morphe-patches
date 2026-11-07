package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.net.Uri;

import androidx.media3.common.MediaItem;
import androidx.media3.datasource.DataSpec;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Playing other sources, saved files and music library files while YouTube Music is off. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class SourceFallbackTest {
    public static Object stableBest(
            Object resolver,
            List<?> sources,
            Object target,
            Object request,
            boolean strict,
            boolean shared,
            int drift,
            Object lengthLater,
            Object refine,
            Long timeout,
            Object accept,
            Object continuation,
            int mask) {
        assertFalse(strict);
        assertFalse(shared);
        assertEquals(0, drift);
        assertNull(lengthLater);
        assertNull(refine);
        assertNull(timeout);
        assertNull(accept);
        assertEquals(4088, mask);
        return continuation;
    }

    @Test
    public void stableBestLookupUsesDefaultsForNewLengthParameters() throws Exception {
        java.lang.reflect.Method call =
                Arrays.stream(SourceFallbackTest.class.getMethods())
                        .filter(m -> m.getName().equals("stableBest"))
                        .findFirst()
                        .get();
        Object continuation = new Object();
        assertSame(
                continuation,
                SourceFallback.invokeBest(
                        call,
                        new Object(),
                        Collections.emptyList(),
                        new Object(),
                        new Object(),
                        continuation));
    }

    @Test
    public void unknownBestLookupSignatureFailsBeforeInvocation() throws Exception {
        java.lang.reflect.Method call = Object.class.getMethod("toString");
        assertThrows(
                NoSuchMethodException.class,
                () ->
                        SourceFallback.invokeBest(
                                call, null, Collections.emptyList(), null, null, null));
    }

    /** Sources are plain strings; a source's config id is the string itself. */
    static final class Fake implements SourceFallback.Host {
        ArrayList<Object> ranked = new ArrayList<>();
        List<Object> enabled = new ArrayList<>();
        Map<String, Object> instances = new HashMap<>();
        Object stream;
        Exception lookupError;
        Runnable inLookup;
        int lookups, libraryReads, readChecks;
        boolean storage = true;
        Exception libraryError;
        CountDownLatch libraryGate;
        List<SourceFallback.Row> library = new ArrayList<>();
        Map<String, String> saved = new HashMap<>();
        Set<String> unreadable = new HashSet<>();
        Runnable watcher;
        long clock = 1_000_000L;
        boolean brokenInstances;

        @Override
        public ArrayList<Object> ranked() {
            return ranked;
        }

        @Override
        public List<Object> enabled() {
            return enabled;
        }

        @Override
        public Object instance(String id) {
            if (brokenInstances) throw new IllegalStateException("host changed");
            return instances.get(id);
        }

        @Override
        public String configId(Object source) {
            return (String) source;
        }

        @Override
        public Object lookup(Object target, List<Object> sources, BooleanSupplier stale)
                throws Exception {
            lookups++;
            if (inLookup != null) inLookup.run();
            if (lookupError != null) throw lookupError;
            return stream;
        }

        @Override
        public Object module(Object found) {
            return "module:" + found;
        }

        @Override
        public String saved(String id) {
            return saved.get(id);
        }

        @Override
        public boolean storage() {
            return storage;
        }

        @Override
        public List<SourceFallback.Row> library() throws Exception {
            libraryReads++;
            if (libraryGate != null) libraryGate.await(5, TimeUnit.SECONDS);
            if (libraryError != null) throw libraryError;
            return library;
        }

        @Override
        public boolean readable(String uri) {
            readChecks++;
            return !unreadable.contains(uri);
        }

        @Override
        public void watch(Runnable changed) {
            watcher = changed;
        }

        @Override
        public long now() {
            return clock;
        }
    }

    /** What BitChord's lookup target carries. */
    static final class Target {
        String title = "Song", artist = "Artist";
        Integer durationSec = 200;
    }

    static boolean[] enabled = {false};

    static final class Registry implements YouTubePolicy.Registry {
        @Override
        public YouTubePolicy.Row youtube() {
            return new YouTubePolicy.Row("yt", enabled[0]);
        }

        @Override
        public void setEnabled(String id, boolean on) {
            enabled[0] = on;
        }

        @Override
        public String origin(DataSpec spec) {
            return null;
        }

        @Override
        public String active(MediaItem item) {
            return null;
        }

        @Override
        public void dropChoices() {}
    }

    Fake host;

    @Before
    public void open() {
        enabled[0] = false;
        YouTubePolicy.reset();
        SourceFallback.reset();
        YouTubePolicy.use(new Registry());
        YouTubePolicy.init(RuntimeEnvironment.getApplication());
        YouTubePolicy.loaded();
        host = new Fake();
        host.ranked.add("a");
        host.instances.put("yt", "yt-instance");
        SourceFallback.use(host);
    }

    @After
    public void close() {
        SourceFallback.reset();
        YouTubePolicy.reset();
    }

    private static SourceFallback.Row row(String title, String artist, int seconds, String uri) {
        return new SourceFallback.Row(title, artist, seconds, uri);
    }

    private Object resolve(String id) throws IOException {
        return SourceFallback.resolve(id, new Target());
    }

    private static <T extends IOException> T failure(Class<T> type, String id) {
        try {
            SourceFallback.resolve(id, new Target());
        } catch (IOException e) {
            assertTrue(e.toString(), type.isInstance(e));
            return type.cast(e);
        }
        fail("expected " + type.getSimpleName());
        return null;
    }

    // ── The source list ─────────────────────────────────────────────────────

    @Test
    public void whileYouTubeIsOnTheNativeListIsReturnedUnchanged() throws Exception {
        enabled[0] = true;
        assertSame(host.ranked, SourceFallback.playbackSources());
        assertNull(resolve("id"));
        assertEquals(0, host.lookups);
        assertEquals(0, host.libraryReads);
    }

    @Test
    public void beforeTheSavedSourcesAreReadTheNativeListComesBackAtOnce() throws Exception {
        YouTubePolicy.reset();
        YouTubePolicy.use(new Registry());
        YouTubePolicy.init(RuntimeEnvironment.getApplication());
        AtomicReference<ArrayList<Object>> got = new AtomicReference<>();
        Thread other = new Thread(() -> got.set(SourceFallback.playbackSources()));
        long start = System.nanoTime();
        other.start();
        other.join(2000);
        assertFalse(other.isAlive());
        assertSame(host.ranked, got.get());
        assertTrue((System.nanoTime() - start) / 1_000_000L < 1000);
    }

    @Test
    public void whileYouTubeIsOffEveryOtherSourceRanksAboveIt() {
        host.ranked.add("b");
        ArrayList<Object> list = SourceFallback.playbackSources();
        assertEquals(Arrays.asList("a", "b", "yt-instance"), list);
        assertNotSame(host.ranked, list);
    }

    @Test
    public void aYouTubeRowInTheNativeListNeverRanksAboveAnAlternate() {
        host.ranked.clear();
        host.ranked.addAll(Arrays.asList("yt", "a"));
        assertEquals(Arrays.asList("a", "yt-instance"), SourceFallback.playbackSources());
    }

    @Test
    public void aLowQualityListIsWidenedToEveryEnabledSource() {
        host.ranked.clear();
        host.enabled = new ArrayList<>(Arrays.asList("a", "c", "a"));
        assertEquals(Arrays.asList("a", "c", "yt-instance"), SourceFallback.playbackSources());
        assertTrue("only the host's enabled rows are used", !host.enabled.contains("off"));
    }

    @Test
    public void withoutAYouTubeInstanceOnlyTheAlternatesAreReturned() {
        host.instances.clear();
        assertEquals(Collections.singletonList("a"), SourceFallback.playbackSources());
    }

    @Test
    public void aFailureReadingTheHostReturnsTheNativeListOnce() {
        host.brokenInstances = true;
        assertSame(host.ranked, SourceFallback.playbackSources());
        assertSame(host.ranked, SourceFallback.playbackSources());
    }

    // ── Other sources ───────────────────────────────────────────────────────

    @Test
    public void theBestAlternateCopyIsUsedEvenWhenItIsLossy() throws Exception {
        host.stream = "lossy-stream";
        assertEquals("module:lossy-stream", resolve("id"));
        assertEquals(1, host.lookups);
    }

    @Test
    public void aSongNobodyHasIsRememberedUntilTheSourcesChange() throws Exception {
        failure(SourceFallback.NoAudio.class, "id");
        failure(SourceFallback.NoAudio.class, "id");
        assertEquals("the second ask does no lookup", 1, host.lookups);
        SourceFallback.forget();
        host.stream = "found";
        assertEquals("module:found", resolve("id"));
        assertEquals(2, host.lookups);
    }

    @Test
    public void theNegativeAnswerExpires() throws Exception {
        failure(SourceFallback.NoAudio.class, "id");
        host.clock += SourceFallback.NONE_MS + 1;
        failure(SourceFallback.NoAudio.class, "id");
        assertEquals(2, host.lookups);
    }

    @Test
    public void withNoEnabledAlternateNothingIsAsked() throws Exception {
        host.ranked.clear();
        SourceFallback.NoAudio none = failure(SourceFallback.NoAudio.class, "id");
        assertEquals("id", none.mediaId);
        assertEquals(0, host.lookups);
    }

    @Test
    public void aFailedLookupEndsAsNoAudioAndIsLogged() throws Exception {
        host.lookupError = new IllegalStateException("host changed");
        failure(SourceFallback.NoAudio.class, "id");
    }

    @Test
    public void anInterruptedLookupLeavesNoNegativeAnswer() throws Exception {
        host.lookupError = new InterruptedException();
        InterruptedIOException stop = failure(InterruptedIOException.class, "id");
        assertTrue(Thread.interrupted());
        assertNull(SourceFallback.noAudio(stop));
        host.lookupError = null;
        host.stream = "found";
        assertEquals("module:found", resolve("id"));
    }

    @Test
    public void aSourceChangeDuringALookupDropsItsResult() throws Exception {
        host.stream = "old";
        host.inLookup = SourceFallback::forget;
        failure(InterruptedIOException.class, "id");
        host.inLookup = null;
        assertEquals("module:old", resolve("id"));
    }

    @Test
    public void noAudioIsFoundInsideWrappedErrors() {
        SourceFallback.NoAudio none = new SourceFallback.NoAudio("id");
        Throwable wrapped = new IOException("Stream resolution timed out", new IOException(none));
        assertSame(none, SourceFallback.noAudio(wrapped));
        assertNull(SourceFallback.noAudio(new IOException(new IOException())));
        Throwable deep = none;
        for (int i = 0; i < 10; i++) deep = new IOException(deep);
        assertNull("the search is bounded", SourceFallback.noAudio(deep));
    }

    @Test
    public void aWaitForALookupEndsAtItsDeadlineOrWhenStale() throws Exception {
        VideoSwitch.Suspending never =
                step -> kotlin.coroutines.intrinsics.CoroutineSingletons.COROUTINE_SUSPENDED;
        long start = System.nanoTime();
        assertNull(VideoSwitch.await(never, 150, null));
        assertTrue((System.nanoTime() - start) / 1_000_000L < 3000);
        try {
            VideoSwitch.await(never, 10_000, () -> true);
            fail("a stale wait must end");
        } catch (java.util.concurrent.CancellationException expected) {
            // The caller turns this into an interrupted read.
        }
    }

    // ── Saved files and the music library ───────────────────────────────────

    @Test
    public void aSavedFileIsUsedBeforeAnythingElseIsAsked() throws Exception {
        host.saved.put("id", "file:///saved.m4a");
        host.stream = "found";
        SourceFallback.LocalMatch match = failure(SourceFallback.LocalMatch.class, "id");
        assertEquals("file:///saved.m4a", match.uri);
        assertEquals(0, host.lookups);
        assertEquals("no library read for a saved file", 0, host.libraryReads);
    }

    @Test
    public void aLibraryFileThatIsTheSameRecordingBeatsAnyAlternate() throws Exception {
        host.library.add(row("Song", "Artist", 201, "content://media/external/audio/media/7"));
        host.stream = "found";
        SourceFallback.LocalMatch match = failure(SourceFallback.LocalMatch.class, "id");
        assertEquals("content://media/external/audio/media/7", match.uri);
        assertEquals(0, host.lookups);
        MediaItem item = item("id");
        assertEquals("content://media/external/audio/media/7", SourceFallback.cached(item));
    }

    @Test
    public void aLibraryFileForADifferentVersionIsNotUsed() throws Exception {
        host.library.add(row("Song (Live)", "Artist", 200, "content://a"));
        host.library.add(row("Song (Remix)", "Artist", 200, "content://b"));
        host.library.add(row("Song - Demo", "Artist", 200, "content://c"));
        host.library.add(row("Song", "Artist", 260, "content://d"));
        host.library.add(row("Song", "Someone Else", 200, "content://e"));
        host.library.add(row("Song", "", 200, "content://f"));
        host.library.add(row("Song", "Artist", 0, "content://g"));
        host.stream = "found";
        assertEquals("module:found", resolve("id"));
        assertEquals(1, host.lookups);
    }

    @Test
    public void aLibraryFileThatCannotBeReadMovesOnToTheNextCandidate() throws Exception {
        host.library.add(row("Song", "Artist", 200, "content://gone"));
        host.library.add(row("Song", "Artist", 202, "content://fine"));
        host.unreadable.add("content://gone");
        SourceFallback.LocalMatch match = failure(SourceFallback.LocalMatch.class, "id");
        assertEquals("content://fine", match.uri);
        assertEquals(2, host.readChecks);
    }

    @Test
    public void whenNoLibraryFileCanBeReadTheAlternatesAreAsked() throws Exception {
        host.library.add(row("Song", "Artist", 200, "content://gone"));
        host.unreadable.add("content://gone");
        host.stream = "found";
        assertEquals("module:found", resolve("id"));
    }

    @Test
    public void aLibraryReadErrorStillTriesTheAlternates() throws Exception {
        host.libraryError = new IOException("denied");
        host.stream = "found";
        assertEquals("module:found", resolve("id"));
        assertEquals(1, host.lookups);
    }

    @Test
    public void theLibraryIsNeverReadWithoutPermission() throws Exception {
        host.storage = false;
        host.library.add(row("Song", "Artist", 200, "content://a"));
        host.stream = "found";
        assertEquals("module:found", resolve("id"));
        assertEquals(0, host.libraryReads);
        assertNull(SourceFallback.cached(item("id")));
    }

    @Test
    public void theLibraryIsReadOnceForManySongsAndAgainAfterItChanges() throws Exception {
        host.library.add(row("Song", "Artist", 200, "content://a"));
        failure(SourceFallback.LocalMatch.class, "one");
        failure(SourceFallback.LocalMatch.class, "two");
        assertEquals(1, host.libraryReads);
        assertNotNull("the change watcher is registered once", host.watcher);
        host.watcher.run();
        failure(SourceFallback.LocalMatch.class, "three");
        assertEquals(2, host.libraryReads);
    }

    @Test
    public void aLibraryChangeForgetsAnEarlierNothingFound() throws Exception {
        failure(SourceFallback.NoAudio.class, "id");
        host.library.add(row("Song", "Artist", 200, "content://a"));
        host.watcher.run();
        failure(SourceFallback.LocalMatch.class, "id");
    }

    @Test
    public void callersAskingTogetherShareOneLibraryRead() throws Exception {
        host.library.add(row("Song", "Artist", 200, "content://a"));
        host.libraryGate = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String id = "id" + i;
            Thread thread =
                    new Thread(
                            () -> {
                                try {
                                    SourceFallback.find(id, "Song", "Artist", 200);
                                } catch (InterruptedException ignored) {
                                }
                            });
            thread.start();
            threads.add(thread);
        }
        Thread.sleep(200);
        host.libraryGate.countDown();
        for (Thread thread : threads) thread.join(5000);
        assertEquals(1, host.libraryReads);
    }

    @Test
    public void aFileThePlayerCouldNotReadIsNotOfferedAgain() throws Exception {
        host.library.add(row("Song", "Artist", 200, "content://a"));
        host.library.add(row("Song", "Artist", 201, "content://b"));
        failure(SourceFallback.LocalMatch.class, "id");
        SourceFallback.reject("id", "content://a");
        assertNull("a rejected hit is dropped at once", SourceFallback.cached(item("id")));
        assertEquals("content://b", SourceFallback.find("id", "Song", "Artist", 200));
        SourceFallback.reject("id", "content://b");
        host.stream = "found";
        assertNull(SourceFallback.cached(item("id")));
        assertEquals("module:found", resolve("id"));
    }

    @Test
    public void aSavedFileThePlayerCouldNotReadIsNotOfferedAgain() {
        host.saved.put("id", "file:///saved.m4a");
        assertEquals("file:///saved.m4a", SourceFallback.cached(item("id")));
        SourceFallback.reject("id", "file:///saved.m4a");
        assertNull(SourceFallback.cached(item("id")));
    }

    @Test
    public void aSavedFileDoesNotNeedItsOriginalSourceToBeOn() {
        host.ranked.clear();
        host.saved.put("id", "file:///saved.m4a");
        assertEquals("file:///saved.m4a", SourceFallback.cached(item("id")));
    }

    @Test
    public void theWatchAddressNamesTheSong() {
        assertEquals("id", SourceFallback.mediaId(item("id")));
        MediaItem plain =
                new MediaItem.Builder()
                        .setMediaId("own")
                        .setUri(Uri.parse("content://media/external/audio/media/1"))
                        .build();
        assertEquals("own", SourceFallback.mediaId(plain));
        assertNull(SourceFallback.mediaId(null));
    }

    private static MediaItem item(String id) {
        return new MediaItem.Builder()
                .setMediaId(id)
                .setUri(Uri.parse("bitchord://watch?v=" + id + "&n=Song&a=Artist&d=200"))
                .build();
    }

    // ── Matching ────────────────────────────────────────────────────────────

    private static String match(String title, String artist, int seconds, SourceFallback.Row... r) {
        return SourceFallback.match(
                Arrays.asList(r), title, artist, seconds, uri -> true, new HashSet<>());
    }

    @Test
    public void labelsCreditsAndAccentsDoNotMakeADifferentSong() {
        assertEquals(
                "x",
                match(
                        "Café del Mar (Official Video) [HD]",
                        "Energy 52 - Topic",
                        200,
                        row("Cafe del Mar", "Energy 52", 200, "x")));
        assertEquals("x", match("Song (feat. Guest)", "A & B", 200, row("Song", "A", 199, "x")));
        assertEquals("x", match("Song", "A", 200, row("Song ft. Someone", "A, C", 200, "x")));
    }

    @Test
    public void everyVersionWordMustAgree() {
        assertNull(match("Song", "A", 200, row("Song (Live)", "A", 200, "x")));
        assertNull(match("Song (Live)", "A", 200, row("Song", "A", 200, "x")));
        assertNull(match("Song (Acoustic)", "A", 200, row("Song (Live)", "A", 200, "x")));
        assertNull(match("Song", "A", 200, row("Song (Sped Up)", "A", 200, "x")));
        assertNull(match("Song", "A", 200, row("Song (Remastered 2011)", "A", 200, "x")));
        assertEquals("x", match("Song (Live)", "A", 200, row("Song - Live", "A", 200, "x")));
    }

    @Test
    public void titleArtistAndLengthAllHaveToAgree() {
        assertNull(match("Song", "A", 200, row("Songs", "A", 200, "x")));
        assertNull(match("Song", "A", 200, row("Song", "B", 200, "x")));
        assertNull(match("Song", "A", 200, row("Song", "A", 205, "x")));
        assertNull(match("Song", "A", 0, row("Song", "A", 200, "x")));
        assertNull(match("Song", "", 200, row("Song", "A", 200, "x")));
        assertNull(match("", "A", 200, row("Song", "A", 200, "x")));
        assertNull(match("Song", "A", 200, row("Song", "<unknown>", 200, "x")));
        assertNull(match("Song", "A", 200, row("Song", "A", 0, "x")));
    }

    @Test
    public void theClosestLengthWinsAndRejectedFilesAreSkipped() {
        SourceFallback.Row far = row("Song", "A", 202, "far");
        SourceFallback.Row near = row("Song", "A", 200, "near");
        assertEquals("near", match("Song", "A", 200, far, near));
        assertEquals(
                "far",
                SourceFallback.match(
                        Arrays.asList(far, near),
                        "Song",
                        "A",
                        200,
                        uri -> true,
                        new HashSet<>(Collections.singletonList("near"))));
    }

    @Test
    public void lengthsAreReadFromTheLibraryText() {
        assertEquals(221, SourceFallback.seconds("3:41"));
        assertEquals(3723, SourceFallback.seconds("1:02:03"));
        assertEquals(0, SourceFallback.seconds("x"));
        assertEquals(0, SourceFallback.seconds(null));
        assertEquals(200, SourceFallback.seconds(200));
    }

    // ── Native retry policy ─────────────────────────────────────────────────

    @Test
    public void exhaustionIsAFatalFileNotFoundSoSteadyPlaybackDoesNotRetryIt() {
        assertTrue(
                ((Object) new SourceFallback.NoAudio("id"))
                        instanceof java.io.FileNotFoundException);
        assertTrue(
                ((Object) new SourceFallback.LocalMatch("id", "file:///a"))
                        instanceof java.io.FileNotFoundException);
        assertFalse(
                "a transient read error stays retryable",
                new IOException("reset") instanceof java.io.FileNotFoundException);
    }

    // ── Best available format, through the production lookup ───────────────

    private static final com.music.bitchord.data.sources.TrackMatcher.Target TARGET =
            new com.music.bitchord.data.sources.TrackMatcher.Target();

    private static Object askHost() throws Exception {
        return SourceFallback.reflected()
                .lookup(TARGET, Collections.singletonList("a"), () -> false);
    }

    @After
    public void resetResolver() {
        com.music.bitchord.data.sources.SourceResolver.offers.clear();
        com.music.bitchord.data.sources.SourceResolver.calls.clear();
        com.music.bitchord.data.sources.SourceResolver.delayMs = 0;
        com.music.bitchord.data.sources.SourceResolver.request =
                com.music.bitchord.data.sources.StreamRequest.Lossless.INSTANCE;
    }

    @Test
    public void aLosslessCopyIsTakenFirstAndNothingElseIsAsked() throws Exception {
        com.music.bitchord.data.sources.SourceResolver.offers.put("Lossless", "flac");
        com.music.bitchord.data.sources.SourceResolver.offers.put("Best", "mp3");
        assertEquals("flac", askHost());
        assertEquals(
                Collections.singletonList("substitute"),
                com.music.bitchord.data.sources.SourceResolver.calls);
    }

    @Test
    public void whenNoSourceHasLosslessTheBestRequestIsTriedOnTheSameSources() throws Exception {
        com.music.bitchord.data.sources.SourceResolver.offers.put("Best", "mp3");
        assertEquals("mp3", askHost());
        assertEquals(
                Arrays.asList("substitute", "best:Best:1016:[a]"),
                com.music.bitchord.data.sources.SourceResolver.calls);
    }

    @Test
    public void aBestOrCappedSettingIsNotAskedTwice() throws Exception {
        com.music.bitchord.data.sources.SourceResolver.request =
                com.music.bitchord.data.sources.StreamRequest.Best.INSTANCE;
        assertNull(askHost());
        assertEquals(
                Collections.singletonList("substitute"),
                com.music.bitchord.data.sources.SourceResolver.calls);
    }

    @Test
    public void theSecondAskNeverExtendsTheOneBudget() throws Exception {
        com.music.bitchord.data.sources.SourceResolver.offers.put("Best", "mp3");
        com.music.bitchord.data.sources.SourceResolver.delayMs = 700;
        SourceFallback.waitMs = 400;
        long start = System.nanoTime();
        assertNull(askHost());
        assertTrue((System.nanoTime() - start) / 1_000_000L < 1500);
        assertEquals(
                Collections.singletonList("substitute"),
                com.music.bitchord.data.sources.SourceResolver.calls);
    }
}

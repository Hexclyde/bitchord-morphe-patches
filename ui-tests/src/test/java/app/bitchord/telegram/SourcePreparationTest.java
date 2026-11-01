package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.net.Uri;

import androidx.media3.datasource.DataSpec;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class SourcePreparationTest {
    static Uri song(String id) {
        return Uri.parse("bitchord://watch?v=" + id + "&n=" + id + "&a=artist&d=180");
    }

    static DataSpec open(String id) {
        return new DataSpec(song(id));
    }

    String hifi(String id) {
        int copy = host.rejectedFiles.contains(id + "_0") ? 1 : 0;
        return SourcePreparation.marked(
                        song(id),
                        host.provider + "|file:tg://" + id + "_" + copy + "|" + host.content)
                .toString();
    }

    /** The host, with each answer and what was done to it on show. */
    class Fake implements SourcePreparation.Host {
        boolean sources = true, background = true, foreground = true, sourceOn = true, manifest;
        boolean honest = true, claimable = true, unknownQuality;
        String terms = "session", content = "v1", provider = "telegram";
        boolean stableIdentity = true;
        volatile boolean undecided;
        long warmMs;
        int kbps = 1000;
        String savedText = "";
        boolean stillPlaying = true;
        Uri currentEntry;
        String cachedRevision = "cache-v1";
        final Set<String> leftToHost = ConcurrentHashMap.newKeySet();
        final Set<String> noCopy = ConcurrentHashMap.newKeySet();
        final Set<String> rejectedFiles = ConcurrentHashMap.newKeySet();
        final Set<String> completeRenditions = ConcurrentHashMap.newKeySet();
        final Set<String> held = ConcurrentHashMap.newKeySet();
        final Map<String, String> parked = new ConcurrentHashMap<>();
        final Map<String, List<String>> youtube = new ConcurrentHashMap<>();
        final Set<String> wholeEntries = ConcurrentHashMap.newKeySet();
        final Set<String> locked = ConcurrentHashMap.newKeySet();
        final Set<String> notBetter = ConcurrentHashMap.newKeySet();
        final List<String> removed = Collections.synchronizedList(new ArrayList<>());
        final List<String> lookups = Collections.synchronizedList(new ArrayList<>());
        final List<String> verified = Collections.synchronizedList(new ArrayList<>());
        final List<String> trusted = Collections.synchronizedList(new ArrayList<>());
        final List<String> warmed = Collections.synchronizedList(new ArrayList<>());
        final List<Boolean> backgrounds = Collections.synchronizedList(new ArrayList<>());
        final List<Runnable> later = Collections.synchronizedList(new ArrayList<>());
        final Map<String, CountDownLatch> gates = new ConcurrentHashMap<>();
        final AtomicInteger running = new AtomicInteger(), mostRunning = new AtomicInteger();

        public boolean sourcesAvailable() {
            return sources;
        }

        public boolean backgroundAllowed() {
            return background;
        }

        public boolean foregroundAllowed() {
            return foreground;
        }

        public boolean leftToHost(String id) {
            return leftToHost.contains(id);
        }

        public Object target(Uri uri) {
            return uri.getQueryParameter("v");
        }

        public Object lookup(Object target) {
            String id = (String) target;
            lookups.add(id);
            if (noCopy.contains(id)) return null;
            int copy = 0;
            while (preparation.holds(id + "|180", "src:x::" + id + "_" + copy)) copy++;
            return copy > 2 ? null : "tg://" + id + "_" + copy;
        }

        public String url(Object stream) {
            return (String) stream;
        }

        public String source(Object stream) {
            return provider;
        }

        public boolean sourceActive(String source) {
            return sourceOn;
        }

        public boolean manifest(Object stream) {
            return manifest;
        }

        public String contentIdentity(Object stream) {
            return stableIdentity ? "file:" + stream + "|" + content : null;
        }

        public String cacheIdentity(Uri marked) {
            return complete(marked)
                    ? "cached:" + marked.getQueryParameter("q") + cachedRevision
                    : null;
        }

        final List<Uri> reopened = new ArrayList<>();

        public boolean reopenManifest(Uri original, Uri marked, Object stream) {
            reopened.add(marked);
            return true;
        }

        public String candidate(Object stream) {
            return "src:x::" + ((String) stream).substring(5);
        }

        public String terms(Object stream) {
            return terms;
        }

        public void verify(Uri marked, Object stream, BooleanSupplier cancelled, boolean inBack)
                throws IOException {
            int now = running.incrementAndGet();
            mostRunning.accumulateAndGet(now, Math::max);
            String file = ((String) stream).substring(5);
            try {
                assertTrue(
                        "Checked under its own rendition",
                        marked.getQueryParameter("q").startsWith("hifi"));
                backgrounds.add(inBack);
                verified.add(file);
                CountDownLatch gate = gates.get(marked.getQueryParameter("v"));
                if (gate != null) {
                    long until = System.nanoTime() + 20_000_000_000L;
                    while (gate.getCount() > 0 && System.nanoTime() < until) {
                        if (cancelled.getAsBoolean()) throw new IOException("Cancelled");
                        Thread.sleep(2);
                    }
                }
                if (cancelled.getAsBoolean()) throw new IOException("Cancelled");
                if (undecided) throw new IOException("Not enough audio");
                if (rejectedFiles.contains(file)) throw new IOException("Not the recording");
            } catch (InterruptedException interrupted) {
                throw new IOException("Interrupted");
            } finally {
                running.decrementAndGet();
            }
        }

        public void trust(Uri marked, Object stream) {
            trusted.add((String) stream);
        }

        public boolean rejected(Uri marked, Object stream) {
            return rejectedFiles.contains(((String) stream).substring(5));
        }

        public boolean honest(Object stream) {
            return honest;
        }

        public boolean claim(Uri marked, Object stream) {
            return claimable;
        }

        public boolean park(String id, Object stream) {
            String was = parked.get(id);
            if (was != null && !was.equals(stream)) return false;
            parked.put(id, (String) stream);
            return true;
        }

        public void unpark(String id, Object stream) {
            parked.remove(id, stream);
        }

        public String parked(String id) {
            return parked.get(id);
        }

        public Object parkedStream(String id) {
            return parked.get(id);
        }

        public void holdUpgrades(String id, boolean hold) {
            if (hold) held.add(id);
            else held.remove(id);
        }

        public long warm(Uri marked, long bytes) {
            warmed.add(marked + "@" + bytes);
            return warmMs;
        }

        public int kbps(Object stream) {
            return kbps;
        }

        public DataSpec rewrite(DataSpec spec, Uri marked) {
            return new DataSpec(marked, spec.position, spec.length);
        }

        public boolean complete(Uri marked) {
            return completeRenditions.contains(marked.toString());
        }

        public long length(Uri marked) {
            return 9L << 20;
        }

        public List<String> youtubeEntries(String id, Uri marked) {
            return new ArrayList<>(youtube.getOrDefault(id, Collections.emptyList()));
        }

        public boolean entryComplete(String key) {
            return wholeEntries.contains(key);
        }

        public boolean better(Object stream, String key, Uri marked, String seconds) {
            return !notBetter.contains(key);
        }

        public boolean remove(String key) {
            if (locked.contains(key)) return false;
            removed.add(key);
            for (List<String> entries : youtube.values()) entries.remove(key);
            return true;
        }

        public void later(Runnable task, long millis) {
            later.add(task);
        }

        public boolean stillPlaying(String id, Uri entry, long position) {
            return stillPlaying
                    && (currentEntry == null
                            || SourcePreparation.entryKey(currentEntry)
                                    .equals(SourcePreparation.entryKey(entry)));
        }

        public String read() {
            return savedText;
        }

        public void write(String saved) {
            savedText = saved;
        }

        public void log(String line) {
            assertFalse("No addresses in the log: " + line, line.contains("tg://"));
        }

        void runLater() {
            List<Runnable> due = new ArrayList<>(later);
            later.clear();
            for (Runnable task : due) task.run();
        }
    }

    Fake host;
    SourcePreparation preparation;
    final Object period = new Object();

    @Before
    public void setup() {
        host = new Fake();
        preparation = new SourcePreparation(host, 300);
        preparation.playing(true);
    }

    @After
    public void teardown() {
        for (CountDownLatch gate : host.gates.values()) gate.countDown();
        preparation.close();
    }

    void queue(String... ids) {
        List<Uri> items = new ArrayList<>();
        for (String id : ids) items.add(song(id));
        preparation.upcoming(items);
    }

    void idle() throws Exception {
        long until = System.currentTimeMillis() + 10_000;
        while (!preparation.idle() && System.currentTimeMillis() < until) Thread.sleep(2);
        assertTrue("The worker settles", preparation.idle());
    }

    String read(String id) throws Exception {
        return preparation.route(new Object(), open(id)).uri.toString();
    }

    // ── Which copy a song opens from ────────────────────────────────────────

    @Test
    public void nothingRunsUntilTheQueueOrAPlayerAsks() throws Exception {
        Thread.sleep(20);
        assertTrue(host.lookups.isEmpty());
        assertFalse("No thread is started by creating it", preparation.started());
    }

    @Test
    public void onlyTheNextTwoItemsArePreparedInOrder() throws Exception {
        queue("a", "b", "c", "d", "e");
        idle();
        assertEquals(Arrays.asList("a", "b"), host.lookups);
        assertEquals(Arrays.asList("a_0", "b_0"), host.verified);
    }

    @Test
    public void oneBackgroundTaskAtATime() throws Exception {
        host.gates.put("a", new CountDownLatch(1));
        queue("a", "b");
        Thread.sleep(50);
        assertEquals("b waits for a", Collections.singletonList("a"), host.lookups);
        host.gates.get("a").countDown();
        idle();
        assertEquals(1, host.mostRunning.get());
    }

    @Test
    public void aPreparedItemOpensFromItsOwnRenditionWithoutCheckingAgain() throws Exception {
        queue("a");
        idle();
        assertEquals(Collections.singletonList(hifi("a") + "@" + (1 << 20)), host.warmed);
        assertEquals(hifi("a"), read("a"));
        assertEquals("Checked once", 1, host.verified.size());
    }

    /** The main case: the queue is already cached from YouTube. */
    @Test
    public void aSongCachedFromYouTubeStillOpensFromItsOwnRendition() throws Exception {
        host.youtube.put("a", new ArrayList<>(Arrays.asList("a#alt")));
        host.wholeEntries.add("a#alt");
        queue("a");
        idle();
        assertEquals("Not skipped for being cached", 1, host.lookups.size());
        DataSpec read = preparation.route(period, open("a"));
        assertTrue(
                "Never the YouTube entry's key",
                read.uri.getQueryParameter("q").startsWith("hifi"));
        assertEquals(hifi("a"), read.uri.toString());
    }

    @Test
    public void aSelectedSongNotInTheQueueIsPreparedFirst() throws Exception {
        assertEquals(hifi("x"), read("x"));
        assertEquals("Someone is waiting", Boolean.FALSE, host.backgrounds.get(0));
        assertEquals("tg://x_0", host.parked.get("x"));
    }

    @Test
    public void aSelectedSongOvertakesAnUpcomingOne() throws Exception {
        host.gates.put("a", new CountDownLatch(1));
        queue("a", "b");
        Thread.sleep(30);
        assertEquals(hifi("x"), read("x"));
        assertTrue("Stopped a to do it", host.verified.contains("x_0"));
    }

    @Test
    public void onePeriodReadsOneFileWhateverIsDecidedLater() throws Exception {
        host.noCopy.add("a");
        DataSpec first = preparation.route(period, open("a"));
        assertEquals("Fell back", song("a"), first.uri);
        host.noCopy.remove("a");
        preparation.failed("nothing");
        DataSpec seek = preparation.route(period, new DataSpec(song("a"), 5000, -1));
        assertEquals("Stays on the same file", song("a"), seek.uri);
        assertEquals(5000, seek.position);
    }

    @Test
    public void aPeriodOnTheBetterCopyStaysOnItAndKeepsItsPosition() throws Exception {
        assertEquals(hifi("a"), preparation.route(period, open("a")).uri.toString());
        DataSpec seek = preparation.route(period, new DataSpec(song("a"), 5000, -1));
        assertEquals(hifi("a"), seek.uri.toString());
        assertEquals(5000, seek.position);
        assertEquals(1, host.verified.size());
    }

    @Test
    public void aParkedStreamChangingUnderAPeriodIsRefusedRatherThanJoined() throws Exception {
        preparation.route(period, open("a"));
        host.parked.put("a", "tg://another");
        try {
            preparation.route(period, new DataSpec(song("a"), 5000, -1));
            fail("Would mix two files in one entry");
        } catch (IOException expected) {
        }
    }

    @Test
    public void aMappedSongHoldsBitChordsOwnSearchAndAFallbackReleasesIt() throws Exception {
        read("a");
        assertTrue(host.held.contains("a"));
        preparation.failed("a");
        assertFalse(host.held.contains("a"));
        assertNull("Its stream isn't offered either", host.parked.get("a"));
    }

    @Test
    public void onlyPlainSongsAreEverTouched() throws Exception {
        String[] others = {
            "bitchord://source?s=addon&t=7",
            "file:///music/a.flac",
            "content://media/1",
            "https://host/a.flac",
            "bitchord://watch?n=noId",
            "bitchord://watch?v=a&q=hifi-1",
            "bitchord://watch?v=a&direct_youtube=1",
            "bitchord://watch?v=a&automix_opus=1",
            "bitchord://watch?v=a&watch_read=1",
            "bitchord://watch?v=a&m=1",
        };
        List<Uri> items = new ArrayList<>();
        for (String uri : others) {
            items.add(Uri.parse(uri));
            DataSpec spec = new DataSpec(Uri.parse(uri));
            assertSame(uri, spec, preparation.route(new Object(), spec));
        }
        preparation.upcoming(items.subList(0, 2));
        preparation.upcoming(items.subList(5, 7));
        idle();
        assertTrue(host.lookups.toString(), host.lookups.isEmpty());
    }

    @Test
    public void whatTheHostKeepsForItselfIsLeftAlone() throws Exception {
        host.leftToHost.add("a");
        queue("a");
        assertEquals(song("a").toString(), read("a"));
        idle();
        assertTrue(host.lookups.isEmpty());
    }

    // ── Fallback ────────────────────────────────────────────────────────────

    @Test
    public void withNoOtherSourceYouTubeIsTheAnswer() throws Exception {
        host.sources = false;
        assertEquals(song("a").toString(), read("a"));
        assertTrue(host.lookups.isEmpty());
    }

    @Test
    public void noCopyFallsBackAndIsntAskedAgainStraightAway() throws Exception {
        host.noCopy.add("a");
        assertEquals(song("a").toString(), read("a"));
        assertEquals(song("a").toString(), read("a"));
        queue("a");
        idle();
        assertEquals(1, host.lookups.size());
    }

    @Test
    public void aDisabledSourceIsNotUsedEvenWhenPrepared() throws Exception {
        queue("a");
        idle();
        host.sourceOn = false;
        host.noCopy.add("a");
        assertEquals(song("a").toString(), read("a"));
    }

    @Test
    public void aRejectedCopyIsHeldBackAndTheNextIsTried() throws Exception {
        host.rejectedFiles.add("a_0");
        assertEquals(hifi("a"), read("a"));
        assertEquals("tg://a_1", host.parked.get("a"));
        assertTrue(preparation.holds("a|180", "src:x::a_0"));
        assertFalse(preparation.holds("a|180", "src:x::a_1"));
        assertFalse(preparation.holds("other|180", "src:x::a_0"));
    }

    @Test
    public void onlyTwoCopiesAreTriedThenTheHostDecides() throws Exception {
        host.rejectedFiles.addAll(Arrays.asList("a_0", "a_1", "a_2"));
        assertEquals(song("a").toString(), read("a"));
        assertEquals(Arrays.asList("a_0", "a_1"), host.verified);
        assertTrue("Other sources sit this song out", preparation.holds("a|180", "src:y::z"));
        assertFalse("YouTube never does", preparation.holds("a|180", "a"));
    }

    @Test
    public void anUndecidedCheckFallsBackWithoutCondemningTheCopy() throws Exception {
        host.undecided = true;
        assertEquals(song("a").toString(), read("a"));
        assertEquals("One attempt: nothing says a copy is wrong", 1, host.verified.size());
    }

    @Test
    public void aSlowCheckFallsBackInTimeAndFinishesForNextTime() throws Exception {
        host.gates.put("a", new CountDownLatch(1));
        long began = System.nanoTime();
        assertEquals(song("a").toString(), read("a"));
        long took = (System.nanoTime() - began) / 1_000_000;
        assertTrue("Waited " + took + " ms", took >= 280 && took < 5000);
        assertTrue(
                "The host's race can't pick it and wait in the check",
                preparation.holds("a|180", "src:x::a_0"));
        host.gates.get("a").countDown();
        idle();
        assertFalse(preparation.holds("a|180", "src:x::a_0"));
        assertEquals(hifi("a"), read("a"));
        assertEquals(1, host.verified.size());
    }

    /** Metered isn't poor: it's what arrives that counts. */
    @Test
    public void aCopyArrivingTooSlowlyToPlayFallsBack() throws Exception {
        host.kbps = 1000;
        host.warmMs = 60_000; // 1 MiB in a minute
        assertEquals(song("a").toString(), read("a"));
        assertNull("Nothing left parked", host.parked.get("a"));
        assertFalse(
                "Only this copy's delivery was at fault, so the search isn't held",
                preparation.holds("a|180", "src:x::a_0"));
    }

    @Test
    public void aCopyArrivingFastEnoughIsUsed() throws Exception {
        host.kbps = 1000;
        host.warmMs = 2000; // 4 Mbit/s
        assertEquals(hifi("a"), read("a"));
    }

    @Test
    public void aFileThatIsntWhatItPromisesIsNotUsed() throws Exception {
        host.honest = false;
        assertEquals(song("a").toString(), read("a"));
    }

    @Test
    public void anEntryThatCantBeClaimedFallsBack() throws Exception {
        host.claimable = false;
        assertEquals(song("a").toString(), read("a"));
        assertTrue(host.warmed.isEmpty());
    }

    @Test
    public void aVerifiedManifestRebuildsItsExactEntryBeforeAnyAudioOpens() throws Exception {
        host.manifest = true;
        try {
            read("a");
            fail("Old progressive source must be released");
        } catch (IOException expected) {
        }
        assertEquals(1, host.verified.size());
        assertEquals(Collections.singletonList(Uri.parse(hifi("a"))), host.reopened);
        assertTrue("Manifest XML is not warmed as an audio file", host.warmed.isEmpty());
    }

    @Test
    public void aStopFromThePlayerReturnsAtOnceAndKeepsTheFlag() throws Exception {
        host.gates.put("a", new CountDownLatch(1));
        Thread.currentThread().interrupt();
        try {
            assertEquals(song("a").toString(), read("a"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void aFailedCopyIsNotOfferedAgainStraightAway() throws Exception {
        read("a");
        preparation.failed("a");
        assertEquals("BitChord's recovery reopens plain", song("a").toString(), read("a"));
    }

    // ── Policy ──────────────────────────────────────────────────────────────

    @Test
    public void aChangedSessionOrRevisionMeansCheckingAgain() throws Exception {
        queue("a");
        idle();
        host.terms = "another session";
        host.savedText = "";
        SourcePreparation fresh = new SourcePreparation(host, 300);
        fresh.close();
        assertEquals(hifi("a"), read("a"));
        assertEquals(
                "The same file passed before, so its answer is reused, on the new terms",
                1,
                host.verified.size());
        assertEquals(1, host.trusted.size());
    }

    @Test
    public void connectionBatteryAndHeatOnlyHoldBackBackgroundWork() throws Exception {
        host.background = false;
        queue("a");
        idle();
        assertTrue(host.lookups.isEmpty());
        assertEquals("The listener asked for this one", hifi("a"), read("a"));
    }

    @Test
    public void aSelectedSongFallsBackWhenItsGateIsClosed() throws Exception {
        host.foreground = false;
        assertEquals(song("a").toString(), read("a"));
        assertTrue(host.lookups.isEmpty());
    }

    @Test
    public void pausingStopsBackgroundWorkAndPlayingAllowsItAgain() throws Exception {
        host.gates.put("a", new CountDownLatch(1));
        queue("a", "b");
        Thread.sleep(30);
        preparation.playing(false);
        idle();
        assertEquals("b never starts", Collections.singletonList("a"), host.lookups);
        host.gates.remove("a");
        queue("a", "b");
        idle();
        assertEquals("Paused: nothing new", 1, host.lookups.size());
        preparation.playing(true);
        queue("a", "b");
        idle();
        assertEquals(Arrays.asList("a", "a", "b"), host.lookups);
    }

    @Test
    public void pausingLeavesASelectedSongsWorkAlone() throws Exception {
        preparation.playing(false);
        assertEquals(hifi("a"), read("a"));
    }

    @Test
    public void aMovedQueueStopsWorkNobodyNeeds() throws Exception {
        host.gates.put("a", new CountDownLatch(1));
        queue("a", "b");
        Thread.sleep(30);
        queue("c");
        idle();
        assertEquals(Arrays.asList("a", "c"), host.lookups);
        assertEquals(hifi("c"), read("c"));
    }

    @Test
    public void workStillNeededIsNotRestartedWhenTheQueueIsReported() throws Exception {
        host.gates.put("a", new CountDownLatch(1));
        queue("a", "b");
        Thread.sleep(30);
        queue("a", "b");
        queue("a", "b");
        host.gates.get("a").countDown();
        idle();
        assertEquals(Arrays.asList("a", "b"), host.lookups);
    }

    @Test
    public void switchedOffNothingHappensAndMappingsGoAtOnce() throws Exception {
        read("a");
        preparation.switched(false);
        assertFalse(host.held.contains("a"));
        queue("b");
        assertEquals(song("a").toString(), read("a"));
        assertEquals(song("c").toString(), read("c"));
        idle();
        assertEquals(1, host.lookups.size());
    }

    @Test
    public void onlyAFewResultsAreKept() throws Exception {
        for (String id : new String[] {"a", "b", "c", "d", "e", "f"}) {
            queue(id);
            idle();
        }
        assertTrue(preparation.kept() <= SourcePreparation.KEPT);
    }

    @Test
    public void statusSaysWhatsTrue() throws Exception {
        assertEquals("", preparation.statusOf("a"));
        host.gates.put("a", new CountDownLatch(1));
        queue("a");
        Thread.sleep(30);
        assertEquals("preparing", preparation.statusOf("a"));
        host.gates.get("a").countDown();
        idle();
        assertEquals("ready", preparation.statusOf("a"));
    }

    @Test
    public void closingStopsTheWorkerAndAnythingAfterIsIgnored() throws Exception {
        host.gates.put("a", new CountDownLatch(1));
        queue("a");
        Thread.sleep(30);
        preparation.close();
        long until = System.currentTimeMillis() + 5000;
        while (preparation.alive() && System.currentTimeMillis() < until) Thread.sleep(2);
        assertFalse(preparation.alive());
        int before = host.lookups.size();
        queue("b");
        assertEquals(song("b").toString(), read("b"));
        assertEquals(before, host.lookups.size());
    }

    @Test
    public void aMissingHostMemberCostsOneAttemptNotTheApp() throws Exception {
        Fake broken =
                new Fake() {
                    @Override
                    public Object lookup(Object target) {
                        throw new NoSuchMethodError("Stripped from the host");
                    }
                };
        SourcePreparation other = new SourcePreparation(broken, 300);
        try {
            assertEquals(song("a").toString(), other.route(new Object(), open("a")).uri.toString());
            assertTrue("The worker survives an Error", other.alive());
        } finally {
            other.close();
        }
    }

    // ── What's cached from YouTube ──────────────────────────────────────────

    void playBetter(String id) throws Exception {
        assertEquals(hifi(id), read(id));
        preparation.current(id, song(id), 1000, true);
    }

    void proved() throws Exception {
        host.runLater();
        idle();
    }

    @Test
    public void theOldCopyGoesOnlyOnceTheBetterOneHasPlayed() throws Exception {
        host.youtube.put("a", new ArrayList<>(Arrays.asList("a#alt")));
        playBetter("a");
        idle();
        assertTrue("Not before it's proved itself", host.removed.isEmpty());
        assertEquals("Waiting on the proof", 1, host.later.size());
        proved();
        assertEquals(Collections.singletonList("a#alt"), host.removed);
    }

    @Test
    public void nothingGoesWhileItIsStillReadOrTheSongMovedOn() throws Exception {
        host.youtube.put("a", new ArrayList<>(Arrays.asList("a#alt")));
        host.stillPlaying = false;
        playBetter("a");
        proved();
        assertTrue(host.removed.isEmpty());
    }

    @Test
    public void nothingGoesWhenThereIsNoBetterCopyPlaying() throws Exception {
        host.youtube.put("a", new ArrayList<>(Arrays.asList("a#alt")));
        host.noCopy.add("a");
        read("a");
        preparation.current("a", song("a"), 1000, true);
        proved();
        assertTrue(host.removed.isEmpty());
    }

    @Test
    public void anEqualCopyDoesNotCostTheOldOne() throws Exception {
        host.youtube.put("a", new ArrayList<>(Arrays.asList("a#alt")));
        host.notBetter.add("a#alt");
        playBetter("a");
        proved();
        assertTrue(host.removed.isEmpty());
    }

    @Test
    public void aSongUpgradedInPlayIsOnTheSameTerms() throws Exception {
        host.youtube.put("a", new ArrayList<>(Arrays.asList("a#alt")));
        host.parked.put("a", "tg://a_0");
        preparation.current("a", Uri.parse(hifi("a")), 1000, true);
        proved();
        assertEquals(Collections.singletonList("a#alt"), host.removed);
    }

    @Test
    public void aWholeYouTubeCopyWaitsForTheBetterCopyToBeWhole() throws Exception {
        host.youtube.put("a", new ArrayList<>(Arrays.asList("a#alt")));
        host.wholeEntries.add("a#alt");
        playBetter("a");
        proved();
        assertTrue("It could play offline; the better one can't yet", host.removed.isEmpty());
        host.warmed.clear();
        host.completeRenditions.add(hifi("a"));
        preparation.current("b", song("b"), 0, true);
        idle();
        assertFalse("Filled once nothing reads it", host.warmed.isEmpty());
        assertEquals(Collections.singletonList("a#alt"), host.removed);
    }

    @Test
    public void aWholeCopyIsNotFilledWhileItsSongIsStillPlaying() throws Exception {
        host.youtube.put("a", new ArrayList<>(Arrays.asList("a#alt")));
        host.wholeEntries.add("a#alt");
        playBetter("a");
        proved();
        host.warmed.clear();
        preparation.current("a", song("a"), 2000, true);
        idle();
        assertTrue(host.warmed.isEmpty());
    }

    @Test
    public void aBusyEntryIsRetriedABoundedNumberOfTimes() throws Exception {
        host.youtube.put("a", new ArrayList<>(Arrays.asList("a#alt")));
        host.locked.add("a#alt");
        playBetter("a");
        for (int i = 0; i < 6; i++) {
            proved();
            preparation.current("a", song("a"), 1000, true);
        }
        assertTrue(host.removed.isEmpty());
        host.locked.clear();
        proved();
        assertTrue("Given up until the next play", host.removed.isEmpty());
    }

    @Test
    public void aBusyEntryGetsAnotherChanceOnTheNextPlay() throws Exception {
        host.youtube.put("a", new ArrayList<>(Arrays.asList("a#alt")));
        host.locked.add("a#alt");
        playBetter("a");
        for (int i = 0; i < 6; i++) {
            proved();
            preparation.current("a", song("a"), 1000, true);
        }
        proved();
        assertTrue(host.removed.isEmpty());
        host.locked.clear();
        playBetter("a");
        proved();
        assertEquals(Collections.singletonList("a#alt"), host.removed);
    }

    @Test
    public void aFinishedCleanUpIsntRepeated() throws Exception {
        host.youtube.put("a", new ArrayList<>(Arrays.asList("a#alt")));
        playBetter("a");
        proved();
        preparation.current("a", song("a"), 2000, true);
        assertTrue(host.later.isEmpty());
    }

    // ── Between sessions ────────────────────────────────────────────────────

    @Test
    public void aWholeBetterCopyIsPreferredLaterWithNoConnection() throws Exception {
        host.youtube.put("a", new ArrayList<>());
        host.completeRenditions.add(hifi("a"));
        playBetter("a");
        proved();
        preparation.close();

        SourcePreparation later = new SourcePreparation(host, 300);
        preparation = later;
        host.foreground = false;
        host.parked.clear();
        host.lookups.clear();
        assertEquals(hifi("a"), read("a"));
        assertTrue("No search and no check", host.lookups.isEmpty());
    }

    @Test
    public void aKnownCopyIsNotUsedOnceItsSourceIsOff() throws Exception {
        host.youtube.put("a", new ArrayList<>());
        host.completeRenditions.add(hifi("a"));
        playBetter("a");
        proved();
        preparation.close();
        preparation = new SourcePreparation(host, 300);
        host.sourceOn = false;
        assertEquals(song("a").toString(), read("a"));
    }

    @Test
    public void aFileThatPassedIsntCheckedAgainInALaterSession() throws Exception {
        read("a");
        preparation.close();
        preparation = new SourcePreparation(host, 300);
        host.parked.clear();
        assertEquals(hifi("a"), read("a"));
        assertEquals(1, host.verified.size());
        assertEquals(Collections.singletonList("tg://a_0"), host.trusted);
    }

    @Test
    public void whatsSavedHoldsNoAddress() throws Exception {
        read("a");
        assertFalse(host.savedText, host.savedText.contains("tg://"));
    }

    static Uri entry(String id, String entry) {
        return song(id).buildUpon().appendQueryParameter("bc_entry", entry).build();
    }

    @Test
    public void duplicateEntriesKeepCurrentPlainAndUpcomingNativeIndependent() throws Exception {
        Uri current = entry("a", "current"), next = entry("a", "next");
        host.foreground = false;
        assertEquals(current, preparation.route(new Object(), new DataSpec(current)).uri);
        host.foreground = true;
        preparation.upcoming(Collections.singletonList(next));
        idle();
        Uri nextUri = preparation.route(new Object(), new DataSpec(next)).uri;
        assertNotEquals(next, nextUri);
        assertEquals(
                "Current label and analysis remain on the opened file",
                current,
                preparation.renditionUri(current));
        assertEquals(
                "Next label and analysis use its opened native file",
                nextUri,
                preparation.renditionUri(next));
        host.youtube.put("a", new ArrayList<>(Collections.singletonList("a#alt")));
        preparation.current("a", current, 1000, true);
        proved();
        assertTrue("An upcoming duplicate cannot evict the current entry", host.removed.isEmpty());
    }

    @Test
    public void aTimedOutEntryCannotFlipWhenItsCheckFinishes() throws Exception {
        Uri current = entry("a", "first"), next = entry("a", "second");
        host.gates.put("a", new CountDownLatch(1));
        Object first = new Object();
        assertEquals(current, preparation.route(first, new DataSpec(current)).uri);
        host.gates.get("a").countDown();
        idle();
        assertEquals(current, preparation.renditionUri(current));
        assertEquals(current, preparation.route(first, new DataSpec(current)).uri);
        Uri selected = preparation.route(new Object(), new DataSpec(next)).uri;
        assertNotEquals(next, selected);
        assertEquals(selected, preparation.renditionUri(next));
    }

    @Test
    public void aPreparedUpcomingEntryPredictsOnlyItsOwnRendition() throws Exception {
        Uri current = entry("a", "plain"), next = entry("a", "native");
        host.foreground = false;
        preparation.route(new Object(), new DataSpec(current));
        host.foreground = true;
        preparation.upcoming(Collections.singletonList(next));
        idle();
        assertEquals(current, preparation.renditionUri(current));
        Uri predicted = preparation.renditionUri(next);
        assertNotEquals(next, predicted);
        assertEquals(predicted, preparation.route(new Object(), new DataSpec(next)).uri);
    }

    @Test
    public void aDuplicateFailureDoesNotRewriteAnotherEntriesDecision() throws Exception {
        Uri a = entry("a", "one"), b = entry("a", "two");
        Uri chosen = preparation.route(new Object(), new DataSpec(a)).uri;
        preparation.route(new Object(), new DataSpec(b));
        preparation.failed("a", b);
        assertEquals(chosen, preparation.renditionUri(a));
        assertEquals(b, preparation.renditionUri(b));
    }

    @Test
    public void changedFileAtSameAddressCannotReusePersistentProofOrCacheSlot() throws Exception {
        Uri first = preparation.route(new Object(), open("a")).uri;
        preparation.close();
        host.parked.clear();
        host.content = "v2";
        preparation = new SourcePreparation(host, 300);
        Uri second = preparation.route(new Object(), open("a")).uri;
        assertNotEquals(
                "Same URL, different file",
                first.getQueryParameter("q"),
                second.getQueryParameter("q"));
        assertEquals(2, host.verified.size());
        assertTrue(host.trusted.isEmpty());
    }

    @Test
    public void urlOnlyProofIsNeverTrustedAfterRestart() throws Exception {
        host.stableIdentity = false;
        Uri first = preparation.route(new Object(), open("a")).uri;
        preparation.close();
        host.parked.clear();
        preparation = new SourcePreparation(host, 300);
        Uri second = preparation.route(new Object(), open("a")).uri;
        assertNotEquals(first.getQueryParameter("q"), second.getQueryParameter("q"));
        assertEquals(2, host.verified.size());
        assertTrue(host.trusted.isEmpty());
        assertFalse(host.savedText.contains("verified2"));
    }

    @Test
    public void offlineCopyNeedsTheSameCachedBytesItRecorded() throws Exception {
        host.completeRenditions.add(hifi("a"));
        playBetter("a");
        proved();
        preparation.close();
        host.parked.clear();
        host.foreground = false;
        host.cachedRevision = "tampered";
        preparation = new SourcePreparation(host, 300);
        assertEquals(song("a"), preparation.route(new Object(), open("a")).uri);
    }

    @Test
    public void changedFileIsDetectedBeforeAnotherRangeCanOpen() throws Exception {
        Object period = new Object();
        preparation.route(period, open("a"));
        host.content = "v2";
        try {
            preparation.route(period, new DataSpec(song("a"), 8192, -1));
            fail("Changed file must not be joined");
        } catch (IOException expected) {
        }
    }

    @Test
    public void unknownOldHifiSlotIsNeverSelectedByANewPreparation() throws Exception {
        host.completeRenditions.add(song("a") + "&q=hifi");
        Uri selected = preparation.route(new Object(), open("a")).uri;
        assertNotEquals("hifi", selected.getQueryParameter("q"));
        assertFalse(host.warmed.toString().contains("&q=hifi@"));
    }

    @Test
    public void fileIdentityChangingDuringCheckIsNotPublished() throws Exception {
        host.gates.put("a", new CountDownLatch(1));
        queue("a");
        long until = System.currentTimeMillis() + 2000;
        while (host.verified.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(2);
        host.content = "edited";
        host.gates.get("a").countDown();
        idle();
        assertEquals("", preparation.statusOf("a"));
        assertTrue(host.warmed.isEmpty());
    }

    @Test
    public void queueEntryTagSurvivesCopiesAndUsesExistingQueueIdentity() throws Exception {
        android.os.Bundle extras = new android.os.Bundle();
        extras.putString("bitchord.queueEntryId", "existing-entry");
        androidx.media3.common.MediaItem original =
                new androidx.media3.common.MediaItem.Builder()
                        .setMediaId("a")
                        .setUri(song("a"))
                        .setMediaMetadata(
                                new androidx.media3.common.MediaMetadata.Builder()
                                        .setExtras(extras)
                                        .build())
                        .build();
        androidx.media3.common.MediaItem tagged = SourceFirst.tag(original);
        assertEquals("existing-entry", tagged.localConfiguration.uri.getQueryParameter("bc_entry"));
        assertEquals(original.mediaId, tagged.mediaId);
        assertEquals(original.mediaMetadata, tagged.mediaMetadata);
        assertEquals(song("a"), SourcePreparation.withoutEntry(tagged.localConfiguration.uri));
        assertSame(tagged, SourceFirst.tag(tagged));
        assertEquals(
                tagged.localConfiguration.uri, tagged.buildUpon().build().localConfiguration.uri);
    }

    @Test
    public void generatedEntryTagsSeparateDuplicateLogicalIds() {
        androidx.media3.common.MediaItem original =
                new androidx.media3.common.MediaItem.Builder()
                        .setMediaId("a")
                        .setUri(song("a"))
                        .build();
        assertNotEquals(
                SourceFirst.tag(original).localConfiguration.uri,
                SourceFirst.tag(original).localConfiguration.uri);
        for (String uri :
                new String[] {
                    "file:///a.wav",
                    "content://media/1",
                    "bitchord://source?s=x&t=y",
                    song("a") + "&direct_youtube=1",
                    song("a") + "&m=1"
                }) {
            androidx.media3.common.MediaItem item =
                    new androidx.media3.common.MediaItem.Builder()
                            .setMediaId("a")
                            .setUri(uri)
                            .build();
            assertSame(item, SourceFirst.tag(item));
        }
    }

    @Test
    public void canonicalMediaItemGetterBindsAnalysisToTheSameEntryThePlayerOpens()
            throws Exception {
        java.lang.reflect.Field active = SourceFirst.class.getDeclaredField("active");
        active.setAccessible(true);
        Object before = active.get(null);
        active.set(null, preparation);
        try {
            Uri a = entry("a", "audible"), b = entry("a", "analysis-next");
            androidx.media3.common.MediaItem current =
                    new androidx.media3.common.MediaItem.Builder()
                            .setMediaId("a")
                            .setUri(a)
                            .build();
            androidx.media3.common.MediaItem next =
                    new androidx.media3.common.MediaItem.Builder()
                            .setMediaId("a")
                            .setUri(b)
                            .build();
            host.foreground = false;
            preparation.route(new Object(), new DataSpec(a));
            host.foreground = true;
            preparation.upcoming(Collections.singletonList(b));
            idle();
            assertEquals(a, SourceFirst.renditionUri(current));
            Uri analysis = SourceFirst.renditionUri(next);
            assertNotEquals(b, analysis);
            assertEquals(analysis, preparation.route(new Object(), new DataSpec(b)).uri);
            assertEquals(a, SourceFirst.renditionUri(current));
            assertEquals(analysis, SourceFirst.renditionUri(next));
            preparation.switched(false);
            assertEquals(
                    "Already bound periods keep the file analysis used",
                    analysis,
                    SourceFirst.renditionUri(next));
        } finally {
            active.set(null, before);
        }
    }

    @Test
    public void lateSameIdUpgradeCannotReplaceThePreparedDuplicateButPlainEntryCanUpgrade()
            throws Exception {
        java.lang.reflect.Field active = SourceFirst.class.getDeclaredField("active");
        active.setAccessible(true);
        Object before = active.get(null);
        active.set(null, preparation);
        try {
            Uri a = entry("a", "plain-a"), c = entry("a", "prepared-c");
            androidx.media3.common.MediaItem plain =
                    new androidx.media3.common.MediaItem.Builder()
                            .setMediaId("a")
                            .setUri(a)
                            .build();
            androidx.media3.common.MediaItem prepared =
                    new androidx.media3.common.MediaItem.Builder()
                            .setMediaId("a")
                            .setUri(c)
                            .build();
            host.foreground = false;
            preparation.route(new Object(), new DataSpec(a));
            host.foreground = true;
            preparation.upcoming(Collections.singletonList(c));
            idle();
            assertFalse(
                    "Ready candidates alone do not block a host upgrade",
                    SourceFirst.preparedEntry(prepared));
            Uri chosen = SourceFirst.renditionUri(prepared);
            assertNotEquals(c, chosen);
            assertEquals(chosen, preparation.route(new Object(), new DataSpec(c)).uri);
            host.held.clear(); // Existing pending/shelved work can bypass the host's refused set.
            assertTrue(
                    "The swap checks the exact chosen entry even when its host hold is lost",
                    SourceFirst.preparedEntry(prepared));
            assertFalse(
                    "The audible plain duplicate retains the ordinary host upgrade path",
                    SourceFirst.preparedEntry(plain));
            assertEquals(a, SourceFirst.renditionUri(plain));
            preparation.switched(false);
            assertTrue(
                    "Disabling preparation preserves the file of an open entry",
                    SourceFirst.preparedEntry(prepared));
            preparation.failed("a", c);
            assertFalse(
                    "The host can recover after that chosen entry fails",
                    SourceFirst.preparedEntry(prepared));
        } finally {
            active.set(null, before);
        }
    }

    @Test
    public void preparedUpgradeGuardRequiresAnActualBindingAndPreservesExplicitUris()
            throws Exception {
        Uri original = entry("a", "chosen");
        Uri marked = preparation.route(new Object(), new DataSpec(original)).uri;
        assertTrue(preparation.preparedEntry(original));
        assertTrue(
                "A rebuilt manifest may already carry its chosen q",
                preparation.preparedEntry(marked));
        for (Uri explicit :
                Arrays.asList(
                        Uri.parse(original + "&q=hifi"),
                        Uri.parse(original + "&q=original"),
                        Uri.parse(original + "&direct_youtube=1"),
                        Uri.parse(original + "&m=1"),
                        Uri.parse(original + "&automix_opus=1"),
                        Uri.parse(original + "&watch_read=1"),
                        Uri.parse("content://saved/a?bc_entry=chosen&v=a"),
                        entry("a", "unbound"))) {
            assertFalse(
                    "Explicit or unbound entry: " + explicit, preparation.preparedEntry(explicit));
        }
        assertFalse(preparation.preparedEntry(null));
        assertFalse(SourceFirst.preparedEntry(null));
        assertFalse(
                SourceFirst.preparedEntry(
                        new androidx.media3.common.MediaItem.Builder()
                                .setMediaId("empty")
                                .build()));
    }

    @Test
    public void sameLogicalSongFromTwoProvidersUsesSeparateFileSlots() throws Exception {
        Uri first = preparation.route(new Object(), open("a")).uri;
        preparation.close();
        host.parked.clear();
        host.provider = "other-addon";
        preparation = new SourcePreparation(host, 300);
        Uri second = preparation.route(new Object(), open("a")).uri;
        assertNotEquals(first.getQueryParameter("q"), second.getQueryParameter("q"));
        assertEquals(2, host.verified.size());
        assertTrue(host.trusted.isEmpty());
    }

    @Test
    public void oldHifiBytesStaySeparateWhetherTheirOldReaderIsActiveOrIdle() throws Exception {
        String legacy = song("a") + "&q=hifi";
        host.completeRenditions.add(legacy);
        host.locked.add("a#hifi");
        Uri first = preparation.route(new Object(), open("a")).uri;
        assertNotEquals(legacy, first.toString());
        assertTrue("An old active file is never reused or removed", host.removed.isEmpty());
        preparation.close();
        host.parked.clear();
        host.locked.clear();
        host.content = "v2";
        preparation = new SourcePreparation(host, 300);
        Uri second = preparation.route(new Object(), open("a")).uri;
        assertNotEquals(legacy, second.toString());
        assertNotEquals(first.getQueryParameter("q"), second.getQueryParameter("q"));
        assertTrue("An idle unrelated file is also outside the new slot", host.removed.isEmpty());
    }

    @Test
    public void missingRewriteAfterFirstOpenCannotSilentlySwitchAFile() throws Exception {
        preparation.close();
        host =
                new Fake() {
                    @Override
                    public DataSpec rewrite(DataSpec request, Uri chosen) {
                        if (request.position > 0)
                            throw new IllegalStateException("Bridge unavailable");
                        return super.rewrite(request, chosen);
                    }
                };
        preparation = new SourcePreparation(host, 300);
        Object period = new Object();
        preparation.route(period, open("a"));
        try {
            preparation.route(period, new DataSpec(song("a"), 4096, -1));
            fail("A read failure must not join another file");
        } catch (IOException expected) {
        }
    }

    @Test
    public void currentPlainEntryKeepsItsActualKeyWhenGlobalSourceStateChanges() {
        Uri a = entry("a", "audible");
        assertEquals("a", preparation.cacheKey(a, "a"));
        assertEquals("a", preparation.cacheKey(a, "a#alt-noatmos"));
        assertEquals("a", preparation.cacheKey(a, "a#alt"));
    }

    @Test
    public void duplicateEntriesFreezeDifferentKeysIndependently() {
        Uri first = entry("a", "before-policy"), next = entry("a", "after-policy");
        assertEquals("a", preparation.cacheKey(first, "a"));
        assertEquals("a#alt-noatmos", preparation.cacheKey(next, "a#alt-noatmos"));
        assertEquals("a", preparation.cacheKey(first, "a#alt-noatmos"));
        assertEquals("a#alt-noatmos", preparation.cacheKey(next, "a"));
        assertEquals("b", preparation.cacheKey(entry("b", "before-policy"), "b"));
    }

    @Test
    public void aMappedCopyAlwaysUsesItsOwnKeyAlongsideTheFrozenPlainKey() {
        Uri plain = entry("a", "same-entry");
        preparation.cacheKey(plain, "a");
        Uri mapped = SourcePreparation.marked(plain, "one-file");
        String key = "a#" + mapped.getQueryParameter("q");
        assertEquals(key, preparation.cacheKey(mapped, key));
        assertEquals("a#another-rendition", preparation.cacheKey(mapped, "a#another-rendition"));
        assertEquals("a", preparation.cacheKey(plain, "a#alt-noatmos"));
    }

    @Test
    public void cacheKeyTagDoesNotChangeTheHostsFirstComputedAnswer() {
        Uri tagged = entry("a", "first-read");
        assertEquals(
                "a#existing-complete-copy",
                preparation.cacheKey(tagged, "a#existing-complete-copy"));
        assertEquals(
                "a#existing-complete-copy", preparation.cacheKey(tagged, "a#new-global-choice"));
    }

    @Test
    public void explicitAndUntaggedCacheKeysAreNeverFrozen() {
        for (Uri uri :
                Arrays.asList(
                        song("a"),
                        Uri.parse("file:///a.wav"),
                        Uri.parse("content://media/1"),
                        Uri.parse("bitchord://source?s=x&t=y&bc_entry=source"),
                        Uri.parse(entry("a", "video") + "&m=1"),
                        Uri.parse(entry("a", "pin") + "&direct_youtube=1"),
                        Uri.parse(entry("a", "analysis") + "&automix_opus=1"),
                        Uri.parse(entry("a", "watch") + "&watch_read=1"))) {
            assertEquals("first", preparation.cacheKey(uri, "first"));
            assertEquals("second", preparation.cacheKey(uri, "second"));
        }
        assertEquals(0, preparation.rememberedKeys());
    }

    @Test
    public void idleCacheKeyMemoryIsBoundedWithoutDroppingEitherLiveDeck() throws Exception {
        Uri first = entry("a", "open-period"), spare = entry("b", "current-spare");
        host.foreground = false;
        Object period = new Object();
        preparation.route(period, new DataSpec(first));
        preparation.cacheKey(first, "a");
        preparation.cacheKey(spare, "b");
        preparation.retainCurrentEntries(Collections.singletonList(spare));
        for (int i = 0; i < SourcePreparation.REMEMBERED + 100; i++)
            preparation.cacheKey(entry("old" + i, "past" + i), "old" + i);
        assertTrue(preparation.rememberedKeys() <= SourcePreparation.REMEMBERED + 2);
        assertEquals("a", preparation.cacheKey(first, "a#alt"));
        assertEquals("b", preparation.cacheKey(spare, "b#alt"));
        assertEquals(first, preparation.route(period, new DataSpec(first)).uri);
    }

    @Test
    public void publicCacheBridgeSharesTheActiveEntryDecision() throws Exception {
        java.lang.reflect.Field active = SourceFirst.class.getDeclaredField("active");
        active.setAccessible(true);
        Object before = active.get(null);
        active.set(null, preparation);
        try {
            Uri uri = entry("a", "public-key");
            assertNull(SourceFirst.cacheKey(uri, null));
            assertEquals("a", SourceFirst.cacheKey(uri, "a"));
            assertEquals("a", SourceFirst.cacheKey(uri, "a#alt-noatmos"));
            active.set(null, null);
            assertEquals("host-default", SourceFirst.cacheKey(uri, "host-default"));
        } finally {
            active.set(null, before);
        }
    }
}

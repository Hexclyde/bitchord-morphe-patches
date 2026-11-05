package app.bitchord.soulseek;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.HorizontalScrollView;
import android.widget.TextView;

import com.music.bitchord.data.model.Song;

import kotlin.Unit;
import kotlin.jvm.functions.Function2;

import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.io.IOException;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class BatchDownloadPageTest {
    ActivityController<Activity> controller;
    Activity activity;
    BatchDownloadPage page;
    Fake fake;
    final String token = "duration-batch";
    Locale before;

    /** Stands in for the host: every search is answered from memory and recorded. */
    static final class Fake implements DownloadBatch.Searcher {
        final List<DownloadBatch.SourceRef> refs = new ArrayList<>();
        final Map<String, List<Song>> songs = new HashMap<>();
        final Set<String> failing = Collections.synchronizedSet(new HashSet<>());
        final List<String> queries = new CopyOnWriteArrayList<>();
        volatile CountDownLatch gate;
        final Map<String, CountDownLatch> sourceGates = new ConcurrentHashMap<>();
        volatile boolean soulseek;
        volatile DownloadBatch.SoulseekResult result;

        Fake source(String id, Song... found) {
            refs.add(new DownloadBatch.SourceRef(null, id, "Addon " + id));
            songs.put(id, Arrays.asList(found));
            return this;
        }

        volatile boolean sourcesFail;

        public List<DownloadBatch.SourceRef> sources() throws Exception {
            if (sourcesFail) throw new IOException("registry");
            return refs;
        }

        public List<?> search(DownloadBatch.SourceRef source, String query) throws Exception {
            queries.add(source.id + ":" + query);
            List<Song> answer = songs.getOrDefault(source.id, Collections.emptyList());
            CountDownLatch wait = gate, own = sourceGates.get(source.id);
            if (wait != null) wait.await();
            if (own != null) own.await();
            if (failing.contains(source.id)) throw new IOException("down");
            return answer;
        }

        public boolean soulseekEnabled() {
            return soulseek;
        }

        public DownloadBatch.SoulseekResult soulseek(String query) throws Exception {
            queries.add("soulseek:" + query);
            CountDownLatch wait = gate;
            if (wait != null) wait.await();
            if (failing.contains("soulseek")) throw new IOException("down");
            return result;
        }
    }

    @Before
    public void open() throws Exception {
        before = Locale.getDefault();
        Locale.setDefault(Locale.ENGLISH);
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        offer(token);
        build();
    }

    /** Registers a three-track batch the way the album and playlist hooks do. */
    static void offer(String token) throws Exception {
        Class<?> pendingType = Class.forName(SoulseekDownloadChoice.class.getName() + "$Pending");
        Constructor<?> constructor = pendingType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Function2<Object, Object, Object> original = (songs, from) -> Unit.INSTANCE;
        List<Song> songs =
                Arrays.asList(
                        new Song("first-track", "First", "First artist", "3:14"),
                        new Song("second-track", "Second", "Second artist", "5:02"),
                        new Song("third-track", "Third", "Third artist", "?"));
        Field pending = SoulseekDownloadChoice.class.getDeclaredField("pending");
        pending.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) pending.get(null);
        map.put(token, constructor.newInstance(original, songs, null));
    }

    /** A fresh page on the current resources, shown as if its screen were in front. */
    void build() throws Exception {
        if (page != null) page.onDestroy();
        page =
                new BatchDownloadPage(
                        activity,
                        new Intent().putExtra(SoulseekDownloadChoice.EXTRA_CHOICE, token));
        page.onCreate(null);
        fake = new Fake();
        page.searcher = fake;
        Field foreground = BatchDownloadPage.class.getDeclaredField("foreground");
        foreground.setAccessible(true);
        foreground.setBoolean(page, true);
        render();
    }

    void render() throws Exception {
        Method method = BatchDownloadPage.class.getDeclaredMethod("render");
        method.setAccessible(true);
        method.invoke(page);
    }

    @After
    public void close() {
        if (page != null) page.onDestroy();
        SoulseekDownloadChoice.forget(token);
        DownloadBatch.forget(token);
        if (controller != null) controller.pause().stop().destroy();
        RuntimeEnvironment.getApplication().getApplicationInfo().flags &=
                ~android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL;
        Locale.setDefault(before);
    }

    static List<View> named(View root, String text) {
        List<View> matches = new ArrayList<>();
        if (root instanceof TextView && ((TextView) root).getText().toString().equals(text))
            matches.add(root);
        if (root instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++)
                matches.addAll(named(((ViewGroup) root).getChildAt(i), text));
        return matches;
    }

    static void collect(View root, String prefix, List<View> out) {
        if (root.getTag() instanceof String && ((String) root.getTag()).startsWith(prefix))
            out.add(root);
        if (root instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++)
                collect(((ViewGroup) root).getChildAt(i), prefix, out);
    }

    List<View> tagged(String prefix) {
        List<View> out = new ArrayList<>();
        collect(page.view, prefix, out);
        return out;
    }

    View tag(String exact) {
        return page.view.findViewWithTag(exact);
    }

    static String allText(View view) {
        StringBuilder text = new StringBuilder();
        if (view instanceof TextView) text.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
                text.append(allText(((ViewGroup) view).getChildAt(i)));
        return text.toString();
    }

    static int descendants(View root) {
        int count = 1;
        if (root instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++)
                count += descendants(((ViewGroup) root).getChildAt(i));
        return count;
    }

    List<View> choices() {
        return named(page.view, "Choose source and quality");
    }

    void await(BooleanSupplier ready) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle();
            if (ready.getAsBoolean()) return;
            Thread.sleep(10);
        }
        fail("timed out waiting for the page");
    }

    int rowCount() {
        return tagged("batch-candidate:").size();
    }

    void toggle(int index) {
        tag("batch-toggle:" + index).performClick();
    }

    /** Opens a track and waits until every source has answered. */
    void expand(int index, int rows) throws Exception {
        toggle(index);
        await(() -> rowCount() == rows && !allText(page.view).contains("Searching"));
    }

    static String key(String source, int n) {
        return source + "\u0000src:" + source + "::" + n;
    }

    static Song song(String source, int n, String title, String artist, String quality) {
        return new Song(
                "src:" + source + "::" + n,
                title,
                artist,
                null,
                "5:01",
                null,
                null,
                null,
                null,
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                quality,
                false,
                null,
                null,
                null,
                null,
                null);
    }

    static SoulseekTrack track(
            String peer, String name, long size, int seconds, int depth, int rate)
            throws Exception {
        return new SoulseekTrack(
                new JSONObject()
                        .put("peer", peer)
                        .put("path", "Second artist - " + name + ".flac")
                        .put("size", size)
                        .put("duration", seconds)
                        .put("bitDepth", depth)
                        .put("sampleRate", rate));
    }

    DownloadBatch.SoulseekResult soulseek(
            AtomicBoolean current, AtomicInteger transfers, SoulseekTrack... rows) {
        return new DownloadBatch.SoulseekResult(
                Arrays.asList(rows),
                "session",
                current::get,
                (app, file, session, now) -> transfers.incrementAndGet());
    }

    @Test
    public void everyTrackShowsItsOwnOriginalDurationIncludingUnknown() {
        assertEquals(1, named(page.view, "Duration · 3:14").size());
        assertEquals(1, named(page.view, "Duration · 5:02").size());
        assertEquals(1, named(page.view, "Duration · Not reported").size());
    }

    @Test
    public void secondRowChooseSourceExpandsInsideItsTileWithoutLaunching() {
        View button = choices().get(1);
        ViewGroup tile = (ViewGroup) tag("batch-candidates:1").getParent();
        ViewGroup rows = (ViewGroup) tile.getParent();
        int before = descendants(tile);
        int siblings = descendants(rows) - before;
        button.performClick();
        assertNull(
                "expanding a track must not open another screen",
                shadowOf(activity).getNextStartedActivity());
        assertSame("tile stays in place", rows, tile.getParent());
        assertTrue("candidates open inside the same tile", descendants(tile) > before);
        assertEquals("other tiles are untouched", siblings, descendants(rows) - descendants(tile));
        assertEquals("still one header per requested track", 3, tagged("batch-toggle:").size());
    }

    @Test
    public void explorerStillReceivesTrackMetadataFromItsIntent() {
        Intent next =
                new Intent()
                        .putExtra(SoulseekDownloadChoice.EXTRA_CHOICE, token)
                        .putExtra("download.batch.index", 1)
                        .putExtra("download.track.title", "Second")
                        .putExtra("download.track.artist", "Second artist")
                        .putExtra("download.track.duration", "5:02");
        DownloadPage summary = new DownloadPage(activity, next) {};
        DownloadExplorerView explorer =
                new DownloadExplorerView(summary, true, () -> {}, () -> {}, (option, now) -> {});
        assertEquals(1, named(explorer.root, "Second artist · 5:02").size());
        assertTrue(named(explorer.root, "First artist · 3:14").isEmpty());
    }

    @Test
    public void expandingOneTrackSearchesOnlyThatTrackAndKeepsOthersClosed() throws Exception {
        fake.source("a", song("a", 1, "Second", "Second artist", "FLAC"));
        expand(1, 1);
        assertEquals(Collections.singletonList("a:Second artist Second"), fake.queries);
        for (String closed : new String[] {"batch-candidates:0", "batch-candidates:2"}) {
            List<View> rowsInside = new ArrayList<>();
            collect(tag(closed), "batch-candidate:", rowsInside);
            assertTrue(closed, rowsInside.isEmpty());
        }
        assertEquals(View.GONE, tag("batch-candidates:0").getVisibility());
        assertEquals(View.VISIBLE, tag("batch-candidates:1").getVisibility());
        assertEquals(1, named(page.view, "Duration · 5:02").size());
        assertEquals(1, named(page.view, "Duration · 3:14").size());
        assertNull(shadowOf(activity).getNextStartedActivity());
    }

    @Test
    public void renderAndOpeningThePageNeverSearchClosedTracks() throws Exception {
        fake.source("a", song("a", 1, "First", "First artist", "FLAC"));
        render();
        render();
        page.onStart();
        await(() -> true);
        Thread.sleep(100);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(fake.queries.isEmpty());
        assertEquals(0, rowCount());
    }

    @Test
    public void candidateRowsShowOnlyWhatTheSourceReported() throws Exception {
        AtomicInteger transfers = new AtomicInteger();
        fake.source(
                "a",
                song("a", 1, "Second", "Second artist", "FLAC 24-bit / 96 kHz"),
                song("a", 2, "Second", "Second artist", "FLAC"),
                song("a", 3, "Second", "Second artist", ""));
        fake.soulseek = true;
        SoulseekTrack track = track("peer-one", "Second", 40_000_000L, 301, 24, 96000);
        fake.result = soulseek(new AtomicBoolean(true), transfers, track);
        expand(1, 4);
        // Visible rows: one compact quality line, provider, and a clock/size line.
        String exact = allText(tag("batch-candidate:" + key("a", 1)));
        assertEquals(exact, 1, count(exact, "FLAC 24-bit / 96 kHz"));
        assertFalse(exact, exact.contains("Bit depth"));
        assertFalse(exact, exact.contains("Sample rate"));
        assertFalse(
                "the requested title is not repeated", exact.contains("Second · Second artist"));
        assertTrue(exact, exact.contains("Addon a"));
        assertTrue(exact, exact.contains("5:01 · File size: Not reported"));
        // Every reported field is still spoken by the radio.
        String spoken = tag("batch-choose:1:" + key("a", 1)).getContentDescription().toString();
        assertTrue(spoken, spoken.contains("Format: FLAC 24-bit / 96 kHz"));
        assertTrue(spoken, spoken.contains("Bit depth: 24 bits"));
        assertTrue(spoken, spoken.contains("Sample rate: 96 kHz"));
        assertTrue(spoken, spoken.contains("Bitrate: Not reported"));
        assertTrue(spoken, spoken.contains("File size: Not reported"));
        assertTrue(spoken, spoken.contains("Duration: 5:01"));
        String plain = allText(tag("batch-candidate:" + key("a", 2)));
        assertTrue(plain, plain.contains("FLAC"));
        assertFalse(plain, plain.contains("Bit depth"));
        assertTrue(
                tag("batch-choose:1:" + key("a", 2))
                        .getContentDescription()
                        .toString()
                        .contains("Bit depth: Not reported"));
        String blank = allText(tag("batch-candidate:" + key("a", 3)));
        assertTrue(blank, blank.contains("Format: Not reported"));
        String peer = allText(tag("batch-candidate:soulseek\u0000" + track.id));
        assertEquals(peer, 1, count(peer, "FLAC · 24 bits · 96 kHz"));
        assertTrue(peer, peer.contains("Soulseek · Peer: peer-one"));
        assertTrue(peer, peer.contains("5:01 · "));
        assertFalse(peer, peer.contains("Not reported"));
        String peerSpoken =
                tag("batch-choose:1:soulseek\u0000" + track.id).getContentDescription().toString();
        assertTrue(peerSpoken, peerSpoken.contains("Bit depth: 24 bits"));
        assertTrue(peerSpoken, peerSpoken.contains("Duration: 5:01"));
        assertEquals(0, transfers.get());
    }

    static int count(String text, String part) {
        int found = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length()))
            found++;
        return found;
    }

    @Test
    public void aDifferentVersionTitleShowsOnlyWhenItDiffersFromTheRequest() throws Exception {
        fake.source(
                "a",
                song("a", 1, "Second", "Second artist", "FLAC"),
                song("a", 2, "Second (Live)", "Second artist", "FLAC"));
        expand(1, 2);
        assertFalse(allText(tag("batch-candidate:" + key("a", 1))).contains("Second · "));
        assertTrue(
                allText(tag("batch-candidate:" + key("a", 2)))
                        .contains("Second (Live) · Second artist"));
        tag("batch-choose:1:" + key("a", 2)).performClick();
        assertTrue(allText(tag("batch-selected:1")).contains("Second (Live) · Second artist"));
        tag("batch-choose:1:" + key("a", 1)).performClick();
        assertFalse(allText(tag("batch-selected:1")).contains("Second"));
        // The request's own title and duration stay in the header, once.
        assertEquals(1, named(page.view, "Second · Second artist").size());
        assertEquals(1, named(page.view, "Duration · 5:02").size());
    }

    @Test
    public void filtersOfferOnlyReportedSpecsAndDoNotChangeTheSelection() throws Exception {
        AtomicInteger transfers = new AtomicInteger();
        fake.source(
                "a",
                song("a", 1, "Second", "Second artist", "FLAC 24-bit / 96 kHz"),
                song("a", 2, "Second", "Second artist", "FLAC"),
                song("a", 3, "Second", "Second artist", ""));
        fake.soulseek = true;
        SoulseekTrack track = track("peer-one", "Second", 40_000_000L, 301, 24, 96000);
        fake.result = soulseek(new AtomicBoolean(true), transfers, track);
        expand(1, 4);
        Set<Object> chips = new HashSet<>();
        for (View chip : tagged("batch-filter:1:")) chips.add(chip.getTag());
        Set<Object> expected =
                new HashSet<>(
                        Arrays.asList(
                                "batch-filter:1:all",
                                "batch-filter:1:FLAC",
                                "batch-filter:1:FLAC · 24 bits · 96 kHz",
                                "batch-filter:1:\u0000none"));
        assertEquals("no invented variants", expected, chips);
        assertEquals(
                "equivalent source labels share one filter",
                expected.size(),
                tagged("batch-filter:1:").size());
        tag("batch-choose:1:" + key("a", 2)).performClick();
        tag("batch-filter:1:FLAC · 24 bits · 96 kHz").performClick();
        assertEquals(2, rowCount());
        assertNotNull(tag("batch-candidate:soulseek\u0000" + track.id));
        assertNotNull(tag("batch-candidate:" + key("a", 1)));
        tag("batch-filter:1:\u0000none").performClick();
        assertEquals(1, rowCount());
        assertNotNull(tag("batch-candidate:" + key("a", 3)));
        tag("batch-filter:1:all").performClick();
        assertEquals(4, rowCount());
        assertEquals(key("a", 2), DownloadBatch.choice(token, 1).candidate.key);
        assertNull(DownloadBatch.choice(token, 0));
        assertNull(DownloadBatch.choice(token, 2));
        assertEquals(0, transfers.get());
    }

    @Test
    public void choiceStaysVisibleWhenCollapsedAndStoresOnlyUntilConfirmed() throws Exception {
        AtomicInteger transfers = new AtomicInteger();
        fake.soulseek = true;
        SoulseekTrack track = track("peer-one", "Second", 40_000_000L, 301, 24, 96000);
        fake.result = soulseek(new AtomicBoolean(true), transfers, track);
        expand(1, 1);
        tag("batch-choose:1:soulseek\u0000" + track.id).performClick();
        DownloadBatch.Choice choice = DownloadBatch.choice(token, 1);
        assertEquals("soulseek\u0000" + track.id, choice.candidate.key);
        assertEquals(0, transfers.get());
        assertTrue(SoulseekSelectedDownloads.selectedReferences.isEmpty());
        toggle(1);
        assertEquals(View.GONE, tag("batch-candidates:1").getVisibility());
        String summary = allText(tag("batch-selected:1"));
        assertTrue(summary, summary.contains("Selected audio file · Soulseek"));
        assertEquals(summary, 1, count(summary, "FLAC · 24 bits · 96 kHz"));
        assertTrue(summary, summary.contains("5:01 · "));
        assertFalse(summary, summary.contains("Bit depth"));
        assertFalse("the request's title is not repeated", summary.contains("Second"));
        // One row: the wide toggle plus a 48dp clear control, not a text Unselect button.
        View clear = tag("batch-unselect:1");
        assertNotNull(clear);
        assertTrue(clear instanceof android.widget.ImageButton);
        assertSame(tag("batch-toggle:1").getParent(), clear.getParent());
        assertTrue(named(page.view, "Unselect").isEmpty());
        assertTrue(clear.getContentDescription().toString().startsWith("Unselect"));
        assertTrue(allText(tag("batch-selected:0")).contains("Source not selected"));
        toggle(1);
        assertTrue(((CompoundButton) tag("batch-choose:1:soulseek\u0000" + track.id)).isChecked());
        tag("batch-unselect:1").performClick();
        assertNull(DownloadBatch.choice(token, 1));
        assertEquals(0, transfers.get());
    }

    @Test
    public void selectionSurvivesRestartAndRetryOfItsSource() throws Exception {
        fake.source("a", song("a", 1, "Second", "Second artist", "FLAC"));
        expand(1, 1);
        tag("batch-choose:1:" + key("a", 1)).performClick();
        page.onStop();
        assertNotNull(DownloadBatch.choice(token, 1));
        page.onStart();
        await(() -> rowCount() == 1 && tag("batch-choose:1:" + key("a", 1)) != null);
        assertTrue(((CompoundButton) tag("batch-choose:1:" + key("a", 1))).isChecked());
        assertEquals(key("a", 1), DownloadBatch.choice(token, 1).candidate.key);
    }

    @Test
    public void oneFailedSourceKeepsOthersAndRetriesInTheSameTile() throws Exception {
        fake.source("a", song("a", 1, "Second", "Second artist", "FLAC"));
        fake.source("b", song("b", 1, "Second", "Second artist", "MP3 320kbps"));
        fake.failing.add("b");
        toggle(1);
        await(() -> tag("batch-retry:1:b") != null && rowCount() == 1);
        assertNotNull(tag("batch-candidate:" + key("a", 1)));
        assertNull(tag("batch-retry:1:a"));
        assertTrue(allText(tag("batch-candidates:1")).contains("Addon b: Source did not answer."));
        View card = (View) tag("batch-candidates:1").getParent();
        fake.failing.clear();
        tag("batch-retry:1:b").performClick();
        await(() -> rowCount() == 2 && tag("batch-retry:1:b") == null);
        assertSame(card, tag("batch-candidates:1").getParent());
        String retried = allText(tag("batch-candidate:" + key("b", 1)));
        assertTrue(retried, retried.contains("MP3 320kbps"));
        assertTrue(
                tag("batch-choose:1:" + key("b", 1))
                        .getContentDescription()
                        .toString()
                        .contains("Bitrate: 320 kbps"));
        int a = 0, b = 0;
        for (String query : fake.queries) {
            if (query.startsWith("a:")) a++;
            if (query.startsWith("b:")) b++;
        }
        assertEquals(1, a);
        assertEquals(2, b);
    }

    @Test
    public void unreadySoulseekShowsItsOwnErrorAndRetry() throws Exception {
        fake.soulseek = true;
        fake.failing.add("soulseek");
        toggle(1);
        await(() -> tag("batch-retry:1:soulseek") != null);
        assertTrue(
                allText(tag("batch-candidates:1"))
                        .contains("Soulseek: Soulseek is not ready. Check account and setup."));
    }

    @Test
    public void lateResultsAfterStopAreDroppedAndOpenCardsSearchAgain() throws Exception {
        fake.source("a", song("a", 1, "Second", "Second artist", "FLAC"));
        fake.gate = new CountDownLatch(1);
        toggle(1);
        await(() -> !fake.queries.isEmpty());
        page.onStop();
        fake.gate.countDown();
        Thread.sleep(150);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, rowCount());
        fake.gate = null;
        page.onStart();
        await(() -> rowCount() == 1);
        assertEquals(2, fake.queries.size());
    }

    @Test
    public void staleLookupResultIsIgnoredAfterTheCardIsSearchedAgain() throws Exception {
        fake.source("a", song("a", 1, "Second", "Second artist", "FLAC"));
        fake.gate = new CountDownLatch(1);
        toggle(1);
        await(() -> fake.queries.size() == 1);
        Field lookups = BatchDownloadPage.class.getDeclaredField("lookups");
        lookups.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, Object> map = (Map<Integer, Object>) lookups.get(page);
        assertNotNull(map.remove(1));
        fake.songs.put("a", Arrays.asList(song("a", 2, "Second", "Second artist", "MP3")));
        toggle(1);
        toggle(1);
        await(() -> fake.queries.size() == 2);
        fake.gate.countDown();
        await(() -> rowCount() == 1);
        Thread.sleep(100);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, rowCount());
        assertNotNull(tag("batch-candidate:" + key("a", 2)));
        assertNull(tag("batch-candidate:" + key("a", 1)));
    }

    @Test
    public void expiredBatchRefusesSelection() throws Exception {
        fake.source("a", song("a", 1, "Second", "Second artist", "FLAC"));
        expand(1, 1);
        SoulseekDownloadChoice.forget(token);
        tag("batch-choose:1:" + key("a", 1)).performClick();
        assertNull(DownloadBatch.choice(token, 1));
        assertTrue(allText(page.view).contains("This track was submitted or the batch expired."));
    }

    @Test
    public void staleSoulseekSessionCannotBeChosen() throws Exception {
        AtomicInteger transfers = new AtomicInteger();
        AtomicBoolean current = new AtomicBoolean(true);
        fake.soulseek = true;
        SoulseekTrack track = track("peer-one", "Second", 1000, 0, 0, 0);
        fake.result = soulseek(current, transfers, track);
        expand(1, 1);
        View choose = tag("batch-choose:1:soulseek\u0000" + track.id);
        assertTrue(choose.isEnabled());
        current.set(false);
        choose.performClick();
        assertNull(DownloadBatch.choice(token, 1));
        assertTrue(allText(page.view).contains("This Soulseek result expired. Search again."));
        assertFalse(tag("batch-choose:1:soulseek\u0000" + track.id).isEnabled());
        assertEquals(0, transfers.get());
    }

    @Test
    public void oversizedFilesCannotBeChosen() throws Exception {
        fake.soulseek = true;
        SoulseekTrack track = track("peer", "Second", DownloadBatch.FILE_LIMIT + 1, 0, 0, 0);
        fake.result = soulseek(new AtomicBoolean(true), new AtomicInteger(), track);
        expand(1, 1);
        assertFalse(tag("batch-choose:1:soulseek\u0000" + track.id).isEnabled());
        assertTrue(allText(page.view).contains("Selected files are limited to 256 MiB."));
    }

    @Test
    public void nothingTransfersUntilTheDialogIsConfirmed() throws Exception {
        AtomicInteger transfers = new AtomicInteger();
        fake.soulseek = true;
        SoulseekTrack track = track("peer", "Second", 1000, 0, 0, 0);
        fake.result = soulseek(new AtomicBoolean(true), transfers, track);
        expand(1, 1);
        tag("batch-choose:1:soulseek\u0000" + track.id).performClick();
        tag("batch-unselect:1").performClick();
        tag("batch-choose:1:soulseek\u0000" + track.id).performClick();
        render();
        assertEquals(0, transfers.get());
        View untouched = (View) tag("batch-candidates:0").getParent();
        View submittedCard = (View) tag("batch-candidates:1").getParent();
        for (View button : named(page.view, "Download selected now")) button.performClick();
        assertEquals(0, transfers.get());
        android.app.AlertDialog dialog =
                (android.app.AlertDialog)
                        org.robolectric.shadows.ShadowAlertDialog.getLatestDialog();
        assertNotNull(dialog);
        assertEquals(0, transfers.get());
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        await(() -> transfers.get() == 1);
        assertTrue(DownloadBatch.choice(token, 1).submitted);
        await(() -> tag("batch-toggle:1") == null);
        // Submission refreshes the changed card in place; no card is rebuilt.
        assertSame(untouched, tag("batch-candidates:0").getParent());
        assertSame(submittedCard, tag("batch-candidates:1").getParent());
        assertEquals(View.GONE, tag("batch-candidates:1").getVisibility());
        assertEquals(0, rowCount());
        assertTrue(allText(tag("batch-selected:1")).contains("Submitted"));
        assertNull(tag("batch-unselect:1"));
    }

    @Test
    public void staleSoulseekOffersGetOneRetryThatRefreshesTheRowAndNeedsReselecting()
            throws Exception {
        AtomicInteger oldTransfers = new AtomicInteger(), newTransfers = new AtomicInteger();
        AtomicBoolean oldSession = new AtomicBoolean(true), newSession = new AtomicBoolean(true);
        fake.source("a", song("a", 1, "Second", "Second artist", "FLAC"));
        fake.soulseek = true;
        SoulseekTrack old = track("peer", "Second", 1000, 301, 24, 96000);
        fake.result = soulseek(oldSession, oldTransfers, old);
        expand(1, 2);
        String radio = "batch-choose:1:soulseek\u0000" + old.id;
        tag(radio).performClick();
        DownloadBatch.Candidate chosen = DownloadBatch.choice(token, 1).candidate;
        assertTrue(tag(radio).getContentDescription().toString().contains("96 kHz"));
        assertTrue(tag(radio).getContentDescription().toString().contains("Duration: 5:01"));
        assertNull(tag("batch-retry:1:soulseek"));
        View card = (View) tag("batch-candidates:1").getParent();
        oldSession.set(false);
        tag("batch-filter:1:all").performClick();
        assertFalse(tag(radio).isEnabled());
        assertNotNull("one explicit Retry for the ended session", tag("batch-retry:1:soulseek"));
        assertTrue(allText(page.view).contains("This Soulseek result expired. Search again."));
        View chip = tag("batch-filter:1:FLAC");
        chip.setFocusableInTouchMode(true);
        assertTrue(chip.requestFocus());

        SoulseekTrack fresh = track("peer", "Second", 1000, 600, 16, 44100);
        assertEquals("same offer reference", old.id, fresh.id);
        fake.result = soulseek(newSession, newTransfers, fresh);
        tag("batch-retry:1:soulseek").performClick();
        String row = "batch-candidate:soulseek\u0000" + old.id;
        await(() -> tag("batch-retry:1:soulseek") == null && allText(tag(row)).contains("10:00"));
        String text = allText(tag(row));
        assertTrue(text, text.contains("FLAC · 16 bits · 44.1 kHz"));
        assertFalse(text, text.contains("5:01"));
        String refreshed = tag(radio).getContentDescription().toString();
        assertTrue(refreshed, refreshed.contains("Bit depth: 16 bits"));
        assertTrue(refreshed, refreshed.contains("Duration: 10:00"));
        CompoundButton again = (CompoundButton) tag(radio);
        assertTrue(again.isEnabled());
        assertFalse("a refreshed offer must be chosen again", again.isChecked());
        assertSame(chosen, DownloadBatch.choice(token, 1).candidate);
        assertSame(chip, tag("batch-filter:1:FLAC"));
        assertSame(chip, tag("batch-candidates:1").findFocus());
        assertSame(card, tag("batch-candidates:1").getParent());
        assertEquals(0, oldTransfers.get());
        assertEquals(0, newTransfers.get());
        int searches = 0;
        for (String query : fake.queries) if (query.startsWith("soulseek:")) searches++;
        assertEquals(2, searches);

        again.performClick();
        assertNotSame(chosen, DownloadBatch.choice(token, 1).candidate);
        assertEquals(1, DownloadBatch.submit(token, false));
        assertEquals(0, oldTransfers.get());
        assertEquals(1, newTransfers.get());
    }

    @Test
    public void endedSessionKeepsTheChosenOfferVisibleButNotPickable() throws Exception {
        AtomicBoolean session = new AtomicBoolean(true);
        fake.soulseek = true;
        SoulseekTrack track = track("peer", "Second", 1000, 301, 24, 96000);
        fake.result = soulseek(session, new AtomicInteger(), track);
        expand(1, 1);
        String radio = "batch-choose:1:soulseek\u0000" + track.id;
        tag(radio).performClick();
        assertTrue(((CompoundButton) tag(radio)).isChecked());
        session.set(false);
        toggle(1);
        toggle(1);
        assertTrue("the chosen offer stays checked", ((CompoundButton) tag(radio)).isChecked());
        assertFalse("but cannot be picked in an ended session", tag(radio).isEnabled());
        assertEquals("soulseek\u0000" + track.id, DownloadBatch.choice(token, 1).candidate.key);
    }

    @Test
    public void bulkChosenOfferStaysCheckedWhileFocusAndRowsStay() throws Exception {
        AtomicInteger transfers = new AtomicInteger();
        fake.source("a", song("a", 1, "Second", "Second artist", "FLAC"));
        fake.soulseek = true;
        SoulseekTrack track = track("peer", "Second", 1000, 301, 24, 96000);
        DownloadBatch.SoulseekResult result = soulseek(new AtomicBoolean(true), transfers, track);
        fake.result = result;
        // A bulk match stores its own instance of the same offer.
        DownloadBatch.select(token, 1, DownloadBatch.fromSoulseek(result, activity).get(0));
        expand(1, 2);
        View radio = tag("batch-choose:1:soulseek\u0000" + track.id);
        assertTrue(((CompoundButton) radio).isChecked());
        View chip = tag("batch-filter:1:FLAC");
        chip.setFocusableInTouchMode(true);
        assertTrue(chip.requestFocus());
        tag("batch-filter:1:all").performClick();
        assertSame(radio, tag("batch-choose:1:soulseek\u0000" + track.id));
        assertSame(chip, tag("batch-candidates:1").findFocus());
        assertEquals(0, transfers.get());
    }

    @Test
    public void sourceDiscoveryFailureKeepsTheSoulseekBulkAction() throws Exception {
        fake.sourcesFail = true;
        page.onStart();
        Thread.sleep(150);
        shadowOf(Looper.getMainLooper()).idle();
        assertNotNull(tag("batch-find:soulseek"));
        assertTrue(fake.queries.isEmpty());
        assertNull(shadowOf(activity).getNextStartedActivity());
    }

    @Test
    public void editedSearchRunsOnlyThatCardAndKeepsTheBoxTheTrackAndTheChoice() throws Exception {
        fake.source("a", song("a", 1, "Second", "Second artist", "FLAC"));
        fake.sourceGates.put("a", new CountDownLatch(1));
        toggle(1);
        await(() -> fake.queries.size() == 1);
        android.widget.EditText box = (android.widget.EditText) tag("batch-query:1");
        assertEquals("Second artist Second", box.getText().toString());
        box.setFocusableInTouchMode(true);
        assertTrue(box.requestFocus());
        box.setText("Edited artist Edited title");
        box.setSelection(3, 6);
        fake.songs.put(
                "a", Arrays.asList(song("a", 2, "Edited title", "Edited artist", "MP3 320kbps")));
        tag("batch-search:1").performClick();
        await(() -> fake.queries.size() == 2);
        assertEquals("a:Edited artist Edited title", fake.queries.get(1));
        fake.sourceGates.get("a").countDown();
        await(() -> tag("batch-candidate:" + key("a", 2)) != null);
        Thread.sleep(100);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("the older query's answer is ignored", 1, rowCount());
        assertNull(tag("batch-candidate:" + key("a", 1)));
        assertSame(box, tag("batch-query:1"));
        assertSame(box, tag("batch-candidates:1").findFocus());
        assertEquals("Edited artist Edited title", box.getText().toString());
        assertEquals(3, box.getSelectionStart());
        assertEquals(6, box.getSelectionEnd());
        // The requested track, its duration and the other cards are unchanged.
        assertEquals(1, named(page.view, "Second · Second artist").size());
        assertEquals(1, named(page.view, "Duration · 5:02").size());
        assertEquals(1, named(page.view, "Duration · 3:14").size());
        assertEquals(2, fake.queries.size());
        tag("batch-choose:1:" + key("a", 2)).performClick();
        assertEquals(key("a", 2), DownloadBatch.choice(token, 1).candidate.key);
        assertNull(DownloadBatch.choice(token, 0));
        assertNull(DownloadBatch.choice(token, 2));
        assertTrue(SoulseekSelectedDownloads.selectedReferences.isEmpty());
        assertNull(shadowOf(activity).getNextStartedActivity());
        toggle(1);
        toggle(1);
        assertSame(box, tag("batch-query:1"));
        assertEquals("Edited artist Edited title", box.getText().toString());
        // Too short a query is refused without searching or touching the choice.
        box.setText("a");
        tag("batch-search:1").performClick();
        assertTrue(
                allText(page.view)
                        .contains("Enter an artist and song title, up to 256 characters."));
        assertEquals(2, fake.queries.size());
        assertEquals(key("a", 2), DownloadBatch.choice(token, 1).candidate.key);
    }

    @Test
    public void inFlightSubmissionDisablesEveryCandidateOfThatTrack() throws Exception {
        fake.soulseek = true;
        SoulseekTrack one = track("peer", "Second", 1000, 0, 0, 0);
        SoulseekTrack two = track("peer", "Other", 2000, 0, 0, 0);
        fake.result = soulseek(new AtomicBoolean(true), new AtomicInteger(), one, two);
        expand(1, 2);
        tag("batch-choose:1:soulseek\u0000" + one.id).performClick();
        assertTrue(tag("batch-choose:1:soulseek\u0000" + two.id).isEnabled());
        assertTrue(tag("batch-unselect:1").isEnabled());
        DownloadBatch.choice(token, 1).submitting = true;
        toggle(1);
        toggle(1);
        render();
        assertFalse(
                "clearing waits while the file is being submitted",
                tag("batch-unselect:1").isEnabled());
        assertFalse(tag("batch-choose:1:soulseek\u0000" + one.id).isEnabled());
        assertFalse(tag("batch-choose:1:soulseek\u0000" + two.id).isEnabled());
        assertEquals("soulseek\u0000" + one.id, DownloadBatch.choice(token, 1).candidate.key);
    }

    @Test
    public void bulkSourceButtonsShareOneStripAndLoadingThemKeepsTheCards() throws Exception {
        for (int i = 0; i < 20; i++) fake.source("src" + i);
        page.onStart();
        View card = (View) tag("batch-toggle:0").getParent();
        await(() -> tag("batch-find:src19") != null);
        List<View> finds = tagged("batch-find:");
        assertTrue(finds.size() >= 20);
        View strip = (View) finds.get(0).getParent().getParent();
        assertTrue(strip instanceof HorizontalScrollView);
        for (View find : finds) assertSame(finds.get(0).getParent(), find.getParent());
        assertSame(
                "loading sources must not rebuild cards", card, tag("batch-toggle:0").getParent());
        assertTrue(fake.queries.isEmpty());
    }

    @Test
    public void laterSourceAnswerKeepsFocusFilterScrollSelectionAndSearches() throws Exception {
        fake.source(
                "a",
                song("a", 1, "Second", "Second artist", "FLAC"),
                song("a", 2, "Second", "Second artist", "MP3 320kbps"));
        fake.source("b", song("b", 1, "Second", "Second artist", "WAV"));
        fake.sourceGates.put("b", new CountDownLatch(1));
        toggle(1);
        await(() -> rowCount() == 2 && tag("batch-filter:1:FLAC") != null);
        View card = (View) tag("batch-candidates:1").getParent();
        tag("batch-choose:1:" + key("a", 2)).performClick();
        View chip = tag("batch-filter:1:FLAC");
        chip.setFocusableInTouchMode(true);
        chip.performClick();
        assertTrue(chip.requestFocus());
        HorizontalScrollView chips = (HorizontalScrollView) chip.getParent().getParent();
        chips.scrollTo(0, 0);
        int scroll = chips.getScrollX();
        assertEquals(1, rowCount());
        fake.sourceGates.get("b").countDown();
        await(() -> tag("batch-filter:1:WAV") != null);
        assertSame("same chip view, not a rebuilt one", chip, tag("batch-filter:1:FLAC"));
        assertSame(chips, chip.getParent().getParent());
        assertEquals("batch-filter:1:FLAC", tag("batch-candidates:1").findFocus().getTag());
        assertSame(chip, tag("batch-candidates:1").findFocus());
        assertEquals(scroll, chips.getScrollX());
        assertSame(card, tag("batch-candidates:1").getParent());
        assertEquals(key("a", 2), DownloadBatch.choice(token, 1).candidate.key);
        assertEquals("filter is still FLAC only", 1, rowCount());
        assertNotNull(tag("batch-candidate:" + key("a", 1)));
        int a = 0, b = 0;
        for (String query : fake.queries) {
            if (query.startsWith("a:")) a++;
            if (query.startsWith("b:")) b++;
        }
        assertEquals(1, a);
        assertEquals(1, b);
    }

    @Test
    public void submittedRowsAreImmutableAndLoseTheirControls() throws Exception {
        AtomicInteger transfers = new AtomicInteger();
        fake.soulseek = true;
        SoulseekTrack track = track("peer", "Second", 1000, 0, 0, 0);
        SoulseekTrack other = track("peer", "Other", 2000, 0, 0, 0);
        fake.result = soulseek(new AtomicBoolean(true), transfers, track, other);
        expand(1, 2);
        tag("batch-choose:1:soulseek\u0000" + track.id).performClick();
        assertEquals(1, DownloadBatch.submit(token, false));
        render();
        assertEquals(1, transfers.get());
        assertNull(tag("batch-toggle:1"));
        assertNull(tag("batch-unselect:1"));
        assertNotNull(tag("batch-toggle:0"));
        assertEquals(View.GONE, tag("batch-candidates:1").getVisibility());
        assertEquals(0, rowCount());
        assertTrue(allText(tag("batch-selected:1")).contains("Submitted"));
        DownloadBatch.Candidate replacement = DownloadBatch.choice(token, 1).candidate;
        try {
            DownloadBatch.select(token, 1, replacement);
            fail("submitted row cannot be replaced");
        } catch (IllegalStateException expected) {
        }
        DownloadBatch.clear(token, 1);
        assertNotNull(DownloadBatch.choice(token, 1));
        assertEquals(1, transfers.get());
    }

    @Test
    public void returningAfterSubmissionDoesNotRestartAnInterruptedClosedTrackSearch()
            throws Exception {
        AtomicInteger transfers = new AtomicInteger();
        fake.source("b");
        CountDownLatch blocked = new CountDownLatch(1);
        fake.sourceGates.put("b", blocked);
        fake.soulseek = true;
        SoulseekTrack file = track("peer", "Second", 1000, 301, 24, 96000);
        fake.result = soulseek(new AtomicBoolean(true), transfers, file);
        tag("batch-toggle:1").performClick();
        await(() -> rowCount() == 1 && fake.queries.size() == 2);
        tag("batch-choose:1:soulseek\u0000" + file.id).performClick();
        named(page.view, "Download selected now").get(0).performClick();
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
                .getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .performClick();
        await(() -> tag("batch-toggle:1") == null);
        assertEquals(1, transfers.get());
        int beforeSearches = fake.queries.size();
        page.onStop();
        blocked.countDown();
        page.onStart();
        Field field = BatchDownloadPage.class.getDeclaredField("lookupWork");
        field.setAccessible(true);
        java.util.concurrent.ThreadPoolExecutor pool =
                (java.util.concurrent.ThreadPoolExecutor) field.get(page);
        for (int stage = 0; stage < 2; stage++) {
            await(() -> pool.getActiveCount() == 0 && pool.getQueue().isEmpty());
            shadowOf(Looper.getMainLooper()).idle();
        }
        assertEquals(
                "submitted closed cards do not search again", beforeSearches, fake.queries.size());
        assertEquals(1, transfers.get());
        assertNull(tag("batch-toggle:1"));
    }

    @Test
    public void partialFailureLeavesSuccessfulRowsOutOfRetries() throws Exception {
        AtomicInteger first = new AtomicInteger(), second = new AtomicInteger();
        SoulseekTrack one = track("peer", "First", 1000, 0, 0, 0);
        SoulseekTrack two = track("peer", "Third", 2000, 0, 0, 0);
        DownloadBatch.SoulseekResult ok =
                new DownloadBatch.SoulseekResult(
                        Collections.singletonList(one),
                        "s",
                        () -> true,
                        (app, file, session, now) -> first.incrementAndGet());
        DownloadBatch.SoulseekResult flaky =
                new DownloadBatch.SoulseekResult(
                        Collections.singletonList(two),
                        "s",
                        () -> true,
                        (app, file, session, now) -> {
                            if (second.incrementAndGet() == 1) throw new IOException("retry");
                        });
        DownloadBatch.select(token, 0, DownloadBatch.fromSoulseek(ok, activity).get(0));
        DownloadBatch.select(token, 2, DownloadBatch.fromSoulseek(flaky, activity).get(0));
        assertEquals(1, DownloadBatch.submit(token, false));
        assertTrue(DownloadBatch.choice(token, 0).submitted);
        assertFalse(DownloadBatch.choice(token, 2).submitted);
        assertFalse(DownloadBatch.choice(token, 2).error.isEmpty());
        render();
        assertTrue(allText(tag("batch-selected:2")).contains("Submission failed."));
        assertNotNull(tag("batch-unselect:2"));
        assertEquals(1, DownloadBatch.submit(token, true));
        assertEquals(1, first.get());
        assertEquals(2, second.get());
    }

    @Test
    public void candidateSelectedOutsideTheTileShowsItsSummaryWithoutTransfer() throws Exception {
        AtomicInteger transfers = new AtomicInteger();
        SoulseekTrack track = track("peer", "Second", 1000, 0, 0, 0);
        DownloadBatch.SoulseekResult result = soulseek(new AtomicBoolean(true), transfers, track);
        DownloadBatch.select(token, 1, DownloadBatch.fromSoulseek(result, activity).get(0));
        render();
        assertTrue(allText(tag("batch-selected:1")).contains("Selected audio file · Soulseek"));
        assertEquals(0, transfers.get());
    }

    @Test
    public void legacyChoiceWithoutCandidateStillShowsItsDetail() throws Exception {
        DownloadBatch.select(token, 0, "Addon A · FLAC", now -> {});
        render();
        assertEquals(1, named(page.view, "Addon A · FLAC").size());
        assertNull(DownloadBatch.choice(token, 0).candidate);
    }

    @Test
    public void sourceChoiceKeepsQualityAndReportedDurationWhenReturningToBatch() throws Exception {
        AtomicInteger transfers = new AtomicInteger();
        DownloadExplorerView.Option option =
                new DownloadExplorerView.Option(
                        "Source",
                        "Second",
                        "Second artist",
                        "FLAC · 24-bit",
                        "FLAC · 5:01",
                        true,
                        null,
                        now -> transfers.incrementAndGet(),
                        null);
        DownloadExplorerPage explorer =
                new DownloadExplorerPage(
                        activity,
                        new Intent()
                                .putExtra(SoulseekDownloadChoice.EXTRA_CHOICE, token)
                                .putExtra("download.batch.index", 1));
        Field field = DownloadExplorerPage.class.getDeclaredField("choice");
        field.setAccessible(true);
        field.set(explorer, token);
        Method admit =
                DownloadExplorerPage.class.getDeclaredMethod(
                        "admit", DownloadExplorerView.Option.class, boolean.class);
        admit.setAccessible(true);
        admit.invoke(explorer, option, false);
        assertEquals(
                "Source · Second · FLAC · 24-bit · 5:01", DownloadBatch.choice(token, 1).detail);
        assertNull(DownloadBatch.choice(token, 0));
        assertEquals(0, transfers.get());
        render();
        assertEquals(1, named(page.view, "Source · Second · FLAC · 24-bit · 5:01").size());
        assertEquals(1, named(page.view, "Duration · 5:02").size());
        explorer.onDestroy();
    }

    @Test
    public void peerDurationUsesReportedSecondsAndShowsUnknownRatherThanGuessing()
            throws Exception {
        JSONObject row =
                new JSONObject()
                        .put("peer", "peer")
                        .put("path", "Artist - Song.flac")
                        .put("size", 90000000)
                        .put("duration", 213)
                        .put("bitrate", 999);
        assertTrue(
                DownloadExplorerPage.soulseekDetail(activity, new SoulseekTrack(row))
                        .contains("Duration: 3:33"));
        row.put("duration", 0);
        assertTrue(
                DownloadExplorerPage.soulseekDetail(activity, new SoulseekTrack(row))
                        .contains("Duration: Not reported"));
    }

    @Test
    public void invalidAndZeroReferenceDurationsAreExplicitlyUnknown() {
        for (String value :
                new String[] {null, "", "?", "0", "0:00", "0:00:00", "3:99", "-1", "2147483648"})
            assertEquals("Not reported", DownloadDetails.referenceDuration(value));
        assertEquals("3:34", DownloadDetails.referenceDuration("3:34"));
        assertEquals("1:03:34", DownloadDetails.referenceDuration("1:03:34"));
    }

    /** Opens the busiest card and lays the real tree out at a phone width. */
    void openAndLayOut() throws Exception {
        fake.source(
                "a",
                song("a", 1, "Second", "Second artist", "FLAC 24-bit / 96 kHz"),
                song("a", 2, "Second", "Second artist", ""));
        fake.soulseek = true;
        fake.result =
                soulseek(
                        new AtomicBoolean(true),
                        new AtomicInteger(),
                        track(
                                "a-peer-with-a-very-long-name",
                                "Second",
                                40_000_000L,
                                301,
                                24,
                                96000));
        expand(1, 3);
        tag("batch-choose:1:" + key("a", 1)).performClick();
        View root = page.view;
        float density = activity.getResources().getDisplayMetrics().density;
        int width = Math.round(393 * density), height = Math.round(852 * density);
        root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }

    void assertTouchTargets() {
        int minimum = Math.round(48 * activity.getResources().getDisplayMetrics().density);
        List<View> controls = tagged("batch-");
        int checked = 0;
        for (View control : controls) {
            String name = String.valueOf(control.getTag());
            if (name.startsWith("batch-candidate:")
                    || name.startsWith("batch-candidates:")
                    || name.startsWith("batch-preview:")
                    || name.startsWith("batch-selected:")) continue;
            if (control.getVisibility() != View.VISIBLE) continue;
            checked++;
            assertTrue(name, control.isClickable() || control instanceof android.widget.EditText);
            assertTrue(name + " " + control.getHeight(), control.getHeight() >= minimum);
        }
        assertTrue(checked >= 6);
    }

    @Test
    public void doubleFontScaleAndRightToLeftStillFitTheTouchTargets() throws Exception {
        // BitChord's manifest sets supportsRtl; the test application does not.
        RuntimeEnvironment.getApplication().getApplicationInfo().flags |=
                android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL;
        android.content.res.Configuration config =
                new android.content.res.Configuration(activity.getResources().getConfiguration());
        config.fontScale = 2f;
        activity.getResources()
                .updateConfiguration(config, activity.getResources().getDisplayMetrics());
        Locale.setDefault(new Locale("ar"));
        build();
        openAndLayOut();
        assertTrue(app.bitchord.telegram.PatchStrings.rtl());
        assertEquals(View.LAYOUT_DIRECTION_RTL, page.view.getLayoutDirection());
        assertTouchTargets();
        Button header = (Button) tag("batch-toggle:1");
        assertNotNull(header.getCompoundDrawablesRelative()[2]);
        // In right-to-left the radio leads on the right of the metadata.
        View radio = tag("batch-choose:1:" + key("a", 1));
        ViewGroup meta = (ViewGroup) ((ViewGroup) radio.getParent()).getChildAt(1);
        assertTrue(radio.getLeft() > meta.getLeft());
    }
}

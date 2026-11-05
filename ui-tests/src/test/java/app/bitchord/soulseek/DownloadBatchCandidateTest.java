package app.bitchord.soulseek;

import static org.junit.Assert.*;

import android.app.Activity;

import com.music.bitchord.data.model.Song;

import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

import java.util.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class DownloadBatchCandidateTest {
    Locale before;
    Activity activity;

    @Before
    public void open() {
        before = Locale.getDefault();
        Locale.setDefault(Locale.ENGLISH);
        activity = Robolectric.buildActivity(Activity.class).setup().get();
    }

    @After
    public void close() {
        Locale.setDefault(before);
    }

    static Song song(String source, int n, String quality) {
        return new Song(
                "src:" + source + "::" + n,
                "Title",
                "Artist",
                null,
                "3:14",
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

    static SoulseekTrack track(String name, long size, int seconds, int depth, int rate, int kbps)
            throws Exception {
        return new SoulseekTrack(
                new JSONObject()
                        .put("peer", "peer")
                        .put("path", "Artist - " + name + ".flac")
                        .put("size", size)
                        .put("duration", seconds)
                        .put("bitDepth", depth)
                        .put("sampleRate", rate)
                        .put("bitrate", kbps));
    }

    @Test
    public void numbersComeOnlyFromExplicitUnitBearingTokens() {
        assertEquals(24, DownloadBatch.reportedBitDepth("FLAC 24-bit"));
        assertEquals(24, DownloadBatch.reportedBitDepth("FLAC 24bit / 96 kHz"));
        assertEquals(96000, DownloadBatch.reportedSampleRate("FLAC 24bit / 96 kHz"));
        assertEquals(44100, DownloadBatch.reportedSampleRate("44.1kHz"));
        assertEquals(44100, DownloadBatch.reportedSampleRate("44,1 kHz"));
        assertEquals(320, DownloadBatch.reportedBitrate("MP3 320kbps"));
        for (String vague :
                new String[] {"FLAC", "Hi-Res", "Lossless", "CD quality", "", "24", "96"}) {
            assertEquals(vague, 0, DownloadBatch.reportedBitDepth(vague));
            assertEquals(vague, 0, DownloadBatch.reportedSampleRate(vague));
            assertEquals(vague, 0, DownloadBatch.reportedBitrate(vague));
        }
        assertEquals(0, DownloadBatch.reportedBitDepth("16-bit or 24-bit"));
        assertEquals(0, DownloadBatch.reportedSampleRate("48 kHz or 96 kHz"));
        assertEquals(0, DownloadBatch.reportedBitDepth("2 bits"));
        assertEquals(16, DownloadBatch.reportedBitDepth("16-bit and 16 bit"));
        for (String range :
                new String[] {
                    "up to 24-bit / 192 kHz", "16–24-bit", "44.1–192 kHz", "MP3 ≤320 kbps",
                    "-24-bit", "-96 kHz", "MP3 -320 kbps", "1e6 kHz"
                }) {
            assertEquals(range, 0, DownloadBatch.reportedBitDepth(range));
            assertEquals(range, 0, DownloadBatch.reportedSampleRate(range));
            assertEquals(range, 0, DownloadBatch.reportedBitrate(range));
        }
    }

    @Test
    public void addonCandidateKeepsRawQualityAndNeverInventsSize() {
        List<DownloadBatch.Candidate> found =
                DownloadBatch.fromSource(
                        "a",
                        "Addon A",
                        Arrays.asList(song("a", 1, " FLAC 24-bit / 96 kHz "), song("b", 2, "x")),
                        activity);
        assertEquals(1, found.size());
        DownloadBatch.Candidate c = found.get(0);
        assertEquals("FLAC 24-bit / 96 kHz", c.quality);
        assertEquals("FLAC · 24 bits · 96 kHz", DownloadBatch.spec(c));
        assertEquals(0, c.size);
        assertEquals(24, c.bitDepth);
        assertEquals(96000, c.sampleRate);
        assertEquals(0, c.bitrate);
        String audio = DownloadBatch.audioLine(c);
        assertTrue(audio, audio.contains("Bitrate: Not reported"));
        assertTrue(DownloadBatch.fileLine(activity, c).contains("File size: Not reported"));
        assertTrue(DownloadBatch.fileLine(activity, c).contains("Duration: 3:14"));
    }

    @Test
    public void addonWithoutQualityReportsNothingAndHasNoSpec() {
        DownloadBatch.Candidate c =
                DownloadBatch.fromSource("a", "A", Arrays.asList(song("a", 1, "")), activity)
                        .get(0);
        assertEquals("", DownloadBatch.spec(c));
        String audio = DownloadBatch.audioLine(c);
        assertTrue(audio, audio.contains("Format: Not reported"));
        assertTrue(audio, audio.contains("Bit depth: Not reported"));
        assertTrue(audio, audio.contains("Sample rate: Not reported"));
    }

    @Test
    public void equivalentReportedCodecsShareFiltersWithoutInventingCodecOrQuality()
            throws Exception {
        DownloadBatch.Candidate addon =
                DownloadBatch.fromSource(
                                "a",
                                "A",
                                Arrays.asList(song("a", 1, "FLAC 24-bit / 96 kHz")),
                                activity)
                        .get(0);
        DownloadBatch.SoulseekResult peers =
                new DownloadBatch.SoulseekResult(
                        Arrays.asList(
                                track("One", 1000, 200, 24, 96000, 1000),
                                track("Two", 2000, 200, 24, 96000, 2200)),
                        "session",
                        () -> true,
                        (app, file, session, now) -> {});
        for (DownloadBatch.Candidate peer : DownloadBatch.fromSoulseek(peers, activity))
            assertEquals(DownloadBatch.spec(addon), DownloadBatch.spec(peer));
        assertEquals("FLAC 24-bit / 96 kHz", addon.quality);
        assertEquals(
                "MP3 · 320 kbps",
                DownloadBatch.spec(
                        DownloadBatch.fromSource(
                                        "a",
                                        "A",
                                        Arrays.asList(song("a", 2, "MP3 320kbps")),
                                        activity)
                                .get(0)));
        assertEquals(
                "Lossless",
                DownloadBatch.spec(
                        DownloadBatch.fromSource(
                                        "a", "A", Arrays.asList(song("a", 3, "Lossless")), activity)
                                .get(0)));
    }

    @Test
    public void sourceResultsAreBoundedPerSource() {
        List<Song> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) many.add(song("a", i, "FLAC"));
        assertEquals(
                DownloadBatch.PER_SOURCE,
                DownloadBatch.fromSource("a", "A", many, activity).size());
    }

    @Test
    public void repeatedSourceReferenceKeepsTheFirstOfferBeforeTheCap() {
        List<Song> rows = new ArrayList<>();
        rows.add(song("a", 1, "FLAC"));
        for (int i = 0; i < 14; i++) rows.add(song("a", 1, "MP3 320kbps"));
        for (int i = 2; i <= 30; i++) rows.add(song("a", i, "FLAC"));
        rows.add(song("b", 99, "FLAC"));
        List<DownloadBatch.Candidate> found = DownloadBatch.fromSource("a", "A", rows, activity);
        assertEquals(DownloadBatch.PER_SOURCE, found.size());
        Set<String> keys = new HashSet<>();
        for (DownloadBatch.Candidate c : found) assertTrue(c.key, keys.add(c.key));
        assertEquals("FLAC", found.get(0).quality);
        assertEquals(0, found.get(0).bitrate);
        assertTrue(keys.contains("a\u0000src:a::12"));
        assertFalse(keys.contains("b\u0000src:b::99"));
    }

    @Test
    public void repeatedSoulseekFileKeepsTheFirstOfferBeforeTheCap() throws Exception {
        List<SoulseekTrack> rows = new ArrayList<>();
        rows.add(track("One", 1000, 301, 24, 96000, 0));
        for (int i = 0; i < 25; i++) rows.add(track("One", 1000, 0, 0, 0, 0));
        for (int i = 0; i < 30; i++) rows.add(track("T" + i, 5000 + i, 0, 0, 0, 0));
        DownloadBatch.SoulseekResult result =
                new DownloadBatch.SoulseekResult(rows, "s", () -> true, (a, f, s, n) -> {});
        List<DownloadBatch.Candidate> found = DownloadBatch.fromSoulseek(result, activity);
        assertEquals(DownloadBatch.PER_PEER, found.size());
        Set<String> keys = new HashSet<>();
        for (DownloadBatch.Candidate c : found) assertTrue(c.key, keys.add(c.key));
        assertEquals(301, found.get(0).durationSeconds);
        assertEquals(24, found.get(0).bitDepth);
        assertTrue(keys.contains("soulseek\u0000" + track("T18", 5018, 0, 0, 0, 0).id));
    }

    @Test
    public void soulseekCandidateUsesReportedNumbersAndBytes() throws Exception {
        DownloadBatch.SoulseekResult result =
                new DownloadBatch.SoulseekResult(
                        Arrays.asList(
                                track("One", 50_000_000L, 213, 24, 96000, 0),
                                track("Two", 30_000_000L, 0, 0, 0, 0)),
                        "session",
                        () -> true,
                        (app, file, session, now) -> {});
        List<DownloadBatch.Candidate> found = DownloadBatch.fromSoulseek(result, activity);
        assertEquals(2, found.size());
        DownloadBatch.Candidate first = found.get(0), second = found.get(1);
        assertEquals("FLAC · 24 bits · 96 kHz", DownloadBatch.spec(first));
        assertEquals("FLAC", DownloadBatch.spec(second));
        assertEquals(50_000_000L, first.size);
        assertTrue(DownloadBatch.fileLine(activity, first).contains("Duration: 3:33"));
        assertFalse(DownloadBatch.fileLine(activity, first).contains("File size: Not reported"));
        assertTrue(DownloadBatch.fileLine(activity, second).contains("Duration: Not reported"));
        assertTrue(DownloadBatch.audioLine(second).contains("Bit depth: Not reported"));
        assertNotEquals(first.key, second.key);
    }

    @Test
    public void soulseekResultsAreBoundedAndOversizedFilesAreFlagged() throws Exception {
        List<SoulseekTrack> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) rows.add(track("T" + i, 1000 + i, 0, 0, 0, 0));
        DownloadBatch.SoulseekResult result =
                new DownloadBatch.SoulseekResult(rows, "s", () -> true, (a, f, s, n) -> {});
        assertEquals(DownloadBatch.PER_PEER, DownloadBatch.fromSoulseek(result, activity).size());
        DownloadBatch.Candidate big =
                DownloadBatch.Candidate.soulseek(
                        track("Big", DownloadBatch.FILE_LIMIT + 1, 0, 0, 0, 0), result, activity);
        assertTrue(big.tooLarge());
    }

    @Test
    public void sameOfferNeedsTheSameReferenceFieldsAndSession() throws Exception {
        DownloadBatch.Admit none = (app, file, session, now) -> {};
        DownloadBatch.SoulseekResult first =
                new DownloadBatch.SoulseekResult(
                        Collections.singletonList(track("One", 1000, 301, 24, 96000, 0)),
                        "s1",
                        () -> true,
                        none);
        DownloadBatch.SoulseekResult again =
                new DownloadBatch.SoulseekResult(
                        Collections.singletonList(track("One", 1000, 301, 24, 96000, 0)),
                        "s1",
                        () -> true,
                        none);
        DownloadBatch.SoulseekResult newSession =
                new DownloadBatch.SoulseekResult(
                        Collections.singletonList(track("One", 1000, 301, 24, 96000, 0)),
                        "s2",
                        () -> true,
                        none);
        DownloadBatch.SoulseekResult changed =
                new DownloadBatch.SoulseekResult(
                        Collections.singletonList(track("One", 1000, 600, 16, 44100, 0)),
                        "s1",
                        () -> true,
                        none);
        DownloadBatch.Candidate a = DownloadBatch.fromSoulseek(first, activity).get(0);
        DownloadBatch.Candidate b = DownloadBatch.fromSoulseek(again, activity).get(0);
        assertNotSame(a, b);
        assertTrue(a.sameOffer(b));
        assertFalse(a.sameOffer(DownloadBatch.fromSoulseek(newSession, activity).get(0)));
        assertFalse(a.sameOffer(DownloadBatch.fromSoulseek(changed, activity).get(0)));
        assertFalse(a.sameOffer(null));
        assertEquals("s1", a.session);
        assertNotNull(a.track);
        List<DownloadBatch.Candidate> addons =
                DownloadBatch.fromSource(
                        "a",
                        "A",
                        Arrays.asList(song("a", 1, "FLAC"), song("a", 1, "FLAC")),
                        activity);
        DownloadBatch.Candidate x = addons.get(0);
        DownloadBatch.Candidate y =
                DownloadBatch.fromSource("a", "A", Arrays.asList(song("a", 1, "FLAC")), activity)
                        .get(0);
        assertTrue(x.sameOffer(y));
        assertFalse(
                x.sameOffer(
                        DownloadBatch.fromSource(
                                        "a", "A", Arrays.asList(song("a", 1, "MP3")), activity)
                                .get(0)));
    }

    @Test
    public void soulseekSelectionRefusesAStaleSession() throws Exception {
        boolean[] current = {true};
        int[] transfers = {0};
        DownloadBatch.SoulseekResult result =
                new DownloadBatch.SoulseekResult(
                        Collections.singletonList(track("One", 1000, 0, 0, 0, 0)),
                        "s",
                        () -> current[0],
                        (app, file, session, now) -> transfers[0]++);
        DownloadBatch.Candidate c = DownloadBatch.fromSoulseek(result, activity).get(0);
        c.selection.start(false);
        assertEquals(1, transfers[0]);
        current[0] = false;
        try {
            c.selection.start(false);
            fail("stale session must not transfer");
        } catch (java.io.IOException expected) {
        }
        assertEquals(1, transfers[0]);
    }
}

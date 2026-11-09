package app.bitchord.telegram;

import static org.junit.Assert.*;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class TransitionAnalysisTest {
    TransitionEditorScreenTest.Service service;
    TransitionEditorScreenTest.Analysis measured;
    TransitionRuntime.Pair pair;

    @Before
    public void setup() {
        service = new TransitionEditorScreenTest.Service();
        measured = new TransitionEditorScreenTest.Analysis();
        service.trackAnalyzer.results.put("a", measured);
        SourceAutomix.service(service);
        pair = new TransitionRuntime.Pair("a", "b", "A", "B", 180, 180, 0);
    }

    @After
    public void release() {
        SourceAutomix.release(service);
    }

    @Test
    public void selectedAudioResultsExposeActualStructureEnergyAndConfidence() {
        var result = TransitionAnalysis.read(pair, 0);
        assertTrue(result.ready);
        assertEquals(130, result.bpm, 0);
        assertTrue(result.beatGrid());
        assertEquals(.9, result.beatConfidence, 0);
        assertEquals(.25, result.introStart, 0);
        assertEquals(16, result.introEnd, 0);
        assertEquals(164, result.outroStart, 0);
        assertEquals(180, result.outroEnd, 0);
        assertEquals(3, result.energy.length);
        assertEquals(.8, result.energy[1][1], 0);
        assertTrue(result.reliableKey());
        assertEquals(.7, result.vocalProbability, 0);
    }

    @Test
    public void lowConfidenceDoesNotBecomeASnappingGridOrAConfidentKey() {
        measured.beatConfidence = .2;
        measured.keyConfidence = .2;
        var result = TransitionAnalysis.read(pair, 0);
        assertFalse(result.beatGrid());
        assertFalse(result.reliableKey());
        assertTrue(result.tempo().contains("Low confidence"));
        assertTrue(result.keyLabel().contains("Low confidence"));
        assertNull(TransitionRuntime.musicalKey(measured));
    }

    @Test
    public void defaultZeroWithoutVocalEvidenceDoesNotClaimAnInstrumentalTrack() {
        measured.vocalProbability = 0;
        measured.vocalActivityMask = Collections.emptyList();
        assertTrue(Double.isNaN(TransitionAnalysis.read(pair, 0).vocalProbability));
        measured.vocalActivityMask = Arrays.asList(0d, 0d);
        assertEquals(0, TransitionAnalysis.read(pair, 0).vocalProbability, 0);
    }

    @Test
    public void pendingOrMissingResultsDoNotExposeDefaultNumbers() {
        measured.status = "waiting";
        var result = TransitionAnalysis.read(pair, 0);
        assertFalse(result.ready);
        assertTrue(Double.isNaN(result.bpm));
        assertEquals(0, result.energy.length);
        assertNull(result.key);
        assertFalse(TransitionAnalysis.read(pair, 1).ready);
    }

    @Test
    public void invalidConfidenceAndIntervalsRemainUnavailable() {
        measured.beatConfidence = Double.NaN;
        measured.keyConfidence = Double.POSITIVE_INFINITY;
        measured.firstBeat = -1;
        measured.introEndTime = 0;
        measured.outroStartTime = 200;
        measured.vocalActivityMask = Arrays.asList(.2, Double.NaN);
        var result = TransitionAnalysis.read(pair, 0);
        assertFalse(result.beatGrid());
        assertFalse(result.reliableKey());
        assertNull(TransitionRuntime.musicalKey(measured));
        assertTrue(Double.isNaN(result.introEnd));
        assertTrue(Double.isNaN(result.outroStart));
        assertTrue(Double.isNaN(result.vocalProbability));
    }

    @Test
    public void memoryPhasesSayWhetherAnotherAttemptIsComing() {
        TransitionAnalysis.Snapshot snapshot = new TransitionAnalysis.Snapshot();
        snapshot.phase = "memory";
        assertEquals("Waiting for memory", snapshot.state());
        snapshot.phase = "memory_limit";
        assertEquals("Not enough memory to analyze this track. Tap Retry.", snapshot.state());
        assertEquals(snapshot.state(), snapshot.tempo());
        assertFalse(snapshot.ready);
        assertFalse(snapshot.beatGrid());
        assertFalse(snapshot.reliableKey());
        assertTrue(Double.isNaN(snapshot.bpm));
        assertNull(snapshot.key);
    }

    @Test
    public void waitingBadgesNeverClaimActiveAnalysis() {
        TransitionAnalysis.Snapshot snapshot = new TransitionAnalysis.Snapshot();
        for (String phase : new String[] {"waiting", "memory", "thermal"}) {
            snapshot.phase = phase;
            assertEquals("Waiting", snapshot.badge());
        }
        snapshot.phase = "reading";
        assertEquals("Reading audio…", snapshot.badge());
    }

    @Test
    public void everyNotReadyPhaseNamesItsOwnReason() {
        String[][] expected = {
            {"waiting", "Waiting for playback"},
            {"queued", "Queued"},
            {"reading", "Reading audio…"},
            {"analyzing", "Analyzing selected audio…"},
            {"failed", "Could not read this track's audio."},
            {"ready", "Analysis result could not be read"},
            {"unavailable", "Not available"},
            {"thermal", "Waiting for device to cool down"}
        };
        for (String[] row : expected) {
            TransitionAnalysis.Snapshot snapshot = new TransitionAnalysis.Snapshot();
            snapshot.phase = row[0];
            assertEquals(row[0], Strings.t(row[1]), snapshot.state());
            assertEquals(row[0], snapshot.state(), snapshot.tempo());
        }
    }

    @Test
    public void aNativeResultWithoutAnExtensionJobIsReadFromTheAnalyzer() {
        assertEquals("waiting", SourceAutomix.analysisPhase("a"));
        var result = TransitionAnalysis.read(pair, 0);
        assertTrue(result.ready);
        assertEquals(130, result.bpm, 0);
        assertEquals("F minor", result.key);
    }

    @Test
    public void aReadyJobWhoseResultIsUnreadableIsNotReportedAsMissing() throws Exception {
        measured.status = "waiting";
        java.lang.reflect.Field jobs = SourceAutomix.class.getDeclaredField("jobs");
        jobs.setAccessible(true);
        Map<String, SourceAutomix.Job> state = new java.util.concurrent.ConcurrentHashMap<>();
        SourceAutomix.Job job =
                new SourceAutomix.Job(
                        service,
                        service.trackAnalyzer,
                        "a",
                        android.net.Uri.parse("content://music/1"),
                        180,
                        "key");
        job.phase = "ready";
        state.put("a", job);
        ((Map<Object, Map<String, SourceAutomix.Job>>) jobs.get(null))
                .put(service.trackAnalyzer, state);
        var result = TransitionAnalysis.read(pair, 0);
        assertFalse(result.ready);
        assertEquals(Strings.t("Analysis result could not be read"), result.state());
    }

    @Test
    public void energyUsesTimestampedMeasurementsWithinTheSelectedRecording() {
        measured.energyCurve =
                Arrays.asList(
                        new TransitionEditorScreenTest.Energy(-1, .2),
                        new TransitionEditorScreenTest.Energy(0, .3),
                        new TransitionEditorScreenTest.Energy(90, .6),
                        new TransitionEditorScreenTest.Energy(30, .8),
                        new TransitionEditorScreenTest.Energy(120, Double.NaN),
                        new TransitionEditorScreenTest.Energy(181, .2));
        var result = TransitionAnalysis.read(pair, 0);
        assertEquals(2, result.energy.length);
        assertEquals(90, result.energy[1][0], 0);
    }
}

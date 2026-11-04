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

package app.bitchord.telegram;

import static org.junit.Assert.*;

import org.junit.Test;

/** Numerical regressions for the upstream ports, including the no-rate-matching path. */
public class MixToolsTest {
    @Test
    public void gridMapHandlesTempoChangesDuplicatesAndExtrapolation() {
        MixTools.Grid grid =
                new MixTools.Grid(new double[] {2, .5, 1, 1.5, 2, 2.6, 3.2, Double.NaN});
        for (double beat = -2; beat < 9; beat += .125)
            assertEquals(beat, grid.at(grid.time(beat)), 1e-9);
        assertEquals(3.2, grid.time(5), 1e-9);
        assertEquals(3.8, grid.time(6), 1e-9);
        assertEquals(.5, grid.beatSeconds(1), .1);
        assertFalse(new MixTools.Grid(new double[] {1, 1, Double.NaN}).valid());
    }

    @Test
    public void beatJumpKeepsRelativePhaseInsteadOfSnappingTheCue() {
        MixTools.Grid grid = new MixTools.Grid(.125, 120);
        assertEquals(2.31, grid.jump(.31, 4), 1e-9);
        assertEquals(.31, grid.jump(grid.jump(.31, 4), -4), 1e-9);
        assertEquals(.625, grid.snap(.61), 1e-9);
        assertEquals(.61, new MixTools.Grid(0, 0).snap(.61), 0);
    }

    @Test
    public void unmatchedTemposFitTheIntroAndRetainTheOutgoingPhraseEnd() {
        MixTools.Grid from = new MixTools.Grid(0, 120), to = new MixTools.Grid(0, 96);
        MixTools.Plan plan = MixTools.unmatched(from, to, 180, 0, 10, 8);
        assertNotNull(plan);
        assertEquals(176, plan.end, 1e-9);
        assertEquals(166, plan.start, 1e-9);
        assertEquals(0, plan.cue, 1e-9);
        assertEquals(5, plan.bars);
        assertEquals(10, plan.end - plan.start + plan.cue, 1e-9);
        assertNull(MixTools.unmatched(new MixTools.Grid(0, 0), to, 180, 0, 10, 8));
        assertNull(MixTools.unmatched(from, to, 4, 0, 10, 16));
    }

    @Test
    public void crossfaderTapersPreservePowerMonotonicityAndEndpoints() {
        for (double taper : new double[] {1, 2, 5, 10}) {
            double previous = -1;
            for (int i = 0; i <= 1000; i++) {
                double in = MixTools.crossfader(1, i / 1000d, taper);
                double out = MixTools.crossfader(0, i / 1000d, taper);
                assertEquals(1, in * in + out * out, 1e-9);
                assertTrue(in + 1e-12 >= previous && in >= 0 && in <= 1);
                previous = in;
            }
            assertEquals(0, MixTools.crossfader(1, 0, taper), 0);
            assertEquals(1, MixTools.crossfader(1, 1, taper), 0);
        }
        assertNotEquals(MixTools.crossfader(1, .2, 1), MixTools.crossfader(1, .2, 10), 1e-3);
    }

    @Test
    public void stereoMeterDoesNotCancelOppositePolarityChannelsOrModifyAudio() {
        DeckMeter meter = new DeckMeter();
        meter.begin();
        meter.frame(1, -1);
        meter.frame(0, 0);
        meter.end(2_000_000_000L);
        float[] reading = meter.reading(2_100_000_000L);
        assertEquals(0, reading[0], 0);
        assertEquals(0, reading[1], 0);
        assertEquals(-3.0103, reading[2], 1e-4);
        assertEquals(reading[2], reading[3], 0);
        assertEquals(1, reading[4], 0);
        assertNull(meter.reading(2_600_000_000L));
        assertEquals(-60, DeckMeter.db(0), 0);
    }
}

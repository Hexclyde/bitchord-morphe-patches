package app.bitchord.telegram;

import static org.junit.Assert.*;

import androidx.media3.common.MediaItem;

import kotlin.jvm.functions.Function5;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class TransitionWaveformTest {
    public static final class Bands {
        public float[] low = {.2f, .8f}, mid = {.1f, .6f}, high = {.05f, .4f};
    }

    @Test
    public void selectedTrackAndExactRangeReachTheReaderWithoutChangingSourceStats()
            throws Exception {
        MediaItem selected =
                new MediaItem.Builder()
                        .setMediaId("telegram:channel:45")
                        .setUri("content://audio/45")
                        .build();
        Function5<MediaItem, Long, Double, Double, Integer, Object> reader =
                (item, duration, from, to, bins) -> {
                    assertSame(selected, item);
                    assertEquals(Long.valueOf(180000), duration);
                    assertEquals(43, from, 0);
                    assertEquals(57, to, 0);
                    assertEquals(Integer.valueOf(512), bins);
                    assertTrue(SourceAutomix.quiet());
                    return new Bands();
                };
        float[][] bands = new TransitionRuntime.WaveRequest(reader, selected, 180, 43, 57).read();
        assertArrayEquals(new float[] {.2f, .8f}, bands[0], 0);
        assertFalse(SourceAutomix.quiet());
    }

    @Test
    public void errorsAndMissingAudioNeverBecomeFabricatedWaveforms() throws Exception {
        MediaItem selected = new MediaItem.Builder().setMediaId("file").build();
        Function5<MediaItem, Long, Double, Double, Integer, Object> empty = (a, b, c, d, e) -> null;
        assertNull(new TransitionRuntime.WaveRequest(empty, selected, 60, 0, 10).read());
        Function5<MediaItem, Long, Double, Double, Integer, Object> bad =
                (a, b, c, d, e) -> {
                    throw new IllegalStateException("decode failed");
                };
        assertThrows(
                IllegalStateException.class,
                () -> new TransitionRuntime.WaveRequest(bad, selected, 60, 0, 10).read());
        assertFalse(SourceAutomix.quiet());
        Bands malformed = new Bands();
        malformed.high = new float[] {Float.NaN, .2f};
        Function5<MediaItem, Long, Double, Double, Integer, Object> invalid =
                (a, b, c, d, e) -> malformed;
        assertNull(new TransitionRuntime.WaveRequest(invalid, selected, 60, 0, 10).read());
    }

    @Test
    public void sixteenBarsRetainTheirLengthForSlowerSongsAndRoundTrip() {
        TransitionEdit edit = new TransitionEdit();
        edit.overlap = TransitionEdit.secondsForBars(16, 120);
        edit.bars = 16;
        TransitionRuntime.Pair pair = new TransitionRuntime.Pair("a", "b", "A", "B", 240, 180, 0);
        assertEquals(32, edit.overlap, 0);
        assertTrue(edit.overlap <= pair.maximumOverlap(edit));
        assertEquals(16, edit.copy().bars);
        assertThrows(IllegalArgumentException.class, () -> TransitionEdit.secondsForBars(16, 0));
        assertEquals(.625, TransitionEdit.snap(.61, 120, .125), 1e-9);
    }

    @Test
    public void presetsRenderDifferentAudioMovesWithoutChangingCueOrDuration() {
        java.util.Set<String> shapes = new java.util.HashSet<>();
        for (int preset = 0; preset <= TransitionEdit.SLAM; preset++) {
            TransitionEdit edit = new TransitionEdit();
            edit.incomingCue = 24;
            edit.overlap = 12;
            edit.preset(preset);
            edit.validate();
            assertEquals(24, edit.incomingCue, 0);
            assertEquals(12, edit.overlap, 0);
            assertEquals(1, edit.gain(0, 0), 1e-8);
            assertEquals(0, edit.gain(0, 1), 1e-8);
            assertEquals(0, edit.gain(1, 0), 1e-8);
            assertEquals(1, edit.gain(1, 1), 1e-8);
            shapes.add(edit.encode());
        }
        assertEquals(6, shapes.size());
    }
}

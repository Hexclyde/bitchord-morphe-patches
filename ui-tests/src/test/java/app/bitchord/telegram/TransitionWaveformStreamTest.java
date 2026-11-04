package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.media.MediaCodec;
import android.media.MediaDataSource;
import android.net.Uri;

import com.music.bitchord.playback.PlaybackService;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.nio.*;
import java.util.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class TransitionWaveformStreamTest {
    private final List<Uri> opened = new ArrayList<>();
    private boolean closed;

    @Before
    public void setup() {
        PlaybackService.stopped = false;
        PlaybackService.cached =
                uri -> {
                    opened.add(uri);
                    return new MediaDataSource() {
                        @Override
                        public int readAt(long at, byte[] b, int offset, int length) {
                            return -1;
                        }

                        @Override
                        public long getSize() {
                            return 100;
                        }

                        @Override
                        public void close() {
                            closed = true;
                        }
                    };
                };
    }

    @After
    public void cleanup() {
        PlaybackService.waveDecoder = null;
        PlaybackService.cached = null;
        PlaybackService.stopped = false;
    }

    private static ByteBuffer pcm(int rate, int frames, double hz, boolean inverted) {
        ByteBuffer pcm = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; i++) {
            short sample = (short) Math.round(24000 * Math.sin(2 * Math.PI * hz * i / rate));
            pcm.putShort(sample).putShort(inverted ? (short) -sample : sample);
        }
        pcm.flip();
        return pcm.asReadOnlyBuffer();
    }

    private static MediaCodec.BufferInfo info(ByteBuffer pcm, double start) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        info.set(0, pcm.remaining(), Math.round(start * 1_000_000), 0);
        return info;
    }

    @Test
    public void actualSelectedSourceAndRegionReachStreamingDecoderWithoutMonoCancellation()
            throws Exception {
        Uri selected = Uri.parse("content://library/lossless-master/7");
        PlaybackService.waveDecoder =
                (source, from, to, stop, chunks) -> {
                    assertEquals(12, from, 0);
                    assertEquals(13, to, 0);
                    assertTrue(SourceAutomix.quiet());
                    assertFalse(stop.invoke());
                    ByteBuffer audio = pcm(48000, 48000, 80, true);
                    chunks.invoke(audio, info(audio, 12), 2, 48000);
                    assertEquals(0, audio.position());
                    return new Object();
                };
        TransitionWaveform.Bands result = TransitionWaveform.read(selected, 12, 13, 64);
        assertNotNull(result);
        assertEquals(64, result.low.length);
        assertTrue(result.low[32] > .5);
        assertEquals(Collections.singletonList(selected), opened);
        assertTrue(closed);
        assertFalse(SourceAutomix.quiet());
    }

    @Test
    public void filterBandsSeparateBassAndTrebleAtTheActualDecodedRate() throws Exception {
        TransitionWaveform.Accumulator bass = new TransitionWaveform.Accumulator(0, 1, 64);
        TransitionWaveform.Accumulator treble = new TransitionWaveform.Accumulator(0, 1, 64);
        ByteBuffer low = pcm(48000, 48000, 80, false), high = pcm(48000, 48000, 6000, false);
        bass.accept(low, info(low, 0), 2, 48000);
        treble.accept(high, info(high, 0), 2, 48000);
        assertTrue(bass.complete());
        assertTrue(treble.complete());
        assertTrue(bass.bands.low[40] > .5);
        assertTrue(treble.bands.low[40] < .01);
        assertTrue(treble.bands.high[40] > .5);
        assertTrue(bass.bands.high[40] < .01);
    }

    @Test
    public void timestampsPlaceAudioInItsRealBinsAndIncompleteDecodeIsRejected() throws Exception {
        TransitionWaveform.Accumulator wave = new TransitionWaveform.Accumulator(8, 10, 64);
        ByteBuffer audio = pcm(48000, 48000, 80, true);
        wave.accept(audio, info(audio, 9), 2, 48000);
        assertFalse(wave.complete());
        assertEquals(0, wave.bands.low[12], 0);
        assertTrue(wave.bands.low[45] > .5);
        PlaybackService.waveDecoder =
                (source, from, to, stop, chunks) -> {
                    chunks.invoke(audio, info(audio, 9), 2, 48000);
                    return new Object();
                };
        assertNull(TransitionWaveform.read(Uri.parse("content://audio/incomplete"), 8, 10, 64));
        assertTrue(closed);
    }

    @Test
    public void malformedOrChangingPcmCannotProduceMisleadingWaveforms() throws Exception {
        TransitionWaveform.Accumulator wave = new TransitionWaveform.Accumulator(0, 1, 64);
        ByteBuffer audio = pcm(48000, 48000, 80, false);
        MediaCodec.BufferInfo invalid = info(audio, 0);
        invalid.offset = 1;
        assertThrows(IOException.class, () -> wave.accept(audio, invalid, 2, 48000));
        wave.accept(audio, info(audio, 0), 2, 48000);
        assertThrows(IOException.class, () -> wave.accept(audio, info(audio, 0), 2, 44100));
    }

    @Test
    public void failuresCloseTheSourceReleaseAdmissionAndKeepMissingAudioUnavailable()
            throws Exception {
        PlaybackService.waveDecoder =
                (source, from, to, stop, chunks) -> {
                    throw new IllegalStateException("Decoder unavailable");
                };
        assertThrows(
                IllegalStateException.class,
                () -> TransitionWaveform.read(Uri.parse("content://audio/failure"), 0, 1, 64));
        assertTrue(closed);
        assertFalse(SourceAutomix.quiet());
        PlaybackService.waveDecoder = (source, from, to, stop, chunks) -> null;
        assertNull(TransitionWaveform.read(Uri.parse("content://audio/missing"), 0, 1, 64));
    }
}

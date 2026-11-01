package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.os.Looper;

import androidx.media3.common.C;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessingPipeline;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;

import com.google.common.collect.ImmutableList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Sing's 16-bit path through Media3's real processor chain, with a processor that does what the
 * patched equaliser does: the tap after it has written a block, and the three methods the patch
 * adds.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class SingPipelineTest {
    static final int RATE = 44100;

    static final class Equaliser extends BaseAudioProcessor {
        @Override
        protected AudioFormat onConfigure(AudioFormat input) {
            return input;
        }

        @Override
        public void queueInput(ByteBuffer input) {
            int frames = input.remaining() / 4;
            if (frames == 0) return;
            ByteBuffer output = replaceOutputBuffer(frames * 4);
            output.put(input);
            output.flip();
            SoundExtras.processPcm16(this, output, 2, RATE);
        }

        @Override
        protected void onQueueEndOfStream() {
            super.onQueueEndOfStream();
            SingOutput.equaliserEnd(this);
        }

        @Override
        public ByteBuffer getOutput() {
            ByteBuffer own = super.getOutput();
            return own.hasRemaining() ? own : SingOutput.equaliserOutput(this, own);
        }

        @Override
        public boolean isEnded() {
            return super.isEnded() && !SingOutput.equaliserHolds(this);
        }

        @Override
        protected void onFlush() {
            SoundExtras.flush(this);
        }

        @Override
        protected void onReset() {
            SoundExtras.flush(this);
        }
    }

    Equaliser equaliser;
    AudioProcessingPipeline pipeline;

    @Before
    public void start() throws Exception {
        SoundExtras.singEnabled = true;
        SoundExtras.singLevel = 100;
        SoundExtras.singMethod = SeparationModel.METHOD;
        SeparationModel.forTest(input -> new float[input.length], true);
        equaliser = new Equaliser();
        pipeline = new AudioProcessingPipeline(ImmutableList.of(equaliser));
        pipeline.configure(new AudioProcessor.AudioFormat(RATE, 2, C.ENCODING_PCM_16BIT));
        pipeline.flush();
    }

    @After
    public void stop() {
        SoundExtras.singEnabled = false;
        SoundExtras.singMethod = VocalCut.VOICE_GATE;
        SeparationModel.forTest(null, false);
    }

    static ByteBuffer pcm(int frames, double hz) {
        ByteBuffer bytes = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder());
        for (int i = 0; i < frames; i++) {
            short value = (short) (12000 * Math.sin(2 * Math.PI * hz * i / RATE));
            bytes.putShort(value);
            bytes.putShort(value);
        }
        bytes.flip();
        return bytes;
    }

    /** What the real sink does: feeds what's accepted, takes output in writes of limited size. */
    long play(ByteBuffer input, int writeBytes) {
        long out = 0;
        int spins = 0;
        while (input.hasRemaining() || !pipeline.isEnded()) {
            if (!input.hasRemaining()) pipeline.queueEndOfStream();
            pipeline.queueInput(input);
            ByteBuffer output = pipeline.getOutput();
            int take = Math.min(writeBytes, output.remaining());
            output.position(output.position() + take);
            out += take;
            if (++spins > 1_000_000) throw new AssertionError("The chain never finished");
        }
        return out;
    }

    @Test
    public void everyFrameInComesOutAndNothingElse() {
        assertEquals(RATE * 4 * 3L, play(pcm(RATE * 3, 440), 4096));
        assertTrue(pipeline.isEnded());
        assertFalse(equaliser.getOutput().hasRemaining());
    }

    @Test
    public void aStreamShorterThanTheDelayStillAllComesOut() {
        assertEquals(1000 * 4L, play(pcm(1000, 440), 256));
        assertTrue(pipeline.isEnded());
    }

    @Test
    public void theFirstFrameOutIsTheFirstFrameIn() {
        ByteBuffer input = pcm(RATE * 3, 440);
        input.putShort(0, (short) 9000);
        input.putShort(2, (short) 9000);
        ByteBuffer first = null;
        int spins = 0;
        while (first == null && spins++ < 100_000) {
            if (!input.hasRemaining()) pipeline.queueEndOfStream();
            pipeline.queueInput(input);
            ByteBuffer output = pipeline.getOutput();
            if (output.hasRemaining()) first = output;
        }
        assertNotNull(first);
        // The filter runs until the first estimate, so it's near the first frame's level, not zero.
        assertNotEquals(0, first.order(ByteOrder.nativeOrder()).getShort(first.position()));
    }

    @Test
    public void aFlushDropsTheTailAndStartsAgain() {
        pipeline.queueInput(pcm(RATE * 3, 440));
        pipeline.getOutput();
        pipeline.flush();
        assertFalse(SingOutput.equaliserHolds(equaliser));
        assertEquals(RATE * 4L, play(pcm(RATE, 880), 4096));
    }

    @Test
    public void levelZeroPassesBitForBit() {
        SoundExtras.singLevel = 0;
        ByteBuffer input = pcm(RATE, 440);
        ByteBuffer expected = input.duplicate().order(ByteOrder.nativeOrder());
        pipeline.queueInput(input);
        ByteBuffer output = pipeline.getOutput().order(ByteOrder.nativeOrder());
        assertEquals(expected.remaining(), output.remaining());
        while (expected.hasRemaining()) assertEquals(expected.getShort(), output.getShort());
    }

    /**
     * A player's speed changing, from any player, leaves every stream's delay exactly as it was.
     */
    @Test
    public void playerCallbacksDoNotReachTheEngines() throws Exception {
        pipeline.queueInput(pcm(RATE * 2, 440));
        ByteBuffer first = pipeline.getOutput();
        first.position(first.limit());
        SeparatedSing engine = SeparationModel.engine(equaliser);
        assertNotNull(engine);
        long held = engine.inputFrames();
        Player player =
                (Player)
                        java.lang.reflect.Proxy.newProxyInstance(
                                Player.class.getClassLoader(),
                                new Class<?>[] {Player.class},
                                (proxy, method, args) ->
                                        method.getName().equals("getApplicationLooper")
                                                ? Looper.getMainLooper()
                                                : null);
        Class<?> type = Class.forName("app.bitchord.telegram.SoundExtras$Watcher");
        java.lang.reflect.Constructor<?> make = type.getDeclaredConstructor(Player.class);
        make.setAccessible(true);
        Player.Listener watcher = (Player.Listener) make.newInstance(player);
        for (float speed : new float[] {1.2f, 1f, 1.5f, 1f})
            watcher.onPlaybackParametersChanged(new PlaybackParameters(speed));
        String status = SeparationModel.status();
        ByteBuffer more = pcm(4096, 440);
        pipeline.queueInput(more);
        ByteBuffer output = pipeline.getOutput();
        assertEquals(held + 4096, engine.inputFrames());
        assertEquals("the delay kept going, so this block plays", 4096 * 4, output.remaining());
        assertSame(engine, SeparationModel.engine(equaliser));
        assertFalse(status.startsWith("Filter fallback"));
    }

    @Test
    public void filterMethodsHaveNoDelay() {
        SoundExtras.singMethod = VocalCut.VOICE_GATE;
        SeparationModel.forTest(null, false);
        pipeline.queueInput(pcm(4096, 440));
        assertEquals(4096 * 4, pipeline.getOutput().remaining());
    }
}

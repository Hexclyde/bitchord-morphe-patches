package app.bitchord.telegram;

import static org.junit.Assert.*;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class TransitionEditTest {
    static final class PcmProcessor {
        public int sampleRate = 48000, channelCount = 2;
    }

    @Test
    public void independentDeckVolumesAndCurvesPersistWithoutChangingLegacyDefaults()
            throws Exception {
        TransitionEdit edit = new TransitionEdit();
        edit.volume[0] = .25;
        edit.volume[1] = .8;
        edit.deckCurve[0] = TransitionEdit.LINEAR;
        edit.deckCurve[1] = TransitionEdit.SMOOTH;
        TransitionEdit restored = edit.copy();
        assertEquals(.1875, restored.gain(0, .25), 1e-9);
        assertEquals(.125, restored.gain(1, .25), 1e-9);
        org.json.JSONObject legacy = new org.json.JSONObject(edit.encode());
        legacy.remove("volume");
        legacy.remove("deckCurve");
        TransitionEdit old = TransitionEdit.decode(legacy.toString());
        assertEquals(1, old.gain(0, 0), 0);
        assertEquals(1, old.gain(1, 1), 0);
        restored.volume[0] = Double.NaN;
        assertThrows(IllegalArgumentException.class, restored::validate);
    }

    @Test
    public void pcmEqProcessesMutableOutputFromReadOnlyCodecInputWithoutMovingItsCursor()
            throws Exception {
        TransitionRuntime.release();
        PcmProcessor processor = new PcmProcessor();
        java.lang.reflect.Field assignment = TransitionRuntime.class.getDeclaredField("assigning");
        assignment.setAccessible(true);
        @SuppressWarnings("unchecked")
        ThreadLocal<double[]> pending = (ThreadLocal<double[]>) assignment.get(null);
        pending.set(new double[] {-6, -6, -6});
        TransitionRuntime.assign(processor);
        pending.remove();
        java.nio.ByteBuffer input =
                java.nio.ByteBuffer.allocateDirect(8).order(java.nio.ByteOrder.nativeOrder());
        input.putShort((short) 10000)
                .putShort((short) -10000)
                .putShort((short) 20000)
                .putShort((short) -20000)
                .flip();
        java.nio.ByteBuffer codec = input.asReadOnlyBuffer(),
                output =
                        java.nio.ByteBuffer.allocateDirect(8)
                                .order(java.nio.ByteOrder.nativeOrder());
        output.put(codec).flip();
        TransitionRuntime.pcm16(processor, output);
        assertEquals(8, codec.position());
        assertEquals(0, output.position());
        assertEquals(8, output.limit());
        assertEquals(5012, output.getShort(0));
        assertEquals(-5012, output.getShort(2));
        TransitionRuntime.release();
    }

    @Test
    public void highPassSweepReachesTheExactEditedCutoff() {
        TransitionEdit edit = new TransitionEdit();
        edit.filter[0] = 2;
        edit.cutoff[0] = 300;
        assertEquals(0, edit.cutoffs(0, 0)[1], 1e-4);
        assertEquals(300, edit.cutoffs(0, 1)[1], 1e-3);
        edit.times[0][1] = TransitionEdit.MIN_GAP;
        edit.times[0][2] = 2 * TransitionEdit.MIN_GAP;
        edit.validate();
    }

    @Test
    public void flatEqMetersBothChannelsWithoutChangingPcmOrTheBufferCursor() throws Exception {
        TransitionRuntime.release();
        PcmProcessor processor = new PcmProcessor();
        java.lang.reflect.Field a = TransitionRuntime.class.getDeclaredField("assigning"),
                s = TransitionRuntime.class.getDeclaredField("assigningSide");
        a.setAccessible(true);
        s.setAccessible(true);
        ThreadLocal<double[]> gains = (ThreadLocal<double[]>) a.get(null);
        ThreadLocal<Integer> side = (ThreadLocal<Integer>) s.get(null);
        gains.set(new double[] {0, 0, 0});
        side.set(1);
        TransitionRuntime.assign(processor);
        gains.remove();
        side.remove();
        java.nio.ByteBuffer audio =
                java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.nativeOrder());
        audio.putShort((short) 16384)
                .putShort((short) -32768)
                .putShort((short) 0)
                .putShort((short) 0)
                .flip();
        byte[] original = audio.array().clone();
        TransitionRuntime.pcm16(processor, audio);
        assertArrayEquals(original, audio.array());
        assertEquals(0, audio.position());
        assertEquals(8, audio.limit());
        float[] meter = TransitionRuntime.meter(1);
        assertNotNull(meter);
        assertEquals(-6.0206, meter[0], 1e-4);
        assertEquals(0, meter[1], 0);
        assertEquals(1, meter[4], 0);
        TransitionRuntime.release();
    }

    @Test
    public void unrelatedVersionSwapsCannotInheritOrClearATrackPairEdit() throws Exception {
        Object owner = new Object(), other = new Object(), plan = new Object();
        TransitionRuntime.bind(owner);
        java.lang.reflect.Field prepared = TransitionRuntime.class.getDeclaredField("prepared"),
                identity = TransitionRuntime.class.getDeclaredField("preparedPlan");
        prepared.setAccessible(true);
        identity.setAccessible(true);
        prepared.set(null, new TransitionEdit());
        identity.set(null, plan);
        TransitionRuntime.arm(owner, plan);
        assertNotNull(TransitionRuntime.incoming(owner, .5f));
        assertNull(TransitionRuntime.incoming(other, .5f));
        TransitionRuntime.arm(other, null);
        TransitionRuntime.finish(other);
        assertNotNull(TransitionRuntime.incoming(owner, .5f));
        TransitionRuntime.finish(owner);
        assertNull(TransitionRuntime.incoming(owner, .5f));
        TransitionRuntime.release();
    }

    @Test
    public void allPresetsHaveCorrectEndpointsAndEqualPowerPreservesEnergy() {
        TransitionEdit edit = new TransitionEdit();
        for (int curve = 0; curve < 3; curve++) {
            edit.curve = curve;
            assertEquals(1, edit.gain(0, 0), 1e-6);
            assertEquals(0, edit.gain(1, 0), 1e-6);
            assertEquals(0, edit.gain(0, 1), 1e-6);
            assertEquals(1, edit.gain(1, 1), 1e-6);
        }
        edit.curve = TransitionEdit.POWER;
        for (int i = 0; i <= 100; i++) {
            double p = i / 100d;
            assertEquals(1, Math.pow(edit.gain(0, p), 2) + Math.pow(edit.gain(1, p), 2), 1e-8);
        }
    }

    @Test
    public void customPointsInterpolateAndRoundTripWithoutAliasing() {
        TransitionEdit edit = new TransitionEdit();
        edit.curve = TransitionEdit.CUSTOM;
        edit.times[1][1] = .2;
        edit.levels[1][1] = .8;
        edit.eq[0][0] = -6;
        edit.incomingCue = 23.5;
        TransitionEdit copy = edit.copy();
        assertEquals(.8, copy.gain(1, .2), 1e-9);
        assertEquals(.4, copy.gain(1, .1), 1e-9);
        assertEquals(23.5, copy.incomingCue, 0);
        copy.levels[1][1] = 0;
        assertEquals(.8, edit.levels[1][1], 0);
    }

    @Test
    public void invalidNumericAndCrossedPointsCannotBeSaved() {
        TransitionEdit edit = new TransitionEdit();
        edit.overlap = Double.NaN;
        assertThrows(IllegalArgumentException.class, edit::encode);
        edit.overlap = 8;
        edit.times[1][1] = .9;
        assertThrows(IllegalArgumentException.class, edit::validate);
        assertThrows(
                IllegalArgumentException.class, () -> TransitionEdit.decode("{\"version\":99}"));
    }

    @Test
    public void incomingAndOutgoingFiltersOpenOnOppositeEnds() {
        TransitionEdit edit = new TransitionEdit();
        edit.filter[0] = 1;
        edit.filter[1] = 1;
        edit.cutoff[0] = edit.cutoff[1] = 400;
        assertEquals(20000, edit.cutoffs(0, 0)[0], .1);
        assertEquals(400, edit.cutoffs(0, 1)[0], .1);
        assertEquals(400, edit.cutoffs(1, 0)[0], .1);
        assertEquals(20000, edit.cutoffs(1, 1)[0], .1);
    }

    @Test
    public void flatEqPreservesStereoSignalAndDeckStatesAreIndependent() {
        float[] audio = new float[2000];
        Random random = new Random(4);
        for (int i = 0; i < audio.length; i++) audio[i] = random.nextFloat() * 2 - 1;
        float[] original = audio.clone();
        new TransitionEdit.Equalizer(48000, 2, new double[] {0, 0, 0}).process(audio, 1000, 2);
        assertArrayEquals(original, audio, 1e-6f);
        TransitionEdit.Equalizer cut =
                new TransitionEdit.Equalizer(48000, 2, new double[] {-6, -6, -6});
        float sample = cut.sample(.5f, 0);
        assertEquals(.5 * Math.pow(10, -6 / 20d), sample, 1e-6);
        assertEquals(0, cut.sample(0, 1), 0);
    }

    @Test
    public void directedPairKeysDoNotCollideOrLoseDirection() {
        assertNotEquals(TransitionRuntime.key("ab", "c"), TransitionRuntime.key("a", "bc"));
        assertNotEquals(TransitionRuntime.key("a", "b"), TransitionRuntime.key("b", "a"));
        TransitionEdit edit = new TransitionEdit();
        edit.incomingCue = 30;
        TransitionRuntime.Pair pair = new TransitionRuntime.Pair("a", "b", "A", "B", 100, 60, 0);
        assertEquals(10, pair.maximumOverlap(edit), 0);
    }
}

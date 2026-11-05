package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.os.Looper;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, shadows = TransitionEditorScreenTest.Installed.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class TransitionTempoPlanTest {
    private final TransitionEditorScreenTest listening = new TransitionEditorScreenTest();

    @Before
    public void setup() throws Exception {
        listening.setup();
    }

    @After
    public void close() throws Exception {
        listening.close();
    }

    private void apply(TransitionEdit edit, boolean automatic) throws Exception {
        TransitionRuntime.Pair pair = TransitionRuntime.pair();
        TransitionStore.get().commit(pair, edit, automatic, error -> assertNull(error));
        TransitionStore.get().awaitIdle();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void planAndHandoffUseEachDeckSourceSpanWithoutChangingTheListener() throws Exception {
        TransitionEdit edit = new TransitionEdit();
        edit.overlapRate[0] = 1.25;
        edit.overlapRate[1] = .8;
        edit.tempoExplicit = true;
        edit.incomingCue = 3;
        apply(edit, false);
        TransitionEditorScreenTest.Plan plan = new TransitionEditorScreenTest.Plan();
        SourceAutomix.plan(plan);
        assertEquals(170, plan.transitionStart, 0);
        assertEquals(180, plan.transitionEnd, 0);
        assertEquals(10, plan.fadeSeconds, 0);
        assertEquals(6.2, plan.incomingHandoffTime, .000001);
        assertEquals(1.25, plan.outgoingPlaybackRate, 0);
        assertEquals(.8, plan.incomingPlaybackRate, 0);
        assertFalse(plan.phaseLock);
        assertEquals(
                1.25f,
                ((androidx.media3.common.Player) TransitionRuntime.player())
                        .getPlaybackParameters()
                        .speed,
                0);
    }

    @Test
    public void automaticTempoUsesFreshTimingAndLeavesNativeFadersAndFiltersInControl()
            throws Exception {
        TransitionEdit edit = new TransitionEdit();
        edit.tempoExplicit = true;
        edit.overlap = 15;
        edit.outgoingTrim = 12;
        edit.incomingCue = 9;
        edit.overlapRate[0] = 1.25;
        edit.overlapRate[1] = .8;
        apply(edit, true);
        TransitionEditorScreenTest.Plan plan = new TransitionEditorScreenTest.Plan();
        plan.filterSweep = .4;
        plan.bassSwap = true;
        plan.reason = "Native transition";
        double nativeWall = plan.fadeSeconds / plan.outgoingPlaybackRate;
        SourceAutomix.plan(plan);
        assertEquals(180 - nativeWall * 1.25, plan.transitionStart, 1e-9);
        assertEquals(180, plan.transitionEnd, 0);
        assertEquals(0, plan.incomingCueTime, 0);
        assertEquals(nativeWall * .8 * .5, plan.incomingHandoffTime, .000001);
        assertEquals(.4, plan.filterSweep, 0);
        assertTrue(plan.bassSwap);
        assertEquals("Native transition", plan.reason);
        java.lang.reflect.Field field =
                TransitionEditorScreenTest.class.getDeclaredField("service");
        field.setAccessible(true);
        Object owner = ((TransitionEditorScreenTest.Service) field.get(listening)).crossfade;
        TransitionRuntime.bind(owner);
        TransitionRuntime.arm(owner, plan);
        assertNull(TransitionRuntime.incoming(owner, .5f));
        assertNull(TransitionRuntime.outgoing(owner, .5f));
        assertFalse(TransitionRuntime.filters(owner, .5f, .5f));
    }

    private Object owner() throws Exception {
        java.lang.reflect.Field field =
                TransitionEditorScreenTest.class.getDeclaredField("service");
        field.setAccessible(true);
        return ((TransitionEditorScreenTest.Service) field.get(listening)).crossfade;
    }

    private static TransitionEditorScreenTest.Plan nativePlan(double incoming, double outgoing) {
        TransitionEditorScreenTest.Plan plan = new TransitionEditorScreenTest.Plan();
        plan.incomingPlaybackRate = incoming;
        plan.outgoingPlaybackRate = outgoing;
        plan.phaseLock = true;
        return plan;
    }

    @Test
    public void manualCustomProfileFollowsTheFreshNativeRatesAndKeepsItsOwnShape()
            throws Exception {
        TransitionEdit edit = new TransitionEdit();
        edit.overlap = 10;
        edit.outgoingTrim = 4;
        edit.incomingCue = 3;
        edit.curve = TransitionEdit.CUSTOM;
        edit.volume[1] = .6;
        edit.eq[1][0] = -3;
        edit.overlapRate[0] = 1.1;
        edit.overlapRate[1] = .9;
        apply(edit, false);
        TransitionRuntime.Pair pair = TransitionRuntime.pair();
        TransitionEdit stored = TransitionRuntime.load(pair);
        assertFalse(stored.tempoExplicit);
        assertEquals(1.1, stored.overlapRate[0], 0);
        TransitionEditorScreenTest.Plan plan = nativePlan(1.04, 1);
        SourceAutomix.plan(plan);
        assertEquals(1, plan.outgoingPlaybackRate, 0);
        assertEquals(1.04, plan.incomingPlaybackRate, 0);
        assertTrue(plan.phaseLock);
        assertEquals(166, plan.transitionStart, 1e-9);
        assertEquals(176, plan.transitionEnd, 1e-9);
        assertEquals(10, plan.fadeSeconds, 1e-9);
        assertEquals(3, plan.incomingCueTime, 1e-9);
        assertEquals(3 + 10 * 1.04 * .5, plan.incomingHandoffTime, 1e-9);
        assertEquals("Custom transition", plan.reason);
        Object owner = owner();
        TransitionRuntime.bind(owner);
        TransitionRuntime.arm(owner, plan);
        assertEquals(stored.gain(1, .2), TransitionRuntime.incoming(owner, .2f), 1e-6);
        assertEquals(stored.gain(0, .2), TransitionRuntime.outgoing(owner, .2f), 1e-6);
        assertEquals(.6, stored.volume[1], 0);
        assertEquals(-3, stored.eq[1][0], 0);
        assertEquals(TransitionEdit.CUSTOM, stored.curve);
    }

    @Test
    public void explicitUnityOverridesTheNativeRatesAndTheirPhaseServo() throws Exception {
        TransitionEdit edit = new TransitionEdit();
        edit.tempoExplicit = true;
        edit.incomingCue = 3;
        apply(edit, false);
        TransitionEditorScreenTest.Plan plan = nativePlan(1.04, 1.04);
        SourceAutomix.plan(plan);
        assertEquals(1, plan.outgoingPlaybackRate, 0);
        assertEquals(1, plan.incomingPlaybackRate, 0);
        assertFalse(plan.phaseLock);
    }

    @Test
    public void anUntouchedAutoPlanKeepsTheNativeRatesAndPhaseServo() throws Exception {
        TransitionEditorScreenTest.Plan plan = nativePlan(1.04, 1);
        SourceAutomix.plan(plan);
        assertEquals(1.04, plan.incomingPlaybackRate, 0);
        assertEquals(1, plan.outgoingPlaybackRate, 0);
        assertTrue(plan.phaseLock);
        assertNull(plan.reason);
    }
}

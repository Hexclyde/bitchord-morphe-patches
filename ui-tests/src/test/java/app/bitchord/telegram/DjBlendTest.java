package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, shadows = DjBlendTest.Installed.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class DjBlendTest {
    @Implements(value = SoundExtras.class, isInAndroidSdk = false)
    public static class Installed {
        @Implementation
        public static boolean djApplied() {
            return true;
        }
    }

    public enum Style {
        EQUAL_POWER
    }

    /** The native plan's field names, which the runtime reads and writes by reflection. */
    public static final class Plan {
        public boolean blocked, shouldStart, markerVisible, bassSwap;
        public double fadeSeconds = 8,
                transitionEnd = 180,
                transitionStart = 172,
                incomingCueTime = 4,
                incomingHandoffTime = 8,
                filterSweep,
                vocalOverlap,
                echoSeconds;
        public String reason = "Smart";
        public Style transitionStyle = Style.EQUAL_POWER;
    }

    public static final class Control {
        public boolean isTransitioning() {
            return false;
        }
    }

    public static final class Service {
        public Object player;
        public Control crossfade = new Control();
    }

    private final MediaItem[] items = {
        new MediaItem.Builder().setMediaId("dj:out").setUri("content://audio/1").build(),
        new MediaItem.Builder().setMediaId("dj:in").setUri("content://audio/2").build()
    };
    private long position = 100_000;
    private Service service;

    private static void know(String id, String key, double bpm) throws Exception {
        Field field = DjMode.class.getDeclaredField("known");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, DjMode.Info> known = (Map<String, DjMode.Info>) field.get(null);
        known.put(id, new DjMode.Info(key, bpm, "test"));
    }

    private static TransitionRuntime.Pair pair() {
        return new TransitionRuntime.Pair("dj:out", "dj:in", "", "", 180, 180, 0);
    }

    @Before
    public void setup() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        Field appField = SoundExtras.class.getDeclaredField("app");
        appField.setAccessible(true);
        appField.set(null, app);
        TransitionStore.closeForTest();
        app.deleteDatabase(TransitionStore.DATABASE);
        service = new Service();
        service.player =
                Proxy.newProxyInstance(
                        Player.class.getClassLoader(),
                        new Class<?>[] {Player.class},
                        (proxy, method, args) -> {
                            switch (method.getName()) {
                                case "getCurrentMediaItemIndex":
                                    return 0;
                                case "getNextMediaItemIndex":
                                    return 1;
                                case "getCurrentMediaItem":
                                    return items[0];
                                case "getMediaItemAt":
                                    return items[(Integer) args[0]];
                                case "getMediaItemCount":
                                    return 2;
                                case "getDuration":
                                    return 180_000L;
                                case "getCurrentPosition":
                                    return position;
                                case "equals":
                                    return proxy == args[0];
                                case "hashCode":
                                    return System.identityHashCode(proxy);
                            }
                            if (method.getReturnType() == boolean.class) return false;
                            if (method.getReturnType() == int.class) return 0;
                            if (method.getReturnType() == long.class) return 0L;
                            if (method.getReturnType() == float.class) return 0f;
                            return null;
                        });
        SourceAutomix.service(service);
        Field known = DjMode.class.getDeclaredField("known");
        known.setAccessible(true);
        ((Map<?, ?>) known.get(null)).clear();
        know("dj:out", "8A", 120);
        know("dj:in", "9A", 122);
        DjMode.setEnabled(true);
    }

    @After
    public void close() throws Exception {
        DjMode.setEnabled(false);
        TransitionRuntime.release();
        SourceAutomix.release(service);
        TransitionStore.closeForTest();
    }

    @Test
    public void compatiblePairGetsALongBlendWithMatchingTimes() throws Exception {
        Plan plan = new Plan();
        double span = TransitionRuntime.djBlend(plan, pair(), service.player, 8);
        assertEquals(16, span, 1e-9);
        assertEquals(16, plan.fadeSeconds, 1e-9);
        assertEquals(164, plan.transitionStart, 1e-9);
        assertEquals(180, plan.transitionEnd, 1e-9);
        assertEquals(4, plan.incomingCueTime, 1e-9);
        assertEquals(12, plan.incomingHandoffTime, 1e-9);
        assertFalse("100 s is before the new start", plan.shouldStart);
        position = 165_000;
        Plan late = new Plan();
        TransitionRuntime.djBlend(late, pair(), service.player, 8);
        assertTrue("165 s is after the new start", late.shouldStart);
    }

    @Test
    public void longerBlendPreservesTheNativeHandoffFraction() throws Exception {
        for (double fraction : new double[] {0, .25, .5, 1}) {
            Plan plan = new Plan();
            plan.incomingHandoffTime = 4 + 8 * fraction;
            assertEquals(16, TransitionRuntime.djBlend(plan, pair(), service.player, 8), 1e-9);
            assertEquals(4 + 16 * fraction, plan.incomingHandoffTime, 1e-9);
        }
    }

    @Test
    public void anUnusableHandoffLeavesTheNativePlanUnchanged() throws Exception {
        Plan plan = new Plan();
        plan.incomingHandoffTime = Double.NaN;
        assertEquals(8, TransitionRuntime.djBlend(plan, pair(), service.player, 8), 0);
        assertEquals(8, plan.fadeSeconds, 0);
        assertEquals(172, plan.transitionStart, 0);
    }

    @Test
    public void blockedIncompatibleOrUnknownPairsAreUntouched() throws Exception {
        Plan blocked = new Plan();
        blocked.blocked = true;
        assertEquals(8, TransitionRuntime.djBlend(blocked, pair(), service.player, 8), 0);
        assertEquals(8, blocked.fadeSeconds, 0);

        know("dj:in", "2B", 122);
        Plan clash = new Plan();
        assertEquals(8, TransitionRuntime.djBlend(clash, pair(), service.player, 8), 0);
        assertEquals(172, clash.transitionStart, 0);

        know("dj:in", "9A", 180);
        Plan tempo = new Plan();
        assertEquals(8, TransitionRuntime.djBlend(tempo, pair(), service.player, 8), 0);
        assertEquals(8, tempo.fadeSeconds, 0);

        Plan unknown = new Plan();
        TransitionRuntime.Pair stranger =
                new TransitionRuntime.Pair("dj:out", "dj:none", "", "", 180, 180, 0);
        assertEquals(8, TransitionRuntime.djBlend(unknown, stranger, service.player, 8), 0);
    }

    @Test
    public void blendNeverOutrunsTheAvailableAudio() throws Exception {
        TransitionRuntime.Pair shortIncoming =
                new TransitionRuntime.Pair("dj:out", "dj:in", "", "", 180, 30, 0);
        Plan plan = new Plan();
        double span = TransitionRuntime.djBlend(plan, shortIncoming, service.player, 8);
        assertEquals((30 - 4) / 3d, span, 1e-9);
        assertTrue(plan.incomingCueTime + 3 * span <= 30 + 1e-9);

        TransitionRuntime.Pair tiny =
                new TransitionRuntime.Pair("dj:out", "dj:in", "", "", 180, 20, 0);
        Plan none = new Plan();
        assertEquals(8, TransitionRuntime.djBlend(none, tiny, service.player, 8), 0);
        assertEquals(172, none.transitionStart, 0);

        Plan early = new Plan();
        early.transitionEnd = 10;
        assertEquals(8, TransitionRuntime.djBlend(early, pair(), service.player, 8), 0);
    }

    @Test
    public void hookEntryBlendsTheNextSongOfTheSessionPlayer() throws Exception {
        DjMode.service(service);
        Plan plan = new Plan();
        DjMode.plan(plan);
        assertEquals(16, plan.fadeSeconds, 1e-9);
        DjMode.setEnabled(false);
        Plan off = new Plan();
        DjMode.plan(off);
        assertEquals(8, off.fadeSeconds, 0);
        DjMode.service(null);
    }

    @Test
    public void savedTransitionBeatsTheDjBlend() throws Exception {
        TransitionRuntime.bind(service);
        TransitionEdit edit = new TransitionEdit();
        edit.overlap = 6;
        edit.outgoingTrim = 2;
        edit.incomingCue = 3;
        TransitionRuntime.Pair pair = TransitionRuntime.pair();
        TransitionRuntime.save(pair, edit);
        TransitionStore.get().awaitIdle();
        TransitionStore.get().lookup(pair);
        TransitionStore.get().awaitIdle();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

        Plan plan = new Plan();
        TransitionRuntime.apply(plan);
        assertEquals("Custom transition", plan.reason);
        assertEquals(6, plan.fadeSeconds, 1e-9);
        assertEquals(172, plan.transitionStart, 1e-9);
        assertEquals(178, plan.transitionEnd, 1e-9);
        assertEquals(3, plan.incomingCueTime, 1e-9);
        assertEquals(6, plan.incomingHandoffTime, 1e-9);

        TransitionRuntime.remove(pair);
        TransitionStore.get().awaitIdle();
        TransitionStore.get().lookup(pair);
        TransitionStore.get().awaitIdle();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        Plan automatic = new Plan();
        TransitionRuntime.apply(automatic);
        assertEquals("Smart", automatic.reason);
        assertEquals(16, automatic.fadeSeconds, 1e-9);
    }
}

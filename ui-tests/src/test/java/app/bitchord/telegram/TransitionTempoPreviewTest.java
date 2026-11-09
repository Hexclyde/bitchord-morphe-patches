package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.os.Looper;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, shadows = TransitionEditorScreenTest.Installed.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class TransitionTempoPreviewTest {
    private final TransitionEditorScreenTest listening = new TransitionEditorScreenTest();
    private final List<String> logged = new ArrayList<>();

    @Before
    public void setup() throws Exception {
        listening.setup();
        HostLog.observer = logged::add;
    }

    @After
    public void close() throws Exception {
        HostLog.observer = null;
        listening.close();
    }

    private long timing(String field) {
        String line = null;
        for (String entry : logged)
            if (entry.startsWith("AutoMix preview timing: ")) {
                assertNull("one timing line per audition", line);
                line = entry;
            }
        assertNotNull(line);
        Matcher match = Pattern.compile(" " + field + "=(-?\\d+)").matcher(line);
        assertTrue(line, match.find());
        return Long.parseLong(match.group(1));
    }

    private String ending() {
        for (String entry : logged)
            if (entry.startsWith("AutoMix preview timing: "))
                return entry.substring(entry.indexOf("end=") + 4);
        return null;
    }

    /** Moves the virtual clock, running the preview's own polling as it goes. */
    private void idle(long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    private TransitionPreview.Audition begin(TransitionEdit edit) throws Exception {
        return TransitionPreview.acquire(TransitionRuntime.pair()).start(edit, () -> {});
    }

    @SuppressWarnings("unchecked")
    private List<AutoMixTestDeck> decks() throws Exception {
        Field field = TransitionEditorScreenTest.class.getDeclaredField("previewDecks");
        field.setAccessible(true);
        return (List<AutoMixTestDeck>) field.get(listening);
    }

    private void tick() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32));
    }

    @Test
    public void previewUsesEachSourceClockAndLeavesTheListeningPlayerUntouched() throws Exception {
        TransitionRuntime.Pair pair = TransitionRuntime.pair();
        TransitionPreview.Session held = TransitionPreview.acquire(pair);
        TransitionEdit edit = new TransitionEdit();
        edit.overlapRate[0] = 1.25;
        edit.overlapRate[1] = .8;
        edit.incomingCue = 3;
        TransitionPreview.Audition preview = held.start(edit, () -> {});
        tick();
        AutoMixTestDeck a = decks().get(0), b = decks().get(1);
        assertEquals(1.25f, a.parameters.speed, 0);
        assertEquals(1f, b.parameters.speed, 0);
        assertEquals(.95f, a.parameters.pitch, 0);
        a.position = Math.round(edit.start(pair.outgoingDuration) * 1000);
        tick();
        assertEquals(1.5625f, a.parameters.speed, 0);
        assertEquals(1f, b.parameters.speed, 0);
        assertTrue(b.playing);
        a.position += 5000;
        tick();
        assertEquals(.5, preview.progress(), .001);
        assertEquals(edit.gain(1, .5), b.volume, .00001);
        assertEquals(.95f, b.parameters.pitch, 0);
        assertEquals(1.25f, held.main.getPlaybackParameters().speed, 0);
        assertEquals(held.position, held.main.getCurrentPosition());
        assertTrue(held.unchanged());
        preview.stop();
        assertTrue(a.closed);
        assertTrue(b.closed);
    }

    @Test
    public void latePreviewStartMapsOutgoingProgressToTheIncomingClock() throws Exception {
        TransitionRuntime.Pair pair = TransitionRuntime.pair();
        TransitionPreview.Session held = TransitionPreview.acquire(pair);
        TransitionEdit edit = new TransitionEdit();
        edit.overlapRate[0] = 2;
        edit.overlapRate[1] = .5;
        edit.incomingCue = 4;
        TransitionPreview.Audition preview = held.start(edit, () -> {});
        tick();
        AutoMixTestDeck a = decks().get(0), b = decks().get(1);
        a.position = Math.round((edit.start(pair.outgoingDuration) + 4) * 1000);
        tick();
        assertEquals(5000, b.position);
        assertEquals(.25, preview.progress(), .001);
        assertEquals(2.5f, a.parameters.speed, 0);
        assertEquals(.625f, b.parameters.speed, 0);
        assertEquals(.95f, b.parameters.pitch, 0);
        assertTrue(held.unchanged());
        preview.stop();
    }

    @Test
    public void slowOutgoingDeckDelaysFirstReadyAndPlayWithoutCountingAStall() throws Exception {
        TransitionEdit edit = new TransitionEdit();
        TransitionPreview.Audition preview = begin(edit);
        AutoMixTestDeck a = decks().get(0);
        a.state = androidx.media3.common.Player.STATE_BUFFERING;
        idle(1000);
        assertFalse(a.playing);
        a.state = androidx.media3.common.Player.STATE_READY;
        idle(100);
        assertTrue(a.playing);
        preview.stop();
        assertTrue(timing("ctorMs") >= 0);
        assertTrue(timing("outReadyMs") >= 1000 && timing("outReadyMs") < 1100);
        assertTrue(timing("outPlayMs") >= timing("outReadyMs"));
        assertEquals(-1, timing("inPlayMs"));
        assertEquals(0, timing("stalls"));
        assertEquals(0, timing("stallMs"));
        assertEquals("stopped", ending());
    }

    @Test
    public void slowIncomingDeckThatIsReadyBeforeTheCueNeverStalls() throws Exception {
        TransitionRuntime.Pair pair = TransitionRuntime.pair();
        TransitionEdit edit = new TransitionEdit();
        TransitionPreview.Audition preview = begin(edit);
        AutoMixTestDeck a = decks().get(0), b = decks().get(1);
        b.state = androidx.media3.common.Player.STATE_BUFFERING;
        idle(800);
        assertTrue(a.playing);
        b.state = androidx.media3.common.Player.STATE_READY;
        idle(100);
        a.position = Math.round(edit.start(pair.outgoingDuration) * 1000);
        idle(100);
        assertTrue(b.playing);
        preview.stop();
        assertTrue(timing("inReadyMs") >= 800 && timing("inReadyMs") < 1000);
        assertTrue(timing("inPlayMs") >= timing("inReadyMs"));
        assertTrue(timing("outReadyMs") < timing("inReadyMs"));
        assertEquals(0, timing("stalls"));
    }

    @Test
    public void incomingDeckBufferingAtTheCuePausesBothAndResumes() throws Exception {
        TransitionRuntime.Pair pair = TransitionRuntime.pair();
        TransitionEdit edit = new TransitionEdit();
        TransitionPreview.Audition preview = begin(edit);
        AutoMixTestDeck a = decks().get(0), b = decks().get(1);
        b.state = androidx.media3.common.Player.STATE_BUFFERING;
        idle(100);
        assertTrue(a.playing);
        a.position = Math.round(edit.start(pair.outgoingDuration) * 1000);
        idle(500);
        assertFalse(a.playing);
        assertFalse(preview.playing());
        b.state = androidx.media3.common.Player.STATE_READY;
        idle(100);
        assertTrue(a.playing);
        assertTrue(b.playing);
        preview.stop();
        assertEquals(1, timing("stalls"));
        assertTrue(timing("stallMs") >= 500 && timing("stallMs") < 700);
        assertTrue(timing("inPlayMs") >= 0);
        assertEquals("stopped", ending());
    }

    @Test
    public void stoppingWhileBufferingCountsTheOpenStall() throws Exception {
        TransitionRuntime.Pair pair = TransitionRuntime.pair();
        TransitionEdit edit = new TransitionEdit();
        TransitionPreview.Audition preview = begin(edit);
        AutoMixTestDeck a = decks().get(0), b = decks().get(1);
        b.state = androidx.media3.common.Player.STATE_BUFFERING;
        idle(100);
        a.position = Math.round(edit.start(pair.outgoingDuration) * 1000);
        idle(300);
        preview.stop();
        preview.stop();
        assertEquals(1, timing("stalls"));
        assertTrue(timing("stallMs") >= 250);
    }

    @Test
    public void failedDeckSetupReportsOnceWithoutTimings() throws Exception {
        TransitionPreview.Factory working = TransitionPreview.factory;
        TransitionPreview.factory =
                (owner, side) -> {
                    if (side == 1) throw new IllegalStateException("content://audio/secret");
                    return working.create(owner, side);
                };
        try {
            begin(new TransitionEdit());
            fail();
        } catch (IllegalStateException expected) {
            assertEquals("setup-failed", ending());
            assertEquals(-1, timing("ctorMs"));
            assertEquals(-1, timing("outPlayMs"));
            for (String entry : logged) assertFalse(entry, entry.contains("secret"));
        } finally {
            TransitionPreview.factory = working;
        }
    }

    private String line() {
        for (String entry : logged) if (entry.startsWith("AutoMix preview timing: ")) return entry;
        return null;
    }

    @Test
    public void aFailedDeckReportsItsSideCodeAndCauseClassOnly() throws Exception {
        TransitionPreview.Audition preview = begin(new TransitionEdit());
        decks().get(1).error =
                new androidx.media3.common.PlaybackException(
                        "https://host/audio?token=secret",
                        new java.io.IOException("hidden"),
                        androidx.media3.common.PlaybackException
                                .ERROR_CODE_IO_NETWORK_CONNECTION_FAILED);
        idle(100);
        assertTrue(preview.closed);
        String line = line();
        assertNotNull(line);
        assertTrue(line, line.contains(" source=incoming "));
        assertTrue(line, line.contains(" code=2001 "));
        assertTrue(line, line.contains(" cause=IOException "));
        assertEquals("error", ending());
        for (String entry : logged) {
            assertFalse(entry, entry.contains("secret"));
            assertFalse(entry, entry.contains("hidden"));
        }
    }

    @Test
    public void aDeckPausedFromOutsideEndsWithThatReason() throws Exception {
        TransitionPreview.Audition preview = begin(new TransitionEdit());
        idle(100);
        assertTrue(decks().get(0).playing);
        decks().get(0).playing = false;
        idle(100);
        assertTrue(preview.closed);
        assertEquals("outgoing-paused", ending());
        assertTrue(line(), line().contains(" source=none code=-1 cause=none "));
    }

    @Test
    public void anAuditionOutranksBackgroundWorkUntilItEnds() throws Exception {
        PlaybackPriority.reset();
        assertFalse(PlaybackPriority.previewActive());
        TransitionPreview.Audition preview = begin(new TransitionEdit());
        assertTrue(
                "The audition owns the priority while it prepares",
                PlaybackPriority.previewActive());
        assertFalse(PlaybackPriority.backgroundAllowed());
        preview.stop();
        assertFalse(PlaybackPriority.previewActive());
        assertEquals(0, PlaybackPriority.leaseCount());
    }

    @Test
    public void aFailedSetupReleasesThePreviewPriority() throws Exception {
        PlaybackPriority.reset();
        TransitionPreview.Factory working = TransitionPreview.factory;
        TransitionPreview.factory =
                (owner, side) -> {
                    if (side == 1) throw new IllegalStateException("failed");
                    return working.create(owner, side);
                };
        try {
            begin(new TransitionEdit());
            fail();
        } catch (IllegalStateException expected) {
            assertFalse(PlaybackPriority.previewActive());
            assertEquals(0, PlaybackPriority.leaseCount());
        } finally {
            TransitionPreview.factory = working;
        }
    }

    @Test
    public void repeatedAuditionsNeverStackPreviewLeases() throws Exception {
        PlaybackPriority.reset();
        for (int i = 0; i < 5; i++) {
            TransitionPreview.Audition preview = begin(new TransitionEdit());
            assertEquals(1, PlaybackPriority.leaseCount());
            preview.stop();
            assertEquals(0, PlaybackPriority.leaseCount());
        }
    }

    @Test
    public void timingNamesEveryStageAndNeverInventsUnobservableOnes() throws Exception {
        TransitionPreview.tapped();
        TransitionPreview.Audition preview = begin(new TransitionEdit());
        tick();
        preview.stop();
        String line = line();
        assertNotNull(line);
        for (String field : new String[] {"tapMs", "sessionMs", "decksMs", "seekMs", "prepareMs"})
            assertTrue(field, timing(field) >= 0);
        assertTrue(line, line.contains(" sessionReused="));
        assertTrue(line, line.contains(" resolveMs=unavailable verifyMs=unavailable "));
    }

    @Test
    public void auditionWithoutATapReportsNoTapTime() throws Exception {
        begin(new TransitionEdit()).stop();
        assertEquals(-1, timing("tapMs"));
    }

    @Test
    public void repeatedAuditionsLeaveThePausedMainPlayerAndQueueAlone() throws Exception {
        TransitionPreview.Session held = TransitionPreview.acquire(TransitionRuntime.pair());
        long position = held.main.getCurrentPosition();
        int index = held.main.getCurrentMediaItemIndex(), count = held.main.getMediaItemCount();
        for (int i = 0; i < 4; i++) {
            TransitionPreview.Audition preview = held.start(new TransitionEdit(), () -> {});
            if (i % 2 == 0) tick();
            // A tap during an audition starts the next one; the earlier decks must be gone.
            if (i == 2) {
                TransitionPreview.Audition next = held.start(new TransitionEdit(), () -> {});
                assertTrue(preview.closed);
                next.stop();
                continue;
            }
            preview.stop();
        }
        assertEquals(position, held.main.getCurrentPosition());
        assertEquals(index, held.main.getCurrentMediaItemIndex());
        assertEquals(count, held.main.getMediaItemCount());
        assertFalse(held.main.getPlayWhenReady());
        assertTrue(held.unchanged());
        for (AutoMixTestDeck deck : decks()) assertTrue(deck.closed);
        assertEquals(0, PlaybackPriority.leaseCount());
    }
}

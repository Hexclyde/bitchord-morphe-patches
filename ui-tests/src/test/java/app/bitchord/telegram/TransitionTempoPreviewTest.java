package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.os.Looper;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, shadows = TransitionEditorScreenTest.Installed.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class TransitionTempoPreviewTest {
    private final TransitionEditorScreenTest listening = new TransitionEditorScreenTest();

    @Before
    public void setup() throws Exception {
        listening.setup();
    }

    @After
    public void close() throws Exception {
        listening.close();
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
}

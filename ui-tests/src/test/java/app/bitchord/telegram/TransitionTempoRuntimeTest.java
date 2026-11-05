package app.bitchord.telegram;

import static org.junit.Assert.*;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class TransitionTempoRuntimeTest {
    public static final class Service {
        public Player player;
    }

    public static final class Controller {
        public Player outgoing, incoming, easing;
    }

    private final Controller controller = new Controller();
    private final Object plan = new Object();
    private final AutoMixTestDeck a = new AutoMixTestDeck(),
            b = new AutoMixTestDeck(),
            c = new AutoMixTestDeck();

    private void decks(Player outgoing, Player incoming, Player easing) {
        controller.outgoing = outgoing;
        controller.incoming = incoming;
        controller.easing = easing;
    }

    private void nativeOnly() throws Exception {
        field("prepared", null);
        field("preparedPlan", null);
    }

    private static void assertDeck(AutoMixTestDeck deck, float speed, float pitch) {
        assertEquals(speed, deck.parameters.speed, 1e-5f);
        assertEquals(pitch, deck.parameters.pitch, 0);
    }

    private void field(String name, Object value) throws Exception {
        Field field = TransitionRuntime.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    @Before
    public void setup() throws Exception {
        TransitionRuntime.release();
        com.music.bitchord.playback.PlaybackService.selectedSpeed = 1f;
        a.item = new MediaItem.Builder().setMediaId("a").build();
        b.item = new MediaItem.Builder().setMediaId("b").build();
        a.parameters = b.parameters = c.parameters = new PlaybackParameters(1f, .95f);
        c.item = new MediaItem.Builder().setMediaId("c").build();
        decks(null, null, null);
        Service service = new Service();
        service.player = a.player();
        SourceAutomix.service(service);
        TransitionRuntime.bind(controller);
        TransitionEdit edit = new TransitionEdit();
        edit.overlapRate[0] = 1.25;
        edit.overlapRate[1] = .8;
        edit.tempoExplicit = true;
        field("prepared", edit);
        field("preparedPlan", plan);
        field("preparedPair", new TransitionRuntime.Pair("a", "b", "a", "b", 180, 180, 0));
    }

    @After
    public void close() {
        TransitionRuntime.release();
        SourceAutomix.release(SourceAutomix.owner());
        com.music.bitchord.playback.PlaybackService.selectedSpeed = 1f;
    }

    @Test
    public void unarmedPlaybackKeepsTheListenersSpeedAndPitch() {
        SourceAutomix.speed(a.player(), 1.9f);
        assertEquals(1f, a.parameters.speed, 0);
        assertEquals(.95f, a.parameters.pitch, 0);
    }

    @Test
    public void outgoingLeadInStaysOriginalAndIncomingPreparesAtItsExplicitRate() {
        TransitionRuntime.arm(controller, plan);
        decks(a.player(), b.player(), null);
        SourceAutomix.speed(a.player(), 1.9f);
        SourceAutomix.speed(b.player(), 1.9f);
        assertEquals(1f, a.parameters.speed, 0);
        assertEquals(.8f, b.parameters.speed, 0);
        assertEquals(.95f, b.parameters.pitch, 0);
        TransitionRuntime.beginTempo(controller);
        assertEquals(1.25f, a.parameters.speed, 0);
        assertEquals(.8f, b.parameters.speed, 0);
        assertEquals(.95f, a.parameters.pitch, 0);
        TransitionRuntime.finish(controller);
        assertEquals(1f, a.parameters.speed, 0);
        assertEquals(1f, b.parameters.speed, 0);
        assertEquals(.95f, b.parameters.pitch, 0);
    }

    @Test
    public void otherControllersCannotArmOrStartTheseRates() {
        TransitionRuntime.arm(new Object(), plan);
        SourceAutomix.speed(b.player(), 2f);
        assertEquals(1f, b.parameters.speed, 0);
        TransitionRuntime.arm(controller, plan);
        decks(a.player(), b.player(), null);
        SourceAutomix.speed(b.player(), 2f);
        TransitionRuntime.beginTempo(new Object());
        assertEquals(1f, a.parameters.speed, 0);
    }

    @Test
    public void aChangedIncomingRecordingCancelsInsteadOfStretchingAnotherTrack() {
        TransitionRuntime.arm(controller, plan);
        decks(a.player(), b.player(), null);
        SourceAutomix.speed(b.player(), 1f);
        b.item = new MediaItem.Builder().setMediaId("other").build();
        TransitionRuntime.beginTempo(controller);
        assertEquals(1f, a.parameters.speed, 0);
        assertEquals(1f, b.parameters.speed, 0);
        assertNull(TransitionRuntime.incoming(controller, .5f));
    }

    @Test
    public void ratesStackOnLiveListenerSpeedAndFinishReturnsToItsLatestValue() {
        com.music.bitchord.playback.PlaybackService.selectedSpeed = 1.25f;
        TransitionRuntime.arm(controller, plan);
        decks(a.player(), b.player(), null);
        SourceAutomix.speed(b.player(), 1f);
        TransitionRuntime.beginTempo(controller);
        assertEquals(1.5625f, a.parameters.speed, 0);
        assertEquals(1f, b.parameters.speed, 0);
        assertEquals(.95f, a.parameters.pitch, 0);
        com.music.bitchord.playback.PlaybackService.selectedSpeed = 1.5f;
        SourceAutomix.speed(a.player(), 1f);
        SourceAutomix.speed(b.player(), 1f);
        assertEquals(1.875f, a.parameters.speed, 0);
        assertEquals(1.2f, b.parameters.speed, .00001f);
        TransitionRuntime.finish(controller);
        assertEquals(1.5f, a.parameters.speed, 0);
        assertEquals(1.5f, b.parameters.speed, 0);
        assertEquals(.95f, b.parameters.pitch, 0);
    }

    @Test
    public void finishPreservesAnUnrelatedPlaybackParameterChange() {
        TransitionRuntime.arm(controller, plan);
        decks(a.player(), b.player(), null);
        SourceAutomix.speed(b.player(), 1f);
        TransitionRuntime.beginTempo(controller);
        b.parameters = new PlaybackParameters(1.4f, 1.1f);
        TransitionRuntime.finish(controller);
        assertEquals(1f, a.parameters.speed, 0);
        assertEquals(1.4f, b.parameters.speed, 0);
        assertEquals(1.1f, b.parameters.pitch, 0);
    }

    private void explicit(double outgoing, double incoming) throws Exception {
        TransitionEdit edit = new TransitionEdit();
        edit.overlapRate[0] = outgoing;
        edit.overlapRate[1] = incoming;
        edit.tempoExplicit = true;
        field("prepared", edit);
    }

    private void armExplicit(double outgoing, double incoming) throws Exception {
        explicit(outgoing, incoming);
        TransitionRuntime.arm(controller, plan);
        decks(a.player(), b.player(), null);
    }

    @Test
    public void nativeAbsoluteSpeedsPassOnceThroughStartPulseHandoffEaseAndEnd() throws Exception {
        nativeOnly();
        com.music.bitchord.playback.PlaybackService.selectedSpeed = 1.25f;
        TransitionRuntime.arm(controller, plan);
        decks(a.player(), b.player(), null);
        SourceAutomix.speed(b.player(), 1.3f);
        assertDeck(b, 1.3f, .95f);
        SourceAutomix.speed(b.player(), 1.326f);
        assertDeck(b, 1.326f, .95f);
        decks(b.player(), a.player(), null);
        SourceAutomix.speed(b.player(), 1.3f);
        assertDeck(b, 1.3f, .95f);
        TransitionRuntime.finish(controller);
        decks(null, a.player(), b.player());
        SourceAutomix.speed(b.player(), 1.275f);
        assertDeck(b, 1.275f, .95f);
        decks(null, null, null);
        SourceAutomix.speed(b.player(), 1.25f);
        assertDeck(b, 1.25f, .95f);
        assertEquals(1.25f, com.music.bitchord.playback.PlaybackService.selectedSpeed, 0);
    }

    @Test
    public void nativeSpeedAppliesToNativeDecksOnlyAndAnUnrelatedDeckGetsTheGlobalBase()
            throws Exception {
        nativeOnly();
        com.music.bitchord.playback.PlaybackService.selectedSpeed = 1.25f;
        decks(a.player(), b.player(), null);
        SourceAutomix.speed(c.player(), 1.3f);
        assertDeck(c, 1.25f, .95f);
        SourceAutomix.speed(b.player(), 1.3f);
        assertDeck(b, 1.3f, .95f);
    }

    @Test
    public void implicitRatesAreNeverOwnedAndNativeFinishAndEaseStayNative() throws Exception {
        com.music.bitchord.playback.PlaybackService.selectedSpeed = 1.25f;
        TransitionEdit edit = new TransitionEdit();
        edit.overlapRate[0] = 1.04;
        edit.overlapRate[1] = 1.04;
        field("prepared", edit);
        TransitionRuntime.arm(controller, plan);
        decks(a.player(), b.player(), null);
        SourceAutomix.speed(b.player(), 1.3f);
        assertDeck(b, 1.3f, .95f);
        TransitionRuntime.beginTempo(controller);
        assertDeck(a, 1f, .95f);
        TransitionRuntime.finish(controller);
        decks(null, a.player(), b.player());
        SourceAutomix.speed(b.player(), 1.275f);
        assertDeck(b, 1.275f, .95f);
    }

    @Test
    public void explicitUnityOwnsSpeedAndIgnoresNativeRatesAndEase() throws Exception {
        com.music.bitchord.playback.PlaybackService.selectedSpeed = 1.25f;
        armExplicit(1, 1);
        SourceAutomix.speed(b.player(), 1.3f);
        assertDeck(b, 1.25f, .95f);
        TransitionRuntime.beginTempo(controller);
        SourceAutomix.speed(a.player(), 1.3f);
        assertDeck(a, 1.25f, .95f);
        SourceAutomix.speed(b.player(), 1.326f);
        assertDeck(b, 1.25f, .95f);
        TransitionRuntime.finish(controller);
        decks(null, a.player(), b.player());
        SourceAutomix.speed(b.player(), 1.3f);
        assertDeck(b, 1.25f, .95f);
    }

    @Test
    public void explicitSlowerIncomingRateIsAppliedOnceToTheGlobalBase() throws Exception {
        com.music.bitchord.playback.PlaybackService.selectedSpeed = 1.25f;
        armExplicit(1, .8);
        SourceAutomix.speed(b.player(), 1.3f);
        assertDeck(b, 1f, .95f);
        TransitionRuntime.beginTempo(controller);
        SourceAutomix.speed(b.player(), 1.3f);
        assertDeck(b, 1f, .95f);
        assertDeck(a, 1.25f, .95f);
    }

    @Test
    public void lateNativeEaseDoesNotReapplyTheExplicitFactorAfterFinish() throws Exception {
        com.music.bitchord.playback.PlaybackService.selectedSpeed = 1.25f;
        armExplicit(1.25, .8);
        SourceAutomix.speed(b.player(), 1f);
        TransitionRuntime.beginTempo(controller);
        TransitionRuntime.finish(controller);
        assertDeck(b, 1.25f, .95f);
        decks(null, null, b.player());
        SourceAutomix.speed(b.player(), 1f);
        assertDeck(b, 1.25f, .95f);
        SourceAutomix.speed(b.player(), 1.125f);
        assertDeck(b, 1.25f, .95f);
        decks(null, null, null);
        com.music.bitchord.playback.PlaybackService.selectedSpeed = 1.125f;
        SourceAutomix.speed(b.player(), 1.125f);
        assertDeck(b, 1.125f, .95f);
    }

    @Test
    public void theReleaseMarkerEndsWhenTheDeckStopsEasing() throws Exception {
        com.music.bitchord.playback.PlaybackService.selectedSpeed = 1.25f;
        armExplicit(1, .8);
        SourceAutomix.speed(b.player(), 1f);
        TransitionRuntime.beginTempo(controller);
        TransitionRuntime.finish(controller);
        decks(null, null, b.player());
        SourceAutomix.speed(b.player(), 1f);
        assertDeck(b, 1.25f, .95f);
        decks(null, null, null);
        SourceAutomix.speed(b.player(), 1.25f);
        assertDeck(b, 1.25f, .95f);
        nativeOnly();
        TransitionRuntime.arm(controller, plan);
        decks(a.player(), b.player(), null);
        SourceAutomix.speed(b.player(), 1.3f);
        assertDeck(b, 1.3f, .95f);
    }

    @Test
    public void theReleaseMarkerEndsOnANewArm() throws Exception {
        com.music.bitchord.playback.PlaybackService.selectedSpeed = 1.25f;
        armExplicit(1, .8);
        SourceAutomix.speed(b.player(), 1f);
        TransitionRuntime.beginTempo(controller);
        TransitionRuntime.finish(controller);
        TransitionEdit next = new TransitionEdit();
        next.overlapRate[0] = 1.04;
        next.overlapRate[1] = 1.04;
        field("prepared", next);
        TransitionRuntime.arm(controller, plan);
        decks(a.player(), b.player(), null);
        SourceAutomix.speed(b.player(), 1.3f);
        assertDeck(b, 1.3f, .95f);
    }

    @Test
    public void theReleaseMarkerEndsWhenAnotherControllerIsBound() throws Exception {
        com.music.bitchord.playback.PlaybackService.selectedSpeed = 1.25f;
        armExplicit(1, .8);
        SourceAutomix.speed(b.player(), 1f);
        TransitionRuntime.beginTempo(controller);
        TransitionRuntime.finish(controller);
        Controller other = new Controller();
        other.incoming = b.player();
        TransitionRuntime.bind(other);
        SourceAutomix.speed(b.player(), 1.3f);
        assertDeck(b, 1.3f, .95f);
    }

    @Test
    public void aForeignSpeedChangeSurvivesFinishAndTheLaterNativeEase() throws Exception {
        com.music.bitchord.playback.PlaybackService.selectedSpeed = 1.25f;
        armExplicit(1.25, .8);
        SourceAutomix.speed(b.player(), 1f);
        TransitionRuntime.beginTempo(controller);
        b.parameters = new PlaybackParameters(1.4f, 1.1f);
        TransitionRuntime.finish(controller);
        assertDeck(b, 1.4f, 1.1f);
        decks(null, null, b.player());
        SourceAutomix.speed(b.player(), 1f);
        assertDeck(b, 1.4f, 1.1f);
        SourceAutomix.speed(b.player(), 1.125f);
        assertDeck(b, 1.4f, 1.1f);
    }

    @Test
    public void aForeignSpeedSurvivesTheUnityCleanupBeforeNativeDecksAreCleared() throws Exception {
        com.music.bitchord.playback.PlaybackService.selectedSpeed = 1.25f;
        armExplicit(1, 1);
        SourceAutomix.speed(b.player(), 1.3f);
        TransitionRuntime.beginTempo(controller);
        b.parameters = new PlaybackParameters(1.4f, 1.1f);
        TransitionRuntime.finish(controller);
        assertDeck(b, 1.4f, 1.1f);
        decks(a.player(), b.player(), null);
        SourceAutomix.speed(b.player(), 1.25f);
        assertDeck(b, 1.4f, 1.1f);
    }

    @Test
    public void aForeignSpeedChosenAfterFinishSurvivesNativeCleanup() throws Exception {
        com.music.bitchord.playback.PlaybackService.selectedSpeed = 1.25f;
        armExplicit(1, .8);
        SourceAutomix.speed(b.player(), 1f);
        TransitionRuntime.beginTempo(controller);
        TransitionRuntime.finish(controller);
        b.parameters = new PlaybackParameters(1.4f, 1.1f);
        decks(null, null, b.player());
        SourceAutomix.speed(b.player(), 1f);
        assertDeck(b, 1.4f, 1.1f);
    }

    @Test
    public void aPitchOnlyChangeSurvivesWhileTheOwnedSpeedReturnsToTheBase() throws Exception {
        com.music.bitchord.playback.PlaybackService.selectedSpeed = 1.25f;
        armExplicit(1.25, .8);
        SourceAutomix.speed(b.player(), 1f);
        TransitionRuntime.beginTempo(controller);
        b.parameters = new PlaybackParameters(b.parameters.speed, 1.1f);
        a.parameters = new PlaybackParameters(a.parameters.speed, 1.05f);
        TransitionRuntime.finish(controller);
        assertDeck(b, 1.25f, 1.1f);
        assertDeck(a, 1.25f, 1.05f);
    }

    @Test
    public void aNewGlobalSpeedDuringTheEaseWinsOverTheStaleBaseAndForeignSnapshot()
            throws Exception {
        com.music.bitchord.playback.PlaybackService.selectedSpeed = 1.25f;
        armExplicit(1.25, .8);
        SourceAutomix.speed(b.player(), 1f);
        TransitionRuntime.beginTempo(controller);
        b.parameters = new PlaybackParameters(1.4f, .95f);
        TransitionRuntime.finish(controller);
        decks(null, null, b.player());
        SourceAutomix.speed(b.player(), 1f);
        assertDeck(b, 1.4f, .95f);
        com.music.bitchord.playback.PlaybackService.selectedSpeed = 1.5f;
        SourceAutomix.speed(b.player(), 1.5f);
        assertDeck(b, 1.5f, .95f);
        com.music.bitchord.playback.PlaybackService.selectedSpeed = 1.25f;
        SourceAutomix.speed(b.player(), 1f);
        assertDeck(b, 1.25f, .95f);
        TransitionRuntime.release();
        TransitionRuntime.bind(controller);
        field("preparedPlan", plan);
        armExplicit(1.25, .8);
        decks(a.player(), b.player(), null);
        SourceAutomix.speed(b.player(), 1.2f);
        TransitionRuntime.beginTempo(controller);
        TransitionRuntime.finish(controller);
        com.music.bitchord.playback.PlaybackService.selectedSpeed = 1.75f;
        decks(null, null, b.player());
        SourceAutomix.speed(b.player(), 1.2f);
        assertDeck(b, 1.75f, .95f);
    }
}

package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Bundle;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Turning YouTube Music off from Sources, and what stays blocked afterwards. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class YouTubePolicyTest {
    /** The host row as the patched setEnabled treats it; `saved` outlives a restart. */
    static final class Host implements YouTubePolicy.Registry {
        static boolean[] saved = {true};
        boolean broken;
        int cleared;
        String origin;
        String active;
        String keyOrigin;

        @Override
        public YouTubePolicy.Row youtube() {
            if (broken) throw new IllegalStateException("host changed");
            return new YouTubePolicy.Row("youtube", saved[0]);
        }

        @Override
        public void setEnabled(String id, boolean on) {
            if (!on && !YouTubePolicy.confirmDisable(id)) return;
            saved[0] = on;
        }

        @Override
        public String origin(DataSpec spec) {
            return origin;
        }

        @Override
        public String active(MediaItem item) {
            return active;
        }

        @Override
        public void dropChoices() {
            cleared++;
        }

        @Override
        public String keyOrigin(String key) {
            return keyOrigin;
        }
    }

    Activity activity;
    Host host;
    SourceFallbackTest.Fake other;

    @Before
    public void open() {
        Host.saved[0] = true;
        YouTubePolicy.reset();
        SourceFallback.reset();
        host = new Host();
        YouTubePolicy.use(host);
        other = new SourceFallbackTest.Fake();
        other.storage = false;
        SourceFallback.use(other);
        start();
    }

    private void start() {
        Context app = RuntimeEnvironment.getApplication();
        YouTubePolicy.init(app);
        YouTubePolicy.loaded();
        ActivityController<Activity> controller =
                Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
    }

    @After
    public void close() {
        YouTubePolicy.reset();
        SourceFallback.reset();
    }

    @Test
    public void nativeRegistryFlagRemainsThePlaybackAuthority() {
        List<com.music.bitchord.data.sources.SourceConfig> before =
                com.music.bitchord.data.sources.SourceRegistry.configs.getValue();
        try {
            YouTubePolicy.use(null);
            java.util.ArrayList<com.music.bitchord.data.sources.SourceConfig> rows =
                    new java.util.ArrayList<>();
            rows.add(
                    new com.music.bitchord.data.sources.SourceConfig(
                            "youtube",
                            com.music.bitchord.data.sources.SourceKind.YOUTUBE,
                            "YouTube Music",
                            "",
                            false,
                            true,
                            false));
            com.music.bitchord.data.sources.SourceRegistry.publish(rows, false);
            assertTrue(YouTubePolicy.playbackBlocked());
            assertFalse(YouTubePolicy.catalogueBlocked());
            com.music.bitchord.data.sources.SourceRegistry.setEnabled("youtube", true);
            assertFalse(YouTubePolicy.playbackBlocked());
        } finally {
            com.music.bitchord.data.sources.SourceRegistry.publish(
                    new java.util.ArrayList<>(before), false);
        }
    }

    private AlertDialog ask() {
        host.setEnabled("youtube", false);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("the question appears", dialog);
        assertTrue(dialog.isShowing());
        return dialog;
    }

    private static void idle() {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    private void press(AlertDialog dialog, int button) {
        dialog.getButton(button).performClick();
        idle();
    }

    private void turnOff(int choice) {
        AlertDialog dialog = ask();
        if (choice >= 0) org.robolectric.Shadows.shadowOf(dialog).clickOnItem(choice);
        press(dialog, DialogInterface.BUTTON_POSITIVE);
    }

    private interface Gate {
        void run() throws IOException;
    }

    private static void blocked(Gate gate) {
        try {
            gate.run();
            fail("expected a refusal");
        } catch (IOException e) {
            assertEquals("YouTube Music is turned off", e.getMessage());
        }
    }

    private static void open(Gate gate) throws IOException {
        gate.run();
    }

    @Test
    public void enabledByDefaultAndNothingIsBlocked() throws Exception {
        assertEquals(YouTubePolicy.PLAYBACK_ONLY, YouTubePolicy.mode());
        open(YouTubePolicy::playbackGate);
        open(YouTubePolicy::catalogueGate);
    }

    @Test
    public void openingTheQuestionChangesNothing() {
        AlertDialog dialog = ask();
        assertTrue(Host.saved[0]);
        assertEquals(
                "Playback only is picked first", 0, dialog.getListView().getCheckedItemPosition());
        assertNull(
                RuntimeEnvironment.getApplication()
                        .getSharedPreferences(YouTubePolicy.PREFERENCES, Context.MODE_PRIVATE)
                        .getString(YouTubePolicy.MODE, null));
    }

    @Test
    public void cancelWritesNothing() throws Exception {
        AlertDialog dialog = ask();
        org.robolectric.Shadows.shadowOf(dialog).clickOnItem(1);
        press(dialog, DialogInterface.BUTTON_NEGATIVE);
        assertTrue(Host.saved[0]);
        assertEquals(0, host.cleared);
        assertFalse(
                RuntimeEnvironment.getApplication()
                        .getSharedPreferences(YouTubePolicy.PREFERENCES, Context.MODE_PRIVATE)
                        .contains(YouTubePolicy.MODE));
        open(YouTubePolicy::playbackGate);
    }

    @Test
    public void playbackOnlyStopsAudioAndKeepsSearch() throws Exception {
        turnOff(-1);
        assertFalse(Host.saved[0]);
        assertEquals(YouTubePolicy.PLAYBACK_ONLY, YouTubePolicy.mode());
        blocked(YouTubePolicy::playbackGate);
        open(YouTubePolicy::catalogueGate);
        assertEquals(1, host.cleared);
    }

    @Test
    public void fullDisableAlsoStopsCatalogueRequests() {
        turnOff(1);
        assertFalse(Host.saved[0]);
        assertEquals(YouTubePolicy.FULL, YouTubePolicy.mode());
        blocked(YouTubePolicy::playbackGate);
        blocked(YouTubePolicy::catalogueGate);
    }

    /** A source that reports the address it actually read, as the cache layer does. */
    static final class Stream implements DataSource {
        final Uri actual;
        boolean opened, closed, asked;

        Stream(String actual) {
            this.actual = actual == null ? null : Uri.parse(actual);
        }

        @Override
        public void addTransferListener(androidx.media3.datasource.TransferListener l) {}

        @Override
        public long open(DataSpec spec) {
            opened = true;
            return 100;
        }

        @Override
        public int read(byte[] b, int o, int n) {
            return -1;
        }

        @Override
        public Uri getUri() {
            asked = true;
            return actual;
        }

        @Override
        public Map<String, List<String>> getResponseHeaders() {
            return java.util.Collections.emptyMap();
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    static final String LOGICAL = "bitchord://watch?v=abc123";
    static final String TAGGED = "bitchord://watch?v=abc123&watch_read=1&automix_opus=1";
    static final String GOOGLE = "https://rr3---sn-x.googlevideo.com/videoplayback?id=1";

    @Test
    public void cachedPolicyUsesRenditionProofAndRejectsMixedBytes() throws Exception {
        List<com.music.bitchord.data.sources.SourceConfig> before =
                com.music.bitchord.data.sources.SourceRegistry.configs.getValue();
        AudioOrigin.Host original = AudioOrigin.host;
        Map<String, String> tags = new java.util.HashMap<>();
        java.util.concurrent.atomic.AtomicBoolean explicitKey =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        try {
            com.music.bitchord.data.sources.SourceRegistry.publish(
                    new java.util.ArrayList<>(
                            java.util.Collections.singletonList(
                                    new com.music.bitchord.data.sources.SourceConfig(
                                            "youtube",
                                            com.music.bitchord.data.sources.SourceKind.YOUTUBE,
                                            "YouTube Music",
                                            "",
                                            false,
                                            true,
                                            false))),
                    false);
            AudioOrigin.host =
                    (AudioOrigin.Host)
                            java.lang.reflect.Proxy.newProxyInstance(
                                    AudioOrigin.Host.class.getClassLoader(),
                                    new Class<?>[] {AudioOrigin.Host.class},
                                    (object, method, arguments) -> {
                                        if (method.getName().equals("requestKey"))
                                            return explicitKey.get() ? "abc123#quality" : null;
                                        if (method.getName().equals("keyOf"))
                                            return "abc123#quality";
                                        if (method.getName().equals("tag"))
                                            return tags.get(arguments[1]);
                                        if (method.getName().equals("hasBytes")) return true;
                                        return null;
                                    });
            YouTubePolicy.use(null);
            tags.put(AudioOrigin.META_ID, "telegram-native");
            tags.put(AudioOrigin.META_KIND, "ADDON");
            tags.put(AudioOrigin.META_ORIGIN, "YOUTUBE");
            Stream alternate = new Stream(LOGICAL);
            assertEquals(100, play(TAGGED, alternate));
            assertFalse(alternate.closed);
            explicitKey.set(false);
            assertEquals(100, play(TAGGED, new Stream(LOGICAL)));
            tags.put(AudioOrigin.META_MIXED, "1");
            refuse(TAGGED, new Stream(LOGICAL));
            tags.clear();
            tags.put(AudioOrigin.META_ORIGIN, "OTHER");
            refuse(TAGGED, new Stream(LOGICAL));
        } finally {
            AudioOrigin.host = original;
            com.music.bitchord.data.sources.SourceRegistry.publish(
                    new java.util.ArrayList<>(before), false);
        }
    }

    private long play(String request, Stream stream) throws IOException {
        return YouTubePolicy.open(stream, new DataSpec(Uri.parse(request)));
    }

    private void refuse(String request, Stream stream) {
        try {
            play(request, stream);
            fail("expected the stream to be refused");
        } catch (IOException e) {
            assertEquals("YouTube Music is turned off", e.getMessage());
        }
        assertTrue("a refused stream is closed", stream.closed);
    }

    @Test
    public void whileOnNothingIsInspected() throws Exception {
        Stream stream = new Stream(GOOGLE);
        assertEquals(100, play(LOGICAL, stream));
        assertFalse(stream.asked);
        assertFalse(stream.closed);
    }

    @Test
    public void networkYouTubeBytesAreClosedInBothModes() {
        turnOff(-1);
        refuse(LOGICAL, new Stream(GOOGLE));
        refuse(TAGGED, new Stream("https://music.youtube.com/x"));
        Host.saved[0] = true;
        turnOff(1);
        refuse(LOGICAL, new Stream(GOOGLE));
    }

    @Test
    public void taggedCachedYouTubeIsClosedWhichEverVariantAsksForIt() {
        turnOff(-1);
        host.origin = "YOUTUBE";
        refuse(LOGICAL, new Stream(LOGICAL));
        refuse(TAGGED, new Stream(TAGGED));
        refuse(LOGICAL + "&q=hi", new Stream(LOGICAL));
    }

    @Test
    public void aCopyFromAnotherSourceKeepsItsYouTubeIdAndPlays() throws Exception {
        turnOff(1);
        // Resolved to the other source's address, with the id still in the request.
        assertEquals(100, play(LOGICAL, new Stream("telegram://file/9")));
        assertEquals(100, play(TAGGED, new Stream("https://cdn.example.net/a.flac")));
        // Cached entry that still shows the logical address but is recorded as another source's.
        host.origin = "OTHER";
        assertEquals(100, play(TAGGED, new Stream(TAGGED)));
        host.origin = "JIOSAAVN";
        assertEquals(100, play(LOGICAL, new Stream(LOGICAL)));
    }

    @Test
    public void savedFilesPlayEvenWhenTheRequestNamesAYouTubeId() throws Exception {
        turnOff(-1);
        assertEquals(100, play(LOGICAL, new Stream("file:///data/music/a.flac")));
        assertEquals(100, play(LOGICAL, new Stream("content://media/external/audio/1")));
        assertEquals(100, play("file:///data/music/a.flac", new Stream(null)));
    }

    @Test
    public void unknownOriginsAreRefusedNotGuessed() {
        turnOff(-1);
        host.origin = null;
        refuse(LOGICAL, new Stream(LOGICAL));
        refuse(LOGICAL, new Stream(null));
    }

    @Test
    public void referenceReadsToOtherHostsAreNotAudioGates() throws Exception {
        turnOff(-1);
        assertEquals(
                100,
                play("https://example.org/captions", new Stream("https://example.org/captions")));
    }

    @Test
    public void aRequestBeforeTheSavedSourcesLoadWaitsThenFollowsTheSavedState() throws Exception {
        // init ran, the saved sources have not been read yet, and the saved state is "off".
        YouTubePolicy.reset();
        YouTubePolicy.use(host);
        Host.saved[0] = false;
        getSharedPreferences().edit().putString(YouTubePolicy.MODE, YouTubePolicy.FULL).commit();
        YouTubePolicy.init(RuntimeEnvironment.getApplication());
        AtomicReference<Object> outcome = new AtomicReference<>();
        Thread first =
                new Thread(
                        () -> {
                            try {
                                YouTubePolicy.catalogueGate();
                                outcome.set("open");
                            } catch (IOException e) {
                                outcome.set(e);
                            }
                        });
        first.start();
        first.join(300);
        assertTrue("the request is held, not let through", first.isAlive());
        YouTubePolicy.loaded();
        first.join(3000);
        assertTrue(outcome.get() instanceof IOException);
    }

    @Test
    public void beforeLoadingTheMainThreadKeepsAudioClosedAndListsOpen() throws Exception {
        YouTubePolicy.reset();
        YouTubePolicy.use(host);
        YouTubePolicy.init(RuntimeEnvironment.getApplication());
        assertTrue(YouTubePolicy.playbackBlocked());
        assertFalse(YouTubePolicy.catalogueBlocked());
        YouTubePolicy.loaded();
        assertFalse(YouTubePolicy.playbackBlocked());
    }

    private static android.content.SharedPreferences getSharedPreferences() {
        return RuntimeEnvironment.getApplication()
                .getSharedPreferences(YouTubePolicy.PREFERENCES, Context.MODE_PRIVATE);
    }

    /** A player that records what the policy does to it; every other call is an error. */
    static final class Fake implements java.lang.reflect.InvocationHandler {
        boolean playWhenReady = true;
        MediaItem item;
        int pauses, plays, index;
        long position = 1234;
        int state = Player.STATE_READY;
        PlaybackException playerError;
        final List<String> order = new ArrayList<>();
        final List<Player.Listener> listeners = new ArrayList<>();
        final java.util.Set<String> calls = new java.util.TreeSet<>();

        Fake(String uri) {
            item = new MediaItem.Builder().setMediaId("abc123").setUri(uri).build();
        }

        Player player() {
            return (Player)
                    java.lang.reflect.Proxy.newProxyInstance(
                            Player.class.getClassLoader(), new Class<?>[] {Player.class}, this);
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
            calls.add(m.getName());
            switch (m.getName()) {
                case "getApplicationLooper":
                    return android.os.Looper.getMainLooper();
                case "getPlayWhenReady":
                    return playWhenReady;
                case "getCurrentMediaItem":
                    return item;
                case "addListener":
                    listeners.add((Player.Listener) args[0]);
                    return null;
                case "pause":
                    pauses++;
                    playWhenReady = false;
                    order.add("pause");
                    return null;
                case "play":
                    plays++;
                    playWhenReady = true;
                    order.add("play");
                    return null;
                case "stop":
                case "prepare":
                    order.add(m.getName());
                    return null;
                case "getCurrentMediaItemIndex":
                    return index;
                case "getCurrentPosition":
                    return position;
                case "getPlaybackState":
                    return state;
                case "getPlayerError":
                    return playerError;
                case "replaceMediaItem":
                    item = (MediaItem) args[1];
                    order.add("replace:" + args[0] + ":" + item.localConfiguration.uri);
                    return null;
                case "seekTo":
                    order.add("seekTo:" + args[0] + ":" + args[1]);
                    return null;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return "fake player";
                default:
                    throw new AssertionError("unexpected player call " + m.getName());
            }
        }

        /** Starts playback through the native play-intent callback. */
        void press() {
            playWhenReady = true;
            for (Player.Listener l : new ArrayList<>(listeners)) tell(l, true);
        }
    }

    /** Calls whichever argument order this Media3 has; the policy answers both. */
    private static void tell(Player.Listener l, boolean ready) {
        try {
            l.getClass()
                    .getMethod("onPlayWhenReadyChanged", boolean.class, int.class)
                    .invoke(l, ready, 1);
            l.getClass()
                    .getMethod("onPlayWhenReadyChanged", int.class, boolean.class)
                    .invoke(l, 1, ready);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void moved(Player.Listener l, MediaItem item) {
        try {
            l.getClass()
                    .getMethod("onMediaItemTransition", MediaItem.class, int.class)
                    .invoke(l, item, 0);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private Fake watching(String uri, String origin) {
        Fake fake = new Fake(uri);
        host.active = origin;
        YouTubePolicy.observe(fake.player());
        keep.add(fake);
        return fake;
    }

    private final List<Object> keep = new ArrayList<>();

    private void publish() {
        // The patched SourceRegistry.publish calls this once the new flags are visible.
        YouTubePolicy.sourcesChanged();
        idle();
    }

    @Test
    public void startupPublicationNeverWaitsForItsOwnCompletionSignal() throws Exception {
        YouTubePolicy.reset();
        YouTubePolicy.use(host);
        Host.saved[0] = false;
        YouTubePolicy.init(RuntimeEnvironment.getApplication());
        Fake fake = watching(LOGICAL, "YOUTUBE");
        Thread loader = new Thread(YouTubePolicy::sourcesChanged);
        loader.start();
        try {
            loader.join(300);
            assertFalse(
                    "publication must return before the loader can signal loaded",
                    loader.isAlive());
            assertEquals(0, fake.pauses);
        } finally {
            YouTubePolicy.loaded();
            loader.join(3000);
            idle();
        }
        assertEquals("the saved off flag applies once loading completes", 1, fake.pauses);
    }

    @Test
    public void anEnabledSourceKeepsItsStartupPlayIntentWhilePreferencesLoad() {
        YouTubePolicy.reset();
        YouTubePolicy.use(host);
        YouTubePolicy.init(RuntimeEnvironment.getApplication());
        Fake fake = watching(LOGICAL, "YOUTUBE");
        fake.press();
        assertTrue("loading preferences must not clear the play request", fake.playWhenReady);
        YouTubePolicy.loaded();
        assertEquals(0, fake.pauses);
    }

    @Test
    public void openedWhileOnThenOffStopsTheYouTubeAudio() {
        Fake fake = watching(LOGICAL, "YOUTUBE");
        publish();
        assertEquals("nothing happens while it is on", 0, fake.pauses);
        Host.saved[0] = false;
        publish();
        assertEquals(1, fake.pauses);
        assertFalse(fake.playWhenReady);
        assertEquals(0, fake.plays);
    }

    @Test
    public void bothOffModesStopBufferedYouTube() {
        for (String mode : new String[] {YouTubePolicy.PLAYBACK_ONLY, YouTubePolicy.FULL}) {
            Host.saved[0] = true;
            YouTubePolicy.reset();
            YouTubePolicy.use(host);
            start();
            getSharedPreferences().edit().putString(YouTubePolicy.MODE, mode).commit();
            Fake fake = watching(LOGICAL, "YOUTUBE");
            Host.saved[0] = false;
            publish();
            assertEquals(mode, 1, fake.pauses);
        }
    }

    @Test
    public void pressingPlayOnBufferedYouTubeOpensTheSongAgainOnce() {
        Fake fake = watching(LOGICAL, "YOUTUBE");
        Host.saved[0] = false;
        publish();
        fake.press();
        assertEquals(
                "dropped and opened again once, which asks the other sources",
                java.util.Arrays.asList("pause", "pause", "stop", "prepare", "play"),
                fake.order);
        assertEquals("the play callback that follows does nothing", 1, fake.plays);
    }

    @Test
    public void turningItBackOnStartsNothingByItself() {
        Fake fake = watching(LOGICAL, "YOUTUBE");
        Host.saved[0] = false;
        publish();
        Host.saved[0] = true;
        publish();
        assertEquals(1, fake.pauses);
        assertEquals(0, fake.plays);
        assertFalse(fake.playWhenReady);
        fake.press();
        assertEquals("playback can start again", 1, fake.pauses);
    }

    @Test
    public void aProvenAlternateCopyKeepsPlayingWhateverItsYouTubeId() {
        Fake fake = watching(TAGGED, "OTHER");
        Host.saved[0] = false;
        publish();
        fake.press();
        assertEquals(0, fake.pauses);
        assertTrue(fake.playWhenReady);
    }

    @Test
    public void aSavedFileKeepsPlaying() {
        Fake fake = watching("file:///data/music/abc123.flac", null);
        Host.saved[0] = false;
        publish();
        fake.press();
        assertEquals(0, fake.pauses);
        Fake shared = watching("content://media/external/audio/1", null);
        publish();
        assertEquals(0, shared.pauses);
    }

    @Test
    public void anUnprovenLogicalTrackIsStoppedNotGuessed() {
        Fake fake = watching(LOGICAL, null);
        Host.saved[0] = false;
        publish();
        assertEquals(1, fake.pauses);
    }

    @Test
    public void aPausedPlayerIsLeftAlone() {
        Fake fake = watching(LOGICAL, "YOUTUBE");
        fake.playWhenReady = false;
        Host.saved[0] = false;
        publish();
        assertEquals(0, fake.pauses);
    }

    @Test
    public void theTrackChangingToAnotherSourceIsJudgedByItsOwnBytes() {
        Fake fake = watching(LOGICAL, "YOUTUBE");
        Host.saved[0] = false;
        publish();
        fake.playWhenReady = true;
        host.active = "OTHER";
        fake.item = new MediaItem.Builder().setMediaId("abc123").setUri(TAGGED).build();
        for (Player.Listener l : fake.listeners) moved(l, fake.item);
        assertEquals(1, fake.pauses);
        assertTrue(fake.playWhenReady);
    }

    @Test
    public void aQueuePlaybackItsPositionSpeedAndPitchAreNeverTouched() {
        Fake fake = watching(LOGICAL, "YOUTUBE");
        Host.saved[0] = false;
        publish();
        fake.press();
        java.util.Set<String> allowed =
                new java.util.TreeSet<>(
                        java.util.Arrays.asList(
                                "getApplicationLooper",
                                "getPlayWhenReady",
                                "getCurrentMediaItem",
                                "getCurrentMediaItemIndex",
                                "getPlaybackState",
                                "getPlayerError",
                                "addListener",
                                "pause",
                                "stop",
                                "prepare",
                                "play"));
        assertTrue(fake.calls.toString(), allowed.containsAll(fake.calls));
    }

    @Test
    public void aPlayerIsWatchedOnceAndForgottenWhenItGoesAway() throws Exception {
        Fake fake = new Fake(LOGICAL);
        Player player = fake.player();
        YouTubePolicy.observe(player);
        YouTubePolicy.observe(player);
        assertEquals(1, YouTubePolicy.watched());
        assertEquals(1, fake.listeners.size());
        player = null;
        fake = null;
        for (int i = 0; i < 40 && YouTubePolicy.watched() > 0; i++) {
            System.gc();
            Thread.sleep(25);
        }
        assertEquals(0, YouTubePolicy.watched());
        Host.saved[0] = false;
        publish();
    }

    @Test
    public void aChangeFromAnotherThreadActsOnThePlayersOwnThread() throws Exception {
        Fake fake = watching(LOGICAL, "YOUTUBE");
        Host.saved[0] = false;
        Thread other = new Thread(YouTubePolicy::sourcesChanged);
        other.start();
        other.join();
        assertEquals("queued, not run on the caller's thread", 0, fake.pauses);
        idle();
        assertEquals(1, fake.pauses);
    }

    @Test
    public void aPlayerBuiltOnAnotherThreadIsWatchedOnItsOwnThread() throws Exception {
        Fake fake = new Fake(LOGICAL);
        host.active = "YOUTUBE";
        Thread other = new Thread(() -> YouTubePolicy.observe(fake.player()));
        other.start();
        other.join();
        assertEquals(0, fake.listeners.size());
        idle();
        assertEquals(1, fake.listeners.size());
    }

    @Test
    public void eachTimeStartsFromTheSavedPickAndTheOtherChoiceWins() throws Exception {
        turnOff(1);
        host.setEnabled("youtube", true);
        open(YouTubePolicy::playbackGate);
        open(YouTubePolicy::catalogueGate);
        AlertDialog dialog = ask();
        assertEquals(1, dialog.getListView().getCheckedItemPosition());
        org.robolectric.Shadows.shadowOf(dialog).clickOnItem(0);
        press(dialog, DialogInterface.BUTTON_POSITIVE);
        assertEquals(YouTubePolicy.PLAYBACK_ONLY, YouTubePolicy.mode());
        open(YouTubePolicy::catalogueGate);
        blocked(YouTubePolicy::playbackGate);
    }

    @Test
    public void turningItBackOnRestoresEverything() throws Exception {
        turnOff(1);
        host.setEnabled("youtube", true);
        open(YouTubePolicy::playbackGate);
        open(YouTubePolicy::catalogueGate);
    }

    @Test
    public void thePickAndTheOffStateSurviveARestart() {
        turnOff(1);
        YouTubePolicy.reset();
        YouTubePolicy.use(new Host());
        YouTubePolicy.init(RuntimeEnvironment.getApplication());
        YouTubePolicy.loaded();
        assertFalse(Host.saved[0]);
        assertEquals(YouTubePolicy.FULL, YouTubePolicy.mode());
        blocked(YouTubePolicy::catalogueGate);
        blocked(YouTubePolicy::playbackGate);
    }

    @Test
    public void rendersAndReopensNeverFlipTheSwitch() {
        ask().dismiss();
        idle();
        ask().dismiss();
        idle();
        assertTrue(Host.saved[0]);
        YouTubePolicy.init(RuntimeEnvironment.getApplication());
        assertTrue(Host.saved[0]);
    }

    @Test
    public void anUnreadableRowBlocksNothing() throws Exception {
        host.broken = true;
        open(YouTubePolicy::playbackGate);
        open(YouTubePolicy::catalogueGate);
    }

    @Test
    public void aCallWithoutAScreenIsLeftOff() {
        YouTubePolicy.reset();
        YouTubePolicy.use(host);
        host.setEnabled("youtube", false);
        assertTrue("no question, no change", Host.saved[0]);
    }

    @Test
    public void refusalsNameNeitherTracksNorAccounts() {
        turnOff(1);
        try {
            YouTubePolicy.playbackGate();
            fail();
        } catch (IOException e) {
            assertEquals("YouTube Music is turned off", e.getMessage());
            assertNull(e.getCause());
        }
    }

    // ── Playing other sources while YouTube Music is off ───────────────────

    private static final String WATCH = "bitchord://watch?v=abc123&n=Song&a=Artist&d=200";

    private Fake offWith(String uri) {
        Fake fake = watching(uri, "YOUTUBE");
        Host.saved[0] = false;
        return fake;
    }

    private static void failed(Player.Listener l, Throwable cause) {
        try {
            l.getClass()
                    .getMethod("onPlayerError", PlaybackException.class)
                    .invoke(
                            l,
                            new PlaybackException(
                                    "failed", cause, PlaybackException.ERROR_CODE_IO_UNSPECIFIED));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void error(Fake fake, Throwable cause) {
        for (Player.Listener l : new ArrayList<>(fake.listeners)) failed(l, cause);
        idle();
    }

    private static int toasts() {
        idle();
        return ShadowToast.shownToastCount();
    }

    private static void settle(int toasts) throws InterruptedException {
        for (int i = 0; i < 100 && ShadowToast.shownToastCount() < toasts; i++) {
            idle();
            Thread.sleep(20);
        }
        idle();
    }

    private static final IOException NONE = new SourceFallback.NoAudio("abc123");

    @Test
    public void aSwitchOffWhilePlayingOnlyPauses() {
        Fake fake = offWith(WATCH);
        publish();
        assertEquals(java.util.Collections.singletonList("pause"), fake.order);
    }

    @Test
    public void aSavedFileReplacesAWatchAddressInPlace() {
        Fake fake = watching(WATCH, "YOUTUBE");
        fake.index = 2;
        Bundle extras = new Bundle();
        extras.putString("bitchord.queueEntryId", "entry-7");
        fake.item =
                new MediaItem.Builder()
                        .setMediaId("abc123")
                        .setUri(WATCH)
                        .setMediaMetadata(new MediaMetadata.Builder().setExtras(extras).build())
                        .build();
        other.saved.put("abc123", "file:///data/music/abc123.m4a");
        Host.saved[0] = false;
        publish();
        assertEquals(
                java.util.Arrays.asList(
                        "replace:2:file:///data/music/abc123.m4a", "seekTo:2:1234", "prepare"),
                fake.order);
        assertEquals("abc123", fake.item.mediaId);
        assertEquals("entry-7", fake.item.mediaMetadata.extras.getString("bitchord.queueEntryId"));
        assertEquals(
                "file:///data/music/abc123.m4a",
                fake.item.mediaMetadata.extras.getString("bitchord.localUri"));
        assertTrue("play intent is not touched", fake.playWhenReady);
        assertEquals(0, fake.pauses);
        assertEquals(0, toasts());
    }

    @Test
    public void aLocalHandoffIsNotAnUnreadableFile() {
        Fake fake = offWith(WATCH);
        String file = "content://media/external/audio/media/819";
        other.saved.put("abc123", file);
        publish();
        assertEquals(file, fake.item.localConfiguration.uri.toString());
        error(fake, new SourceFallback.LocalMatch("abc123", file));
        assertEquals(file, fake.item.localConfiguration.uri.toString());
        assertEquals(file, SourceFallback.cached(fake.item));
        assertTrue(fake.playWhenReady);
    }

    @Test
    public void aLocalFileDoesNotKeepAManifestMimeHint() {
        Fake fake = offWith(WATCH);
        fake.item = fake.item.buildUpon().setMimeType("application/vnd.apple.mpegurl").build();
        other.saved.put("abc123", "file:///data/music/copy.flac");
        publish();
        assertEquals("file", fake.item.localConfiguration.uri.getScheme());
        assertNull(fake.item.localConfiguration.mimeType);
    }

    @Test
    public void localMatchesRecordTheirLocationWithoutReplacingKnownOrigins() {
        AudioOrigin.Host previous = AudioOrigin.host;
        Context app = RuntimeEnvironment.getApplication();
        AudioOrigin.host =
                (AudioOrigin.Host)
                        java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {AudioOrigin.Host.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("context")) return app;
                                    if (method.getName().equals("renditionUri"))
                                        return ((MediaItem) args[0]).localConfiguration.uri;
                                    if (method.getReturnType() == boolean.class) return false;
                                    return null;
                                });
        String file = "content://media/external/audio/media/819";
        try {
            AudioOrigin.forgetSaved("abc123");
            Fake local = offWith(WATCH);
            other.saved.put("abc123", file);
            publish();
            assertEquals(file, local.item.mediaMetadata.extras.getString("bitchord.localUri"));
            AudioOrigin.Source found = AudioOrigin.savedSource("abc123", file);
            assertNotNull(found);
            assertEquals("LOCAL", found.kind);
            assertEquals(Strings.t("On device"), found.name);
            AudioOrigin.recordSaved(
                    "abc123",
                    file,
                    new AudioOrigin.Source(
                            "telegram-native",
                            "Unofficial Telegram",
                            "ADDON",
                            "resolved",
                            "FLAC",
                            null,
                            24,
                            48000));
            Fake saved = offWith(WATCH);
            publish();
            assertEquals(file, saved.item.mediaMetadata.extras.getString("bitchord.localUri"));
            assertEquals("telegram-native", AudioOrigin.savedSource("abc123", file).id);
        } finally {
            AudioOrigin.forgetSaved("abc123");
            AudioOrigin.host = previous;
        }
    }

    @Test
    public void aSavedFileIsNotSwappedInWhileYouTubeIsOn() {
        Fake fake = watching(WATCH, "OTHER");
        other.saved.put("abc123", "file:///data/music/abc123.m4a");
        publish();
        fake.press();
        assertEquals(java.util.Collections.emptyList(), fake.order);
    }

    @Test
    public void aLibraryFileFoundInTheBackgroundReplacesTheWatchAddress() throws Exception {
        other.storage = true;
        other.library.add(
                new SourceFallback.Row(
                        "Song", "Artist", 200, "content://media/external/audio/media/9"));
        Fake fake = watching(WATCH, "YOUTUBE");
        fake.state = Player.STATE_BUFFERING;
        Host.saved[0] = false;
        publish();
        for (int i = 0; i < 100 && fake.order.size() < 4; i++) {
            Thread.sleep(20);
            idle();
        }
        assertEquals(
                java.util.Arrays.asList(
                        "pause",
                        "replace:0:content://media/external/audio/media/9",
                        "seekTo:0:1234",
                        "prepare"),
                fake.order);
    }

    @Test
    public void aPausedDeckReadsNoLibraryWhenYouTubeIsTurnedOff() throws Exception {
        other.storage = true;
        Fake fake = watching(WATCH, "YOUTUBE");
        fake.playWhenReady = false;
        Host.saved[0] = false;
        publish();
        Thread.sleep(100);
        assertEquals(0, other.libraryReads);
        assertEquals(java.util.Collections.emptyList(), fake.order);
    }

    @Test
    public void anUnreadableSavedFileGoesBackToTheWatchAddress() {
        Fake fake = watching(WATCH, "YOUTUBE");
        other.saved.put("abc123", "file:///data/music/abc123.m4a");
        Host.saved[0] = false;
        publish();
        fake.order.clear();
        MediaItem file = fake.item;
        error(fake, new IOException("gone"));
        assertEquals(
                java.util.Arrays.asList("replace:0:" + WATCH, "seekTo:0:1234", "prepare"),
                fake.order);
        assertNull("the unreadable file is not offered again", SourceFallback.cached(fake.item));
        assertEquals(0, toasts());
        assertNotEquals(file, fake.item);
    }

    @Test
    public void nothingAnywhereShowsOneToastForTheCurrentSong() {
        Fake fake = offWith(WATCH);
        publish();
        fake.playWhenReady = true;
        error(fake, new IOException("timed out", NONE));
        assertEquals(1, toasts());
        assertEquals("No audio track found", ShadowToast.getTextOfLatestToast());
        error(fake, new IOException("timed out", NONE));
        assertEquals("the same song again says nothing", 1, toasts());
        for (Player.Listener l : fake.listeners) moved(l, fake.item);
        error(fake, new IOException("timed out", NONE));
        assertEquals("a new turn of the song says it again", 2, toasts());
    }

    @Test
    public void theSameSongQueuedTwiceIsToldApartByItsPlace() {
        Fake fake = offWith(WATCH);
        publish();
        fake.playWhenReady = true;
        error(fake, new IOException(NONE));
        assertEquals(1, toasts());
        fake.index = 1;
        error(fake, new IOException(NONE));
        assertEquals("the second place is its own song", 2, toasts());
        error(fake, new IOException(NONE));
        assertEquals(2, toasts());
    }

    @Test
    public void noToastForAnotherSongAPausedDeckOrAnInterruptedLookup() {
        Fake fake = offWith(WATCH);
        publish();
        fake.playWhenReady = true;
        error(fake, new IOException(new SourceFallback.NoAudio("other")));
        error(fake, new java.io.InterruptedIOException("cancelled"));
        fake.playWhenReady = false;
        error(fake, new IOException(NONE));
        assertEquals(0, toasts());
    }

    @Test
    public void noToastWhenYouTubeMusicIsOn() {
        Fake fake = watching(WATCH, "OTHER");
        error(fake, new IOException(NONE));
        assertEquals(0, toasts());
    }

    @Test
    public void aRefusalAsksTheAlternatesOnceBeforeAnythingIsSaidAndThenSaysNoAudio()
            throws Exception {
        other.ranked.add("a");
        Fake fake = offWith(WATCH);
        publish();
        fake.playWhenReady = true;
        fake.order.clear();
        error(fake, new IOException(new YouTubePolicy.Refused("off")));
        assertEquals(java.util.Arrays.asList("pause", "stop", "prepare", "play"), fake.order);
        assertEquals("not exhausted yet", 0, toasts());
        error(fake, new IOException(new YouTubePolicy.Refused("off")));
        settle(1);
        assertEquals(1, ShadowToast.shownToastCount());
    }

    @Test
    public void aRefusalWithNoAlternateStillLooksInTheLibraryBeforeSayingNoAudio()
            throws Exception {
        other.ranked.clear();
        other.storage = true;
        other.library.add(new SourceFallback.Row("Song", "Artist", 200, "content://m/1"));
        Fake fake = offWith(WATCH);
        error(fake, new IOException(new YouTubePolicy.Refused("off")));
        for (int i = 0; i < 100 && fake.order.size() < 3; i++) {
            Thread.sleep(20);
            idle();
        }
        assertEquals(
                java.util.Arrays.asList("replace:0:content://m/1", "seekTo:0:1234", "prepare"),
                fake.order);
        assertEquals(0, toasts());
    }

    @Test
    public void aSongSkippedWhileTheLookupRunsGetsNoToast() throws Exception {
        other.ranked.clear();
        other.storage = true;
        other.libraryGate = new java.util.concurrent.CountDownLatch(1);
        Fake fake = offWith(WATCH);
        error(fake, new IOException(new YouTubePolicy.Refused("off")));
        fake.index = 1;
        other.libraryGate.countDown();
        Thread.sleep(300);
        idle();
        assertEquals(0, toasts());
    }

    @Test
    public void aRefusalWithNothingAnywhereSaysNoAudio() throws Exception {
        other.ranked.clear();
        Fake fake = offWith(WATCH);
        publish();
        fake.playWhenReady = true;
        error(fake, new IOException(new YouTubePolicy.Refused("off")));
        settle(1);
        assertEquals(1, ShadowToast.shownToastCount());
    }

    @Test
    public void turningYouTubeMusicBackOnClearsEverythingAndStartsNothing() {
        Fake fake = offWith(WATCH);
        publish();
        fake.press();
        int plays = fake.plays;
        Host.saved[0] = true;
        publish();
        error(fake, new IOException(NONE));
        assertEquals(plays, fake.plays);
        assertEquals(0, toasts());
    }

    @Test
    public void aRefusedKeyIsOnlyReplacedWhileYouTubeMusicIsOffAndTheKeyLeadsToYouTube() {
        host.keyOrigin = "YOUTUBE";
        assertFalse("on", YouTubePolicy.refusesKey("abc"));
        Host.saved[0] = false;
        assertTrue("off, cached from YouTube", YouTubePolicy.refusesKey("abc"));
        host.keyOrigin = null;
        assertTrue("off, nothing recorded", YouTubePolicy.refusesKey("abc"));
        host.keyOrigin = "OTHER";
        assertFalse("off, another source's bytes", YouTubePolicy.refusesKey("abc"));
    }

    // ── Nothing else enabled: decided above the cache, before any YouTube request ──

    /** A cache data source that records whether it was ever asked to open. */
    private static final class Source implements java.lang.reflect.InvocationHandler {
        int opens;

        androidx.media3.datasource.DataSource source() {
            return (androidx.media3.datasource.DataSource)
                    java.lang.reflect.Proxy.newProxyInstance(
                            Player.class.getClassLoader(),
                            new Class<?>[] {androidx.media3.datasource.DataSource.class},
                            this);
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
            if (m.getName().equals("open")) {
                opens++;
                return 100L;
            }
            if (m.getName().equals("getUri")) return Uri.parse("https://cdn.example/a.mp3");
            if (m.getName().equals("hashCode")) return 1;
            return null;
        }
    }

    private static Throwable openOnLoader(Source source, String uri) throws Exception {
        java.util.concurrent.atomic.AtomicReference<Throwable> thrown =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread loader =
                new Thread(
                        () -> {
                            try {
                                YouTubePolicy.open(source.source(), new DataSpec(Uri.parse(uri)));
                            } catch (Throwable e) {
                                thrown.set(e);
                            }
                        });
        loader.start();
        loader.join(5000);
        return thrown.get();
    }

    @Test
    public void withNothingEnabledAnExhaustedSongFailsBeforeAnyYouTubeRequest() throws Exception {
        other.ranked.clear();
        other.storage = true;
        Host.saved[0] = false;
        Source source = new Source();
        Throwable error = openOnLoader(source, WATCH);
        assertTrue(String.valueOf(error), error instanceof SourceFallback.NoAudio);
        assertEquals("abc123", ((SourceFallback.NoAudio) error).mediaId);
        assertEquals("the cache and YouTube were never asked", 0, source.opens);
    }

    @Test
    public void withNothingEnabledASavedFileOrLibraryFileEndsInAMatchNotARequest()
            throws Exception {
        other.ranked.clear();
        other.storage = true;
        Host.saved[0] = false;
        other.saved.put("abc123", "file:///data/abc123.m4a");
        Source source = new Source();
        Throwable error = openOnLoader(source, WATCH);
        assertTrue(String.valueOf(error), error instanceof SourceFallback.LocalMatch);
        assertEquals("file:///data/abc123.m4a", ((SourceFallback.LocalMatch) error).uri);
        other.saved.clear();
        SourceFallback.forget();
        other.library.add(new SourceFallback.Row("Song", "Artist", 200, "content://m/3"));
        error = openOnLoader(source, WATCH);
        assertTrue(String.valueOf(error), error instanceof SourceFallback.LocalMatch);
        assertEquals(0, source.opens);
    }

    @Test
    public void withNothingEnabledVerifiedCacheAndDirectFilesStillOpen() throws Exception {
        other.ranked.clear();
        Host.saved[0] = false;
        Source source = new Source();
        host.origin = "OTHER";
        assertNull(openOnLoader(source, WATCH));
        host.origin = null;
        assertNull(openOnLoader(source, "file:///data/music/a.flac"));
        assertNull(openOnLoader(source, "content://media/external/audio/media/5"));
        assertEquals(3, source.opens);
    }

    @Test
    public void withAnAlternateEnabledTheOpenIsLeftToTheNativePath() throws Exception {
        other.ranked.add("a");
        Host.saved[0] = false;
        Source source = new Source();
        assertNull(openOnLoader(source, WATCH));
        assertEquals(1, source.opens);
    }

    @Test
    public void theMainThreadNeverWaitsForALibraryRead() {
        other.ranked.clear();
        other.storage = true;
        Host.saved[0] = false;
        Source source = new Source();
        try {
            YouTubePolicy.open(source.source(), new DataSpec(Uri.parse(WATCH)));
        } catch (IOException e) {
            fail(e.toString());
        }
        assertEquals(1, source.opens);
        assertEquals(0, other.libraryReads);
    }

    @Test
    public void anExhaustedOpenReachesTheCurrentSongsToastOnlyOnce() throws Exception {
        other.ranked.clear();
        Host.saved[0] = false;
        Fake fake = watching(WATCH, "YOUTUBE");
        publish();
        fake.playWhenReady = true;
        Throwable error = openOnLoader(new Source(), WATCH);
        assertEquals("nothing shown until the player reports it", 0, toasts());
        error(fake, new IOException("failed", error));
        assertEquals(1, toasts());
        error(fake, new IOException("failed", error));
        assertEquals(1, toasts());
    }

    // ── Which occurrence an answer belongs to ──────────────────────────────

    private static MediaItem entry(String uri, String entry) {
        Bundle extras = new Bundle();
        extras.putString("bitchord.queueEntryId", entry);
        return new MediaItem.Builder()
                .setMediaId("abc123")
                .setUri(uri)
                .setMediaMetadata(new MediaMetadata.Builder().setExtras(extras).build())
                .build();
    }

    @Test
    public void aLibraryFileFoundForAReplacedOccurrenceIsNotSwappedIn() throws Exception {
        other.storage = true;
        other.library.add(new SourceFallback.Row("Song", "Artist", 200, "content://m/9"));
        other.libraryGate = new java.util.concurrent.CountDownLatch(1);
        Fake fake = watching(WATCH, "YOUTUBE");
        fake.item = entry(WATCH, "first");
        fake.state = Player.STATE_BUFFERING;
        Host.saved[0] = false;
        publish();
        fake.item = entry(WATCH, "second");
        other.libraryGate.countDown();
        Thread.sleep(300);
        idle();
        assertFalse(fake.order.toString(), fake.order.toString().contains("replace"));
    }

    @Test
    public void aReplacedOccurrenceGetsNoToastFromTheOldOnesLookup() throws Exception {
        other.ranked.clear();
        other.storage = true;
        other.libraryGate = new java.util.concurrent.CountDownLatch(1);
        Fake fake = offWith(WATCH);
        fake.item = entry(WATCH, "first");
        error(fake, new IOException(new YouTubePolicy.Refused("off")));
        fake.item = entry(WATCH, "second");
        other.libraryGate.countDown();
        Thread.sleep(300);
        idle();
        assertEquals(0, toasts());
    }

    @Test
    public void theToastIsCheckedAgainWhenItActuallyRuns() {
        Fake fake = offWith(WATCH);
        publish();
        fake.playWhenReady = true;
        for (Player.Listener l : new ArrayList<>(fake.listeners)) failed(l, new IOException(NONE));
        fake.playWhenReady = false;
        assertEquals("the player stopped meanwhile", 0, toasts());

        Fake again = offWith(WATCH + "&x=1");
        again.playWhenReady = true;
        for (Player.Listener l : new ArrayList<>(again.listeners)) failed(l, new IOException(NONE));
        again.item = entry(WATCH + "&x=1", "other");
        assertEquals("the song was replaced meanwhile", 0, toasts());
    }

    @Test
    public void aSourceChangeBetweenPostingAndShowingCancelsTheToast() {
        Fake fake = offWith(WATCH);
        publish();
        fake.playWhenReady = true;
        for (Player.Listener l : new ArrayList<>(fake.listeners)) failed(l, new IOException(NONE));
        Host.saved[0] = true;
        YouTubePolicy.sourcesChanged();
        Host.saved[0] = false;
        YouTubePolicy.sourcesChanged();
        assertEquals(0, toasts());
    }

    @Test
    public void anErrorFromWhileTheDeckWasPausedIsAnsweredWhenItIsAskedToPlay() {
        Fake fake = offWith(WATCH);
        publish();
        fake.playWhenReady = false;
        fake.playerError =
                new PlaybackException(
                        "failed",
                        new IOException(NONE),
                        PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
        error(fake, new IOException(NONE));
        assertEquals("a paused deck says nothing", 0, toasts());
        fake.press();
        idle();
        assertEquals(1, toasts());
        fake.press();
        idle();
        assertEquals("and only once", 1, toasts());
    }
}

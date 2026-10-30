package app.bitchord.soulseek;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.Button;

import app.bitchord.telegram.PatchStrings;

import kotlin.Unit;
import kotlin.jvm.functions.Function2;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystemClock;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The real source-choice facade never downloads or contacts a service in these tests. */
@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class SoulseekDownloadChoiceTest {
    public static final class Song {
        private final String id, artist, title;

        Song(String id) {
            this(id, "Fixture artist", "Fixture title");
        }

        Song(String id, String artist, String title) {
            this.id = id;
            this.artist = artist;
            this.title = title;
        }

        public String getVideoId() {
            return id;
        }

        public String getArtist() {
            return artist;
        }

        public String getTitle() {
            return title;
        }
    }

    private static final class Original implements Function2<Object, Object, Object> {
        final Object result = new Object();
        int calls;
        Object songs, from;
        boolean fail;

        public Object invoke(Object songs, Object from) {
            calls++;
            this.songs = songs;
            this.from = from;
            if (fail) throw new IllegalStateException("fixture failure");
            return result;
        }
    }

    private Context context;
    private Locale previous;

    @SuppressWarnings("unchecked")
    private Map<String, ?> pending() throws Exception {
        Field field = SoulseekDownloadChoice.class.getDeclaredField("pending");
        field.setAccessible(true);
        return (Map<String, ?>) field.get(null);
    }

    private void clearState() throws Exception {
        pending().clear();
        Field field = SoulseekDownloadChoice.class.getDeclaredField("main");
        field.setAccessible(true);
        ((android.os.Handler) field.get(null)).removeCallbacksAndMessages(null);
        while (shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity() != null) {}
    }

    @Before
    public void prepare() throws Exception {
        previous = Locale.getDefault();
        Locale.setDefault(Locale.ENGLISH);
        context = RuntimeEnvironment.getApplication();
        PatchStrings.refresh(context);
        SoulseekBridge.reset(context);
        DownloadSettings.reset();
        DownloadDestinations.configured = true;
        SoulseekSelectedDownloads.selectedReferences.clear();
        AudioFormatPreferences.save(context, AudioFormatPreferences.defaults());
        shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions(android.Manifest.permission.HIDE_OVERLAY_WINDOWS);
        clearState();
    }

    @After
    public void cleanup() throws Exception {
        clearState();
        SoulseekBridge.instance.work.shutdownNow();
        SoulseekBridge.instance = null;
        Locale.setDefault(previous);
        PatchStrings.refresh(context);
    }

    private Intent offered(Original original, List<?> songs) {
        assertSame(Unit.INSTANCE, SoulseekDownloadChoice.offer(original, songs, null));
        Intent intent = shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
        assertNotNull("eligible manual selection opens an explicit choice", intent);
        assertEquals(
                DownloadExplorerActivity.class.getName(), intent.getComponent().getClassName());
        assertEquals(
                Intent.FLAG_ACTIVITY_NEW_TASK, intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    @Test
    public void eligibleSingletonWaitsForExplicitChoiceThenResumesOriginalOnce() throws Exception {
        Song song = new Song("Abc_12-Xy9Z");
        List<Song> songs = new ArrayList<>(Collections.singletonList(song));
        Original original = new Original();
        Intent intent = offered(original, songs);
        String token = intent.getStringExtra(SoulseekDownloadChoice.EXTRA_CHOICE);
        assertNotNull(token);
        assertTrue(SoulseekDownloadChoice.available(token));
        assertEquals(
                "Fixture artist Fixture title",
                intent.getStringExtra(SoulseekDownloadChoice.EXTRA_QUERY));
        assertEquals(0, original.calls);
        assertEquals(0, SoulseekBridge.get().searches);
        songs.clear();
        assertTrue(SoulseekDownloadChoice.resume(token, context));
        assertEquals(1, original.calls);
        assertNull(original.from);
        assertEquals(Collections.singletonList(song), original.songs);
        assertFalse(SoulseekDownloadChoice.available(token));
        assertFalse(SoulseekDownloadChoice.resume(token, context));
        assertEquals("same continuation is never called twice", 1, original.calls);
    }

    @Test
    public void ownedAndNonYoutubeIdentifiersKeepExactOriginalFlow() throws Exception {
        for (String id :
                Arrays.asList(
                        "src:telegram-native::file",
                        "src:soulseek-native::file",
                        "src:user-addon::file",
                        "local:music/track",
                        "https://media.example/song",
                        "short",
                        "twelve_chars")) {
            Original original = new Original();
            List<Song> songs = Collections.singletonList(new Song(id));
            assertSame(original.result, SoulseekDownloadChoice.offer(original, songs, null));
            assertEquals(id, 1, original.calls);
            assertSame(songs, original.songs);
            assertNull(original.from);
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
        }
        assertTrue(pending().isEmpty());
    }

    @Test
    public void albumsCollectionsRetriesAndDisabledModuleAreNotHeld() throws Exception {
        Original album = new Original();
        List<Song> songs = Arrays.asList(new Song("Abc_12-Xy9Z"), new Song("Abc_12-Xy9Y"));
        Object from = new Object();
        assertSame(album.result, SoulseekDownloadChoice.offer(album, songs, null));
        assertSame(songs, album.songs);
        assertEquals(1, album.calls);
        Original collection = new Original();
        List<Song> one = Collections.singletonList(songs.get(0));
        assertSame(collection.result, SoulseekDownloadChoice.offer(collection, one, from));
        assertSame(from, collection.from);
        assertSame(one, collection.songs);
        assertEquals(1, collection.calls);
        DownloadSettings.soulseek = false;
        Original disabled = new Original();
        assertSame(Unit.INSTANCE, SoulseekDownloadChoice.offer(disabled, one, null));
        assertEquals(0, disabled.calls);
        assertEquals(1, pending().size());
        assertNotNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
    }

    @Test
    public void missingBridgeKeepsHostUsableAndReturnsOriginalValue() throws Exception {
        SoulseekBridge.instance.work.shutdownNow();
        SoulseekBridge.instance = null;
        Original original = new Original();
        List<Song> songs = Collections.singletonList(new Song("Abc_12-Xy9Z"));
        try {
            assertSame(original.result, SoulseekDownloadChoice.offer(original, songs, null));
            assertEquals(1, original.calls);
            assertTrue(pending().isEmpty());
        } finally {
            SoulseekBridge.reset(context);
        }
    }

    @Test
    public void disabledModuleDoesNotRetryAnOriginalCallbackThatThrows() throws Exception {
        DownloadSettings.soulseek = false;
        Original original = new Original();
        original.fail = true;
        Intent intent = offered(original, Collections.singletonList(new Song("Abc_12-Xy9Z")));
        assertEquals(0, original.calls);
        assertFalse(
                SoulseekDownloadChoice.resume(
                        intent.getStringExtra(SoulseekDownloadChoice.EXTRA_CHOICE), context));
        assertEquals(1, original.calls);
        assertTrue(pending().isEmpty());
    }

    @Test
    public void cancelExpiredOrLostProcessNeverFallsBackAutomatically() throws Exception {
        Original cancelled = new Original();
        String token =
                offered(cancelled, Collections.singletonList(new Song("Abc_12-Xy9Z")))
                        .getStringExtra(SoulseekDownloadChoice.EXTRA_CHOICE);
        SoulseekDownloadChoice.forget(token);
        assertFalse(SoulseekDownloadChoice.resume(token, context));
        assertEquals(0, cancelled.calls);

        Original expired = new Original();
        token =
                offered(expired, Collections.singletonList(new Song("Abc_12-Xy9Z")))
                        .getStringExtra(SoulseekDownloadChoice.EXTRA_CHOICE);
        ShadowSystemClock.advanceBy(Duration.ofMinutes(3));
        assertFalse(SoulseekDownloadChoice.available(token));
        assertFalse(SoulseekDownloadChoice.resume(token, context));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, expired.calls);

        Original lost = new Original();
        token =
                offered(lost, Collections.singletonList(new Song("Abc_12-Xy9Z")))
                        .getStringExtra(SoulseekDownloadChoice.EXTRA_CHOICE);
        pending().clear(); // A recreated process cannot recover a live UI callback.
        assertFalse(SoulseekDownloadChoice.available(token));
        assertFalse(SoulseekDownloadChoice.resume(token, context));
        assertEquals(0, lost.calls);
    }

    @Test
    public void pendingChoicesAreBoundedAndRefusalDoesNotStartOriginalDownload() throws Exception {
        List<Original> originals = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Original original = new Original();
            originals.add(original);
            tokens.add(
                    offered(original, Collections.singletonList(new Song("Abc_12-Xy9Z")))
                            .getStringExtra(SoulseekDownloadChoice.EXTRA_CHOICE));
        }
        assertEquals(8, pending().size());
        Original full = new Original();
        assertSame(
                Unit.INSTANCE,
                SoulseekDownloadChoice.offer(
                        full, Collections.singletonList(new Song("Abc_12-Xy9Z")), null));
        assertEquals(0, full.calls);
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
        assertEquals(8, pending().size());
        for (Original original : originals) assertEquals(0, original.calls);
        SoulseekDownloadChoice.forget(tokens.get(0));
        offered(full, Collections.singletonList(new Song("Abc_12-Xy9Z")));
        assertEquals(8, pending().size());
        assertEquals(0, full.calls);
    }

    @Test
    public void activityLaunchFailureCancelsPendingChoiceWithoutAutomaticOriginal()
            throws Exception {
        Context refusesActivities =
                new ContextWrapper(context) {
                    @Override
                    public Context getApplicationContext() {
                        return this;
                    }

                    @Override
                    public void startActivity(Intent intent) {
                        throw new IllegalStateException("fixture launch refused");
                    }
                };
        SoulseekBridge.reset(refusesActivities);
        Original original = new Original();
        assertSame(
                Unit.INSTANCE,
                SoulseekDownloadChoice.offer(
                        original, Collections.singletonList(new Song("Abc_12-Xy9Z")), null));
        assertEquals(0, original.calls);
        assertTrue(pending().isEmpty());
    }

    @Test
    public void originalFailureConsumesOneChoiceAndNeverRetriesIt() throws Exception {
        Original original = new Original();
        original.fail = true;
        String token =
                offered(original, Collections.singletonList(new Song("Abc_12-Xy9Z")))
                        .getStringExtra(SoulseekDownloadChoice.EXTRA_CHOICE);
        assertFalse(SoulseekDownloadChoice.resume(token, context));
        assertEquals(1, original.calls);
        assertFalse(SoulseekDownloadChoice.resume(token, context));
        assertEquals(1, original.calls);
    }

    @Test
    public void originalScreenButtonResumesOnlyTheCapturedOriginalCallback() throws Exception {
        Original original = new Original();
        Song song = new Song("Abc_12-Xy9Z");
        Intent intent = offered(original, Collections.singletonList(song));
        ActivityController<SoulseekSearchActivity> controller =
                Robolectric.buildActivity(SoulseekSearchActivity.class, intent).setup();
        try {
            SoulseekSearchActivity activity = controller.get();
            Button button =
                    activity.findViewById(android.R.id.content)
                            .findViewWithTag("soulseek-choice-original");
            assertNotNull(button);
            assertEquals(0, original.calls);
            button.performClick();
            assertEquals(1, original.calls);
            assertEquals(Collections.singletonList(song), original.songs);
            assertNull(original.from);
            assertFalse(
                    SoulseekDownloadChoice.available(
                            intent.getStringExtra(SoulseekDownloadChoice.EXTRA_CHOICE)));
            assertEquals(0, SoulseekBridge.get().searches);
            assertTrue(SoulseekSelectedDownloads.selectedReferences.isEmpty());
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test
    public void emptyOrFailedSoulseekSearchNeedsExplicitReturnAndOriginalChoice() throws Exception {
        for (boolean failure : new boolean[] {false, true}) {
            SoulseekBridge bridge = SoulseekBridge.get();
            bridge.searchFailure = failure;
            Original original = new Original();
            Intent intent = offered(original, Collections.singletonList(new Song("Abc_12-Xy9Z")));
            String token = intent.getStringExtra(SoulseekDownloadChoice.EXTRA_CHOICE);
            ActivityController<SoulseekSearchActivity> controller =
                    Robolectric.buildActivity(SoulseekSearchActivity.class, intent).setup();
            try {
                SoulseekSearchActivity activity = controller.get();
                View root = activity.findViewById(android.R.id.content);
                View form = root.findViewWithTag("soulseek-search-form");
                assertEquals(View.GONE, form.getVisibility());
                View choose = root.findViewWithTag("soulseek-choice-search");
                choose.performClick();
                assertTrue(SoulseekDownloadChoice.available(token));
                assertEquals(View.VISIBLE, form.getVisibility());
                assertEquals(0, original.calls);
                Button submit = root.findViewWithTag("soulseek-search-submit");
                long deadline =
                        System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                do {
                    shadowOf(Looper.getMainLooper()).idle();
                    if (submit.isEnabled()) break;
                    Thread.sleep(5);
                } while (System.nanoTime() < deadline);
                assertTrue("search must reach an actionable finished state", submit.isEnabled());
                assertEquals(0, original.calls);
                assertTrue(SoulseekSelectedDownloads.selectedReferences.isEmpty());
                assertTrue(SoulseekDownloadChoice.available(token));
                assertEquals(0, original.calls);
                root.findViewWithTag("soulseek-search-choose-source").performClick();
                assertEquals(View.GONE, form.getVisibility());
                Button resume = root.findViewWithTag("soulseek-choice-original");
                assertTrue(resume.isShown());
                assertTrue(resume.isEnabled());
                assertEquals("source-picker navigation alone cannot download", 0, original.calls);
                resume.performClick();
                assertEquals(1, original.calls);
                assertFalse(SoulseekDownloadChoice.available(token));
            } finally {
                controller.pause().stop().destroy();
            }
            assertEquals(
                    "only the explicit original action invokes continuation", 1, original.calls);
        }
    }

    @Test
    public void closingAnUnchosenScreenCancelsOriginalWithoutDownloading() throws Exception {
        Original original = new Original();
        Intent intent = offered(original, Collections.singletonList(new Song("Abc_12-Xy9Z")));
        String token = intent.getStringExtra(SoulseekDownloadChoice.EXTRA_CHOICE);
        ActivityController<SoulseekSearchActivity> controller =
                Robolectric.buildActivity(SoulseekSearchActivity.class, intent).setup();
        assertTrue(SoulseekDownloadChoice.available(token));
        controller.pause().stop().destroy();
        assertFalse(SoulseekDownloadChoice.available(token));
        assertFalse(SoulseekDownloadChoice.resume(token, context));
        assertEquals(0, original.calls);
        assertTrue(SoulseekSelectedDownloads.selectedReferences.isEmpty());
    }
}

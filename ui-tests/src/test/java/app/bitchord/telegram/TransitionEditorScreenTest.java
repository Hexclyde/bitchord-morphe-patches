package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.*;
import android.widget.*;

import androidx.media3.common.*;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

import java.lang.reflect.*;
import java.util.*;

@RunWith(RobolectricTestRunner.class)
@Config(
        manifest = Config.NONE,
        sdk = 35,
        qualifiers = "w393dp-h852dp-night-mdpi",
        shadows = TransitionEditorScreenTest.Installed.class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TransitionEditorScreenTest {
    @Implements(value = SourceAutomix.class, isInAndroidSdk = false)
    public static class Installed {
        @Implementation
        public static boolean applied() {
            return true;
        }
    }

    public static final class Analysis {
        public String status = "ready", key = "F minor";
        public double bpm = 130, firstBeat = .05, beatConfidence = .9, keyConfidence = .85;
        public Double audibleStartTime = .25;
        public double introEndTime = 16, contentEndTime = 180;
        public double outroStartTime = 164, vocalProbability = .7;
        public List<Double> vocalActivityMask = Arrays.asList(.1, .8, .7);
        public List<Energy> energyCurve =
                Arrays.asList(new Energy(0, .1), new Energy(90, .8), new Energy(180, .2));
    }

    public static final class Energy {
        public double time, energy;

        Energy(double time, double energy) {
            this.time = time;
            this.energy = energy;
        }
    }

    public enum Style {
        EQUAL_POWER
    }

    public static final class Plan {
        public boolean blocked, shouldStart, markerVisible, bassSwap, phaseLock = true;
        public double fadeSeconds = 8,
                transitionEnd = 180,
                transitionStart = 172,
                incomingCueTime,
                incomingHandoffTime = 4,
                filterSweep,
                vocalOverlap,
                echoSeconds,
                incomingPlaybackRate = 1.1,
                outgoingPlaybackRate = .9;
        public String reason;
        public Style transitionStyle = Style.EQUAL_POWER;
    }

    public static final class Analyzer {
        public Map<String, Object> results = new HashMap<>();
    }

    public static final class Control {
        public boolean isTransitioning() {
            return false;
        }
    }

    public static final class Service {
        public Object player;
        public Analyzer trackAnalyzer = new Analyzer();
        public Control crossfade = new Control();
    }

    private org.robolectric.android.controller.ActivityController<TransitionEditorActivity>
            activity;
    private Service service;
    private int index;
    private long position = 12000;
    private boolean playing;
    private PlaybackParameters speed = new PlaybackParameters(1.25f, .95f);
    private final List<String> calls = new ArrayList<>();
    private MediaItem[] items;
    private final List<AutoMixTestDeck> previewDecks = new ArrayList<>();
    private TransitionPreview.Factory previousFactory;

    @Before
    public void setup() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        Field appField = SoundExtras.class.getDeclaredField("app");
        appField.setAccessible(true);
        appField.set(null, app);
        TransitionStore.closeForTest();
        app.deleteDatabase(TransitionStore.DATABASE);
        app.getSharedPreferences(TransitionRuntime.PREFERENCES, 0).edit().clear().commit();
        app.getSharedPreferences("bitchord_automix_editor", 0).edit().clear().commit();
        previousFactory = TransitionPreview.factory;
        TransitionPreview.factory =
                (owner, deck) -> {
                    AutoMixTestDeck player = new AutoMixTestDeck();
                    previewDecks.add(player);
                    return player;
                };
        items =
                new MediaItem[] {
                    new MediaItem.Builder()
                            .setMediaId("local:midnight")
                            .setUri("content://audio/1")
                            .setMediaMetadata(
                                    new MediaMetadata.Builder()
                                            .setTitle("Midnight Drive")
                                            .setArtist("Noround")
                                            .build())
                            .build(),
                    new MediaItem.Builder()
                            .setMediaId("telegram:open-skies")
                            .setUri("bitchord://source/telegram/2")
                            .setMediaMetadata(
                                    new MediaMetadata.Builder()
                                            .setTitle("Open Skies")
                                            .setArtist("Noround")
                                            .build())
                            .build()
                };
        service = new Service();
        service.player =
                Proxy.newProxyInstance(
                        Player.class.getClassLoader(),
                        new Class<?>[] {Player.class},
                        (proxy, method, args) -> {
                            switch (method.getName()) {
                                case "getCurrentMediaItemIndex":
                                    return index;
                                case "getNextMediaItemIndex":
                                    return index + 1 < items.length ? index + 1 : -1;
                                case "getCurrentMediaItem":
                                    return items[index];
                                case "getMediaItemAt":
                                    return items[(Integer) args[0]];
                                case "getMediaItemCount":
                                    return items.length;
                                case "getDuration":
                                    return 180000L;
                                case "getCurrentPosition":
                                    return position;
                                case "getVolume":
                                    return 1f;
                                case "getPlayWhenReady":
                                    return playing;
                                case "getPlaybackParameters":
                                    return speed;
                                case "seekTo":
                                    calls.add("seek");
                                    index = (Integer) args[0];
                                    position = (Long) args[1];
                                    return null;
                                case "pause":
                                    calls.add("pause");
                                    playing = false;
                                    return null;
                                case "play":
                                    calls.add("play");
                                    playing = true;
                                    return null;
                                case "setPlaybackParameters":
                                    calls.add("speed");
                                    speed = (PlaybackParameters) args[0];
                                    return null;
                                case "setPlayWhenReady":
                                    playing = (Boolean) args[0];
                                    return null;
                                case "equals":
                                    return proxy == args[0];
                                case "hashCode":
                                    return System.identityHashCode(proxy);
                                case "toString":
                                    return "Editor player fixture";
                            }
                            if (method.getReturnType() == boolean.class) return false;
                            if (method.getReturnType() == int.class) return 0;
                            if (method.getReturnType() == long.class) return 0L;
                            if (method.getReturnType() == float.class) return 0f;
                            return null;
                        });
        service.trackAnalyzer.results.put(items[0].mediaId, new Analysis());
        service.trackAnalyzer.results.put(items[1].mediaId, new Analysis());
        SourceAutomix.service(service);
        com.music.bitchord.playback.PlaybackService.selectedSpeed = 1.25f;
        com.music.bitchord.data.settings.AppSettings.lastPlayerScreen.setValue("MAIN");
        VideoSwitch.enabled = false;
        com.music.bitchord.playback.PlaybackService.cached =
                uri ->
                        new android.media.MediaDataSource() {
                            public int readAt(long at, byte[] bytes, int offset, int length) {
                                return -1;
                            }

                            public long getSize() {
                                return 100;
                            }

                            public void close() {}
                        };
        com.music.bitchord.playback.PlaybackService.waveDecoder =
                (source, from, to, abort, chunks) -> {
                    // PCM fixture follows the requested source interval; production never generates
                    // audio.
                    int rate = 8000, frames = (int) Math.ceil((to - from) * rate);
                    java.nio.ByteBuffer pcm =
                            java.nio.ByteBuffer.allocate(frames * 4)
                                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    for (int i = 0; i < frames; i++) {
                        double envelope = .25 + .2 * Math.sin(i / 811d);
                        short sample =
                                (short)
                                        (16000
                                                * envelope
                                                * (Math.sin(i * .063)
                                                        + .6 * Math.sin(i * .63)
                                                        + .3 * Math.sin(i * 1.9)));
                        pcm.putShort(sample).putShort((short) -sample);
                    }
                    pcm.flip();
                    android.media.MediaCodec.BufferInfo info =
                            new android.media.MediaCodec.BufferInfo();
                    info.set(0, pcm.remaining(), Math.round(from * 1_000_000), 0);
                    if (abort.invoke()) return null;
                    chunks.invoke(pcm.asReadOnlyBuffer(), info, 2, rate);
                    return new Object();
                };
    }

    @After
    public void close() throws Exception {
        if (activity != null) activity.pause().stop().destroy();
        TransitionRuntime.release();
        SourceAutomix.release(service);
        TransitionPreview.factory = previousFactory;
        TransitionStore.closeForTest();
        RuntimeEnvironment.setFontScale(1f);
        com.music.bitchord.playback.PlaybackService.waveDecoder = null;
        com.music.bitchord.playback.PlaybackService.cached = null;
    }

    private void awaitWaves(Field jobs, TransitionEditorActivity page) throws Exception {
        for (java.util.concurrent.Future<?> pending :
                (java.util.concurrent.Future<?>[]) jobs.get(page))
            if (pending != null) pending.get(10, java.util.concurrent.TimeUnit.SECONDS);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    private TransitionEditorActivity open() throws Exception {
        activity = Robolectric.buildActivity(TransitionEditorActivity.class).setup();
        TransitionEditorActivity page = activity.get();
        Field jobs = TransitionEditorActivity.class.getDeclaredField("waveJobs");
        jobs.setAccessible(true);
        awaitWaves(jobs, page);
        databaseIdle();
        awaitWaves(jobs, page);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        layout(393, 852);
        return page;
    }

    @Test
    public void analysisIsAccessibleFromTheExpandedAndPinnedLayouts() throws Exception {
        open();
        View button = tag("automix-analysis");
        assertEquals(View.VISIBLE, button.getVisibility());
        assertTrue(button.getHeight() >= 48);
        button.performClick();
        var dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(dialog.isShowing());
        assertNotNull(
                dialog.getWindow().getDecorView().findViewWithTag("automix-analysis-energy:0"));
        assertNotNull(
                dialog.getWindow().getDecorView().findViewWithTag("automix-analysis-track:1"));
        View sheet = dialog.getWindow().getDecorView();
        sheet.measure(
                View.MeasureSpec.makeMeasureSpec(375, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(740, View.MeasureSpec.EXACTLY));
        sheet.layout(0, 0, 375, 740);
        Bitmap image = Bitmap.createBitmap(375, 740, Bitmap.Config.ARGB_8888);
        sheet.draw(new Canvas(image));
        java.io.File file = new java.io.File("target/previews/automix-analysis-details.png");
        file.getParentFile().mkdirs();
        try (java.io.FileOutputStream stream = new java.io.FileOutputStream(file)) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream));
        }
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        tag("transition-tab:3").performClick();
        layout(393, 852);
        assertEquals(View.VISIBLE, button.getVisibility());
        button.performClick();
        assertTrue(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().isShowing());
        render("automix-editor-analysis-entry.png", 393, 852);
    }

    @Test
    public void analysisRefreshesWithoutChangingOrApplyingTheDraft() throws Exception {
        open();
        String before = edit().encode();
        tag("automix-analysis").performClick();
        Analysis result = (Analysis) service.trackAnalyzer.results.get(items[0].mediaId);
        result.bpm = 144;
        Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(1100));
        assertTrue(((TextView) tag("automix-analysis")).getText().toString().contains("144"));
        assertEquals(before, edit().encode());
        assertNull(TransitionRuntime.load(TransitionRuntime.pair()));
        assertFalse(calls.contains("seek"));
    }

    @Test
    public void previewSetupFailureLeavesRetryAndTheListeningSnapshotUsable() throws Exception {
        playing = true;
        open();
        TransitionPreview.factory =
                (owner, deck) -> {
                    throw new java.io.IOException("source failed");
                };
        tag("transition-preview").performClick();
        assertTrue(tag("transition-preview").isEnabled());
        assertFalse(playing);
        assertEquals(12000, position);
        assertFalse(calls.contains("seek"));
        activity.get().finish();
        activity.pause().stop().destroy();
        activity = null;
        assertTrue(playing);
    }

    private void databaseIdle() throws Exception {
        for (int i = 0; i < 3; i++) {
            TransitionStore.get().awaitIdle();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        }
    }

    private void advancePreview() {
        Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(40));
    }

    @Test
    public void aColdPlayerRecallsTheStoredPairAtADifferentQueuePosition() throws Exception {
        TransitionEdit edit = new TransitionEdit();
        edit.incomingCue = 7;
        edit.preset(TransitionEdit.MELT);
        TransitionRuntime.save(TransitionRuntime.pair(), edit);
        databaseIdle();
        TransitionStore.closeForTest();
        TransitionRuntime.release();
        items =
                new MediaItem[] {
                    new MediaItem.Builder().setMediaId("earlier-song").build(), items[0], items[1]
                };
        index = 1;
        TransitionRuntime.bind(service.crossfade);
        assertTrue(TransitionRuntime.holdPlanner(service.crossfade));
        databaseIdle();
        assertFalse(TransitionRuntime.holdPlanner(service.crossfade));
        Plan plan = new Plan();
        SourceAutomix.plan(plan);
        assertEquals(7, plan.incomingCueTime, 0);
        TransitionRuntime.arm(service.crossfade, plan);
        assertEquals(edit.gain(0, .4), TransitionRuntime.outgoing(service.crossfade, .4f), 1e-6);
    }

    @Test
    public void losingAuditionFocusStopsBothPrivateDecksAndLeavesListeningPaused()
            throws Exception {
        playing = true;
        TransitionPreview.Session session = TransitionPreview.acquire(TransitionRuntime.pair());
        TransitionPreview.Audition preview = session.start(new TransitionEdit(), () -> {});
        advancePreview();
        previewDecks.get(0).position = 174000;
        advancePreview();
        android.media.AudioManager manager =
                RuntimeEnvironment.getApplication()
                        .getSystemService(android.media.AudioManager.class);
        var request = Shadows.shadowOf(manager).getLastAudioFocusRequest();
        android.media.AudioManager.OnAudioFocusChangeListener listener = request.listener;
        if (listener == null)
            listener =
                    org.robolectric.util.ReflectionHelpers.callInstanceMethod(
                            request.audioFocusRequest, "getOnAudioFocusChangeListener");
        listener.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
        assertTrue(preview.closed);
        for (AutoMixTestDeck deck : previewDecks) assertTrue(deck.closed);
        assertFalse(playing);
        assertEquals(12000, position);
        assertFalse(calls.contains("seek"));
    }

    @Test
    public void deniedAuditionFocusDoesNotStartAnyPrivateOrListeningPlayback() throws Exception {
        playing = true;
        TransitionPreview.Session session = TransitionPreview.acquire(TransitionRuntime.pair());
        android.media.AudioManager manager =
                RuntimeEnvironment.getApplication()
                        .getSystemService(android.media.AudioManager.class);
        Shadows.shadowOf(manager)
                .setNextFocusRequestResponse(android.media.AudioManager.AUDIOFOCUS_REQUEST_FAILED);
        assertThrows(
                IllegalStateException.class, () -> session.start(new TransitionEdit(), () -> {}));
        assertTrue(previewDecks.isEmpty());
        assertFalse(playing);
        assertEquals(12000, position);
        session.close(true);
        assertTrue(playing);
        assertFalse(calls.contains("seek"));
    }

    @Test
    public void auditionFadersAndEffectsUseTheDraftWithoutWritingItToThePlayer() throws Exception {
        playing = true;
        open();
        tag("transition-preset:3").performClick();
        tag("transition-preview").performClick();
        advancePreview();
        previewDecks.get(0).position = 176000;
        advancePreview();
        assertEquals(edit().gain(0, .5), previewDecks.get(0).volume, 1e-6);
        assertEquals(edit().gain(1, .5), previewDecks.get(1).volume, 1e-6);
        assertEquals(2, previewDecks.get(1).effects.filter[1]);
        assertNull(TransitionRuntime.load(TransitionRuntime.pair()));
        assertFalse(playing);
        assertEquals(12000, position);
        assertFalse(calls.contains("seek"));
    }

    @Test
    public void bufferingAndSourceErrorsStopOnlyTheAudition() throws Exception {
        playing = true;
        TransitionPreview.Session session = TransitionPreview.acquire(TransitionRuntime.pair());
        TransitionPreview.Audition preview = session.start(new TransitionEdit(), () -> {});
        previewDecks.get(1).state = Player.STATE_BUFFERING;
        advancePreview();
        // The outgoing lead-in is audible while the incoming deck still loads.
        assertEquals(1, previewDecks.get(0).plays);
        assertFalse(playing);
        previewDecks.get(0).position = 173990;
        advancePreview();
        assertFalse("Both wait when the incoming deck is needed", previewDecks.get(0).playing);
        previewDecks.get(1).state = Player.STATE_READY;
        advancePreview();
        previewDecks.get(0).position = 174000;
        advancePreview();
        assertTrue(previewDecks.get(1).playing);
        previewDecks.get(1).error = new PlaybackException("Source failed", null, 1001);
        advancePreview();
        assertTrue(preview.closed);
        assertFalse(preview.completed);
        assertNotNull(preview.error);
        assertFalse(playing);
        assertEquals(12000, position);
        assertFalse(calls.contains("seek"));
        session.close(true);
        assertTrue(playing);
    }

    @Test
    public void incomingDeckIsNotSoughtAgainWhenItAlreadySitsAtTheCue() throws Exception {
        playing = true;
        TransitionPreview.Session session = TransitionPreview.acquire(TransitionRuntime.pair());
        TransitionEdit start = new TransitionEdit();
        session.start(start, () -> {});
        AutoMixTestDeck incoming = previewDecks.get(1);
        long cue = incoming.position;
        advancePreview();
        previewDecks.get(0).position = Math.round(start.start(180) * 1000) + 20;
        advancePreview();
        assertTrue(incoming.playing);
        assertEquals(cue, incoming.position);
        session.close(true);
    }

    @Test
    public void abandoningAnUnappliedDraftResumesListeningWithoutSavingIt() throws Exception {
        RuntimeEnvironment.getApplication()
                .getSharedPreferences("bitchord_automix_editor", 0)
                .edit()
                .putBoolean("auto_save", false)
                .commit();
        playing = true;
        open();
        tag("transition-preset:3").performClick();
        tag("transition-preview").performClick();
        advancePreview();
        activity.get().onBackPressed();
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
                .getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .performClick();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertTrue(playing);
        assertEquals(12000, position);
        assertFalse(calls.contains("seek"));
        assertNull(TransitionRuntime.load(TransitionRuntime.pair()));
    }

    @Test
    public void anInitiallyPausedSongRemainsPausedAfterApplyingTheDraft() throws Exception {
        open();
        tag("transition-preset:3").performClick();
        tag("transition-preview").performClick();
        advancePreview();
        tag("transition-save").performClick();
        databaseIdle();
        assertFalse(playing);
        assertEquals(12000, position);
        assertNotNull(TransitionRuntime.load(TransitionRuntime.pair()));
    }

    @Test
    public void partialDeckCreationFailureReleasesCreatedAudioAndKeepsTheSnapshot()
            throws Exception {
        playing = true;
        TransitionPreview.Session session = TransitionPreview.acquire(TransitionRuntime.pair());
        TransitionPreview.factory =
                (owner, side) -> {
                    if (side == 1) throw new java.io.IOException("Unavailable decoder");
                    AutoMixTestDeck deck = new AutoMixTestDeck();
                    previewDecks.add(deck);
                    return deck;
                };
        assertThrows(
                java.io.IOException.class, () -> session.start(new TransitionEdit(), () -> {}));
        assertTrue(previewDecks.get(0).closed);
        assertFalse(playing);
        assertEquals(12000, position);
        session.close(true);
        assertTrue(playing);
        assertFalse(calls.contains("seek"));
    }

    private View root() {
        return activity.get().findViewById(android.R.id.content);
    }

    private View tag(String tag) {
        return root().findViewWithTag(tag);
    }

    private void layout(int width, int height) {
        View view = activity.get().getWindow().getDecorView();
        view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    private TransitionEdit edit() throws Exception {
        Field field = TransitionEditorActivity.class.getDeclaredField("edit");
        field.setAccessible(true);
        return (TransitionEdit) field.get(activity.get());
    }

    @Test
    public void playerEntryWorksWithoutTheAudioVideoSwitchAndKeepsLyricsAndQueueClear() {
        var host = Robolectric.buildActivity(Activity.class).setup();
        try {
            PlayerIcons.EditorEntry entry = new PlayerIcons.EditorEntry(host.get());
            host.get().setContentView(entry);
            assertEquals(View.VISIBLE, entry.getVisibility());
            entry.performClick();
            assertEquals(
                    TransitionEditorActivity.class.getName(),
                    Shadows.shadowOf(host.get())
                            .getNextStartedActivity()
                            .getComponent()
                            .getClassName());
            for (String mode : new String[] {"LYRICS", "QUEUE"}) {
                com.music.bitchord.data.settings.AppSettings.lastPlayerScreen.setValue(mode);
                entry.refresh();
                assertEquals(View.GONE, entry.getVisibility());
            }
        } finally {
            host.pause().stop().destroy();
        }
    }

    @Test
    public void presetsAffectSavedPlaybackParametersAndSavingReturnsToThePlayer() throws Exception {
        TransitionEditorActivity page = open();
        tag("transition-preset:3").performClick(); // Rise
        assertEquals(2, edit().filter[1]);
        tag("transition-save").performClick();
        databaseIdle();
        TransitionEdit saved = TransitionRuntime.load(TransitionRuntime.pair());
        assertNotNull(saved);
        assertEquals(TransitionEdit.RISE, saved.preset);
        assertEquals(2, saved.filter[1]);
        assertFalse(page.isFinishing());
        assertEquals(1.25f, speed.speed, 0);
    }

    @Test
    public void exactBarDurationAndCustomPointsSurviveRecreation() throws Exception {
        open();
        tag("transition-preset:2").performClick();
        tag("transition-bars:16").performClick();
        assertEquals(TransitionEdit.secondsForBars(16, 130), edit().overlap, 1e-9);
        assertEquals(16, edit().bars);
        String before = edit().encode();
        activity.recreate();
        assertEquals(before, edit().encode());
    }

    @Test
    public void aChangedQueueDuringPreviewDoesNotPauseOrSeekTheNewMusic() throws Exception {
        playing = true;
        TransitionPreview.Session session = TransitionPreview.acquire(TransitionRuntime.pair());
        TransitionPreview.Audition preview = session.start(new TransitionEdit(), () -> {});
        advancePreview();
        calls.clear();
        items[1] = new MediaItem.Builder().setMediaId("replacement").build();
        advancePreview();
        assertTrue(preview.closed);
        assertTrue(session.closed);
        assertTrue(calls.isEmpty());
        assertEquals(1.25f, speed.speed, 0);
        assertEquals(.95f, speed.pitch, 0);
    }

    @Test
    public void toolsEditRecallSaveReopenAndDriveTheRealPlannerAndFaders() throws Exception {
        open();
        tag("transition-tab:3").performClick();
        tag("transition-grid:1").performClick();
        assertEquals(.005, edit().gridOffset[0], 1e-9);
        tag("transition-jump:-4").performClick();
        double markedEnd = 180 - edit().outgoingTrim;
        tag("transition-cue:0").performClick();
        assertEquals(markedEnd, edit().cues[0][0], 1e-9);
        tag("transition-jump:-4").performClick();
        tag("transition-cue:0").performClick();
        assertEquals(markedEnd, 180 - edit().outgoingTrim, 1e-9);
        edit().curve = TransitionEdit.DJ;
        edit().faderSharpness = 4;
        edit().eq[1][0] = -3;
        tag("transition-save").performClick();
        databaseIdle();
        TransitionRuntime.Pair pair = TransitionRuntime.pair();
        TransitionEdit saved = TransitionRuntime.load(pair);
        assertEquals(4, saved.faderSharpness, 0);
        assertEquals(-3, saved.eq[1][0], 0);
        assertEquals(.005, saved.gridOffset[0], 1e-9);
        Plan plan = new Plan();
        TransitionRuntime.bind(service.crossfade);
        SourceAutomix.plan(plan);
        assertEquals(saved.start(180), plan.transitionStart, 1e-9);
        assertEquals(saved.incomingCue, plan.incomingCueTime, 1e-9);
        assertEquals(1, plan.incomingPlaybackRate, 0);
        assertEquals(1, plan.outgoingPlaybackRate, 0);
        assertFalse(plan.phaseLock);
        TransitionRuntime.arm(service.crossfade, plan);
        assertEquals(saved.gain(1, .2), TransitionRuntime.incoming(service.crossfade, .2f), 1e-6);
        assertEquals(saved.gain(0, .2), TransitionRuntime.outgoing(service.crossfade, .2f), 1e-6);
        assertEquals(1.25f, speed.speed, 0);
        activity.pause().stop().destroy();
        activity = null;
        open();
        assertEquals(saved.encode(), edit().encode());
    }

    @Test
    public void aDifferentRenditionWithTheSameMediaIdCannotInheritOrSaveTheOldTiming()
            throws Exception {
        TransitionRuntime.Pair original = TransitionRuntime.pair();
        TransitionRuntime.save(original, new TransitionEdit());
        databaseIdle();
        items[1] = items[1].buildUpon().setUri("content://a-different-master/2").build();
        assertNull(TransitionRuntime.load(TransitionRuntime.pair()));
        assertThrows(
                IllegalArgumentException.class,
                () -> TransitionRuntime.save(original, new TransitionEdit()));
    }

    @Test
    public void recreatingAQueueEntryForTheSameRenditionKeepsItsSavedEdit() throws Exception {
        items[1] =
                items[1].buildUpon()
                        .setUri("content://audio/2?" + SourcePreparation.ENTRY + "=first")
                        .build();
        TransitionRuntime.save(TransitionRuntime.pair(), new TransitionEdit());
        databaseIdle();
        items[1] =
                items[1].buildUpon()
                        .setUri("content://audio/2?" + SourcePreparation.ENTRY + "=second")
                        .build();
        assertNotNull(TransitionRuntime.load(TransitionRuntime.pair()));
    }

    @Test
    public void stoppingPreviewLeavesTheSongPausedAndRetainsAnExplicitSpeedChange()
            throws Exception {
        playing = true;
        TransitionPreview.Session session = TransitionPreview.acquire(TransitionRuntime.pair());
        TransitionPreview.Audition preview = session.start(new TransitionEdit(), () -> {});
        advancePreview();
        speed = new PlaybackParameters(1.5f, .95f);
        com.music.bitchord.playback.PlaybackService.selectedSpeed = 1.5f;
        preview.stop();
        assertTrue(preview.closed);
        assertFalse(playing);
        assertEquals(12000, position);
        assertEquals(1.5f, speed.speed, 0);
        session.close(true);
        assertTrue(playing);
        assertEquals(.95f, speed.pitch, 0);
        assertFalse(calls.contains("seek"));
    }

    @Test
    public void naturalPreviewCompletionNeverAdvancesTheRealQueue() throws Exception {
        playing = true;
        TransitionPreview.Session session = TransitionPreview.acquire(TransitionRuntime.pair());
        TransitionPreview.Audition preview = session.start(new TransitionEdit(), () -> {});
        advancePreview();
        assertEquals(2, previewDecks.size());
        previewDecks.get(0).position = 172000;
        advancePreview();
        previewDecks.get(0).position = 180000;
        advancePreview();
        assertTrue(preview.completed);
        assertTrue(preview.closed);
        assertEquals(0, index);
        assertEquals(12000, position);
        assertFalse(playing);
        for (AutoMixTestDeck deck : previewDecks) {
            assertTrue(deck.closed);
            assertEquals(0, deck.repeats);
            assertFalse(deck.shuffled);
        }
        assertFalse(calls.contains("seek"));
        session.close(true);
        assertTrue(playing);
    }

    @Test
    public void repeatedPreviewKeepsTheListeningSongPausedBetweenPasses() throws Exception {
        playing = true;
        open();
        tag("transition-tab:3").performClick();
        tag("transition-repeat").performClick();
        tag("transition-preview").performClick();
        advancePreview();
        previewDecks.get(0).position = 172000;
        advancePreview();
        previewDecks.get(0).position = 180000;
        advancePreview();
        Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(400));
        assertEquals(4, previewDecks.size());
        assertFalse(playing);
        assertEquals(0, index);
        assertEquals(12000, position);
        assertFalse(calls.contains("seek"));
        activity.pause().stop();
        Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofSeconds(1));
        int count = previewDecks.size();
        Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofSeconds(1));
        assertEquals(count, previewDecks.size());
        assertFalse(playing);
        for (AutoMixTestDeck deck : previewDecks) assertTrue(deck.closed);
    }

    @Test
    public void draftPreviewDoesNotCommitAndApplyResumesTheExactPausedSong() throws Exception {
        playing = true;
        open();
        tag("transition-preset:3").performClick();
        tag("transition-preview").performClick();
        advancePreview();
        assertFalse(playing);
        assertEquals(12000, position);
        assertEquals(0, index);
        assertEquals(items[0], previewDecks.get(0).item);
        assertEquals(items[1], previewDecks.get(1).item);
        assertEquals(1.25f, previewDecks.get(1).parameters.speed, 0);
        assertNull(TransitionRuntime.load(TransitionRuntime.pair()));
        tag("transition-preview").performClick();
        assertFalse(playing);
        assertNull(TransitionRuntime.load(TransitionRuntime.pair()));
        tag("transition-save").performClick();
        databaseIdle();
        assertTrue(playing);
        assertEquals(12000, position);
        assertEquals(0, index);
        assertFalse(calls.contains("seek"));
        TransitionEdit saved = TransitionRuntime.load(TransitionRuntime.pair());
        assertNotNull(saved);
        assertEquals(TransitionEdit.RISE, saved.preset);
        TransitionStore.closeForTest();
        TransitionRuntime.load(TransitionRuntime.pair());
        databaseIdle();
        Plan plan = new Plan();
        TransitionRuntime.bind(service.crossfade);
        SourceAutomix.plan(plan);
        assertEquals(saved.overlap, plan.fadeSeconds, 0);
        assertEquals(saved.incomingCue, plan.incomingCueTime, 0);
        TransitionRuntime.arm(service.crossfade, plan);
        assertEquals(saved.gain(1, .4), TransitionRuntime.incoming(service.crossfade, .4f), 1e-6);
    }

    @Test
    public void rotationStopsPrivateDecksAndKeepsTheOriginalPauseSnapshot() throws Exception {
        playing = true;
        open();
        tag("transition-preview").performClick();
        advancePreview();
        activity.recreate();
        databaseIdle();
        assertFalse(playing);
        assertEquals(12000, position);
        assertEquals(0, index);
        for (AutoMixTestDeck deck : previewDecks) assertTrue(deck.closed);
        tag("transition-preset:2").performClick();
        tag("transition-save").performClick();
        databaseIdle();
        assertTrue(playing);
        assertEquals(12000, position);
        assertFalse(calls.contains("seek"));
    }

    @Test
    public void phraseSuggestionIsEditableAndRejectsIncompleteMeasurements() throws Exception {
        open();
        tag("transition-tab:3").performClick();
        tag("transition-suggest").performClick();
        assertTrue(edit().outgoingTrim > 0);
        assertTrue(edit().overlap > 0);
        assertTrue(edit().incomingCue >= .25);
        edit().validate();
        Analysis missing = new Analysis();
        missing.beatConfidence = .1;
        service.trackAnalyzer.results.put(items[1].mediaId, missing);
        String before = edit().encode();
        tag("transition-suggest").performClick();
        assertEquals(before, edit().encode());
        assertEquals(1.25f, speed.speed, 0);
    }

    @Test
    @Config(qualifiers = "w852dp-h393dp-land-night-mdpi")
    public void landscapeKeepsTheTimelineAndControlsAvailable() throws Exception {
        open();
        layout(852, 393);
        render("automix-editor-landscape.png", 852, 393);
        assertTrue(tag("transition-save").getWidth() >= 44);
        assertTrue(tag("transition-preview").getHeight() >= 44);
        int[] location = new int[2];
        tag("transition-track:1").getLocationOnScreen(location);
        assertTrue(location[1] + tag("transition-track:1").getHeight() <= 393);
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-notnight-mdpi")
    public void smallPhoneAndLargeTextKeepSaveAndToolsUsable() throws Exception {
        RuntimeEnvironment.setFontScale(1.5f);
        open();
        tag("transition-tab:3").performClick();
        layout(320, 640);
        render("automix-editor-compact-light.png", 320, 640);
        assertTrue(tag("transition-save").getWidth() >= 44);
        assertTrue(tag("transition-grid:1").isEnabled());
    }

    @Test
    public void autoSavePersistsADraftWithoutApplyingItAndCloseResumesListening() throws Exception {
        playing = true;
        open();
        tag("transition-preset:3").performClick();
        tag("transition-preview").performClick();
        advancePreview();
        assertFalse(playing);
        Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(500));
        databaseIdle();
        assertNull(TransitionRuntime.load(TransitionRuntime.pair()));
        var draft = new java.util.concurrent.atomic.AtomicReference<TransitionStore.Draft>();
        TransitionStore.get().readDraft(TransitionRuntime.pair(), draft::set);
        databaseIdle();
        assertEquals(TransitionEdit.RISE, draft.get().edit.preset);
        assertFalse(draft.get().automatic);
        activity.get().onBackPressed();
        databaseIdle();
        assertTrue(activity.get().isFinishing());
        assertTrue(playing);
        assertEquals(12000, position);
        assertFalse(calls.contains("seek"));
        activity.pause().stop().destroy();
        activity = null;
        TransitionStore.closeForTest();
        open();
        assertEquals(TransitionEdit.RISE, edit().preset);
        assertNull(TransitionRuntime.load(TransitionRuntime.pair()));
    }

    @Test
    public void autoApplyWaitsUntilPreviewEndsThenCommitsAndResumesWithoutClosing()
            throws Exception {
        playing = true;
        open();
        tag("transition-preset:3").performClick();
        tag("transition-preview").performClick();
        advancePreview();
        tag("automix-auto-apply").performClick();
        Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofSeconds(1));
        databaseIdle();
        assertNull(TransitionRuntime.load(TransitionRuntime.pair()));
        assertFalse(playing);
        previewDecks.get(0).position = 180000;
        advancePreview();
        databaseIdle();
        assertNotNull(TransitionRuntime.load(TransitionRuntime.pair()));
        assertTrue(playing);
        assertEquals(12000, position);
        assertFalse(activity.get().isFinishing());
        assertEquals(1.25f, speed.speed, 0);
    }

    @Test
    public void compactWaveformAndTrackNamesStayPinnedAboveScrollingControls() throws Exception {
        open();
        tag("transition-tab:1").performClick();
        layout(393, 852);
        assertEquals(132, tag("automix-workspace").getHeight());
        assertEquals(View.VISIBLE, tag("automix-compact").getVisibility());
        assertEquals(View.GONE, tag("automix-expanded").getVisibility());
        int[] before = new int[2], after = new int[2];
        tag("automix-mini-timeline").getLocationOnScreen(before);
        ((ScrollView) tag("automix-controls-scroll")).scrollTo(0, 150);
        layout(393, 852);
        tag("automix-mini-timeline").getLocationOnScreen(after);
        assertArrayEquals(before, after);
        render("automix-editor-pinned.png", 393, 852);
        tag("automix-expand").performClick();
        layout(393, 852);
        assertEquals(View.VISIBLE, tag("automix-expanded").getVisibility());
        assertEquals(37, tag("transition-preset:1").getHeight());
    }

    private void compactThroughOptions() throws Exception {
        tag("transition-tab:3").performClick();
        layout(393, 852);
        tag("automix-expand").performClick();
        layout(393, 852);
        ScrollView scroll = (ScrollView) tag("automix-controls-scroll");
        int range = scroll.getChildAt(0).getHeight() - scroll.getHeight();
        assertTrue("The real options have scrollable content", range > 0);
        scroll.scrollTo(0, Math.min(range, 80));
        layout(393, 852);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        layout(393, 852);
        assertEquals(View.VISIBLE, tag("automix-compact").getVisibility());
    }

    private void gesture(View view, float fromX, float fromY, float toX, float toY) {
        int[] actions = {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP};
        for (int i = 0; i < actions.length; i++) {
            MotionEvent event =
                    MotionEvent.obtain(
                            0, 40L * i, actions[i], i == 0 ? fromX : toX, i == 0 ? fromY : toY, 0);
            try {
                assertTrue(
                        "The visible control accepts the gesture", view.dispatchTouchEvent(event));
            } finally {
                event.recycle();
            }
        }
    }

    @Test
    public void scrollingBackRestoresExpandedWaveformsWithoutLayoutOscillation() throws Exception {
        open();
        compactThroughOptions();
        ScrollView scroll = (ScrollView) tag("automix-controls-scroll");
        scroll.scrollTo(0, 0);
        float x = scroll.getWidth() / 2f, y = scroll.getHeight() / 4f;
        gesture(scroll, x, y, x, Math.min(scroll.getHeight() - 1, y + 100));
        layout(393, 852);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        layout(393, 852);
        assertEquals(
                "Returning to the start restores the full workspace",
                View.VISIBLE,
                tag("automix-expanded").getVisibility());
        assertEquals(View.GONE, tag("automix-compact").getVisibility());
        assertTrue(tag("automix-workspace").getHeight() > 132);
        assertEquals(0, scroll.getScrollY());
        layout(393, 852);
        assertEquals(View.VISIBLE, tag("automix-expanded").getVisibility());
    }

    @Test
    public void scrollingDeeperFromAnOptionDoesNotExpandTheWaveforms() throws Exception {
        open();
        tag("transition-tab:3").performClick();
        layout(393, 852);
        ScrollView scroll = (ScrollView) tag("automix-controls-scroll");
        View option = tag("transition-repeat");
        int[] parent = new int[2], child = new int[2];
        scroll.getLocationOnScreen(parent);
        option.getLocationOnScreen(child);
        float x = child[0] - parent[0] + option.getWidth() / 2f;
        float y = child[1] - parent[1] + option.getHeight() / 2f;
        assertTrue(y > 30 && y < scroll.getHeight());
        gesture(scroll, x, y, x, y - 30);
        layout(393, 852);
        assertEquals(View.VISIBLE, tag("automix-compact").getVisibility());
        assertEquals(View.GONE, tag("automix-expanded").getVisibility());
    }

    @Test
    public void compactPreviewWorksThroughAReachableNativeTouchTarget() throws Exception {
        open();
        compactThroughOptions();
        View button = tag("automix-mini-preview");
        assertTrue(button.isShown());
        assertTrue("Preview width", button.getWidth() >= 48);
        assertTrue("Preview height", button.getHeight() >= 48);
        gesture(
                button,
                button.getWidth() / 2f,
                button.getHeight() / 2f,
                button.getWidth() / 2f,
                button.getHeight() / 2f);
        advancePreview();
        assertFalse(previewDecks.isEmpty());
        assertTrue(previewDecks.get(0).plays > 0);
        assertEquals(Strings.t("Stop"), button.getContentDescription());
        gesture(
                button,
                button.getWidth() / 2f,
                button.getHeight() / 2f,
                button.getWidth() / 2f,
                button.getHeight() / 2f);
        advancePreview();
        assertTrue(previewDecks.get(0).closed);
        assertEquals(Strings.t("Preview"), button.getContentDescription());
    }

    @Test
    public void compactGraphDragEditsTheCueAndTapRestoresTheFullGraph() throws Exception {
        open();
        tag("transition-preset:2").performClick();
        compactThroughOptions();
        View graph = tag("automix-mini-timeline");
        assertTrue(graph.isShown());
        double before = edit().incomingCue;
        float x = graph.getWidth() * .5f, y = graph.getHeight() * .75f;
        gesture(graph, x, y, graph.getWidth() * .25f, y);
        assertTrue(
                "Dragging the incoming waveform changes its cue", edit().incomingCue > before + .1);
        assertEquals(View.VISIBLE, tag("automix-compact").getVisibility());
        gesture(graph, x, y, x, y);
        layout(393, 852);
        assertEquals(View.VISIBLE, tag("automix-expanded").getVisibility());
    }

    @Test
    public void explicitSaveDraftRemembersItWithAutoSaveOffButDoesNotApply() throws Exception {
        open();
        tag("automix-auto-save").performClick();
        tag("transition-preset:2").performClick();
        assertEquals(View.VISIBLE, tag("automix-save-draft").getVisibility());
        tag("automix-save-draft").performClick();
        databaseIdle();
        assertNull(TransitionRuntime.load(TransitionRuntime.pair()));
        activity.get().onBackPressed();
        assertTrue(activity.get().isFinishing());
    }

    @Test
    public void reopeningAnAppliedPairRecognizesItAsAppliedWithoutAFalseDirtyDraft()
            throws Exception {
        open();
        tag("transition-preset:3").performClick();
        tag("transition-save").performClick();
        databaseIdle();
        activity.pause().stop().destroy();
        activity = null;
        TransitionStore.closeForTest();
        open();
        assertTrue(
                ((TextView) tag("transition-status"))
                        .getText()
                        .toString()
                        .contains("Applied to this track pair"));
        assertEquals("Draft saved", ((TextView) tag("automix-save-state")).getText().toString());
    }

    @Test
    public void closingImmediatelyFlushesTheDraftAndResumesTheEditorPausedSong() throws Exception {
        playing = true;
        open();
        tag("transition-preset:3").performClick();
        tag("transition-preview").performClick();
        advancePreview();
        activity.get().onBackPressed();
        databaseIdle();
        assertTrue(activity.get().isFinishing());
        assertTrue(playing);
        assertEquals(12000, position);
        assertNull(TransitionRuntime.load(TransitionRuntime.pair()));
    }

    @Test
    public void autoOptionsHaveVisibleLabelsBesideTheirSwitches() throws Exception {
        open();
        TelegramSwitch apply = (TelegramSwitch) tag("automix-auto-apply"),
                save = (TelegramSwitch) tag("automix-auto-save");
        assertTrue(apply.getWidth() >= 64 && apply.getHeight() >= 48);
        assertTrue(save.getWidth() >= 64 && save.getHeight() >= 48);
        assertEquals(
                "Auto-apply",
                ((TextView) ((ViewGroup) apply.getParent()).getChildAt(1)).getText().toString());
        assertEquals(
                "Auto-save",
                ((TextView) ((ViewGroup) save.getParent()).getChildAt(1)).getText().toString());
    }

    @Test
    public void autoApplyAlsoResumesAfterAuditioningAnAlreadyAppliedDraft() throws Exception {
        playing = true;
        open();
        tag("transition-preset:3").performClick();
        tag("transition-save").performClick();
        databaseIdle();
        tag("automix-auto-apply").performClick();
        tag("transition-preview").performClick();
        advancePreview();
        assertFalse(playing);
        previewDecks.get(0).position = 180000;
        advancePreview();
        databaseIdle();
        assertTrue(playing);
        assertEquals(12000, position);
        assertFalse(activity.get().isFinishing());
    }

    @Test
    public void curveDraggingTracksTheFingerWhenTheDeckMasterVolumeIsReduced() throws Exception {
        open();
        tag("transition-preset:0").performClick();
        edit().volume[0] = .5;
        View graph = tag("transition-timeline");
        float band = (graph.getHeight() - 14) / 2f;
        double t = edit().times[0][1];
        float x = (float) (graph.getWidth() * (.15 + .7 * t));
        float from = 8 + band * (.9f - .8f * (float) edit().gain(0, t)),
                to = 8 + band * (.9f - .8f * .25f);
        int[] actions = {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP};
        for (int i = 0; i < actions.length; i++) {
            MotionEvent event = MotionEvent.obtain(0, i * 40, actions[i], x, i == 0 ? from : to, 0);
            graph.onTouchEvent(event);
            event.recycle();
        }
        assertEquals(.25, edit().gain(0, edit().times[0][1]), .002);
    }

    private void render(String name, int width, int height) throws Exception {
        Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        activity.get().getWindow().getDecorView().draw(new Canvas(image));
        java.io.File file = new java.io.File("target/previews/" + name);
        file.getParentFile().mkdirs();
        try (java.io.FileOutputStream stream = new java.io.FileOutputStream(file)) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream));
        }
    }

    @Test
    public void adjustmentsExposeValuesToScreenReaders() throws Exception {
        open();
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(activity.get().getWindow().getDecorView());
        int measured = 0;
        while (!pending.isEmpty()) {
            View view = pending.removeFirst();
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) pending.add(group.getChildAt(i));
            }
            if (!(view instanceof SeekBar)) continue;
            android.view.accessibility.AccessibilityNodeInfo node =
                    view.createAccessibilityNodeInfo();
            try {
                assertNotNull(
                        String.valueOf(view.getContentDescription()), node.getStateDescription());
                assertFalse(node.getStateDescription().toString().trim().isEmpty());
                assertNotNull(node.getRangeInfo());
                measured++;
            } finally {
                node.recycle();
            }
        }
        assertTrue(measured > 2);
    }

    @Test
    public void overlapHasAnInlineSliderAndActionsShareASeparateRow() throws Exception {
        open();
        View slider = tag("automix-overlap-slider");
        assertTrue(slider instanceof SeekBar);
        assertTrue(slider.getHeight() >= 48);
        assertEquals("Overlap", slider.getContentDescription().toString());
        tag("automix-auto-save").performClick();
        layout(393, 852);
        View draft = tag("automix-save-draft"), apply = tag("transition-save");
        assertSame(draft.getParent(), apply.getParent());
        assertNotSame(draft.getParent(), tag("transition-status").getParent());
        assertTrue(draft.getHeight() >= 48 && apply.getHeight() >= 48);
        assertEquals(draft.getWidth(), apply.getWidth());
    }

    @Test
    public void overlapSliderSpansTheSupportedRangeShowsLiveSecondsAndSnapsToBeats()
            throws Exception {
        open();
        SeekBar slider = (SeekBar) tag("automix-overlap-slider");
        TextView value = (TextView) tag("automix-overlap-value");
        TransitionRuntime.Pair pair = TransitionRuntime.pair();
        assertEquals(Math.round((pair.maximumOverlap(edit()) - .5) * 10), slider.getMax(), 1);
        assertEquals(48, value.getHeight());
        slider.setProgress(0);
        Method change =
                SeekBar.class.getDeclaredMethod(
                        "onProgressRefresh", float.class, boolean.class, int.class);
        change.setAccessible(true);
        change.invoke(slider, 0f, true, 55);
        double beat = 60d / 130;
        assertEquals(Math.round((.5 + 5.5) / beat) * beat, edit().overlap, 1e-9);
        assertTrue(value.getText().toString().endsWith(" s"));
        View snap = tag("transition-snap");
        snap.performClick();
        change.invoke(slider, 0f, true, 33);
        assertEquals(3.8, edit().overlap, 1e-9);
        tag("transition-bars:8").performClick();
        assertEquals(8, edit().bars);
        assertEquals(TransitionEdit.secondsForBars(8, 130), edit().overlap, 1e-9);
        change.invoke(slider, 0f, true, 10);
        assertEquals(0, edit().bars);
    }

    @Test
    public void unavailableBarLengthsExplainThemselvesAndKeepTheOverlapInRange() throws Exception {
        open();
        TransitionRuntime.Pair pair = TransitionRuntime.pair();
        for (int bars : new int[] {2, 4, 8, 16}) {
            tag("transition-bars:" + bars).performClick();
            if (TransitionEdit.secondsForBars(bars, 130) > pair.maximumOverlap(edit()) + .001) {
                assertEquals(
                        "Not enough audio",
                        ((TextView) tag("transition-status")).getText().toString());
            }
        }
        assertTrue(edit().overlap >= .5 && edit().overlap <= pair.maximumOverlap(edit()) + .001);
    }

    @Test
    public void firstHeaderPrefetchContinuesAfterSavedSettingsFinishLoading() throws Exception {
        TransitionStore.closeForTest();
        TransitionWaveCache.clearMemory();
        Field checked = TransitionWaveCache.class.getDeclaredField("checked");
        checked.setAccessible(true);
        checked.setLong(null, -10000);
        Field requested = TransitionWaveCache.class.getDeclaredField("requested");
        requested.setAccessible(true);
        requested.set(null, null);
        TransitionRuntime.Pair pair = TransitionRuntime.pair();
        assertNotNull(pair);
        assertFalse(TransitionStore.get().lookup(pair).known);
        TransitionWaveCache.prefetch();
        databaseIdle();
        Field field = TransitionWaveCache.class.getDeclaredField("preloads");
        field.setAccessible(true);
        ((java.util.concurrent.ExecutorService) field.get(null))
                .submit(() -> {})
                .get(10, java.util.concurrent.TimeUnit.SECONDS);
        TransitionEdit draft = TransitionRuntime.automaticDraft(pair);
        for (int side = 0; side < 2; side++) {
            double start = side == 0 ? draft.start(pair.outgoingDuration) : draft.incomingCue;
            double duration = side == 0 ? pair.outgoingDuration : pair.incomingDuration;
            double from = Math.max(0, Math.floor((start - draft.overlap * .22) / 4) * 4);
            double to = Math.min(duration, Math.ceil((start + draft.overlap * 1.22) / 4) * 4);
            assertNotNull(
                    "Header prefetch warms selected deck " + side,
                    TransitionWaveCache.peek(TransitionWaveCache.identity(pair, side), from, to));
        }
    }

    @Test
    public void nativeEditorRendersTheWaveformsAndPresetRow() throws Exception {
        open();
        tag("transition-preset:0").performClick();
        layout(393, 852);
        java.lang.reflect.Field waves = TransitionEditorActivity.class.getDeclaredField("waves");
        waves.setAccessible(true);
        assertNotNull(((float[][][]) waves.get(activity.get()))[0]);
        View view = activity.get().getWindow().getDecorView();
        Bitmap image = Bitmap.createBitmap(393, 852, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(image));
        java.io.File file = new java.io.File("target/previews/automix-editor-portrait.png");
        file.getParentFile().mkdirs();
        try (java.io.FileOutputStream stream = new java.io.FileOutputStream(file)) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream));
        }
        assertTrue(tag("transition-preview").getHeight() >= 44);
        assertTrue(tag("transition-save").getHeight() >= 44);
    }
}

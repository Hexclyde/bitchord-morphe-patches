package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Bundle;
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

    /** A native plan without effects, as the planner publishes a plain equal-power blend. */
    static Plan plain() {
        Plan plan = new Plan();
        plan.phaseLock = false;
        plan.incomingPlaybackRate = 1;
        plan.outgoingPlaybackRate = 1;
        return plan;
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
    private long duration = 180000;
    private long preparedDuration = 180000;
    private boolean completePreparation = true;
    private boolean playing;
    private PlaybackParameters speed = new PlaybackParameters(1.25f, .95f);
    private final List<String> calls = new ArrayList<>();
    private final List<String> mutations = new ArrayList<>();
    private MediaItem[] items;
    private final List<AutoMixTestDeck> previewDecks = new ArrayList<>();
    private TransitionPreview.Factory previousFactory;
    private TransitionPairSelection.Reader previousReader;

    @Before
    public void setup() throws Exception {
        previousReader = TransitionPairSelection.reader;
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
                            String name = method.getName();
                            if (name.startsWith("set")
                                    || name.startsWith("seek")
                                    || name.equals("play")
                                    || name.equals("pause")
                                    || name.equals("prepare")
                                    || name.equals("stop")) mutations.add(name);
                            switch (name) {
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
                                    return duration;
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
                                case "replaceMediaItem":
                                    calls.add("replace:" + args[0]);
                                    int slot = (Integer) args[0];
                                    MediaItem old = items[slot], replacement = (MediaItem) args[1];
                                    items[slot] = replacement;
                                    if (slot == index
                                            && !Objects.equals(
                                                    old.localConfiguration,
                                                    replacement.localConfiguration)) {
                                        position = 0;
                                        duration = C.TIME_UNSET;
                                    }
                                    return null;
                                case "prepare":
                                    if (completePreparation) duration = preparedDuration;
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
        TransitionRuntime.apply(plain());
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
        TransitionPairSelection.reader = previousReader;
        android.content.ContentResolver files =
                RuntimeEnvironment.getApplication().getContentResolver();
        for (android.content.UriPermission grant :
                new ArrayList<>(files.getPersistedUriPermissions()))
            files.releasePersistableUriPermission(
                    grant.getUri(), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
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
        assertTrue(tag("automix-analysis").getContentDescription().toString().contains("144"));
        View card =
                org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
                        .getWindow()
                        .getDecorView()
                        .findViewWithTag("automix-analysis-track:0");
        assertTrue(
                "The open analysis panel refreshes its measured tempo", containsText(card, "144"));
        assertEquals(before, edit().encode());
        assertNull(TransitionRuntime.load(TransitionRuntime.pair()));
        assertFalse(calls.contains("seek"));
    }

    @Test
    public void pendingIncomingAnalysisIsDistinctFromMissingMeasurementsAndRefreshes()
            throws Exception {
        service.trackAnalyzer.results.remove(items[1].mediaId);
        open();
        String before = edit().encode();
        tag("automix-analysis").performClick();
        View sheet =
                org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
                        .getWindow()
                        .getDecorView();
        View pending = sheet.findViewWithTag("automix-analysis-track:1");
        assertTrue(containsText(pending, Strings.t("Analyzing selected audio…")));
        assertNull(pending.findViewWithTag("automix-analysis-energy:1"));
        assertNotNull(sheet.findViewWithTag("automix-analysis-energy:0"));
        Analysis unavailable = new Analysis();
        unavailable.bpm = Double.NaN;
        unavailable.key = null;
        unavailable.energyCurve = Collections.emptyList();
        unavailable.audibleStartTime = null;
        unavailable.introEndTime =
                unavailable.outroStartTime = unavailable.contentEndTime = Double.NaN;
        service.trackAnalyzer.results.put(items[1].mediaId, unavailable);
        Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(1100));
        View complete = sheet.findViewWithTag("automix-analysis-track:1");
        assertTrue(containsText(complete, Strings.t("Ready")));
        assertFalse(containsText(complete, Strings.t("Analyzing selected audio…")));
        assertTrue(containsText(complete, Strings.t("Not available")));
        assertNull(complete.findViewWithTag("automix-analysis-energy:1"));
        Analysis measured = new Analysis();
        measured.bpm = 144;
        service.trackAnalyzer.results.put(items[1].mediaId, measured);
        Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(1100));
        assertTrue(containsText(sheet.findViewWithTag("automix-analysis-track:1"), "144"));
        assertNotNull(sheet.findViewWithTag("automix-analysis-energy:1"));
        assertEquals(before, edit().encode());
        assertNull(TransitionRuntime.load(TransitionRuntime.pair()));
        assertTrue(mutations.isEmpty());
        assertTrue(previewDecks.isEmpty());
    }

    @Test
    public void estimatedTrackFactsRemainReadableAtDoubleFontScale() throws Exception {
        for (MediaItem item : items) {
            Analysis low = (Analysis) service.trackAnalyzer.results.get(item.mediaId);
            low.bpm = 161.916;
            low.beatConfidence = .3;
            low.keyConfidence = .3;
        }
        RuntimeEnvironment.setFontScale(2f);
        try {
            open();
            layout(320, 852);
            int height = 852;
            if (tag("automix-expanded").getVisibility() != View.VISIBLE) {
                height = 1200;
                layout(320, height);
                tag("automix-expand").performClick();
                layout(320, height);
            }
            for (int deck = 0; deck < 2; deck++) {
                Field titles = TransitionEditorActivity.class.getDeclaredField("titleViews");
                titles.setAccessible(true);
                TextView title = ((TextView[]) titles.get(activity.get()))[deck];
                assertTrue("Track titles keep space beside the facts", title.getWidth() >= 48);
                TextView badge = (TextView) tag("automix-key:" + deck);
                TextView meta = (TextView) tag("automix-meta:" + deck);
                for (TextView fact : new TextView[] {badge, meta}) {
                    assertTrue(fact.getText().toString().contains(Strings.t("Estimated")));
                    assertTrue(at(fact)[0] >= 0);
                    assertTrue(at(fact)[0] + fact.getWidth() <= 320);
                    for (int line = 0; line < fact.getLineCount(); line++) {
                        assertTrue(
                                "The complete fact fits its line",
                                fact.getLayout().getLineWidth(line)
                                        <= fact.getWidth()
                                                - fact.getTotalPaddingLeft()
                                                - fact.getTotalPaddingRight()
                                                + 1);
                        assertEquals(0, fact.getLayout().getEllipsisCount(line));
                    }
                }
                assertTrue(at(badge)[1] + badge.getHeight() <= at(meta)[1] + 1);
            }
            render("automix-editor-estimated-font2.png", 320, height);
        } finally {
            RuntimeEnvironment.setFontScale(1f);
        }
    }

    @Test
    public void camelotKeyBadgeSitsAboveTheTempoAndDurationLine() throws Exception {
        open();
        for (int deck = 0; deck < 2; deck++) {
            TextView badge = (TextView) tag("automix-key:" + deck);
            TextView meta = (TextView) tag("automix-meta:" + deck);
            assertNotNull(badge);
            assertEquals(KeyTempo.camelotOf("F minor"), badge.getText().toString());
            assertTrue(badge.isShown() && badge.getHeight() >= 20);
            assertTrue(
                    "The key is above the tempo line",
                    at(badge)[1] + badge.getHeight() <= at(meta)[1] + 1);
            assertTrue(meta.getText().toString().contains("130"));
            assertTrue(meta.getText().toString().contains("3:00"));
            assertFalse(meta.getText().toString().contains(badge.getText().toString()));
        }
        Analysis low = (Analysis) service.trackAnalyzer.results.get(items[0].mediaId);
        low.keyConfidence = .2;
        Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(1100));
        String estimated = ((TextView) tag("automix-key:0")).getText().toString();
        assertTrue(estimated.startsWith(KeyTempo.camelotOf("F minor")));
        assertFalse(estimated.contains("?"));
        assertTrue(estimated.contains(Strings.t("Estimated")));
        assertTrue(
                tag("automix-key:0")
                        .getContentDescription()
                        .toString()
                        .contains(Strings.t("Estimated")));
        assertEquals(KeyTempo.camelotOf("F minor"), ((TextView) tag("automix-key:1")).getText());
    }

    @Test
    public void lowConfidenceMeasurementsStayVisibleButNeverBecomeATrustedGrid() throws Exception {
        for (MediaItem item : items) {
            Analysis low = (Analysis) service.trackAnalyzer.results.get(item.mediaId);
            low.bpm = 161.916;
            low.beatConfidence = .3725;
            low.keyConfidence = .4;
        }
        open();
        for (int deck = 0; deck < 2; deck++) {
            TextView meta = (TextView) tag("automix-meta:" + deck);
            assertTrue(meta.getText().toString().contains("161.9"));
            assertFalse(meta.getText().toString().contains("?"));
            assertTrue(meta.getText().toString().contains(Strings.t("Estimated")));
            assertTrue(meta.getContentDescription().toString().contains("161.9"));
            assertTrue(meta.getContentDescription().toString().contains(Strings.t("Estimated")));
            String key = ((TextView) tag("automix-key:" + deck)).getText().toString();
            assertTrue(key.startsWith(KeyTempo.camelotOf("F minor")));
            assertFalse(key.contains("?"));
            assertTrue(key.contains(Strings.t("Estimated")));
            assertTrue(
                    "The key stays above the tempo line",
                    at(tag("automix-key:" + deck))[1] + tag("automix-key:" + deck).getHeight()
                            <= at(meta)[1] + 1);
        }
        java.lang.reflect.Field grid = TransitionEditorActivity.class.getDeclaredField("bpms");
        grid.setAccessible(true);
        for (double bpm : (double[]) grid.get(activity.get())) assertEquals(0, bpm, 0);
        assertNull(
                TransitionRuntime.musicalKey(service.trackAnalyzer.results.get(items[0].mediaId)));
    }

    @Test
    public void autoTimingWaitsForTheActualNativePlanAndAdoptsItOnlyWhileUntouched()
            throws Exception {
        TransitionRuntime.release();
        open();
        assertNull(TransitionRuntime.automaticPlan(TransitionRuntime.pair()));
        String reason = Strings.t("Automatic mix is not ready yet.");
        Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(1100));
        assertFalse(tag("transition-preview").isEnabled());
        assertFalse(tag("automix-mini-preview").isEnabled());
        assertTrue(tag("transition-preview").getContentDescription().toString().contains(reason));
        assertEquals(reason, ((TextView) tag("transition-status")).getText().toString());
        TextView value = (TextView) tag("automix-overlap-value");
        assertEquals("—", value.getText().toString());
        assertTrue(value.getContentDescription().toString().contains(reason));
        java.lang.reflect.Field pending =
                TransitionEditorActivity.class.getDeclaredField("nativePending");
        pending.setAccessible(true);
        assertTrue(pending.getBoolean(activity.get()));
        tag("transition-preview").performClick();
        assertTrue(previewDecks.isEmpty());
        Plan plan = plain();
        plan.fadeSeconds = 12;
        TransitionRuntime.apply(plan);
        Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(1100));
        assertEquals(12, edit().overlap, 1e-9);
        assertEquals(168, edit().start(180), 1e-9);
        assertFalse(pending.getBoolean(activity.get()));
        assertTrue(tag("transition-preview").isEnabled());
        assertTrue(tag("automix-mini-preview").isEnabled());
        assertTrue(value.getText().toString().contains("12"));
        assertEquals("", ((TextView) tag("transition-status")).getText().toString());
        tag("transition-preview").performClick();
        assertEquals(2, previewDecks.size());
    }

    @Test
    public void manualEditsKeepPreviewAvailableWhileTheNativePlanIsMissing() throws Exception {
        TransitionRuntime.release();
        open();
        assertFalse(tag("transition-preview").isEnabled());
        tag("transition-tab:3").performClick();
        layout(393, 852);
        tag("transition-bars:4").performClick();
        assertTrue(tag("transition-preview").isEnabled());
        assertTrue(tag("automix-mini-preview").isEnabled());
        assertNotEquals("—", ((TextView) tag("automix-overlap-value")).getText().toString());
        tag("transition-preview").performClick();
        assertEquals(2, previewDecks.size());
    }

    @Test
    public void aLateNativePlanNeverReplacesTheListenersEdits() throws Exception {
        TransitionRuntime.release();
        open();
        tag("transition-tab:3").performClick();
        layout(393, 852);
        tag("transition-bars:4").performClick();
        double mine = edit().overlap;
        String draft = edit().encode();
        Plan plan = plain();
        plan.fadeSeconds = 16;
        TransitionRuntime.apply(plan);
        Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(2100));
        assertEquals(mine, edit().overlap, 1e-9);
        assertEquals(draft, edit().encode());
    }

    @Test
    public void aLateNativePlanDoesNotMarkTheUntouchedDraftAsChanged() throws Exception {
        TransitionRuntime.release();
        open();
        TransitionRuntime.apply(plain());
        TransitionRuntime.Pair pair = TransitionRuntime.pair();
        assertNotNull(TransitionRuntime.automaticPlan(pair));
        Plan plan = plain();
        plan.fadeSeconds = 12;
        TransitionRuntime.apply(plan);
        Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(2100));
        assertEquals(12, edit().overlap, 1e-9);
        assertNull(TransitionRuntime.load(pair));
        assertFalse(calls.contains("seek"));
        assertEquals(1.25f, speed.speed, 0);
    }

    @Test
    public void analysisIsRequestedAsSoonAsTheStoreIsReadyEvenWhileWaveformsDecode()
            throws Exception {
        service.trackAnalyzer.results.clear();
        open();
        for (MediaItem item : items)
            assertEquals("queued", SourceAutomix.analysisPhase(item.mediaId));
    }

    @Test
    public void emptyDurationShowsOnlyTheMessageAndCloseAndNeverStartsPlayback() throws Exception {
        duration = C.TIME_UNSET;
        open();
        View save = tag("transition-save"), menu = tag("transition-menu");
        assertTrue(save == null || !save.isShown());
        assertTrue(menu == null || !menu.isShown());
        assertTrue(tag("transition-close").isShown());
        assertFalse(((TextView) tag("transition-status")).getText().toString().isEmpty());
        assertTrue(tag("transition-status").isShown());
        assertTrue(mutations.isEmpty());
        assertFalse(playing);
        assertEquals(12000, position);
    }

    private void openMenu() {
        tag("transition-menu").performClick();
    }

    @Test
    public void beforeIsDisabledWithItsReasonWhenNoNativePlanExists() throws Exception {
        TransitionRuntime.release();
        open();
        openMenu();
        View item = dialogView("transition-menu-before");
        assertFalse(item.isEnabled());
        assertTrue(
                item.getContentDescription()
                        .toString()
                        .contains(Strings.t("Automatic mix is not ready yet.")));
        assertTrue(previewDecks.isEmpty());
    }

    @Test
    public void beforeIsDisabledWithItsReasonWhenTheNativePlanHasEffects() throws Exception {
        Plan plan = new Plan();
        plan.filterSweep = 3;
        TransitionRuntime.apply(plan);
        open();
        openMenu();
        View item = dialogView("transition-menu-before");
        assertFalse(item.isEnabled());
        assertTrue(
                item.getContentDescription()
                        .toString()
                        .contains(
                                Strings.t(
                                        "Before isn't available: Automix adds effects to this"
                                                + " blend.")));
        assertTrue(previewDecks.isEmpty());
    }

    @Test
    public void aLocalFileWithoutAKnownFingerprintLoadsThenBindsTheSameActivity() throws Exception {
        java.io.File file = java.io.File.createTempFile("automix-local", ".flac");
        file.deleteOnExit();
        items[0] = items[0].buildUpon().setUri(android.net.Uri.fromFile(file)).build();
        Field workerField = SourceAutomix.class.getDeclaredField("identityWorker");
        workerField.setAccessible(true);
        java.util.concurrent.ExecutorService worker =
                (java.util.concurrent.ExecutorService) workerField.get(null);
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Future<?> block =
                worker.submit(
                        () -> {
                            entered.countDown();
                            try {
                                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                        });
        TransitionEditorActivity page;
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            activity = Robolectric.buildActivity(TransitionEditorActivity.class).setup();
            page = activity.get();
            TextView status = (TextView) tag("transition-status");
            assertNull(tag("transition-save"));
            assertEquals(Strings.t("Loading…"), status.getText().toString());
            assertFalse(tag("transition-menu").isShown());
            assertTrue(tag("transition-close").isShown());
            assertTrue(mutations.isEmpty());
        } finally {
            release.countDown();
        }
        block.get(5, java.util.concurrent.TimeUnit.SECONDS);
        SourceAutomix.prepareIdentity(SourceFirst.renditionUri(items[0]))
                .get(10, java.util.concurrent.TimeUnit.SECONDS);
        for (int i = 0; i < 40 && tag("transition-save") == null; i++) {
            Thread.sleep(25);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        }
        databaseIdle();
        assertSame(page, activity.get());
        assertNotNull("The editor binds once the fingerprint is known", tag("transition-save"));
        assertTrue(tag("transition-menu").isShown());
        assertNotEquals(
                Strings.t("Add two tracks to the queue first."),
                ((TextView) tag("transition-status")).getText().toString());
        assertTrue(mutations.isEmpty());
        assertFalse(playing);
        assertEquals(12000, position);
    }

    private java.util.concurrent.CountDownLatch blockIdentityWorker() throws Exception {
        Field member = SourceAutomix.class.getDeclaredField("identityWorker");
        member.setAccessible(true);
        java.util.concurrent.ExecutorService worker =
                (java.util.concurrent.ExecutorService) member.get(null);
        java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1),
                started = new java.util.concurrent.CountDownLatch(1);
        worker.submit(
                () -> {
                    started.countDown();
                    hold.await();
                    return null;
                });
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
        return hold;
    }

    private void openWithLocalFile() throws Exception {
        java.io.File file = java.io.File.createTempFile("automix-local", ".flac");
        file.deleteOnExit();
        items[0] = items[0].buildUpon().setUri(android.net.Uri.fromFile(file)).build();
        SourceAutomix.prepareIdentity(SourceFirst.renditionUri(items[0]))
                .get(10, java.util.concurrent.TimeUnit.SECONDS);
        TransitionRuntime.apply(plain());
        open();
        assertTrue(tag("transition-save").isEnabled() || tag("transition-save").isShown());
    }

    private void ageFingerprint() throws Exception {
        // Fingerprints of local files are only trusted for one second on the main thread.
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1100));
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    private void awaitRecovery() throws Exception {
        for (int i = 0; i < 40 && !tag("transition-save").isEnabled(); i++) {
            Thread.sleep(25);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        }
    }

    @Test
    public void anExpiredFingerprintChecksInsteadOfDetachingAndRecoversWhenTheWorkerFinishes()
            throws Exception {
        openWithLocalFile();
        assertTrue(tag("transition-save").isEnabled());
        assertTrue(tag("transition-preview").isEnabled());
        java.util.concurrent.CountDownLatch hold = blockIdentityWorker();
        ageFingerprint();
        assertFalse(tag("transition-save").isEnabled());
        assertFalse(tag("transition-preview").isEnabled());
        assertFalse(tag("automix-mini-preview").isEnabled());
        String shown = ((TextView) tag("transition-status")).getText().toString();
        assertEquals(Strings.t("Loading…"), shown);
        assertNotEquals(Strings.t("Use this pair to apply it"), shown);
        tag("transition-save").performClick();
        assertNotEquals(
                Strings.t("Use this pair to apply it"),
                ((TextView) tag("transition-status")).getText().toString());
        hold.countDown();
        awaitRecovery();
        assertTrue("Apply recovers when the worker finishes", tag("transition-save").isEnabled());
        assertTrue(tag("transition-preview").isEnabled());
        assertEquals("", ((TextView) tag("transition-status")).getText().toString());
        assertFalse(calls.contains("seek"));
    }

    @Test
    public void anUnreadableExpiredFingerprintSettlesWithoutImmediateRetriesOrMutations()
            throws Exception {
        openWithLocalFile();
        Map<String, ?> stored =
                new HashMap<>(
                        RuntimeEnvironment.getApplication()
                                .getSharedPreferences(
                                        TransitionRuntime.PREFERENCES, Context.MODE_PRIVATE)
                                .getAll());
        MediaItem[] queued = items.clone();
        long at = position;
        PlaybackParameters parameters = speed;
        java.util.function.Function<java.io.File, String> previous = SourceAutomix.statReader;
        java.util.concurrent.atomic.AtomicInteger reads =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean readOnMain =
                new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.CountDownLatch hold = blockIdentityWorker();
        Field member = SourceAutomix.class.getDeclaredField("identityWorker");
        member.setAccessible(true);
        java.util.concurrent.ExecutorService worker =
                (java.util.concurrent.ExecutorService) member.get(null);
        try {
            SourceAutomix.statReader =
                    file -> {
                        reads.incrementAndGet();
                        readOnMain.set(
                                android.os.Looper.myLooper() == android.os.Looper.getMainLooper());
                        throw new IllegalStateException("File is unavailable");
                    };
            ageFingerprint();
            assertFalse(tag("transition-save").isEnabled());
            assertFalse(tag("transition-preview").isEnabled());
            java.util.concurrent.CompletableFuture<String> pending =
                    SourceAutomix.prepareIdentity(SourceFirst.renditionUri(items[0]));
            hold.countDown();
            try {
                pending.get(5, java.util.concurrent.TimeUnit.SECONDS);
                fail("The file read must fail");
            } catch (java.util.concurrent.ExecutionException expected) {
                assertTrue(expected.getCause() instanceof IllegalStateException);
            }
            worker.submit(() -> null).get(5, java.util.concurrent.TimeUnit.SECONDS);
            org.robolectric.shadows.ShadowLooper loop =
                    Shadows.shadowOf(android.os.Looper.getMainLooper());
            long now = android.os.SystemClock.uptimeMillis();
            for (int task = 0; task < 8; task++) {
                java.time.Duration next = loop.getNextScheduledTaskTime();
                if (next.isZero() || next.toMillis() > android.os.SystemClock.uptimeMillis()) break;
                loop.runOneTask();
                worker.submit(() -> null).get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
            java.time.Duration next = loop.getNextScheduledTaskTime();
            assertTrue(
                    "A failed identity must not queue an immediate completion loop",
                    next.isZero() || next.toMillis() > android.os.SystemClock.uptimeMillis());
            assertEquals("Only the failed read ran before the next delayed tick", 1, reads.get());
            assertFalse("File facts are not read on the UI thread", readOnMain.get());
            assertTrue(
                    "Handling a failure must not advance through another analysis tick",
                    android.os.SystemClock.uptimeMillis() - now < 1000);
            assertEquals(
                    Strings.t("Could not read this file"),
                    ((TextView) tag("transition-status")).getText().toString());
            assertFalse(tag("transition-save").isEnabled());
            assertFalse(tag("transition-preview").isEnabled());
            assertFalse(tag("automix-mini-preview").isEnabled());
            assertEquals(
                    stored,
                    RuntimeEnvironment.getApplication()
                            .getSharedPreferences(
                                    TransitionRuntime.PREFERENCES, Context.MODE_PRIVATE)
                            .getAll());
            assertArrayEquals(queued, items);
            assertEquals(at, position);
            assertSame(parameters, speed);
            assertFalse(playing);
            assertTrue(mutations.isEmpty());
            assertTrue(previewDecks.isEmpty());
        } finally {
            SourceAutomix.statReader = previous;
            hold.countDown();
        }
    }

    @Test
    public void aQueueChangedWhileCheckingNeverAppliesTheOldDraft() throws Exception {
        openWithLocalFile();
        TransitionRuntime.Pair old = TransitionRuntime.pair();
        java.util.concurrent.CountDownLatch hold = blockIdentityWorker();
        ageFingerprint();
        assertFalse(tag("transition-save").isEnabled());
        items[1] =
                new MediaItem.Builder()
                        .setMediaId("local:replaced")
                        .setUri("content://audio/9")
                        .build();
        hold.countDown();
        for (int i = 0; i < 40; i++) {
            Thread.sleep(25);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        }
        assertFalse("The old draft cannot be applied", tag("transition-save").isEnabled());
        tag("transition-save").performClick();
        databaseIdle();
        assertNull(TransitionRuntime.load(old));
        assertEquals(
                Strings.t("Use this pair to apply it"),
                ((TextView) tag("transition-status")).getText().toString());
        assertFalse(calls.contains("replace:0") || calls.contains("replace:1"));
    }

    @Test
    public void beforePreviewsTheActualNativeTimingAndChangesNothingElse() throws Exception {
        TransitionRuntime.apply(plain());
        open();
        String draft = edit().encode();
        TransitionRuntime.Pair pair = TransitionRuntime.pair();
        openMenu();
        View item = dialogView("transition-menu-before");
        assertTrue(item.isEnabled());
        item.performClick();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(2, previewDecks.size());
        assertEquals(8, previewDecks.get(0).effects.overlap, 1e-9);
        assertEquals(172, previewDecks.get(0).effects.start(180), 1e-9);
        assertEquals(draft, edit().encode());
        assertNull(TransitionRuntime.load(pair));
        assertEquals(1.25f, speed.speed, 0);
        assertFalse(calls.contains("seek"));
        assertFalse(calls.contains("replace:0") || calls.contains("replace:1"));
        assertFalse(playing);
        assertEquals(12000, position);
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
    public void blendHeadroomFollowsBitChordsEqualPowerLaw() {
        double half = Math.sqrt(.5);
        assertEquals(.840896, TransitionPreview.headroom(half, half), 1e-5);
        assertEquals(1f, TransitionPreview.headroom(1, 0), 0);
        assertEquals(1f, TransitionPreview.headroom(0, 1), 0);
        assertEquals(1f, TransitionPreview.headroom(.5, .5), 0);
    }

    @Test
    public void auditionTrimsBothPrivateDecksAtTheBlendMidpointAndRestoresThemOnStop()
            throws Exception {
        playing = true;
        TransitionPreview.Session session = TransitionPreview.acquire(TransitionRuntime.pair());
        TransitionEdit draft = new TransitionEdit();
        TransitionPreview.Audition preview = session.start(draft, () -> {});
        advancePreview();
        previewDecks.get(0).position = 172000;
        advancePreview();
        for (AutoMixTestDeck deck : previewDecks) assertEquals(1f, deck.trim, 0);
        previewDecks.get(0).position = 176000;
        advancePreview();
        float expected = TransitionPreview.headroom(draft.gain(0, .5), draft.gain(1, .5));
        assertTrue(expected < 1f);
        for (AutoMixTestDeck deck : previewDecks) assertEquals(expected, deck.trim, 1e-6f);
        assertEquals(1.25f, speed.speed, 0);
        assertFalse(calls.contains("seek"));
        assertFalse(calls.contains("speed"));
        preview.stop();
        for (AutoMixTestDeck deck : previewDecks) {
            assertEquals(1f, deck.trim, 0);
            assertTrue(deck.closed);
        }
        session.close(true);
    }

    @Test
    public void aDeckThatCannotApplyHeadroomEndsTheAuditionInsteadOfPlayingTooLoud()
            throws Exception {
        playing = true;
        TransitionPreview.Factory kept = TransitionPreview.factory;
        TransitionPreview.factory =
                (owner, deck) -> {
                    AutoMixTestDeck player =
                            new AutoMixTestDeck() {
                                @Override
                                public void trim(float gain) {
                                    if (gain != 1f)
                                        throw new IllegalStateException("No headroom control");
                                }
                            };
                    previewDecks.add(player);
                    return player;
                };
        TransitionPreview.Session session = TransitionPreview.acquire(TransitionRuntime.pair());
        TransitionPreview.Audition preview = session.start(new TransitionEdit(), () -> {});
        advancePreview();
        previewDecks.get(0).position = 172000;
        advancePreview();
        previewDecks.get(0).position = 176000;
        advancePreview();
        assertTrue(preview.closed);
        assertNotNull(preview.error);
        for (AutoMixTestDeck deck : previewDecks) assertTrue(deck.closed);
        TransitionPreview.factory = kept;
        session.close(true);
    }

    @Test
    public void aLongBlendIsHeardAroundItsMiddleWithMatchingIncomingPosition() throws Exception {
        playing = true;
        TransitionPreview.Session session = TransitionPreview.acquire(TransitionRuntime.pair());
        TransitionEdit draft = new TransitionEdit();
        draft.overlap = 30;
        TransitionPreview.Audition preview = session.start(draft, () -> {});
        assertEquals(157000, previewDecks.get(0).position);
        assertEquals(9000, previewDecks.get(1).position);
        advancePreview();
        previewDecks.get(0).position = 159000;
        advancePreview();
        assertTrue(previewDecks.get(1).playing);
        assertFalse(preview.closed);
        previewDecks.get(0).position = 171500;
        advancePreview();
        assertTrue(preview.completed);
        assertFalse(playing);
        assertEquals(12000, position);
        session.close(true);
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

    private boolean containsText(View view, String text) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(text))
            return true;
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
                if (containsText(((ViewGroup) view).getChildAt(i), text)) return true;
        return false;
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
        tag("transition-tab:3").performClick();
        layout(393, 852);
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
        Plan plan = plain();
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
        tag("transition-tab:2").performClick();
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
        Plan plan = plain();
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
        boolean condensed = tag("automix-compact").getVisibility() == View.VISIBLE;
        View preview = tag(condensed ? "automix-mini-preview" : "transition-preview");
        assertTrue(preview.isShown() && preview.getHeight() >= 48);
        assertTrue(at(preview)[1] + preview.getHeight() <= 393);
        if (!condensed) {
            View track = tag("transition-track:1");
            assertTrue(at(track)[1] + track.getHeight() <= 393);
        }
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
        mode("automix-auto-apply", true);
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
        assertEquals(190, tag("automix-workspace").getHeight());
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
        assertEquals(48, tag("transition-preset:1").getHeight());
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
        View option = tag("transition-suggest");
        assertTrue(
                option.requestRectangleOnScreen(
                        new android.graphics.Rect(0, 0, option.getWidth(), option.getHeight()),
                        true));
        layout(393, 852);
        assertTrue(option.isShown());
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

    /** One finger sent through the window, so the real parent chain routes every event. */
    private final class Finger {
        private final float x;
        private float y;
        private long time;
        final List<Integer> scrolls = new ArrayList<>();

        Finger(float x, float y) {
            this.x = x;
            this.y = y;
        }

        void down() {
            send(MotionEvent.ACTION_DOWN);
        }

        void moveTo(float to) {
            y = to;
            time += 16;
            send(MotionEvent.ACTION_MOVE);
            scrolls.add(((ScrollView) tag("automix-controls-scroll")).getScrollY());
        }

        void up() {
            send(MotionEvent.ACTION_UP);
        }

        void cancel() {
            send(MotionEvent.ACTION_CANCEL);
        }

        private void send(int action) {
            MotionEvent event = MotionEvent.obtain(0, time, action, x, y, 0);
            try {
                activity.get().getWindow().getDecorView().dispatchTouchEvent(event);
            } finally {
                event.recycle();
            }
            frame();
        }
    }

    /** Runs what the platform runs between frames. */
    private void frame() {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        layout(393, 852);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        layout(393, 852);
    }

    private boolean compactShown() {
        return tag("automix-compact").getVisibility() == View.VISIBLE;
    }

    private int[] inWindow(View view) {
        int[] at = new int[2];
        view.getLocationInWindow(at);
        return at;
    }

    /** The first control that is fully inside the visible part of the options. */
    private View visibleControl(ViewGroup parent, Rect window) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            int[] at = inWindow(child);
            Rect box = new Rect(at[0], at[1], at[0] + child.getWidth(), at[1] + child.getHeight());
            if (child.isClickable() && window.contains(box)) return child;
            if (child instanceof ViewGroup) {
                View inside = visibleControl((ViewGroup) child, window);
                if (inside != null) return inside;
            }
        }
        return null;
    }

    private void showExpandedOptions() {
        tag("transition-tab:3").performClick();
        layout(393, 852);
        if (compactShown()) tag("automix-expand").performClick();
        layout(393, 852);
        assertFalse(compactShown());
    }

    /** Replaces the options with plain content of a known height. */
    private ScrollView plainOptions(int overflow) {
        ScrollView scroll = (ScrollView) tag("automix-controls-scroll");
        ViewGroup controls = (ViewGroup) scroll.getChildAt(0);
        controls.removeAllViews();
        controls.addView(new View(activity.get()), new LinearLayout.LayoutParams(-1, 1));
        layout(393, 852);
        int viewport = scroll.getHeight() - controls.getPaddingTop() - controls.getPaddingBottom();
        controls.getChildAt(0)
                .setLayoutParams(new LinearLayout.LayoutParams(-1, viewport + overflow));
        layout(393, 852);
        return scroll;
    }

    private int changes(List<Boolean> states) {
        int changes = 0;
        for (int i = 1; i < states.size(); i++)
            if (!states.get(i).equals(states.get(i - 1))) changes++;
        return changes;
    }

    @Test
    public void draggingUpFromAControlScrollsSteadilyAndCompactsOnce() throws Exception {
        open();
        showExpandedOptions();
        ScrollView scroll = (ScrollView) tag("automix-controls-scroll");
        int[] at = inWindow(scroll);
        Rect window = new Rect(at[0], at[1], at[0] + scroll.getWidth(), at[1] + scroll.getHeight());
        View control = visibleControl(scroll, window);
        assertNotNull("A control is visible in the options", control);
        int[] spot = inWindow(control);
        float x = spot[0] + control.getWidth() / 2f, from = spot[1] + control.getHeight() / 2f;
        List<Boolean> states = new ArrayList<>(List.of(compactShown()));
        Finger finger = new Finger(x, from);
        finger.down();
        for (int i = 1; i <= 12; i++) {
            finger.moveTo(from - 10 * i);
            states.add(compactShown());
        }
        finger.up();
        for (int i = 0; i < 4; i++) {
            frame();
            states.add(compactShown());
        }
        for (int i = 1; i < finger.scrolls.size(); i++)
            assertTrue(
                    "The content follows the finger: " + finger.scrolls,
                    finger.scrolls.get(i) >= finger.scrolls.get(i - 1));
        assertTrue("The options scrolled: " + finger.scrolls, scroll.getScrollY() > 0);
        assertTrue("The waveforms condense", compactShown());
        assertEquals("The layout changes once: " + states, 1, changes(states));
    }

    @Test
    public void draggingUpFromEmptySpaceScrollsSteadilyAndCompactsOnce() throws Exception {
        open();
        showExpandedOptions();
        ScrollView scroll = plainOptions(60);
        int[] at = inWindow(scroll);
        float x = at[0] + 4, from = at[1] + scroll.getHeight() * .8f;
        List<Boolean> states = new ArrayList<>(List.of(compactShown()));
        Finger finger = new Finger(x, from);
        finger.down();
        for (int i = 1; i <= 8; i++) {
            finger.moveTo(from - 10 * i);
            states.add(compactShown());
        }
        finger.up();
        for (int i = 0; i < 4; i++) {
            frame();
            states.add(compactShown());
        }
        for (int i = 1; i < finger.scrolls.size(); i++)
            assertTrue(
                    "The content follows the finger: " + finger.scrolls,
                    finger.scrolls.get(i) >= finger.scrolls.get(i - 1));
        assertTrue("The waveforms condense", compactShown());
        assertEquals("The layout changes once: " + states, 1, changes(states));
    }

    @Test
    public void condensingThatRemovesTheScrollRangeDoesNotExpandAgain() throws Exception {
        open();
        showExpandedOptions();
        ScrollView scroll = plainOptions(40);
        int[] at = inWindow(scroll);
        float x = at[0] + 4, from = at[1] + scroll.getHeight() * .8f;
        Finger finger = new Finger(x, from);
        finger.down();
        for (int i = 1; i <= 6; i++) finger.moveTo(from - 10 * i);
        finger.up();
        List<Boolean> states = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            frame();
            states.add(compactShown());
        }
        assertTrue("The viewport clamp is not a request to expand: " + states, compactShown());
        assertEquals(0, changes(states));
    }

    @Test
    public void pullingDownAtTheStartExpandsOnceAndEndsTheGesture() throws Exception {
        open();
        showExpandedOptions();
        ScrollView scroll = plainOptions(400);
        scroll.scrollTo(0, 60);
        frame();
        assertTrue(compactShown());
        scroll.scrollTo(0, 0);
        frame();
        // The scroll back to the start is a request to expand.
        assertFalse(compactShown());
        int[] at = inWindow(scroll);
        float x = at[0] + 4, from = at[1] + scroll.getHeight() * .8f;
        Finger finger = new Finger(x, from);
        finger.down();
        for (int i = 1; i <= 6; i++) finger.moveTo(from - 10 * i);
        finger.up();
        frame();
        assertTrue(compactShown());
        at = inWindow(scroll);
        from = at[1] + scroll.getHeight() * .3f;
        List<Boolean> states = new ArrayList<>(List.of(compactShown()));
        finger = new Finger(x, from);
        finger.down();
        for (int i = 1; i <= 8; i++) {
            finger.moveTo(from + 10 * i);
            states.add(compactShown());
        }
        finger.up();
        for (int i = 0; i < 4; i++) {
            frame();
            states.add(compactShown());
        }
        assertFalse("Pulling down at the start expands: " + states, compactShown());
        assertEquals(0, scroll.getScrollY());
        assertEquals("The layout changes once: " + states, 1, changes(states));
    }

    @Test
    public void aCancelledDragStillEndsTheTouchSoScrollingToTheStartExpands() throws Exception {
        open();
        showExpandedOptions();
        ScrollView scroll = plainOptions(400);
        int[] at = inWindow(scroll);
        float x = at[0] + 4, from = at[1] + scroll.getHeight() * .8f;
        Finger finger = new Finger(x, from);
        finger.down();
        for (int i = 1; i <= 6; i++) finger.moveTo(from - 10 * i);
        finger.cancel();
        frame();
        assertTrue(compactShown());
        scroll.scrollTo(0, 20);
        frame();
        scroll.scrollTo(0, 0);
        frame();
        assertFalse("Reaching the start without a finger expands", compactShown());
    }

    @Test
    public void aShortWindowKeepsTheWaveformsCondensedWithoutOfferingAnEmptyExpand()
            throws Exception {
        open();
        layout(393, 520);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        layout(393, 520);
        assertTrue("A short window condenses the waveforms", compactShown());
        assertNotEquals(
                "Expanding cannot work here, so it is not offered",
                View.VISIBLE,
                tag("automix-expand").getVisibility());
        for (int i = 0; i < 4; i++) {
            tag("automix-mini-timeline").performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            layout(393, 520);
            assertTrue(compactShown());
        }
        layout(393, 852);
        assertEquals(
                "A taller window offers it again",
                View.VISIBLE,
                tag("automix-expand").getVisibility());
        tag("automix-expand").performClick();
        layout(393, 852);
        layout(393, 852);
        assertFalse("Expanding works once there is room", compactShown());
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
        mode("automix-auto-save", false);
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
        assertEquals("Mix applied", ((TextView) tag("automix-apply-state")).getText().toString());
        Field dirty = TransitionEditorActivity.class.getDeclaredField("dirtyDraft");
        dirty.setAccessible(true);
        assertFalse(dirty.getBoolean(activity.get()));
        Field applied = TransitionEditorActivity.class.getDeclaredField("appliedState");
        applied.setAccessible(true);
        Method state = TransitionEditorActivity.class.getDeclaredMethod("state");
        state.setAccessible(true);
        assertEquals(applied.get(activity.get()), state.invoke(activity.get()));
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
    public void modeHandlesAreLabelledSeparateCircularTargets() throws Exception {
        open();
        for (String tag : new String[] {"automix-auto-apply", "automix-auto-save"}) {
            View handle = tag(tag),
                    action = tag(tag.endsWith("apply") ? "transition-save" : "automix-save-draft");
            assertEquals(48, handle.getWidth());
            assertEquals(48, handle.getHeight());
            assertTrue(handle.getContentDescription().toString().contains("mode"));
            assertTrue(
                    "Handle and action targets never overlap",
                    at(handle)[0] + 48 <= at(action)[0]
                            || at(action)[0] + action.getWidth() <= at(handle)[0]);
        }
        assertFalse(tag("automix-save-draft").isEnabled());
        assertTrue(tag("transition-save").getVisibility() == View.VISIBLE);
    }

    private void mode(String handle, boolean automatic) {
        tag(handle).performClick();
        android.app.Dialog dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        dialog.getWindow()
                .getDecorView()
                .findViewWithTag(automatic ? "automix-mode-automatic" : "automix-mode-manual")
                .performClick();
        layout(393, 852);
    }

    @Test
    public void autoApplyAlsoResumesAfterAuditioningAnAlreadyAppliedDraft() throws Exception {
        playing = true;
        open();
        tag("transition-preset:3").performClick();
        tag("transition-save").performClick();
        databaseIdle();
        mode("automix-auto-apply", true);
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
        mode("automix-auto-save", false);
        layout(393, 852);
        View draft = tag("automix-save-draft"), apply = tag("transition-save");
        assertEquals(at(draft)[1], at(apply)[1]);
        assertEquals(draft.getWidth(), apply.getWidth());
        assertNotSame(draft.getParent(), tag("transition-status").getParent());
        assertTrue(draft.getHeight() >= 48 && apply.getHeight() >= 48);
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
        assertTrue(value.getText().toString().contains(" s"));
        assertTrue(
                "Measured beats give rounded bars",
                value.getText().toString().contains("≈")
                        || value.getText().toString().contains(Strings.t("bars")));
        View snap = tag("transition-snap");
        snap.performClick();
        change.invoke(slider, 0f, true, 33);
        assertEquals(3.8, edit().overlap, 1e-9);
        tag("transition-tab:3").performClick();
        layout(393, 852);
        tag("transition-bars:8").performClick();
        assertEquals(8, edit().bars);
        assertEquals(TransitionEdit.secondsForBars(8, 130), edit().overlap, 1e-9);
        change.invoke(slider, 0f, true, 10);
        assertEquals(0, edit().bars);
    }

    @Test
    public void unavailableBarLengthsExplainThemselvesAndKeepTheOverlapInRange() throws Exception {
        open();
        tag("transition-tab:3").performClick();
        layout(393, 852);
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
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
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
        for (int i = 0; i < 4; i++) {
            TextView label = (TextView) tag("transition-tab:" + i);
            assertNotNull(
                    "Each tab has a distinct icon above its label",
                    label.getCompoundDrawables()[1]);
            assertEquals(label.getText().toString(), label.getContentDescription().toString());
            assertEquals(
                    "The default-width tab label stays visible",
                    0,
                    label.getLayout().getEllipsisCount(0));
            assertTrue(label.getLayout().getLineWidth(0) > 0);
        }
        assertEquals("Track analysis ›", ((TextView) tag("automix-analysis")).getText().toString());
    }

    @Test
    @Config(qualifiers = "w393dp-h852dp-notnight-mdpi")
    public void lightThemeKeepsEveryTabLabelAndTheAnalysisActionReadable() throws Exception {
        open();
        layout(393, 852);
        for (int i = 0; i < 4; i++) {
            TextView label = (TextView) tag("transition-tab:" + i);
            assertNotNull(label.getCompoundDrawables()[1]);
            assertEquals(0, label.getLayout().getEllipsisCount(0));
            assertTrue(label.getLayout().getLineWidth(0) > 0);
            assertTrue(
                    label.getLayout().getHeight()
                            <= label.getHeight()
                                    - label.getCompoundPaddingTop()
                                    - label.getCompoundPaddingBottom());
        }
        assertEquals("Track analysis ›", ((TextView) tag("automix-analysis")).getText().toString());
        render("automix-editor-tabs-light.png", 393, 852);
    }

    @Test
    public void selectingPresetsKeepsOptionsScrollableInAShortWindow() throws Exception {
        open();
        for (int preset : new int[] {0, 2, 3, 1}) {
            layout(393, 852);
            if (tag("automix-compact").getVisibility() == View.VISIBLE)
                tag("automix-expand").performClick();
            layout(393, 852);
            assertTrue(
                    "The preset is reachable before choosing it",
                    tag("transition-preset:" + preset).isShown());
            tag("transition-preset:" + preset).performClick();
            layout(393, 680);
            ScrollView scroll = (ScrollView) tag("automix-controls-scroll");
            assertTrue("Preset selection must leave an options viewport", scroll.getHeight() >= 96);
            int range = scroll.getChildAt(0).getHeight() - scroll.getHeight();
            assertTrue("Preset options remain scrollable", range > 0);
            scroll.scrollTo(0, range);
            layout(393, 680);
            assertTrue("Options can scroll after changing the preset", scroll.getScrollY() > 0);
            scroll.scrollTo(0, 0);
            float y = scroll.getHeight() * .75f;
            gesture(scroll, 4, y, 4, Math.max(4, y - 80));
            layout(393, 680);
            assertTrue(
                    "A vertical touch gesture still scrolls preset options",
                    scroll.getScrollY() > 0);
        }
    }

    @Test
    public void dockActionsStayVisibleAndEqualInBothModes() throws Exception {
        open();
        layout(393, 852);
        View apply = tag("transition-save"), draft = tag("automix-save-draft");
        assertEquals(View.VISIBLE, draft.getVisibility());
        assertFalse("Automatic saving disables its action", draft.isEnabled());
        assertEquals(draft.getWidth(), apply.getWidth());
        mode("automix-auto-save", false);
        assertTrue(draft.isEnabled());
        assertEquals(draft.getWidth(), apply.getWidth());
        assertTrue(draft.getHeight() >= 48 && apply.getHeight() >= 48);
    }

    @Test
    public void openingEditorKeepsWaveformWorkBelowPlaybackPriorityWithoutStartingAudio()
            throws Exception {
        TransitionWaveCache.clearMemory();
        com.music.bitchord.playback.PlaybackService.WaveDecoder decoder =
                com.music.bitchord.playback.PlaybackService.waveDecoder;
        List<Integer> priorities = new java.util.concurrent.CopyOnWriteArrayList<>();
        com.music.bitchord.playback.PlaybackService.waveDecoder =
                (source, from, to, abort, chunks) -> {
                    priorities.add(
                            android.os.Process.getThreadPriority(android.os.Process.myTid()));
                    return decoder.decode(source, from, to, abort, chunks);
                };
        playing = true;
        mutations.clear();
        android.media.AudioManager audio =
                RuntimeEnvironment.getApplication()
                        .getSystemService(android.media.AudioManager.class);
        Object focus = Shadows.shadowOf(audio).getLastAudioFocusRequest();
        open();
        assertEquals(
                "Opening the editor cannot mutate listening playback",
                Collections.emptyList(),
                mutations);
        assertSame(
                "Opening the editor cannot request audio focus",
                focus,
                Shadows.shadowOf(audio).getLastAudioFocusRequest());
        assertFalse("The real waveform decoder must run", priorities.isEmpty());
        for (int priority : priorities)
            assertTrue(
                    "Waveform decoding must yield to playback",
                    priority >= android.os.Process.THREAD_PRIORITY_BACKGROUND);
        assertTrue("Opening the editor keeps the listening player playing", playing);
        assertEquals(12000, position);
        assertEquals(1.25f, speed.speed, 0);
        assertEquals(.95f, speed.pitch, 0);
        assertTrue(previewDecks.isEmpty());
        assertFalse(calls.contains("seek"));
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-notnight-mdpi")
    public void compactFooterActionsFitAtDoubleFontScale() throws Exception {
        RuntimeEnvironment.setFontScale(2f);
        RuntimeEnvironment.getApplication()
                .getSharedPreferences("bitchord_automix_editor", 0)
                .edit()
                .putBoolean("auto_save", false)
                .commit();
        open();
        layout(320, 640);
        render("automix-editor-actions-font2-green2.png", 320, 640);
        View draft = tag("automix-save-draft"), apply = tag("transition-save");
        assertTrue(draft.getHeight() >= 48 && apply.getHeight() >= 48);
        assertEquals(draft.getWidth(), apply.getWidth());
        assertTrue(at(draft)[0] + draft.getWidth() <= at(apply)[0]);
        assertTrue(at(apply)[0] + apply.getWidth() <= 320);
        for (View child : new View[] {draft, apply}) {
            TextView text = (TextView) child;
            assertNotNull(text.getLayout());
            assertTrue(
                    text.getLayout().getHeight()
                            <= text.getHeight()
                                    - text.getCompoundPaddingTop()
                                    - text.getCompoundPaddingBottom());
        }
        assertTrue(tag("automix-controls-scroll").getHeight() >= 96);
        TextView analysis = (TextView) tag("automix-analysis");
        assertEquals("Analysis ›", analysis.getText().toString());
        assertEquals(1, analysis.getLineCount());
        assertTrue(
                "The full measured tempo stays in the accessible label",
                String.valueOf(analysis.getContentDescription()).contains("130"));
        assertTrue(String.valueOf(analysis.getContentDescription()).startsWith("Track analysis"));
        for (int i = 0; i < 4; i++) {
            TextView label = (TextView) tag("transition-tab:" + i);
            assertNotNull(label.getCompoundDrawables()[1]);
            assertEquals(1, label.getLineCount());
            assertEquals(label.getText().toString(), label.getContentDescription().toString());
            assertTrue(
                    label.getLayout().getHeight()
                            <= label.getHeight()
                                    - label.getCompoundPaddingTop()
                                    - label.getCompoundPaddingBottom());
        }
    }

    private int[] at(View view) {
        int[] where = new int[2];
        view.getLocationOnScreen(where);
        return where;
    }

    private android.graphics.drawable.Drawable shapeOf(android.graphics.drawable.Drawable d) {
        while (d != null) {
            if (d instanceof android.graphics.drawable.RippleDrawable
                    || d instanceof android.graphics.drawable.LayerDrawable)
                d = ((android.graphics.drawable.LayerDrawable) d).getDrawable(0);
            else if (d instanceof android.graphics.drawable.InsetDrawable)
                d = ((android.graphics.drawable.InsetDrawable) d).getDrawable();
            else break;
        }
        return d;
    }

    private void collect(View view, List<TextView> out) {
        if (view instanceof TextView
                && view.isShown()
                && view.isClickable()
                && !String.valueOf(view.getTag()).startsWith("transition-tab:")
                && !String.valueOf(view.getTag()).startsWith("automix-analysis-"))
            out.add((TextView) view);
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
                collect(((ViewGroup) view).getChildAt(i), out);
    }

    @Test
    public void everyVisibleActionSharesOneTouchShape() throws Exception {
        open();
        List<TextView> actions = new ArrayList<>();
        collect(root(), actions);
        assertTrue(actions.size() > 8);
        List<String> wrong = new ArrayList<>();
        for (TextView action : actions) {
            String name = action.getTag() + " " + action.getText() + " " + action.getHeight();
            if (action.getHeight() < 48) wrong.add("height " + name);
            android.graphics.drawable.Drawable shape = shapeOf(action.getBackground());
            if (shape instanceof android.graphics.drawable.GradientDrawable
                    && ((android.graphics.drawable.GradientDrawable) shape).getCornerRadius() != 24)
                wrong.add("radius " + name);
        }
        for (String tag : new String[] {"transition-preview", "automix-player-play"}) {
            View icon = tag(tag);
            if (icon.getWidth() != 48 || icon.getHeight() != 48) wrong.add("icon " + tag);
        }
        for (View icon : root().getTouchables())
            if (icon.getContentDescription() != null
                    && (icon.getContentDescription().toString().equals("Close")
                            || icon.getContentDescription().toString().equals("Settings"))
                    && (icon.getWidth() != 48 || icon.getHeight() != 48))
                wrong.add("icon " + icon.getContentDescription());
        assertEquals(Collections.emptyList(), wrong);
    }

    @Test
    public void draftAnalysisAndApplyArePinnedBetweenWaveformsAndPresets() throws Exception {
        RuntimeEnvironment.getApplication()
                .getSharedPreferences("bitchord_automix_editor", 0)
                .edit()
                .putBoolean("auto_save", false)
                .commit();
        open();
        View workspace = tag("automix-workspace"), presets = tag("transition-preset:0");
        View draft = tag("automix-save-draft"),
                analysis = tag("automix-analysis"),
                apply = tag("transition-save");
        for (View view : new View[] {draft, analysis, apply}) {
            assertTrue(view.isShown());
            for (android.view.ViewParent p = view.getParent(); p instanceof View; p = p.getParent())
                assertFalse("Dock stays outside scrolling controls", p instanceof ScrollView);
            assertTrue(at(view)[1] >= at(workspace)[1] + workspace.getHeight());
            assertTrue(at(view)[1] + view.getHeight() <= at(presets)[1]);
        }
        assertEquals(at(draft)[1], at(apply)[1]);
        assertEquals(draft.getWidth(), apply.getWidth());
        assertTrue(at(draft)[0] < at(analysis)[0] && at(analysis)[0] < at(apply)[0]);
    }

    @Test
    public void compactPreviewIsCentredOnTheWaveform() throws Exception {
        open();
        compactThroughOptions();
        View button = tag("automix-mini-preview"), graph = tag("automix-mini-timeline");
        assertEquals(48, button.getWidth());
        assertEquals(48, button.getHeight());
        assertEquals(at(graph)[0] + graph.getWidth() / 2f, at(button)[0] + 24, 2);
        assertEquals(at(graph)[1] + graph.getHeight() / 2f, at(button)[1] + 24, 2);
        assertTrue("Waveform spans the full width", graph.getWidth() >= 393 - 32);
    }

    private View dialogView(String tag) {
        android.app.Dialog dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        View decor = dialog.getWindow().getDecorView();
        int width = dialog.getWindow().getAttributes().width;
        if (width <= 0)
            width =
                    RuntimeEnvironment.getApplication()
                            .getResources()
                            .getDisplayMetrics()
                            .widthPixels;
        decor.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(
                        RuntimeEnvironment.getApplication()
                                .getResources()
                                .getDisplayMetrics()
                                .heightPixels,
                        View.MeasureSpec.AT_MOST));
        decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight());
        View found = decor.findViewWithTag(tag);
        assertNotNull(tag, found);
        return found;
    }

    private void menuUndo() {
        tag("transition-menu").performClick();
        dialogView("transition-menu-undo").performClick();
    }

    private void assertListeningUntouched() {
        assertEquals(Collections.emptyList(), mutations);
        assertEquals(1.25f, speed.speed, 0);
        assertEquals(.95f, speed.pitch, 0);
        assertTrue(previewDecks.isEmpty());
    }

    private void step(View button, int action) {
        assertTrue(button.performAccessibilityAction(action, null));
    }

    @Test
    public void rateStepsByOnePercentThroughTalkBackAndKeyboardAndUndoRestoresIt()
            throws Exception {
        playing = true;
        open();
        View button = tag("automix-rate:0");
        assertTrue(button.isShown() && button.getHeight() >= 48);
        step(
                button,
                android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
                        .ACTION_SCROLL_FORWARD
                        .getId());
        assertEquals(1.01, edit().overlapRate[0], 1e-9);
        button.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP));
        button.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP));
        assertEquals(1.02, edit().overlapRate[0], 1e-9);
        assertTrue(button.getContentDescription().toString().contains("1.02"));
        Bundle args = new Bundle();
        args.putFloat(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,
                1.5f);
        assertTrue(
                button.performAccessibilityAction(
                        android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
                                .ACTION_SET_PROGRESS
                                .getId(),
                        args));
        assertEquals(1.5, edit().overlapRate[0], 1e-9);
        assertEquals(1, edit().overlapRate[1], 0);
        menuUndo();
        assertEquals(1.02, edit().overlapRate[0], 1e-9);
        assertTrue(playing);
        assertListeningUntouched();
    }

    @Test
    public void popoverStepsTypesAndRejectsInvalidValuesWithoutTouchingPlayback() throws Exception {
        playing = true;
        open();
        String before = edit().encode();
        tag("automix-rate:1").performClick();
        assertEquals("Opening the popover changes nothing", before, edit().encode());
        View up = dialogView("automix-rate-up");
        assertTrue(up.getHeight() >= 48 || up.getMeasuredHeight() >= 48);
        up.performClick();
        assertEquals(1.01, edit().overlapRate[1], 1e-9);
        TextView value = (TextView) dialogView("automix-rate-value");
        value.setText("3");
        value.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        assertEquals(
                "An invalid value leaves the draft unchanged", 1.01, edit().overlapRate[1], 1e-9);
        assertNotNull(value.getError());
        value.setText("0.95");
        value.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        assertEquals(.95, edit().overlapRate[1], 1e-9);
        dialogView("automix-rate-reset").performClick();
        assertEquals(1, edit().overlapRate[1], 0);
        assertEquals(1, edit().overlapRate[0], 0);
        assertTrue(playing);
        assertListeningUntouched();
    }

    @Test
    public void matchingTargetsTheOtherEffectiveBpmAndResetOnlyClearsRates() throws Exception {
        open();
        edit().manualBpm[0] = 160;
        edit().manualBpm[1] = 120;
        tag("transition-tab:3").performClick();
        View match = tag("transition-match:1");
        assertTrue(match.isEnabled());
        match.performClick();
        assertEquals(160d / 120, edit().overlapRate[1], 1e-9);
        assertEquals(160, edit().overlapBpm(1, 130), 1e-9);
        assertEquals(1, edit().overlapRate[0], 0);
        edit().overlapRate[0] = .8;
        tag("transition-tab:3").performClick();
        tag("transition-tempo-reset").performClick();
        assertEquals(1, edit().overlapRate[0], 0);
        assertEquals(1, edit().overlapRate[1], 0);
        assertEquals(160, edit().manualBpm[0], 0);
        assertEquals(120, edit().manualBpm[1], 0);
        assertEquals(1.25f, speed.speed, 0);
        menuUndo();
        assertEquals(160d / 120, edit().overlapRate[1], 1e-9);
    }

    @Test
    public void matchingIsDisabledOutsideTheHalfToDoubleRange() throws Exception {
        open();
        edit().manualBpm[0] = 250;
        edit().manualBpm[1] = 100;
        tag("transition-tab:3").performClick();
        assertFalse(tag("transition-match:1").isEnabled());
        assertFalse(tag("transition-match:0").isEnabled());
        edit().manualBpm[0] = 200;
        tag("transition-tab:3").performClick();
        assertTrue(tag("transition-match:1").isEnabled());
        assertTrue(tag("transition-match:0").isEnabled());
    }

    @Test
    public void fadeAndRiseKeepTheTempoContract() throws Exception {
        open();
        edit().manualBpm[0] = 150;
        edit().manualBpm[1] = 110;
        edit().overlapRate[0] = 1.1;
        edit().overlapRate[1] = .9;
        for (int preset : new int[] {2, 3}) {
            tag("transition-preset:" + preset).performClick();
            assertEquals(150, edit().manualBpm[0], 0);
            assertEquals(110, edit().manualBpm[1], 0);
            assertEquals(1.1, edit().overlapRate[0], 1e-9);
            assertEquals(.9, edit().overlapRate[1], 1e-9);
        }
    }

    @Test
    public void automaticTimingKeepsExplicitTemposThroughApplyAndReopen() throws Exception {
        open();
        edit().manualBpm[0] = 150;
        edit().manualBpm[1] = 110;
        edit().overlapRate[0] = 1.1;
        edit().overlapRate[1] = .9;
        edit().tempoExplicit = true;
        tag("transition-preset:1").performClick();
        assertTrue(tag("transition-preset:1").isSelected());
        assertArrayEquals(new double[] {150, 110}, edit().manualBpm, 0);
        assertArrayEquals(new double[] {1.1, .9}, edit().overlapRate, 0);
        tag("transition-save").performClick();
        databaseIdle();
        assertTrue(TransitionRuntime.load(TransitionRuntime.pair()).automaticTiming);
        activity.pause().stop().destroy();
        activity = null;
        open();
        assertTrue(tag("transition-preset:1").isSelected());
        assertArrayEquals(new double[] {150, 110}, edit().manualBpm, 0);
        assertArrayEquals(new double[] {1.1, .9}, edit().overlapRate, 0);
        assertEquals(1.25f, speed.speed, 0);
        assertEquals(.95f, speed.pitch, 0);
    }

    private Plan nativeRates(double outgoing, double incoming) {
        Plan plan = plain();
        plan.outgoingPlaybackRate = outgoing;
        plan.incomingPlaybackRate = incoming;
        return plan;
    }

    @Test
    public void nativeDerivedRatesOnUntouchedAutoAreNeverStoredAsAnOverride() throws Exception {
        TransitionRuntime.apply(nativeRates(1.1, .9));
        open();
        TransitionRuntime.Pair pair = TransitionRuntime.pair();
        assertEquals(1.1, TransitionRuntime.automaticPlan(pair).overlapRate[0], 1e-9);
        assertEquals(1.1, edit().overlapRate[0], 1e-9);
        assertFalse(edit().automaticTiming);
        assertNull("Opening writes nothing", TransitionRuntime.load(pair));
        tag("transition-save").performClick();
        databaseIdle();
        assertNull("Applying Auto leaves native tempo free", TransitionRuntime.load(pair));
    }

    @Test
    public void lateNativeRatesAreAdoptedOnUntouchedAutoAndNeverPinned() throws Exception {
        TransitionRuntime.apply(nativeRates(1.1, .9));
        open();
        TransitionRuntime.apply(nativeRates(1.2, .8));
        Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(1100));
        assertEquals(1.2, edit().overlapRate[0], 1e-9);
        assertEquals(.8, edit().overlapRate[1], 1e-9);
        assertFalse(edit().automaticTiming);
        assertNull(TransitionRuntime.load(TransitionRuntime.pair()));
    }

    @Test
    public void explicitTempoOnAutoKeepsItsRatesWhileTimingFollowsTheNativePlan() throws Exception {
        TransitionRuntime.apply(nativeRates(1.1, .9));
        open();
        edit().overlapRate[0] = 1.3;
        edit().overlapRate[1] = .7;
        edit().tempoExplicit = true;
        Plan next = nativeRates(1.2, .8);
        next.fadeSeconds = 12;
        TransitionRuntime.apply(next);
        Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(1100));
        assertEquals(1.3, edit().overlapRate[0], 1e-9);
        assertEquals(.7, edit().overlapRate[1], 1e-9);
        assertTrue(edit().automaticTiming);
        assertEquals(
                next.fadeSeconds / 1.2,
                TransitionRuntime.automaticPlan(TransitionRuntime.pair()).overlap,
                1e-9);
    }

    @Test
    public void manualTempoThenAutoIsPreservedStoredAndRetainedAcrossReopen() throws Exception {
        TransitionRuntime.apply(nativeRates(1.1, .9));
        open();
        edit().manualBpm[0] = 150;
        edit().manualBpm[1] = 110;
        edit().overlapRate[0] = 1.3;
        edit().overlapRate[1] = .7;
        edit().tempoExplicit = true;
        tag("transition-preset:2").performClick();
        tag("transition-preset:1").performClick();
        assertTrue(edit().automaticTiming);
        assertArrayEquals(new double[] {150, 110}, edit().manualBpm, 0);
        assertArrayEquals(new double[] {1.3, .7}, edit().overlapRate, 0);
        tag("transition-save").performClick();
        databaseIdle();
        assertTrue(TransitionRuntime.load(TransitionRuntime.pair()).automaticTiming);
        activity.pause().stop().destroy();
        activity = null;
        open();
        assertTrue(tag("transition-preset:1").isSelected());
        assertArrayEquals(new double[] {1.3, .7}, edit().overlapRate, 0);
        assertTrue(edit().automaticTiming);
    }

    @Test
    public void editingVolumeOrEqOnANativeAutoNeverPinsItsRates() throws Exception {
        TransitionRuntime.apply(nativeRates(1.1, .9));
        open();
        tag("transition-preset:3").performClick();
        assertFalse(edit().tempoExplicit);
        assertEquals(1.1, edit().overlapRate[0], 1e-9);
        TransitionRuntime.apply(nativeRates(1.2, .8));
        tag("transition-preset:1").performClick();
        assertFalse("Native rates are not the listener's choice", edit().tempoExplicit);
        assertEquals(1.2, edit().overlapRate[0], 1e-9);
        assertEquals(.8, edit().overlapRate[1], 1e-9);
        tag("transition-save").performClick();
        databaseIdle();
        assertNull(TransitionRuntime.load(TransitionRuntime.pair()));
    }

    @Test
    public void customFadesFollowFreshNativeRatesWithoutChangingTheDraftOrPlayback()
            throws Exception {
        RuntimeEnvironment.getApplication()
                .getSharedPreferences("bitchord_automix_editor", 0)
                .edit()
                .putBoolean("auto_save", false)
                .putBoolean("auto_apply", false)
                .commit();
        TransitionRuntime.apply(nativeRates(1.1, .9));
        open();
        tag("transition-preset:3").performClick();
        assertFalse(edit().automaticTiming);
        assertFalse(edit().tempoExplicit);
        assertArrayEquals(new double[] {1.1, .9}, edit().overlapRate, 0);
        TransitionEdit custom = edit().copy();
        MediaItem[] queued = items.clone();
        long at = position;
        PlaybackParameters parameters = speed;
        Map<String, ?> stored =
                new HashMap<>(
                        RuntimeEnvironment.getApplication()
                                .getSharedPreferences(
                                        TransitionRuntime.PREFERENCES, Context.MODE_PRIVATE)
                                .getAll());
        Plan next = nativeRates(1.08, 1.04);
        next.fadeSeconds = 14;
        next.incomingCueTime = 6;
        TransitionRuntime.apply(next);
        Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(1100));
        custom.overlapRate[0] = 1.08;
        custom.overlapRate[1] = 1.04;
        assertEquals(
                "Only native-derived rates follow the refreshed plan",
                custom.encode(),
                edit().encode());
        assertFalse(edit().tempoExplicit);
        assertNull(TransitionRuntime.load(TransitionRuntime.pair()));
        java.util.concurrent.atomic.AtomicReference<TransitionStore.Draft> draft =
                new java.util.concurrent.atomic.AtomicReference<>();
        TransitionStore.get().readDraft(TransitionRuntime.pair(), draft::set);
        databaseIdle();
        assertNotNull(draft.get());
        assertNull(draft.get().edit);
        assertNull(draft.get().error);
        assertEquals(
                stored,
                RuntimeEnvironment.getApplication()
                        .getSharedPreferences(TransitionRuntime.PREFERENCES, Context.MODE_PRIVATE)
                        .getAll());
        assertArrayEquals(queued, items);
        assertEquals(at, position);
        assertSame(parameters, speed);
        assertFalse(playing);
        assertTrue(mutations.isEmpty());
        assertTrue(previewDecks.isEmpty());
    }

    @Test
    public void aSavedManualDraftKeepsNativeRatesImplicitAcrossReopen() throws Exception {
        TransitionRuntime.apply(nativeRates(1.1, .9));
        open();
        tag("transition-preset:3").performClick();
        Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(700));
        databaseIdle();
        activity.pause().stop().destroy();
        activity = null;
        TransitionRuntime.apply(nativeRates(1.2, .8));
        open();
        assertFalse(edit().tempoExplicit);
        tag("transition-preset:1").performClick();
        assertFalse(edit().tempoExplicit);
        assertEquals(1.2, edit().overlapRate[0], 1e-9);
        assertEquals(.8, edit().overlapRate[1], 1e-9);
    }

    @Test
    public void anExplicitUnityResetOnNativeRatesSurvivesAutoApplyAndReopen() throws Exception {
        TransitionRuntime.apply(nativeRates(1.1, .9));
        open();
        tag("transition-tab:3").performClick();
        tag("transition-tempo-reset").performClick();
        assertTrue(edit().tempoExplicit);
        assertArrayEquals(new double[] {1, 1}, edit().overlapRate, 0);
        tag("transition-preset:1").performClick();
        assertTrue(edit().tempoExplicit);
        assertArrayEquals(new double[] {1, 1}, edit().overlapRate, 0);
        tag("transition-save").performClick();
        databaseIdle();
        TransitionEdit stored = TransitionRuntime.load(TransitionRuntime.pair());
        assertTrue(stored.automaticTiming);
        assertTrue(stored.tempoExplicit);
        activity.pause().stop().destroy();
        activity = null;
        open();
        assertTrue(tag("transition-preset:1").isSelected());
        assertArrayEquals(new double[] {1, 1}, edit().overlapRate, 0);
        assertTrue(edit().tempoExplicit);
    }

    @Test
    public void autoChosenFromUntouchedAutoStaysNative() throws Exception {
        TransitionRuntime.apply(nativeRates(1.1, .9));
        open();
        tag("transition-preset:1").performClick();
        assertFalse(edit().automaticTiming);
        assertEquals(1.1, edit().overlapRate[0], 1e-9);
    }

    @Test
    public void barLengthsFollowTheOverlapTempoAndGridScaleWithoutFakingAnalysis()
            throws Exception {
        open();
        edit().manualBpm[0] = 160;
        edit().overlapRate[0] = 1.25;
        tag("transition-tab:3").performClick();
        layout(393, 852);
        tag("transition-bars:4").performClick();
        assertEquals(4, edit().bars);
        assertEquals(TransitionEdit.secondsForBars(4, 200), edit().overlap, 1e-9);
        edit().tempoScale[0] = .5;
        tag("transition-bars:2").performClick();
        assertEquals(TransitionEdit.secondsForBars(2, 100), edit().overlap, 1e-9);
        String analysis = tag("automix-analysis").getContentDescription().toString();
        assertTrue(analysis, analysis.contains("130") && !analysis.contains("160"));
        tag("automix-analysis").performClick();
        View card =
                org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
                        .getWindow()
                        .getDecorView()
                        .findViewWithTag("automix-analysis-track:0");
        assertTrue(containsText(card, "130"));
        assertFalse(containsText(card, "160"));
    }

    @Test
    public void filterDepthSliderEditsTheFieldAndUndoRestoresIt() throws Exception {
        open();
        tag("transition-preset:3").performClick();
        tag("transition-tab:2").performClick();
        layout(393, 852);
        SeekBar depth = null;
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(root());
        while (!pending.isEmpty()) {
            View view = pending.removeFirst();
            if (view instanceof ViewGroup)
                for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
                    pending.add(((ViewGroup) view).getChildAt(i));
            if (view instanceof SeekBar
                    && String.valueOf(view.getContentDescription()).toLowerCase().contains("depth"))
                depth = (SeekBar) view;
        }
        assertNotNull("Effects offers a filter depth slider", depth);
        Bundle args = new Bundle();
        args.putFloat(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,
                500f);
        assertTrue(
                depth.performAccessibilityAction(
                        android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
                                .ACTION_SET_PROGRESS
                                .getId(),
                        args));
        assertEquals(.5, edit().filterDepth, .01);
        menuUndo();
        assertEquals(1, edit().filterDepth, 1e-9);
    }

    @Test
    @Config(qualifiers = "w393dp-h852dp-notnight-mdpi")
    public void rateCaptionFollowsFontScaleAndFitsItsCapsule() throws Exception {
        RuntimeEnvironment.setFontScale(2f);
        try {
            open();
            layout(393, 852);
            for (String tag : new String[] {"automix-rate:0", "automix-rate:1"}) {
                TextView caption = (TextView) tag(tag);
                assertTrue(caption.isShown());
                assertTrue("Caption scales with text size", caption.getTextSize() >= 24);
                assertNotNull(caption.getLayout());
                assertEquals(1, caption.getLineCount());
                assertTrue(
                        caption.getLayout().getLineWidth(0)
                                <= caption.getWidth()
                                        - caption.getTotalPaddingLeft()
                                        - caption.getTotalPaddingRight());
            }
        } finally {
            RuntimeEnvironment.setFontScale(1f);
        }
    }

    private void touch(View view, int action) {
        MotionEvent event =
                MotionEvent.obtain(0, 0, action, view.getWidth() / 2f, view.getHeight() / 2f, 0);
        try {
            view.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private void heldAdjustmentSuspendsPendingAutoApply(String holder) throws Exception {
        playing = true;
        open();
        mode("automix-auto-apply", true);
        tag("transition-preset:3").performClick();
        layout(393, 852);
        View held = tag(holder);
        assertTrue(held.isShown() && held.isEnabled() && held.getWidth() > 0);
        touch(held, MotionEvent.ACTION_DOWN);
        Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(1500));
        databaseIdle();
        assertNull(
                "Nothing commits while the control is held",
                TransitionRuntime.load(TransitionRuntime.pair()));
        assertTrue(playing);
        assertEquals(Collections.emptyList(), mutations);
        assertEquals(1.25f, speed.speed, 0);
        touch(held, MotionEvent.ACTION_CANCEL);
        Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofSeconds(2));
        databaseIdle();
        assertNotNull(
                "The pending edit applies after release",
                TransitionRuntime.load(TransitionRuntime.pair()));
    }

    @Test
    public void holdingTheModeHandleSuspendsAPendingAutoApply() throws Exception {
        heldAdjustmentSuspendsPendingAutoApply("automix-auto-apply");
    }

    @Test
    public void holdingTheOverlapSliderSuspendsAPendingAutoApply() throws Exception {
        heldAdjustmentSuspendsPendingAutoApply("automix-overlap-slider");
    }

    private String trackTitle(int side) {
        ViewGroup names = (ViewGroup) ((ViewGroup) tag("transition-track:" + side)).getChildAt(1);
        return ((TextView) names.getChildAt(0)).getText().toString();
    }

    /** Picks a file through the real Tools button and the activity-result path. */
    private void chooseFile(int side, String name, long millis) throws Exception {
        TransitionPairSelection.reader =
                (context, uri) -> new TransitionPairSelection.Info(name, "Artist", millis);
        tag("transition-tab:3").performClick();
        layout(393, 852);
        tag("transition-load:" + side).performClick();
        android.content.Intent data =
                new android.content.Intent()
                        .setData(
                                android.net.Uri.parse("content://picked/" + name.replace(' ', '-')))
                        .addFlags(
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        | android.content.Intent
                                                .FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        activity.get()
                .onActivityResult(
                        side == 0
                                ? TransitionPairSelection.OUTGOING_REQUEST
                                : TransitionPairSelection.INCOMING_REQUEST,
                        android.app.Activity.RESULT_OK,
                        data);
        long end = System.nanoTime() + 5_000_000_000L;
        while (!name.equals(trackTitle(side))) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            if (System.nanoTime() > end) fail("The chosen file never reached the editor");
            Thread.sleep(5);
        }
        databaseIdle();
        layout(393, 852);
    }

    private void threeSongQueue() {
        items =
                new MediaItem[] {
                    items[0],
                    items[1],
                    new MediaItem.Builder()
                            .setMediaId("local:rest")
                            .setUri("content://audio/3")
                            .setMediaMetadata(new MediaMetadata.Builder().setTitle("Rest").build())
                            .build()
                };
    }

    /** Capsule rows excluding the label, in pixels at mdpi. */
    private int painted(View view) {
        Bitmap image =
                Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.getBackground().setBounds(0, 0, view.getWidth(), view.getHeight());
        view.getBackground().draw(new Canvas(image));
        int top = -1, bottom = -1;
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++)
                if (android.graphics.Color.alpha(image.getPixel(x, y)) > 200) {
                    if (top < 0) top = y;
                    bottom = y;
                    break;
                }
        return top < 0 ? 0 : bottom - top + 1;
    }

    private void assertPresetsPaintInsideTheTarget(boolean largeText) {
        for (int i = 0; i < 8; i++) {
            TextView preset = (TextView) tag("transition-preset:" + i);
            assertTrue(preset.getHeight() >= 48);
            int paint = painted(preset);
            assertTrue("Preset " + i + " paints inside its target", paint < preset.getHeight());
            if (largeText) {
                assertTrue(preset.getLayout().getHeight() <= paint);
                assertTrue(
                        preset.getLayout().getLineWidth(0)
                                <= preset.getWidth()
                                        - preset.getTotalPaddingLeft()
                                        - preset.getTotalPaddingRight());
            } else assertEquals("Preset " + i, 34, paint, 1);
        }
    }

    @Test
    public void presetButtonsPaintAboutThirtyFourDpInsideA48dpTarget() throws Exception {
        open();
        assertPresetsPaintInsideTheTarget(false);
        assertTrue(tag("transition-save").getHeight() >= 48);
        assertTrue(tag("automix-save-draft").getHeight() >= 48);
        assertEquals(painted(tag("transition-preset:0")), painted(tag("transition-preset:7")));
    }

    @Test
    public void presetButtonsGrowWithTheTextAtDoubleFontScaleWithoutClipping() throws Exception {
        RuntimeEnvironment.setFontScale(2f);
        try {
            open();
            layout(393, 852);
            assertPresetsPaintInsideTheTarget(true);
            assertTrue(tag("transition-save").getHeight() >= 48);
        } finally {
            RuntimeEnvironment.setFontScale(1f);
        }
    }

    private void queueMoves() {
        service.trackAnalyzer.results.put("local:rest", new Analysis());
        index = 1;
        position = 0;
        duration = 180000;
    }

    private void settle() throws Exception {
        for (int i = 0; i < 6; i++) {
            Shadows.shadowOf(android.os.Looper.getMainLooper())
                    .idleFor(java.time.Duration.ofMillis(500));
            databaseIdle();
        }
        layout(393, 852);
    }

    private TransitionStore.Draft draftOf(TransitionRuntime.Pair target) throws Exception {
        TransitionStore.Draft[] found = new TransitionStore.Draft[1];
        TransitionStore.get().readDraft(target, draft -> found[0] = draft);
        databaseIdle();
        return found[0];
    }

    private void assertQueueUntouched() {
        assertEquals(Collections.emptyList(), mutations);
        for (String call : calls) assertFalse(call, call.startsWith("replace"));
    }

    @Test
    public void aCleanSavedPairFollowsTheQueueOnANaturalHandoff() throws Exception {
        playing = true;
        threeSongQueue();
        open();
        tag("transition-save").performClick();
        databaseIdle();
        TransitionRuntime.Pair old = TransitionRuntime.pair();
        tag("automix-analysis").performClick();
        android.app.AlertDialog analysis =
                org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        mutations.clear();
        calls.clear();
        queueMoves();
        settle();
        assertEquals("Open Skies", trackTitle(0));
        assertEquals("Rest", trackTitle(1));
        assertTrue(analysis.isShowing());
        assertTrue(
                containsText(
                        analysis.getWindow()
                                .getDecorView()
                                .findViewWithTag("automix-analysis-track:0"),
                        "Open Skies"));
        assertTrue(tag("transition-save").isEnabled());
        assertNull(TransitionRuntime.load(TransitionRuntime.pair()));
        assertNull(draftOf(TransitionRuntime.pair()).edit);
        assertNull(TransitionRuntime.load(old));
        assertQueueUntouched();
        assertTrue(playing);
        assertTrue(
                RuntimeEnvironment.getApplication()
                        .getContentResolver()
                        .getPersistedUriPermissions()
                        .isEmpty());
    }

    private void dirtyThenHandOff(long beforeMs) throws Exception {
        threeSongQueue();
        open();
        TransitionRuntime.Pair old = TransitionRuntime.pair();
        tag("transition-preset:3").performClick();
        if (beforeMs > 0)
            Shadows.shadowOf(android.os.Looper.getMainLooper())
                    .idleFor(java.time.Duration.ofMillis(beforeMs));
        queueMoves();
        settle();
        TransitionStore.Draft kept = draftOf(old);
        assertNotNull("The old pair's draft is written before the editor leaves it", kept.edit);
        assertEquals(TransitionEdit.RISE, kept.edit.preset);
        assertEquals("Open Skies", trackTitle(0));
        assertNotEquals(TransitionEdit.RISE, edit().preset);
        assertNull(draftOf(TransitionRuntime.pair()).edit);
        assertNull(TransitionRuntime.load(old));
        assertNull(TransitionRuntime.load(TransitionRuntime.pair()));
        assertQueueUntouched();
    }

    @Test
    public void aPendingAutoSaveOfTheOldPairIsWrittenBeforeFollowing() throws Exception {
        dirtyThenHandOff(0);
    }

    @Test
    public void aSaveAlreadyRunningForTheOldPairFinishesBeforeFollowing() throws Exception {
        dirtyThenHandOff(470);
    }

    @Test
    public void aFailedOldPairSaveKeepsTheEditorAndItsDraftInsteadOfFollowing() throws Exception {
        threeSongQueue();
        open();
        android.database.sqlite.SQLiteDatabase db =
                RuntimeEnvironment.getApplication()
                        .openOrCreateDatabase(TransitionStore.DATABASE, 0, null);
        db.execSQL("DROP TABLE drafts");
        db.close();
        tag("transition-preset:3").performClick();
        queueMoves();
        settle();
        assertEquals("Midnight Drive", trackTitle(0));
        assertEquals(TransitionEdit.RISE, edit().preset);
        assertTrue(containsText(root(), Strings.t("Draft not saved")));
        assertQueueUntouched();
    }

    @Test
    public void aDirtyDraftWithAutoSaveOffStaysUntilFollowIsChosen() throws Exception {
        threeSongQueue();
        open();
        mode("automix-auto-save", false);
        tag("transition-preset:3").performClick();
        tag("transition-tab:3").performClick();
        layout(393, 852);
        queueMoves();
        settle();
        assertEquals("Midnight Drive", trackTitle(0));
        assertEquals(TransitionEdit.RISE, edit().preset);
        assertTrue(containsText(root(), Strings.t("Unsaved draft")));
        View follow = tag("transition-follow");
        assertNotNull("The old draft offers an explicit way to follow", follow);
        follow.performClick();
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
                .getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
                .performClick();
        settle();
        assertEquals("Cancel keeps the draft", "Midnight Drive", trackTitle(0));
        assertEquals(TransitionEdit.RISE, edit().preset);
        tag("transition-follow").performClick();
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
                .getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .performClick();
        settle();
        assertEquals("Open Skies", trackTitle(0));
        assertNotEquals(TransitionEdit.RISE, edit().preset);
        assertQueueUntouched();
    }

    @Test
    public void aChosenFileAndAReversedPairNeverFollowTheQueueSilently() throws Exception {
        playing = true;
        threeSongQueue();
        open();
        chooseFile(1, "Local Song", 200000);
        tag("transition-reverse").performClick();
        mutations.clear();
        queueMoves();
        settle();
        assertEquals("Local Song", trackTitle(0));
        assertEquals("Midnight Drive", trackTitle(1));
        assertNotNull("The preview pair offers Use", tag("transition-use"));
        assertNotNull("and an explicit Follow", tag("transition-follow"));
        assertQueueUntouched();
        assertTrue(
                RuntimeEnvironment.getApplication()
                        .getContentResolver()
                        .getPersistedUriPermissions()
                        .isEmpty());
        tag("transition-follow").performClick();
        settle();
        assertEquals("Open Skies", trackTitle(0));
        assertQueueUntouched();
    }

    private boolean[] waveBusy() throws Exception {
        Field busy = TransitionEditorActivity.class.getDeclaredField("waveBusy");
        busy.setAccessible(true);
        return ((boolean[]) busy.get(activity.get())).clone();
    }

    private float[][][] waves() throws Exception {
        Field waves = TransitionEditorActivity.class.getDeclaredField("waves");
        waves.setAccessible(true);
        return ((float[][][]) waves.get(activity.get())).clone();
    }

    private void blockedOutgoingWave(boolean nextCached) throws Exception {
        TransitionWaveCache.clearMemory();
        if (nextCached) {
            TransitionRuntime.Pair pair = TransitionRuntime.pair();
            double[] range =
                    TransitionWaveCache.window(pair, TransitionRuntime.automaticDraft(pair), 1);
            TransitionWaveCache.load(
                    TransitionWaveCache.identity(pair, 1),
                    range[0],
                    range[1],
                    () -> new float[][] {{.1f, .2f}, {.3f, .4f}, {.5f, .6f}});
        }
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        var original = com.music.bitchord.playback.PlaybackService.waveDecoder;
        com.music.bitchord.playback.PlaybackService.waveDecoder =
                (source, from, to, abort, chunks) -> {
                    try {
                        while (!gate.await(20, java.util.concurrent.TimeUnit.MILLISECONDS))
                            if (abort.invoke()) return null;
                    } catch (InterruptedException stopped) {
                        return null;
                    }
                    return original.decode(source, from, to, abort, chunks);
                };
        try {
            activity = Robolectric.buildActivity(TransitionEditorActivity.class).setup();
            databaseIdle();
            layout(393, 852);
            boolean[] busy = waveBusy();
            assertTrue("The current track decodes first", busy[0]);
            assertFalse("The next track does not compete for the decoder", busy[1]);
            assertNull(waves()[0]);
            if (nextCached) assertNotNull("Available next peaks stay visible", waves()[1]);
            gate.countDown();
            Field jobs = TransitionEditorActivity.class.getDeclaredField("waveJobs");
            jobs.setAccessible(true);
            awaitWaves(jobs, activity.get());
            awaitWaves(jobs, activity.get());
            assertNotNull(waves()[0]);
            assertNotNull(waves()[1]);
        } finally {
            gate.countDown();
            com.music.bitchord.playback.PlaybackService.waveDecoder = original;
        }
    }

    @Test
    public void theNextWaveWaitsWhileTheCurrentWaveDecodes() throws Exception {
        blockedOutgoingWave(false);
    }

    @Test
    public void aFailedWaveWaitsForExplicitRetryThenRecoversWithoutPlaybackChanges()
            throws Exception {
        TransitionWaveCache.clearMemory();
        java.io.File directory =
                new java.io.File(
                        RuntimeEnvironment.getApplication().getCacheDir(), "automix_waveforms");
        java.io.File[] files = directory.listFiles();
        if (files != null) for (java.io.File file : files) file.delete();
        var original = com.music.bitchord.playback.PlaybackService.waveDecoder;
        java.util.concurrent.atomic.AtomicBoolean fail =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicInteger attempts =
                new java.util.concurrent.atomic.AtomicInteger();
        com.music.bitchord.playback.PlaybackService.waveDecoder =
                (source, from, to, abort, chunks) -> {
                    attempts.incrementAndGet();
                    return fail.get() ? null : original.decode(source, from, to, abort, chunks);
                };
        try {
            open();
            assertNull(waves()[0]);
            assertNull(waves()[1]);
            int stopped = attempts.get();
            assertTrue(stopped > 0);
            Shadows.shadowOf(android.os.Looper.getMainLooper())
                    .idleFor(java.time.Duration.ofMillis(2100));
            assertEquals("A failed window does not retry on every tick", stopped, attempts.get());
            fail.set(false);
            tag("automix-analysis").performClick();
            org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
                    .getButton(android.app.AlertDialog.BUTTON_NEUTRAL)
                    .performClick();
            Field jobs = TransitionEditorActivity.class.getDeclaredField("waveJobs");
            jobs.setAccessible(true);
            awaitWaves(jobs, activity.get());
            awaitWaves(jobs, activity.get());
            assertNotNull("Explicit Retry recovers the current waveform", waves()[0]);
            assertNotNull(waves()[1]);
            assertFalse(playing);
            assertTrue(mutations.isEmpty());
            assertTrue(previewDecks.isEmpty());
        } finally {
            com.music.bitchord.playback.PlaybackService.waveDecoder = original;
        }
    }

    @Test
    public void aCachedNextWaveShowsAtOnceWhileTheCurrentWaveIsBlocked() throws Exception {
        blockedOutgoingWave(true);
    }

    /** Holds the store's own single worker, so a started write stays unfinished. */
    private java.util.concurrent.CountDownLatch blockStore() throws Exception {
        Field member = TransitionStore.class.getDeclaredField("io");
        member.setAccessible(true);
        java.util.concurrent.ExecutorService worker =
                (java.util.concurrent.ExecutorService) member.get(TransitionStore.get());
        java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1),
                started = new java.util.concurrent.CountDownLatch(1);
        worker.submit(
                () -> {
                    started.countDown();
                    hold.await();
                    return null;
                });
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
        return hold;
    }

    private void deliverPick(int side, String name, long millis) {
        TransitionPairSelection.reader =
                (context, uri) -> new TransitionPairSelection.Info(name, "Artist", millis);
        android.content.Intent data =
                new android.content.Intent()
                        .setData(
                                android.net.Uri.parse("content://picked/" + name.replace(' ', '-')))
                        .addFlags(
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        | android.content.Intent
                                                .FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        activity.get()
                .onActivityResult(
                        side == 0
                                ? TransitionPairSelection.OUTGOING_REQUEST
                                : TransitionPairSelection.INCOMING_REQUEST,
                        android.app.Activity.RESULT_OK,
                        data);
    }

    private void launchPicker(int side) {
        tag("transition-tab:3").performClick();
        layout(393, 852);
        tag("transition-load:" + side).performClick();
    }

    private void waitMain(long millis) throws Exception {
        long end = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < end) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            Thread.sleep(5);
        }
    }

    private Object editorField(String name) throws Exception {
        Field member = TransitionEditorActivity.class.getDeclaredField(name);
        member.setAccessible(true);
        return member.get(activity.get());
    }

    private void assertNoGrantsTaken() {
        assertTrue(
                RuntimeEnvironment.getApplication()
                        .getContentResolver()
                        .getPersistedUriPermissions()
                        .isEmpty());
    }

    @Test
    public void aChosenFileWaitsForTheOldPairsInitialAndLatestDraftWrites() throws Exception {
        playing = true;
        open();
        TransitionRuntime.Pair old = TransitionRuntime.pair();
        launchPicker(1);
        java.util.concurrent.CountDownLatch hold = blockStore();
        try {
            tag("transition-preset:3").performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper())
                    .idleFor(java.time.Duration.ofMillis(500));
            tag("transition-preset:4").performClick();
            int latest = edit().preset;
            assertEquals(TransitionEdit.BLEND, latest);
            mutations.clear();
            deliverPick(1, "Local Song", 200000);
            waitMain(400);
            assertEquals(
                    "The old pair stays while its draft is unsaved", "Open Skies", trackTitle(1));
            assertEquals(latest, edit().preset);
            hold.countDown();
            settle();
            assertEquals("Local Song", trackTitle(1));
            TransitionStore.Draft kept = draftOf(old);
            assertNotNull(kept.edit);
            assertEquals("The latest edit is what was saved", latest, kept.edit.preset);
            assertNull(TransitionRuntime.load(old));
            assertQueueUntouched();
            assertNoGrantsTaken();
            assertTrue(playing);
        } finally {
            hold.countDown();
        }
    }

    @Test
    public void aPendingChosenFileSurvivesRotationWhileTheOldDraftIsWriting() throws Exception {
        open();
        TransitionRuntime.Pair old = TransitionRuntime.pair();
        launchPicker(1);
        java.util.concurrent.CountDownLatch hold = blockStore();
        try {
            tag("transition-preset:3").performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper())
                    .idleFor(java.time.Duration.ofMillis(500));
            tag("transition-preset:4").performClick();
            deliverPick(1, "Local Song", 200000);
            waitMain(400);
            activity.recreate();
            layout(393, 852);
            assertEquals("Open Skies", trackTitle(1));
            hold.countDown();
            settle();
            assertEquals("The chosen file survives the rotation", "Local Song", trackTitle(1));
            TransitionStore.Draft kept = draftOf(old);
            assertNotNull(kept.edit);
            assertEquals(TransitionEdit.BLEND, kept.edit.preset);
            assertQueueUntouched();
            assertNoGrantsTaken();
        } finally {
            hold.countDown();
        }
    }

    @Test
    public void aFailedOldDraftSaveKeepsTheChoiceAndTheEditUntilRetrySucceeds() throws Exception {
        threeSongQueue();
        open();
        TransitionRuntime.Pair old = TransitionRuntime.pair();
        launchPicker(1);
        assertNotNull(Shadows.shadowOf(activity.get()).getNextStartedActivityForResult());
        android.database.sqlite.SQLiteDatabase db =
                RuntimeEnvironment.getApplication()
                        .openOrCreateDatabase(TransitionStore.DATABASE, 0, null);
        db.execSQL(
                "CREATE TRIGGER no_draft BEFORE INSERT ON drafts BEGIN"
                        + " SELECT RAISE(ABORT, 'unavailable'); END");
        try {
            tag("transition-preset:3").performClick();
            deliverPick(1, "Local Song", 200000);
            settle();
            assertEquals("Open Skies", trackTitle(1));
            assertEquals(TransitionEdit.RISE, edit().preset);
            assertTrue(containsText(root(), Strings.t("Draft not saved")));
            queueMoves();
            settle();
            tag("transition-tab:3").performClick();
            layout(393, 852);
            tag("transition-follow").performClick();
            org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
                    .getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
                    .performClick();
            assertEquals("Cancel keeps the failed draft", "Midnight Drive", trackTitle(0));
            db.execSQL("DROP TRIGGER no_draft");
            mode("automix-auto-save", false);
            tag("automix-save-draft").performClick();
            settle();
            assertEquals("The kept choice binds without the picker", "Local Song", trackTitle(1));
            assertNull(Shadows.shadowOf(activity.get()).peekNextStartedActivityForResult());
            assertEquals(TransitionEdit.RISE, draftOf(old).edit.preset);
            assertQueueUntouched();
        } finally {
            db.close();
        }
    }

    private void assertChoiceKeptOnCancelAndDroppedOnDiscard(Runnable choose) throws Exception {
        playing = true;
        open();
        mode("automix-auto-save", false);
        TransitionRuntime.Pair old = TransitionRuntime.pair();
        tag("transition-preset:3").performClick();
        tag("transition-tab:3").performClick();
        layout(393, 852);
        mutations.clear();
        choose.run();
        waitMain(300);
        android.app.AlertDialog dialog =
                org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("Changing a dirty pair requires a choice", dialog);
        assertTrue("A dirty draft asks before the pair changes", dialog.isShowing());
        assertEquals("Open Skies", trackTitle(1));
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick();
        waitMain(100);
        assertEquals("Open Skies", trackTitle(1));
        assertEquals(TransitionEdit.RISE, edit().preset);
        assertSame(
                "The chooser keeps the pair that is shown",
                editorField("pair"),
                ((TransitionPairSelection) editorField("selection")).current());
        choose.run();
        waitMain(300);
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().cancel();
        waitMain(100);
        assertEquals("Cancelling the dialog keeps the draft", "Open Skies", trackTitle(1));
        assertEquals(TransitionEdit.RISE, edit().preset);
        assertSame(
                editorField("pair"),
                ((TransitionPairSelection) editorField("selection")).current());
        choose.run();
        waitMain(300);
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
                .getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .performClick();
        settle();
        assertNotEquals("Open Skies", trackTitle(1));
        assertNotEquals(TransitionEdit.RISE, edit().preset);
        assertNull("Discarding saves nothing", draftOf(old).edit);
        assertQueueUntouched();
        assertTrue(playing);
        assertNoGrantsTaken();
    }

    @Test
    public void reversingADirtyDraftWithAutoSaveOffAsksBeforeChangingThePair() throws Exception {
        assertChoiceKeptOnCancelAndDroppedOnDiscard(() -> tag("transition-reverse").performClick());
    }

    @Test
    public void loadingAFileOverADirtyDraftWithAutoSaveOffAsksBeforeChangingThePair()
            throws Exception {
        assertChoiceKeptOnCancelAndDroppedOnDiscard(
                () -> {
                    tag("transition-load:1").performClick();
                    deliverPick(1, "Local Song", 200000);
                });
    }

    @Test
    public void largeFontGraphsKeepFullTargetsAndRestoreWhenTheViewportGrows() throws Exception {
        for (MediaItem item : items) {
            Analysis low = (Analysis) service.trackAnalyzer.results.get(item.mediaId);
            low.bpm = 161.916;
            low.beatConfidence = .3;
            low.keyConfidence = .3;
        }
        RuntimeEnvironment.setFontScale(2f);
        try {
            open();
            layout(320, 852);
            boolean compact = tag("automix-expanded").getVisibility() != View.VISIBLE;
            View graph = tag(compact ? "automix-mini-timeline" : "transition-timeline");
            View preview = tag(compact ? "automix-mini-preview" : "transition-preview");
            assertTrue("The graph keeps a full touch height", graph.getHeight() >= 48);
            assertTrue(preview.getHeight() >= 48 && preview.getWidth() >= 48);
            assertTrue(at(preview)[1] >= at(graph)[1]);
            assertTrue(at(preview)[1] + preview.getHeight() <= at(graph)[1] + graph.getHeight());
            if (compact) {
                render("automix-editor-compact-estimated-font2.png", 320, 852);
                double before = edit().incomingCue;
                float x = graph.getWidth() * .5f, y = graph.getHeight() * .75f;
                gesture(graph, x, y, graph.getWidth() * .25f, y);
                assertTrue(
                        "The compact waveform remains editable", edit().incomingCue > before + .1);
                preview.performClick();
                advancePreview();
                assertEquals(2, previewDecks.size());
                preview.performClick();
                layout(320, 1200);
                tag("automix-expand").performClick();
                layout(320, 1200);
            }
            assertEquals(View.VISIBLE, tag("automix-expanded").getVisibility());
            assertTrue(tag("transition-timeline").getHeight() >= 48);
        } finally {
            RuntimeEnvironment.setFontScale(1f);
        }
    }

    private View findText(View view, String text) {
        if (view instanceof TextView && ((TextView) view).getText().toString().equals(text))
            return view;
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                View found = findText(((ViewGroup) view).getChildAt(i), text);
                if (found != null) return found;
            }
        return null;
    }

    private EditText findInput(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                EditText found = findInput(((ViewGroup) view).getChildAt(i));
                if (found != null) return found;
            }
        return null;
    }

    /** The typed overlap sheet is open with the value entered but not yet saved. */
    private View typedOverlapSheet(String text) {
        tag("automix-overlap-value").performClick();
        android.app.Dialog dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertTrue(dialog.isShowing());
        findInput(dialog.getWindow().getDecorView()).setText(text);
        return dialog.getWindow().getDecorView();
    }

    private TransitionRuntime.Pair handOffWhileEditing(View sheet) throws Exception {
        TransitionRuntime.Pair old = TransitionRuntime.pair();
        queueMoves();
        settle();
        assertTrue(
                "The sheet stays open",
                org.robolectric.shadows.ShadowDialog.getLatestDialog().isShowing());
        assertEquals(
                "The old pair stays while its value is being typed",
                "Midnight Drive",
                trackTitle(0));
        assertEquals("Open Skies", trackTitle(1));
        return old;
    }

    @Test
    public void savingATypedValueAfterAHandoffAppliesItToTheOldPairOnly() throws Exception {
        threeSongQueue();
        open();
        View sheet = typedOverlapSheet("6");
        TransitionRuntime.Pair old = handOffWhileEditing(sheet);
        findText(sheet, Strings.t("Save")).performClick();
        settle();
        TransitionStore.Draft kept = draftOf(old);
        assertNotNull("The typed value is saved with the pair it was typed for", kept.edit);
        assertEquals(6, kept.edit.overlap, 1e-9);
        assertEquals("Open Skies", trackTitle(0));
        assertNull(draftOf(TransitionRuntime.pair()).edit);
        assertNull(TransitionRuntime.load(TransitionRuntime.pair()));
        assertQueueUntouched();
    }

    @Test
    public void cancellingATypedValueLetsTheEditorFollowWithoutWritingEitherPair()
            throws Exception {
        threeSongQueue();
        open();
        View sheet = typedOverlapSheet("6");
        TransitionRuntime.Pair old = handOffWhileEditing(sheet);
        findText(sheet, Strings.t("Cancel")).performClick();
        settle();
        assertEquals("Open Skies", trackTitle(0));
        assertNull(draftOf(old).edit);
        assertNull(draftOf(TransitionRuntime.pair()).edit);
        assertQueueUntouched();
    }

    @Test
    public void aHeldGestureKeepsTheOldPairUntilItIsReleased() throws Exception {
        threeSongQueue();
        open();
        TransitionRuntime.Pair old = TransitionRuntime.pair();
        tag("transition-preset:3").performClick();
        View slider = tag("automix-overlap-slider");
        touch(slider, MotionEvent.ACTION_DOWN);
        queueMoves();
        settle();
        assertEquals("The pair under the finger is not swapped", "Midnight Drive", trackTitle(0));
        assertEquals(TransitionEdit.RISE, edit().preset);
        touch(slider, MotionEvent.ACTION_UP);
        settle();
        assertEquals("Open Skies", trackTitle(0));
        TransitionStore.Draft kept = draftOf(old);
        assertNotNull(kept.edit);
        assertEquals(TransitionEdit.RISE, kept.edit.preset);
        assertNull(draftOf(TransitionRuntime.pair()).edit);
        assertNull(TransitionRuntime.load(TransitionRuntime.pair()));
        assertQueueUntouched();
    }

    @Test
    public void loadingAndReversingOnlyChangeThePreviewPair() throws Exception {
        playing = true;
        open();
        mutations.clear();
        MediaItem[] before = items.clone();
        chooseFile(1, "Local Song", 200000);
        assertEquals("Midnight Drive", trackTitle(0));
        tag("transition-reverse").performClick();
        assertEquals("Local Song", trackTitle(0));
        assertEquals("Midnight Drive", trackTitle(1));
        assertArrayEquals(before, items);
        assertTrue(playing);
        assertEquals(12000, position);
        assertEquals(Collections.emptyList(), mutations);
        assertFalse(calls.contains("pause"));
        assertTrue(
                "Choosing a file takes no queue grant",
                RuntimeEnvironment.getApplication()
                        .getContentResolver()
                        .getPersistedUriPermissions()
                        .isEmpty());
        assertTrue(previewDecks.isEmpty());
    }

    @Test
    public void previewStartsFromTheQueueBaselineAndNeverWritesTheQueue() throws Exception {
        playing = true;
        open();
        chooseFile(1, "Local Song", 200000);
        MediaItem[] before = items.clone();
        tag("transition-preview").performClick();
        advancePreview();
        assertFalse("The listening song pauses once the preview starts", playing);
        assertEquals(2, previewDecks.size());
        assertArrayEquals(before, items);
        for (String call : calls) assertFalse(call, call.startsWith("replace"));
    }

    @Test
    public void rotatingALocalPreviewKeepsItsDraftAndBothFileChoicesWithoutPausing()
            throws Exception {
        playing = true;
        open();
        mode("automix-auto-save", false);
        chooseFile(1, "Local In", 200000);
        tag("transition-preset:3").performClick();
        String before = edit().encode();
        mutations.clear();
        activity.recreate();
        databaseIdle();
        layout(393, 852);
        assertEquals("Local In", trackTitle(1));
        assertEquals(before, edit().encode());
        assertTrue(playing);
        assertTrue(mutations.isEmpty());
        assertTrue(
                RuntimeEnvironment.getApplication()
                        .getContentResolver()
                        .getPersistedUriPermissions()
                        .isEmpty());
        // With auto-save off, a changed draft is saved before another file replaces the pair.
        tag("automix-save-draft").performClick();
        databaseIdle();
        chooseFile(0, "Local Out", 210000);
        assertEquals("Local In", trackTitle(1));
        tag("automix-save-draft").performClick();
        databaseIdle();
        assertEquals(
                2,
                RuntimeEnvironment.getApplication()
                        .getContentResolver()
                        .getPersistedUriPermissions()
                        .size());
        assertTrue(playing);
        assertTrue(mutations.isEmpty());
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-night-mdpi")
    public void wrappedToolActionsShareOneHeightAtDoubleFontScale() throws Exception {
        RuntimeEnvironment.setFontScale(2f);
        try {
            open();
            tag("transition-tab:3").performClick();
            layout(320, 640);
            TextView reverse = (TextView) tag("transition-reverse");
            TextView recall = (TextView) tag("transition-recall");
            assertTrue("The real label wraps", recall.getLineCount() > 1);
            assertEquals(reverse.getHeight(), recall.getHeight());
            assertTrue(reverse.getHeight() >= 48);
            for (TextView action : new TextView[] {reverse, recall})
                assertTrue(
                        action.getLayout().getHeight()
                                <= action.getHeight()
                                        - action.getCompoundPaddingTop()
                                        - action.getCompoundPaddingBottom());
        } finally {
            RuntimeEnvironment.setFontScale(1f);
        }
    }

    @Test
    public void anInFlightDraftSaveKeepsItsGrantAcrossActivityRecreation() throws Exception {
        open();
        mode("automix-auto-save", false);
        chooseFile(1, "Local In", 200000);
        tag("transition-preset:3").performClick();
        Field field = TransitionStore.class.getDeclaredField("io");
        field.setAccessible(true);
        java.util.concurrent.ExecutorService io =
                (java.util.concurrent.ExecutorService) field.get(TransitionStore.get());
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Future<?> blocked =
                io.submit(
                        () -> {
                            entered.countDown();
                            try {
                                if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                                    throw new AssertionError("The draft writer was not released");
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError(interrupted);
                            }
                        });
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            tag("automix-save-draft").performClick();
            assertEquals(
                    "Saving draft…", ((TextView) tag("automix-save-state")).getText().toString());
            assertFalse(tag("automix-save-draft").isEnabled());
            activity.recreate();
            assertEquals(
                    1,
                    RuntimeEnvironment.getApplication()
                            .getContentResolver()
                            .getPersistedUriPermissions()
                            .size());
        } finally {
            release.countDown();
            blocked.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        databaseIdle();
        layout(393, 852);
        assertEquals("Local In", trackTitle(1));
        assertEquals(TransitionEdit.RISE, edit().preset);
        tag("automix-save-draft").performClick();
        databaseIdle();
        chooseFile(0, "Local Out", 210000);
        tag("automix-save-draft").performClick();
        databaseIdle();
        assertEquals(
                2,
                RuntimeEnvironment.getApplication()
                        .getContentResolver()
                        .getPersistedUriPermissions()
                        .size());
        assertFalse(calls.contains("pause"));
    }

    @Test
    public void aDestroyedEditorsSaveRetryCannotOverwriteANewerManualSave() throws Exception {
        open();
        Field field = TransitionStore.class.getDeclaredField("io");
        field.setAccessible(true);
        java.util.concurrent.ExecutorService io =
                (java.util.concurrent.ExecutorService) field.get(TransitionStore.get());
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Future<?> blocked =
                io.submit(
                        () -> {
                            entered.countDown();
                            try {
                                if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                                    throw new AssertionError("The draft writer was not released");
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError(interrupted);
                            }
                        });
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            tag("transition-preset:2").performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper())
                    .idleFor(java.time.Duration.ofMillis(450));
            assertFalse(tag("automix-save-draft").isEnabled());
            tag("transition-preset:3").performClick();
            TransitionEditorActivity old = activity.get();
            activity.recreate();
            assertTrue(old.isDestroyed());
        } finally {
            release.countDown();
            blocked.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        databaseIdle();
        mode("automix-auto-save", false);
        tag("transition-preset:4").performClick();
        tag("automix-save-draft").performClick();
        databaseIdle();
        java.util.concurrent.atomic.AtomicReference<TransitionStore.Draft> stored =
                new java.util.concurrent.atomic.AtomicReference<>();
        TransitionStore.get().readDraft(TransitionRuntime.pair(), stored::set);
        databaseIdle();
        assertEquals(TransitionEdit.BLEND, stored.get().edit.preset);
        Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(600));
        databaseIdle();
        TransitionStore.get().readDraft(TransitionRuntime.pair(), stored::set);
        databaseIdle();
        assertEquals(
                "The destroyed editor must not replace the newer saved draft",
                TransitionEdit.BLEND,
                stored.get().edit.preset);
        assertFalse(
                RuntimeEnvironment.getApplication()
                        .getSharedPreferences("bitchord_automix_editor", 0)
                        .getBoolean("auto_save", true));
        assertNull(TransitionRuntime.load(TransitionRuntime.pair()));
    }

    @Test
    public void aDetachedPairCannotBeAppliedButItsDraftIsSavedWithoutActivation() throws Exception {
        open();
        mode("automix-auto-save", false);
        chooseFile(1, "Local Song", 200000);
        tag("transition-preset:3").performClick();
        View apply = tag("transition-save");
        assertFalse("Apply needs the queue to hold this pair", apply.isEnabled());
        apply.performClick();
        databaseIdle();
        assertNull(TransitionRuntime.load(TransitionRuntime.pair()));
        tag("automix-save-draft").performClick();
        databaseIdle();
        assertNull(
                "Saving a draft never activates it",
                TransitionRuntime.load(TransitionRuntime.pair()));
        assertFalse(
                "The saved draft keeps its file readable",
                RuntimeEnvironment.getApplication()
                        .getContentResolver()
                        .getPersistedUriPermissions()
                        .isEmpty());
        assertFalse(calls.contains("pause"));
        tag("transition-preset:0").performClick();
        tag("transition-tab:3").performClick();
        tag("transition-recall").performClick();
        databaseIdle();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(TransitionEdit.RISE, edit().preset);
    }

    @Test
    public void usingAPairReplacesOnlyTheCurrentAndNextSongAndSettlesOntoTheQueuePair()
            throws Exception {
        playing = true;
        threeSongQueue();
        open();
        chooseFile(0, "New Out", 200000);
        chooseFile(1, "New In", 210000);
        MediaItem untouched = items[2];
        View use = tag("transition-use");
        assertNotNull("A pair that differs from the queue offers Use", use);
        assertTrue(use.getHeight() >= 48);
        use.performClick();
        assertTrue(calls.contains("replace:0") && calls.contains("replace:1"));
        assertEquals("local:rest", items[2].mediaId);
        assertSame("Unrelated queue entries keep their identity", untouched, items[2]);
        assertEquals("content://picked/New-Out", items[0].localConfiguration.uri.toString());
        assertEquals("content://picked/New-In", items[1].localConfiguration.uri.toString());
        assertFalse(
                "The chosen files stay readable once the queue holds them",
                RuntimeEnvironment.getApplication()
                        .getContentResolver()
                        .getPersistedUriPermissions()
                        .isEmpty());
        Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofSeconds(3));
        databaseIdle();
        layout(393, 852);
        assertNull("The queue pair needs no Use button", tag("transition-use"));
        assertTrue(tag("transition-save").isEnabled());
        assertNull(
                "Using a pair never applies it", TransitionRuntime.load(TransitionRuntime.pair()));
    }

    @Test
    public void aDetachedDraftFollowsTheCanonicalPairAfterUseWithoutActivating() throws Exception {
        threeSongQueue();
        open();
        mode("automix-auto-save", false);
        chooseFile(0, "New Out", 200000);
        chooseFile(1, "New In", 210000);
        tag("transition-preset:3").performClick();
        tag("automix-save-draft").performClick();
        databaseIdle();
        tag("transition-tab:3").performClick();
        tag("transition-use").performClick();
        Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofSeconds(3));
        databaseIdle();
        assertNull(TransitionRuntime.load(TransitionRuntime.pair()));
        activity.pause().stop().destroy();
        activity = null;
        open();
        assertEquals("The reopened queue pair keeps the draft", TransitionEdit.RISE, edit().preset);
        assertNull(
                "Reopening never activates the draft",
                TransitionRuntime.load(TransitionRuntime.pair()));
        assertEquals("New Out", trackTitle(0));
    }

    @Test
    public void openingAnUnappliedDraftDoesNotArmAutomaticApply() throws Exception {
        open();
        mode("automix-auto-save", false);
        tag("transition-preset:3").performClick();
        tag("automix-save-draft").performClick();
        databaseIdle();
        activity.pause().stop().destroy();
        activity = null;
        RuntimeEnvironment.getApplication()
                .getSharedPreferences("bitchord_automix_editor", 0)
                .edit()
                .putBoolean("auto_apply", true)
                .commit();
        mutations.clear();
        open();
        Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofSeconds(2));
        databaseIdle();
        assertEquals(TransitionEdit.RISE, edit().preset);
        assertNull(
                "Opening a saved draft never activates it",
                TransitionRuntime.load(TransitionRuntime.pair()));
        assertListeningUntouched();
    }

    @Test
    public void useDoesNotApplyAnEarlierDetachedEditWhenAutomaticApplyIsEnabled() throws Exception {
        RuntimeEnvironment.getApplication()
                .getSharedPreferences("bitchord_automix_editor", 0)
                .edit()
                .putBoolean("auto_apply", true)
                .commit();
        open();
        chooseFile(0, "New Out", 200000);
        tag("transition-preset:3").performClick();
        tag("transition-tab:3").performClick();
        preparedDuration = 200000;
        tag("transition-use").performClick();
        Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofSeconds(3));
        databaseIdle();
        assertNull(
                "Use changes the queue without applying the draft",
                TransitionRuntime.load(TransitionRuntime.pair()));
        assertTrue(
                RuntimeEnvironment.getApplication()
                        .getSharedPreferences("bitchord_automix_editor", 0)
                        .getBoolean("auto_apply", false));
    }

    @Test
    public void closeWaitsForNativeDurationBeforeMigratingASavedLocalDraft() throws Exception {
        open();
        mode("automix-auto-save", false);
        chooseFile(0, "New Out", 200000);
        tag("transition-preset:3").performClick();
        tag("automix-save-draft").performClick();
        databaseIdle();
        tag("transition-tab:3").performClick();
        completePreparation = false;
        tag("transition-use").performClick();
        assertEquals(C.TIME_UNSET, duration);
        tag("transition-close").performClick();
        assertFalse(
                "Preparation has not supplied the canonical pair yet",
                activity.get().isFinishing());
        assertEquals(
                "Wait for the pair to finish loading",
                ((TextView) tag("transition-status")).getText().toString());
        activity.recreate();
        databaseIdle();
        layout(393, 852);
        assertEquals("New Out", trackTitle(0));
        assertEquals(TransitionEdit.RISE, edit().preset);
        assertEquals(C.TIME_UNSET, duration);
        duration = 180000;
        Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofSeconds(2));
        databaseIdle();
        tag("transition-close").performClick();
        assertTrue(activity.get().isFinishing());
        activity.pause().stop().destroy();
        activity = null;
        open();
        assertEquals(TransitionEdit.RISE, edit().preset);
        assertNull(TransitionRuntime.load(TransitionRuntime.pair()));
    }

    @Test
    public void reselectingFilesKeepsTheNewestPairAndItsOwnDraft() throws Exception {
        open();
        mode("automix-auto-save", false);
        chooseFile(1, "First", 200000);
        tag("transition-preset:3").performClick();
        tag("automix-save-draft").performClick();
        databaseIdle();
        chooseFile(1, "Second", 190000);
        chooseFile(1, "First", 200000);
        chooseFile(1, "Second", 190000);
        databaseIdle();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals("Second", trackTitle(1));
        assertNotEquals(
                "The newest selection has no draft of its own", TransitionEdit.RISE, edit().preset);
    }

    private boolean inScroller(View view) {
        for (android.view.ViewParent p = view.getParent(); p instanceof View; p = p.getParent())
            if (p instanceof ScrollView || p instanceof HorizontalScrollView) return true;
        return false;
    }

    /**
     * Every visible action is at least 48 high, its label is whole, and pinned ones are in view.
     */
    private void assertWholeTargets(int width, int height) {
        List<TextView> actions = new ArrayList<>();
        collect(root(), actions);
        assertTrue(actions.size() > 8);
        for (TextView action : actions) {
            String name = action.getTag() + " " + action.getText();
            assertTrue("height " + name, action.getHeight() >= 48);
            android.text.Layout text = action.getLayout();
            assertNotNull(name, text);
            assertTrue(
                    "label height " + name,
                    text.getHeight()
                            <= action.getHeight()
                                    - action.getCompoundPaddingTop()
                                    - action.getCompoundPaddingBottom());
            for (int line = 0; line < text.getLineCount(); line++) {
                assertEquals("ellipsis " + name, 0, text.getEllipsisCount(line));
                assertTrue(
                        "label width " + name,
                        text.getLineWidth(line)
                                <= action.getWidth()
                                        - action.getCompoundPaddingLeft()
                                        - action.getCompoundPaddingRight()
                                        + 1);
            }
            if (inScroller(action)) continue;
            int[] at = at(action);
            assertTrue("inside " + name, at[0] >= 0 && at[1] >= 0);
            assertTrue(
                    "inside " + name,
                    at[0] + action.getWidth() <= width && at[1] + action.getHeight() <= height);
        }
        View draft = tag("automix-save-draft"), apply = tag("transition-save");
        assertEquals(draft.getWidth(), apply.getWidth());
        assertEquals(at(draft)[1], at(apply)[1]);
    }

    @Test
    @Config(qualifiers = "w852dp-h393dp-land-night-mdpi")
    public void besideTheControlsTheWorkspaceCondensesOnlyWhenTheFullViewCannotFit()
            throws Exception {
        open();
        layout(852, 700);
        assertEquals(View.VISIBLE, tag("automix-expanded").getVisibility());
        assertTrue(tag("transition-preview").getHeight() >= 48);
        assertTrue(tag("transition-timeline").getHeight() >= 48);
        assertWholeTargets(852, 700);
        layout(852, 393);
        assertEquals(View.VISIBLE, tag("automix-compact").getVisibility());
        assertEquals(View.GONE, tag("automix-expanded").getVisibility());
        View preview = tag("automix-mini-preview"), graph = tag("automix-mini-timeline");
        assertEquals(48, preview.getHeight());
        assertTrue(graph.getHeight() >= 48);
        for (String rate : new String[] {"automix-mini-rate:0", "automix-mini-rate:1"})
            assertTrue(rate, tag(rate).getHeight() >= 48 && at(tag(rate))[1] + 48 <= 393);
        assertTrue("Presets stay in the side pane", tag("transition-preset:0").isShown());
        assertTrue(at(tag("automix-overlap-slider"))[1] + 48 <= 393);
        assertWholeTargets(852, 393);
        render("automix-editor-landscape-short.png", 852, 393);
        layout(852, 700);
        assertEquals(
                "The full view returns when it fits",
                View.VISIBLE,
                tag("automix-expanded").getVisibility());
    }

    @Test
    @Config(qualifiers = "w852dp-h393dp-land-night-mdpi")
    public void shortLandscapeAtDoubleFontKeepsEveryTargetWholeAndUnclipped() throws Exception {
        RuntimeEnvironment.setFontScale(2f);
        try {
            open();
            layout(852, 393);
            render("automix-editor-landscape-font2-green3.png", 852, 393);
            assertTrue(tag("automix-dock").isShown());
            assertEquals(
                    "One action row beside the controls",
                    at(tag("automix-save-draft"))[1],
                    at(tag("automix-analysis"))[1]);
            assertWholeTargets(852, 393);
            assertTrue(tag("transition-preset:0").isShown());
            assertEquals(
                    "The spoken label stays complete",
                    "Track analysis",
                    String.valueOf(tag("automix-analysis").getContentDescription()).split(",")[0]);
        } finally {
            RuntimeEnvironment.setFontScale(1f);
        }
    }

    @Test
    @Config(qualifiers = "w852dp-h393dp-land-night-mdpi")
    public void theRateSheetScrollsSoEveryActionStaysReachable() throws Exception {
        RuntimeEnvironment.setFontScale(2f);
        try {
            open();
            layout(852, 393);
            View rate =
                    tag("automix-rate:0").isShown()
                            ? tag("automix-rate:0")
                            : tag("automix-mini-rate:0");
            rate.performClick();
            ScrollView sheet = (ScrollView) dialogView("automix-rate-scroll");
            View decor =
                    org.robolectric.shadows.ShadowDialog.getLatestDialog()
                            .getWindow()
                            .getDecorView();
            decor.measure(
                    View.MeasureSpec.makeMeasureSpec(270, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.AT_MOST));
            decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight());
            assertTrue(sheet.getHeight() <= 200);
            View content = sheet.getChildAt(0);
            assertTrue("The content outgrows the window", content.getHeight() > sheet.getHeight());
            sheet.scrollTo(0, content.getHeight() - sheet.getHeight());
            View done = dialogView("automix-rate-done");
            int top = done.getTop() - sheet.getScrollY();
            assertTrue(
                    "Done is reachable", top >= 0 && top + done.getHeight() <= sheet.getHeight());
            done.performClick();
            assertFalse(org.robolectric.shadows.ShadowDialog.getLatestDialog().isShowing());
        } finally {
            RuntimeEnvironment.setFontScale(1f);
        }
    }
}

package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import app.bitchord.soulseek.DownloadSettings;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;

import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(
        manifest = Config.NONE,
        sdk = 35,
        qualifiers = "w393dp-h852dp-mdpi",
        shadows = {
            ExtrasPageTest.EverythingInstalled.class,
            PatchSettingsScreenTest.AutoMixInstalled.class,
            PatchSettingsScreenTest.PreviewInstalled.class
        },
        instrumentedPackages = {
            "app.bitchord.telegram.SoundExtras",
            "app.bitchord.telegram.SourceAutomix",
            "app.bitchord.soulseek.SoulseekPreview"
        })
public class PatchSettingsScreenTest {
    @Implements(value = SourceAutomix.class, isInAndroidSdk = false)
    public static class AutoMixInstalled {
        @Implementation
        protected static boolean applied() {
            return true;
        }
    }

    @Implements(value = app.bitchord.soulseek.SoulseekPreview.class, isInAndroidSdk = false)
    public static class PreviewInstalled {
        @Implementation
        protected static boolean installed() {
            return true;
        }
    }

    ActivityController<ExtrasActivity> controller;
    ExtrasActivity activity;
    Context app;

    @Before
    public void create() throws Exception {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences(SoundExtras.PREFERENCES, 0).edit().clear().commit();
        app.getSharedPreferences(EffectsApps.PREFERENCES, 0).edit().clear().commit();
        app.getSharedPreferences(OutputEq.HOST_PREFERENCES, 0).edit().clear().commit();
        java.lang.reflect.Field preferences = SoundExtras.class.getDeclaredField("preferences");
        preferences.setAccessible(true);
        preferences.set(null, null);
        preferences = EffectsApps.class.getDeclaredField("preferences");
        preferences.setAccessible(true);
        preferences.set(null, null);
        for (Class<?> type :
                new Class<?>[] {AudioFocus.class, SteadyPlayback.class, SmartDownloads.class}) {
            java.lang.reflect.Field state = type.getDeclaredField("preferences");
            state.setAccessible(true);
            state.set(null, null);
        }
        for (String store :
                new String[] {
                    AudioFocus.PREFERENCES, SteadyPlayback.PREFERENCES, SmartDownloads.PREFERENCES
                }) app.getSharedPreferences(store, 0).edit().clear().commit();
        controller = Robolectric.buildActivity(ExtrasActivity.class).setup().visible();
        activity = controller.get();
    }

    @After
    public void close() {
        controller.pause().stop().destroy();
    }

    @Test
    @Config(qualifiers = "w852dp-h393dp-land-mdpi")
    public void shortWindowsKeepControlsReachableWithOnePageScroll() {
        android.widget.ScrollView scroll = (android.widget.ScrollView) tagged("patch-list");
        assertSame(search(), scroll.findViewWithTag("patch-search"));
        assertNotNull(scroll.findViewWithTag("patch:pitch"));
        EditText input = search();
        input.requestFocus();
        input.setText("pitch");
        idle();
        assertSame(input, search());
        assertTrue(input.hasFocus());
        input.setText("");
        idle();
        scroll.scrollTo(0, 500);
        assertTrue(scroll.getScrollY() > 0);
    }

    @Test
    public void pageTitleHasATweaksIconAndBinaryRowsExplainTheirEffect() {
        TextView title = text("patch-page-title");
        assertNotNull(title.getCompoundDrawablesRelative()[0]);
        assertEquals("Patches & tweaks", title.getText().toString());
        assertTrue(text("summary:audio-focus").getText().toString().contains("calls"));
        assertTrue(text("summary:video-switch").getText().toString().contains("player"));
        String preview = text("summary:streamseek-preview").getText().toString();
        assertTrue(preview.contains("temporary") || preview.equals("Requires Soulseek downloads"));
        assertFalse(toggle("audio-focus").isChecked());
        assertEquals(View.GONE, tagged("settings:audio-focus").getVisibility());
    }

    @Test
    public void autoMixExplainsNativeAnalysisAndChosenSpeedBeforeTheEditor() {
        try {
            open("source-automix");
            View details = tagged("details:source-automix");
            java.util.List<View> contents = new java.util.ArrayList<>();
            ExtrasPageTest.collect(details, contents);
            StringBuilder copy = new StringBuilder();
            for (View item : contents)
                if (item instanceof TextView) copy.append(((TextView) item).getText()).append('\n');
            assertTrue(copy.toString().contains("BitChord’s AutoMix"));
            assertTrue(copy.toString().contains("audio that will play"));
            assertTrue(copy.toString().contains("chosen playback speed"));
            assertTrue(copy.toString().contains("Transition editor"));
        } finally {
            com.music.bitchord.data.settings.AppSettings.setSmartFadeEnabled(false);
        }
    }

    @Test
    public void storageSenseIsASearchableUtilityWithoutAnEnableSwitch() {
        Map<String, ?> before = app.getSharedPreferences(SoundExtras.PREFERENCES, 0).getAll();
        View storage = tagged("patch-storage-sense");
        assertNull(
                activity.findViewById(android.R.id.content)
                        .findViewWithTag("enable:storage-sense"));
        search().setText("cached audio");
        assertEquals(View.VISIBLE, ((View) storage.getParent()).getVisibility());
        assertEquals(View.GONE, tagged("patch:pitch").getVisibility());
        assertEquals(View.GONE, tagged("patch-empty").getVisibility());
        assertTrue(text("patch-counts").getText().toString().endsWith("0 shown"));
        storage.performClick();
        android.content.Intent opened = Shadows.shadowOf(activity).getNextStartedActivity();
        assertEquals(StorageSenseActivity.class.getName(), opened.getComponent().getClassName());
        assertEquals(before, app.getSharedPreferences(SoundExtras.PREFERENCES, 0).getAll());
        search().setText("");
        tagged("patch-category:audio").performClick();
        assertEquals(View.GONE, ((View) storage.getParent()).getVisibility());
        tagged("patch-category:library").performClick();
        assertEquals(View.VISIBLE, ((View) storage.getParent()).getVisibility());
    }

    @Test
    public void categoryLabelsRetainBreathingRoomAcrossSelectionAndSearch() {
        for (String id : new String[] {"audio", "playback", "library", "sources", "all", "audio"}) {
            tagged("patch-category:" + id).performClick();
            search().setText("audio");
            search().setText("");
            for (String chip : new String[] {"all", "sources", "playback", "audio", "library"}) {
                TextView label = text("patch-category:" + chip);
                assertEquals(14, label.getPaddingStart());
                assertEquals(14, label.getPaddingEnd());
                assertTrue(label.getMinHeight() >= 48);
                label.measure(
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                assertTrue(
                        label.getMeasuredWidth()
                                >= label.getPaint().measureText(label.getText().toString()) + 28);
            }
        }
    }

    View tagged(String tag) {
        View result = activity.findViewById(android.R.id.content).findViewWithTag(tag);
        assertNotNull(tag, result);
        return result;
    }

    TelegramSwitch toggle(String id) {
        return (TelegramSwitch) tagged("enable:" + id);
    }

    EditText search() {
        return (EditText) tagged("patch-search");
    }

    boolean visible(String id) {
        return tagged("patch:" + id).getVisibility() == View.VISIBLE;
    }

    void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    TextView text(String tag) {
        return (TextView) tagged(tag);
    }

    void open(String id) {
        toggle(id).performClick();
        tagged("settings:" + id).performClick();
    }

    void waitFor(String id) throws Exception {
        for (int i = 0; i < 300; i++) {
            idle();
            if (toggle(id).isEnabled()) return;
            Thread.sleep(10);
        }
        fail("Setting did not finish: " + id);
    }

    View described(View view, String name) {
        if (view.getContentDescription() != null
                && (name.contentEquals(view.getContentDescription())
                        || view.getContentDescription().toString().startsWith(name + ", ")))
            return view;
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                View result = described(((ViewGroup) view).getChildAt(i), name);
                if (result != null) return result;
            }
        return null;
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void eachInstalledFeatureHasADistinctVisibleVectorIcon() {
        java.util.Set<Integer> drawings = new java.util.HashSet<>();
        for (String id :
                new String[] {
                    "telegram",
                    "soulseek",
                    "peer-downloads",
                    "debrid",
                    "streamseek-preview",
                    "flac-audio",
                    "download-quality",
                    "sing",
                    "pitch",
                    "loudness",
                    "sleep-fade",
                    "battery-saver",
                    "lossless-bluetooth",
                    "headphone-correction",
                    "output-eq",
                    "dj-mode",
                    "jamesdsp",
                    "source-automix",
                    "mixed-playlists",
                    "video-switch",
                    "audio-focus",
                    "steady-playback",
                    "smart-downloads"
                }) {
            View icon =
                    activity.findViewById(android.R.id.content).findViewWithTag("patch-icon:" + id);
            if (icon == null) {
                activity.feature(id, id, id, () -> false, on -> {}, null);
                icon = tagged("patch-icon:" + id);
            }
            icon.layout(0, 0, 24, 28);
            android.graphics.Bitmap image =
                    android.graphics.Bitmap.createBitmap(
                            24, 28, android.graphics.Bitmap.Config.ARGB_8888);
            icon.draw(new android.graphics.Canvas(image));
            int[] pixels = new int[24 * 28];
            image.getPixels(pixels, 0, 24, 0, 0, 24, 28);
            int visible = 0;
            for (int pixel : pixels) if ((pixel >>> 24) > 0) visible++;
            assertTrue(id + " must be visible", visible > 12);
            assertTrue(
                    id + " must not reuse another feature's icon",
                    drawings.add(java.util.Arrays.hashCode(pixels)));
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, icon.getImportantForAccessibility());
            image.recycle();
        }
    }

    @Test
    public void travelFeaturesKeepTheirRealControlsAndBinaryFeaturesHaveNoGear() throws Exception {
        assertEquals(View.GONE, tagged("settings:audio-focus").getVisibility());
        open("steady-playback");
        assertNotNull(described(activity.findViewById(android.R.id.content), "On Wi-Fi"));
        SteadyPlayback.setWifiMb(96);
        tagged("done:steady-playback").performClick();
        toggle("steady-playback").performClick();
        assertEquals(96, SteadyPlayback.wifiMb);
        tagged("patch-undo").performClick();
        assertTrue(SteadyPlayback.enabled);
        assertEquals(96, SteadyPlayback.wifiMb);
        open("smart-downloads");
        assertNotNull(
                described(activity.findViewById(android.R.id.content), "Songs to keep downloaded"));
        SmartDownloads.setChargingOnly(false);
        tagged("done:smart-downloads").performClick();
        toggle("smart-downloads").performClick();
        assertFalse(SmartDownloads.chargingOnly);
        assertTrue(
                app.getSharedPreferences(SmartDownloads.PREFERENCES, 0).contains("chargingOnly"));
    }

    @Test
    public void renderingAndSearchingPreservePreferencesAndDoNotBuildHiddenSettings() {
        Map<String, ?> before = app.getSharedPreferences(SoundExtras.PREFERENCES, 0).getAll();
        int[] builds = {0}, reads = {0};
        activity.feature(
                "reader",
                "Measured sound",
                "Warm organic output",
                () -> {
                    reads[0]++;
                    return false;
                },
                on -> fail("Rendering wrote a setting"),
                parent -> builds[0]++);
        int count = reads[0];
        search().requestFocus();
        search().setText("organic");
        search().setSelection(3);
        assertTrue(visible("reader"));
        assertFalse(visible("pitch"));
        search().setText("Audio");
        assertTrue(visible("reader"));
        assertTrue(visible("soulseek"));
        tagged("patch-category:playback").performClick();
        assertTrue(search().hasFocus());
        assertEquals("Audio", search().getText().toString());
        assertEquals(count, reads[0]);
        assertEquals(0, builds[0]);
        assertEquals(before, app.getSharedPreferences(SoundExtras.PREFERENCES, 0).getAll());
    }

    @Test
    public void enabledSelectorCountsRealInstalledEntriesAndFiltersByCategory() {
        activity.feature(
                "available", "Available control", "Summary", () -> true, on -> {}, parent -> {});
        tagged("patch-mode:enabled").performClick();
        assertTrue(visible("available"));
        assertFalse(visible("pitch"));
        assertTrue(text("patch-counts").getText().toString().startsWith("1 of "));
        tagged("patch-category:sources").performClick();
        assertFalse(visible("available"));
        assertEquals(View.VISIBLE, tagged("patch-empty").getVisibility());
        tagged("patch-mode:all").performClick();
        assertTrue(visible("soulseek"));
        assertFalse(visible("pitch"));
        tagged("patch-category:all").performClick();
        assertTrue(visible("pitch"));
    }

    @Test
    public void togglesStayCompactUntilTheGearAndDoneIsNonDestructive() {
        toggle("pitch").performClick();
        assertEquals(View.GONE, tagged("details:pitch").getVisibility());
        tagged("settings:pitch").performClick();
        assertEquals(View.VISIBLE, tagged("details:pitch").getVisibility());
        tagged("done:pitch").performClick();
        assertEquals(View.GONE, tagged("details:pitch").getVisibility());
        assertTrue(SoundExtras.pitchEnabled);
        toggle("battery-saver").performClick();
        assertEquals(View.GONE, tagged("settings:battery-saver").getVisibility());
        toggle("streamseek-preview").performClick();
        assertFalse(toggle("streamseek-preview").isChecked());
        assertEquals(View.GONE, tagged("settings:streamseek-preview").getVisibility());
    }

    @Test
    public void undoPreservesNewConfigurationAndUnrelatedChanges() {
        toggle("pitch").performClick();
        SoundExtras.setPitch(5);
        SoundExtras.setTargetLufs(-12);
        tagged("patch-undo").performClick();
        assertFalse(SoundExtras.pitchEnabled);
        assertEquals(5, SoundExtras.pitchSemitones);
        assertEquals(-12, SoundExtras.targetLufs);
        controller.recreate();
        activity = controller.get();
        assertFalse(toggle("pitch").isChecked());
        assertEquals(5, SoundExtras.pitchSemitones);
    }

    @Test
    public void newerTogglesReplaceUndoWithoutRollingBackEarlierFeatures() {
        toggle("sing").performClick();
        toggle("pitch").performClick();
        tagged("patch-undo").performClick();
        assertTrue(SoundExtras.singEnabled);
        assertFalse(SoundExtras.pitchEnabled);
    }

    @Test
    public void externalStateChangesInvalidateUndo() {
        toggle("pitch").performClick();
        SoundExtras.setPitchEnabled(false);
        tagged("patch-undo").performClick();
        assertFalse(SoundExtras.pitchEnabled);
    }

    @Test
    public void jamesDspUndoRestoresOnlyItsUntouchedEqualizerDependency() {
        com.music.bitchord.data.settings.AppSettings.setEqualizerEnabled(true);
        toggle("jamesdsp").performClick();
        assertTrue(EffectsApps.useJamesDsp);
        assertFalse(EffectsApps.hostEqualiserOn(app));
        SoundExtras.setPitch(3);
        tagged("patch-undo").performClick();
        assertFalse(EffectsApps.useJamesDsp);
        assertTrue(EffectsApps.hostEqualiserOn(app));
        assertEquals(3, SoundExtras.pitchSemitones);
        toggle("jamesdsp").performClick();
        com.music.bitchord.data.settings.AppSettings.setEqualizerEnabled(true);
        com.music.bitchord.data.settings.AppSettings.setEqualizerEnabled(false);
        tagged("patch-undo").performClick();
        assertTrue(EffectsApps.useJamesDsp);
        assertFalse(EffectsApps.hostEqualiserOn(app));
    }

    @Test
    public void leavingThePageExpiresUndo() {
        toggle("pitch").performClick();
        controller.pause();
        controller.resume();
        tagged("patch-undo").performClick();
        assertTrue(SoundExtras.pitchEnabled);
    }

    @Test
    public void sliderFineAdjustmentAndResetUseSupportedPreferencesOnlyOnInteraction() {
        SoundExtras.setPitch(3);
        open("pitch");
        View details = tagged("details:pitch");
        assertEquals(3, SoundExtras.pitchSemitones);
        View increase = described(details, "Increase Pitch");
        assertNotNull(increase);
        increase.performClick();
        assertEquals(4, SoundExtras.pitchSemitones);
        described(details, "Decrease Pitch").performClick();
        assertEquals(3, SoundExtras.pitchSemitones);
        described(details, "Reset Pitch").performClick();
        assertEquals(0, SoundExtras.pitchSemitones);
        toggle("pitch").performClick();
        assertEquals(0, SoundExtras.pitchSemitones);
    }

    @Test
    public void queryModeCategoryAndExpandedControlsPersistOnRotation() {
        open("pitch");
        search().setText("pitch");
        tagged("patch-category:audio").performClick();
        tagged("patch-mode:enabled").performClick();
        controller.recreate();
        activity = controller.get();
        assertEquals("pitch", search().getText().toString());
        assertTrue(tagged("patch-category:audio").isSelected());
        assertTrue(tagged("patch-mode:enabled").isSelected());
        assertTrue(visible("pitch"));
        assertEquals(View.VISIBLE, tagged("details:pitch").getVisibility());
        assertFalse(visible("sing"));
    }

    @Test
    public void autoMixUsesTheHostGateWithoutChangingSpeedOrPitch() {
        SoundExtras.setPitch(4);
        assertFalse(com.music.bitchord.data.settings.AppSettings.getSmartFadeEnabled().getValue());
        toggle("source-automix").performClick();
        assertTrue(com.music.bitchord.data.settings.AppSettings.getSmartFadeEnabled().getValue());
        assertEquals(4, SoundExtras.pitchSemitones);
        tagged("patch-undo").performClick();
        assertFalse(com.music.bitchord.data.settings.AppSettings.getSmartFadeEnabled().getValue());
        assertEquals(4, SoundExtras.pitchSemitones);
    }

    @Test
    public void downloadToggleAndUndoRunOffTheUiThreadAndKeepOtherSettings() throws Exception {
        app.getSharedPreferences("bitchord_download_features", 0)
                .edit()
                .putString("destination", "telegram")
                .commit();
        toggle("peer-downloads").performClick();
        waitFor("peer-downloads");
        assertTrue(DownloadSettings.peekEnabled(app, "peer-downloads"));
        assertNotSame(Looper.getMainLooper().getThread(), DownloadSettings.lastWriter);
        tagged("patch-undo").performClick();
        waitFor("peer-downloads");
        assertFalse(DownloadSettings.peekEnabled(app, "peer-downloads"));
        assertEquals(
                "telegram",
                app.getSharedPreferences("bitchord_download_features", 0)
                        .getString("destination", ""));
    }

    @Test
    public void downloadReadsNeverMigrateOrEnableServices() {
        assertFalse(app.getSharedPreferences("bitchord_download_features", 0).contains("debrid"));
        assertFalse(DownloadSettings.peekEnabled(app, "debrid"));
        search().setText("debrid");
        assertFalse(app.getSharedPreferences("bitchord_download_features", 0).contains("debrid"));
    }
}

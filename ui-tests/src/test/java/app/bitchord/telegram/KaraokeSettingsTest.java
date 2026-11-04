package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.SeekBar;
import android.widget.TextView;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * The Karaoke settings page and the capsule on the lyrics share one level, one method, one switch.
 */
@RunWith(RobolectricTestRunner.class)
@Config(
        manifest = Config.NONE,
        sdk = 35,
        qualifiers = "w393dp-h852dp-night-mdpi",
        shadows = ExtrasActivityTest.InstalledAudio.class,
        instrumentedPackages = "app.bitchord.telegram.SoundExtras")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class KaraokeSettingsTest {
    private ActivityController<ExtrasActivity> controller;

    @Before
    public void create() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences(SoundExtras.PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
        java.lang.reflect.Field preferences = SoundExtras.class.getDeclaredField("preferences");
        preferences.setAccessible(true);
        preferences.set(null, null);
        SoundExtras.singEnabled = false;
        SoundExtras.singMethod = VocalCut.VOICE_GATE;
        SoundExtras.singLevel = SoundExtras.SING_DEFAULT;
        SeparationModel.forTest(null, false);
        controller = Robolectric.buildActivity(ExtrasActivity.class).setup();
    }

    @After
    public void stop() throws Exception {
        controller.pause().stop().destroy();
        java.lang.reflect.Field pending =
                SeparationModel.class.getDeclaredField("reconcilePending");
        pending.setAccessible(true);
        java.util.concurrent.atomic.AtomicBoolean flag =
                (java.util.concurrent.atomic.AtomicBoolean) pending.get(null);
        long end = System.nanoTime() + 5_000_000_000L;
        while (flag.get() && System.nanoTime() < end) Thread.sleep(5);
        SoundExtras.singEnabled = false;
        SeparationModel.forTest(null, false);
    }

    private View content() {
        return controller.get().findViewById(android.R.id.content);
    }

    private static void collect(View view, List<View> out) {
        out.add(view);
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
                collect(((ViewGroup) view).getChildAt(i), out);
    }

    private SeekBar voice() {
        controller.get().findViewById(android.R.id.content);
        if (content().findViewWithTag("details:sing") == null
                || content().findViewWithTag("details:sing").getVisibility() != View.VISIBLE) {
            TelegramSwitch toggle = content().findViewWithTag("enable:sing");
            if (!toggle.isChecked()) toggle.performClick();
            content().findViewWithTag("settings:sing").performClick();
        }
        List<View> all = new ArrayList<>();
        collect(content().findViewWithTag("details:sing"), all);
        for (View view : all)
            if (view instanceof SeekBar
                    && String.valueOf(view.getContentDescription()).startsWith("Voice"))
                return (SeekBar) view;
        throw new AssertionError("The Voice slider is on the Karaoke page");
    }

    private boolean hasText(String text) {
        List<View> all = new ArrayList<>();
        collect(content(), all);
        for (View view : all)
            if (view instanceof TextView && text.contentEquals(((TextView) view).getText()))
                return true;
        return false;
    }

    private static Bundle progress(float value) {
        Bundle arguments = new Bundle();
        arguments.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value);
        return arguments;
    }

    @Test
    public void thePageCallsTheFeatureKaraoke() {
        assertTrue(hasText("Karaoke"));
        assertFalse(hasText("Sing"));
        assertNotNull(content().findViewWithTag("enable:sing"));
    }

    @Test
    public void theSettingsSliderMovesTheLevelTheCapsuleShows() {
        SeekBar bar = voice();
        assertEquals(SoundExtras.SING_DEFAULT, bar.getProgress());
        bar.performAccessibilityAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),
                progress(30));
        assertEquals(30, SoundExtras.singLevel);
        assertEquals(30, KaraokeLyrics.level());
        assertEquals("30%", KaraokeLyrics.percent());
    }

    @Test
    public void theCapsulesLevelIsWhatTheSettingsSliderShowsNext() {
        voice();
        KaraokeLyrics.activate();
        KaraokeLyrics.setLevel(80);
        controller.recreate();
        SeekBar bar = voice();
        assertEquals(80, bar.getProgress());
        assertEquals(SeparationModel.METHOD, SoundExtras.singMethod);
        assertTrue(SoundExtras.singEnabled);
        assertNotNull(content().findViewWithTag("sing-method-model"));
    }

    @Test
    public void resetOnThePageGoesToTheValueTheCapsuleResetsTo() {
        SeekBar bar = voice();
        bar.performAccessibilityAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), progress(5));
        List<View> all = new ArrayList<>();
        collect(content().findViewWithTag("details:sing"), all);
        for (View view : all)
            if (view instanceof TextView
                    && "Reset".contentEquals(((TextView) view).getText())
                    && String.valueOf(view.getContentDescription()).contains("Voice")) {
                view.performClick();
                break;
            }
        assertEquals(KaraokeLyrics.DEFAULT_LEVEL, SoundExtras.singLevel);
        assertEquals(SoundExtras.SING_DEFAULT, KaraokeLyrics.level());
    }

    @Test
    public void offFromTheCapsuleIsTheSwitchOnThePage() {
        voice();
        KaraokeLyrics.activate();
        assertTrue(SoundExtras.singEnabled);
        KaraokeLyrics.deactivate();
        controller.recreate();
        TelegramSwitch toggle = content().findViewWithTag("enable:sing");
        assertFalse(toggle.isChecked());
        assertEquals(SoundExtras.SING_DEFAULT, SoundExtras.singLevel);
    }
}

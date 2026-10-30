package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(
        manifest = Config.NONE,
        sdk = 35,
        qualifiers = "w393dp-h852dp-night-mdpi",
        shadows = ExtrasActivityTest.InstalledAudio.class,
        instrumentedPackages = "app.bitchord.telegram.SoundExtras")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class ExtrasActivityTest {
    @Implements(value = SoundExtras.class, isInAndroidSdk = false)
    public static class InstalledAudio {
        @Implementation
        protected static boolean singApplied() {
            return true;
        }

        @Implementation
        protected static boolean pitchApplied() {
            return true;
        }

        @Implementation
        protected static boolean loudnessApplied() {
            return true;
        }

        @Implementation
        protected static boolean sleepFadeApplied() {
            return true;
        }

        @Implementation
        protected static boolean batterySaverApplied() {
            return true;
        }

        @Implementation
        protected static boolean losslessBluetoothApplied() {
            return true;
        }
    }

    private ActivityController<ExtrasActivity> controller;
    private ExtrasActivity activity;
    private boolean first, second;

    @Before
    public void create() throws Exception {
        com.music.bitchord.data.settings.AppSettings.bitPerfectMode.setValue(
                com.music.bitchord.data.settings.AppSettings.BitPerfectMode.OFF);
        android.content.Context app = org.robolectric.RuntimeEnvironment.getApplication();
        app.getSharedPreferences(SoundExtras.PREFERENCES, android.content.Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
        java.lang.reflect.Field preferences = SoundExtras.class.getDeclaredField("preferences");
        preferences.setAccessible(true);
        preferences.set(null, null);
        controller = Robolectric.buildActivity(ExtrasActivity.class).setup();
        activity = controller.get();
        activity.feature(
                "first",
                "First feature",
                "A short summary",
                () -> first,
                on -> first = on,
                parent -> parent.addView(label("First detailed controls")));
        activity.feature(
                "second",
                "Second feature",
                "Another short summary",
                () -> second,
                on -> second = on,
                parent -> parent.addView(label("Second detailed controls")));
    }

    @After
    public void stop() {
        controller.pause().stop().destroy();
    }

    private TextView label(String value) {
        TextView text = new TextView(activity);
        text.setText(value);
        return text;
    }

    private TextView bitPerfectNote(View view) {
        if (view instanceof TextView
                && ((TextView) view).getText().toString().startsWith("Bit-perfect output is on in"))
            return (TextView) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = bitPerfectNote(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test
    public void bitPerfectNoteFollowsHostSettingAndLeavesControlsUsable() {
        assertNull(bitPerfectNote(activity.findViewById(android.R.id.content)));
        com.music.bitchord.data.settings.AppSettings.bitPerfectMode.setValue(
                com.music.bitchord.data.settings.AppSettings.BitPerfectMode.USB_DIRECT);
        controller.recreate();
        activity = controller.get();
        assertNotNull(bitPerfectNote(activity.findViewById(android.R.id.content)));
        toggle("sing").performClick();
        shown("sing");
        assertTrue(gear("sing").isEnabled());
        com.music.bitchord.data.settings.AppSettings.bitPerfectMode.setValue(
                com.music.bitchord.data.settings.AppSettings.BitPerfectMode.OFF);
        controller.recreate();
        activity = controller.get();
        assertNull(bitPerfectNote(activity.findViewById(android.R.id.content)));
    }

    private View tagged(String tag) {
        return activity.findViewById(android.R.id.content).findViewWithTag(tag);
    }

    private TelegramSwitch toggle(String id) {
        return (TelegramSwitch) tagged("enable:" + id);
    }

    private ImageButton gear(String id) {
        return (ImageButton) tagged("settings:" + id);
    }

    private void hidden(String id) {
        assertEquals(View.GONE, tagged("details:" + id).getVisibility());
    }

    private void shown(String id) {
        assertEquals(View.VISIBLE, tagged("details:" + id).getVisibility());
    }

    @Test
    public void disabledCardsAreCompactAndGearCannotOpenThem() {
        hidden("first");
        hidden("second");
        assertFalse(gear("first").isEnabled());
        assertEquals(0.38f, gear("first").getAlpha(), 0.001f);
        gear("first").performClick();
        hidden("first");
        assertFalse(first);
        assertNotNull(gear("first").getDrawable());
        assertEquals(48, gear("first").getLayoutParams().width);
        assertEquals(48, gear("first").getLayoutParams().height);
    }

    @Test
    public void enablingOpensOnlyOneFeatureAndRetainsIndependentOnStates() {
        toggle("first").performClick();
        assertTrue(first);
        shown("first");
        hidden("second");
        assertTrue(gear("first").isEnabled());
        toggle("second").performClick();
        assertTrue(first);
        assertTrue(second);
        hidden("first");
        shown("second");
        gear("first").performClick();
        shown("first");
        hidden("second");
        gear("first").performClick();
        shown("first");
        toggle("first").performClick();
        assertFalse(first);
        assertTrue(second);
        hidden("first");
        hidden("second");
        assertFalse(gear("first").isEnabled());
        gear("second").performClick();
        shown("second");
    }

    @Test
    public void focusingAnotherFeatureCollapsesPreviousWithoutDisablingIt() {
        toggle("first").performClick();
        shown("first");
        View other = tagged("patch:second");
        View header = ((LinearLayout) other).getChildAt(0);
        header.setFocusableInTouchMode(true);
        assertTrue(header.requestFocus());
        hidden("first");
        hidden("second");
        assertTrue(first);
        assertFalse(second);
        gear("first").performClick();
        shown("first");
    }

    @Test
    public void changedSettingsReturningFromSetupUpdateSwitchAndGear() {
        toggle("first").performClick();
        shown("first");
        controller.pause();
        first = false;
        controller.resume();
        hidden("first");
        assertFalse(toggle("first").isChecked());
        assertFalse(gear("first").isEnabled());
    }

    @Test
    public void enabledCardAndScrollAreSavedForRecreation() {
        toggle("second").performClick();
        shown("second");
        Bundle saved = new Bundle();
        activity.onSaveInstanceState(saved);
        assertEquals("second", saved.getString("expandedPatch"));
        assertTrue(saved.containsKey("patchScroll"));
    }

    @Test
    public void realInstalledTweaksRestoreEnabledValuesAndExpansionAfterRotation() {
        toggle("sing").performClick();
        SoundExtras.setSingLevel(80);
        toggle("pitch").performClick();
        SoundExtras.setPitch(4);
        shown("pitch");
        hidden("sing");
        controller.recreate();
        activity = controller.get();
        assertTrue(toggle("sing").isChecked());
        assertTrue(toggle("pitch").isChecked());
        assertEquals(80, SoundExtras.singLevel);
        assertEquals(4, SoundExtras.pitchSemitones);
        shown("pitch");
        hidden("sing");
        toggle("pitch").performClick();
        hidden("pitch");
        assertEquals(4, SoundExtras.pitchSemitones);
        assertFalse(gear("pitch").isEnabled());
    }

    private void layoutPage() {
        View root = activity.getWindow().getDecorView();
        root.measure(
                View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(852, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 393, 852);
    }

    @Test
    public void largeFontKeepsGearAndSwitchSeparateAndControlsReachable() throws Exception {
        org.robolectric.RuntimeEnvironment.setFontScale(1.5f);
        controller.recreate();
        activity = controller.get();
        toggle("sing").performClick();
        layoutPage();
        assertEquals(48, gear("sing").getWidth());
        assertEquals(64, toggle("sing").getWidth());
        assertEquals(48, toggle("sing").getHeight());
        assertTrue(gear("sing").getRight() <= ((View) toggle("sing").getParent()).getLeft());
        shown("sing");
        assertTrue(gear("sing").isEnabled());
    }

    @Test
    public void failedEnableDoesNotExposeDetailsOrEnableGear() {
        activity.feature(
                "failed",
                "Unavailable feature",
                "Summary",
                () -> false,
                on -> {
                    throw new java.io.IOException("backend unavailable");
                },
                parent -> parent.addView(label("Unavailable controls")));
        toggle("failed").performClick();
        assertFalse(toggle("failed").isChecked());
        assertFalse(gear("failed").isEnabled());
        hidden("failed");
    }
}

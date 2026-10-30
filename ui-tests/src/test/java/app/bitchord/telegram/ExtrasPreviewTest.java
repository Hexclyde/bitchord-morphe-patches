package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** Optional native bitmap captures, run separately from the behavioral UI suite. */
@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(
        manifest = Config.NONE,
        sdk = 35,
        qualifiers = "w393dp-h852dp-night-mdpi",
        shadows = ExtrasActivityTest.InstalledAudio.class,
        instrumentedPackages = "app.bitchord.telegram.SoundExtras")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ExtrasPreviewTest {
    private ActivityController<ExtrasActivity> controller;
    private ExtrasActivity activity;

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
        controller = Robolectric.buildActivity(ExtrasActivity.class).setup();
        activity = controller.get();
    }

    @After
    public void stop() {
        controller.pause().stop().destroy();
    }

    private void enableSing() {
        TelegramSwitch toggle =
                activity.findViewById(android.R.id.content).findViewWithTag("enable:sing");
        toggle.performClick();
        View details = activity.findViewById(android.R.id.content).findViewWithTag("details:sing");
        assertEquals(View.VISIBLE, details.getVisibility());
    }

    private void screenshot(String name) throws Exception {
        View root = activity.getWindow().getDecorView();
        root.measure(
                View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(852, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 393, 852);
        Bitmap bitmap = Bitmap.createBitmap(393, 852, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        java.io.File output = new java.io.File("target/previews/" + name + ".png");
        output.getParentFile().mkdirs();
        try (java.io.FileOutputStream stream = new java.io.FileOutputStream(output)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        }
    }

    @Test
    public void renderDarkCompactAndExpandedNativePage() throws Exception {
        assertEquals(Color.BLACK, new TelegramStyle(activity).background);
        screenshot("patches-dark-compact");
        enableSing();
        screenshot("patches-dark-expanded");
    }

    @Test
    @Config(qualifiers = "w393dp-h852dp-notnight-mdpi")
    public void renderLightNativePage() throws Exception {
        assertEquals(Color.WHITE, new TelegramStyle(activity).background);
        enableSing();
        screenshot("patches-light-expanded");
    }

    @Test
    public void renderLargeFontNativePage() throws Exception {
        RuntimeEnvironment.setFontScale(1.5f);
        controller.recreate();
        activity = controller.get();
        enableSing();
        screenshot("patches-large-font-expanded");
    }
}

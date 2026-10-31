package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.app.Activity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.compose.runtime.ComposerImpl;
import androidx.media3.common.MediaItem;
import androidx.media3.session.MediaController;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;

@RunWith(RobolectricTestRunner.class)
@Config(
        manifest = Config.NONE,
        sdk = 35,
        qualifiers = "w393dp-h852dp-night-mdpi",
        shadows = VideoOverlayTest.NoVersionLookup.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class VideoOverlayTest {
    /** View selection is tested here; host version lookup/swap has its own regression suite. */
    @org.robolectric.annotation.Implements(value = SoundExtras.class, isInAndroidSdk = false)
    public static class NoVersionLookup {
        @org.robolectric.annotation.Implementation
        public static android.content.Context app() {
            return null;
        }
    }

    ActivityController<Activity> owner;
    LinearLayout column;
    ComposerImpl composer;
    MediaController controller;

    private static void set(String name, Object value) throws Exception {
        Field f = VideoSwitch.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }

    @Before
    public void setup() throws Exception {
        owner = Robolectric.buildActivity(Activity.class).setup();
        column = new LinearLayout(owner.get());
        column.setOrientation(LinearLayout.VERTICAL);
        owner.get().setContentView(column);
        composer = new ComposerImpl(owner.get(), column);
        controller = new MediaController();
        controller.item = new MediaItem.Builder().setMediaId("dQw4w9WgXcQ").build();
        set("controller", new WeakReference<>(controller));
        set("preferences", null);
        set("switching", null);
        VideoSwitch.preferredVideo = false;
        VideoSwitch.enabled = true;
    }

    @After
    public void close() throws Exception {
        VideoSwitch.enabled = false;
        owner.pause().stop().destroy();
        set("controller", new WeakReference<>(null));
    }

    private VideoSwitch.Header header() {
        VideoSwitch.header(composer);
        return (VideoSwitch.Header) composer.view;
    }

    private void measure() {
        column.measure(
                View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(852, View.MeasureSpec.EXACTLY));
        column.layout(0, 0, 393, 852);
    }

    @Test
    public void headerReservesSpaceBetweenTheHandleAndArtwork() {
        View handle = new View(owner.get());
        column.addView(handle, new LinearLayout.LayoutParams(-1, 32));
        VideoSwitch.Header row = header();
        View artwork = new View(owner.get());
        column.addView(artwork, new LinearLayout.LayoutParams(-1, 0, 1));
        measure();
        assertSame(column, row.getParent());
        assertEquals(32, row.getTop());
        assertEquals(48, row.getHeight());
        assertEquals(80, artwork.getTop());
        FrameLayout window = owner.get().findViewById(android.R.id.content);
        assertEquals("No floating sibling is added to the window", 1, window.getChildCount());
        assertEquals("The normal layout has no status-bar offset", 0, row.getTranslationY(), 0.01);
        assertEquals(0, row.getElevation(), 0.01);
        assertEquals(2, row.control.getChildCount());
    }

    @Test
    public void modeButtonsUseIconsAndSeparateAccessibleTargets() {
        VideoSwitch.Pill row = header().control;
        measure();
        for (View button : new View[] {row.audio, row.video}) {
            assertTrue(button.getWidth() >= 48);
            assertTrue(button.getHeight() >= 48);
            assertFalse(button instanceof android.widget.TextView);
            AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
            button.onInitializeAccessibilityNodeInfo(node);
            assertEquals("android.widget.RadioButton", node.getClassName());
            assertTrue(node.isCheckable());
            assertTrue(
                    node.getContentDescription()
                            .toString()
                            .equals(button == row.audio ? "Audio" : "Video"));
            assertEquals(button == row.audio, node.isChecked());
        }
        row.video.performClick();
        assertTrue(VideoSwitch.preferredVideo);
        assertTrue(row.video.isSelected());
        assertFalse(row.audio.isSelected());
        row.audio.performClick();
        assertFalse(VideoSwitch.preferredVideo);
    }

    @Test
    public void aPendingVersionSwapDisablesBothButtonsUntilItSettles() throws Exception {
        VideoSwitch.Pill row = header().control;
        set("switching", "dQw4w9WgXcQ");
        row.refresh();
        assertFalse(row.audio.isEnabled());
        assertFalse(row.video.isEnabled());
        row.video.performClick();
        assertFalse(VideoSwitch.preferredVideo);
        set("switching", null);
        row.refresh();
        assertTrue(row.video.isEnabled());
        row.video.performClick();
        assertTrue(VideoSwitch.preferredVideo);
    }

    @Test
    public void hidingThePatchOrPlayingExternalAudioReleasesItsLayoutSpace() {
        VideoSwitch.Header row = header();
        measure();
        assertEquals(48, row.getHeight());
        VideoSwitch.setEnabled(false);
        measure();
        assertEquals(0, row.getHeight());
        VideoSwitch.setEnabled(true);
        controller.item = new MediaItem.Builder().setMediaId("tg:123").build();
        row.control.refresh();
        measure();
        assertEquals(0, row.getHeight());
    }

    @Test
    public void recompositionAndDetachedPlayerNeverMoveTheControlIntoTheWindow() {
        VideoSwitch.Header first = header();
        VideoSwitch.header(composer);
        assertSame(first, composer.view);
        assertEquals(1, column.getChildCount());
        column.removeView(first);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertNull(first.getParent());
        assertEquals(0, column.getChildCount());
        ComposerImpl next = new ComposerImpl(owner.get(), column);
        VideoSwitch.header(next);
        assertNotSame(first, next.view);
        assertEquals(1, column.getChildCount());
    }

    @Test
    @Config(qualifiers = "w852dp-h393dp-land-night-mdpi")
    public void landscapeAndLargeTextKeepBothTouchTargets() {
        org.robolectric.RuntimeEnvironment.setFontScale(2f);
        VideoSwitch.Header row = header();
        measure();
        assertEquals(48, row.getHeight());
        assertEquals(104, row.control.getWidth());
        assertTrue(row.control.audio.getWidth() >= 48);
        assertTrue(row.control.video.getWidth() >= 48);
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void controlsRevealTheArtworkAndUseThePlayerTintForSelection() {
        VideoSwitch.Header row = header();
        row.control.tint(0xffefbb77);
        for (boolean video : new boolean[] {false, true}) {
            VideoSwitch.setPreferredVideo(video);
            measure();
            android.graphics.Bitmap image =
                    android.graphics.Bitmap.createBitmap(
                            393, 48, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(image);
            canvas.drawColor(0xff2d1938);
            row.draw(canvas);
            int start = row.control.getLeft();
            assertEquals(
                    "No gray plate covers the artwork", 0xff2d1938, image.getPixel(start + 2, 24));
            int selectedCenter = start + (video ? 78 : 26);
            assertEquals(
                    "Selected mode follows the native player tint",
                    0xffefbb77,
                    image.getPixel(selectedCenter, 44));
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void renderProductionIconsInBothSavedModes() throws Exception {
        VideoSwitch.Header row = header();
        java.io.File folder =
                new java.io.File(
                        System.getProperty("basedir", "ui-tests"), "../build/player-icons-preview");
        folder.mkdirs();
        for (boolean video : new boolean[] {false, true}) {
            VideoSwitch.setPreferredVideo(video);
            measure();
            android.graphics.Bitmap image =
                    android.graphics.Bitmap.createBitmap(
                            393, 48, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(image);
            canvas.drawColor(0xff2d1938);
            row.draw(canvas);
            try (java.io.FileOutputStream out =
                    new java.io.FileOutputStream(
                            new java.io.File(folder, video ? "video.png" : "audio.png"))) {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            }
        }
    }
}

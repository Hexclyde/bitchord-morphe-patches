package app.bitchord.soulseek;

import static org.junit.Assert.*;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.CheckBox;
import android.widget.Checkable;
import android.widget.ScrollView;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, qualifiers = "w393dp-h852dp-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
public class AudioFormatScreenTest {
    private ActivityController<AudioFormatSetupActivity> controller;
    private AudioFormatSetupActivity activity;

    @Before
    public void create() {
        RuntimeEnvironment.getApplication()
                .getSharedPreferences("bitchord_audio_formats", 0)
                .edit()
                .clear()
                .commit();
        controller = Robolectric.buildActivity(AudioFormatSetupActivity.class).setup();
        activity = controller.get();
        layout();
    }

    @After
    public void stop() {
        controller.pause().stop().destroy();
    }

    private void layout() {
        View decor = activity.getWindow().getDecorView();
        decor.measure(
                View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(852, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 393, 852);
    }

    private View root() {
        return activity.findViewById(android.R.id.content);
    }

    private View described(View view, String description) {
        if (description.equals(view.getContentDescription())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View result = described(group.getChildAt(i), description);
                if (result != null) return result;
            }
        }
        return null;
    }

    private TextView text(View view, String value) {
        if (view instanceof TextView && value.contentEquals(((TextView) view).getText()))
            return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView result = text(group.getChildAt(i), value);
                if (result != null) return result;
            }
        }
        return null;
    }

    private AudioFormatPreferences.Settings draft() {
        try {
            Bundle state = new Bundle();
            activity.onSaveInstanceState(state);
            return AudioFormatPreferences.decode(state.getString("draft"));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private void pointer(View handle, int action, float y) {
        MotionEvent event = MotionEvent.obtain(0, 10, action, 24, y, 0);
        assertTrue(handle.dispatchTouchEvent(event));
        event.recycle();
    }

    @Test
    public void formatsUseSwitchesAndFortyEightDpDragTargets() {
        for (AudioFormatPreferences.Format format : AudioFormatPreferences.defaults().order) {
            View toggle = described(root(), "Accept " + format.label);
            assertNotNull(toggle);
            assertTrue(toggle instanceof Checkable);
            assertFalse(toggle instanceof CheckBox);
            assertEquals(48, toggle.getHeight());
            View handle = described(root(), "Reorder " + format.label);
            assertEquals(48, handle.getWidth());
            assertEquals(48, handle.getHeight());
            AccessibilityNodeInfo info = toggle.createAccessibilityNodeInfo();
            assertTrue(info.isChecked());
            assertEquals("android.widget.Switch", info.getClassName());
        }
        assertNotNull(text(root(), "Download formats"));
        assertNotNull(described(root(), "Back"));
        assertNull(text(root(), "↑"));
        assertNull(text(root(), "↓"));
    }

    @Test
    public void actualPointerDropMovesAcrossMultipleRowsAndKeepsEnabledChoices() {
        described(root(), "Accept ALAC").performClick();
        View handle = described(root(), "Reorder FLAC");
        pointer(handle, MotionEvent.ACTION_DOWN, 200);
        pointer(handle, MotionEvent.ACTION_MOVE, 2000);
        pointer(handle, MotionEvent.ACTION_UP, 2000);
        AudioFormatPreferences.Settings result = draft();
        assertEquals(AudioFormatPreferences.Format.FLAC, result.order.get(8));
        assertEquals(AudioFormatPreferences.Format.ALAC, result.order.get(0));
        assertFalse(result.enabled.contains(AudioFormatPreferences.Format.ALAC));
        assertTrue(result.enabled.contains(AudioFormatPreferences.Format.FLAC));
        // Dragging changes the draft, not the stored download policy, until Save.
        assertEquals(
                AudioFormatPreferences.Format.FLAC,
                AudioFormatPreferences.snapshot(activity).order.get(0));
    }

    @Test
    public void cancelledPointerRestoresOrderAndDoesNotToggleTheFormat() {
        View handle = described(root(), "Reorder MP3");
        pointer(handle, MotionEvent.ACTION_DOWN, 700);
        pointer(handle, MotionEvent.ACTION_MOVE, -1000);
        pointer(handle, MotionEvent.ACTION_CANCEL, -1000);
        assertEquals(AudioFormatPreferences.defaults().order, draft().order);
        assertEquals(AudioFormatPreferences.defaults().enabled, draft().enabled);
    }

    @Test
    public void disabledFormatsStayReorderableAndLastEnabledCannotBeDisabled() {
        for (AudioFormatPreferences.Format format : AudioFormatPreferences.defaults().order) {
            if (format != AudioFormatPreferences.Format.FLAC)
                described(root(), "Accept " + format.label).performClick();
        }
        described(root(), "Accept FLAC").performClick();
        assertEquals(1, draft().enabled.size());
        assertTrue(draft().enabled.contains(AudioFormatPreferences.Format.FLAC));
        View handle = described(root(), "Reorder MP3");
        assertTrue(handle.performAccessibilityAction(0x7e020001, null));
        assertEquals(AudioFormatPreferences.Format.MP3, draft().order.get(7));
        assertFalse(draft().enabled.contains(AudioFormatPreferences.Format.MP3));
    }

    @Test
    public void accessibilityAndKeyboardMovesSaveAndRestoreAcrossRecreation() {
        View handle = described(root(), "Reorder FLAC");
        assertFalse(handle.performAccessibilityAction(0x7e020001, null));
        assertTrue(handle.performAccessibilityAction(0x7e020002, null));
        assertTrue(
                handle.dispatchKeyEvent(
                        new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)));
        Bundle saved = new Bundle();
        controller.saveInstanceState(saved).pause().stop().destroy();
        controller =
                Robolectric.buildActivity(AudioFormatSetupActivity.class)
                        .create(saved)
                        .start()
                        .restoreInstanceState(saved)
                        .resume()
                        .visible();
        activity = controller.get();
        layout();
        assertEquals(AudioFormatPreferences.Format.FLAC, draft().order.get(2));
        text(root(), "Save format choices").performClick();
        assertTrue(activity.isFinishing());
        assertEquals(
                AudioFormatPreferences.Format.FLAC,
                AudioFormatPreferences.snapshot(activity).order.get(2));
    }

    @Test(timeout = 15000)
    public void stationaryDragAtViewportEdgeScrollsBeforeDrop() throws Exception {
        ScrollView scroll = (ScrollView) ((ViewGroup) root()).getChildAt(0);
        View handle = described(root(), "Reorder FLAC");
        int[] location = new int[2];
        scroll.getLocationOnScreen(location);
        float bottom = location[1] + scroll.getHeight() - 10;
        pointer(handle, MotionEvent.ACTION_DOWN, 200);
        pointer(handle, MotionEvent.ACTION_MOVE, bottom);
        java.lang.reflect.Field callback =
                AudioFormatSetupActivity.class.getDeclaredField("autoScroll");
        callback.setAccessible(true);
        // Bound the number of animation frames explicitly: a continuously scheduled drag is
        // intentionally never idle until release. Every frame still exercises the real View.
        Runnable frame = (Runnable) callback.get(activity);
        for (int count = 0; count < 8; count++) frame.run();
        assertTrue("dragging at the lower edge scrolls the whole list", scroll.getScrollY() > 0);
        pointer(handle, MotionEvent.ACTION_UP, bottom);
        assertTrue(draft().order.indexOf(AudioFormatPreferences.Format.FLAC) > 0);
    }
}

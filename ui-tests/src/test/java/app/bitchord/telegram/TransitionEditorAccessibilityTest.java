package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.os.Bundle;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.SeekBar;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, qualifiers = "w393dp-h852dp-mdpi")
public class TransitionEditorAccessibilityTest {
    @Test
    public void spokenSecondsFollowAccessibleFineAdjustmentAndRerender() throws Exception {
        checkValue("Overlap", 0, 20, 8, " s", 500, "8 s", "10 s");
    }

    @Test
    public void spokenEqualiserGainIncludesItsNegativeValueAndUnits() throws Exception {
        checkValue("Low", -12, 12, -6, " dB", 375, "-6 dB", "-3 dB");
    }

    @Test
    @Config(sdk = 29)
    public void olderAndroidIncludesUnitsInTheAdjustableControlLabel() throws Exception {
        checkValue("Low", -12, 12, -6, " dB", 375, "-6 dB", "-3 dB");
    }

    private void checkValue(
            String title,
            double min,
            double max,
            double initial,
            String unit,
            int progress,
            String initialSpoken,
            String changedSpoken)
            throws Exception {
        var owner = Robolectric.buildActivity(TransitionEditorActivity.class).setup().visible();
        try {
            var activity = owner.get();
            LinearLayout controls = new LinearLayout(activity);
            controls.setOrientation(LinearLayout.VERTICAL);
            var slider =
                    TransitionEditorActivity.class.getDeclaredMethod(
                            "slider",
                            LinearLayout.class,
                            String.class,
                            double.class,
                            double.class,
                            double.class,
                            String.class,
                            TransitionEditorActivity.Value.class);
            slider.setAccessible(true);
            double[] actual = {initial};
            TransitionEditorActivity.Value change = value -> actual[0] = value;
            slider.invoke(activity, controls, title, min, max, initial, unit, change);
            activity.setContentView(controls);
            controls.measure(
                    View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(852, View.MeasureSpec.EXACTLY));
            controls.layout(0, 0, 393, 852);
            SeekBar bar = (SeekBar) ((LinearLayout) controls.getChildAt(0)).getChildAt(1);
            assertSpokenValue(bar, title, initialSpoken);
            Bundle arguments = new Bundle();
            arguments.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, progress);
            assertTrue(
                    bar.performAccessibilityAction(
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),
                            arguments));
            assertEquals(min + (max - min) * progress / 1000, actual[0], 1e-9);
            assertSpokenValue(bar, title, changedSpoken);
            controls.removeAllViews();
            slider.invoke(activity, controls, title, min, max, initial, unit, change);
            bar = (SeekBar) ((LinearLayout) controls.getChildAt(0)).getChildAt(1);
            assertSpokenValue(bar, title, initialSpoken);
        } finally {
            owner.pause().stop().destroy();
        }
    }

    private void assertSpokenValue(SeekBar bar, String title, String value) {
        AccessibilityNodeInfo node = bar.createAccessibilityNodeInfo();
        assertEquals("android.widget.SeekBar", node.getClassName());
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            assertEquals(title, node.getContentDescription());
            assertEquals(value, node.getStateDescription());
        } else {
            assertEquals(title + " · " + value, node.getContentDescription());
        }
    }
}

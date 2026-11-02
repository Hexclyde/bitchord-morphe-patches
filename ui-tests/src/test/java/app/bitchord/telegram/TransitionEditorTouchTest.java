package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.app.Dialog;
import android.graphics.Rect;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, qualifiers = "w393dp-h852dp-mdpi")
public class TransitionEditorTouchTest {
    @Test
    public void preciseAdjustmentsHaveReachableTargetsAtDefaultTextSize() throws Exception {
        checkTargets(1f);
    }

    @Test
    public void preciseAdjustmentsHaveReachableTargetsWithEnlargedText() throws Exception {
        checkTargets(1.3f);
    }

    private void checkTargets(float fontScale) throws Exception {
        RuntimeEnvironment.setFontScale(fontScale);
        var owner = Robolectric.buildActivity(TransitionEditorActivity.class).setup().visible();
        Dialog dialog = null;
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
            slider.invoke(
                    activity,
                    controls,
                    "Overlap",
                    .5,
                    30d,
                    8d,
                    " s",
                    (TransitionEditorActivity.Value) value -> {});
            activity.setContentView(controls);
            controls.measure(
                    View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(852, View.MeasureSpec.EXACTLY));
            controls.layout(0, 0, 393, 852);
            LinearLayout adjustment = (LinearLayout) controls.getChildAt(0);
            TextView caption = (TextView) adjustment.getChildAt(0);
            SeekBar bar = (SeekBar) adjustment.getChildAt(1);
            int minimum = Math.round(48 * activity.getResources().getDisplayMetrics().density);
            for (View target : new View[] {caption, bar}) {
                Rect hit = new Rect();
                target.getHitRect(hit);
                assertTrue(target.getClass().getSimpleName() + " height", hit.height() >= minimum);
                assertTrue(target.getClass().getSimpleName() + " width", hit.width() >= minimum);
                assertTrue(target.isEnabled());
            }
            assertTrue(caption.isClickable());
            assertTrue(caption.performClick());
            dialog = ShadowDialog.getLatestDialog();
            assertNotNull("The numeric adjustment opens", dialog);
            assertTrue(dialog.isShowing());
        } finally {
            if (dialog != null) dialog.dismiss();
            owner.pause().stop().destroy();
            RuntimeEnvironment.setFontScale(1f);
        }
    }
}

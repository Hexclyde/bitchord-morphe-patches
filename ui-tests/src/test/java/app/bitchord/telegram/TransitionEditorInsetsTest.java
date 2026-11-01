package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.graphics.Insets;
import android.view.View;
import android.view.WindowInsets;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, qualifiers = "w393dp-h852dp-mdpi")
public class TransitionEditorInsetsTest {
    @Test
    public void barsCutoutsAndKeyboardInsetTheEditorWithoutAccumulatingPadding() {
        var owner = Robolectric.buildActivity(TransitionEditorActivity.class).setup();
        try {
            View root = owner.get().findViewById(android.R.id.content);
            root = ((android.view.ViewGroup) root).getChildAt(0);
            WindowInsets bars =
                    new WindowInsets.Builder()
                            .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 30, 0, 24))
                            .setInsets(WindowInsets.Type.displayCutout(), Insets.of(18, 0, 0, 0))
                            .build();
            root.dispatchApplyWindowInsets(bars);
            assertEquals(38, root.getPaddingTop());
            assertEquals(34, root.getPaddingLeft());
            assertEquals(32, root.getPaddingBottom());
            root.dispatchApplyWindowInsets(bars);
            assertEquals(38, root.getPaddingTop());
            root.dispatchApplyWindowInsets(
                    new WindowInsets.Builder(bars)
                            .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 260))
                            .build());
            assertEquals(268, root.getPaddingBottom());
        } finally {
            owner.pause().stop().destroy();
        }
    }
}

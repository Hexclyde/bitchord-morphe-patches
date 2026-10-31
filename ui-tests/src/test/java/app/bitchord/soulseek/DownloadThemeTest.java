package app.bitchord.soulseek;

import static org.junit.Assert.*;

import android.graphics.Color;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/** Exercises the real native shell, including later-added status and file rows. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class DownloadThemeTest {
    public static class Screen extends DownloadActivity {
        ScrollView scroll;
        LinearLayout body;
        TextView title;
        Button action, oldBack;
        EditText key;
        CompoundButton enabled;

        @Override
        public void onCreate(Bundle state) {
            super.onCreate(state);
            scroll = new ScrollView(this);
            body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(body);
            title = new TextView(this);
            title.setText("Debrid downloads");
            title.setTextSize(24);
            body.addView(title);
            oldBack = new Button(this);
            oldBack.setText("Back");
            body.addView(oldBack);
            key = new EditText(this);
            key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            key.setSaveEnabled(false);
            key.setText("private-test-input");
            body.addView(key);
            enabled = downloadStyle.switchButton();
            enabled.setText("Enable service");
            enabled.setChecked(true);
            body.addView(enabled);
            action = new Button(this);
            action.setText("Resolve link");
            action.setEnabled(false);
            body.addView(action);
            setContentView(scroll);
        }
    }

    @Test
    public void hostSurfacesAndControlsFollowDarkAndLightModes() {
        for (String qualifier : new String[] {"night", "notnight"}) {
            RuntimeEnvironment.setQualifiers(qualifier);
            try (ActivityController<Screen> controller =
                    Robolectric.buildActivity(Screen.class).setup()) {
                Screen screen = controller.get();
                boolean dark = qualifier.equals("night");
                assertEquals(dark, screen.downloadStyle.dark);
                assertEquals(dark ? Color.BLACK : Color.WHITE, screen.downloadStyle.background);
                assertEquals(dark ? Color.WHITE : Color.BLACK, screen.title.getCurrentTextColor());
                assertTrue(screen.action.getBackground() instanceof RippleDrawable);
                assertEquals("Resolve link", screen.action.getText().toString());
                assertFalse(screen.action.isEnabled());
                assertTrue(screen.action.getMinimumHeight() >= screen.downloadStyle.dp(48));
            }
        }
    }

    @Test
    public void circularBackHeaderReplacesLegacyBackAndLeavesCredentialsPrivate() {
        try (ActivityController<Screen> controller =
                Robolectric.buildActivity(Screen.class).setup()) {
            Screen screen = controller.get();
            assertEquals("Back", screen.body.getChildAt(0).getContentDescription());
            assertEquals(View.GONE, screen.oldBack.getVisibility());
            assertFalse(screen.key.isSaveEnabled());
            assertEquals("private-test-input", screen.key.getText().toString());
            assertEquals(
                    InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    screen.key.getInputType() & InputType.TYPE_MASK_VARIATION);
            assertNotNull(screen.key.getTransformationMethod());
            assertTrue(screen.enabled.isChecked());
            assertNull(screen.enabled.getButtonDrawable());
            assertNotNull(screen.enabled.getCompoundDrawablesRelative()[2]);
            screen.body.getChildAt(0).performClick();
            assertTrue(screen.isFinishing());
        }
    }

    @Test
    public void dynamicRowsAreStyledWithoutChangingStateOrCustomControls() {
        try (ActivityController<Screen> controller =
                Robolectric.buildActivity(Screen.class).setup()) {
            Screen screen = controller.get();
            Button late = new Button(screen);
            late.setText("Resume selected audio");
            late.setEnabled(false);
            screen.body.addView(late);
            TextView custom = new TextView(screen);
            custom.setTextSize(19);
            custom.setTextColor(Color.MAGENTA);
            screen.downloadStyle.markCustom(custom);
            screen.body.addView(custom);
            screen.scroll.getViewTreeObserver().dispatchOnGlobalLayout();
            assertTrue(late.getBackground() instanceof RippleDrawable);
            assertFalse(late.isEnabled());
            assertEquals("Resume selected audio", late.getText().toString());
            assertEquals(Color.MAGENTA, custom.getCurrentTextColor());
            assertEquals("private-test-input", screen.key.getText().toString());
            screen.downloadStyle.applyTree(screen.body);
            assertEquals(Color.MAGENTA, custom.getCurrentTextColor());
        }
    }

    @Test
    public void fileSelectionCheckboxKeepsItsNativeSelectionRole() {
        try (ActivityController<Screen> controller =
                Robolectric.buildActivity(Screen.class).setup()) {
            Screen screen = controller.get();
            CheckBox file = new CheckBox(screen);
            file.setText("Selected audio file");
            file.setChecked(true);
            screen.body.addView(file);
            screen.downloadStyle.applyTree(file);
            assertNotNull(file.getButtonDrawable());
            assertNull(file.getCompoundDrawablesRelative()[2]);
            assertTrue(file.isChecked());
            assertEquals("android.widget.CheckBox", file.getAccessibilityClassName());
        }
    }
}

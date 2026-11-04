package app.bitchord.telegram;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.music.bitchord.data.spotify.LocalPlaylistStore;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, qualifiers = "w360dp-h640dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class DjPlaylistPickerTest {
    private Activity activity;

    @Before
    public void setup() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        Field field = SoundExtras.class.getDeclaredField("app");
        field.setAccessible(true);
        field.set(null, app);
        MixedPlaylists.init(app);
        activity = Robolectric.buildActivity(Activity.class).setup().get();
    }

    private void store(int count) throws Exception {
        List<Object> playlists = new ArrayList<>();
        for (int i = 0; i < count; i++)
            playlists.add(
                    MixedPlaylists.playlist("local-" + i, "Playlist " + i, new ArrayList<>()));
        LocalPlaylistStore._playlists.setValue(playlists);
    }

    private Dialog open() throws Exception {
        DjPlaylists.choose(activity);
        Field worker = DjPlaylists.class.getDeclaredField("worker");
        worker.setAccessible(true);
        ((ExecutorService) worker.get(null)).submit(() -> {}).get(5, TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        return dialog;
    }

    private static void collect(View view, Class<?> type, List<View> into) {
        if (type.isInstance(view)) into.add(view);
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
                collect(((ViewGroup) view).getChildAt(i), type, into);
    }

    private static View content(Dialog dialog) {
        return ((ViewGroup) dialog.getWindow().getDecorView().findViewById(android.R.id.content))
                .getChildAt(0);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    @Test
    public void longListScrollsInsideABoundedViewAndCancelStaysVisible() throws Exception {
        store(40);
        LinearLayout body = (LinearLayout) content(open());
        List<View> scrolls = new ArrayList<>();
        collect(body, ScrollView.class, scrolls);
        assertEquals(1, scrolls.size());
        ScrollView scroll = (ScrollView) scrolls.get(0);
        assertSame("The list is a direct child of the card", body, scroll.getParent());
        assertEquals(
                activity.getResources().getDisplayMetrics().heightPixels / 2,
                scroll.getLayoutParams().height);

        TextView cancel = (TextView) body.getChildAt(body.getChildCount() - 1);
        assertEquals("Cancel", cancel.getText().toString());
        assertTrue("Cancel is outside the scrolling list", body.indexOfChild(scroll) >= 0);
        assertNotSame(scroll, cancel.getParent());
        assertTrue(cancel.getMinHeight() >= dp(48));
    }

    @Test
    public void shortListShrinksToItsRows() throws Exception {
        store(2);
        LinearLayout body = (LinearLayout) content(open());
        List<View> scrolls = new ArrayList<>();
        collect(body, ScrollView.class, scrolls);
        assertEquals(2 * dp(58), scrolls.get(0).getLayoutParams().height);
    }

    @Test
    public void rowsAreLabelledButtonsOfTouchSize() throws Exception {
        store(40);
        View body = content(open());
        List<View> texts = new ArrayList<>();
        ScrollView scroll = (ScrollView) find(body, ScrollView.class);
        collect(scroll, TextView.class, texts);
        assertEquals(40, texts.size());
        for (int i = 0; i < texts.size(); i++) {
            TextView row = (TextView) texts.get(i);
            assertEquals("Playlist " + i, row.getText().toString());
            assertTrue(row.isClickable() && row.isFocusable());
            assertTrue(row.getMinHeight() >= dp(48));
            AccessibilityNodeInfo info = row.createAccessibilityNodeInfo();
            assertEquals(Button.class.getName(), info.getClassName().toString());
            assertEquals("Playlist " + i, info.getText().toString());
            info.recycle();
        }
        TelegramStyle style = new TelegramStyle(activity);
        assertEquals(style.foreground, ((TextView) texts.get(0)).getCurrentTextColor());
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-night-mdpi")
    public void rowsFollowTheDarkTheme() throws Exception {
        store(3);
        View body = content(open());
        TextView row = (TextView) find(find(body, ScrollView.class), TextView.class);
        assertEquals(android.graphics.Color.WHITE, row.getCurrentTextColor());
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-notnight-mdpi")
    public void rowsFollowTheLightTheme() throws Exception {
        store(3);
        View body = content(open());
        TextView row = (TextView) find(find(body, ScrollView.class), TextView.class);
        assertEquals(android.graphics.Color.BLACK, row.getCurrentTextColor());
    }

    @Test
    public void tappingARowOpensTheConfirmation() throws Exception {
        store(40);
        Dialog picker = open();
        List<View> texts = new ArrayList<>();
        collect(find(content(picker), ScrollView.class), TextView.class, texts);
        assertTrue(texts.get(7).performClick());
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(picker.isShowing());
        Dialog confirm = ShadowDialog.getLatestDialog();
        assertNotSame(picker, confirm);
        assertTrue(confirm.isShowing());
        List<View> labels = new ArrayList<>();
        collect(content(confirm), TextView.class, labels);
        StringBuilder all = new StringBuilder();
        for (View label : labels) all.append(((TextView) label).getText()).append('\n');
        assertTrue(all.toString(), all.toString().contains("Playlist 7"));
        assertTrue(all.toString().contains("Save"));
    }

    private static View find(View root, Class<?> type) {
        List<View> found = new ArrayList<>();
        collect(root, type, found);
        return found.get(0);
    }
}

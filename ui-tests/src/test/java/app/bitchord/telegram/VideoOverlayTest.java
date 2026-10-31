package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import androidx.media3.common.MediaItem;
import androidx.media3.session.MediaController;

import kotlin.jvm.functions.Function0;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class VideoOverlayTest {
    public static class Motion {
        public Window window;
        public boolean open = true, holding;
        public final Function0<Boolean> shown = () -> open;

        public boolean getHolding() {
            return holding;
        }
    }

    ActivityController<Activity> first, second;
    Motion sheet;
    MediaController controller;

    private static void set(String name, Object value) throws Exception {
        Field f = VideoSwitch.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }

    private static View pill() throws Exception {
        Field f = VideoSwitch.class.getDeclaredField("pill");
        f.setAccessible(true);
        return (View) ((WeakReference<?>) f.get(null)).get();
    }

    @Before
    public void setup() throws Exception {
        first = Robolectric.buildActivity(Activity.class).setup();
        second = Robolectric.buildActivity(Activity.class).setup();
        sheet = new Motion();
        controller = new MediaController();
        controller.item = new MediaItem.Builder().setMediaId("dQw4w9WgXcQ").build();
        VideoSwitch.enabled = true;
        set("motion", new WeakReference<>(sheet));
        set("controller", new WeakReference<>(controller));
        set("pill", new WeakReference<>(null));
    }

    @After
    public void cleanup() throws Exception {
        sheet.window = null;
        VideoSwitch.attach();
        set("motion", new WeakReference<>(null));
        set("controller", new WeakReference<>(null));
        VideoSwitch.enabled = false;
        first.pause().stop().destroy();
        second.pause().stop().destroy();
    }

    @Test
    public void openingAndRecreatingTheWindowRestoresTheVisibleSwitch() throws Exception {
        VideoSwitch.attach();
        assertNull(pill());
        sheet.window = first.get().getWindow();
        VideoSwitch.attach();
        View original = pill();
        assertNotNull(original);
        assertEquals(View.VISIBLE, original.getVisibility());
        sheet.window = second.get().getWindow();
        VideoSwitch.attach();
        assertNull(original.getParent());
        assertNotSame(original, pill());
        assertSame(
                sheet.window.getDecorView().findViewById(android.R.id.content), pill().getParent());
        assertEquals(View.VISIBLE, pill().getVisibility());
        sheet.window = null;
        View replaced = pill();
        VideoSwitch.attach();
        assertNull(replaced.getParent());
        assertNull(pill());
        sheet.window = first.get().getWindow();
        VideoSwitch.attach();
        assertEquals(View.VISIBLE, pill().getVisibility());
    }

    @Test
    public void duplicateWindowWritesDoNotDuplicateTheSwitch() throws Exception {
        sheet.window = first.get().getWindow();
        VideoSwitch.attach();
        View original = pill();
        ViewGroup parent = (ViewGroup) original.getParent();
        int count = parent.getChildCount();
        VideoSwitch.attach();
        assertSame(original, pill());
        assertEquals(count, parent.getChildCount());
    }

    @Test
    public void closedPlayerAndExternalTracksHideTheControl() throws Exception {
        sheet.window = first.get().getWindow();
        VideoSwitch.attach();
        sheet.open = false;
        ((VideoSwitch.Pill) pill()).refresh();
        assertEquals(View.GONE, pill().getVisibility());
        sheet.open = true;
        controller.item = new MediaItem.Builder().setMediaId("tg:123").build();
        ((VideoSwitch.Pill) pill()).refresh();
        assertEquals(View.GONE, pill().getVisibility());
    }
}

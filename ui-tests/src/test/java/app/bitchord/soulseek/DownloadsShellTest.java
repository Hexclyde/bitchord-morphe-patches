package app.bitchord.soulseek;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Intent;
import android.view.*;
import android.widget.*;

import androidx.compose.runtime.ComposerImpl;

import com.music.bitchord.ui.components.BottomTab;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;

import java.util.*;

/**
 * Real Android views through a host ABI fixture; actual host descriptors are checked in patched
 * APKs.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class DownloadsShellTest {
    ActivityController<Activity> owner;
    Activity activity;
    FrameLayout root, content;
    TextView miniPlayer;
    ComposerImpl composer;
    List<BottomTab> tabs;
    List<Integer> selected;

    public static class Padding {
        public float calculateTopPadding() {
            return 90;
        }

        public float calculateBottomPadding() {
            return 210;
        }
    }

    @Before
    public void setup() {
        owner = Robolectric.buildActivity(Activity.class).setup();
        activity = owner.get();
        SoulseekBridge.reset(activity);
        root = new FrameLayout(activity);
        content = new FrameLayout(activity);
        miniPlayer = new TextView(activity);
        miniPlayer.setText("BitChord mini player");
        root.addView(content);
        root.addView(miniPlayer);
        activity.setContentView(root);
        composer = new ComposerImpl(activity, content);
        tabs =
                Arrays.asList(
                        new BottomTab("Home", null),
                        new BottomTab("Explore", null),
                        new BottomTab("Library", null),
                        new BottomTab("Search", null));
        selected = new ArrayList<>();
        DownloadNavigation.callback(
                tabs,
                (Function1<Object, Object>)
                        value -> {
                            selected.add(((Number) value).intValue());
                            return Unit.INSTANCE;
                        });
        DownloadNavigation.index(tabs, 3);
    }

    @After
    public void cleanup() throws Exception {
        DownloadsShell.close();
        owner.pause().stop().destroy();
        for (String name : new String[] {"callback", "wrapped"}) {
            java.lang.reflect.Field f = DownloadNavigation.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(null, null);
        }
        SoulseekBridge.instance.work.shutdownNow();
    }

    @Test
    public void downloadsUseThePlayerContentSlotAndMeasuredPadding() {
        assertTrue(DownloadsShell.open(new Intent(activity, DownloadsHubActivity.class)));
        assertEquals(Arrays.asList(2), selected);
        assertTrue(DownloadsShell.compose(null, new Padding(), composer));
        assertEquals(2, composer.defaults);
        assertNotNull(composer.modifier);
        assertSame(miniPlayer, root.getChildAt(1));
        assertSame(root, miniPlayer.getParent());
        assertEquals(1, content.getChildCount());
        assertEquals(
                Math.round(90 * activity.getResources().getDisplayMetrics().density),
                composer.view.getPaddingTop());
        assertEquals(
                Math.round(210 * activity.getResources().getDisplayMetrics().density),
                composer.view.getPaddingBottom());
        assertEquals(2, DownloadNavigation.index(tabs, 2));
        assertEquals("Downloads", DownloadsShell.heading("Library"));
        assertTrue(androidx.compose.runtime.ParcelableSnapshotMutableState.reads > 0);
        assertNotNull(coil.util.DrawableUtils.back);
        coil.util.DrawableUtils.back.invoke();
        assertFalse(DownloadsShell.visible());
        assertEquals(Arrays.asList(2, 3), selected);
        assertSame(miniPlayer, root.getChildAt(1));
    }

    @Test
    public void explicitModifierIsPreservedWithoutDefaulting() {
        DownloadsShell.open(new Intent(activity, DownloadsHubActivity.class));
        androidx.compose.ui.Modifier modifier = new androidx.compose.ui.Modifier() {};
        assertTrue(DownloadsShell.compose(modifier, new Padding(), composer));
        assertSame(modifier, composer.modifier);
        assertEquals(0, composer.defaults);
    }

    @Test
    public void explorerAndBackStayInsideTheSamePlayer() {
        DownloadsShell.open(new Intent(activity, DownloadsHubActivity.class));
        DownloadsShell.compose(null, new Padding(), composer);
        View embedded = composer.view;
        assertTrue(
                DownloadsShell.navigate(
                        activity, new Intent(activity, DownloadExplorerActivity.class)));
        DownloadsShell.compose(null, new Padding(), composer);
        assertSame(embedded, composer.view);
        assertSame(miniPlayer, root.getChildAt(1));
        assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
        assertTrue(DownloadsShell.handleBack());
        DownloadsShell.compose(null, new Padding(), composer);
        assertSame(embedded, composer.view);
        assertTrue(DownloadsShell.visible());
        assertTrue(DownloadsShell.handleBack());
        assertFalse(DownloadsShell.visible());
    }

    @Test
    public void backgroundingStopsOnlyTheEmbeddedPageWork() throws Exception {
        DownloadsShell.open(new Intent(activity, DownloadExplorerActivity.class));
        DownloadsShell.compose(null, new Padding(), composer);
        java.lang.reflect.Field pageField = composer.view.getClass().getDeclaredField("page");
        pageField.setAccessible(true);
        Object page = pageField.get(composer.view);
        java.lang.reflect.Field foreground = page.getClass().getDeclaredField("foreground");
        foreground.setAccessible(true);
        assertTrue(foreground.getBoolean(page));
        owner.pause().stop();
        assertFalse(foreground.getBoolean(page));
        owner.start().resume();
        assertTrue(foreground.getBoolean(page));
        assertSame(miniPlayer, root.getChildAt(1));
    }

    @Test
    public void nativeLibraryAndSearchKeepTheirOriginalRoutes() {
        Function1<Object, Object> click =
                (Function1<Object, Object>)
                        DownloadNavigation.callback(
                                tabs,
                                (Function1<Object, Object>)
                                        value -> {
                                            selected.add(((Number) value).intValue());
                                            return Unit.INSTANCE;
                                        });
        DownloadsShell.open(new Intent(activity, DownloadsHubActivity.class));
        click.invoke(3);
        assertFalse(DownloadsShell.visible());
        assertEquals(2, (int) selected.get(selected.size() - 1));
        assertEquals(3, DownloadNavigation.index(tabs, 2));
        click.invoke(4);
        assertEquals(3, (int) selected.get(selected.size() - 1));
    }
}

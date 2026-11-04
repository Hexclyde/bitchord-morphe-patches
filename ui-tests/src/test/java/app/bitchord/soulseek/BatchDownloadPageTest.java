package app.bitchord.soulseek;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import com.music.bitchord.data.model.Song;

import kotlin.Unit;
import kotlin.jvm.functions.Function2;

import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class BatchDownloadPageTest {
    ActivityController<Activity> controller;
    Activity activity;
    BatchDownloadPage page;
    final String token = "duration-batch";
    Locale before;

    @Before
    public void open() throws Exception {
        before = Locale.getDefault();
        Locale.setDefault(Locale.ENGLISH);
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        Class<?> pendingType = Class.forName(SoulseekDownloadChoice.class.getName() + "$Pending");
        Constructor<?> constructor = pendingType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Function2<Object, Object, Object> original = (songs, from) -> Unit.INSTANCE;
        List<Song> songs =
                Arrays.asList(
                        new Song("first-track", "First", "First artist", "3:14"),
                        new Song("second-track", "Second", "Second artist", "5:02"),
                        new Song("third-track", "Third", "Third artist", "?"));
        Field pending = SoulseekDownloadChoice.class.getDeclaredField("pending");
        pending.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) pending.get(null);
        map.put(token, constructor.newInstance(original, songs, null));
        page =
                new BatchDownloadPage(
                        activity,
                        new Intent().putExtra(SoulseekDownloadChoice.EXTRA_CHOICE, token));
        page.onCreate(null);
        render();
    }

    void render() throws Exception {
        Method method = BatchDownloadPage.class.getDeclaredMethod("render");
        method.setAccessible(true);
        method.invoke(page);
    }

    @After
    public void close() {
        if (page != null) page.onDestroy();
        SoulseekDownloadChoice.forget(token);
        DownloadBatch.forget(token);
        if (controller != null) controller.pause().stop().destroy();
        Locale.setDefault(before);
    }

    static List<View> named(View root, String text) {
        List<View> matches = new ArrayList<>();
        if (root instanceof TextView && ((TextView) root).getText().toString().equals(text))
            matches.add(root);
        if (root instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++)
                matches.addAll(named(((ViewGroup) root).getChildAt(i), text));
        return matches;
    }

    List<View> choices() {
        return named(page.view, "Choose source and quality");
    }

    @Test
    public void everyTrackShowsItsOwnOriginalDurationIncludingUnknown() {
        assertEquals(1, named(page.view, "Duration · 3:14").size());
        assertEquals(1, named(page.view, "Duration · 5:02").size());
        assertEquals(1, named(page.view, "Duration · Not reported").size());
    }

    @Test
    public void secondRowButtonPassesItsOwnFreshTrackMetadata() {
        assertEquals(3, choices().size());
        assertTrue(choices().get(1) instanceof Button);
        choices().get(1).performClick();
        Intent next = shadowOf(activity).getNextStartedActivity();
        assertNotNull(next);
        assertEquals(1, next.getIntExtra("download.batch.index", -1));
        assertEquals("Second", next.getStringExtra("download.track.title"));
        assertEquals("Second artist", next.getStringExtra("download.track.artist"));
        assertEquals("5:02", next.getStringExtra("download.track.duration"));
        assertEquals("", next.getStringExtra("download.track.artwork"));
        assertEquals(
                "Second artist Second", next.getStringExtra(SoulseekDownloadChoice.EXTRA_QUERY));
        DownloadPage summary = new DownloadPage(activity, next) {};
        DownloadExplorerView explorer =
                new DownloadExplorerView(summary, true, () -> {}, () -> {}, (option, now) -> {});
        assertEquals(1, named(explorer.root, "Second artist · 5:02").size());
        assertTrue(named(explorer.root, "First artist · 3:14").isEmpty());
    }

    @Test
    public void firstAndUnknownRowsDoNotReuseSecondRowMetadata() {
        choices().get(0).performClick();
        Intent first = shadowOf(activity).getNextStartedActivity();
        choices().get(2).performClick();
        Intent third = shadowOf(activity).getNextStartedActivity();
        assertNotSame(first, third);
        assertEquals("3:14", first.getStringExtra("download.track.duration"));
        assertEquals("Third", third.getStringExtra("download.track.title"));
        assertEquals("Not reported", third.getStringExtra("download.track.duration"));
    }

    @Test
    public void sourceChoiceKeepsQualityAndReportedDurationWhenReturningToBatch() throws Exception {
        AtomicInteger transfers = new AtomicInteger();
        DownloadExplorerView.Option option =
                new DownloadExplorerView.Option(
                        "Source",
                        "Second",
                        "Second artist",
                        "FLAC · 24-bit",
                        "FLAC · 5:01",
                        true,
                        null,
                        now -> transfers.incrementAndGet(),
                        null);
        DownloadExplorerPage explorer =
                new DownloadExplorerPage(
                        activity,
                        new Intent()
                                .putExtra(SoulseekDownloadChoice.EXTRA_CHOICE, token)
                                .putExtra("download.batch.index", 1));
        Field field = DownloadExplorerPage.class.getDeclaredField("choice");
        field.setAccessible(true);
        field.set(explorer, token);
        Method admit =
                DownloadExplorerPage.class.getDeclaredMethod(
                        "admit", DownloadExplorerView.Option.class, boolean.class);
        admit.setAccessible(true);
        admit.invoke(explorer, option, false);
        assertEquals(
                "Source · Second · FLAC · 24-bit · 5:01", DownloadBatch.choice(token, 1).detail);
        assertNull(DownloadBatch.choice(token, 0));
        assertEquals(0, transfers.get());
        render();
        assertEquals(1, named(page.view, "Source · Second · FLAC · 24-bit · 5:01").size());
        assertEquals(1, named(page.view, "Duration · 5:02").size());
        explorer.onDestroy();
    }

    @Test
    public void peerDurationUsesReportedSecondsAndShowsUnknownRatherThanGuessing()
            throws Exception {
        JSONObject row =
                new JSONObject()
                        .put("peer", "peer")
                        .put("path", "Artist - Song.flac")
                        .put("size", 90000000)
                        .put("duration", 213)
                        .put("bitrate", 999);
        assertTrue(
                DownloadExplorerPage.soulseekDetail(activity, new SoulseekTrack(row))
                        .contains("Duration: 3:33"));
        row.put("duration", 0);
        assertTrue(
                DownloadExplorerPage.soulseekDetail(activity, new SoulseekTrack(row))
                        .contains("Duration: Not reported"));
    }

    @Test
    public void invalidAndZeroReferenceDurationsAreExplicitlyUnknown() {
        for (String value :
                new String[] {null, "", "?", "0", "0:00", "0:00:00", "3:99", "-1", "2147483648"})
            assertEquals("Not reported", DownloadDetails.referenceDuration(value));
        assertEquals("3:34", DownloadDetails.referenceDuration("3:34"));
        assertEquals("1:03:34", DownloadDetails.referenceDuration("1:03:34"));
    }
}

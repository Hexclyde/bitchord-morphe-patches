package app.bitchord.soulseek;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class PeerDownloadRefreshTest {
    private final List<Runnable> queued = new ArrayList<>();
    private final List<Integer> applied = new ArrayList<>();
    private final AtomicInteger loads = new AtomicInteger();
    private int failures;

    private PeerDownloadRows.Refresher<Integer> refresher() {
        return new PeerDownloadRows.Refresher<>(
                queued::add,
                new Handler(Looper.getMainLooper()),
                1500,
                loads::incrementAndGet,
                new PeerDownloadRows.Refresher.Target<Integer>() {
                    @Override
                    public void apply(Integer value) {
                        applied.add(value);
                    }

                    @Override
                    public void failed() {
                        failures++;
                    }
                });
    }

    private void runQueued() {
        List<Runnable> current = new ArrayList<>(queued);
        queued.clear();
        for (Runnable task : current) task.run();
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void nothingLoadsOnTheUiThreadOrBeforeResume() {
        PeerDownloadRows.Refresher<Integer> refresher = refresher();
        refresher.request();
        assertEquals(0, queued.size());
        refresher.resume();
        assertEquals(1, queued.size());
        assertEquals("Loading waits for the background executor", 0, loads.get());
    }

    @Test
    public void requestsDuringALoadCollapseIntoOneFollowUp() {
        PeerDownloadRows.Refresher<Integer> refresher = refresher();
        refresher.resume();
        for (int i = 0; i < 20; i++) refresher.request();
        assertEquals(1, queued.size());
        runQueued();
        assertEquals(Collections.singletonList(1), applied);
        assertEquals("One coalesced follow-up", 1, queued.size());
        runQueued();
        assertEquals(Arrays.asList(1, 2), applied);
        assertEquals("No further immediate load", 0, queued.size());
    }

    @Test
    public void refreshesAtTheIntervalOnlyAfterTheLastLoadFinished() {
        PeerDownloadRows.Refresher<Integer> refresher = refresher();
        refresher.resume();
        runQueued();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1499));
        assertEquals(0, queued.size());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2));
        assertEquals(1, queued.size());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5000));
        assertEquals("A slow load cannot stack ticks", 1, queued.size());
    }

    @Test
    public void aResultArrivingAfterPauseIsDropped() {
        PeerDownloadRows.Refresher<Integer> refresher = refresher();
        refresher.resume();
        refresher.pause();
        runQueued();
        assertTrue(applied.isEmpty());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5000));
        assertEquals("Paused pages do not keep polling", 0, queued.size());
    }

    @Test
    public void onlyTheNewestLoadAppliesAfterResumingTwice() {
        PeerDownloadRows.Refresher<Integer> refresher = refresher();
        refresher.resume();
        Runnable stale = queued.remove(0);
        refresher.pause();
        refresher.resume();
        Runnable fresh = queued.remove(0);
        fresh.run();
        stale.run();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Collections.singletonList(1), applied);
    }

    @Test
    public void failedLoadsReportAndKeepRefreshing() {
        PeerDownloadRows.Refresher<Integer> refresher =
                new PeerDownloadRows.Refresher<>(
                        queued::add,
                        new Handler(Looper.getMainLooper()),
                        1500,
                        () -> {
                            throw new java.io.IOException("offline");
                        },
                        new PeerDownloadRows.Refresher.Target<Integer>() {
                            @Override
                            public void apply(Integer value) {
                                fail("Nothing to apply");
                            }

                            @Override
                            public void failed() {
                                failures++;
                            }
                        });
        refresher.resume();
        runQueued();
        assertEquals(1, failures);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1501));
        assertEquals(1, queued.size());
    }

    @Test
    public void aClosedExecutorDoesNotBreakThePage() {
        PeerDownloadRows.Refresher<Integer> refresher =
                new PeerDownloadRows.Refresher<>(
                        task -> {
                            throw new RejectedExecutionException();
                        },
                        new Handler(Looper.getMainLooper()),
                        1500,
                        loads::incrementAndGet,
                        new PeerDownloadRows.Refresher.Target<Integer>() {
                            @Override
                            public void apply(Integer value) {}

                            @Override
                            public void failed() {}
                        });
        refresher.resume();
        refresher.request();
        assertEquals(0, loads.get());
    }

    @Test
    public void malformedPeerKeysStillRender() {
        assertEquals("…", PeerDownloadRows.shortKey(null));
        assertEquals("…", PeerDownloadRows.shortKey(""));
        assertEquals("abc", PeerDownloadRows.shortKey("abc"));
        assertEquals("abcdefabcdef", PeerDownloadRows.shortKey("abcdefabcdef"));
        assertEquals("abcdefabcdef…", PeerDownloadRows.shortKey("abcdefabcdef012345"));
        assertEquals("abc", PeerDownloadRows.shortKey("a\nb\u202ec"));
    }

    @Test
    public void stateChangesKeepTheFocusedControlsIdentity() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout section = new LinearLayout(activity);
        section.setOrientation(LinearLayout.VERTICAL);
        activity.setContentView(section);
        PeerDownloadRows rows = new PeerDownloadRows(new DownloadStyle(activity));
        rows.group(section);
        rows.begin(section);
        LinearLayout entry = rows.row(section, "job");
        rows.text(entry, "Track", 16);
        rows.progress(entry, 10, 100);
        LinearLayout strip = rows.actions(entry);
        Button control = rows.action(strip, "Pause", "Pause, Track", false, () -> {});
        ImageButton more = rows.overflow(strip, "Actions for Track", () -> {});
        rows.end(section);
        control.setFocusableInTouchMode(true);
        control.requestFocus();
        assertEquals(1000, ((ProgressBar) entry.getChildAt(1)).getMax());
        assertEquals(100, ((ProgressBar) entry.getChildAt(1)).getProgress());
        assertEquals(
                activity.getResources().getDisplayMetrics().density * 48,
                more.getLayoutParams().height,
                1);

        rows.begin(section);
        assertSame(entry, rows.row(section, "job"));
        rows.text(entry, "Track", 16);
        // The job finished downloading: no progress bar, and its action changed to Resume.
        assertSame(strip, rows.actions(entry));
        assertSame(control, rows.action(strip, "Resume", "Resume, Track", false, () -> {}));
        assertSame(more, rows.overflow(strip, "Actions for Track", () -> {}));
        rows.end(section);

        assertEquals(2, entry.getChildCount());
        assertEquals("Resume", control.getText().toString());
        assertEquals("Resume, Track", control.getContentDescription().toString());
        assertTrue(control.hasFocus());
        activity.finish();
    }

    @Test
    public void rowsWithoutActionsLoseTheirStrip() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout section = new LinearLayout(activity);
        PeerDownloadRows rows = new PeerDownloadRows(new DownloadStyle(activity));
        rows.begin(section);
        LinearLayout entry = rows.row(section, "job");
        rows.text(entry, "Track", 16);
        rows.overflow(rows.actions(entry), "Actions", () -> {});
        rows.end(section);
        assertEquals(2, entry.getChildCount());
        rows.begin(section);
        rows.row(section, "job");
        rows.text(entry, "Track", 16);
        rows.end(section);
        assertEquals(1, entry.getChildCount());
        rows.begin(section);
        rows.row(section, "job");
        rows.text(entry, "Track", 16);
        rows.overflow(rows.actions(entry), "Actions", () -> {});
        rows.end(section);
        assertEquals(2, entry.getChildCount());
        activity.finish();
    }

    @Test
    public void aRowGrowingAboveKeepsTheVisibleRowInPlace() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ScrollView scroll = new ScrollView(activity);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);
        activity.setContentView(scroll);
        View above = new View(activity);
        body.addView(above, new LinearLayout.LayoutParams(-1, 300));
        LinearLayout visibleRow = new LinearLayout(activity);
        visibleRow.setTag("peer-row:visible");
        body.addView(visibleRow, new LinearLayout.LayoutParams(-1, 2000));
        layout(scroll);
        scroll.scrollTo(0, 500);
        assertEquals(500, scroll.getScrollY());
        PeerDownloadRows.ScrollAnchor anchor = new PeerDownloadRows.ScrollAnchor(scroll);
        anchor.capture();
        above.setLayoutParams(new LinearLayout.LayoutParams(-1, 450));
        layout(scroll);
        scroll.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(650, scroll.getScrollY());
        activity.finish();
    }

    private static void layout(View view) {
        view.measure(
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 400, 800);
    }

    /** The activity needs the full download runtime, so its Stop contract is pinned at source. */
    @Test
    public void stoppingPeerWorkLeavesOtherDownloadsRunning() throws Exception {
        Path source =
                Paths.get("..")
                        .resolve(
                                "extensions/soulseek/src/main/java/app/bitchord/soulseek/BulkDownloadsActivity.java");
        if (!Files.exists(source)) source = Paths.get(source.toString().substring(3));
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        assertTrue(text.contains("open().disablePeers()"));
        assertFalse(text.contains("SoulseekSelectedDownloads.cancelAll"));
        assertFalse(text.contains("BulkTransferService.requestStop"));
        assertFalse("Resume must not open setup", text.contains("destinationSetupAsked"));
    }
}

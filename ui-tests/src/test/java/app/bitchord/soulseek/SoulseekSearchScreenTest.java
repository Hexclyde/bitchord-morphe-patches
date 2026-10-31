package app.bitchord.soulseek;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.LocaleList;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import app.bitchord.telegram.CapturePolicy;
import app.bitchord.telegram.PatchStrings;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Real screen and format policy, with deterministic search/queue adapters and no network. */
@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(
        manifest = Config.NONE,
        sdk = 35,
        qualifiers = "w393dp-h852dp-night-mdpi",
        instrumentedPackages = "app.bitchord.telegram.CapturePolicy")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
public class SoulseekSearchScreenTest {
    @Implements(value = CapturePolicy.class, isInAndroidSdk = false)
    public static class TestingBuild {
        @Implementation
        protected static boolean testingBuild() {
            return true;
        }
    }

    private ActivityController<SoulseekSearchActivity> controller;
    private SoulseekSearchActivity activity;
    private SoulseekBridge bridge;
    private Locale previous;

    @Before
    public void prepare() throws Exception {
        previous = Locale.getDefault();
        Locale.setDefault(Locale.ENGLISH);
        Context app = RuntimeEnvironment.getApplication();
        app.getSystemService(android.app.LocaleManager.class)
                .setApplicationLocales(LocaleList.getEmptyLocaleList());
        app.getApplicationInfo().flags |= android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL;
        PatchStrings.refresh(app);
        shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions(android.Manifest.permission.HIDE_OVERLAY_WINDOWS);
        bridge = SoulseekBridge.reset(app);
        DownloadSettings.reset();
        DownloadDestinations.configured = true;
        DownloadDestinations.mode = DownloadDestinations.PHONE;
        DownloadDestinations.opened = 0;
        SoulseekSelectedDownloads.selectedReferences.clear();
        SoulseekSelectedDownloads.accepted = true;
        AudioFormatPreferences.save(app, AudioFormatPreferences.defaults());
    }

    @After
    public void close() {
        if (bridge.searchBlock != null) bridge.searchBlock.countDown();
        if (controller != null) controller.pause().stop().destroy();
        bridge.work.shutdownNow();
        RuntimeEnvironment.getApplication()
                .getSystemService(android.app.LocaleManager.class)
                .setApplicationLocales(LocaleList.getEmptyLocaleList());
        Locale.setDefault(previous);
        PatchStrings.refresh(RuntimeEnvironment.getApplication());
    }

    private void create() {
        create(null);
    }

    private void create(Intent intent) {
        controller =
                (intent == null
                                ? Robolectric.buildActivity(SoulseekSearchActivity.class)
                                : Robolectric.buildActivity(SoulseekSearchActivity.class, intent))
                        .setup();
        activity = controller.get();
        shadowOf(Looper.getMainLooper()).idle();
        layout();
    }

    @Test
    public void progressLinkOpensDownloadsInsteadOfPeerSharingSetup() {
        create();
        java.util.ArrayList<View> matches = new java.util.ArrayList<>();
        activity.getWindow()
                .getDecorView()
                .findViewsWithText(matches, "View downloads", View.FIND_VIEWS_WITH_TEXT);
        assertEquals(1, matches.size());
        matches.get(0).performClick();
        Intent target = shadowOf(activity).getNextStartedActivity();
        assertNotNull(target);
        assertEquals(DownloadsHubActivity.class.getName(), target.getComponent().getClassName());
    }

    private void layout() {
        View decor = activity.getWindow().getDecorView();
        decor.measure(
                View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(852, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 393, 852);
    }

    private View tagged(String name) {
        return activity.findViewById(android.R.id.content).findViewWithTag(name);
    }

    private EditText query() {
        return (EditText) tagged("soulseek-search-query");
    }

    private Button submit() {
        return (Button) tagged("soulseek-search-submit");
    }

    private ViewGroup results() {
        return (ViewGroup) tagged("soulseek-search-results");
    }

    private String status() {
        return ((TextView) tagged("soulseek-search-status")).getText().toString();
    }

    private void until(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        do {
            shadowOf(Looper.getMainLooper()).idle();
            if (condition.getAsBoolean()) return;
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        fail(message);
    }

    private void search(String value) throws Exception {
        query().setText(value);
        submit().performClick();
        assertFalse("Query remains bound to the in-flight request", query().isEnabled());
        until(() -> bridge.searches == 1 && submit().isEnabled(), "search did not finish");
        assertTrue("Query can be edited after success or failure", query().isEnabled());
        layout();
    }

    private String text(View view) {
        StringBuilder found = new StringBuilder();
        if (view instanceof TextView) found.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) found.append(text(group.getChildAt(i)));
        }
        return found.toString();
    }

    private SoulseekTrack track(String peer, String file) throws Exception {
        return new SoulseekTrack(
                new JSONObject()
                        .put("peer", peer)
                        .put("path", file)
                        .put("size", 6_000_000)
                        .put("duration", 180)
                        .put("freeSlots", 1)
                        .put("speed", 200_000));
    }

    @Test
    public void openingAndEditingQueryNeverStartSearchOrQueueFiles() {
        create();
        query().setText("Fixture artist Fixture song");
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, bridge.searches);
        assertTrue(SoulseekSelectedDownloads.selectedReferences.isEmpty());
        assertEquals(0, results().getChildCount());
        assertTrue(activity.downloadStyle.dark);
    }

    @Test
    public void prefilledQueryNeverStartsAnAutomaticSearch() {
        create(
                new Intent(RuntimeEnvironment.getApplication(), SoulseekSearchActivity.class)
                        .putExtra(
                                SoulseekDownloadChoice.EXTRA_QUERY, "Fixture artist Fixture song"));
        assertEquals("Fixture artist Fixture song", query().getText().toString());
        assertEquals(0, bridge.searches);
        assertTrue(SoulseekSelectedDownloads.selectedReferences.isEmpty());
    }

    @Test
    public void lostProcessChoiceIsVisibleAndCannotStartOriginalDownload() {
        create(
                new Intent(RuntimeEnvironment.getApplication(), SoulseekSearchActivity.class)
                        .putExtra(
                                SoulseekDownloadChoice.EXTRA_CHOICE, "lost-after-process-restart"));
        assertFalse(tagged("soulseek-choice-original").isEnabled());
        TextView visible = (TextView) tagged("soulseek-choice-status");
        assertTrue(visible.isShown());
        assertTrue(
                visible.getText().toString(),
                visible.getText().toString().toLowerCase(Locale.ROOT).contains("expired"));
        assertEquals(View.GONE, tagged("soulseek-search-form").getVisibility());
        assertEquals(0, bridge.searches);
        assertTrue(SoulseekSelectedDownloads.selectedReferences.isEmpty());
    }

    @Test
    public void explicitFileSelectionQueuesOnlyItsCatalogReferenceWithProvenanceVisible()
            throws Exception {
        SoulseekTrack flac = track("fixture-peer", "Fixture artist - Fixture song.flac");
        SoulseekTrack mp3 = track("other-peer", "Fixture artist - Fixture song.mp3");
        bridge.searchRows.add(flac);
        bridge.searchRows.add(mp3);
        create();
        search("Fixture artist Fixture song");
        assertEquals("Fixture artist Fixture song", bridge.lastQuery);
        assertTrue(SoulseekSelectedDownloads.selectedReferences.isEmpty());
        String visible = text(results());
        assertTrue(visible, visible.contains("Fixture song"));
        assertTrue(visible, visible.contains("fixture-peer"));
        assertTrue(status(), status().toLowerCase(Locale.ROOT).contains("unverified"));
        View download = tagged("soulseek-search-download-" + flac.id);
        assertNotNull(download);
        download.performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, SoulseekSelectedDownloads.selectedReferences.size());
        assertEquals(
                "src:soulseek-native::" + flac.id,
                SoulseekSelectedDownloads.selectedReferences.get(0));
    }

    @Test
    public void disabledFormatsAreFilteredBeforeFileSelection() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        AudioFormatPreferences.Settings policy = AudioFormatPreferences.defaults();
        for (AudioFormatPreferences.Format format : policy.order)
            if (format != AudioFormatPreferences.Format.FLAC)
                policy = policy.withEnabled(format, false);
        AudioFormatPreferences.save(app, policy);
        SoulseekTrack flac = track("fixture-peer", "Fixture artist - Fixture song.flac");
        SoulseekTrack mp3 = track("other-peer", "Fixture artist - Fixture song.mp3");
        bridge.searchRows.add(flac);
        bridge.searchRows.add(mp3);
        create();
        search("Fixture song");
        assertNotNull(tagged("soulseek-search-download-" + flac.id));
        assertNull(tagged("soulseek-search-download-" + mp3.id));
    }

    @Test
    public void reconnectInvalidatesVisibleFileChoiceWithoutSubstitution() throws Exception {
        SoulseekTrack track = track("fixture-peer", "Fixture artist - Fixture song.flac");
        bridge.searchRows.add(track);
        create();
        search("Fixture song");
        bridge.sessionTag = "reconnected-session";
        tagged("soulseek-search-download-" + track.id).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(SoulseekSelectedDownloads.selectedReferences.isEmpty());
        assertTrue(status(), status().contains("search again"));
    }

    @Test
    public void sourceDisabledAndSignedOutStatesPreventSearch() {
        DownloadSettings.soulseek = false;
        create();
        query().setText("Fixture song");
        submit().performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, bridge.searches);
        assertTrue(SoulseekSelectedDownloads.selectedReferences.isEmpty());
        controller.pause().stop().destroy();
        controller = null;
        DownloadSettings.soulseek = true;
        bridge.connected = false;
        create();
        query().setText("Fixture song");
        submit().performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, bridge.searches);
        assertTrue(SoulseekSelectedDownloads.selectedReferences.isEmpty());
    }

    @Test
    public void searchFailureShowsRetryableStateWithoutOriginalDownloadFallback() throws Exception {
        bridge.searchFailure = true;
        create();
        search("Fixture song");
        assertFalse(status(), status().contains("fixture-peer-secret"));
        assertTrue(SoulseekSelectedDownloads.selectedReferences.isEmpty());
        assertEquals(0, results().getChildCount());
    }

    @Test
    public void stoppingWithSearchInFlightCannotPublishLateResultsOrDownload() throws Exception {
        bridge.searchRows.add(track("fixture-peer", "Fixture artist - Fixture song.flac"));
        bridge.searchBlock = new CountDownLatch(1);
        bridge.searchEntered = new CountDownLatch(1);
        bridge.ignoreInterrupts = true;
        create();
        query().setText("Fixture song");
        submit().performClick();
        assertTrue(bridge.searchEntered.await(3, TimeUnit.SECONDS));
        assertFalse("In-flight query cannot be changed under its results", query().isEnabled());
        controller.pause().stop();
        assertTrue("Cancelling on page stop restores query editing", query().isEnabled());
        bridge.searchBlock.countDown();
        java.lang.reflect.Field field = SoulseekSearchActivity.class.getDeclaredField("worker");
        field.setAccessible(true);
        java.util.concurrent.ThreadPoolExecutor worker =
                (java.util.concurrent.ThreadPoolExecutor) field.get(activity);
        until(
                () -> worker.getActiveCount() == 0 && worker.getQueue().isEmpty(),
                "stale search did not exit");
        assertEquals(0, results().getChildCount());
        assertTrue(SoulseekSelectedDownloads.selectedReferences.isEmpty());
        controller.destroy();
        controller = null;
    }

    @Test
    @Config(shadows = TestingBuild.class)
    public void testingSearchScreenAllowsCaptureAndFollowsHostThemeAndRtl() {
        RuntimeEnvironment.setQualifiers("w393dp-h852dp-notnight-mdpi");
        RuntimeEnvironment.getApplication()
                .getSystemService(android.app.LocaleManager.class)
                .setApplicationLocales(new LocaleList(new Locale("ar")));
        PatchStrings.refresh(RuntimeEnvironment.getApplication());
        create();
        assertFalse(activity.downloadStyle.dark);
        assertEquals(Color.WHITE, activity.downloadStyle.background);
        assertEquals(
                View.LAYOUT_DIRECTION_RTL,
                activity.getWindow().getDecorView().getLayoutDirection());
        assertEquals(
                0,
                activity.getWindow().getAttributes().flags
                        & WindowManager.LayoutParams.FLAG_SECURE);
        assertTrue(submit().getMinimumHeight() >= activity.downloadStyle.dp(48));
    }
}

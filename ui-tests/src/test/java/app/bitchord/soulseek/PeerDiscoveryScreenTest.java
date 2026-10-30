package app.bitchord.soulseek;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.ClipboardManager;
import android.content.Context;
import android.os.LocaleList;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;

import app.bitchord.telegram.PatchStrings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, qualifiers = "w393dp-h852dp-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
public class PeerDiscoveryScreenTest {
    private ActivityController<PeerDiscoveryActivity> controller;
    private PeerDiscoveryActivity activity;
    private PeerDiscovery service;
    private CountDownLatch release;
    private Locale previous;

    @Before
    public void prepare() {
        previous = Locale.getDefault();
        Locale.setDefault(Locale.ENGLISH);
        RuntimeEnvironment.getApplication()
                .getSystemService(android.app.LocaleManager.class)
                .setApplicationLocales(LocaleList.getEmptyLocaleList());
        PatchStrings.refresh(RuntimeEnvironment.getApplication());
        RuntimeEnvironment.getApplication().getApplicationInfo().flags |=
                android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL;
        shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions(android.Manifest.permission.HIDE_OVERLAY_WINDOWS);
        PeerDiscovery.fixture = service = new PeerDiscovery();
    }

    private void create() throws Exception {
        controller = Robolectric.buildActivity(PeerDiscoveryActivity.class).setup();
        activity = controller.get();
        settle();
        View decor = activity.getWindow().getDecorView();
        decor.measure(
                View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(852, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 393, 852);
    }

    @After
    public void cleanup() {
        if (release != null) release.countDown();
        if (controller != null) controller.pause().stop().destroy();
        RuntimeEnvironment.getApplication()
                .getSystemService(android.app.LocaleManager.class)
                .setApplicationLocales(LocaleList.getEmptyLocaleList());
        Locale.setDefault(previous);
        PatchStrings.refresh(RuntimeEnvironment.getApplication());
        PeerDiscovery.fixture = null;
    }

    private ThreadPoolExecutor worker() throws Exception {
        Field field = PeerDiscoveryActivity.class.getDeclaredField("worker");
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(activity);
    }

    private void settle() throws Exception {
        ThreadPoolExecutor worker = worker();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        do {
            shadowOf(Looper.getMainLooper()).idle();
            if (worker.getActiveCount() == 0 && worker.getQueue().isEmpty()) {
                shadowOf(Looper.getMainLooper()).idle();
                if (worker.getActiveCount() == 0 && worker.getQueue().isEmpty()) return;
            }
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        fail("Discovery settings worker did not finish");
    }

    private View tagged(String tag) {
        return activity.findViewById(android.R.id.content).findViewWithTag(tag);
    }

    private CompoundButton toggle(String tag) {
        return (CompoundButton) tagged(tag);
    }

    private boolean hasText(View view, String part) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(part))
            return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                if (hasText(group.getChildAt(i), part)) return true;
        }
        return false;
    }

    private int copyButtons(View view) {
        int count =
                view.getTag() instanceof String && ((String) view.getTag()).startsWith("copy-peer:")
                        ? 1
                        : 0;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                count += copyButtons(group.getChildAt(i));
        }
        return count;
    }

    @Test
    public void defaultsAreCompactAndDoNotStartNetworkOrAdvertising() throws Exception {
        create();
        assertFalse(toggle("discovery-enabled").isChecked());
        assertFalse(toggle("discovery-advertise").isChecked());
        assertEquals(
                "android.widget.Switch", toggle("discovery-enabled").getAccessibilityClassName());
        assertFalse(tagged("discovery-gear").isEnabled());
        assertEquals(48, tagged("discovery-gear").getHeight());
        assertEquals(48, toggle("discovery-enabled").getHeight());
        assertEquals(View.GONE, tagged("discovery-details").getVisibility());
        assertEquals(0, service.starts);
        assertEquals(0, service.advertised);
    }

    @Test
    public void enableExpandsAndSeparatePublishingSettingNeverStartsScan() throws Exception {
        create();
        toggle("discovery-enabled").performClick();
        settle();
        assertTrue(service.enabled);
        assertTrue(tagged("discovery-gear").isEnabled());
        assertEquals(View.VISIBLE, tagged("discovery-details").getVisibility());
        assertFalse(toggle("discovery-advertise").isChecked());
        toggle("discovery-advertise").performClick();
        settle();
        assertTrue(service.advertise);
        assertEquals(0, service.starts);
        assertEquals(View.VISIBLE, tagged("discovery-details").getVisibility());
        tagged("discovery-gear").performClick();
        assertEquals(View.GONE, tagged("discovery-details").getVisibility());
        tagged("discovery-gear").performClick();
        assertEquals(View.VISIBLE, tagged("discovery-details").getVisibility());
        toggle("discovery-enabled").performClick();
        settle();
        assertFalse(service.enabled);
        assertTrue(service.stops > 0);
        assertFalse(tagged("discovery-gear").isEnabled());
        assertEquals(View.GONE, tagged("discovery-details").getVisibility());
    }

    @Test
    public void failedSaveRestoresActualSettingAndDoesNotExposeFailureDetails() throws Exception {
        create();
        service.fail = true;
        toggle("discovery-enabled").performClick();
        settle();
        assertFalse(toggle("discovery-enabled").isChecked());
        assertFalse(tagged("discovery-gear").isEnabled());
        assertTrue(
                hasText(activity.getWindow().getDecorView(), "Could not update peer discovery."));
        assertFalse(hasText(activity.getWindow().getDecorView(), "fixture failure"));
    }

    @Test
    public void candidatesArePagedRecentUntrustedKeysWithExplicitCopyOnly() throws Exception {
        service.enabled = true;
        for (int i = 0; i < 117; i++) service.candidates.add(new PeerDiscovery.Candidate(i));
        create();
        tagged("discovery-gear").performClick();
        View root = activity.getWindow().getDecorView();
        assertEquals(50, copyButtons(root));
        assertTrue(hasText(root, "Recent · expires at"));
        assertFalse(hasText(root, "Online"));
        tagged("discovery-more").performClick();
        assertEquals(100, copyButtons(root));
        tagged("discovery-more").performClick();
        assertEquals(117, copyButtons(root));
        assertEquals(View.GONE, tagged("discovery-more").getVisibility());
        String full = service.candidates.get(83).key;
        tagged("copy-peer:" + full).performClick();
        ClipboardManager clipboard =
                (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        assertEquals(full, clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        assertEquals(0, service.starts);
        assertTrue(hasText(root, "Compare the full key before pairing."));
    }

    @Test
    public void backgroundCancelsQueuedScanAndResumeDoesNotRestartIt() throws Exception {
        service.enabled = true;
        create();
        tagged("discovery-gear").performClick();
        CountDownLatch entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        worker().execute(
                        () -> {
                            entered.countDown();
                            try {
                                release.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                        });
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        tagged("discovery-find").performClick();
        controller.pause().stop();
        release.countDown();
        settle();
        assertEquals(0, service.starts);
        assertTrue(service.stops > 0);
        controller.restart().start().resume().visible();
        settle();
        assertEquals(0, service.starts);
        tagged("discovery-find").performClick();
        settle();
        assertEquals(1, service.starts);
        controller.pause().stop();
        settle();
        assertFalse(service.running);
        controller.restart().start().resume().visible();
        settle();
        assertEquals(1, service.starts);
    }

    @Test
    public void rtlLayoutKeepsTechnicalPeerKeysLeftToRight() throws Exception {
        RuntimeEnvironment.getApplication()
                .getSystemService(android.app.LocaleManager.class)
                .setApplicationLocales(LocaleList.forLanguageTags("ar"));
        service.enabled = true;
        PeerDiscovery.Candidate candidate = new PeerDiscovery.Candidate(34);
        service.candidates.add(candidate);
        create();
        tagged("discovery-gear").performClick();
        View decor = activity.getWindow().getDecorView();
        assertEquals(View.LAYOUT_DIRECTION_RTL, decor.getLayoutDirection());
        String title = PatchStrings.t("Peer discovery");
        assertNotEquals("Peer discovery", title);
        assertTrue(title.matches(".*[\\u0600-\\u06ff].*"));
        assertTrue(hasText(decor, title));
        String publication = PatchStrings.t("Discoverable while this page is open");
        assertNotEquals("Discoverable while this page is open", publication);
        assertEquals(publication, tagged("discovery-advertise").getContentDescription());
        String shortened = candidate.key.substring(0, 12) + "…" + candidate.key.substring(56);
        java.util.ArrayList<View> keys = new java.util.ArrayList<>();
        decor.findViewsWithText(keys, shortened, View.FIND_VIEWS_WITH_TEXT);
        assertEquals(1, keys.size());
        assertEquals(View.TEXT_DIRECTION_LTR, keys.get(0).getTextDirection());
        assertEquals(0, service.starts);
    }

    @Test
    public void destructionPreservesTheStopBarrierBehindAnInFlightAction() throws Exception {
        service.enabled = service.running = true;
        create();
        CountDownLatch entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        ThreadPoolExecutor worker = worker();
        worker.execute(
                () -> {
                    entered.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        controller.pause().stop().destroy();
        controller = null;
        release.countDown();
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        assertFalse(service.running);
        assertTrue(service.stops > 0);
    }
}

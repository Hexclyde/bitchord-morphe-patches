package app.bitchord.soulseek;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.graphics.Rect;
import android.os.LocaleList;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
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
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, qualifiers = "w393dp-h852dp-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
public class SoulseekMainScreenTest {
    private ActivityController<SoulseekActivity> controller;
    private SoulseekActivity activity;
    private SoulseekBridge bridge;
    private Locale previous;

    @Before
    public void prepare() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        previous = Locale.getDefault();
        Locale.setDefault(Locale.ENGLISH);
        app.getSystemService(android.app.LocaleManager.class)
                .setApplicationLocales(LocaleList.getEmptyLocaleList());
        app.getApplicationInfo().flags |= android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL;
        shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions(android.Manifest.permission.HIDE_OVERLAY_WINDOWS);
        PatchStrings.refresh(app);
        bridge = SoulseekBridge.reset(app);
        DownloadSettings.reset();
        DownloadDestinations.configured = true;
        DownloadDestinations.mode = DownloadDestinations.PHONE;
        DownloadDestinations.opened = 0;
        AudioFormatPreferences.save(app, AudioFormatPreferences.defaults());
    }

    private void create() throws Exception {
        controller = Robolectric.buildActivity(SoulseekActivity.class).setup();
        activity = controller.get();
        settle();
        layout();
    }

    private void layout() {
        View decor = activity.getWindow().getDecorView();
        decor.measure(
                View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(852, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 393, 852);
    }

    @After
    public void close() {
        if (bridge.loginBlock != null) bridge.loginBlock.countDown();
        if (controller != null) controller.pause().stop().destroy();
        bridge.work.shutdownNow();
        RuntimeEnvironment.getApplication()
                .getSystemService(android.app.LocaleManager.class)
                .setApplicationLocales(LocaleList.getEmptyLocaleList());
        Locale.setDefault(previous);
        PatchStrings.refresh(RuntimeEnvironment.getApplication());
    }

    private View tagged(String tag) {
        return activity.findViewById(android.R.id.content).findViewWithTag(tag);
    }

    private EditText input(String tag) {
        return (EditText) tagged(tag);
    }

    private String status() {
        return ((TextView) tagged("soulseek-status")).getText().toString();
    }

    private boolean contains(View view, String value) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(value))
            return true;
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
                if (contains(((ViewGroup) view).getChildAt(i), value)) return true;
        return false;
    }

    private ThreadPoolExecutor worker() throws Exception {
        Field f = SoulseekActivity.class.getDeclaredField("worker");
        f.setAccessible(true);
        return (ThreadPoolExecutor) f.get(activity);
    }

    private void settle() throws Exception {
        ThreadPoolExecutor w = worker();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        do {
            shadowOf(Looper.getMainLooper()).idle();
            if (w.getActiveCount() == 0 && w.getQueue().isEmpty()) {
                shadowOf(Looper.getMainLooper()).idle();
                if (w.getActiveCount() == 0 && w.getQueue().isEmpty()) return;
            }
            Thread.sleep(5);
        } while (System.nanoTime() < end);
        fail("Main settings worker did not finish");
    }

    private void until(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
            if (condition.getAsBoolean()) {
                settle();
                return;
            }
            Thread.sleep(5);
        }
        fail("Account state did not update");
    }

    @Test
    public void disabledSourceDoesNotClaimTheRetainedAccountIsSignedIn() throws Exception {
        DownloadSettings.soulseek = false;
        bridge.connected = true;
        create();
        assertFalse(((CompoundButton) tagged("soulseek-enabled")).isChecked());
        assertTrue(status().contains("Enable Soulseek"));
        assertFalse(status().contains("Signed in"));
        assertFalse(tagged("soulseek-find").isEnabled());
        assertFalse(tagged("soulseek-account-gear").isEnabled());
        assertEquals(View.GONE, tagged("soulseek-account-details").getVisibility());
        assertEquals(View.GONE, tagged("soulseek-setup").getVisibility());
        assertEquals(View.VISIBLE, tagged("soulseek-optional").getVisibility());
        assertEquals(0, DownloadDestinations.opened);
        assertNull(shadowOf(activity).getNextStartedActivity());
    }

    @Test
    public void readyOverviewShowsTheActualSetupAndOpensOnlyTheSearchScreen() throws Exception {
        bridge.username = "demo-listener";
        DownloadSettings.peers = DownloadSettings.debrid = false;
        DownloadDestinations.mode = DownloadDestinations.BOTH;
        create();
        assertEquals("Signed in as demo-listener", status());
        assertEquals(
                "Ready to search", ((TextView) tagged("soulseek-readiness")).getText().toString());
        assertTrue(tagged("soulseek-find").isEnabled());
        assertEquals(View.GONE, tagged("soulseek-account-details").getVisibility());
        assertEquals(View.GONE, tagged("soulseek-optional-details").getVisibility());
        assertTrue(contains(tagged("soulseek-destinations"), "Both"));
        assertTrue(contains(tagged("soulseek-formats"), "9 formats enabled"));
        assertTrue(contains(tagged("soulseek-formats"), "FLAC · ALAC · WAV"));
        assertFalse(contains(activity.getWindow().getDecorView(), "Search Addons"));
        assertEquals(48, tagged("soulseek-account-gear").getHeight());
        Rect statusRect = new Rect(), setupRect = new Rect();
        assertTrue(tagged("soulseek-status").getGlobalVisibleRect(statusRect));
        assertTrue(tagged("soulseek-formats").getGlobalVisibleRect(setupRect));
        assertTrue(statusRect.bottom < setupRect.top);
        assertTrue(setupRect.bottom <= 852);
        assertEquals(tagged("soulseek-formats").getHeight(), setupRect.height());
        tagged("soulseek-find").performClick();
        android.content.Intent opened = shadowOf(activity).getNextStartedActivity();
        assertEquals(SoulseekSearchActivity.class.getName(), opened.getComponent().getClassName());
        assertTrue(opened.getExtras() == null || opened.getExtras().isEmpty());
        assertEquals(0, bridge.searches);
    }

    @Test
    public void missingSetupStaysInlineAndAccountSecretsClearAfterExplicitSignIn()
            throws Exception {
        bridge.connected = false;
        bridge.status = "Not signed in";
        bridge.credentials.saved = null;
        bridge.username = "";
        DownloadDestinations.configured = false;
        RuntimeEnvironment.getApplication()
                .getSharedPreferences("bitchord_audio_formats", 0)
                .edit()
                .clear()
                .commit();
        create();
        assertEquals(0, DownloadDestinations.opened);
        assertNull(shadowOf(activity).getNextStartedActivity());
        assertFalse(tagged("soulseek-find").isEnabled());
        assertEquals(View.GONE, tagged("soulseek-account-details").getVisibility());
        tagged("soulseek-open-account").performClick();
        input("soulseek-username").setText("new-listener");
        input("soulseek-password").setText("fixture-not-a-real-password");
        assertFalse(input("soulseek-password").isSaveEnabled());
        assertEquals(View.TEXT_DIRECTION_LTR, input("soulseek-username").getTextDirection());
        ((CompoundButton) tagged("soulseek-remember")).setChecked(false);
        tagged("soulseek-sign-in").performClick();
        assertEquals("", input("soulseek-password").getText().toString());
        until(() -> bridge.lastLoginMode == 2 && status().contains("Signed in as"));
        assertNull(bridge.credentials.saved);
        assertEquals(View.GONE, tagged("soulseek-account-details").getVisibility());
        assertFalse(tagged("soulseek-find").isEnabled());
        assertTrue(
                ((TextView) tagged("soulseek-readiness"))
                        .getText()
                        .toString()
                        .contains("where copies go"));
        tagged("soulseek-destinations").performClick();
        assertEquals(1, DownloadDestinations.opened);
        assertEquals(
                DownloadDestinationActivity.class.getName(),
                shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
    }

    @Test
    public void sourceCanBeDisabledWhileTheLoginWorkerIsBusy() throws Exception {
        bridge.connected = false;
        bridge.status = "Not signed in";
        bridge.credentials.saved = null;
        bridge.loginEntered = new CountDownLatch(1);
        bridge.loginBlock = new CountDownLatch(1);
        create();
        tagged("soulseek-open-account").performClick();
        input("soulseek-username").setText("new-listener");
        input("soulseek-password").setText("fixture-password");
        tagged("soulseek-sign-in").performClick();
        assertTrue(bridge.loginEntered.await(3, TimeUnit.SECONDS));
        ((CompoundButton) tagged("soulseek-enabled")).performClick();
        settle();
        assertFalse(DownloadSettings.soulseek);
        assertFalse(tagged("soulseek-find").isEnabled());
        assertTrue(status().contains("Enable Soulseek"));
        assertEquals("", input("soulseek-password").getText().toString());
        bridge.loginBlock.countDown();
    }

    @Test
    public void optionalToolsDoNotMakeSoulseekReadyAndOffToolsCannotOpen() throws Exception {
        DownloadSettings.peers = false;
        DownloadSettings.debrid = false;
        bridge.connected = false;
        bridge.status = "Not signed in";
        create();
        tagged("soulseek-optional-toggle").performClick();
        assertEquals(View.VISIBLE, tagged("soulseek-optional-details").getVisibility());
        assertFalse(tagged("soulseek-peers").isEnabled());
        assertFalse(tagged("soulseek-debrid").isEnabled());
        assertFalse(tagged("soulseek-find").isEnabled());
        assertNull(shadowOf(activity).getNextStartedActivity());
    }

    @Test
    public void signedInAccountCanBeForgottenFromItsSettingsWithoutDisablingTheSource()
            throws Exception {
        create();
        tagged("soulseek-account-gear").performClick();
        tagged("soulseek-account-actions-toggle").performClick();
        input("soulseek-password").setText("dummy-secret");
        tagged("soulseek-forget").performClick();
        assertEquals("", input("soulseek-password").getText().toString());
        assertEquals("", input("soulseek-username").getText().toString());
        until(() -> bridge.forgets == 1 && status().equals("Not signed in"));
        assertNull(bridge.credentials.saved);
        assertTrue(DownloadSettings.soulseek);
        assertFalse(tagged("soulseek-find").isEnabled());
        assertEquals(View.GONE, tagged("soulseek-reconnect").getVisibility());
    }

    @Test
    public void unreadableSavedCredentialsDoNotBlockAccountRecovery() throws Exception {
        bridge.credentials.unreadable = true;
        create();
        assertTrue(tagged("soulseek-account-gear").isEnabled());
        tagged("soulseek-account-gear").performClick();
        tagged("soulseek-account-actions-toggle").performClick();
        tagged("soulseek-forget").performClick();
        until(() -> bridge.forgets == 1 && status().equals("Not signed in"));
        assertNull(bridge.credentials.saved);
        assertFalse(bridge.credentials.unreadable);
        assertFalse(status().contains("fixture protected storage failure"));
    }
}

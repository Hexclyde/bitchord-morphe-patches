package app.bitchord.soulseek;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;

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

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.crypto.spec.SecretKeySpec;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, qualifiers = "w393dp-h852dp-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
public class DebridSetupScreenTest {
    private ActivityController<DebridSetupActivity> controller;
    private DebridSetupActivity activity;
    private DebridCredentials credentials;
    private MemoryStorage storage;

    private static final class MemoryStorage implements DebridCredentials.Storage {
        volatile byte[] sealed;
        volatile boolean fail;
        volatile CountDownLatch entered, release;

        public byte[] read() {
            return sealed;
        }

        public void write(byte[] bytes) throws IOException {
            if (entered != null) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Gate timeout");
                } catch (InterruptedException interrupted) {
                    throw new IOException(interrupted);
                }
            }
            if (fail) throw new IOException("Protected storage unavailable");
            sealed = bytes.clone();
        }

        public void clear() {
            sealed = null;
        }
    }

    @Before
    public void prepareCredentials() throws Exception {
        shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions(android.Manifest.permission.HIDE_OVERLAY_WINDOWS);
        storage = new MemoryStorage();
        credentials =
                new DebridCredentials(
                        storage,
                        new DebridCredentials.AesCrypto(
                                create -> new SecretKeySpec(new byte[32], "AES"),
                                "debrid-ui-fixture"));
        instance(credentials);
        DebridDownloads.validated = null;
    }

    private void create() throws Exception {
        controller = Robolectric.buildActivity(DebridSetupActivity.class).setup();
        activity = controller.get();
        settle();
        View decor = activity.getWindow().getDecorView();
        decor.measure(
                View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(852, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 393, 852);
    }

    @After
    public void stop() throws Exception {
        if (storage.release != null) storage.release.countDown();
        if (controller != null) controller.pause().stop().destroy();
        instance(null);
    }

    private static void instance(DebridCredentials value) throws Exception {
        Field field = DebridCredentials.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, value);
    }

    private void settle() throws Exception {
        Field field = DebridSetupActivity.class.getDeclaredField("worker");
        field.setAccessible(true);
        ThreadPoolExecutor worker = (ThreadPoolExecutor) field.get(activity);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        do {
            shadowOf(Looper.getMainLooper()).idle();
            if (worker.getActiveCount() == 0 && worker.getQueue().isEmpty()) {
                shadowOf(Looper.getMainLooper()).idle();
                return;
            }
            Thread.sleep(5);
        } while (System.nanoTime() < end);
        fail("Settings worker did not complete");
    }

    private View root() {
        return activity.findViewById(android.R.id.content);
    }

    private View described(View view, String value) {
        if (value.equals(view.getContentDescription())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = described(group.getChildAt(i), value);
                if (found != null) return found;
            }
        }
        return null;
    }

    private TextView text(View view, String value) {
        if (view instanceof TextView && value.contentEquals(((TextView) view).getText()))
            return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = text(group.getChildAt(i), value);
                if (found != null) return found;
            }
        }
        return null;
    }

    private CompoundButton toggle(DebridProvider.Kind kind) {
        return (CompoundButton) described(root(), "Enable " + kind.label);
    }

    private View gear(DebridProvider.Kind kind) {
        return described(root(), kind.label + " settings");
    }

    private View details(DebridProvider.Kind kind) {
        return described(root(), kind.label + " controls");
    }

    private AlertDialog dialog() throws Exception {
        Field field = DebridSetupActivity.class.getDeclaredField("keyDialog");
        field.setAccessible(true);
        return (AlertDialog) field.get(activity);
    }

    @Test
    public void providersStartCollapsedWithNativeSwitchesAndDisabledSettings() throws Exception {
        create();
        for (DebridProvider.Kind kind : DebridProvider.Kind.values()) {
            assertFalse(toggle(kind).isChecked());
            assertEquals("android.widget.Switch", toggle(kind).getAccessibilityClassName());
            assertFalse(gear(kind).isEnabled());
            assertEquals(48, gear(kind).getHeight());
            assertEquals(View.GONE, details(kind).getVisibility());
        }
        assertNotNull(described(root(), "Back"));
        assertTrue(
                (activity.getWindow().getAttributes().flags
                                & WindowManager.LayoutParams.FLAG_SECURE)
                        != 0);
    }

    @Test
    public void enableWithoutKeyUsesProtectedPromptAndCancelKeepsDisabled() throws Exception {
        create();
        toggle(DebridProvider.Kind.REAL_DEBRID).performClick();
        AlertDialog dialog = dialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        assertTrue(
                (dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE)
                        != 0);
        EditText input =
                (EditText)
                        described(
                                dialog.getWindow().getDecorView(), "Real-Debrid API key to enable");
        assertEquals(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                input.getInputType() & InputType.TYPE_MASK_VARIATION);
        assertFalse(input.isSaveEnabled());
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, input.getImportantForAutofill());
        input.setText("fixture-cancelled-key");
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("", input.getText().toString());
        assertFalse(toggle(DebridProvider.Kind.REAL_DEBRID).isChecked());
        assertFalse(credentials.snapshot(DebridProvider.Kind.REAL_DEBRID).enabled);
        assertFalse(gear(DebridProvider.Kind.REAL_DEBRID).isEnabled());
        assertEquals(View.GONE, details(DebridProvider.Kind.REAL_DEBRID).getVisibility());
    }

    @Test
    public void successfulEncryptedSaveEnablesAndExpandsOnlyAfterWriteCompletes() throws Exception {
        create();
        storage.entered = new CountDownLatch(1);
        storage.release = new CountDownLatch(1);
        toggle(DebridProvider.Kind.TORBOX).performClick();
        AlertDialog dialog = dialog();
        EditText input =
                (EditText) described(dialog.getWindow().getDecorView(), "TorBox API key to enable");
        input.setText("fixture-committed-key");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(storage.entered.await(5, TimeUnit.SECONDS));
        assertFalse(toggle(DebridProvider.Kind.TORBOX).isChecked());
        assertFalse(gear(DebridProvider.Kind.TORBOX).isEnabled());
        assertEquals(View.GONE, details(DebridProvider.Kind.TORBOX).getVisibility());
        storage.release.countDown();
        settle();
        assertTrue(toggle(DebridProvider.Kind.TORBOX).isChecked());
        assertTrue(credentials.snapshot(DebridProvider.Kind.TORBOX).enabled);
        assertTrue(gear(DebridProvider.Kind.TORBOX).isEnabled());
        assertEquals(View.VISIBLE, details(DebridProvider.Kind.TORBOX).getVisibility());
        assertEquals("", input.getText().toString());
        assertFalse(
                new String(storage.sealed, StandardCharsets.UTF_8)
                        .contains("fixture-committed-key"));
        for (DebridProvider.Kind kind : DebridProvider.Kind.values())
            if (kind != DebridProvider.Kind.TORBOX)
                assertEquals(View.GONE, details(kind).getVisibility());
    }

    @Test
    public void failedProtectedSaveLeavesServiceDisabledAndPromptCleared() throws Exception {
        create();
        storage.fail = true;
        toggle(DebridProvider.Kind.ALLDEBRID).performClick();
        AlertDialog dialog = dialog();
        EditText input =
                (EditText)
                        described(dialog.getWindow().getDecorView(), "AllDebrid API key to enable");
        input.setText("fixture-failed-key");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
        assertFalse(credentials.snapshot(DebridProvider.Kind.ALLDEBRID).configured);
        assertFalse(toggle(DebridProvider.Kind.ALLDEBRID).isChecked());
        assertFalse(gear(DebridProvider.Kind.ALLDEBRID).isEnabled());
        assertEquals(View.GONE, details(DebridProvider.Kind.ALLDEBRID).getVisibility());
        assertEquals("", input.getText().toString());
    }

    @Test
    public void settingsReopenOneProviderAndDisableCollapsesWithoutRemovingSavedKey()
            throws Exception {
        credentials.save(DebridProvider.Kind.REAL_DEBRID, "fixture-existing-key", true);
        credentials.save(DebridProvider.Kind.PREMIUMIZE, "fixture-other-key", true);
        create();
        gear(DebridProvider.Kind.REAL_DEBRID).performClick();
        assertEquals(View.VISIBLE, details(DebridProvider.Kind.REAL_DEBRID).getVisibility());
        gear(DebridProvider.Kind.PREMIUMIZE).setFocusableInTouchMode(true);
        assertTrue(gear(DebridProvider.Kind.PREMIUMIZE).requestFocus());
        gear(DebridProvider.Kind.PREMIUMIZE).performClick();
        assertEquals(View.GONE, details(DebridProvider.Kind.REAL_DEBRID).getVisibility());
        assertEquals(View.VISIBLE, details(DebridProvider.Kind.PREMIUMIZE).getVisibility());
        toggle(DebridProvider.Kind.REAL_DEBRID).setFocusableInTouchMode(true);
        assertTrue(toggle(DebridProvider.Kind.REAL_DEBRID).requestFocus());
        assertEquals(View.GONE, details(DebridProvider.Kind.PREMIUMIZE).getVisibility());
        gear(DebridProvider.Kind.REAL_DEBRID).performClick();
        toggle(DebridProvider.Kind.REAL_DEBRID).performClick();
        settle();
        assertFalse(credentials.snapshot(DebridProvider.Kind.REAL_DEBRID).enabled);
        assertTrue(credentials.snapshot(DebridProvider.Kind.REAL_DEBRID).configured);
        assertEquals(View.GONE, details(DebridProvider.Kind.REAL_DEBRID).getVisibility());
        assertFalse(gear(DebridProvider.Kind.REAL_DEBRID).isEnabled());
    }

    @Test
    public void nestedTestAccountAndRemoveActionsKeepProviderScope() throws Exception {
        credentials.save(DebridProvider.Kind.PREMIUMIZE, "fixture-existing-key", true);
        create();
        gear(DebridProvider.Kind.PREMIUMIZE).performClick();
        View panel = details(DebridProvider.Kind.PREMIUMIZE);
        text(panel, "Test API key").performClick();
        settle();
        assertEquals(DebridProvider.Kind.PREMIUMIZE, DebridDownloads.validated);
        text(panel, "Open Premiumize account").performClick();
        Intent intent = shadowOf(activity).getNextStartedActivity();
        assertEquals(Intent.ACTION_VIEW, intent.getAction());
        assertEquals("https://www.premiumize.me/account", intent.getDataString());
        text(panel, "Remove API key").performClick();
        settle();
        assertFalse(credentials.snapshot(DebridProvider.Kind.PREMIUMIZE).configured);
        assertFalse(toggle(DebridProvider.Kind.PREMIUMIZE).isChecked());
        assertFalse(gear(DebridProvider.Kind.PREMIUMIZE).isEnabled());
        assertEquals(View.GONE, panel.getVisibility());
    }
}

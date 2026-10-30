package app.bitchord.soulseek;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.app.LocaleManager;
import android.os.LocaleList;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.bitchord.telegram.PatchStrings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.crypto.spec.SecretKeySpec;

/** Exercises actual translated download pages without provider traffic or real credentials. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, qualifiers = "w393dp-h852dp-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
public class DownloadLocalizationTest {
    private Locale before;
    private ActivityController<? extends DownloadActivity> controller;
    private DownloadActivity activity;

    @Before
    public void reset() throws Exception {
        before = Locale.getDefault();
        Locale.setDefault(Locale.ENGLISH);
        localeManager().setApplicationLocales(LocaleList.getEmptyLocaleList());
        RuntimeEnvironment.getApplication().getApplicationInfo().flags |=
                android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL;
        shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions(android.Manifest.permission.HIDE_OVERLAY_WINDOWS);
        RuntimeEnvironment.getApplication()
                .getSharedPreferences("bitchord_audio_formats", 0)
                .edit()
                .clear()
                .commit();
        credentials(null);
        PatchStrings.refresh(RuntimeEnvironment.getApplication());
    }

    @After
    public void close() throws Exception {
        if (controller != null) controller.pause().stop().destroy();
        credentials(null);
        localeManager().setApplicationLocales(LocaleList.getEmptyLocaleList());
        Locale.setDefault(before);
        PatchStrings.refresh(RuntimeEnvironment.getApplication());
    }

    private LocaleManager localeManager() {
        return RuntimeEnvironment.getApplication().getSystemService(LocaleManager.class);
    }

    private void formats() {
        controller = Robolectric.buildActivity(AudioFormatSetupActivity.class).setup();
        activity = controller.get();
        layout();
    }

    private void layout() {
        View decor = activity.getWindow().getDecorView();
        decor.measure(
                View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(852, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 393, 852);
    }

    private View root() {
        return activity.findViewById(android.R.id.content);
    }

    private TextView text(View view, String value) {
        if (view instanceof TextView && value.contentEquals(((TextView) view).getText()))
            return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView result = text(group.getChildAt(i), value);
                if (result != null) return result;
            }
        }
        return null;
    }

    private <T extends View> T first(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                T result = first(group.getChildAt(i), type);
                if (result != null) return result;
            }
        }
        return null;
    }

    private void translated(String english) {
        String localized = PatchStrings.t(english);
        assertNotEquals("Missing translation: " + english, english, localized);
        assertNotNull("Translated control is rendered: " + localized, text(root(), localized));
        assertNull("The English control is replaced", text(root(), english));
    }

    @Test
    public void frenchPhoneLanguageTranslatesFormatPageAndKeepsCodecNames() {
        Locale.setDefault(Locale.FRENCH);
        formats();
        translated("Download formats");
        translated("Save format choices");
        assertNotNull(text(root(), "FLAC"));
        assertEquals(
                View.LAYOUT_DIRECTION_LTR, first(root(), ScrollView.class).getLayoutDirection());
    }

    @Test
    public void arabicFormatPageUsesTranslatedLabelsAndRightToLeftLayout() {
        Locale.setDefault(Locale.forLanguageTag("ar"));
        formats();
        translated("Download formats");
        translated("Save format choices");
        assertEquals(
                View.LAYOUT_DIRECTION_RTL, first(root(), ScrollView.class).getLayoutDirection());
    }

    @Test
    public void applicationLanguageOverridesEnglishPhoneLanguageImmediately() {
        localeManager().setApplicationLocales(new LocaleList(Locale.FRENCH));
        formats();
        assertEquals("en", Locale.getDefault().getLanguage());
        assertEquals("fr", PatchStrings.locale().getLanguage());
        translated("Download formats");
        translated("Save format choices");
    }

    @Test
    public void translatedProviderDialogLeavesSecretsFilenamesAndRelayUrlsUnchanged()
            throws Exception {
        Locale.setDefault(Locale.FRENCH);
        MemoryStorage storage = new MemoryStorage();
        DebridCredentials saved =
                new DebridCredentials(
                        storage,
                        new DebridCredentials.AesCrypto(
                                create -> new SecretKeySpec(new byte[32], "AES"),
                                "debrid-localization-fixture"));
        credentials(saved);
        controller = Robolectric.buildActivity(DebridSetupActivity.class).setup();
        activity = controller.get();
        settle();
        layout();
        translated("Debrid download services");
        CompoundButton enabled = first(root(), CompoundButton.class);
        assertNotEquals("Enable Real-Debrid", enabled.getContentDescription().toString());
        enabled.performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(dialog.isShowing());
        assertEquals("Annuler", dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
        String save = PatchStrings.t("Save and enable");
        assertNotEquals("Save and enable", save);
        assertEquals(save, dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        EditText key = first(dialog.getWindow().getDecorView(), EditText.class);
        key.setText("Cancel");
        TextView filename =
                activity.downloadStyle.text(
                        "Search",
                        16,
                        activity.downloadStyle.foreground,
                        activity.downloadStyle.regular);
        EditText relay = new EditText(activity);
        relay.setText("wss://relay.example/Cancel?key=Search");
        LinearLayout body = (LinearLayout) first(root(), ScrollView.class).getChildAt(0);
        body.addView(filename);
        body.addView(relay);
        activity.downloadStyle.applyTree(root());
        activity.downloadStyle.applyTree(dialog.getWindow().getDecorView());
        assertEquals("Cancel", key.getText().toString());
        assertEquals("Search", filename.getText().toString());
        assertEquals("wss://relay.example/Cancel?key=Search", relay.getText().toString());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
        assertEquals("Cancel", saved.keyForValidation(DebridProvider.Kind.REAL_DEBRID));
        assertTrue(saved.snapshot(DebridProvider.Kind.REAL_DEBRID).enabled);
        assertEquals("", key.getText().toString());
        assertFalse(new String(storage.sealed, StandardCharsets.UTF_8).contains("Cancel"));
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

    private static void credentials(DebridCredentials value) throws Exception {
        Field field = DebridCredentials.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, value);
    }

    private static final class MemoryStorage implements DebridCredentials.Storage {
        volatile byte[] sealed;

        public byte[] read() {
            return sealed;
        }

        public void write(byte[] value) {
            sealed = value.clone();
        }

        public void clear() {
            sealed = null;
        }
    }
}

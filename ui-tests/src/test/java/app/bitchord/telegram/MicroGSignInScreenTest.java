package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.ListView;
import android.widget.TextView;

import com.music.bitchord.data.settings.AppSettings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;

/** Actual sign-in entry and WebView fallback with synthetic authenticator availability. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MicroGSignInScreenTest {
    static final String LOGIN =
            "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube"
                    + "&continue=https%3A%2F%2Fmusic.youtube.com%2F";
    ActivityController<Activity> controller;
    Activity activity;
    WebView web;

    @Before
    public void open() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
        web = new WebView(activity);
    }

    @After
    public void close() {
        AppSettings.themeMode.setValue("SYSTEM");
        web.destroy();
        controller.pause().stop().destroy();
    }

    void available() {
        Shadows.shadowOf(AccountManager.get(activity)).addAuthenticator("app.revanced");
    }

    @Test
    public void absentAuthenticatorLoadsOriginalGoogleSignIn() {
        MicroGSignIn.load(web, LOGIN);
        assertEquals(LOGIN, Shadows.shadowOf(web).getLastLoadedUrl());
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
    }

    @Test
    public void availableAuthenticatorOffersMicroGWithoutLoadingLoginFirst() {
        available();
        MicroGSignIn.load(web, LOGIN);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertEquals("Use MicroG", dialog.getButton(DialogInterface.BUTTON_POSITIVE).getText());
        assertNull(Shadows.shadowOf(web).getLastLoadedUrl());
    }

    @Test
    public void choosingGoogleKeepsOriginalSignInUrl() {
        available();
        MicroGSignIn.load(web, LOGIN);
        ShadowAlertDialog.getLatestAlertDialog()
                .getButton(DialogInterface.BUTTON_NEGATIVE)
                .performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(LOGIN, Shadows.shadowOf(web).getLastLoadedUrl());
    }

    @Test
    public void cancellingOfferLoadsOriginalSignIn() {
        available();
        MicroGSignIn.load(web, LOGIN);
        ShadowAlertDialog.getLatestAlertDialog().cancel();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(LOGIN, Shadows.shadowOf(web).getLastLoadedUrl());
    }

    @Test
    public void channelSelectionDoesNotOfferAuthenticationAgain() {
        available();
        MicroGSignIn.load(web, MicroGSignIn.MUSIC);
        assertEquals(MicroGSignIn.MUSIC, Shadows.shadowOf(web).getLastLoadedUrl());
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
    }

    @Test
    public void multipleAccountsHaveAnAppThemedReadableChooser() {
        available();
        for (String name :
                new String[] {
                    "listener.one@example.invalid",
                    "listener.two@example.invalid",
                    "listener.three@example.invalid"
                })
            Shadows.shadowOf(AccountManager.get(activity))
                    .addAccount(new Account(name, "app.revanced"));
        MicroGSignIn.load(web, LOGIN);
        ShadowAlertDialog.getLatestAlertDialog()
                .getButton(DialogInterface.BUTTON_POSITIVE)
                .performClick();
        for (int i = 0; i < 5; i++) Shadows.shadowOf(Looper.getMainLooper()).idle();
        AlertDialog chooser = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(
                "MicroG account rows must stay in the explicitly themed app dialog",
                chooser.getListView());
        assertEquals(3, chooser.getListView().getAdapter().getCount());
        assertNull(
                "Opening the chooser must not start Google login",
                Shadows.shadowOf(web).getLastLoadedUrl());
    }

    AlertDialog chooser(String theme) {
        AppSettings.themeMode.setValue(theme);
        available();
        for (int i = 1; i <= 3; i++)
            Shadows.shadowOf(AccountManager.get(activity))
                    .addAccount(new Account("listener." + i + "@example.invalid", "app.revanced"));
        MicroGSignIn.load(web, LOGIN);
        ShadowAlertDialog.getLatestAlertDialog()
                .getButton(DialogInterface.BUTTON_POSITIVE)
                .performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return ShadowAlertDialog.getLatestAlertDialog();
    }

    TextView text(View view, String value) {
        if (view instanceof TextView && ((TextView) view).getText().toString().equals(value))
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

    static double luminance(int color) {
        double result = 0;
        int[] channels = {Color.red(color), Color.green(color), Color.blue(color)};
        double[] weights = {.2126, .7152, .0722};
        for (int i = 0; i < 3; i++) {
            double c = channels[i] / 255.0;
            result += weights[i] * (c <= .04045 ? c / 12.92 : Math.pow((c + .055) / 1.055, 2.4));
        }
        return result;
    }

    void readable(AlertDialog dialog, String name, int width) throws Exception {
        ListView list = dialog.getListView();
        assertNotNull(list);
        int surface = ((ColorDrawable) list.getBackground()).getColor();
        TextView title = text(dialog.getWindow().getDecorView(), "Choose an account");
        assertNotNull(title);
        for (int i = -1; i < list.getAdapter().getCount(); i++) {
            TextView label = i < 0 ? title : (TextView) list.getAdapter().getView(i, null, list);
            double a = luminance(label.getCurrentTextColor()), b = luminance(surface);
            assertTrue(
                    "Readable title/account text",
                    (Math.max(a, b) + .05) / (Math.min(a, b) + .05) >= 4.5);
            if (i >= 0) assertTrue(label.getMinHeight() >= 48);
        }
        View root = dialog.getWindow().getDecorView();
        root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.AT_MOST));
        root.layout(0, 0, width, root.getMeasuredHeight());
        Bitmap bitmap = Bitmap.createBitmap(width, root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        java.io.File output = new java.io.File("target/previews/microg-" + name + ".png");
        output.getParentFile().mkdirs();
        try (java.io.FileOutputStream stream = new java.io.FileOutputStream(output)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        }
    }

    @Test
    @Config(qualifiers = "w393dp-h852dp-notnight-mdpi")
    public void darkAppHasReadableAccountRowsOnLightSystem() throws Exception {
        readable(chooser("DARK"), "dark", 393);
    }

    @Test
    @Config(qualifiers = "w393dp-h852dp-night-mdpi")
    public void lightAppHasReadableAccountRowsOnDarkSystem() throws Exception {
        readable(chooser("LIGHT"), "light", 393);
    }

    @Test
    public void largeTextWrapsOnANarrowScreen() throws Exception {
        android.content.res.Configuration config =
                new android.content.res.Configuration(activity.getResources().getConfiguration());
        config.fontScale = 1.5f;
        activity.getResources()
                .updateConfiguration(config, activity.getResources().getDisplayMetrics());
        AlertDialog dialog = chooser("DARK");
        readable(dialog, "large-text", 320);
        TextView row = text(dialog.getWindow().getDecorView(), "listener.1@example.invalid");
        assertNotNull(row);
        assertTrue("Large account text wraps", row.getLineCount() >= 2);
        assertTrue(
                "Wrapped text fits the row",
                row.getHeight()
                        >= row.getLineHeight() * row.getLineCount()
                                + row.getPaddingTop()
                                + row.getPaddingBottom());
    }

    @Test
    public void selectedAccountAloneProvidesItsOneTimeLoginLink() {
        AccountManager accounts = AccountManager.get(activity);
        AlertDialog dialog = chooser("DARK");
        Account first = new Account("listener.1@example.invalid", "app.revanced");
        Account selected = new Account("listener.2@example.invalid", "app.revanced");
        String link = "https://accounts.google.com/MergeSession?uberauth=synthetic-second";
        accounts.setAuthToken(
                first,
                MicroGSignIn.tokenType(),
                "https://accounts.google.com/MergeSession?uberauth=synthetic-first");
        accounts.setAuthToken(selected, MicroGSignIn.tokenType(), link);
        ListView list = dialog.getListView();
        list.performItemClick(list.getAdapter().getView(1, null, list), 1, 1);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(link, Shadows.shadowOf(web).getLastLoadedUrl());
        assertNull(accounts.peekAuthToken(selected, MicroGSignIn.tokenType()));
        assertNotNull(accounts.peekAuthToken(first, MicroGSignIn.tokenType()));
    }

    @Test
    public void cancellingAccountChooserReturnsToGoogle() {
        chooser("DARK").cancel();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(LOGIN, Shadows.shadowOf(web).getLastLoadedUrl());
    }

    @Test
    public void invalidSelectedAccountTokenFallsBackToGoogle() {
        AlertDialog dialog = chooser("LIGHT");
        AccountManager.get(activity)
                .setAuthToken(
                        new Account("listener.1@example.invalid", "app.revanced"),
                        MicroGSignIn.tokenType(),
                        "ya29.synthetic-not-a-web-login");
        ListView list = dialog.getListView();
        list.performItemClick(list.getAdapter().getView(0, null, list), 0, 0);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(LOGIN, Shadows.shadowOf(web).getLastLoadedUrl());
    }
}

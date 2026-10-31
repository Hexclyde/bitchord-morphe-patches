package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.accounts.AccountManager;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Looper;
import android.webkit.WebView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

/** Actual sign-in entry and WebView fallback with synthetic authenticator availability. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
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
}

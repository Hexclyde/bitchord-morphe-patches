package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.accounts.AccountManager;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.widget.Button;
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

import java.time.Duration;
import java.util.Collections;
import java.util.Map;

/** Links to other apps inside the YouTube Music sign-in WebView. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LoginNavigationScreenTest {
    static final String LOGIN =
            "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube"
                    + "&prompt=select_account&continue=https%3A%2F%2Fmusic.youtube.com%2F";

    /** The address from the sign-in error page, with an app link's usual fragment. */
    static final String PLAY_STORE =
            "intent://play.google.com/store/apps/details?id=com.google.android.apps.youtube.music"
                + "&pcampaignid=web_signin#Intent;scheme=https;package=com.android.vending;"
                + "S.browser_fallback_url=https%3A%2F%2Fplay.google.com%2Fstore%2Fapps%2Fdetails"
                + "%3Fid%3Dcom.google.android.apps.youtube.music;end";

    static final String MUSIC_APP_LINK =
            "intent://music.youtube.com/?feature=open_in_app#Intent;scheme=https;"
                    + "package=com.google.android.apps.youtube.music;"
                    + "S.browser_fallback_url=https%3A%2F%2Fmusic.youtube.com%2F;end";

    ActivityController<Activity> controller;
    Activity activity;
    WebView web;

    @Before
    public void open() {
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
        web = new WebView(activity);
        activity.setContentView(web);
    }

    @After
    public void close() {
        AppSettings.themeMode.setValue("SYSTEM");
        web.destroy();
        controller.pause().stop().destroy();
    }

    static WebResourceRequest request(
            String url, boolean mainFrame, boolean gesture, boolean redirect) {
        Uri uri = Uri.parse(url);
        return new WebResourceRequest() {
            @Override
            public Uri getUrl() {
                return uri;
            }

            @Override
            public boolean isForMainFrame() {
                return mainFrame;
            }

            @Override
            public boolean isRedirect() {
                return redirect;
            }

            @Override
            public boolean hasGesture() {
                return gesture;
            }

            @Override
            public String getMethod() {
                return "GET";
            }

            @Override
            public Map<String, String> getRequestHeaders() {
                return Collections.emptyMap();
            }
        };
    }

    static String fallback(String target) {
        return "intent://example.invalid/#Intent;scheme=https;package=com.example.app;"
                + "S.browser_fallback_url="
                + Uri.encode(target)
                + ";end";
    }

    void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    String lastLoaded() {
        return Shadows.shadowOf(web).getLastLoadedUrl();
    }

    /** The factory's order: the page load, then registration. */
    void signInPage() {
        web.loadUrl(LOGIN);
        LoginNavigation.track(web, LOGIN);
    }

    void noOtherApp() {
        assertNull(
                "Sign-in must never start another app",
                Shadows.shadowOf(activity).getNextStartedActivity());
        assertNull(Shadows.shadowOf(activity.getApplication()).getNextStartedActivity());
    }

    @Test
    public void otherWebViewsSuchAsPoTokenAreLeftAlone() {
        WebView poToken = new WebView(activity);
        assertFalse(LoginNavigation.tracked(poToken));
        for (boolean main : new boolean[] {true, false})
            for (boolean gesture : new boolean[] {true, false})
                for (String url : new String[] {PLAY_STORE, MUSIC_APP_LINK, "market://x", LOGIN})
                    assertFalse(
                            LoginNavigation.override(poToken, request(url, main, gesture, false)));
        idle();
        assertNull(Shadows.shadowOf(poToken).getLastLoadedUrl());
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
        poToken.destroy();
    }

    @Test
    public void webAddressesInSignInAreLeftToTheWebView() {
        signInPage();
        for (String url :
                new String[] {
                    LOGIN,
                    "https://accounts.google.com/v3/signin/challenge/pwd",
                    "https://consent.youtube.com/m?continue=x",
                    "http://example.invalid/",
                    "about:blank",
                    "data:text/html,ok",
                    "blob:https://accounts.google.com/0d5e"
                })
            for (boolean main : new boolean[] {true, false})
                assertFalse(url, LoginNavigation.override(web, request(url, main, true, false)));
        idle();
        assertEquals(LOGIN, lastLoaded());
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
    }

    @Test
    public void observedPlayStoreRedirectKeepsTheSignInPage() {
        signInPage();
        assertTrue(LoginNavigation.override(web, request(PLAY_STORE, true, false, true)));
        idle();
        assertEquals(
                "The WebView must not load the app link or its Play Store fallback",
                LOGIN,
                lastLoaded());
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
        noOtherApp();
    }

    @Test
    public void frameAppLinksAreStoppedQuietly() {
        signInPage();
        for (String url : new String[] {PLAY_STORE, MUSIC_APP_LINK, "market://details?id=x"})
            for (boolean gesture : new boolean[] {true, false})
                assertTrue(LoginNavigation.override(web, request(url, false, gesture, false)));
        idle();
        assertEquals("A frame never moves the page, even to a sign-in site", LOGIN, lastLoaded());
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
        noOtherApp();
    }

    @Test
    public void tappedAppLinkExplainsAndCanStartAgain() {
        signInPage();
        web.loadUrl("https://accounts.google.com/v3/signin/identifier");
        assertTrue(LoginNavigation.override(web, request(PLAY_STORE, true, true, false)));
        idle();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        assertEquals("Carry on", dialog.getButton(DialogInterface.BUTTON_POSITIVE).getText());
        assertEquals("Start again", dialog.getButton(DialogInterface.BUTTON_NEGATIVE).getText());
        assertTrue(LoginNavigation.override(web, request(PLAY_STORE, true, true, false)));
        idle();
        assertSame(
                "A second link doesn't stack dialogs",
                dialog,
                ShadowAlertDialog.getLatestAlertDialog());
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        idle();
        assertEquals(LOGIN, lastLoaded());
        assertFalse(dialog.isShowing());
        noOtherApp();
    }

    @Test
    public void carryingOnStaysOnTheCurrentPage() {
        signInPage();
        String current = "https://accounts.google.com/v3/signin/identifier";
        web.loadUrl(current);
        assertTrue(
                LoginNavigation.override(web, request("market://details?id=x", true, true, false)));
        idle();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        idle();
        assertFalse(dialog.isShowing());
        assertEquals(current, lastLoaded());
    }

    @Test
    public void redirectBeforeAnyPageExplains() {
        LoginNavigation.track(web, LOGIN);
        assertNull(web.getUrl());
        assertTrue(LoginNavigation.override(web, request(PLAY_STORE, true, false, true)));
        idle();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("A blank sign-in page gets a way back", dialog);
        assertNull(lastLoaded());
    }

    @Test
    public void closingSignInClosesTheExplanation() {
        signInPage();
        assertTrue(LoginNavigation.override(web, request(PLAY_STORE, true, true, false)));
        idle();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(dialog.isShowing());
        ((ViewGroup) web.getParent()).removeView(web);
        idle();
        assertFalse(dialog.isShowing());
    }

    @Test
    public void signInAppLinkOpensItsWebPageInTheSameWebView() {
        signInPage();
        assertTrue(LoginNavigation.override(web, request(MUSIC_APP_LINK, true, false, true)));
        idle();
        assertEquals("https://music.youtube.com/", lastLoaded());
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
        noOtherApp();
    }

    @Test
    public void signInAppLinkWithoutFallbackOpensItsHttpsAddress() {
        signInPage();
        String link =
                "intent://accounts.google.com/ServiceLogin?service=youtube#Intent;scheme=https;"
                        + "package=com.google.android.gms;end";
        assertTrue(LoginNavigation.override(web, request(link, true, true, false)));
        idle();
        assertEquals("https://accounts.google.com/ServiceLogin?service=youtube", lastLoaded());
    }

    @Test
    public void untrustedFallbacksNeverLoad() {
        signInPage();
        String[] targets = {
            "https://play.google.com/store/apps/details?id=x",
            "http://music.youtube.com/",
            "https://music.youtube.com.example.invalid/",
            "https://example.invalid\\@music.youtube.com/",
            "https://example.invalid%5C@music.youtube.com/",
            "https://user@music.youtube.com/",
            "https://music.youtube.com:8443/",
            "https://music.youtube.com\t/",
            "javascript:alert(1)",
            "intent://music.youtube.com/#Intent;scheme=https;end",
            "file:///data/data/com.music.bitchord/shared_prefs/x.xml",
            "content://com.music.bitchord.provider/x"
        };
        for (String target : targets) {
            assertTrue(
                    target,
                    LoginNavigation.override(web, request(fallback(target), true, false, false)));
            assertNull(target, LoginNavigation.webPage(Uri.parse(fallback(target))));
        }
        String foreignData = "intent://example.invalid/#Intent;scheme=https;package=x;end";
        assertTrue(LoginNavigation.override(web, request(foreignData, true, false, false)));
        String httpData = "intent://music.youtube.com/#Intent;scheme=http;package=x;end";
        assertTrue(LoginNavigation.override(web, request(httpData, true, false, false)));
        idle();
        assertEquals(LOGIN, lastLoaded());
        noOtherApp();
    }

    @Test
    public void otherIntentExtrasAndComponentsAreIgnored() {
        signInPage();
        String link =
                "intent://music.youtube.com/#Intent;scheme=https;"
                    + "component=com.example.app/.Steal;action=android.intent.action.SEND;"
                    + "S.token=abc123;S.browser_fallback_url=https%3A%2F%2Fmusic.youtube.com%2F;end";
        assertTrue(LoginNavigation.override(web, request(link, true, true, false)));
        idle();
        assertEquals("https://music.youtube.com/", lastLoaded());
        assertFalse(lastLoaded().contains("abc123"));
        noOtherApp();
    }

    @Test
    public void repeatedAppLinksStopOpeningPagesAndExplain() {
        signInPage();
        for (int i = 0; i < LoginNavigation.MAX_OPENS; i++) {
            assertTrue(LoginNavigation.override(web, request(MUSIC_APP_LINK, true, false, true)));
            idle();
            assertEquals("https://music.youtube.com/", lastLoaded());
            web.loadUrl(LOGIN);
        }
        assertTrue(LoginNavigation.override(web, request(MUSIC_APP_LINK, true, false, true)));
        idle();
        assertEquals("A loop of app links stops loading pages", LOGIN, lastLoaded());
        assertNotNull(ShadowAlertDialog.getLatestAlertDialog());
        ShadowAlertDialog.getLatestAlertDialog().dismiss();
        Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(Duration.ofMillis(LoginNavigation.OPEN_WINDOW_MS + 1));
        assertTrue(LoginNavigation.override(web, request(MUSIC_APP_LINK, true, false, true)));
        idle();
        assertEquals("https://music.youtube.com/", lastLoaded());
    }

    @Test
    public void cookiesAreNeitherReadNorChanged() {
        CookieManager cookies = CookieManager.getInstance();
        cookies.setCookie("https://accounts.google.com/", "SID=kept; Secure");
        cookies.setCookie("https://music.youtube.com/", "LOGIN_INFO=kept; Secure");
        String google = cookies.getCookie("https://accounts.google.com/");
        String music = cookies.getCookie("https://music.youtube.com/");
        signInPage();
        LoginNavigation.override(web, request(PLAY_STORE, true, true, false));
        LoginNavigation.override(web, request(MUSIC_APP_LINK, true, false, true));
        LoginNavigation.override(web, request(PLAY_STORE, false, false, false));
        idle();
        ShadowAlertDialog.getLatestAlertDialog()
                .getButton(DialogInterface.BUTTON_NEGATIVE)
                .performClick();
        idle();
        assertEquals(google, cookies.getCookie("https://accounts.google.com/"));
        assertEquals(music, cookies.getCookie("https://music.youtube.com/"));
    }

    @Test
    public void malformedLinksFailClosedWithoutCrashing() {
        signInPage();
        for (String url :
                new String[] {
                    "intent:#Intent;S.browser_fallback_url=%zz;end",
                    "intent://#Intent;end",
                    "intent:",
                    "intent://x/#Intent;B.browser_fallback_url=true;end",
                    "vnd.youtube:abc",
                    "mailto:someone@example.invalid"
                }) {
            assertTrue(url, LoginNavigation.override(web, request(url, true, false, false)));
            assertTrue(url, LoginNavigation.override(web, request(url, false, false, false)));
        }
        assertFalse(LoginNavigation.override(web, null));
        assertFalse(LoginNavigation.override(null, request(PLAY_STORE, true, true, false)));
        idle();
        assertEquals(LOGIN, lastLoaded());
        noOtherApp();
    }

    @Test
    public void googleSignInPageWorksWithoutMicroG() {
        MicroGSignIn.load(web, LOGIN);
        LoginNavigation.track(web, LOGIN);
        assertEquals(LOGIN, lastLoaded());
        assertTrue(LoginNavigation.override(web, request(PLAY_STORE, true, false, true)));
        idle();
        assertEquals(LOGIN, lastLoaded());
        noOtherApp();
    }

    @Test
    public void logLinesNameOnlyTheKindOfLink() {
        java.util.List<String> lines = new java.util.ArrayList<>();
        HostLog.observer = lines::add;
        try {
            signInPage();
            String secret =
                    "intent://music.youtube.com/?token=SECRET_TOKEN&email=listener%40example.invalid"
                        + "#Intent;scheme=https;S.browser_fallback_url="
                        + "https%3A%2F%2Fmusic.youtube.com%2F%3Fsid%3DSECRET_SID;end";
            LoginNavigation.override(web, request(secret, true, false, true));
            LoginNavigation.override(web, request(PLAY_STORE, false, false, false));
            LoginNavigation.override(web, request(PLAY_STORE, true, true, false));
            LoginNavigation.override(
                    web, request("secretscheme-SECRET_SCHEME:x", true, true, false));
            idle();
        } finally {
            HostLog.observer = null;
        }
        assertFalse(lines.isEmpty());
        for (String line : lines)
            for (String leak :
                    new String[] {
                        "SECRET", "listener", "token", "youtube.com", "play.google", "://", "?"
                    }) assertFalse(line, line.contains(leak));
    }

    @Test
    public void googleSignInPageChosenOverMicroGIsCovered() {
        Shadows.shadowOf(AccountManager.get(activity)).addAuthenticator("app.revanced");
        MicroGSignIn.load(web, LOGIN);
        LoginNavigation.track(web, LOGIN);
        AlertDialog offer = ShadowAlertDialog.getLatestAlertDialog();
        assertEquals("Use MicroG", offer.getButton(DialogInterface.BUTTON_POSITIVE).getText());
        assertNull("The offer still comes before any page", lastLoaded());
        offer.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        idle();
        assertEquals(LOGIN, lastLoaded());
        assertFalse(
                "MicroG RE's one-time https link is left to the WebView",
                LoginNavigation.override(
                        web,
                        request(
                                "https://accounts.google.com/accounts/MergeSession?uberauth=x",
                                true,
                                false,
                                true)));
        assertTrue(LoginNavigation.override(web, request(PLAY_STORE, true, true, false)));
        idle();
        assertEquals(LOGIN, lastLoaded());
        AlertDialog explained = ShadowAlertDialog.getLatestAlertDialog();
        assertNotSame(offer, explained);
        assertEquals("Start again", explained.getButton(DialogInterface.BUTTON_NEGATIVE).getText());
        noOtherApp();
    }

    @Test
    public void channelChangeStartsAgainFromYouTubeMusic() {
        web.loadUrl(MicroGSignIn.MUSIC);
        LoginNavigation.track(web, MicroGSignIn.MUSIC);
        web.loadUrl("https://music.youtube.com/channel_switcher");
        assertTrue(LoginNavigation.override(web, request(PLAY_STORE, true, true, false)));
        idle();
        ShadowAlertDialog.getLatestAlertDialog()
                .getButton(DialogInterface.BUTTON_NEGATIVE)
                .performClick();
        idle();
        assertEquals(MicroGSignIn.MUSIC, lastLoaded());
    }

    @Test
    public void startAgainIsOfferedOnlyForASignInEntry() {
        web.loadUrl("https://example.invalid/");
        LoginNavigation.track(web, "https://example.invalid/");
        assertTrue(LoginNavigation.override(web, request(PLAY_STORE, true, true, false)));
        idle();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        Button none = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
        assertTrue(none == null || none.getVisibility() != View.VISIBLE);
    }

    @Test
    public void explanationIsReadableInLightAndDark() {
        for (String theme : new String[] {"LIGHT", "DARK"}) {
            AppSettings.themeMode.setValue(theme);
            WebView view = new WebView(activity);
            activity.setContentView(view);
            view.loadUrl(LOGIN);
            LoginNavigation.track(view, LOGIN);
            assertTrue(LoginNavigation.override(view, request(PLAY_STORE, true, true, false)));
            idle();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            View root = dialog.getWindow().getDecorView();
            int surface = surface(dialog.getWindow().getDecorView().getBackground());
            for (String text :
                    new String[] {
                        "This link opens another app",
                        "Sign-in stays in BitChord, so links to other apps, such as Google"
                                + " Play, don’t open here. Carry on with this page, or start"
                                + " sign-in again.",
                        "Carry on",
                        "Start again"
                    }) {
                TextView label = text(root, text);
                assertNotNull(theme + " " + text, label);
                double a = luminance(label.getCurrentTextColor()), b = luminance(surface);
                assertTrue(
                        theme + " readable: " + text,
                        (Math.max(a, b) + .05) / (Math.min(a, b) + .05) >= 4.5);
            }
            assertEquals(theme.equals("DARK"), luminance(surface) < .2);
            dialog.dismiss();
            view.destroy();
        }
    }

    static int surface(Drawable background) {
        if (background instanceof ColorDrawable) return ((ColorDrawable) background).getColor();
        if (background instanceof GradientDrawable)
            return ((GradientDrawable) background).getColor().getDefaultColor();
        fail("Unexpected dialog surface " + background);
        return Color.TRANSPARENT;
    }

    TextView text(View view, String value) {
        if (view instanceof TextView
                && ((TextView) view).getText().toString().equalsIgnoreCase(value))
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
}

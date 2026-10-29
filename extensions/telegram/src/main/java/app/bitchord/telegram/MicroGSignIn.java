package app.bitchord.telegram;

import android.accounts.AccountManager;
import android.accounts.AuthenticatorDescription;
import android.accounts.OperationCanceledException;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import android.widget.Toast;

import java.net.URLEncoder;

/**
 * Signs in to YouTube Music with an account that's already in MicroG RE, instead of typing it into
 * Google's sign-in page.
 *
 * <p>BitChord signs in by opening Google's sign-in page in a WebView and keeping the cookies
 * music.youtube.com is given. MicroG RE can give an account's web cookies to an app it trusts: it
 * answers a {@code weblogin:} token request with a one-time link that signs that WebView in. The
 * patch tells MicroG RE that BitChord is YouTube Music, as ReVanced and Morphe do for YouTube Music
 * itself, so MicroG RE asks Google for the link as YouTube Music. The link is opened in BitChord's
 * own sign-in WebView, and from there BitChord carries on as if the page had been used: it waits
 * for YouTube Music to load and asks which channel to use.
 *
 * <p>MicroG RE shows its account chooser, and once its own consent screen. When MicroG RE isn't
 * installed, or anything fails, Google's sign-in page opens as before.
 */
public final class MicroGSignIn {
    private MicroGSignIn() {}

    /** MicroG RE's account type: its package prefix, {@code app.revanced}. */
    static final String ACCOUNT_TYPE = "app.revanced";

    static final String MUSIC = "https://music.youtube.com/";
    static final String GOOGLE_LOGIN = "https://accounts.google.com/ServiceLogin";

    /** The token that asks MicroG RE for a link that signs a browser in to YouTube Music. */
    static String tokenType() {
        try {
            return "weblogin:continue=" + URLEncoder.encode(MUSIC, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    /** Whether {@code url} is BitChord's sign-in page, rather than YouTube Music itself. */
    static boolean isSignIn(String url) {
        return url != null && url.startsWith(GOOGLE_LOGIN) && url.contains("music.youtube.com");
    }

    /** Whether a token MicroG RE returned is a link to open. */
    static boolean usable(String link) {
        return link != null && link.startsWith("https://") && !link.contains("WILL_NOT_SIGN_IN");
    }

    /** MicroG RE's package. */
    static final String PACKAGE = ACCOUNT_TYPE + ".android.gms";

    /** Replaces BitChord's sign-in WebView loading its first page. */
    public static void load(WebView view, String url) {
        Activity activity = activityOf(view.getContext());
        String skip =
                !isSignIn(url)
                        ? null // Changing channel: YouTube Music itself, nothing to say.
                        : activity == null
                                ? "the sign-in page isn't in an activity"
                                : !installed(activity) ? "MicroG RE isn't installed" : "";
        if (skip == null || !skip.isEmpty()) {
            if (skip != null) HostLog.d("MicroG sign-in not offered: " + skip);
            view.loadUrl(url);
            return;
        }
        try {
            new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Strings.t("Sign in to YouTube Music"))
                    .setMessage(
                            Strings.t(
                                    "MicroG RE is on this phone. Sign in with a Google account"
                                            + " that’s already in MicroG, or use Google’s sign-in"
                                            + " page."))
                    .setPositiveButton(
                            Strings.t("Use MicroG"), (d, w) -> viaMicroG(activity, view, url))
                    .setNegativeButton(
                            Strings.t("Google sign-in page"), (d, w) -> view.loadUrl(url))
                    .setOnCancelListener(d -> view.loadUrl(url))
                    .show();
        } catch (RuntimeException e) {
            HostLog.d("MicroG sign-in couldn't be offered: " + e);
            view.loadUrl(url);
        }
    }

    private static void viaMicroG(Activity activity, WebView view, String url) {
        AccountManager accounts = AccountManager.get(activity);
        HostLog.d("MicroG sign-in: asking MicroG RE for a YouTube Music sign-in link");
        try {
            // With no account in MicroG RE this opens its sign-in; with several, its chooser.
            accounts.getAuthTokenByFeatures(
                    ACCOUNT_TYPE,
                    tokenType(),
                    null,
                    activity,
                    null,
                    null,
                    future -> {
                        String link = null;
                        String trouble = null;
                        try {
                            Bundle result = future.getResult();
                            link = result.getString(AccountManager.KEY_AUTHTOKEN);
                            if (!usable(link)) trouble = "no link was given";
                        } catch (OperationCanceledException e) {
                            HostLog.d("MicroG sign-in cancelled");
                            view.loadUrl(url);
                            return;
                        } catch (Exception e) {
                            trouble = e.getClass().getSimpleName() + ": " + e.getMessage();
                        }
                        if (trouble != null) {
                            HostLog.d("MicroG sign-in failed: " + trouble);
                            Toast.makeText(
                                            activity,
                                            Strings.f(
                                                    "MicroG couldn’t sign in ({0}). Opening"
                                                            + " Google’s sign-in page instead.",
                                                    trouble),
                                            Toast.LENGTH_LONG)
                                    .show();
                            view.loadUrl(url);
                            return;
                        }
                        // The link works once. Don't let Android hand the same one out again.
                        accounts.invalidateAuthToken(ACCOUNT_TYPE, link);
                        HostLog.d("MicroG sign-in: opening the link MicroG RE gave");
                        view.loadUrl(link);
                    },
                    new Handler(Looper.getMainLooper()));
        } catch (RuntimeException e) {
            HostLog.d("MicroG sign-in failed: " + e);
            view.loadUrl(url);
        }
    }

    /** Whether MicroG RE and its account type are on this phone. */
    static boolean installed(Context context) {
        boolean app = false, accounts = false;
        try {
            context.getPackageManager().getPackageInfo(PACKAGE, 0);
            app = true;
        } catch (Exception ignored) {
            // Not installed, or hidden from BitChord.
        }
        try {
            for (AuthenticatorDescription type :
                    AccountManager.get(context).getAuthenticatorTypes())
                if (ACCOUNT_TYPE.equals(type.type)) accounts = true;
        } catch (RuntimeException ignored) {
            // No account service.
        }
        if (app != accounts)
            HostLog.d(
                    "MicroG sign-in: MicroG RE "
                            + (app
                                    ? "is installed but its accounts aren't visible"
                                    : "isn't visible as an app")
                            + (accounts ? ", though its account type is" : ""));
        return accounts;
    }

    private static Activity activityOf(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}

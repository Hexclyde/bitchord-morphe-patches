package app.bitchord.telegram;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;

import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Keeps BitChord's YouTube Music sign-in on the web when a page in it links to another app.
 *
 * <p>Google's and YouTube's pages can send an Android browser to an {@code intent:} link, such as
 * one for the YouTube Music app on Google Play. A browser hands those to another app. A WebView
 * can't, and replaces the sign-in page with "net::ERR_UNKNOWN_URL_SCHEME". In the sign-in WebView
 * these links are stopped instead. When an {@code intent:} link names a page on Google's or YouTube
 * Music's sign-in sites to use without the app, that page opens in the same WebView. No other app
 * is opened, and nothing else the link carries is used. Every other WebView, including the one
 * BitChord uses for PoTokens, is left alone.
 */
public final class LoginNavigation {
    private LoginNavigation() {}

    /** Leave the navigation to the WebView. */
    static final int PASS = 0;

    /** Stop the navigation and stay on the current page. */
    static final int STOP = 1;

    /** Open the web page an {@code intent:} link offers instead. */
    static final int OPEN = 2;

    /** Stop the navigation and say why. */
    static final int EXPLAIN = 3;

    /** Schemes a WebView loads itself. */
    private static final String[] WEB_SCHEMES = {
        "http", "https", "about", "data", "blob", "javascript", "file", "content"
    };

    /** Sites whose https pages a link may open in the sign-in WebView. */
    static final String[] SIGN_IN_HOSTS = {
        "accounts.google.com", "accounts.youtube.com", "music.youtube.com"
    };

    /** Pages opened from links within {@link #OPEN_WINDOW_MS} before a link counts as a loop. */
    static final int MAX_OPENS = 3;

    static final long OPEN_WINDOW_MS = 30_000;

    private static final class Session {
        String entry;
        int opens;
        long firstOpen;
        boolean explaining;

        boolean mayOpen(long now) {
            if (opens == 0 || now - firstOpen > OPEN_WINDOW_MS) {
                opens = 0;
                firstOpen = now;
            }
            return opens++ < MAX_OPENS;
        }
    }

    private static final Map<WebView, Session> SESSIONS = new WeakHashMap<>();

    /**
     * Marks {@code view} as the sign-in WebView. Called right after it's asked to load its first
     * page; {@code url} is the page sign-in starts again from.
     */
    public static void track(WebView view, String url) {
        if (view == null) return;
        synchronized (SESSIONS) {
            Session session = SESSIONS.get(view);
            if (session == null) {
                session = new Session();
                SESSIONS.put(view, session);
            }
            if (session.entry == null && signInPage(url)) session.entry = url;
        }
    }

    static boolean tracked(WebView view) {
        synchronized (SESSIONS) {
            return SESSIONS.containsKey(view);
        }
    }

    /**
     * Asked first by the sign-in WebView's client in {@code shouldOverrideUrlLoading}. True means
     * the navigation was handled here; false leaves it to {@code WebViewClient}.
     */
    public static boolean override(WebView view, WebResourceRequest request) {
        try {
            if (view == null || request == null) return false;
            Session session;
            synchronized (SESSIONS) {
                session = SESSIONS.get(view);
            }
            if (session == null) return false;
            Uri url = request.getUrl();
            int step = step(url, request.isForMainFrame());
            if (step == PASS) return false;
            String kind = kind(url);
            if (step == STOP) {
                HostLog.d("Sign-in stopped a frame from opening another app (" + kind + ")");
                return true;
            }
            String page = webPage(url);
            if (page != null && session.mayOpen(SystemClock.uptimeMillis())) {
                HostLog.d("Sign-in opened the web page an app link offered (" + kind + ")");
                view.post(() -> view.loadUrl(page));
                return true;
            }
            String current = view.getUrl();
            boolean onPage = current != null && current.startsWith("https://");
            if (!request.hasGesture() && onPage && page == null) {
                HostLog.d(
                        "Sign-in stayed on its page instead of opening another app (" + kind + ")");
                return true;
            }
            HostLog.d("Sign-in kept a link to another app from opening (" + kind + ")");
            explain(view, session);
            return true;
        } catch (RuntimeException e) {
            HostLog.d("Sign-in link check failed: " + e.getClass().getSimpleName());
            return false;
        }
    }

    /** What to do with a navigation to {@code url} in the sign-in WebView. */
    static int step(Uri url, boolean mainFrame) {
        String scheme = url == null ? null : url.getScheme();
        if (scheme == null) return PASS;
        scheme = scheme.toLowerCase(Locale.ROOT);
        for (String web : WEB_SCHEMES) if (web.equals(scheme)) return PASS;
        // A WebView can't load anything else; a frame isn't allowed to take the page with it.
        if (!mainFrame) return STOP;
        return webPage(url) != null ? OPEN : EXPLAIN;
    }

    /**
     * The sign-in page an {@code intent:} link offers in place of an app: its browser fallback, or
     * the https address the intent itself points at. Null when neither is a sign-in page.
     */
    static String webPage(Uri url) {
        if (url == null || !"intent".equalsIgnoreCase(url.getScheme())) return null;
        Intent intent;
        try {
            intent = Intent.parseUri(url.toString(), Intent.URI_INTENT_SCHEME);
        } catch (java.net.URISyntaxException | RuntimeException e) {
            return null;
        }
        String fallback = null;
        try {
            fallback = intent.getStringExtra("browser_fallback_url");
        } catch (RuntimeException ignored) {
            // A fallback of the wrong type is no fallback.
        }
        if (signInPage(fallback)) return fallback;
        Uri data = intent.getData();
        String address = data == null ? null : data.toString();
        return signInPage(address) ? address : null;
    }

    /**
     * Whether {@code url} is an https page on a sign-in site. The address must start with the exact
     * host, so a parser that reads it differently can't put it on another site.
     */
    static boolean signInPage(String url) {
        if (url == null) return false;
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c <= ' ' || c == '\\' || c >= 0x7f) return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        for (String host : SIGN_IN_HOSTS) {
            String origin = "https://" + host;
            if (!lower.startsWith(origin)) continue;
            if (lower.length() == origin.length()) return true;
            char next = lower.charAt(origin.length());
            if (next == '/' || next == '?' || next == '#') return true;
        }
        return false;
    }

    /** A label for the log: the link's kind, never the link. */
    static String kind(Uri url) {
        String scheme = url == null ? null : url.getScheme();
        if (scheme == null) return "no scheme";
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (scheme.equals("intent") || scheme.equals("market")) return scheme;
        return "app link";
    }

    /** Explains the blocked link and offers to restart sign-in. */
    private static void explain(WebView view, Session session) {
        Activity activity = activityOf(view.getContext());
        if (activity == null || activity.isFinishing() || session.explaining) return;
        try {
            Strings.init(activity);
            TelegramStyle style = new TelegramStyle(activity);
            String entry = session.entry;
            AlertDialog.Builder builder =
                    new AlertDialog.Builder(
                                    activity,
                                    style.dark
                                            ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                                            : android.R.style
                                                    .Theme_DeviceDefault_Light_Dialog_Alert)
                            .setTitle(Strings.t("This link opens another app"))
                            .setMessage(
                                    Strings.t(
                                            "Sign-in stays in BitChord, so links to other apps,"
                                                    + " such as Google Play, don’t open here."
                                                    + " Carry on with this page, or start"
                                                    + " sign-in again."))
                            .setPositiveButton(Strings.t("Carry on"), null);
            if (entry != null)
                builder.setNegativeButton(Strings.t("Start again"), (d, w) -> view.loadUrl(entry));
            AlertDialog dialog = builder.create();
            View.OnAttachStateChangeListener gone =
                    new View.OnAttachStateChangeListener() {
                        @Override
                        public void onViewAttachedToWindow(View v) {}

                        @Override
                        public void onViewDetachedFromWindow(View v) {
                            dialog.dismiss();
                        }
                    };
            dialog.setOnDismissListener(
                    d -> {
                        session.explaining = false;
                        view.removeOnAttachStateChangeListener(gone);
                    });
            view.addOnAttachStateChangeListener(gone);
            session.explaining = true;
            dialog.show();
            style.tuneDialog(dialog);
        } catch (RuntimeException e) {
            session.explaining = false;
            HostLog.d(
                    "Sign-in couldn't explain a link to another app: "
                            + e.getClass().getSimpleName());
        }
    }

    private static Activity activityOf(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}

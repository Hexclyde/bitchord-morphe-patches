package app.bitchord.telegram;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.webkit.*;
import android.widget.*;

import org.json.JSONArray;

import java.util.concurrent.*;

/** The provider's real page owns verification; the app never solves or skips its challenge. */
public final class FlacVerificationActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private WebView web;
    private TextView status, proceed;
    private boolean busy;
    private volatile boolean closed;

    @Override
    public void onCreate(Bundle state) {
        setTheme(
                TelegramStyle.isDark(this)
                        ? android.R.style.Theme_Material_NoActionBar
                        : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);
        if (!SoundExtras.flacAddonApplied()) {
            finish();
            return;
        }
        Strings.refresh(this);
        CapturePolicy.apply(this);
        TelegramStyle style = new TelegramStyle(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(style.background);
        root.setPadding(style.dp(16), style.dp(8), style.dp(16), style.dp(8));
        if (android.os.Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener(
                (v, insets) -> {
                    v.setPadding(
                            style.dp(16) + insets.getSystemWindowInsetLeft(),
                            style.dp(8) + insets.getSystemWindowInsetTop(),
                            style.dp(16) + insets.getSystemWindowInsetRight(),
                            style.dp(8) + insets.getSystemWindowInsetBottom());
                    return insets;
                });
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(
                style.backButton(this::finish),
                new LinearLayout.LayoutParams(style.dp(48), style.dp(48)));
        heading.addView(
                style.text(
                        Strings.t("Provider verification"), 21, style.foreground, style.semibold),
                new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(heading);
        status =
                style.text(
                        Strings.t(
                                "Use the provider's download action to complete its browser"
                                    + " verification, then choose Continue. BitChord will download"
                                    + " only the file and quality you select in Explorer."),
                        14,
                        style.secondary,
                        style.regular);
        status.setPadding(0, style.dp(8), 0, style.dp(12));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(status);
        try {
            web = new WebView(this);
            web.getSettings().setJavaScriptEnabled(true);
            web.getSettings().setDomStorageEnabled(true);
            web.getSettings().setAllowFileAccess(false);
            web.getSettings().setAllowContentAccess(false);
            web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            web.setWebViewClient(
                    new WebViewClient() {
                        @Override
                        public boolean shouldOverrideUrlLoading(
                                WebView view, WebResourceRequest request) {
                            return request.isForMainFrame() && !official(request.getUrl());
                        }
                    });
            web.setDownloadListener(
                    (url, agent, disposition, type, size) ->
                            status.setText(
                                    Strings.t(
                                            "Complete the provider verification, then choose"
                                                    + " Continue to return to BitChord.")));
            root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
            web.loadUrl(FlacAddon.BACKEND + "/");
        } catch (RuntimeException unavailable) {
            status.setText(
                    Strings.t(
                            "Android WebView is unavailable. Enable a system WebView provider and"
                                    + " reopen verification."));
        }
        proceed = style.button(Strings.t("Continue"), true, this::complete);
        proceed.setTag("flac-verification-continue");
        proceed.setMinHeight(style.dp(48));
        root.addView(proceed, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
    }

    static boolean official(Uri uri) {
        return uri != null
                && "https".equals(uri.getScheme())
                && "flacdownloader.com".equals(uri.getHost())
                && uri.getUserInfo() == null
                && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    private void complete() {
        if (busy || web == null || !official(Uri.parse(web.getUrl() == null ? "" : web.getUrl())))
            return;
        busy = true;
        proceed.setEnabled(false);
        // Read only this provider's session capability, after the explicit Continue action.
        web.evaluateJavascript(
                "(function(){try{return localStorage.getItem('fd_sp')||'';}catch(e){return"
                        + " '';}})()",
                value -> {
                    String session;
                    try {
                        session = new JSONArray("[" + value + "]").optString(0, "");
                    } catch (Exception invalid) {
                        session = "";
                    }
                    if (session.isEmpty()) {
                        busy = false;
                        proceed.setEnabled(true);
                        status.setText(
                                Strings.t(
                                        "Complete verification on the provider page before"
                                                + " continuing."));
                        return;
                    }
                    final String pass = session;
                    worker.execute(
                            () -> {
                                try {
                                    FlacAddon.acceptSession(pass);
                                    runOnUiThread(
                                            () -> {
                                                if (!closed) {
                                                    setResult(RESULT_OK);
                                                    finish();
                                                }
                                            });
                                } catch (Exception unavailable) {
                                    runOnUiThread(
                                            () -> {
                                                if (closed) return;
                                                busy = false;
                                                proceed.setEnabled(true);
                                                status.setText(
                                                        Strings.t(
                                                                "Provider verification could not be"
                                                                    + " confirmed. Complete it on"
                                                                    + " the page and try Continue"
                                                                    + " again."));
                                            });
                                }
                            });
                });
    }

    @Override
    protected void onDestroy() {
        closed = true;
        worker.shutdownNow();
        if (web != null) {
            web.stopLoading();
            web.destroy();
        }
        super.onDestroy();
    }
}

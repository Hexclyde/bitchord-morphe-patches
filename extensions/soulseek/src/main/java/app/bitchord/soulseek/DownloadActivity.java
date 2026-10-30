package app.bitchord.soulseek;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import app.bitchord.telegram.PatchStrings;

/** Shared native shell for download pages; no dependency on the optional Telegram patch. */
class DownloadActivity extends Activity {
    protected DownloadStyle downloadStyle;
    private String patchLanguage;

    @Override
    public void onCreate(Bundle state) {
        boolean dark = DownloadStyle.isDark(this);
        int theme =
                getResources()
                        .getIdentifier(
                                dark ? "PatchNativeTheme" : "PatchNativeThemeLight",
                                "style",
                                getPackageName());
        setTheme(
                theme != 0
                        ? theme
                        : dark
                                ? android.R.style.Theme_Material_NoActionBar
                                : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);
        PatchStrings.refresh(this);
        patchLanguage = PatchStrings.locale().toLanguageTag();
        getWindow()
                .getDecorView()
                .setLayoutDirection(
                        PatchStrings.rtl() ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        downloadStyle = new DownloadStyle(this);
        getWindow().setStatusBarColor(downloadStyle.background);
        getWindow().setNavigationBarColor(downloadStyle.background);
        getWindow()
                .getDecorView()
                .setSystemUiVisibility(
                        downloadStyle.dark
                                ? 0
                                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    @Override
    protected void onResume() {
        super.onResume();
        PatchStrings.refresh(this);
        if (patchLanguage != null && !patchLanguage.equals(PatchStrings.locale().toLanguageTag()))
            recreate();
    }

    @Override
    public void setContentView(View content) {
        if (downloadStyle == null) {
            super.setContentView(content);
            return;
        }
        ScrollView scroll;
        LinearLayout body;
        if (content instanceof ScrollView
                && ((ScrollView) content).getChildCount() == 1
                && ((ScrollView) content).getChildAt(0) instanceof LinearLayout) {
            scroll = (ScrollView) content;
            body = (LinearLayout) scroll.getChildAt(0);
        } else {
            scroll = new ScrollView(this);
            body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(body);
            body.addView(content);
        }
        scroll.setBackgroundColor(downloadStyle.background);
        scroll.setFillViewport(true);
        LinearLayout.LayoutParams back =
                new LinearLayout.LayoutParams(downloadStyle.dp(48), downloadStyle.dp(48));
        back.bottomMargin = downloadStyle.dp(4);
        body.addView(downloadStyle.backButton(this::finish), 0, back);
        body.setPadding(
                downloadStyle.dp(20),
                downloadStyle.dp(16),
                downloadStyle.dp(20),
                downloadStyle.dp(28));
        final LinearLayout page = body;
        scroll.setOnApplyWindowInsetsListener(
                (view, insets) -> {
                    page.setPadding(
                            downloadStyle.dp(20),
                            downloadStyle.dp(16) + insets.getSystemWindowInsetTop(),
                            downloadStyle.dp(20),
                            downloadStyle.dp(28) + insets.getSystemWindowInsetBottom());
                    return insets;
                });
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(() -> downloadStyle.applyTree(page));
        super.setContentView(scroll);
        downloadStyle.applyTree(page);
        scroll.requestApplyInsets();
    }
}

package app.bitchord.soulseek;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import app.bitchord.telegram.PatchStrings;

/** A real View controller, owned either by AndroidView in the player or a fallback Activity. */
abstract class DownloadPage extends ContextThemeWrapper {
    final Activity activity;
    final Intent intent;
    final DownloadStyle downloadStyle;
    View view;
    private boolean destroyed;

    DownloadPage(Activity activity, Intent intent) {
        super(activity, theme(activity));
        this.activity = activity;
        this.intent = intent;
        PatchStrings.refresh(activity);
        downloadStyle = new DownloadStyle(activity);
    }

    private static int theme(Activity activity) {
        int id =
                activity.getResources()
                        .getIdentifier(
                                DownloadStyle.isDark(activity)
                                        ? "PatchNativeTheme"
                                        : "PatchNativeThemeLight",
                                "style",
                                activity.getPackageName());
        return id != 0 ? id : android.R.style.Theme_Material_NoActionBar;
    }

    void onCreate(Bundle state) {}

    protected void onStart() {}

    protected void onStop() {}

    protected void onDestroy() {
        destroyed = true;
    }

    final Intent getIntent() {
        return intent;
    }

    final boolean isFinishing() {
        return destroyed || activity.isFinishing();
    }

    final void finish() {
        if (DownloadsShell.owns(activity)) DownloadsShell.back();
        else activity.finish();
    }

    @Override
    public void startActivity(Intent target) {
        if (!DownloadsShell.navigate(activity, target)) activity.startActivity(target);
    }

    final void setContentView(View content) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(
                downloadStyle.dp(20),
                downloadStyle.dp(16),
                downloadStyle.dp(20),
                downloadStyle.dp(28));
        if (!(this instanceof DownloadsHubPage))
            body.addView(downloadStyle.backButton(this::finish));
        body.addView(content);
        scroll.addView(body);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(downloadStyle.background);
        scroll.setLayoutDirection(
                PatchStrings.rtl() ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(() -> downloadStyle.applyTree(body));
        downloadStyle.applyTree(body);
        view = scroll;
    }
}

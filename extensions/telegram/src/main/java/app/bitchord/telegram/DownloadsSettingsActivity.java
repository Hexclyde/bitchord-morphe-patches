package app.bitchord.telegram;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** The patch's page behind the row added to BitChord's Downloads settings. */
public final class DownloadsSettingsActivity extends Activity {
    private TelegramStyle style;

    @Override
    public void onCreate(Bundle saved) {
        boolean dark = TelegramStyle.isDark(this);
        setTheme(
                dark
                        ? android.R.style.Theme_Material_NoActionBar
                        : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(saved);
        Strings.refresh(this);
        style = new TelegramStyle(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Rebuilt so BitChord's own values are current after a trip back to its settings.
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(style.background);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(style.dp(20), style.dp(8), style.dp(20), style.dp(32));
        scroll.addView(body);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(
                style.backButton(this::finish),
                new LinearLayout.LayoutParams(style.dp(48), style.dp(48)));
        TextView title =
                style.text(Strings.t("Patch downloads"), 28, style.foreground, style.heavy);
        LinearLayout.LayoutParams titleSpace = new LinearLayout.LayoutParams(0, -2, 1);
        titleSpace.leftMargin = style.dp(12);
        header.addView(title, titleSpace);
        body.addView(header);
        DownloadsSettings.addSection(this, body, style);
        setContentView(scroll);
    }
}

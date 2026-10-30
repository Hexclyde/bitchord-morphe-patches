package app.bitchord.soulseek;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** First-run format selection and sequence, also available from download settings. */
public final class AudioFormatSetupActivity extends Activity {
    private AudioFormatPreferences.Settings settings;
    private LinearLayout rows;

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        settings = AudioFormatPreferences.snapshot(this);
        if (saved != null && saved.containsKey("draft")) {
            try {
                settings = AudioFormatPreferences.decode(saved.getString("draft"));
            } catch (Exception invalidDraft) {
                /* Use saved preferences. */
            }
        }
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        body.setPadding(padding, padding, padding, padding);
        scroll.addView(body);
        setContentView(scroll);
        TextView title = new TextView(this);
        title.setText("Choose download formats");
        title.setTextSize(24);
        body.addView(title);
        TextView explanation = new TextView(this);
        explanation.setText(
                "Enable the formats you accept, then use Up and Down to set their preference order."
                        + " At least one format must stay enabled.\n\n"
                        + "Order applies among matching artist, title and duration candidates. A"
                        + " filename is unverified; the downloaded content must match an enabled"
                        + " format. Encoding checks cannot prove that a lossless file was never"
                        + " converted from a lossy source.");
        body.addView(explanation);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        body.addView(rows);
        render();
        Button save = new Button(this);
        save.setText("Save format choices");
        body.addView(save);
        save.setOnClickListener(
                v -> {
                    try {
                        AudioFormatPreferences.save(this, settings);
                        finish();
                    } catch (Exception error) {
                        Toast.makeText(
                                        this,
                                        "Could not save audio format choices. Try again.",
                                        Toast.LENGTH_LONG)
                                .show();
                    }
                });
    }

    @Override
    public void onSaveInstanceState(Bundle out) {
        out.putString("draft", AudioFormatPreferences.encode(settings));
        super.onSaveInstanceState(out);
    }

    private void render() {
        rows.removeAllViews();
        for (int index = 0; index < settings.order.size(); index++) {
            final int position = index;
            AudioFormatPreferences.Format format = settings.order.get(index);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            rows.addView(row);
            CheckBox enabled = new CheckBox(this);
            enabled.setText((index + 1) + ". " + format.label);
            enabled.setChecked(settings.enabled.contains(format));
            row.addView(enabled, new LinearLayout.LayoutParams(0, -2, 1));
            enabled.setOnCheckedChangeListener(
                    (button, selected) -> {
                        try {
                            settings = settings.withEnabled(format, selected);
                        } catch (IllegalArgumentException last) {
                            button.setChecked(true);
                            Toast.makeText(
                                            this,
                                            "Keep at least one audio format enabled.",
                                            Toast.LENGTH_SHORT)
                                    .show();
                        }
                    });
            Button up = new Button(this);
            up.setText("↑");
            up.setContentDescription("Move " + format.label + " up");
            up.setEnabled(index > 0);
            row.addView(up);
            up.setOnClickListener(
                    v -> {
                        settings = settings.move(position, -1);
                        render();
                    });
            Button down = new Button(this);
            down.setText("↓");
            down.setContentDescription("Move " + format.label + " down");
            down.setEnabled(index < settings.order.size() - 1);
            row.addView(down);
            down.setOnClickListener(
                    v -> {
                        settings = settings.move(position, 1);
                        render();
                    });
        }
    }
}

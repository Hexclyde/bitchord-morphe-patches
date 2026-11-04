package app.bitchord.telegram;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local support recording with an explicit document export. */
public final class DiagnosticsActivity extends Activity {
    private static final int SAVE = 2301;
    private TelegramStyle style;
    private TextView state, start, stop, save, share, delete;
    private boolean sharing;
    private long exportRevision;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "support-export");
                        t.setDaemon(true);
                        return t;
                    });
    private final Runnable refresh =
            new Runnable() {
                public void run() {
                    status();
                    ui.postDelayed(this, 1000);
                }
            };

    @Override
    public void onCreate(Bundle saved) {
        boolean dark = TelegramStyle.isDark(this);
        setTheme(
                dark
                        ? android.R.style.Theme_Material_NoActionBar
                        : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(saved);
        Strings.init(this);
        Diagnostics.init(this);
        style = new TelegramStyle(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(style.background);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setLayoutDirection(
                Strings.rightToLeft() ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        body.setPadding(style.dp(20), style.dp(8), style.dp(20), style.dp(32));
        scroll.addView(body);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(
                style.backButton(this::finish),
                new LinearLayout.LayoutParams(style.dp(48), style.dp(48)));
        TextView title = style.text(Strings.t("Diagnostics"), 26, style.foreground, style.heavy);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(header);
        TextView explanation =
                style.text(
                        Strings.t(
                                "Start a 15-minute recording, reproduce the problem, then return"
                                    + " here to save the report. Recording survives app restarts."
                                    + " Nothing is uploaded."),
                        15,
                        style.secondary,
                        style.regular);
        explanation.setPadding(0, style.dp(16), 0, style.dp(20));
        body.addView(explanation);
        state = style.text("", 18, style.foreground, style.semibold);
        state.setTag("diagnostics-state");
        state.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(state);
        start =
                action(
                        body,
                        Strings.t("Start"),
                        "diagnostics-start",
                        () -> {
                            Diagnostics.start(this);
                            status();
                        });
        stop =
                action(
                        body,
                        Strings.t("Stop"),
                        "diagnostics-stop",
                        () -> {
                            Diagnostics.stop();
                            status();
                        });
        share = action(body, Strings.t("Share"), "diagnostics-share", this::share);
        save = action(body, Strings.t("Save"), "diagnostics-save", this::save);
        delete =
                action(
                        body,
                        Strings.t("Delete"),
                        "diagnostics-delete",
                        () -> {
                            AlertDialog dialog =
                                    new AlertDialog.Builder(this)
                                            .setTitle(Strings.t("Delete report?"))
                                            .setPositiveButton(
                                                    Strings.t("Delete"),
                                                    (d, w) -> {
                                                        exportRevision++;
                                                        Diagnostics.clear();
                                                        status();
                                                    })
                                            .setNegativeButton(Strings.t("Cancel"), null)
                                            .create();
                            dialog.show();
                            style.tuneDialog(dialog);
                        });
        TextView scope =
                style.text(
                        Strings.t(
                                "Google and MicroG service logs require computer-assisted"
                                        + " capture."),
                        14,
                        style.secondary,
                        style.regular);
        scope.setPadding(0, style.dp(20), 0, 0);
        body.addView(scope);
        setContentView(scroll);
        status();
    }

    private TextView action(LinearLayout parent, String title, String tag, Runnable run) {
        TextView button = style.button(title, false, run);
        button.setTag(tag);
        button.setMinimumHeight(style.dp(48));
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2);
        space.topMargin = style.dp(12);
        parent.addView(button, space);
        return button;
    }

    private void status() {
        if (state == null) return;
        boolean active = Diagnostics.active();
        state.setText(active ? Strings.t("Recording") : Strings.t("Off"));
        start.setEnabled(!active);
        stop.setEnabled(active);
    }

    private void share() {
        if (sharing) return;
        sharing = true;
        final long revision = exportRevision;
        share.setEnabled(false);
        worker.execute(
                () -> {
                    try {
                        java.io.File archive =
                                DiagnosticArchive.create(
                                        getApplicationContext(), Diagnostics.report());
                        Uri uri = DiagnosticFiles.uri(getApplicationContext(), archive);
                        Intent send =
                                new Intent(Intent.ACTION_SEND)
                                        .setType("application/zip")
                                        .putExtra(Intent.EXTRA_STREAM, uri)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        send.setClipData(
                                android.content.ClipData.newRawUri("BitChord diagnostics", uri));
                        ui.post(
                                () -> {
                                    sharing = false;
                                    if (isFinishing()
                                            || isDestroyed()
                                            || revision != exportRevision) {
                                        archive.delete();
                                        if (share != null) share.setEnabled(true);
                                        return;
                                    }
                                    share.setEnabled(true);
                                    try {
                                        startActivity(
                                                Intent.createChooser(send, Strings.t("Share")));
                                    } catch (RuntimeException unavailable) {
                                        message(Strings.t("Failed"));
                                    }
                                });
                    } catch (Exception failed) {
                        ui.post(
                                () -> {
                                    sharing = false;
                                    if (!isFinishing() && !isDestroyed()) {
                                        share.setEnabled(true);
                                        message(Strings.t("Failed"));
                                    }
                                });
                    }
                });
    }

    private void save() {
        Intent intent =
                new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TITLE, "bitchord-diagnostics.txt");
        try {
            startActivityForResult(intent, SAVE);
        } catch (RuntimeException unavailable) {
            message(Strings.t("Failed"));
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != SAVE || result != RESULT_OK || data == null || data.getData() == null)
            return;
        Uri destination = data.getData();
        worker.execute(
                () -> {
                    boolean success = false;
                    try (OutputStream stream =
                            getContentResolver().openOutputStream(destination, "wt")) {
                        if (stream != null) {
                            stream.write(Diagnostics.report().getBytes(StandardCharsets.UTF_8));
                            success = true;
                        }
                    } catch (Exception ignored) {
                    }
                    boolean saved = success;
                    ui.post(
                            () -> {
                                if (!isFinishing() && !isDestroyed())
                                    message(saved ? Strings.t("Saved") : Strings.t("Failed"));
                            });
                });
    }

    private void message(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refresh);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(refresh);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        worker.shutdown();
        super.onDestroy();
    }
}

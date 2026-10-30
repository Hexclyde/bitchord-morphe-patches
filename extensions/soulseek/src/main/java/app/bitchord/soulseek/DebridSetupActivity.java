package app.bitchord.soulseek;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Optional provider switches and protected API-key entry. */
public final class DebridSetupActivity extends DownloadActivity {
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<Runnable>(8),
                    r -> new Thread(r, "debrid-settings"));
    private final List<Row> rows = new ArrayList<>();
    private TextView notice;
    private DebridCredentials credentials;
    private boolean destroyed;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false);
        if (Build.VERSION.SDK_INT >= 31) getWindow().setHideOverlayWindows(true);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (20 * getResources().getDisplayMetrics().density);
        body.setPadding(p, p, p, p);
        scroll.addView(body);
        setContentView(scroll);
        button(body, "Back").setOnClickListener(v -> finish());
        text(body, "Debrid download services", 24);
        text(
                body,
                "Each service is optional and starts disabled. Use your own account API key. Keys"
                        + " are encrypted on this phone and are not included in backups or peer"
                        + " messages.",
                16);
        text(
                body,
                "These services resolve links; they do not search a music catalogue. Files use your"
                        + " saved Phone, Telegram main channel or Both destination. Service limits,"
                        + " supported hosts and account requirements still apply.",
                14);
        button(body, "Open debrid downloads")
                .setOnClickListener(
                        v -> startActivity(new Intent(this, DebridDownloadsActivity.class)));
        notice = text(body, "Loading protected settings…", 14);
        for (DebridProvider.Kind kind : DebridProvider.Kind.values()) {
            Row row = new Row(kind, body);
            rows.add(row);
            row.busy(true);
        }
        button(body, "Remove all saved debrid keys")
                .setOnClickListener(
                        v ->
                                new AlertDialog.Builder(this)
                                        .setTitle("Remove saved API keys?")
                                        .setMessage(
                                                "All debrid services will be disabled. Active"
                                                    + " debrid requests will stop; files already"
                                                    + " copied remain saved.")
                                        .setNegativeButton("Keep", null)
                                        .setPositiveButton(
                                                "Remove keys",
                                                (dialog, which) ->
                                                        task(
                                                                null,
                                                                () -> {
                                                                    DebridCredentials.clearSaved(
                                                                            getApplicationContext());
                                                                    credentials =
                                                                            DebridCredentials.get(
                                                                                    getApplicationContext());
                                                                    return "Saved debrid keys"
                                                                            + " removed.";
                                                                }))
                                        .show());
        task(
                null,
                () -> {
                    credentials = DebridCredentials.get(getApplicationContext());
                    return "Choose the services you want to use.";
                });
    }

    private final class Row {
        final DebridProvider.Kind kind;
        final CheckBox enabled;
        final EditText key;
        final TextView summary;
        final List<Button> actions = new ArrayList<>();
        boolean rendering;

        Row(DebridProvider.Kind kind, LinearLayout parent) {
            this.kind = kind;
            LinearLayout body = new LinearLayout(DebridSetupActivity.this);
            body.setOrientation(LinearLayout.VERTICAL);
            if (downloadStyle != null) downloadStyle.card(body);
            parent.addView(body);
            text(body, kind.label, 20);
            enabled = new CheckBox(DebridSetupActivity.this);
            enabled.setText("Enable " + kind.label);
            body.addView(enabled);
            summary = text(body, "No API key saved", 14);
            key = new EditText(DebridSetupActivity.this);
            key.setHint("API key (leave blank to keep a saved key)");
            key.setSingleLine(true);
            key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            key.setSaveEnabled(false);
            key.setFilters(
                    new android.text.InputFilter[] {
                        new android.text.InputFilter.LengthFilter(4096)
                    });
            if (Build.VERSION.SDK_INT >= 26)
                key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            body.addView(key);
            Button save = action(body, "Save API key");
            save.setOnClickListener(
                    v -> {
                        String entered = takeKey();
                        boolean on = enabled.isChecked();
                        task(
                                this,
                                () -> {
                                    requireCredentials().save(kind, entered, on);
                                    return kind.label + " settings saved.";
                                });
                    });
            Button test = action(body, "Test API key");
            test.setOnClickListener(
                    v -> {
                        String entered = key.getText().toString().trim();
                        task(
                                this,
                                () -> {
                                    String candidate =
                                            entered.isEmpty()
                                                    ? requireCredentials().keyForValidation(kind)
                                                    : DebridCredentials.validateKey(entered);
                                    DebridDownloads.get(getApplicationContext())
                                            .validate(kind, candidate);
                                    return kind.label
                                            + " accepted this API key. Save any new key before"
                                            + " downloading.";
                                });
                    });
            Button remove = action(body, "Remove API key");
            remove.setOnClickListener(
                    v -> {
                        key.setText("");
                        task(
                                this,
                                () -> {
                                    requireCredentials().remove(kind);
                                    return kind.label + " disabled and saved key removed.";
                                });
                    });
            Button account = action(body, "Open " + kind.label + " account");
            account.setOnClickListener(
                    v -> {
                        try {
                            startActivity(
                                    new Intent(Intent.ACTION_VIEW, Uri.parse(accountUrl(kind))));
                        } catch (RuntimeException unavailable) {
                            notice.setText(
                                    "A browser is required to open the service account page.");
                        }
                    });
            enabled.setOnCheckedChangeListener(
                    (view, on) -> {
                        if (rendering) return;
                        String entered = takeKey();
                        if (on
                                && entered.isEmpty()
                                && (credentials == null
                                        || !credentials.snapshot(kind).configured)) {
                            render();
                            notice.setText("Enter this service's API key before enabling it.");
                            return;
                        }
                        task(
                                this,
                                () -> {
                                    requireCredentials().save(kind, entered, on);
                                    return kind.label
                                            + (on
                                                    ? " enabled."
                                                    : " disabled. Active requests for this service"
                                                            + " are paused.");
                                });
                    });
        }

        String takeKey() {
            String value = key.getText().toString().trim();
            key.setText("");
            return value;
        }

        Button action(LinearLayout body, String label) {
            Button result = button(body, label);
            actions.add(result);
            return result;
        }

        void render() {
            rendering = true;
            if (credentials != null) {
                DebridCredentials.Snapshot saved = credentials.snapshot(kind);
                enabled.setChecked(saved.enabled);
                summary.setText(saved.configured ? "API key saved securely" : "No API key saved");
            } else {
                enabled.setChecked(false);
                summary.setText("Protected settings unavailable");
            }
            rendering = false;
        }

        void busy(boolean busy) {
            enabled.setEnabled(!busy);
            key.setEnabled(!busy);
            for (Button b : actions) b.setEnabled(!busy);
        }
    }

    interface Action {
        String run() throws Exception;
    }

    private DebridCredentials requireCredentials() throws java.io.IOException {
        if (credentials == null)
            throw new java.io.IOException("Protected settings are unavailable.");
        return credentials;
    }

    private void task(Row row, Action action) {
        if (row != null) row.busy(true);
        else for (Row item : rows) item.busy(true);
        notice.setText("Working…");
        try {
            worker.execute(
                    () -> {
                        String message;
                        try {
                            message = action.run();
                        } catch (Exception | LinkageError failure) {
                            message =
                                    "Could not complete this action. Check the API key, account"
                                        + " access and connection, then retry. Saved keys are not"
                                        + " shown.";
                        }
                        final String result = message;
                        runOnUiThread(
                                () -> {
                                    if (destroyed || isFinishing()) return;
                                    notice.setText(result);
                                    for (Row item : rows) {
                                        item.render();
                                        item.busy(credentials == null);
                                    }
                                });
                    });
        } catch (java.util.concurrent.RejectedExecutionException full) {
            notice.setText("Wait for the current action to finish.");
            for (Row item : rows) item.busy(credentials == null);
        }
    }

    private static String accountUrl(DebridProvider.Kind kind) {
        switch (kind) {
            case REAL_DEBRID:
                return "https://real-debrid.com/apitoken";
            case ALLDEBRID:
                return "https://alldebrid.com/apikeys/";
            case PREMIUMIZE:
                return "https://www.premiumize.me/account";
            default:
                return "https://torbox.app/settings";
        }
    }

    private TextView text(LinearLayout parent, String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setPadding(0, 10, 0, 10);
        parent.addView(view);
        return view;
    }

    private Button button(LinearLayout parent, String title) {
        Button view = new Button(this);
        view.setText(title);
        parent.addView(view);
        return view;
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        for (Row row : rows) row.key.setText("");
        worker.shutdownNow();
        super.onDestroy();
    }
}

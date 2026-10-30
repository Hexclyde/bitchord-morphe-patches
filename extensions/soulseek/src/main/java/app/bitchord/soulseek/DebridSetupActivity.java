package app.bitchord.soulseek;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.bitchord.telegram.CapturePolicy;
import app.bitchord.telegram.PatchStrings;

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
    private Row expanded;
    private AlertDialog keyDialog;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        CapturePolicy.apply(this);
        if (Build.VERSION.SDK_INT >= 31) getWindow().setHideOverlayWindows(true);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (20 * getResources().getDisplayMetrics().density);
        body.setPadding(p, p, p, p);
        scroll.addView(body);
        setContentView(scroll);
        button(body, PatchStrings.t("Back")).setOnClickListener(v -> finish());
        text(body, PatchStrings.t("Debrid download services"), 24);
        text(
                body,
                PatchStrings.t(
                        "Each service is optional and starts disabled. Use your own account API"
                                + " key. Keys are encrypted on this phone and are not included in"
                                + " backups or peer messages."),
                16);
        text(
                body,
                PatchStrings.t(
                        "These services resolve links; they do not search a music catalogue. Files"
                            + " use your saved Phone, Telegram main channel or Both destination."
                            + " Service limits, supported hosts and account requirements still"
                            + " apply."),
                14);
        button(body, PatchStrings.t("Open debrid downloads"))
                .setOnClickListener(
                        v -> startActivity(new Intent(this, DebridDownloadsActivity.class)));
        notice = text(body, PatchStrings.t("Loading protected settings…"), 14);
        for (DebridProvider.Kind kind : DebridProvider.Kind.values()) {
            Row row = new Row(kind, body);
            rows.add(row);
            row.busy(true);
        }
        scroll.getViewTreeObserver()
                .addOnGlobalFocusChangeListener(
                        (oldFocus, newFocus) -> {
                            for (Row row : rows)
                                if (row.contains(newFocus) && row != expanded) {
                                    expand(null);
                                    break;
                                }
                        });
        button(body, PatchStrings.t("Remove all saved debrid keys"))
                .setOnClickListener(
                        v ->
                                new AlertDialog.Builder(this)
                                        .setTitle(PatchStrings.t("Remove saved API keys?"))
                                        .setMessage(
                                                PatchStrings.t(
                                                        "All debrid services will be disabled."
                                                            + " Active debrid requests will stop;"
                                                            + " files already copied remain"
                                                            + " saved."))
                                        .setNegativeButton(PatchStrings.t("Keep"), null)
                                        .setPositiveButton(
                                                PatchStrings.t("Remove keys"),
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

    private void expand(Row row) {
        expanded = row;
        for (Row item : rows) item.updateExpansion();
    }

    private EditText keyInput() {
        EditText input = new EditText(this);
        input.setHint(PatchStrings.t("API key"));
        input.setTextDirection(View.TEXT_DIRECTION_LTR);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setSaveEnabled(false);
        input.setSaveFromParentEnabled(false);
        input.setFilters(
                new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(4096)});
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        downloadStyle.field(input);
        return input;
    }

    private void promptForKey(Row row) {
        if (keyDialog != null) keyDialog.dismiss();
        EditText input = keyInput();
        input.setContentDescription(PatchStrings.f("{0} API key to enable", row.kind.label));
        LinearLayout content = new LinearLayout(this);
        content.setPadding(downloadStyle.dp(20), downloadStyle.dp(4), downloadStyle.dp(20), 0);
        content.addView(input, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setTitle(PatchStrings.f("Enable {0}", row.kind.label))
                        .setMessage(
                                PatchStrings.t(
                                        "Enter your API key to enable this service on this phone."))
                        .setView(content)
                        .setNegativeButton(PatchStrings.t("Cancel"), null)
                        .setPositiveButton(PatchStrings.t("Save and enable"), null)
                        .create();
        dialog.setOnDismissListener(
                ignored -> {
                    input.setText("");
                    if (keyDialog == dialog) keyDialog = null;
                });
        keyDialog = dialog;
        if (dialog.getWindow() != null) {
            CapturePolicy.apply(dialog.getWindow());
            if (Build.VERSION.SDK_INT >= 31) dialog.getWindow().setHideOverlayWindows(true);
        }
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                        view -> {
                            String entered = input.getText().toString().trim();
                            try {
                                DebridCredentials.validateKey(entered);
                            } catch (java.io.IOException invalid) {
                                input.setError(PatchStrings.t("Enter a valid API key."));
                                return;
                            }
                            input.setText("");
                            dialog.dismiss();
                            task(
                                    row,
                                    () -> {
                                        requireCredentials().save(row.kind, entered, true);
                                        return PatchStrings.f("{0} enabled.", row.kind.label);
                                    },
                                    true);
                        });
    }

    private final class Row {
        final DebridProvider.Kind kind;
        final CompoundButton enabled;
        final EditText key;
        final TextView summary;
        final ImageButton gear;
        final LinearLayout card, details;
        final List<Button> actions = new ArrayList<>();
        boolean rendering, working;

        Row(DebridProvider.Kind kind, LinearLayout parent) {
            this.kind = kind;
            card = new LinearLayout(DebridSetupActivity.this);
            downloadStyle.card(card);
            parent.addView(card);
            LinearLayout header = new LinearLayout(DebridSetupActivity.this);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setMinimumHeight(downloadStyle.dp(64));
            card.addView(header, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout labels = new LinearLayout(DebridSetupActivity.this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(0, 0, downloadStyle.dp(8), 0);
            header.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            labels.addView(
                    downloadStyle.text(
                            kind.label, 17, downloadStyle.foreground, downloadStyle.medium));
            summary =
                    downloadStyle.text(
                            PatchStrings.t("No API key saved"),
                            13,
                            downloadStyle.secondary,
                            downloadStyle.regular);
            labels.addView(summary);
            enabled = downloadStyle.switchButton();
            enabled.setContentDescription(PatchStrings.f("Enable {0}", kind.label));
            header.addView(
                    enabled,
                    new LinearLayout.LayoutParams(downloadStyle.dp(52), downloadStyle.dp(48)));
            gear = downloadStyle.settingsButton();
            gear.setContentDescription(PatchStrings.f("{0} settings", kind.label));
            header.addView(
                    gear,
                    new LinearLayout.LayoutParams(downloadStyle.dp(48), downloadStyle.dp(48)));
            gear.setOnClickListener(view -> expand(expanded == this ? null : this));
            details = new LinearLayout(DebridSetupActivity.this);
            details.setOrientation(LinearLayout.VERTICAL);
            details.setContentDescription(PatchStrings.f("{0} controls", kind.label));
            details.setVisibility(View.GONE);
            card.addView(details, new LinearLayout.LayoutParams(-1, -2));
            text(
                    details,
                    PatchStrings.t(
                            "API keys stay encrypted on this phone. Leave blank to keep your saved"
                                    + " key."),
                    14);
            key = keyInput();
            key.setContentDescription(PatchStrings.f("{0} replacement API key", kind.label));
            details.addView(key);
            Button save = action(details, PatchStrings.t("Save API key"));
            save.setOnClickListener(
                    v -> {
                        String entered = takeKey();
                        boolean on = enabled.isChecked();
                        task(
                                this,
                                () -> {
                                    requireCredentials().save(kind, entered, on);
                                    return PatchStrings.f("{0} settings saved.", kind.label);
                                },
                                true);
                    });
            Button test = action(details, PatchStrings.t("Test API key"));
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
                                    return PatchStrings.f(
                                            "{0} accepted this API key. Save any new key before"
                                                    + " downloading.",
                                            kind.label);
                                });
                    });
            Button remove = action(details, PatchStrings.t("Remove API key"));
            remove.setOnClickListener(
                    v -> {
                        key.setText("");
                        task(
                                this,
                                () -> {
                                    requireCredentials().remove(kind);
                                    return PatchStrings.f(
                                            "{0} disabled and saved key removed.", kind.label);
                                });
                    });
            Button account = action(details, PatchStrings.f("Open {0} account", kind.label));
            account.setOnClickListener(
                    v -> {
                        try {
                            startActivity(
                                    new Intent(Intent.ACTION_VIEW, Uri.parse(accountUrl(kind))));
                        } catch (RuntimeException unavailable) {
                            notice.setText(
                                    PatchStrings.t(
                                            "A browser is required to open the service account"
                                                    + " page."));
                        }
                    });
            enabled.setOnCheckedChangeListener(
                    (view, on) -> {
                        if (rendering) return;
                        if (on) render();
                        expand(null);
                        if (on && (credentials == null || !credentials.snapshot(kind).configured)) {
                            promptForKey(this);
                            return;
                        }
                        task(
                                this,
                                () -> {
                                    requireCredentials().setEnabled(kind, on);
                                    return PatchStrings.f(
                                            on
                                                    ? "{0} enabled."
                                                    : "{0} disabled. Active requests for this"
                                                            + " service are paused.",
                                            kind.label);
                                },
                                on);
                    });
        }

        boolean contains(View view) {
            while (view != null) {
                if (view == card) return true;
                ViewParent parent = view.getParent();
                view = parent instanceof View ? (View) parent : null;
            }
            return false;
        }

        void updateExpansion() {
            boolean open = enabled.isChecked() && expanded == this;
            details.setVisibility(open ? View.VISIBLE : View.GONE);
            if (!open) key.setText("");
            gear.setEnabled(enabled.isChecked() && !working);
            gear.setAlpha(gear.isEnabled() ? 1 : .35f);
            gear.setSelected(open);
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
                summary.setText(
                        saved.enabled
                                ? PatchStrings.t("Enabled · API key saved securely")
                                : saved.configured
                                        ? PatchStrings.t("Disabled · API key saved")
                                        : PatchStrings.t("No API key saved"));
            } else {
                enabled.setChecked(false);
                summary.setText(PatchStrings.t("Protected settings unavailable"));
            }
            rendering = false;
            if (!enabled.isChecked() && expanded == this) expanded = null;
            updateExpansion();
        }

        void busy(boolean busy) {
            working = busy;
            enabled.setEnabled(!busy);
            key.setEnabled(!busy);
            for (Button b : actions) b.setEnabled(!busy);
            updateExpansion();
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
        task(row, action, false);
    }

    private void task(Row row, Action action, boolean expandOnSuccess) {
        for (Row item : rows) item.busy(true);
        notice.setText(PatchStrings.t("Working…"));
        try {
            worker.execute(
                    () -> {
                        String message;
                        boolean success;
                        try {
                            message = action.run();
                            success = true;
                        } catch (Exception | LinkageError failure) {
                            success = false;
                            message =
                                    "Could not complete this action. Check the API key, account"
                                        + " access and connection, then retry. Saved keys are not"
                                        + " shown.";
                        }
                        final String result = message;
                        final boolean completed = success;
                        runOnUiThread(
                                () -> {
                                    if (destroyed || isFinishing()) return;
                                    notice.setText(PatchStrings.t(result));
                                    for (Row item : rows) {
                                        item.render();
                                        item.busy(credentials == null);
                                    }
                                    if (completed
                                            && expandOnSuccess
                                            && row != null
                                            && row.enabled.isChecked()) expand(row);
                                });
                    });
        } catch (java.util.concurrent.RejectedExecutionException full) {
            notice.setText(PatchStrings.t("Wait for the current action to finish."));
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
        if (keyDialog != null) keyDialog.dismiss();
        for (Row row : rows) row.key.setText("");
        worker.shutdownNow();
        super.onDestroy();
    }
}

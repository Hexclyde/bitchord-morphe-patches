package app.bitchord.soulseek;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Guided account and download setup. A signed-in account never implies a reachable file peer. */
public final class SoulseekActivity extends DownloadActivity {
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<Runnable>(8),
                    action -> new Thread(action, "soulseek-main-settings"));
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicLong revision = new AtomicLong();
    private volatile SoulseekBridge bridge;
    private volatile boolean foreground = true, destroyed, loginBusy, settingsBusy;
    private boolean reading, rendering, accountExpanded, actionsExpanded, optionalExpanded;
    private boolean credentialsDirty = true;
    private State saved;
    private String uiError = "";
    private TextView status, readiness, sourceSummary, destinationSummary, formatSummary;
    private TextView peerSummary, debridSummary;
    private CompoundButton enabled, remember;
    private ImageButton gear;
    private EditText username, password;
    private LinearLayout accountDetails, accountActions, setup, optional, optionalDetails;
    private Button connect, reconnect, openAccount, forget, clear, find, peer, debrid;
    private final Runnable refresh =
            new Runnable() {
                public void run() {
                    if (!foreground || destroyed) return;
                    if (!reading && !settingsBusy) read(false);
                    handler.postDelayed(this, 1000);
                }
            };

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        CapturePolicy.apply(this);
        if (Build.VERSION.SDK_INT >= 31) getWindow().setHideOverlayWindows(true);
        if (state != null) accountExpanded = state.getBoolean("accountExpanded");
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = column();
        scroll.addView(body);
        setContentView(scroll);
        text(body, PatchStrings.t("Soulseek downloads"), 28, true);
        status = text(body, PatchStrings.t("Loading…"), 17, true);
        status.setTag("soulseek-status");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        readiness = text(body, "", 14, false);
        readiness.setTag("soulseek-readiness");
        find = button(body, PatchStrings.t("Find music on Soulseek"));
        find.setTag("soulseek-find");
        find.setOnClickListener(
                view -> {
                    if (readyToSearch())
                        startActivity(new Intent(this, SoulseekSearchActivity.class));
                });

        LinearLayout account = card(body);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(downloadStyle.dp(64));
        account.addView(header);
        LinearLayout labels = column();
        header.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        text(labels, PatchStrings.t("Soulseek account"), 17, true);
        sourceSummary = text(labels, "", 13, false);
        gear = downloadStyle.settingsButton();
        gear.setTag("soulseek-account-gear");
        gear.setContentDescription(PatchStrings.t("Account settings"));
        header.addView(
                gear, new LinearLayout.LayoutParams(downloadStyle.dp(48), downloadStyle.dp(48)));
        enabled = downloadStyle.switchButton();
        enabled.setTag("soulseek-enabled");
        enabled.setContentDescription(
                PatchStrings.f("Enable {0}", PatchStrings.t("Soulseek downloads")));
        header.addView(
                enabled, new LinearLayout.LayoutParams(downloadStyle.dp(52), downloadStyle.dp(48)));
        reconnect = button(account, PatchStrings.t("Reconnect saved account"));
        reconnect.setTag("soulseek-reconnect");
        reconnect.setOnClickListener(view -> reconnect());
        openAccount = button(account, PatchStrings.t("Sign in"));
        openAccount.setTag("soulseek-open-account");
        openAccount.setOnClickListener(
                view -> {
                    accountExpanded = true;
                    render();
                });
        accountDetails = column();
        accountDetails.setTag("soulseek-account-details");
        account.addView(accountDetails);
        username = input(accountDetails, PatchStrings.t("Soulseek username"), false);
        username.setTag("soulseek-username");
        password = input(accountDetails, PatchStrings.t("Soulseek password"), true);
        password.setTag("soulseek-password");
        LinearLayout rememberRow = new LinearLayout(this);
        rememberRow.setGravity(Gravity.CENTER_VERTICAL);
        accountDetails.addView(rememberRow);
        TextView rememberLabel =
                text(
                        null,
                        PatchStrings.t("Remember this account on this phone (encrypted)"),
                        14,
                        false);
        rememberRow.addView(rememberLabel, new LinearLayout.LayoutParams(0, -2, 1));
        remember = downloadStyle.switchButton();
        remember.setTag("soulseek-remember");
        remember.setChecked(true);
        remember.setContentDescription(
                PatchStrings.t("Remember this account on this phone (encrypted)"));
        rememberRow.addView(
                remember,
                new LinearLayout.LayoutParams(downloadStyle.dp(52), downloadStyle.dp(48)));
        connect = button(accountDetails, PatchStrings.t("Sign in"));
        connect.setTag("soulseek-sign-in");
        connect.setOnClickListener(view -> signIn());
        Button actions = button(accountDetails, PatchStrings.t("Account actions"));
        actions.setTag("soulseek-account-actions-toggle");
        actions.setOnClickListener(
                view -> {
                    actionsExpanded = !actionsExpanded;
                    render();
                });
        accountActions = column();
        accountActions.setTag("soulseek-account-actions");
        accountDetails.addView(accountActions);
        forget = button(accountActions, PatchStrings.t("Sign out and forget account"));
        forget.setTag("soulseek-forget");
        forget.setOnClickListener(view -> forget());
        clear =
                button(
                        accountActions,
                        PatchStrings.t("Clear temporary audio cache and disconnect"));
        clear.setTag("soulseek-clear");
        clear.setOnClickListener(view -> clearCache());
        gear.setOnClickListener(
                view -> {
                    accountExpanded = !accountExpanded;
                    if (!accountExpanded) password.setText("");
                    render();
                });
        enabled.setOnCheckedChangeListener(
                (view, on) -> {
                    if (rendering) return;
                    render();
                    changeEnabled(on);
                });

        setup = card(body);
        setup.setTag("soulseek-setup");
        text(setup, PatchStrings.t("Download setup"), 17, true);
        destinationSummary =
                actionRow(
                        setup,
                        PatchStrings.t("Where copies go"),
                        "soulseek-destinations",
                        () -> {
                            collapseAccount();
                            DownloadDestinations.openSettings(this);
                        });
        formatSummary =
                actionRow(
                        setup,
                        PatchStrings.t("Formats, best first"),
                        "soulseek-formats",
                        () -> {
                            collapseAccount();
                            AudioFormatPreferences.openSettings(this);
                        });

        optional = card(body);
        optional.setTag("soulseek-optional");
        Button options = button(optional, PatchStrings.t("More download options"));
        options.setTag("soulseek-optional-toggle");
        options.setOnClickListener(
                view -> {
                    collapseAccount();
                    optionalExpanded = !optionalExpanded;
                    render();
                });
        optionalDetails = column();
        optionalDetails.setTag("soulseek-optional-details");
        optional.addView(optionalDetails);
        text(
                optionalDetails,
                PatchStrings.t("Other download options are controlled in Patches & tweaks."),
                14,
                false);
        peerSummary = text(optionalDetails, "", 14, false);
        peer = button(optionalDetails, PatchStrings.t("Peer downloads"));
        peer.setTag("soulseek-peers");
        peer.setOnClickListener(
                view -> {
                    if (saved != null && saved.peers)
                        startActivity(new Intent(this, BulkDownloadsActivity.class));
                });
        text(
                optionalDetails,
                PatchStrings.t("Pair a trusted phone and choose files to send or download."),
                14,
                false);
        debridSummary = text(optionalDetails, "", 14, false);
        debrid = button(optionalDetails, PatchStrings.t("Debrid services and API keys"));
        debrid.setTag("soulseek-debrid");
        debrid.setOnClickListener(
                view -> {
                    if (saved != null && saved.debrid)
                        startActivity(new Intent(this, DebridSetupActivity.class));
                });
        render();
        read(true);
    }

    private static final class State {
        boolean enabled, signedIn, hasSaved, destinationReady, formatsReady, peers, debrid;
        String user = "", mode = "";
        List<String> formats = new ArrayList<>();
    }

    private State snapshot(boolean credentials) throws Exception {
        if (bridge == null) {
            SoulseekBridge.boot(getApplicationContext());
            bridge = SoulseekBridge.get();
        }
        State result = new State();
        result.enabled = DownloadSettings.enabled(this, DownloadSettings.SOULSEEK);
        result.peers = DownloadSettings.enabled(this, DownloadSettings.PEERS);
        result.debrid = DownloadSettings.enabled(this, DownloadSettings.DEBRID);
        result.user = bridge.username;
        try {
            bridge.ready();
            result.signedIn = result.enabled;
        } catch (java.io.IOException signedOut) {
            result.signedIn = false;
        }
        result.hasSaved = saved != null && saved.hasSaved;
        if (credentials) {
            try {
                String[] account = bridge.credentials.load();
                result.hasSaved = account != null;
                if (result.user.isEmpty() && account != null) result.user = account[0];
            } catch (Exception unavailable) {
                // Unreadable credentials must not prevent the user opening account settings
                // and explicitly forgetting them. The bridge retains its redacted status.
                result.hasSaved = false;
            }
        }
        try {
            result.mode = DownloadDestinations.snapshot(this).mode;
            result.destinationReady = true;
        } catch (java.io.IOException unconfigured) {
            result.destinationReady = false;
        }
        AudioFormatPreferences.Settings formats = AudioFormatPreferences.snapshot(this);
        result.formatsReady = formats.configured;
        for (AudioFormatPreferences.Format format : formats.order)
            if (formats.enabled.contains(format)) result.formats.add(format.label);
        return result;
    }

    private void read(boolean credentials) {
        if (credentials) credentialsDirty = true;
        if (destroyed || !foreground || reading) return;
        reading = true;
        boolean reloadCredentials = credentialsDirty;
        credentialsDirty = false;
        long intent = revision.get();
        try {
            worker.execute(
                    () -> {
                        State result = null;
                        try {
                            if (active(intent)) result = snapshot(reloadCredentials);
                        } catch (Exception | LinkageError unavailable) {
                            /* Keep raw errors and credentials out of the page. */
                        }
                        State next = result;
                        handler.post(
                                () -> {
                                    if (!active(intent)) return;
                                    reading = false;
                                    if (next != null) accept(next);
                                    else
                                        uiError =
                                                PatchStrings.t(
                                                        "Soulseek settings unavailable. Restart"
                                                                + " BitChord and try again.");
                                    render();
                                });
                    });
        } catch (RejectedExecutionException full) {
            reading = false;
        }
    }

    private void accept(State next) {
        boolean wasSignedIn = saved != null && saved.signedIn;
        saved = next;
        if (username.getText().length() == 0 && !next.user.isEmpty()) username.setText(next.user);
        if (next.signedIn && !wasSignedIn) {
            accountExpanded = false;
            password.setText("");
        }
    }

    private void changeEnabled(boolean on) {
        if (bridge == null || settingsBusy || !foreground) {
            render();
            return;
        }
        settingsBusy = true;
        uiError = "";
        long intent = revision.incrementAndGet();
        reading = false;
        render();
        worker.execute(
                () -> {
                    State result = null;
                    boolean failed = false;
                    try {
                        DownloadSettings.setEnabled(
                                getApplicationContext(), DownloadSettings.SOULSEEK, on);
                        result = snapshot(true);
                    } catch (Exception | LinkageError failure) {
                        failed = true;
                    }
                    settingsBusy = false;
                    State next = result;
                    boolean failure = failed;
                    handler.post(
                            () -> {
                                if (!active(intent)) return;
                                if (next != null) {
                                    accept(next);
                                }
                                if (failure)
                                    uiError =
                                            PatchStrings.t(
                                                    "This setting could not be changed. Try"
                                                            + " again.");
                                render();
                            });
                });
    }

    private void signIn() {
        if (bridge == null || loginBusy || settingsBusy || saved == null || !saved.enabled) return;
        String user = username.getText().toString().trim();
        String secret = password.getText().toString();
        int mode = remember.isChecked() ? 1 : 2;
        password.setText("");
        loginBusy = true;
        uiError = "";
        long attempt = bridge.beginLogin();
        render();
        bridge.work.execute(
                () -> {
                    try {
                        bridge.login(user, secret, mode, attempt);
                    } catch (Throwable failure) {
                        /* Bridge owns a redacted, actionable login status. */
                    } finally {
                        loginBusy = false;
                        refreshAfterAccountAction();
                    }
                });
    }

    private void reconnect() {
        if (bridge == null || loginBusy || settingsBusy || saved == null || !saved.enabled) return;
        loginBusy = true;
        uiError = "";
        long attempt = bridge.beginLogin();
        render();
        bridge.work.execute(
                () -> {
                    try {
                        String[] account = bridge.credentials.load();
                        if (account == null)
                            bridge.status = "No saved account. Enter your username and password.";
                        else bridge.login(account[0], account[1], 0, attempt);
                    } catch (Throwable failure) {
                        bridge.status =
                                "Saved account could not connect. Enter the account again to"
                                        + " retry.";
                    } finally {
                        loginBusy = false;
                        refreshAfterAccountAction();
                    }
                });
    }

    private void forget() {
        if (bridge == null) return;
        password.setText("");
        username.setText("");
        uiError = "";
        // A control thread must remain available while login/search occupies the bridge IO worker.
        new Thread(
                        () -> {
                            try {
                                bridge.forget();
                            } catch (Throwable failure) {
                                bridge.status =
                                        "Signed out, but credentials could not be cleared. Retry"
                                                + " forgetting the account.";
                            } finally {
                                refreshAfterAccountAction();
                            }
                        },
                        "soulseek-signout")
                .start();
    }

    private void clearCache() {
        if (bridge == null) return;
        password.setText("");
        bridge.beginLogin();
        bridge.work.execute(
                () -> {
                    try {
                        bridge.clearTemporaryCache();
                    } catch (Exception failure) {
                        bridge.status =
                                "Cache not cleared. Stop Soulseek downloads and retry. If the"
                                        + " engine could not stop, restart BitChord first.";
                    } finally {
                        refreshAfterAccountAction();
                    }
                });
    }

    private void refreshAfterAccountAction() {
        handler.post(
                () -> {
                    if (!destroyed && foreground) {
                        uiError = "";
                        read(true);
                        render();
                    }
                });
    }

    private boolean readyToSearch() {
        return saved != null
                && saved.enabled
                && saved.signedIn
                && saved.destinationReady
                && saved.formatsReady
                && !loginBusy
                && !settingsBusy;
    }

    private void render() {
        if (status == null) return;
        boolean on = saved != null && saved.enabled;
        boolean signedIn = saved != null && saved.signedIn;
        String accountStatus;
        if (!uiError.isEmpty()) accountStatus = uiError;
        else if (saved == null) accountStatus = PatchStrings.t("Loading…");
        else if (!on) accountStatus = PatchStrings.t("Enable Soulseek to sign in and search.");
        else if (loginBusy) accountStatus = PatchStrings.t("Connecting to Soulseek…");
        else if (signedIn) accountStatus = PatchStrings.f("Signed in as {0}", saved.user);
        else accountStatus = PatchStrings.t(bridge == null ? "Not signed in" : bridge.status);
        if (!accountStatus.contentEquals(status.getText())) status.setText(accountStatus);
        String next =
                !on
                        ? PatchStrings.t("Enable Soulseek to sign in and search.")
                        : !signedIn
                                ? PatchStrings.t("Sign in to search Soulseek.")
                                : !saved.destinationReady
                                        ? PatchStrings.t("Choose where copies go before searching.")
                                        : !saved.formatsReady
                                                ? PatchStrings.t(
                                                        "Choose your audio formats before"
                                                                + " searching.")
                                                : PatchStrings.t("Ready to search");
        readiness.setText(next);
        readiness.setVisibility(on ? View.VISIBLE : View.GONE);
        rendering = true;
        enabled.setChecked(on);
        rendering = false;
        enabled.setEnabled(saved != null && !settingsBusy && foreground);
        sourceSummary.setText(on ? PatchStrings.t("Source enabled") : PatchStrings.t("Off"));
        if (!on) {
            accountExpanded = actionsExpanded = optionalExpanded = false;
            password.setText("");
        }
        gear.setEnabled(on && !settingsBusy && foreground);
        gear.setAlpha(gear.isEnabled() ? 1 : .35f);
        gear.setSelected(on && accountExpanded);
        accountDetails.setVisibility(on && accountExpanded ? View.VISIBLE : View.GONE);
        accountActions.setVisibility(actionsExpanded ? View.VISIBLE : View.GONE);
        reconnect.setVisibility(on && !signedIn && saved.hasSaved ? View.VISIBLE : View.GONE);
        openAccount.setVisibility(
                on && !signedIn && !saved.hasSaved && !accountExpanded ? View.VISIBLE : View.GONE);
        openAccount.setEnabled(on && !loginBusy && !settingsBusy);
        reconnect.setEnabled(on && !loginBusy && !settingsBusy);
        connect.setEnabled(on && !loginBusy && !settingsBusy);
        username.setEnabled(!loginBusy && !settingsBusy);
        password.setEnabled(!loginBusy && !settingsBusy);
        remember.setEnabled(!loginBusy && !settingsBusy);
        forget.setEnabled(on && bridge != null);
        clear.setEnabled(on && !settingsBusy);
        find.setEnabled(readyToSearch());
        setup.setVisibility(on ? View.VISIBLE : View.GONE);
        optional.setVisibility(saved != null ? View.VISIBLE : View.GONE);
        optionalDetails.setVisibility(optionalExpanded ? View.VISIBLE : View.GONE);
        if (saved != null) {
            destinationSummary.setText(
                    !saved.destinationReady
                            ? PatchStrings.t("Not chosen yet")
                            : DownloadDestinations.BOTH.equals(saved.mode)
                                    ? PatchStrings.t("Both")
                                    : DownloadDestinations.TELEGRAM.equals(saved.mode)
                                            ? PatchStrings.t("Telegram main channel")
                                            : PatchStrings.t("Phone"));
            formatSummary.setText(
                    !saved.formatsReady
                            ? PatchStrings.t("Not chosen yet")
                            : PatchStrings.f("{0} formats enabled", saved.formats.size())
                                    + "\n"
                                    + android.text.TextUtils.join(" · ", saved.formats));
            peerSummary.setText(
                    saved.peers
                            ? PatchStrings.t("Exchange selected files with a paired phone")
                            : PatchStrings.t("Off"));
            debridSummary.setText(
                    saved.debrid
                            ? PatchStrings.t(
                                    "Use your own Real-Debrid, AllDebrid, Premiumize or TorBox"
                                            + " account")
                            : PatchStrings.t("Off"));
            peer.setEnabled(saved.peers);
            debrid.setEnabled(saved.debrid);
        }
    }

    private void collapseAccount() {
        accountExpanded = false;
        password.setText("");
        render();
    }

    private boolean active(long intent) {
        return !destroyed && foreground && revision.get() == intent;
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        handler.removeCallbacks(refresh);
        handler.post(refresh);
        read(true);
    }

    @Override
    protected void onPause() {
        foreground = false;
        revision.incrementAndGet();
        reading = false;
        handler.removeCallbacks(refresh);
        if (password != null) password.setText("");
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("accountExpanded", accountExpanded);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        worker.shutdown();
        if (password != null) password.setText("");
        super.onDestroy();
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout view = column();
        downloadStyle.card(view);
        parent.addView(view);
        return view;
    }

    private TextView text(LinearLayout parent, String value, int size, boolean prominent) {
        TextView view =
                downloadStyle.text(
                        value,
                        size,
                        prominent ? downloadStyle.foreground : downloadStyle.secondary,
                        size >= 24
                                ? downloadStyle.heavy
                                : prominent ? downloadStyle.medium : downloadStyle.regular);
        view.setPadding(0, downloadStyle.dp(8), 0, downloadStyle.dp(8));
        if (parent != null) parent.addView(view);
        return view;
    }

    private Button button(LinearLayout parent, String label) {
        Button view = new Button(this);
        view.setText(label);
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        downloadStyle.button(view);
        return view;
    }

    private EditText input(LinearLayout parent, String hint, boolean secret) {
        EditText view = new EditText(this);
        view.setHint(hint);
        view.setContentDescription(hint);
        view.setTextDirection(View.TEXT_DIRECTION_LTR);
        view.setSingleLine(true);
        view.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | (secret
                                ? InputType.TYPE_TEXT_VARIATION_PASSWORD
                                : InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD));
        view.setSaveEnabled(false);
        view.setSaveFromParentEnabled(false);
        if (Build.VERSION.SDK_INT >= 26)
            view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        downloadStyle.field(view);
        return view;
    }

    private TextView actionRow(LinearLayout parent, String title, String tag, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(downloadStyle.dp(64));
        row.setTag(tag);
        row.setFocusable(true);
        row.setClickable(true);
        row.setOnClickListener(view -> action.run());
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout labels = column();
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        text(labels, title, 16, true);
        TextView summary = text(labels, "", 14, false);
        ImageButton gear = downloadStyle.settingsButton();
        gear.setContentDescription(title);
        gear.setOnClickListener(view -> action.run());
        row.addView(
                gear, new LinearLayout.LayoutParams(downloadStyle.dp(48), downloadStyle.dp(48)));
        return summary;
    }
}

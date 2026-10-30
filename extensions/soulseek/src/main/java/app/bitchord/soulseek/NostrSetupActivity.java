package app.bitchord.soulseek;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.bitchord.telegram.CapturePolicy;
import app.bitchord.telegram.PatchStrings;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public final class NostrSetupActivity extends DownloadActivity {
    private volatile NostrCoordinator coordinator;
    private volatile RelayDiscovery discovery;
    private volatile boolean destroyed;
    private boolean busy, controlBusy;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService control = Executors.newSingleThreadExecutor();
    private final AtomicLong revision = new AtomicLong();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Button> actions = new ArrayList<>();
    private TextView identity, status, result;
    private EditText relays, peers, target;
    private Button stop, cancelScan, applyRelays, showRelays;
    private final Runnable refresh =
            new Runnable() {
                public void run() {
                    NostrCoordinator current = coordinator;
                    if (current != null && !destroyed) {
                        RelayDiscovery scan = discovery;
                        status.setText(
                                current.status()
                                        + "\n"
                                        + current.diagnostics()
                                        + (scan == null ? "" : "\n\n" + scan.status()));
                        updateControls();
                    }
                    if (!destroyed) handler.postDelayed(this, 1000);
                }
            };

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        CapturePolicy.apply(this);
        if (Build.VERSION.SDK_INT >= 31) getWindow().setHideOverlayWindows(true);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        body.setPadding(padding, padding, padding, padding);
        scroll.addView(body);
        setContentView(scroll);
        button(body, PatchStrings.t("Back"), false).setOnClickListener(v -> finish());
        text(body, PatchStrings.t("Download connection setup"), 24);
        text(
                body,
                PatchStrings.t(
                        "Experimental encrypted coordination through public Nostr relays. Uses a"
                                + " separate identity and never messages your Telegram contacts."
                                + " Connection coordination is for downloads; it does not stream"
                                + " music."),
                16);
        text(body, PatchStrings.t("Your connection public key"), 16);
        identity = text(body, PatchStrings.t("Loading…"), 14);
        identity.setTextIsSelectable(true);
        button(body, PatchStrings.t("Copy public key"), true)
                .setOnClickListener(
                        v -> {
                            ClipboardManager clipboard =
                                    (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            if (clipboard != null && identity.getText().length() == 64) {
                                clipboard.setPrimaryClip(
                                        ClipData.newPlainText(
                                                "BitChord public key", identity.getText()));
                                result.setText(PatchStrings.t("Public key copied."));
                            }
                        });
        text(
                body,
                PatchStrings.t(
                        "Exchange public keys through a trusted channel and compare the full key. A"
                            + " key does not verify a person's name or Soulseek account. Relays can"
                            + " see connection metadata; direct peers can see your public IP"
                            + " address."),
                14);
        relays =
                field(
                        body,
                        PatchStrings.t("Relay URLs: one wss:// address per line, up to 3"),
                        1024,
                        false);
        text(
                body,
                PatchStrings.t(
                        "Find best relays sends small, encrypted test messages addressed to this"
                            + " app's own identity. It tests up to 24 candidates within a"
                            + " two-minute budget. Results reflect this phone's network without GPS"
                            + " or location lookup, and measure coordination latency rather than"
                            + " download speed. Leaving this screen cancels the check."),
                14);
        button(body, PatchStrings.t("Find best relays"), true)
                .setOnClickListener(v -> perform(io, null, () -> discovery.scan()));
        cancelScan = button(body, PatchStrings.t("Cancel relay check"), false);
        cancelScan.setEnabled(false);
        cancelScan.setOnClickListener(
                v ->
                        perform(
                                control,
                                "Relay check cancelled.",
                                () -> {
                                    RelayDiscovery scan = discovery;
                                    if (scan != null) scan.cancel();
                                }));
        applyRelays = button(body, PatchStrings.t("Use compatible relay selection"), true);
        applyRelays.setEnabled(false);
        applyRelays.setOnClickListener(
                v ->
                        perform(
                                io,
                                "Relay selection saved. Paired phones still need at least one"
                                        + " common relay.",
                                () -> {
                                    String selected = NostrConfig.lines(discovery.preferred());
                                    coordinator.configureRelays(selected);
                                    long intent = revision.get();
                                    handler.post(
                                            () -> {
                                                if (!destroyed && revision.get() == intent)
                                                    relays.setText(selected);
                                            });
                                }));
        showRelays = button(body, PatchStrings.t("Show checked relays"), true);
        showRelays.setEnabled(false);
        showRelays.setOnClickListener(
                v -> {
                    RelayDiscovery.Report report = discovery == null ? null : discovery.report();
                    if (report == null) return;
                    TextView details = new TextView(this);
                    details.setText(report.details());
                    details.setTextIsSelectable(true);
                    details.setPadding(padding, padding, padding, padding);
                    ScrollView entries = new ScrollView(this);
                    entries.addView(details);
                    new AlertDialog.Builder(this)
                            .setTitle(PatchStrings.t("Relay measurements"))
                            .setView(entries)
                            .setPositiveButton(PatchStrings.t("Close"), null)
                            .show();
                });
        text(
                body,
                PatchStrings.t(
                        "Applying a selection preserves working configured relays. To replace those"
                            + " with faster alternatives, coordinate the change with your paired"
                            + " phone. Another check explores more candidates. Stop coordination"
                            + " before checking relays."),
                14);
        peers =
                field(
                        body,
                        PatchStrings.t(
                                "Trusted peer public keys: one 64-character key per line, up to"
                                        + " 16"),
                        2048,
                        false);
        button(body, PatchStrings.t("Save settings"), true)
                .setOnClickListener(
                        v -> {
                            String relayText = relays.getText().toString();
                            String peerText = peers.getText().toString();
                            perform(
                                    io,
                                    "Settings saved.",
                                    () -> coordinator.configure(relayText, peerText));
                        });
        button(body, PatchStrings.t("Start coordination"), true)
                .setOnClickListener(
                        v ->
                                perform(
                                        io,
                                        "Coordination started. Waiting for relay connections.",
                                        () -> coordinator.start()));
        stop = button(body, PatchStrings.t("Stop coordination"), false);
        stop.setEnabled(false);
        stop.setOnClickListener(
                v ->
                        perform(
                                control,
                                "Coordination stopped.",
                                () -> {
                                    NostrCoordinator current = coordinator;
                                    RelayDiscovery scan = discovery;
                                    if (scan != null) scan.cancel();
                                    if (current != null) current.stop();
                                }));
        target = field(body, PatchStrings.t("Trusted peer public key to test"), 64, true);
        button(body, PatchStrings.t("Test trusted peer"), true)
                .setOnClickListener(
                        v -> {
                            String peer = target.getText().toString().trim();
                            perform(
                                    io,
                                    "Connection test sent. Check the response count below.",
                                    () -> coordinator.ping(peer));
                        });
        button(body, PatchStrings.t("Reset connection identity"), true)
                .setOnClickListener(
                        v ->
                                new AlertDialog.Builder(this)
                                        .setTitle(PatchStrings.t("Reset connection identity?"))
                                        .setMessage(
                                                PatchStrings.t(
                                                        "Stops coordination and deletes this app's"
                                                            + " connection identity and trusted"
                                                            + " peers. You will need to exchange"
                                                            + " the new public key with peers."))
                                        .setNegativeButton(PatchStrings.t("Cancel"), null)
                                        .setPositiveButton(
                                                PatchStrings.t("Reset"),
                                                (dialog, which) ->
                                                        perform(
                                                                control,
                                                                "Identity reset.",
                                                                () -> {
                                                                    RelayDiscovery scan = discovery;
                                                                    if (scan != null) scan.cancel();
                                                                    coordinator.forget();
                                                                    loadFields();
                                                                }))
                                        .show());
        status = text(body, PatchStrings.t("Coordination is off until you start it."), 14);
        result = text(body, PatchStrings.t("Save settings while coordination is stopped."), 14);
        perform(
                io,
                "Ready.",
                () -> {
                    coordinator = NostrCoordinator.get(getApplicationContext());
                    discovery = new RelayDiscovery(getApplicationContext(), coordinator);
                    loadFields();
                });
    }

    private void loadFields() throws Exception {
        long intent = revision.get();
        String key = coordinator.publicKey();
        String relayText = coordinator.relaysText();
        String peerText = coordinator.peersText();
        handler.post(
                () -> {
                    if (destroyed || revision.get() != intent) return;
                    identity.setText(key);
                    relays.setText(relayText);
                    peers.setText(peerText);
                });
    }

    private interface Action {
        void run() throws Exception;
    }

    private void perform(ExecutorService executor, String success, Action action) {
        if (destroyed) return;
        long intent = revision.incrementAndGet();
        busy = true;
        if (executor == control) controlBusy = true;
        updateControls();
        result.setText(PatchStrings.t("Working…"));
        executor.execute(
                () -> {
                    if (destroyed || revision.get() != intent) return;
                    String message = success;
                    try {
                        action.run();
                        if (message == null && discovery != null) message = discovery.status();
                    } catch (Exception | LinkageError e) {
                        message =
                                "Could not complete this action. Check the relay URLs, trusted peer"
                                        + " keys and connection status, then retry.";
                    }
                    String completed = message;
                    handler.post(
                            () -> {
                                if (destroyed || revision.get() != intent) return;
                                result.setText(PatchStrings.t(completed));
                                busy = false;
                                if (executor == control) controlBusy = false;
                                updateControls();
                            });
                });
    }

    private void updateControls() {
        RelayDiscovery scan = discovery;
        boolean checking = scan != null && scan.scanning();
        boolean ready = coordinator != null && !busy && !checking;
        for (Button button : actions) button.setEnabled(ready);
        if (stop != null) stop.setEnabled(coordinator != null && !controlBusy);
        if (cancelScan != null) cancelScan.setEnabled(checking && !controlBusy);
        if (applyRelays != null) {
            RelayDiscovery.Report report = scan == null ? null : scan.report();
            applyRelays.setEnabled(
                    ready && report != null && report.complete && report.recommendation.canApply);
            if (showRelays != null) showRelays.setEnabled(ready && report != null);
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

    private EditText field(LinearLayout parent, String hint, int limit, boolean singleLine) {
        EditText view = new EditText(this);
        view.setHint(hint);
        view.setTextDirection(View.TEXT_DIRECTION_LTR);
        view.setSingleLine(singleLine);
        view.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        | (singleLine ? 0 : InputType.TYPE_TEXT_FLAG_MULTI_LINE));
        view.setFilters(new InputFilter[] {new InputFilter.LengthFilter(limit)});
        view.setSaveEnabled(false);
        view.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        parent.addView(view);
        return view;
    }

    private Button button(LinearLayout parent, String title, boolean action) {
        Button view = new Button(this);
        view.setText(title);
        parent.addView(view);
        if (action) actions.add(view);
        return view;
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        revision.incrementAndGet();
        handler.removeCallbacksAndMessages(null);
        RelayDiscovery scan = discovery;
        if (scan != null) {
            control.execute(scan::cancel);
        }
        io.shutdownNow();
        control.shutdown();
        super.onDestroy();
    }
}

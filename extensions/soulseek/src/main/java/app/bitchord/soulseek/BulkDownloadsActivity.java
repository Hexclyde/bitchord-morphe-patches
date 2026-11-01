package app.bitchord.soulseek;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.bitchord.telegram.CapturePolicy;
import app.bitchord.telegram.PatchStrings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Explicit file sharing and resumable downloads between paired app identities. */
public final class BulkDownloadsActivity extends DownloadActivity {
    private static final int PICK_AUDIO = 4701;
    private static final int MAX_SELECTED_ROWS = 8;
    private static final long REFRESH_MS = 1500;
    private volatile BulkCoordinator coordinator;
    private EditText recipient;
    private TextView status, selectedHeading, shareNote;
    private Button shareButton, peerPicker;
    private LinearLayout content, connection, incoming, shared, jobs, selected;
    private PeerDownloadRows stableRows;
    private PeerDownloadRows.ScrollAnchor anchor;
    private PeerDownloadRows.Refresher<Snapshot> refresher;
    private Snapshot latest;
    private Dialog openDialog;
    private boolean recipientSuggested;
    private volatile boolean peersStale = true;
    private volatile boolean stoppedByRequest;
    private volatile List<String> savedPeers = Collections.emptyList();
    private volatile String actionStatus = "";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService snapshots =
            Executors.newSingleThreadExecutor(
                    runnable -> {
                        Thread thread = new Thread(runnable, "bitchord-peer-snapshots");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<Runnable>(16),
                    runnable -> {
                        Thread thread = new Thread(runnable, "bitchord-download-actions");
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy());
    private final ThreadPoolExecutor commands =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<Runnable>(16),
                    runnable -> {
                        Thread thread = new Thread(runnable, "bitchord-download-commands");
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy());

    /** Display facts read off the UI thread, so applying a snapshot never touches storage. */
    private static final class Snapshot {
        String coordinatorStatus = "", runtimeStatus = "";
        boolean running, destinationReady, formatsReady;
        List<String> peers = Collections.emptyList();
        final List<SelectedRow> selected = new ArrayList<>();
        final List<OfferRow> offers = new ArrayList<>();
        final List<ShareRow> shares = new ArrayList<>();
        final List<JobRow> jobs = new ArrayList<>();
    }

    private static final class SelectedRow {
        final String reference, name, summary;
        final boolean cancellable;

        SelectedRow(SoulseekSelectedDownloads.Progress item) {
            reference = item.reference;
            name = item.name;
            summary = item.summary();
            cancellable = item.canCancel();
        }
    }

    private static final class OfferRow {
        final String session, name, detail;

        OfferRow(BulkCoordinator.Incoming offer) {
            session = offer.session;
            name = offer.name;
            detail =
                    bytes(offer.size)
                            + " · "
                            + PatchStrings.f("From {0}", PeerDownloadRows.shortKey(offer.peer))
                            + " · "
                            + PatchStrings.state(offer.state);
        }
    }

    private static final class ShareRow {
        final BulkDownloads.Share share;
        final String detail;

        ShareRow(BulkDownloads.Share share) {
            this.share = share;
            detail = bytes(share.size);
        }
    }

    private static final class JobRow {
        final String id, name, detail;
        final long done, total;
        final boolean pausable, resumable, cancellable, pendingTelegram, active;

        JobRow(BulkJobStore.Job job) {
            BulkJobStore.State state = job.state;
            id = job.id;
            name = job.offer.name;
            done = job.verifiedBytes();
            total = job.offer.size;
            pendingTelegram =
                    "uploading".equals(job.telegramStatus)
                            || "receipt-check-required".equals(job.telegramStatus);
            detail =
                    PatchStrings.state(state)
                            + " · "
                            + bytes(done)
                            + " / "
                            + bytes(total)
                            + (job.route.isEmpty() ? "" : " · " + job.route)
                            + (job.needsPhone()
                                    ? "\n"
                                            + PatchStrings.f(
                                                    "Phone folder: {0}",
                                                    PatchStrings.state(job.phoneStatus))
                                    : "")
                            + (job.needsTelegram()
                                    ? "\n"
                                            + PatchStrings.f(
                                                    "Telegram main channel: {0}",
                                                    PatchStrings.state(job.telegramStatus))
                                    : "")
                            + (job.message.isEmpty() ? "" : "\n" + PatchStrings.t(job.message))
                            + (pendingTelegram
                                    ? "\n"
                                            + PatchStrings.t(
                                                    "Telegram may finish sending after a pause."
                                                            + " Resume checks the existing"
                                                            + " receipt.")
                                    : "");
            active =
                    state == BulkJobStore.State.RUNNING
                            || state == BulkJobStore.State.QUEUED
                            || state == BulkJobStore.State.PAUSED;
            pausable = state == BulkJobStore.State.RUNNING || state == BulkJobStore.State.QUEUED;
            resumable =
                    state == BulkJobStore.State.PAUSED
                            || state == BulkJobStore.State.FAILED
                            || state == BulkJobStore.State.VERIFIED;
            cancellable =
                    state != BulkJobStore.State.COMPLETE && state != BulkJobStore.State.CANCELLED;
        }
    }

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        CapturePolicy.apply(this);
        if (Build.VERSION.SDK_INT >= 31) getWindow().setHideOverlayWindows(true);
        stableRows = new PeerDownloadRows(downloadStyle);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);
        setContentView(scroll);
        anchor = new PeerDownloadRows.ScrollAnchor(scroll);
        TextView title = new TextView(this);
        title.setText(PatchStrings.t("Peer downloads"));
        title.setTextSize(24);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        body.addView(title);
        connection = section(body);
        // Nothing below is shown until real state arrives, so empty rows cannot flash.
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setVisibility(View.GONE);
        downloadStyle.markCustom(content);
        body.addView(content, new LinearLayout.LayoutParams(-1, -2));
        sectionHeading(content, PatchStrings.t("Incoming offers"));
        incoming = section(content);
        sectionHeading(content, PatchStrings.t("Download queue"));
        jobs = section(content);
        sectionHeading(content, PatchStrings.t("Share files"));
        buildShareForm(content);
        shared = section(content);
        selectedHeading = sectionHeading(content, PatchStrings.t("Soulseek downloads"));
        selected = section(content);
        refresher =
                new PeerDownloadRows.Refresher<>(
                        snapshots,
                        ui,
                        REFRESH_MS,
                        this::loadSnapshot,
                        new PeerDownloadRows.Refresher.Target<Snapshot>() {
                            @Override
                            public void apply(Snapshot snapshot) {
                                render(snapshot);
                            }

                            @Override
                            public void failed() {
                                setStatus(
                                        PatchStrings.t(
                                                "Download storage could not be opened. Restart"
                                                        + " BitChord and try again."));
                            }
                        });
        renderConnection(null);
    }

    private LinearLayout section(LinearLayout parent) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        stableRows.group(group);
        parent.addView(group);
        return group;
    }

    private TextView sectionHeading(LinearLayout parent, String value) {
        TextView label =
                downloadStyle.text(value, 13, downloadStyle.secondary, downloadStyle.semibold);
        label.setPadding(downloadStyle.dp(16), downloadStyle.dp(14), downloadStyle.dp(16), 0);
        if (Build.VERSION.SDK_INT >= 28) label.setAccessibilityHeading(true);
        parent.addView(label);
        return label;
    }

    private void buildShareForm(LinearLayout parent) {
        LinearLayout form = new LinearLayout(this);
        downloadStyle.card(form);
        parent.addView(form);
        recipient = new EditText(this);
        recipient.setHint(PatchStrings.t("Saved peer's 64-character app public key"));
        recipient.setContentDescription(PatchStrings.t("Saved peer's 64-character app public key"));
        recipient.setTextDirection(View.TEXT_DIRECTION_LTR);
        recipient.setSingleLine(true);
        recipient.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        recipient.setSaveEnabled(false);
        if (Build.VERSION.SDK_INT >= 26)
            recipient.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        form.addView(recipient, new LinearLayout.LayoutParams(-1, -2));
        downloadStyle.field(recipient);
        peerPicker = new Button(this);
        peerPicker.setText(PatchStrings.t("Choose saved peer"));
        peerPicker.setOnClickListener(view -> choosePeer());
        form.addView(peerPicker, new LinearLayout.LayoutParams(-1, -2));
        downloadStyle.textAction(peerPicker);
        shareButton = new Button(this);
        shareButton.setText(PatchStrings.t("Choose audio files to share"));
        shareButton.setOnClickListener(view -> chooseFiles());
        form.addView(shareButton, new LinearLayout.LayoutParams(-1, -2));
        downloadStyle.button(shareButton);
        stableRows.tone(shareButton, true);
        shareNote = downloadStyle.text("", 13, downloadStyle.secondary, downloadStyle.regular);
        shareNote.setPadding(0, downloadStyle.dp(8), 0, downloadStyle.dp(4));
        form.addView(shareNote);
    }

    private BulkCoordinator open() throws IOException {
        BulkCoordinator current = coordinator;
        if (current == null) {
            current = BulkCoordinator.get(getApplicationContext());
            coordinator = current;
        }
        return current;
    }

    /** Runs on the snapshot thread; storage, locks and network-derived state stay off the UI. */
    private Snapshot loadSnapshot() throws Exception {
        BulkCoordinator current = open();
        BulkDownloads downloads = current.downloads();
        Context app = getApplicationContext();
        Snapshot next = new Snapshot();
        next.coordinatorStatus = current.status();
        next.runtimeStatus = downloads.status();
        next.running = current.isRunning();
        try {
            DownloadDestinations.snapshot(app);
            next.destinationReady = true;
        } catch (IOException unconfigured) {
            next.destinationReady = false;
        }
        next.formatsReady = AudioFormatPreferences.snapshot(app).configured;
        if (peersStale) {
            // The identity file is read only when the page returns, not on every progress tick.
            try {
                savedPeers = NostrConfig.peers(NostrCoordinator.get(app).peersText());
            } catch (Exception noneSaved) {
                savedPeers = Collections.emptyList();
            }
            peersStale = false;
        }
        next.peers = savedPeers;
        for (SoulseekSelectedDownloads.Progress item : SoulseekSelectedDownloads.snapshots()) {
            if (item.handedOff || next.selected.size() >= MAX_SELECTED_ROWS)
                continue; // Persisted copy jobs appear in the queue.
            next.selected.add(new SelectedRow(item));
        }
        for (BulkCoordinator.Incoming offer : current.offers())
            next.offers.add(new OfferRow(offer));
        for (BulkDownloads.Share share : downloads.shares()) next.shares.add(new ShareRow(share));
        for (BulkJobStore.Job job : downloads.jobs()) next.jobs.add(new JobRow(job));
        return next;
    }

    private void render(Snapshot snapshot) {
        if (isFinishing() || isDestroyed()) return;
        anchor.capture();
        latest = snapshot;
        content.setVisibility(View.VISIBLE);
        renderConnection(snapshot);
        renderOffers(snapshot);
        renderJobs(snapshot);
        renderShares(snapshot);
        renderSelected(snapshot);
    }

    private void renderConnection(Snapshot snapshot) {
        stableRows.begin(connection);
        LinearLayout state = stableRows.row(connection, "state");
        status = stableRows.text(state, statusLine(snapshot), 15);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout strip = stableRows.actions(state);
        if (snapshot != null) {
            if (snapshot.peers.isEmpty())
                stableRows.action(
                        strip,
                        PatchStrings.t("Pair a peer"),
                        PatchStrings.t("Pair a peer"),
                        true,
                        () -> startActivity(new Intent(this, NostrSetupActivity.class)));
            else if (snapshot.running)
                stableRows.action(
                        strip,
                        PatchStrings.t("Stop peer connections"),
                        PatchStrings.t("Stop peer connections"),
                        false,
                        this::confirmStop);
            else
                stableRows.action(
                        strip,
                        PatchStrings.t("Start download connections"),
                        PatchStrings.t("Start download connections"),
                        true,
                        () -> {
                            stoppedByRequest = false;
                            command("Starting connections", () -> open().start());
                        });
        }
        stableRows.overflow(strip, PatchStrings.t("More peer download options"), this::pageMenu);
        if (snapshot != null && !(snapshot.destinationReady && snapshot.formatsReady)) {
            LinearLayout setup = stableRows.row(connection, "setup");
            stableRows.text(
                    setup,
                    PatchStrings.t(
                            "Choose where copies go and your formats before accepting downloads."),
                    14);
            stableRows.action(
                    stableRows.actions(setup),
                    PatchStrings.t("Finish setup"),
                    PatchStrings.t("Finish setup"),
                    false,
                    this::readyForDownloads);
        }
        // The IP disclosure stays on the page; the longer explanation opens on request.
        LinearLayout privacy = stableRows.row(connection, "privacy");
        stableRows.text(
                privacy,
                PatchStrings.t("Direct connections reveal your IP address to the paired phone."),
                13);
        stableRows.action(
                stableRows.actions(privacy),
                PatchStrings.t("Privacy and routes"),
                PatchStrings.t("Privacy and routes"),
                false,
                this::showPrivacy);
        stableRows.end(connection);
    }

    private String statusLine(Snapshot snapshot) {
        if (snapshot == null)
            return PatchStrings.t("Loading…") + (actionStatus.isEmpty() ? "" : "\n" + actionStatus);
        boolean stopped = stoppedByRequest && !snapshot.running;
        String main =
                stopped
                        ? PatchStrings.t(
                                "Peer connections are stopped. Resume paused jobs explicitly.")
                        : PatchStrings.t(snapshot.coordinatorStatus);
        String runtime =
                stopped || snapshot.runtimeStatus.isEmpty()
                        ? ""
                        : "\n" + PatchStrings.t(snapshot.runtimeStatus);
        return main + runtime + (actionStatus.isEmpty() ? "" : "\n" + actionStatus);
    }

    private void renderOffers(Snapshot snapshot) {
        stableRows.begin(incoming);
        if (snapshot.offers.isEmpty())
            stableRows.text(
                    stableRows.row(incoming, "empty"), PatchStrings.t("No pending offers."), 14);
        for (OfferRow offer : snapshot.offers) {
            LinearLayout entry = stableRows.row(incoming, offer.session);
            stableRows.text(entry, offer.name, 16);
            stableRows.text(entry, offer.detail, 13);
            LinearLayout strip = stableRows.actions(entry);
            stableRows.action(
                    strip,
                    PatchStrings.t("Accept download"),
                    PatchStrings.t("Accept download") + ", " + offer.name,
                    true,
                    () -> {
                        if (readyForDownloads())
                            command("Requesting this download", () -> open().accept(offer.session));
                    });
            stableRows.action(
                    strip,
                    PatchStrings.t("Decline"),
                    PatchStrings.t("Decline") + ", " + offer.name,
                    false,
                    () -> command("Declining this offer", () -> open().decline(offer.session)));
        }
        stableRows.end(incoming);
    }

    private void renderJobs(Snapshot snapshot) {
        stableRows.begin(jobs);
        if (snapshot.jobs.isEmpty())
            stableRows.text(
                    stableRows.row(jobs, "empty"), PatchStrings.t("No queued downloads."), 14);
        for (JobRow job : snapshot.jobs) {
            LinearLayout entry = stableRows.row(jobs, job.id);
            stableRows.text(entry, job.name, 16);
            stableRows.text(entry, job.detail, 13);
            if (job.active) stableRows.progress(entry, job.done, job.total);
            if (!job.pausable && !job.resumable && !job.cancellable) continue;
            LinearLayout strip = stableRows.actions(entry);
            if (job.pausable)
                stableRows.action(
                        strip,
                        PatchStrings.t("Pause"),
                        PatchStrings.t("Pause") + ", " + job.name,
                        false,
                        () -> command("Pausing download", () -> downloads().pause(job.id)));
            else if (job.resumable)
                stableRows.action(
                        strip,
                        PatchStrings.t("Resume"),
                        PatchStrings.t("Resume") + ", " + job.name,
                        false,
                        () -> command("Resuming download", () -> downloads().resume(job.id)));
            if (job.cancellable)
                stableRows.overflow(
                        strip, PatchStrings.f("Actions for {0}", job.name), () -> jobMenu(job));
        }
        stableRows.end(jobs);
    }

    private void renderShares(Snapshot snapshot) {
        boolean paired = !snapshot.peers.isEmpty();
        visible(recipient, paired);
        visible(shareButton, paired);
        visible(peerPicker, snapshot.peers.size() > 1);
        String note =
                PatchStrings.t(
                        paired
                                ? "Only selected files are copied into private sharing storage."
                                        + " Revoke a shared file to stop serving it. Recipients"
                                        + " must approve each offer within two minutes."
                                : "Pair a peer before sharing files.");
        if (!note.contentEquals(shareNote.getText())) shareNote.setText(note);
        // Offer the only saved key, once; sharing still requires choosing files explicitly.
        if (!recipientSuggested && snapshot.peers.size() == 1 && recipient.length() == 0) {
            recipient.setText(snapshot.peers.get(0));
            recipientSuggested = true;
        }
        stableRows.begin(shared);
        if (snapshot.shares.isEmpty())
            stableRows.text(
                    stableRows.row(shared, "empty"), PatchStrings.t("No files are shared."), 14);
        for (ShareRow row : snapshot.shares) {
            LinearLayout entry = stableRows.row(shared, row.share.fileId);
            stableRows.text(entry, row.share.name, 16);
            stableRows.text(entry, row.detail, 13);
            stableRows.overflow(
                    stableRows.actions(entry),
                    PatchStrings.f("Actions for {0}", row.share.name),
                    () -> shareMenu(row.share));
        }
        stableRows.end(shared);
    }

    private void renderSelected(Snapshot snapshot) {
        boolean any = !snapshot.selected.isEmpty();
        visible(selectedHeading, any);
        visible(selected, any);
        stableRows.begin(selected);
        for (SelectedRow item : snapshot.selected) {
            LinearLayout entry = stableRows.row(selected, item.reference);
            stableRows.text(entry, item.summary, 14);
            if (item.cancellable)
                stableRows.action(
                        stableRows.actions(entry),
                        PatchStrings.t("Cancel"),
                        PatchStrings.t("Cancel") + ", " + item.name,
                        false,
                        () ->
                                confirm(
                                        PatchStrings.t("Cancel this download?"),
                                        item.name,
                                        PatchStrings.t("Cancel download"),
                                        () ->
                                                command(
                                                        "Stopping this download",
                                                        () ->
                                                                SoulseekSelectedDownloads.cancel(
                                                                        item.reference))));
        }
        stableRows.end(selected);
    }

    private static void visible(View view, boolean shown) {
        int wanted = shown ? View.VISIBLE : View.GONE;
        if (view.getVisibility() != wanted) view.setVisibility(wanted);
    }

    /** Setup opens only from an action that needs it, never because the page came back. */
    private boolean readyForDownloads() {
        return DownloadDestinations.ensureConfigured(this)
                && AudioFormatPreferences.ensureConfigured(this);
    }

    private void chooseFiles() {
        if (latest == null || !readyForDownloads()) return;
        try {
            List<String> peers = NostrConfig.peers(recipient.getText().toString());
            if (peers.size() != 1 || !latest.peers.contains(peers.get(0))) throw new IOException();
        } catch (IOException invalid) {
            note("Enter one peer's app key saved in Connection keys and relays.");
            return;
        }
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.setType("audio/*");
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(picker, PICK_AUDIO);
    }

    private void choosePeer() {
        if (latest == null || latest.peers.isEmpty()) return;
        List<String> peers = latest.peers;
        String[] labels = new String[peers.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = PeerDownloadRows.shortKey(peers.get(i));
        show(
                new AlertDialog.Builder(this)
                        .setTitle(PatchStrings.t("Choose saved peer"))
                        .setItems(labels, (dialog, at) -> recipient.setText(peers.get(at)))
                        .setNegativeButton(PatchStrings.t("Cancel"), null)
                        .create());
    }

    private void pageMenu() {
        menu(
                PatchStrings.t("Peer downloads"),
                PatchStrings.t("Connection, storage and related download settings."),
                new String[] {
                    PatchStrings.t("Connection keys and relays"),
                    PatchStrings.t("Where copies go"),
                    PatchStrings.t("Formats, best first"),
                    PatchStrings.t("Debrid services and API keys"),
                    PatchStrings.t("Debrid link downloads")
                },
                new Runnable[] {
                    () -> startActivity(new Intent(this, NostrSetupActivity.class)),
                    () -> DownloadDestinations.openSettings(this),
                    () -> AudioFormatPreferences.openSettings(this),
                    () -> startActivity(new Intent(this, DebridSetupActivity.class)),
                    () -> startActivity(new Intent(this, DebridDownloadsActivity.class))
                });
    }

    private void showPrivacy() {
        remember(
                downloadStyle.detailsPanel(
                        PatchStrings.t("Privacy and routes"),
                        PatchStrings.t(
                                        "Share selected audio files with a saved peer's app key."
                                                + " Downloads try a direct encrypted UDP"
                                                + " connection, then available I2P, Tor and IPFS"
                                                + " routes. These files are downloaded completely"
                                                + " before playback.")
                                + "\n\n"
                                + PatchStrings.t(
                                        "Direct connections reveal your IP address to the paired"
                                                + " phone. Your Telegram phone number and personal"
                                                + " account are not used for coordination. Public"
                                                + " STUN services and Nostr relays are used to"
                                                + " establish connections."),
                        new LinearLayout(this)));
    }

    private void jobMenu(JobRow job) {
        String stop =
                job.pendingTelegram
                        ? PatchStrings.t("Pause / check receipt")
                        : PatchStrings.t("Cancel");
        menu(
                job.name,
                job.detail,
                new String[] {stop},
                new Runnable[] {
                    () ->
                            confirm(
                                    job.pendingTelegram
                                            ? PatchStrings.t("Pause / check receipt")
                                            : PatchStrings.t("Cancel this download?"),
                                    job.name,
                                    job.pendingTelegram ? stop : PatchStrings.t("Cancel download"),
                                    () ->
                                            command(
                                                    "Stopping this download",
                                                    () -> downloads().cancel(job.id)))
                });
    }

    private void shareMenu(BulkDownloads.Share share) {
        menu(
                share.name,
                bytes(share.size),
                new String[] {
                    PatchStrings.t("Send this offer again"), PatchStrings.t("Revoke sharing")
                },
                new Runnable[] {
                    () -> {
                        String peer = recipient.getText().toString().trim();
                        action("Sending file offer", () -> open().sendOffer(share, peer));
                    },
                    () ->
                            confirm(
                                    PatchStrings.t("Revoke sharing?"),
                                    share.name,
                                    PatchStrings.t("Revoke sharing"),
                                    () ->
                                            command(
                                                    "Revoking shared file",
                                                    () -> open().revoke(share.fileId)))
                });
    }

    /** Only peer work stops here: Soulseek, debrid and archive downloads keep their service. */
    private void confirmStop() {
        confirm(
                PatchStrings.t("Stop peer connections?"),
                PatchStrings.t(
                        "Peer downloads pause and shared files stop being served. Other"
                                + " downloads keep running."),
                PatchStrings.t("Stop peer connections"),
                () ->
                        command(
                                "Stopping connections",
                                () -> {
                                    open().disablePeers();
                                    stoppedByRequest = true;
                                }));
    }

    private void menu(String title, String message, String[] labels, Runnable[] actions) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        Dialog[] panel = new Dialog[1];
        for (int i = 0; i < labels.length; i++) {
            Runnable action = actions[i];
            Button item = new Button(this);
            item.setText(labels[i]);
            list.addView(item, new LinearLayout.LayoutParams(-1, -2));
            downloadStyle.textAction(item);
            item.setOnClickListener(
                    view -> {
                        panel[0].dismiss();
                        action.run();
                    });
        }
        panel[0] = downloadStyle.detailsPanel(title, message, list);
        remember(panel[0]);
    }

    private void confirm(String title, String message, String label, Runnable action) {
        show(
                new AlertDialog.Builder(this)
                        .setTitle(title)
                        .setMessage(message)
                        .setNegativeButton(PatchStrings.t("Keep"), null)
                        .setPositiveButton(label, (dialog, which) -> action.run())
                        .create());
    }

    private void show(AlertDialog dialog) {
        dialog.show();
        remember(dialog);
    }

    private void remember(Dialog dialog) {
        if (openDialog != null && openDialog != dialog && openDialog.isShowing())
            openDialog.dismiss();
        openDialog = dialog;
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_AUDIO || result != RESULT_OK || data == null) return;
        final String peer = recipient.getText().toString().trim();
        List<Uri> uris = new ArrayList<>();
        ClipData items = data.getClipData();
        if (items != null) {
            for (int i = 0; i < Math.min(items.getItemCount(), 16); i++)
                uris.add(items.getItemAt(i).getUri());
        } else if (data.getData() != null) uris.add(data.getData());
        for (Uri uri : uris) {
            action(
                    "Preparing selected audio",
                    () -> {
                        BulkCoordinator current = open();
                        BulkDownloads.Share share =
                                current.downloads().stage(uri, displayName(uri));
                        current.sendOffer(share, peer);
                    });
        }
        if (items != null && items.getItemCount() > 16)
            note("Selected the first 16 files. Share the remaining files in another batch.");
    }

    private String displayName(Uri uri) throws Exception {
        if (!"content".equals(uri.getScheme())) throw new IOException("Choose a document file.");
        try (Cursor cursor =
                getContentResolver()
                        .query(
                                uri,
                                new String[] {OpenableColumns.DISPLAY_NAME},
                                null,
                                null,
                                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) return name;
            }
        }
        throw new IOException("The document has no audio filename.");
    }

    private BulkDownloads downloads() throws IOException {
        return open().downloads();
    }

    private interface Action {
        void run() throws Exception;
    }

    private void action(String label, Action action) {
        submit(worker, label, action);
    }

    private void command(String label, Action action) {
        submit(commands, label, action);
    }

    private void submit(ThreadPoolExecutor executor, String label, Action action) {
        actionStatus = PatchStrings.t(label) + "…";
        try {
            executor.execute(
                    () -> {
                        try {
                            action.run();
                            actionStatus = PatchStrings.f("{0} completed.", PatchStrings.t(label));
                        } catch (Exception | LinkageError failed) {
                            actionStatus =
                                    PatchStrings.f(
                                            "{0} could not finish. Check setup, storage and the"
                                                    + " paired phone, then retry.",
                                            PatchStrings.t(label));
                        }
                        peersStale = true;
                        ui.post(() -> refresher.request());
                    });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            actionStatus = PatchStrings.t("Too many pending actions. Wait for the current batch.");
        }
        refreshStatus();
    }

    private void note(String message) {
        actionStatus = PatchStrings.t(message);
        refreshStatus();
    }

    /** Shows the action state at once; the next snapshot supplies everything else. */
    private void refreshStatus() {
        setStatus(statusLine(latest));
        refresher.request();
    }

    private void setStatus(String value) {
        if (status != null && !value.contentEquals(status.getText())) status.setText(value);
    }

    private static String bytes(long value) {
        return String.format(java.util.Locale.ROOT, "%.1f MiB", value / 1048576.0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        peersStale = true;
        refresher.resume();
    }

    @Override
    protected void onPause() {
        refresher.pause();
        anchor.release();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        refresher.pause();
        ui.removeCallbacksAndMessages(null);
        if (openDialog != null && openDialog.isShowing()) openDialog.dismiss();
        snapshots.shutdownNow();
        worker.shutdown();
        commands.shutdown();
        super.onDestroy();
    }
}

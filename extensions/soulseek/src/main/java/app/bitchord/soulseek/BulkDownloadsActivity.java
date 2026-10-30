package app.bitchord.soulseek;

import android.content.ClipData;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Explicit file sharing and resumable downloads between paired app identities. */
public final class BulkDownloadsActivity extends DownloadActivity {
    private static final int PICK_AUDIO = 4701;
    private BulkCoordinator coordinator;
    private BulkDownloads downloads;
    private EditText recipient;
    private TextView status;
    private LinearLayout incoming, shared, jobs;
    private boolean destinationSetupAsked, formatSetupAsked;
    private volatile String actionStatus = "";
    private final Handler ui = new Handler(Looper.getMainLooper());
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
    private final Runnable refresh =
            new Runnable() {
                @Override
                public void run() {
                    render();
                    ui.postDelayed(this, 1500);
                }
            };

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        CapturePolicy.apply(this);
        if (Build.VERSION.SDK_INT >= 31) getWindow().setHideOverlayWindows(true);
        try {
            coordinator = BulkCoordinator.get(this);
            downloads = BulkDownloads.get(this);
        } catch (Exception | LinkageError unavailable) {
            TextView error = new TextView(this);
            error.setText(
                    PatchStrings.t(
                            "Download storage could not be opened. Restart BitChord and try"
                                    + " again."));
            setContentView(error);
            return;
        }
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        body.setPadding(padding, padding, padding, padding);
        scroll.addView(body);
        setContentView(scroll);
        button(body, PatchStrings.t("Back"), () -> finish());
        text(body, PatchStrings.t("Peer downloads"), 24);
        text(
                body,
                PatchStrings.t(
                        "Share selected audio files with a saved peer's app key. Downloads try a"
                            + " direct encrypted UDP connection, then available I2P, Tor and IPFS"
                            + " routes. These files are downloaded completely before playback."),
                16);
        text(
                body,
                PatchStrings.t(
                        "Direct connections reveal your IP address to the paired phone. Your"
                                + " Telegram phone number and personal account are not used for"
                                + " coordination. Public STUN services and Nostr relays are used to"
                                + " establish connections."),
                14);
        button(
                body,
                PatchStrings.t("Connection keys and relays"),
                () -> startActivity(new Intent(this, NostrSetupActivity.class)));
        button(
                body,
                PatchStrings.t("Where copies go"),
                () -> DownloadDestinations.openSettings(this));
        button(
                body,
                PatchStrings.t("Formats, best first"),
                () -> AudioFormatPreferences.openSettings(this));
        button(
                body,
                PatchStrings.t("Debrid services and API keys"),
                () -> startActivity(new Intent(this, DebridSetupActivity.class)));
        button(
                body,
                PatchStrings.t("Debrid link downloads"),
                () -> startActivity(new Intent(this, DebridDownloadsActivity.class)));
        button(
                body,
                PatchStrings.t("Start download connections"),
                () -> command("Starting connections", () -> coordinator.start()));
        button(
                body,
                PatchStrings.t("Stop connections and pause downloads"),
                () ->
                        command(
                                "Stopping connections",
                                () -> {
                                    SoulseekSelectedDownloads.cancelAll();
                                    try {
                                        coordinator.stop();
                                    } finally {
                                        BulkTransferService.requestStop(getApplicationContext());
                                    }
                                }));
        status = text(body, "", 16);
        text(body, PatchStrings.t("Share files"), 20);
        recipient = new EditText(this);
        recipient.setHint(PatchStrings.t("Saved peer's 64-character app public key"));
        recipient.setTextDirection(View.TEXT_DIRECTION_LTR);
        recipient.setSingleLine(true);
        recipient.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        recipient.setSaveEnabled(false);
        if (Build.VERSION.SDK_INT >= 26)
            recipient.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        body.addView(recipient);
        button(
                body,
                PatchStrings.t("Choose audio files to share"),
                () -> {
                    if (!readyForDownloads()) return;
                    try {
                        List<String> peers = NostrConfig.peers(recipient.getText().toString());
                        if (peers.size() != 1) throw new java.io.IOException();
                        if (!NostrConfig.peers(NostrCoordinator.get(this).peersText())
                                .contains(peers.get(0))) throw new java.io.IOException();
                    } catch (Exception invalid) {
                        actionStatus =
                                "Enter one peer's app key saved in Connection keys and relays.";
                        render();
                        return;
                    }
                    Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    picker.setType("audio/*");
                    picker.addCategory(Intent.CATEGORY_OPENABLE);
                    picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivityForResult(picker, PICK_AUDIO);
                });
        text(
                body,
                PatchStrings.t(
                        "Only selected files are copied into private sharing storage. Revoke a"
                            + " shared file to stop serving it. Recipients must approve each offer"
                            + " within two minutes."),
                14);
        shared = group(body);
        text(body, PatchStrings.t("Incoming offers"), 20);
        incoming = group(body);
        text(body, PatchStrings.t("Download queue"), 20);
        jobs = group(body);
        render();
    }

    private boolean readyForDownloads() {
        return DownloadDestinations.ensureConfigured(this)
                && AudioFormatPreferences.ensureConfigured(this);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_AUDIO || result != RESULT_OK || data == null || coordinator == null)
            return;
        final String peer = recipient.getText().toString().trim();
        List<Uri> selected = new ArrayList<>();
        ClipData items = data.getClipData();
        if (items != null) {
            for (int i = 0; i < Math.min(items.getItemCount(), 16); i++)
                selected.add(items.getItemAt(i).getUri());
        } else if (data.getData() != null) selected.add(data.getData());
        for (Uri uri : selected) {
            action(
                    "Preparing selected audio",
                    () -> {
                        BulkDownloads.Share share = downloads.stage(uri, displayName(uri));
                        coordinator.sendOffer(share, peer);
                    });
        }
        if (items != null && items.getItemCount() > 16)
            actionStatus =
                    "Selected the first 16 files. Share the remaining files in another batch.";
    }

    private String displayName(Uri uri) throws Exception {
        if (!"content".equals(uri.getScheme()))
            throw new java.io.IOException("Choose a document file.");
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
        throw new java.io.IOException("The document has no audio filename.");
    }

    private void render() {
        if (coordinator == null || status == null || isFinishing()) return;
        downloads = coordinator.downloads();
        String nativeStatus = downloads.status();
        String selectedStatus = SoulseekSelectedDownloads.status();
        status.setText(
                PatchStrings.t(coordinator.status())
                        + (nativeStatus.isEmpty() ? "" : "\n" + PatchStrings.t(nativeStatus))
                        + (selectedStatus.isEmpty() ? "" : "\n" + PatchStrings.t(selectedStatus))
                        + (actionStatus.isEmpty() ? "" : "\n" + PatchStrings.t(actionStatus)));
        incoming.removeAllViews();
        List<BulkCoordinator.Incoming> offers = coordinator.offers();
        if (offers.isEmpty()) text(incoming, PatchStrings.t("No pending offers."), 14);
        for (BulkCoordinator.Incoming offer : offers) {
            text(
                    incoming,
                    offer.name
                            + " · "
                            + bytes(offer.size)
                            + "\n"
                            + PatchStrings.f("From {0}", shortKey(offer.peer))
                            + " · "
                            + PatchStrings.state(offer.state),
                    16);
            button(
                    incoming,
                    PatchStrings.t("Accept download"),
                    () -> {
                        if (readyForDownloads())
                            command(
                                    "Requesting this download",
                                    () -> coordinator.accept(offer.session));
                    });
            button(
                    incoming,
                    PatchStrings.t("Decline"),
                    () -> {
                        coordinator.decline(offer.session);
                        render();
                    });
        }
        shared.removeAllViews();
        List<BulkDownloads.Share> shares = downloads.shares();
        if (shares.isEmpty()) text(shared, PatchStrings.t("No files are shared."), 14);
        for (BulkDownloads.Share share : shares) {
            text(shared, share.name + " · " + bytes(share.size), 16);
            button(
                    shared,
                    PatchStrings.t("Send this offer again"),
                    () -> {
                        String peer = recipient.getText().toString().trim();
                        action("Sending file offer", () -> coordinator.sendOffer(share, peer));
                    });
            button(
                    shared,
                    PatchStrings.t("Revoke sharing"),
                    () -> command("Revoking shared file", () -> coordinator.revoke(share.fileId)));
        }
        jobs.removeAllViews();
        List<BulkJobStore.Job> downloadsNow = downloads.jobs();
        if (downloadsNow.isEmpty()) text(jobs, PatchStrings.t("No queued downloads."), 14);
        for (BulkJobStore.Job job : downloadsNow) {
            text(
                    jobs,
                    job.offer.name
                            + " · "
                            + PatchStrings.state(job.state)
                            + "\n"
                            + bytes(job.verifiedBytes())
                            + " / "
                            + bytes(job.offer.size)
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
                            + (job.message.isEmpty() ? "" : "\n" + PatchStrings.t(job.message)),
                    16);
            if (job.state == BulkJobStore.State.RUNNING || job.state == BulkJobStore.State.QUEUED)
                button(
                        jobs,
                        PatchStrings.t("Pause"),
                        () -> command("Pausing download", () -> downloads.pause(job.id)));
            else if (job.state == BulkJobStore.State.PAUSED
                    || job.state == BulkJobStore.State.FAILED
                    || job.state == BulkJobStore.State.VERIFIED)
                button(
                        jobs,
                        PatchStrings.t("Resume"),
                        () -> command("Resuming download", () -> downloads.resume(job.id)));
            boolean pendingTelegram =
                    "uploading".equals(job.telegramStatus)
                            || "receipt-check-required".equals(job.telegramStatus);
            if (pendingTelegram)
                text(
                        jobs,
                        PatchStrings.t(
                                "Telegram may finish sending after a pause. Resume checks the"
                                        + " existing receipt."),
                        14);
            if (job.state != BulkJobStore.State.COMPLETE
                    && job.state != BulkJobStore.State.CANCELLED)
                button(
                        jobs,
                        pendingTelegram
                                ? PatchStrings.t("Pause / check receipt")
                                : PatchStrings.t("Cancel"),
                        () -> command("Stopping this download", () -> downloads.cancel(job.id)));
        }
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
                        ui.post(() -> render());
                    });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            actionStatus = "Too many pending actions. Wait for the current batch.";
        }
        render();
    }

    private static String shortKey(String key) {
        return key.substring(0, 12) + "…";
    }

    private static String bytes(long value) {
        return String.format(java.util.Locale.ROOT, "%.1f MiB", value / 1048576.0);
    }

    private LinearLayout group(LinearLayout parent) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        if (downloadStyle != null) downloadStyle.card(group);
        parent.addView(group);
        return group;
    }

    private TextView text(LinearLayout parent, String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setPadding(0, 10, 0, 10);
        parent.addView(view);
        return view;
    }

    private void button(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(view -> action.run());
        parent.addView(button);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refresh);
        if (!destinationSetupAsked) {
            destinationSetupAsked = true;
            if (!DownloadDestinations.ensureConfigured(this)) return;
        }
        if (!formatSetupAsked) {
            try {
                DownloadDestinations.snapshot(this);
            } catch (Exception unconfigured) {
                return;
            }
            formatSetupAsked = true;
            AudioFormatPreferences.ensureConfigured(this);
        }
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
        commands.shutdown();
        super.onDestroy();
    }
}

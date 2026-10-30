package app.bitchord.soulseek;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.*;

import app.bitchord.telegram.PatchStrings;

import org.json.*;

import java.util.*;
import java.util.concurrent.*;

/** Overview of actual host downloads and the existing destination journals. */
final class DownloadsHubPage extends DownloadPage {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread thread = new Thread(r, "downloads-hub");
                        thread.setDaemon(true);
                        return thread;
                    });
    private LinearLayout rows;
    private TextView status;
    private String filter = "All", last = "";
    private volatile boolean foreground;
    private final Runnable refresh = () -> snapshot();

    DownloadsHubPage(android.app.Activity activity, Intent intent) {
        super(activity, intent);
    }

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        HostDownloadAccess.init(getApplicationContext());
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        text(body, PatchStrings.t("Downloads"), 28);
        text(
                body,
                PatchStrings.t("Follow downloads, saved files and confirmed destination copies."),
                15);
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        LinearLayout filters = new LinearLayout(this);
        scroll.addView(filters);
        body.addView(scroll);
        for (String label :
                new String[] {"All", "Active", "Queue", "On device", "Sharing", "Telegram"})
            button(
                    filters,
                    label,
                    () -> {
                        filter = label;
                        last = "";
                        snapshot();
                    });
        status = text(body, PatchStrings.t("Loading downloads…"), 14);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        body.addView(rows);
        button(
                body,
                PatchStrings.t("Find available downloads"),
                () -> startActivity(new Intent(this, DownloadExplorerActivity.class)));
        button(
                body,
                PatchStrings.t("Copy destinations and Telegram setup"),
                () -> DownloadDestinations.openSettings(activity));
        button(
                body,
                PatchStrings.t("Peer sharing and transfer controls"),
                () -> startActivity(new Intent(this, BulkDownloadsActivity.class)));
        button(
                body,
                PatchStrings.t("Debrid link downloads"),
                () -> startActivity(new Intent(this, DebridDownloadsActivity.class)));
        button(body, PatchStrings.t("Public upload slots"), this::slotsDialog);
        button(
                body,
                PatchStrings.t("Rescan public sharing inventory"),
                () -> command(() -> PublicSoulseekShares.reconcile(getApplicationContext())));
        setContentView(body);
    }

    @Override
    protected void onStart() {
        super.onStart();
        foreground = true;
        snapshot();
    }

    @Override
    protected void onStop() {
        foreground = false;
        ui.removeCallbacks(refresh);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private static final class Row {
        final String name, detail, group;
        final Runnable action;
        final String actionName;
        String savedUri = "", hostId = "";

        Row(String name, String detail, String group, String actionName, Runnable action) {
            this.name = name;
            this.detail = detail;
            this.group = group;
            this.actionName = actionName;
            this.action = action;
        }
    }

    private void snapshot() {
        if (!foreground) return;
        ui.removeCallbacks(refresh);
        try {
            worker.execute(
                    () -> {
                        List<Row> data = new ArrayList<>();
                        String warning = "";
                        try {
                            Map<?, ?> active = HostDownloadAccess.state("active");
                            for (Map.Entry<?, ?> entry : active.entrySet()) {
                                String id = String.valueOf(entry.getKey());
                                Object state = entry.getValue();
                                String kind = state.getClass().getSimpleName();
                                String detail = kind;
                                if (kind.equals("Running")) {
                                    try {
                                        detail =
                                                PatchStrings.f(
                                                        "Downloading · {0}%",
                                                        Math.round(
                                                                ((Number)
                                                                                        HostDownloadAccess
                                                                                                .propertyObject(
                                                                                                        state,
                                                                                                        "fraction"))
                                                                                .floatValue()
                                                                        * 100));
                                    } catch (Exception ignored) {
                                    }
                                } else if (kind.equals("Failed"))
                                    detail = HostDownloadAccess.propertyText(state, "reason");
                                String source = "BitChord";
                                if (id.startsWith("src:")) {
                                    int separator = id.indexOf("::");
                                    if (separator > 4) {
                                        Object config =
                                                HostDownloadAccess.registry(
                                                        "config", id.substring(4, separator));
                                        if (config != null)
                                            source =
                                                    String.valueOf(
                                                            HostDownloadAccess.call(
                                                                    config, "getDisplayName"));
                                    }
                                }
                                final String reference = id;
                                String title =
                                        HostDownloadAccess.selectedTitle(
                                                getApplicationContext(), id);
                                if (title.isEmpty()) title = id;
                                data.add(
                                        new Row(
                                                title,
                                                source + " · " + detail,
                                                kind.equals("Queued") ? "Queue" : "Active",
                                                kind.equals("Running") || kind.equals("Queued")
                                                        ? "Cancel"
                                                        : null,
                                                () ->
                                                        command(
                                                                () ->
                                                                        HostDownloadAccess.cancel(
                                                                                reference))));
                            }
                            Map<?, ?> metadata = HostDownloadAccess.state("_savedMetadata");
                            for (Map.Entry<?, ?> entry :
                                    HostDownloadAccess.state("saved").entrySet()) {
                                String id = String.valueOf(entry.getKey()),
                                        uri = String.valueOf(entry.getValue());
                                String title =
                                        metadata.get(id) == null
                                                ? id
                                                : HostDownloadAccess.propertyText(
                                                        metadata.get(id), "title");
                                if (title.isEmpty()) title = id;
                                data.add(
                                        fileRow(
                                                title,
                                                PatchStrings.t("Saved by BitChord"),
                                                id,
                                                uri));
                            }
                        } catch (Exception | LinkageError unavailable) {
                            warning = PatchStrings.t("BitChord download state is unavailable.");
                        }
                        for (SoulseekSelectedDownloads.Progress progress :
                                SoulseekSelectedDownloads.snapshots()) {
                            if (progress.phase == SoulseekSelectedDownloads.Phase.COMPLETE)
                                continue;
                            String group =
                                    progress.phase == SoulseekSelectedDownloads.Phase.QUEUED
                                            ? "Queue"
                                            : progress.phase
                                                                    == SoulseekSelectedDownloads
                                                                            .Phase.COMPLETE
                                                            && progress.needsPhone
                                                    ? "On device"
                                                    : "Active";
                            data.add(
                                    new Row(
                                            progress.name,
                                            PatchStrings.t("Source: Soulseek")
                                                    + "\n"
                                                    + progress.summary(),
                                            group,
                                            progress.canCancel() ? "Cancel" : null,
                                            () ->
                                                    SoulseekSelectedDownloads.cancel(
                                                            progress.reference)));
                        }
                        BulkDownloads bulk = BulkDownloads.existing();
                        if (bulk == null)
                            try {
                                bulk = BulkDownloads.get(getApplicationContext());
                            } catch (java.io.IOException unavailable) {
                            }
                        if (bulk != null) {
                            for (BulkDownloads.Share share : bulk.shares()) {
                                if (share.revoked) continue;
                                data.add(
                                        new Row(
                                                share.name,
                                                PatchStrings.t(
                                                        "Paired-peer sharing · available to"
                                                                + " authorised recipients"),
                                                "Sharing",
                                                "Manage",
                                                () ->
                                                        startActivity(
                                                                new Intent(
                                                                        this,
                                                                        BulkDownloadsActivity
                                                                                .class))));
                            }
                            for (BulkJobStore.Job job : bulk.jobs()) {
                                if (!job.publishedUri.isEmpty()
                                        && "complete".equals(job.phoneStatus)) {
                                    final String uri = job.publishedUri;
                                    data.add(
                                            fileRow(
                                                    job.offer.name,
                                                    PatchStrings.t("Phone copy confirmed"),
                                                    "",
                                                    uri));
                                }
                                if (!job.telegramReceipt.isEmpty()
                                        && "complete".equals(job.telegramStatus))
                                    data.add(
                                            new Row(
                                                    job.offer.name,
                                                    PatchStrings.t("Telegram copy confirmed"),
                                                    "Telegram",
                                                    "Manage",
                                                    () ->
                                                            startActivity(
                                                                    new Intent(
                                                                            this,
                                                                            BulkDownloadsActivity
                                                                                    .class))));
                            }
                        }

                        try {
                            JSONObject sharing =
                                    PublicSoulseekShares.snapshot(getApplicationContext());
                            boolean enabled = sharing.optBoolean("enabled"),
                                    online = sharing.optBoolean("online");
                            data.add(
                                    new Row(
                                            PatchStrings.t("Public Soulseek sharing"),
                                            enabled
                                                    ? (online
                                                            ? PatchStrings.t(
                                                                    "Enabled · selected files are"
                                                                            + " public")
                                                            : PatchStrings.t(
                                                                    "Enabled · waiting for Soulseek"
                                                                            + " connection"))
                                                    : PatchStrings.t(
                                                            "Paused · files are not advertised"),
                                            "Sharing",
                                            enabled
                                                    ? PatchStrings.t("Pause sharing")
                                                    : PatchStrings.t("Enable sharing"),
                                            () -> sharingDialog(enabled)));
                            JSONArray inventory = sharing.getJSONArray("inventory");
                            for (int i = 0; i < inventory.length(); i++) {
                                JSONObject item = inventory.getJSONObject(i);
                                String shareId = item.getString("id");
                                data.add(
                                        new Row(
                                                item.getString("name"),
                                                PatchStrings.f(
                                                        "Public sharing copy · {0} bytes",
                                                        item.getLong("bytes")),
                                                "Sharing",
                                                PatchStrings.t("Stop sharing this file"),
                                                () ->
                                                        command(
                                                                () ->
                                                                        PublicSoulseekShares.remove(
                                                                                getApplicationContext(),
                                                                                shareId))));
                            }
                            JSONObject live = sharing.optJSONObject("live");
                            JSONArray uploads =
                                    live == null ? new JSONArray() : live.getJSONArray("uploads");
                            for (int i = 0; i < uploads.length(); i++) {
                                JSONObject upload = uploads.getJSONObject(i);
                                String peer = upload.getString("peer"),
                                        path = upload.getString("path"),
                                        phase = upload.getString("phase");
                                data.add(
                                        new Row(
                                                path,
                                                peer
                                                        + " · "
                                                        + phase
                                                        + " · "
                                                        + upload.getLong("sent")
                                                        + " / "
                                                        + upload.getLong("size")
                                                        + " · "
                                                        + Math.round(upload.optDouble("speed"))
                                                        + " B/s",
                                                "Sharing",
                                                phase.equals("uploading")
                                                        ? PatchStrings.t("Cancel upload")
                                                        : null,
                                                () ->
                                                        command(
                                                                () ->
                                                                        SoulseekNative.cancelUpload(
                                                                                SoulseekBridge.get()
                                                                                        .ready(),
                                                                                peer,
                                                                                path))));
                            }
                        } catch (Exception unavailable) {
                            data.add(
                                    new Row(
                                            PatchStrings.t("Public Soulseek sharing"),
                                            PatchStrings.t(
                                                    "Connect Soulseek to manage public uploads."),
                                            "Sharing",
                                            "Soulseek account and setup",
                                            () ->
                                                    startActivity(
                                                            new Intent(
                                                                    this,
                                                                    SoulseekActivity.class))));
                        }
                        try {
                            JSONArray archives = DownloadArchives.snapshot(getApplicationContext());
                            for (int i = 0; i < archives.length(); i++) {
                                JSONObject archive = archives.getJSONObject(i);
                                String archiveId = archive.getString("id"),
                                        phase = archive.getString("phase");
                                String detail =
                                        archiveStatus(phase)
                                                + " · "
                                                + PatchStrings.t(archive.optString("error"));
                                if (archive.optBoolean("localRemoved"))
                                    detail += " · " + PatchStrings.t("Local copy removed");
                                Row row =
                                        new Row(
                                                archive.getString("title"),
                                                detail,
                                                "Telegram",
                                                PatchStrings.t("Archive actions"),
                                                () -> archiveActions(archiveId));
                                data.add(row);
                            }
                        } catch (Exception unavailable) {
                            data.add(
                                    new Row(
                                            PatchStrings.t("Telegram archives"),
                                            PatchStrings.t("Archive journal could not be read."),
                                            "Telegram",
                                            null,
                                            null));
                        }
                        final String notice = warning;
                        ui.post(
                                () -> {
                                    if (!foreground || isFinishing()) return;
                                    StringBuilder signature =
                                            new StringBuilder(filter).append(notice);
                                    for (Row row : data)
                                        signature
                                                .append(row.group)
                                                .append(row.name)
                                                .append(row.detail);
                                    if (!last.equals(signature.toString())) {
                                        last = signature.toString();
                                        rows.removeAllViews();
                                        int visible = 0;
                                        for (Row row : data) {
                                            if (!filter.equals("All") && !filter.equals(row.group))
                                                continue;
                                            if (visible++ >= 100) break;
                                            LinearLayout card = new LinearLayout(this);
                                            card.setOrientation(LinearLayout.VERTICAL);
                                            downloadStyle.card(card);
                                            rows.addView(card);
                                            text(card, row.name, 18);
                                            text(card, row.detail, 14);
                                            if (row.actionName != null)
                                                button(card, row.actionName, row.action);
                                            if (!row.savedUri.isEmpty()) {
                                                button(
                                                        card,
                                                        PatchStrings.t("Offload to Telegram"),
                                                        () -> offloadDialog(row));
                                                button(
                                                        card,
                                                        PatchStrings.t(
                                                                "Share publicly on Soulseek"),
                                                        () -> shareFileDialog(row));
                                            }
                                        }
                                        if (visible == 0)
                                            text(
                                                    rows,
                                                    PatchStrings.t("No items in this view."),
                                                    16);
                                        status.setText(
                                                notice.isEmpty()
                                                        ? PatchStrings.f(
                                                                "Showing {0} items",
                                                                Math.min(visible, 100))
                                                        : notice);
                                    }
                                    ui.postDelayed(refresh, 1500);
                                });
                    });
        } catch (RejectedExecutionException stopped) {
        }
    }

    private String archiveStatus(String phase) {
        switch (phase) {
            case "queued":
                return PatchStrings.t("Queued for Telegram");
            case "uploading":
                return PatchStrings.t("Uploading original file");
            case "verifying":
                return PatchStrings.t("Verifying Telegram read-back");
            case "removing":
                return PatchStrings.t("Removing local copy");
            case "restoring":
                return PatchStrings.t("Restoring original bytes");
            case "restore-submitted":
                return PatchStrings.t("Restore submitted · follow progress in Downloads");
            case "archived":
                return PatchStrings.t("Archive verified · local copy kept");
            case "offloaded":
                return PatchStrings.t("Offloaded to Telegram");
            case "paused":
                return PatchStrings.t("Archive paused · retry to continue");
            default:
                return PatchStrings.t("Archive action needs attention");
        }
    }

    private Row fileRow(String title, String detail, String id, String uri) {
        Row row =
                new Row(
                        title,
                        detail,
                        "On device",
                        "Open file",
                        () -> {
                            try {
                                startActivity(
                                        new Intent(Intent.ACTION_VIEW)
                                                .setDataAndType(Uri.parse(uri), "audio/*")
                                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                            } catch (Exception unavailable) {
                                status.setText(
                                        PatchStrings.t(
                                                "Saved file could not be opened. Check its storage"
                                                        + " location."));
                            }
                        });
        row.savedUri = uri;
        row.hostId = id;
        return row;
    }

    private void offloadDialog(Row row) {
        new AlertDialog.Builder(this)
                .setTitle(PatchStrings.t("Offload to Telegram"))
                .setMessage(
                        PatchStrings.t(
                                "Upload original bytes to your private main channel and download"
                                    + " them again to verify the hash. Your local file stays until"
                                    + " you choose Remove local copy in Archive actions. Public"
                                    + " sharing copies use separate space."))
                .setPositiveButton(
                        PatchStrings.t("Archive and verify"),
                        (dialog, which) ->
                                command(
                                        () ->
                                                DownloadArchives.archive(
                                                        getApplicationContext(),
                                                        row.hostId,
                                                        Uri.parse(row.savedUri),
                                                        row.name)))
                .setNegativeButton(PatchStrings.t("Cancel"), null)
                .show();
    }

    private void shareFileDialog(Row row) {
        new AlertDialog.Builder(this)
                .setTitle(PatchStrings.t("Share publicly on Soulseek"))
                .setMessage(
                        PatchStrings.t(
                                "Create an audio copy in the public sharing inventory. Other"
                                    + " Soulseek users can search and download it while sharing is"
                                    + " enabled. This copy uses phone storage."))
                .setPositiveButton(
                        PatchStrings.t("Add selected file"),
                        (dialog, which) ->
                                command(
                                        () ->
                                                PublicSoulseekShares.add(
                                                        getApplicationContext(),
                                                        Uri.parse(row.savedUri),
                                                        row.name)))
                .setNegativeButton(PatchStrings.t("Cancel"), null)
                .show();
    }

    private void sharingDialog(boolean enabled) {
        new AlertDialog.Builder(this)
                .setMessage(
                        enabled
                                ? PatchStrings.t("Pause public sharing and cancel upload grants?")
                                : PatchStrings.t(
                                        "Make only the files in your public sharing inventory"
                                                + " searchable and downloadable by other Soulseek"
                                                + " users?"))
                .setPositiveButton(
                        PatchStrings.t("Continue"),
                        (dialog, which) ->
                                command(
                                        () -> {
                                            JSONObject snapshot =
                                                    PublicSoulseekShares.snapshot(
                                                            getApplicationContext());
                                            PublicSoulseekShares.enable(
                                                    getApplicationContext(),
                                                    !enabled,
                                                    snapshot.getInt("slots"));
                                        }))
                .setNegativeButton(PatchStrings.t("Cancel"), null)
                .show();
    }

    private void slotsDialog() {
        new AlertDialog.Builder(this)
                .setTitle(PatchStrings.t("Public upload slots"))
                .setItems(
                        new String[] {"1", "2", "3", "4"},
                        (dialog, index) ->
                                command(
                                        () -> {
                                            JSONObject snapshot =
                                                    PublicSoulseekShares.snapshot(
                                                            getApplicationContext());
                                            PublicSoulseekShares.enable(
                                                    getApplicationContext(),
                                                    snapshot.optBoolean("enabled"),
                                                    index + 1);
                                        }))
                .show();
    }

    private void archiveActions(String id) {
        new AlertDialog.Builder(this)
                .setTitle(PatchStrings.t("Archive actions"))
                .setItems(
                        new String[] {
                            PatchStrings.t("Retry archive and verification"),
                            PatchStrings.t("Remove local copy"),
                            PatchStrings.t("Restore original to phone folder")
                        },
                        (dialog, index) -> {
                            if (index == 0)
                                command(() -> DownloadArchives.retry(getApplicationContext(), id));
                            else if (index == 2)
                                command(
                                        () ->
                                                DownloadArchives.restore(
                                                        getApplicationContext(), id));
                            else
                                new AlertDialog.Builder(this)
                                        .setMessage(
                                                PatchStrings.t(
                                                        "Verify the Telegram copy again, compare"
                                                            + " your local file, then remove only"
                                                            + " this local copy? Public sharing"
                                                            + " copies remain until you stop"
                                                            + " sharing them."))
                                        .setPositiveButton(
                                                PatchStrings.t("Verify and remove"),
                                                (confirm, which) ->
                                                        command(
                                                                () ->
                                                                        DownloadArchives
                                                                                .removeLocal(
                                                                                        getApplicationContext(),
                                                                                        id)))
                                        .setNegativeButton(PatchStrings.t("Cancel"), null)
                                        .show();
                        })
                .show();
    }

    interface Action {
        void run() throws Exception;
    }

    private void command(Action action) {
        try {
            worker.execute(
                    () -> {
                        try {
                            action.run();
                        } catch (Exception failed) {
                            ui.post(
                                    () ->
                                            status.setText(
                                                    PatchStrings.t(
                                                            "Download action failed. Try again.")));
                        }
                    });
        } catch (RejectedExecutionException stopped) {
        }
    }

    private TextView text(LinearLayout parent, String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        parent.addView(view);
        return view;
    }

    private void button(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(PatchStrings.t(label));
        button.setOnClickListener(v -> action.run());
        parent.addView(button);
    }
}

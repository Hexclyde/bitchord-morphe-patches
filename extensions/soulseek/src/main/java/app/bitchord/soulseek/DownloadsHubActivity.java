package app.bitchord.soulseek;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.*;

import app.bitchord.telegram.PatchStrings;

import java.util.*;
import java.util.concurrent.*;

/** Overview of actual host downloads and the existing destination journals. */
public final class DownloadsHubActivity extends DownloadActivity {
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
                () -> DownloadDestinations.openSettings(this));
        button(
                body,
                PatchStrings.t("Peer sharing and transfer controls"),
                () -> startActivity(new Intent(this, BulkDownloadsActivity.class)));
        button(
                body,
                PatchStrings.t("Debrid link downloads"),
                () -> startActivity(new Intent(this, DebridDownloadsActivity.class)));
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
                                        new Row(
                                                title,
                                                "Saved by BitChord",
                                                "On device",
                                                "Open file",
                                                () -> {
                                                    try {
                                                        startActivity(
                                                                new Intent(Intent.ACTION_VIEW)
                                                                        .setDataAndType(
                                                                                Uri.parse(uri),
                                                                                "audio/*")
                                                                        .addFlags(
                                                                                Intent
                                                                                        .FLAG_GRANT_READ_URI_PERMISSION));
                                                    } catch (Exception unavailable) {
                                                        status.setText(
                                                                PatchStrings.t(
                                                                        "Saved file could not be"
                                                                            + " opened. Check its"
                                                                            + " storage"
                                                                            + " location."));
                                                    }
                                                }));
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
                                            new Row(
                                                    job.offer.name,
                                                    PatchStrings.t("Phone copy confirmed"),
                                                    "On device",
                                                    "Open file",
                                                    () -> {
                                                        try {
                                                            startActivity(
                                                                    new Intent(Intent.ACTION_VIEW)
                                                                            .setDataAndType(
                                                                                    Uri.parse(uri),
                                                                                    "audio/*")
                                                                            .addFlags(
                                                                                    Intent
                                                                                            .FLAG_GRANT_READ_URI_PERMISSION));
                                                        } catch (Exception unavailable) {
                                                            status.setText(
                                                                    PatchStrings.t(
                                                                            "Saved file could not"
                                                                                + " be opened."
                                                                                + " Check its"
                                                                                + " storage"
                                                                                + " location."));
                                                        }
                                                    }));
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
                                        }
                                        if (visible == 0)
                                            text(
                                                    rows,
                                                    PatchStrings.t("No items in this view."),
                                                    16);
                                        if (filter.equals("Sharing"))
                                            text(
                                                    rows,
                                                    PatchStrings.t(
                                                            "This build shows paired-peer shares."
                                                                + " Public Soulseek uploads are not"
                                                                + " implemented yet."),
                                                    14);
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

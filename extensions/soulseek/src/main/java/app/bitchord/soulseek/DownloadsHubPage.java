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
    private ImageButton viewButton;
    private String viewMode = "comfy";
    private List<Row> currentRows = Collections.emptyList();
    static final String[] VIEW_IDS = {"grid", "list", "compact", "comfy", "detailed"};
    static final String[] VIEW_LABELS = {"Grid", "List", "Compact", "Comfy", "Detailed"};
    private TextView status;
    private String filter = "All", last = "";
    private final List<TextView> filterViews = new ArrayList<>();
    private final Map<String, Long> fileSizes = new HashMap<>();
    private volatile boolean foreground;
    private final Runnable refresh = () -> snapshot();

    DownloadsHubPage(android.app.Activity activity, Intent intent) {
        super(activity, intent);
    }

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        HostDownloadAccess.init(getApplicationContext());
        String savedView =
                getSharedPreferences("bitchord_download_ui", 0).getString("view", "comfy");
        viewMode = Arrays.asList(VIEW_IDS).contains(savedView) ? savedView : "comfy";
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView heading =
                downloadStyle.text(
                        PatchStrings.t("Downloads"),
                        28,
                        downloadStyle.foreground,
                        downloadStyle.heavy);
        heading.setGravity(android.view.Gravity.CENTER_VERTICAL);
        titleBar.addView(heading, new LinearLayout.LayoutParams(0, downloadStyle.dp(52), 1));
        ImageButton settings = downloadStyle.settingsButton();
        settings.setContentDescription(PatchStrings.t("Download settings"));
        settings.setOnClickListener(v -> settingsMenu());
        titleBar.addView(
                settings,
                new LinearLayout.LayoutParams(downloadStyle.dp(48), downloadStyle.dp(48)));
        body.addView(titleBar);
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        status =
                downloadStyle.text(
                        PatchStrings.t("Loading downloads…"),
                        13,
                        downloadStyle.secondary,
                        downloadStyle.regular);
        toolbar.addView(status, new LinearLayout.LayoutParams(0, downloadStyle.dp(48), 1));
        viewButton = new ImageButton(this);
        viewButton.setTag("download-view");
        viewButton.setBackground(downloadStyle.shape(downloadStyle.card, 12, false));
        viewButton.setPadding(
                downloadStyle.dp(12),
                downloadStyle.dp(12),
                downloadStyle.dp(12),
                downloadStyle.dp(12));
        downloadStyle.markCustom(viewButton);
        updateViewButton();
        viewButton.setOnClickListener(
                v -> {
                    int index = Arrays.asList(VIEW_IDS).indexOf(viewMode);
                    setViewMode(VIEW_IDS[(index + 1) % VIEW_IDS.length]);
                    viewButton.announceForAccessibility(viewLabel());
                });
        viewButton.setOnLongClickListener(
                v -> {
                    viewDialog();
                    return true;
                });
        toolbar.addView(
                viewButton,
                new LinearLayout.LayoutParams(downloadStyle.dp(44), downloadStyle.dp(44)));
        ImageButton search = new ImageButton(this);
        search.setImageDrawable(downloadStyle.viewIcon("search"));
        search.setBackground(null);
        search.setPadding(
                downloadStyle.dp(12),
                downloadStyle.dp(12),
                downloadStyle.dp(12),
                downloadStyle.dp(12));
        search.setContentDescription(PatchStrings.t("Find available downloads"));
        search.setOnClickListener(
                v -> startActivity(new Intent(this, DownloadExplorerActivity.class)));
        downloadStyle.markCustom(search);
        toolbar.addView(
                search, new LinearLayout.LayoutParams(downloadStyle.dp(48), downloadStyle.dp(48)));
        body.addView(toolbar);
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout filters = new LinearLayout(this);
        scroll.addView(filters);
        LinearLayout.LayoutParams filterSpace = new LinearLayout.LayoutParams(-1, -2);
        filterSpace.setMargins(0, downloadStyle.dp(8), 0, downloadStyle.dp(14));
        body.addView(scroll, filterSpace);
        for (String value :
                new String[] {"All", "Active", "Queue", "On device", "Sharing", "Telegram"}) {
            TextView chip =
                    downloadStyle.text(
                            PatchStrings.t(value),
                            13,
                            downloadStyle.foreground,
                            downloadStyle.medium);
            chip.setTag(value);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setPadding(downloadStyle.dp(14), 0, downloadStyle.dp(14), 0);
            chip.setMinHeight(downloadStyle.dp(44));
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setOnClickListener(
                    v -> {
                        filter = value;
                        last = "";
                        updateFilters();
                        renderRows(currentRows);
                        snapshot();
                    });
            LinearLayout.LayoutParams placement =
                    new LinearLayout.LayoutParams(-2, downloadStyle.dp(44));
            placement.setMarginEnd(downloadStyle.dp(7));
            filters.addView(chip, placement);
            filterViews.add(chip);
        }
        updateFilters();
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setTag("download-items");
        body.addView(rows);
        TextView uploads =
                downloadStyle.text(
                        "↑  " + PatchStrings.t("View upload activity") + "  ›",
                        14,
                        downloadStyle.secondary,
                        downloadStyle.medium);
        uploads.setMinHeight(downloadStyle.dp(48));
        uploads.setGravity(android.view.Gravity.CENTER_VERTICAL);
        uploads.setClickable(true);
        uploads.setFocusable(true);
        uploads.setOnClickListener(
                v -> startActivity(new Intent(this, BulkDownloadsActivity.class)));
        body.addView(uploads);
        TextView find =
                downloadStyle.text(
                        PatchStrings.t("Find available downloads"),
                        15,
                        downloadStyle.foreground,
                        downloadStyle.semibold);
        find.setMinHeight(downloadStyle.dp(48));
        find.setGravity(android.view.Gravity.CENTER);
        find.setBackground(downloadStyle.shape(downloadStyle.card, 24, false));
        find.setClickable(true);
        find.setFocusable(true);
        find.setOnClickListener(
                v -> startActivity(new Intent(this, DownloadExplorerActivity.class)));
        body.addView(find);
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

    static final class Row {
        final String name, detail, group;
        final Runnable action;
        final String actionName;
        String savedUri = "", hostId = "";
        String artist = "", source = "", format = "", artwork = "";
        long received = -1, total = -1;
        float fraction = Float.NaN;

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
                        Set<String> savedUris = new HashSet<>();
                        String warning = "";
                        try {
                            Map<?, ?> metadata = HostDownloadAccess.state("_savedMetadata");
                            Map<?, ?> active = HostDownloadAccess.state("active");
                            for (Map.Entry<?, ?> entry : active.entrySet()) {
                                String id = String.valueOf(entry.getKey());
                                Object state = entry.getValue();
                                String kind = state.getClass().getSimpleName();
                                String detail = kind;
                                if (kind.equals("Running")) {
                                    try {
                                        float fraction =
                                                ((Number)
                                                                HostDownloadAccess.propertyObject(
                                                                        state, "fraction"))
                                                        .floatValue();
                                        detail =
                                                Float.isFinite(fraction)
                                                                && fraction >= 0
                                                                && fraction <= 1
                                                        ? PatchStrings.f(
                                                                "Downloading · {0}%",
                                                                Math.round(fraction * 100))
                                                        : PatchStrings.t("Downloading");
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
                                Row row =
                                        new Row(
                                                title,
                                                detail,
                                                kind.equals("Queued") ? "Queue" : "Active",
                                                kind.equals("Running") || kind.equals("Queued")
                                                        ? "Cancel"
                                                        : null,
                                                () ->
                                                        command(
                                                                () ->
                                                                        HostDownloadAccess.cancel(
                                                                                reference)));
                                row.source = source;
                                populateMetadata(row, metadata.get(id));
                                if (kind.equals("Running"))
                                    try {
                                        row.fraction =
                                                ((Number)
                                                                HostDownloadAccess.propertyObject(
                                                                        state, "fraction"))
                                                        .floatValue();
                                        if (row.fraction < 0 || row.fraction > 1)
                                            row.fraction = Float.NaN;
                                    } catch (Exception ignored) {
                                    }
                                data.add(row);
                            }
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
                                if (data.size() >= 100) break;
                                if (!savedUris.add(uri)) continue;
                                Row row = fileRow(title, PatchStrings.t("On device"), id, uri);
                                populateMetadata(row, metadata.get(id));
                                row.source = "YouTube";
                                if (id.startsWith("src:")) {
                                    row.source = "BitChord";
                                    int separator = id.indexOf("::");
                                    if (separator > 4)
                                        try {
                                            Object config =
                                                    HostDownloadAccess.registry(
                                                            "config", id.substring(4, separator));
                                            if (config != null)
                                                row.source =
                                                        String.valueOf(
                                                                HostDownloadAccess.call(
                                                                        config, "getDisplayName"));
                                        } catch (Exception ignored) {
                                        }
                                }
                                data.add(row);
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
                            Row row =
                                    new Row(
                                            progress.name,
                                            progress.summary(),
                                            group,
                                            progress.canCancel() ? "Cancel" : null,
                                            () ->
                                                    SoulseekSelectedDownloads.cancel(
                                                            progress.reference));
                            row.source = "Soulseek";
                            row.received = progress.downloaded;
                            row.total = progress.total;
                            row.fraction =
                                    progress.total > 0
                                            ? progress.downloaded / (float) progress.total
                                            : Float.NaN;
                            data.add(row);
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
                                        && "complete".equals(job.phoneStatus)
                                        && savedUris.add(job.publishedUri)) {
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
                                            new StringBuilder(filter)
                                                    .append(viewMode)
                                                    .append(notice);
                                    for (Row row : data)
                                        signature
                                                .append(row.artist)
                                                .append(row.source)
                                                .append(row.format)
                                                .append(row.artwork)
                                                .append(row.fraction)
                                                .append(row.received)
                                                .append(row.total)
                                                .append(row.group)
                                                .append(row.name)
                                                .append(row.detail);
                                    if (!last.equals(signature.toString())) {
                                        last = signature.toString();
                                        currentRows = data;
                                        int visible = renderRows(data);
                                        long activeCount =
                                                data.stream()
                                                        .filter(row -> row.group.equals("Active"))
                                                        .count();
                                        long queuedCount =
                                                data.stream()
                                                        .filter(row -> row.group.equals("Queue"))
                                                        .count();
                                        status.setText(
                                                notice.isEmpty()
                                                        ? PatchStrings.f(
                                                                "{0} active · {1} queued",
                                                                activeCount, queuedCount)
                                                        : notice);
                                    }
                                    ui.postDelayed(refresh, 1500);
                                });
                    });
        } catch (RejectedExecutionException stopped) {
        }
    }

    private String viewLabel() {
        return PatchStrings.t(VIEW_LABELS[Arrays.asList(VIEW_IDS).indexOf(viewMode)]);
    }

    private void updateViewButton() {
        viewButton.setImageDrawable(downloadStyle.viewIcon(viewMode));
        viewButton.setContentDescription(
                PatchStrings.f("Current view: {0}. Tap to cycle; hold to choose.", viewLabel()));
    }

    private void viewDialog() {
        String[] labels = new String[VIEW_LABELS.length];
        for (int i = 0; i < labels.length; i++) labels[i] = PatchStrings.t(VIEW_LABELS[i]);
        new AlertDialog.Builder(this)
                .setTitle(PatchStrings.t("Download view"))
                .setSingleChoiceItems(
                        labels,
                        Arrays.asList(VIEW_IDS).indexOf(viewMode),
                        (dialog, which) -> {
                            setViewMode(VIEW_IDS[which]);
                            dialog.dismiss();
                        })
                .setNegativeButton(PatchStrings.t("Cancel"), null)
                .show();
    }

    void setViewMode(String mode) {
        if (!Arrays.asList(VIEW_IDS).contains(mode)) return;
        viewMode = mode;
        getSharedPreferences("bitchord_download_ui", 0).edit().putString("view", mode).apply();
        updateViewButton();
        last = "";
        renderRows(currentRows);
    }

    int renderRows(List<Row> data) {
        currentRows = data;
        rows.removeAllViews();
        rows.setContentDescription(PatchStrings.f("Download view: {0}", viewLabel()));
        int visible = 0;
        boolean grid =
                viewMode.equals("grid") && getResources().getConfiguration().fontScale < 1.4f;
        LinearLayout gridLine = null;
        for (Row row : data) {
            if (filter.equals("All") && row.group.equals("Sharing")) continue;
            if (!filter.equals("All") && !filter.equals(row.group)) continue;
            if (visible >= 100) break;
            LinearLayout card = rowView(row);
            card.setTag("download-row:" + visible);
            if (grid) {
                if (visible % 2 == 0) {
                    gridLine = new LinearLayout(this);
                    gridLine.setOrientation(LinearLayout.HORIZONTAL);
                    rows.addView(gridLine);
                }
                LinearLayout.LayoutParams placement = new LinearLayout.LayoutParams(0, -2, 1);
                placement.setMargins(
                        0, 0, downloadStyle.dp(visible % 2 == 0 ? 8 : 0), downloadStyle.dp(10));
                gridLine.addView(card, placement);
            } else rows.addView(card);
            visible++;
        }
        if (grid && visible % 2 != 0)
            gridLine.addView(new android.view.View(this), new LinearLayout.LayoutParams(0, 1, 1));
        if (visible == 0) {
            TextView empty =
                    downloadStyle.text(
                            PatchStrings.t("No items in this view."),
                            16,
                            downloadStyle.secondary,
                            downloadStyle.regular);
            empty.setGravity(android.view.Gravity.CENTER);
            empty.setPadding(0, downloadStyle.dp(40), 0, downloadStyle.dp(40));
            rows.addView(empty);
        }
        if (status != null) {
            long active = data.stream().filter(row -> row.group.equals("Active")).count();
            long queued = data.stream().filter(row -> row.group.equals("Queue")).count();
            status.setText(PatchStrings.f("{0} active · {1} queued", active, queued));
        }
        return visible;
    }

    private void updateFilters() {
        for (TextView chip : filterViews) {
            boolean selected = filter.equals(chip.getTag());
            chip.setSelected(selected);
            chip.setTextColor(selected ? downloadStyle.background : downloadStyle.secondary);
            chip.setBackground(
                    downloadStyle.shape(
                            selected ? downloadStyle.foreground : downloadStyle.card, 22, false));
        }
    }

    private void settingsMenu() {
        new AlertDialog.Builder(this)
                .setTitle(PatchStrings.t("Download settings"))
                .setItems(
                        new String[] {
                            PatchStrings.t("Copy destinations and Telegram setup"),
                            PatchStrings.t("Peer sharing and transfer controls"),
                            PatchStrings.t("Debrid link downloads"),
                            PatchStrings.t("Public Soulseek sharing"),
                            PatchStrings.t("Public upload slots"),
                            PatchStrings.t("Rescan public sharing inventory")
                        },
                        (dialog, which) -> {
                            if (which == 0) DownloadDestinations.openSettings(activity);
                            else if (which == 1)
                                startActivity(new Intent(this, BulkDownloadsActivity.class));
                            else if (which == 2)
                                startActivity(new Intent(this, DebridDownloadsActivity.class));
                            else if (which == 3)
                                command(
                                        () -> {
                                            boolean enabled =
                                                    PublicSoulseekShares.snapshot(
                                                                    getApplicationContext())
                                                            .optBoolean("enabled");
                                            ui.post(() -> sharingDialog(enabled));
                                        });
                            else if (which == 4) slotsDialog();
                            else
                                command(
                                        () ->
                                                PublicSoulseekShares.reconcile(
                                                        getApplicationContext()));
                        })
                .setNegativeButton(PatchStrings.t("Cancel"), null)
                .show();
    }

    private void populateMetadata(Row row, Object metadata) {
        if (metadata == null) return;
        row.artist = HostDownloadAccess.propertyText(metadata, "artist");
        row.artwork = HostDownloadAccess.propertyText(metadata, "thumbnailUrl");
        row.format = HostDownloadAccess.propertyText(metadata, "downloadFormat");
    }

    private LinearLayout rowView(Row row) {
        boolean compact = viewMode.equals("compact"),
                grid = viewMode.equals("grid"),
                list = viewMode.equals("list"),
                detailed = viewMode.equals("detailed");
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = downloadStyle.dp(compact ? 4 : list ? 10 : 15);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(
                compact ? null : downloadStyle.shape(downloadStyle.card, grid ? 20 : 28, false));
        LinearLayout.LayoutParams placement = new LinearLayout.LayoutParams(-1, -2);
        placement.setMargins(0, 0, 0, downloadStyle.dp(compact ? 2 : 11));
        card.setLayoutParams(placement);
        downloadStyle.markCustom(card);
        if (grid)
            card.addView(cover(row, 124), new LinearLayout.LayoutParams(-1, downloadStyle.dp(124)));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        if (grid) header.setPadding(0, downloadStyle.dp(8), 0, 0);
        card.addView(header);
        if (!compact && !grid) {
            int size = downloadStyle.dp(list ? 36 : 52);
            LinearLayout.LayoutParams art = new LinearLayout.LayoutParams(size, size);
            art.setMarginEnd(downloadStyle.dp(12));
            header.addView(cover(row, list ? 36 : 52), art);
        }
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        header.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        TextView title =
                downloadStyle.text(
                        row.name,
                        compact ? 14 : list ? 15 : 17,
                        downloadStyle.foreground,
                        downloadStyle.semibold);
        title.setMaxLines(grid || detailed ? 2 : 1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labels.addView(title);
        List<String> parts = new ArrayList<>();
        if (!row.artist.isEmpty()) parts.add(row.artist);
        if (!detailed && !row.source.isEmpty()) parts.add(row.source);
        if (!detailed && !row.format.isEmpty()) parts.add(row.format.toUpperCase(Locale.ROOT));
        if (!compact && !parts.isEmpty()) {
            TextView subtitle =
                    downloadStyle.text(
                            android.text.TextUtils.join(" · ", parts),
                            compact ? 12 : 13,
                            downloadStyle.secondary,
                            downloadStyle.regular);
            subtitle.setMaxLines(detailed ? 2 : 1);
            subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            subtitle.setPadding(0, downloadStyle.dp(4), 0, 0);
            labels.addView(subtitle);
        }
        ImageButton menu = new ImageButton(this);
        menu.setImageDrawable(downloadStyle.viewIcon("more"));
        menu.setBackground(null);
        menu.setPadding(
                downloadStyle.dp(13),
                downloadStyle.dp(13),
                downloadStyle.dp(13),
                downloadStyle.dp(13));
        menu.setContentDescription(PatchStrings.f("Actions for {0}", row.name));
        menu.setOnClickListener(v -> rowActions(row));
        downloadStyle.markCustom(menu);
        header.addView(
                menu, new LinearLayout.LayoutParams(downloadStyle.dp(44), downloadStyle.dp(48)));
        boolean downloading = row.group.equals("Active");
        if (downloading && !compact) {
            ProgressBar progress =
                    new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            progress.setTag("download-progress");
            progress.setMax(1000);
            progress.setIndeterminate(!Float.isFinite(row.fraction));
            if (Float.isFinite(row.fraction))
                progress.setProgress(Math.round(Math.max(0, Math.min(1, row.fraction)) * 1000));
            android.content.res.ColorStateList green =
                    android.content.res.ColorStateList.valueOf(0xff2ed15a);
            progress.setProgressTintList(green);
            progress.setIndeterminateTintList(green);
            progress.setProgressBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(downloadStyle.outline));
            LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(-1, downloadStyle.dp(3));
            bar.topMargin = downloadStyle.dp(16);
            card.addView(progress, bar);
            downloadStyle.markCustom(progress);
        }
        String detail = row.detail;
        if (row.group.equals("On device"))
            detail =
                    "✓  "
                            + PatchStrings.t("On device")
                            + (row.total > 0
                                    ? " · "
                                            + android.text.format.Formatter.formatShortFileSize(
                                                    this, row.total)
                                    : "");
        else if (row.group.equals("Queue")) detail = "◷  " + detail;
        else if (row.group.equals("Telegram")) detail = "☁  " + detail;
        if (compact && !parts.isEmpty())
            detail = android.text.TextUtils.join(" · ", parts) + " · " + detail;
        TextView state =
                downloadStyle.text(
                        detail, compact ? 11 : 12, downloadStyle.secondary, downloadStyle.regular);
        state.setTag("download-status");
        state.setMaxLines(compact ? 1 : detailed ? 4 : 2);
        state.setEllipsize(android.text.TextUtils.TruncateAt.END);
        state.setPadding(0, downloadStyle.dp(compact ? 2 : downloading ? 9 : 13), 0, 0);
        if (compact) labels.addView(state);
        else card.addView(state);
        if (detailed) {
            List<String> attributes = new ArrayList<>();
            if (!row.source.isEmpty()) attributes.add(PatchStrings.f("Source · {0}", row.source));
            if (!row.format.isEmpty())
                attributes.add(PatchStrings.f("Format · {0}", row.format.toUpperCase(Locale.ROOT)));
            if (row.total > 0)
                attributes.add(
                        PatchStrings.f(
                                "Size · {0}",
                                android.text.format.Formatter.formatShortFileSize(
                                        this, row.total)));
            attributes.add(PatchStrings.f("Category · {0}", PatchStrings.t(row.group)));
            TextView facts =
                    downloadStyle.text(
                            android.text.TextUtils.join("\n", attributes),
                            12,
                            downloadStyle.secondary,
                            downloadStyle.regular);
            facts.setPadding(0, downloadStyle.dp(10), 0, 0);
            card.addView(facts);
        }
        return card;
    }

    private android.view.View cover(Row row, int size) {
        FrameLayout frame = new FrameLayout(this);
        frame.setTag("download-cover");
        frame.setBackground(downloadStyle.shape(downloadStyle.outline, 10, false));
        frame.setClipToOutline(true);
        TextView note =
                downloadStyle.text(
                        "♫", size > 70 ? 34 : 23, downloadStyle.secondary, downloadStyle.medium);
        note.setGravity(android.view.Gravity.CENTER);
        note.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        frame.addView(note, new FrameLayout.LayoutParams(-1, -1));
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setVisibility(android.view.View.GONE);
        image.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
        downloadStyle.markCustom(frame);
        downloadStyle.markCustom(image);
        DownloadArtwork.bind(image, row.artwork, row.savedUri);
        return frame;
    }

    private void rowActions(Row row) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(PatchStrings.t("Details"));
        actions.add(
                () ->
                        new AlertDialog.Builder(this)
                                .setTitle(row.name)
                                .setMessage(row.detail)
                                .setPositiveButton(PatchStrings.t("Done"), null)
                                .show());
        if (row.actionName != null && row.action != null) {
            labels.add(PatchStrings.t(row.actionName));
            actions.add(row.action);
        }
        if (!row.savedUri.isEmpty()) {
            if (mixedPlaylistsAvailable()) {
                labels.add(PatchStrings.t("Add to playlist"));
                actions.add(
                        () -> {
                            try {
                                Class.forName("app.bitchord.telegram.MixedPlaylists")
                                        .getMethod(
                                                "offerFile",
                                                android.app.Activity.class,
                                                String.class,
                                                String.class)
                                        .invoke(null, activity, row.savedUri, row.name);
                            } catch (ReflectiveOperationException | LinkageError unavailable) {
                                android.widget.Toast.makeText(
                                                activity,
                                                PatchStrings.t("Playlist action is unavailable"),
                                                android.widget.Toast.LENGTH_SHORT)
                                        .show();
                            }
                        });
            }
            labels.add(PatchStrings.t("Offload to Telegram"));
            actions.add(() -> offloadDialog(row));
            labels.add(PatchStrings.t("Share publicly on Soulseek"));
            actions.add(() -> shareFileDialog(row));
        }
        new AlertDialog.Builder(this)
                .setTitle(row.name)
                .setItems(
                        labels.toArray(new String[0]), (dialog, which) -> actions.get(which).run())
                .show();
    }

    private static boolean mixedPlaylistsAvailable() {
        try {
            return Boolean.TRUE.equals(
                    Class.forName("app.bitchord.telegram.MixedPlaylists")
                            .getMethod("available")
                            .invoke(null));
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return false;
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
        Long known = fileSizes.get(uri);
        if (known == null) {
            long bytes = -1;
            if (!uri.endsWith("playlist.m3u8"))
                try (android.content.res.AssetFileDescriptor file =
                        getContentResolver().openAssetFileDescriptor(Uri.parse(uri), "r")) {
                    if (file != null) bytes = file.getLength();
                } catch (Exception ignored) {
                }
            known = bytes;
            fileSizes.put(uri, known);
        }
        row.total = known;
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

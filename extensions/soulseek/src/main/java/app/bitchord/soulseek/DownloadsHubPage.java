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
    private volatile boolean foreground;
    private final Runnable refresh = () -> snapshot();

    DownloadsHubPage(android.app.Activity activity, Intent intent) {
        super(activity, intent);
    }

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        HostDownloadAccess.init(getApplicationContext());
        String savedView = getSharedPreferences("bitchord_download_ui", 0).getString("view", "comfy");
        viewMode = Arrays.asList(VIEW_IDS).contains(savedView) ? savedView : "comfy";
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView heading = text(titleBar, PatchStrings.t("Downloads"), 28);
        heading.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(titleBar);
        text(
                body,
                PatchStrings.t("Follow downloads, saved files and confirmed destination copies."),
                15);
        viewButton = new ImageButton(this);
        viewButton.setTag("download-view");
        viewButton.setBackground(downloadStyle.shape(downloadStyle.card, 11, true));
        viewButton.setPadding(downloadStyle.dp(12), downloadStyle.dp(12), downloadStyle.dp(12), downloadStyle.dp(12));
        downloadStyle.markCustom(viewButton);
        updateViewButton();
        viewButton.setOnClickListener(v -> {
            int index = Arrays.asList(VIEW_IDS).indexOf(viewMode);
            setViewMode(VIEW_IDS[(index + 1) % VIEW_IDS.length]);
            viewButton.announceForAccessibility(viewLabel());
        });
        viewButton.setOnLongClickListener(v -> { viewDialog(); return true; });
        titleBar.addView(viewButton, new LinearLayout.LayoutParams(downloadStyle.dp(48), downloadStyle.dp(48)));
        ImageButton search = new ImageButton(this);
        search.setImageDrawable(downloadStyle.viewIcon("search"));
        search.setBackground(null);
        search.setPadding(downloadStyle.dp(12), downloadStyle.dp(12), downloadStyle.dp(12), downloadStyle.dp(12));
        search.setContentDescription(PatchStrings.t("Find available downloads"));
        search.setOnClickListener(v -> startActivity(new Intent(this, DownloadExplorerActivity.class)));
        downloadStyle.markCustom(search);
        titleBar.addView(search, new LinearLayout.LayoutParams(downloadStyle.dp(48), downloadStyle.dp(48)));
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
        rows.setTag("download-items");
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

    static final class Row {
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
                                            new StringBuilder(filter).append(viewMode).append(notice);
                                    for (Row row : data)
                                        signature
                                                .append(row.group)
                                                .append(row.name)
                                                .append(row.detail);
                                    if (!last.equals(signature.toString())) {
                                        last = signature.toString();
                                        currentRows = data;
                                        int visible = renderRows(data);
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

    private String viewLabel() {
        return PatchStrings.t(VIEW_LABELS[Arrays.asList(VIEW_IDS).indexOf(viewMode)]);
    }

    private void updateViewButton() {
        viewButton.setImageDrawable(downloadStyle.viewIcon(viewMode));
        viewButton.setContentDescription(PatchStrings.f("Current view: {0}. Tap to cycle; hold to choose.", viewLabel()));
    }

    private void viewDialog() {
        String[] labels = new String[VIEW_LABELS.length];
        for (int i = 0; i < labels.length; i++) labels[i] = PatchStrings.t(VIEW_LABELS[i]);
        new AlertDialog.Builder(this).setTitle(PatchStrings.t("Download view"))
                .setSingleChoiceItems(labels, Arrays.asList(VIEW_IDS).indexOf(viewMode), (dialog, which) -> {
                    setViewMode(VIEW_IDS[which]);
                    dialog.dismiss();
                }).setNegativeButton(PatchStrings.t("Cancel"), null).show();
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
        boolean grid = viewMode.equals("grid")
                && getResources().getConfiguration().fontScale < 1.4f;
        LinearLayout gridLine = null;
        for (Row row : data) {
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
                placement.setMargins(0, 0, downloadStyle.dp(visible % 2 == 0 ? 8 : 0), downloadStyle.dp(10));
                gridLine.addView(card, placement);
            } else rows.addView(card);
            visible++;
        }
        if (grid && visible % 2 != 0) gridLine.addView(new android.view.View(this), new LinearLayout.LayoutParams(0, 1, 1));
        if (visible == 0) text(rows, PatchStrings.t("No items in this view."), 16);
        return visible;
    }

    private LinearLayout rowView(Row row) {
        boolean compact = viewMode.equals("compact"), grid = viewMode.equals("grid"),
                list = viewMode.equals("list"), detailed = viewMode.equals("detailed");
        LinearLayout card = new LinearLayout(this);
        downloadStyle.card(card);
        int pad = downloadStyle.dp(compact ? 4 : list ? 10 : 15);
        card.setPadding(pad, pad, pad, pad);
        if (compact) card.setBackgroundColor(downloadStyle.background);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setOrientation(grid ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        card.addView(header);
        if (!compact) {
            TextView artwork = downloadStyle.text(row.name.isEmpty() ? "♪" : row.name.substring(0, 1), grid ? 30 : 19,
                    downloadStyle.foreground, downloadStyle.semibold);
            artwork.setGravity(android.view.Gravity.CENTER);
            artwork.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            artwork.setBackground(downloadStyle.shape(downloadStyle.outline, 9, false));
            int size = downloadStyle.dp(grid ? 90 : list ? 34 : 48);
            LinearLayout.LayoutParams art = new LinearLayout.LayoutParams(grid ? -1 : size, size);
            if (!grid) art.setMarginEnd(downloadStyle.dp(10));
            else art.bottomMargin = downloadStyle.dp(9);
            header.addView(artwork, art);
        }
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        header.addView(labels, grid ? new LinearLayout.LayoutParams(-1, -2) : new LinearLayout.LayoutParams(0, -2, 1));
        TextView title = downloadStyle.text(row.name, compact ? 14 : list ? 15 : 17,
                downloadStyle.foreground, downloadStyle.semibold);
        title.setMaxLines(grid || detailed ? 2 : 1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labels.addView(title);
        TextView detail = downloadStyle.text(row.detail, compact ? 12 : 13,
                downloadStyle.secondary, downloadStyle.regular);
        if (!detailed) detail.setMaxLines(compact || list ? 1 : 3);
        detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labels.addView(detail);
        Button menu = new Button(this);
        menu.setText("⋮");
        menu.setContentDescription(PatchStrings.f("Actions for {0}", row.name));
        menu.setMinHeight(downloadStyle.dp(48));
        menu.setMinimumWidth(downloadStyle.dp(48));
        menu.setOnClickListener(v -> rowActions(row));
        header.addView(menu, new LinearLayout.LayoutParams(grid ? -1 : downloadStyle.dp(48), downloadStyle.dp(48)));
        if (detailed) {
            TextView group = downloadStyle.text(PatchStrings.f("Category · {0}", PatchStrings.t(row.group)), 13,
                    downloadStyle.secondary, downloadStyle.regular);
            group.setPadding(0, downloadStyle.dp(10), 0, 0);
            card.addView(group);
            if (!row.savedUri.isEmpty()) text(card, PatchStrings.t("Phone copy confirmed"), 13);
        }
        return card;
    }

    private void rowActions(Row row) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(PatchStrings.t("Details"));
        actions.add(() -> new AlertDialog.Builder(this).setTitle(row.name).setMessage(row.detail)
                .setPositiveButton(PatchStrings.t("Done"), null).show());
        if (row.actionName != null && row.action != null) {
            labels.add(PatchStrings.t(row.actionName)); actions.add(row.action);
        }
        if (!row.savedUri.isEmpty()) {
            if (mixedPlaylistsAvailable()) {
                labels.add(PatchStrings.t("Add to playlist"));
                actions.add(() -> {
                    try {
                        Class.forName("app.bitchord.telegram.MixedPlaylists")
                                .getMethod("offerFile", android.app.Activity.class, String.class, String.class)
                                .invoke(null, activity, row.savedUri, row.name);
                    } catch (ReflectiveOperationException | LinkageError unavailable) {
                        android.widget.Toast.makeText(activity, PatchStrings.t("Playlist action is unavailable"), android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
            }
            labels.add(PatchStrings.t("Offload to Telegram")); actions.add(() -> offloadDialog(row));
            labels.add(PatchStrings.t("Share publicly on Soulseek")); actions.add(() -> shareFileDialog(row));
        }
        new AlertDialog.Builder(this).setTitle(row.name).setItems(labels.toArray(new String[0]),
                (dialog, which) -> actions.get(which).run()).show();
    }

    private static boolean mixedPlaylistsAvailable() {
        try {
            return Boolean.TRUE.equals(Class.forName("app.bitchord.telegram.MixedPlaylists")
                    .getMethod("available").invoke(null));
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

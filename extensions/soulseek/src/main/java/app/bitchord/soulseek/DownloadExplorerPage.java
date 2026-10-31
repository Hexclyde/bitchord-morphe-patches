package app.bitchord.soulseek;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.*;

import app.bitchord.telegram.PatchStrings;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Live, bounded search across the host's download-enabled addons and explicit Soulseek files. */
final class DownloadExplorerPage extends DownloadPage {
    private final java.util.List<Future<?>> searches = new CopyOnWriteArrayList<>();
    private final ThreadPoolExecutor actions =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<Runnable>(8),
                    runnable -> {
                        Thread thread = new Thread(runnable, "download-explorer-actions");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final ThreadPoolExecutor work =
            new ThreadPoolExecutor(
                    3,
                    3,
                    20,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<Runnable>(24),
                    runnable -> {
                        Thread thread = new Thread(runnable, "download-explorer");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicLong revision = new AtomicLong();
    private DownloadExplorerView explorer;
    private EditText query;
    private LinearLayout results;
    private TextView status;
    private String choice;
    private volatile boolean foreground;
    private final AtomicInteger pending = new AtomicInteger();
    private int found;
    private boolean started;

    DownloadExplorerPage(android.app.Activity activity, Intent intent) {
        super(activity, intent);
    }

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        SoulseekBridge.boot(getApplicationContext());
        HostDownloadAccess.init(getApplicationContext());
        choice = getIntent().getStringExtra(SoulseekDownloadChoice.EXTRA_CHOICE);
        boolean batch =
                getIntent().getIntExtra("download.batch.index", -1) >= 0
                        && SoulseekDownloadChoice.isBatch(choice);
        explorer = new DownloadExplorerView(this, batch, this::search, this::settings, this::admit);
        query = explorer.query;
        results = explorer.results;
        status = explorer.status;
        view = explorer.root;
    }

    @Override
    protected void onStart() {
        super.onStart();
        foreground = true;
        if (!started && choice != null && query.getText().toString().trim().length() >= 2) {
            started = true;
            search();
        }
    }

    @Override
    protected void onStop() {
        foreground = false;
        revision.incrementAndGet();
        cancelSearches();
        work.getQueue().clear();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        work.shutdownNow();
        actions.shutdown();
        if (!activity.isChangingConfigurations()
                && choice != null
                && !SoulseekDownloadChoice.isBatch(choice)) SoulseekDownloadChoice.forget(choice);
        super.onDestroy();
    }

    private boolean current(long version) {
        return foreground && revision.get() == version && !isFinishing();
    }

    private void cancelSearches() {
        for (Future<?> search : searches) search.cancel(true);
        searches.clear();
        work.getQueue().clear();
    }

    private void search() {
        final android.content.Context application = getApplicationContext();
        String value = query.getText().toString().trim();
        if (value.length() < 2 || value.length() > 256) {
            status.setText(PatchStrings.t("Enter an artist and song title, up to 256 characters."));
            return;
        }
        long version = revision.incrementAndGet();
        cancelSearches();
        explorer.reset();
        work.getQueue().clear();
        pending.set(0);
        found = 0;
        status.setText(
                PatchStrings.t(
                        "Searching enabled download sources. Results appear as each source"
                                + " answers."));
        submit(
                version,
                () -> {
                    List<?> sources = HostDownloadAccess.sources();
                    if (sources.size() > 20) sources = sources.subList(0, 20);
                    for (Object source : sources) {
                        String id = HostDownloadAccess.sourceText(source, "getConfigId");
                        String name = HostDownloadAccess.sourceText(source, "getDisplayName");
                        if (id.equals("soulseek-native")
                                || HostDownloadAccess.sourceText(source, "getKind")
                                        .equals("YOUTUBE")) continue;
                        final LinearLayout[] card = new LinearLayout[1];
                        ui.post(
                                () -> {
                                    if (current(version)) {
                                        card[0] = card(name);
                                        text(card[0], PatchStrings.t("Searching…"), 14);
                                    }
                                });
                        submit(
                                version,
                                () -> {
                                    try {
                                        List<?> rows = HostDownloadAccess.search(source, value);
                                        ui.post(
                                                () -> {
                                                    if (!current(version) || card[0] == null)
                                                        return;
                                                    LinearLayout group = card[0];
                                                    group.removeAllViews();
                                                    text(group, name, 19);
                                                    int count = 0;
                                                    for (Object song : rows) {
                                                        String reference =
                                                                HostDownloadAccess.propertyText(
                                                                        song, "videoId");
                                                        if (!reference.startsWith(
                                                                "src:" + id + "::")) continue;
                                                        if (count++ >= 12) break;
                                                        found++;
                                                        String title =
                                                                HostDownloadAccess.propertyText(
                                                                        song, "title");
                                                        String artist =
                                                                HostDownloadAccess.propertyText(
                                                                        song, "artist");
                                                        String quality =
                                                                HostDownloadAccess.propertyText(
                                                                        song, "sourceQuality");
                                                        String duration =
                                                                HostDownloadAccess.propertyText(
                                                                        song, "durationText");
                                                        explorer.add(
                                                                group,
                                                                new DownloadExplorerView.Option(
                                                                        name,
                                                                        title,
                                                                        artist,
                                                                        quality,
                                                                        duration,
                                                                        reportedLossless(quality),
                                                                        false,
                                                                        now ->
                                                                                HostDownloadAccess
                                                                                        .enqueue(
                                                                                                application,
                                                                                                song,
                                                                                                id,
                                                                                                now),
                                                                        null));
                                                    }
                                                    if (count == 0)
                                                        text(
                                                                group,
                                                                PatchStrings.t(
                                                                        "No downloadable matches"
                                                                            + " from this source."),
                                                                14);
                                                });
                                    } catch (Exception | LinkageError failed) {
                                        ui.post(
                                                () -> {
                                                    if (current(version) && card[0] != null) {
                                                        card[0].removeAllViews();
                                                        text(card[0], name, 19);
                                                        text(
                                                                card[0],
                                                                PatchStrings.t(
                                                                        "Source did not answer."
                                                                                + " Check its"
                                                                                + " connection and"
                                                                                + " retry."),
                                                                14);
                                                    }
                                                });
                                    }
                                });
                    }
                    ui.post(
                            () -> {
                                if (current(version))
                                    text(
                                            results,
                                            PatchStrings.t(
                                                    "Reported formats are unverified. Review the"
                                                        + " recording version before selecting."),
                                            14);
                            });
                });
        if (DownloadSettings.enabled(this, DownloadSettings.SOULSEEK))
            submit(
                    version,
                    () -> {
                        try {
                            SoulseekBridge bridge = SoulseekBridge.get();
                            List<SoulseekTrack> rows =
                                    AudioFormatPreferences.ordered(
                                            AudioFormatPreferences.snapshot(this),
                                            bridge.search(value));
                            String session = bridge.sessionTag;
                            ui.post(
                                    () -> {
                                        if (!current(version)) return;
                                        LinearLayout group = card("Soulseek");
                                        if (rows.isEmpty())
                                            text(
                                                    group,
                                                    PatchStrings.t(
                                                            "No downloadable matches from this"
                                                                    + " source."),
                                                    14);
                                        int count = 0;
                                        for (SoulseekTrack row : rows) {
                                            if (count++ >= 20) break;
                                            found++;
                                            explorer.add(
                                                    group,
                                                    new DownloadExplorerView.Option(
                                                            "Soulseek",
                                                            row.title,
                                                            row.artist,
                                                            row.advertisedExtension.toUpperCase(
                                                                    java.util.Locale.ROOT),
                                                            android.text.format.Formatter
                                                                            .formatShortFileSize(
                                                                                    this, row.size)
                                                                    + " · "
                                                                    + PatchStrings.f(
                                                                            "Peer: {0}", row.peer),
                                                            reportedLossless(row.format),
                                                            row.freeSlots == 1,
                                                            now -> {
                                                                if (!session.equals(
                                                                        bridge.sessionTag))
                                                                    throw new java.io.IOException(
                                                                            "Search result"
                                                                                    + " expired");
                                                                if (!SoulseekSelectedDownloads
                                                                        .enqueueSelectedFile(
                                                                                application,
                                                                                row,
                                                                                session,
                                                                                now))
                                                                    throw new java.io.IOException(
                                                                            "Selected file could"
                                                                                + " not be queued");
                                                            },
                                                            SoulseekPreview.available(this)
                                                                            && SoulseekPreview
                                                                                    .supported(row)
                                                                    ? () -> {
                                                                        try {
                                                                            SoulseekPreview.open(
                                                                                    activity, row,
                                                                                    session);
                                                                        } catch (
                                                                                Exception expired) {
                                                                            status.setText(
                                                                                    PatchStrings.t(
                                                                                            "Preview"
                                                                                                + " unavailable."
                                                                                                + " Check"
                                                                                                + " the selected"
                                                                                                + " file"
                                                                                                + " and Soulseek"
                                                                                                + " session."));
                                                                        }
                                                                    }
                                                                    : null));
                                        }
                                    });
                        } catch (Exception | LinkageError failed) {
                            ui.post(
                                    () -> {
                                        if (current(version))
                                            text(
                                                    card("Soulseek"),
                                                    PatchStrings.t(
                                                            "Soulseek is not ready. Check account"
                                                                    + " and setup."),
                                                    14);
                                    });
                        }
                    });
    }

    interface Selection extends DownloadBatch.Selection {
        void start(boolean now) throws Exception;
    }

    private void admit(DownloadExplorerView.Option option, boolean now) {
        String source = option.source, title = option.title;
        Selection selection = option.selection;
        int batchIndex = getIntent().getIntExtra("download.batch.index", -1);
        if (batchIndex >= 0 && SoulseekDownloadChoice.isBatch(choice)) {
            try {
                DownloadBatch.select(choice, batchIndex, source + " · " + title, selection);
            } catch (IllegalStateException expired) {
                status.setText(
                        PatchStrings.t(
                                "This track was submitted or the batch expired. Return to the batch"
                                        + " to review it."));
                return;
            }
            startActivity(
                    new Intent(this, BatchDownloadActivity.class)
                            .putExtra(SoulseekDownloadChoice.EXTRA_CHOICE, choice));
            return;
        }
        select(selection, now);
    }

    private void select(Selection selection, boolean now) {
        long version = revision.get();
        explorer.busy(true);
        explorer.message(PatchStrings.t("Submitting download…"));
        try {
            actions.execute(
                    () -> {
                        try {
                            selection.start(now);
                            ui.post(
                                    () -> {
                                        explorer.busy(false);
                                        if (current(version))
                                            explorer.message(
                                                    PatchStrings.t(
                                                            "Selected download submitted. Open"
                                                                    + " Downloads to follow"
                                                                    + " progress."));
                                    });
                        } catch (Exception failed) {
                            ui.post(
                                    () -> {
                                        explorer.busy(false);
                                        if (current(version))
                                            explorer.message(
                                                    PatchStrings.t(
                                                            "Download action failed. Check the"
                                                                    + " selected source and try"
                                                                    + " again."));
                                    });
                        }
                    });
        } catch (RejectedExecutionException busy) {
            explorer.busy(false);
            explorer.message(
                    PatchStrings.t("Search is busy. Wait for current sources and try again."));
        }
    }

    private void submit(long version, Action action) {
        if (!current(version)) return;
        pending.incrementAndGet();
        try {
            searches.removeIf(Future::isDone);
            searches.add(
                    work.submit(
                            () -> {
                                try {
                                    if (current(version)) action.run();
                                } catch (Exception | LinkageError failed) {
                                    ui.post(
                                            () -> {
                                                if (current(version))
                                                    status.setText(
                                                            PatchStrings.t(
                                                                    "Download action failed. Check"
                                                                        + " the selected source and"
                                                                        + " try again."));
                                            });
                                } finally {
                                    ui.post(
                                            () -> {
                                                if (current(version)) {
                                                    int waiting = pending.decrementAndGet();
                                                    status.setText(
                                                            waiting == 0
                                                                    ? PatchStrings.f(
                                                                            "Search finished · {0}"
                                                                                    + " results",
                                                                            found)
                                                                    : PatchStrings.f(
                                                                            "Searching · {0}"
                                                                                + " sources still"
                                                                                + " working",
                                                                            waiting));
                                                }
                                            });
                                }
                            }));
        } catch (RejectedExecutionException busy) {
            pending.decrementAndGet();
            if (current(version))
                status.setText(
                        PatchStrings.t("Search is busy. Wait for current sources and try again."));
        }
    }

    interface Action {
        void run() throws Exception;
    }

    private LinearLayout card(String label) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        results.addView(group, new LinearLayout.LayoutParams(-1, -2));
        text(group, label, 19);
        return group;
    }

    private TextView text(LinearLayout parent, String value, int size) {
        TextView view =
                downloadStyle.text(
                        value,
                        size,
                        size >= 16 ? downloadStyle.foreground : downloadStyle.secondary,
                        size >= 16 ? downloadStyle.medium : downloadStyle.regular);
        view.setPadding(0, downloadStyle.dp(5), 0, downloadStyle.dp(5));
        parent.addView(view);
        return view;
    }

    static boolean reportedLossless(String quality) {
        String label = quality.toLowerCase(java.util.Locale.ROOT);
        return label.contains("lossless")
                || label.contains("flac")
                || label.contains("alac")
                || label.equals("wav")
                || label.equals("aiff")
                || label.equals("dsd");
    }

    private void settings() {
        java.util.List<String> labels = new java.util.ArrayList<>();
        labels.add(PatchStrings.t("Edit search"));
        labels.add(PatchStrings.t("Formats, best first"));
        labels.add(PatchStrings.t("Soulseek account and setup"));
        labels.add(PatchStrings.t("View downloads"));
        boolean automatic = choice != null && !SoulseekDownloadChoice.isBatch(choice);
        if (automatic) labels.add(PatchStrings.t("Use BitChord automatic source selection"));
        new AlertDialog.Builder(this)
                .setItems(
                        labels.toArray(new String[0]),
                        (dialog, index) -> {
                            if (index == 0) explorer.showSearch();
                            else if (index == 1) AudioFormatPreferences.openSettings(activity);
                            else if (index == 2)
                                startActivity(new Intent(this, SoulseekActivity.class));
                            else if (index == 3)
                                startActivity(new Intent(this, DownloadsHubActivity.class));
                            else
                                new AlertDialog.Builder(this)
                                        .setMessage(
                                                PatchStrings.t(
                                                        "May use YouTube or another enabled"
                                                                + " source."))
                                        .setPositiveButton(
                                                PatchStrings.t("Continue"),
                                                (d, w) -> {
                                                    if (SoulseekDownloadChoice.resume(choice, this))
                                                        finish();
                                                    else
                                                        status.setText(
                                                                PatchStrings.t(
                                                                        "This download choice"
                                                                            + " expired. Return to"
                                                                            + " the song and try"
                                                                            + " again."));
                                                })
                                        .setNegativeButton(PatchStrings.t("Cancel"), null)
                                        .show();
                        })
                .show();
    }
}

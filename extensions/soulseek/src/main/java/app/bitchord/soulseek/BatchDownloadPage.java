package app.bitchord.soulseek;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.*;

import app.bitchord.telegram.PatchStrings;

import java.util.*;
import java.util.concurrent.*;

final class BatchDownloadPage extends DownloadPage {
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout rows, sources;
    private TextView status;
    private String token;
    private boolean foreground, busy;
    private Future<?> task;
    private long generation;

    BatchDownloadPage(Activity activity, Intent intent) {
        super(activity, intent);
    }

    @Override
    public void onCreate(Bundle saved) {
        token = getIntent().getStringExtra(SoulseekDownloadChoice.EXTRA_CHOICE);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(1);
        text(body, PatchStrings.t("Batch source selection"), 28);
        text(
                body,
                PatchStrings.t(
                        "Choose each track, or find unique title and artist matches in one source."
                            + " Review reported quality before submitting. Unselected tracks stay"
                            + " out of the queue."),
                15);
        status = text(body, "", 15);
        sources = new LinearLayout(this);
        sources.setOrientation(1);
        body.addView(sources);
        if (DownloadSettings.enabled(this, DownloadSettings.SOULSEEK))
            button(
                    body,
                    PatchStrings.t("Find matches in Soulseek"),
                    () -> run(() -> DownloadBatch.findSoulseek(token, getApplicationContext())));
        rows = new LinearLayout(this);
        rows.setOrientation(1);
        body.addView(rows);
        button(body, PatchStrings.t("Download selected now"), () -> confirm(true));
        button(body, PatchStrings.t("Add selected to download queue"), () -> confirm(false));
        setContentView(body);
    }

    @Override
    protected void onStart() {
        foreground = true;
        render();
        loadSources();
    }

    @Override
    protected void onStop() {
        foreground = false;
        generation++;
        busy = false;
        if (task != null) task.cancel(true);
    }

    @Override
    protected void onDestroy() {
        work.shutdownNow();
        super.onDestroy();
    }

    private void loadSources() {
        run(
                () -> {
                    List<Object[]> values = new ArrayList<>();
                    for (Object source : HostDownloadAccess.sources()) {
                        String id = HostDownloadAccess.sourceText(source, "getConfigId");
                        if (id.equals("soulseek-native")
                                || HostDownloadAccess.sourceText(source, "getKind")
                                        .equals("YOUTUBE")) continue;
                        values.add(
                                new Object[] {
                                    source, HostDownloadAccess.sourceText(source, "getDisplayName")
                                });
                        if (values.size() >= 20) break;
                    }
                    ui.post(
                            () -> {
                                if (!foreground) return;
                                sources.removeAllViews();
                                for (Object[] item : values) {
                                    Object source = item[0];
                                    String name = (String) item[1];
                                    button(
                                            sources,
                                            PatchStrings.f("Find matches in {0}", name),
                                            () ->
                                                    run(
                                                            () ->
                                                                    DownloadBatch.find(
                                                                            token,
                                                                            source,
                                                                            getApplicationContext())));
                                }
                            });
                });
    }

    private void render() {
        rows.removeAllViews();
        List<?> songs = SoulseekDownloadChoice.songs(token);
        status.setText(
                songs.isEmpty()
                        ? PatchStrings.t(
                                "This batch expired. Return to the album or playlist and try"
                                        + " again.")
                        : PatchStrings.f(
                                "{0} tracks · review source and quality for each", songs.size()));
        for (int i = 0; i < songs.size(); i++) {
            final int index = i;
            Object song = songs.get(i);
            DownloadBatch.Choice choice = DownloadBatch.choice(token, i);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(1);
            downloadStyle.card(card);
            rows.addView(card);
            text(
                    card,
                    HostDownloadAccess.propertyText(song, "title")
                            + " · "
                            + HostDownloadAccess.propertyText(song, "artist"),
                    17);
            text(
                    card,
                    choice == null
                            ? PatchStrings.t("Source not selected")
                            : choice.detail
                                    + (choice.submitted ? " · " + PatchStrings.t("Submitted") : ""),
                    14);
            if (choice != null && !choice.error.isEmpty())
                text(card, PatchStrings.t(choice.error), 14);
            if (choice == null || !choice.submitted) {
                button(
                        card,
                        PatchStrings.t("Choose source and quality"),
                        () -> {
                            String query =
                                    HostDownloadAccess.propertyText(song, "artist")
                                            + " "
                                            + HostDownloadAccess.propertyText(song, "title");
                            startActivity(
                                    new Intent(this, DownloadExplorerActivity.class)
                                            .putExtra(SoulseekDownloadChoice.EXTRA_CHOICE, token)
                                            .putExtra(
                                                    SoulseekDownloadChoice.EXTRA_QUERY,
                                                    query.substring(
                                                            0, Math.min(query.length(), 256)))
                                            .putExtra("download.batch.index", index));
                        });
                if (choice != null)
                    button(
                            card,
                            PatchStrings.t("Unselect"),
                            () -> {
                                DownloadBatch.clear(token, index);
                                render();
                            });
            }
        }
    }

    private void confirm(boolean now) {
        if (busy) return;
        int selected = 0;
        for (int i = 0; i < SoulseekDownloadChoice.songs(token).size(); i++) {
            DownloadBatch.Choice choice = DownloadBatch.choice(token, i);
            if (choice != null && !choice.submitted) selected++;
        }
        if (selected == 0) {
            status.setText(PatchStrings.t("Select at least one track first."));
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage(
                        PatchStrings.f(
                                "Submit {0} selected tracks? Existing transfers continue. Each"
                                        + " track keeps its chosen source.",
                                selected))
                .setPositiveButton(
                        PatchStrings.t("Continue"),
                        (dialog, which) -> run(() -> DownloadBatch.submit(token, now)))
                .setNegativeButton(PatchStrings.t("Cancel"), null)
                .show();
    }

    interface Action {
        void run() throws Exception;
    }

    private void run(Action action) {
        if (busy || work.isShutdown()) return;
        busy = true;
        status.setText(PatchStrings.t("Working…"));
        final long operation = ++generation;
        task =
                work.submit(
                        () -> {
                            String error = "";
                            try {
                                action.run();
                            } catch (Exception failed) {
                                error =
                                        PatchStrings.t(
                                                "Batch action failed. Review the selections and"
                                                    + " retry.");
                            }
                            final String result = error;
                            ui.post(
                                    () -> {
                                        if (operation != generation) return;
                                        busy = false;
                                        if (foreground) {
                                            render();
                                            if (!result.isEmpty()) status.setText(result);
                                        }
                                    });
                        });
    }

    private TextView text(LinearLayout parent, String value, int size) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        parent.addView(text);
        return text;
    }

    private void button(LinearLayout parent, String value, Runnable action) {
        Button button = new Button(this);
        button.setText(value);
        button.setOnClickListener(v -> action.run());
        parent.addView(button);
    }
}

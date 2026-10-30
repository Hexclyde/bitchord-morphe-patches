package app.bitchord.soulseek;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.bitchord.telegram.CapturePolicy;
import app.bitchord.telegram.PatchStrings;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Manual searches and explicit file choices; no streaming or automatic replacement of a song. */
public final class SoulseekSearchActivity extends DownloadActivity {
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    30,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<Runnable>(1),
                    task -> {
                        Thread thread = new Thread(task, "soulseek-manual-search");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final AtomicLong revision = new AtomicLong();
    private volatile boolean foreground;
    private Future<?> search;
    private EditText query;
    private TextView status, choiceStatus;
    private Button submit, originalButton;
    private LinearLayout results;
    private LinearLayout choiceCard, form;
    private String choice;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        CapturePolicy.apply(this);
        if (Build.VERSION.SDK_INT >= 31) getWindow().setHideOverlayWindows(true);
        SoulseekBridge.boot(getApplicationContext());
        choice = getIntent().getStringExtra(SoulseekDownloadChoice.EXTRA_CHOICE);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);
        text(body, PatchStrings.t("Find music on Soulseek"), 28);
        text(
                body,
                PatchStrings.t(
                        "Search for a file, review the peer's details, then choose exactly what to"
                                + " download. Your current song and its download status stay"
                                + " unchanged."),
                16);
        if (choice != null) {
            LinearLayout source = choiceCard = card(body);
            text(source, PatchStrings.t("Choose download source"), 20);
            text(
                    source,
                    PatchStrings.t(
                            "Soulseek searches for a separate file. BitChord uses its existing"
                                    + " download sources and settings."),
                    15);
            Button find = button(source, PatchStrings.t("Search Soulseek"));
            find.setTag("soulseek-choice-search");
            find.setOnClickListener(
                    view -> {
                        chooseSoulseek();
                        if (query.getText().toString().trim().length() >= 2) startSearch();
                        else query.requestFocus();
                    });
            Button original =
                    originalButton =
                            button(source, PatchStrings.t("Continue with BitChord download"));
            original.setTag("soulseek-choice-original");
            original.setEnabled(SoulseekDownloadChoice.available(choice));
            text(source, PatchStrings.t("May use YouTube or another enabled source."), 14);
            original.setOnClickListener(
                    view -> {
                        original.setEnabled(false);
                        if (SoulseekDownloadChoice.resume(choice, this)) finish();
                        else
                            choiceStatus.setText(
                                    PatchStrings.t(
                                            "This download choice expired. Return to the song and"
                                                    + " try again."));
                    });
            choiceStatus = text(source, "", 15);
            choiceStatus.setTag("soulseek-choice-status");
            refreshChoice();
        }
        form = card(body);
        form.setTag("soulseek-search-form");
        text(form, PatchStrings.t("Source: Soulseek"), 18);
        query = new EditText(this);
        query.setTag("soulseek-search-query");
        query.setSingleLine(true);
        query.setHint(PatchStrings.t("Artist and song title"));
        String prefill = getIntent().getStringExtra(SoulseekDownloadChoice.EXTRA_QUERY);
        if (prefill != null)
            query.setText(prefill.length() > 256 ? prefill.substring(0, 256) : prefill);
        form.addView(query);
        submit = button(form, PatchStrings.t("Search Soulseek"));
        submit.setTag("soulseek-search-submit");
        submit.setOnClickListener(view -> startSearch());
        status =
                text(
                        form,
                        PatchStrings.t(
                                "Ready to search. Nothing downloads until you choose a file."),
                        15);
        status.setTag("soulseek-search-status");
        if (choice != null && !SoulseekDownloadChoice.available(choice))
            status.setText(
                    PatchStrings.t(
                            "This download choice expired. Return to the song and try again."));
        if (choice != null) {
            Button sources = button(form, PatchStrings.t("Choose download source"));
            sources.setTag("soulseek-search-choose-source");
            sources.setOnClickListener(view -> showChoice());
        }
        button(form, PatchStrings.t("Soulseek account and setup"))
                .setOnClickListener(
                        view -> startActivity(new Intent(this, SoulseekActivity.class)));
        button(form, PatchStrings.t("Audio formats and preference order"))
                .setOnClickListener(
                        view -> startActivity(new Intent(this, AudioFormatSetupActivity.class)));
        button(form, PatchStrings.t("Where copies go"))
                .setOnClickListener(view -> DownloadDestinations.openSettings(this));
        button(form, PatchStrings.t("View downloads"))
                .setOnClickListener(
                        view -> startActivity(new Intent(this, BulkDownloadsActivity.class)));
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setTag("soulseek-search-results");
        body.addView(results);
        if (choice != null) {
            form.setVisibility(View.GONE);
            results.setVisibility(View.GONE);
        }
        setContentView(scroll);
    }

    @Override
    protected void onStart() {
        super.onStart();
        foreground = true;
    }

    @Override
    protected void onStop() {
        foreground = false;
        revision.incrementAndGet();
        if (search != null) search.cancel(true);
        worker.getQueue().clear();
        if (submit != null) submit.setEnabled(true);
        if (query != null) query.setEnabled(true);
        if (results != null) results.removeAllViews();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        foreground = false;
        revision.incrementAndGet();
        worker.shutdownNow();
        if (!isChangingConfigurations() && choice != null) SoulseekDownloadChoice.forget(choice);
        super.onDestroy();
    }

    private SoulseekBridge ready() throws Exception {
        DownloadSettings.requireEnabled(this, DownloadSettings.SOULSEEK);
        SoulseekBridge bridge = SoulseekBridge.get();
        bridge.ready();
        DownloadDestinations.snapshot(this);
        if (!AudioFormatPreferences.snapshot(this).configured)
            throw new IOException("Choose your audio formats and their order first.");
        return bridge;
    }

    private void startSearch() {
        if (!foreground) return;
        String terms = query.getText().toString().trim();
        if (terms.length() < 2 || terms.length() > 256) {
            status.setText(PatchStrings.t("Search using 2–256 characters."));
            return;
        }
        chooseSoulseek();
        long intent = revision.incrementAndGet();
        results.removeAllViews();
        submit.setEnabled(false);
        query.setEnabled(false);
        status.setText(PatchStrings.t("Searching Soulseek…"));
        try {
            search =
                    worker.submit(
                            () -> {
                                try {
                                    if (!active(intent)) return;
                                    SoulseekBridge bridge = ready();
                                    String session = bridge.sessionTag;
                                    AudioFormatPreferences.Settings formats =
                                            AudioFormatPreferences.snapshot(this);
                                    List<SoulseekTrack> found =
                                            AudioFormatPreferences.ordered(
                                                    formats, bridge.search(terms));
                                    runOnUiThread(
                                            () -> {
                                                if (!active(intent)) return;
                                                submit.setEnabled(true);
                                                query.setEnabled(true);
                                                if (!session.equals(bridge.sessionTag)) {
                                                    status.setText(
                                                            PatchStrings.t(
                                                                    "Soulseek session changed."
                                                                            + " Search again."));
                                                    return;
                                                }
                                                status.setText(
                                                        PatchStrings.t(
                                                                found.isEmpty()
                                                                        ? "No matching files. Try"
                                                                              + " different search"
                                                                              + " words. BitChord"
                                                                              + " download was not"
                                                                              + " started."
                                                                        : "Choose a file below."
                                                                              + " Peer filenames,"
                                                                              + " formats and"
                                                                              + " availability are"
                                                                              + " unverified."));
                                                if (!found.isEmpty())
                                                    text(
                                                            results,
                                                            PatchStrings.t(
                                                                    "Details are peer claims. The"
                                                                        + " downloaded file's codec"
                                                                        + " is checked before it is"
                                                                        + " saved."),
                                                            14);
                                                for (SoulseekTrack track : found)
                                                    showResult(track, session, intent);
                                            });
                                } catch (Exception | LinkageError unavailable) {
                                    runOnUiThread(
                                            () -> {
                                                if (active(intent)) {
                                                    submit.setEnabled(true);
                                                    query.setEnabled(true);
                                                    status.setText(
                                                            PatchStrings.t(
                                                                    "Soulseek search could not"
                                                                        + " complete. Check"
                                                                        + " sign-in, formats and"
                                                                        + " copy destinations, then"
                                                                        + " retry."));
                                                }
                                            });
                                }
                            });
        } catch (RuntimeException busy) {
            submit.setEnabled(true);
            query.setEnabled(true);
            status.setText(PatchStrings.t("A previous search is stopping. Try again shortly."));
        }
    }

    private boolean active(long intent) {
        return foreground && revision.get() == intent && !isFinishing();
    }

    private void chooseSoulseek() {
        if (choiceCard != null) choiceCard.setVisibility(View.GONE);
        form.setVisibility(View.VISIBLE);
        results.setVisibility(View.VISIBLE);
    }

    private void refreshChoice() {
        if (originalButton == null) return;
        boolean available = SoulseekDownloadChoice.available(choice);
        originalButton.setEnabled(available);
        choiceStatus.setText(
                available
                        ? ""
                        : PatchStrings.t(
                                "This download choice expired. Return to the song and try again."));
    }

    private void showChoice() {
        revision.incrementAndGet();
        if (search != null) search.cancel(true);
        worker.getQueue().clear();
        submit.setEnabled(true);
        query.setEnabled(true);
        results.removeAllViews();
        form.setVisibility(View.GONE);
        results.setVisibility(View.GONE);
        choiceCard.setVisibility(View.VISIBLE);
        refreshChoice();
    }

    private void showResult(SoulseekTrack track, String session, long intent) {
        LinearLayout item = card(results);
        TextView title = text(item, track.title, 20);
        title.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        text(item, track.artist, 16);
        text(item, PatchStrings.t("Source: Soulseek"), 15);
        text(item, PatchStrings.f("Peer: {0}", track.peer), 14);
        text(
                item,
                PatchStrings.f(
                        "File: {0}",
                        track.path.substring(
                                Math.max(track.path.lastIndexOf('/'), track.path.lastIndexOf('\\'))
                                        + 1)),
                14);
        text(
                item,
                PatchStrings.f(
                        "Format: {0} · {1}",
                        track.advertisedExtension.toUpperCase(java.util.Locale.ROOT),
                        Formatter.formatShortFileSize(this, track.size)),
                14);
        Button download = button(item, PatchStrings.t("Download this file"));
        download.setTag("soulseek-search-download-" + track.id);
        download.setOnClickListener(
                view -> {
                    if (!active(intent)) return;
                    try {
                        SoulseekBridge bridge = ready();
                        if (!session.equals(bridge.sessionTag)
                                || !bridge.track(track.id).id.equals(track.id))
                            throw new IOException("Search result expired");
                        if (SoulseekSelectedDownloads.enqueueSelected(
                                this, "src:soulseek-native::" + track.id)) {
                            download.setEnabled(false);
                            status.setText(
                                    PatchStrings.t(
                                            "Source: Soulseek. Selected file queued. Open View"
                                                    + " downloads for progress."));
                        } else
                            status.setText(
                                    PatchStrings.t(
                                            "The selected file could not be queued. Review setup"
                                                    + " and try again."));
                    } catch (Exception unavailable) {
                        status.setText(
                                PatchStrings.t(
                                        "The selected file is no longer ready. Check setup and"
                                                + " search again."));
                    }
                });
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        downloadStyle.card(card);
        parent.addView(card);
        return card;
    }

    private TextView text(LinearLayout parent, String value, int size) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        parent.addView(text);
        return text;
    }

    private Button button(LinearLayout parent, String label) {
        Button button = new Button(this);
        button.setText(label);
        parent.addView(button);
        return button;
    }
}

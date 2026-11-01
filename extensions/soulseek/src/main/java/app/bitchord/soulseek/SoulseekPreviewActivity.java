package app.bitchord.soulseek;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.*;

import app.bitchord.telegram.CapturePolicy;
import app.bitchord.telegram.PatchStrings;
import app.bitchord.telegram.RecordingMatch;

import org.json.JSONObject;

import java.io.*;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Foreground preview and transparent evidence. Closing never silently saves or selects another
 * peer.
 */
public final class SoulseekPreviewActivity extends Activity {
    private static final int REFERENCE = 51, EXPORT = 52;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "streamseek-preview");
                        t.setDaemon(true);
                        return t;
                    });
    private final ScheduledExecutorService monitor =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "streamseek-preview-status");
                        t.setDaemon(true);
                        return t;
                    });
    private final AtomicLong revision = new AtomicLong();
    private volatile SoulseekPreview.Session session;
    private SoulseekPreview.Offer offer;
    private PreviewPlayer playback;
    private Future<?> operation;
    private ScheduledFuture<?> polling;
    private TextView status, formatText, referenceText, evidenceText;
    private Button play, keepNow, keepQueue, compare, confirm;
    private DownloadStyle style;
    private RecordingMatch.Reference reference;
    private JSONObject header, match;
    private String token, sha, exportText;
    private long received;
    private boolean foreground, confirmed, limitReached;

    @Override
    public void onCreate(Bundle saved) {
        setTheme(
                DownloadStyle.isDark(this)
                        ? android.R.style.Theme_Material_Dialog_NoActionBar
                        : android.R.style.Theme_Material_Light_Dialog_NoActionBar);
        super.onCreate(saved);
        CapturePolicy.apply(this);
        PatchStrings.refresh(this);
        style = new DownloadStyle(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(style.dp(20), style.dp(20), style.dp(20), style.dp(16));
        text(body, PatchStrings.t("Check the recording"), 22);
        try {
            token = getIntent().getStringExtra(SoulseekPreview.EXTRA);
            offer = SoulseekPreview.offer(token, this);
        } catch (Exception unavailable) {
            text(body, PatchStrings.t("Preview expired. Select the file again."), 16);
            button(body, PatchStrings.t("Done"), this::finish);
            show(body);
            return;
        }
        text(body, offer.track.title, 17);
        text(body, PatchStrings.f("Peer: {0}", offer.track.peer), 12);
        status = text(body, PatchStrings.t("Ready to preview the selected peer and file"), 14);
        formatText =
                text(
                        body,
                        PatchStrings.t("Advertised format: ")
                                + offer.track.advertisedExtension.toUpperCase(Locale.ROOT)
                                + " · unverified",
                        12);
        text(
                body,
                PatchStrings.t(
                        "Preview uses temporary data and storage. Playback stops after 30 seconds."
                                + " A fast peer may transfer the complete file."),
                12);
        LinearLayout playbackControls = controls(body);
        play = button(playbackControls, PatchStrings.t("Start preview"), this::startPreview);
        button(
                playbackControls,
                PatchStrings.t("Stop preview"),
                () -> stopPreview("Preview stopped"));
        LinearLayout admission = controls(body);
        keepNow = button(admission, PatchStrings.t("Keep download now"), () -> keep(true));
        keepQueue = button(admission, PatchStrings.t("Add to download queue"), () -> keep(false));
        keepNow.setEnabled(false);
        keepQueue.setEnabled(false);
        LinearLayout referenceGroup = section(body, "Reference audio");
        referenceText =
                text(
                        referenceGroup,
                        PatchStrings.t(
                                "Choose a known local recording as the reference. This build"
                                    + " compares the beginning of the files; it does not capture"
                                    + " YouTube playback."),
                        13);
        button(
                referenceGroup,
                PatchStrings.t("Choose reference audio"),
                () -> {
                    stopPreview("Choose a reference, then restart the preview");
                    startActivityForResult(
                            new Intent(Intent.ACTION_OPEN_DOCUMENT)
                                    .setType("audio/*")
                                    .addCategory(Intent.CATEGORY_OPENABLE),
                            REFERENCE);
                });
        compare = button(referenceGroup, PatchStrings.t("Compare audio excerpt"), this::compare);
        compare.setEnabled(false);
        LinearLayout evidence = section(body, "Recording evidence");
        evidenceText =
                text(
                        evidence,
                        PatchStrings.t(
                                "No audio comparison yet. A matching excerpt does not verify the"
                                        + " whole track, master or lossless origin."),
                        13);
        button(evidence, PatchStrings.t("Inspect complete-file checksum"), this::checksum);
        confirm =
                button(
                        evidence,
                        PatchStrings.t("Confirm correct recording"),
                        () -> {
                            if (sha != null) {
                                confirmed = !confirmed;
                                showEvidence();
                            }
                        });
        confirm.setEnabled(false);
        button(evidence, PatchStrings.t("Export match report"), this::exportReport);
        Button done = button(body, PatchStrings.t("Done"), this::finish);
        done.setGravity(android.view.Gravity.CENTER);
        done.setBackground(style.shape(style.field, 24, false));
        show(body);
    }

    private LinearLayout section(LinearLayout parent, String title) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(0, style.dp(18), 0, 0);
        View rule = new View(this);
        rule.setBackgroundColor(style.outline);
        section.addView(rule, new LinearLayout.LayoutParams(-1, style.dp(1)));
        text(section, PatchStrings.t(title), 16);
        parent.addView(section);
        return section;
    }

    private LinearLayout controls(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(
                getResources().getConfiguration().fontScale > 1.25f
                        ? LinearLayout.VERTICAL
                        : LinearLayout.HORIZONTAL);
        parent.addView(row);
        return row;
    }

    private void show(LinearLayout body) {
        ScrollView scroll =
                new ScrollView(this) {
                    @Override
                    protected void onMeasure(int width, int height) {
                        int max = getResources().getDisplayMetrics().heightPixels * 85 / 100;
                        super.onMeasure(
                                width,
                                View.MeasureSpec.makeMeasureSpec(
                                        Math.min(max, View.MeasureSpec.getSize(height)),
                                        View.MeasureSpec.AT_MOST));
                    }
                };
        scroll.addView(body);
        scroll.setClipToOutline(true);
        scroll.setBackground(style.shape(style.card, 22, false));
        setContentView(scroll);
        android.view.Window window = getWindow();
        window.setBackgroundDrawable(style.shape(style.card, 22, false));
        android.view.WindowManager.LayoutParams attrs = window.getAttributes();
        attrs.flags |= android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        window.setAttributes(attrs);
        window.setDimAmount(.28f);
        window.setLayout(
                Math.min(
                        style.dp(360),
                        getResources().getDisplayMetrics().widthPixels - style.dp(32)),
                -2);
    }

    private TextView text(LinearLayout body, String value, int size) {
        TextView view =
                style.text(
                        PatchStrings.t(value),
                        size,
                        size >= 16 ? style.foreground : style.secondary,
                        size >= 16 ? style.semibold : style.regular);
        view.setPadding(0, style.dp(6), 0, style.dp(6));
        if (size >= 16 && Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true);
        body.addView(view);
        return view;
    }

    private Button button(LinearLayout body, String value, Runnable action) {
        Button button = new Button(this);
        button.setText(PatchStrings.t(value));
        button.setTextSize(14);
        button.setTextColor(style.foreground);
        button.setTypeface(style.medium);
        button.setAllCaps(false);
        button.setGravity(android.view.Gravity.CENTER_VERTICAL);
        button.setMinHeight(style.dp(48));
        button.setMinimumHeight(style.dp(48));
        button.setPadding(style.dp(8), style.dp(10), style.dp(8), style.dp(10));
        button.setBackground(
                new android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(
                                style.dark ? 0x22ffffff : 0x17000000),
                        style.shape(android.graphics.Color.TRANSPARENT, 12, false),
                        null));
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params =
                body.getOrientation() == LinearLayout.HORIZONTAL
                        ? new LinearLayout.LayoutParams(0, -2, 1)
                        : new LinearLayout.LayoutParams(-1, -2);
        body.addView(button, params);
        style.markCustom(button);
        return button;
    }

    @Override
    protected void onStart() {
        super.onStart();
        foreground = true;
    }

    @Override
    protected void onStop() {
        foreground = false;
        stopPreview("Preview stopped when leaving the screen");
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        stopPreview("Preview closed");
        work.shutdownNow();
        monitor.shutdownNow();
        if (!isChangingConfigurations()) SoulseekPreview.forget(token);
        super.onDestroy();
    }

    private boolean current(long version) {
        return foreground && !isFinishing() && revision.get() == version;
    }

    private void startPreview() {
        if (offer == null) return;
        if (playback != null && session != null && !session.closed.get()) {
            try {
                if (playback.player.isPlaying()) {
                    playback.player.pause();
                    play.setText(PatchStrings.t("Resume preview"));
                } else {
                    if (limitReached) {
                        status.setText(
                                PatchStrings.t("Restart the preview to hear the beginning again"));
                        return;
                    }
                    playback.play();
                    play.setText(PatchStrings.t("Pause preview"));
                }
            } catch (Exception failed) {
                stopPreview("Preview could not resume");
            }
            return;
        }
        stopPreview("Preparing preview");
        final long version = revision.get();
        sha = null;
        confirmed = false;
        match = null;
        header = null;
        received = 0;
        limitReached = false;
        confirm.setEnabled(false);
        try {
            BulkTransferService.ensureRunning(this);
        } catch (Exception unavailable) {
            status.setText(
                    PatchStrings.t("Preview service could not start. Try again from this screen."));
            return;
        }
        play.setEnabled(false);
        operation =
                work.submit(
                        () -> {
                            SoulseekPreview.Session created = null;
                            try {
                                created = SoulseekPreview.start(this, offer);
                                final SoulseekPreview.Session started = created;
                                main.post(
                                        () -> {
                                            if (!current(version)) {
                                                started.close();
                                                return;
                                            }
                                            session = started;
                                            try {
                                                playback = new PreviewPlayer(this, started);
                                                play.setEnabled(true);
                                                play.setText(PatchStrings.t("Pause preview"));
                                                keepNow.setEnabled(true);
                                                keepQueue.setEnabled(true);
                                                compare.setEnabled(reference != null);
                                                poll(version, started);
                                            } catch (Exception | LinkageError unsupported) {
                                                stopPreview(
                                                        "Preview player is unavailable for this"
                                                                + " file or BitChord version");
                                            }
                                        });
                            } catch (Exception failed) {
                                if (created != null) created.close();
                                main.post(
                                        () -> {
                                            if (current(version)) {
                                                play.setEnabled(true);
                                                status.setText(
                                                        PatchStrings.t(
                                                                "Preview could not start. Check"
                                                                    + " Soulseek account, queue and"
                                                                    + " free space."));
                                            }
                                        });
                            }
                        });
    }

    private void poll(long version, SoulseekPreview.Session started) {
        if (polling != null) polling.cancel(false);
        polling =
                monitor.scheduleWithFixedDelay(
                        () -> {
                            try {
                                ProgressiveReader.Snapshot state = started.snapshot();
                                JSONObject inspected = null;
                                if ("failed".equals(state.status)
                                        || "cancelled".equals(state.status))
                                    throw new IOException("Peer transfer stopped");
                                if (header == null
                                        && state.downloaded >= 38
                                        && "flac".equals(offer.track.advertisedExtension)) {
                                    try (ProgressiveReader reader = started.reader(0, 38)) {
                                        byte[] bytes = new byte[38];
                                        int at = 0, n;
                                        while (at < 38 && (n = reader.read(bytes, at, 38 - at)) > 0)
                                            at += n;
                                        inspected = PreviewEvidence.flacHeader(bytes);
                                    }
                                }
                                JSONObject details = inspected;
                                main.post(
                                        () -> {
                                            if (!current(version)
                                                    || session != started
                                                    || playback == null) return;
                                            received = state.downloaded;
                                            if (details != null) {
                                                header = details;
                                                formatText.setText(
                                                        PatchStrings.f(
                                                                "Header inspected: FLAC {0}-bit /"
                                                                        + " {1} Hz · {2} channels",
                                                                details.optInt("bit_depth"),
                                                                details.optInt("sample_rate_hz"),
                                                                details.optInt("channels")));
                                            }
                                            if (!playback.format().isEmpty())
                                                formatText.setText(playback.format());
                                            String error = playback.error();
                                            if (!error.isEmpty()) {
                                                stopPreview(error);
                                                return;
                                            }
                                            if (playback.player.getCurrentPosition() >= 30_000) {
                                                playback.player.pause();
                                                limitReached = true;
                                                play.setText(PatchStrings.t("Preview complete"));
                                                play.setEnabled(false);
                                            }
                                            if (state.downloaded >= 64L * 1024 * 1024
                                                    && !state.complete) {
                                                stopPreview(
                                                        "Preview reached its 64 MiB temporary-data"
                                                            + " limit. Choose Download to keep the"
                                                            + " full file.");
                                                return;
                                            }
                                            String phase =
                                                    limitReached
                                                            ? "30-second preview complete"
                                                            : state.complete
                                                                    ? "Complete file temporarily"
                                                                            + " buffered"
                                                                    : state.downloaded == 0
                                                                            ? "Waiting for peer"
                                                                            : playback.player
                                                                                            .isPlaying()
                                                                                    ? "Playing"
                                                                                          + " preview"
                                                                                    : "Buffering or"
                                                                                          + " paused";
                                            status.setText(
                                                    PatchStrings.t(phase)
                                                            + " · "
                                                            + android.text.format.Formatter
                                                                    .formatShortFileSize(
                                                                            this, state.downloaded)
                                                            + " / "
                                                            + android.text.format.Formatter
                                                                    .formatShortFileSize(
                                                                            this, state.total));
                                        });
                            } catch (Exception failed) {
                                main.post(
                                        () -> {
                                            if (current(version) && session == started)
                                                stopPreview(
                                                        "Preview stopped. The session changed, the"
                                                                + " peer failed, or the temporary"
                                                                + " session expired.");
                                        });
                            }
                        },
                        0,
                        1,
                        TimeUnit.SECONDS);
    }

    private void keep(boolean now) {
        final SoulseekPreview.Session selected = session;
        if (selected == null) return;
        final long version = revision.get();
        keepNow.setEnabled(false);
        keepQueue.setEnabled(false);
        operation =
                work.submit(
                        () -> {
                            boolean accepted = false;
                            try {
                                accepted = selected.keep(now);
                            } catch (Exception ignored) {
                            }
                            final boolean success = accepted;
                            main.post(
                                    () -> {
                                        if (!current(version)) return;
                                        if (success) {
                                            stopPreview(
                                                    "Selected download retained. Follow its"
                                                            + " progress in Downloads.");
                                        } else {
                                            status.setText(
                                                    PatchStrings.t(
                                                            "Could not queue this file. Check"
                                                                    + " destination and format"
                                                                    + " settings."));
                                            keepNow.setEnabled(true);
                                            keepQueue.setEnabled(true);
                                        }
                                    });
                        });
    }

    private void compare() {
        final SoulseekPreview.Session selected = session;
        final RecordingMatch.Reference known = reference;
        if (selected == null || known == null) return;
        long version = revision.get();
        compare.setEnabled(false);
        evidenceText.setText(PatchStrings.t("Comparing up to 25 seconds of audio…"));
        operation =
                work.submit(
                        () -> {
                            try {
                                PreviewDecode.Pcm pcm =
                                        PreviewDecode.candidate(selected, selected::check);
                                JSONObject result =
                                        RecordingMatch.compare(
                                                known, pcm.samples, pcm.length, pcm.rate);
                                main.post(
                                        () -> {
                                            if (current(version)) {
                                                match = result;
                                                compare.setEnabled(true);
                                                showEvidence();
                                            }
                                        });
                            } catch (Exception failed) {
                                main.post(
                                        () -> {
                                            if (current(version)) {
                                                compare.setEnabled(true);
                                                evidenceText.setText(
                                                        PatchStrings.t(
                                                                "Audio comparison unavailable. No"
                                                                        + " match has been"
                                                                        + " established."));
                                            }
                                        });
                            }
                        });
    }

    private void checksum() {
        final SoulseekPreview.Session selected = session;
        if (selected == null) {
            status.setText(PatchStrings.t("Start a preview first"));
            return;
        }
        long version = revision.get();
        operation =
                work.submit(
                        () -> {
                            try {
                                ProgressiveReader.Snapshot state = selected.snapshot();
                                if (!state.complete) throw new IOException("Incomplete");
                                String digest =
                                        PreviewEvidence.sha256(
                                                state.file, state.total, selected::check);
                                main.post(
                                        () -> {
                                            if (current(version)) {
                                                received = state.downloaded;
                                                sha = digest;
                                                confirm.setEnabled(true);
                                                showEvidence();
                                            }
                                        });
                            } catch (Exception incomplete) {
                                main.post(
                                        () -> {
                                            if (current(version))
                                                evidenceText.setText(
                                                        PatchStrings.t(
                                                                "A complete buffered file is"
                                                                    + " required for its checksum."
                                                                    + " Preview excerpts cannot"
                                                                    + " verify the complete"
                                                                    + " file."));
                                        });
                            }
                        });
    }

    private void showEvidence() {
        String value =
                match == null
                        ? PatchStrings.t("No audio comparison yet")
                        : PatchStrings.t(
                                        match.optString("verdict").equals("excerpt_match")
                                                ? "Audio excerpt matched"
                                                : "No confident excerpt match")
                                + "\n"
                                + String.format(
                                        Locale.ROOT,
                                        "%.1f s checked · %d / %d landmarks · offset %.2f s",
                                        match.optDouble("candidate_seconds"),
                                        match.optInt("aligned_landmarks"),
                                        match.optInt("candidate_landmarks"),
                                        match.optDouble("reference_offset_seconds"));
        if (sha != null)
            value +=
                    "\nSHA-256: "
                            + sha
                            + "\n"
                            + PatchStrings.t(
                                    "Checksum identifies this file; no trusted reference checksum"
                                            + " was compared.");
        if (confirmed)
            value += "\n" + PatchStrings.t("Confirmed by you for this complete file hash");
        value +=
                "\n"
                        + PatchStrings.t(
                                "Whole recording, mastering and lossless origin remain"
                                        + " unverified.");
        evidenceText.setText(value);
        confirm.setText(
                PatchStrings.t(
                        confirmed ? "Withdraw my confirmation" : "Confirm correct recording"));
    }

    private void exportReport() {
        try {
            exportText =
                    PreviewEvidence.report(offer.track, received, header, match, sha, confirmed)
                            .toString(2);
            startActivityForResult(
                    new Intent(Intent.ACTION_CREATE_DOCUMENT)
                            .setType("application/json")
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .putExtra(
                                    Intent.EXTRA_TITLE,
                                    "streamseek-match-"
                                            + offer.track.id.substring(0, 12)
                                            + ".json"),
                    EXPORT);
        } catch (Exception unavailable) {
            status.setText(PatchStrings.t("No preview report is available"));
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == REFERENCE) {
            long version = revision.get();
            referenceText.setText(PatchStrings.t("Reading reference audio…"));
            operation =
                    work.submit(
                            () -> {
                                try {
                                    PreviewDecode.Pcm pcm =
                                            PreviewDecode.local(
                                                    this,
                                                    uri,
                                                    () -> {
                                                        if (Thread.currentThread().isInterrupted())
                                                            throw new InterruptedIOException();
                                                    });
                                    RecordingMatch.Reference selected =
                                            RecordingMatch.reference(
                                                    pcm.samples,
                                                    pcm.length,
                                                    pcm.rate,
                                                    displayName(uri));
                                    main.post(
                                            () -> {
                                                if (!isFinishing() && revision.get() == version) {
                                                    reference = selected;
                                                    referenceText.setText(
                                                            PatchStrings.f(
                                                                    "Reference: {0} · {1} seconds",
                                                                    selected.name,
                                                                    String.format(
                                                                            Locale.ROOT,
                                                                            "%.1f",
                                                                            selected.seconds)));
                                                    compare.setEnabled(session != null);
                                                }
                                            });
                                } catch (Exception invalid) {
                                    main.post(
                                            () ->
                                                    referenceText.setText(
                                                            PatchStrings.t(
                                                                    "Reference audio could not be"
                                                                            + " decoded")));
                                }
                            });
        } else if (request == EXPORT && exportText != null) {
            String report = exportText;
            work.submit(
                    () -> {
                        try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                            if (out == null) throw new IOException();
                            out.write(report.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            main.post(
                                    () -> status.setText(PatchStrings.t("Match report exported")));
                        } catch (Exception failed) {
                            main.post(
                                    () ->
                                            status.setText(
                                                    PatchStrings.t(
                                                            "Match report could not be exported")));
                        }
                    });
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor =
                getContentResolver()
                        .query(
                                uri,
                                new String[] {OpenableColumns.DISPLAY_NAME},
                                null,
                                null,
                                null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) {
        }
        return "Selected local reference";
    }

    private void stopPreview(String message) {
        revision.incrementAndGet();
        if (operation != null) operation.cancel(true);
        if (polling != null) polling.cancel(false);
        operation = null;
        polling = null;
        SoulseekPreview.Session previous = session;
        session = null;
        if (previous != null) previous.close();
        if (playback != null) playback.close();
        playback = null;
        if (play != null) {
            play.setEnabled(true);
            play.setText(PatchStrings.t("Start preview"));
            keepNow.setEnabled(false);
            keepQueue.setEnabled(false);
            compare.setEnabled(false);
            status.setText(PatchStrings.t(message));
        }
    }
}

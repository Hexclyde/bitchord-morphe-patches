package app.bitchord.soulseek;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** User-initiated links and selected audio files; debrid services are not catalogues. */
public final class DebridDownloadsActivity extends Activity {
    private DebridDownloads downloads;
    private DebridCredentials credentials;
    private Spinner provider;
    private EditText link;
    private TextView notice;
    private LinearLayout results, jobs, recent;
    private String preparedId = "";
    private final Map<String, CheckBox> selections = new LinkedHashMap<>();
    private boolean destroyed, working, destinationAsked, formatsAsked;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<Runnable>(16),
                    r -> new Thread(r, "debrid-download-actions"));
    private final Runnable refresh =
            new Runnable() {
                public void run() {
                    renderJobs();
                    ui.postDelayed(this, 1000);
                }
            };

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) preparedId = state.getString("preparedId", "");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false);
        if (Build.VERSION.SDK_INT >= 31) getWindow().setHideOverlayWindows(true);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (20 * getResources().getDisplayMetrics().density);
        body.setPadding(p, p, p, p);
        scroll.addView(body);
        setContentView(scroll);
        button(body, "Back", () -> finish());
        text(body, "Debrid downloads", 24);
        text(
                body,
                "Choose an enabled service and paste a supported HTTPS link or magnet. Select the"
                    + " audio files to save using your chosen destination and format preferences.",
                16);
        text(
                body,
                "Resolve may create a transfer in your service account. Account limits and host"
                    + " support apply. Only selected audio files are saved here; this page does not"
                    + " search a music catalogue or stream audio.",
                14);
        button(
                body,
                "Services and API keys",
                () -> startActivity(new Intent(this, DebridSetupActivity.class)));
        button(body, "Where copies go", () -> DownloadDestinations.openSettings(this));
        button(body, "Formats, best first", () -> AudioFormatPreferences.openSettings(this));
        provider = new Spinner(this);
        List<String> names = new ArrayList<>();
        for (DebridProvider.Kind k : DebridProvider.Kind.values()) names.add(k.label);
        ArrayAdapter<String> adapter =
                new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        provider.setAdapter(adapter);
        body.addView(provider);
        link = new EditText(this);
        link.setHint("HTTPS link or magnet");
        link.setSingleLine(true);
        link.setSaveEnabled(false);
        link.setFilters(
                new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(8192)});
        if (Build.VERSION.SDK_INT >= 26)
            link.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        body.addView(link);
        notice = text(body, "Loading download settings…", 14);
        button(
                body,
                "Resolve link",
                () -> {
                    if (working) return;
                    final DebridProvider.Kind kind =
                            DebridProvider.Kind.values()[provider.getSelectedItemPosition()];
                    final String source = link.getText().toString().trim();
                    if (source.isEmpty()) {
                        notice.setText("Paste a supported link first.");
                        return;
                    }
                    runAction(
                            () -> {
                                requireReady();
                                credentials.enabledKey(kind);
                                DebridDownloads.Prepared prepared = downloads.prepare(kind, source);
                                runOnUiThread(() -> showPrepared(prepared));
                                return "Link checked. Select the audio files to save, or refresh a"
                                        + " pending transfer.";
                            });
                });
        button(
                body,
                "Refresh provider transfer",
                () -> {
                    if (preparedId.isEmpty()) {
                        notice.setText("Resolve a link first.");
                        return;
                    }
                    final String id = preparedId;
                    runAction(
                            () -> {
                                requireReady();
                                DebridDownloads.Prepared p1 = downloads.refresh(id);
                                runOnUiThread(() -> showPrepared(p1));
                                return "Provider transfer refreshed.";
                            });
                });
        results = group(body);
        text(body, "Recent provider transfers", 18);
        recent = group(body);
        button(
                body,
                "Download selected audio",
                () -> {
                    if (working) return;
                    if (!DownloadDestinations.ensureConfigured(this)
                            || !AudioFormatPreferences.ensureConfigured(this)) return;
                    final List<String> selected = new ArrayList<>();
                    for (Map.Entry<String, CheckBox> item : selections.entrySet())
                        if (item.getValue().isChecked()) selected.add(item.getKey());
                    if (preparedId.isEmpty() || selected.isEmpty()) {
                        notice.setText("Select at least one available audio file.");
                        return;
                    }
                    final String id = preparedId;
                    final DownloadDestinations.Snapshot destination;
                    final String formats =
                            AudioFormatPreferences.encode(AudioFormatPreferences.snapshot(this));
                    try {
                        destination = DownloadDestinations.snapshot(this);
                    } catch (Exception unavailable) {
                        notice.setText("Choose an available save destination first.");
                        return;
                    }
                    runAction(
                            () -> {
                                requireReady();
                                List<String> ids =
                                        downloads.enqueue(id, selected, destination, formats);
                                return ids.size()
                                        + " selected file(s) queued using your saved copy"
                                        + " destinations.";
                            });
                });
        button(
                body,
                "Open copy queue",
                () -> startActivity(new Intent(this, BulkDownloadsActivity.class)));
        text(body, "Debrid transfer queue", 20);
        text(
                body,
                "Pause or cancel stops local fetching. A transfer already created in your service"
                    + " account may continue there. Use that service's account page to manage it.",
                14);
        jobs = group(body);
        runAction(
                () -> {
                    credentials = DebridCredentials.get(getApplicationContext());
                    downloads = DebridDownloads.get(getApplicationContext());
                    for (DebridDownloads.Prepared prepared : downloads.prepared())
                        if (prepared.id.equals(preparedId))
                            runOnUiThread(() -> showPrepared(prepared));
                    return "Choose an enabled service, or reopen an existing provider transfer.";
                });
    }

    private void requireReady() throws java.io.IOException {
        if (downloads == null || credentials == null)
            throw new java.io.IOException("Download settings are unavailable.");
    }

    private void showPrepared(DebridDownloads.Prepared prepared) {
        if (destroyed || isFinishing()) return;
        preparedId = prepared.id;
        selections.clear();
        results.removeAllViews();
        text(results, prepared.kind.label + " · " + prepared.state, 18);
        if (prepared.message != null && !prepared.message.isEmpty())
            text(results, prepared.message, 14);
        AudioFormatPreferences.Settings policy = AudioFormatPreferences.snapshot(this);
        int audio = 0;
        for (DebridModels.Entry entry : prepared.entries) {
            if (!selectable(entry, policy)) continue;
            try {
                String name = AudioFormatPreferences.audioName(entry.name);
                CheckBox selected = new CheckBox(this);
                selected.setText(name + " · " + bytes(entry.size));
                results.addView(selected);
                selections.put(entry.fileId, selected);
                audio++;
            } catch (java.io.IOException invalidName) {
                /* Unsupported names do not become download paths. */
            }
        }
        if (audio == 0)
            text(
                    results,
                    prepared.ready
                            ? "No files match your enabled audio formats and size limit."
                            : "The provider is still preparing files. Refresh this transfer later.",
                    14);
    }

    static boolean selectable(DebridModels.Entry entry, AudioFormatPreferences.Settings settings) {
        if (entry.size > DebridModels.MAX_BYTES || entry.size == 0) return false;
        for (AudioFormatPreferences.Format format :
                AudioFormatPreferences.candidateFormats(entry.name))
            if (settings.enabled.contains(format)) return true;
        return false;
    }

    private void renderJobs() {
        if (downloads == null || jobs == null || destroyed || isFinishing()) return;
        jobs.removeAllViews();
        List<DebridDownloads.Job> list = downloads.jobs();
        recent.removeAllViews();
        for (DebridDownloads.Prepared prepared : downloads.prepared()) {
            button(
                    recent,
                    prepared.kind.label
                            + " · "
                            + prepared.state
                            + " · "
                            + prepared.id.substring(0, Math.min(6, prepared.id.length())),
                    () -> showPrepared(prepared));
            button(
                    recent,
                    "Dismiss link here (keep provider transfer)",
                    () ->
                            runAction(
                                    () -> {
                                        downloads.discardPrepared(prepared.id);
                                        runOnUiThread(
                                                () -> {
                                                    if (prepared.id.equals(preparedId)) {
                                                        preparedId = "";
                                                        selections.clear();
                                                        results.removeAllViews();
                                                    }
                                                });
                                        return "Link dismissed here. The provider-side transfer was"
                                                + " not removed.";
                                    }));
        }
        if (list.isEmpty()) text(jobs, "No debrid downloads queued.", 14);
        for (DebridDownloads.Job job : list) {
            text(jobs, job.name + " · " + job.kind.label, 16);
            text(
                    jobs,
                    job.state
                            + " · "
                            + bytes(job.bytes)
                            + (job.total > 0 ? " / " + bytes(job.total) : "")
                            + "\n"
                            + job.message,
                    14);
            if (job.bulkJobId != null && !job.bulkJobId.isEmpty()) {
                button(
                        jobs,
                        "Check saved copies",
                        () -> startActivity(new Intent(this, BulkDownloadsActivity.class)));
            } else {
                button(
                        jobs,
                        "Pause",
                        () ->
                                runAction(
                                        () -> {
                                            downloads.pause(job.id);
                                            return "Local debrid fetching paused.";
                                        }));
                button(
                        jobs,
                        "Resume",
                        () ->
                                runAction(
                                        () -> {
                                            downloads.resume(job.id);
                                            return "Local debrid fetching resumed.";
                                        }));
                button(
                        jobs,
                        "Cancel local download",
                        () ->
                                runAction(
                                        () -> {
                                            downloads.cancel(job.id);
                                            return "Local download cancelled. A provider-side"
                                                    + " transfer may still exist in your"
                                                    + " account.";
                                        }));
            }
            if (!"RUNNING".equals(job.state) && !"QUEUED".equals(job.state))
                button(
                        jobs,
                        "Dismiss local status",
                        () ->
                                runAction(
                                        () -> {
                                            downloads.forget(job.id);
                                            return "Local status dismissed. Saved-copy receipts"
                                                    + " remain in the copy queue.";
                                        }));
        }
    }

    interface Action {
        String run() throws Exception;
    }

    private void runAction(Action action) {
        if (working) {
            notice.setText("Wait for the current action to finish.");
            return;
        }
        working = true;
        try {
            worker.execute(
                    () -> {
                        String message;
                        try {
                            credentials = DebridCredentials.get(getApplicationContext());
                            downloads = DebridDownloads.get(getApplicationContext());
                            message = action.run();
                        } catch (Exception | LinkageError failure) {
                            message =
                                    "This action could not finish. Check the enabled service, API"
                                            + " key, supported link, account limits and connection,"
                                            + " then retry.";
                        }
                        final String result = message;
                        runOnUiThread(
                                () -> {
                                    working = false;
                                    if (!destroyed && !isFinishing()) {
                                        notice.setText(result);
                                        renderJobs();
                                    }
                                });
                    });
        } catch (java.util.concurrent.RejectedExecutionException full) {
            working = false;
            notice.setText("Too many pending actions. Wait and retry.");
        }
    }

    private static String bytes(long value) {
        return value < 0
                ? "size not reported"
                : value < 1024 * 1024
                        ? value + " bytes"
                        : String.format(java.util.Locale.ROOT, "%.1f MiB", value / (1024.0 * 1024));
    }

    private LinearLayout group(LinearLayout parent) {
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.VERTICAL);
        parent.addView(result);
        return result;
    }

    private TextView text(LinearLayout parent, String value, int size) {
        TextView result = new TextView(this);
        result.setText(value);
        result.setTextSize(size);
        result.setPadding(0, 10, 0, 10);
        parent.addView(result);
        return result;
    }

    private void button(LinearLayout parent, String label, Runnable action) {
        Button result = new Button(this);
        result.setText(label);
        result.setOnClickListener(v -> action.run());
        parent.addView(result);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refresh);
        if (!destinationAsked) {
            destinationAsked = true;
            if (!DownloadDestinations.ensureConfigured(this)) return;
        }
        if (!formatsAsked) {
            try {
                DownloadDestinations.snapshot(this);
            } catch (Exception missing) {
                return;
            }
            formatsAsked = true;
            AudioFormatPreferences.ensureConfigured(this);
        }
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(refresh);
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString("preparedId", preparedId);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacksAndMessages(null);
        if (link != null) link.setText("");
        worker.shutdownNow();
        super.onDestroy();
    }
}

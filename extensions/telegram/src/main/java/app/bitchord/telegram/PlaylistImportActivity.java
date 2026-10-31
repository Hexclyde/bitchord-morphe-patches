package app.bitchord.telegram;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Native import review; catalog lookups never write to a service or device playlist. */
public final class PlaylistImportActivity extends Activity {
    private TelegramStyle style;
    private TextView status, save, cancel;
    private ListView list;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Row> rows = new ArrayList<>();
    private List<PlaylistImport.Playlist> playlists;
    private volatile boolean stopped;
    private boolean ready;
    private Review adapter;

    private static final class Row {
        final int playlist;
        final PlaylistImport.Track source;
        List<Object> candidates = new ArrayList<>();
        Object selected;
        boolean reviewed, skipped, failed;

        Row(int playlist, PlaylistImport.Track source) {
            this.playlist = playlist;
            this.source = source;
        }
    }

    @Override
    public void onCreate(Bundle state) {
        boolean dark = TelegramStyle.isDark(this);
        setTheme(
                dark
                        ? android.R.style.Theme_Material_NoActionBar
                        : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);
        Strings.refresh(this);
        style = new TelegramStyle(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(1);
        root.setPadding(style.dp(20), style.dp(8), style.dp(20), style.dp(16));
        root.setBackgroundColor(style.background);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(
                style.backButton(this::finish),
                new LinearLayout.LayoutParams(style.dp(48), style.dp(48)));
        TextView title =
                style.text(Strings.t("Import playlists"), 24, style.foreground, style.semibold);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(header);
        status = style.text(Strings.t("Reading playlist…"), 14, style.secondary, style.regular);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setPadding(0, style.dp(12), 0, style.dp(12));
        root.addView(status);
        list = new ListView(this);
        list.setDivider(null);
        list.setCacheColorHint(android.graphics.Color.TRANSPARENT);
        adapter = new Review();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        cancel =
                style.button(
                        Strings.t("Cancel"),
                        false,
                        () -> {
                            stopped = true;
                            worker.shutdownNow();
                            finish();
                        });
        root.addView(cancel);
        save = style.button(Strings.t("Save playlists"), true, this::save);
        save.setEnabled(false);
        save.setAlpha(.45f);
        LinearLayout.LayoutParams bottom = new LinearLayout.LayoutParams(-1, -2);
        bottom.topMargin = style.dp(8);
        root.addView(save, bottom);
        setContentView(root);
        Uri uri = getIntent().getData();
        if (uri == null) {
            status.setText(Strings.t("Choose a playlist file first."));
            return;
        }
        worker.execute(() -> load(uri));
    }

    @Override
    protected void onDestroy() {
        stopped = true;
        worker.shutdownNow();
        super.onDestroy();
    }

    private boolean active() {
        return !stopped && !isFinishing() && !isDestroyed();
    }

    private void ui(Runnable run) {
        runOnUiThread(
                () -> {
                    if (active()) run.run();
                });
    }

    private void load(Uri uri) {
        try {
            String name = "Imported playlist";
            try (android.database.Cursor cursor =
                    getContentResolver()
                            .query(
                                    uri,
                                    new String[] {OpenableColumns.DISPLAY_NAME},
                                    null,
                                    null,
                                    null)) {
                if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
            }
            String raw;
            try (InputStream input = getContentResolver().openInputStream(uri);
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] block = new byte[8192];
                int count;
                while ((count = input.read(block)) != -1) {
                    if (stopped) return;
                    if (bytes.size() + count > 16 * 1024 * 1024)
                        throw new IOException("File too large");
                    bytes.write(block, 0, count);
                }
                raw = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            }
            playlists = PlaylistImport.parse(raw, name);
            List<Row> parsed = new ArrayList<>();
            for (int p = 0; p < playlists.size(); p++)
                for (PlaylistImport.Track track : playlists.get(p).tracks)
                    parsed.add(new Row(p, track));
            ui(
                    () -> {
                        rows.addAll(parsed);
                        adapter.notifyDataSetChanged();
                    });
            Map<String, List<Object>> cache = new HashMap<>();
            for (int at = 0; at < parsed.size(); at++) {
                if (stopped || Thread.currentThread().isInterrupted()) return;
                Row row = parsed.get(at);
                Object selected = row.source.direct();
                List<Object> candidates = new ArrayList<>();
                boolean exact = selected != null, failed = false;
                if (exact) candidates.add(selected);
                else
                    try {
                        String query = row.source.artist + "\n" + row.source.title;
                        List<Object> found = cache.get(query);
                        if (found == null) {
                            found = PlaylistImport.search(row.source);
                            if (cache.size() < 200) cache.put(query, found);
                        }
                        candidates.addAll(found);
                        candidates.sort(
                                (a, b) ->
                                        Double.compare(
                                                ImportConfidence.score(row.source, b),
                                                ImportConfidence.score(row.source, a)));
                        Object best = PlaylistImport.best(row.source, candidates);
                        if (best != null
                                && !row.source.artist.isEmpty()
                                && ImportConfidence.score(row.source, best) >= .85) selected = best;
                    } catch (InterruptedException cancelled) {
                        return;
                    } catch (Exception unavailable) {
                        failed = true;
                    }
                final Object chosen = selected;
                final List<Object> options = candidates;
                final boolean error = failed;
                final int progress = at + 1;
                ui(
                        () -> {
                            row.candidates = options;
                            row.selected = chosen;
                            row.reviewed = exact || chosen != null;
                            row.failed = error;
                            status.setText(
                                    Strings.f("Matching {0} of {1}", progress, parsed.size()));
                            adapter.notifyDataSetChanged();
                        });
                if (!exact) Thread.sleep(150);
            }
            ui(
                    () -> {
                        ready = true;
                        cancel.setText(Strings.t("Skip unresolved tracks"));
                        cancel.setOnClickListener(
                                v -> {
                                    for (Row row : rows)
                                        if (!row.reviewed) {
                                            row.skipped = true;
                                            row.reviewed = true;
                                            row.selected = null;
                                        }
                                    update();
                                });
                        update();
                    });
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
        } catch (Exception invalid) {
            ui(
                    () ->
                            status.setText(
                                    Strings.t(
                                            "Could not read this file. Choose JSON, CSV, TSV, TXT"
                                                    + " or M3U playlist metadata.")));
        }
    }

    private void update() {
        int selected = 0, review = 0, skipped = 0;
        for (Row row : rows) {
            if (row.selected != null && !row.skipped) selected++;
            if (!row.reviewed) review++;
            if (row.skipped) skipped++;
        }
        status.setText(
                Strings.f("{0} matched · {1} to review · {2} skipped", selected, review, skipped));
        save.setEnabled(ready && selected > 0 && review == 0);
        save.setAlpha(save.isEnabled() ? 1 : .45f);
        adapter.notifyDataSetChanged();
    }

    private final class Review extends BaseAdapter {
        public int getCount() {
            return rows.size();
        }

        public Object getItem(int at) {
            return rows.get(at);
        }

        public long getItemId(int at) {
            return at;
        }

        public View getView(int at, View recycled, android.view.ViewGroup parent) {
            Row row = rows.get(at);
            LinearLayout item = new LinearLayout(PlaylistImportActivity.this);
            item.setOrientation(1);
            item.setPadding(style.dp(12), style.dp(14), style.dp(12), style.dp(14));
            item.setMinimumHeight(style.dp(76));
            TextView title = style.text(row.source.title, 16, style.foreground, style.semibold);
            title.setMaxLines(2);
            item.addView(title);
            TextView artist = style.text(row.source.artist, 13, style.secondary, style.regular);
            item.addView(artist);
            String match =
                    row.skipped
                            ? Strings.t("Skipped")
                            : row.selected != null
                                    ? Strings.f(
                                            "Matched: {0}",
                                            MixedSongCodec.text(row.selected, "artist"))
                                    : row.failed
                                            ? Strings.t("Search failed · tap to review")
                                            : Strings.t("Review match");
            TextView detail = style.text(match, 13, style.secondary, style.regular);
            detail.setPadding(0, style.dp(4), 0, 0);
            item.addView(detail);
            item.setClickable(true);
            item.setFocusable(true);
            item.setOnClickListener(
                    v -> {
                        if (ready) choose(row);
                    });
            return item;
        }
    }

    private void choose(Row row) {
        String[] labels = new String[row.candidates.size() + 1];
        int selected = labels.length - 1;
        for (int i = 0; i < row.candidates.size(); i++) {
            Object song = row.candidates.get(i);
            labels[i] =
                    MixedSongCodec.text(song, "title")
                            + "\n"
                            + MixedSongCodec.text(song, "artist")
                            + " · "
                            + MixedSongCodec.text(song, "durationText");
            if (row.selected != null
                    && MixedSongCodec.key(song).equals(MixedSongCodec.key(row.selected)))
                selected = i;
        }
        labels[labels.length - 1] = Strings.t("Skip this track");
        AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setTitle(row.source.title)
                        .setSingleChoiceItems(
                                labels,
                                selected,
                                (d, index) -> {
                                    row.reviewed = true;
                                    row.skipped = index == row.candidates.size();
                                    row.selected = row.skipped ? null : row.candidates.get(index);
                                    d.dismiss();
                                    update();
                                })
                        .setNegativeButton(Strings.t("Cancel"), null)
                        .create();
        dialog.setOnShowListener(d -> style.tuneDialog(dialog));
        dialog.show();
    }

    private void save() {
        if (!ready) return;
        List<ImportedPlaylist> imported = new ArrayList<>();
        for (int p = 0; p < playlists.size(); p++) {
            List<Object> songs = new ArrayList<>();
            for (Row row : rows)
                if (row.playlist == p && !row.skipped && row.selected != null)
                    songs.add(row.selected);
            if (!songs.isEmpty())
                imported.add(
                        new ImportedPlaylist(
                                playlists.get(p).title, songs, playlists.get(p).unavailable));
        }
        if (imported.isEmpty()) return;
        AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setTitle(Strings.t("Save playlists"))
                        .setMessage(
                                Strings.f(
                                        "Create {0} independent playlists on this device?",
                                        imported.size()))
                        .setNegativeButton(Strings.t("Cancel"), null)
                        .setPositiveButton(
                                Strings.t("Save"),
                                (d, w) -> {
                                    try {
                                        MixedPlaylists.importAll(imported);
                                        stopped = true;
                                        Toast.makeText(this, Strings.t("Saved"), Toast.LENGTH_SHORT)
                                                .show();
                                        finish();
                                    } catch (Exception failure) {
                                        status.setText(Strings.t("Could not save this playlist."));
                                    }
                                })
                        .create();
        dialog.setOnShowListener(d -> style.tuneDialog(dialog));
        dialog.show();
    }
}

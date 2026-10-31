package app.bitchord.telegram;

import android.app.*;
import android.content.*;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;

import org.json.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Native playlist links, device copies, queue snapshots and import. */
public final class PlaylistToolsActivity extends Activity {
    private TelegramStyle style;
    private LinearLayout body, actionGroup;
    private TextView status;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean busy;
    private static final int IMPORT = 41;

    @Override
    public void onCreate(Bundle saved) {
        boolean dark = TelegramStyle.isDark(this);
        setTheme(
                dark
                        ? android.R.style.Theme_Material_NoActionBar
                        : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(saved);
        Strings.refresh(this);
        style = new TelegramStyle(this);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (style != null && !busy) build();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private TextView label(String text, int size) {
        return style.text(
                text,
                size,
                size < 16 ? style.secondary : style.foreground,
                size >= 20 ? style.semibold : style.regular);
    }

    private void build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(style.background);
        body = new LinearLayout(this);
        body.setOrientation(1);
        body.setPadding(style.dp(20), style.dp(8), style.dp(20), style.dp(32));
        body.setBackgroundColor(style.background);
        scroll.addView(body);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(
                style.backButton(this::finish),
                new LinearLayout.LayoutParams(style.dp(48), style.dp(48)));
        TextView pageTitle = style.text(Strings.t("Playlists"), 28, style.foreground, style.heavy);
        LinearLayout.LayoutParams titleSpace = new LinearLayout.LayoutParams(0, -2, 1);
        titleSpace.leftMargin = style.dp(12);
        header.addView(pageTitle, titleSpace);
        body.addView(header);
        TextView intro =
                style.text(
                        Strings.t("Saved links, device playlists and imports"),
                        14,
                        style.secondary,
                        style.regular);
        intro.setPadding(0, style.dp(12), 0, style.dp(16));
        body.addView(intro);
        status = style.text("", 14, style.secondary, style.regular);
        status.setVisibility(View.GONE);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(status);
        actionGroup = PlaylistRows.group(body, style);
        action(
                Strings.t("Open playlist link"),
                Strings.t("Open, keep in Library, or save a device copy"),
                this::linkDialog);
        action(
                Strings.t("Save queue"),
                Strings.t("Current track and upcoming queue"),
                () -> {
                    try {
                        List<Object> queue = GuestPlaylists.queue();
                        if (queue.isEmpty()) throw new IllegalStateException();
                        name(Strings.t("Save queue"), title -> save(title, queue));
                    } catch (Exception empty) {
                        message(Strings.t("Add tracks to the queue first."));
                    }
                });
        heading(Strings.t("Saved playlists"));
        try {
            JSONArray bookmarks = GuestPlaylists.bookmarks();
            if (bookmarks.length() == 0)
                body.addView(label(Strings.t("No saved playlist links yet."), 14));
            for (int i = 0; i < bookmarks.length(); i++) {
                JSONObject bookmark = bookmarks.getJSONObject(i);
                String id = bookmark.getString("id"), title = bookmark.getString("title");
                action(
                        title,
                        Strings.t("Saved YouTube playlist link"),
                        () -> bookmarkActions(id, title));
            }
        } catch (Exception invalid) {
            message(Strings.t("Could not load playlists."));
        }
        heading(Strings.t("Device playlists"));
        try {
            int count = 0;
            for (Object playlist : MixedPlaylists.nativePlaylists()) {
                if (!MixedSongCodec.text(playlist, "id").startsWith("bc_mix_")) continue;
                count++;
                int songs = MixedPlaylists.songs(playlist).size();
                action(
                        MixedSongCodec.text(playlist, "title"),
                        songs == 1 ? Strings.t("1 song") : Strings.f("{0} songs", songs),
                        () -> edit(playlist));
            }
            if (count == 0) body.addView(label(Strings.t("No device playlists yet."), 14));
        } catch (Exception unavailable) {
            message(Strings.t("Could not load playlists."));
        }
        heading(Strings.t("Import playlists"));
        action(
                Strings.t("Import playlist file"),
                Strings.t("JSON, CSV, TXT or M3U · review matches before saving"),
                () -> {
                    try {
                        startActivityForResult(
                                new Intent(Intent.ACTION_OPEN_DOCUMENT)
                                        .addCategory(Intent.CATEGORY_OPENABLE)
                                        .setType("*/*")
                                        .putExtra(
                                                Intent.EXTRA_MIME_TYPES,
                                                new String[] {
                                                    "application/json",
                                                    "text/*",
                                                    "application/octet-stream",
                                                    "audio/x-mpegurl",
                                                    "audio/mpegurl",
                                                    "application/x-mpegurl",
                                                    "application/vnd.apple.mpegurl"
                                                }),
                                IMPORT);
                    } catch (Exception unavailable) {
                        message(Strings.t("No file picker is available."));
                    }
                });
        heading(Strings.t("Connected services"));
        action(
                Strings.t("Playlist sync"),
                Strings.t("Spotify and YouTube Music · manual two-way sync"),
                () -> startActivity(new Intent(this, PlaylistSyncActivity.class)));
        setContentView(scroll);
    }

    private void heading(String title) {
        TextView text = style.text(title, 13, style.secondary, style.semibold);
        text.setPadding(style.dp(16), style.dp(20), style.dp(16), style.dp(8));
        body.addView(text);
        actionGroup = PlaylistRows.group(body, style);
    }

    private void action(String title, String summary, Runnable action) {
        PlaylistRows.add(
                actionGroup == null ? body : actionGroup,
                style,
                title,
                summary,
                () -> {
                    if (!busy) action.run();
                });
    }

    private void message(String text) {
        if (status != null) {
            status.setText(text);
            status.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    interface Named {
        void accept(String name) throws Exception;
    }

    private void name(String title, Named callback) {
        input(
                title,
                "",
                text -> {
                    if (text.trim().isEmpty() || text.length() > 300)
                        throw new IllegalArgumentException();
                    callback.accept(text.trim());
                });
    }

    private void input(String title, String initial, Named callback) {
        EditText value = new EditText(this);
        value.setSingleLine();
        value.setText(initial);
        value.setInputType(InputType.TYPE_CLASS_TEXT);
        value.setContentDescription(title);
        AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setTitle(title)
                        .setView(value)
                        .setNegativeButton(Strings.t("Cancel"), null)
                        .setPositiveButton(Strings.t("Save"), null)
                        .create();
        dialog.setOnShowListener(
                d -> {
                    style.tuneDialog(dialog);
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                            .setOnClickListener(
                                    v -> {
                                        try {
                                            callback.accept(value.getText().toString());
                                            dialog.dismiss();
                                        } catch (Exception invalid) {
                                            value.setError(
                                                    Strings.t("Check this value and try again."));
                                        }
                                    });
                });
        dialog.show();
    }

    private void linkDialog() {
        input(
                Strings.t("Open playlist link"),
                "",
                value -> {
                    String id = GuestPlaylists.playlistId(value);
                    bookmarkActions(id, id);
                });
    }

    private void bookmarkActions(String id, String title) {
        String[] choices = {
            Strings.t("Open"),
            Strings.t("Keep in library"),
            Strings.t("Save a device copy"),
            Strings.t("Remove saved link")
        };
        AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setTitle(title)
                        .setItems(
                                choices,
                                (d, which) -> {
                                    try {
                                        if (which == 0) GuestPlaylists.open(this, id);
                                        if (which == 1)
                                            name(
                                                    Strings.t("Playlist name"),
                                                    name -> {
                                                        GuestPlaylists.bookmark(id, name);
                                                        build();
                                                    });
                                        if (which == 2)
                                            name(
                                                    Strings.t("Playlist name"),
                                                    name -> fetch(id, name));
                                        if (which == 3) {
                                            GuestPlaylists.forget(id);
                                            build();
                                        }
                                    } catch (Exception unavailable) {
                                        message(Strings.t("Could not open this playlist."));
                                    }
                                })
                        .setNegativeButton(Strings.t("Cancel"), null)
                        .create();
        dialog.setOnShowListener(d -> style.tuneDialog(dialog));
        dialog.show();
    }

    private void fetch(String id, String title) {
        busy = true;
        message(Strings.t("Loading…"));
        worker.execute(
                () -> {
                    try {
                        List<Object> songs = GuestPlaylists.copy(id);
                        if (Thread.currentThread().isInterrupted()) return;
                        runOnUiThread(
                                () -> {
                                    busy = false;
                                    if (isFinishing() || isDestroyed()) return;
                                    if (songs.isEmpty()) {
                                        message(
                                                Strings.t(
                                                        "This playlist has no available tracks."));
                                        return;
                                    }
                                    save(title, songs);
                                });
                    } catch (Exception unavailable) {
                        runOnUiThread(
                                () -> {
                                    busy = false;
                                    if (!isFinishing() && !isDestroyed())
                                        message(
                                                Strings.t(
                                                        "Could not read the complete playlist. No"
                                                                + " copy was saved."));
                                });
                    }
                });
    }

    private void save(String title, List<Object> songs) {
        try {
            MixedPlaylists.createAll(title, songs);
            build();
            message(Strings.f("Saved {0} tracks on this device", songs.size()));
        } catch (Exception unavailable) {
            message(Strings.t("Could not save this playlist."));
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == IMPORT && result == RESULT_OK && data != null && data.getData() != null)
            startActivity(new Intent(this, PlaylistImportActivity.class).setData(data.getData()));
    }

    private void edit(Object playlist) {
        String id = MixedSongCodec.text(playlist, "id"),
                title = MixedSongCodec.text(playlist, "title");
        String[] actions = {
            Strings.t("Open"), Strings.t("Rename"), Strings.t("Edit tracks"), Strings.t("Delete")
        };
        AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setTitle(title)
                        .setItems(
                                actions,
                                (d, index) -> {
                                    try {
                                        if (index == 0) GuestPlaylists.openLocal(this, id);
                                        if (index == 1)
                                            name(
                                                    Strings.t("Rename"),
                                                    name -> {
                                                        MixedPlaylists.edit(
                                                                id,
                                                                name,
                                                                MixedPlaylists.songs(playlist),
                                                                false);
                                                        build();
                                                    });
                                        if (index == 2) tracks(playlist);
                                        if (index == 3) confirmDelete(playlist);
                                    } catch (Exception failure) {
                                        message(Strings.t("Could not save this playlist."));
                                    }
                                })
                        .setNegativeButton(Strings.t("Cancel"), null)
                        .create();
        dialog.setOnShowListener(d -> style.tuneDialog(dialog));
        dialog.show();
    }

    private void confirmDelete(Object playlist) {
        AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setTitle(Strings.t("Delete playlist?"))
                        .setMessage(MixedSongCodec.text(playlist, "title"))
                        .setNegativeButton(Strings.t("Cancel"), null)
                        .setPositiveButton(
                                Strings.t("Delete"),
                                (d, w) -> {
                                    try {
                                        MixedPlaylists.edit(
                                                MixedSongCodec.text(playlist, "id"),
                                                "",
                                                Collections.emptyList(),
                                                true);
                                        build();
                                    } catch (Exception failure) {
                                        message(Strings.t("Could not save this playlist."));
                                    }
                                })
                        .create();
        dialog.setOnShowListener(d -> style.tuneDialog(dialog));
        dialog.show();
    }

    private void tracks(Object playlist) throws Exception {
        List<Object> songs = MixedPlaylists.songs(playlist);
        String[] titles = new String[songs.size()];
        for (int i = 0; i < titles.length; i++)
            titles[i] = MixedSongCodec.text(songs.get(i), "title");
        AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setTitle(Strings.t("Edit tracks"))
                        .setItems(titles, (d, index) -> trackActions(playlist, songs, index))
                        .setNegativeButton(Strings.t("Cancel"), null)
                        .create();
        dialog.setOnShowListener(d -> style.tuneDialog(dialog));
        dialog.show();
    }

    private void trackActions(Object playlist, List<Object> songs, int index) {
        String[] actions = {Strings.t("Move up"), Strings.t("Move down"), Strings.t("Remove")};
        AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setTitle(MixedSongCodec.text(songs.get(index), "title"))
                        .setItems(
                                actions,
                                (d, which) -> {
                                    try {
                                        List<Object> next = new ArrayList<>(songs);
                                        if (which == 0 && index > 0)
                                            Collections.swap(next, index, index - 1);
                                        if (which == 1 && index + 1 < next.size())
                                            Collections.swap(next, index, index + 1);
                                        if (which == 2) next.remove(index);
                                        MixedPlaylists.edit(
                                                MixedSongCodec.text(playlist, "id"),
                                                MixedSongCodec.text(playlist, "title"),
                                                next,
                                                false);
                                        build();
                                    } catch (Exception failure) {
                                        message(Strings.t("Could not save this playlist."));
                                    }
                                })
                        .setNegativeButton(Strings.t("Cancel"), null)
                        .create();
        dialog.setOnShowListener(d -> style.tuneDialog(dialog));
        dialog.show();
    }
}

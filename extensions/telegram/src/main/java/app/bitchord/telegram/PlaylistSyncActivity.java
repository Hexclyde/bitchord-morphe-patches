package app.bitchord.telegram;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;

import org.json.*;

import java.util.*;

/** Native connection setup, playlist pairing, match review and explicit sync preview. */
public final class PlaylistSyncActivity extends Activity {
    private TelegramStyle style;
    private LinearLayout body, actionGroup;
    private TextView status;
    private PlaylistSyncEngine engine;
    private boolean busy, destroyed;
    private PlaylistSyncEngine.Preview preview;

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
        engine = new PlaylistSyncEngine(this);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (style != null && !busy && preview == null) build();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!busy) {
            preview = null;
            build();
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
    }

    private void ui(Runnable action) {
        runOnUiThread(
                () -> {
                    if (!destroyed && !isFinishing()) action.run();
                });
    }

    private void shell(String title) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(style.background);
        actionGroup = null;
        body = new LinearLayout(this);
        body.setOrientation(1);
        body.setPadding(style.dp(20), style.dp(8), style.dp(20), style.dp(28));
        body.setBackgroundColor(style.background);
        scroll.addView(body);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(
                style.backButton(
                        () -> {
                            if (preview != null && !busy) {
                                preview = null;
                                build();
                            } else finish();
                        }),
                new LinearLayout.LayoutParams(style.dp(48), style.dp(48)));
        TextView heading = style.text(title, 26, style.foreground, style.heavy);
        LinearLayout.LayoutParams titleSpace = new LinearLayout.LayoutParams(0, -2, 1);
        titleSpace.leftMargin = style.dp(12);
        header.addView(heading, titleSpace);
        body.addView(header);
        status = text("", 14);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setVisibility(View.GONE);
        status.setPadding(0, style.dp(12), 0, style.dp(12));
        body.addView(status);
        setContentView(scroll);
    }

    private void message(String value) {
        status.setText(value);
        status.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private TextView text(String value, int size) {
        return style.text(
                value,
                size,
                size < 16 ? style.secondary : style.foreground,
                size >= 20 ? style.semibold : style.regular);
    }

    private void heading(String title) {
        TextView heading = style.text(title, 13, style.secondary, style.semibold);
        heading.setPadding(style.dp(16), style.dp(20), style.dp(16), style.dp(8));
        body.addView(heading);
        actionGroup = PlaylistRows.group(body, style);
    }

    private void note(String message) {
        TextView view = text(message, 14);
        view.setPadding(0, 0, 0, style.dp(12));
        body.addView(view);
    }

    private View action(String title, Runnable action) {
        String[] parts = title.split("\n", 2);
        return PlaylistRows.add(
                actionGroup == null ? body : actionGroup,
                style,
                parts[0],
                parts.length > 1 ? parts[1] : "",
                () -> {
                    if (!busy) action.run();
                });
    }

    private void primary(String title, Runnable action) {
        TextView button =
                style.button(
                        title,
                        true,
                        () -> {
                            if (!busy) action.run();
                        });
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2);
        space.topMargin = style.dp(12);
        body.addView(button, space);
    }

    private void build() {
        shell(Strings.t("Playlist sync"));
        note(Strings.t("Compare paired playlists and review changes before each manual sync."));
        heading(Strings.t("Connections"));
        boolean spotify = false, youtube = false;
        try {
            spotify = new SpotifySyncAuth(this).secrets.get("spotify") != null;
        } catch (Exception unavailable) {
        }
        try {
            engine.youtube.account();
            youtube = true;
        } catch (Exception unavailable) {
        }
        action("Spotify\n" + Strings.t(spotify ? "Connected" : "Not connected"), this::spotify);
        action(
                "YouTube Music\n" + Strings.t(youtube ? "Connected" : "Not connected"),
                () -> {
                    try {
                        engine.youtube.account();
                        PlaylistModal modal =
                                new PlaylistModal(
                                        this,
                                        "YouTube Music",
                                        Strings.t(
                                                "Uses the active account and profile in"
                                                        + " BitChord."));
                        modal.action(Strings.t("Done"), true, modal::dismiss);
                        modal.show();
                    } catch (Exception signedOut) {
                        GuestPlaylists.signIn(this);
                    }
                });
        heading(Strings.t("Paired playlists"));
        action(Strings.t("New playlist pair"), this::setup);
        try {
            List<PlaylistSyncEngine.Job> jobs = engine.jobs();
            if (jobs.isEmpty()) note(Strings.t("Pair two playlists you can edit to get started."));
            for (PlaylistSyncEngine.Job job : jobs)
                action(job.name + "\n" + phase(job.phase), () -> job(job));
        } catch (Exception invalid) {
            message(Strings.t("Could not load saved sync pairs."));
        }
    }

    private String phase(String phase) {
        if (phase.equals("synced")) return Strings.t("Synced");
        if (phase.equals("needs_verification")) return Strings.t("Needs verification");
        if (phase.equals("confirming_removals")) return Strings.t("Confirming removals");
        return Strings.t("Not synced yet");
    }

    private void spotify() {
        try {
            String token = new SpotifySyncAuth(this).secrets.get("spotify");
            if (token != null) {
                PlaylistModal modal =
                        new PlaylistModal(
                                this,
                                "Spotify",
                                Strings.t("Disconnecting keeps your playlists and sync pairs."));
                modal.action(
                        Strings.t("Disconnect"),
                        true,
                        () -> {
                            modal.dismiss();
                            busy = true;
                            PlaylistSyncEngine.IO.execute(
                                    () -> {
                                        try {
                                            new SpotifySyncAuth(this).disconnect();
                                        } catch (Exception ignored) {
                                        }
                                        ui(
                                                () -> {
                                                    busy = false;
                                                    build();
                                                });
                                    });
                        });
                modal.action(Strings.t("Cancel"), false, modal::dismiss);
                modal.show();
                return;
            }
        } catch (Exception unavailable) {
        }
        PlaylistModal modal =
                new PlaylistModal(
                        this,
                        Strings.t("Connect Spotify"),
                        Strings.t(
                                "Use your Spotify developer client ID and register this redirect"
                                        + " URI:"));
        TextView redirect = text(SpotifySyncAuth.REDIRECT, 12);
        redirect.setTextIsSelectable(true);
        redirect.setPadding(style.dp(16), 0, style.dp(16), style.dp(12));
        modal.body.addView(redirect);
        EditText client = modal.input(Strings.t("Spotify client ID"));
        modal.action(
                Strings.t("Connect"),
                true,
                () -> {
                    String id = client.getText().toString().trim();
                    if (!id.matches("[A-Za-z0-9]{16,128}")) {
                        client.setError(Strings.t("Check this value and try again."));
                        return;
                    }
                    modal.dismiss();
                    busy = true;
                    message(Strings.t("Opening Spotify…"));
                    PlaylistSyncEngine.IO.execute(
                            () -> {
                                try {
                                    String url = new SpotifySyncAuth(this).begin(id);
                                    ui(
                                            () -> {
                                                busy = false;
                                                startActivity(
                                                        new Intent(
                                                                Intent.ACTION_VIEW,
                                                                Uri.parse(url)));
                                            });
                                } catch (Exception unavailable) {
                                    ui(
                                            () -> {
                                                busy = false;
                                                message(
                                                        Strings.t(
                                                                "Could not open sign-in. Try"
                                                                        + " connecting again."));
                                            });
                                }
                            });
                });
        modal.action(
                Strings.t("Copy redirect URI"),
                false,
                () ->
                        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                                .setPrimaryClip(
                                        ClipData.newPlainText(
                                                "Spotify", SpotifySyncAuth.REDIRECT)));
        modal.action(
                Strings.t("Developer setup"),
                false,
                () ->
                        startActivity(
                                new Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://developer.spotify.com/dashboard"))));
        modal.action(Strings.t("Cancel"), false, modal::dismiss);
        modal.show();
    }

    private EditText field(String label) {
        TextView title = text(label, 14);
        title.setPadding(0, style.dp(12), 0, style.dp(6));
        body.addView(title);
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextColor(style.foreground);
        input.setHintTextColor(style.secondary);
        input.setTextSize(16);
        input.setTypeface(style.regular);
        input.setPadding(style.dp(16), style.dp(12), style.dp(16), style.dp(12));
        input.setBackground(style.shape(style.card, 20, false));
        input.setContentDescription(label);
        input.setMinHeight(style.dp(48));
        body.addView(input);
        return input;
    }

    private void setup() {
        shell(Strings.t("New playlist pair"));
        note(
                Strings.t(
                        "Choose existing playlists you can edit. A preview does not change either"
                                + " service."));
        EditText name = field(Strings.t("Name")),
                spotify = field(Strings.t("Spotify playlist link")),
                youtube = field(Strings.t("YouTube Music playlist link"));
        heading(Strings.t("Sync options"));
        TelegramSwitch toggle = new TelegramSwitch(this, style);
        toggle.setChecked(false);
        toggle.setContentDescription(Strings.t("Mirror removals"));
        TextView label = text(Strings.t("Mirror removals"), 16);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(toggle);
        body.addView(row);
        note(Strings.t("Off by default. Removals must be confirmed by two complete reads."));
        action(
                Strings.t("Save pair and preview"),
                () -> {
                    try {
                        PlaylistSyncEngine.Job job = new PlaylistSyncEngine.Job();
                        job.name = name.getText().toString().trim();
                        if (job.name.isEmpty() || job.name.length() > 300)
                            throw new IllegalArgumentException();
                        job.spotifyId = PlaylistSyncEngine.spotifyId(spotify.getText().toString());
                        job.youtubeId = GuestPlaylists.playlistId(youtube.getText().toString());
                        job.removals = toggle.isChecked();
                        engine.save(job);
                        load(job);
                    } catch (Exception invalid) {
                        message(Strings.t("Check the name and playlist links, then try again."));
                    }
                });
    }

    private void job(PlaylistSyncEngine.Job job) {
        PlaylistModal modal = new PlaylistModal(this, job.name, phase(job.phase));
        modal.action(
                Strings.t("Preview sync"),
                true,
                () -> {
                    modal.dismiss();
                    load(job);
                });
        modal.action(
                Strings.t("Sync options"),
                false,
                () -> {
                    modal.dismiss();
                    options(job);
                });
        modal.action(
                Strings.t("Remove pair"),
                false,
                () -> {
                    modal.dismiss();
                    PlaylistModal confirm =
                            new PlaylistModal(
                                    this,
                                    Strings.t("Remove sync pair?"),
                                    Strings.t(
                                            "The playlists in your music services will be kept."));
                    confirm.action(
                            Strings.t("Remove"),
                            true,
                            () -> {
                                engine.remove(job);
                                confirm.dismiss();
                                build();
                            });
                    confirm.action(Strings.t("Cancel"), false, confirm::dismiss);
                    confirm.show();
                });
        modal.action(Strings.t("Cancel"), false, modal::dismiss);
        modal.show();
    }

    private void options(PlaylistSyncEngine.Job job) {
        PlaylistModal modal =
                new PlaylistModal(
                        this,
                        Strings.t("Sync options"),
                        Strings.t(
                                "Removals stay off until you enable them. A new preview is required"
                                        + " after changing this option."));
        boolean[] checked = {job.removals};
        modal.choices(new String[] {Strings.t("Mirror removals")}, checked, (index, on) -> {});
        modal.action(
                Strings.t("Change limits"),
                false,
                () -> {
                    job.removals = checked[0];
                    modal.dismiss();
                    limits(job);
                });
        modal.action(
                Strings.t("Save"),
                true,
                () -> {
                    try {
                        job.removals = checked[0];
                        engine.save(job);
                        modal.dismiss();
                        build();
                    } catch (Exception failed) {
                        message(Strings.t("Could not save sync options."));
                    }
                });
        modal.action(Strings.t("Cancel"), false, modal::dismiss);
        modal.show();
    }

    private void limits(PlaylistSyncEngine.Job job) {
        PlaylistModal modal =
                new PlaylistModal(
                        this,
                        Strings.t("Change limits"),
                        Strings.t("Maximum additions and removals allowed in one sync pass."));
        TextView addLabel = text(Strings.t("Additions"), 14);
        addLabel.setPadding(style.dp(16), 0, style.dp(16), style.dp(4));
        modal.body.addView(addLabel);
        EditText adds = modal.input(Strings.t("Additions"));
        adds.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        adds.setText(String.valueOf(job.maxAdds));
        TextView removeLabel = text(Strings.t("Removals"), 14);
        removeLabel.setPadding(style.dp(16), 0, style.dp(16), style.dp(4));
        modal.body.addView(removeLabel);
        EditText removes = modal.input(Strings.t("Removals"));
        removes.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        removes.setText(String.valueOf(job.maxRemovals));
        modal.action(
                Strings.t("Save"),
                true,
                () -> {
                    try {
                        int a = Integer.parseInt(adds.getText().toString()),
                                r = Integer.parseInt(removes.getText().toString());
                        if (a < 0 || a > 5000 || r < 0 || r > 5000)
                            throw new IllegalArgumentException();
                        job.maxAdds = a;
                        job.maxRemovals = r;
                        engine.save(job);
                        modal.dismiss();
                        build();
                    } catch (Exception invalid) {
                        adds.setError("0 – 5000");
                    }
                });
        modal.action(
                Strings.t("Cancel"),
                false,
                () -> {
                    modal.dismiss();
                    options(job);
                });
        modal.show();
    }

    private void load(PlaylistSyncEngine.Job job) {
        busy = true;
        preview = null;
        shell(Strings.t("Preview sync"));
        message(Strings.t("Reading playlists…"));
        PlaylistSyncEngine.IO.execute(
                () -> {
                    try {
                        PlaylistSyncEngine.Preview ready = engine.preview(job, this::progress);
                        ui(
                                () -> {
                                    busy = false;
                                    preview = ready;
                                    review();
                                });
                    } catch (Exception failed) {
                        ui(
                                () -> {
                                    busy = false;
                                    message(error(failed));
                                    action(Strings.t("Sync options"), () -> options(job));
                                    action(
                                            Strings.t("Back"),
                                            () -> {
                                                preview = null;
                                                build();
                                            });
                                });
                    }
                });
    }

    private void progress(int done, int total) {
        if (destroyed || isFinishing()) throw new java.util.concurrent.CancellationException();
        ui(() -> message(Strings.f("Matching {0} of {1}", done, total)));
    }

    private String error(Exception failed) {
        String message = failed.getMessage();
        if (message != null
                && (failed instanceof IllegalStateException
                        || failed instanceof IllegalArgumentException)) return message;
        return Strings.t("Sync could not finish. Review your connections and try again.");
    }

    private void review() {
        PlaylistSyncEngine.Preview draft = preview;
        if (draft == null) return;
        shell(Strings.t("Preview sync"));
        note(draft.job.name);
        if (!draft.unresolved.isEmpty()) {
            message(Strings.f("Review {0} track matches", draft.unresolved.size()));
            for (PlaylistSyncEngine.Unmatched row : new ArrayList<>(draft.unresolved))
                action(row.source.title + "\n" + row.source.artist, () -> match(row));
            return;
        }
        PlaylistSyncPlan plan = draft.plan;
        if (plan == null) return;
        if (plan.awaitingConfirmation)
            note(
                    Strings.t(
                            "A removal was observed. This pass records it without changing either"
                                    + " playlist; another complete read must confirm it."));
        for (String service : new String[] {"spotify", "youtube"}) {
            PlaylistSyncPlan.Change change = plan.changes.get(service);
            heading(service.equals("spotify") ? "Spotify" : "YouTube Music");
            note(
                    Strings.f(
                            "{0} additions · {1} removals",
                            change.add.size(), change.remove.size()));
            for (String key : change.add) note("+ " + title(draft, key));
            for (String key : change.remove) note("− " + title(draft, key));
        }
        int skipped = draft.job.excludeSpotify.size() + draft.job.excludeYoutube.size();
        if (skipped > 0)
            note(
                    Strings.f(
                            "{0} unmatched tracks will stay in their original service only",
                            skipped));
        primary(
                Strings.t("Apply sync"),
                () -> {
                    PlaylistModal confirm =
                            new PlaylistModal(
                                    this,
                                    Strings.t("Apply playlist changes?"),
                                    Strings.t(
                                            "This updates both linked service playlists using the"
                                                    + " changes shown in the preview."));
                    confirm.action(
                            Strings.t("Apply"),
                            true,
                            () -> {
                                confirm.dismiss();
                                apply();
                            });
                    confirm.action(Strings.t("Cancel"), false, confirm::dismiss);
                    confirm.show();
                });
    }

    private String title(PlaylistSyncEngine.Preview draft, String canonical) {
        String id = canonical.substring(3);
        for (YouTubePlaylistApi.Track track : draft.youtube.tracks)
            if (track.id.equals(id)) return track.metadata.title;
        for (SpotifyPlaylistApi.Track track : draft.spotify.tracks)
            if (id.equals(draft.job.links.get(track.id))) return track.metadata.title;
        return id;
    }

    private void match(PlaylistSyncEngine.Unmatched row) {
        PlaylistModal modal = new PlaylistModal(this, row.source.title, row.source.artist);
        for (PlaylistSyncEngine.Candidate candidate : row.candidates)
            modal.action(
                    MixedSongCodec.text(candidate.song, "title")
                            + "\n"
                            + MixedSongCodec.text(candidate.song, "artist")
                            + " · "
                            + MixedSongCodec.text(candidate.song, "durationText"),
                    false,
                    () -> {
                        try {
                            PlaylistSyncEngine.resolve(preview, row, candidate);
                            modal.dismiss();
                            review();
                        } catch (Exception invalid) {
                            Toast.makeText(
                                            this,
                                            Strings.t(
                                                    "Choose a different recording or keep this"
                                                            + " track in its original service."),
                                            Toast.LENGTH_LONG)
                                    .show();
                        }
                    });
        modal.action(
                Strings.t("Keep in original service only"),
                false,
                () -> {
                    PlaylistSyncEngine.resolve(preview, row, null);
                    modal.dismiss();
                    review();
                });
        modal.action(Strings.t("Cancel"), false, modal::dismiss);
        modal.show();
    }

    private void apply() {
        PlaylistSyncEngine.Preview draft = preview;
        if (draft == null) return;
        busy = true;
        message(Strings.t("Applying and verifying changes…"));
        PlaylistSyncEngine.IO.execute(
                () -> {
                    try {
                        String result = engine.apply(draft);
                        ui(
                                () -> {
                                    busy = false;
                                    preview = null;
                                    build();
                                    message(
                                            result.equals("synced")
                                                    ? Strings.t("Synced")
                                                    : Strings.t(
                                                            "Removal observation saved. Preview"
                                                                    + " again to confirm."));
                                });
                    } catch (Exception failed) {
                        ui(
                                () -> {
                                    busy = false;
                                    preview = null;
                                    build();
                                    message(
                                            Strings.t(
                                                    "Sync stopped. Some changes may have applied;"
                                                            + " preview again to verify before"
                                                            + " retrying."));
                                });
                    }
                });
    }
}

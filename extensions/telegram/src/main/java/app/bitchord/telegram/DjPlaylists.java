package app.bitchord.telegram;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Deliberate harmonic ordering of a device playlist, without external-service writes. */
final class DjPlaylists {
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService worker =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread thread = new Thread(r, "dj-playlist");
                        thread.setDaemon(true);
                        return thread;
                    });

    private DjPlaylists() {}

    static void choose(Activity activity) {
        worker.execute(
                () -> {
                    try {
                        List<Object> playlists = MixedPlaylists.nativePlaylists();
                        main.post(
                                () -> {
                                    if (activity.isFinishing() || activity.isDestroyed()) return;
                                    if (playlists.isEmpty()) {
                                        message(activity, Strings.t("No playlists yet"));
                                        return;
                                    }
                                    PlaylistModal picker =
                                            new PlaylistModal(
                                                    activity,
                                                    Strings.t("Save DJ order"),
                                                    Strings.t(
                                                            "Choose a local playlist. Online"
                                                                + " playlists are not changed."));
                                    // The rows are PlaylistModal's own actions, moved into a
                                    // scroll view so a long list can't push Cancel off screen.
                                    int first = picker.body.getChildCount();
                                    for (Object playlist : playlists)
                                        picker.action(
                                                MixedSongCodec.text(playlist, "title"),
                                                false,
                                                () -> {
                                                    picker.dismiss();
                                                    confirm(activity, playlist);
                                                });
                                    ScrollView scroll = new ScrollView(activity);
                                    LinearLayout rows = new LinearLayout(activity);
                                    rows.setOrientation(LinearLayout.VERTICAL);
                                    scroll.addView(rows);
                                    while (picker.body.getChildCount() > first) {
                                        View row = picker.body.getChildAt(first);
                                        picker.body.removeViewAt(first);
                                        rows.addView(row);
                                    }
                                    int most =
                                            activity.getResources().getDisplayMetrics().heightPixels
                                                    / 2;
                                    picker.body.addView(
                                            scroll,
                                            new LinearLayout.LayoutParams(
                                                    -1,
                                                    Math.min(
                                                            most,
                                                            playlists.size()
                                                                    * picker.style.dp(58))));
                                    picker.action(Strings.t("Cancel"), false, picker::dismiss);
                                    picker.show();
                                });
                    } catch (Exception unavailable) {
                        main.post(
                                () ->
                                        message(
                                                activity,
                                                Strings.t("Could not save this playlist.")));
                    }
                });
    }

    private static void confirm(Activity activity, Object playlist) {
        int mode = DjMode.journey;
        PlaylistModal dialog =
                new PlaylistModal(
                        activity,
                        Strings.t("Save DJ order"),
                        MixedSongCodec.text(playlist, "title")
                                + "\n"
                                + Strings.t(
                                        "The first track stays first. Tracks without measurements"
                                                + " keep their relative order."));
        dialog.action(
                Strings.t("Save"),
                true,
                () -> {
                    dialog.dismiss();
                    worker.execute(
                            () -> {
                                try {
                                    List<Object> original = MixedPlaylists.songs(playlist);
                                    List<Object> result = new ArrayList<>();
                                    if (!original.isEmpty()) {
                                        result.add(original.get(0));
                                        List<DjMode.Info> info = new ArrayList<>();
                                        for (int i = 1; i < original.size(); i++)
                                            info.add(
                                                    DjMode.info(
                                                            MixedSongCodec.text(
                                                                    original.get(i), "videoId")));
                                        List<Integer> order =
                                                DjOrder.order(
                                                        DjMode.info(
                                                                MixedSongCodec.text(
                                                                        original.get(0),
                                                                        "videoId")),
                                                        info,
                                                        Collections.nCopies(info.size(), false),
                                                        mode);
                                        for (int at : order) result.add(original.get(at + 1));
                                    }
                                    MixedPlaylists.reorder(playlist, result);
                                    main.post(() -> message(activity, Strings.t("Saved")));
                                } catch (Exception changed) {
                                    main.post(
                                            () ->
                                                    message(
                                                            activity,
                                                            Strings.t(
                                                                    "Could not save this"
                                                                            + " playlist.")));
                                }
                            });
                });
        dialog.action(Strings.t("Cancel"), false, dialog::dismiss);
        dialog.show();
    }

    private static void message(Activity activity, String text) {
        if (!activity.isFinishing() && !activity.isDestroyed())
            Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
    }
}

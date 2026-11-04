package app.bitchord.telegram;

import android.net.Uri;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** What BitChord calls to play a song from its best enabled source first. */
public final class SourceFirst {
    private SourceFirst() {}

    private static volatile boolean switchedOn = true;
    private static volatile SourcePreparation active;
    private static volatile Object service;

    /** The service started. Nothing runs until the queue or a player asks. */
    public static void service(Object owner) {
        try {
            if (service == owner && active != null) return;
            SourcePreparation old = active;
            if (old != null) old.close();
            service = owner;
            Constructor<?> constructor =
                    Class.forName("app.bitchord.telegram.SourcePreparationHost")
                            .getDeclaredConstructor(Object.class);
            constructor.setAccessible(true);
            SourcePreparation.Host host = (SourcePreparation.Host) constructor.newInstance(owner);
            SourcePreparation next = new SourcePreparation(host, SourcePreparation.FIRST_WAIT_MS);
            next.switched(switchedOn);
            active = next;
            host.attach(next);
        } catch (Throwable unavailable) {
            HostLog.d("Source preparation unavailable: " + unavailable.getClass().getSimpleName());
        }
    }

    public static void release(Object owner) {
        SourcePreparation running = active;
        if (running == null || service != owner) return;
        active = null;
        service = null;
        running.close();
    }

    public static void catalogueChanged() {
        SourcePreparation running = active;
        Object owner = service;
        if (running == null || owner == null) return;
        running.catalogueChanged(
                () -> {
                    if (active != running || service != owner) return;
                    try {
                        Field field = owner.getClass().getDeclaredField("player");
                        field.setAccessible(true);
                        queueChanged(owner, field.get(owner));
                    } catch (ReflectiveOperationException unavailable) {
                        HostLog.d(
                                "Source catalogue refresh skipped: "
                                        + unavailable.getClass().getSimpleName());
                    }
                });
    }

    /** Whether playing better copies first is switched on. */
    public static void setEnabled(boolean enabled) {
        switchedOn = enabled;
        SourcePreparation running = active;
        if (running != null) running.switched(enabled);
    }

    public static boolean isEnabled() {
        return switchedOn;
    }

    /** "preparing", "ready" or "": what can be said of a song right now. */
    public static String status(String mediaId) {
        SourcePreparation running = active;
        return running == null ? "" : running.statusOf(mediaId);
    }

    /** The queue moved or playback started. On the player's thread. */
    public static void queueChanged(Object owner, Object player) {
        SourcePreparation running = active;
        if (running == null || !(player instanceof Player)) return;
        try {
            Player queue = (Player) player;
            int next = (Integer) Player.class.getMethod("getNextMediaItemIndex").invoke(queue);
            int count = (Integer) Player.class.getMethod("getMediaItemCount").invoke(queue);
            List<Uri> items = new ArrayList<>();
            for (int i = next;
                    next >= 0 && i < count && items.size() < SourcePreparation.LOOKAHEAD;
                    i++) {
                MediaItem item = queue.getMediaItemAt(i);
                if (item.localConfiguration != null) items.add(item.localConfiguration.uri);
            }
            List<Uri> decks = new ArrayList<>();
            MediaItem audible = queue.getCurrentMediaItem();
            if (audible != null && audible.localConfiguration != null)
                decks.add(audible.localConfiguration.uri);
            for (String name : new String[] {"player", "spare"}) {
                try {
                    Field member = owner.getClass().getDeclaredField(name);
                    member.setAccessible(true);
                    Object deck = member.get(owner);
                    MediaItem item =
                            deck instanceof Player ? ((Player) deck).getCurrentMediaItem() : null;
                    if (item != null && item.localConfiguration != null)
                        decks.add(item.localConfiguration.uri);
                } catch (ReflectiveOperationException absent) {
                }
            }
            running.retainCurrentEntries(decks);
            running.upcoming(items);
            current(running, queue);
        } catch (Throwable unavailable) {
            HostLog.d("Source preparation skipped: " + unavailable.getClass().getSimpleName());
        }
    }

    private static void current(SourcePreparation running, Player player) {
        MediaItem item = player.getCurrentMediaItem();
        if (item == null || item.localConfiguration == null) return;
        running.current(
                item.mediaId,
                item.localConfiguration.uri,
                player.getCurrentPosition(),
                player.isPlaying());
    }

    public static void playing(boolean isPlaying) {
        SourcePreparation running = active;
        if (running == null) return;
        running.playing(isPlaying);
        if (isPlaying && service != null) {
            try {
                Player player = hostPlayer(service);
                if (player != null) current(running, player);
            } catch (Throwable ignored) {
                // Only the clean-up looks at this.
            }
        }
    }

    /** The player reported an error: whatever it was reading is no longer offered. */
    public static void failed() {
        SourcePreparation running = active;
        if (running == null || service == null) return;
        try {
            Player player = hostPlayer(service);
            MediaItem item = player == null ? null : player.getCurrentMediaItem();
            if (item != null)
                running.failed(
                        item.mediaId,
                        item.localConfiguration == null ? null : item.localConfiguration.uri);
        } catch (Throwable ignored) {
            // BitChord's own recovery still runs.
        }
    }

    private static Player hostPlayer(Object owner) throws Exception {
        Field member = owner.getClass().getDeclaredField("player");
        member.setAccessible(true);
        return (Player) member.get(owner);
    }

    /** Pure identity tag: no source lookup, I/O or queue mutation. */
    public static MediaItem tag(MediaItem item) {
        if (item == null || item.localConfiguration == null) return item;
        Uri uri = item.localConfiguration.uri;
        if (!SourcePreparation.eligible(uri)
                || uri.getQueryParameter(SourcePreparation.ENTRY) != null) return item;
        try {
            try {
                if (Boolean.TRUE.equals(
                        Class.forName("com.music.bitchord.playback.OriginalVersion")
                                .getMethod("isPinned", String.class)
                                .invoke(null, item.mediaId))) return item;
            } catch (ClassNotFoundException | NoSuchMethodException absent) {
            }
            String entry =
                    item.mediaMetadata == null || item.mediaMetadata.extras == null
                            ? null
                            : item.mediaMetadata.extras.getString("bitchord.queueEntryId");
            if (entry == null || entry.isEmpty()) entry = UUID.randomUUID().toString();
            Uri tagged =
                    uri.buildUpon().appendQueryParameter(SourcePreparation.ENTRY, entry).build();
            Object builder = MediaItem.class.getMethod("buildUpon").invoke(item);
            // The release host's setter returns void. Never chain its reflection result.
            builder.getClass().getMethod("setUri", String.class).invoke(builder, tagged.toString());
            return (MediaItem) builder.getClass().getMethod("build").invoke(builder);
        } catch (Exception unavailable) {
            return item;
        }
    }

    /** Exact logical cache URI chosen by this queue entry's playback period. */
    public static Uri renditionUri(MediaItem item) {
        if (item == null || item.localConfiguration == null) return null;
        return renditionUri(item.localConfiguration.uri);
    }

    /** Host cache bookkeeping already carries the tagged URI, before a MediaItem is available. */
    public static Uri renditionUri(Uri original) {
        SourcePreparation running = active;
        return original == null || running == null ? original : running.renditionUri(original);
    }

    /** Used at the host key factory so actual reads, analysis and labels share one entry key. */
    public static String cacheKey(Uri original, String computed) {
        SourcePreparation running = active;
        return running == null ? computed : running.cacheKey(original, computed);
    }

    /** Read-only guard at both checks of the host's automatic upgrade swap. */
    public static boolean preparedEntry(MediaItem item) {
        SourcePreparation running = active;
        return running != null
                && item != null
                && item.localConfiguration != null
                && running.preparedEntry(item.localConfiguration.uri);
    }

    /** A preparation approval can be consumed only by this entry's exact selected rendition. */
    public static boolean isPreparedUri(Uri uri) {
        SourcePreparation running = active;
        return running != null && running.isPreparedUri(uri);
    }

    /** Replaces the open of BitChord's playback source, above its cache. */
    public static long open(Object period, DataSource source, DataSpec spec) throws IOException {
        SourcePreparation running = active;
        return YouTubePolicy.open(source, running == null ? spec : running.route(period, spec));
    }
}

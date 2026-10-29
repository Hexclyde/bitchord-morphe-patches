package app.bitchord.telegram;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;

import com.music.bitchord.playback.PlaybackService;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * BitChord switches to a better copy of the playing song (say, your Telegram FLAC replacing the
 * YouTube stream) by swapping the source under the live player, which leaves a gap. This hands the
 * switch to BitChord's own two-player version swap instead: the new copy is loaded on the standby
 * player while the current one keeps playing, then crossfaded in at the same position. When that
 * can't run (Automix mid-blend, no standby player), the original swap is used.
 *
 * <p>The two copies rarely start at the same instant, and BitChord takes the offset between two
 * copies of the same song as zero. For a Telegram copy, {@link VersionAlign} measures it first, and
 * the swap starts the new copy that much later or earlier, so nothing repeats or is skipped.
 */
public final class SmoothUpgrade {
    private SmoothUpgrade() {}

    static volatile boolean enabled = true;
    private static final Set<Object> handedOver =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    // The offset measured for the swap about to start, and when: {offsetMs, millis}.
    private static volatile long[] aligned;

    /** Replaces the upgrade's replaceMediaItem call. */
    public static void replace(Object service, Player player, int index, MediaItem item) {
        handedOver.remove(player);
        if (enabled) {
            String playing = mediaId(player.getCurrentMediaItem());
            boolean measuring = false;
            try {
                measuring =
                        VersionAlign.measure(
                                player,
                                item,
                                offset -> {
                                    // The song may have changed while it was measured.
                                    if (player.getCurrentMediaItemIndex() != index
                                            || !Objects.equals(
                                                    playing, mediaId(player.getCurrentMediaItem())))
                                        return;
                                    aligned =
                                            offset == null
                                                    ? null
                                                    : new long[] {
                                                        offset, System.currentTimeMillis()
                                                    };
                                    if (swap(service, player, index, item)) return;
                                    aligned = null;
                                    long at =
                                            player.getCurrentPosition()
                                                    + (offset == null ? 0 : offset);
                                    player.replaceMediaItem(index, item);
                                    player.seekTo(index, Math.max(0, at));
                                    player.prepare();
                                });
            } catch (Throwable ignored) {
                // Not measured: swapped as before.
            }
            if (measuring || swap(service, player, index, item)) {
                handedOver.add(player);
                return;
            }
        }
        player.replaceMediaItem(index, item);
    }

    private static boolean swap(Object service, Player player, int index, MediaItem item) {
        try {
            return PlaybackService.bitchordSmoothSwap(service, player, index, item);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String mediaId(MediaItem item) {
        return item == null ? null : item.mediaId;
    }

    /**
     * Called first in BitChord's version alignment: the offset measured for the upgrade being
     * swapped in, once, or null to let BitChord measure as it does.
     */
    public static Long alignedOffset() {
        long[] found = aligned;
        aligned = null;
        return found != null && System.currentTimeMillis() - found[1] < 10_000 ? found[0] : null;
    }

    /** Replaces the upgrade's seekTo call; the smooth swap keeps its own position. */
    public static void seekTo(Player player, int index, long positionMs) {
        if (!handedOver.contains(player)) player.seekTo(index, positionMs);
    }

    /** Replaces the upgrade's prepare call. */
    public static void prepare(Player player) {
        if (!handedOver.remove(player)) player.prepare();
    }
}

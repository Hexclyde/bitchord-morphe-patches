package app.bitchord.telegram;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;

import com.music.bitchord.playback.PlaybackService;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * BitChord switches to a better copy of the playing song (say, your Telegram FLAC replacing the
 * YouTube stream) by swapping the source under the live player, which leaves a gap. This hands the
 * switch to BitChord's own two-player version swap instead: the new copy is loaded on the standby
 * player while the current one keeps playing, then crossfaded in at the same position. When that
 * can't run (Automix mid-blend, no standby player), the original swap is used.
 */
public final class SmoothUpgrade {
    private SmoothUpgrade() {}

    static volatile boolean enabled = true;
    private static final Set<Object> handedOver =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /** Replaces the upgrade's replaceMediaItem call. */
    public static void replace(Object service, Player player, int index, MediaItem item) {
        handedOver.remove(player);
        if (enabled)
            try {
                if (PlaybackService.bitchordSmoothSwap(service, player, index, item)) {
                    handedOver.add(player);
                    return;
                }
            } catch (Throwable ignored) {
                // Fall through to BitChord's own swap.
            }
        player.replaceMediaItem(index, item);
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

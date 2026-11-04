package app.bitchord.telegram;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.os.Bundle;
import android.os.Looper;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class DjQueueArrangeTest {
    /** A player over a plain list, recording every move. */
    private static final class Queue {
        final List<MediaItem> items = new ArrayList<>();
        int current;
        boolean shuffle;
        int moves;
        final Player player =
                (Player)
                        Proxy.newProxyInstance(
                                Player.class.getClassLoader(),
                                new Class<?>[] {Player.class},
                                (proxy, method, args) -> {
                                    switch (method.getName()) {
                                        case "getCurrentMediaItemIndex":
                                            return current;
                                        case "getMediaItemCount":
                                            return items.size();
                                        case "getMediaItemAt":
                                            return items.get((Integer) args[0]);
                                        case "getShuffleModeEnabled":
                                            return shuffle;
                                        case "moveMediaItems":
                                            int from = (Integer) args[0], to = (Integer) args[1];
                                            List<MediaItem> block =
                                                    new ArrayList<>(items.subList(from, to));
                                            items.subList(from, to).clear();
                                            items.addAll((Integer) args[2], block);
                                            moves++;
                                            return null;
                                        case "equals":
                                            return proxy == args[0];
                                        case "hashCode":
                                            return System.identityHashCode(proxy);
                                        default:
                                            throw new UnsupportedOperationException(
                                                    method.getName());
                                    }
                                });

        List<String> ids() {
            List<String> ids = new ArrayList<>();
            for (MediaItem item : items) ids.add(item.mediaId);
            return ids;
        }
    }

    private static MediaItem item(String id, boolean userQueued) {
        MediaItem.Builder builder =
                new MediaItem.Builder().setMediaId(id).setUri("https://example.test/" + id);
        if (userQueued) {
            Bundle extras = new Bundle();
            extras.putString("bitchord.queueTier", "USER_QUEUE");
            builder.setMediaMetadata(new MediaMetadata.Builder().setExtras(extras).build());
        }
        return builder.build();
    }

    private static Queue queue(int current, String... ids) {
        Queue queue = new Queue();
        queue.current = current;
        for (String id : ids) queue.items.add(item(id, false));
        return queue;
    }

    @SuppressWarnings("unchecked")
    private static void know(String id, String key, double bpm) throws Exception {
        Field field = DjMode.class.getDeclaredField("known");
        field.setAccessible(true);
        ((Map<String, DjMode.Info>) field.get(null)).put(id, new DjMode.Info(key, bpm, "test"));
    }

    @Before
    public void setup() throws Exception {
        Field known = DjMode.class.getDeclaredField("known");
        known.setAccessible(true);
        ((Map<?, ?>) known.get(null)).clear();
        DjMode.setJourney(DjOrder.STEADY);
        DjMode.setEnabled(true);
        DjMode.lastArrangement = "";
        know("now", "8A", 120);
        know("far", "2B", 160);
        know("a", "9A", 122);
        know("b", "8A", 121);
        know("c", "10A", 124);
    }

    @After
    public void tearDown() {
        com.music.bitchord.playback.QueueShuffle.setEnabled(false);
        DjMode.setEnabled(false);
    }

    /** Runs the ordering worker to completion, then the main-thread apply step. */
    private static void settle() throws Exception {
        Method worker = DjMode.class.getDeclaredMethod("worker");
        worker.setAccessible(true);
        ((ExecutorService) worker.invoke(null)).submit(() -> {}).get(5, TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static void arrange(Queue queue) throws Exception {
        DjMode.arrange(queue.player);
        settle();
    }

    @Test
    public void wholeTailIsOrderedAndNothingBeforeItMoves() throws Exception {
        Queue queue = queue(1, "earlier", "now", "far", "a", "b", "c");
        arrange(queue);
        assertEquals(Arrays.asList("earlier", "now", "b", "a", "c", "far"), queue.ids());
        assertTrue(DjMode.lastArrangement.startsWith("Reordered"));
    }

    @Test
    public void lastSongAndUnknownCurrentLeaveTheQueueAlone() throws Exception {
        Queue last = queue(2, "far", "a", "now");
        arrange(last);
        assertEquals(0, last.moves);
        Queue unknown = queue(0, "mystery", "far", "a");
        arrange(unknown);
        assertEquals(
                new HashSet<>(Arrays.asList("mystery", "far", "a")), new HashSet<>(unknown.ids()));
    }

    @Test
    public void disabledModeNeverMovesAnything() throws Exception {
        DjMode.setEnabled(false);
        Queue off = queue(0, "now", "far", "a", "b");
        arrange(off);
        assertEquals(0, off.moves);

        DjMode.setEnabled(true);
        Queue late = queue(0, "now", "far", "a", "b");
        DjMode.arrange(late.player);
        DjMode.setEnabled(false);
        settle();
        assertEquals(0, late.moves);
    }

    @Test
    public void queuedSongsKeepTheirPlaceAndNothingCrossesThem() throws Exception {
        Queue queue = queue(0, "now", "far", "a", "b");
        queue.items.add(item("c", true));
        queue.items.add(item("a", false));
        arrange(queue);
        assertEquals(Arrays.asList("now", "b", "a", "far", "c", "a"), queue.ids());
        assertTrue(DjMode.userQueued(queue.items.get(4)));
    }

    @Test
    public void duplicateOccurrencesAllSurvive() throws Exception {
        Queue queue = queue(0, "now", "far", "a", "far", "a", "b");
        arrange(queue);
        List<String> ids = queue.ids();
        Collections.sort(ids);
        assertEquals(Arrays.asList("a", "a", "b", "far", "far", "now"), ids);
        assertEquals("now", queue.items.get(0).mediaId);
    }

    @Test
    public void skippingOrEditingBeforeTheOrderArrivesDropsIt() throws Exception {
        Queue skipped = queue(0, "now", "far", "a", "b");
        DjMode.arrange(skipped.player);
        skipped.current = 1;
        settle();
        assertEquals(0, skipped.moves);

        Queue inserted = queue(0, "now", "far", "a", "b");
        DjMode.arrange(inserted.player);
        inserted.items.add(1, item("c", false));
        settle();
        assertEquals(0, inserted.moves);
    }

    @Test
    public void queueRestoredAfterADroppedOrderIsArrangedAgain() throws Exception {
        Queue queue = queue(0, "now", "far", "a", "b");
        DjMode.arrange(queue.player);
        queue.items.add(1, item("c", false));
        settle();
        assertEquals(0, queue.moves);
        queue.items.remove(1);
        arrange(queue);
        assertEquals(Arrays.asList("now", "b", "a", "far"), queue.ids());
    }

    @Test
    public void shuffleIsLeftToTheHost() throws Exception {
        Queue queue = queue(0, "now", "far", "a", "b");
        queue.shuffle = true;
        arrange(queue);
        assertEquals(0, queue.moves);

        Queue late = queue(0, "now", "far", "a", "b");
        DjMode.arrange(late.player);
        late.shuffle = true;
        settle();
        assertEquals(0, late.moves);
        late.shuffle = false;
        arrange(late);
        assertEquals(Arrays.asList("now", "b", "a", "far"), late.ids());
    }

    @Test
    public void inAppShuffleKeepsItsOrder() throws Exception {
        com.music.bitchord.playback.QueueShuffle.setEnabled(true);
        Queue queue = queue(0, "now", "far", "a", "b");
        arrange(queue);
        assertEquals(0, queue.moves);
        assertEquals(Arrays.asList("now", "far", "a", "b"), queue.ids());

        com.music.bitchord.playback.QueueShuffle.setEnabled(false);
        arrange(queue);
        assertEquals(Arrays.asList("now", "b", "a", "far"), queue.ids());
    }

    @Test
    public void inAppShuffleSwitchedOnWhileTheOrderIsPending() throws Exception {
        Queue queue = queue(0, "now", "far", "a", "b");
        DjMode.arrange(queue.player);
        com.music.bitchord.playback.QueueShuffle.setEnabled(true);
        settle();
        assertEquals(0, queue.moves);
        assertEquals(Arrays.asList("now", "far", "a", "b"), queue.ids());

        // The dropped order left no stamp, so switching shuffle off orders the same queue.
        com.music.bitchord.playback.QueueShuffle.setEnabled(false);
        arrange(queue);
        assertEquals(Arrays.asList("now", "b", "a", "far"), queue.ids());
    }

    @Test
    public void bothShufflesOffOrdersTheQueue() throws Exception {
        Queue queue = queue(0, "now", "far", "a", "b");
        queue.shuffle = false;
        com.music.bitchord.playback.QueueShuffle.setEnabled(false);
        arrange(queue);
        assertEquals(Arrays.asList("now", "b", "a", "far"), queue.ids());
    }

    @Test
    public void secondPassDoesNotMoveAgainOrHideTheReorder() throws Exception {
        Queue queue = queue(0, "now", "far", "a", "b");
        arrange(queue);
        int moved = queue.moves;
        String said = DjMode.lastArrangement;
        assertTrue(moved > 0);
        arrange(queue);
        arrange(queue);
        assertEquals(moved, queue.moves);
        assertEquals(said, DjMode.lastArrangement);
        assertEquals(Arrays.asList("now", "b", "a", "far"), queue.ids());
    }
}

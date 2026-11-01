package app.bitchord.telegram;

import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Finds what is playing and transferring. If any of it can't be read, nothing counts as idle. */
public final class StorageSenseActive {
    private StorageSenseActive() {}

    private static final int BEHIND = 1, AHEAD = 3;
    private static volatile WeakReference<Object> service = new WeakReference<>(null);

    /** Called by Morphe from BitChord's playback service as it starts. */
    public static void playbackService(Object playbackService) {
        service = new WeakReference<>(playbackService);
    }

    /** Morphe makes this true once the service above reports itself. */
    public static boolean playbackHookApplied() {
        return false;
    }

    private static final class Queue {
        final List<String> mediaIds = new ArrayList<>(), keys = new ArrayList<>();
        final List<String> uris = new ArrayList<>();
        boolean known;
    }

    private static Object player() throws Exception {
        Object owner = service.get();
        if (owner != null) {
            Field field = owner.getClass().getDeclaredField("player");
            field.setAccessible(true);
            return field.get(owner);
        }
        return DjMode.sessionPlayer();
    }

    private static void read(Queue queue) {
        try {
            Object found = player();
            if (found == null) {
                // No player object: known idle only when the service would have reported itself.
                queue.known = playbackHookApplied();
                return;
            }
            Player player = (Player) found;
            int current = player.getCurrentMediaItemIndex();
            int count = player.getMediaItemCount();
            for (int at = Math.max(0, current - BEHIND);
                    at < count && at <= current + AHEAD;
                    at++) {
                MediaItem item = player.getMediaItemAt(at);
                if (item == null) continue;
                if (item.mediaId != null) queue.mediaIds.add(item.mediaId);
                if (item.localConfiguration != null && item.localConfiguration.uri != null)
                    queue.uris.add(item.localConfiguration.uri.toString());
            }
            queue.known = true;
        } catch (Exception | LinkageError unreadable) {
            queue.known = false;
        }
    }

    /** Players answer on their own thread only. A busy main thread reads as unknown. */
    static StorageSense.Active capture() {
        Queue queue = new Queue();
        if (Looper.myLooper() == Looper.getMainLooper()) read(queue);
        else {
            CountDownLatch done = new CountDownLatch(1);
            new Handler(Looper.getMainLooper())
                    .post(
                            () -> {
                                read(queue);
                                done.countDown();
                            });
            try {
                if (!done.await(2, TimeUnit.SECONDS)) return StorageSense.Active.unknown();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return StorageSense.Active.unknown();
            }
        }
        if (!queue.known) return StorageSense.Active.unknown();
        List<String> downloads = new ArrayList<>();
        try {
            Class<?> type = Class.forName("com.music.bitchord.playback.AudioCache");
            Object heads = declared(type, "analysisHeadsInFlight");
            if (!(heads instanceof Collection)) return StorageSense.Active.unknown();
            for (Object id : (Collection<?>) heads) queue.mediaIds.add(String.valueOf(id));
            for (String uri : queue.uris) {
                if (!uri.startsWith("bitchord:")) continue;
                Object key =
                        type.getMethod("cacheKeyOf", android.net.Uri.class)
                                .invoke(null, android.net.Uri.parse(uri));
                if (key instanceof String) queue.keys.add((String) key);
            }
            Class<?> host = Class.forName("com.music.bitchord.download.Downloads");
            Object lock = declared(host, "lock");
            synchronized (lock) {
                downloads.addAll(keys(declared(host, "pending")));
                downloads.addAll(keys(declared(host, "running")));
            }
            Object flow = declared(host, "active");
            java.lang.reflect.Method read = flow.getClass().getMethod("getValue");
            read.setAccessible(true);
            downloads.addAll(keys(read.invoke(flow)));
        } catch (Exception | LinkageError unreadable) {
            return StorageSense.Active.unknown();
        }
        return new StorageSense.Active(true, queue.mediaIds, queue.keys, queue.uris, downloads);
    }

    private static Object declared(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static List<String> keys(Object map) throws Exception {
        if (!(map instanceof Map)) throw new IllegalStateException("Unexpected download state");
        List<String> out = new ArrayList<>();
        for (Object key : ((Map<?, ?>) map).keySet()) out.add(String.valueOf(key));
        return out;
    }
}

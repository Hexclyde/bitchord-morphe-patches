package app.bitchord.telegram;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;

import com.music.bitchord.data.NerdStats;
import com.music.bitchord.data.sources.StreamFormat;
import com.music.bitchord.playback.PlaybackService;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;

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
 *
 * <p>The comparison is also a check that it's the same recording: when the copies don't line up,
 * the stream keeps playing, and a file that fails at two upgrades isn't used for the song again.
 * When the song has only just started, the switch waits for a few seconds of it to compare.
 *
 * <p>Starting the new copy still takes a moment, so before the crossfade it's held back until it
 * plays in step with the old one (see {@link #settled}).
 */
public final class SmoothUpgrade {
    private SmoothUpgrade() {}

    static volatile boolean enabled = true;
    private static final Set<Object> handedOver =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    // The offset measured for the swap about to start, and when: {offsetMs, millis}.
    private static volatile double[] aligned;
    private static volatile String alignedFor;

    // Whether BitChord's swap asked for that offset: only then is it this swap's to take.
    private static volatile boolean armed;

    /** The offset the recording check measured for this upgrade, in milliseconds, or null. */
    static volatile java.util.function.Function<String, Long> verified =
            SmoothUpgrade::verifiedOffset;

    /** The player being listened to; null when there's none. On the player's thread. */
    static volatile java.util.function.Supplier<Player> playing = SmoothUpgrade::servicePlayer;

    /** Starts BitChord's two-player swap; false when it can't run right now. */
    interface Launcher {
        boolean start(Object service, Player player, int index, MediaItem item);
    }

    static volatile Launcher launcher = PlaybackService::bitchordSmoothSwap;

    static volatile Measurer measurer = VersionAlign::measure;

    /** Compares the new copy with what plays; see {@link VersionAlign#measure}. */
    interface Measurer {
        int measure(Player player, MediaItem item, VersionAlign.Measured then);
    }

    /** What identifies the copy an item plays, so an offset is only used with the one it fits. */
    static volatile java.util.function.Function<MediaItem, Object> rendition =
            item ->
                    item == null || item.localConfiguration == null
                            ? null
                            : String.valueOf(item.localConfiguration.uri);

    /** What BitChord records about the stream a song plays, once an upgrade is heard. */
    interface Stats {
        void stream(String mediaId, StreamFormat format, String source);

        void source(String mediaId, String name);
    }

    static volatile Stats stats =
            new Stats() {
                @Override
                public void stream(String mediaId, StreamFormat format, String source) {
                    NerdStats.onSourceStream(mediaId, format, source);
                }

                @Override
                public void source(String mediaId, String name) {
                    NerdStats.recordSource(mediaId, name);
                }
            };

    /** Diagnostics go to BitChord's log; never an exception's message, only its class. */
    static volatile java.util.function.Consumer<String> log = HostLog::d;

    private static void note(String line) {
        try {
            log.accept(line);
        } catch (Throwable ignored) {
            // Logging is never worth failing over.
        }
    }

    private static String named(Throwable failure) {
        return failure.getClass().getSimpleName();
    }

    /** Runs a task later, on the player's thread, and returns what cancels it. */
    interface Scheduler {
        Runnable after(Player player, long delayMs, Runnable task);
    }

    static volatile Scheduler scheduler =
            (player, delayMs, task) -> {
                android.os.Handler handler = new android.os.Handler(player.getApplicationLooper());
                handler.postDelayed(task, delayMs);
                return () -> handler.removeCallbacks(task);
            };

    /** The longest an upgrade may stay undecided, from its start until the copy is heard. */
    static final long COMMIT_MS = 120_000;

    /** How BitChord starts the watch that reverts an upgrade whose duration is wrong. */
    interface Watcher {
        void launch(Object scope, Object context, Object start, Object block, int mask)
                throws Exception;
    }

    static volatile Watcher watcher = SmoothUpgrade::launchReflectively;

    /**
     * What BitChord does as it commits an upgrade, besides replacing the item: it records the new
     * stream in its statistics, drops the fallback it kept for the song and starts the watch that
     * checks the copy's duration. The copy only counts once it is heard, which can fail, so all of
     * that belongs to this one attempt and waits for it.
     */
    static final class SwapCommit {
        final String mediaId;
        final MediaItem target;
        final Object targetRendition;
        final Player player;
        Object service;
        final java.util.List<Runnable> held = new java.util.ArrayList<>();
        final java.util.List<Runnable> cleanups = new java.util.ArrayList<>();
        // 0 waiting for the copy to be heard, 1 heard, 2 given up on.
        int state;
        // Whether BitChord's two-player swap runs for this attempt.
        volatile boolean swapping;
        Integer generation;

        SwapCommit(Object service, Player player, MediaItem target) {
            this.service = service;
            this.player = player;
            this.target = target;
            this.mediaId = mediaId(target);
            this.targetRendition = rendition.apply(target);
        }

        boolean is(Object item) {
            return item instanceof MediaItem
                    && (item == target
                            || (targetRendition != null
                                    && Objects.equals(mediaId, mediaId((MediaItem) item))
                                    && Objects.equals(
                                            targetRendition, rendition.apply((MediaItem) item))));
        }
    }

    private static volatile SwapCommit commit;
    private static final java.util.Map<Object, SwapCommit> swapOwners =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final ThreadLocal<SwapCommit> callbackOwner = new ThreadLocal<>();

    /** Each coroutine keeps the attempt that created its target, including after cancellation. */
    public static void bindSwap(Object coroutine) {
        synchronized (swapOwners) {
            if (swapOwners.containsKey(coroutine)) return;
            SwapCommit h = commit;
            try {
                Class<?> type = coroutine.getClass();
                if (h == null
                        || !h.swapping
                        || type.getField("this$0").get(coroutine) != h.service
                        || type.getField("$activePlayer").get(coroutine) != h.player
                        || type.getField("$targetMediaItem").get(coroutine) != h.target) h = null;
            } catch (ReflectiveOperationException unavailable) {
                h = null;
            }
            if (h != null) {
                try {
                    h.generation = coroutine.getClass().getField("$generation").getInt(coroutine);
                } catch (ReflectiveOperationException unavailable) {
                    h.generation = null;
                }
            }
            swapOwners.put(coroutine, h);
        }
    }

    public static void captureSwap(Object coroutine) {
        callbackOwner.set(swapOwners.get(coroutine));
    }

    private static SwapCommit takeSwap() {
        SwapCommit h = callbackOwner.get();
        callbackOwner.remove();
        return h;
    }

    // The attempt the offset in `aligned` was measured for.
    private static volatile SwapCommit alignedOwner;

    /** Whether this attempt is still the one waiting to be heard. */
    static boolean live(SwapCommit h) {
        synchronized (h) {
            return h.state == 0 && commit == h;
        }
    }

    /** Starts an upgrade attempt; one still waiting is given up on. */
    static SwapCommit begin(Object service, Player player, MediaItem target) {
        SwapCommit previous = commit;
        // Its marks are the new attempt's now: the host set them again for it.
        if (previous != null) drop(previous, "a newer upgrade started", false);
        SwapCommit next = new SwapCommit(service, player, target);
        commit = next;
        try {
            addCleanup(
                    next,
                    scheduler.after(
                            player, COMMIT_MS, () -> drop(next, "it was not heard in time", true)));
        } catch (Throwable unscheduled) {
            // Without a looper there is no deadline; the other ways out still apply.
        }
        return next;
    }

    private static void addCleanup(SwapCommit h, Runnable cleanup) {
        if (cleanup == null) return;
        synchronized (h) {
            if (h.state == 0) {
                h.cleanups.add(cleanup);
                return;
            }
        }
        cleanup.run();
    }

    /** Runs {@code action} for the song now, or once its upgrade is heard; never if it fails. */
    private static void hold(Object mediaId, Runnable action) {
        SwapCommit h = commit;
        if (h != null && Objects.equals(h.mediaId, mediaId)) {
            synchronized (h) {
                if (h.state == 0) {
                    h.held.add(action);
                    return;
                }
                if (h.state == 2) return;
            }
        }
        action.run();
    }

    /** Replaces the upgrade's NerdStats.onSourceStream call. */
    public static void stream(String mediaId, StreamFormat format, String source) {
        hold(mediaId, () -> stats.stream(mediaId, format, source));
    }

    /** Replaces the upgrade's NerdStats.recordSource call. */
    public static void source(String mediaId, String name) {
        hold(mediaId, () -> stats.source(mediaId, name));
    }

    /**
     * Replaces the upgrade's removal of the fallback it kept for the song. Only the fallback that
     * was there for this attempt goes; a newer one for the same song stays.
     */
    public static void shelved(java.util.concurrent.ConcurrentHashMap<?, ?> map, Object mediaId) {
        Object mine = map.get(mediaId);
        if (mine == null) return;
        hold(
                mediaId,
                () ->
                        ((java.util.concurrent.ConcurrentHashMap<Object, Object>) map)
                                .remove(mediaId, mine));
    }

    /** Replaces the launch of the watch that reverts an upgrade whose duration is wrong. */
    public static void watch(Object scope, Object context, Object start, Object block, int mask) {
        String id = null;
        try {
            java.lang.reflect.Field field = block.getClass().getField("$mediaId");
            id = (String) field.get(block);
        } catch (Throwable unknown) {
            // Started at once, as BitChord does.
        }
        hold(
                id,
                () -> {
                    try {
                        watcher.launch(scope, context, start, block, mask);
                    } catch (Throwable failed) {
                        note(
                                "Unofficial Telegram couldn't start the upgrade check: "
                                        + named(failed));
                    }
                });
    }

    private static final java.util.Map<Player, Long> restorePositions =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** A rejected copy falls back at the current position, not the earlier audition snapshot. */
    public static void restoreCopy(Player player, int index, MediaItem item) {
        long at = player.getCurrentPosition();
        player.replaceMediaItem(index, item);
        restorePositions.put(player, Math.max(0, at));
    }

    public static void restoreSeek(Player player, int index, long snapshot) {
        Long at = restorePositions.remove(player);
        player.seekTo(index, at == null ? snapshot : at);
    }

    private static void launchReflectively(
            Object scope, Object context, Object start, Object block, int mask) throws Exception {
        Class<?> jobs = Class.forName("kotlinx.coroutines.JobKt");
        for (java.lang.reflect.Method method : jobs.getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (method.getName().equals("launch$default")
                    && types.length == 5
                    && types[4] == int.class) {
                method.invoke(null, scope, context, start, block, mask);
                return;
            }
        }
        throw new NoSuchMethodException("launch$default");
    }

    /**
     * Called when BitChord's swap has made {@code active} the player that plays, in place of {@code
     * old}, for {@code service}. Only the attempt that started this swap, and whose copy is on that
     * player, is heard.
     */
    public static void adopted(Object service, Object old, Object active) {
        SwapCommit h = takeSwap();
        if (h == null || service == null || !(active instanceof Player)) return;
        synchronized (h) {
            if (h.state != 0 || !h.swapping || h.service != service || old != h.player) return;
        }
        try {
            if (active == old || !h.is(((Player) active).getCurrentMediaItem())) return;
        } catch (RuntimeException unreadable) {
            return; // Not readable from this thread: not confirmed.
        }
        adopt(h);
    }

    /**
     * Replaces the swap's own direct replaceMediaItem. The copy is then only loaded: it counts once
     * the player reports it ready.
     */
    public static void replaced(Player player, int index, MediaItem item) {
        player.replaceMediaItem(index, item);
        SwapCommit h = takeSwap();
        if (h != null && h.swapping && h.player == player && h.is(item) && live(h))
            awaitHeard(h, player);
    }

    /** BitChord's swap for this service ended without adopting: it was stopped or failed. */
    public static void abandoned(Object service) {
        SwapCommit h = takeSwap();
        if (h != null && service != null && h.swapping && h.service == service)
            drop(h, "BitChord's swap was stopped", true);
    }

    /** BitChord's swap for this service ended with {@code failure}, which includes a cancel. */
    public static void ended(Object service, Throwable failure) {
        SwapCommit h = takeSwap();
        if (h != null && service != null && h.swapping && h.service == service)
            drop(
                    h,
                    "BitChord's swap ended ("
                            + (failure == null ? "no reason" : named(failure))
                            + ")",
                    true);
    }

    /** The direct replacement is heard once its player reports the copy ready and playing. */
    private static void awaitHeard(SwapCommit h, Player player) {
        Player.Listener listener =
                new Player.Listener() {
                    boolean loading = player.getPlaybackState() != Player.STATE_READY;

                    private void look() {
                        if (loading && live(h) && heard(h, player)) adopt(h);
                    }

                    @Override
                    public void onPlaybackStateChanged(int state) {
                        if (state != Player.STATE_READY) loading = true;
                        look();
                    }

                    @Override
                    public void onIsPlayingChanged(boolean isPlaying) {
                        look();
                    }

                    @Override
                    public void onPlayWhenReadyChanged(int reason, boolean playWhenReady) {
                        look();
                    }

                    public void onPlayWhenReadyChanged(boolean ready, int reason) {
                        look();
                    }

                    public void onMediaItemTransition(MediaItem next, int reason) {
                        onMediaItemTransition(reason, next);
                    }

                    @Override
                    public void onPlayerError(androidx.media3.common.PlaybackException error) {
                        drop(h, "playback failed (" + named(error) + ")", true);
                    }

                    @Override
                    public void onMediaItemTransition(int reason, MediaItem next) {
                        if (live(h) && (next == null || !h.is(next)))
                            drop(h, "the track changed", true);
                    }
                };
        try {
            player.addListener(listener);
        } catch (RuntimeException unreachable) {
            drop(h, "the player couldn't be watched", true);
            return;
        }
        addCleanup(
                h,
                () -> {
                    try {
                        player.removeListener(listener);
                    } catch (RuntimeException ignored) {
                        // The player is gone with its listeners.
                    }
                });
    }

    private static boolean heard(SwapCommit h, Player player) {
        try {
            return player.getPlaybackState() == Player.STATE_READY
                    && h.is(player.getCurrentMediaItem())
                    && (!player.getPlayWhenReady() || player.isPlaying());
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    private static void finish(SwapCommit h, java.util.List<Runnable> cleanups) {
        for (Runnable cleanup : cleanups) {
            try {
                cleanup.run();
            } catch (Throwable ignored) {
                // Nothing left to undo.
            }
        }
        if (alignedOwner == h) {
            aligned = null;
            alignedFor = null;
            armed = false;
            alignedOwner = null;
        }
    }

    private static void adopt(SwapCommit h) {
        java.util.List<Runnable> run, cleanups;
        synchronized (h) {
            if (h.state != 0) return;
            h.state = 1;
            h.service = null;
            run = new java.util.ArrayList<>(h.held);
            h.held.clear();
            cleanups = new java.util.ArrayList<>(h.cleanups);
            h.cleanups.clear();
        }
        finish(h, cleanups);
        for (Runnable action : run) {
            try {
                action.run();
            } catch (Throwable failed) {
                note("Unofficial Telegram couldn't record the upgrade: " + named(failed));
            }
        }
    }

    /**
     * The copy never became the one that plays: BitChord's statistics and fallback stay. {@code
     * marks} also clears what BitChord marked on its service, unless a newer attempt owns them.
     */
    private static void drop(SwapCommit h, String reason, boolean marks) {
        Object service;
        java.util.List<Runnable> cleanups;
        synchronized (h) {
            if (h.state != 0) return;
            h.state = 2;
            service = h.service;
            h.service = null;
            h.held.clear();
            cleanups = new java.util.ArrayList<>(h.cleanups);
            h.cleanups.clear();
        }
        finish(h, cleanups);
        cancelSwap(h, service);
        Settling s = settling;
        if (s != null && s.commit == h) clearSettling(s);
        if (marks && commit == h && ownsGeneration(h, service)) release(service, h.mediaId);
        note("Unofficial Telegram left the song as it was: " + reason);
    }

    static void drop(SwapCommit h, String reason) {
        drop(h, reason, true);
    }

    private static boolean ownsGeneration(SwapCommit h, Object service) {
        if (h.generation == null || service == null) return true;
        try {
            java.lang.reflect.Field field =
                    service.getClass().getDeclaredField("versionSwapGeneration");
            field.setAccessible(true);
            return field.getInt(service) == h.generation;
        } catch (ReflectiveOperationException unavailable) {
            return false;
        }
    }

    private static void cancelSwap(SwapCommit h, Object service) {
        if (!h.swapping || service == null || h.generation == null) return;
        try {
            java.lang.reflect.Field generation =
                    service.getClass().getDeclaredField("versionSwapGeneration");
            generation.setAccessible(true);
            if (generation.getInt(service) != h.generation) return;
            java.lang.reflect.Field task = service.getClass().getDeclaredField("versionSwapJob");
            task.setAccessible(true);
            Object job = task.get(service);
            if (job != null)
                job.getClass()
                        .getMethod("cancel", CancellationException.class)
                        .invoke(job, new CancellationException("Upgrade ended"));
            java.lang.reflect.Field spareField = service.getClass().getDeclaredField("spare");
            spareField.setAccessible(true);
            Object spare = spareField.get(service);
            if (spare instanceof Player
                    && spare != h.player
                    && h.is(((Player) spare).getCurrentMediaItem())) {
                ((Player) spare).pause();
                ((Player) spare).clearMediaItems();
            }
        } catch (ReflectiveOperationException unavailable) {
            note("Unofficial Telegram couldn't release the standby: " + named(unavailable));
        }
    }

    /** Clears what BitChord marked on its service as the swap began, which nothing else will. */
    private static void release(Object service, String mediaId) {
        if (service == null) return;
        try {
            java.lang.reflect.Field field = service.getClass().getDeclaredField("swappingMediaId");
            field.setAccessible(true);
            if (!Objects.equals(field.get(service), mediaId)) return;
            field.set(service, null);
            field = service.getClass().getDeclaredField("swapCutAt");
            field.setAccessible(true);
            field.set(service, null);
        } catch (Throwable ignored) {
            // The service may already have finished the swap.
        }
    }

    private static Player servicePlayer() {
        try {
            java.lang.reflect.Method owner =
                    Class.forName("app.bitchord.telegram.SourceAutomix").getDeclaredMethod("owner");
            owner.setAccessible(true);
            Object service = owner.invoke(null);
            java.lang.reflect.Field player = service.getClass().getDeclaredField("player");
            player.setAccessible(true);
            return (Player) player.get(service);
        } catch (Throwable unavailable) {
            return null;
        }
    }

    private static final java.util.Map<String, AutoCloseable> auditions = new java.util.HashMap<>();

    public static void auditionStarted(String mediaId) {
        synchronized (auditions) {
            auditionEnded(mediaId);
            auditions.put(mediaId, PlaybackPriority.upgrade(mediaId));
        }
    }

    public static void auditionEnded(String mediaId) {
        synchronized (auditions) {
            AutoCloseable lease = auditions.remove(mediaId);
            if (lease != null) {
                try {
                    lease.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Replaces where BitChord's audition of an upgrade starts. It's handed the position from when
     * the upgrade began, before the copy was fetched and checked, but it must then buffer past
     * where the song is by the time it asks, and it only buffers 40 seconds from its start. A check
     * that took long enough would leave that out of reach, whatever the connection.
     */
    public static long auditionPosition(MediaItem item, long at) {
        try {
            Player player = playing.get();
            MediaItem current = player == null ? null : player.getCurrentMediaItem();
            if (item == null || current == null || !Objects.equals(item.mediaId, current.mediaId))
                return at;
            long currentAt = Math.max(at, player.getCurrentPosition());
            Long offset = null;
            try {
                offset =
                        (Long)
                                Class.forName("app.bitchord.telegram.SourcePreference")
                                        .getMethod("peekVerifiedOffset", String.class)
                                        .invoke(null, item.mediaId);
            } catch (ReflectiveOperationException unavailable) {
            }
            long target = Math.max(0, currentAt + (offset == null ? 0 : offset));
            note(
                    "Upgrade audition track="
                            + item.mediaId
                            + " snapshotMs="
                            + at
                            + " currentMs="
                            + currentAt
                            + " targetMs="
                            + target);
            return target;
        } catch (Throwable unavailable) {
            return at;
        }
    }

    private static Long verifiedOffset(String mediaId) {
        try {
            return (Long)
                    Class.forName("app.bitchord.telegram.SourcePreference")
                            .getMethod("verifiedOffset", String.class)
                            .invoke(null, mediaId);
        } catch (Throwable unavailable) {
            return null;
        }
    }

    /** How long the new copy may be held back before the crossfade, and listened to at most. */
    static final long SETTLE_MS = 2400, LISTEN_MS = 1500;

    /** Close enough; and the most cut to catch up, more being sought instead. */
    static final double CLOSE_MS = 0.5, MOST_CUT_MS = 150;

    private static volatile Settling settling;

    /** How long to wait for enough of the song to compare, in seconds of it playing. */
    static final int LISTEN_SECONDS = 25;

    private static final ThreadLocal<Object> commitOwner = new ThreadLocal<>();

    /** Captures the service before the host reuses its register while building the item. */
    public static void captureOwner(Object service) {
        commitOwner.set(service);
    }

    static Object takeOwner() {
        Object service = commitOwner.get();
        commitOwner.remove();
        return service;
    }

    /** Replaces the upgrade's replaceMediaItem call. */
    public static void replace(Object ignored, Player player, int index, MediaItem item) {
        Object service = takeOwner();
        handedOver.remove(player);
        SwapCommit h = begin(service, player, item);
        if (enabled && start(h, player, index, item, 0, 0)) {
            handedOver.add(player);
            return;
        }
        // BitChord seeks and prepares next; the copy counts once the player reports it ready.
        player.replaceMediaItem(index, item);
        awaitHeard(h, player);
    }

    /**
     * Compares the Telegram copy with what's playing before switching, waiting for the first few
     * seconds of the song when it has only just started. True when this takes the switch over.
     */
    private static boolean start(
            SwapCommit h, Player player, int index, MediaItem item, int played, int polls) {
        String playing = mediaId(player.getCurrentMediaItem());
        int status;
        try {
            status =
                    measurer.measure(
                            player,
                            item,
                            (found, telegramId) -> {
                                // A newer attempt, or an end, may have come while it was measured.
                                if (!live(h)) return;
                                // The song may have changed while it was measured.
                                if (!still(player, index, playing)) {
                                    drop(h, "the track changed");
                                    return;
                                }
                                measured(h, player, index, item, found, telegramId);
                            });
        } catch (Throwable ignored) {
            status = VersionAlign.CANT; // Not measured: swapped as before.
        }
        if (status == VersionAlign.MEASURING) return true;
        if (!live(h)) return true;
        if (status == VersionAlign.WAIT && played < LISTEN_SECONDS && polls < 3600) {
            if (polls == 0)
                note(
                        "Unofficial Telegram is listening to the song for a few seconds before"
                                + " switching, to check the Telegram copy is the same recording");
            try {
                addCleanup(
                        h,
                        scheduler.after(
                                player,
                                1000,
                                () -> {
                                    if (!live(h)) return;
                                    if (!still(player, index, playing)) {
                                        drop(h, "the track changed");
                                        return;
                                    }
                                    int next = player.isPlaying() ? played + 1 : played;
                                    if (!start(h, player, index, item, next, polls + 1))
                                        go(h, player, index, item, null);
                                }));
            } catch (Throwable unscheduled) {
                return false;
            }
            return true;
        }
        if (polls == 0) return swap(h, player, index, item);
        note("Unofficial Telegram couldn't compare the copies; switching anyway");
        return false;
    }

    private static boolean still(Player player, int index, String playing) {
        return player.getCurrentMediaItemIndex() == index
                && Objects.equals(playing, mediaId(player.getCurrentMediaItem()));
    }

    /**
     * The comparison is in. When the copies line up, the switch goes ahead from where the Telegram
     * copy has the same moment; when they don't, it's most likely another recording under the same
     * name, such as a cover with the original's tags, and the stream keeps playing.
     */
    static void measured(
            SwapCommit h,
            Player player,
            int index,
            MediaItem item,
            double[] found,
            String telegramId) {
        if (!live(h)) return;
        if (found != null && !VersionAlign.clear(found)) {
            CharSequence title =
                    player.getCurrentMediaItem() == null
                                    || player.getCurrentMediaItem().mediaMetadata == null
                            ? null
                            : player.getCurrentMediaItem().mediaMetadata.title;
            long duration = player.getDuration();
            SoundCheck recordings = SoundCheck.recordings;
            boolean dropped =
                    title != null
                            && duration > 0
                            && recordings != null
                            && recordings.soundedDifferent(
                                    telegramId,
                                    title.toString(),
                                    (int) Math.round(duration / 1000.0),
                                    found[1]);
            note(
                    String.format(
                            java.util.Locale.US,
                            "Unofficial Telegram kept the stream: the Telegram copy doesn't sound"
                                    + " like what's playing (loudness match %.2f), so it's"
                                    + " probably another recording%s",
                            found[1],
                            dropped
                                    ? "; it won't be used for this song again"
                                    : "; it'll be compared again next time"));
            drop(h, "the Telegram copy is probably another recording");
            return;
        }
        go(h, player, index, item, VersionAlign.clear(found) ? found[0] : null);
    }

    /** Switches, starting the new copy {@code offset} ms later than the old one's position. */
    private static void go(SwapCommit h, Player player, int index, MediaItem item, Double offset) {
        if (!live(h)) return;
        aligned = offset == null ? null : new double[] {offset, System.currentTimeMillis()};
        alignedFor = mediaId(item);
        alignedOwner = h;
        if (swap(h, player, index, item)) return;
        aligned = null;
        alignedOwner = null;
        long at = player.getCurrentPosition() + (offset == null ? 0 : Math.round(offset));
        player.replaceMediaItem(index, item);
        player.seekTo(index, Math.max(0, at));
        player.prepare();
        awaitHeard(h, player);
    }

    private static boolean swap(SwapCommit h, Player player, int index, MediaItem item) {
        Settling s = null;
        try {
            // An offset measured for another attempt is not this one's.
            if (alignedOwner != h) {
                aligned = null;
                alignedFor = null;
                armed = false;
            }
            offerVerified(mediaId(item));
            alignedOwner = h;
            double[] offset = aligned;
            s =
                    new Settling(
                            h,
                            player,
                            index,
                            item,
                            offset == null || System.currentTimeMillis() - offset[1] >= 10_000
                                    ? null
                                    : offset[0]);
            settling = s;
            synchronized (h) {
                h.swapping = true;
            }
            if (launcher.start(h.service, player, index, item)) return true;
        } catch (CancellationException cancelled) {
            return true;
        } catch (Throwable ignored) {
            // Falls back to the original swap, which keeps the old copy's position.
        }
        synchronized (h) {
            h.swapping = false;
        }
        clearSettling(s);
        aligned = null;
        alignedFor = null;
        armed = false;
        return false;
    }

    /** Unless this upgrade was measured here, starts from the recording check's offset. */
    static void offerVerified(String mediaId) {
        if (!Objects.equals(alignedFor, mediaId)
                || (aligned != null && System.currentTimeMillis() - aligned[1] >= 10_000)) {
            aligned = null;
            armed = false;
        }
        alignedFor = mediaId;
        Long offset = aligned == null ? verified.apply(mediaId) : null;
        if (offset != null) aligned = new double[] {offset, System.currentTimeMillis()};
    }

    /**
     * Called as BitChord's swap is set up, for whether to run its alignment step, which {@link
     * #alignedOffset} answers first. It otherwise takes the offset between two copies of the same
     * song as zero and never asks.
     */
    public static boolean measureOffset() {
        double[] found = aligned;
        armed = found != null && System.currentTimeMillis() - found[1] < 10_000;
        return armed;
    }

    private static String mediaId(MediaItem item) {
        return item == null ? null : item.mediaId;
    }

    /**
     * Called first in BitChord's version alignment: the offset measured for the upgrade being
     * swapped in, once, or null to let BitChord measure as it does.
     */
    public static Long alignedOffset() {
        double[] found = aligned;
        boolean asked = armed;
        aligned = null;
        alignedFor = null;
        armed = false;
        return asked && found != null && System.currentTimeMillis() - found[1] < 10_000
                ? Math.round(found[0])
                : null;
    }

    /** One upgrade's swap, from launch until the new copy plays in step. */
    static final class Settling {
        final SwapCommit commit;
        final Player main;
        final int index;
        final String mainId;
        final MediaItem target;
        final Double offset;
        final Object reference;
        long began, looked;
        int seeks;
        double first = Double.NaN, cut;
        VersionAlign.Heard catching;
        java.util.concurrent.Future<double[]> measuring;

        Settling(SwapCommit commit, Player main, int index, MediaItem target, Double offset) {
            this.commit = commit;
            this.main = main;
            this.index = index;
            this.mainId = mediaId(main.getCurrentMediaItem());
            this.reference = rendition.apply(main.getCurrentMediaItem());
            this.target = target;
            this.offset = offset;
        }

        boolean owns(Player spare) {
            MediaItem item = spare.getCurrentMediaItem();
            return target != null
                    && item != null
                    && Objects.equals(target.mediaId, item.mediaId)
                    && Objects.equals(
                            target.localConfiguration == null
                                    ? null
                                    : target.localConfiguration.uri,
                            item.localConfiguration == null ? null : item.localConfiguration.uri);
        }
    }

    private static long now() {
        return System.nanoTime() / 1_000_000;
    }

    /**
     * Replaces the playback state BitChord's swap waits on before the crossfade, which it gives up
     * on after a while. The new copy has just been sought to the old one's position, but starting
     * takes a moment, so it plays a little behind. Until it's in step this says it's still
     * buffering: it's listened to (see {@link VersionAlign#lag}) and leaves out what it's behind
     * by, or is sought again when that's more than a cut should be.
     */
    public static int settled(Player spare) {
        int state = spare.getPlaybackState();
        Settling s = settling;
        if (s == null || spare == s.main) return state;
        if (!s.owns(spare)) {
            clearSettling(s);
            return state;
        }
        try {
            if (!live(s.commit)) throw abort(s, spare, "the upgrade was given up on");
            if (!still(s.main, s.index, s.mainId))
                throw abort(s, spare, "the playing track changed");
            // The offset was measured against this copy; against another it would be wrong.
            if (!Objects.equals(s.reference, rendition.apply(s.main.getCurrentMediaItem())))
                throw abort(s, spare, "the playing copy changed");
            long now = now();
            if (s.began == 0) s.began = now;
            if (now - s.began > SETTLE_MS) {
                if (state == Player.STATE_READY && clocksAgree(s, spare)) {
                    done(s, "verified playback positions agree", spare);
                    return state;
                }
                throw abort(s, spare, "alignment timed out");
            }
            if (state != Player.STATE_READY) return state;
            return step(s, spare) ? state : Player.STATE_BUFFERING;
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (Throwable failure) {
            throw abort(s, spare, "alignment failed");
        }
    }

    /** The host can skip its ready wait; never fade in a copy that was not aligned. */
    public static void fadeVolume(Player spare, float volume) {
        Settling s = settling;
        if (s != null && s.owns(spare) && volume > 0) {
            settled(spare);
            if (settling == s) throw abort(s, spare, "the incoming copy is not aligned");
        }
        spare.setVolume(volume);
    }

    private static boolean clocksAgree(Settling s, Player spare) {
        return s.offset != null
                && Math.abs(s.main.getCurrentPosition() + s.offset - spare.getCurrentPosition())
                        <= 25;
    }

    private static void clearSettling(Settling s) {
        if (s == null) return;
        if (s.measuring != null) s.measuring.cancel(true);
        if (settling == s) settling = null;
    }

    private static CancellationException abort(Settling s, Player spare, String reason) {
        clearSettling(s);
        try {
            spare.stop();
            spare.setVolume(1f);
        } catch (Throwable ignored) {
            // The outgoing player remains untouched.
        }
        drop(s.commit, reason);
        note("Unofficial Telegram kept the current audio: " + reason);
        return new CancellationException("Quality upgrade was not aligned");
    }

    private static boolean step(Settling s, Player spare) throws Exception {
        long now = now();
        // A slow seek can leave the new copy beyond the waveform search window.
        if (s.offset != null
                && Math.abs(s.main.getCurrentPosition() + s.offset - spare.getCurrentPosition())
                        > 300
                && s.seeks < 2) {
            s.seeks++;
            if (s.measuring != null) s.measuring.cancel(true);
            s.measuring = null;
            spare.seekTo(
                    spare.getCurrentMediaItemIndex(),
                    Math.max(0, s.main.getCurrentPosition() + Math.round(s.offset)));
            return false;
        }
        if (s.catching != null) return s.catching.caughtUp() ? done(s, null, spare) : false;
        float speed = s.main.getPlaybackParameters().speed;
        if (s.measuring == null) {
            if (now - s.looked < 50) return false;
            s.looked = now;
            s.measuring = VersionAlign.lagLater(speed, 300, 600);
            return false;
        }
        if (!s.measuring.isDone()) return false;
        double[] found;
        try {
            found = s.measuring.get();
        } finally {
            s.measuring = null;
        }
        if (found == null) {
            if (now - s.began <= LISTEN_MS) return false;
            if (clocksAgree(s, spare)) return done(s, "verified playback positions agree", spare);
            throw abort(s, spare, "couldn't align both copies");
        }
        double lag = found[0];
        if (Double.isNaN(s.first)) s.first = lag;
        if (Math.abs(lag) <= CLOSE_MS) return done(s, null, spare);
        VersionAlign.Heard fresh = VersionAlign.newest();
        if (lag > 0 && lag <= MOST_CUT_MS && fresh != null) {
            s.cut += lag;
            fresh.catchUp(lag * speed * 1000);
            s.catching = fresh;
            return false;
        }
        if (s.seeks >= 2) throw abort(s, spare, "the copies are still too far apart");
        // Sought a little short, so it's behind and the rest is cut.
        s.seeks++;
        spare.seekTo(
                spare.getCurrentMediaItemIndex(),
                Math.max(0, spare.getCurrentPosition() + Math.round((lag - 20) * speed)));
        return false;
    }

    private static boolean done(Settling s, String trouble, Player spare) {
        clearSettling(s);
        note(
                String.format(
                        java.util.Locale.US,
                        "Unofficial Telegram settled the upgrade: the Telegram copy started"
                                + " %s ms behind; cut %.2f ms, sought %d times%s",
                        Double.isNaN(s.first)
                                ? "?"
                                : String.format(java.util.Locale.US, "%+.2f", s.first),
                        s.cut,
                        s.seeks,
                        trouble == null ? "" : "; " + trouble));
        if (trouble == null && s.catching != null) checkLater(spare, s.main);
        return true;
    }

    /** Logs how far apart the two copies are partway into the crossfade. */
    private static void checkLater(Player spare, Player main) {
        try {
            float speed = main.getPlaybackParameters().speed;
            new android.os.Handler(spare.getApplicationLooper())
                    .postDelayed(
                            () -> {
                                double[] left = VersionAlign.lag(speed, 100, 400);
                                if (left != null)
                                    note(
                                            String.format(
                                                    java.util.Locale.US,
                                                    "Unofficial Telegram upgrade in step: %+.2f ms"
                                                            + " apart (waveform match %.2f)",
                                                    left[0],
                                                    left[1]));
                            },
                            450);
        } catch (Throwable ignored) {
            // Only a log line.
        }
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

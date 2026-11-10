package app.bitchord.telegram;

import androidx.media3.common.Player;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

public final class SmoothUpgradeTest {
    private static final java.util.Map<Object, Object> coroutines =
            new java.util.IdentityHashMap<>();

    public static final class SwapCoroutine {
        public final Object this$0;
        public final Player $activePlayer;
        public final int $generation;
        public final androidx.media3.common.MediaItem $targetMediaItem;

        SwapCoroutine(Object owner, Player player, androidx.media3.common.MediaItem item) {
            this$0 = owner;
            $generation = ((FakeService) owner).versionSwapGeneration;
            $activePlayer = player;
            $targetMediaItem = item;
        }
    }

    private static void adopted(Object owner, Object old, Object active) {
        SmoothUpgrade.captureSwap(coroutines.get(owner));
        SmoothUpgrade.adopted(owner, old, active);
    }

    private static void abandoned(Object owner) {
        SmoothUpgrade.captureSwap(coroutines.get(owner));
        SmoothUpgrade.abandoned(owner);
    }

    private static void ended(Object owner, Throwable failure) {
        SmoothUpgrade.captureSwap(coroutines.get(owner));
        SmoothUpgrade.ended(owner, failure);
    }

    static int checks;

    static void check(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }

    static androidx.media3.common.MediaItem item(String id) throws Exception {
        androidx.media3.common.MediaItem item = new androidx.media3.common.MediaItem();
        java.lang.reflect.Field field = item.getClass().getField("mediaId");
        field.setAccessible(true);
        field.set(item, id);
        return item;
    }

    static Player clockPlayer(androidx.media3.common.MediaItem song, long[] position, int state) {
        return clockPlayer(song, position, state, new ArrayList<>());
    }

    static Player clockPlayer(
            androidx.media3.common.MediaItem song, long[] position, int state, List<String> calls) {
        return (Player)
                Proxy.newProxyInstance(
                        Player.class.getClassLoader(),
                        new Class<?>[] {Player.class},
                        (proxy, method, arguments) -> {
                            switch (method.getName()) {
                                case "getCurrentMediaItem":
                                    return song;
                                case "getCurrentMediaItemIndex":
                                    return 0;
                                case "getCurrentPosition":
                                    return position[0];
                                case "getPlaybackState":
                                    return state;
                                case "getDuration":
                                    return 0L;
                                case "getPlaybackParameters":
                                    return new androidx.media3.common.PlaybackParameters(1f, 1f);
                                case "hashCode":
                                    return System.identityHashCode(proxy);
                                case "equals":
                                    return proxy == arguments[0];
                                case "getPlayWhenReady":
                                case "isPlaying":
                                    return false;
                                case "addListener":
                                case "removeListener":
                                    return null;
                                case "seekTo":
                                    position[0] = (long) arguments[1];
                                    calls.add("seek:" + position[0]);
                                    return null;
                                case "stop":
                                    calls.add("stop");
                                    return null;
                                case "setVolume":
                                    calls.add("volume:" + arguments[0]);
                                    return null;
                                default:
                                    if (!method.getName().startsWith("get"))
                                        calls.add(method.getName());
                                    return null;
                            }
                        });
    }

    /** A player whose state the test moves, and whose listeners it calls as ExoPlayer would. */
    static final class Deck implements java.lang.reflect.InvocationHandler {
        androidx.media3.common.MediaItem item;
        int state = Player.STATE_READY;
        boolean playWhenReady = true, playing = true, delayBuffer;
        final long[] position;
        final List<String> calls;
        final List<Player.Listener> listeners = new ArrayList<>();
        final Player player;

        Deck(androidx.media3.common.MediaItem item, long[] position, List<String> calls) {
            this.item = item;
            this.position = position;
            this.calls = calls;
            player =
                    (Player)
                            Proxy.newProxyInstance(
                                    Player.class.getClassLoader(),
                                    new Class<?>[] {Player.class},
                                    this);
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] arguments) {
            switch (method.getName()) {
                case "getCurrentMediaItem":
                    return item;
                case "getCurrentMediaItemIndex":
                    return 0;
                case "getCurrentPosition":
                    return position[0];
                case "getPlaybackState":
                    return state;
                case "getDuration":
                    return 0L;
                case "getPlayWhenReady":
                    return playWhenReady;
                case "isPlaying":
                    return playing;
                case "getPlaybackParameters":
                    return new androidx.media3.common.PlaybackParameters(1f, 1f);
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == arguments[0];
                case "addListener":
                    listeners.add((Player.Listener) arguments[0]);
                    return null;
                case "removeListener":
                    listeners.remove(arguments[0]);
                    return null;
                case "replaceMediaItem":
                    calls.add("replaceMediaItem");
                    item = (androidx.media3.common.MediaItem) arguments[1];
                    if (!delayBuffer) {
                        state = Player.STATE_BUFFERING;
                        playing = false;
                    }
                    return null;
                case "seekTo":
                    position[0] = (long) arguments[1];
                    calls.add("seek:" + position[0]);
                    return null;
                case "setVolume":
                    calls.add("volume:" + arguments[0]);
                    return null;
                default:
                    if (!method.getName().startsWith("get")) calls.add(method.getName());
                    return null;
            }
        }

        /** The player decoded enough of the current item to play it. */
        void ready() {
            state = Player.STATE_READY;
            playing = playWhenReady;
            for (Player.Listener each : new ArrayList<>(listeners))
                each.onPlaybackStateChanged(Player.STATE_READY);
        }

        void fail() {
            state = 1;
            playing = false;
            for (Player.Listener each : new ArrayList<>(listeners))
                each.onPlayerError(new androidx.media3.common.PlaybackException());
        }

        void moveTo(androidx.media3.common.MediaItem next) {
            item = next;
            for (Player.Listener each : new ArrayList<>(listeners))
                each.onMediaItemTransition(0, next);
        }
    }

    /** Tasks the extension asked to run later, which the test runs when it chooses. */
    static final class Clock implements SmoothUpgrade.Scheduler {
        final List<Runnable> tasks = new ArrayList<>();
        int cancelled;

        @Override
        public Runnable after(Player player, long delayMs, Runnable task) {
            tasks.add(task);
            return () -> {
                tasks.remove(task);
                cancelled++;
            };
        }

        void run() {
            for (Runnable task : new ArrayList<>(tasks)) task.run();
        }
    }

    /** The parts of the host service an upgrade's swap marks while it runs. */
    public static final class FakeJob {
        int cancellations;

        public void cancel(java.util.concurrent.CancellationException failure) {
            cancellations++;
        }
    }

    static final class FakeService {
        int versionSwapGeneration = 1;
        FakeJob versionSwapJob = new FakeJob();
        Player spare;
        String swappingMediaId;
        Long swapCutAt;
    }

    static Clock clock;
    static List<String> logged;
    static int launches;

    /** One upgrade as the host runs it: its bookkeeping follows the swap call, before it ends. */
    static final class Upgrade {
        final FakeService service;
        final List<String> recorded = new ArrayList<>(), calls = new ArrayList<>();
        final java.util.concurrent.ConcurrentHashMap<String, Object> shelf =
                new java.util.concurrent.ConcurrentHashMap<>();
        final androidx.media3.common.MediaItem target;
        final long[] position = {72_000};
        final Deck deck;
        final Player main;
        final String id;
        Object coroutine;

        Upgrade(String id, boolean smooth) throws Exception {
            this(id, smooth, new FakeService());
        }

        Upgrade(String id, boolean smooth, FakeService service) throws Exception {
            this.id = id;
            this.service = service;
            service.swappingMediaId = id;
            service.swapCutAt = 5L;
            shelf.put(id, "youtube");
            target = item(id);
            deck = new Deck(item(id), position, calls);
            main = deck.player;
            SmoothUpgrade.stats =
                    new SmoothUpgrade.Stats() {
                        public void stream(
                                String mediaId,
                                com.music.bitchord.data.sources.StreamFormat format,
                                String source) {
                            recorded.add("stream:" + mediaId + ":" + source);
                        }

                        public void source(String mediaId, String name) {
                            recorded.add("source:" + mediaId + ":" + name);
                        }
                    };
            SmoothUpgrade.launcher =
                    (owner, player, index, item) -> {
                        coroutine = new SwapCoroutine(owner, player, item);
                        SmoothUpgrade.bindSwap(coroutine);
                        coroutines.put(owner, coroutine);
                        return smooth;
                    };
        }

        /** BitChord's commit: the item swap, then its statistics, then the fallback it drops. */
        void commit() {
            service.swappingMediaId = id;
            SmoothUpgrade.captureOwner(service);
            SmoothUpgrade.replace(null, main, 0, target);
            SmoothUpgrade.stream(id, null, "Telegram");
            SmoothUpgrade.source(id, "Telegram");
            SmoothUpgrade.seekTo(main, 0, position[0]);
            SmoothUpgrade.prepare(main);
            SmoothUpgrade.shelved(shelf, id);
        }

        /** The standby player, which BitChord's swap has just made the one that plays. */
        Player spare(androidx.media3.common.MediaItem item) {
            return clockPlayer(item, new long[] {70_000}, Player.STATE_READY);
        }

        /** BitChord's swap adopts the standby player: the new copy is audible. */
        void adopt() {
            adopted(service, main, spare(target));
        }

        boolean untouched() {
            return recorded.isEmpty() && shelf.containsKey(id);
        }

        boolean committed() {
            return recorded.equals(
                            List.of("stream:" + id + ":Telegram", "source:" + id + ":Telegram"))
                    && !shelf.containsKey(id);
        }

        boolean released() {
            return service.swappingMediaId == null && service.swapCutAt == null;
        }
    }

    static Object settling() throws Exception {
        java.lang.reflect.Field field = SmoothUpgrade.class.getDeclaredField("settling");
        field.setAccessible(true);
        return field.get(null);
    }

    static boolean timedOut(Player incoming) throws Exception {
        Object s = settling();
        java.lang.reflect.Field began = s.getClass().getDeclaredField("began");
        began.setAccessible(true);
        began.setLong(s, System.nanoTime() / 1_000_000 - SmoothUpgrade.SETTLE_MS - 1);
        try {
            SmoothUpgrade.settled(incoming);
            return false;
        } catch (java.util.concurrent.CancellationException expected) {
            return true;
        }
    }

    /** What BitChord's version-check coroutine is: a block that carries the song it checks. */
    public static final class Watch {
        public final String $mediaId;

        Watch(String mediaId) {
            this.$mediaId = mediaId;
        }
    }

    static boolean refused(Player spare) {
        try {
            SmoothUpgrade.settled(spare);
            return false;
        } catch (java.util.concurrent.CancellationException expected) {
            return true;
        }
    }

    static void asynchronousUpgrades() throws Exception {
        SmoothUpgrade.enabled = true;
        SmoothUpgrade.playing = () -> null;
        SmoothUpgrade.measurer = (player, item, then) -> VersionAlign.CANT;
        clock = new Clock();
        SmoothUpgrade.scheduler = clock;
        logged = new ArrayList<>();
        SmoothUpgrade.log = logged::add;
        SmoothUpgrade.rendition = item -> null;
        List<String> spareCalls = new ArrayList<>();

        // The swap is in flight: nothing BitChord records about the song may change yet.
        Upgrade run = new Upgrade("song", true);
        run.commit();
        check(run.calls.isEmpty(), "A handed-over upgrade leaves the playing item alone");
        check(run.untouched(), "Statistics and the fallback wait for the copy to be heard");
        adopted(new FakeService(), run.main, run.spare(run.target));
        check(run.untouched(), "Another service's swap is not this upgrade");
        adopted(run.service, run.spare(run.target), run.spare(run.target));
        check(run.untouched(), "A swap that replaced another player is not this upgrade");
        adopted(run.service, run.spare(run.target), run.main);
        check(run.untouched(), "The player that was replaced is not the one adopted");
        adopted(run.service, run.main, run.main);
        check(run.untouched(), "A player cannot be adopted over itself");
        adopted(run.service, run.main, run.spare(item("song")));
        check(run.untouched(), "Another item of the song is not the upgrade");
        check(
                PlaybackPriority.upgradeActive(),
                "Alignment retains priority after audition has ended");
        adopted(run.service, run.main, run.spare(item("other")));
        check(run.untouched(), "Another song's adoption is not the upgrade");
        run.adopt();
        check(run.committed(), "Adoption commits the statistics, in order: " + run.recorded);
        check(
                !PlaybackPriority.upgradeActive(),
                "Audible adoption releases the handover reservation");
        run.recorded.clear();
        run.adopt();
        check(run.recorded.isEmpty(), "An upgrade commits once");
        check(clock.tasks.isEmpty(), "A heard upgrade leaves no deadline behind");

        // The host's own direct swap loads the copy; it counts once the player has it ready.
        run = new Upgrade("song", true);
        run.commit();
        SmoothUpgrade.captureSwap(run.coroutine);
        SmoothUpgrade.replaced(run.main, 0, run.target);
        check(run.calls.equals(List.of("replaceMediaItem")), "The direct swap still happens");
        check(run.untouched(), "A loaded copy is not yet a heard one");
        run.deck.ready();
        check(run.committed(), "Ready playback of the copy commits");
        check(run.deck.listeners.isEmpty() && clock.tasks.isEmpty(), "And nothing is left over");

        // BitChord's original swap: replace, seek and prepare; still only heard when ready.
        SmoothUpgrade.measurer = (player, item, then) -> VersionAlign.CANT;
        run = new Upgrade("song", false);
        run.commit();
        check(
                run.calls.equals(List.of("replaceMediaItem", "seek:72000", "prepare")),
                "The original swap is used: " + run.calls);
        check(run.untouched(), "A prepared copy is not recorded before it plays");
        run.deck.ready();
        check(run.committed(), "It is recorded once it plays: " + run.recorded);
        run.deck.ready();
        check(run.recorded.size() == 2, "Once");

        // A listener is not fooled by a copy that is ready but silenced.
        run = new Upgrade("song", false);
        run.commit();
        run.deck.state = Player.STATE_READY;
        run.deck.playing = false;
        new ArrayList<>(run.deck.listeners)
                .forEach(each -> each.onPlaybackStateChanged(Player.STATE_READY));
        check(run.untouched(), "A suppressed copy is not heard");
        run.deck.playing = true;
        new ArrayList<>(run.deck.listeners).forEach(each -> each.onIsPlayingChanged(true));
        check(run.committed(), "It is once it plays");

        // A paused listener has the copy ready, which is all a pause can show.
        run = new Upgrade("song", false);
        run.commit();
        run.deck.playWhenReady = false;
        run.deck.ready();
        check(run.committed(), "A paused player with the copy ready has it");

        // The direct replacement fails.
        run = new Upgrade("song", false);
        run.commit();
        run.deck.fail();
        check(run.untouched() && run.released(), "A failed copy leaves the song alone");
        check(run.deck.listeners.isEmpty() && clock.tasks.isEmpty(), "A failure cleans up");
        run.deck.ready();
        check(run.untouched(), "A failed copy is never committed later");
        check(
                logged.stream().anyMatch(line -> line.contains("PlaybackException")),
                "The failure is named by class: " + logged);

        // The track changes before the copy is heard.
        run = new Upgrade("song", false);
        run.commit();
        run.deck.moveTo(item("next"));
        check(run.untouched() && run.released(), "A track change leaves the song alone");
        run.deck.ready();
        check(run.untouched(), "And the copy is not committed later");

        // It is never heard: the bounded wait ends it.
        run = new Upgrade("song", false);
        run.commit();
        clock.run();
        check(run.untouched() && run.released(), "An expired attempt leaves the song alone");
        check(run.deck.listeners.isEmpty() && clock.tasks.isEmpty(), "Expiry cleans up");
        run.deck.ready();
        check(run.untouched(), "An expired copy is never committed later");

        // The swap gives up on an unaligned copy: the old stream keeps playing, as recorded.
        run = new Upgrade("song", true);
        run.commit();
        Player spare = clockPlayer(run.target, new long[] {70_000}, Player.STATE_READY, spareCalls);
        check(timedOut(spare), "An unaligned copy is refused");
        check(run.untouched(), "A refused upgrade leaves statistics and fallback as they were");
        check(run.released(), "A refused upgrade releases the service's swap marks");
        check(clock.tasks.isEmpty(), "A refused upgrade leaves no deadline behind");
        run.adopt();
        check(run.untouched(), "A refused upgrade cannot be committed later");

        // The song changes while the swap runs.
        run = new Upgrade("song", true);
        run.commit();
        run.deck.item = item("next");
        spare = clockPlayer(run.target, new long[] {70_000}, Player.STATE_READY, spareCalls);
        check(refused(spare), "A track change cancels the swap");
        check(run.untouched() && run.released(), "A track change leaves the song's state alone");

        // BitChord's swap ends without adopting: stopped, or failed, or cancelled from outside.
        run = new Upgrade("song", true);
        run.commit();
        abandoned(new FakeService());
        run.adopt();
        check(run.committed(), "Another service's abort does not end this upgrade");
        run = new Upgrade("song", true);
        run.commit();
        abandoned(run.service);
        check(run.untouched() && run.released(), "A stopped swap leaves the song alone");
        check(clock.tasks.isEmpty(), "A stopped swap leaves no deadline behind");
        run.adopt();
        check(run.untouched(), "A stopped swap is never committed later");
        run = new Upgrade("song", true);
        run.commit();
        ended(run.service, new java.util.concurrent.CancellationException("job secret"));
        check(run.untouched() && run.released(), "A cancelled swap leaves the song alone");
        check(settling() == null, "A cancelled swap releases its settling state");
        check(
                logged.stream().anyMatch(line -> line.contains("CancellationException"))
                        && logged.stream().noneMatch(line -> line.contains("job secret")),
                "The cancel is named by class only: " + logged);
        run = new Upgrade("song", true);
        run.commit();
        run.adopt();
        ended(run.service, new IllegalStateException("late"));
        check(run.committed(), "A swap that ended after it was heard keeps its record");

        run = new Upgrade("queued-load", false);
        run.deck.delayBuffer = true;
        run.commit();
        run.deck.ready();
        check(run.untouched(), "The old ready state is not proof of a replacement decoder");
        run.deck.state = Player.STATE_BUFFERING;
        for (Player.Listener listener : new ArrayList<>(run.deck.listeners))
            listener.onPlaybackStateChanged(Player.STATE_BUFFERING);
        run.deck.ready();
        check(run.committed(), "A loading-to-ready cycle confirms the replacement");

        run = new Upgrade("rollback", false);
        run.position[0] = 108000;
        SmoothUpgrade.restoreCopy(run.main, 0, item("rollback"));
        SmoothUpgrade.restoreSeek(run.main, 0, 72000);
        check(run.position[0] == 108000, "Rollback uses the current position, not an old snapshot");

        Upgrade oldAttempt = new Upgrade("same", true);
        oldAttempt.commit();
        Upgrade newAttempt = new Upgrade("same", true, oldAttempt.service);
        newAttempt.commit();
        SmoothUpgrade.captureSwap(oldAttempt.coroutine);
        SmoothUpgrade.ended(oldAttempt.service, new java.util.concurrent.CancellationException());
        check(!newAttempt.released(), "Old cancellation leaves the new attempt's marks alone");
        SmoothUpgrade.captureSwap(oldAttempt.coroutine);
        SmoothUpgrade.adopted(
                oldAttempt.service, newAttempt.main, newAttempt.spare(newAttempt.target));
        check(newAttempt.untouched(), "Old adoption cannot commit the newer attempt");
        newAttempt.adopt();
        check(newAttempt.committed(), "The newer attempt can still finish after old callbacks");

        // A swap that never reports at all is ended by the bounded wait.
        run = new Upgrade("song", true);
        run.commit();
        List<String> expiredDeck = new ArrayList<>();
        run.service.spare =
                clockPlayer(run.target, new long[] {70000}, Player.STATE_READY, expiredDeck);
        clock.run();
        check(
                expiredDeck.contains("pause") && expiredDeck.contains("clearMediaItems"),
                "Expiry releases the owned standby's playback buffers");
        check(run.untouched() && run.released(), "A silent swap expires");
        check(settling() == null, "An expired swap releases its settling state");
        check(run.service.versionSwapJob.cancellations == 1, "Expiry cancels the owned host job");
        run.adopt();
        check(run.untouched(), "An expired swap cannot be committed later");

        run = new Upgrade("song", true);
        run.commit();
        run.service.versionSwapGeneration++;
        FakeJob laterJob = new FakeJob();
        run.service.versionSwapJob = laterJob;
        clock.run();
        check(
                laterJob.cancellations == 0 && !run.released(),
                "Expiry cannot cancel or clear a newer native swap generation");

        // A newer upgrade supersedes one still in flight.
        run = new Upgrade("first", true);
        run.commit();
        Upgrade next = new Upgrade("second", true);
        next.commit();
        check(run.untouched(), "The superseded upgrade's state stays untouched");
        run.adopt();
        check(run.untouched(), "A superseded upgrade cannot be committed later");
        next.adopt();
        check(next.committed(), "The newer upgrade commits");

        // The same song upgraded again: the old attempt's callbacks are not the new one's.
        List<VersionAlign.Measured> callbacks = new ArrayList<>();
        int[] asked = {0};
        SmoothUpgrade.measurer =
                (player, item, then) -> {
                    callbacks.add(then);
                    asked[0]++;
                    return VersionAlign.MEASURING;
                };
        Upgrade first = new Upgrade("song", true);
        first.commit();
        Upgrade second = new Upgrade("song", true, first.service);
        second.commit();
        int[] started = {0};
        SmoothUpgrade.launcher =
                (owner, player, index, item) -> {
                    started[0]++;
                    Object coroutine = new SwapCoroutine(owner, player, item);
                    SmoothUpgrade.bindSwap(coroutine);
                    coroutines.put(owner, coroutine);
                    return true;
                };
        callbacks.get(0).done(new double[] {250, 1.0, 1.0, 0}, "file");
        check(started[0] == 0, "An old attempt's measurement does not start a swap");
        callbacks.get(0).done(new double[] {250, 0.1, 0.0, 0}, "file");
        check(second.untouched(), "An old attempt's refusal does not drop the newer attempt");
        check(
                second.service.swappingMediaId != null,
                "A superseded attempt does not clear the newer attempt's marks");
        callbacks.get(1).done(new double[] {250, 1.0, 1.0, 0}, "file");
        check(started[0] == 1, "The current attempt's measurement starts its swap");
        first.adopt();
        check(first.untouched() && second.untouched(), "An old attempt's adoption is refused");
        second.adopt();
        check(second.committed(), "The newer attempt is heard");
        check(first.untouched(), "And the old attempt stays as it was");

        // A superseded attempt's own pending wait does not run.
        SmoothUpgrade.measurer =
                (player, item, then) -> {
                    asked[0]++;
                    return VersionAlign.WAIT;
                };
        clock.tasks.clear();
        asked[0] = 0;
        first = new Upgrade("song", true);
        first.commit();
        check(asked[0] == 1 && clock.tasks.size() == 2, "Waiting schedules its next look");
        Runnable stale = clock.tasks.get(1);
        second = new Upgrade("song", true);
        SmoothUpgrade.measurer = (player, item, then) -> VersionAlign.CANT;
        second.commit();
        stale.run();
        check(asked[0] == 1, "A superseded attempt's wait does nothing");
        SmoothUpgrade.launcher = (owner, player, index, item) -> true;
        second.deck.ready();
        check(first.untouched(), "And leaves the old attempt as it was");

        // Measured later: a copy that doesn't match is never swapped in or recorded.
        VersionAlign.Measured[] answer = {null};
        SmoothUpgrade.measurer =
                (player, item, then) -> {
                    answer[0] = then;
                    return VersionAlign.MEASURING;
                };
        run = new Upgrade("song", true);
        run.commit();
        check(run.untouched(), "While measuring nothing changes");
        answer[0].done(new double[] {250, 0.1, 0.0, 0}, "file");
        check(run.untouched(), "A copy that doesn't match leaves the song alone");
        check(run.released(), "A declined upgrade releases the service's swap marks");

        // A matching copy is swapped in, and only then recorded.
        run = new Upgrade("song", true);
        run.commit();
        answer[0].done(new double[] {250, 1.0, 1.0, 0}, "file");
        check(run.untouched(), "An aligned copy is not recorded until it is heard");
        run.adopt();
        check(run.committed(), "Then it is");

        // The song changes while the copy is being measured.
        run = new Upgrade("song", true);
        run.commit();
        run.deck.item = item("next");
        answer[0].done(new double[] {250, 1.0, 1.0, 0}, "file");
        check(run.untouched() && run.released(), "A track change during the measurement ends it");

        // The measured offset belongs to the rendition it was measured against.
        String[] heard = {"opus"};
        SmoothUpgrade.rendition = item -> heard[0];
        run = new Upgrade("song", true);
        run.commit();
        answer[0].done(new double[] {250, 1.0, 1.0, 0}, "file");
        heard[0] = "aac";
        spareCalls.clear();
        spare = clockPlayer(run.target, new long[] {70_000}, Player.STATE_READY, spareCalls);
        check(refused(spare), "Another rendition is playing: the offset does not apply");
        check(
                spareCalls.stream().noneMatch(call -> call.startsWith("seek")),
                "An unrelated offset never moves the new copy: " + spareCalls);
        check(run.untouched() && run.released(), "And the song's state is left alone");
        SmoothUpgrade.rendition = item -> null;

        // An offset left by an attempt that ended is not lent to the next one.
        SmoothUpgrade.measurer =
                (player, item, then) -> {
                    answer[0] = then;
                    return VersionAlign.MEASURING;
                };
        SmoothUpgrade.verified = id -> null;
        run = new Upgrade("song", true);
        run.commit();
        answer[0].done(new double[] {250, 1.0, 1.0, 0}, "file");
        abandoned(run.service);
        check(!SmoothUpgrade.measureOffset(), "An ended attempt leaves no offset behind");
        SmoothUpgrade.measurer = (player, item, then) -> VersionAlign.CANT;

        // The fallback it keeps belongs to the attempt: a newer one for the song stays.
        run = new Upgrade("song", true);
        run.commit();
        run.shelf.put("song", "newer fallback");
        run.adopt();
        check(
                run.recorded.size() == 2 && "newer fallback".equals(run.shelf.get("song")),
                "A newer fallback is not removed by an older attempt");
        run = new Upgrade("song", true);
        run.shelf.clear();
        run.commit();
        run.adopt();
        check(run.recorded.size() == 2 && run.shelf.isEmpty(), "No fallback: nothing to remove");
        java.util.concurrent.ConcurrentHashMap<String, Object> other =
                new java.util.concurrent.ConcurrentHashMap<>();
        other.put("elsewhere", "kept");
        SmoothUpgrade.shelved(other, "elsewhere");
        check(other.isEmpty(), "A song with no upgrade pending loses its fallback at once");

        // The check that reverts a wrong-length copy starts when the copy is heard.
        List<String> checks = new ArrayList<>();
        SmoothUpgrade.watcher = (scope, context, start, block, mask) -> checks.add("watch:" + mask);
        run = new Upgrade("song", true);
        run.commit();
        SmoothUpgrade.watch(null, null, null, new Watch("song"), 4);
        check(checks.isEmpty(), "The duration check waits for the copy to be heard");
        run.adopt();
        check(checks.equals(List.of("watch:4")), "It starts once the copy is heard: " + checks);
        checks.clear();
        run = new Upgrade("song", true);
        run.commit();
        SmoothUpgrade.watch(null, null, null, new Watch("song"), 4);
        abandoned(run.service);
        run.adopt();
        check(checks.isEmpty(), "A copy that was never heard is never checked");
        SmoothUpgrade.watch(null, null, null, new Watch("unrelated"), 4);
        check(checks.equals(List.of("watch:4")), "Another song's check is not held back");
        checks.clear();
        SmoothUpgrade.watch(null, null, null, new Object(), 4);
        check(checks.equals(List.of("watch:4")), "A check that names no song starts at once");

        // A failure while recording is logged by class, never by message.
        run = new Upgrade("song", true);
        SmoothUpgrade.stats =
                new SmoothUpgrade.Stats() {
                    public void stream(
                            String mediaId,
                            com.music.bitchord.data.sources.StreamFormat format,
                            String source) {
                        throw new IllegalStateException("secret-token");
                    }

                    public void source(String mediaId, String name) {}
                };
        run.commit();
        run.adopt();
        check(
                logged.stream().anyMatch(line -> line.contains("IllegalStateException"))
                        && logged.stream().noneMatch(line -> line.contains("secret-token")),
                "A recording failure is named by class only: " + logged);
        SmoothUpgrade.scheduler = null;
    }

    public static void main(String[] args) throws Exception {
        PlaybackPriority.reset();
        long[] now = {1};
        PlaybackPriority.clock = () -> now[0];
        SmoothUpgrade.auditionStarted("current");
        check(PlaybackPriority.upgradeActive(), "Audition reserves playback preparation");
        check(!PlaybackPriority.analysisAllowed(), "Analysis yields while auditioning");
        SmoothUpgrade.auditionEnded("other");
        check(PlaybackPriority.upgradeActive(), "Another track cannot release this audition");
        SmoothUpgrade.auditionEnded("current");
        check(!PlaybackPriority.upgradeActive(), "Completion releases the reservation");
        now[0] += PlaybackPriority.SETTLE_NANOS + 1;
        check(PlaybackPriority.backgroundAllowed(), "Background work resumes after settling");
        SmoothUpgrade.auditionStarted("expires");
        now[0] += PlaybackPriority.UPGRADE_TTL_NANOS + PlaybackPriority.SETTLE_NANOS + 1;
        check(
                !PlaybackPriority.upgradeActive(),
                "An abandoned audition cannot starve background work");
        SmoothUpgrade.auditionEnded("expires");
        PlaybackPriority.reset();
        Object service = new Object();
        SmoothUpgrade.captureOwner(service);
        java.util.concurrent.atomic.AtomicReference<Object> otherOwner =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread unrelated = new Thread(() -> otherOwner.set(SmoothUpgrade.takeOwner()));
        unrelated.start();
        unrelated.join();
        check(otherOwner.get() == null, "Another thread cannot consume the commit's service");
        check(
                SmoothUpgrade.takeOwner() == service,
                "The captured service survives host register reuse");
        check(SmoothUpgrade.takeOwner() == null, "A commit consumes its service once");
        Object replacement = new Object();
        SmoothUpgrade.captureOwner(service);
        SmoothUpgrade.captureOwner(replacement);
        check(
                SmoothUpgrade.takeOwner() == replacement,
                "A new commit replaces an abandoned capture");
        List<String> calls = new ArrayList<>();
        Player player =
                (Player)
                        Proxy.newProxyInstance(
                                Player.class.getClassLoader(),
                                new Class<?>[] {Player.class},
                                (proxy, method, arguments) -> {
                                    switch (method.getName()) {
                                        case "hashCode":
                                            return System.identityHashCode(proxy);
                                        case "equals":
                                            return proxy == arguments[0];
                                        case "getPlaybackState":
                                            return Player.STATE_READY;
                                        case "isPlaying":
                                            return false;
                                        case "addListener":
                                        case "removeListener":
                                            return null;
                                        default:
                                            // Reading the player changes nothing.
                                            if (!method.getName().startsWith("get"))
                                                calls.add(method.getName());
                                            return null;
                                    }
                                });
        // The host test build links against the compile-only service, whose swap always
        // declines, so this exercises the fallback: BitChord's original three calls, in order.
        SmoothUpgrade.enabled = true;
        SmoothUpgrade.captureOwner(service);
        SmoothUpgrade.replace(new Object(), player, 0, null);
        check(
                SmoothUpgrade.takeOwner() == null,
                "Replacement consumes its own capture on fallback");
        SmoothUpgrade.seekTo(player, 0, 1234);
        SmoothUpgrade.prepare(player);
        check(
                calls.equals(List.of("replaceMediaItem", "seekTo", "prepare")),
                "Declined swap falls back to the original calls: " + calls);
        check(
                SmoothUpgrade.settled(player) == Player.STATE_READY,
                "No swap of ours waiting: BitChord's own swaps see the real state");
        calls.clear();
        SmoothUpgrade.enabled = false;
        SmoothUpgrade.replace(new Object(), player, 0, null);
        SmoothUpgrade.seekTo(player, 0, 1234);
        SmoothUpgrade.prepare(player);
        check(
                calls.equals(List.of("replaceMediaItem", "seekTo", "prepare")),
                "Switched off, the original calls run");

        // The recording check's offset reaches BitChord's alignment step for this swap only.
        SmoothUpgrade.enabled = true;
        check(!SmoothUpgrade.measureOffset(), "Nothing measured: BitChord's swap isn't asked");
        check(SmoothUpgrade.alignedOffset() == null, "And there's nothing to hand over");
        List<String> asked = new ArrayList<>();
        SmoothUpgrade.verified =
                id -> {
                    asked.add(id);
                    return 250L;
                };
        SmoothUpgrade.offerVerified("song");
        check(asked.equals(List.of("song")), "Asks for this song's offset: " + asked);
        check(
                SmoothUpgrade.alignedOffset() == null,
                "Another swap that didn't ask doesn't take this one's offset");
        SmoothUpgrade.offerVerified("song");
        check(SmoothUpgrade.measureOffset(), "This swap runs the alignment step");
        Long offset = SmoothUpgrade.alignedOffset();
        check(offset != null && offset == 250, "And starts from the verified offset: " + offset);
        check(SmoothUpgrade.alignedOffset() == null, "Once");
        check(!SmoothUpgrade.measureOffset(), "And the next swap isn't asked");
        SmoothUpgrade.verified = id -> "first".equals(id) ? 250L : 90L;
        SmoothUpgrade.offerVerified("first");
        check(SmoothUpgrade.measureOffset(), "The first upgrade arms its offset");
        SmoothUpgrade.offerVerified("second");
        check(SmoothUpgrade.measureOffset(), "The second upgrade arms its own offset");
        check(
                SmoothUpgrade.alignedOffset() == 90L,
                "An abandoned upgrade cannot lend another song its offset");
        SmoothUpgrade.verified = id -> null;
        SmoothUpgrade.offerVerified("song");
        check(!SmoothUpgrade.measureOffset(), "No verified offset: as before, none is applied");
        SmoothUpgrade.verified = id -> 250L;
        calls.clear();
        SmoothUpgrade.replace(new Object(), player, 0, null);
        SmoothUpgrade.seekTo(player, 0, 1234);
        SmoothUpgrade.prepare(player);
        check(
                calls.equals(List.of("replaceMediaItem", "seekTo", "prepare")),
                "A declined swap still falls back: " + calls);
        check(!SmoothUpgrade.measureOffset(), "And leaves no offset behind for a later swap");

        // An audition starts where the song is by then, not where it was when the upgrade began.
        androidx.media3.common.MediaItem song = item("song"), other = item("other");
        long[] position = {72_000};
        androidx.media3.common.MediaItem[] playing = {song};
        Player live =
                (Player)
                        Proxy.newProxyInstance(
                                Player.class.getClassLoader(),
                                new Class<?>[] {Player.class},
                                (proxy, method, arguments) -> {
                                    switch (method.getName()) {
                                        case "getCurrentMediaItem":
                                            return playing[0];
                                        case "getCurrentPosition":
                                            return position[0];
                                        default:
                                            return null;
                                    }
                                });
        SmoothUpgrade.playing = () -> live;
        check(SmoothUpgrade.auditionPosition(song, 40_000) == 72_000, "Starts where the song is");
        position[0] = 30_000;
        check(SmoothUpgrade.auditionPosition(song, 40_000) == 40_000, "Never further back");
        position[0] = 72_000;
        playing[0] = other;
        check(SmoothUpgrade.auditionPosition(song, 40_000) == 40_000, "Not another song's place");
        playing[0] = null;
        check(SmoothUpgrade.auditionPosition(song, 40_000) == 40_000, "Nothing playing");
        check(SmoothUpgrade.auditionPosition(null, 40_000) == 40_000, "No song named");
        SmoothUpgrade.playing = () -> null;
        check(SmoothUpgrade.auditionPosition(song, 40_000) == 40_000, "No player");
        SmoothUpgrade.playing =
                () -> {
                    throw new IllegalStateException("Wrong thread");
                };
        check(SmoothUpgrade.auditionPosition(song, 40_000) == 40_000, "A failure changes nothing");
        long[] oldPosition = {72_000}, newPosition = {70_000};
        Player outgoing = clockPlayer(song, oldPosition, Player.STATE_READY);
        Player incoming = clockPlayer(song, newPosition, Player.STATE_READY);
        SmoothUpgrade.Settling late =
                new SmoothUpgrade.Settling(
                        SmoothUpgrade.begin(null, outgoing, song), outgoing, 0, song, 0.0);
        late.began = System.nanoTime() / 1_000_000 - SmoothUpgrade.SETTLE_MS - 1;
        java.lang.reflect.Field pending = SmoothUpgrade.class.getDeclaredField("settling");
        pending.setAccessible(true);
        pending.set(null, late);
        boolean cancelled = false;
        try {
            SmoothUpgrade.settled(incoming);
        } catch (java.util.concurrent.CancellationException expected) {
            cancelled = true;
        }
        check(cancelled, "An unaligned copy must not become audible when settling times out");
        check(pending.get(null) == null, "A refused swap releases its settling state");
        List<String> outgoingCalls = new ArrayList<>(), incomingCalls = new ArrayList<>();
        outgoing = clockPlayer(song, oldPosition, Player.STATE_READY, outgoingCalls);
        incoming = clockPlayer(song, newPosition, Player.STATE_BUFFERING, incomingCalls);
        late =
                new SmoothUpgrade.Settling(
                        SmoothUpgrade.begin(null, outgoing, song), outgoing, 0, song, 0.0);
        late.began = System.nanoTime() / 1_000_000 - SmoothUpgrade.SETTLE_MS - 1;
        java.util.concurrent.CompletableFuture<double[]> work =
                new java.util.concurrent.CompletableFuture<>();
        late.measuring = work;
        pending.set(null, late);
        cancelled = false;
        try {
            SmoothUpgrade.settled(incoming);
        } catch (java.util.concurrent.CancellationException expected) {
            cancelled = true;
        }
        check(cancelled, "A buffering copy also observes the alignment deadline");
        check(work.isCancelled(), "Refusing a swap cancels its measurement");
        check(
                incomingCalls.equals(List.of("stop", "volume:1.0")),
                "The unused incoming player is stopped");
        check(
                outgoingCalls.isEmpty() && oldPosition[0] == 72_000,
                "The heard audio is not sought or stopped");

        incomingCalls.clear();
        incoming = clockPlayer(song, newPosition, Player.STATE_READY, incomingCalls);
        late =
                new SmoothUpgrade.Settling(
                        SmoothUpgrade.begin(null, outgoing, song), outgoing, 0, song, 250.0);
        pending.set(null, late);
        check(
                SmoothUpgrade.settled(incoming) == Player.STATE_BUFFERING,
                "A copy outside the search window is held silent");
        check(
                newPosition[0] == 72_250 && incomingCalls.equals(List.of("seek:72250")),
                "Resync uses the live position plus the verified recording offset");
        // seekTo can return while the sink still contains pre-seek audio. Repeated polling
        // must not spend both retries on that same old position/window.
        newPosition[0] = 70_000;
        check(
                SmoothUpgrade.settled(incoming) == Player.STATE_BUFFERING && late.seeks == 1,
                "An asynchronous seek is allowed to settle before another correction");
        newPosition[0] = 72_250;
        late.began = System.nanoTime() / 1_000_000 - SmoothUpgrade.SETTLE_MS - 1;
        check(
                SmoothUpgrade.settled(incoming) == Player.STATE_READY,
                "Verified clocks can agree when PCM comparison is unavailable");
        check(pending.get(null) == null, "A confirmed clock match completes settling");
        SmoothUpgrade.fadeVolume(incoming, .5f);
        check(incomingCalls.contains("volume:0.5"), "A confirmed copy may fade in");

        incomingCalls.clear();
        late =
                new SmoothUpgrade.Settling(
                        SmoothUpgrade.begin(null, outgoing, song), outgoing, 0, song, null);
        pending.set(null, late);
        cancelled = false;
        try {
            SmoothUpgrade.fadeVolume(incoming, .5f);
        } catch (java.util.concurrent.CancellationException expected) {
            cancelled = true;
        }
        check(
                cancelled && !incomingCalls.contains("volume:0.5"),
                "Skipping the host ready wait cannot expose an unverified copy");

        incomingCalls.clear();
        late =
                new SmoothUpgrade.Settling(
                        SmoothUpgrade.begin(null, outgoing, song), outgoing, 0, song, null);
        late.began = System.nanoTime() / 1_000_000;
        late.measuring =
                java.util.concurrent.CompletableFuture.completedFuture(new double[] {0, 1, 1});
        pending.set(null, late);
        check(
                SmoothUpgrade.settled(incoming) == Player.STATE_READY,
                "A matching waveform completes settling without a stored offset");
        SmoothUpgrade.fadeVolume(incoming, .5f);
        check(
                incomingCalls.equals(List.of("volume:0.5")),
                "Waveform alignment does not seek or alter speed and pitch");

        late =
                new SmoothUpgrade.Settling(
                        SmoothUpgrade.begin(null, outgoing, other), outgoing, 0, other, null);
        pending.set(null, late);
        incomingCalls.clear();
        SmoothUpgrade.fadeVolume(incoming, .25f);
        check(
                incomingCalls.equals(List.of("volume:0.25")),
                "An unrelated version swap is not intercepted");
        check(
                SmoothUpgrade.settled(incoming) == Player.STATE_READY && pending.get(null) == null,
                "An unrelated pair clears the old alignment state");
        asynchronousUpgrades();
        System.out.println("PASS: " + checks + " smooth-upgrade fallback checks.");
    }
}

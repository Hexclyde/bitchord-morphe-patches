package app.bitchord.telegram;

import androidx.media3.common.Player;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

public final class SmoothUpgradeTest {
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
                                case "getPlaybackParameters":
                                    return new androidx.media3.common.PlaybackParameters(1f, 1f);
                                case "hashCode":
                                    return System.identityHashCode(proxy);
                                case "equals":
                                    return proxy == arguments[0];
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

    public static void main(String[] args) throws Exception {
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
        SmoothUpgrade.Settling late = new SmoothUpgrade.Settling(null, outgoing, 0, song, 0.0);
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
        late = new SmoothUpgrade.Settling(null, outgoing, 0, song, 0.0);
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
        late = new SmoothUpgrade.Settling(null, outgoing, 0, song, 250.0);
        pending.set(null, late);
        check(
                SmoothUpgrade.settled(incoming) == Player.STATE_BUFFERING,
                "A copy outside the search window is held silent");
        check(
                newPosition[0] == 72_250 && incomingCalls.equals(List.of("seek:72250")),
                "Resync uses the live position plus the verified recording offset");
        late.began = System.nanoTime() / 1_000_000 - SmoothUpgrade.SETTLE_MS - 1;
        check(
                SmoothUpgrade.settled(incoming) == Player.STATE_READY,
                "Verified clocks can agree when PCM comparison is unavailable");
        check(pending.get(null) == null, "A confirmed clock match completes settling");
        SmoothUpgrade.fadeVolume(incoming, .5f);
        check(incomingCalls.contains("volume:0.5"), "A confirmed copy may fade in");

        incomingCalls.clear();
        late = new SmoothUpgrade.Settling(null, outgoing, 0, song, null);
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
        late = new SmoothUpgrade.Settling(null, outgoing, 0, song, null);
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

        late = new SmoothUpgrade.Settling(null, outgoing, 0, other, null);
        pending.set(null, late);
        incomingCalls.clear();
        SmoothUpgrade.fadeVolume(incoming, .25f);
        check(
                incomingCalls.equals(List.of("volume:0.25")),
                "An unrelated version swap is not intercepted");
        check(
                SmoothUpgrade.settled(incoming) == Player.STATE_READY && pending.get(null) == null,
                "An unrelated pair clears the old alignment state");
        System.out.println("PASS: " + checks + " smooth-upgrade fallback checks.");
    }
}

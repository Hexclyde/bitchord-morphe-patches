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
        System.out.println("PASS: " + checks + " smooth-upgrade fallback checks.");
    }
}

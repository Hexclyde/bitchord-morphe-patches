package app.bitchord.telegram;

import android.media.AudioManager;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Play alongside other apps, Steady playback and Smart downloads. */
public final class TravelFeaturesTest {
    static int checks;

    static void check(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }

    static String bucket(String month, String tracks) {
        return "{\"version\":1,\"month\":\"" + month + "\",\"tracks\":[" + tracks + "]}";
    }

    static String track(String id, int plays, long last) {
        return "{\"id\":\""
                + id
                + "\",\"title\":\"T "
                + id
                + "\",\"artist\":\"A\",\"album\":\"B\","
                + "\"albumId\":\"MPRE1\",\"artistId\":\"UC1\",\"art\":\"https://x/y.jpg\","
                + "\"ms\":1000,\"plays\":"
                + plays
                + ",\"last\":"
                + last
                + ",\"days\":{}}";
    }

    /**
     * One load as Media3's Loader handles it: the error count rises before the retry policy hears
     * of it, a load that made progress starts counting again from 1, and the player's check throws
     * the last error once the count is above the allowance.
     */
    static final class Load {
        int count;

        void error() {
            count++;
            SteadyPlayback.loadError(count);
        }

        void errorAfterProgress() {
            error();
            count = 1;
        }

        boolean stops() {
            return count > SteadyPlayback.minRetries(3);
        }
    }

    /** Thread state is what's under test, so each scenario gets a playback thread of its own. */
    static void onPlaybackThread(Runnable scenario) throws Exception {
        Throwable[] failed = new Throwable[1];
        Thread thread = new Thread(scenario);
        thread.setUncaughtExceptionHandler((t, e) -> failed[0] = e);
        thread.start();
        thread.join();
        if (failed[0] != null) throw new AssertionError(failed[0]);
    }

    static void retries() throws Exception {
        SteadyPlayback.enabled = true;
        onPlaybackThread(
                () -> {
                    Load load = new Load();
                    SteadyPlayback.observed(false, false, false);
                    for (int i = 0; i < 20; i++) load.error();
                    check(!load.stops(), "waits while there's no signal");
                    SteadyPlayback.observed(true, true, true);
                    check(!load.stops(), "the signal coming back doesn't end the song");
                    load.error();
                    load.error();
                    load.error();
                    check(!load.stops(), "BitChord's three retries still apply after the outage");
                    load.error();
                    check(load.stops(), "a server that keeps failing still stops it");
                });
        onPlaybackThread(
                () -> {
                    Load load = new Load();
                    SteadyPlayback.observed(false, false, false);
                    for (int i = 0; i < 20; i++) load.error();
                    SteadyPlayback.observed(true, true, true);
                    Load next = new Load();
                    next.error();
                    check(
                            SteadyPlayback.minRetries(3) == 3,
                            "a new load proves the outage's errors are over");
                    next.error();
                    next.error();
                    next.error();
                    check(next.stops(), "and it stops after BitChord's usual number");
                });
        onPlaybackThread(
                () -> {
                    Load load = new Load();
                    SteadyPlayback.observed(false, false, false);
                    for (int i = 0; i < 20; i++) load.error();
                    SteadyPlayback.observed(true, true, true);
                    load.errorAfterProgress();
                    check(load.count == 1 && !load.stops(), "the load recovered");
                    load.error();
                    check(
                            SteadyPlayback.minRetries(3) == 3,
                            "its progress reset the count, so later errors are ordinary ones");
                    load.error();
                    load.error();
                    check(load.stops(), "and they stop it as usual");
                });
        onPlaybackThread(
                () -> {
                    Load load = new Load();
                    SteadyPlayback.observed(false, false, false);
                    for (int i = 0; i < 5; i++) load.error();
                    SteadyPlayback.observed(true, false, true);
                    load.error();
                    check(!load.stops(), "a network with nothing behind it isn't back yet");
                    SteadyPlayback.observed(true, true, true);
                    check(
                            SteadyPlayback.minRetries(3) == 9,
                            "errors before validation are excused: "
                                    + SteadyPlayback.minRetries(3));
                });
        onPlaybackThread(
                () -> {
                    Load online = new Load();
                    SteadyPlayback.observed(true, true, true);
                    for (int i = 0; i < 3; i++) online.error();
                    check(!online.stops(), "an online error within the allowance");
                    online.error();
                    check(online.stops(), "online failures aren't hidden by this at all");
                });
        // One player's outage says nothing about another's load: the policy is shared.
        onPlaybackThread(
                () -> {
                    Load load = new Load();
                    SteadyPlayback.observed(false, false, false);
                    for (int i = 0; i < 20; i++) load.error();
                    SteadyPlayback.observed(true, true, true);
                    try {
                        onPlaybackThread(
                                () ->
                                        check(
                                                SteadyPlayback.minRetries(3) == 3,
                                                "another player keeps the usual allowance"));
                    } catch (Exception interrupted) {
                        throw new AssertionError(interrupted);
                    }
                    check(SteadyPlayback.minRetries(3) == 23, "this one keeps its excused errors");
                });
        onPlaybackThread(
                () -> {
                    Load load = new Load();
                    SteadyPlayback.observed(false, false, false);
                    for (int i = 0; i < 20; i++) load.error();
                    SteadyPlayback.enabled = false;
                    check(!load.stops(), "turning it off mid-outage doesn't end the song itself");
                    SteadyPlayback.observed(true, true, true);
                    check(!load.stops(), "nor does the signal returning afterwards");
                    new Load().error();
                    check(SteadyPlayback.minRetries(3) == 3, "and the next load is ordinary");
                    SteadyPlayback.enabled = true;
                });
        onPlaybackThread(
                () -> {
                    SteadyPlayback.observed(false, false, false);
                    Load load = new Load();
                    for (int i = 0; i < SteadyPlayback.OFFLINE_RETRIES; i++) load.error();
                    check(!load.stops(), "the wait is long");
                    load.error();
                    check(load.stops(), "but not without end");
                });
        SteadyPlayback.enabled = false;
        onPlaybackThread(
                () -> {
                    SteadyPlayback.observed(false, false, false);
                    Load load = new Load();
                    load.error();
                    check(SteadyPlayback.minRetries(3) == 3, "off changes nothing, even offline");
                });
        SteadyPlayback.observed(true, false, true);
        check(!SteadyPlayback.online(), "validated, not just connected");
        check(SteadyPlayback.link() == SteadyPlayback.Link.UNMETERED, "still Wi-Fi for read-ahead");
        SteadyPlayback.observed(true, false, false);
        check(SteadyPlayback.link() == SteadyPlayback.Link.METERED, "still mobile for read-ahead");
        SteadyPlayback.observed(false, false, false);
        check(SteadyPlayback.link() == SteadyPlayback.Link.NONE, "no network");
        SteadyPlayback.observed(true, true, false);
        check(SteadyPlayback.online(), "validated");
    }

    static final class Allocator {
        int allocatedCount, availableCount;
        int individualAllocationSize = 65_536;
        int targetBufferSize;
    }

    static final class LoadingState {
        int targetBufferBytes;

        LoadingState(int bytes) {
            targetBufferBytes = bytes;
        }
    }

    static final class Control {
        int targetBufferBytesOverwrite;
        final Allocator allocator = new Allocator();
        final Map<Object, LoadingState> loadingStates = new LinkedHashMap<>();

        Control(int target) {
            targetBufferBytesOverwrite = target;
            loadingStates.put(new Object(), new LoadingState(target));
            updateAllocator();
        }

        void updateAllocator() {
            allocator.targetBufferSize = 0;
            for (LoadingState state : loadingStates.values())
                allocator.targetBufferSize += state.targetBufferBytes;
        }
    }

    static final class Parameters {
        long bufferedDurationUs = 3_000_000;
    }

    static boolean admitted(Control control, Parameters parameters) throws Exception {
        try {
            return (Boolean)
                    SteadyPlayback.class
                            .getMethod("allowLoading", Object.class, Object.class)
                            .invoke(null, control, parameters);
        } catch (NoSuchMethodException beforeGate) {
            SteadyPlayback.tick(control);
            return true;
        }
    }

    static long allocated(Control... controls) {
        long total = 0;
        for (Control control : controls)
            total +=
                    (long) (control.allocator.allocatedCount + control.allocator.availableCount)
                            * control.allocator.individualAllocationSize;
        return total;
    }

    static void clearControls() throws Exception {
        for (String name : new String[] {"controls", "observedControls"})
            try {
                java.lang.reflect.Field field = SteadyPlayback.class.getDeclaredField(name);
                field.setAccessible(true);
                ((Map<?, ?>) field.get(null)).clear();
            } catch (NoSuchFieldException beforeGate) {
            }
    }

    static void bufferMemory() throws Exception {
        int mb = 1 << 20;
        boolean enabled = SteadyPlayback.enabled;
        int wifi = SteadyPlayback.wifiMb, mobile = SteadyPlayback.mobileMb;
        java.lang.reflect.Field heap = null;
        Object previousHeap = null;
        try {
            try {
                heap = SteadyPlayback.class.getDeclaredField("heapBytes");
                heap.setAccessible(true);
                previousHeap = heap.get(null);
                heap.set(null, (LongSupplier) () -> 192L << 20);
            } catch (NoSuchFieldException beforeGate) {
            }
            clearControls();
            SteadyPlayback.setEnabled(true);
            SteadyPlayback.setWifiMb(64);
            SteadyPlayback.observed(true, true, true);
            Control main = new Control(8 * mb), spare = new Control(8 * mb);
            Control canvas = new Control(131_203_072);
            canvas.targetBufferBytesOverwrite = -1;
            int nativeCanvasTarget = canvas.targetBufferBytesOverwrite;
            SteadyPlayback.loadControl(main);
            SteadyPlayback.loadControl(spare);
            Parameters ready = new Parameters();
            admitted(canvas, ready);
            Control[] players = {main, spare, canvas};
            for (int pass = 0; pass < 5000; pass++) {
                boolean grew = false;
                for (Control control : players)
                    if (admitted(control, ready)
                            && (long) control.allocator.allocatedCount * 65_536
                                    < control.allocator.targetBufferSize) {
                        control.allocator.allocatedCount++;
                        grew = true;
                    }
                if (!grew) break;
            }
            check(
                    allocated(players) <= 96L * mb,
                    "A 192 MiB app must leave half its heap for application work: "
                            + allocated(players));
            check(
                    (long) canvas.allocator.allocatedCount * 65_536 <= 16L * mb,
                    "Animated artwork must not fill the native 125 MiB video allowance");
            check(
                    canvas.targetBufferBytesOverwrite == nativeCanvasTarget,
                    "The auxiliary player's native target is retained");
            check(
                    main.targetBufferBytesOverwrite >= 8 * mb
                            && main.targetBufferBytesOverwrite <= 40 * mb,
                    "Both primary players retain at least the native floor within the budget");
            check(SteadyPlayback.wifiMb == 64, "Memory pressure does not change the saved choice");
            main.loadingStates.put(new Object(), new LoadingState(8 * mb));
            SteadyPlayback.tick(main);
            check(
                    main.allocator.targetBufferSize <= 40 * mb,
                    "Additional native loading states share the same primary allowance");
            int used = canvas.allocator.allocatedCount;
            canvas.allocator.allocatedCount = 0;
            canvas.allocator.availableCount = used;
            check(
                    admitted(canvas, ready),
                    "The ready artwork player can refill from its own pool without new heap");
            canvas.allocator.availableCount = 0;
            main.allocator.availableCount = used;
            check(
                    !admitted(canvas, ready),
                    "Other players' retained pools prevent a new auxiliary allocation");
            main.allocator.availableCount = 0;
            canvas.allocator.allocatedCount = used;
            int primaryUsed = main.allocator.allocatedCount;
            main.allocator.allocatedCount = 0;
            main.allocator.availableCount = primaryUsed;
            check(
                    admitted(main, ready),
                    "The primary can maintain read-ahead by reusing its existing pool");
            main.allocator.availableCount = 0;
            main.allocator.allocatedCount = primaryUsed;
            ready.bufferedDurationUs = 1_500_000;
            check(
                    admitted(canvas, ready),
                    "Initial audio/video readiness is allowed below two seconds");
            ready.bufferedDurationUs = 2_000_000;
            check(
                    !admitted(canvas, ready),
                    "Once ready, the artwork buffer stops at its allowance");
            SteadyPlayback.setEnabled(false);
            check(admitted(canvas, ready), "Off leaves the auxiliary loading decision unchanged");
            SteadyPlayback.tick(main);
            SteadyPlayback.tick(spare);
            check(
                    main.targetBufferBytesOverwrite == 8 * mb
                            && spare.targetBufferBytesOverwrite == 8 * mb,
                    "Off restores each primary's native target");
            check(
                    canvas.targetBufferBytesOverwrite == nativeCanvasTarget,
                    "Off preserves the original auxiliary target");
        } finally {
            clearControls();
            if (heap != null) heap.set(null, previousHeap);
            SteadyPlayback.setWifiMb(wifi);
            SteadyPlayback.setMobileMb(mobile);
            SteadyPlayback.setEnabled(enabled);
        }
    }

    public static void main(String[] args) throws Exception {
        // Play alongside other apps.
        check(AudioFocus.takesFocus(true, false), "BitChord's focus when off");
        check(!AudioFocus.takesFocus(true, true), "no focus when playing alongside");
        check(!AudioFocus.takesFocus(false, false), "a spare player never takes focus");
        check(AudioFocus.inCall(AudioManager.MODE_IN_CALL), "phone call");
        check(AudioFocus.inCall(AudioManager.MODE_IN_COMMUNICATION), "internet call");
        check(AudioFocus.inCall(AudioManager.MODE_RINGTONE), "ringing");
        check(!AudioFocus.inCall(AudioManager.MODE_NORMAL), "no call");
        check(AudioFocus.pausesForCall(true, 2), "buffering starts by itself, so it's paused");
        check(AudioFocus.pausesForCall(true, 3), "playing is paused");
        check(!AudioFocus.pausesForCall(false, 3), "already paused has nothing to resume");
        check(!AudioFocus.pausesForCall(true, 4), "a finished queue has nothing to resume");
        check(AudioFocus.canResume(false, 3) && AudioFocus.canResume(false, 2), "still prepared");
        check(!AudioFocus.canResume(true, 3), "already asked to play");
        check(
                !AudioFocus.canResume(false, 1) && !AudioFocus.canResume(false, 4),
                "nothing to play");
        check(AudioFocus.headphones(8) && AudioFocus.headphones(4), "unplugging counts");
        check(!AudioFocus.headphones(7), "the call link closes after every call on a headset");
        check(!AudioFocus.headphones(2), "the phone's own speaker isn't headphones");

        // Steady playback.
        int mb = 1 << 20;
        check(
                SteadyPlayback.targetBytes(false, SteadyPlayback.Link.UNMETERED, 64, 32) == 8 * mb,
                "off keeps BitChord's 8 MB");
        check(
                SteadyPlayback.targetBytes(true, SteadyPlayback.Link.UNMETERED, 64, 32) == 64 * mb,
                "Wi-Fi limit");
        check(
                SteadyPlayback.targetBytes(true, SteadyPlayback.Link.METERED, 64, 32) == 32 * mb,
                "mobile limit");
        check(
                SteadyPlayback.targetBytes(true, SteadyPlayback.Link.NONE, 64, 32) == 64 * mb,
                "offline keeps what's buffered");
        check(
                SteadyPlayback.targetBytes(true, SteadyPlayback.Link.METERED, 64, 4) == 8 * mb,
                "never below BitChord's own");
        check(SteadyPlayback.nearest(50, SteadyPlayback.WIFI_CHOICES) == 64, "nearest choice");
        check(SteadyPlayback.nearest(1, SteadyPlayback.MOBILE_CHOICES) == 8, "smallest choice");
        check(SteadyPlayback.minRetries(3) == 3, "BitChord's retries while off or online");
        retries();
        bufferMemory();

        // Smart downloads.
        File dir = Files.createTempDirectory("listening").toFile();
        YearMonth now = YearMonth.of(2026, 11);
        Files.write(
                new File(dir, "2026-11.json").toPath(),
                bucket(
                                "2026-11",
                                track("aaaaaaaaaaa", 5, 300)
                                        + ","
                                        + track("bbbbbbbbbbb", 2, 200)
                                        + ","
                                        + track("src:telegram-native::1", 50, 100))
                        .getBytes(StandardCharsets.UTF_8));
        Files.write(
                new File(dir, "2026-10.json").toPath(),
                bucket("2026-10", track("bbbbbbbbbbb", 2, 150) + "," + track("ccccccccccc", 4, 50))
                        .getBytes(StandardCharsets.UTF_8));
        Files.write(
                new File(dir, "2026-06.json").toPath(),
                bucket("2026-06", track("ddddddddddd", 99, 10)).getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "broken.json").toPath(), "{".getBytes(StandardCharsets.UTF_8));
        Map<String, SmartDownloads.Track> tracks = SmartDownloads.readMonths(dir, now, 3);
        check(tracks.size() == 4, "three months, eligible source songs: " + tracks.keySet());
        check(!tracks.containsKey("ddddddddddd"), "older months left out");
        check(tracks.get("bbbbbbbbbbb").plays == 4, "plays added up across months");
        check(tracks.get("bbbbbbbbbbb").last == 200, "latest play kept");
        List<SmartDownloads.Track> top = SmartDownloads.choose(tracks, 10);
        check(top.size() == 4, "all with three plays or more");
        check(top.get(0).id.equals("src:telegram-native::1"), "most played first");
        check(top.get(2).id.equals("bbbbbbbbbbb"), "tie broken by most recent");
        check(SmartDownloads.choose(tracks, 1).size() == 1, "limited");
        check(
                tracks.get("aaaaaaaaaaa")
                        .song()
                        .getString("thumbnailUrl")
                        .equals("https://x/y.jpg"),
                "artwork carried");
        check(tracks.get("aaaaaaaaaaa").song().getString("albumName").equals("B"), "album carried");
        System.out.println(
                "PASS: " + checks + " play alongside, steady playback and smart download checks.");
    }
}

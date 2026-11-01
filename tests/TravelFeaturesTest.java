package app.bitchord.telegram;

import android.media.AudioManager;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

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
        return "{\"id\":\"" + id + "\",\"title\":\"T " + id + "\",\"artist\":\"A\",\"album\":\"B\","
                + "\"albumId\":\"MPRE1\",\"artistId\":\"UC1\",\"art\":\"https://x/y.jpg\","
                + "\"ms\":1000,\"plays\":" + plays + ",\"last\":" + last + ",\"days\":{}}";
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

        // Steady playback.
        int mb = 1 << 20;
        check(SteadyPlayback.targetBytes(false, SteadyPlayback.Link.UNMETERED, 64, 32) == 8 * mb, "off keeps BitChord's 8 MB");
        check(SteadyPlayback.targetBytes(true, SteadyPlayback.Link.UNMETERED, 64, 32) == 64 * mb, "Wi-Fi limit");
        check(SteadyPlayback.targetBytes(true, SteadyPlayback.Link.METERED, 64, 32) == 32 * mb, "mobile limit");
        check(SteadyPlayback.targetBytes(true, SteadyPlayback.Link.NONE, 64, 32) == 64 * mb, "offline keeps what's buffered");
        check(SteadyPlayback.targetBytes(true, SteadyPlayback.Link.METERED, 64, 4) == 8 * mb, "never below BitChord's own");
        check(SteadyPlayback.nearest(50, SteadyPlayback.WIFI_CHOICES) == 64, "nearest choice");
        check(SteadyPlayback.nearest(1, SteadyPlayback.MOBILE_CHOICES) == 8, "smallest choice");
        check(SteadyPlayback.minRetries(3) == 3, "BitChord's retries while off or online");

        // Smart downloads.
        File dir = Files.createTempDirectory("listening").toFile();
        YearMonth now = YearMonth.of(2026, 11);
        Files.write(new File(dir, "2026-11.json").toPath(), bucket("2026-11",
                track("aaaaaaaaaaa", 5, 300) + "," + track("bbbbbbbbbbb", 2, 200) + ","
                        + track("src:telegram-native::1", 50, 100)).getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "2026-10.json").toPath(), bucket("2026-10",
                track("bbbbbbbbbbb", 2, 150) + "," + track("ccccccccccc", 4, 50)).getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "2026-06.json").toPath(), bucket("2026-06",
                track("ddddddddddd", 99, 10)).getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "broken.json").toPath(), "{".getBytes(StandardCharsets.UTF_8));
        Map<String, SmartDownloads.Track> tracks = SmartDownloads.readMonths(dir, now, 3);
        check(tracks.size() == 3, "three months, YouTube songs only: " + tracks.keySet());
        check(!tracks.containsKey("ddddddddddd"), "older months left out");
        check(tracks.get("bbbbbbbbbbb").plays == 4, "plays added up across months");
        check(tracks.get("bbbbbbbbbbb").last == 200, "latest play kept");
        List<SmartDownloads.Track> top = SmartDownloads.choose(tracks, 10);
        check(top.size() == 3, "all with three plays or more");
        check(top.get(0).id.equals("aaaaaaaaaaa"), "most played first");
        check(top.get(1).id.equals("bbbbbbbbbbb"), "tie broken by most recent");
        check(SmartDownloads.choose(tracks, 1).size() == 1, "limited");
        check(top.get(0).song().getString("thumbnailUrl").equals("https://x/y.jpg"), "artwork carried");
        check(top.get(0).song().getString("albumName").equals("B"), "album carried");
        System.out.println("PASS: " + checks + " play alongside, steady playback and smart download checks.");
    }
}

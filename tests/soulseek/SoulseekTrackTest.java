package app.bitchord.soulseek;

import org.json.JSONObject;

public final class SoulseekTrackTest {
    static int checks;

    static void check(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }

    static SoulseekTrack track(String peer, String path, long size) throws Exception {
        return new SoulseekTrack(
                new JSONObject()
                        .put("peer", peer)
                        .put("path", path)
                        .put("size", size)
                        .put("duration", 225)
                        .put("bitrate", 320));
    }

    interface Task {
        void run() throws Exception;
    }

    static void denied(Task task, String message) throws Exception {
        try {
            task.run();
            throw new AssertionError(message);
        } catch (java.io.IOException expected) {
            checks++;
        }
    }

    public static void main(String[] args) throws Exception {
        SoulseekTrack first = track("peer-one", "Music\\Album\\Artist - Song.FLAC", 1000);
        check(
                first.format.equals("flac") && first.mime.equals("audio/flac"),
                "Case-insensitive audio extension");
        check(
                first.artist.equals("Artist") && first.title.equals("Song"),
                "Display names parsed from file");
        check(first.id.matches("[a-f0-9]{64}"), "Opaque deterministic asset id");
        check(
                first.id.equals(track("peer-one", "Music\\Album\\Artist - Song.FLAC", 1000).id),
                "Same asset reuses identity");
        check(
                !first.id.equals(track("peer-two", "Music\\Album\\Artist - Song.FLAC", 1000).id),
                "Different peer is a different asset");
        check(
                !first.id.equals(track("peer-one", "Music\\Other\\Artist - Song.FLAC", 1000).id),
                "Same basename in other folder is distinct");
        check(
                !first.id.equals(track("peer-one", "Music\\Album\\Artist - Song.FLAC", 1001).id),
                "Different size is distinct");
        JSONObject metadata = first.metadata();
        check(
                !metadata.has("audioQuality") && !metadata.has("peer") && !metadata.has("path"),
                "Metadata exposes neither unverified quality nor private peer paths");
        check(first.freeSlots == -1 && first.speed == 0, "missing peer hints remain unknown");
        JSONObject hints =
                new JSONObject()
                        .put("peer", "one")
                        .put("path", "Artist - Song.flac")
                        .put("size", 1000)
                        .put("freeSlots", 1)
                        .put("speed", 123456);
        SoulseekTrack advertised = new SoulseekTrack(hints);
        check(
                advertised.freeSlots == 1 && advertised.speed == 123456,
                "bounded numeric peer hints retained");
        check(
                !advertised.metadata().getBoolean("peerHintsVerified"),
                "availability and speed claims are explicitly unverified");
        for (Object invalid : new Object[] {-1, 2, 1.5, "1", Long.MAX_VALUE}) {
            check(
                    new SoulseekTrack(new JSONObject(hints.toString()).put("freeSlots", invalid))
                                    .freeSlots
                            == -1,
                    "invalid free-slot flag ignored: " + invalid);
        }
        for (Object invalid : new Object[] {-1, 1.5, "999999999", Long.MAX_VALUE, 4294967296L}) {
            check(
                    new SoulseekTrack(new JSONObject(hints.toString()).put("speed", invalid)).speed
                            == 0,
                    "invalid speed ignored: " + invalid);
        }
        denied(() -> track("one", "a.exe", 1000), "Executables rejected");
        denied(() -> track("one", "a.flac.exe", 1000), "Disguised executable rejected");
        denied(() -> track("one", "a.flac", -1), "Negative size rejected");
        denied(
                () -> track("one", "a.flac", 3L * 1024 * 1024 * 1024),
                "Oversized advertisement rejected");
        denied(() -> track("one", "a\0.flac", 1000), "NUL remote path rejected");
        System.out.println("PASS: " + checks + " Soulseek peer metadata checks.");
    }
}

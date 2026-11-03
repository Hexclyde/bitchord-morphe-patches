package app.bitchord.telegram;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

public final class VideoSwitchLyricsTest {
    public static final class Song {
        public String videoId, queueEntryId;
        public boolean isVideo;

        Song(String id, String entry, boolean video) {
            videoId = id;
            queueEntryId = entry;
            isVideo = video;
        }
    }

    public static final class Request {
        public final String videoId;

        Request(String id) {
            videoId = id;
        }
    }

    public static final class Model {
        public Object selectedLyricsSource = "Manual";
        public List<Object> lyricsFor = key("audio000001");
        public long lyricsGeneration = 7;
        public Request currentLyricsRequest = new Request("audio000001");
        public int choices, requests;
        public String requestedId, resultSource = "Fallback", state = "NOT_FETCHED";
        public long requestedGeneration;

        public void bitchordSelectLyricsProvider(Object source) {
            choices++;
            if (state.equals("NOT_FOUND")) return;
            selectedLyricsSource = source;
            if (state.equals("FOUND")) resultSource = (String) source;
            if (state.equals("NOT_FETCHED")) {
                requests++;
                requestedId = currentLyricsRequest.videoId;
                requestedGeneration = lyricsGeneration;
            }
        }

        void reset(List<Object> next) {
            selectedLyricsSource = null;
            lyricsFor = next;
            lyricsGeneration++;
            currentLyricsRequest = new Request((String) next.get(0));
        }
    }

    static List<Object> key(String id) {
        return new ArrayList<>(
                Arrays.asList(
                        id,
                        new HashSet<>(Arrays.asList("Automatic")),
                        new ArrayList<>(Arrays.asList("Automatic", "Manual")),
                        false));
    }

    static int checks;

    static void check(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }

    static final Song AUDIO = new Song("audio000001", "entry-1", false);
    static final Song VIDEO = new Song("video000001", "entry-1", true);

    static Model run(String state, Object selected, Song playing, long after, boolean onMain) {
        VideoSwitch.enabled = true;
        VideoSwitch.preferredVideo = true;
        Model model = new Model();
        model.state = state;
        model.selectedLyricsSource = selected;
        VideoSwitch.pendingLyricsSwitch(AUDIO, VIDEO, 100);
        List<Object> next = key(VIDEO.videoId);
        VideoSwitch.beforeLyrics(model, next, VIDEO, 110, true);
        model.reset(next);
        VideoSwitch.afterLyrics(model, playing, after, onMain);
        return model;
    }

    public static void main(String[] args) {
        Model lazy = run("NOT_FETCHED", "Manual", VIDEO, 120, true);
        check(lazy.requests == 1, "a manually selected lazy provider is actually requested");
        check(VIDEO.videoId.equals(lazy.requestedId), "fresh lyrics use the target rendition id");
        check(lazy.requestedGeneration == 8, "fresh lyrics use the new generation");
        check("Manual".equals(lazy.selectedLyricsSource), "explicit provider intent survives");
        check(
                lazy.lyricsFor.get(1).equals(key(AUDIO.videoId).get(1)),
                "automatic sources unchanged");
        Model automatic = run("NOT_FETCHED", null, VIDEO, 120, true);
        check(automatic.choices == 0, "Automatic does not become a manual selection");
        Model found = run("FOUND", "Manual", VIDEO, 120, true);
        check(
                found.choices == 1 && found.requests == 0 && found.resultSource.equals("Manual"),
                "a target-generation hit uses the native chooser without another request");
        Model fetching = run("FETCHING", "Manual", VIDEO, 120, true);
        check(
                fetching.choices == 1 && fetching.requests == 0,
                "an existing target request is reused");
        Model absent = run("NOT_FOUND", "Manual", VIDEO, 120, true);
        check(
                absent.requests == 0 && absent.resultSource.equals("Fallback"),
                "missing provider keeps honest fallback");
        Model unrelated =
                run("NOT_FETCHED", "Manual", new Song(VIDEO.videoId, "entry-2", true), 120, true);
        check(unrelated.choices == 0, "same rendition in another queue occurrence is unrelated");
        check(
                run("NOT_FETCHED", "Manual", VIDEO, 40_000, true).choices == 0,
                "an expired switch cannot change provider");
        check(
                run("NOT_FETCHED", "Manual", VIDEO, 120, false).choices == 0,
                "off-main completion is inert");
        Model changed = new Model();
        VideoSwitch.pendingLyricsSwitch(AUDIO, VIDEO, 100);
        List<Object> next = key(VIDEO.videoId);
        VideoSwitch.beforeLyrics(changed, next, VIDEO, 110, true);
        changed.reset(next);
        changed.selectedLyricsSource = "Later choice";
        VideoSwitch.afterLyrics(changed, VIDEO, 120, true);
        check(
                changed.choices == 0 && changed.selectedLyricsSource.equals("Later choice"),
                "later manual choice wins");
        Model stale = new Model();
        VideoSwitch.pendingLyricsSwitch(AUDIO, VIDEO, 100);
        VideoSwitch.beforeLyrics(stale, next, VIDEO, 110, true);
        stale.reset(next);
        stale.lyricsGeneration++;
        VideoSwitch.afterLyrics(stale, VIDEO, 120, true);
        check(stale.choices == 0, "stale lookup generation is inert");
        Model roundTrip = new Model();
        VideoSwitch.pendingLyricsSwitch(AUDIO, VIDEO, 100);
        VideoSwitch.beforeLyrics(roundTrip, next, VIDEO, 110, true);
        roundTrip.reset(next);
        VideoSwitch.afterLyrics(roundTrip, VIDEO, 120, true);
        VideoSwitch.preferredVideo = false;
        VideoSwitch.pendingLyricsSwitch(VIDEO, AUDIO, 130);
        List<Object> audioKey = key(AUDIO.videoId);
        VideoSwitch.beforeLyrics(roundTrip, audioKey, AUDIO, 140, true);
        roundTrip.reset(audioKey);
        VideoSwitch.afterLyrics(roundTrip, AUDIO, 150, true);
        check(
                roundTrip.requests == 2
                        && AUDIO.videoId.equals(roundTrip.requestedId)
                        && roundTrip.requestedGeneration == 9,
                "round trip requests fresh audio lyrics too");
        VideoSwitch.preferredVideo = true;
        Model settings = new Model();
        List<Object> changedKey = key(VIDEO.videoId);
        changedKey.set(3, true);
        VideoSwitch.pendingLyricsSwitch(AUDIO, VIDEO, 100);
        VideoSwitch.beforeLyrics(settings, changedKey, VIDEO, 110, true);
        settings.reset(changedKey);
        VideoSwitch.afterLyrics(settings, VIDEO, 120, true);
        check(settings.choices == 0, "changed lyric settings use the native lookup");
        VideoSwitch.enabled = false;
        Model disabled = new Model();
        VideoSwitch.pendingLyricsSwitch(AUDIO, VIDEO, 100);
        VideoSwitch.beforeLyrics(disabled, next, VIDEO, 110, true);
        disabled.reset(next);
        VideoSwitch.afterLyrics(disabled, VIDEO, 120, true);
        check(disabled.choices == 0, "disabled switch never chooses a provider");
        VideoSwitch.enabled = true;
        Model uncommitted = new Model();
        VideoSwitch.pendingLyricsSwitch(AUDIO, VIDEO, 100);
        VideoSwitch.beforeLyrics(uncommitted, next, AUDIO, 110, true);
        uncommitted.reset(next);
        check(
                VideoSwitch.afterLyrics(uncommitted, AUDIO, 120, true),
                "a valid pending source waits for commit");
        check(
                uncommitted.choices == 0,
                "dispatch without a committed target cannot restore a provider");
        VideoSwitch.afterLyrics(uncommitted, VIDEO, 130, true);
        check(
                uncommitted.requests == 1,
                "delayed target commit restores only its fresh provider request");
        Model missing = new Model();
        VideoSwitch.pendingLyricsSwitch(AUDIO, new Song(VIDEO.videoId, null, true), 100);
        VideoSwitch.beforeLyrics(missing, next, VIDEO, 110, true);
        missing.reset(next);
        VideoSwitch.afterLyrics(missing, VIDEO, 120, true);
        check(missing.choices == 0, "missing queue identity fails closed");
        Model abort = new Model();
        VideoSwitch.pendingLyricsSwitch(AUDIO, VIDEO, 100);
        VideoSwitch.beforeLyrics(abort, next, VIDEO, 110, true);
        abort.reset(next);
        VideoSwitch.setEnabled(false);
        VideoSwitch.setEnabled(true);
        VideoSwitch.afterLyrics(abort, VIDEO, 120, true);
        check(abort.choices == 0, "disabling cancels pending provider selection permanently");
        System.out.println("PASS: " + checks + " native lyrics-choice switch checks.");
    }
}

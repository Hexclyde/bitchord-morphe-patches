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
        public long durationMs = 215_000;

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

        int loads;

        /** BitChord's loadLyrics: the same key returns before the length is looked at again. */
        void load(List<Object> next, long ms, Song playing, long now, boolean hooked) {
            if (hooked) VideoSwitch.reloadLyrics(this, ms, playing, now, true);
            if (next.equals(lyricsFor) || ms <= 0) return;
            VideoSwitch.beforeLyrics(this, next, playing, now, true);
            reset(next);
            currentLyricsRequest.durationMs = ms;
            loads++;
            VideoSwitch.afterLyrics(this, playing, now, true);
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
        Song other = new Song("otherother1", "entry-0", false);
        VideoSwitch.noteCurrent(other, 100);
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

        for (boolean wantVideo : new boolean[] {true, false}) {
            for (boolean listenerFirst : new boolean[] {false, true}) {
                VideoSwitch.setEnabled(false);
                VideoSwitch.setEnabled(true);
                VideoSwitch.preferredVideo = !wantVideo;
                Song from = wantVideo ? AUDIO : VIDEO;
                Song to = wantVideo ? VIDEO : AUDIO;
                Model swapped = new Model();
                swapped.lyricsFor = key(from.videoId);
                swapped.currentLyricsRequest = new Request(from.videoId);
                List<Object> target = key(to.videoId);
                VideoSwitch.noteCurrent(other, 400);
                VideoSwitch.noteCurrent(from, 410);
                if (listenerFirst) VideoSwitch.noteCurrent(to, 420);
                VideoSwitch.beforeLyrics(swapped, target, to, 430, true);
                swapped.reset(target);
                VideoSwitch.afterLyrics(swapped, to, 440, true);
                check(
                        swapped.requests == 1 && to.videoId.equals(swapped.requestedId),
                        "a swap made by BitChord keeps the provider ("
                                + wantVideo
                                + ", "
                                + listenerFirst
                                + ")");
                check(
                        "Manual".equals(swapped.selectedLyricsSource),
                        "the provider outlives the stale saved preference");
            }
        }
        VideoSwitch.setEnabled(false);
        VideoSwitch.setEnabled(true);
        VideoSwitch.noteCurrent(other, 500);
        VideoSwitch.noteCurrent(AUDIO, 510);
        Model elsewhere = new Model();
        VideoSwitch.beforeLyrics(
                elsewhere, key(VIDEO.videoId), new Song(VIDEO.videoId, "entry-2", true), 520, true);
        elsewhere.reset(key(VIDEO.videoId));
        VideoSwitch.afterLyrics(elsewhere, new Song(VIDEO.videoId, "entry-2", true), 530, true);
        check(elsewhere.choices == 0, "another queue entry never inherits the provider");
        VideoSwitch.setEnabled(false);
        VideoSwitch.noteCurrent(other, 600);
        VideoSwitch.noteCurrent(AUDIO, 610);
        Model off = new Model();
        VideoSwitch.beforeLyrics(off, key(VIDEO.videoId), VIDEO, 620, true);
        off.reset(key(VIDEO.videoId));
        VideoSwitch.afterLyrics(off, VIDEO, 630, true);
        check(off.choices == 0, "no host swap pins a provider while the switch is off");
        VideoSwitch.setEnabled(true);

        // A provider with nothing for the video is still the listener's choice for the song.
        VideoSwitch.preferredVideo = true;
        Model gone = run("NOT_FOUND", "Manual", VIDEO, 120, true);
        check(
                gone.choices == 1 && gone.selectedLyricsSource == null,
                "a missing provider leaves the video on the honest fallback");
        gone.state = "NOT_FETCHED";
        VideoSwitch.preferredVideo = false;
        VideoSwitch.pendingLyricsSwitch(VIDEO, AUDIO, 130);
        List<Object> back = key(AUDIO.videoId);
        VideoSwitch.beforeLyrics(gone, back, AUDIO, 140, true);
        gone.reset(back);
        VideoSwitch.afterLyrics(gone, AUDIO, 150, true);
        check(
                gone.requests == 1
                        && AUDIO.videoId.equals(gone.requestedId)
                        && "Manual".equals(gone.selectedLyricsSource),
                "the provider returns with the song after a version that lacked it");
        Song audioTwo = new Song(AUDIO.videoId, "entry-2", false);
        Song videoTwo = new Song(VIDEO.videoId, "entry-2", true);
        Model nextEntry = new Model();
        nextEntry.selectedLyricsSource = null;
        VideoSwitch.preferredVideo = true;
        VideoSwitch.pendingLyricsSwitch(audioTwo, videoTwo, 200);
        VideoSwitch.beforeLyrics(nextEntry, key(VIDEO.videoId), videoTwo, 210, true);
        nextEntry.reset(key(VIDEO.videoId));
        VideoSwitch.afterLyrics(nextEntry, videoTwo, 220, true);
        check(nextEntry.choices == 0, "a kept provider never moves to another queue entry");
        // The length lands after the track: a lookup made with the catalogue length goes stale.
        for (boolean hooked : new boolean[] {false, true}) {
            VideoSwitch.setEnabled(false);
            VideoSwitch.setEnabled(true);
            VideoSwitch.preferredVideo = true;
            VideoSwitch.noteCurrent(AUDIO, 700);
            Model late = new Model();
            VideoSwitch.pendingLyricsSwitch(AUDIO, VIDEO, 710);
            VideoSwitch.noteCurrent(VIDEO, 715);
            late.load(key(VIDEO.videoId), 215_000, VIDEO, 720, hooked);
            late.load(key(VIDEO.videoId), 231_000, VIDEO, 800, hooked);
            check(
                    hooked ? late.loads == 2 : late.loads == 1,
                    "the host never looks the video up again at its real length (" + hooked + ")");
            if (!hooked) continue;
            check(
                    late.currentLyricsRequest.durationMs == 231_000,
                    "the reload uses the length the video really has");
            check(
                    "Manual".equals(late.selectedLyricsSource) && late.requests == 2,
                    "the reload keeps the chosen provider");
            late.load(key(VIDEO.videoId), 231_000, VIDEO, 900, hooked);
            late.load(key(VIDEO.videoId), 232_500, VIDEO, 950, hooked);
            check(late.loads == 2, "the same or a close length is not looked up again");
            late.load(key(VIDEO.videoId), 215_000, VIDEO, 1000, hooked);
            late.load(key(VIDEO.videoId), 231_000, VIDEO, 1100, hooked);
            late.load(key(VIDEO.videoId), 215_000, VIDEO, 1200, hooked);
            check(late.loads <= 4, "a length that keeps flipping stops reloading");
        }
        VideoSwitch.setEnabled(false);
        VideoSwitch.setEnabled(true);
        VideoSwitch.noteCurrent(VIDEO, 2000);
        Model intro = new Model();
        intro.load(key(VIDEO.videoId), 231_000, VIDEO, 2010, true);
        intro.load(key(VIDEO.videoId), 231_000, VIDEO, 2100, true);
        check(intro.loads == 1, "a video intro the first lookup already used is not reloaded");
        Model auto = new Model();
        auto.selectedLyricsSource = null;
        auto.load(key(VIDEO.videoId), 215_000, VIDEO, 2200, true);
        auto.load(key(VIDEO.videoId), 231_000, VIDEO, 2300, true);
        check(auto.loads == 2 && auto.choices == 0, "Automatic reloads without a manual choice");
        Model wrong = new Model();
        wrong.load(key(VIDEO.videoId), 215_000, VIDEO, 2400, true);
        wrong.load(key(VIDEO.videoId), 231_000, AUDIO, 2500, true);
        check(wrong.loads == 1, "another playing version is never reloaded");
        wrong.load(key(VIDEO.videoId), 0, VIDEO, 2700, true);
        check(wrong.loads == 1, "an unknown length is never reloaded");
        VideoSwitch.enabled = false;
        wrong.load(key(VIDEO.videoId), 231_000, VIDEO, 2800, true);
        check(wrong.loads == 1, "a disabled switch never reloads");
        VideoSwitch.enabled = true;
        System.out.println("PASS: " + checks + " native lyrics-choice switch checks.");
    }
}

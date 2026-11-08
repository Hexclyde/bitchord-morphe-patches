package app.bitchord.telegram;

import com.music.bitchord.data.model.PlaybackSourceType;
import com.music.bitchord.data.model.QueueTier;
import com.music.bitchord.data.model.Song;

/** Audio to video and back keeps the song analysable by Automix and the play statistics. */
public final class VideoRenditionTest {
    static int checks;

    static void check(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }

    static Song song(String id, boolean video, boolean origin, String entry) {
        return new Song(
                id,
                "Title",
                "Artist",
                null,
                "3:00",
                null,
                null,
                null,
                null,
                video,
                origin,
                null,
                entry == null ? QueueTier.CONTEXT : QueueTier.USER_QUEUE,
                entry,
                "Radio",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                "Playlist",
                PlaybackSourceType.valueOf(PlaybackSourceType.values()[0].name()),
                "list-1",
                null);
    }

    /** BitChord's Automix and stats gate: a video, or a song that began as one, is skipped. */
    static boolean analysable(Song song) {
        return !song.isVideo && !song.isVideoOrigin;
    }

    public static void main(String[] args) throws Exception {
        Song audio = song("audioAud01", false, false, "entry-1");
        Song video = song("videoVid01", true, true, "entry-1");
        check(analysable(audio), "a native song is analysable");
        check(!analysable(video), "a video is not");

        Song toVideo = (Song) VideoSwitch.asReplacement(video, audio, true);
        check(toVideo != video && toVideo.isVideo && toVideo.isVideoOrigin, "the video version");
        check("entry-1".equals(toVideo.queueEntryId), "the video keeps its queue entry");
        check("Radio".equals(toVideo.radioName), "and its radio");
        check(!analysable(toVideo), "a video stays skipped by Automix");

        Song back = (Song) VideoSwitch.asReplacement(audio, toVideo, false);
        check(back != audio && "audioAud01".equals(back.videoId), "a fresh copy of the song");
        check(!back.isVideo, "the song version is not a video");
        check(!back.isVideoOrigin, "and does not claim to have begun as one");
        check(analysable(back), "Automix and stats read the song again");
        check("entry-1".equals(back.queueEntryId), "the song keeps its queue entry");
        check("list-1".equals(back.playbackSourceId), "and its source");
        check(!audio.isVideoOrigin && video.isVideoOrigin, "the cached versions stay as they were");

        Song again = (Song) VideoSwitch.asReplacement(video, back, true);
        check(again.isVideo && again.isVideoOrigin, "the video can be chosen again");
        VideoSwitch.enabled = true;
        VideoSwitch.preferredVideo = false;
        check(
                !VideoSwitch.pictureAllowed("videoVid001"),
                "Audio suppresses a queued video's picture");
        check(!VideoSwitch.pictureAllowed("videoVid002"), "Audio suppresses the next video too");
        VideoSwitch.noteCurrent(song("audioAud001", false, false, "entry-manual"), 100);
        VideoSwitch.noteCurrent(song("videoVid001", true, true, "entry-manual"), 101);
        check(
                VideoSwitch.pictureAllowed("videoVid001"),
                "An explicit host version switch keeps its picture");
        VideoSwitch.noteCurrent(song("videoVid002", true, true, "entry-next"), 102);
        check(
                !VideoSwitch.pictureAllowed("videoVid002"),
                "The host exception ends at the next queue entry");
        check(!VideoSwitch.pictureAllowed("videoVid001"), "The previous exception is cleared too");
        VideoSwitch.preferredVideo = true;
        check(VideoSwitch.pictureAllowed("videoVid001"), "Video permits the host picture check");
        VideoSwitch.enabled = false;
        VideoSwitch.preferredVideo = false;
        check(
                VideoSwitch.pictureAllowed("videoVid001"),
                "Disabled switch leaves the host picture alone");
        System.out.println("PASS: " + checks + " video rendition checks.");
    }
}

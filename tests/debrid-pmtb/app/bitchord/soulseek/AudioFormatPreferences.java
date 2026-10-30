package app.bitchord.soulseek;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Only the filename-candidate boundary; real codec checks have their own host suite. */
final class AudioFormatPreferences {
    static List<String> candidateFormats(String name) {
        return name.toLowerCase(Locale.ROOT)
                        .matches(
                                ".*\\.(flac|alac|wav|wave|aif|aiff|aifc|dsf|dff|opus|ogg|oga|vorbis|aac|m4a|mp4|mp3)$")
                ? Collections.singletonList("audio")
                : Collections.emptyList();
    }
}

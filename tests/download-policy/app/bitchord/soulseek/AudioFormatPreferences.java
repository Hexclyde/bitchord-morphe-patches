package app.bitchord.soulseek;

import java.util.List;

/** Compile-only policy stand-in: these tests verify playback never reaches search. */
final class AudioFormatPreferences {
    static Object snapshot(Object context) {
        throw new AssertionError("Playback reached format preferences");
    }

    static List<SoulseekTrack> ordered(Object policy, List<SoulseekTrack> tracks) {
        throw new AssertionError("Playback reached download candidate ranking");
    }
}

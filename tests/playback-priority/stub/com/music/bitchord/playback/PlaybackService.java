package com.music.bitchord.playback;

/** Runtime stand-in for the host service: only what the checked code calls. */
public final class PlaybackService {
    private PlaybackService() {}

    public static boolean bitchordAutomixStopped() {
        return false;
    }
}

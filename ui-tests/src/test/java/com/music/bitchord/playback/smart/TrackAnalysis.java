package com.music.bitchord.playback.smart;

/** Analysis result fixture; the actual class/field ABI is checked in every patched APK. */
public final class TrackAnalysis {
    public String trackId;
    public double bpm = 128;

    public TrackAnalysis(String id) {
        trackId = id;
    }
}

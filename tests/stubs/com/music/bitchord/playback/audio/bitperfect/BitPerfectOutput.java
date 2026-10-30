package com.music.bitchord.playback.audio.bitperfect;

import com.music.bitchord.data.settings.AppSettings.Flow;

/** Public state shape inspected in the released BitChord1.9 DEX. */
public final class BitPerfectOutput {
    public static final class Status {
        public final boolean active;

        public Status(boolean active) {
            this.active = active;
        }
    }

    public static final Flow<Status> _status = new Flow<>(new Status(false));
}

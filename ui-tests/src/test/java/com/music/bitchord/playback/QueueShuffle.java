package com.music.bitchord.playback;

import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

/** The in-app shuffle state as BitChord 1.9 exposes it: a static read-only state flow. */
public final class QueueShuffle {
    private static final MutableStateFlow<Boolean> STATE = StateFlowKt.MutableStateFlow(false);

    private QueueShuffle() {}

    public static StateFlow<Boolean> getEnabled() {
        return STATE;
    }

    public static void setEnabled(boolean enabled) {
        STATE.setValue(enabled);
    }
}

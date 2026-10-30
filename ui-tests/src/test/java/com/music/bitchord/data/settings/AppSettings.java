package com.music.bitchord.data.settings;

import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

/** Host theme setting used by the real native screens under Robolectric. */
public final class AppSettings {
    public static final MutableStateFlow<String> themeMode = StateFlowKt.MutableStateFlow("SYSTEM");

    public enum BitPerfectMode {
        OFF,
        USB_DIRECT
    }

    public static final MutableStateFlow<BitPerfectMode> bitPerfectMode =
            StateFlowKt.MutableStateFlow(BitPerfectMode.OFF);

    private AppSettings() {}
}

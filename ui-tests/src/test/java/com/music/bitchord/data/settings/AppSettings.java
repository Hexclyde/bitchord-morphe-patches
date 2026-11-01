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

    private static final MutableStateFlow<Boolean> smartFadeEnabled =
            StateFlowKt.MutableStateFlow(false);

    public static MutableStateFlow<Boolean> getSmartFadeEnabled() {
        return smartFadeEnabled;
    }

    public static void setSmartFadeEnabled(boolean enabled) {
        smartFadeEnabled.setValue(enabled);
        org.robolectric.RuntimeEnvironment.getApplication()
                .getSharedPreferences("bitchord_settings", 0)
                .edit()
                .putBoolean("smart_fade_enabled", enabled)
                .apply();
    }

    public static void setEqualizerEnabled(boolean enabled) {
        org.robolectric.RuntimeEnvironment.getApplication()
                .getSharedPreferences("bitchord_settings", 0)
                .edit()
                .putBoolean("equalizer_enabled", enabled)
                .apply();
    }

    private AppSettings() {}
}

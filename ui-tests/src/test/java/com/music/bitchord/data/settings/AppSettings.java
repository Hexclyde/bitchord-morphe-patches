package com.music.bitchord.data.settings;

import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

/** Host theme setting used by the real native screens under Robolectric. */
public final class AppSettings {
    public static final MutableStateFlow<DownloadQuality> downloadQuality =
            StateFlowKt.MutableStateFlow(DownloadQuality.LOSSLESS);
    public static final MutableStateFlow<String> themeMode = StateFlowKt.MutableStateFlow("SYSTEM");
    public static final MutableStateFlow<String> lastPlayerScreen =
            StateFlowKt.MutableStateFlow("MAIN");

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

    public static final AppSettings INSTANCE = new AppSettings();
    public static final HostFlow<Integer> equalizerToneX = new HostFlow<>(0);
    public static final HostFlow<Integer> equalizerToneY = new HostFlow<>(0);
    public static final HostFlow<Float> equalizerBalance = new HostFlow<>(0f);
    public static final HostFlow<EqualizerMode> equalizerMode =
            new HostFlow<>(EqualizerMode.DYNAMIC);

    /**
     * A state flow as R8 leaves it: a final class with its own setValue and no interface for it.
     */
    public static final class HostFlow<T> {
        private volatile T value;

        HostFlow(T value) {
            this.value = value;
        }

        public T getValue() {
            return value;
        }

        public void setValue(T value) {
            this.value = value;
        }
    }

    public static final java.util.List<String> equalizerCalls = new java.util.ArrayList<>();

    public static void setEqualizerBands(java.util.List<Float> bands) {
        equalizerCalls.add("bands");
    }

    public static void setEqualizerFocused(boolean focused) {
        equalizerCalls.add("focused=" + focused);
    }

    public static void setEqualizerEnabled(boolean enabled) {
        equalizerCalls.add("enabled=" + enabled);
        org.robolectric.RuntimeEnvironment.getApplication()
                .getSharedPreferences("bitchord_settings", 0)
                .edit()
                .putBoolean("equalizer_enabled", enabled)
                .apply();
    }

    private AppSettings() {}
}

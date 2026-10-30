package com.music.bitchord.data.settings;

/** The two public state flows used by the bit-perfect runtime contract. */
public final class AppSettings {
    public enum Mode {
        OFF,
        USB_ANDROID,
        USB_DIRECT,
        DAP_EXCLUSIVE
    }

    public static final class Flow<T> {
        public T value;

        public Flow(T value) {
            this.value = value;
        }

        public T getValue() {
            return value;
        }
    }

    public static final Flow<Mode> bitPerfectMode = new Flow<>(Mode.OFF);
}

package com.music.bitchord.playback;

/** Public flow surface used by the pinned host timer's reflective read-only contract. */
public final class SleepTimer {
    public static final Flow<Long> deadline = new Flow<>(null);
    public static final Flow<Boolean> afterTrack = new Flow<>(false);

    private SleepTimer() {}

    public static final class Flow<T> {
        private T value;

        Flow(T value) {
            this.value = value;
        }

        public T getValue() {
            return value;
        }

        public void setValue(T value) {
            this.value = value;
        }
    }
}

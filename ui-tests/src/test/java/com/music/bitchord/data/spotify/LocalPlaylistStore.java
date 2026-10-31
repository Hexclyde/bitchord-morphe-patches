package com.music.bitchord.data.spotify;

import java.util.*;

/** The optimized APK exposes concrete methods; its MutableStateFlow interface is empty. */
public final class LocalPlaylistStore {
    public static final OptimizedState _playlists = new OptimizedState();

    public static final class OptimizedState {
        private List<Object> value = new ArrayList<>();

        public synchronized Object getValue() {
            return value;
        }

        public synchronized void setValue(Object next) {
            value = (List<Object>) next;
        }

        public synchronized boolean compareAndSet(Object expected, Object next) {
            if (!Objects.equals(value, expected)) return false;
            setValue(next);
            return true;
        }
    }
}

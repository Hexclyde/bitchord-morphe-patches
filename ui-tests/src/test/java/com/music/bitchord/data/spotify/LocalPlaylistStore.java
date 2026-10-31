package com.music.bitchord.data.spotify;

import kotlinx.coroutines.flow.*;

import java.util.*;

public final class LocalPlaylistStore {
    public static final MutableStateFlow<List<Object>> _playlists =
            StateFlowKt.MutableStateFlow(new ArrayList<>());
}

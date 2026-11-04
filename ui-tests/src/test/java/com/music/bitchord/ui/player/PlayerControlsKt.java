package com.music.bitchord.ui.player;

import androidx.compose.runtime.DynamicProvidableCompositionLocal;

public final class PlayerControlsKt {
    public static final DynamicProvidableCompositionLocal LocalPlayerTint =
            new DynamicProvidableCompositionLocal();

    public static DynamicProvidableCompositionLocal getLocalPlayerTint() {
        return LocalPlayerTint;
    }
}

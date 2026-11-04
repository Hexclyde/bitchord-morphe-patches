package com.music.bitchord.ui.player;

import androidx.compose.runtime.DynamicProvidableCompositionLocal;
import androidx.compose.ui.Modifier;

/** The pinned player's chrome local and its additive blend, as the host spells them. */
public final class PlayerChromeKt {
    public static final DynamicProvidableCompositionLocal LocalPlayerChrome =
            new DynamicProvidableCompositionLocal();

    public static int blends;

    public static final class Additive implements Modifier {}

    public static Modifier additiveChrome(Modifier base, boolean enabled) {
        blends++;
        return enabled ? base.then(new Additive()) : base;
    }
}

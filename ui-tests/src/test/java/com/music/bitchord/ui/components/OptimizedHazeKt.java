package com.music.bitchord.ui.components;

import androidx.compose.ui.Modifier;

import dev.chrisbanes.haze.HazeState;
import dev.chrisbanes.haze.HazeStyle;

import kotlin.jvm.functions.Function1;

public final class OptimizedHazeKt {
    public static final class Frost implements Modifier {
        public final HazeState state;
        public final HazeStyle style;

        Frost(HazeState state, HazeStyle style) {
            this.state = state;
            this.style = style;
        }
    }

    public static HazeStyle menuFrost(float alpha, long tint) {
        return new HazeStyle(alpha, tint);
    }

    public static Modifier optimizedHazeEffect(
            Modifier base, HazeState state, HazeStyle style, Function1 block) {
        return base.then(new Frost(state, style));
    }
}

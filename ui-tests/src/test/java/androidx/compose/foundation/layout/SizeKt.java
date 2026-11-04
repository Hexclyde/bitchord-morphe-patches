package androidx.compose.foundation.layout;

import androidx.compose.ui.Modifier;

/** Pinned host size-modifier ABI; view measurement remains Android's responsibility. */
public final class SizeKt {
    public static final Modifier FillWholeMaxWidth = new Modifier() {};
    public static final Modifier FillWholeMaxSize = new Modifier() {};

    public static final class Fill implements Modifier {
        public final Modifier base;
        public final float fraction;

        Fill(Modifier base, float fraction) {
            this.base = base;
            this.fraction = fraction;
        }
    }

    public static final class Wrap implements Modifier {
        public final androidx.compose.ui.BiasAlignment.Horizontal alignment;
        public final boolean unbounded;

        Wrap(androidx.compose.ui.BiasAlignment.Horizontal alignment, boolean unbounded) {
            this.alignment = alignment;
            this.unbounded = unbounded;
        }
    }

    public static final class Side implements Modifier {
        public final float dp;

        Side(float dp) {
            this.dp = dp;
        }
    }

    public static Modifier wrapContentWidth(
            Modifier base,
            androidx.compose.ui.BiasAlignment.Horizontal alignment,
            boolean unbounded) {
        return base.then(new Wrap(alignment, unbounded));
    }

    public static Modifier size(Modifier base, float dp) {
        return base.then(new Side(dp));
    }

    public static Modifier fillMaxSize(Modifier base, float fraction) {
        return new Fill(base, fraction);
    }
}

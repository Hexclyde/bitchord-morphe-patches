package androidx.compose.foundation.layout;

import androidx.compose.ui.Modifier;

/** Pinned host size-modifier ABI; view measurement remains Android's responsibility. */
public final class SizeKt {
    public static final Modifier FillWholeMaxSize = new Modifier() {};

    public static final class Fill implements Modifier {
        public final Modifier base;
        public final float fraction;

        Fill(Modifier base, float fraction) {
            this.base = base;
            this.fraction = fraction;
        }
    }

    public static Modifier fillMaxSize(Modifier base, float fraction) {
        return new Fill(base, fraction);
    }
}

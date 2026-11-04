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

    public static Modifier heightIn(Modifier base, float min, float max) {
        return base.then(new SizeElement(Float.NaN, min, Float.NaN, max));
    }

    public static Modifier fillMaxSize(Modifier base, float fraction) {
        return new Fill(base, fraction);
    }

    /**
     * Compose's required-size element. A bound left at Dp.Unspecified (NaN) takes the incoming
     * constraint instead, as the native size node does when it does not enforce its parent.
     */
    public static final class RequiredSizeIn implements Modifier {
        public final float minWidth, minHeight, maxWidth, maxHeight;

        RequiredSizeIn(float minWidth, float minHeight, float maxWidth, float maxHeight) {
            this.minWidth = minWidth;
            this.minHeight = minHeight;
            this.maxWidth = maxWidth;
            this.maxHeight = maxHeight;
        }

        private static boolean given(float dp) {
            return !Float.isNaN(dp);
        }

        private static int px(float dp, float density) {
            return Float.isInfinite(dp) ? Integer.MAX_VALUE : Math.round(Math.max(0, dp) * density);
        }

        /**
         * The limits the child is measured with: {minWidth, maxWidth, minHeight, maxHeight} in
         * pixels, Integer.MAX_VALUE for no limit, from the parent's {minW, maxW, minH, maxH}.
         */
        public int[] childConstraints(int[] incoming, float density) {
            int targetMaxW = given(maxWidth) ? px(maxWidth, density) : Integer.MAX_VALUE;
            int targetMaxH = given(maxHeight) ? px(maxHeight, density) : Integer.MAX_VALUE;
            int targetMinW = given(minWidth) ? Math.min(px(minWidth, density), targetMaxW) : 0;
            int targetMinH = given(minHeight) ? Math.min(px(minHeight, density), targetMaxH) : 0;
            int minW = given(minWidth) ? targetMinW : Math.min(incoming[0], targetMaxW);
            int maxW = given(maxWidth) ? targetMaxW : Math.max(incoming[1], targetMinW);
            int minH = given(minHeight) ? targetMinH : Math.min(incoming[2], targetMaxH);
            int maxH = given(maxHeight) ? targetMaxH : Math.max(incoming[3], targetMinH);
            return new int[] {minW, maxW, minH, maxH};
        }

        /** What the parent sees: the child's size held to the incoming limits. */
        public static int reported(int child, int min, int max) {
            return Math.max(min, Math.min(max, child));
        }
    }

    /**
     * The host leaves minWidth explicit after shrinking; mask bits 1, 2 and 3 default minHeight,
     * maxWidth and maxHeight to Dp.Unspecified.
     */
    public static Modifier requiredSizeIn$default(
            Modifier base,
            float minWidth,
            float minHeight,
            float maxWidth,
            float maxHeight,
            int mask) {
        return base.then(
                new RequiredSizeIn(
                        minWidth,
                        (mask & 2) != 0 ? Float.NaN : minHeight,
                        (mask & 4) != 0 ? Float.NaN : maxWidth,
                        (mask & 8) != 0 ? Float.NaN : maxHeight));
    }
}

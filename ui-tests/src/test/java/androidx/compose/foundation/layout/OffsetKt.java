package androidx.compose.foundation.layout;

import androidx.compose.ui.Modifier;

public final class OffsetKt {
    public static final class Padding implements Modifier {
        public final float start, top, end, bottom;

        Padding(float start, float top, float end, float bottom) {
            this.start = start;
            this.top = top;
            this.end = end;
            this.bottom = bottom;
        }
    }

    public static final class Offset implements Modifier {
        public final float x, y;

        Offset(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    public static Modifier offset(Modifier base, float x, float y) {
        return base.then(new Offset(x, y));
    }

    /** The host's four-sided padding without a default mask. */
    public static Modifier padding(Modifier base, float start, float top, float end, float bottom) {
        return base.then(new Padding(start, top, end, bottom));
    }

    /** Like the host's: the last argument says which of the others are left to their defaults. */
    public static Modifier padding(
            Modifier base, float start, float top, float end, float bottom, int defaults) {
        return base.then(new Padding(start, top, end, bottom));
    }

    /**
     * What offset(Function1) records: the lambda is read in the placement phase. Like the host's
     * OffsetPxElement (its toString prints rtlAware=true), x grows toward the end edge.
     */
    public static final class OffsetBy implements Modifier {
        public final boolean rtlAware = true;
        public final kotlin.jvm.functions.Function1<Object, Object> offset;

        OffsetBy(kotlin.jvm.functions.Function1<Object, Object> offset) {
            this.offset = offset;
        }
    }

    public static Modifier offset(
            Modifier base, kotlin.jvm.functions.Function1<Object, Object> offset) {
        return base.then(new OffsetBy(offset));
    }
}

package androidx.compose.ui.unit;

/** The host's packed offset: x in the high 32 bits, y in the low 32; its constructor is private. */
public final class IntOffset {
    public final long packed;

    private IntOffset(long packed) {
        this.packed = packed;
    }

    public int x() {
        return (int) (packed >> 32);
    }

    public int y() {
        return (int) packed;
    }
}

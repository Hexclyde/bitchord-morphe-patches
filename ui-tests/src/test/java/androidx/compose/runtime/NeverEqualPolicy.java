package androidx.compose.runtime;

public final class NeverEqualPolicy implements SnapshotMutationPolicy {
    public static final NeverEqualPolicy INSTANCE = new NeverEqualPolicy();

    private NeverEqualPolicy() {}

    @Override
    public boolean equivalent(Object a, Object b) {
        return false;
    }

    @Override
    public Object merge(Object previous, Object current, Object applied) {
        return null;
    }
}

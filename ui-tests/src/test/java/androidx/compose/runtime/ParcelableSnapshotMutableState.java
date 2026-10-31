package androidx.compose.runtime;

public final class ParcelableSnapshotMutableState {
    public static int reads, writes;
    private Object value;
    private final SnapshotMutationPolicy policy;

    public ParcelableSnapshotMutableState(Object value, SnapshotMutationPolicy policy) {
        this.value = value;
        this.policy = policy;
    }

    public Object getValue() {
        reads++;
        return value;
    }

    public void setValue(Object value) {
        if (!policy.equivalent(this.value, value)) {
            writes++;
            this.value = value;
        }
    }
}

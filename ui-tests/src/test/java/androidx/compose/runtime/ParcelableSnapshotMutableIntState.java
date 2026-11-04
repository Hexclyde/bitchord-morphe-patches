package androidx.compose.runtime;

public final class ParcelableSnapshotMutableIntState {
    public static int reads, writes;
    private int value;

    public ParcelableSnapshotMutableIntState(int value) {
        this.value = value;
    }

    public int getIntValue() {
        reads++;
        return value;
    }

    public void setIntValue(int value) {
        writes++;
        this.value = value;
    }
}

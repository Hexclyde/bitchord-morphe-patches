package android.content;

public final class Intent {
    public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
    public final Class<?> target;

    public Intent(Context context, Class<?> target) {
        this.target = target;
    }

    public Intent addFlags(int flags) {
        return this;
    }
}

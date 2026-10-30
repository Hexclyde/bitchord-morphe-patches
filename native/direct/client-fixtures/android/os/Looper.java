package android.os;

public final class Looper {
    private static final Looper MAIN = new Looper();

    public static Looper myLooper() {
        return null;
    }

    public static Looper getMainLooper() {
        return MAIN;
    }
}

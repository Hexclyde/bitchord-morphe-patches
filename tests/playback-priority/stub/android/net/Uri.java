package android.net;

/** Just enough of the platform Uri to build a DataSpec on the JVM. */
public class Uri {
    public static final Uri EMPTY = new Uri();

    @Override
    public String toString() {
        return "";
    }
}

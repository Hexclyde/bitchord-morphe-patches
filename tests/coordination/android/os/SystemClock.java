package android.os;

import java.util.concurrent.atomic.AtomicLong;

public final class SystemClock {
    private static final AtomicLong NOW = new AtomicLong(100_000);

    public static long elapsedRealtime() {
        return NOW.get();
    }

    public static void advance(long milliseconds) {
        NOW.addAndGet(milliseconds);
    }
}

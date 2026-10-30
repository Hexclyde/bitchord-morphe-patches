package android.os;

import java.util.concurrent.atomic.AtomicLong;

public final class SystemClock {
    private static final AtomicLong now = new AtomicLong(100_000);

    public static long elapsedRealtime() {
        return now.get();
    }

    public static void advance(long value) {
        now.addAndGet(value);
    }
}

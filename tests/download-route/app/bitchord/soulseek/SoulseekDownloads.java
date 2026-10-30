package app.bitchord.soulseek;

import kotlin.coroutines.CoroutineContext;
import kotlin.jvm.functions.Function2;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.CancellationException;

public final class SoulseekDownloads {
    static final byte[] ORIGINAL = new byte[] {4, 8, 15, 16, 23, 42};
    static String lastUrl;
    static CoroutineContext lastContext;
    static int fetches;
    static boolean cancelled;

    public static Long fetch(
            String url,
            OutputStream sink,
            Function2<Long, Long, ?> progress,
            CoroutineContext context)
            throws IOException {
        lastUrl = url;
        lastContext = context;
        fetches++;
        if (cancelled) throw new CancellationException("Cancelled fixture");
        if (!url.startsWith(SoulseekAddon.ORIGIN + SoulseekBridge.INSTANCE.sessionTag + "/audio/"))
            throw new IOException("Session changed");
        sink.write(ORIGINAL);
        progress.invoke((long) ORIGINAL.length, (long) ORIGINAL.length);
        return (long) ORIGINAL.length;
    }
}

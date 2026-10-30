package app.bitchord.soulseek;

import kotlin.coroutines.CoroutineContext;
import kotlin.jvm.functions.Function2;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class SoulseekDownloads {
    static volatile int fetches;
    static final java.util.List<String> requests =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    static volatile String url;
    static volatile CountDownLatch entered, release;
    static volatile boolean pauseBeforeReturn;
    static volatile String failure;

    static Long fetchSelected(
            SoulseekTrack track,
            String session,
            OutputStream sink,
            Function2<Long, Long, ?> progress,
            CoroutineContext context)
            throws IOException {
        return fetch(
                SoulseekAddon.ORIGIN + session + "/audio/" + track.id, sink, progress, context);
    }

    static Long fetch(
            String request,
            OutputStream sink,
            Function2<Long, Long, ?> progress,
            CoroutineContext context)
            throws IOException {
        fetches++;
        requests.add(request);
        url = request;
        if (failure != null) throw new IOException(failure);
        CountDownLatch gate = entered, proceed = release;
        if (gate != null) {
            gate.countDown();
            try {
                if (!proceed.await(5, TimeUnit.SECONDS))
                    throw new IOException("Test gate timed out");
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("cancelled");
            }
        }
        sink.write(new byte[] {'f', 'L', 'a', 'C'});
        progress.invoke(4L, 4L);
        if (pauseBeforeReturn) com.music.bitchord.data.settings.AppSettings.allowed = false;
        return 4L;
    }
}

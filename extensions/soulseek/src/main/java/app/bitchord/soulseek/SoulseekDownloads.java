package app.bitchord.soulseek;

import kotlin.coroutines.CoroutineContext;
import kotlin.jvm.functions.Function2;

import kotlinx.coroutines.JobKt;

import java.io.IOException;
import java.io.OutputStream;

/** Copies the selected file into BitChord's phone-download sink. */
public final class SoulseekDownloads {
    private SoulseekDownloads() {}

    public static Long fetch(
            String url,
            OutputStream sink,
            Function2<Long, Long, ?> progress,
            CoroutineContext context)
            throws IOException {
        JobKt.ensureActive(context);
        SoulseekBridge bridge = SoulseekBridge.get();
        String id = SoulseekAddon.audioId(bridge, url);
        long total = bridge.track(id).size;
        try (ProgressiveReader reader = bridge.open(id, 0, -1)) {
            reader.cancellation = () -> JobKt.ensureActive(context);
            byte[] buffer = new byte[64 * 1024];
            long written = 0;
            int count;
            while ((count = reader.read(buffer, 0, buffer.length)) != -1) {
                JobKt.ensureActive(context);
                sink.write(buffer, 0, count);
                written += count;
                progress.invoke(written, total);
            }
            JobKt.ensureActive(context);
            if (written != total) throw new IOException("Soulseek audio download is incomplete");
            sink.flush();
            return written;
        }
    }
}

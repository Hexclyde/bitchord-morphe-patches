package app.bitchord.soulseek;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class SoulseekBridge {
    static final long MAX_CACHE_BYTES = 256L * 1024 * 1024;
    static SoulseekBridge instance = new SoulseekBridge();
    final Map<String, SoulseekTrack> tracks = new ConcurrentHashMap<>();
    volatile String sessionTag = "old-session";
    volatile boolean changeSessionOnLookup;
    volatile TransferStatus progress = new TransferStatus("queued", 0, 4, 7);
    volatile CountDownLatch pollEntered, pollRelease;

    static final class TransferStatus {
        final String state;
        final long downloaded, total, queuePosition;

        TransferStatus(String state, long downloaded, long total, long queuePosition) {
            this.state = state;
            this.downloaded = downloaded;
            this.total = total;
            this.queuePosition = queuePosition;
        }
    }

    TransferStatus transferStatus(String id, String session) throws IOException {
        if (pollEntered != null) {
            pollEntered.countDown();
            try {
                if (!pollRelease.await(5, TimeUnit.SECONDS)) throw new IOException("Timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
        }
        return progress;
    }

    static SoulseekBridge get() {
        return instance;
    }

    SoulseekTrack track(String id) throws IOException {
        SoulseekTrack track = tracks.get(id);
        if (track == null) throw new IOException("Selected result expired.");
        if (changeSessionOnLookup) {
            sessionTag = "new-session";
            changeSessionOnLookup = false;
        }
        return track;
    }
}

package app.bitchord.soulseek;

import android.net.Uri;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class DownloadOnlyRoutingTest {
    private static int checks;

    private interface Action {
        void run() throws Exception;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static IOException rejected(Action action) throws Exception {
        checks++;
        try {
            action.run();
        } catch (IOException expected) {
            return expected;
        }
        throw new AssertionError("Expected IOException");
    }

    private static DataSpec spec(String url) {
        return new DataSpec(Uri.parse(url), 17, 64);
    }

    private static final class Downstream implements DataSource {
        final Map<String, List<String>> headers =
                Collections.singletonMap("Content-Type", Collections.singletonList("audio/ogg"));
        int opens, reads, closes, listeners;
        DataSpec received;
        TransferListener listener;
        Uri uri;
        IOException openError, closeError;
        RuntimeException uncheckedOpenError;

        public void addTransferListener(TransferListener value) {
            listeners++;
            listener = value;
        }

        public long open(DataSpec value) throws IOException {
            opens++;
            received = value;
            uri = value.uri;
            if (openError != null) throw openError;
            if (uncheckedOpenError != null) throw uncheckedOpenError;
            return 123;
        }

        public int read(byte[] buffer, int offset, int length) {
            reads++;
            int count = Math.min(3, length);
            for (int i = 0; i < count; i++) buffer[offset + i] = (byte) (i + 1);
            return count;
        }

        public Uri getUri() {
            return uri;
        }

        public Map<String, List<String>> getResponseHeaders() {
            return headers;
        }

        public void close() throws IOException {
            closes++;
            uri = null;
            if (closeError != null) throw closeError;
        }
    }

    private static void reservedPlaybackNeverTouchesTransfers() throws Exception {
        Downstream downstream = new Downstream();
        DataSource routing = SoulseekDataSource.wrap(downstream);
        for (String url :
                new String[] {
                    "https://soulseek.bitchord.invalid/session/audio/abc",
                    "https://SOULSEEK.BITCHORD.INVALID/session/audio/abc",
                    "http://soulseek.bitchord.invalid/session/audio/abc",
                    "https://soulseek.bitchord.invalid:443/session/audio/abc",
                    "https://user:secret@soulseek.bitchord.invalid/session/audio/abc?token=value#part",
                    "//soulseek.bitchord.invalid/session/audio/abc"
                }) {
            check(SoulseekAddon.reserved(url), "Fixture must target reserved host");
            IOException error = rejected(() -> routing.open(spec(url)));
            check(
                    error.getMessage()
                            .equals("Soulseek is download-only. Use Download to save this track."),
                    "Playback failure directs the user to Download");
            check(
                    downstream.opens == 0 && downstream.reads == 0,
                    "Reserved identifier never reaches downstream");
            check(routing.getUri() == null, "Rejected identifier is not retained");
            check(routing.getResponseHeaders().isEmpty(), "Rejected source has no headers");
            rejected(() -> routing.read(new byte[8], 0, 8));
            routing.close();
            check(downstream.closes == 0, "Rejected source owns no downstream resource");
        }
        check(SoulseekBridge.accesses == 0, "Playback did not access native download bridge");
        DataSpec normal = spec("https://media.example/audio.ogg");
        check(routing.open(normal) == 123, "Normal playback works after rejection");
        routing.close();
        check(downstream.opens == 1 && downstream.closes == 1, "Wrapper remains reusable");
    }

    private static void normalPlaybackIsTransparent() throws Exception {
        Downstream downstream = new Downstream();
        DataSource routing = SoulseekDataSource.wrap(downstream);
        TransferListener listener = new TransferListener() {};
        routing.addTransferListener(listener);
        check(
                downstream.listeners == 1 && downstream.listener == listener,
                "Transfer listener forwarded exactly once");
        check(routing.getUri() == null, "Unopened wrapper has no URI");
        check(routing.getResponseHeaders().isEmpty(), "Unopened wrapper has no headers");
        DataSpec normal = spec("https://media.example/audio.ogg?access=value");
        check(routing.open(normal) == 123, "Open byte count forwarded");
        check(
                downstream.received.uri.toString().equals(normal.uri.toString())
                        && downstream.received.position == normal.position
                        && downstream.received.length == normal.length,
                "Original URI/range specification forwarded unchanged");
        check(
                routing.getUri().toString().equals(normal.uri.toString()),
                "Active source URI forwarded");
        check(
                routing.getResponseHeaders().equals(downstream.headers),
                "Headers forwarded unchanged");
        byte[] buffer = new byte[7];
        Arrays.fill(buffer, (byte) 9);
        check(routing.read(buffer, 2, 3) == 3, "Read count forwarded");
        check(Arrays.equals(buffer, new byte[] {9, 9, 1, 2, 3, 9, 9}), "Read range preserved");
        rejected(() -> routing.open(normal));
        check(downstream.opens == 1, "Double open does not reopen downstream");
        routing.close();
        check(downstream.closes == 1, "Owned source closed once");
        check(
                routing.getUri() == null && routing.getResponseHeaders().isEmpty(),
                "Close clears visible source state");
        rejected(() -> routing.read(buffer, 0, buffer.length));
        routing.close();
        check(downstream.closes == 1, "Repeated close is harmless");
        routing.open(spec("https://soulseek.bitchord.invalid.example/audio.ogg"));
        check(downstream.opens == 2, "Only the exact reserved hostname is intercepted");
        routing.close();
    }

    private static void failedOpenReleasesResources() throws Exception {
        Downstream downstream = new Downstream();
        DataSource routing = SoulseekDataSource.wrap(downstream);
        IOException primary = new IOException("open failed");
        downstream.openError = primary;
        check(
                rejected(() -> routing.open(spec("https://media.example/a"))) == primary,
                "Original open failure preserved");
        check(downstream.closes == 1, "Failed open closes partially acquired resource");
        check(
                routing.getUri() == null && routing.getResponseHeaders().isEmpty(),
                "Failed open clears state");
        routing.close();
        check(downstream.closes == 1, "Failure cleanup is not repeated");
        downstream.openError = null;
        routing.open(spec("https://media.example/b"));
        routing.close();
        check(
                downstream.opens == 2 && downstream.closes == 2,
                "Wrapper recovers from open failure");

        downstream.openError = new IOException("second open failure");
        downstream.closeError = new IOException("cleanup failure");
        IOException error = rejected(() -> routing.open(spec("https://media.example/c")));
        check(error == downstream.openError, "Cleanup failure does not replace primary error");
        check(
                error.getSuppressed().length == 1
                        && error.getSuppressed()[0] == downstream.closeError,
                "Cleanup failure is retained as suppressed evidence");
        check(routing.getUri() == null, "Cleanup failure cannot retain active state");

        downstream.openError = null;
        downstream.closeError = null;
        downstream.uncheckedOpenError = new IllegalStateException("invalid state");
        checks++;
        try {
            routing.open(spec("https://media.example/d"));
            throw new AssertionError("Unchecked failure expected");
        } catch (IllegalStateException expected) {
            check(expected == downstream.uncheckedOpenError, "Unchecked failure preserved");
        }
        check(routing.getUri() == null, "Unchecked open failure clears state");
        check(downstream.closes == 4, "Unchecked open failure closes resource");

        downstream.uncheckedOpenError = null;
        routing.open(spec("https://media.example/e"));
        downstream.closeError = new IOException("close failure");
        check(rejected(routing::close) == downstream.closeError, "Close failure propagated");
        check(
                routing.getUri() == null && routing.getResponseHeaders().isEmpty(),
                "Close failure still clears active state");
        routing.close();
        check(downstream.closes == 5, "Failed close is not repeated");
    }

    public static void main(String[] args) throws Exception {
        reservedPlaybackNeverTouchesTransfers();
        normalPlaybackIsTransparent();
        failedOpenReleasesResources();
        check(SoulseekBridge.accesses == 0, "All routing checks avoid the download engine");
        System.out.println("PASS: " + checks + " download-only routing checks.");
    }
}

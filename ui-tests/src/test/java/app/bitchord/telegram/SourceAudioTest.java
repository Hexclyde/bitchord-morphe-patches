package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.net.Uri;

import androidx.media3.datasource.*;

import com.music.bitchord.playback.PlaybackService;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class SourceAudioTest {
    List<Memory> opened;
    byte[] audio = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    String changed;

    class Memory implements DataSource {
        int position;
        boolean closed;
        Uri uri;

        public void addTransferListener(TransferListener ignored) {}

        public long open(DataSpec spec) {
            position = (int) spec.position;
            uri = changed == null ? spec.uri : Uri.parse(changed);
            return audio.length - position;
        }

        public int read(byte[] b, int o, int n) {
            if (position == audio.length) return -1;
            int count = Math.min(n, audio.length - position);
            System.arraycopy(audio, position, b, o, count);
            position += count;
            return count;
        }

        public Uri getUri() {
            return uri;
        }

        public Map<String, List<String>> getResponseHeaders() {
            return Collections.emptyMap();
        }

        public void close() {
            closed = true;
        }
    }

    @Before
    public void setup() {
        opened = new ArrayList<>();
        PlaybackService.source =
                () -> {
                    Memory next = new Memory();
                    opened.add(next);
                    return next;
                };
    }

    SourceAudio open(String uri) throws Exception {
        return new SourceAudio(new DataSpec(Uri.parse(uri)), System.nanoTime() + 5_000_000_000L);
    }

    @Test
    public void nativeAndNetworkSchemesUseIdenticalRandomAccess() throws Exception {
        for (String scheme :
                new String[] {
                    "bctg://audio/1",
                    "smb://share/a.flac",
                    "content://music/42",
                    "https://host/a.flac"
                })
            try (SourceAudio input = open(scheme)) {
                byte[] b = new byte[3];
                assertEquals(10, input.getSize());
                assertEquals(3, input.readAt(5, b, 0, 3));
                assertArrayEquals(new byte[] {5, 6, 7}, b);
                assertEquals(3, input.readAt(0, b, 0, 3));
                assertArrayEquals(new byte[] {0, 1, 2}, b);
                assertEquals(-1, input.readAt(10, b, 0, 3));
            }
        assertTrue(opened.stream().allMatch(s -> s.closed));
    }

    @Test
    public void independentReadersNeverShareSeekPositions() throws Exception {
        try (SourceAudio a = open("bctg://audio/1");
                SourceAudio b = open("bctg://audio/1")) {
            byte[] out = new byte[1];
            a.readAt(2, out, 0, 1);
            assertEquals(2, out[0]);
            b.readAt(7, out, 0, 1);
            assertEquals(7, out[0]);
            a.readAt(3, out, 0, 1);
            assertEquals(3, out[0]);
        }
    }

    @Test
    public void changedRenditionIsRefusedBeforeMixingBytes() throws Exception {
        try (SourceAudio input = open("https://host/a.flac")) {
            changed = "https://host/different.mp3";
            try {
                input.readAt(5, new byte[1], 0, 1);
                fail();
            } catch (IOException expected) {
            }
        }
    }

    @Test
    public void closedAndExpiredAnalysisCannotKeepFetching() throws Exception {
        SourceAudio input = open("https://host/a");
        input.close();
        try {
            input.readAt(0, new byte[1], 0, 1);
            fail();
        } catch (IOException expected) {
        }
        int count = opened.size();
        try {
            new SourceAudio(new DataSpec(Uri.parse("https://host/a")), 0);
            fail();
        } catch (IOException expected) {
        }
        assertEquals(count, opened.size());
    }
}

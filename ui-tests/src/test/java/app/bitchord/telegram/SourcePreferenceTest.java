package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.net.Uri;

import androidx.media3.datasource.DataSpec;

import com.music.bitchord.data.sources.SourceStream;
import com.music.bitchord.data.sources.TrackMatcher;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class SourcePreferenceTest {
    enum Kind {
        YOUTUBE,
        ADDON,
        LOCAL
    }

    public static class Source {
        Kind kind;

        Source(Kind kind) {
            this.kind = kind;
        }

        public Kind getKind() {
            return kind;
        }
    }

    static class Format {
        String codec = "AAC";
        Integer kbps = 256, bitDepth = null, sampleRateHz = 44100;
    }

    static class Callback {
        Object f$0 = new Format(), f$1 = new TrackMatcher.Target();
    }

    static class Song {
        String videoId;

        Song(String id) {
            videoId = id;
        }
    }

    @Test
    public void enabledAlternativesKeepTheirRelativeOrderAheadOfYouTube() {
        Object youtube = new Source(Kind.YOUTUBE),
                telegram = new Source(Kind.ADDON),
                local = new Source(Kind.LOCAL);
        ArrayList<Object> original = new ArrayList<>(Arrays.asList(youtube, telegram, local));
        assertEquals(Arrays.asList(telegram, local, youtube), SourcePreference.rank(original));
        assertEquals(Arrays.asList(youtube, telegram, local), original);
    }

    @Test
    public void equalKnownFormatCanReplaceYouTubeButDowngradesAreNotNewlyAccepted() {
        Callback callback = new Callback();
        Format candidate = new Format();
        SourceStream stream = new SourceStream(candidate);
        assertEquals(Boolean.TRUE, SourcePreference.equalUpgrade(callback, null, stream));
        candidate.kbps = 128;
        assertNull(SourcePreference.equalUpgrade(callback, null, stream));
        candidate.kbps = 256;
        candidate.codec = "MP3";
        assertNull(SourcePreference.equalUpgrade(callback, null, stream));
        candidate.codec = "AAC";
        candidate.kbps = null;
        assertNull(SourcePreference.equalUpgrade(callback, null, stream));
        assertNull(SourcePreference.equalUpgrade(callback, null, new Object()));
    }

    @Test
    public void swapOffsetsAreScopedConsumedAndExpire() throws Exception {
        java.lang.reflect.Field field = SourcePreference.class.getDeclaredField("pendingOffsets");
        field.setAccessible(true);
        Map<String, double[]> offsets = (Map<String, double[]>) field.get(null);
        offsets.clear();
        offsets.put("a", new double[] {250, System.currentTimeMillis()});
        assertNull(SourcePreference.alignedOffset(null, new Song("a"), new Song("b")));
        assertEquals(
                Long.valueOf(250),
                SourcePreference.alignedOffset(null, new Song("a"), new Song("a")));
        assertNull(SourcePreference.alignedOffset(null, new Song("a"), new Song("a")));
        offsets.put("a", new double[] {250, System.currentTimeMillis() - 61_000});
        assertNull(SourcePreference.alignedOffset(null, new Song("a"), new Song("a")));
    }

    @Test
    public void explicitFilesAndPickedRowsDoNotRequireAYouTubeLookup() throws Exception {
        for (String uri :
                new String[] {
                    "file:///a.flac",
                    "content://music/42",
                    "bitchord://source?s=telegram-native&t=12_34&mix=stereo"
                }) {
            DataSpec request = new DataSpec(Uri.parse(uri)),
                    resolved = new DataSpec(Uri.parse("bctg://audio/12_34"));
            assertSame(resolved, SourcePreference.guard(request, resolved));
        }
    }
}

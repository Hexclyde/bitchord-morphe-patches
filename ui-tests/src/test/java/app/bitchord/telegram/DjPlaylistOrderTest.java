package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.content.Context;

import com.music.bitchord.data.spotify.LocalPlaylistStore;

import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

import java.util.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class DjPlaylistOrderTest {
    private Object track(String id) throws Exception {
        return MixedSongCodec.decode(
                new JSONObject().put("videoId", id).put("title", id).put("artist", "Artist"));
    }

    @Before
    public void setup() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        var field = SoundExtras.class.getDeclaredField("app");
        field.setAccessible(true);
        field.set(null, app);
        LocalPlaylistStore._playlists.setValue(new ArrayList<>());
        MixedPlaylists.init(app);
    }

    @Test
    public void reorderPreservesDuplicatesMetadataAndUnrelatedPlaylists() throws Exception {
        Object first = track("abcdefghijk"), second = track("lmnopqrstuv");
        Object target =
                MixedPlaylists.playlist(
                        "native-local", "Local", Arrays.asList(first, second, first));
        Object unrelated = MixedPlaylists.playlist("other-local", "Other", List.of(second));
        LocalPlaylistStore._playlists.setValue(Arrays.asList(target, unrelated));
        MixedPlaylists.reorder(target, Arrays.asList(second, first, first));
        var result = MixedPlaylists.nativePlaylists();
        assertEquals(Arrays.asList(second, first, first), MixedPlaylists.songs(result.get(0)));
        assertSame(unrelated, result.get(1));
        assertEquals("Local", MixedSongCodec.text(result.get(0), "title"));
    }

    @Test
    public void staleMembershipAndLostOccurrencesCannotOverwriteUserChanges() throws Exception {
        Object first = track("abcdefghijk"), second = track("lmnopqrstuv");
        Object old =
                MixedPlaylists.playlist(
                        "native-local", "Local", Arrays.asList(first, second, first));
        LocalPlaylistStore._playlists.setValue(List.of(old));
        try {
            MixedPlaylists.reorder(old, Arrays.asList(first, second));
            fail("Duplicate occurrence must survive");
        } catch (IllegalArgumentException expected) {
        }
        Object edited = MixedPlaylists.playlist("native-local", "Renamed", List.of(second, first));
        LocalPlaylistStore._playlists.setValue(List.of(edited));
        try {
            MixedPlaylists.reorder(old, Arrays.asList(second, first, first));
            fail("Stale snapshot must not overwrite changes");
        } catch (ConcurrentModificationException expected) {
        }
        assertSame(edited, MixedPlaylists.nativePlaylists().get(0));
    }
}

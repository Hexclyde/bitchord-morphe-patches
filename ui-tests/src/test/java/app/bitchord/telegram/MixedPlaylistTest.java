package app.bitchord.telegram;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;

import com.music.bitchord.data.spotify.LocalPlaylistStore;

import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

import java.util.*;

@RunWith(RobolectricTestRunner.class)
@Config(
        manifest = Config.NONE,
        sdk = 35,
        shadows = MixedPlaylistTest.Installed.class,
        instrumentedPackages = "app.bitchord.telegram.SoundExtras")
@LooperMode(LooperMode.Mode.PAUSED)
public class MixedPlaylistTest {
    @Implements(value = SoundExtras.class, isInAndroidSdk = false)
    public static class Installed {
        @Implementation
        protected static boolean mixedPlaylistsApplied() {
            return true;
        }
    }

    Context app;

    @Before
    public void setup() throws Exception {
        app = RuntimeEnvironment.getApplication();
        for (String n : new String[] {"context", "prefs", "hostPrefs", "changed"}) {
            java.lang.reflect.Field f = MixedPlaylists.class.getDeclaredField(n);
            f.setAccessible(true);
            f.set(null, null);
        }
        java.lang.reflect.Field m = MixedPlaylists.class.getDeclaredField("managed");
        m.setAccessible(true);
        ((Set<?>) m.get(null)).clear();
        app.getSharedPreferences(MixedPlaylists.PREFERENCES, 0).edit().clear().commit();
        app.getSharedPreferences(MixedPlaylists.HOST_PREFERENCES, 0).edit().clear().commit();
        LocalPlaylistStore._playlists.setValue(new ArrayList<>());
        MixedPlaylists.init(app);
    }

    Object stream(String id) throws Exception {
        return MixedSongCodec.decode(
                new JSONObject()
                        .put("videoId", id)
                        .put("title", "Track")
                        .put("artist", "Artist")
                        .put("sourceQuality", "LOSSLESS"));
    }

    String id(Object p) {
        return MixedSongCodec.text(p, "id");
    }

    @Test
    public void filesDownloadsAndStreamsSurviveRestartTogether() throws Exception {
        Object local = MixedSongCodec.file("content://music/local", "Local"),
                download = MixedSongCodec.file("content://downloads/42", "Downloaded"),
                yt = stream("dQw4w9WgXcQ"),
                addon = stream("src:custom::file-7");
        String id = MixedPlaylists.create("Mixed", local);
        for (Object song : new Object[] {download, yt, addon})
            MixedPlaylists.membership(song, Collections.singleton(id));
        assertEquals(4, MixedPlaylists.songs(MixedPlaylists.nativePlaylists().get(0)).size());
        LocalPlaylistStore._playlists.setValue(new ArrayList<>());
        MixedPlaylists.restore();
        List<Object> songs = MixedPlaylists.songs(MixedPlaylists.nativePlaylists().get(0));
        assertEquals(4, songs.size());
        assertEquals("file:content://music/local", MixedSongCodec.key(songs.get(0)));
        assertEquals("file:content://downloads/42", MixedSongCodec.key(songs.get(1)));
        assertEquals("stream:dQw4w9WgXcQ", MixedSongCodec.key(songs.get(2)));
        assertEquals("stream:src:custom::file-7", MixedSongCodec.key(songs.get(3)));
    }

    @Test
    public void existingLocalPlaylistIsPromotedWithoutLosingItsTracks() throws Exception {
        Object original = stream("dQw4w9WgXcQ");
        LocalPlaylistStore._playlists.setValue(
                new ArrayList<>(
                        Arrays.asList(
                                MixedPlaylists.playlist(
                                        "sp_local_existing",
                                        "Imported",
                                        new ArrayList<>(Arrays.asList(original))))));
        Object local = MixedSongCodec.file("content://music/9", "Local");
        MixedPlaylists.membership(local, Collections.singleton("sp_local_existing"));
        MixedPlaylists.restore();
        assertEquals(2, MixedPlaylists.songs(MixedPlaylists.nativePlaylists().get(0)).size());
        MixedPlaylists.membership(local, Collections.emptySet());
        assertEquals(1, MixedPlaylists.songs(MixedPlaylists.nativePlaylists().get(0)).size());
    }

    @Test
    public void duplicatesAreNotAddedAndUncheckingRemovesMembership() throws Exception {
        Object song = stream("src:custom::file-1");
        String id = MixedPlaylists.create("Source", song);
        MixedPlaylists.membership(song, Collections.singleton(id));
        assertEquals(1, MixedPlaylists.songs(MixedPlaylists.nativePlaylists().get(0)).size());
        MixedPlaylists.membership(song, Collections.emptySet());
        MixedPlaylists.restore();
        assertEquals(0, MixedPlaylists.songs(MixedPlaylists.nativePlaylists().get(0)).size());
    }

    @Test
    public void nativeRenameAndDeleteUpdateTheOwnedSnapshot() throws Exception {
        String id =
                MixedPlaylists.create("Before", MixedSongCodec.file("content://music/1", "Local"));
        Object old = MixedPlaylists.nativePlaylists().get(0);
        LocalPlaylistStore._playlists.setValue(
                new ArrayList<>(
                        Arrays.asList(
                                MixedPlaylists.playlist(id, "After", MixedPlaylists.songs(old)))));
        app.getSharedPreferences(MixedPlaylists.HOST_PREFERENCES, 0)
                .edit()
                .putString("playlists_json", "renamed")
                .commit();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        MixedPlaylists.restore();
        assertEquals(
                "After", MixedSongCodec.text(MixedPlaylists.nativePlaylists().get(0), "title"));
        LocalPlaylistStore._playlists.setValue(new ArrayList<>());
        app.getSharedPreferences(MixedPlaylists.HOST_PREFERENCES, 0)
                .edit()
                .putString("playlists_json", "deleted")
                .commit();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        MixedPlaylists.restore();
        assertEquals(0, MixedPlaylists.nativePlaylists().size());
    }

    @Test
    public void cachedCallbackHonorsLaterDisable() throws Exception {
        android.app.Activity activity =
                Robolectric.buildActivity(android.app.Activity.class).setup().get();
        final int[] called = {0};
        kotlin.jvm.functions.Function0<kotlin.Unit> original =
                () -> {
                    called[0]++;
                    return kotlin.Unit.INSTANCE;
                };
        MixedPlaylists.setEnabled(true);
        kotlin.jvm.functions.Function0<kotlin.Unit> wrapped =
                MixedPlaylists.playlistAction(stream("dQw4w9WgXcQ"), original);
        MixedPlaylists.setEnabled(false);
        wrapped.invoke();
        assertEquals(1, called[0]);
        activity.finish();
    }

    @Test
    public void disabledFeaturePreservesOriginalCallbacksAndSavedPlaylists() throws Exception {
        String id = MixedPlaylists.create("Kept", stream("dQw4w9WgXcQ"));
        kotlin.jvm.functions.Function0<kotlin.Unit> original = () -> kotlin.Unit.INSTANCE;
        assertSame(original, MixedPlaylists.playlistAction(stream("dQw4w9WgXcQ"), original));
        MixedPlaylists.setEnabled(true);
        assertNotSame(original, MixedPlaylists.playlistAction(stream("dQw4w9WgXcQ"), original));
        MixedPlaylists.setEnabled(false);
        MixedPlaylists.restore();
        assertEquals(id, id(MixedPlaylists.nativePlaylists().get(0)));
    }
}

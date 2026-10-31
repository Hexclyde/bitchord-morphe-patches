import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;

import java.io.*;
import java.util.*;

/** Actual menu hooks, native model/flow ABI and standalone runtime isolation. */
public class VerifyMixedPlaylistsApk {
    static int count;

    static void check(boolean value, String why) {
        count++;
        if (!value) throw new AssertionError(why);
    }

    static int calls(ClassDef type, String name) {
        int count = 0;
        for (Method m : type.getMethods())
            if (m.getImplementation() != null)
                for (Instruction i : m.getImplementation().getInstructions())
                    if (i instanceof ReferenceInstruction r
                            && r.getReference() instanceof MethodReference mr
                            && mr.getDefiningClass()
                                    .equals("Lapp/bitchord/telegram/MixedPlaylists;")
                            && mr.getName().equals(name)) count++;
        return count;
    }

    static boolean field(ClassDef type, String name) {
        for (Field f : type.getFields()) if (f.getName().equals(name)) return true;
        return false;
    }

    public static void main(String[] args) throws Exception {
        Map<String, ClassDef> types = new HashMap<>();
        var dex = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.getDefault());
        for (String entry : dex.getDexEntryNames())
            for (ClassDef type : dex.getEntry(entry).getDexFile().getClasses())
                types.put(type.getType(), type);
        String base = "Lapp/bitchord/telegram/";
        check(types.containsKey(base + "MixedPlaylists;"), "Mixed playlist runtime present");
        check(types.containsKey(base + "MixedSongCodec;"), "Complete Song metadata codec present");
        check(
                calls(
                                types.get("Lcom/music/bitchord/ui/components/SongActionsMenuKt;"),
                                "playlistAction")
                        == 1,
                "Song menu wraps Add to playlist exactly once");
        check(
                calls(
                                types.get("Lcom/music/bitchord/ui/screens/LibraryScreenKt;"),
                                "newPlaylistAction")
                        == 1,
                "Library wraps New playlist exactly once");
        String guest = "Lapp/bitchord/telegram/GuestPlaylists;";
        Map<String, Integer> hooks = new HashMap<>();
        for (ClassDef type : types.values())
            for (Method method : type.getMethods())
                if (method.getImplementation() != null)
                    for (Instruction step : method.getImplementation().getInstructions())
                        if (step instanceof ReferenceInstruction ref
                                && ref.getReference() instanceof MethodReference call
                                && call.getDefiningClass().equals(guest))
                            hooks.merge(call.getName(), 1, Integer::sum);
        for (String name : List.of("library", "detail", "service", "release"))
            check(hooks.getOrDefault(name, 0) >= 1, "Guest playlist hook: " + name);
        check(
                types.containsKey("Lapp/bitchord/telegram/PlaylistToolsActivity;"),
                "Native playlist tools retained");
        check(
                types.containsKey("Lapp/bitchord/telegram/PlaylistImport;"),
                "Native playlist importer retained");
        ClassDef page = types.get("Lcom/music/bitchord/data/YtMusicRepository$SongPage;");
        check(
                field(page, "songs") && field(page, "continuation"),
                "Complete playlist paging ABI retained");
        check(
                field(types.get("Lcom/music/bitchord/playback/MusicLink;"), "_pending"),
                "Native pending page relay retained");
        int guards = 0;
        for (ClassDef type : types.values()) guards += calls(type, "allowPlaylist");
        check(guards == 1, "Only playlist menu eligibility is extended");
        ClassDef song = types.get("Lcom/music/bitchord/data/model/Song;");
        String[] fields = {
            "videoId",
            "title",
            "artist",
            "thumbnailUrl",
            "durationText",
            "artistId",
            "artists",
            "albumId",
            "albumName",
            "isVideo",
            "isVideoOrigin",
            "setVideoId",
            "queueTier",
            "queueEntryId",
            "radioName",
            "localUri",
            "downloadFormat",
            "localPath",
            "localDateAddedSeconds",
            "localDateModifiedSeconds",
            "trackNumber",
            "sourceQuality",
            "sourceHiRes",
            "isExplicit",
            "playbackSource",
            "playbackSourceType",
            "playbackSourceId",
            "feedbackToken"
        };
        for (String f : fields) check(field(song, f), "Native Song field " + f);
        boolean constructor = false;
        for (Method m : song.getMethods())
            if (m.getName().equals("<init>")
                    && m.getParameterTypes().size() == 28
                    && m.getParameterTypes()
                            .get(12)
                            .equals("Lcom/music/bitchord/data/model/QueueTier;"))
                constructor = true;
        check(constructor, "Exact primary Song constructor");
        ClassDef playlists = types.get("Lcom/music/bitchord/data/spotify/LocalPlaylistStore;");
        check(field(playlists, "_playlists"), "Host playlist state is retained");
        ClassDef concrete = types.get("Lkotlinx/coroutines/flow/StateFlowImpl;");
        check(
                concrete != null && (concrete.getAccessFlags() & 1) == 1,
                "Concrete flow class is public for reflection");
        for (String name : List.of("getValue", "setValue", "compareAndSet")) {
            boolean found = false;
            for (Method method : concrete.getMethods())
                if (method.getName().equals(name) && (method.getAccessFlags() & 1) == 1)
                    found = true;
            check(found, "Concrete optimized playlist flow API retained: " + name);
        }
        boolean playlistConstructor = false;
        for (Method method :
                types.get("Lcom/music/bitchord/data/spotify/LocalPlaylist;").getMethods())
            if (method.getName().equals("<init>")
                    && method.getParameterTypes()
                            .equals(
                                    List.of(
                                            "Ljava/lang/String;",
                                            "Ljava/lang/String;",
                                            "Ljava/util/List;"))) playlistConstructor = true;
        check(playlistConstructor, "Exact native playlist constructor");
        ClassDef playlist = types.get("Lcom/music/bitchord/data/spotify/LocalPlaylist;");
        for (String f : new String[] {"id", "title", "songs"})
            check(field(playlist, f), "Native playlist field " + f);
        check(
                field(types.get("Lcom/music/bitchord/data/model/ArtistRef;"), "name"),
                "Artist credit name preserved");
        check(
                field(types.get("Lcom/music/bitchord/data/model/ArtistRef;"), "browseId"),
                "Artist credit navigation preserved");
        boolean marker = false;
        for (Method m : types.get(base + "SoundExtras;").getMethods())
            if (m.getName().equals("mixedPlaylistsApplied"))
                for (Instruction i : m.getImplementation().getInstructions())
                    if (i instanceof NarrowLiteralInstruction n && n.getNarrowLiteral() == 1)
                        marker = true;
        check(marker, "Selected patch enables installed marker");
        boolean standalone = args.length > 1 && args[1].equals("standalone");
        if (standalone) {
            check(
                    !types.containsKey(base + "TelegramBridge;"),
                    "No Telegram account implementation in standalone patch");
            check(
                    !types.containsKey("Lorg/drinkless/tdlib/Client;"),
                    "No TDLib in standalone playlist patch");
            check(
                    !types.containsKey("Lapp/bitchord/soulseek/SoulseekBridge;"),
                    "No Soulseek account required by playlist patch");
        }
        System.out.println("PASS: " + count + " mixed-source playlist APK/ABI checks");
    }
}

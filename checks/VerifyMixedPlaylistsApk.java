import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import java.util.*;import java.io.*;
/** Actual menu hooks, native model/flow ABI and standalone runtime isolation. */
public class VerifyMixedPlaylistsApk {
 static int count;static void check(boolean value,String why){count++;if(!value)throw new AssertionError(why);}
 static int calls(ClassDef type,String name){int count=0;for(Method m:type.getMethods())if(m.getImplementation()!=null)for(Instruction i:m.getImplementation().getInstructions())if(i instanceof ReferenceInstruction r&&r.getReference() instanceof MethodReference mr&&mr.getDefiningClass().equals("Lapp/bitchord/telegram/MixedPlaylists;")&&mr.getName().equals(name))count++;return count;}
 static boolean field(ClassDef type,String name){for(Field f:type.getFields())if(f.getName().equals(name))return true;return false;}
 public static void main(String[]args)throws Exception {
  Map<String,ClassDef> types=new HashMap<>();var dex=DexFileFactory.loadDexContainer(new File(args[0]),Opcodes.getDefault());for(String entry:dex.getDexEntryNames())for(ClassDef type:dex.getEntry(entry).getDexFile().getClasses())types.put(type.getType(),type);
  String base="Lapp/bitchord/telegram/";check(types.containsKey(base+"MixedPlaylists;"),"Mixed playlist runtime present");check(types.containsKey(base+"MixedSongCodec;"),"Complete Song metadata codec present");
  check(calls(types.get("Lcom/music/bitchord/ui/components/SongActionsMenuKt;"),"playlistAction")==1,"Song menu wraps Add to playlist exactly once");
  check(calls(types.get("Lcom/music/bitchord/ui/screens/LibraryScreenKt;"),"newPlaylistAction")==1,"Library wraps New playlist exactly once");
  int guards=0;for(ClassDef type:types.values())guards+=calls(type,"allowPlaylist");check(guards==1,"Only playlist menu eligibility is extended");
  ClassDef song=types.get("Lcom/music/bitchord/data/model/Song;");String[] fields={"videoId","title","artist","thumbnailUrl","durationText","artistId","artists","albumId","albumName","isVideo","isVideoOrigin","setVideoId","queueTier","queueEntryId","radioName","localUri","downloadFormat","localPath","localDateAddedSeconds","localDateModifiedSeconds","trackNumber","sourceQuality","sourceHiRes","isExplicit","playbackSource","playbackSourceType","playbackSourceId","feedbackToken"};
  for(String f:fields)check(field(song,f),"Native Song field "+f);
  boolean constructor=false;for(Method m:song.getMethods())if(m.getName().equals("<init>")&&m.getParameterTypes().size()==28&&m.getParameterTypes().get(12).equals("Lcom/music/bitchord/data/model/QueueTier;"))constructor=true;check(constructor,"Exact primary Song constructor");
  ClassDef playlists=types.get("Lcom/music/bitchord/data/spotify/LocalPlaylistStore;");check(field(playlists,"_playlists"),"Host playlist state is retained");
  ClassDef playlist=types.get("Lcom/music/bitchord/data/spotify/LocalPlaylist;");for(String f:new String[]{"id","title","songs"})check(field(playlist,f),"Native playlist field "+f);
  check(field(types.get("Lcom/music/bitchord/data/model/ArtistRef;"),"name"),"Artist credit name preserved");check(field(types.get("Lcom/music/bitchord/data/model/ArtistRef;"),"browseId"),"Artist credit navigation preserved");
  boolean marker=false;for(Method m:types.get(base+"SoundExtras;").getMethods())if(m.getName().equals("mixedPlaylistsApplied"))for(Instruction i:m.getImplementation().getInstructions())if(i instanceof NarrowLiteralInstruction n&&n.getNarrowLiteral()==1)marker=true;check(marker,"Selected patch enables installed marker");
  boolean standalone=args.length>1&&args[1].equals("standalone");if(standalone){check(!types.containsKey(base+"TelegramBridge;"),"No Telegram account implementation in standalone patch");check(!types.containsKey("Lorg/drinkless/tdlib/Client;"),"No TDLib in standalone playlist patch");check(!types.containsKey("Lapp/bitchord/soulseek/SoulseekBridge;"),"No Soulseek account required by playlist patch");}
  System.out.println("PASS: "+count+" mixed-source playlist APK/ABI checks");
 }
}

import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import java.io.File;
import java.util.*;
import org.w3c.dom.Element;

/** Check the exact host ABI used by reflection and the explicit-preview boundary. */
public final class VerifyStreamseekApk {
  static int checks;
  static Map<String, ClassDef> types = new HashMap<>();

  static void check(boolean value, String why) {
    checks++;
    if (!value) throw new AssertionError(why);
  }

  static void method(String owner, String name, String result, String... args) {
    ClassDef type = types.get(owner);
    check(type != null, "Host class " + owner);
    boolean found = false;
    for (Method m : type.getMethods())
      if (m.getName().equals(name)
          && m.getReturnType().equals(result)
          && m.getParameterTypes().equals(List.of(args))
          && (m.getAccessFlags() & 1) != 0) found = true;
    check(found, "Public exact ABI " + owner + "->" + name);
  }

  public static void main(String[] args) throws Exception {
    File apk = new File(args[0]);
    boolean selected = args.length < 2 || !args[1].equals("disabled");
    var dex = DexFileFactory.loadDexContainer(apk, Opcodes.getDefault());
    for (String entry : dex.getDexEntryNames())
      for (ClassDef type : dex.getEntry(entry).getDexFile().getClasses())
        types.put(type.getType(), type);
    String base = "Lapp/bitchord/soulseek/";
    ClassDef preview = types.get(base + "SoulseekPreview;");
    check(preview != null, "Preview runtime belongs to Soulseek extension");
    boolean marker = false;
    for (Method m : preview.getMethods())
      if (m.getName().equals("installed"))
        for (Instruction i : m.getImplementation().getInstructions())
          if (i instanceof NarrowLiteralInstruction literal && literal.getNarrowLiteral() == 1)
            marker = true;
    check(marker == selected, "Optional installed marker matches selected patch");
    int activities = 0;
    var nodes = VerifyApk.manifest(apk).getElementsByTagName("activity");
    for (int i = 0; i < nodes.getLength(); i++) {
      Element activity = (Element) nodes.item(i);
      if (!activity
          .getAttributeNS(VerifyApk.NS, "name")
          .equals("app.bitchord.soulseek.SoulseekPreviewActivity")) continue;
      activities++;
      check(
          activity.getAttributeNS(VerifyApk.NS, "exported").equals("false"),
          "Preview activity private");
      check(
          activity.getElementsByTagName("intent-filter").getLength() == 0,
          "No external preview intents");
    }
    check(activities == (selected ? 1 : 0), "Optional activity declaration");
    for (String name : List.of("Fingerprint", "RecordingMatch"))
      check(
          types.containsKey("Lapp/bitchord/telegram/" + name + ";"),
          "Source-independent evidence runtime " + name);
    Set<String> referenceFields = new HashSet<>();
    for (Method m : types.get(base + "SoulseekPreviewActivity;").getMethods()) {
      if (m.getImplementation() == null) continue;
      for (Instruction i : m.getImplementation().getInstructions())
        if (i instanceof ReferenceInstruction r && r.getReference() instanceof FieldReference f
            && f.getDefiningClass().equals("Lapp/bitchord/telegram/RecordingMatch$Reference;"))
          referenceFields.add(f.getName());
    }
    check(referenceFields.containsAll(List.of("name", "seconds")),
        "Reference labels read runtime fields instead of inlined compile-only values");
    method(
        "Landroidx/media3/exoplayer/ExoPlayer$Builder;",
        "<init>",
        "V",
        "Landroid/content/Context;");
    method(
        "Landroidx/media3/exoplayer/ExoPlayer$Builder;",
        "setMediaSourceFactory",
        "V",
        "Landroidx/media3/exoplayer/source/MediaSource$Factory;");
    method(
        "Landroidx/media3/exoplayer/ExoPlayer$Builder;",
        "build",
        "Landroidx/media3/exoplayer/ExoPlayerImpl;");
    for (String field : List.of("handleAudioFocus", "handleAudioBecomingNoisy")) {
      boolean found = false;
      for (Field f : types.get("Landroidx/media3/exoplayer/ExoPlayer$Builder;").getFields())
        if (f.getName().equals(field) && f.getType().equals("Z") && (f.getAccessFlags() & 1) != 0)
          found = true;
      check(found, "Public host focus field " + field);
    }
    method(
        "Landroidx/media3/exoplayer/source/ProgressiveMediaSource$Factory;",
        "<init>",
        "V",
        "Landroidx/media3/datasource/DataSource$Factory;",
        "Landroidx/media3/extractor/ExtractorsFactory;");
    method("Landroidx/media3/extractor/DefaultExtractorsFactory;", "<init>", "V");
    method(
        "Landroidx/media3/common/MediaItem;",
        "fromUri",
        "Landroidx/media3/common/MediaItem;",
        "Ljava/lang/String;");
    method(
        "Landroidx/media3/common/Player;",
        "setMediaItem",
        "V",
        "Landroidx/media3/common/MediaItem;");
    method("Landroidx/media3/common/Player;", "setPlayWhenReady", "V", "Z");
    method("Landroidx/media3/common/Player;", "prepare", "V");
    method("Landroidx/media3/exoplayer/ExoPlayer;", "release", "V");
    method(
        "Landroidx/media3/exoplayer/ExoPlayerImpl;",
        "getPlayerError",
        "Landroidx/media3/common/PlaybackException;");
    ClassDef routing = types.get(base + "SoulseekDataSource$Routing;");
    check(routing != null, "Ordinary Soulseek source guard retained");
    for (Method m : routing.getMethods())
      if (m.getImplementation() != null)
        for (Instruction i : m.getImplementation().getInstructions())
          if (i instanceof ReferenceInstruction r
              && r.getReference() instanceof MethodReference call)
            check(
                !call.getName().equals("openSelected"),
                "Ordinary router cannot start a peer preview");
    if (args.length > 1 && args[1].equals("standalone")) {
      check(
          !types.containsKey("Lapp/bitchord/telegram/TelegramBridge;"),
          "Preview needs no Telegram account implementation");
      check(!types.containsKey("Lorg/drinkless/tdlib/Client;"), "Preview needs no TDLib");
    }
    System.out.println(
        "PASS: " + checks + " Streamseek optional patch, host ABI and preview isolation checks");
  }
}

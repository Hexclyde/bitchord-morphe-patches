import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.instruction.*;

import org.w3c.dom.*;

import java.io.*;
import java.util.*;

import javax.xml.parsers.DocumentBuilderFactory;

/** Inspect the installed settings entry and UI-only filtering without changing the registry. */
public final class VerifyPatchUiApk {
    static int checks;

    static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        File apk = new File(args[0]);
        boolean telegram = Boolean.parseBoolean(args[1]);
        boolean downloads = Boolean.parseBoolean(args[2]);
        boolean extraTweaks = args.length > 3 && Boolean.parseBoolean(args[3]);
        boolean runtime = telegram || downloads || extraTweaks;
        boolean enabled = downloads || extraTweaks;
        var dex = DexFileFactory.loadDexContainer(apk, Opcodes.getDefault());
        Map<String, ClassDef> classes = new HashMap<>();
        for (String entry : dex.getDexEntryNames())
            for (ClassDef cls : dex.getEntry(entry).getDexFile().getClasses())
                check(classes.put(cls.getType(), cls) == null, "Unique class " + cls.getType());
        String base = "Lapp/bitchord/telegram/";
        String downloadNav = "Lapp/bitchord/soulseek/DownloadNavigation;";
        check(
                classes.containsKey(downloadNav) == downloads,
                "Downloads navigation runtime exists only with the download addon");
        for (String owner : List.of("FloatingBottomBarKt", "GlassNavBarKt")) {
            int navigationHooks = 0;
            for (Method method :
                    classes.get("Lcom/music/bitchord/ui/components/" + owner + ";").getMethods()) {
                if (method.getImplementation() == null) continue;
                for (Instruction instruction : method.getImplementation().getInstructions()) {
                    if (!(instruction instanceof ReferenceInstruction)) continue;
                    Reference reference = ((ReferenceInstruction) instruction).getReference();
                    if (reference instanceof MethodReference
                            && ((MethodReference) reference).getDefiningClass().equals(downloadNav)
                            && ((MethodReference) reference).getName().equals("tabs"))
                        navigationHooks++;
                }
            }
            check(
                    navigationHooks == (downloads ? 1 : 0),
                    "Downloads icon is installed only with download addon: " + owner);
        }
        check(
                classes.containsKey(base + "ExtrasActivity;") == runtime,
                "Shared page matches selection");
        check(
                classes.containsKey(base + "TelegramBridge;") == telegram,
                "Telegram implementation matches selection");
        check(
                classes.containsKey("Lorg/drinkless/tdlib/Client;") == telegram,
                "No TDLib in download-only selection");
        int entries = 0, starts = 0, filters = 0;
        boolean title = false;
        Set<String> common =
                Set.of(
                        "ExtrasActivity",
                        "ExtrasUi",
                        "HostSettingsRow",
                        "SoundExtras",
                        "AutomixAccess",
                        "SourceAudio",
                        "DspAnalysis",
                        "SourceAutomix",
                        "SourcePreference",
                        "RecordingIdentity",
                        "TelegramStyle",
                        "TelegramSwitch",
                        "Strings",
                        "PatchStrings",
                        "CapturePolicy",
                        "DiagnosticRecord",
                        "DiagnosticStore",
                        "DiagnosticArchive",
                        "DiagnosticFiles",
                        "Diagnostics",
                        "DiagnosticsActivity",
                        "LoginNavigation",
                        "YouTubePolicy",
                        "SourceFallback",
                        "NativeSourceConfig",
                        "Translations",
                        "HostLog",
                        "VocalCut",
                        "SeparatedSing",
                        "SeparationModel",
                        "SingOutput",
                        "KaraokeLyrics",
                        "KaraokeCapsule",
                        "PatchSources",
                        "Outputs",
                        "SleepFade",
                        "HeadphoneEq",
                        "OutputEq",
                        "KeyTempo",
                        "DjMode",
                        "DjOrder",
                        "DjPlaylists",
                        "DownloadChoice",
                        "PlayerDownload",
                        "PlayerDownloadHost",
                        "EffectsApps",
                        "BitPerfect",
                        "VideoSwitch",
                        "PlayerIcons",
                        "DownloadSelection",
                        "DownloadFolder",
                        "StorageSense",
                        "StorageSenseActive",
                        "StorageSenseActivity",
                        "StorageSenseAuto",
                        "StorageSenseEngine",
                        "StorageSenseHostCache",
                        "StorageSensePatchCaches",
                        "StorageSenseSaved",
                        "StorageSenseScanner",
                        "DownloadsSettings",
                        "DownloadsSettingsActivity",
                        "DownloadsSettingsUi",
                        "SmartPlaylistActivity",
                        "TelegramGlyph",
                        "SmartPlaylistService",
                        "SmartPlaylistStore",
                        "SmartPlaylistMatcher",
                        "LocalPlaylist",
                        "LocalPlaylistEngine",
                        "LocalPlaylistLexicon",
                        "LocalPlaylistLibrary",
                        "LocalPlaylistTags",
                        "DownloadControls",
                        "AudioOrigin",
                        "AudioOriginHost",
                        "AudioOriginSaved",
                        "FlacMetadata",
                        "SourceFirst",
                        "SourcePreparation",
                        "TelegramMediaChanges",
                        "AudioFocus",
                        "SteadyPlayback",
                        "SmartDownloads",
                        "SmartDownloadsJob",
                        "SmartDownloadSources",
                        "SmartDownloadTransfer",
                        "MixedPlaylists",
                        "PlaylistModal",
                        "PlaylistRows",
                        "GuestPlaylists",
                        "ImportedPlaylist",
                        "PlaylistImport",
                        "PlaylistSyncPlan",
                        "PlaylistSyncEngine",
                        "PlaylistSyncActivity",
                        "PlaylistAuthActivity",
                        "SyncSecrets",
                        "SyncHttp",
                        "SpotifySyncAuth",
                        "SpotifyPlaylistApi",
                        "YouTubePlaylistApi",
                        "ImportConfidence",
                        "PlaylistImportActivity",
                        "PlaylistToolsActivity",
                        "TransitionEdit",
                        "MixTools",
                        "DeckMeter",
                        "TransitionWaveform",
                        "TransitionWaveCache",
                        "TransitionPreview",
                        "TransitionPairSelection",
                        "TransitionAnalysis",
                        "TransitionStore",
                        "TransitionRuntime",
                        "TransitionEditorActivity",
                        "MixedSongCodec",
                        "FlacAddon",
                        "Fingerprint",
                        "RecordingMatch");
        for (ClassDef cls : classes.values()) {
            String simple =
                    cls.getType().startsWith(base)
                            ? cls.getType()
                                    .substring(base.length())
                                    .replace(";", "")
                                    .split("\\$", 2)[0]
                            : "";
            for (Method method : cls.getMethods()) {
                if (method.getImplementation() == null) continue;
                List<Instruction> code = new ArrayList<>();
                method.getImplementation().getInstructions().forEach(code::add);
                for (int index = 0; index < code.size(); index++) {
                    Instruction instruction = code.get(index);
                    if (!(instruction instanceof ReferenceInstruction reference)) continue;
                    if (reference.getReference() instanceof StringReference text
                            && cls.getType().equals(base + "ExtrasActivity;")
                            && text.getString().equals("Patches & tweaks")) title = true;
                    if (!(reference.getReference() instanceof MethodReference call)) continue;
                    if (call.getDefiningClass().equals(base + "ExtrasUi;")
                            && call.getName().equals("tweaksSection")) {
                        entries++;
                        check(
                                cls.getType()
                                                .equals(
                                                        "Lcom/music/bitchord/ui/screens/SettingsSheetKt;")
                                        && method.getName().startsWith("SettingsScreen-"),
                                "Entry uses BitChord Settings screen");
                        MethodReference anchor =
                                index > 0
                                                && code.get(index - 1)
                                                        instanceof ReferenceInstruction before
                                                && before.getReference()
                                                        instanceof MethodReference previous
                                        ? previous
                                        : null;
                        check(
                                anchor != null
                                        && anchor.getDefiningClass().equals(cls.getType())
                                        && (anchor.getName().startsWith("SearchableSettingsGroup-")
                                                || anchor.getName().equals("SettingsCategoryList")),
                                "Entry follows the supported main settings anchor");
                        List<Integer> arguments = registers(code.get(index - 1));
                        int expectedComposer =
                                arguments.get(
                                        anchor.getName().equals("SettingsCategoryList") ? 1 : 4);
                        check(
                                registers(instruction).equals(List.of(expectedComposer)),
                                "Settings entry receives the live host composer");
                    }
                    if (call.getDefiningClass().equals(base + "ExtrasUi;")
                            && call.getName().equals("start")) starts++;
                    if (call.getDefiningClass().equals(base + "PatchSources;")
                            && call.getName().equals("visibleState")) {
                        filters++;
                        check(
                                cls.getType().startsWith("Lcom/music/bitchord/ui/screens/")
                                        && method.getName().equals("SourcesScreen"),
                                "Filtering applies only to collected Sources UI state");
                        check(index >= 2, "Source state filter follows collection");
                        Instruction collect = code.get(index - 2);
                        check(
                                collect instanceof ReferenceInstruction collected
                                        && collected.getReference()
                                                instanceof MethodReference callTo
                                        && callTo.getName().equals("collectAsStateWithLifecycle")
                                        && callTo.getReturnType()
                                                .equals("Landroidx/compose/runtime/MutableState;")
                                        && code.get(index - 1).getOpcode()
                                                == Opcode.MOVE_RESULT_OBJECT,
                                "Filter wraps the collected Sources state");
                        check(
                                registers(instruction)
                                        .equals(
                                                List.of(
                                                        ((OneRegisterInstruction)
                                                                        code.get(index - 1))
                                                                .getRegisterA())),
                                "Source filter receives the collected state register");
                    }
                    if (common.contains(simple)) {
                        check(
                                !call.getDefiningClass().startsWith("Lapp/bitchord/soulseek/")
                                        && !call.getDefiningClass().startsWith("Lorg/drinkless/"),
                                "Shared settings has no optional download or TDLib dependency");
                        if (call.getDefiningClass().startsWith(base)) {
                            String owner =
                                    call.getDefiningClass()
                                            .substring(base.length())
                                            .replace(";", "")
                                            .split("\\$", 2)[0];
                            check(
                                    common.contains(owner),
                                    "Shared settings references only shared helpers: " + call);
                        }
                    }
                }
            }
        }
        check(entries == (enabled ? 1 : 0), "Exactly one Patches & tweaks settings entry");
        check(starts == (enabled ? 1 : 0), "Exactly one settings initialization hook");
        check(filters == (enabled ? 1 : 0), "Exactly one Sources UI filter");
        if (enabled) {
            verifyTweaksIcon(apk, classes);
            if (Arrays.asList(args).contains("--regressions")) iconRegressions(apk, classes);
        }

        check(
                classes.containsKey(base + "DownloadFolder;") == runtime,
                "Folder runtime follows shared settings selection");
        if (runtime) {
            verifyDownloadFolder(classes);
            if (Arrays.asList(args).contains("--regressions")) folderRegressions(classes);
        }
        check(title == runtime, "Shared page uses renamed title");
        Document manifest = VerifyApk.manifest(apk);
        var activities = manifest.getElementsByTagName("activity");
        int declared = 0;
        for (int i = 0; i < activities.getLength(); i++) {
            Element activity = (Element) activities.item(i);
            if (!activity.getAttributeNS(VerifyApk.NS, "name")
                    .equals("app.bitchord.telegram.ExtrasActivity")) continue;
            declared++;
            check(
                    activity.getAttributeNS(VerifyApk.NS, "exported").equals("false"),
                    "Settings page is private");
            check(
                    activity.getAttributeNS(VerifyApk.NS, "label").equals("Patches & tweaks"),
                    "Renamed activity label");
            check(
                    activity.getElementsByTagName("intent-filter").getLength() == 0,
                    "No extra launcher");
        }
        check(declared == (enabled ? 1 : 0), "Shared settings Activity declared once");
        System.out.println("PASS: " + checks + " shared settings/source-isolation checks");
    }

    static List<Integer> registers(Instruction instruction) {
        if (instruction instanceof RegisterRangeInstruction range) {
            List<Integer> result = new ArrayList<>();
            for (int i = 0; i < range.getRegisterCount(); i++)
                result.add(range.getStartRegister() + i);
            return result;
        }
        if (instruction instanceof FiveRegisterInstruction fixed)
            return List.of(
                            fixed.getRegisterC(),
                            fixed.getRegisterD(),
                            fixed.getRegisterE(),
                            fixed.getRegisterF(),
                            fixed.getRegisterG())
                    .subList(0, fixed.getRegisterCount());
        throw new AssertionError("Expected invoke register encoding");
    }

    static void verifyTweaksIcon(File apk, Map<String, ClassDef> classes) throws Exception {
        String owner = "Lapp/bitchord/telegram/ExtrasUi;";
        ClassDef ui = classes.get(owner);
        Method bridge = null;
        for (Method method : ui.getMethods())
            if (method.getName().equals("hostPainter")) {
                check(bridge == null, "One settings painter bridge");
                bridge = method;
            }
        check(bridge != null && bridge.getImplementation() != null, "Settings icon bridge exists");
        check(
                bridge.getParameterTypes()
                                .equals(List.of("I", "Landroidx/compose/runtime/ComposerImpl;"))
                        && bridge.getReturnType()
                                .equals("Landroidx/compose/ui/graphics/painter/Painter;")
                        && (bridge.getAccessFlags() & 8) != 0,
                "Settings painter bridge preserves its native signature");
        List<Instruction> code = new ArrayList<>();
        bridge.getImplementation().getInstructions().forEach(code::add);
        check(
                code.size() == 3
                        && code.get(0).getOpcode() == Opcode.INVOKE_STATIC_RANGE
                        && code.get(1).getOpcode() == Opcode.MOVE_RESULT_OBJECT
                        && code.get(2).getOpcode() == Opcode.RETURN_OBJECT,
                "Settings icon bridge invokes and returns the native painter");
        MethodReference painter = VerifyApk.call(code.get(0));
        check(
                painter != null
                        && painter.getName().equals("painterResource")
                        && painter.getParameterTypes()
                                .equals(List.of("I", "Landroidx/compose/runtime/ComposerImpl;"))
                        && painter.getReturnType()
                                .equals("Landroidx/compose/ui/graphics/painter/Painter;"),
                "Settings icon uses the qualified native painter signature");
        int resource = bridge.getImplementation().getRegisterCount() - 2;
        check(
                registers(code.get(0)).equals(List.of(resource, resource + 1))
                        && ((OneRegisterInstruction) code.get(1)).getRegisterA() == resource
                        && ((OneRegisterInstruction) code.get(2)).getRegisterA() == resource,
                "Settings icon preserves the resource and composer arguments and returned painter");
        int resolved = 0;
        for (Method method : classes.get(painter.getDefiningClass()).getMethods())
            if (method.getName().equals(painter.getName())
                    && method.getParameterTypes().equals(painter.getParameterTypes())
                    && method.getReturnType().equals(painter.getReturnType())
                    && (method.getAccessFlags() & 8) != 0
                    && method.getImplementation() != null) resolved++;
        check(resolved == 1, "Settings painter target exists exactly once");
        int rows = 0;
        boolean resourceName = false;
        for (Method method : ui.getMethods()) {
            if (method.getImplementation() == null) continue;
            Map<Integer, String> values = new HashMap<>();
            int parameter = method.getImplementation().getRegisterCount();
            for (int i = method.getParameterTypes().size() - 1; i >= 0; i--) {
                String type = method.getParameterTypes().get(i).toString();
                parameter -= type.equals("J") || type.equals("D") ? 2 : 1;
                if (type.equals("Landroidx/compose/runtime/ComposerImpl;"))
                    values.put(parameter, "composer");
            }
            String pending = null;
            for (Instruction instruction : method.getImplementation().getInstructions()) {
                MethodReference call = VerifyApk.call(instruction);
                if (instruction instanceof ReferenceInstruction reference
                        && reference.getReference() instanceof StringReference text
                        && text.getString().equals("patches_tweaks")) resourceName = true;
                if (call != null) {
                    List<Integer> args = registers(instruction);
                    pending = null;
                    if (call.getDefiningClass().equals(owner)
                            && call.getName().equals("tweaksIcon")) pending = "resource";
                    if (call.getDefiningClass().equals(owner)
                            && call.getName().equals("hostPainter")) {
                        check(
                                "resource".equals(values.get(args.get(0)))
                                        && "composer".equals(values.get(args.get(1))),
                                "Settings row resolves its vector with the live composer");
                        pending = "painter";
                    }
                    if (call.getName().equals("call")
                            && call.getDefiningClass()
                                    .equals("Lapp/bitchord/telegram/HostSettingsRow;")) {
                        check(
                                "painter".equals(values.get(args.get(3)))
                                        && "composer".equals(values.get(args.get(5))),
                                "Settings row receives the native icon painter and live composer");
                        rows++;
                    }
                } else if (instruction.getOpcode() == Opcode.MOVE_RESULT_OBJECT
                        || instruction.getOpcode() == Opcode.MOVE_RESULT
                        || instruction.getOpcode() == Opcode.MOVE_RESULT_WIDE) {
                    int destination = ((OneRegisterInstruction) instruction).getRegisterA();
                    if (pending == null) values.remove(destination);
                    else {
                        check(
                                instruction.getOpcode()
                                        == (pending.equals("resource")
                                                ? Opcode.MOVE_RESULT
                                                : Opcode.MOVE_RESULT_OBJECT),
                                "Settings icon result uses its declared scalar or object type");
                        values.put(destination, pending);
                    }
                    pending = null;
                } else if (instruction.getOpcode().name.startsWith("move-object")) {
                    TwoRegisterInstruction move = (TwoRegisterInstruction) instruction;
                    String value = values.get(move.getRegisterB());
                    if (value == null) values.remove(move.getRegisterA());
                    else values.put(move.getRegisterA(), value);
                } else if (instruction.getOpcode().setsRegister()
                        && instruction instanceof OneRegisterInstruction write)
                    values.remove(write.getRegisterA());
            }
        }
        check(rows == 1 && resourceName, "One settings row uses the named tweaks vector");
        try (com.reandroid.apk.ApkModule module = com.reandroid.apk.ApkModule.loadApkFile(apk)) {
            module.getTableBlock();
            var file = module.getResFile("res/drawable/patches_tweaks.xml");
            check(file != null, "Native tweaks drawable is packaged");
            StringWriter text = new StringWriter();
            var xml = com.reandroid.xml.XMLFactory.newSerializer();
            xml.setOutput(text);
            file.readAsXmlDocument().serialize(xml);
            xml.flush();
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Element vector =
                    factory.newDocumentBuilder()
                            .parse(new ByteArrayInputStream(text.toString().getBytes("UTF-8")))
                            .getDocumentElement();
            check(vector.getTagName().equals("vector"), "Settings icon is a native vector");
            for (String dimension : List.of("width", "height"))
                check(
                        Float.parseFloat(
                                        vector.getAttributeNS(VerifyApk.NS, dimension)
                                                .replace("dip", "")
                                                .replace("dp", ""))
                                == 24f,
                        "Settings icon has a 24dp " + dimension);
            check(
                    vector.getElementsByTagName("path").getLength() == 1,
                    "Compact sliders path exists");
            verifyIconPath((Element) vector.getElementsByTagName("path").item(0));
        }
    }

    static void verifyIconPath(Element path) {
        String data = path.getAttributeNS(VerifyApk.NS, "pathData");
        check(
                data.startsWith("M3,5H6") && data.contains("A2,2"),
                "Tweaks vector contains the slider rails and knobs");
        check(
                Float.parseFloat(path.getAttributeNS(VerifyApk.NS, "strokeWidth")) == 1.7f,
                "Tweaks icon has visible consistent line weight");
        String stroke = path.getAttributeNS(VerifyApk.NS, "strokeColor");
        check(
                stroke.equalsIgnoreCase("#ffffffff") || stroke.equalsIgnoreCase("#ffffff"),
                "Tweaks icon has an opaque tintable stroke");
    }

    static void verifyDownloadFolder(Map<String, ClassDef> classes) {
        String helper = "Lapp/bitchord/telegram/DownloadFolder;";
        Map<String, Integer> counts = new HashMap<>();
        Method transfer = null;
        int commit = -1;
        for (ClassDef owner : classes.values()) {
            if (!owner.getType().startsWith("Lcom/music/bitchord/")) continue;
            for (Method method : owner.getMethods()) {
                if (method.getImplementation() == null) continue;
                List<Instruction> code = new ArrayList<>();
                method.getImplementation().getInstructions().forEach(code::add);
                for (int i = 0; i < code.size(); i++) {
                    MethodReference call = VerifyApk.call(code.get(i));
                    if (call == null || !call.getDefiningClass().equals(helper)) continue;
                    counts.merge(call.getName(), 1, Integer::sum);
                    check(
                            (code.get(i).getOpcode() == Opcode.INVOKE_STATIC
                                    || code.get(i).getOpcode() == Opcode.INVOKE_STATIC_RANGE),
                            "Folder bridge is a static invocation");
                    if (call.getName().equals("commit")) {
                        transfer = method;
                        commit = i;
                    }
                }
            }
        }
        for (String name : List.of("custom", "existing", "insert", "open", "commit"))
            check(counts.getOrDefault(name, 0) == 1, "Exactly one native folder bridge: " + name);
        check(
                counts.getOrDefault("delete", 0) == 2,
                "Native interrupted and completed files use document-aware deletion");
        int savedDeletes = 0;
        for (ClassDef owner : classes.values()) {
            if (!owner.getType().startsWith("Lcom/music/bitchord/")) continue;
            for (Method method : owner.getMethods()) {
                if (method.getImplementation() == null) continue;
                List<Instruction> body = new ArrayList<>();
                method.getImplementation().getInstructions().forEach(body::add);
                if (body.stream()
                        .noneMatch(
                                i ->
                                        i instanceof ReferenceInstruction
                                                && ((ReferenceInstruction) i).getReference()
                                                        instanceof StringReference
                                                && ((StringReference)
                                                                ((ReferenceInstruction) i)
                                                                        .getReference())
                                                        .getString()
                                                        .equals("could not delete "))) continue;
                verifySavedDeletion(body);
                savedDeletes++;
            }
        }
        check(savedDeletes == 1, "Unique behavior-qualified saved-file deletion guard");
        check(
                counts.getOrDefault("export", 0) == 2,
                "Lookup and transfer use captured storage policy");
        List<Instruction> code = new ArrayList<>();
        transfer.getImplementation().getInstructions().forEach(code::add);
        MethodReference call = VerifyApk.call(code.get(commit));
        check(
                call.getReturnType().equals("Landroid/net/Uri;"),
                "Folder commit returns the actual provider URI");
        check(
                code.get(commit + 1).getOpcode() == Opcode.MOVE_RESULT_OBJECT,
                "Folder completion captures an object URI");
        List<Integer> destination = registers(code.get(commit));
        int nil = destination.get(3);
        check(
                nil == destination.get(4) && nil != destination.get(1),
                "Folder completion has separate URI and null registers");
        Instruction zero = code.get(commit - 1), clear = code.get(commit + 2);
        check(
                zero.getOpcode() == Opcode.CONST_4
                        && ((OneRegisterInstruction) zero).getRegisterA() == nil
                        && ((NarrowLiteralInstruction) zero).getNarrowLiteral() == 0,
                "Native pending destination clear receives null");
        FieldReference cleared =
                clear instanceof ReferenceInstruction
                                && ((ReferenceInstruction) clear).getReference()
                                        instanceof FieldReference
                        ? (FieldReference) ((ReferenceInstruction) clear).getReference()
                        : null;
        check(
                clear.getOpcode() == Opcode.IPUT_OBJECT
                        && ((TwoRegisterInstruction) clear).getRegisterA() == nil
                        && cleared != null
                        && cleared.getDefiningClass().equals("Lkotlin/jvm/internal/Ref$ObjectRef;")
                        && cleared.getName().equals("element"),
                "Native pending destination is cleared immediately after successful commit");
        int uri = registers(code.get(commit)).get(1);
        check(
                ((OneRegisterInstruction) code.get(commit + 1)).getRegisterA() == uri,
                "Folder commit replaces the native completed URI register");
        int remember = -1;
        for (int i = commit + 2; i < Math.min(code.size(), commit + 10); i++) {
            MethodReference next = VerifyApk.call(code.get(i));
            if (next != null
                    && next.getDefiningClass().equals("Lcom/music/bitchord/download/Downloads;")
                    && next.getName().equals("remember")) {
                remember = i;
                break;
            }
            Instruction instruction = code.get(i);
            check(
                    !(instruction instanceof OneRegisterInstruction)
                            || ((OneRegisterInstruction) instruction).getRegisterA() != uri,
                    "Completed folder URI remains live until native publication");
        }
        check(
                remember >= 0 && registers(code.get(remember)).get(2) == uri,
                "Native saved-file publication receives the completed document URI");
        for (Instruction instruction : code) {
            MethodReference nativeCall = VerifyApk.call(instruction);
            check(
                    nativeCall == null
                            || !nativeCall
                                    .getDefiningClass()
                                    .equals("Landroid/content/ContentResolver;")
                            || !List.of("insert", "openOutputStream", "update")
                                    .contains(nativeCall.getName()),
                    "Progressive destination IO uses the qualified folder adapters");
        }
    }

    static void verifySavedDeletion(List<Instruction> body) {
        int remove = -1;
        List<Integer> forgets = new ArrayList<>();
        for (int i = 0; i < body.size(); i++) {
            MethodReference call = VerifyApk.call(body.get(i));
            if (call == null) continue;
            if (call.getName().equals("delete")
                    && call.getDefiningClass().equals("Lapp/bitchord/telegram/DownloadFolder;")) {
                check(remove < 0, "Saved deletion has one adapter call");
                remove = i;
            }
            check(
                    !(call.getDefiningClass().equals("Landroid/content/ContentResolver;")
                            && call.getName().equals("delete")),
                    "Saved document deletion uses the document adapter");
            if (call.getDefiningClass().equals("Lcom/music/bitchord/download/Downloads;")
                    && call.getName().equals("forget")) forgets.add(i);
        }
        check(
                remove >= 0
                        && forgets.size() == 2
                        && forgets.get(0) < remove
                        && forgets.get(1) > remove,
                "Earlier missing-file cleanup stays separate from explicit deletion");
        int forget = forgets.get(1);
        check(forget >= 5 && forget + 1 < body.size(), "Saved deletion guard sites exist");
        MethodReference unbox = VerifyApk.call(body.get(forget - 3));
        check(
                unbox != null
                        && unbox.getDefiningClass().equals("Ljava/lang/Boolean;")
                        && unbox.getName().equals("booleanValue")
                        && unbox.getParameterTypes().isEmpty()
                        && unbox.getReturnType().equals("Z"),
                "Saved deletion tests its actual Boolean result");
        int result = registers(body.get(forget - 3)).get(0);
        Instruction cast = body.get(forget - 5),
                singleton = body.get(forget - 4),
                move = body.get(forget - 2),
                branch = body.get(forget - 1);
        check(
                cast.getOpcode() == Opcode.CHECK_CAST
                        && ((OneRegisterInstruction) cast).getRegisterA() == result
                        && ((TypeReference) ((ReferenceInstruction) cast).getReference())
                                .getType()
                                .equals("Ljava/lang/Boolean;"),
                "Saved deletion unboxes the native completion value");
        check(
                singleton.getOpcode() == Opcode.SGET_OBJECT
                        && move.getOpcode() == Opcode.MOVE_RESULT
                        && branch.getOpcode() == Opcode.IF_EQZ,
                "Only successful saved deletion can forget the row");
        int scratch = ((OneRegisterInstruction) move).getRegisterA();
        check(
                scratch != result
                        && ((OneRegisterInstruction) singleton).getRegisterA() == scratch
                        && ((OneRegisterInstruction) branch).getRegisterA() == scratch
                        && !registers(body.get(forget)).contains(scratch),
                "Saved deletion guard uses the qualified dead scratch register");
        check(
                List.of(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)
                        .contains(body.get(forget + 1).getOpcode()),
                "Saved deletion has a native return branch");
        Instruction destination = VerifyApk.branchTarget(body, forget - 1);
        check(
                destination == VerifyApk.branchTarget(body, forget + 1)
                        && destination.getOpcode() == Opcode.RETURN_OBJECT
                        && ((OneRegisterInstruction) destination).getRegisterA() == result,
                "Failed deletion returns without forgetting its saved row");
    }

    static void folderRegressions(Map<String, ClassDef> classes) {
        String helper = "Lapp/bitchord/telegram/DownloadFolder;";
        int rejected = 0;
        for (ClassDef owner : classes.values()) {
            if (!owner.getType().startsWith("Lcom/music/bitchord/")) continue;
            for (Method method : owner.getMethods()) {
                if (method.getImplementation() == null) continue;
                List<Instruction> original = new ArrayList<>();
                method.getImplementation().getInstructions().forEach(original::add);
                for (int i = 0; i < original.size(); i++) {
                    MethodReference call = VerifyApk.call(original.get(i));
                    if (call == null || !call.getDefiningClass().equals(helper)) continue;
                    if (call.getName().equals("delete")
                            && original.stream()
                                    .anyMatch(
                                            x ->
                                                    x instanceof ReferenceInstruction
                                                            && ((ReferenceInstruction) x)
                                                                            .getReference()
                                                                    instanceof StringReference
                                                            && ((StringReference)
                                                                            ((ReferenceInstruction)
                                                                                            x)
                                                                                    .getReference())
                                                                    .getString()
                                                                    .equals("could not delete "))) {
                        int forget = -1;
                        for (int j = i + 1; j < original.size(); j++) {
                            MethodReference next = VerifyApk.call(original.get(j));
                            if (next != null
                                    && next.getDefiningClass()
                                            .equals("Lcom/music/bitchord/download/Downloads;")
                                    && next.getName().equals("forget")) forget = j;
                        }
                        check(forget > 0, "Saved deletion negative variant has a final forget");
                        OneRegisterInstruction branch =
                                (OneRegisterInstruction) original.get(forget - 1);
                        List<Instruction> wrong = new ArrayList<>(original);
                        wrong.set(
                                forget - 1,
                                new ImmutableInstruction21t(
                                        Opcode.IF_NEZ,
                                        branch.getRegisterA(),
                                        ((OffsetInstruction) branch).getCodeOffset()));
                        rejectFolder(
                                withCode(classes, method, wrong),
                                "failed deletion forgets saved row");
                        rejected++;
                    }
                    if (List.of("existing", "insert", "open", "delete").contains(call.getName())) {
                        List<Instruction> broken = new ArrayList<>(original);
                        broken.set(i, new ImmutableInstruction10x(Opcode.NOP));
                        rejectFolder(
                                withCode(classes, method, broken), "missing " + call.getName());
                        rejected++;
                    } else if (call.getName().equals("commit")) {
                        List<Instruction> uncleared = new ArrayList<>(original);
                        uncleared.set(i + 2, new ImmutableInstruction10x(Opcode.NOP));
                        rejectFolder(
                                withCode(classes, method, uncleared),
                                "completed pending destination remains live");
                        rejected++;
                        int uri = registers(original.get(i)).get(1);
                        for (Instruction wrong :
                                List.of(
                                        new ImmutableInstruction11x(Opcode.MOVE_RESULT, uri),
                                        new ImmutableInstruction11x(
                                                Opcode.MOVE_RESULT_OBJECT, uri == 0 ? 1 : 0))) {
                            List<Instruction> broken = new ArrayList<>(original);
                            broken.set(i + 1, wrong);
                            rejectFolder(
                                    withCode(classes, method, broken),
                                    "completion URI type or register");
                            rejected++;
                        }
                    }
                }
            }
        }
        check(rejected == 9, "Nine folder bridge negative variants exercised");
    }

    static void rejectFolder(Map<String, ClassDef> classes, String name) {
        boolean rejected = false;
        try {
            verifyDownloadFolder(classes);
        } catch (AssertionError expected) {
            rejected = true;
        }
        check(rejected, "Folder negative variant rejected: " + name);
    }

    static Map<String, ClassDef> withCode(
            Map<String, ClassDef> classes, Method target, List<Instruction> code) {
        ClassDef original = classes.get(target.getDefiningClass());
        List<Method> methods = new ArrayList<>();
        for (Method candidate : original.getMethods())
            methods.add(
                    candidate.equals(target)
                            ? new ImmutableMethod(
                                    target.getDefiningClass(),
                                    target.getName(),
                                    target.getParameters(),
                                    target.getReturnType(),
                                    target.getAccessFlags(),
                                    target.getAnnotations(),
                                    target.getHiddenApiRestrictions(),
                                    new ImmutableMethodImplementation(
                                            target.getImplementation().getRegisterCount(),
                                            code,
                                            target.getImplementation().getTryBlocks(),
                                            target.getImplementation().getDebugItems()))
                            : candidate);
        Map<String, ClassDef> mutated = new HashMap<>(classes);
        mutated.put(
                original.getType(),
                new ImmutableClassDef(
                        original.getType(),
                        original.getAccessFlags(),
                        original.getSuperclass(),
                        original.getInterfaces(),
                        original.getSourceFile(),
                        original.getAnnotations(),
                        original.getFields(),
                        methods));
        return mutated;
    }

    static void rejectIcon(File apk, Map<String, ClassDef> classes, String name) throws Exception {
        try {
            verifyTweaksIcon(apk, classes);
        } catch (AssertionError expected) {
            System.out.println("PASS: settings icon negative variant rejected: " + name);
            return;
        }
        throw new AssertionError("Settings icon negative variant accepted: " + name);
    }

    static void iconRegressions(File apk, Map<String, ClassDef> classes) throws Exception {
        String owner = "Lapp/bitchord/telegram/ExtrasUi;";
        for (Method method : classes.get(owner).getMethods()) {
            if (method.getName().equals("hostPainter")) {
                List<Instruction> code = new ArrayList<>();
                method.getImplementation().getInstructions().forEach(code::add);
                code.set(2, new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 2));
                rejectIcon(apk, withCode(classes, method, code), "composer returned as painter");
                code.set(2, new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 1));
                code.set(
                        0,
                        new ImmutableInstruction3rc(
                                Opcode.INVOKE_STATIC_RANGE, 0, 2, VerifyApk.call(code.get(0))));
                rejectIcon(
                        apk, withCode(classes, method, code), "wrong resource/composer arguments");
            }
            if (method.getImplementation() == null) continue;
            List<Instruction> code = new ArrayList<>();
            method.getImplementation().getInstructions().forEach(code::add);
            for (int index = 0; index < code.size(); index++) {
                MethodReference call = VerifyApk.call(code.get(index));
                if (call != null
                        && call.getDefiningClass().equals(owner)
                        && call.getName().equals("tweaksIcon")) {
                    List<Instruction> typed = new ArrayList<>(code);
                    typed.set(
                            index + 1,
                            new ImmutableInstruction11x(
                                    Opcode.MOVE_RESULT_OBJECT,
                                    ((OneRegisterInstruction) code.get(index + 1)).getRegisterA()));
                    rejectIcon(
                            apk, withCode(classes, method, typed), "integer icon read as object");
                }
                if (call != null
                        && call.getName().equals("call")
                        && call.getDefiningClass()
                                .equals("Lapp/bitchord/telegram/HostSettingsRow;")) {
                    int painter = registers(code.get(index)).get(3);
                    code.add(index, new ImmutableInstruction21s(Opcode.CONST_16, painter, 0));
                    rejectIcon(apk, withCode(classes, method, code), "row receives no painter");
                    break;
                }
            }
        }
        Element path =
                DocumentBuilderFactory.newInstance()
                        .newDocumentBuilder()
                        .newDocument()
                        .createElement("path");
        String[][] valid = {
            {"pathData", "M3,5H6M10,5H21M10,5A2,2 0,1 1,6 5"},
            {"strokeWidth", "1.7"},
            {"strokeColor", "#ffffffff"}
        };
        for (String[] attribute : valid)
            path.setAttributeNS(VerifyApk.NS, "android:" + attribute[0], attribute[1]);
        verifyIconPath(path);
        String[] invalid = {"", "0", "#00ffffff"};
        for (int index = 0; index < valid.length; index++) {
            String name = valid[index][0];
            path.setAttributeNS(VerifyApk.NS, "android:" + name, invalid[index]);
            boolean rejected = false;
            try {
                verifyIconPath(path);
            } catch (AssertionError expected) {
                rejected = true;
            }
            check(rejected, "Invisible settings icon negative variant rejected: " + name);
            path.setAttributeNS(VerifyApk.NS, "android:" + name, valid[index][1]);
        }
    }
}

import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;

import org.w3c.dom.*;

import java.io.File;
import java.util.*;

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
        check(classes.containsKey(downloadNav) == downloads,
                "Downloads navigation runtime exists only with the download addon");
        for (String owner : List.of("FloatingBottomBarKt", "GlassNavBarKt")) {
            int navigationHooks = 0;
            for (Method method : classes.get("Lcom/music/bitchord/ui/components/" + owner + ";").getMethods()) {
                if (method.getImplementation() == null) continue;
                for (Instruction instruction : method.getImplementation().getInstructions()) {
                    if (!(instruction instanceof ReferenceInstruction)) continue;
                    Reference reference = ((ReferenceInstruction) instruction).getReference();
                    if (reference instanceof MethodReference
                            && ((MethodReference) reference).getDefiningClass().equals(downloadNav)
                            && ((MethodReference) reference).getName().equals("tabs")) navigationHooks++;
                }
            }
            check(navigationHooks == (downloads ? 1 : 0),
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
                        "SoundExtras",
                        "TelegramStyle",
                        "TelegramSwitch",
                        "Strings",
                        "PatchStrings",
                        "CapturePolicy",
                        "Translations",
                        "HostLog",
                        "VocalCut",
                        "PatchSources",
                        "Outputs",
                        "SleepFade",
                        "HeadphoneEq",
                        "OutputEq",
                        "KeyTempo",
                        "DjMode",
                        "DownloadChoice",
                        "EffectsApps",
                        "BitPerfect",
                        "VideoSwitch",
                        "MixedPlaylists",
                        "MixedSongCodec",
                        "FlacAddon");
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
}

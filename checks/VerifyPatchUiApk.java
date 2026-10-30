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
        boolean runtime = telegram || downloads;
        boolean enabled = downloads || (args.length > 3 && Boolean.parseBoolean(args[3]));
        var dex = DexFileFactory.loadDexContainer(apk, Opcodes.getDefault());
        Map<String, ClassDef> classes = new HashMap<>();
        for (String entry : dex.getDexEntryNames())
            for (ClassDef cls : dex.getEntry(entry).getDexFile().getClasses())
                check(classes.put(cls.getType(), cls) == null, "Unique class " + cls.getType());
        String base = "Lapp/bitchord/telegram/";
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
                        "Translations",
                        "HostLog",
                        "VocalCut",
                        "PatchSources");
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
                for (Instruction instruction : method.getImplementation().getInstructions()) {
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
                    }
                    if (call.getDefiningClass().equals(base + "ExtrasUi;")
                            && call.getName().equals("start")) starts++;
                    if (call.getDefiningClass().equals(base + "PatchSources;")
                            && call.getName().equals("visibleState")) {
                        filters++;
                        check(
                                cls.getType()
                                                .equals(
                                                        "Lcom/music/bitchord/ui/screens/DiscordScreenKt;")
                                        && method.getName().equals("SourcesScreen"),
                                "Filtering applies only to collected Sources UI state");
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
}

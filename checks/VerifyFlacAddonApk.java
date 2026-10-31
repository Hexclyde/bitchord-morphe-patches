import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;

import java.io.*;
import java.util.*;

/** Pinned host adapter boundary, exact constructor and optional-patch isolation. */
public class VerifyFlacAddonApk {
    static int count;

    static void check(boolean value, String why) {
        count++;
        if (!value) throw new AssertionError(why);
    }

    public static void main(String[] args) throws Exception {
        Map<String, ClassDef> types = new HashMap<>();
        var container = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.getDefault());
        for (String entry : container.getDexEntryNames())
            for (ClassDef type : container.getEntry(entry).getDexFile().getClasses())
                types.put(type.getType(), type);
        String base = "Lapp/bitchord/telegram/";
        check(types.containsKey(base + "FlacAddon;"), "Adapter runtime present");
        int hooks = 0, starts = 0;
        boolean marker = false;
        for (ClassDef type : types.values())
            for (Method method : type.getMethods())
                if (method.getImplementation() != null)
                    for (Instruction ins : method.getImplementation().getInstructions()) {
                        if (ins instanceof ReferenceInstruction ref
                                && ref.getReference() instanceof MethodReference call
                                && call.getDefiningClass().equals(base + "FlacAddon;")) {
                            if (call.getName().equals("bodyOrNull")) {
                                check(
                                        type.getType()
                                                .equals(
                                                        "Lcom/music/bitchord/download/Downloads$run$2;"),
                                        "Only AddonClient IO routes metadata");
                                hooks++;
                            }
                            if (call.getName().equals("init")
                                    && type.getType().equals(base + "SoundExtras;")) starts++;
                        }
                        if (type.getType().equals(base + "SoundExtras;")
                                && method.getName().equals("flacAddonApplied")
                                && ins instanceof NarrowLiteralInstruction value
                                && value.getNarrowLiteral() == 1) marker = true;
                    }
        check(hooks == 1, "Exactly one adapter dispatcher");
        check(starts == 1, "Shared startup registers source once");
        check(marker, "Patch installed marker set");
        ClassDef config = types.get("Lcom/music/bitchord/data/sources/SourceConfig;");
        boolean constructor = false;
        for (Method method : config.getMethods())
            if (method.getName().equals("<init>") && method.getParameterTypes().size() == 7)
                constructor = true;
        check(constructor, "Native SourceConfig ABI retained");
        ClassDef registry = types.get("Lcom/music/bitchord/data/sources/SourceRegistry;");
        Set<String> methods = new HashSet<>();
        for (Method m : registry.getMethods()) methods.add(m.getName());
        for (String name : new String[] {"publish", "config", "setEnabled"})
            check(methods.contains(name), "Registry ABI " + name);
        if (args.length > 1 && args[1].equals("standalone")) {
            check(!types.containsKey(base + "TelegramBridge;"), "No Telegram account dependency");
            check(!types.containsKey("Lorg/drinkless/tdlib/Client;"), "No TDLib dependency");
            check(
                    !types.containsKey("Lapp/bitchord/soulseek/SoulseekBridge;"),
                    "No Soulseek account dependency");
        }
        System.out.println("PASS: " + count + " FLAC adapter APK/ABI checks");
    }
}

import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;

import java.io.File;
import java.util.*;
import java.util.zip.ZipFile;

/** Resolve the host ABI actually called by the standalone download integration. */
public final class VerifySoulseekHostAbi {
    static int checks;

    static void check(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        File original = new File(args[0]), patched = new File(args[1]);
        var dex = DexFileFactory.loadDexContainer(patched, Opcodes.getDefault());
        Map<String, ClassDef> classes = new HashMap<>();
        for (String entry : dex.getDexEntryNames())
            for (ClassDef cls : dex.getEntry(entry).getDexFile().getClasses())
                check(classes.put(cls.getType(), cls) == null, "Unique class " + cls.getType());
        for (ClassDef cls : classes.values()) {
            if (!cls.getType().startsWith("Lapp/bitchord/soulseek/")
                    && !Set.of(
                                    "ExtrasUi",
                                    "ExtrasActivity",
                                    "TelegramStyle",
                                    "TelegramSwitch",
                                    "PatchSources")
                            .contains(
                                    cls.getType()
                                            .replace("Lapp/bitchord/telegram/", "")
                                            .replace(";", "")
                                            .split("\\$", 2)[0])) continue;
            for (Method method : cls.getMethods()) {
                if (method.getImplementation() == null) continue;
                for (Instruction instruction : method.getImplementation().getInstructions()) {
                    if (instruction instanceof ReferenceInstruction r
                            && r.getReference() instanceof MethodReference target
                            && (target.getDefiningClass().startsWith("Lcom/music/bitchord/")
                                    || target.getDefiningClass().startsWith("Landroidx/media3/")))
                        check(
                                VerifyApk.resolves(
                                        classes,
                                        target,
                                        target.getDefiningClass(),
                                        new HashSet<>()),
                                "Standalone runtime host call resolves: " + target);
                }
            }
        }
        String ns = VerifyApk.NS;
        var before = VerifyApk.manifest(original).getDocumentElement();
        var after = VerifyApk.manifest(patched).getDocumentElement();
        check(
                before.getAttribute("package").equals(after.getAttribute("package")),
                "Standalone patch preserves application ID");
        check(
                before.getAttributeNS(ns, "versionName")
                        .equals(after.getAttributeNS(ns, "versionName")),
                "Standalone patch preserves host version");
        check(
                before.getAttributeNS(ns, "versionCode")
                        .equals(after.getAttributeNS(ns, "versionCode")),
                "Standalone patch preserves host version code");
        try (ZipFile input = new ZipFile(original);
                ZipFile output = new ZipFile(patched)) {
            Set<String> inputAbis = new HashSet<>(), outputAbis = new HashSet<>();
            input.stream()
                    .filter(e -> e.getName().startsWith("lib/") && e.getName().endsWith(".so"))
                    .forEach(e -> inputAbis.add(e.getName().split("/")[1]));
            output.stream()
                    .filter(e -> e.getName().startsWith("lib/") && e.getName().endsWith(".so"))
                    .forEach(e -> outputAbis.add(e.getName().split("/")[1]));
            boolean universal19 =
                    before.getAttributeNS(ns, "versionName").equals("1.9")
                            && inputAbis.equals(
                                    Set.of("arm64-v8a", "armeabi-v7a", "x86", "x86_64"));
            Set<String> expectedAbis = new HashSet<>(inputAbis);
            if (universal19) expectedAbis.remove("x86");
            check(outputAbis.equals(expectedAbis), "Only fully supported native ABIs remain");
            if (universal19)
                check(
                        output.stream().noneMatch(e -> e.getName().startsWith("lib/x86/")),
                        "Universal1.9 removes the entire unsupported32-bit x86 native directory");
            for (var entry :
                    input.stream()
                            .filter(
                                    e ->
                                            e.getName().startsWith("lib/")
                                                    && e.getName().endsWith(".so"))
                            .toList()) {
                if (universal19 && entry.getName().startsWith("lib/x86/")) continue;
                var retained = output.getEntry(entry.getName());
                check(
                        retained != null
                                && Arrays.equals(
                                        input.getInputStream(entry).readAllBytes(),
                                        output.getInputStream(retained).readAllBytes()),
                        "Original host native library retained exactly: " + entry.getName());
            }
        }
        System.out.println("PASS: " + checks + " standalone host ABI and isolation checks");
    }
}

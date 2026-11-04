import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;

import java.io.File;
import java.util.*;

/** Checks the two native cipher wrappers without changing the evaluator's limits. */
public final class VerifyCipherMemoryApk {
    private static int checks;

    private static void check(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }

    private static List<Instruction> code(Method method) {
        List<Instruction> result = new ArrayList<>();
        if (method.getImplementation() != null)
            method.getImplementation().getInstructions().forEach(result::add);
        return result;
    }

    private static MethodReference call(Instruction instruction) {
        return instruction instanceof ReferenceInstruction ref
                        && ref.getReference() instanceof MethodReference method
                ? method
                : null;
    }

    private static String text(Instruction instruction) {
        return instruction instanceof ReferenceInstruction ref
                        && ref.getReference() instanceof StringReference string
                ? string.getString()
                : "";
    }

    private static boolean contains(Method method, String marker) {
        return code(method).stream().anyMatch(i -> text(i).contains(marker));
    }

    private static List<String> args(MethodReference method) {
        return method.getParameterTypes().stream().map(Object::toString).toList();
    }

    private static Method single(List<Method> methods, String label) {
        check(methods.size() == 1, label + " is unique");
        return methods.get(0);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("patched.apkOrDexDirectory");
        List<ClassDef> classes = new ArrayList<>();
        File input = new File(args[0]);
        if (input.isDirectory()) {
            File[] dex = input.listFiles((dir, name) -> name.endsWith(".dex"));
            check(dex != null && dex.length > 0, "DEX input is present");
            for (File file : dex)
                classes.addAll(DexFileFactory.loadDexFile(file, null).getClasses());
        } else {
            var dexArchive = DexFileFactory.loadDexContainer(input, Opcodes.getDefault());
            for (String entry : dexArchive.getDexEntryNames())
                classes.addAll(dexArchive.getEntry(entry).getDexFile().getClasses());
        }
        List<Method> methods = new ArrayList<>();
        for (ClassDef type : classes) type.getMethods().forEach(methods::add);
        Method solve =
                single(
                        methods.stream()
                                .filter(
                                        m ->
                                                contains(m, "var payload = ")
                                                        && contains(m, "var r = jsc(payload);"))
                                .toList(),
                        "EJS wrapper");
        Method bounded =
                single(
                        methods.stream()
                                .filter(
                                        m ->
                                                contains(m, "const value = (")
                                                        && contains(m, "return text.length <= "))
                                .toList(),
                        "Bounded wrapper");
        for (Method method : List.of(solve, bounded)) {
            check(
                    code(method).stream()
                            .noneMatch(
                                    i -> {
                                        MethodReference ref = call(i);
                                        return ref != null
                                                && ref.getDefiningClass()
                                                        .startsWith("Lkotlin/text/")
                                                && ref.getName().equals("trimIndent");
                                    }),
                    "Cipher wrapper keeps its assembled string without another indentation copy");
            check(
                    code(method).stream()
                            .anyMatch(
                                    i -> {
                                        MethodReference ref = call(i);
                                        return ref != null
                                                && ref.getDefiningClass()
                                                        .equals("Ljava/lang/StringBuilder;")
                                                && ref.getName().equals("toString");
                                    }),
                    "Native wrapper assembly");
        }
        check(contains(bounded, "Invalid QuickJS result limit"), "Result-limit validation");
        check(
                code(bounded).stream()
                                .filter(
                                        i -> {
                                            MethodReference ref = call(i);
                                            return ref != null
                                                    && ref.getDefiningClass()
                                                            .equals("Lcom/dokar/quickjs/QuickJs;")
                                                    && ref.getName().equals("gc")
                                                    && args(ref).isEmpty();
                                        })
                                .count()
                        == 2,
                "Both native finally GC paths");
        check(
                code(bounded).stream()
                        .anyMatch(
                                i -> {
                                    MethodReference ref = call(i);
                                    return ref != null
                                            && ref.getDefiningClass()
                                                    .equals("Lcom/dokar/quickjs/QuickJs;")
                                            && ref.getName().equals("setMaxStackSize")
                                            && args(ref).equals(List.of("J"));
                                }),
                "Native stack limit");
        check(
                code(bounded).stream()
                        .anyMatch(
                                i -> {
                                    MethodReference ref = call(i);
                                    return ref != null
                                            && ref.getName().equals("unlock")
                                            && ref.getReturnType().equals("V");
                                }),
                "Native mutex cleanup");
        check(
                contains(solve, "preprocessed player too large")
                        && contains(solve, "solver output too large"),
                "EJS payload/output bounds");
        System.out.println("Cipher memory APK checks: " + checks + " passed");
    }
}

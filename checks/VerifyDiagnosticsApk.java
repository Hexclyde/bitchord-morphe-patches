import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;

import java.io.File;
import java.util.*;

/** Checks application logging and login observers in the installed selection. */
public final class VerifyDiagnosticsApk {
    private static final String RUNTIME = "Lapp/bitchord/telegram/Diagnostics;";
    private static final String WEB = "Landroid/webkit/WebViewClient;";
    private static int checks;

    private static void check(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
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

    private static long calls(Method method, String owner, String name) {
        return code(method).stream()
                .map(VerifyDiagnosticsApk::call)
                .filter(Objects::nonNull)
                .filter(ref -> ref.getDefiningClass().equals(owner) && ref.getName().equals(name))
                .count();
    }

    public static void main(String[] args) throws Exception {
        boolean enabled = args.length < 2 || Boolean.parseBoolean(args[1]);
        Map<String, ClassDef> classes = new HashMap<>();
        var dex = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.getDefault());
        for (String entry : dex.getDexEntryNames())
            for (ClassDef cls : dex.getEntry(entry).getDexFile().getClasses())
                check(classes.put(cls.getType(), cls) == null, "Unique class: " + cls.getType());
        ClassDef extras = classes.get("Lapp/bitchord/telegram/SoundExtras;");
        if (extras == null) {
            check(!enabled, "Diagnostics requires the shared settings runtime");
            System.out.println("PASS: diagnostics runtime not selected");
            return;
        }
        Method marker = null;
        for (Method method : extras.getMethods())
            if (method.getName().equals("diagnosticsApplied")) marker = method;
        check(marker != null, "Diagnostics marker exists");
        List<Instruction> markerCode = code(marker);
        check(
                markerCode.size() >= 2
                        && markerCode.get(0) instanceof NarrowLiteralInstruction
                        && ((NarrowLiteralInstruction) markerCode.get(0)).getNarrowLiteral()
                                == (enabled ? 1 : 0)
                        && markerCode.get(1).getOpcode() == Opcode.RETURN,
                "Diagnostics marker matches selected patch");
        Map<String, Integer> observed = new HashMap<>();
        List<Method> clients = new ArrayList<>();
        for (ClassDef cls : classes.values())
            if (!cls.getType().startsWith("Lapp/bitchord/"))
                for (Method method : cls.getMethods()) {
                    for (Instruction instruction : code(method)) {
                        MethodReference ref = call(instruction);
                        if (ref != null && ref.getDefiningClass().equals(RUNTIME))
                            observed.merge(ref.getName(), 1, Integer::sum);
                    }
                    if (calls(method, RUNTIME, "pageFinished") == 1) clients.add(method);
                }
        if (!enabled) {
            check(observed.isEmpty(), "Unselected diagnostics adds no native observers");
            System.out.println("PASS " + checks + " diagnostics selection checks");
            return;
        }
        check(classes.containsKey(RUNTIME), "Diagnostics runtime is present");
        check(
                classes.containsKey("Lapp/bitchord/telegram/DiagnosticStore;"),
                "Private bounded journal is present");
        check(
                classes.containsKey("Lapp/bitchord/telegram/DiagnosticsActivity;"),
                "Native export screen is present");
        for (String name :
                List.of(
                        "init",
                        "nativeLine",
                        "watch",
                        "requested",
                        "pageStarted",
                        "pageFinished",
                        "visited",
                        "httpError",
                        "sslError",
                        "rendererGone",
                        "webError",
                        "legacyWebError"))
            check(observed.getOrDefault(name, 0) == 1, "One native observer for " + name);
        check(clients.size() == 1, "Observed login client is unique");
        ClassDef client = classes.get(clients.get(0).getDefiningClass());
        check(
                WEB.equals(client.getSuperclass()),
                "Login client keeps its original WebViewClient superclass");
        int delegated = 0;
        for (Method method : client.getMethods())
            for (String name :
                    List.of(
                            "pageStarted",
                            "visited",
                            "httpError",
                            "sslError",
                            "webError",
                            "legacyWebError"))
                if (calls(method, RUNTIME, name) == 1) {
                    List<Instruction> instructions = code(method);
                    check(
                            instructions.stream()
                                    .anyMatch(
                                            instruction -> {
                                                MethodReference ref = call(instruction);
                                                return (instruction.getOpcode()
                                                                        == Opcode.INVOKE_SUPER
                                                                || instruction.getOpcode()
                                                                        == Opcode
                                                                                .INVOKE_SUPER_RANGE)
                                                        && ref != null
                                                        && ref.getDefiningClass().equals(WEB)
                                                        && ref.getName().equals(method.getName())
                                                        && ref.getParameterTypes()
                                                                .equals(method.getParameterTypes());
                                            }),
                            "Added login observer delegates the original superclass: " + name);
                    check(
                            instructions.get(instructions.size() - 1).getOpcode()
                                    == Opcode.RETURN_VOID,
                            "Observer does not override a navigation result: " + name);
                    delegated++;
                }
        check(delegated == 6, "All added callbacks preserve superclass behavior");
        System.out.println("PASS " + checks + " diagnostics APK checks");
    }
}

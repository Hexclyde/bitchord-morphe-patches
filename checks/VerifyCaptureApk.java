import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;

import java.io.File;
import java.util.*;

/** Checks the actual testing profile and its sensitive-screen/window call sites. */
public final class VerifyCaptureApk {
    private static final String POLICY = "Lapp/bitchord/telegram/CapturePolicy;";
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static List<Instruction> code(Method method) {
        List<Instruction> result = new ArrayList<>();
        if (method.getImplementation() != null)
            method.getImplementation().getInstructions().forEach(result::add);
        return result;
    }

    private static boolean calls(Method method, String owner, String name, String parameter) {
        for (Instruction instruction : code(method))
            if (instruction instanceof ReferenceInstruction ref
                    && ref.getReference() instanceof MethodReference target
                    && target.getDefiningClass().equals(owner)
                    && target.getName().equals(name)
                    && target.getParameterTypes().equals(List.of(parameter))) return true;
        return false;
    }

    public static void main(String[] args) throws Exception {
        var dex = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.getDefault());
        Map<String, ClassDef> classes = new HashMap<>();
        for (String entry : dex.getDexEntryNames())
            for (ClassDef cls : dex.getEntry(entry).getDexFile().getClasses())
                check(classes.put(cls.getType(), cls) == null, "Unique class " + cls.getType());
        ClassDef policy = classes.get(POLICY);
        if (policy == null) {
            System.out.println("PASS: no capture runtime selected");
            return;
        }
        Method marker = null;
        for (Method method : policy.getMethods())
            if (method.getName().equals("testingBuild")) marker = method;
        check(
                marker != null && (marker.getAccessFlags() & 9) == 9,
                "Capture profile marker has a public static ABI");
        List<Instruction> body = code(marker);
        check(
                body.size() == 2
                        && body.get(0) instanceof NarrowLiteralInstruction literal
                        && literal.getNarrowLiteral() == 1
                        && body.get(1).getOpcode() == Opcode.RETURN,
                "Beta.8 capture profile is enabled in the actual patched DEX");
        for (String parameter :
                List.of(
                        "Landroid/app/Activity;",
                        "Landroid/view/Window;",
                        "Landroid/content/Context;")) {
            String name = parameter.equals("Landroid/content/Context;") ? "start" : "apply";
            Method entry = null;
            for (Method method : policy.getMethods())
                if (method.getName().equals(name)
                        && method.getParameterTypes().equals(List.of(parameter))) entry = method;
            check(
                    entry != null
                            && (entry.getAccessFlags() & 9) == 9
                            && entry.getReturnType().equals("V"),
                    "Capture helper public static entry exists: " + name + parameter);
            if (parameter.equals("Landroid/view/Window;"))
                check(
                        calls(entry, "Landroid/view/Window;", "clearFlags", "I")
                                && calls(entry, "Landroid/view/Window;", "addFlags", "I"),
                        "Window policy has both testing allowance and protected defaults");
            if (parameter.equals("Landroid/app/Activity;"))
                check(
                        calls(entry, "Landroid/app/Activity;", "setRecentsScreenshotEnabled", "Z"),
                        "Activity policy also controls recents screenshots");
        }
        int starts = 0, returns = 0;
        ClassDef application = classes.get("Lcom/music/bitchord/BitChordApplication;");
        for (Method method : application.getMethods())
            if (method.getName().equals("onCreate")) {
                for (Instruction instruction : code(method)) {
                    if (instruction.getOpcode() == Opcode.RETURN_VOID) returns++;
                    if (instruction instanceof ReferenceInstruction ref
                            && ref.getReference() instanceof MethodReference target
                            && target.getDefiningClass().equals(POLICY)
                            && target.getName().equals("start")) starts++;
                }
            }
        check(
                returns > 0 && starts == returns,
                "Capture callbacks installed on every startup return");
        ClassDef callbacks = classes.get("Lapp/bitchord/telegram/CapturePolicy$TestingWindows;");
        check(callbacks != null, "Capture lifecycle callback implementation is present");
        for (String name : List.of("onActivityCreated", "onActivityStarted", "onActivityResumed")) {
            boolean bound = false;
            for (Method method : callbacks.getMethods())
                if (method.getName().equals(name))
                    for (Instruction instruction : code(method))
                        if (instruction instanceof ReferenceInstruction ref
                                && ref.getReference() instanceof MethodReference target
                                && target.getDefiningClass().equals(POLICY)) bound = true;
            check(bound, "Host windows covered at " + name);
        }
        Set<String> sensitive =
                Set.of(
                        "Lapp/bitchord/telegram/TelegramActivity;",
                        "Lapp/bitchord/soulseek/SoulseekActivity;",
                        "Lapp/bitchord/soulseek/BulkDownloadsActivity;",
                        "Lapp/bitchord/soulseek/DebridDownloadsActivity;",
                        "Lapp/bitchord/soulseek/DownloadDestinationActivity;",
                        "Lapp/bitchord/soulseek/DebridSetupActivity;",
                        "Lapp/bitchord/soulseek/NostrSetupActivity;");
        for (String type : sensitive) {
            ClassDef cls = classes.get(type);
            if (cls == null) continue;
            boolean bound = false;
            for (Method method : cls.getMethods())
                if (method.getName().equals("onCreate"))
                    bound = calls(method, POLICY, "apply", "Landroid/app/Activity;");
            check(bound, "Sensitive screen follows capture profile: " + type);
        }
        for (String type :
                List.of(
                        "Lapp/bitchord/telegram/TelegramActivity;",
                        "Lapp/bitchord/soulseek/DebridSetupActivity;")) {
            ClassDef cls = classes.get(type);
            if (cls == null) continue;
            boolean bound = false;
            for (Method method : cls.getMethods())
                bound |= calls(method, POLICY, "apply", "Landroid/view/Window;");
            check(bound, "Credential dialogs follow capture profile: " + type);
        }
        for (ClassDef cls : classes.values()) {
            if (!cls.getType().startsWith("Lapp/bitchord/") || cls.getType().equals(POLICY))
                continue;
            for (Method method : cls.getMethods())
                for (Instruction instruction : code(method))
                    if (instruction instanceof ReferenceInstruction ref
                            && ref.getReference() instanceof MethodReference target) {
                        check(
                                !target.getDefiningClass().equals("Landroid/view/Window;")
                                        || !target.getName().equals("addFlags"),
                                "Injected screens cannot bypass central capture policy");
                        check(
                                !target.getDefiningClass().equals("Landroid/app/Activity;")
                                        || !target.getName().equals("setRecentsScreenshotEnabled"),
                                "Recents capture is controlled by the build profile");
                    }
        }
        System.out.println("PASS: " + checks + " testing capture profile and window checks");
    }
}

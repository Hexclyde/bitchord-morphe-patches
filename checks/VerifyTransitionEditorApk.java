import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;

import java.io.File;
import java.util.*;

/** Verifies the retained, optimized audio paths used by the transition editor. */
public final class VerifyTransitionEditorApk {
    static int checks;
    static final String RUNTIME = "Lapp/bitchord/telegram/TransitionRuntime;";

    static void require(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    static Method method(ClassDef owner, String name) {
        for (Method method : owner.getMethods()) if (method.getName().equals(name)) return method;
        throw new AssertionError("Missing " + owner.getType() + "->" + name);
    }

    static MethodReference call(Instruction instruction) {
        return instruction instanceof ReferenceInstruction r
                        && r.getReference() instanceof MethodReference m
                ? m
                : null;
    }

    static void first(ClassDef owner, String method, String bridge, String result) {
        Method target = method(owner, method);
        Instruction entry = target.getImplementation().getInstructions().iterator().next();
        MethodReference ref = call(entry);
        require(
                ref != null
                        && ref.getDefiningClass().equals(RUNTIME)
                        && ref.getName().equals(bridge)
                        && ref.getReturnType().equals(result),
                method + " must call " + bridge + " before optimized registers are reused");
    }

    public static void main(String[] args) throws Exception {
        Map<String, ClassDef> types = new HashMap<>();
        MultiDexContainer<? extends DexFile> apk =
                DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.forApi(35));
        for (String entry : apk.getDexEntryNames())
            for (ClassDef type : apk.getEntry(entry).getDexFile().getClasses())
                types.put(type.getType(), type);
        require(types.containsKey(RUNTIME), "Transition runtime retained");
        require(
                types.containsKey("Lapp/bitchord/telegram/TransitionEditorActivity;"),
                "Native editor retained");
        ClassDef controller = types.get("Lcom/music/bitchord/playback/CrossfadeController;");
        first(controller, "mixRise", "incoming", "Ljava/lang/Float;");
        first(controller, "mixFall", "outgoing", "Ljava/lang/Float;");
        first(controller, "rideFilters", "filters", "Z");
        Method begin = method(controller, "begin");
        java.util.List<Instruction> beginCode = new java.util.ArrayList<>();
        begin.getImplementation().getInstructions().forEach(beginCode::add);
        MethodReference arm = call(beginCode.get(2));
        require(
                arm != null
                        && arm.getDefiningClass().equals(RUNTIME)
                        && arm.getName().equals("arm")
                        && arm.getParameterTypes()
                                .equals(
                                        java.util.List.of(
                                                "Ljava/lang/Object;", "Ljava/lang/Object;")),
                "Arming is bound to this controller and its exact smart plan");
        first(controller, "considerSmartTransition", "bind", "V");
        first(controller, "finish", "finish", "V");
        first(controller, "release", "release", "V");
        Method pcm =
                method(
                        types.get("Lcom/music/bitchord/playback/TransitionFilterProcessor;"),
                        "queueInput");
        java.util.List<Instruction> pcmCode = new java.util.ArrayList<>();
        pcm.getImplementation().getInstructions().forEach(pcmCode::add);
        int pcmHooks = 0;
        for (int i = 1; i < pcmCode.size(); i++) {
            MethodReference ref = call(pcmCode.get(i));
            if (ref != null
                    && ref.getDefiningClass().equals(RUNTIME)
                    && ref.getName().equals("pcm16")) {
                pcmHooks++;
                MethodReference previous = call(pcmCode.get(i - 1));
                require(
                        previous != null && previous.getName().equals("flip"),
                        "PCM EQ processes mutable output after flip");
            }
        }
        require(
                pcmHooks == 2,
                "Both PCM output branches apply EQ, without touching read-only input or early"
                        + " returns");
        first(types.get("Lokio/internal/DefaultSocket;"), "process", "floats", "V");
        ClassDef callback =
                types.get("Lcom/music/bitchord/playback/PlaybackService$MediaLibraryCallback;");
        int assignments = 0;
        for (Method method : callback.getMethods())
            if (method.getImplementation() != null)
                for (Instruction step : method.getImplementation().getInstructions()) {
                    MethodReference ref = call(step);
                    if (ref != null
                            && ref.getDefiningClass().equals(RUNTIME)
                            && ref.getName().equals("assign")) assignments++;
                }
        require(assignments == 2, "Both physical decks bind EQ after role-aware filtering");
        System.out.println("PASS: " + checks + " transition editor APK checks");
    }
}

import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;

import java.io.File;
import java.util.*;

/** Verifies the available, optimized audio paths used by the transition editor. */
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
        require(types.containsKey(RUNTIME), "Transition runtime available");
        require(
                types.containsKey("Lapp/bitchord/telegram/TransitionEditorActivity;"),
                "Native editor available");
        for (String name :
                List.of(
                        "MixTools",
                        "DeckMeter",
                        "TransitionWaveform",
                        "TransitionWaveCache",
                        "TransitionPreview",
                        "TransitionAnalysis",
                        "TransitionStore"))
            require(
                    types.containsKey("Lapp/bitchord/telegram/" + name + ";"),
                    "Standalone helper available: " + name);
        ClassDef editor = types.get("Lapp/bitchord/telegram/TransitionEditorActivity;");
        int optionRows = 0;
        for (Instruction step :
                method(editor, "buildLayout").getImplementation().getInstructions()) {
            MethodReference ref = call(step);
            if (ref != null
                    && ref.getDefiningClass().equals(editor.getType())
                    && ref.getName().equals("optionRow")) optionRows++;
        }
        require(
                optionRows == 2,
                "Auto-save and Auto-apply show visible labels beside their switches");
        ClassDef host = types.get("Lcom/music/bitchord/playback/PlaybackService;");
        Method wave = method(host, "bitchordWaveDecode");
        require(
                wave.getParameterTypes()
                        .equals(
                                List.of(
                                        "Landroid/media/MediaDataSource;",
                                        "D",
                                        "D",
                                        "Lkotlin/jvm/functions/Function0;",
                                        "Lkotlin/jvm/functions/Function4;")),
                "Selected-source waveform bridge passes region, cancellation and streaming PCM");
        boolean directDecode = false;
        for (Instruction step : wave.getImplementation().getInstructions()) {
            MethodReference ref = call(step);
            if (ref != null
                    && ref.getDefiningClass()
                            .equals("Lcom/music/bitchord/playback/smart/AudioDecoder;"))
                directDecode = true;
        }
        require(
                directDecode,
                "Waveform calls the available decoder directly without substituting an analysis"
                        + " URI");
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(args[0])) {
            require(
                    zip.getEntry("assets/bitchord/automix/licenses/AUTOMIX-COMPONENTS.txt") != null,
                    "Component notices are present in standalone and combined APKs");
        }
        ClassDef controller = types.get("Lcom/music/bitchord/playback/CrossfadeController;");
        String playerHeader = "Lapp/bitchord/telegram/VideoSwitch;";
        int landscape = 0, entries = 0;
        for (ClassDef owner : types.values())
            for (Method method : owner.getMethods()) {
                if (method.getImplementation() == null) continue;
                for (Instruction step : method.getImplementation().getInstructions()) {
                    MethodReference ref = call(step);
                    if (ref != null
                            && ref.getDefiningClass().equals(playerHeader)
                            && ref.getName().equals("header")) {
                        entries++;
                        if (owner.getType()
                                        .equals("Lcom/music/bitchord/ui/player/LandscapePlayerKt;")
                                && method.getName().equals("LandscapeMainPane")) landscape++;
                    }
                }
            }
        require(
                entries == 1 && landscape == 1,
                "Exactly one pair slot, in the landscape player's main pane");
        VerifyVideoSwitchApk.verifyPlayerIcons(types);
        require(
                types.containsKey("Lapp/bitchord/telegram/PlayerIcons$EditorEntry;"),
                "Editor icon available independently of mode-switch selection");
        ClassDef bands = types.get("Lcom/music/bitchord/playback/smart/MixWaveform$Bands;");
        for (String name : List.of("low", "mid", "high")) {
            boolean available = false;
            for (Field field : bands.getFields())
                if (field.getName().equals(name) && field.getType().equals("[F")) available = true;
            require(available, "Real host waveform band " + name);
        }
        for (Field field : controller.getFields())
            if (field.getName().equals("mixWaveform"))
                require(
                        types.get(field.getType())
                                .getInterfaces()
                                .contains("Lkotlin/jvm/functions/Function5;"),
                        "Waveform callback passes the five-argument callable interface");
        first(controller, "mixRise", "incoming", "Ljava/lang/Float;");
        first(controller, "mixFall", "outgoing", "Ljava/lang/Float;");
        first(controller, "rideFilters", "filters", "Z");
        Method begin = method(controller, "begin");
        java.util.List<Instruction> beginCode = new java.util.ArrayList<>();
        begin.getImplementation().getInstructions().forEach(beginCode::add);
        MethodReference guard = call(beginCode.get(0));
        require(
                guard != null
                        && guard.getDefiningClass().equals(RUNTIME)
                        && guard.getName().equals("previewHolding")
                        && beginCode.get(2).getOpcode() == Opcode.IF_EQZ
                        && beginCode.get(3).getOpcode() == Opcode.CONST_4
                        && beginCode.get(4).getOpcode() == Opcode.RETURN
                        && begin.getReturnType().equals("Z"),
                "A pending live transition cannot start during an independent audition");
        MethodReference arm = call(beginCode.get(8));
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
        Method considering = method(controller, "considerSmartTransition");
        java.util.List<Instruction> planning = new java.util.ArrayList<>();
        considering.getImplementation().getInstructions().forEach(planning::add);
        require(
                call(planning.get(1)).getName().equals("holdPlanner")
                        && planning.get(4).getOpcode() == Opcode.RETURN_VOID,
                "Live planning waits for the pair database and stays paused throughout editing");
        ClassDef session = types.get("Lapp/bitchord/telegram/TransitionPreview$Session;");
        boolean mutatesQueue = false;
        for (Method method : session.getMethods())
            if (method.getImplementation() != null)
                for (Instruction step : method.getImplementation().getInstructions()) {
                    MethodReference ref = call(step);
                    if (ref != null
                            && Set.of(
                                            "seekTo",
                                            "setMediaItem",
                                            "setMediaItems",
                                            "replaceMediaItem",
                                            "moveMediaItems")
                                    .contains(ref.getName())) mutatesQueue = true;
                }
        require(!mutatesQueue, "The listening snapshot never seeks or mutates the real queue");
        require(
                types.get("Lapp/bitchord/telegram/TransitionStore$Helper;")
                        .getSuperclass()
                        .equals("Landroid/database/sqlite/SQLiteOpenHelper;"),
                "Applied transitions use the persistent app database");
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

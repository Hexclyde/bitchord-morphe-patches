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

    /** The listening snapshot's one explicit queue change and the rollback it alone calls. */
    static boolean allowsQueueWrite(String method) {
        return method.equals("usePair") || method.equals("restore");
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
        int docks = 0, handles = 0, columns = 0;
        for (Instruction step :
                method(editor, "buildLayout").getImplementation().getInstructions()) {
            MethodReference ref = call(step);
            if (ref != null
                    && ref.getDefiningClass().equals(editor.getType())
                    && ref.getName().equals("buildDock")) docks++;
        }
        for (Instruction step : method(editor, "buildDock").getImplementation().getInstructions()) {
            MethodReference ref = call(step);
            if (ref == null || !ref.getDefiningClass().equals(editor.getType())) continue;
            if (ref.getName().equals("modeHandle")) handles++;
            if (ref.getName().equals("dockColumn")) columns++;
            require(
                    !ref.getName().equals("setMode"),
                    "Rendering mode controls does not change modes");
        }
        require(
                docks == 1 && handles == 2 && columns == 2,
                "Pinned draft and apply controls each have an independent native mode handle");
        Set<String> modeLabels = new HashSet<>(),
                preferences = new HashSet<>(),
                tags = new HashSet<>();
        for (Instruction step : method(editor, "modeDialog").getImplementation().getInstructions())
            if (step instanceof ReferenceInstruction ref
                    && ref.getReference() instanceof StringReference text)
                modeLabels.add(text.getString());
        for (Instruction step : method(editor, "modeHandle").getImplementation().getInstructions())
            if (step instanceof ReferenceInstruction ref
                    && ref.getReference() instanceof StringReference text)
                tags.add(text.getString());
        for (Instruction step : method(editor, "setMode").getImplementation().getInstructions())
            if (step instanceof ReferenceInstruction ref
                    && ref.getReference() instanceof StringReference text)
                preferences.add(text.getString());
        require(
                modeLabels.containsAll(
                        Set.of("Manual", "Automatic", "Draft saving", "Apply settings")),
                "Both mode dialogs expose manual and automatic choices");
        require(
                tags.containsAll(Set.of("automix-auto-save", "automix-auto-apply")),
                "Draft and apply mode handles remain separately accessible");
        require(
                preferences.containsAll(Set.of("auto_save", "auto_apply")),
                "Mode actions persist their distinct editor preferences");
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
        for (String name : List.of("outgoing", "incoming", "easing")) {
            int matching = 0;
            for (Field field : controller.getFields())
                if (field.getName().equals(name)) {
                    matching++;
                    require(
                            field.getType().equals("Landroidx/media3/exoplayer/ExoPlayer;")
                                    && (field.getAccessFlags() & 0x8) == 0,
                            "Native deck ownership field is an instance player: " + name);
                }
            require(matching == 1, "One native deck ownership field: " + name);
        }
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
        require(
                allowsQueueWrite("usePair")
                        && allowsQueueWrite("restore")
                        && !allowsQueueWrite("selectPair")
                        && !allowsQueueWrite("settle")
                        && !allowsQueueWrite("<init>")
                        && !allowsQueueWrite("close")
                        && !allowsQueueWrite("start"),
                "Only the explicit pair change and its rollback may write the real queue");
        Set<String> queueWrites =
                Set.of(
                        "seekTo",
                        "setMediaItem",
                        "setMediaItems",
                        "replaceMediaItem",
                        "replaceMediaItems",
                        "moveMediaItem",
                        "moveMediaItems",
                        "addMediaItem",
                        "addMediaItems",
                        "removeMediaItem",
                        "removeMediaItems",
                        "clearMediaItems");
        Set<String> queueWriters = new TreeSet<>(), restoreCallers = new TreeSet<>();
        for (Method method : session.getMethods())
            if (method.getImplementation() != null)
                for (Instruction step : method.getImplementation().getInstructions()) {
                    MethodReference ref = call(step);
                    // Direct Player calls and reflective calls by method name both count.
                    if (ref != null && queueWrites.contains(ref.getName()))
                        queueWriters.add(method.getName());
                    if (ref != null
                            && ref.getDefiningClass().equals(session.getType())
                            && ref.getName().equals("restore"))
                        restoreCallers.add(method.getName());
                    if (step instanceof ReferenceInstruction r
                            && r.getReference() instanceof StringReference text
                            && queueWrites.contains(text.getString()))
                        queueWriters.add(method.getName());
                }
        for (String writer : queueWriters)
            require(
                    allowsQueueWrite(writer),
                    "The listening snapshot writes the real queue only in usePair: " + writer);
        require(
                restoreCallers.isEmpty() || restoreCallers.equals(Set.of("usePair")),
                "Queue rollback is reachable only from the explicit pair change");
        require(
                types.get("Lapp/bitchord/telegram/TransitionStore$Helper;")
                        .getSuperclass()
                        .equals("Landroid/database/sqlite/SQLiteOpenHelper;"),
                "Applied transitions use the persistent app database");
        int tempoStarts = 0;
        for (Method method : controller.getMethods()) {
            if (method.getImplementation() == null) continue;
            List<Instruction> body = new ArrayList<>();
            method.getImplementation().getInstructions().forEach(body::add);
            for (int i = 0; i < body.size(); i++) {
                MethodReference ref = call(body.get(i));
                if (ref == null
                        || !ref.getDefiningClass().equals(RUNTIME)
                        || !ref.getName().equals("beginTempo")) continue;
                tempoStarts++;
                int receiver = method.getImplementation().getRegisterCount() - 1;
                for (CharSequence parameter : method.getParameterTypes())
                    receiver -=
                            parameter.toString().equals("J") || parameter.toString().equals("D")
                                    ? 2
                                    : 1;
                require(
                        body.get(i) instanceof RegisterRangeInstruction range
                                && range.getRegisterCount() == 1
                                && range.getStartRegister() == receiver,
                        "Overlap tempo uses this controller's unchanged receiver register");
                require(i + 1 < body.size(), "Tempo starts before an executable playback call");
                MethodReference audible = call(body.get(i + 1));
                require(
                        audible != null
                                && audible.getName().equals("setPlayWhenReady")
                                && audible.getParameterTypes().equals(List.of("Z")),
                        "Explicit overlap tempo is applied before the incoming deck starts");
                MethodReference silent = i > 0 ? call(body.get(i - 1)) : null;
                MethodReference clock = i + 2 < body.size() ? call(body.get(i + 2)) : null;
                require(
                        silent != null
                                && silent.getName().equals("setVolume")
                                && clock != null
                                && clock.getDefiningClass().equals("Landroid/os/SystemClock;")
                                && clock.getName().equals("elapsedRealtime"),
                        "The tempo hook belongs to the silent-deck blend-start path");
            }
        }
        require(tempoStarts == 1, "Exactly one guarded overlap-tempo start");
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

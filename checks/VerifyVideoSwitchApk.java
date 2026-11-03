import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;

import java.io.File;
import java.util.*;

/** Qualify measured player-child slots, not a window overlay. */
public final class VerifyVideoSwitchApk {
    static int checks;

    static void check(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }

    static MethodReference call(Instruction i) {
        return i instanceof ReferenceInstruction r && r.getReference() instanceof MethodReference m
                ? m
                : null;
    }

    static FieldReference field(Instruction i) {
        return i instanceof ReferenceInstruction r && r.getReference() instanceof FieldReference f
                ? f
                : null;
    }

    record Hook(Method method, List<Instruction> code, int index) {}

    static void verifyLyrics(Map<String, ClassDef> types) {
        List<Hook> before = new ArrayList<>(), ready = new ArrayList<>();
        for (ClassDef type : types.values()) {
            if (!type.getType().startsWith("Lcom/music/bitchord/")) continue;
            for (Method method : type.getMethods()) {
                if (method.getImplementation() == null) continue;
                List<Instruction> code = new ArrayList<>();
                method.getImplementation().getInstructions().forEach(code::add);
                for (int i = 0; i < code.size(); i++) {
                    MethodReference target = call(code.get(i));
                    if (target == null
                            || !target.getDefiningClass()
                                    .equals("Lapp/bitchord/telegram/VideoSwitch;")) continue;
                    if (target.getName().equals("lyricsBeforeReset"))
                        before.add(new Hook(method, code, i));
                    if (target.getName().equals("lyricsReady"))
                        ready.add(new Hook(method, code, i));
                }
            }
        }
        check(
                before.size() == 1 && ready.size() == 1,
                "Exactly one paired lyrics reset/startup hook");
        Hook start = before.getFirst(), finish = ready.getFirst();
        check(start.method().equals(finish.method()), "Lyrics hooks share the real native lookup");
        List<Instruction> code = start.code();
        int keyAt = start.index() + 1, jobAt = finish.index() - 1;
        FieldReference key = field(code.get(keyAt)), job = field(code.get(jobAt));
        check(
                code.get(keyAt).getOpcode() == Opcode.IPUT_OBJECT
                        && key != null
                        && key.getName().equals("lyricsFor")
                        && key.getType().equals("Ljava/util/List;"),
                "Capture precedes the native lyrics key store");
        String model = key.getDefiningClass();
        check(
                code.get(jobAt).getOpcode() == Opcode.IPUT_OBJECT
                        && job != null
                        && job.getDefiningClass().equals(model)
                        && job.getName().equals("lyricsJob"),
                "Restore follows the new native lookup job store");
        int generation = -1, selection = -1;
        for (int i = keyAt + 1; i < jobAt; i++) {
            FieldReference f = field(code.get(i));
            if (f == null || !f.getDefiningClass().equals(model)) continue;
            if (code.get(i).getOpcode() == Opcode.IPUT_WIDE
                    && f.getName().equals("lyricsGeneration")) generation = i;
            if (code.get(i).getOpcode() == Opcode.IPUT_OBJECT
                    && f.getName().equals("selectedLyricsSource")) selection = i;
        }
        check(
                keyAt < generation && generation < selection && selection < jobAt,
                "Native key, generation, provider reset and job startup follow their expected"
                    + " order");
        int owner = ((TwoRegisterInstruction) code.get(keyAt)).getRegisterB();
        FiveRegisterInstruction capture = (FiveRegisterInstruction) code.get(start.index());
        check(
                capture.getRegisterCount() == 2
                        && capture.getRegisterC() == owner
                        && capture.getRegisterD()
                                == ((TwoRegisterInstruction) code.get(keyAt)).getRegisterA(),
                "Capture receives the actual model and incoming lookup key");
        check(
                code.get(finish.index()) instanceof RegisterRangeInstruction r
                        && r.getRegisterCount() == 1
                        && r.getStartRegister() == owner
                        && ((TwoRegisterInstruction) code.get(jobAt)).getRegisterB() == owner,
                "Startup restores the same model after its actual job assignment");
        int nullRegister = ((TwoRegisterInstruction) code.get(selection)).getRegisterA();
        boolean zero = false;
        for (Instruction i : code) {
            if (i.getOpcode() == Opcode.PACKED_SWITCH) break;
            if (i instanceof NarrowLiteralInstruction n
                    && n.getNarrowLiteral() == 0
                    && i instanceof OneRegisterInstruction r
                    && r.getRegisterA() == nullRegister) zero = true;
        }
        check(
                zero
                        && code.subList(keyAt, selection).stream()
                                .noneMatch(
                                        i ->
                                                i.getOpcode().setsRegister()
                                                        && i instanceof OneRegisterInstruction r
                                                        && r.getRegisterA() == nullRegister),
                "Native selection is still reset to null");
        List<Instruction> reset = code.subList(selection, jobAt);
        for (String type :
                List.of("Ljava/util/LinkedHashMap;", "Ljava/util/concurrent/ConcurrentHashMap;"))
            check(
                    reset.stream()
                            .anyMatch(
                                    i ->
                                            call(i) != null
                                                    && call(i).getDefiningClass().equals(type)
                                                    && call(i).getName().equals("clear")),
                    "Native lyric jobs/results are cleared: " + type);
        for (String name : List.of("_lyrics", "_lyricsSource"))
            check(
                    reset.stream()
                            .anyMatch(
                                    i ->
                                            field(i) != null
                                                    && field(i).getDefiningClass().equals(model)
                                                    && field(i).getName().equals(name)),
                    "Native displayed lyric state is reset: " + name);
        check(
                reset.stream().anyMatch(i -> call(i) != null && call(i).getName().equals("cancel")),
                "Previous native lyric work is cancelled");
        check(
                code.get(jobAt - 1).getOpcode() == Opcode.MOVE_RESULT_OBJECT
                        && call(code.get(jobAt - 2)) != null
                        && call(code.get(jobAt - 2)).getName().equals("launch$default"),
                "New lyrics job is the native coroutine launch result");
        Method bridge = null;
        for (Method m : types.get(model).getMethods())
            if (m.getName().equals("bitchordSelectLyricsProvider")) {
                check(bridge == null, "Only one provider selection bridge");
                bridge = m;
            }
        check(
                bridge != null
                        && (bridge.getAccessFlags() & 9) == 1
                        && bridge.getParameterTypes().equals(List.of("Ljava/lang/Object;"))
                        && bridge.getReturnType().equals("V"),
                "Public instance bridge accepts the explicit provider");
        List<Instruction> body = new ArrayList<>();
        bridge.getImplementation().getInstructions().forEach(body::add);
        int construct = -1, invokeAt = -1;
        for (int i = 0; i < body.size(); i++) {
            MethodReference target = call(body.get(i));
            if (target == null) continue;
            if (target.getName().equals("<init>")) construct = i;
            if (target.getName().equals("invoke")
                    && target.getParameterTypes().equals(List.of("Ljava/lang/Object;")))
                invokeAt = i;
        }
        check(
                construct >= 0 && invokeAt > construct,
                "Bridge constructs then invokes the native chooser");
        MethodReference constructor = call(body.get(construct)),
                invocation = call(body.get(invokeAt));
        check(
                constructor.getDefiningClass().startsWith("Lcom/music/bitchord/")
                        && constructor.getDefiningClass().equals(invocation.getDefiningClass()),
                "Bridge uses one existing BitChord callback type");
        int first = ((RegisterRangeInstruction) body.get(construct)).getStartRegister(),
                discriminator = -1;
        for (Instruction i : body.subList(0, construct))
            if (i instanceof NarrowLiteralInstruction n
                    && i instanceof OneRegisterInstruction r
                    && r.getRegisterA() == first + 7) discriminator = n.getNarrowLiteral();
        ClassDef callback = types.get(constructor.getDefiningClass());
        Method chooser = null;
        for (Method m : callback.getMethods())
            if (m.getName().equals(invocation.getName())
                    && m.getParameterTypes().equals(invocation.getParameterTypes())
                    && m.getReturnType().equals(invocation.getReturnType())) chooser = m;
        check(chooser != null, "Constructed native callback has the invoked method");
        List<Instruction> dispatch = new ArrayList<>();
        chooser.getImplementation().getInstructions().forEach(dispatch::add);
        List<Integer> offsets = new ArrayList<>();
        int address = 0, switchAt = -1;
        for (int i = 0; i < dispatch.size(); i++) {
            offsets.add(address);
            address += dispatch.get(i).getCodeUnits();
            if (dispatch.get(i).getOpcode() == Opcode.PACKED_SWITCH) switchAt = i;
        }
        check(switchAt >= 0, "Native chooser dispatch exists");
        int payloadAt =
                offsets.indexOf(
                        offsets.get(switchAt)
                                + ((OffsetInstruction) dispatch.get(switchAt)).getCodeOffset());
        int branch = -1;
        for (SwitchElement e : ((SwitchPayload) dispatch.get(payloadAt)).getSwitchElements())
            if (e.getKey() == discriminator)
                branch = offsets.indexOf(offsets.get(switchAt) + e.getOffset());
        check(branch >= 0, "Bridge discriminator selects an actual native chooser arm");
        int writes = 0;
        boolean fresh = false;
        for (int i = branch;
                i < dispatch.size() && dispatch.get(i).getOpcode() != Opcode.RETURN_OBJECT;
                i++) {
            FieldReference f = field(dispatch.get(i));
            MethodReference m = call(dispatch.get(i));
            if (dispatch.get(i).getOpcode() == Opcode.IPUT_OBJECT
                    && f != null
                    && f.getDefiningClass().equals(model)
                    && f.getName().equals("selectedLyricsSource")) writes++;
            if (m != null
                    && m.getDefiningClass().equals(model)
                    && m.getParameterTypes().size() == 3
                    && m.getParameterTypes().get(1).equals("J")
                    && m.getReturnType().equals("V")) fresh = true;
        }
        check(
                writes == 3 && fresh,
                "Chosen callback arm handles cached, pending and fresh provider requests");
    }

    public static void main(String[] args) throws Exception {
        var apk = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.getDefault());
        Map<String, ClassDef> types = new HashMap<>();
        for (String entry : apk.getDexEntryNames())
            for (ClassDef type : apk.getEntry(entry).getDexFile().getClasses())
                types.put(type.getType(), type);
        int slots = 0, controllers = 0, controllerCallers = 0, portrait = 0, landscape = 0;
        String runtime = "Lapp/bitchord/telegram/VideoSwitch;";
        for (ClassDef type : types.values())
            for (Method method : type.getMethods()) {
                if (method.getImplementation() == null) continue;
                List<Instruction> code = new ArrayList<>();
                method.getImplementation().getInstructions().forEach(code::add);
                for (int i = 0; i < code.size(); i++) {
                    var target = call(code.get(i));
                    if (target == null) continue;
                    if (target.getDefiningClass().equals(runtime)) {
                        if (target.getName().equals("controller")) controllers++;
                        check(
                                !Set.of("content", "attach", "bindContent", "motion")
                                        .contains(target.getName()),
                                "No window attachment or animation hook remains");
                        if (target.getName().equals("header")) {
                            slots++;
                            var previous = call(code.get(i - 1));
                            if (type.getType()
                                    .equals(
                                            "Lcom/music/bitchord/ui/player/NowPlayingScreenKt$$ExternalSyntheticLambda74;")) {
                                portrait++;
                                check(
                                        previous != null && previous.getName().equals("endNode"),
                                        "Portrait slot follows the completed drag/caption Box");
                                check(
                                        code.get(i + 1) instanceof ReferenceInstruction r
                                                && r.getReference() instanceof FieldReference f
                                                && f.getDefiningClass()
                                                        .equals(
                                                                "Landroidx/compose/foundation/layout/ColumnScopeInstance;"),
                                        "Portrait slot is a Column child before the weighted"
                                                + " artwork");
                            } else {
                                landscape++;
                                check(
                                        type.getType()
                                                        .equals(
                                                                "Lcom/music/bitchord/ui/player/LandscapePlayerKt;")
                                                && method.getName().equals("LandscapeMainPane"),
                                        "Landscape slot belongs to the main player pane");
                                check(
                                        previous != null
                                                && previous.getDefiningClass()
                                                        .equals(
                                                                "Landroidx/compose/runtime/Updater;")
                                                && previous.getName().equals("set-impl"),
                                        "Landscape slot follows its Column modifier binding");
                            }
                        }
                    }
                    if (type.getType().startsWith("Lcom/music/bitchord/")
                            && target.getDefiningClass()
                                    .equals("Lcom/music/bitchord/playback/PlayerConnectionKt;")
                            && target.getName().equals("rememberPlayerState")) controllerCallers++;
                    if (type.getType().startsWith("Lapp/bitchord/telegram/VideoSwitch"))
                        check(
                                !target.getDefiningClass().equals("Landroid/view/Window;"),
                                "Mode controls never mutate the Window");
                }
            }
        check(
                slots == 2 && portrait == 1 && landscape == 1,
                "Exactly one measured slot in each player layout");
        check(
                controllers == 1 && controllerCallers > 0,
                "Controller binding is in a called host method");
        ClassDef sizes = types.get("Landroidx/compose/foundation/layout/SizeKt;");
        boolean fill = false;
        for (Field f : sizes.getFields())
            if (f.getName().equals("FillWholeMaxWidth") && (f.getAccessFlags() & 9) == 9)
                fill = true;
        check(fill, "Public width modifier matches native AndroidView ABI");
        ClassDef layers = types.get("Landroidx/compose/ui/ZIndexElement;");
        boolean constructor = false;
        for (Method m : layers.getMethods())
            if (m.getName().equals("<init>")
                    && m.getParameterTypes().equals(List.of("F"))
                    && (m.getAccessFlags() & 1) == 1) constructor = true;
        check(constructor, "Header uses the retained native stacking modifier");
        boolean then = false;
        for (Method m : types.get("Landroidx/compose/ui/Modifier;").getMethods())
            if (m.getName().equals("then")
                    && m.getParameterTypes().equals(List.of("Landroidx/compose/ui/Modifier;")))
                then = true;
        check(then, "Width and stacking modifiers compose through the host interface");
        boolean androidView = false;
        for (Method m :
                types.get("Landroidx/compose/ui/viewinterop/AndroidView_androidKt;").getMethods())
            if (m.getName().equals("AndroidView")
                    && m.getParameterTypes().size() == 6
                    && m.getParameterTypes()
                            .get(3)
                            .equals("Landroidx/compose/runtime/ComposerImpl;")) androidView = true;
        check(androidView, "Exact six-argument AndroidView entry point retained");
        check(
                types.get("Lapp/bitchord/telegram/VideoSwitch$Header;")
                        .getSuperclass()
                        .equals("Landroid/widget/FrameLayout;"),
                "Header owns its intrinsic measured height");
        check(
                types.get("Lapp/bitchord/telegram/VideoSwitch$Pill;")
                        .getSuperclass()
                        .equals("Landroid/widget/LinearLayout;"),
                "Two icon targets share a native row");
        verifyLyrics(types);
        System.out.println(
                "PASS: "
                        + checks
                        + " native player layout, icon-control and called-host ABI checks");
    }
}

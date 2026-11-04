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

    static List<Set<Integer>> successors(Method method, List<Instruction> code) {
        Map<Integer, Integer> indices = new HashMap<>();
        int[] offsets = new int[code.size()];
        int address = 0;
        for (int i = 0; i < code.size(); i++) {
            offsets[i] = address;
            indices.put(address, i);
            address += code.get(i).getCodeUnits();
        }
        List<Set<Integer>> edges = new ArrayList<>();
        for (int i = 0; i < code.size(); i++) edges.add(new HashSet<>());
        for (int i = 0; i < code.size(); i++) {
            Instruction instruction = code.get(i);
            Opcode opcode = instruction.getOpcode();
            if (opcode.canContinue() && i + 1 < code.size()) edges.get(i).add(i + 1);
            if (opcode == Opcode.PACKED_SWITCH || opcode == Opcode.SPARSE_SWITCH) {
                Integer payload =
                        indices.get(offsets[i] + ((OffsetInstruction) instruction).getCodeOffset());
                check(
                        payload != null && code.get(payload) instanceof SwitchPayload,
                        "Native switch has a valid payload");
                for (SwitchElement branch :
                        ((SwitchPayload) code.get(payload)).getSwitchElements()) {
                    Integer target = indices.get(offsets[i] + branch.getOffset());
                    check(target != null, "Native switch targets an instruction boundary");
                    edges.get(i).add(target);
                }
            } else if (opcode.name().startsWith("IF_") || opcode.name().startsWith("GOTO")) {
                Integer target =
                        indices.get(offsets[i] + ((OffsetInstruction) instruction).getCodeOffset());
                check(target != null, "Native branch targets an instruction boundary");
                edges.get(i).add(target);
            }
        }
        for (TryBlock<? extends ExceptionHandler> block : method.getImplementation().getTryBlocks())
            for (ExceptionHandler handler : block.getExceptionHandlers()) {
                Integer target = indices.get(handler.getHandlerCodeAddress());
                check(target != null, "Native handler targets an instruction boundary");
                int end = block.getStartCodeAddress() + block.getCodeUnitCount();
                for (int i = 0; i < code.size(); i++)
                    if (offsets[i] >= block.getStartCodeAddress()
                            && offsets[i] < end
                            && code.get(i).getOpcode().canThrow()) edges.get(i).add(target);
            }
        return edges;
    }

    static void verifyCaptureFlow(Hook capture, int keyAt) {
        List<Set<Integer>> edges = successors(capture.method(), capture.code());
        Set<Integer> predecessors = new HashSet<>();
        for (int i = 0; i < edges.size(); i++)
            if (edges.get(i).contains(keyAt)) predecessors.add(i);
        check(
                predecessors.equals(Set.of(capture.index())),
                "Every lyrics key-store predecessor is the capture hook; found " + predecessors);
        Set<Integer> reachable = new HashSet<>();
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        pending.add(0);
        while (!pending.isEmpty()) {
            int at = pending.removeFirst();
            if (reachable.add(at)) pending.addAll(edges.get(at));
        }
        check(
                reachable.contains(capture.index()) && reachable.contains(keyAt),
                "Lyrics capture and key store are reachable from native lookup entry");
    }

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
        verifyCaptureFlow(start, keyAt);
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

    static final String ICONS = "Lapp/bitchord/telegram/PlayerIcons;";
    static final String SLEEVE_STATS = "SleeveNerdStats";

    /** The register an invoke passes as its argument [at]. */
    static int argument(Instruction call, int at) {
        if (call instanceof RegisterRangeInstruction range) return range.getStartRegister() + at;
        FiveRegisterInstruction five = (FiveRegisterInstruction) call;
        return switch (at) {
            case 0 -> five.getRegisterC();
            case 1 -> five.getRegisterD();
            case 2 -> five.getRegisterE();
            case 3 -> five.getRegisterF();
            default -> five.getRegisterG();
        };
    }

    static int register(Instruction i) {
        return ((OneRegisterInstruction) i).getRegisterA();
    }

    /** Where a branch at [from] lands, as an instruction index. */
    static int landing(List<Instruction> code, int from) {
        int[] address = new int[code.size() + 1];
        for (int i = 0; i < code.size(); i++)
            address[i + 1] = address[i] + code.get(i).getCodeUnits();
        int target = address[from] + ((OffsetInstruction) code.get(from)).getCodeOffset();
        for (int i = 0; i < code.size(); i++) if (address[i] == target) return i;
        throw new AssertionError("Branch lands inside an instruction");
    }

    static boolean named(Instruction i, String owner, String name) {
        MethodReference ref = call(i);
        return ref != null && ref.getDefiningClass().equals(owner) && ref.getName().equals(name);
    }

    static final String PLAYER = "Lcom/music/bitchord/ui/player/";

    /**
     * The player's icons, found as the patch finds them: the drag strip is taller by the pair's
     * row, the pair is the strip's child ahead of the caption test, and each artwork slot lifts its
     * stats line, then adds the editor icon after it. Nothing here names a minified lambda.
     */
    static void verifyPlayerIcons(Map<String, ClassDef> types) {
        int strips = 0, modes = 0, lifts = 0, landscapeLifts = 0, above = 0, square = 0;
        for (ClassDef owner : types.values())
            for (Method method : owner.getMethods()) {
                if (method.getImplementation() == null) continue;
                List<Instruction> code = new ArrayList<>();
                method.getImplementation().getInstructions().forEach(code::add);
                boolean portrait =
                        code.stream()
                                .anyMatch(
                                        step ->
                                                step instanceof ReferenceInstruction r
                                                        && r.getReference()
                                                                instanceof StringReference text
                                                        && text.getString()
                                                                .equals("spotifyCanvasDim"));
                boolean hasStats =
                        code.stream()
                                .anyMatch(
                                        step -> {
                                            MethodReference stats = call(step);
                                            return stats != null
                                                    && stats.getName().equals(SLEEVE_STATS);
                                        });
                for (int i = 0; i < code.size(); i++) {
                    MethodReference ref = call(code.get(i));
                    if (ref == null || !ref.getDefiningClass().equals(ICONS)) continue;
                    if (owner.getType().startsWith("Lapp/bitchord/telegram/")) continue;
                    check(
                            !hasStats || ref.getName().startsWith("lift"),
                            "Only the stats lift is placed inside the stats lambdas");
                    switch (ref.getName()) {
                        case "strip" -> {
                            strips++;
                            check(portrait, "The strip height is read in the portrait player");
                            int height = argument(code.get(i), 0);
                            check(
                                    code.get(i + 1).getOpcode() == Opcode.MOVE_RESULT
                                            && register(code.get(i + 1)) == height,
                                    "The strip height is taken from PlayerIcons.strip");
                            MethodReference next = call(code.get(i + 2));
                            check(
                                    next != null
                                            && next.getName().equals("height-3ABfNKs")
                                            && argument(code.get(i + 2), 1) == height,
                                    "The taller height is the one the strip is sized with");
                            int previous = i - 1;
                            while (call(code.get(previous)) == null) previous--;
                            check(
                                    call(code.get(previous)).getName().startsWith("fillMaxWidth"),
                                    "The strip is the full-width box ahead of the handle");
                        }
                        case "modes" -> {
                            modes++;
                            check(portrait, "The pair is placed in the portrait player");
                            check(
                                    code.get(i + 1).getOpcode().name.startsWith("if-"),
                                    "The pair goes in front of the caption's own test");
                            int shown = -1, close = -1;
                            for (int j = i + 2; j < code.size() && close < 0; j++) {
                                if (named(
                                        code.get(j), PLAYER + "NowPlayingScreenKt;", "WhileShown"))
                                    shown = j;
                                if (shown >= 0
                                        && named(
                                                code.get(j),
                                                "Landroidx/compose/runtime/ComposerImpl;",
                                                "endNode")) close = j;
                            }
                            check(shown > 0 && close > 0, "The caption follows the pair");
                            check(
                                    landing(code, i + 1) > shown && landing(code, i + 1) <= close,
                                    "The caption test skips only the caption");
                            check(
                                    argument(code.get(i), 0) == argument(code.get(close), 0),
                                    "The pair takes the strip's composer");
                            boolean handle = false;
                            for (int j = Math.max(0, i - 16); j < i; j++)
                                handle |=
                                        named(
                                                code.get(j),
                                                "Landroidx/compose/foundation/layout/BoxKt;",
                                                "Box");
                            check(handle, "The pair follows the drag handle");
                        }
                        case "liftPortrait", "liftLandscape" -> {
                            lifts++;
                            if (ref.getName().equals("liftLandscape")) landscapeLifts++;
                            int modifier = argument(code.get(i), 0);
                            check(
                                    code.get(i + 1).getOpcode() == Opcode.MOVE_RESULT_OBJECT
                                            && register(code.get(i + 1)) == modifier
                                            && code.get(i + 2).getOpcode() == Opcode.CHECK_CAST
                                            && register(code.get(i + 2)) == modifier,
                                    "The lifted modifier is cast back to a Modifier");
                            MethodReference stats = call(code.get(i + 3));
                            check(
                                    stats != null
                                            && stats.getName().equals(SLEEVE_STATS)
                                            && argument(code.get(i + 3), 1) == modifier,
                                    "The stats line is given the lifted modifier");
                            check(
                                    code.get(i + 4).getOpcode().name.startsWith("goto"),
                                    "Nothing is emitted inside the scaled sleeve after the stats");
                        }
                        case "editorAbove" -> {
                            above++;
                            check(!portrait, "The editor icon is not in the strip");
                            int title = argument(code.get(i), 0);
                            MethodReference offset = call(code.get(i - 2));
                            check(
                                    code.get(i - 1).getOpcode() == Opcode.MOVE_RESULT_OBJECT
                                            && register(code.get(i - 1)) == title
                                            && offset != null
                                            && offset.getName().equals("offset")
                                            && offset.getParameterTypes().size() == 2
                                            && offset.getParameterTypes()
                                                    .get(1)
                                                    .equals("Lkotlin/jvm/functions/Function1;"),
                                    "The icon takes the title row's own offset result");
                            List<String> order = new ArrayList<>();
                            int policy = -1;
                            for (int j = i + 1; j < code.size(); j++) {
                                MethodReference next = call(code.get(j));
                                if (next == null) continue;
                                if (next.getName().equals("startReusableNode")) break;
                                if (List.of(
                                                "height-3ABfNKs",
                                                "onGloballyPositioned",
                                                "rowMeasurePolicy")
                                        .contains(next.getName())) order.add(next.getName());
                                if (next.getName().equals("rowMeasurePolicy")) policy = j;
                            }
                            check(
                                    order.equals(
                                            List.of(
                                                    "height-3ABfNKs",
                                                    "onGloballyPositioned",
                                                    "rowMeasurePolicy")),
                                    "The icon precedes the title row's height, position and"
                                            + " measure");
                            int composer = -1;
                            List<? extends CharSequence> shape =
                                    call(code.get(policy)).getParameterTypes();
                            for (int k = 0; k < shape.size(); k++)
                                if (shape.get(k)
                                        .toString()
                                        .equals("Landroidx/compose/runtime/ComposerImpl;"))
                                    composer = argument(code.get(policy), k);
                            check(
                                    argument(code.get(i), 1) == composer,
                                    "The icon takes the composer the title row uses");
                            int fill = i - 3;
                            while (call(code.get(fill)) == null
                                    || !call(code.get(fill)).getName().startsWith("fillMaxWidth"))
                                fill--;
                            check(fill > 0, "The title row is the full-width modifier, offset");
                        }
                        case "editorOnSquare" -> {
                            square++;
                            check(!portrait, "The landscape icon is not in the strip");
                            int modifier = argument(code.get(i), 0);
                            check(
                                    code.get(i - 1).getOpcode() == Opcode.IF_EQZ
                                            && code.get(i - 2).getOpcode() == Opcode.MOVE_RESULT
                                            && named(
                                                    code.get(i - 3),
                                                    "Landroidx/compose/runtime/ComposerImpl;",
                                                    "shouldExecute")
                                            && argument(code.get(i), 1)
                                                    == argument(code.get(i - 3), 0),
                                    "The landscape icon starts the executed artwork branch");
                            boolean named = false, artwork = false;
                            for (int j = 0; j < code.size(); j++) {
                                named |=
                                        code.get(j) instanceof ReferenceInstruction r
                                                && r.getReference() instanceof StringReference t
                                                && t.getString().equals("artworkModifier");
                                artwork |=
                                        j > i
                                                && named(
                                                        code.get(j),
                                                        PLAYER + "LandscapePlayerKt;",
                                                        "LandscapeArtwork");
                            }
                            check(named && artwork, "It sits ahead of the landscape artwork call");
                            check(modifier < 16, "The artwork modifier is still in its register");
                        }
                        case "editor" -> check(false, "No icon is placed inside the stats lambda");
                        default -> check(false, "Unknown player icon hook " + ref.getName());
                    }
                }
            }
        check(strips == 1 && modes == 1, "One strip height and one pair in the portrait player");
        check(lifts == 2 && landscapeLifts == 1, "One lifted stats line per artwork layout");
        check(
                above == 1 && square == 1,
                "One editor icon beside the title row and one by the square");
        verifySymbols(types);
    }

    /** The host symbols the icons bind to exist, with the shapes the extension looks them up by. */
    static void verifySymbols(Map<String, ClassDef> types) {
        ClassDef icons = types.get(ICONS);
        check(icons != null, "The player icons are in the APK");
        Method symbols = null;
        for (Method method : icons.getMethods())
            if (method.getName().equals("symbols") && method.getParameterTypes().isEmpty())
                symbols = method;
        Instruction first = symbols.getImplementation().getInstructions().iterator().next();
        check(
                first.getOpcode() == Opcode.CONST_STRING
                        && ((StringReference) ((ReferenceInstruction) first).getReference())
                                        .getString()
                                        .length()
                                > 0,
                "The host symbols are filled in");
        Map<String, String> table = new HashMap<>();
        for (String line :
                ((StringReference) ((ReferenceInstruction) first).getReference())
                        .getString()
                        .split("\n"))
            table.put(line.substring(0, line.indexOf('=')), line.substring(line.indexOf('=') + 1));
        for (String key :
                List.of(
                        "androidView",
                        "padding",
                        "companion",
                        "topCenter",
                        "bottomCenter",
                        "childData",
                        "startGroup",
                        "endGroup",
                        "offset",
                        "wrapWidth",
                        "size",
                        "centerH",
                        "zIndex",
                        "minWidth",
                        "minHeight",
                        "maxWidth",
                        "maxHeight")) check(table.containsKey(key), "Host symbol " + key);
        boolean tint = table.containsKey("tint"), chrome = table.containsKey("chrome");
        check(!(tint && chrome), "One player ink");
        check(
                (tint || chrome) == table.containsKey("consume"),
                "The ink local is read with the composer's consume");
        check(chrome == table.containsKey("blend"), "The chrome local comes with its blend helper");
        for (Map.Entry<String, String> entry : table.entrySet()) {
            String ref = entry.getValue();
            String owner = ref.substring(0, ref.indexOf(';') + 1);
            String name =
                    ref.substring(
                            ref.indexOf("->") + 2, ref.indexOf(ref.contains("(") ? '(' : ':'));
            ClassDef type = types.get(owner);
            check(type != null, entry.getKey() + " owner exists");
            boolean found = false;
            if (ref.contains("(")) {
                String parameters = ref.substring(ref.indexOf('(') + 1, ref.indexOf(')'));
                String result = ref.substring(ref.indexOf(')') + 1);
                for (Method method : type.getMethods())
                    found |=
                            method.getName().equals(name)
                                    && String.join("", method.getParameterTypes())
                                            .equals(parameters)
                                    && method.getReturnType().equals(result);
            } else for (Field field : type.getFields()) found |= field.getName().equals(name);
            check(found, entry.getKey() + " exists in the APK: " + ref);
        }
        String blend = table.get("blend");
        if (blend != null) {
            String helper = blend.substring(blend.indexOf("->") + 2, blend.indexOf('('));
            check(
                    table.get("chrome").startsWith(blend.substring(0, blend.indexOf(';') + 1)),
                    "The blend lives with the chrome local");
            int uses = 0;
            for (ClassDef owner : types.values())
                for (Method method : owner.getMethods()) {
                    if (method.getImplementation() == null) continue;
                    boolean portrait = false;
                    int calls = 0;
                    for (Instruction step : method.getImplementation().getInstructions()) {
                        if (step instanceof ReferenceInstruction r
                                && r.getReference() instanceof StringReference text
                                && text.getString().equals("spotifyCanvasDim")) portrait = true;
                        MethodReference ref = call(step);
                        if (ref != null
                                && ref.getName().equals(helper)
                                && ref.getDefiningClass()
                                        .equals(blend.substring(0, blend.indexOf(';') + 1)))
                            calls++;
                    }
                    if (portrait) uses += calls;
                }
            check(uses > 0, "The drag strip itself applies the blend helper the icons bind to");
        }
    }

    static int jumpTarget(List<Instruction> code, int[] at, int index) {
        int target = at[index] + ((OffsetInstruction) code.get(index)).getCodeOffset();
        int found = Arrays.binarySearch(at, target);
        check(found >= 0, "A branch lands on an instruction boundary");
        return found;
    }

    static boolean frameInput(List<Instruction> code, int register, int before, int incoming) {
        for (int depth = 0; depth < 8; depth++) {
            int write = -1;
            for (int i = before - 1; i >= 0; i--)
                if (code.get(i) instanceof OneRegisterInstruction one
                        && code.get(i).getOpcode().setsRegister()
                        && one.getRegisterA() == register) {
                    write = i;
                    break;
                }
            if (write < 0) return register == incoming;
            Opcode opcode = code.get(write).getOpcode();
            if (opcode != Opcode.MOVE_OBJECT
                    && opcode != Opcode.MOVE_OBJECT_FROM16
                    && opcode != Opcode.MOVE_OBJECT_16) return false;
            register = ((TwoRegisterInstruction) code.get(write)).getRegisterB();
            before = write;
        }
        return false;
    }

    /** The window-state gate replaces only the host's landscape test, with its register free. */
    static void verifyFullscreen(Map<String, ClassDef> types) {
        String state = "Lapp/bitchord/telegram/VideoSwitch$Fullscreen;";
        int gates = 0, requests = 0, binds = 0, handlers = 0, portraits = 0;
        for (ClassDef type : types.values())
            for (Method method : type.getMethods()) {
                if (method.getImplementation() == null) continue;
                List<Instruction> code = new ArrayList<>();
                method.getImplementation().getInstructions().forEach(code::add);
                for (int i = 0; i < code.size(); i++) {
                    MethodReference target = call(code.get(i));
                    if (target == null
                            || !target.getDefiningClass().equals(state)
                            || type.getType().startsWith("Lapp/bitchord/telegram/")) continue;
                    if (target.getName().equals("show")) {
                        gates++;
                        int[] at = new int[code.size()];
                        for (int j = 1; j < at.length; j++)
                            at[j] = at[j - 1] + code.get(j - 1).getCodeUnits();
                        check(target.getReturnType().equals("Z"), "The gate answers a boolean");
                        int landscape = ((RegisterRangeInstruction) code.get(i)).getStartRegister();
                        check(
                                code.get(i + 1).getOpcode() == Opcode.MOVE_RESULT
                                        && code.get(i + 2).getOpcode() == Opcode.IF_EQZ
                                        && code.get(i + 3).getOpcode() == Opcode.NOP
                                        && code.get(i + 4).getOpcode() == Opcode.IF_EQZ,
                                "The gate replaces only landscape and keeps the video test");
                        int free = ((OneRegisterInstruction) code.get(i + 1)).getRegisterA();
                        check(
                                free != landscape
                                        && free
                                                != ((OneRegisterInstruction) code.get(i + 4))
                                                        .getRegisterA()
                                        && ((OneRegisterInstruction) code.get(i + 2)).getRegisterA()
                                                == free,
                                "The gate answer is tested in its own register");
                        int other = jumpTarget(code, at, i + 2);
                        check(
                                other == jumpTarget(code, at, i + 4) && other > i + 4,
                                "Both tests leave to the same non-fullscreen path");
                        boolean retest = false;
                        for (int j = other; j < Math.min(code.size(), other + 200); j++) {
                            if (code.get(j).getOpcode() == Opcode.IF_EQZ
                                    && ((OneRegisterInstruction) code.get(j)).getRegisterA()
                                            == landscape) retest = true;
                            if (code.get(j) instanceof OneRegisterInstruction one
                                    && code.get(j).getOpcode().setsRegister()
                                    && one.getRegisterA() == landscape) break;
                        }
                        check(retest, "The landscape layout still reads the host's own value");
                        boolean branchedInto = false;
                        for (int j = 0; j < code.size(); j++)
                            if (code.get(j) instanceof OffsetInstruction
                                    && !(code.get(j) instanceof SwitchPayload)
                                    && j != i + 2
                                    && j != i + 4
                                    && code.get(j).getOpcode() != Opcode.PACKED_SWITCH
                                    && code.get(j).getOpcode() != Opcode.SPARSE_SWITCH
                                    && jumpTarget(code, at, j) == i + 3) branchedInto = true;
                        check(!branchedInto, "Nothing jumps past the gate's invoke");
                    }
                    if (target.getName().equals("bind")) {
                        binds++;
                        check(
                                target.getParameterTypes()
                                                .equals(
                                                        List.of(
                                                                "Lkotlin/jvm/functions/Function0;",
                                                                "Landroidx/compose/runtime/ComposerImpl;"))
                                        && method.getParameterTypes().size() == 9
                                        && method.getParameterTypes()
                                                .get(0)
                                                .equals("Landroidx/compose/ui/Modifier;")
                                        && method.getParameterTypes()
                                                .get(4)
                                                .equals("Lkotlin/jvm/functions/Function0;")
                                        && method.getParameterTypes()
                                                .get(7)
                                                .equals("Landroidx/compose/runtime/ComposerImpl;"),
                                "The back binding receives the native frame overlay and composer");
                        boolean frost = false, overlay = false;
                        for (Field candidate : type.getFields())
                            if (candidate.getName().equals("LocalPlayerFrost")) frost = true;
                        for (Instruction step : code)
                            if (step instanceof ReferenceInstruction r
                                    && r.getReference() instanceof StringReference text
                                    && text.getString().equals("overlayOpen")) overlay = true;
                        int previous = i - 1;
                        if (code.get(previous).getOpcode() == Opcode.MOVE_RESULT_OBJECT) previous--;
                        MethodReference group = call(code.get(previous));
                        check(
                                frost
                                        && overlay
                                        && group != null
                                        && group.getDefiningClass()
                                                .equals("Landroidx/compose/runtime/ComposerImpl;")
                                        && group.getName().equals("startRestartGroup"),
                                "The binding is inside the native overlay frame's restart scope");
                        FiveRegisterInstruction bound = (FiveRegisterInstruction) code.get(i);
                        int parameters = 0, overlayWord = 0, composerWord = 0;
                        for (int n = 0; n < method.getParameterTypes().size(); n++) {
                            if (n == 4) overlayWord = parameters;
                            if (n == 7) composerWord = parameters;
                            String parameter = method.getParameterTypes().get(n).toString();
                            parameters += parameter.equals("J") || parameter.equals("D") ? 2 : 1;
                        }
                        int base = method.getImplementation().getRegisterCount() - parameters;
                        FiveRegisterInstruction restart =
                                (FiveRegisterInstruction) code.get(previous);
                        check(
                                frameInput(code, bound.getRegisterC(), i, base + overlayWord)
                                        && frameInput(
                                                code,
                                                restart.getRegisterC(),
                                                previous,
                                                base + composerWord)
                                        && bound.getRegisterD()
                                                == (previous == i - 1
                                                        ? restart.getRegisterC()
                                                        : ((OneRegisterInstruction) code.get(i - 1))
                                                                .getRegisterA()),
                                "The binding uses the original native overlay/composer aliases");
                    }
                    if (target.getName().equals("requested")) {
                        requests++;
                        check(
                                method.getParameterTypes().equals(List.of("I", "Z"))
                                        && target.getParameterTypes()
                                                .equals(
                                                        List.of(
                                                                "Ljava/lang/Object;",
                                                                "Landroid/app/Activity;",
                                                                "Z")),
                                "The window request captures its native controller and window");
                        FiveRegisterInstruction captured = (FiveRegisterInstruction) code.get(i);
                        TwoRegisterInstruction loaded = (TwoRegisterInstruction) code.get(i - 1);
                        int receiver = method.getImplementation().getRegisterCount() - 3;
                        check(
                                captured.getRegisterCount() == 3
                                        && captured.getRegisterC() == receiver
                                        && captured.getRegisterD() == loaded.getRegisterA()
                                        && captured.getRegisterE() == receiver + 2
                                        && frameInput(code, loaded.getRegisterB(), i - 1, receiver),
                                "The captured controller owns the native orientation window");
                        check(
                                field(code.get(i - 1)) != null
                                        && field(code.get(i - 1))
                                                .getType()
                                                .equals("Landroid/app/Activity;"),
                                "The window request follows the load of its window");
                    }
                }
            }
        check(gates == 1 && binds == 1, "Exactly one window-state gate in the host player");
        for (ClassDef type : types.values())
            if (type.getType().equals(state))
                for (Method method : type.getMethods()) {
                    if (method.getName().equals("portrait")) {
                        portraits++;
                        List<Instruction> body = new ArrayList<>();
                        method.getImplementation().getInstructions().forEach(body::add);
                        MethodReference bridge = body.size() < 2 ? null : call(body.get(1));
                        check(
                                body.size() >= 3
                                        && body.get(0).getOpcode() == Opcode.CHECK_CAST
                                        && body.get(1).getOpcode() == Opcode.INVOKE_VIRTUAL_RANGE
                                        && bridge != null
                                        && bridge.getParameterTypes().isEmpty()
                                        && bridge.getReturnType().equals("V")
                                        && ((TypeReference)
                                                        ((ReferenceInstruction) body.get(0))
                                                                .getReference())
                                                .getType()
                                                .equals(bridge.getDefiningClass()),
                                "Fullscreen exit calls the native orientation controller");
                        ClassDef controller = types.get(bridge.getDefiningClass());
                        Method portrait = null;
                        for (Method candidate : controller.getMethods())
                            if (candidate.getName().equals(bridge.getName())
                                    && candidate.getParameterTypes().isEmpty()
                                    && candidate.getReturnType().equals("V")) portrait = candidate;
                        check(
                                portrait != null && portrait.getImplementation() != null,
                                "Native portrait exit exists");
                        List<Instruction> steps = new ArrayList<>();
                        portrait.getImplementation().getInstructions().forEach(steps::add);
                        boolean nativePortrait = false;
                        for (int n = 0; n < steps.size(); n++) {
                            MethodReference request = call(steps.get(n));
                            if (request == null
                                    || !request.getDefiningClass().equals(controller.getType())
                                    || !request.getParameterTypes().equals(List.of("I", "Z")))
                                continue;
                            FiveRegisterInstruction invoke = (FiveRegisterInstruction) steps.get(n);
                            Integer direction = null, landscape = null;
                            for (int j = 0; j < n; j++) {
                                if (!(steps.get(j) instanceof OneRegisterInstruction reg)
                                        || !steps.get(j).getOpcode().setsRegister()) continue;
                                Integer value =
                                        steps.get(j) instanceof NarrowLiteralInstruction literal
                                                ? literal.getNarrowLiteral()
                                                : null;
                                if (reg.getRegisterA() == invoke.getRegisterD()) direction = value;
                                if (reg.getRegisterA() == invoke.getRegisterE()) landscape = value;
                            }
                            nativePortrait =
                                    Integer.valueOf(1).equals(direction)
                                            && Integer.valueOf(0).equals(landscape)
                                            && frameInput(
                                                    steps,
                                                    invoke.getRegisterC(),
                                                    n,
                                                    portrait.getImplementation().getRegisterCount()
                                                            - 1);
                        }
                        check(
                                nativePortrait,
                                "Native exit requests portrait on its own controller");
                        continue;
                    }
                    if (!method.getName().equals("hostBack")) continue;
                    handlers++;
                    List<Instruction> code = new ArrayList<>();
                    method.getImplementation().getInstructions().forEach(code::add);
                    MethodReference native_ = code.isEmpty() ? null : call(code.get(0));
                    check(
                            native_ != null
                                    && native_.getName().equals("BackHandler")
                                    && native_.getParameterTypes()
                                            .equals(
                                                    List.of(
                                                            "Z",
                                                            "Lkotlin/jvm/functions/Function0;",
                                                            "Landroidx/compose/runtime/ComposerImpl;",
                                                            "I",
                                                            "I")),
                            "The back handler placeholder calls BitChord's own handler");
                    ClassDef owner = types.get(native_.getDefiningClass());
                    boolean dispatcher = false;
                    for (Method host : owner.getMethods()) {
                        if (!host.getName().equals("BackHandler")
                                || host.getImplementation() == null) continue;
                        for (Instruction step : host.getImplementation().getInstructions())
                            if (step instanceof ReferenceInstruction reference
                                    && reference.getReference() instanceof TypeReference type2
                                    && type2.getType()
                                            .equals(
                                                    "Landroidx/activity/OnBackPressedDispatcherOwner;"))
                                dispatcher = true;
                    }
                    check(dispatcher, "The handler registers with the activity's back dispatcher");
                }
        check(portraits == 1, "Exactly one native portrait exit bridge");
        check(handlers == 1, "Exactly one back handler placeholder");
        check(requests == 1, "Exactly one window request report");
    }

    public static void main(String[] args) throws Exception {
        var apk = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.getDefault());
        Map<String, ClassDef> types = new HashMap<>();
        for (String entry : apk.getDexEntryNames())
            for (ClassDef type : apk.getEntry(entry).getDexFile().getClasses())
                types.put(type.getType(), type);
        int slots = 0, controllers = 0, controllerCallers = 0, landscape = 0;
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
                            check(
                                    code.stream()
                                            .noneMatch(
                                                    instruction ->
                                                            instruction
                                                                            instanceof
                                                                            ReferenceInstruction
                                                                                            reference
                                                                    && reference.getReference()
                                                                            instanceof
                                                                            StringReference text
                                                                    && text.getString()
                                                                            .equals(
                                                                                    "spotifyCanvasDim")),
                                    "The portrait pair is placed in the drag strip, not as a"
                                            + " Column row");
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
                                                    .equals("Landroidx/compose/runtime/Updater;")
                                            && previous.getName().equals("set-impl"),
                                    "Landscape slot follows its Column modifier binding");
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
                slots == 1 && landscape == 1,
                "Exactly one measured pair slot in the landscape player layout");
        verifyPlayerIcons(types);
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
        verifyFullscreen(types);
        System.out.println(
                "PASS: "
                        + checks
                        + " native player layout, icon-control and called-host ABI checks");
    }
}

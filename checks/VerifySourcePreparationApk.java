import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;

import java.io.File;
import java.util.*;

/** Proves source-preparation return hooks execute on every branch and retain their real inputs. */
public final class VerifySourcePreparationApk {
    private static final String FIRST = "Lapp/bitchord/telegram/SourceFirst;";
    private static final String CORE = "Lapp/bitchord/telegram/SourcePreparation;";
    private static final String ADAPTER = "Lapp/bitchord/telegram/SourcePreparationHost;";
    private static final String PREFERENCE = "Lapp/bitchord/telegram/SourcePreference;";
    private static final String CACHE = "Lcom/music/bitchord/playback/AudioCache;";
    private static final String CONNECTION = "Lcom/music/bitchord/playback/PlayerConnectionKt;";
    private static final String SERVICE = "Lcom/music/bitchord/playback/PlaybackService;";
    private static final String REGISTRY = "Lcom/music/bitchord/data/sources/SourceRegistry;";
    private static final String SPEC = "Landroidx/media3/datasource/DataSpec;";
    private static final String ITEM = "Landroidx/media3/common/MediaItem;";
    private static final String URI = "Landroid/net/Uri;";
    private static final String STRING = "Ljava/lang/String;";
    private static final Map<String, ClassDef> classes = new HashMap<>();
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static ClassDef type(String owner) {
        ClassDef result = classes.get(owner);
        check(result != null, "Required preparation class: " + owner);
        return result;
    }

    private static List<String> args(MethodReference method) {
        List<String> result = new ArrayList<>();
        for (CharSequence arg : method.getParameterTypes()) result.add(arg.toString());
        return result;
    }

    private static Method method(String owner, String name, String... parameters) {
        List<Method> found = new ArrayList<>();
        for (Method method : type(owner).getMethods())
            if (method.getName().equals(name) && args(method).equals(Arrays.asList(parameters)))
                found.add(method);
        check(found.size() == 1, "Unique preparation ABI: " + owner + "->" + name);
        return found.get(0);
    }

    private static Field member(String owner, String name) {
        List<Field> found = new ArrayList<>();
        for (Field field : type(owner).getFields())
            if (field.getName().equals(name)) found.add(field);
        check(found.size() == 1, "Preparation field: " + owner + "->" + name);
        return found.get(0);
    }

    private static List<Instruction> code(Method method) {
        check(method.getImplementation() != null, "Executable preparation method: " + method);
        List<Instruction> result = new ArrayList<>();
        method.getImplementation().getInstructions().forEach(result::add);
        return result;
    }

    private static MethodReference call(Instruction instruction) {
        return instruction instanceof ReferenceInstruction ref
                        && ref.getReference() instanceof MethodReference method
                ? method
                : null;
    }

    private static FieldReference field(Instruction instruction) {
        return instruction instanceof ReferenceInstruction ref
                        && ref.getReference() instanceof FieldReference field
                ? field
                : null;
    }

    private static boolean calls(Instruction instruction, String owner, String name) {
        MethodReference ref = call(instruction);
        return ref != null && ref.getDefiningClass().equals(owner) && ref.getName().equals(name);
    }

    private static int[] registers(Instruction instruction) {
        if (instruction instanceof RegisterRangeInstruction range) {
            int[] values = new int[range.getRegisterCount()];
            for (int i = 0; i < values.length; i++) values[i] = range.getStartRegister() + i;
            return values;
        }
        if (instruction instanceof FiveRegisterInstruction five)
            return Arrays.copyOf(
                    new int[] {
                        five.getRegisterC(),
                        five.getRegisterD(),
                        five.getRegisterE(),
                        five.getRegisterF(),
                        five.getRegisterG()
                    },
                    five.getRegisterCount());
        throw new AssertionError("Expected invoke argument registers");
    }

    private static boolean writes(Instruction instruction, int register) {
        return instruction.getOpcode().setsRegister()
                && instruction instanceof OneRegisterInstruction one
                && (one.getRegisterA() == register
                        || instruction.getOpcode().setsWideRegister()
                                && one.getRegisterA() + 1 == register);
    }

    private static int parameter(Method method, int parameter) {
        int value = method.getImplementation().getRegisterCount();
        for (String type : args(method)) value -= type.equals("J") || type.equals("D") ? 2 : 1;
        for (int i = 0; i < parameter; i++)
            value += args(method).get(i).equals("J") || args(method).get(i).equals("D") ? 2 : 1;
        return value;
    }

    /** Every reachable normal return must execute this transform, including branch/switch paths. */
    public static void requireTransform(Method method, String owner, String name) {
        requireTransform(method, owner, name, null);
    }

    private static void requireTransform(
            Method method, String owner, String name, String constructed) {
        List<Instruction> body = code(method);
        Map<Integer, Integer> positions = new HashMap<>();
        int[] address = new int[body.size()];
        int pc = 0;
        for (int i = 0; i < body.size(); i++) {
            address[i] = pc;
            positions.put(pc, i);
            pc += body.get(i).getCodeUnits();
        }
        boolean[][] visited = new boolean[2][body.size()];
        Deque<int[]> pending = new ArrayDeque<>();
        pending.add(new int[] {0, 0});
        int returned = 0;
        while (!pending.isEmpty()) {
            int[] path = pending.removeFirst();
            int index = path[0], seen = path[1];
            check(index >= 0 && index < body.size(), "Control flow stays inside " + method);
            if (visited[seen][index]) continue;
            visited[seen][index] = true;
            Instruction line = body.get(index);
            if (calls(line, owner, name)) seen = 1;
            if (constructed != null
                    && line.getOpcode() == Opcode.NEW_INSTANCE
                    && line instanceof ReferenceInstruction ref
                    && ref.getReference() instanceof TypeReference type
                    && type.getType().equals(constructed)) {
                check(seen == 1, "Swap construction bypasses its prepared-entry guard");
                returned++;
            }
            if (line.getOpcode() == Opcode.RETURN_OBJECT) {
                if (constructed == null) {
                    check(
                            seen == 1,
                            "Reachable return bypasses "
                                    + owner
                                    + "->"
                                    + name
                                    + " in "
                                    + method
                                    + " at "
                                    + Integer.toHexString(address[index]));
                    returned++;
                }
                continue;
            }
            if (line instanceof OffsetInstruction branch
                    && line.getOpcode() != Opcode.FILL_ARRAY_DATA) {
                Integer target = positions.get(address[index] + branch.getCodeOffset());
                check(target != null, "Branch has an instruction target in " + method);
                if (line.getOpcode() == Opcode.PACKED_SWITCH
                        || line.getOpcode() == Opcode.SPARSE_SWITCH) {
                    check(
                            body.get(target) instanceof SwitchPayload,
                            "Switch has its payload in " + method);
                    for (SwitchElement element :
                            ((SwitchPayload) body.get(target)).getSwitchElements()) {
                        Integer arm = positions.get(address[index] + element.getOffset());
                        check(arm != null, "Switch arm has an instruction target in " + method);
                        pending.add(new int[] {arm, seen});
                    }
                } else pending.add(new int[] {target, seen});
            }
            if (line.getOpcode().canContinue()) pending.add(new int[] {index + 1, seen});
        }
        check(returned > 0, "Transform has a reachable object return: " + method);
    }

    private static void upgradeGuard() {
        Method point = method(SERVICE, "access$swapPointFor", SERVICE, STRING);
        String constructed = SERVICE.substring(0, SERVICE.length() - 1) + "$SwapPoint;";
        check(point.getReturnType().equals(constructed), "Upgrade check retains SwapPoint ABI");
        requireTransform(point, FIRST, "preparedEntry", constructed);
        List<Instruction> body = code(point);
        int current = -1, guard = -1;
        for (int i = 0; i < body.size(); i++) {
            if (calls(body.get(i), "Landroidx/media3/common/Player;", "getCurrentMediaItem")) {
                check(current == -1, "Upgrade check reads one exact current item");
                current = i;
            }
            if (calls(body.get(i), FIRST, "preparedEntry")) {
                check(guard == -1, "Upgrade check has one prepared-entry guard");
                guard = i;
            }
        }
        check(current >= 0 && guard == current + 2, "Guard immediately follows current item read");
        check(
                body.get(current + 1).getOpcode() == Opcode.MOVE_RESULT_OBJECT,
                "Upgrade guard receives the actual current item result");
        int item = ((OneRegisterInstruction) body.get(current + 1)).getRegisterA();
        check(
                Arrays.equals(registers(body.get(guard)), new int[] {item})
                        && args(call(body.get(guard))).equals(List.of(ITEM))
                        && call(body.get(guard)).getReturnType().equals("Z"),
                "Upgrade guard's current-item register and boolean descriptor are exact");
        check(
                body.get(guard + 1).getOpcode() == Opcode.MOVE_RESULT
                        && body.get(guard + 2).getOpcode() == Opcode.IF_EQZ
                        && body.get(guard + 3).getOpcode() == Opcode.RETURN_OBJECT,
                "Prepared result immediately returns without constructing a swap");
        check(
                ((OneRegisterInstruction) body.get(guard + 1)).getRegisterA()
                        == ((OneRegisterInstruction) body.get(guard + 2)).getRegisterA(),
                "Upgrade branch consumes the actual guard result");
        check(
                ((OffsetInstruction) body.get(guard + 2)).getCodeOffset()
                        == body.get(guard + 2).getCodeUnits() + body.get(guard + 3).getCodeUnits(),
                "Only an unprepared entry reaches the original upgrade path");
        int empty = ((OneRegisterInstruction) body.get(guard + 3)).getRegisterA();
        check(
                body.get(1).getOpcode() == Opcode.CONST_4
                        && ((NarrowLiteralInstruction) body.get(1)).getNarrowLiteral() == 0
                        && ((OneRegisterInstruction) body.get(1)).getRegisterA() == empty,
                "Prepared entry returns the original null swap point");
        for (int i = 2; i < guard + 3; i++)
            check(!writes(body.get(i), empty), "Guard retains its null return register");
        for (String caller : List.of("$swapIn$at$1;", "$swapIn$6;")) {
            Method resume =
                    method(
                            SERVICE.substring(0, SERVICE.length() - 1) + caller,
                            "invokeSuspend",
                            "Ljava/lang/Object;");
            check(
                    code(resume).stream()
                            .anyMatch(line -> calls(line, SERVICE, "access$swapPointFor")),
                    "Both initial and final legacy swap checks reach the guard: " + caller);
        }
    }

    /**
     * Linear adjacency alone is insufficient: a branch can jump straight to a return that has an
     * inserted call immediately before it. Reject every normal, switch and exception edge into the
     * move-result/return tail, proving every returning path executes the replacement call.
     */
    private static List<Integer> wrappedReturns(
            Method method, String owner, String name, int arity) {
        requireTransform(method, owner, name);
        List<Instruction> body = code(method);
        Map<Integer, Integer> index = new HashMap<>();
        int[] address = new int[body.size()];
        int end = 0;
        for (int i = 0; i < body.size(); i++) {
            address[i] = end;
            index.put(end, i);
            end += body.get(i).getCodeUnits();
        }
        Set<Integer> forbidden = new HashSet<>();
        List<Integer> wrappers = new ArrayList<>();
        for (int i = 0; i < body.size(); i++)
            if (body.get(i).getOpcode() == Opcode.RETURN_OBJECT) {
                check(
                        i >= 2
                                && body.get(i - 1).getOpcode() == Opcode.MOVE_RESULT_OBJECT
                                && calls(body.get(i - 2), owner, name),
                        "Every object return uses " + name + ": " + method);
                int result = ((OneRegisterInstruction) body.get(i)).getRegisterA();
                check(
                        ((OneRegisterInstruction) body.get(i - 1)).getRegisterA() == result,
                        "Return consumes the actual " + name + " result");
                int[] inputs = registers(body.get(i - 2));
                check(
                        inputs.length == arity && inputs[inputs.length - 1] == result,
                        name + " receives the original returned value");
                MethodReference target = call(body.get(i - 2));
                check(
                        target.getReturnType().equals(method.getReturnType()),
                        name + " preserves return type");
                wrappers.add(i - 2);
                forbidden.add(address[i - 1]);
                forbidden.add(address[i]);
            }
        check(!wrappers.isEmpty(), "Wrapped return exists: " + method);
        for (int i = 0; i < body.size(); i++) {
            Instruction instruction = body.get(i);
            if (!(instruction instanceof OffsetInstruction offset)) continue;
            int target = address[i] + offset.getCodeOffset();
            if (instruction.getOpcode() == Opcode.FILL_ARRAY_DATA) continue;
            check(index.containsKey(target), "Branch lands on an instruction boundary: " + method);
            if (instruction.getOpcode() == Opcode.PACKED_SWITCH
                    || instruction.getOpcode() == Opcode.SPARSE_SWITCH) {
                Instruction payload = body.get(index.get(target));
                check(payload instanceof SwitchPayload, "Switch payload is available");
                for (SwitchElement element : ((SwitchPayload) payload).getSwitchElements())
                    check(
                            !forbidden.contains(address[i] + element.getOffset()),
                            "Switch cannot bypass " + name + ": " + method);
            } else
                check(
                        !forbidden.contains(target),
                        "Branch cannot bypass "
                                + name
                                + ": "
                                + method
                                + " at "
                                + Integer.toHexString(address[i]));
        }
        for (TryBlock<? extends ExceptionHandler> block : method.getImplementation().getTryBlocks())
            for (ExceptionHandler handler : block.getExceptionHandlers())
                check(
                        !forbidden.contains(handler.getHandlerCodeAddress()),
                        "Handler cannot bypass " + name);
        return wrappers;
    }

    private static void factory() {
        String owner = member(CACHE, "keyFactory").getType();
        Method factory = method(owner, "buildCacheKey", SPEC);
        check(factory.getReturnType().equals(STRING), "Playback cache factory returns String");
        List<Instruction> body = code(factory);
        int at = -1, uriRegister = -1;
        for (int i = 0; i < body.size(); i++) {
            FieldReference ref = field(body.get(i));
            if (body.get(i).getOpcode() == Opcode.IGET_OBJECT
                    && ref != null
                    && ref.getDefiningClass().equals(SPEC)
                    && ref.getName().equals("uri")
                    && ref.getType().equals(URI)) {
                check(at == -1, "Unique cache factory input URI");
                at = i;
                TwoRegisterInstruction read = (TwoRegisterInstruction) body.get(i);
                uriRegister = read.getRegisterA();
                check(
                        read.getRegisterB() == parameter(factory, 0),
                        "Factory URI comes from its actual DataSpec parameter");
            }
        }
        check(at >= 0, "Factory reads the original URI");
        for (int i = at + 1; i < body.size(); i++)
            check(
                    !writes(body.get(i), uriRegister),
                    "Factory retains the URI argument through all returns");
        List<Integer> hooks = wrappedReturns(factory, FIRST, "cacheKey", 2);
        check(hooks.size() == 2, "Both normal and watch cache factory returns are wrapped");
        for (int hook : hooks) {
            int[] actual = registers(body.get(hook));
            check(
                    actual[0] == uriRegister,
                    "cacheKey receives the original entry URI rather than a reused parameter");
            MethodReference ref = call(body.get(hook));
            check(
                    args(ref).equals(List.of(URI, STRING)),
                    "cacheKey argument descriptor matches its register values");
        }
        Method helper = method(CACHE, "cacheKeyOf", URI);
        check(helper.getReturnType().equals(STRING), "Public cache helper returns String");
        List<Instruction> helperCode = code(helper);
        int lookup = -1;
        for (int i = 0; i < helperCode.size(); i++)
            if (calls(helperCode.get(i), owner, "buildCacheKey")) {
                check(lookup == -1, "Public helper delegates once to the real playback factory");
                lookup = i;
            }
        check(
                lookup >= 0 && helperCode.get(lookup + 1).getOpcode() == Opcode.MOVE_RESULT_OBJECT,
                "Public helper receives the frozen key");
        int result = ((OneRegisterInstruction) helperCode.get(lookup + 1)).getRegisterA();
        for (int i = lookup + 2; i < helperCode.size(); i++) {
            check(
                    !writes(helperCode.get(i), result),
                    "Public cache helper preserves the frozen key");
            if (helperCode.get(i).getOpcode() == Opcode.RETURN_OBJECT)
                check(
                        ((OneRegisterInstruction) helperCode.get(i)).getRegisterA() == result,
                        "Public cache helper returns the actual frozen answer");
        }
    }

    private static void taggingAndRanking() {
        Method tag = method(CONNECTION, "toMediaItem", "Lcom/music/bitchord/data/model/Song;");
        check(tag.getReturnType().equals(ITEM), "Song conversion retains MediaItem return type");
        wrappedReturns(tag, FIRST, "tag", 1);
        Method ranked = method(REGISTRY, "activeForPlayback");
        check(
                ranked.getReturnType().equals("Ljava/util/ArrayList;"),
                "Playback source list type remains ArrayList");
        wrappedReturns(ranked, PREFERENCE, "rank", 1);
        Method current = method(SERVICE, "onTrackBecameCurrent", ITEM, "Z", "I", "Z");
        List<Instruction> body = code(current);
        int count = 0;
        for (int i = 0; i < body.size(); i++)
            if (calls(body.get(i), FIRST, "renditionUri")) {
                int[] inputs = registers(body.get(i));
                check(
                        inputs.length == 1
                                && i > 0
                                && body.get(i - 1).getOpcode() == Opcode.IGET_OBJECT,
                        "Current-track bookkeeping maps its actual URI read");
                FieldReference ref = field(body.get(i - 1));
                check(
                        ref != null
                                && ref.getName().equals("uri")
                                && ref.getDefiningClass()
                                        .equals(
                                                "Landroidx/media3/common/MediaItem$LocalConfiguration;")
                                && inputs[0]
                                        == ((OneRegisterInstruction) body.get(i - 1))
                                                .getRegisterA(),
                        "Bookkeeping receives the same queue entry URI");
                count++;
            }
        check(count == 3, "Played cache, folder lookup and upgrade marker all use canonical URI");
    }

    private static void publicStatic(String name, String result, String... parameters) {
        Method method = method(FIRST, name, parameters);
        check(
                (method.getAccessFlags()
                                        & (AccessFlags.PUBLIC.getValue()
                                                | AccessFlags.STATIC.getValue()))
                                == (AccessFlags.PUBLIC.getValue() | AccessFlags.STATIC.getValue())
                        && method.getReturnType().equals(result),
                "Public canonical bridge ABI: " + name + args(method));
    }

    private static void runtimeAbi() {
        publicStatic("tag", ITEM, ITEM);
        publicStatic("renditionUri", URI, ITEM);
        publicStatic("renditionUri", URI, URI);
        publicStatic("cacheKey", STRING, URI, STRING);
        publicStatic("preparedEntry", "Z", ITEM);
        check(
                method(CORE, "cacheKey", URI, STRING).getReturnType().equals(STRING),
                "Core cache identity return ABI");
        for (String name :
                List.of("cacheKeys", "entries", "periods", "periodEntries", "currentEntries"))
            member(CORE, name);
        method(ADAPTER, "<init>", "Ljava/lang/Object;");
        for (Method method : type(FIRST).getMethods())
            if (method.getImplementation() != null)
                for (Instruction line : method.getImplementation().getInstructions()) {
                    MethodReference call = call(line);
                    FieldReference member = field(line);
                    check(
                            call == null
                                    || !call.getDefiningClass().equals(ADAPTER)
                                            && !call.getDefiningClass()
                                                    .equals(
                                                            "Lapp/bitchord/telegram/TelegramMatch;"),
                            "Common facade has no typed optional runtime method dependency");
                    check(
                            member == null
                                    || !member.getDefiningClass().equals(ADAPTER)
                                            && !member.getDefiningClass()
                                                    .equals(
                                                            "Lapp/bitchord/telegram/TelegramMatch;"),
                            "Common facade has no typed optional runtime field dependency");
                }
    }

    private static void reflectionAbi() {
        method(ITEM, "buildUpon");
        String builder = "Landroidx/media3/common/MediaItem$Builder;";
        method(builder, "setUri", STRING);
        method(builder, "build");
        member(builder, "mimeType");
        method("Landroidx/media3/common/Player;", "replaceMediaItem", "I", ITEM);
        String manifest = "Lcom/music/bitchord/download/ManifestFile$write$2;";
        method(
                manifest,
                "<init>",
                STRING,
                "Z",
                "Lkotlin/jvm/functions/Function2;",
                "Ljava/io/File;",
                STRING,
                "Ljava/io/OutputStream;",
                "Ljava/util/Map;",
                "Lkotlin/coroutines/Continuation;");
        method(manifest, "invokeSuspend", "Ljava/lang/Object;");
    }

    private static void absent() {
        int hooks = 0;
        for (ClassDef type : classes.values())
            if (type.getType().startsWith("Lcom/music/bitchord/"))
                for (Method method : type.getMethods())
                    if (method.getImplementation() != null)
                        for (Instruction instruction :
                                method.getImplementation().getInstructions()) {
                            MethodReference ref = call(instruction);
                            if (ref != null && ref.getDefiningClass().equals(FIRST)) hooks++;
                        }
        check(hooks == 0, "Disabled preparation adds no host routing hooks");
    }

    public static void main(String[] command) throws Exception {
        if (command.length < 1 || command.length > 2)
            throw new IllegalArgumentException(
                    "Usage: VerifySourcePreparationApk apk [enabled|disabled|optional]");
        classes.clear();
        checks = 0;
        var container = DexFileFactory.loadDexContainer(new File(command[0]), Opcodes.getDefault());
        for (String name : container.getDexEntryNames())
            for (ClassDef type : container.getEntry(name).getDexFile().getClasses())
                classes.put(type.getType(), type);
        String expected = command.length == 1 ? "enabled" : command[1];
        check(
                List.of("present", "absent", "enabled", "disabled", "optional").contains(expected),
                "Valid preparation checker mode");
        boolean hooks = false;
        for (ClassDef type : classes.values())
            if (type.getType().startsWith("Lcom/music/bitchord/"))
                for (Method method : type.getMethods())
                    if (method.getImplementation() != null)
                        for (Instruction line : method.getImplementation().getInstructions()) {
                            MethodReference call = call(line);
                            if (call != null && call.getDefiningClass().equals(FIRST)) hooks = true;
                        }
        if (expected.equals("absent")
                || expected.equals("disabled")
                || expected.equals("optional") && !hooks) absent();
        else {
            factory();
            taggingAndRanking();
            upgradeGuard();
            runtimeAbi();
            reflectionAbi();
        }
        System.out.println(
                "PASS: "
                        + checks
                        + " source-preparation APK control-flow and input contracts ("
                        + expected
                        + ")");
    }
}

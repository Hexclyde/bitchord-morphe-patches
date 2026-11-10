import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;

import java.io.File;
import java.util.*;

/** Checks source records, exact rendition routing, and their optimized host integration. */
public final class VerifyAudioOriginApk {
    private static final String BASE = "Lapp/bitchord/telegram/";
    private static final String ORIGIN = BASE + "AudioOrigin;";
    private static final String HOST = BASE + "AudioOrigin$Host;";
    private static final String ADAPTER = BASE + "AudioOriginHost;";
    private static final String SAVED = BASE + "AudioOriginSaved;";
    private static final String CACHE = "Lcom/music/bitchord/playback/AudioCache;";
    private static final String SERVICE = "Lcom/music/bitchord/playback/PlaybackService;";
    private static final String DOWNLOADS = "Lcom/music/bitchord/download/Downloads;";
    private static final String ITEM = "Landroidx/media3/common/MediaItem;";
    private static final String SPEC = "Landroidx/media3/datasource/DataSpec;";
    private static final String URI = "Landroid/net/Uri;";
    private static final String STRING = "Ljava/lang/String;";
    private static final String OBJECT = "Ljava/lang/Object;";
    private static final Map<String, ClassDef> types = new HashMap<>();
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static ClassDef type(String name) {
        ClassDef result = types.get(name);
        check(result != null, "Required source-record class: " + name);
        return result;
    }

    private static List<String> parameters(MethodReference method) {
        List<String> result = new ArrayList<>();
        for (CharSequence p : method.getParameterTypes()) result.add(p.toString());
        return result;
    }

    private static Method method(String owner, String name, String... args) {
        List<Method> found = new ArrayList<>();
        for (Method method : type(owner).getMethods())
            if (method.getName().equals(name) && parameters(method).equals(Arrays.asList(args)))
                found.add(method);
        check(found.size() == 1, "Unique source-record ABI: " + owner + "->" + name);
        return found.get(0);
    }

    private static Method named(String owner, String name) {
        List<Method> found = new ArrayList<>();
        for (Method method : type(owner).getMethods())
            if (method.getName().equals(name)) found.add(method);
        check(found.size() == 1, "Unique host method: " + owner + "->" + name);
        return found.get(0);
    }

    private static List<Instruction> code(Method method) {
        check(method.getImplementation() != null, "Executable method: " + method);
        List<Instruction> result = new ArrayList<>();
        method.getImplementation().getInstructions().forEach(result::add);
        return result;
    }

    private static MethodReference call(Instruction instruction) {
        return instruction instanceof ReferenceInstruction r
                        && r.getReference() instanceof MethodReference m
                ? m
                : null;
    }

    private static FieldReference field(Instruction instruction) {
        return instruction instanceof ReferenceInstruction r
                        && r.getReference() instanceof FieldReference f
                ? f
                : null;
    }

    private static String text(Instruction instruction) {
        return instruction instanceof ReferenceInstruction r
                        && r.getReference() instanceof StringReference s
                ? s.getString()
                : null;
    }

    private static List<Integer> sites(Method method, String owner, String name) {
        List<Integer> found = new ArrayList<>();
        List<Instruction> code = code(method);
        for (int i = 0; i < code.size(); i++) {
            MethodReference call = call(code.get(i));
            if (call != null
                    && call.getDefiningClass().equals(owner)
                    && call.getName().equals(name)) found.add(i);
        }
        return found;
    }

    private static int site(Method method, String owner, String name) {
        List<Integer> found = sites(method, owner, name);
        check(found.size() == 1, "One " + name + " hook in " + method);
        return found.get(0);
    }

    private static int[] registers(Instruction instruction) {
        if (instruction instanceof RegisterRangeInstruction r) {
            int[] result = new int[r.getRegisterCount()];
            for (int i = 0; i < result.length; i++) result[i] = r.getStartRegister() + i;
            return result;
        }
        if (instruction instanceof FiveRegisterInstruction r) {
            return Arrays.copyOf(
                    new int[] {
                        r.getRegisterC(),
                        r.getRegisterD(),
                        r.getRegisterE(),
                        r.getRegisterF(),
                        r.getRegisterG()
                    },
                    r.getRegisterCount());
        }
        throw new AssertionError("Expected invoke registers: " + instruction.getOpcode());
    }

    private static int width(String type) {
        return type.equals("J") || type.equals("D") ? 2 : 1;
    }

    private static int parameterRegister(Method method, int index) {
        List<String> args = parameters(method);
        int first = method.getImplementation().getRegisterCount();
        for (String arg : args) first -= width(arg);
        for (int i = 0; i < index; i++) first += width(args.get(i));
        return first;
    }

    /** Follows object moves and invoke results to their actual defining parameter or call. */
    private static String value(Method method, int register, int before) {
        List<Instruction> code = code(method);
        for (int i = before - 1; i >= 0; i--) {
            Instruction instruction = code.get(i);
            if (!(instruction instanceof OneRegisterInstruction r) || r.getRegisterA() != register)
                continue;
            String op = instruction.getOpcode().name();
            if (op.equals("CHECK_CAST")) continue;
            if (op.startsWith("MOVE_OBJECT"))
                return value(method, ((TwoRegisterInstruction) instruction).getRegisterB(), i);
            if (op.equals("MOVE_RESULT_OBJECT")) {
                MethodReference source = i == 0 ? null : call(code.get(i - 1));
                return source == null
                        ? "other"
                        : source.getDefiningClass() + "->" + source.getName();
            }
            if (op.startsWith("CONST_STRING")) return "text:" + text(instruction);
            if (op.equals("IGET_OBJECT") || op.equals("SGET_OBJECT")) {
                FieldReference source = field(instruction);
                return source == null
                        ? "other"
                        : source.getDefiningClass() + "->" + source.getName();
            }
            if (op.startsWith("CONST")
                    || op.startsWith("NEW_")
                    || op.startsWith("AGET")
                    || op.startsWith("IGET")
                    || op.startsWith("SGET")
                    || op.startsWith("MOVE")
                    || op.startsWith("ADD_")
                    || op.startsWith("AND_")) return "other";
        }
        for (int i = 0; i < method.getParameterTypes().size(); i++)
            if (parameterRegister(method, i) == register) return "arg:" + i;
        return "other";
    }

    private static void arguments(Method method, String hook, int... parameters) {
        int at = site(method, ORIGIN, hook);
        int[] regs = registers(code(method).get(at));
        check(regs.length == parameters.length, "Hook arity: " + hook);
        for (int i = 0; i < regs.length; i++)
            check(
                    value(method, regs[i], at).equals("arg:" + parameters[i]),
                    hook + " receives host parameter " + parameters[i]);
        MethodReference target = call(code(method).get(at));
        Method implementation =
                method(ORIGIN, target.getName(), parameters(target).toArray(new String[0]));
        check(
                implementation.getReturnType().equals(target.getReturnType()),
                "Hook return ABI: " + hook);
    }

    private static void publicField(String owner, String name, String expected) {
        boolean found = false;
        for (Field field : type(owner).getFields())
            if (field.getName().equals(name)
                    && (field.getAccessFlags() & AccessFlags.PUBLIC.getValue()) != 0
                    && (expected == null || field.getType().equals(expected))) found = true;
        check(found, "Reflective host field: " + owner + "->" + name);
    }

    private static void canonical() {
        Method active = method(ORIGIN, "activeSource", ITEM);
        Method played = method(ORIGIN, "played", ITEM);
        for (Method method : List.of(active, played)) {
            int at = site(method, HOST, "renditionUri");
            int[] args = registers(code(method).get(at));
            check(
                    args.length == 2 && value(method, args[1], at).equals("arg:0"),
                    method.getName() + " maps the exact MediaItem entry");
            check(
                    sites(method, ORIGIN, "uriOf").isEmpty(),
                    method.getName() + " cannot bypass canonical playback identity");
        }
        int key = site(active, HOST, "keyOf");
        int[] keyArgs = registers(code(active).get(key));
        check(
                keyArgs.length == 2
                        && value(active, keyArgs[1], key).equals(HOST + "->renditionUri"),
                "Active cache key uses the canonical rendition URI");
        Method adapter = method(ADAPTER, "renditionUri", ITEM);
        int reflect = site(adapter, ADAPTER, "call");
        int[] reflectArgs = registers(code(adapter).get(reflect));
        check(
                reflectArgs.length == 5
                        && value(adapter, reflectArgs[1], reflect).equals(ADAPTER + "->sourceFirst")
                        && value(adapter, reflectArgs[2], reflect).equals("text:audibleUri"),
                "Adapter invokes the optional per-entry renditionUri accessor");
        boolean currentArgument = false;
        List<Instruction> adapterCode = code(adapter);
        for (int i = 0; i < reflect; i++) {
            Instruction instruction = adapterCode.get(i);
            if ((instruction.getOpcode() == Opcode.FILLED_NEW_ARRAY
                            || instruction.getOpcode() == Opcode.FILLED_NEW_ARRAY_RANGE)
                    && i + 1 < reflect
                    && adapterCode.get(i + 1).getOpcode() == Opcode.MOVE_RESULT_OBJECT
                    && ((OneRegisterInstruction) adapterCode.get(i + 1)).getRegisterA()
                            == reflectArgs[4]) {
                int[] values = registers(instruction);
                currentArgument |=
                        values.length == 1 && value(adapter, values[0], i).equals("arg:0");
            }
            if (instruction.getOpcode() != Opcode.APUT_OBJECT) continue;
            ThreeRegisterInstruction put = (ThreeRegisterInstruction) instruction;
            currentArgument |=
                    put.getRegisterB() == reflectArgs[4]
                            && value(adapter, put.getRegisterA(), i).equals("arg:0");
        }
        check(
                currentArgument,
                "Reflective canonical accessor receives this exact item, not a by-ID replacement");
        int fallback = site(adapter, ORIGIN, "uriOf");
        check(
                value(adapter, registers(code(adapter).get(fallback))[0], fallback).equals("arg:0")
                        && fallback > reflect,
                "Optional-runtime fallback preserves the original item URI");
        boolean optionalName = false;
        for (Method method : type(ADAPTER).getMethods())
            if (method.getImplementation() != null)
                for (Instruction i : code(method))
                    optionalName |= "app.bitchord.telegram.SourceFirst".equals(text(i));
        check(optionalName, "Canonical runtime is discovered by its optional class name");
        if (types.containsKey(BASE + "SourceFirst;")) {
            Method getter = method(BASE + "SourceFirst;", "audibleUri", ITEM);
            check(
                    getter.getReturnType().equals(URI) && (getter.getAccessFlags() & 9) == 9,
                    "Installed canonical accessor is public static and returns a URI");
        }
    }

    private static void served() {
        Method open = method("Landroidx/media3/datasource/ResolvingDataSource;", "open", SPEC);
        int at = site(open, ORIGIN, "recordServed");
        List<Instruction> lines = code(open);
        int[] args = registers(lines.get(at));
        check(
                args.length == 2 && value(open, args[0], at).equals("arg:0"),
                "Served hook retains the requested spec before the resolver overwrites it");
        check(
                at >= 2 && lines.get(at - 1).getOpcode() == Opcode.MOVE_RESULT_OBJECT,
                "Served hook follows the final resolver result");
        MethodReference resolver = call(lines.get(at - 2));
        check(
                resolver != null
                        && resolver.getReturnType().equals(SPEC)
                        && (resolver.getName().equals("resolveDataSpec")
                                || (resolver.getDefiningClass().equals(BASE + "SourcePreference;")
                                        && resolver.getName().equals("resolve"))),
                "Served hook observes the real resolver or verified-source guard");
        check(
                args[1] == ((OneRegisterInstruction) lines.get(at - 1)).getRegisterA(),
                "Served hook receives the resolved spec, not its original URI");
        onlyPredecessor(open, at, at - 1, "Served record follows only the resolver result");
        onlyPredecessor(open, at - 1, at - 2, "Resolved spec follows only the resolver call");
        onlyPredecessor(
                open,
                at - 2,
                at - 3,
                "Resolver cannot bypass preserving the original requested spec");
        Field resolverField = null;
        for (Field field : type(SERVICE).getFields())
            if (field.getName().equals("streamResolver")) resolverField = field;
        check(resolverField != null, "Host resolver field is present");
        Method source = method(resolverField.getType(), "resolveDataSpec", SPEC);
        int stream = site(source, ORIGIN, "streamResolved");
        Instruction previous = code(source).get(stream - 1);
        check(
                previous.getOpcode() == Opcode.CHECK_CAST
                        && ((TypeReference) ((ReferenceInstruction) previous).getReference())
                                .getType()
                                .equals("Lcom/music/bitchord/data/sources/SourceStream;")
                        && registers(code(source).get(stream))[0]
                                == ((OneRegisterInstruction) previous).getRegisterA(),
                "Source-row capture receives the stream returned by the real source");
        Method runtime = method(ORIGIN, "recordServed", SPEC, STRING);
        site(runtime, HOST, "requestKey");
        site(runtime, ORIGIN, "decide");
        site(runtime, ORIGIN, "record");
        Method key = method(ADAPTER, "requestKey", SPEC);
        check(
                code(key).stream().anyMatch(i -> "key".equals(text(i))),
                "Requests are tagged by the actual DataSpec key");
    }

    private static void stats() {
        Method transition = method(SERVICE, "onTrackBecameCurrent", ITEM, "Z", "I", "Z");
        arguments(transition, "played", 0);
        Method publish = method(SERVICE, "access$publishNerdStats", SERVICE);
        int item = site(publish, ORIGIN, "statsItem");
        int itemRegister = registers(code(publish).get(item))[0];
        check(
                value(publish, itemRegister, item).endsWith("->getCurrentMediaItem"),
                "Stats records the player's current MediaItem, including queue-entry identity");
        String snapshot = "Lcom/music/bitchord/data/NerdStats$Snapshot;";
        Method constructor = named(snapshot, "<init>");
        int sourceParameter = -1;
        for (Instruction i : code(constructor)) {
            FieldReference f = field(i);
            if (i.getOpcode() == Opcode.IPUT_OBJECT
                    && f != null
                    && f.getName().equals("sourceName")) {
                int source = ((TwoRegisterInstruction) i).getRegisterA();
                for (int p = 0; p < constructor.getParameterTypes().size(); p++)
                    if (source == parameterRegister(constructor, p)) sourceParameter = p;
            }
        }
        check(
                sourceParameter >= 0 && parameters(constructor).get(sourceParameter).equals(STRING),
                "Snapshot sourceName is a real constructor string parameter");
        int build = site(publish, snapshot, "<init>");
        int replace = site(publish, ORIGIN, "statsName");
        int word = 1;
        for (int p = 0; p < sourceParameter; p++) word += width(parameters(constructor).get(p));
        int target = registers(code(publish).get(build))[word];
        check(
                replace == build - 2
                        && code(publish).get(replace + 1).getOpcode() == Opcode.MOVE_RESULT_OBJECT
                        && registers(code(publish).get(replace))[0] == target
                        && ((OneRegisterInstruction) code(publish).get(replace + 1)).getRegisterA()
                                == target,
                "Actual source label replaces only the snapshot's sourceName argument");
        onlyPredecessor(
                publish,
                build,
                build - 1,
                "Snapshot construction cannot bypass source-name replacement");
        onlyPredecessor(
                publish, build - 1, replace, "Stats source-name result must follow its hook call");
        check(
                !reachableWithout(publish, build, item),
                "Every snapshot construction path captures its current MediaItem");
        site(method(ORIGIN, "statsName", STRING), ORIGIN, "activeSource");
        if (!types.containsKey(BASE + "PlaybackStats;")) return;
        site(publish, BASE + "PlaybackStats;", "measureLossless");
        List<Method> measurement = new ArrayList<>();
        for (ClassDef owner : types.values()) {
            if (!owner.getInterfaces().contains("Lkotlin/jvm/functions/Function0;")) continue;
            for (Method candidate : owner.getMethods()) {
                if (!candidate.getName().equals("invoke")
                        || !candidate.getParameterTypes().isEmpty()
                        || candidate.getImplementation() == null) continue;
                boolean cache = false, pending = false;
                for (Instruction line : code(candidate)) {
                    FieldReference member = field(line);
                    if (member != null && member.getDefiningClass().equals(SERVICE)) {
                        cache |= member.getName().equals("measuredBitrateCache");
                        pending |= member.getName().equals("measuringBitrate");
                    }
                }
                if (cache && pending) measurement.add(candidate);
            }
        }
        check(measurement.size() == 1, "One asynchronous bitrate measurement callback");
        bitrateMeasurement(measurement.get(0));
    }

    static void bitrateMeasurement(Method callback) {
        List<Instruction> lines = code(callback);
        String prepare = BASE + "SourceFirst;";
        int canonical = site(callback, prepare, "audibleUri");
        int stringify = site(callback, URI, "toString");
        int[] uri = registers(lines.get(canonical));
        check(
                uri.length == 1 && parameters(call(lines.get(canonical))).equals(List.of(URI)),
                "Bitrate maps the exact playback URI");
        check(
                value(callback, uri[0], canonical)
                        .equals(ITEM.substring(0, ITEM.length() - 1) + "$LocalConfiguration;->uri"),
                "Bitrate canonicalization starts with the player's own entry URI");
        check(
                lines.get(canonical + 1).getOpcode() == Opcode.MOVE_RESULT_OBJECT,
                "Bitrate consumes the canonical URI result");
        onlyPredecessor(
                callback,
                canonical + 1,
                canonical,
                "Bitrate URI result cannot bypass its canonical call");
        check(
                value(callback, registers(lines.get(stringify))[0], stringify)
                        .equals(prepare + "->audibleUri"),
                "Bitrate measurement key describes the selected rendition");
        check(
                !reachableWithout(callback, stringify, canonical)
                        && !reachableWithout(callback, stringify, canonical + 1),
                "Every bitrate key path maps its selected rendition first");
        List<String> arguments =
                List.of(URI, "J", SERVICE, STRING, "Lkotlin/coroutines/Continuation;");
        List<Integer> constructors = new ArrayList<>(), cacheReads = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            MethodReference target = call(lines.get(i));
            if (target == null) continue;
            if (target.getName().equals("<init>") && parameters(target).equals(arguments))
                constructors.add(i);
            if (target.getDefiningClass().equals("Ljava/util/concurrent/ConcurrentHashMap;")
                    && target.getName().equals("get")
                    && value(callback, registers(lines.get(i))[0], i)
                            .equals(SERVICE + "->measuredBitrateCache")) cacheReads.add(i);
        }
        check(
                constructors.size() == 1 && cacheReads.size() == 1,
                "One keyed bitrate lookup and one asynchronous size measurement");
        int construct = constructors.get(0), cached = cacheReads.get(0);
        int[] captured = registers(lines.get(construct));
        check(
                captured.length == 7
                        && value(callback, captured[1], construct).equals(prepare + "->audibleUri")
                        && value(callback, captured[5], construct).equals(URI + "->toString")
                        && value(callback, registers(lines.get(cached))[1], cached)
                                .equals(URI + "->toString"),
                "The worker URI and both bitrate cache keys describe the same canonical rendition");
        for (int consumer : List.of(construct, cached))
            check(
                    !reachableWithout(callback, consumer, canonical + 1)
                            && !reachableWithout(callback, consumer, stringify + 1),
                    "No branch can capture an old bitrate URI or key");
        String worker = call(lines.get(construct)).getDefiningClass();
        Method constructor = method(worker, "<init>", arguments.toArray(new String[0]));
        String uriField = null;
        for (Instruction line : code(constructor)) {
            if (line.getOpcode() == Opcode.IPUT_OBJECT
                    && ((TwoRegisterInstruction) line).getRegisterA()
                            == parameterRegister(constructor, 0)) {
                check(uriField == null, "Worker captures its URI once");
                uriField = field(line).getName();
            }
        }
        check(uriField != null, "Worker retains its URI constructor argument");
        String capturedField = worker + "->" + uriField;
        Method create = method(worker, "create", OBJECT, "Lkotlin/coroutines/Continuation;");
        List<Integer> clones = new ArrayList<>();
        List<Instruction> creation = code(create);
        for (int i = 0; i < creation.size(); i++) {
            MethodReference target = call(creation.get(i));
            if (target != null
                    && target.getDefiningClass().equals(worker)
                    && target.getName().equals("<init>")
                    && parameters(target).equals(arguments)) clones.add(i);
        }
        check(clones.size() == 1, "One size-measurement coroutine clone");
        int clone = clones.get(0);
        check(
                value(create, registers(code(create).get(clone))[1], clone).equals(capturedField),
                "The coroutine clone preserves the exact captured URI");
        Method read = method(worker, "invokeSuspend", OBJECT);
        int length = site(read, CACHE, "contentLengthOf");
        check(
                value(read, registers(code(read).get(length))[0], length).equals(capturedField),
                "The asynchronous measurement reads its captured rendition's actual content size");
    }

    /** Actual incoming edges, including switches and exception handlers, rather than layout. */
    private static List<Set<Integer>> predecessors(Method method) {
        List<Instruction> lines = code(method);
        Map<Integer, Integer> index = new HashMap<>();
        int[] addresses = new int[lines.size()];
        int pc = 0;
        List<Set<Integer>> edges = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            addresses[i] = pc;
            index.put(pc, i);
            pc += lines.get(i).getCodeUnits();
            edges.add(new HashSet<>());
        }
        if (!lines.isEmpty()) edges.get(0).add(-1);
        for (int i = 0; i < lines.size(); i++) {
            Instruction line = lines.get(i);
            if (line instanceof PayloadInstruction) continue;
            if (line.getOpcode().canContinue()
                    && i + 1 < lines.size()
                    && !(lines.get(i + 1) instanceof PayloadInstruction)) edges.get(i + 1).add(i);
            if (line instanceof OffsetInstruction offset
                    && line.getOpcode() != Opcode.FILL_ARRAY_DATA) {
                Integer target = index.get(addresses[i] + offset.getCodeOffset());
                check(target != null, "Source hook branch target is an instruction boundary");
                if (line.getOpcode() == Opcode.PACKED_SWITCH
                        || line.getOpcode() == Opcode.SPARSE_SWITCH) {
                    check(
                            lines.get(target) instanceof SwitchPayload,
                            "Source hook switch payload exists");
                    for (SwitchElement element :
                            ((SwitchPayload) lines.get(target)).getSwitchElements()) {
                        Integer destination = index.get(addresses[i] + element.getOffset());
                        check(
                                destination != null,
                                "Source hook switch case is an instruction boundary");
                        edges.get(destination).add(i);
                    }
                } else edges.get(target).add(i);
            }
            if (line.getOpcode().canThrow()) {
                for (TryBlock<? extends ExceptionHandler> block :
                        method.getImplementation().getTryBlocks()) {
                    if (addresses[i] < block.getStartCodeAddress()
                            || addresses[i]
                                    >= block.getStartCodeAddress() + block.getCodeUnitCount())
                        continue;
                    for (ExceptionHandler handler : block.getExceptionHandlers()) {
                        Integer destination = index.get(handler.getHandlerCodeAddress());
                        check(
                                destination != null,
                                "Source hook exception handler is an instruction boundary");
                        edges.get(destination).add(i);
                    }
                }
            }
        }
        return edges;
    }

    private static void onlyPredecessor(Method method, int target, int required, String message) {
        Set<Integer> incoming = predecessors(method).get(target);
        check(
                incoming.equals(Set.of(required)),
                message + ": incoming instruction indices " + incoming);
    }

    private static boolean reachableWithout(Method method, int target, int excluded) {
        List<Set<Integer>> incoming = predecessors(method);
        boolean[] reached = new boolean[incoming.size()];
        ArrayDeque<Integer> waiting = new ArrayDeque<>();
        if (excluded != 0) waiting.add(0);
        while (!waiting.isEmpty()) {
            int at = waiting.removeFirst();
            if (at == target) return true;
            if (reached[at]) continue;
            reached[at] = true;
            for (int next = 0; next < incoming.size(); next++)
                if (next != excluded && !reached[next] && incoming.get(next).contains(at))
                    waiting.add(next);
        }
        return false;
    }

    private static void downloads() {
        arguments(named(DOWNLOADS, "access$transfer"), "transferring", 0, 1, 2);
        arguments(named(DOWNLOADS, "remember"), "remembered", 0, 1, 2);
        Method defaults = named(DOWNLOADS, "remember$default");
        arguments(defaults, "adopting", 4);
        check(
                code(defaults).stream()
                        .anyMatch(
                                i ->
                                        i.getOpcode() == Opcode.AND_INT_LIT8
                                                && ((WideLiteralInstruction) i).getWideLiteral()
                                                        == 8),
                "Adopted files are distinguished by the host's omitted-format flag");
        arguments(method(DOWNLOADS, "forget", STRING), "forgetSaved", 0);
        arguments(method(DOWNLOADS, "clear", STRING), "downloadEnded", 0);
        arguments(method(DOWNLOADS, "fail", STRING, STRING), "downloadEnded", 0);
        for (String name : List.of("sourceRoute", "cachedRoute")) {
            Method router = named(DOWNLOADS, name);
            arguments(
                    router,
                    name.equals("sourceRoute") ? "sourceRouting" : "cacheRouting",
                    router.getParameterTypes().size() - 1);
            List<Instruction> lines = code(router);
            int returns = 0;
            for (int i = 0; i < lines.size(); i++)
                if (lines.get(i).getOpcode() == Opcode.RETURN_OBJECT) {
                    returns++;
                    MethodReference hook = i == 0 ? null : call(lines.get(i - 1));
                    check(
                            hook != null
                                    && hook.getDefiningClass().equals(ORIGIN)
                                    && hook.getName().equals("routed")
                                    && registers(lines.get(i - 1))[0]
                                            == ((OneRegisterInstruction) lines.get(i))
                                                    .getRegisterA(),
                            "Every " + name + " return binds that exact route object");
                    onlyPredecessor(
                            router,
                            i,
                            i - 1,
                            "No branch or handler may bypass routed at a " + name + " return");
                }
            check(returns > 0, "Download router returns a result");
        }
        Method fallback = named(DOWNLOADS, "access$routeFor");
        int at = site(fallback, ORIGIN, "youtubeRoute");
        MethodReference built = at == 0 ? null : call(code(fallback).get(at - 1));
        check(
                built != null
                        && built.getDefiningClass()
                                .equals(DOWNLOADS.substring(0, DOWNLOADS.length() - 1) + "$Route;")
                        && built.getName().equals("<init>")
                        && registers(code(fallback).get(at))[0]
                                == registers(code(fallback).get(at - 1))[0],
                "YouTube fallback tags its own newly constructed route");
        check(
                sites(
                                                fallback,
                                                "Lcom/music/bitchord/playback/StreamResolver;",
                                                "resolveForDownload")
                                        .size()
                                > 0
                        || code(fallback).subList(0, at).stream()
                                .anyMatch(
                                        i ->
                                                call(i) != null
                                                        && call(i).getName()
                                                                .equals("resolveForDownload")),
                "YouTube route follows an actual YouTube download resolve");
    }

    private static void records() {
        for (String name :
                List.of(
                        "AudioOrigin",
                        "AudioOriginHost",
                        "AudioOriginSaved",
                        "AudioOrigin$Source",
                        "AudioOrigin$Host",
                        "AudioOrigin$Stream",
                        "AudioOrigin$Config",
                        "AudioOrigin$Request")) type(BASE + name + ";");
        for (String name : List.of("id", "name", "kind", "codec"))
            publicField(BASE + "AudioOrigin$Source;", name, STRING);
        for (String name : List.of("kbps", "bits", "rate"))
            publicField(BASE + "AudioOrigin$Source;", name, "Ljava/lang/Integer;");
        Method read = method(CACHE, "bitchordOriginRead", STRING, STRING);
        Method write = method(CACHE, "bitchordOriginWrite", STRING, "[" + STRING);
        Method bytes = method(CACHE, "bitchordOriginHasBytes", STRING);
        for (Method bridge : List.of(read, write, bytes)) {
            check((bridge.getAccessFlags() & 9) == 9, "Public static metadata bridge: " + bridge);
            check(code(bridge).size() > 3, "Metadata bridge is implemented: " + bridge);
        }
        check(
                read.getReturnType().equals(STRING)
                        && write.getReturnType().equals("V")
                        && bytes.getReturnType().equals("Z"),
                "Metadata bridge return ABI");
        String store = null;
        for (Field field : type(CACHE).getFields())
            if (field.getName().equals("cache")) store = field.getType();
        check(store != null, "Metadata is stored beside the actual cache resource");
        int metadata = site(read, store, "getContentMetadata");
        check(
                value(read, registers(code(read).get(metadata))[1], metadata).equals("arg:0"),
                "Metadata read uses the exact cache key");
        int mutate = site(write, store, "applyContentMetadataMutations");
        check(
                value(write, registers(code(write).get(mutate))[1], mutate).equals("arg:0"),
                "Metadata mutations use the exact cache key");
        int spans = site(bytes, store, "getCachedSpans");
        check(
                value(bytes, registers(code(bytes).get(spans))[1], spans).equals("arg:0"),
                "Byte proof queries spans on that same rendition key");
        Method record = named(ORIGIN, "record");
        site(record, HOST, "hasBytes");
        check(
                sites(record, HOST, "write").size() >= 4,
                "Record mutations handle stale and mixed entries");
        check(
                code(record).stream().anyMatch(i -> i.getOpcode() == Opcode.MONITOR_ENTER),
                "Concurrent source records are serialized");
        Method saved = named(SAVED, "put");
        site(saved, SAVED, "encode");
        site(saved, "Landroid/content/SharedPreferences$Editor;", "apply");
        Method lookup = method(ORIGIN, "savedSource", STRING, STRING);
        site(lookup, SAVED, "get");
        check(
                sites(lookup, ORIGIN, "rowSource").isEmpty(),
                "A different or unrecorded saved URI cannot inherit a row's provider");
        Method hash = method(SAVED, "digest", STRING);
        site(hash, "Ljava/security/MessageDigest;", "digest");
        check(
                code(hash).stream().anyMatch(i -> "SHA-256".equals(text(i))),
                "Saved file identity persists as a URI digest");
        for (String name :
                List.of("activeSource", "played", "cachedSource", "recordServed", "statsName"))
            for (Method m : type(ORIGIN).getMethods())
                if (m.getName().equals(name))
                    check(code(m).size() > 3, "Implemented source API: " + name);
        for (String id :
                List.of(
                        "bc_src_id",
                        "bc_src_name",
                        "bc_src_kind",
                        "bc_src_proof",
                        "bc_src_codec",
                        "bc_src_kbps",
                        "bc_src_bits",
                        "bc_src_rate",
                        "bc_src_mixed")) {
            boolean found = false;
            for (Method m : type(ORIGIN).getMethods())
                if (m.getImplementation() != null)
                    for (Instruction i : code(m)) found |= id.equals(text(i));
            check(found, "Persistent cache metadata field: " + id);
        }
    }

    private static void independence() {
        Set<String> forbidden =
                Set.of(
                        BASE + "SourceFirst;",
                        BASE + "TelegramBridge;",
                        BASE + "InternalAddon;",
                        BASE + "NativeDownloads;");
        for (ClassDef type : types.values()) {
            if (!type.getType().startsWith(BASE + "AudioOrigin")) continue;
            List<String> referenced = new ArrayList<>();
            referenced.add(type.getSuperclass());
            referenced.addAll(type.getInterfaces());
            for (Field f : type.getFields()) referenced.add(f.getType());
            for (Method m : type.getMethods()) {
                referenced.addAll(parameters(m));
                referenced.add(m.getReturnType());
                if (m.getImplementation() == null) continue;
                for (Instruction i : code(m))
                    if (i instanceof ReferenceInstruction r) {
                        Reference ref = r.getReference();
                        if (ref instanceof TypeReference t) referenced.add(t.getType());
                        if (ref instanceof MethodReference target) {
                            referenced.add(target.getDefiningClass());
                            referenced.addAll(parameters(target));
                            referenced.add(target.getReturnType());
                        }
                        if (ref instanceof FieldReference f) {
                            referenced.add(f.getDefiningClass());
                            referenced.add(f.getType());
                        }
                    }
            }
            for (String reference : referenced) {
                if (reference == null) continue;
                String ref = reference;
                while (ref.startsWith("[")) ref = ref.substring(1);
                check(
                        !forbidden.contains(ref)
                                && !ref.startsWith("Lapp/bitchord/soulseek/")
                                && !ref.startsWith("Lorg/drinkless/tdlib/"),
                        "Source records link without optional runtimes: " + ref);
                if (ref.startsWith(BASE + "AudioOrigin"))
                    check(
                            types.containsKey(ref),
                            "Every source-record helper dependency is packaged: " + ref);
            }
        }
    }

    private static int branchTarget(List<Instruction> code, int at) {
        int address = 0;
        for (int i = 0; i < at; i++) address += code.get(i).getCodeUnits();
        int target = address + ((OffsetInstruction) code.get(at)).getCodeOffset();
        address = 0;
        for (int i = 0; i < code.size(); i++) {
            if (address == target) return i;
            address += code.get(i).getCodeUnits();
        }
        throw new AssertionError("Bridge branch does not target an instruction");
    }

    private static boolean constant(Instruction instruction, int register, int number) {
        return instruction.getOpcode() == Opcode.CONST_4
                && ((OneRegisterInstruction) instruction).getRegisterA() == register
                && ((NarrowLiteralInstruction) instruction).getNarrowLiteral() == number;
    }

    /** Branch tests do not refine a cache register to the null type in ART. */
    private static void bridgeReturns() {
        Method read = method(CACHE, "bitchordOriginRead", STRING, STRING);
        List<Instruction> lines = code(read);
        check(
                lines.size() >= 4
                        && lines.get(0).getOpcode() == Opcode.SGET_OBJECT
                        && lines.get(1).getOpcode() == Opcode.IF_NEZ,
                "String metadata read guards the actual cache field");
        int cache = ((OneRegisterInstruction) lines.get(0)).getRegisterA();
        check(
                field(lines.get(0)).getDefiningClass().equals(CACHE)
                        && field(lines.get(0)).getName().equals("cache")
                        && ((OneRegisterInstruction) lines.get(1)).getRegisterA() == cache,
                "Read guard tests the cache register it loaded");
        check(
                constant(lines.get(2), cache, 0)
                        && lines.get(3).getOpcode() == Opcode.RETURN_OBJECT
                        && ((OneRegisterInstruction) lines.get(3)).getRegisterA() == cache
                        && branchTarget(lines, 1) == 4,
                "Null-cache read explicitly assigns null before returning a String");
        int returns = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).getOpcode() != Opcode.RETURN_OBJECT) continue;
            returns++;
            if (i == 3) continue;
            int register = ((OneRegisterInstruction) lines.get(i)).getRegisterA();
            check(
                    i >= 2
                            && lines.get(i - 1).getOpcode() == Opcode.MOVE_RESULT_OBJECT
                            && ((OneRegisterInstruction) lines.get(i - 1)).getRegisterA()
                                    == register
                            && call(lines.get(i - 2)) != null
                            && call(lines.get(i - 2)).getName().equals("get")
                            && call(lines.get(i - 2)).getReturnType().equals(STRING),
                    "Live-cache object return comes from the String metadata getter");
        }
        check(returns == 2, "Metadata read has only its null and String return paths");

        Method bytes = method(CACHE, "bitchordOriginHasBytes", STRING);
        lines = code(bytes);
        check(
                lines.get(0).getOpcode() == Opcode.SGET_OBJECT
                        && lines.get(1).getOpcode() == Opcode.IF_EQZ,
                "Cached-byte proof guards a null cache");
        int none = branchTarget(lines, 1);
        int empty = site(bytes, "Ljava/util/Collection;", "isEmpty");
        check(
                lines.get(empty + 1).getOpcode() == Opcode.MOVE_RESULT
                        && lines.get(empty + 2).getOpcode() == Opcode.IF_NEZ
                        && ((OneRegisterInstruction) lines.get(empty + 1)).getRegisterA()
                                == ((OneRegisterInstruction) lines.get(empty + 2)).getRegisterA()
                        && branchTarget(lines, empty + 2) == none,
                "Null-cache and empty-span paths share the explicit false result");
        int falseRegister = ((OneRegisterInstruction) lines.get(none)).getRegisterA();
        check(
                constant(lines.get(none), falseRegister, 0)
                        && lines.get(none + 1).getOpcode() == Opcode.RETURN
                        && ((OneRegisterInstruction) lines.get(none + 1)).getRegisterA()
                                == falseRegister,
                "Null/empty cache returns a freshly assigned primitive false");
        int trueRegister = ((OneRegisterInstruction) lines.get(empty + 3)).getRegisterA();
        check(
                constant(lines.get(empty + 3), trueRegister, 1)
                        && lines.get(empty + 4).getOpcode() == Opcode.RETURN
                        && ((OneRegisterInstruction) lines.get(empty + 4)).getRegisterA()
                                == trueRegister,
                "Nonempty cache returns a freshly assigned primitive true");
        check(
                lines.stream().noneMatch(i -> i.getOpcode() == Opcode.RETURN_OBJECT),
                "Boolean metadata bridge cannot return an object register");

        Method write = method(CACHE, "bitchordOriginWrite", STRING, "[" + STRING);
        lines = code(write);
        check(
                lines.get(0).getOpcode() == Opcode.SGET_OBJECT
                        && lines.get(1).getOpcode() == Opcode.IF_EQZ
                        && lines.get(branchTarget(lines, 1)).getOpcode() == Opcode.RETURN_VOID,
                "Null-cache metadata write goes directly to a void return");
        check(
                lines.stream()
                        .noneMatch(
                                i ->
                                        i.getOpcode() == Opcode.RETURN_OBJECT
                                                || i.getOpcode() == Opcode.RETURN
                                                || i.getOpcode() == Opcode.RETURN_WIDE),
                "Metadata write has no typed value-return path");
        Instruction create = lines.get(2);
        MethodReference initialize = call(lines.get(3));
        check(
                create.getOpcode() == Opcode.NEW_INSTANCE
                        && initialize != null
                        && initialize.getName().equals("<init>")
                        && initialize.getReturnType().equals("V")
                        && initialize
                                .getDefiningClass()
                                .equals(
                                        ((TypeReference)
                                                        ((ReferenceInstruction) create)
                                                                .getReference())
                                                .getType())
                        && registers(lines.get(3))[0]
                                == ((OneRegisterInstruction) create).getRegisterA(),
                "Metadata mutator is initialized before any use");
    }

    private static void hookTargets() {
        Map<String, List<String>> signatures = new LinkedHashMap<>();
        signatures.put("recordServed", List.of(SPEC, SPEC));
        signatures.put("streamResolved", List.of(OBJECT));
        signatures.put("played", List.of(ITEM));
        signatures.put("statsItem", List.of(ITEM));
        signatures.put("statsName", List.of(STRING));
        for (String name : List.of("sourceRouting", "cacheRouting", "routed", "youtubeRoute"))
            signatures.put(name, List.of(OBJECT));
        signatures.put("transferring", List.of("Landroid/content/Context;", OBJECT, OBJECT));
        signatures.put("adopting", List.of("I"));
        signatures.put("remembered", List.of(OBJECT, OBJECT, URI));
        signatures.put("forgetSaved", List.of(STRING));
        signatures.put("downloadEnded", List.of(STRING));
        Map<String, Integer> counts = new HashMap<>();
        for (ClassDef type : types.values()) {
            // R8 can move host lambdas into unrelated AndroidX packages.
            if (type.getType().startsWith("Lapp/bitchord/")) continue;
            for (Method method : type.getMethods()) {
                if (method.getImplementation() == null) continue;
                for (Instruction instruction : code(method)) {
                    MethodReference call = call(instruction);
                    if (call == null
                            || !call.getDefiningClass().equals(ORIGIN)
                            || !signatures.containsKey(call.getName())) continue;
                    check(
                            parameters(call).equals(signatures.get(call.getName())),
                            "Exact injected hook parameter ABI: " + call);
                    Method target =
                            method(ORIGIN, call.getName(), parameters(call).toArray(new String[0]));
                    check(
                            target.getImplementation() != null
                                    && (target.getAccessFlags() & 9) == 9
                                    && target.getReturnType().equals(call.getReturnType())
                                    && (instruction.getOpcode() == Opcode.INVOKE_STATIC
                                            || instruction.getOpcode()
                                                    == Opcode.INVOKE_STATIC_RANGE),
                            "Injected hook targets an implemented public static method: " + call);
                    counts.merge(call.getName(), 1, Integer::sum);
                }
            }
        }
        for (String name : signatures.keySet()) {
            int expected = name.equals("routed") ? 6 : name.equals("downloadEnded") ? 2 : 1;
            check(
                    counts.getOrDefault(name, 0) == expected,
                    "Expected installed source hook count: " + name);
        }
    }

    private static void hostAbi() {
        publicField(SPEC, "key", STRING);
        String stream = "Lcom/music/bitchord/data/sources/SourceStream;";
        String format = "Lcom/music/bitchord/data/sources/StreamFormat;";
        publicField(stream, "url", STRING);
        publicField(stream, "sourceConfigId", STRING);
        publicField(stream, "format", format);
        for (String name : List.of("codec", "kbps", "kbpsInferred", "bitDepth", "sampleRateHz"))
            publicField(
                    format,
                    name,
                    name.equals("codec")
                            ? STRING
                            : name.equals("kbpsInferred") ? "Z" : "Ljava/lang/Integer;");
        publicField("Lcom/music/bitchord/data/sources/SourceRegistry;", "configs", null);
        publicField(CACHE, "appContext", "Landroid/content/Context;");
        for (String name : List.of("getId", "getDisplayName", "getKind")) {
            Method getter = method("Lcom/music/bitchord/data/sources/SourceConfig;", name);
            check(
                    (getter.getAccessFlags() & 1) != 0
                            && getter.getReturnType()
                                    .equals(
                                            name.equals("getKind")
                                                    ? "Lcom/music/bitchord/data/sources/SourceKind;"
                                                    : STRING),
                    "Public configured source identity ABI: " + name);
        }
        method("Lkotlinx/coroutines/flow/StateFlow;", "getValue");
        method(CACHE, "cacheKeyOf", URI);
        method(CACHE, "keysOf", STRING);
        method("Lcom/music/bitchord/playback/QualityUpgrade;", "forcedStream", URI);
        method("Lcom/music/bitchord/playback/StreamChoice;", "of", STRING);
        method("Lcom/music/bitchord/playback/StreamChoice;", "isSubstitute", STRING);
        method("Lcom/music/bitchord/data/sources/SourceRegistry;", "config", STRING);
    }

    public static void main(String[] args) throws Exception {
        check(args.length >= 1, "Usage: VerifyAudioOriginApk patched.apk");
        types.clear();
        checks = 0;
        var dex = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.getDefault());
        for (String name : dex.getDexEntryNames())
            for (ClassDef type : dex.getEntry(name).getDexFile().getClasses())
                types.put(type.getType(), type);
        records();
        bridgeReturns();
        canonical();
        served();
        stats();
        downloads();
        hostAbi();
        hookTargets();
        independence();
        System.out.println("PASS: " + checks + " source-record APK contracts");
    }
}

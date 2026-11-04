import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;

import java.io.File;
import java.util.*;

/** Checks the catalogue receiver and worker boundaries in the installed extension bytecode. */
public final class VerifyTelegramCatalogueApk {
    private static final String BASE = "Lapp/bitchord/telegram/";
    private static final String BRIDGE = BASE + "TelegramBridge;";
    private static final String QUEUE = BASE + "TelegramCatalogueUpdates;";
    private static final String SEARCH = BASE + "LiveSearch;";
    private static final String TD_OBJECT = "Lorg/drinkless/tdlib/TdApi$Object;";
    private static final String TD_MESSAGE = "Lorg/drinkless/tdlib/TdApi$Message;";
    private static final String ATOMIC = "Ljava/util/concurrent/atomic/AtomicLong;";
    private static final Map<String, ClassDef> classes = new HashMap<>();
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static List<String> arguments(MethodReference method) {
        List<String> result = new ArrayList<>();
        for (CharSequence type : method.getParameterTypes()) result.add(type.toString());
        return result;
    }

    private static Method method(String owner, String name, String... arguments) {
        ClassDef type = classes.get(owner);
        check(type != null, "Catalogue class exists: " + owner);
        List<Method> found = new ArrayList<>();
        for (Method method : type.getMethods())
            if (method.getName().equals(name) && arguments(method).equals(Arrays.asList(arguments)))
                found.add(method);
        check(found.size() == 1, "Unique catalogue method: " + owner + "->" + name);
        return found.get(0);
    }

    private static List<Instruction> code(Method method) {
        check(method.getImplementation() != null, "Executable catalogue method: " + method);
        List<Instruction> result = new ArrayList<>();
        method.getImplementation().getInstructions().forEach(result::add);
        return result;
    }

    private static MethodReference call(Instruction instruction) {
        return instruction instanceof ReferenceInstruction reference
                        && reference.getReference() instanceof MethodReference method
                ? method
                : null;
    }

    private static FieldReference field(Instruction instruction) {
        return instruction instanceof ReferenceInstruction reference
                        && reference.getReference() instanceof FieldReference field
                ? field
                : null;
    }

    private static boolean calls(Instruction instruction, String owner, String name) {
        MethodReference method = call(instruction);
        return method != null
                && method.getDefiningClass().equals(owner)
                && method.getName().equals(name);
    }

    private static int[] registers(Instruction instruction) {
        if (instruction instanceof RegisterRangeInstruction range) {
            int[] out = new int[range.getRegisterCount()];
            for (int i = 0; i < out.length; i++) out[i] = range.getStartRegister() + i;
            return out;
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
        throw new AssertionError("Catalogue invoke exposes argument registers");
    }

    private static String fieldToken(FieldReference field) {
        return "field:" + field.getDefiningClass() + "->" + field.getName();
    }

    private static int width(String type) {
        return type.equals("J") || type.equals("D") ? 2 : 1;
    }

    private static Map<Integer, Set<String>> copy(Map<Integer, Set<String>> input) {
        Map<Integer, Set<String>> result = new HashMap<>();
        for (var entry : input.entrySet())
            result.put(entry.getKey(), new HashSet<>(entry.getValue()));
        return result;
    }

    private static final class Flow {
        final List<Instruction> body;
        final List<Map<Integer, Set<String>>> before = new ArrayList<>();
        final List<List<Integer>> edges = new ArrayList<>();

        Flow(Method method) {
            body = code(method);
            Map<Integer, Integer> indexes = new HashMap<>();
            int address = 0;
            for (int i = 0; i < body.size(); i++) {
                indexes.put(address, i);
                address += body.get(i).getCodeUnits();
                before.add(null);
            }
            address = 0;
            for (int i = 0; i < body.size(); i++) {
                Instruction line = body.get(i);
                String name = line.getOpcode().name();
                List<Integer> next = new ArrayList<>();
                if (line instanceof OffsetInstruction offset) {
                    check(
                            !name.contains("SWITCH") && !name.equals("FILL_ARRAY_DATA"),
                            "Catalogue boundary has supported direct control flow: " + method);
                    Integer target = indexes.get(address + offset.getCodeOffset());
                    check(target != null, "Catalogue branch lands on an instruction");
                    next.add(target);
                }
                if (!name.startsWith("GOTO")
                        && !name.startsWith("RETURN")
                        && !name.equals("THROW")
                        && i + 1 < body.size()) next.add(i + 1);
                edges.add(next);
                address += line.getCodeUnits();
            }
            int words = (method.getAccessFlags() & 8) == 0 ? 1 : 0;
            for (String type : arguments(method)) words += width(type);
            int register = method.getImplementation().getRegisterCount() - words;
            Map<Integer, Set<String>> initial = new HashMap<>();
            if ((method.getAccessFlags() & 8) == 0) initial.put(register++, Set.of("this"));
            for (int i = 0; i < method.getParameterTypes().size(); i++) {
                int count = width(method.getParameterTypes().get(i).toString());
                for (int part = 0; part < count; part++) initial.put(register++, Set.of("p" + i));
            }
            before.set(0, initial);
            ArrayDeque<Integer> pending = new ArrayDeque<>();
            pending.add(0);
            while (!pending.isEmpty()) {
                int at = pending.removeFirst();
                Map<Integer, Set<String>> after = copy(before.get(at));
                Instruction line = body.get(at);
                if (line.getOpcode().setsRegister() && line instanceof OneRegisterInstruction one) {
                    Set<String> value = Set.of("other");
                    String name = line.getOpcode().name();
                    if (name.startsWith("MOVE") && line instanceof TwoRegisterInstruction two)
                        value = after.getOrDefault(two.getRegisterB(), Set.of("other"));
                    else if (line.getOpcode() == Opcode.CMP_LONG) value = Set.of("compare:" + at);
                    else if (name.startsWith("IGET") || name.startsWith("SGET"))
                        value = Set.of(fieldToken(Objects.requireNonNull(field(line))));
                    int count = line.getOpcode().setsWideRegister() ? 2 : 1;
                    for (int part = 0; part < count; part++)
                        after.put(one.getRegisterA() + part, new HashSet<>(value));
                }
                for (int next : edges.get(at)) {
                    Map<Integer, Set<String>> state = before.get(next);
                    boolean changed = state == null;
                    if (state == null) before.set(next, state = new HashMap<>());
                    for (var entry : after.entrySet())
                        changed |=
                                state.computeIfAbsent(entry.getKey(), key -> new HashSet<>())
                                        .addAll(entry.getValue());
                    if (changed) pending.add(next);
                }
            }
        }

        Set<String> origin(int at, int register) {
            Map<Integer, Set<String>> frame = before.get(at);
            return frame == null ? Set.of() : frame.getOrDefault(register, Set.of("other"));
        }

        void receiverEnqueuesOnReturn() {
            ArrayDeque<int[]> pending = new ArrayDeque<>();
            Set<String> visited = new HashSet<>();
            pending.add(new int[] {0, 0});
            while (!pending.isEmpty()) {
                int[] state = pending.removeFirst();
                int at = state[0], queued = state[1];
                if (!visited.add(at + ":" + queued)) continue;
                if (calls(body.get(at), QUEUE, "accept")) queued = 1;
                if (body.get(at).getOpcode().name().startsWith("RETURN"))
                    check(queued == 1, "Every normal receiver return follows catalogue enqueue");
                for (int next : edges.get(at)) pending.add(new int[] {next, queued});
            }
        }

        void guardedDispatch(int target, String parameter) {
            Set<String> comparisons = new HashSet<>();
            for (int at = 0; at < body.size(); at++)
                if (body.get(at).getOpcode() == Opcode.CMP_LONG
                        && body.get(at) instanceof ThreeRegisterInstruction compare) {
                    Set<String> left = origin(at, compare.getRegisterB());
                    Set<String> right = origin(at, compare.getRegisterC());
                    Set<String> captured = Set.of(parameter),
                            current = Set.of("field:" + BRIDGE + "->generation");
                    if ((left.equals(captured) && right.equals(current))
                            || (right.equals(captured) && left.equals(current)))
                        comparisons.add("compare:" + at);
                }
            check(
                    !comparisons.isEmpty(),
                    "Client dispatch compares captured and current generations");
            ArrayDeque<int[]> pending = new ArrayDeque<>();
            Set<String> visited = new HashSet<>();
            pending.add(new int[] {0, 0, 0});
            while (!pending.isEmpty()) {
                int[] state = pending.removeFirst();
                int at = state[0], held = state[1], equal = state[2];
                if (!visited.add(at + ":" + held + ":" + equal)) continue;
                if (at == target) {
                    check(held == 1, "Client dispatch holds the client lifecycle monitor");
                    check(equal == 1, "Only equal client generations reach receiver dispatch");
                }
                Instruction line = body.get(at);
                if ((line.getOpcode() == Opcode.MONITOR_ENTER
                                || line.getOpcode() == Opcode.MONITOR_EXIT)
                        && origin(at, ((OneRegisterInstruction) line).getRegisterA())
                                .equals(Set.of("field:" + BRIDGE + "->clientState")))
                    held = line.getOpcode() == Opcode.MONITOR_ENTER ? 1 : 0;
                boolean guard =
                        (line.getOpcode() == Opcode.IF_EQZ || line.getOpcode() == Opcode.IF_NEZ)
                                && origin(at, ((OneRegisterInstruction) line).getRegisterA()).size()
                                        == 1
                                && comparisons.contains(
                                        origin(at, ((OneRegisterInstruction) line).getRegisterA())
                                                .iterator()
                                                .next());
                for (int branch = 0; branch < edges.get(at).size(); branch++) {
                    int nextEqual = equal;
                    if (guard)
                        nextEqual = (branch == 0) == (line.getOpcode() == Opcode.IF_EQZ) ? 1 : 0;
                    pending.add(new int[] {edges.get(at).get(branch), held, nextEqual});
                }
            }
        }
    }

    private static void receiverCalls(Method method) {
        for (Instruction line : code(method)) {
            MethodReference call = call(line);
            FieldReference field = field(line);
            check(
                    field == null || !field.getName().equals("catalogueState"),
                    "Receive path does not acquire the database lifecycle lock: " + method);
            if (call == null) continue;
            String owner = call.getDefiningClass();
            check(
                    !owner.equals(BASE + "MainChannel;")
                            && !owner.equals(BASE + "TelegramIndex;")
                            && !owner.equals(BASE + "IndexStore;")
                            && !owner.equals(BASE + "SqliteIndexStore;")
                            && !owner.startsWith("Landroid/database/")
                            && !owner.equals("Ljava/lang/Thread;"),
                    "Receive path has no catalogue database, copying or sleep call: " + call);
        }
    }

    private static void verify() {
        Method selected = method(BRIDGE, "catalogueSelected", "J");
        receiverCalls(selected);
        int contains = 0;
        Set<String> selectionFields = new HashSet<>();
        for (Instruction line : code(selected)) {
            FieldReference member = field(line);
            if (member != null) selectionFields.add(member.getName());
            MethodReference call = call(line);
            if (call == null) continue;
            check(
                    (call.getDefiningClass().equals("Ljava/util/Set;")
                                    && call.getName().equals("contains"))
                            || (call.getDefiningClass().equals("Ljava/lang/Long;")
                                    && call.getName().equals("valueOf")),
                    "Receive selection reads only the published set snapshots");
            if (call.getName().equals("contains")) contains++;
        }
        check(
                contains == 2 && selectionFields.equals(Set.of("selected", "catalogueAuxiliary")),
                "Receive selection checks ordinary and auxiliary chats");
        Field auxiliary = null;
        for (Field field : classes.get(BRIDGE).getFields())
            if (field.getName().equals("catalogueAuxiliary")) auxiliary = field;
        check(
                auxiliary != null
                        && (auxiliary.getAccessFlags() & AccessFlags.VOLATILE.getValue()) != 0,
                "Auxiliary receive selection is published through a volatile snapshot");

        Method revision = method(BRIDGE, "nextCatalogueRevision");
        int increments = 0;
        boolean counter = false;
        for (Instruction line : code(revision)) {
            FieldReference member = field(line);
            if (member != null) {
                check(
                        member.getDefiningClass().equals(BRIDGE)
                                && member.getName().equals("catalogueRevision")
                                && member.getType().equals(ATOMIC),
                        "Catalogue revision reads its own atomic counter");
                counter = true;
            }
            check(
                    line.getOpcode() != Opcode.MONITOR_ENTER
                            && line.getOpcode() != Opcode.MONITOR_EXIT,
                    "Catalogue revision does not acquire the database lifecycle lock");
            MethodReference call = call(line);
            if (call != null) {
                check(
                        calls(line, ATOMIC, "incrementAndGet"),
                        "Catalogue revision uses its atomic counter");
                increments++;
            }
        }
        check(increments == 1 && counter, "One atomic catalogue revision increment");
        receiverCalls(method(BRIDGE, "clearCatalogueLookups"));

        Method update = method(BRIDGE, "update", TD_OBJECT, "J");
        receiverCalls(update);
        Flow updateFlow = new Flow(update);
        int accepts = 0;
        for (int i = 0; i < updateFlow.body.size(); i++)
            if (calls(updateFlow.body.get(i), QUEUE, "accept")) {
                int[] input = registers(updateFlow.body.get(i));
                check(
                        input.length == 4
                                && updateFlow.origin(i, input[1]).equals(Set.of("p0"))
                                && updateFlow.origin(i, input[2]).equals(Set.of("p1")),
                        "Receiver enqueues the exact event and captured generation parameters");
                accepts++;
            }
        check(accepts == 1, "One catalogue enqueue boundary exists");
        updateFlow.receiverEnqueuesOnReturn();
        int callers = 0;
        for (ClassDef type : classes.values())
            for (Method caller : type.getMethods()) {
                if (caller.getImplementation() == null) continue;
                List<Instruction> body = code(caller);
                for (int at = 0; at < body.size(); at++) {
                    if (!calls(body.get(at), BRIDGE, "update")) continue;
                    callers++;
                    Flow flow = new Flow(caller);
                    List<String> parameters = arguments(caller);
                    List<String> longs = new ArrayList<>();
                    for (int i = 0; i < parameters.size(); i++)
                        if (parameters.get(i).equals("J")) longs.add("p" + i);
                    int[] input = registers(body.get(at));
                    check(
                            longs.size() == 1
                                    && input.length == 4
                                    && flow.origin(at, input[2]).equals(Set.of(longs.get(0))),
                            "Client dispatch passes its captured generation rather than the global"
                                    + " field");
                    flow.guardedDispatch(at, longs.get(0));
                }
            }
        check(callers == 1, "Only the generation-bound client dispatcher invokes the receiver");

        int metadata = 0;
        for (ClassDef type : classes.values()) {
            if (!type.getInterfaces().contains(BASE + "TelegramCatalogueWorker$Host;")) continue;
            Method lookup = method(type.getType(), "lookup", "J", "J");
            check(
                    lookup.getReturnType().equals(TD_MESSAGE),
                    "Catalogue metadata has the typed TDLib result");
            for (Instruction line : code(lookup)) {
                if (calls(line, SEARCH, "metadata")) metadata++;
                check(
                        !calls(line, BRIDGE, "call"),
                        "Catalogue metadata cannot bypass the shared request gate");
            }
        }
        check(metadata == 1, "The real catalogue host uses the shared metadata cooldown");
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 1, "Usage: VerifyTelegramCatalogueApk patched.apk");
        var dex = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.getDefault());
        for (String entry : dex.getDexEntryNames())
            for (ClassDef type : dex.getEntry(entry).getDexFile().getClasses())
                classes.put(type.getType(), type);
        verify();
        System.out.println("PASS: " + checks + " catalogue receiver bytecode contracts");
    }
}

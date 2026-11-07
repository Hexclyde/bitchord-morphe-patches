import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;

import java.io.File;
import java.util.*;

/** Walk the actual dispatch branches for each native URL and an ordinary HTTPS URL. */
public final class VerifyNativeDispatchApk {
    static final String TG = "Lapp/bitchord/telegram/";
    static final String SK = "Lapp/bitchord/soulseek/";

    static MethodReference call(Instruction instruction) {
        return instruction instanceof ReferenceInstruction r
                        && r.getReference() instanceof MethodReference method
                ? method
                : null;
    }

    static String owner(MethodReference method, String name) {
        if (method == null || !method.getName().equals(name)) return null;
        if (method.getDefiningClass()
                .equals(TG + (name.equals("reserved") ? "InternalAddon;" : "NativeDownloads;")))
            return "telegram";
        if (method.getDefiningClass()
                .equals(SK + (name.equals("reserved") ? "SoulseekAddon;" : "SoulseekDownloads;")))
            return "soulseek";
        return null;
    }

    static String route(List<Instruction> code, int start, String input) {
        String predicate = null;
        for (int i = start, steps = 0; i < code.size() && steps++ < 80; i++) {
            Instruction instruction = code.get(i);
            MethodReference method = call(instruction);
            String tested = owner(method, "reserved");
            if (tested != null) predicate = tested;
            String fetched = owner(method, "fetch");
            if (fetched != null) return fetched;
            if (instruction.getOpcode() == Opcode.NEW_INSTANCE
                    && instruction instanceof ReferenceInstruction ref
                    && ref.getReference().toString().equals("Lokhttp3/Request$Builder;"))
                return "http";
            if (instruction.getOpcode() == Opcode.IF_EQZ && predicate != null) {
                if (i == 0
                        || code.get(i - 1).getOpcode() != Opcode.MOVE_RESULT
                        || ((OneRegisterInstruction) code.get(i - 1)).getRegisterA()
                                != ((OneRegisterInstruction) instruction).getRegisterA())
                    throw new AssertionError("Dispatch condition must read its own predicate");
                if (!predicate.equals(input)) {
                    int target = code.indexOf(VerifyApk.branchTarget(code, i));
                    if (target <= i) throw new AssertionError("Fallback must move forward");
                    i = target - 1;
                }
                predicate = null;
            }
        }
        throw new AssertionError("Native dispatch did not reach a download route");
    }

    static boolean bodyHook(Instruction instruction) {
        MethodReference method = call(instruction);
        return method != null
                && method.getName().equals("bodyOrNull")
                && (method.getDefiningClass().equals(TG + "InternalAddon;")
                        || method.getDefiningClass().equals(SK + "SoulseekAddon;"));
    }

    static int[] addresses(List<Instruction> code) {
        int[] at = new int[code.size()];
        for (int i = 1; i < at.length; i++) at[i] = at[i - 1] + code.get(i - 1).getCodeUnits();
        return at;
    }

    static int indexAt(int[] address, int unit) {
        int index = Arrays.binarySearch(address, unit);
        if (index < 0) throw new AssertionError("Branch does not land on an instruction");
        return index;
    }

    static int target(List<Instruction> code, int[] address, int from) {
        return indexAt(
                address, address[from] + ((OffsetInstruction) code.get(from)).getCodeOffset());
    }

    static boolean readsClassId(Instruction instruction, String owner) {
        return instruction.getOpcode() == Opcode.IGET
                && instruction instanceof ReferenceInstruction ref
                && ref.getReference() instanceof FieldReference field
                && field.getDefiningClass().equals(owner)
                && field.getName().equals("$r8$classId");
    }

    /** Cases of the host's dispatch switch from which the request URL call can be reached. */
    static Set<Integer> requestCases(
            List<Instruction> code, int[] address, int switchAt, int urlAt) {
        Set<Integer> owning = new TreeSet<>();
        var payload = (SwitchPayload) code.get(target(code, address, switchAt));
        for (SwitchElement element : payload.getSwitchElements()) {
            Set<Integer> seen = new HashSet<>();
            Deque<Integer> pending =
                    new ArrayDeque<>(
                            List.of(indexAt(address, address[switchAt] + element.getOffset())));
            while (!pending.isEmpty()) {
                int at = pending.removeLast();
                if (at >= code.size() || !seen.add(at)) continue;
                Instruction instruction = code.get(at);
                Opcode opcode = instruction.getOpcode();
                if (instruction instanceof SwitchPayload
                        || instruction.getClass().getSimpleName().contains("ArrayPayload"))
                    continue;
                if (opcode == Opcode.PACKED_SWITCH || opcode == Opcode.SPARSE_SWITCH) {
                    var inner = (SwitchPayload) code.get(target(code, address, at));
                    for (SwitchElement each : inner.getSwitchElements())
                        pending.add(indexAt(address, address[at] + each.getOffset()));
                    pending.add(at + 1);
                } else if (instruction instanceof OffsetInstruction) {
                    pending.add(target(code, address, at));
                    if (!opcode.name.startsWith("goto")) pending.add(at + 1);
                } else if (!opcode.name.startsWith("return") && opcode != Opcode.THROW)
                    pending.add(at + 1);
            }
            if (seen.contains(urlAt)) owning.add(element.getKey());
        }
        return owning;
    }

    /**
     * Runs the entry of the merged request coroutine for every class id, holding an object that is
     * not a string, as the other coroutines do. Only the addon branch may reach the URL cast.
     */
    static int metadata(ClassDef cls, Method method, List<Instruction> code) {
        int last = -1;
        for (int i = 0; i < code.size(); i++) if (bodyHook(code.get(i))) last = i;
        if (last < 0) return 0;
        VerifyApk.check(
                last + 3 < code.size() && code.get(last + 3).getOpcode() == Opcode.RETURN_OBJECT,
                "Metadata hook returns its answer");
        int original = last + 4;
        int[] address = addresses(code);
        int self =
                method.getImplementation().getRegisterCount()
                        - method.getParameterTypes().size()
                        - 1;
        int urlAt = -1, switchAt = -1;
        for (int i = original; i < code.size(); i++) {
            MethodReference call = call(code.get(i));
            if (call != null
                    && call.getDefiningClass().equals("Lokhttp3/Request$Builder;")
                    && call.getName().equals("url")) urlAt = i;
            if (switchAt < 0 && readsClassId(code.get(i), cls.getType())) switchAt = i + 1;
        }
        VerifyApk.check(urlAt > 0, "Metadata host builds the request");
        boolean merged = switchAt >= 0;
        if (merged)
            while (code.get(switchAt).getOpcode() != Opcode.PACKED_SWITCH
                    && code.get(switchAt).getOpcode() != Opcode.SPARSE_SWITCH
                    && switchAt < original + 12) switchAt++;
        Set<Integer> reaching = new TreeSet<>();
        for (int id = -1; id <= 70; id++) {
            String outcome = entry(code, address, self, original, id);
            if (outcome.equals("original")) continue;
            VerifyApk.check(
                    outcome.equals("hook") || outcome.equals("url cast"),
                    "Metadata entry only answers or continues: " + outcome);
            reaching.add(id);
        }
        if (!merged) {
            VerifyApk.check(reaching.size() == 72, "An unmerged request is hooked for every entry");
            return 1;
        }
        Set<Integer> cases = requestCases(code, address, switchAt, urlAt);
        VerifyApk.check(
                reaching.size() == 1 && cases.equals(reaching),
                "Only the dispatch case that builds the request reaches the metadata hook: "
                        + reaching
                        + " vs "
                        + cases);
        return 1;
    }

    /**
     * Interprets the entry up to the first original instruction; strings are told by their cast.
     */
    static String entry(List<Instruction> code, int[] address, int self, int original, int id) {
        Map<Integer, Object> registers = new HashMap<>();
        registers.put(self, "this");
        Object result = null;
        String answered = null;
        for (int at = 0, steps = 0; steps++ < 200; at++) {
            if (at == original) return answered == null ? "original" : answered;
            Instruction instruction = code.get(at);
            Opcode opcode = instruction.getOpcode();
            int a = instruction instanceof OneRegisterInstruction one ? one.getRegisterA() : -1;
            int b = instruction instanceof TwoRegisterInstruction two ? two.getRegisterB() : -1;
            switch (opcode) {
                case MOVE_OBJECT_FROM16 -> registers.put(a, registers.get(b));
                case IGET -> registers.put(a, id);
                case IGET_OBJECT -> registers.put(a, new Object());
                case CONST_4, CONST_16 ->
                        registers.put(
                                a, (int) ((WideLiteralInstruction) instruction).getWideLiteral());
                case IF_NE -> {
                    if (!Objects.equals(registers.get(a), registers.get(b)))
                        at = target(code, address, at) - 1;
                }
                case IF_EQZ -> {
                    if (registers.get(a) == null) at = target(code, address, at) - 1;
                }
                case GOTO, GOTO_16, GOTO_32 -> at = target(code, address, at) - 1;
                case CHECK_CAST -> {
                    if (((ReferenceInstruction) instruction)
                                    .getReference()
                                    .toString()
                                    .equals("Ljava/lang/String;")
                            && !(registers.get(a) instanceof String)) return "url cast";
                }
                case INVOKE_STATIC -> {
                    VerifyApk.check(
                            bodyHook(instruction), "Entry calls only native metadata hooks");
                    answered = "hook";
                    result = null;
                }
                case MOVE_RESULT_OBJECT -> registers.put(a, result);
                default -> throw new AssertionError("Unexpected entry instruction " + opcode);
            }
        }
        throw new AssertionError("Metadata entry did not finish");
    }

    public static void main(String[] args) throws Exception {
        var dex = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.getDefault());
        int dispatchers = 0, cases = 0, metadataHosts = 0;
        for (String entry : dex.getDexEntryNames())
            for (ClassDef cls : dex.getEntry(entry).getDexFile().getClasses()) {
                for (Method method : cls.getMethods()) {
                    if (method.getImplementation() == null) continue;
                    List<Instruction> code = new ArrayList<>();
                    method.getImplementation().getInstructions().forEach(code::add);
                    metadataHosts += metadata(cls, method, code);
                    if (code.stream().noneMatch(i -> owner(call(i), "fetch") != null)) continue;
                    Set<String> handlers = new HashSet<>();
                    int start = -1;
                    for (int i = 0; i < code.size(); i++) {
                        String found = owner(call(code.get(i)), "reserved");
                        if (found != null) {
                            handlers.add(found);
                            if (start < 0) start = i;
                        }
                    }
                    if (start < 0) continue;
                    dispatchers++;
                    for (String input : List.of("telegram", "soulseek", "http")) {
                        String expected = handlers.contains(input) ? input : "http";
                        String actual = route(code, start, input);
                        if (!expected.equals(actual))
                            throw new AssertionError(
                                    input + " URL reached " + actual + "; expected " + expected);
                        cases++;
                    }
                }
            }
        if (metadataHosts > 1)
            throw new AssertionError("Native metadata must have one host request path");
        if (dispatchers > 1) throw new AssertionError("Native dispatch must have one host IO path");
        System.out.println(
                "PASS: "
                        + cases
                        + " actual native/HTTPS dispatch paths; dispatchers="
                        + dispatchers
                        + " metadataHosts="
                        + metadataHosts);
    }
}

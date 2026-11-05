import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.dexlib2.immutable.instruction.*;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;

import java.io.File;
import java.util.*;

/**
 * Checks that FLAC tag headers pass their comment block through the merge helper, or not at all.
 */
public final class VerifyFlacMetadataApk {
    private static final String HELPER = "Lapp/bitchord/telegram/FlacMetadata;";
    private static int checks;

    private static void check(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }

    private static MethodReference call(Instruction instruction) {
        return instruction instanceof ReferenceInstruction ref
                        && ref.getReference() instanceof MethodReference method
                ? method
                : null;
    }

    private static List<String> args(MethodReference method) {
        return method.getParameterTypes().stream().map(Object::toString).toList();
    }

    private static boolean merge(MethodReference method) {
        return method != null
                && method.getDefiningClass().equals(HELPER)
                && method.getName().equals("mergeMetadata");
    }

    private static int register(Instruction instruction) {
        return ((OneRegisterInstruction) instruction).getRegisterA();
    }

    private static int[] arguments(Instruction instruction) {
        FiveRegisterInstruction five = (FiveRegisterInstruction) instruction;
        int[] all = {
            five.getRegisterC(),
            five.getRegisterD(),
            five.getRegisterE(),
            five.getRegisterF(),
            five.getRegisterG()
        };
        return Arrays.copyOf(all, five.getRegisterCount());
    }

    private static String describe(Instruction instruction) {
        StringBuilder text = new StringBuilder(instruction.getOpcode().toString());
        if (instruction instanceof ReferenceInstruction ref)
            text.append(' ').append(ref.getReference());
        if (instruction instanceof FiveRegisterInstruction)
            text.append(Arrays.toString(arguments(instruction)));
        else if (instruction instanceof RegisterRangeInstruction range)
            text.append(range.getStartRegister()).append(':').append(range.getRegisterCount());
        else {
            if (instruction instanceof OneRegisterInstruction one)
                text.append(" a").append(one.getRegisterA());
            if (instruction instanceof TwoRegisterInstruction two)
                text.append(" b").append(two.getRegisterB());
            if (instruction instanceof ThreeRegisterInstruction three)
                text.append(" c").append(three.getRegisterC());
        }
        if (instruction instanceof WideLiteralInstruction literal)
            text.append(" #").append(literal.getWideLiteral());
        return text.toString();
    }

    private static boolean sets(Instruction instruction, int register) {
        return instruction instanceof OneRegisterInstruction one
                && instruction.getOpcode().setsRegister()
                && (one.getRegisterA() == register
                        || (instruction.getOpcode().setsWideRegister()
                                && one.getRegisterA() + 1 == register));
    }

    /** Verifies the six inserted instructions and the tuple they guard; returns the call index. */
    private static int verifyTail(List<Instruction> code, int parameters, String type) {
        int at = -1;
        for (int i = 0; i < code.size(); i++) {
            MethodReference ref = call(code.get(i));
            if (!merge(ref)) continue;
            check(at < 0, "The header calls the merge helper once");
            at = i;
            check(
                    code.get(i).getOpcode() == Opcode.INVOKE_STATIC
                            && args(ref).equals(List.of("[B", "[B", "I"))
                            && ref.getReturnType().equals("[B"),
                    "The helper keeps its ([B[BI)[B ABI");
        }
        check(at >= 5 && at + 7 < code.size(), "The header calls the merge helper");
        Instruction move = code.get(at - 1);
        check(move.getOpcode() == Opcode.MOVE_OBJECT_FROM16, "The first argument is copied");
        int scratch = register(move);
        check(
                ((TwoRegisterInstruction) move).getRegisterB() == parameters,
                "The copied value is the original first argument");
        int[] merged = arguments(code.get(at));
        check(merged.length == 3 && merged[0] == scratch, "The helper receives the copy");
        int metadata = merged[1];
        int offset = merged[2];
        Instruction result = code.get(at + 1);
        check(
                result.getOpcode() == Opcode.MOVE_RESULT_OBJECT && register(result) == metadata,
                "The merged block replaces the metadata register");
        Instruction test = code.get(at + 2);
        check(
                test.getOpcode() == Opcode.IF_NEZ
                        && register(test) == metadata
                        && ((OffsetInstruction) test).getCodeOffset()
                                == test.getCodeUnits()
                                        + code.get(at + 3).getCodeUnits()
                                        + code.get(at + 4).getCodeUnits(),
                "A null merge result branches past the null return");
        Instruction clear = code.get(at + 3);
        check(
                clear.getOpcode() == Opcode.CONST_4
                        && register(clear) == scratch
                        && ((NarrowLiteralInstruction) clear).getNarrowLiteral() == 0,
                "A failed merge clears the scratch register");
        Instruction none = code.get(at + 4);
        check(
                none.getOpcode() == Opcode.RETURN_OBJECT && register(none) == scratch,
                "A failed merge returns no header");
        Instruction kind = code.get(at + 5);
        check(
                kind instanceof NarrowLiteralInstruction && register(kind) == scratch,
                "The scratch register is overwritten by the original discriminator");
        Instruction build = code.get(at + 6);
        MethodReference constructor = call(build);
        check(
                build.getOpcode() == Opcode.INVOKE_DIRECT
                        && constructor != null
                        && constructor.getName().equals("<init>")
                        && constructor.getDefiningClass().equals(type)
                        && args(constructor).equals(List.of("Ljava/lang/Object;", "I", "I")),
                "The original result constructor follows");
        int[] built = arguments(build);
        check(
                built.length == 4
                        && built[1] == metadata
                        && built[2] == offset
                        && built[3] == scratch,
                "The constructor receives the merged block, the original offset and the"
                        + " discriminator");
        Instruction finish = code.get(at + 7);
        check(
                finish.getOpcode() == Opcode.RETURN_OBJECT && register(finish) == built[0],
                "The result is returned");
        MethodReference bytes = call(code.get(at - 4));
        check(
                code.get(at - 5).getOpcode() == Opcode.NEW_INSTANCE
                        && register(code.get(at - 5)) == built[0]
                        && code.get(at - 4).getOpcode() == Opcode.INVOKE_VIRTUAL
                        && bytes != null
                        && bytes.getName().equals("toByteArray")
                        && arguments(code.get(at - 4)).length == 1
                        && code.get(at - 3).getOpcode() == Opcode.MOVE_RESULT_OBJECT
                        && register(code.get(at - 3)) == metadata,
                "The comment block comes from the rebuilt stream");
        MethodReference verify = call(code.get(at - 2));
        check(
                verify != null
                        && verify.getName().equals("checkNotNullExpressionValue")
                        && Arrays.equals(
                                arguments(code.get(at - 2)), new int[] {metadata, scratch}),
                "The comment block is checked before the merge");
        Set<Integer> used = new HashSet<>(List.of(built[0], metadata, offset, scratch));
        check(used.size() == 4, "Tuple registers are distinct");
        check(
                used.stream().allMatch(r -> r >= 0 && r <= 15 && r < parameters),
                "Tuple registers are low locals");
        for (Instruction instruction : code)
            for (int r = parameters; r < parameters + 8; r++)
                check(!sets(instruction, r), "No argument register is overwritten");
        return at;
    }

    private static Instruction inspected(Instruction value, int[] registers, MethodReference ref) {
        return new ImmutableInstruction35c(
                value.getOpcode(),
                registers.length,
                registers.length > 0 ? registers[0] : 0,
                registers.length > 1 ? registers[1] : 0,
                registers.length > 2 ? registers[2] : 0,
                registers.length > 3 ? registers[3] : 0,
                registers.length > 4 ? registers[4] : 0,
                ref);
    }

    private static void rejects(String label, List<Instruction> code, int parameters, String type) {
        boolean rejected = false;
        try {
            verifyTail(code, parameters, type);
        } catch (AssertionError | RuntimeException expected) {
            rejected = true;
        }
        check(rejected, "Mutant is rejected: " + label);
    }

    private static void regressions(List<Instruction> code, int at, int parameters, String type) {
        int[] merged = arguments(code.get(at));
        MethodReference ref = call(code.get(at));
        int[] built = arguments(code.get(at + 6));
        MethodReference constructor = call(code.get(at + 6));

        List<Instruction> swapped = new ArrayList<>(code);
        swapped.set(at, inspected(code.get(at), new int[] {merged[0], merged[2], merged[1]}, ref));
        rejects("metadata and offset swapped", swapped, parameters, type);

        List<Instruction> aliased = new ArrayList<>(code);
        aliased.set(at, inspected(code.get(at), new int[] {merged[0], merged[1], merged[1]}, ref));
        rejects("offset replaced by metadata", aliased, parameters, type);

        List<Instruction> head = new ArrayList<>(code);
        head.set(
                at - 1,
                new ImmutableInstruction22x(
                        Opcode.MOVE_OBJECT_FROM16, register(code.get(at - 1)), parameters + 1));
        rejects("another argument as the original header", head, parameters, type);

        List<Instruction> unguarded = new ArrayList<>(code);
        unguarded.remove(at + 2);
        rejects("null branch removed", unguarded, parameters, type);

        List<Instruction> changed = new ArrayList<>(code);
        changed.set(
                at + 6,
                inspected(
                        code.get(at + 6),
                        new int[] {built[0], built[2], built[1], built[3]},
                        constructor));
        rejects("constructor arguments changed", changed, parameters, type);

        List<Instruction> abi = new ArrayList<>(code);
        abi.set(
                at,
                inspected(
                        code.get(at),
                        merged,
                        new ImmutableMethodReference(
                                HELPER, "mergeMetadata", List.of("[B", "[B"), "[B")));
        rejects("helper with another ABI", abi, parameters, type);

        List<Instruction> missing = new ArrayList<>(code);
        missing.remove(at);
        rejects("helper call removed", missing, parameters, type);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2)
            throw new IllegalArgumentException(
                    "patched.apk present|absent [original.apk] [--regressions]");
        boolean present = args[1].equals("present");
        check(present || args[1].equals("absent"), "Mode is present or absent");
        File original = null;
        boolean regressions = false;
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--regressions")) regressions = true;
            else original = new File(args[i]);
        }
        List<ClassDef> classes = new ArrayList<>();
        var container = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.getDefault());
        for (String entry : container.getDexEntryNames())
            classes.addAll(container.getEntry(entry).getDexFile().getClasses());
        Method header = null;
        int mentions = 0;
        int merges = 0;
        for (ClassDef type : classes) {
            if (type.getType().equals(HELPER)) continue;
            for (Method method : type.getMethods()) {
                if (method.getImplementation() == null) continue;
                for (Instruction instruction : method.getImplementation().getInstructions()) {
                    MethodReference ref = call(instruction);
                    if (ref != null && ref.getDefiningClass().equals(HELPER)) mentions++;
                    if (merge(ref)) {
                        merges++;
                        header = method;
                    }
                }
            }
        }
        boolean helperClass = classes.stream().anyMatch(c -> c.getType().equals(HELPER));
        if (!present) {
            check(mentions == 0 && !helperClass, "No FLAC metadata helper or hook is present");
            System.out.println("FLAC metadata APK checks: " + checks + " passed");
            return;
        }
        ClassDef helper =
                classes.stream().filter(c -> c.getType().equals(HELPER)).findFirst().orElse(null);
        check(helper != null, "The merge helper is present");
        boolean exposed = false;
        for (Method method : helper.getMethods())
            if (method.getName().equals("mergeMetadata")
                    && args(method).equals(List.of("[B", "[B", "I"))
                    && method.getReturnType().equals("[B")
                    && (method.getAccessFlags() & AccessFlags.STATIC.getValue()) != 0
                    && (method.getAccessFlags() & AccessFlags.PUBLIC.getValue()) != 0)
                exposed = true;
        check(exposed, "The helper is public static mergeMetadata([B[BI)[B");
        check(merges == 1 && header != null, "Exactly one hook calls the merge helper");
        check(
                (header.getAccessFlags() & AccessFlags.STATIC.getValue()) != 0
                        && args(header).size() == 8
                        && args(header).get(0).equals("[B")
                        && args(header).get(5).equals("[B"),
                "The hook is in the static eight-argument header");
        List<Instruction> code = new ArrayList<>();
        header.getImplementation().getInstructions().forEach(code::add);
        int registers = header.getImplementation().getRegisterCount();
        int parameters = registers - 8;
        int at = verifyTail(code, parameters, header.getReturnType());
        if (original != null) {
            var before = DexFileFactory.loadDexContainer(original, Opcodes.getDefault());
            Method base = null;
            for (String entry : before.getDexEntryNames())
                for (ClassDef type : before.getEntry(entry).getDexFile().getClasses())
                    if (type.getType().equals(header.getDefiningClass()))
                        for (Method method : type.getMethods())
                            if (method.getName().equals(header.getName())
                                    && args(method).equals(args(header))
                                    && method.getReturnType().equals(header.getReturnType()))
                                base = method;
            check(base != null, "The original header exists");
            List<Instruction> source = new ArrayList<>();
            base.getImplementation().getInstructions().forEach(source::add);
            check(
                    base.getImplementation().getRegisterCount() == registers,
                    "The header has no extra registers");
            List<String> kept = new ArrayList<>();
            for (int i = 0; i < code.size(); i++)
                if (i < at - 1 || i > at + 4) kept.add(describe(code.get(i)));
            List<String> expected = new ArrayList<>();
            source.forEach(i -> expected.add(describe(i)));
            check(kept.equals(expected), "Every original instruction is unchanged");
        }
        if (regressions) regressions(code, at, parameters, header.getReturnType());
        System.out.println("FLAC metadata APK checks: " + checks + " passed");
    }
}

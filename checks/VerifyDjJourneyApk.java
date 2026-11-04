import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;

import java.io.File;
import java.util.*;

/**
 * Checks the DJ journey hook in BitChord's smart-transition planner. With a patched and an original
 * APK, the {@code DjMode.plan} call sits right before the plan store and every branch, switch case
 * and exception range that reached the store reaches the call. With {@code --original <apk>} it
 * checks the host code the hook relies on.
 */
public final class VerifyDjJourneyApk {
    static int checks;

    static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    /** The plan-publishing method of one APK, with its code and the index of the store. */
    static final class Site {
        Method method;
        List<Instruction> code = new ArrayList<>();
        int[] address;
        int store;
        MethodImplementation implementation;

        /** How many branches, switch cases and exception entries land on each instruction. */
        Map<Integer, Integer> into = new HashMap<>();

        /** Exception ranges that start, end or continue across each instruction. */
        List<int[]> ranges = new ArrayList<>();
    }

    static Site find(File apk) throws Exception {
        Site found = null;
        MultiDexContainer<? extends DexBackedDexFile> container =
                DexFileFactory.loadDexContainer(apk, Opcodes.getDefault());
        for (String entry : container.getDexEntryNames()) {
            for (ClassDef cls : container.getEntry(entry).getDexFile().getClasses()) {
                boolean owner = false;
                for (Field field : cls.getFields())
                    owner |=
                            field.getName().equals("lastSmartPlan")
                                    && field.getType().endsWith("/TransitionPlan;");
                if (!owner) continue;
                for (Method method : cls.getMethods()) {
                    if (method.getImplementation() == null) continue;
                    Site site = new Site();
                    site.method = method;
                    site.implementation = method.getImplementation();
                    site.implementation.getInstructions().forEach(site.code::add);
                    site.store = -1;
                    int stores = 0;
                    for (int i = 0; i < site.code.size(); i++)
                        if (isStore(site.code.get(i))) {
                            stores++;
                            site.store = i;
                        }
                    if (stores == 0) continue;
                    check(stores == 1, "The planner publishes its plan once, found " + stores);
                    check(found == null, "One method publishes the smart-transition plan");
                    index(site);
                    found = site;
                }
            }
        }
        check(found != null, "The smart-transition plan store exists in " + apk.getName());
        return found;
    }

    static boolean isStore(Instruction instruction) {
        return instruction.getOpcode() == Opcode.IPUT_OBJECT
                && ((ReferenceInstruction) instruction).getReference() instanceof FieldReference f
                && f.getName().equals("lastSmartPlan")
                && f.getType().endsWith("/TransitionPlan;");
    }

    static void index(Site site) {
        int n = site.code.size();
        site.address = new int[n + 1];
        Map<Integer, Integer> at = new HashMap<>();
        int unit = 0;
        for (int i = 0; i < n; i++) {
            site.address[i] = unit;
            at.put(unit, i);
            unit += site.code.get(i).getCodeUnits();
        }
        site.address[n] = unit;
        for (int i = 0; i < n; i++) {
            Instruction instruction = site.code.get(i);
            Opcode opcode = instruction.getOpcode();
            if (instruction instanceof OffsetInstruction offset
                    && !(instruction instanceof SwitchPayload)
                    && opcode != Opcode.PACKED_SWITCH
                    && opcode != Opcode.SPARSE_SWITCH
                    && opcode != Opcode.FILL_ARRAY_DATA)
                land(site, at.get(site.address[i] + offset.getCodeOffset()));
            if (opcode == Opcode.PACKED_SWITCH || opcode == Opcode.SPARSE_SWITCH) {
                int payload =
                        at.get(site.address[i] + ((OffsetInstruction) instruction).getCodeOffset());
                for (SwitchElement element :
                        ((SwitchPayload) site.code.get(payload)).getSwitchElements())
                    land(site, at.get(site.address[i] + element.getOffset()));
            }
        }
        for (TryBlock<? extends ExceptionHandler> block : site.implementation.getTryBlocks()) {
            int start = at.get(block.getStartCodeAddress());
            Integer end = at.get(block.getStartCodeAddress() + block.getCodeUnitCount());
            site.ranges.add(new int[] {start, end == null ? n : end});
            for (ExceptionHandler handler : block.getExceptionHandlers())
                land(site, at.get(handler.getHandlerCodeAddress()));
        }
    }

    static void land(Site site, Integer target) {
        check(target != null, "Every branch lands on an instruction");
        site.into.merge(target, 1, Integer::sum);
    }

    static int landing(Site site, int index) {
        return site.into.getOrDefault(index, 0);
    }

    /** Exception ranges that begin or end at a boundary, which an insertion could move. */
    static boolean edge(Site site, int index) {
        for (int[] range : site.ranges) if (range[0] == index || range[1] == index) return true;
        return false;
    }

    static boolean covers(Site site, int index) {
        for (int[] range : site.ranges) if (range[0] <= index && index < range[1]) return true;
        return false;
    }

    static boolean isPlan(Instruction instruction, int register) {
        return isPlan(instruction, register, "Lapp/bitchord/telegram/DjMode;");
    }

    static boolean isPlan(Instruction instruction, int register, String owner) {
        if (!(instruction instanceof ReferenceInstruction reference)
                || !(reference.getReference() instanceof MethodReference method)
                || !method.getDefiningClass().equals(owner)
                || !method.getName().equals("plan")
                || !method.getReturnType().equals("V")
                || !method.getParameterTypes().equals(List.of("Ljava/lang/Object;"))) return false;
        if (instruction instanceof RegisterRangeInstruction range)
            return instruction.getOpcode() == Opcode.INVOKE_STATIC_RANGE
                    && range.getStartRegister() == register
                    && range.getRegisterCount() == 1;
        return instruction instanceof FiveRegisterInstruction five
                && instruction.getOpcode() == Opcode.INVOKE_STATIC
                && five.getRegisterCount() == 1
                && five.getRegisterC() == register;
    }

    /** Instructions that run; payload tables and padding are laid out again by the patcher. */
    static int executable(Site site) {
        int count = 0;
        for (Instruction instruction : site.code)
            if (!(instruction instanceof PayloadInstruction)
                    && instruction.getOpcode() != Opcode.NOP) count++;
        return count;
    }

    static int plans(Site site) {
        int count = 0;
        for (Instruction instruction : site.code)
            if (instruction instanceof ReferenceInstruction reference
                    && reference.getReference() instanceof MethodReference method
                    && method.getDefiningClass().equals("Lapp/bitchord/telegram/DjMode;")
                    && method.getName().equals("plan")) count++;
        return count;
    }

    static void host(File original) throws Exception {
        Site site = find(original);
        Instruction store = site.code.get(site.store);
        check(store.getOpcode() == Opcode.IPUT_OBJECT, "The plan is stored with iput-object");
        check(
                ((TwoRegisterInstruction) store).getRegisterA() < 256,
                "The plan register fits the range call");
        check(
                site.method.getParameterTypes().toString().equals("[J, Z]"),
                "The planner keeps its (long, boolean) parameters");
        check(plans(site) == 0, "The original planner has no DJ call");
        // Both forms of the hook are label-safe only if the store is not an exception edge.
        check(!edge(site, site.store), "No exception range starts or ends at the store");
        check(!edge(site, site.store + 1), "No exception range starts or ends after the store");
        System.out.println(
                "host: "
                        + site.method.getDefiningClass()
                        + "->"
                        + site.method.getName()
                        + " store@"
                        + site.store
                        + " branches into store="
                        + landing(site, site.store)
                        + " after="
                        + landing(site, site.store + 1)
                        + " covered="
                        + covers(site, site.store));
    }

    static void patched(File output, File original) throws Exception {
        boolean callable = false;
        var container = DexFileFactory.loadDexContainer(output, Opcodes.getDefault());
        for (String entry : container.getDexEntryNames())
            for (ClassDef type : container.getEntry(entry).getDexFile().getClasses())
                if (type.getType().equals("Lapp/bitchord/telegram/DjMode;")) {
                    check((type.getAccessFlags() & 1) != 0, "The DJ hook class is public");
                    for (Method method : type.getMethods())
                        if (method.getName().equals("plan")
                                && method.getParameterTypes().equals(List.of("Ljava/lang/Object;"))
                                && method.getReturnType().equals("V"))
                            callable = (method.getAccessFlags() & 9) == 9;
                }
        check(callable, "The native DJ plan entry is public and static");
        Site after = find(output), before = find(original);
        check(plans(after) == 1, "The planner calls DjMode.plan once");
        int register = ((TwoRegisterInstruction) after.code.get(after.store)).getRegisterA();
        check(
                register == ((TwoRegisterInstruction) before.code.get(before.store)).getRegisterA(),
                "The stored plan keeps its register");
        check(after.store > 0, "A call precedes the store");
        String automix = "Lapp/bitchord/telegram/SourceAutomix;";
        boolean combined =
                isPlan(after.code.get(after.store - 1), register, automix)
                        || (after.store > 1
                                && isPlan(after.code.get(after.store - 2), register, automix));
        int call = after.store - (combined ? 2 : 1);
        check(call >= 0, "The plan hooks fit before the store");
        boolean dj = false, source = false;
        for (int at = call; at < after.store; at++) {
            boolean thisDj = isPlan(after.code.get(at), register);
            boolean thisSource = isPlan(after.code.get(at), register, automix);
            check(thisDj || thisSource, "Only plan hooks run immediately before publication");
            check(!(thisDj && dj) && !(thisSource && source), "Each plan hook runs once");
            dj |= thisDj;
            source |= thisSource;
            if (at > call) check(landing(after, at) == 0, "Nothing bypasses a plan hook");
        }
        check(dj && source == combined, "DJ and selected source analysis share publication");
        if (!combined) {
            check(
                    executable(after) == executable(before) + 1,
                    "The standalone hook adds one planner instruction");
            check(
                    after.store == before.store + 1,
                    "The standalone call occupies the original store location");
        }
        check(
                landing(after, call) == landing(before, before.store),
                "Everything that reached the store reaches the call: "
                        + landing(before, before.store)
                        + " vs "
                        + landing(after, call));
        check(
                landing(after, after.store) == 0,
                "Nothing jumps past the call into the store: " + landing(after, after.store));
        check(
                landing(after, after.store + 1) == landing(before, before.store + 1),
                "The code after the store keeps its entries: "
                        + landing(before, before.store + 1)
                        + " vs "
                        + landing(after, after.store + 1));
        check(
                covers(after, call) == covers(before, before.store)
                        && covers(after, after.store) == covers(before, before.store),
                "The call and the store share the store's exception coverage");
        check(
                edge(after, call) == edge(before, before.store),
                "No exception range starts or ends between the call and the store");
        System.out.println("patched: DjMode.plan(v" + register + ") at " + call);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--original")) host(new File(args[1]));
        else if (args.length == 2) patched(new File(args[0]), new File(args[1]));
        else
            throw new IllegalArgumentException(
                    "usage: <patched.apk> <original.apk> | --original <apk>");
        System.out.println("PASS: " + checks + " DJ journey hook checks");
    }
}

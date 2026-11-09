import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;

import java.io.File;
import java.util.*;

/** Checks that refusing an unaligned upgrade cannot fall through to player adoption. */
public final class VerifyUpgradeSafetyApk {
    static MethodReference call(Instruction i) {
        return i instanceof ReferenceInstruction r && r.getReference() instanceof MethodReference m
                ? m
                : null;
    }

    static void exit(Map<Integer, Instruction> code, int start) {
        Set<Integer> seen = new HashSet<>();
        Deque<Integer> pending = new ArrayDeque<>(List.of(start));
        boolean throwsOut = false;
        while (!pending.isEmpty()) {
            int pc = pending.removeFirst();
            if (!seen.add(pc)) continue;
            Instruction instruction = code.get(pc);
            if (instruction == null)
                throw new AssertionError("Invalid cancellation handler target");
            MethodReference method = call(instruction);
            if (method != null
                    && (method.getName().contains("adoptPlayerForVersionSwap")
                            || method.getName().equals("replaceMediaItem")))
                throw new AssertionError("Cancellation can commit the incoming player");
            Opcode op = instruction.getOpcode();
            if (op == Opcode.THROW) {
                throwsOut = true;
                continue;
            }
            if (op.name.startsWith("return")) continue;
            if (instruction instanceof OffsetInstruction offset) {
                if (op == Opcode.PACKED_SWITCH || op == Opcode.SPARSE_SWITCH) {
                    SwitchPayload payload = (SwitchPayload) code.get(pc + offset.getCodeOffset());
                    for (SwitchElement e : payload.getSwitchElements())
                        pending.add(pc + e.getOffset());
                } else pending.add(pc + offset.getCodeOffset());
                if (op.name.startsWith("goto")) continue;
            }
            pending.add(pc + instruction.getCodeUnits());
        }
        if (!throwsOut) throw new AssertionError("Cancellation must leave the coroutine");
    }

    public static void main(String[] args) throws Exception {
        var dex = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.getDefault());
        int guarded = 0, priority = 0, audition = 0, deferred = 0, adoption = 0;
        for (String entry : dex.getDexEntryNames())
            for (ClassDef cls : dex.getEntry(entry).getDexFile().getClasses())
                for (Method method : cls.getMethods()) {
                    if (!cls.getType().startsWith("Lcom/music/bitchord/")) continue;
                    MethodImplementation impl = method.getImplementation();
                    if (impl == null) continue;
                    Map<Integer, Instruction> code = new LinkedHashMap<>();
                    int pc = 0, smooth = -1, verified = -1, adopting = -1, adopted = -1;
                    List<Instruction> sequence = new ArrayList<>();
                    impl.getInstructions().forEach(sequence::add);
                    if (cls.getType().contains("$smoothSwapCurrentTrackVersion$1;")
                            && method.getName().equals("invokeSuspend")) {
                        MethodReference entryCall = call(sequence.get(0));
                        if (entryCall == null || !entryCall.getName().equals("bindSwap"))
                            throw new AssertionError(
                                    "Swap coroutine does not bind its own attempt");
                        for (int n = 1; n < sequence.size(); n++) {
                            MethodReference callback = call(sequence.get(n));
                            if (callback != null
                                    && callback.getDefiningClass()
                                            .equals("Lapp/bitchord/telegram/SmoothUpgrade;")
                                    && Set.of("adopted", "replaced", "abandoned", "ended")
                                            .contains(callback.getName())) {
                                MethodReference capture = call(sequence.get(n - 1));
                                if (capture == null || !capture.getName().equals("captureSwap"))
                                    throw new AssertionError(
                                            "Swap callback lost its attempt owner");
                                if (!(sequence.get(n - 1) instanceof RegisterRangeInstruction range)
                                        || range.getStartRegister() != impl.getRegisterCount() - 2
                                        || range.getRegisterCount() != 1)
                                    throw new AssertionError(
                                            "Swap callback captures the wrong coroutine");
                            }
                        }
                    }
                    Set<String> held = new HashSet<>();
                    boolean direct = false;
                    for (Instruction i : impl.getInstructions()) {
                        code.put(pc, i);
                        pc += i.getCodeUnits();
                    }
                    for (var at : code.entrySet()) {
                        MethodReference target = call(at.getValue());
                        if (target == null) continue;
                        if (target.getDefiningClass()
                                        .equals("Lapp/bitchord/telegram/SmoothUpgrade;")
                                && target.getName().equals("auditionPosition")) {
                            int next = at.getKey() + at.getValue().getCodeUnits();
                            Instruction result = code.get(next);
                            if (result == null || result.getOpcode() != Opcode.MOVE_RESULT_WIDE)
                                throw new AssertionError("Audition position result is ignored");
                            int position = ((OneRegisterInstruction) result).getRegisterA();
                            next += result.getCodeUnits();
                            boolean consumed = false;
                            for (int step = 0; step < 8 && code.containsKey(next); step++) {
                                Instruction instruction = code.get(next);
                                MethodReference seek = call(instruction);
                                if (seek != null && seek.getName().equals("seekTo")) {
                                    int slot = 1;
                                    for (CharSequence type : seek.getParameterTypes()) {
                                        if (type.toString().equals("J")) {
                                            int actual;
                                            if (instruction
                                                    instanceof RegisterRangeInstruction range)
                                                actual = range.getStartRegister() + slot;
                                            else {
                                                FiveRegisterInstruction registers =
                                                        (FiveRegisterInstruction) instruction;
                                                int[] argsAt = {
                                                    registers.getRegisterC(),
                                                    registers.getRegisterD(),
                                                    registers.getRegisterE(),
                                                    registers.getRegisterF(),
                                                    registers.getRegisterG()
                                                };
                                                actual = argsAt[slot];
                                            }
                                            consumed = actual == position;
                                            break;
                                        }
                                        slot += type.toString().equals("D") ? 2 : 1;
                                    }
                                    break;
                                }
                                next += instruction.getCodeUnits();
                            }
                            if (!consumed)
                                throw new AssertionError(
                                        "Standby seek does not use the current audition position");
                            audition++;
                        }
                        String owner = target.getDefiningClass();
                        if (owner.equals("Lapp/bitchord/telegram/SmoothUpgrade;")
                                && Set.of("stream", "source", "shelved").contains(target.getName()))
                            held.add(target.getName());
                        if (owner.equals("Lcom/music/bitchord/data/NerdStats;")
                                && Set.of("onSourceStream", "recordSource")
                                        .contains(target.getName())) direct = true;
                        if (target.getName().equals("access$adoptPlayerForVersionSwap"))
                            adopting = at.getKey();
                        if (owner.equals("Lapp/bitchord/telegram/SmoothUpgrade;")
                                && target.getName().equals("adopted")) adopted = at.getKey();
                        if (target.getName().equals("alignedOffset")) {
                            if (target.getDefiningClass()
                                    .equals("Lapp/bitchord/telegram/SmoothUpgrade;"))
                                smooth = at.getKey();
                            if (target.getDefiningClass()
                                    .equals("Lapp/bitchord/telegram/SourcePreference;"))
                                verified = at.getKey();
                        }
                        if (!target.getDefiningClass()
                                        .equals("Lapp/bitchord/telegram/SmoothUpgrade;")
                                || !Set.of("settled", "fadeVolume").contains(target.getName()))
                            continue;
                        boolean caught = false;
                        for (TryBlock<? extends ExceptionHandler> block : impl.getTryBlocks()) {
                            if (at.getKey() < block.getStartCodeAddress()
                                    || at.getKey()
                                            >= block.getStartCodeAddress()
                                                    + block.getCodeUnitCount()) continue;
                            for (ExceptionHandler handler : block.getExceptionHandlers()) {
                                exit(code, handler.getHandlerCodeAddress());
                                caught = true;
                            }
                        }
                        if (!caught)
                            throw new AssertionError(
                                    "Upgrade guard bypasses coroutine cleanup: "
                                            + method.getDefiningClass()
                                            + "->"
                                            + method.getName());
                        guarded++;
                    }
                    if (held.size() == 3) {
                        if (direct)
                            throw new AssertionError(
                                    "The commit still records the source before adoption");
                        deferred++;
                    }
                    if (adopting >= 0) {
                        if (adopted < adopting)
                            throw new AssertionError("Adopting the player is not reported");
                        adoption++;
                    }
                    if (smooth >= 0 && verified >= 0) {
                        if (smooth >= verified)
                            throw new AssertionError(
                                    "A coarse offset overrides the measured upgrade offset");
                        priority++;
                    }
                }
        if (guarded != 2 || priority != 1 || audition != 1 || deferred != 1 || adoption != 1)
            throw new AssertionError(
                    "Missing upgrade guards or alignment priority: "
                            + guarded
                            + "/"
                            + priority
                            + "/"
                            + audition
                            + "/"
                            + deferred
                            + "/"
                            + adoption);
        System.out.println(
                "PASS: 2 cancellation paths preserve the outgoing player; measured offset has"
                        + " priority; standby seek consumes the current audition position; the"
                        + " source record waits for the replacement player");
    }
}

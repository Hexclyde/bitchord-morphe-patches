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
        int guarded = 0, priority = 0;
        for (String entry : dex.getDexEntryNames())
            for (ClassDef cls : dex.getEntry(entry).getDexFile().getClasses())
                for (Method method : cls.getMethods()) {
                    if (!cls.getType().startsWith("Lcom/music/bitchord/")) continue;
                    MethodImplementation impl = method.getImplementation();
                    if (impl == null) continue;
                    Map<Integer, Instruction> code = new LinkedHashMap<>();
                    int pc = 0, smooth = -1, verified = -1;
                    for (Instruction i : impl.getInstructions()) {
                        code.put(pc, i);
                        pc += i.getCodeUnits();
                    }
                    for (var at : code.entrySet()) {
                        MethodReference target = call(at.getValue());
                        if (target == null) continue;
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
                    if (smooth >= 0 && verified >= 0) {
                        if (smooth >= verified)
                            throw new AssertionError(
                                    "A coarse offset overrides the measured upgrade offset");
                        priority++;
                    }
                }
        if (guarded != 2 || priority != 1)
            throw new AssertionError(
                    "Missing upgrade guards or alignment priority: " + guarded + "/" + priority);
        System.out.println(
                "PASS: 2 cancellation paths preserve the outgoing player; measured offset has"
                        + " priority");
    }
}

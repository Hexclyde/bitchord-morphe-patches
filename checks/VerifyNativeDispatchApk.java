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

    public static void main(String[] args) throws Exception {
        var dex = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.getDefault());
        int dispatchers = 0, cases = 0;
        for (String entry : dex.getDexEntryNames())
            for (ClassDef cls : dex.getEntry(entry).getDexFile().getClasses()) {
                if (!cls.getType().equals("Lcom/music/bitchord/wear/WearBridge$sendToWatch$1;"))
                    continue;
                for (Method method : cls.getMethods()) {
                    if (method.getImplementation() == null) continue;
                    List<Instruction> code = new ArrayList<>();
                    method.getImplementation().getInstructions().forEach(code::add);
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
        if (dispatchers > 1) throw new AssertionError("Native dispatch must have one host IO path");
        System.out.println(
                "PASS: "
                        + cases
                        + " actual native/HTTPS dispatch paths; dispatchers="
                        + dispatchers);
    }
}

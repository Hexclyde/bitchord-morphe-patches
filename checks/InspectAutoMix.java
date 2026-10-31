import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;

import java.io.File;
import java.util.*;

public class InspectAutoMix {
    public static void main(String[] args) throws Exception {
        var container = DexFileFactory.loadDexContainer(new File(args[0]), null);
        for (String entry : container.getDexEntryNames()) {
            for (ClassDef type : container.getEntry(entry).getDexFile().getClasses()) {
                boolean selected =
                        Arrays.asList(args).subList(1, args.length).contains(type.getType());
                if (!selected && args.length > 1 && args[1].startsWith("@")) {
                    String target = args[1].substring(1);
                    for (Method method : type.getMethods())
                        if (method.getImplementation() != null)
                            for (Instruction instruction :
                                    method.getImplementation().getInstructions())
                                if (instruction instanceof ReferenceInstruction ref
                                        && ref.getReference().toString().contains(target))
                                    selected = true;
                }
                if (!selected) continue;
                System.out.println(type.getType());
                for (Field f : type.getFields()) System.out.println("FIELD " + f);
                for (Method m : type.getMethods()) {
                    System.out.println("METHOD " + m + " flags=" + m.getAccessFlags());
                    if (m.getImplementation() == null) continue;
                    int index = 0;
                    for (Instruction instruction : m.getImplementation().getInstructions()) {
                        System.out.println(
                                "  "
                                        + index++
                                        + " "
                                        + instruction.getOpcode()
                                        + " "
                                        + (instruction instanceof ReferenceInstruction ref
                                                ? ref.getReference()
                                                : ""));
                    }
                }
            }
        }
    }
}

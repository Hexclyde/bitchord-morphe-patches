import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;

import java.io.File;
import java.util.*;

/** Verify the optimized host's actual window lifecycle, not only its constructor. */
public final class VerifyVideoSwitchApk {
    public static void main(String[] args) throws Exception {
        var apk = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.getDefault());
        int writes = 0, hooks = 0;
        boolean shown = false, holding = false, controller = false, toSong = false;
        for (String entry : apk.getDexEntryNames())
            for (ClassDef type : apk.getEntry(entry).getDexFile().getClasses()) {
                if (type.getType().equals("Lcom/music/bitchord/ui/player/PlayerSheetMotion;")) {
                    for (Field field : type.getFields())
                        if (field.getName().equals("shown")) shown = true;
                    for (Method method : type.getMethods())
                        if (method.getName().equals("getHolding")
                                && method.getParameterTypes().isEmpty()) holding = true;
                }
                if (type.getType().equals("Landroidx/media3/session/MediaController;"))
                    for (Method method : type.getMethods())
                        if (method.getName().equals("getCurrentMediaItem")
                                && method.getParameterTypes().isEmpty()) controller = true;
                if (type.getType().equals("Lcom/music/bitchord/playback/PlayerConnectionKt;"))
                    for (Method method : type.getMethods())
                        if (method.getName().equals("toSong") && (method.getAccessFlags() & 9) == 9)
                            toSong = true;
                for (Method method : type.getMethods()) {
                    if (method.getImplementation() == null) continue;
                    List<Instruction> code = new ArrayList<>();
                    method.getImplementation().getInstructions().forEach(code::add);
                    for (int i = 0; i < code.size(); i++) {
                        Instruction instruction = code.get(i);
                        if (!(instruction instanceof ReferenceInstruction r)
                                || !(r.getReference() instanceof FieldReference field)
                                || instruction.getOpcode() != Opcode.IPUT_OBJECT
                                || !field.getDefiningClass()
                                        .equals("Lcom/music/bitchord/ui/player/PlayerSheetMotion;")
                                || !field.getName().equals("window")) continue;
                        writes++;
                        Instruction next = code.get(i + 1);
                        if (!(next instanceof ReferenceInstruction call)
                                || !(call.getReference() instanceof MethodReference target)
                                || !target.getDefiningClass()
                                        .equals("Lapp/bitchord/telegram/VideoSwitch;")
                                || !target.getName().equals("motion")
                                || !(next instanceof RegisterRangeInstruction range)
                                || range.getStartRegister()
                                        != ((TwoRegisterInstruction) instruction).getRegisterB())
                            throw new AssertionError("Unobserved player window write in " + method);
                        hooks++;
                    }
                }
            }
        if (writes < 2 || hooks != writes || !shown || !holding || !controller || !toSong)
            throw new AssertionError(
                    "Incomplete player lifecycle/host ABI: " + writes + "/" + hooks);
        System.out.println(
                "PASS: " + hooks + " player window writes observed; overlay host ABIs retained");
    }
}

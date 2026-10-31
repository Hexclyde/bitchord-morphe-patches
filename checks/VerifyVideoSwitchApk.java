import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;

import java.io.File;
import java.util.*;

/** Verify the switch follows the actual player composition, independent of animation windows. */
public final class VerifyVideoSwitchApk {
    static int checks;

    static void check(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }

    public static void main(String[] args) throws Exception {
        var apk = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.getDefault());
        Map<String, ClassDef> types = new HashMap<>();
        for (String entry : apk.getDexEntryNames())
            for (ClassDef type : apk.getEntry(entry).getDexFile().getClasses())
                types.put(type.getType(), type);
        int anchors = 0, controllerHooks = 0, playerCalls = 0, controllerCalls = 0;
        for (ClassDef type : types.values())
            for (Method method : type.getMethods()) {
                if (method.getImplementation() == null) continue;
                boolean group = false;
                for (Instruction instruction : method.getImplementation().getInstructions()) {
                    if (!(instruction instanceof ReferenceInstruction r)
                            || !(r.getReference() instanceof MethodReference call)) continue;
                    if (call.getDefiningClass().equals("Landroidx/compose/runtime/ComposerImpl;")
                            && call.getName().equals("startRestartGroup")) group = true;
                    if (call.getDefiningClass().equals("Lapp/bitchord/telegram/VideoSwitch;")) {
                        if (call.getName().equals("content")) {
                            anchors++;
                            check(
                                    type.getType()
                                                    .equals(
                                                            "Lcom/music/bitchord/ui/player/NowPlayingScreenKt;")
                                            && method.getName().startsWith("NowPlayingScreen-"),
                                    "Anchor belongs to the real full player");
                            check(group, "LocalView consumed inside a restart group");
                        }
                        if (call.getName().equals("controller")) controllerHooks++;
                    }
                    if (type.getType().startsWith("Lcom/music/bitchord/")) {
                        if (call.getDefiningClass()
                                        .equals("Lcom/music/bitchord/ui/player/NowPlayingScreenKt;")
                                && call.getName().startsWith("NowPlayingScreen-")) playerCalls++;
                        if (call.getDefiningClass()
                                        .equals("Lcom/music/bitchord/playback/PlayerConnectionKt;")
                                && call.getName().equals("rememberPlayerState")) controllerCalls++;
                    }
                }
            }
        check(anchors == 1, "Exactly one full-player content anchor");
        check(
                controllerHooks == 1 && controllerCalls > 0,
                "Controller hook is on a called host method");
        check(playerCalls > 0, "Host actually invokes the anchored player");
        ClassDef locals =
                types.get("Landroidx/compose/ui/platform/AndroidCompositionLocals_androidKt;");
        check(locals != null, "Android composition locals present");
        boolean localView = false, consume = false;
        for (Method m : locals.getMethods())
            if (m.getName().equals("getLocalView")
                    && m.getParameterTypes().isEmpty()
                    && (m.getAccessFlags() & 9) == 9) localView = true;
        for (Method m : types.get("Landroidx/compose/runtime/ComposerImpl;").getMethods())
            if (m.getName().equals("consume")
                    && m.getParameterTypes()
                            .equals(
                                    List.of(
                                            "Landroidx/compose/runtime/ProvidableCompositionLocal;")))
                consume = true;
        check(localView && consume, "Exact LocalView/consume reflection ABI");
        for (Method m : types.get("Lapp/bitchord/telegram/VideoSwitch;").getMethods())
            if (m.getName().equals("attach"))
                for (Instruction i : m.getImplementation().getInstructions())
                    if (i instanceof ReferenceInstruction r
                            && r.getReference() instanceof MethodReference call)
                        check(
                                !call.getDefiningClass().equals("Landroid/view/Window;"),
                                "Mount does not depend on the animation window");
        System.out.println(
                "PASS: "
                        + checks
                        + " player content-anchor, reduced-motion and called-host ABI checks");
    }
}

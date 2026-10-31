import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;

import java.io.File;
import java.util.*;

/** Qualify measured player-child slots, not a window overlay. */
public final class VerifyVideoSwitchApk {
    static int checks;

    static void check(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }

    static MethodReference call(Instruction i) {
        return i instanceof ReferenceInstruction r && r.getReference() instanceof MethodReference m
                ? m
                : null;
    }

    public static void main(String[] args) throws Exception {
        var apk = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.getDefault());
        Map<String, ClassDef> types = new HashMap<>();
        for (String entry : apk.getDexEntryNames())
            for (ClassDef type : apk.getEntry(entry).getDexFile().getClasses())
                types.put(type.getType(), type);
        int slots = 0, controllers = 0, controllerCallers = 0, portrait = 0, landscape = 0;
        String runtime = "Lapp/bitchord/telegram/VideoSwitch;";
        for (ClassDef type : types.values())
            for (Method method : type.getMethods()) {
                if (method.getImplementation() == null) continue;
                List<Instruction> code = new ArrayList<>();
                method.getImplementation().getInstructions().forEach(code::add);
                for (int i = 0; i < code.size(); i++) {
                    var target = call(code.get(i));
                    if (target == null) continue;
                    if (target.getDefiningClass().equals(runtime)) {
                        if (target.getName().equals("controller")) controllers++;
                        check(
                                !Set.of("content", "attach", "bindContent", "motion")
                                        .contains(target.getName()),
                                "No window attachment or animation hook remains");
                        if (target.getName().equals("header")) {
                            slots++;
                            var previous = call(code.get(i - 1));
                            if (type.getType()
                                    .equals(
                                            "Lcom/music/bitchord/ui/player/NowPlayingScreenKt$$ExternalSyntheticLambda74;")) {
                                portrait++;
                                check(
                                        previous != null && previous.getName().equals("endNode"),
                                        "Portrait slot follows the completed drag/caption Box");
                                check(
                                        code.get(i + 1) instanceof ReferenceInstruction r
                                                && r.getReference() instanceof FieldReference f
                                                && f.getDefiningClass()
                                                        .equals(
                                                                "Landroidx/compose/foundation/layout/ColumnScopeInstance;"),
                                        "Portrait slot is a Column child before the weighted"
                                                + " artwork");
                            } else {
                                landscape++;
                                check(
                                        type.getType()
                                                        .equals(
                                                                "Lcom/music/bitchord/ui/player/LandscapePlayerKt;")
                                                && method.getName().equals("LandscapeMainPane"),
                                        "Landscape slot belongs to the main player pane");
                                check(
                                        previous != null
                                                && previous.getDefiningClass()
                                                        .equals(
                                                                "Landroidx/compose/runtime/Updater;")
                                                && previous.getName().equals("set-impl"),
                                        "Landscape slot follows its Column modifier binding");
                            }
                        }
                    }
                    if (type.getType().startsWith("Lcom/music/bitchord/")
                            && target.getDefiningClass()
                                    .equals("Lcom/music/bitchord/playback/PlayerConnectionKt;")
                            && target.getName().equals("rememberPlayerState")) controllerCallers++;
                    if (type.getType().startsWith("Lapp/bitchord/telegram/VideoSwitch"))
                        check(
                                !target.getDefiningClass().equals("Landroid/view/Window;"),
                                "Mode controls never mutate the Window");
                }
            }
        check(
                slots == 2 && portrait == 1 && landscape == 1,
                "Exactly one measured slot in each player layout");
        check(
                controllers == 1 && controllerCallers > 0,
                "Controller binding is in a called host method");
        ClassDef sizes = types.get("Landroidx/compose/foundation/layout/SizeKt;");
        boolean fill = false;
        for (Field f : sizes.getFields())
            if (f.getName().equals("FillWholeMaxWidth") && (f.getAccessFlags() & 9) == 9)
                fill = true;
        check(fill, "Public width modifier matches native AndroidView ABI");
        ClassDef layers = types.get("Landroidx/compose/ui/ZIndexElement;");
        boolean constructor = false;
        for (Method m : layers.getMethods())
            if (m.getName().equals("<init>")
                    && m.getParameterTypes().equals(List.of("F"))
                    && (m.getAccessFlags() & 1) == 1) constructor = true;
        check(constructor, "Header uses the retained native stacking modifier");
        boolean then = false;
        for (Method m : types.get("Landroidx/compose/ui/Modifier;").getMethods())
            if (m.getName().equals("then")
                    && m.getParameterTypes().equals(List.of("Landroidx/compose/ui/Modifier;")))
                then = true;
        check(then, "Width and stacking modifiers compose through the host interface");
        boolean androidView = false;
        for (Method m :
                types.get("Landroidx/compose/ui/viewinterop/AndroidView_androidKt;").getMethods())
            if (m.getName().equals("AndroidView")
                    && m.getParameterTypes().size() == 6
                    && m.getParameterTypes()
                            .get(3)
                            .equals("Landroidx/compose/runtime/ComposerImpl;")) androidView = true;
        check(androidView, "Exact six-argument AndroidView entry point retained");
        check(
                types.get("Lapp/bitchord/telegram/VideoSwitch$Header;")
                        .getSuperclass()
                        .equals("Landroid/widget/FrameLayout;"),
                "Header owns its intrinsic measured height");
        check(
                types.get("Lapp/bitchord/telegram/VideoSwitch$Pill;")
                        .getSuperclass()
                        .equals("Landroid/widget/LinearLayout;"),
                "Two icon targets share a native row");
        System.out.println(
                "PASS: "
                        + checks
                        + " native player layout, icon-control and called-host ABI checks");
    }
}

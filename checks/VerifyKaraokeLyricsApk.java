import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;

import java.io.File;
import java.util.*;

/**
 * Checks the Karaoke control on the lyrics in a patched APK. Args: patched.apk [present|absent].
 * The hooks are looked for by what BitChord does at each place, never by an R8 name.
 */
public class VerifyKaraokeLyricsApk {
    static final String KARAOKE = "Lapp/bitchord/telegram/KaraokeLyrics;";
    static final String CAPSULE = "Lapp/bitchord/telegram/KaraokeCapsule;";
    static final String EXTRAS = "Lapp/bitchord/telegram/SoundExtras;";
    static final String LYRICS = "Lcom/music/bitchord/ui/player/PlayerLyricsKt;";
    static final String COMPOSER = "Landroidx/compose/runtime/ComposerImpl;";
    static final String ALIGNMENT = "Landroidx/compose/ui/Alignment$Companion;";
    static final String SIZE_KT = "Landroidx/compose/foundation/layout/SizeKt;";
    static final String OFFSET_KT = "Landroidx/compose/foundation/layout/OffsetKt;";
    static int fails;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) fails++;
    }

    static Map<String, ClassDef> load(File f) throws Exception {
        Map<String, ClassDef> out = new HashMap<>();
        var box = DexFileFactory.loadDexContainer(f, Opcodes.getDefault());
        for (String e : box.getDexEntryNames())
            for (ClassDef c : box.getEntry(e).getDexFile().getClasses()) out.put(c.getType(), c);
        return out;
    }

    static Method resolved(Map<String, ClassDef> dex, String reference) {
        if (reference == null || !reference.contains("->")) return null;
        ClassDef owner = dex.get(reference.substring(0, reference.indexOf("->")));
        if (owner == null) return null;
        for (Method method : owner.getMethods()) {
            String actual =
                    method.getDefiningClass()
                            + "->"
                            + method.getName()
                            + "("
                            + String.join("", method.getParameterTypes())
                            + ")"
                            + method.getReturnType();
            if (reference.equals(actual)) return method;
        }
        return null;
    }

    /** A method's instructions with the instruction index every branch lands on. */
    static final class Code {
        final List<Instruction> ins = new ArrayList<>();
        final Map<Integer, Integer> target = new HashMap<>();

        Code(Method m) {
            if (m.getImplementation() == null) return;
            Map<Integer, Integer> at = new HashMap<>();
            int address = 0;
            for (Instruction i : m.getImplementation().getInstructions()) {
                at.put(address, ins.size());
                ins.add(i);
                address += i.getCodeUnits();
            }
            address = 0;
            for (int k = 0; k < ins.size(); k++) {
                Instruction i = ins.get(k);
                String op = i.getOpcode().name();
                if (i instanceof OffsetInstruction o
                        && !op.startsWith("PACKED")
                        && !op.startsWith("SPARSE")
                        && !op.startsWith("FILL")) {
                    Integer to = at.get(address + o.getCodeOffset());
                    if (to != null) target.put(k, to);
                }
                address += i.getCodeUnits();
            }
        }

        MethodReference call(int k) {
            return ins.get(k) instanceof ReferenceInstruction r
                            && r.getReference() instanceof MethodReference m
                    ? m
                    : null;
        }

        FieldReference field(int k) {
            return ins.get(k) instanceof ReferenceInstruction r
                            && r.getReference() instanceof FieldReference f
                    ? f
                    : null;
        }

        boolean calls(String type, String name) {
            for (int k = 0; k < ins.size(); k++) {
                MethodReference m = call(k);
                if (m != null && m.getDefiningClass().equals(type) && m.getName().equals(name))
                    return true;
            }
            return false;
        }

        List<Integer> callsTo(String type, String name) {
            List<Integer> found = new ArrayList<>();
            for (int k = 0; k < ins.size(); k++) {
                MethodReference m = call(k);
                if (m != null && m.getDefiningClass().equals(type) && m.getName().equals(name))
                    found.add(k);
            }
            return found;
        }

        boolean targeted(int index) {
            return target.containsValue(index);
        }

        boolean isGoto(int k) {
            String n = ins.get(k).getOpcode().name();
            return n.equals("GOTO") || n.equals("GOTO_16") || n.equals("GOTO_32");
        }
    }

    static MethodReference call(Code code, int k) {
        return code.call(k);
    }

    static int paramSlots(Method m) {
        int n = 0;
        for (CharSequence type : m.getParameterTypes())
            n += type.toString().equals("J") || type.toString().equals("D") ? 2 : 1;
        return n;
    }

    static boolean hasHaze;

    /** True when the instruction writes the register (not a store, branch, return or call). */
    static boolean writes(Instruction i, int register) {
        String n = i.getOpcode().name().toLowerCase().replace('_', '-');
        for (String p :
                new String[] {
                    "if-", "iput", "sput", "aput", "return", "monitor", "throw", "invoke"
                }) if (n.startsWith(p)) return false;
        return i instanceof OneRegisterInstruction o && o.getRegisterA() == register;
    }

    /** The whole-number float in the register just before index {@code at}, or null. */
    static Float floatBefore(Code code, int at, int register) {
        int convert = -1;
        for (int k = at - 1; k >= 0 && convert < 0; k--)
            if (writes(code.ins.get(k), register)) convert = k;
        if (convert < 0 || code.ins.get(convert).getOpcode() != Opcode.INT_TO_FLOAT) return null;
        int source = ((TwoRegisterInstruction) code.ins.get(convert)).getRegisterB();
        int load = -1;
        for (int k = convert - 1; k >= 0 && load < 0; k--)
            if (writes(code.ins.get(k), source)) load = k;
        if (load < 0) return null;
        Opcode op = code.ins.get(load).getOpcode();
        if (op != Opcode.CONST_4 && op != Opcode.CONST_16) return null;
        return (float) ((NarrowLiteralInstruction) code.ins.get(load)).getNarrowLiteral();
    }

    /** Calls to Compose's size modifier in this code: the instruction indices. */
    static List<Integer> sizeCalls(Code code) {
        List<Integer> found = new ArrayList<>();
        for (int k = 0; k < code.ins.size(); k++) {
            MethodReference m = code.call(k);
            if (m != null
                    && code.ins.get(k) instanceof FiveRegisterInstruction
                    && m.getDefiningClass().equals(SIZE_KT)
                    && m.getName().startsWith("size-")
                    && m.getParameterTypes()
                            .toString()
                            .equals("[Landroidx/compose/ui/Modifier;, F]")) found.add(k);
        }
        return found;
    }

    static boolean refers(Method m, String prefix) {
        if (m.getImplementation() == null) return false;
        for (Instruction i : m.getImplementation().getInstructions())
            if (i instanceof ReferenceInstruction r
                    && String.valueOf(r.getReference()).contains(prefix)) return true;
        return false;
    }

    public static void main(String[] args) throws Exception {
        boolean present = args.length < 2 || args[1].equals("present");
        Map<String, ClassDef> dex = load(new File(args[0]));

        // Every host method that calls KaraokeLyrics, whatever it is called.
        List<String> callers = new ArrayList<>();
        for (ClassDef c : dex.values()) {
            if (c.getType().startsWith("Lapp/bitchord/telegram/Karaoke")) continue;
            if (!c.getType().startsWith("Lcom/music/bitchord/") && !c.getType().startsWith("Ldev/"))
                continue;
            for (Method m : c.getMethods()) {
                Code code = new Code(m);
                for (int k = 0; k < code.ins.size(); k++)
                    if (code.call(k) != null && code.call(k).getDefiningClass().equals(KARAOKE))
                        callers.add(
                                c.getType() + "->" + m.getName() + ":" + code.call(k).getName());
            }
        }
        if (!present) {
            check(callers.isEmpty(), "No host method reaches the Karaoke control: " + callers);
            System.exit(fails == 0 ? 0 : 1);
        }

        check(
                dex.containsKey(KARAOKE) && dex.containsKey(CAPSULE),
                "The Karaoke control is in the dex");
        if (!dex.containsKey(KARAOKE) || !dex.containsKey(CAPSULE) || !dex.containsKey(EXTRAS)) {
            System.out.println(fails + " FAILED");
            System.exit(1);
        }
        ClassDef extras = dex.get(EXTRAS);
        Method marker = null;
        for (Method m : extras.getMethods()) if (m.getName().equals("singApplied")) marker = m;
        check(marker != null && marker.getImplementation() != null, "singApplied exists");
        boolean markerOn = false;
        for (Instruction i : marker.getImplementation().getInstructions())
            if (i instanceof NarrowLiteralInstruction n
                    && i.getOpcode() == Opcode.CONST_4
                    && n.getNarrowLiteral() == 1) markerOn = true;
        check(markerOn, "singApplied answers true once Karaoke is installed");

        // What the patch handed the extension: every required symbol, and a consume whenever a
        // tint.
        String table = "";
        for (Method m : dex.get(KARAOKE).getMethods())
            if (m.getName().equals("symbols") && m.getImplementation() != null)
                for (Instruction i : m.getImplementation().getInstructions())
                    if (i instanceof ReferenceInstruction r
                            && r.getReference() instanceof StringReference s
                            && s.getString().length() > table.length()) table = s.getString();
        for (String key :
                new String[] {
                    "androidView",
                    "padding",
                    "companion",
                    "alignment",
                    "childData",
                    "startGroup",
                    "endGroup",
                    "requiredSize",
                    "offsetBy",
                    "intOffset"
                })
            check(
                    table.contains("\n" + key + "=") || table.startsWith(key + "="),
                    "The patch resolved " + key);
        check(
                !table.contains("\ntint=") || table.contains("\nconsume="),
                "A player tint comes with the consume that reads it");

        Map<String, String> symbols = new HashMap<>();
        for (String line : table.split("\n")) {
            int separator = line.indexOf('=');
            if (separator > 0)
                symbols.put(line.substring(0, separator), line.substring(separator + 1));
        }
        // The landscape slot is sized and moved with layout modifiers that exist in this build.
        Method required = resolved(dex, symbols.get("requiredSize"));
        check(
                required != null
                        && required.getDefiningClass().equals(SIZE_KT)
                        && required.getName().endsWith("$default")
                        && required.getParameterTypes()
                                .equals(
                                        List.of(
                                                "Landroidx/compose/ui/Modifier;",
                                                "F",
                                                "F",
                                                "F",
                                                "F",
                                                "I")),
                "The required-size modifier resolves with its default mask");
        Method offsetBy = resolved(dex, symbols.get("offsetBy"));
        check(
                offsetBy != null
                        && offsetBy.getDefiningClass().equals(OFFSET_KT)
                        && offsetBy.getParameterTypes()
                                .equals(
                                        List.of(
                                                "Landroidx/compose/ui/Modifier;",
                                                "Lkotlin/jvm/functions/Function1;")),
                "The lambda offset modifier resolves");
        Method packed = resolved(dex, symbols.get("intOffset"));
        check(
                packed != null && packed.getParameterTypes().equals(List.of("J")),
                "The packed offset class resolves its long constructor");

        hasHaze = table.contains("\nhazeEffect=");
        if (hasHaze) {
            for (String key :
                    List.of(
                            "drawElement",
                            "drawContent",
                            "drawContext",
                            "contextCanvas",
                            "nativeCanvas"))
                check(
                        resolved(dex, symbols.get(key)) != null,
                        "The drawing boundary resolves " + key);
            Method element = resolved(dex, symbols.get("drawElement"));
            Method content = resolved(dex, symbols.get("drawContent"));
            Method context = resolved(dex, symbols.get("drawContext"));
            Method canvas = resolved(dex, symbols.get("contextCanvas"));
            Method nativeCanvas = resolved(dex, symbols.get("nativeCanvas"));
            if (element != null
                    && content != null
                    && context != null
                    && canvas != null
                    && nativeCanvas != null) {
                check(
                        element.getName().equals("<init>")
                                && element.getParameterTypes()
                                        .equals(List.of("Lkotlin/jvm/functions/Function1;")),
                        "The visual clip is a drawing modifier callback");
                check(
                        content.getParameterTypes().isEmpty()
                                && content.getReturnType().equals("V")
                                && content.getDefiningClass().equals(context.getDefiningClass()),
                        "The native drawing callback owns its context");
                check(
                        context.getReturnType().equals(canvas.getDefiningClass())
                                && canvas.getParameterTypes().isEmpty(),
                        "The canvas follows the actual native context type");
                check(
                        nativeCanvas.getParameterTypes().equals(List.of(canvas.getReturnType()))
                                && nativeCanvas.getReturnType().equals("Landroid/graphics/Canvas;"),
                        "The drawing boundary reaches the platform canvas");
            }
        }
        // Exactly three calls: the portrait lyrics, the landscape slot and the frosted-glass state.
        int portrait = 0, landscape = 0, haze = 0;
        for (String caller : callers) {
            String type = caller.substring(0, caller.indexOf("->"));
            String call = caller.substring(caller.lastIndexOf(':') + 1);
            if (call.equals("portrait")) portrait++;
            else if (call.equals("landscape")) landscape++;
            else if (call.equals("haze")
                    && type.startsWith("Lcom/music/bitchord/ui/player/NowPlayingScreenKt")) haze++;
        }
        check(
                callers.size() == 3 && portrait == 1 && landscape == 1 && haze == 1,
                "Only the portrait lyrics, the landscape slot and the glass state call it: "
                        + callers);

        for (ClassDef c : dex.values()) {
            for (Method m : c.getMethods()) {
                Code code = new Code(m);
                List<Integer> hooks = code.callsTo(KARAOKE, "portrait");
                if (!hooks.isEmpty() && !c.getType().startsWith("Lapp/bitchord/telegram/Karaoke")) {
                    int hook = hooks.get(0);
                    check(
                            c.getType()
                                    .startsWith("Lcom/music/bitchord/ui/player/NowPlayingScreenKt"),
                            "The portrait hook is in the player screen: " + c.getType());
                    check(code.callsTo(KARAOKE, "portrait").size() == 1, "Hooked once");
                    MethodReference motion = hook > 0 ? code.call(hook - 1) : null;
                    check(
                            motion != null
                                    && motion.getDefiningClass().equals(LYRICS)
                                    && motion.getName().equals("LyricsTranslationMotion"),
                            "It follows the lyrics panel's motion wrapper, outside the translate"
                                    + " buttons");
                    check(
                            code.callsTo(LYRICS, "LyricsTranslationMotion").size() == 1,
                            "That wrapper is the portrait lyrics panel's only one");
                    boolean passesVisibility =
                            code.ins.get(hook) instanceof FiveRegisterInstruction f
                                    && f.getRegisterCount() >= 2
                                    && code.ins.get(hook + 1).getOpcode() == Opcode.IF_EQZ
                                    && ((OneRegisterInstruction) code.ins.get(hook + 1))
                                                    .getRegisterA()
                                            == f.getRegisterD();
                    check(
                            passesVisibility,
                            "It is given the register the host tests next to see if the lyrics"
                                    + " show");
                    String taken = call(code, hook).getParameterTypes().toString();
                    check(
                            taken.equals("[Ljava/lang/Object;, Z]")
                                    || taken.equals("[Ljava/lang/Object;, Z, Ljava/lang/Object;]"),
                            "It takes the composer, the lyrics-visible flag and maybe the picker");
                    if (taken.endsWith("Ljava/lang/Object;]") && taken.contains(", Z,")) {
                        int picker = ((FiveRegisterInstruction) code.ins.get(hook)).getRegisterE();
                        boolean reads = false;
                        for (int k = hook + 1; k < code.ins.size(); k++)
                            if (code.call(k) != null
                                    && code.call(k).getName().equals("getPicking")
                                    && code.ins.get(k) instanceof FiveRegisterInstruction r
                                    && r.getRegisterC() == picker) {
                                reads = true;
                                break;
                            }
                        check(reads, "The picker it is given is the one the host asks if picking");
                    }
                    // The backdrop's frost state is reported first, from the screen's own
                    // parameter.
                    boolean frostFirst = false;
                    if (code.ins.get(0) instanceof RegisterRangeInstruction first
                            && code.call(0) != null
                            && code.call(0).getDefiningClass().equals(KARAOKE)
                            && code.call(0).getName().equals("haze")
                            && m.getImplementation() != null) {
                        int offset = 0, slot = -1;
                        for (CharSequence type : m.getParameterTypes()) {
                            if (type.toString().equals("Ldev/chrisbanes/haze/HazeState;"))
                                slot =
                                        m.getImplementation().getRegisterCount()
                                                - paramSlots(m)
                                                + offset;
                            offset +=
                                    type.toString().equals("J") || type.toString().equals("D")
                                            ? 2
                                            : 1;
                        }
                        frostFirst =
                                slot == first.getStartRegister() && first.getRegisterCount() == 1;
                    }
                    check(
                            frostFirst || !hasHaze,
                            "The player screen reports its own backdrop frost state first");
                    // Neither the translate buttons nor their fade decide whether the capsule
                    // exists.
                    List<Integer> toggles = code.callsTo(LYRICS, "TranslationToggleButton");
                    check(
                            toggles.size() == 1 && toggles.get(0) > hook,
                            "The translate buttons come after it and do not gate it");
                    int align = -1;
                    for (int k = toggles.get(0); k >= 1; k--)
                        if (code.call(k) != null
                                && code.call(k).getName().equals("align")
                                && code.field(k - 1) != null
                                && code.field(k - 1).getName().equals("BottomEnd")
                                && code.field(k - 1).getDefiningClass().equals(ALIGNMENT)) {
                            align = k;
                            break;
                        }
                    check(
                            align > hook,
                            "The bottom-end box of the translate button is the same box");
                }
                List<Integer> lands = code.callsTo(KARAOKE, "landscape");
                if (!lands.isEmpty() && !c.getType().startsWith("Lapp/bitchord/telegram/Karaoke")) {
                    int hook = lands.get(0);
                    check(
                            c.getType().equals("Lcom/music/bitchord/ui/player/LandscapePlayerKt;"),
                            "The landscape hook is in the landscape player: " + c.getType());
                    List<Integer> statuses = code.callsTo(LYRICS, "LyricsStatusWithChange");
                    check(
                            statuses.size() == 1 && statuses.get(0) < hook,
                            "It is in the pane that holds the status line, after it");
                    int status = statuses.isEmpty() ? hook : statuses.get(0);
                    boolean beforeJump = code.isGoto(hook + 1) && code.target.containsKey(hook + 1);
                    check(beforeJump, "It sits right before the jump into the slot's end");
                    if (!beforeJump) continue;
                    int close = code.target.get(hook + 1);
                    MethodReference end = code.call(close);
                    check(
                            end != null
                                    && end.getDefiningClass().equals(COMPOSER)
                                    && end.getName().equals("end")
                                    && end.getParameterTypes().toString().equals("[Z]"),
                            "That jump lands on the call that closes the translation slot");
                    int entries = 0, jumps = 0;
                    for (Map.Entry<Integer, Integer> e : code.target.entrySet()) {
                        if (e.getValue() != close) continue;
                        entries++;
                        if (code.isGoto(e.getKey())) jumps++;
                    }
                    check(
                            jumps == 1 && entries == 1,
                            "The slot has one common jump and no branch bypasses Karaoke");
                    int content = -1;
                    for (int k = status; k < hook; k++)
                        if (code.call(k) != null
                                && code.call(k).getName().equals("invoke")
                                && code.call(k).getDefiningClass().contains("ComposableLambdaImpl")
                                && code.call(k).getParameterTypes().size() == 2) content = k;
                    check(
                            content > status && content < hook,
                            "The translation toggle slot runs before it, after the status line");
                    boolean elseJoins = false;
                    for (Map.Entry<Integer, Integer> e : code.target.entrySet())
                        if (code.isGoto(e.getKey())
                                && e.getKey() > hook
                                && e.getValue() <= hook
                                && e.getValue() > content) elseJoins = true;
                    check(elseJoins, "The other branch jumps back to just before the hook");
                    check(
                            code.ins.get(hook) instanceof FiveRegisterInstruction f
                                    && f.getRegisterCount() == 1
                                    && f.getRegisterC()
                                            == ((FiveRegisterInstruction) code.ins.get(close))
                                                    .getRegisterC(),
                            "It passes the composer that closes the slot");

                    // One 34 dp slot each side of the status line, as the offsets assume.
                    List<Integer> slots = sizeCalls(code);
                    check(
                            slots.size() == 2 && slots.get(0) < status && slots.get(1) > status,
                            "The row has one size-modified slot either side of the status line");
                    for (int slot : slots)
                        check(
                                Float.valueOf(34f)
                                        .equals(
                                                floatBefore(
                                                        code,
                                                        slot,
                                                        ((FiveRegisterInstruction)
                                                                        code.ins.get(slot))
                                                                .getRegisterD())),
                                "A row slot is 34 dp");

                    // The status line keeps its own 12 dp side margin, and nothing widens it.
                    check(
                            code.callsTo(KARAOKE, "landscapeReserve").isEmpty(),
                            "No margin hook is left");
                    List<Integer> margins = new ArrayList<>();
                    for (int k = 0; k < code.ins.size(); k++)
                        if (code.call(k) != null
                                && code.call(k).getDefiningClass().equals(OFFSET_KT)
                                && code.call(k).getName().startsWith("padding-")
                                && code.call(k)
                                        .getParameterTypes()
                                        .toString()
                                        .equals("[F, F, I, Landroidx/compose/ui/Modifier;]")
                                && k > slots.get(0)
                                && k < status) margins.add(k);
                    check(margins.size() == 1, "The status box has one horizontal padding");
                    if (margins.size() == 1) {
                        int at = margins.get(0);
                        check(
                                Float.valueOf(12f)
                                        .equals(
                                                floatBefore(
                                                        code,
                                                        at,
                                                        ((FiveRegisterInstruction) code.ins.get(at))
                                                                .getRegisterC())),
                                "The status line is padded 12 dp a side");
                    }
                }
                if (c.getType().equals(LYRICS)) {
                    for (Method t : c.getMethods()) {
                        if (!t.getName().equals("TranslationToggleButton")) continue;
                        Code body = new Code(t);
                        int discs = 0;
                        for (int slot : sizeCalls(body))
                            if (Float.valueOf(34f)
                                    .equals(
                                            floatBefore(
                                                    body,
                                                    slot,
                                                    ((FiveRegisterInstruction) body.ins.get(slot))
                                                            .getRegisterD()))) discs++;
                        check(discs == 1, "The translate button is one 34 dp disc");
                    }
                }
            }
        }

        // The control needs nothing optional: no Telegram, TDLib, Soulseek, ONNX or switch classes.
        String[] optional = {
            "Lorg/drinkless",
            "Lai/onnxruntime",
            "Lapp/bitchord/telegram/Telegram",
            "Lapp/bitchord/soulseek",
            "Lapp/bitchord/telegram/VideoSwitch",
            "Lapp/bitchord/telegram/AudioOrigin",
            "Lapp/bitchord/telegram/SourceAutomix",
        };
        List<String> reaches = new ArrayList<>();
        for (String type : dex.keySet())
            if (type.equals(KARAOKE)
                    || type.startsWith(KARAOKE.replace(";", "$"))
                    || type.equals(CAPSULE)
                    || type.startsWith(CAPSULE.replace(";", "$")))
                for (Method m : dex.get(type).getMethods())
                    for (String bad : optional)
                        if (refers(m, bad)) reaches.add(type + "." + m.getName() + " -> " + bad);
        check(
                reaches.isEmpty(),
                "The control has no hard dependency on Telegram, TDLib, Soulseek, ONNX, "
                        + "the switch or the source engines: "
                        + reaches);

        // The model path stays the one in SeparationModel.
        boolean usesModel = false, usesEnabled = false, usesLevel = false;
        for (Method m : dex.get(KARAOKE).getMethods()) {
            usesModel |= refers(m, "SeparationModel;->METHOD") || refers(m, "SeparationModel");
            usesEnabled |= refers(m, "SoundExtras;->setSingEnabled");
            usesLevel |= refers(m, "SoundExtras;->setSingLevel");
        }
        check(
                usesModel && usesEnabled && usesLevel,
                "Taps use the model, the enable setter and the level setter of the settings page");
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}

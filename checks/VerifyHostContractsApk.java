import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;

import java.io.File;
import java.util.*;

/**
 * Checks the BitChord contracts that the source list, DJ queue and Battery saver changes rely on.
 * With {@code --original <apk>} the source configuration shape agrees with the Sources screen,
 * BitChord's queue shuffle state is readable when it exists, and the Automix switch setter has a
 * return that no branch skips. With a patched and an original APK, every such return in the patched
 * setter is preceded by the Battery saver call when that patch is applied.
 */
public final class VerifyHostContractsApk {
    static int checks;

    static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    static final String STRING = "Ljava/lang/String;";
    static final String KIND = "Lcom/music/bitchord/data/sources/SourceKind;";
    static final List<String> LEGACY_CONFIG = List.of(STRING, KIND, STRING, STRING, "Z", "Z", "Z");
    static final List<String> QUALITY_CONFIG =
            List.of(STRING, KIND, STRING, STRING, "Z", "Z", "Z", "Ljava/util/List;", STRING);
    static final String PADDING = "Landroidx/compose/foundation/layout/PaddingValuesImpl;";
    static final String F0 = "Lkotlin/jvm/functions/Function0;";
    static final String F1 = "Lkotlin/jvm/functions/Function1;";
    static final List<String> TAIL =
            List.of(
                    "Landroidx/compose/ui/Modifier;",
                    "Landroidx/compose/runtime/ComposerImpl;",
                    "I");

    static List<String> join(List<String> head, List<String> tail) {
        List<String> all = new ArrayList<>(head);
        all.addAll(tail);
        return all;
    }

    static Map<String, ClassDef> classes(File apk) throws Exception {
        Map<String, ClassDef> all = new HashMap<>();
        MultiDexContainer<? extends DexBackedDexFile> container =
                DexFileFactory.loadDexContainer(apk, Opcodes.getDefault());
        for (String entry : container.getDexEntryNames())
            for (ClassDef cls : container.getEntry(entry).getDexFile().getClasses())
                all.put(cls.getType(), cls);
        return all;
    }

    static List<String> types(Method method) {
        List<String> out = new ArrayList<>();
        for (CharSequence type : method.getParameterTypes()) out.add(type.toString());
        return out;
    }

    static long constructors(ClassDef config, List<String> shape) {
        return stream(config.getMethods())
                .filter(
                        m ->
                                m.getName().equals("<init>")
                                        && (m.getAccessFlags() & AccessFlags.PUBLIC.getValue()) != 0
                                        && types(m).equals(shape))
                .count();
    }

    static <T> java.util.stream.Stream<T> stream(Iterable<T> values) {
        return java.util.stream.StreamSupport.stream(values.spliterator(), false);
    }

    static void sources(Map<String, ClassDef> all) {
        ClassDef config = all.get("Lcom/music/bitchord/data/sources/SourceConfig;");
        check(config != null, "SourceConfig exists");
        long legacy = constructors(config, LEGACY_CONFIG);
        long quality = constructors(config, QUALITY_CONFIG);
        long fields =
                stream(config.getFields())
                        .filter(
                                f ->
                                        (f.getName().equals("qualityOptions")
                                                        && f.getType().equals("Ljava/util/List;"))
                                                || (f.getName().equals("pickedQuality")
                                                        && f.getType().equals(STRING)))
                        .count();
        boolean old = legacy == 1 && quality == 0 && fields == 0;
        boolean current = legacy == 0 && quality == 1 && fields == 2;
        check(old || current, "SourceConfig has exactly one known constructor shape");
        List<String> screen =
                join(
                        join(
                                List.of(PADDING),
                                current ? List.of(F1, F1, F0, F0, F0) : List.of(F1, F0, F0, F0)),
                        TAIL);
        int owners = 0;
        for (ClassDef cls : all.values()) {
            if (!cls.getType().startsWith("Lcom/music/bitchord/ui/screens/")) continue;
            for (Method m : cls.getMethods())
                if (m.getName().equals("SourcesScreen")
                        && m.getReturnType().equals("V")
                        && types(m).equals(screen)) {
                    owners++;
                    int reads = 0;
                    for (Instruction i : m.getImplementation().getInstructions())
                        if (i.getOpcode() == Opcode.SGET_OBJECT
                                && ((ReferenceInstruction) i).getReference()
                                        instanceof FieldReference f
                                && f.getDefiningClass()
                                        .equals("Lcom/music/bitchord/data/sources/SourceRegistry;")
                                && f.getName().equals("configs")) reads++;
                    check(reads == 1, "The Sources screen reads the configuration once");
                }
        }
        check(
                owners == 1,
                "One Sources screen takes the " + (current ? "quality" : "1.9") + " callbacks");
    }

    /** What the source rows are built and published with. */
    static void registry(Map<String, ClassDef> all) {
        ClassDef kind = all.get(KIND);
        check(kind != null, "SourceKind exists");
        check(
                stream(kind.getFields())
                        .anyMatch(
                                f ->
                                        f.getName().equals("ADDON")
                                                && f.getType().equals(KIND)
                                                && isStatic(f.getAccessFlags())
                                                && isPublic(f.getAccessFlags())),
                "SourceKind.ADDON is a public static field");
        ClassDef registry = all.get("Lcom/music/bitchord/data/sources/SourceRegistry;");
        check(registry != null, "SourceRegistry exists");
        check(
                stream(registry.getFields())
                        .anyMatch(
                                f ->
                                        f.getName().equals("configs")
                                                && isStatic(f.getAccessFlags())
                                                && isPublic(f.getAccessFlags())
                                                && implementsType(
                                                        all,
                                                        f.getType(),
                                                        "Lkotlinx/coroutines/flow/StateFlow;")),
                "SourceRegistry.configs is a public static state flow");
        for (String call :
                List.of(
                        "publish:Ljava/util/ArrayList;,Z",
                        "config:Ljava/lang/String;",
                        "setEnabled:Ljava/lang/String;,Z")) {
            String[] parts = call.split(":");
            check(
                    stream(registry.getMethods())
                                    .filter(
                                            m ->
                                                    m.getName().equals(parts[0])
                                                            && isStatic(m.getAccessFlags())
                                                            && isPublic(m.getAccessFlags())
                                                            && types(m).equals(
                                                                            List.of(
                                                                                    parts[1].split(
                                                                                            ","))))
                                    .count()
                            == 1,
                    "SourceRegistry." + parts[0] + " is a public static call");
        }
        ClassDef config = all.get("Lcom/music/bitchord/data/sources/SourceConfig;");
        for (String getter :
                List.of(
                        "getId:Ljava/lang/String;",
                        "getBaseUrl:Ljava/lang/String;",
                        "getEnabled:Z")) {
            String[] parts = getter.split(":");
            check(
                    stream(config.getMethods())
                            .anyMatch(
                                    m ->
                                            m.getName().equals(parts[0])
                                                    && isPublic(m.getAccessFlags())
                                                    && !isStatic(m.getAccessFlags())
                                                    && types(m).isEmpty()
                                                    && m.getReturnType().equals(parts[1])),
                    "SourceConfig." + parts[0] + " is a public getter");
        }
    }

    static void shuffle(Map<String, ClassDef> all) {
        ClassDef shuffle = all.get("Lcom/music/bitchord/playback/QueueShuffle;");
        if (shuffle == null) return;
        Set<String> states =
                Set.of(
                        "Lkotlinx/coroutines/flow/StateFlow;",
                        "Lkotlinx/coroutines/flow/ReadonlyStateFlow;");
        check(
                stream(shuffle.getMethods())
                                .filter(
                                        m ->
                                                m.getName().equals("getEnabled")
                                                        && !m.getParameterTypes()
                                                                .iterator()
                                                                .hasNext()
                                                        && states.contains(m.getReturnType())
                                                        && (m.getAccessFlags()
                                                                        & AccessFlags.PUBLIC
                                                                                .getValue())
                                                                != 0)
                                .count()
                        == 1,
                "The in-app shuffle state is a public state flow");
    }

    static boolean isPublic(int flags) {
        return (flags & AccessFlags.PUBLIC.getValue()) != 0;
    }

    static boolean isStatic(int flags) {
        return (flags & AccessFlags.STATIC.getValue()) != 0;
    }

    /** A public, concrete, no-argument or one-argument method of the class or a superclass. */
    static Method callable(Map<String, ClassDef> all, String type, String name, String argument) {
        for (ClassDef cls = all.get(type); cls != null; cls = all.get(cls.getSuperclass()))
            for (Method m : cls.getMethods())
                if (m.getName().equals(name)
                        && isPublic(m.getAccessFlags())
                        && (m.getAccessFlags() & AccessFlags.ABSTRACT.getValue()) == 0
                        && types(m).equals(argument == null ? List.of() : List.of(argument)))
                    return m;
        return null;
    }

    static boolean implementsType(Map<String, ClassDef> all, String type, String wanted) {
        if (type == null || type.equals(wanted)) return true;
        ClassDef cls = all.get(type);
        if (cls == null) return false;
        for (String face : cls.getInterfaces()) if (implementsType(all, face, wanted)) return true;
        return cls.getSuperclass() != null && implementsType(all, cls.getSuperclass(), wanted);
    }

    /** The calls the equaliser per output and Battery saver make on BitChord's settings. */
    static void settings(Map<String, ClassDef> all) {
        ClassDef settings = all.get("Lcom/music/bitchord/data/settings/AppSettings;");
        check(settings != null, "AppSettings exists");
        boolean instance = false;
        for (Field f : settings.getFields())
            instance |=
                    f.getName().equals("INSTANCE")
                            && f.getType().equals(settings.getType())
                            && isStatic(f.getAccessFlags());
        check(instance, "AppSettings has the INSTANCE the setters are called on");
        for (String name :
                List.of("equalizerToneX", "equalizerToneY", "equalizerBalance", "equalizerMode")) {
            Field flow = null;
            for (Field f : settings.getFields())
                if (f.getName().equals(name)
                        && isStatic(f.getAccessFlags())
                        && isPublic(f.getAccessFlags())) flow = f;
            check(flow != null, name + " is a public static field");
            ClassDef type = all.get(flow.getType());
            check(type != null && isPublic(type.getAccessFlags()), name + " has a public class");
            // The class is called through its own setValue: the MutableStateFlow interface has
            // none.
            check(
                    callable(all, flow.getType(), "setValue", "Ljava/lang/Object;") != null,
                    name + " has a public setValue(Object)");
            check(
                    callable(all, flow.getType(), "getValue", null) != null,
                    name + " has a public getValue()");
        }
        for (String call :
                List.of(
                        "setEqualizerBands:Ljava/util/List;",
                        "setEqualizerFocused:Z",
                        "setEqualizerEnabled:Z")) {
            String[] parts = call.split(":");
            long found =
                    stream(settings.getMethods())
                            .filter(
                                    m ->
                                            m.getName().equals(parts[0])
                                                    && isStatic(m.getAccessFlags())
                                                    && isPublic(m.getAccessFlags())
                                                    && m.getReturnType().equals("V")
                                                    && types(m).equals(List.of(parts[1])))
                            .count();
            check(found == 1, parts[0] + " is a public static setter");
        }
        ClassDef mode = all.get("Lcom/music/bitchord/data/settings/EqualizerMode;");
        check(mode != null, "EqualizerMode exists");
        // R8 can drop an enum constant's field; the constant itself is created and found by name.
        check(
                stream(mode.getMethods())
                                .filter(
                                        m ->
                                                m.getName().equals("values")
                                                        && isStatic(m.getAccessFlags())
                                                        && isPublic(m.getAccessFlags())
                                                        && types(m).isEmpty())
                                .count()
                        == 1,
                "EqualizerMode lists its values");
        Set<String> names = new HashSet<>();
        for (Method m : mode.getMethods())
            if (m.getName().equals("<clinit>"))
                for (Instruction i : code(m))
                    if (i instanceof ReferenceInstruction r
                            && r.getReference() instanceof StringReference text)
                        names.add(text.getString());
        check(
                names.containsAll(List.of("DYNAMIC", "MANUAL")),
                "EqualizerMode creates DYNAMIC and MANUAL");
        ClassDef state = all.get("Lkotlinx/coroutines/flow/StateFlow;");
        check(
                state != null
                        && stream(state.getMethods())
                                .anyMatch(
                                        m -> m.getName().equals("getValue") && types(m).isEmpty()),
                "StateFlow has getValue()");
        Method fade = null;
        for (Method m : settings.getMethods())
            if (m.getName().equals("getSmartFadeEnabled")
                    && isStatic(m.getAccessFlags())
                    && isPublic(m.getAccessFlags())
                    && types(m).isEmpty()) fade = m;
        check(fade != null, "The Automix switch is read through a public static getter");
        check(
                implementsType(all, fade.getReturnType(), "Lkotlinx/coroutines/flow/StateFlow;"),
                "The Automix switch getter returns a StateFlow");
    }

    static Method setter(Map<String, ClassDef> all) {
        ClassDef settings = all.get("Lcom/music/bitchord/data/settings/AppSettings;");
        check(settings != null, "AppSettings exists");
        Method found = null;
        for (Method m : settings.getMethods())
            if (m.getName().equals("setSmartFadeEnabled")
                    && types(m).equals(List.of("Z"))
                    && m.getReturnType().equals("V")
                    && (m.getAccessFlags() & AccessFlags.STATIC.getValue()) != 0) {
                check(found == null, "One Automix switch setter");
                found = m;
            }
        check(
                found != null && found.getImplementation() != null,
                "The Automix switch setter exists");
        return found;
    }

    /** Instruction indexes that a branch or switch lands on. */
    static Set<Integer> landings(List<Instruction> code) {
        Map<Integer, Integer> at = new HashMap<>();
        int[] address = new int[code.size()];
        int unit = 0;
        for (int i = 0; i < code.size(); i++) {
            address[i] = unit;
            at.put(unit, i);
            unit += code.get(i).getCodeUnits();
        }
        Set<Integer> into = new HashSet<>();
        for (int i = 0; i < code.size(); i++) {
            Instruction instruction = code.get(i);
            Opcode opcode = instruction.getOpcode();
            if (opcode == Opcode.PACKED_SWITCH || opcode == Opcode.SPARSE_SWITCH) {
                int payload =
                        at.get(address[i] + ((OffsetInstruction) instruction).getCodeOffset());
                for (SwitchElement element :
                        ((SwitchPayload) code.get(payload)).getSwitchElements())
                    into.add(at.get(address[i] + element.getOffset()));
            } else if (instruction instanceof OffsetInstruction offset
                    && !(instruction instanceof SwitchPayload)
                    && opcode != Opcode.FILL_ARRAY_DATA)
                into.add(at.get(address[i] + offset.getCodeOffset()));
        }
        return into;
    }

    static List<Instruction> code(Method method) {
        List<Instruction> out = new ArrayList<>();
        method.getImplementation().getInstructions().forEach(out::add);
        return out;
    }

    static void original(File apk) throws Exception {
        Map<String, ClassDef> all = classes(apk);
        sources(all);
        registry(all);
        shuffle(all);
        settings(all);
        List<Instruction> code = code(setter(all));
        Set<Integer> into = landings(code);
        int returns = 0;
        for (int i = 0; i < code.size(); i++)
            if (code.get(i).getOpcode() == Opcode.RETURN_VOID) {
                returns++;
                check(!into.contains(i), "No branch skips the setter's return");
            }
        check(returns >= 1, "The setter returns");
    }

    static boolean applied(Map<String, ClassDef> all, String name) {
        return applied(all, "Lapp/bitchord/telegram/SoundExtras;", name);
    }

    static boolean applied(Map<String, ClassDef> all, String owner, String name) {
        ClassDef extras = all.get(owner);
        if (extras == null) return false;
        for (Method m : extras.getMethods()) {
            if (!m.getName().equals(name) || m.getImplementation() == null) continue;
            List<Instruction> body = code(m);
            return body.size() == 2
                    && body.get(0).getOpcode() == Opcode.CONST_4
                    && ((NarrowLiteralInstruction) body.get(0)).getNarrowLiteral() == 1;
        }
        return false;
    }

    static void patched(File patched, File original) throws Exception {
        original(original);
        Map<String, ClassDef> now = classes(patched);
        sources(now);
        registry(now);
        settings(now);
        List<Instruction> code = code(setter(now));
        Set<Integer> into = landings(code);
        boolean refresh =
                applied(now, "batterySaverApplied")
                        || applied(now, "Lapp/bitchord/telegram/SourceAutomix;", "applied");
        int hooks = 0, returns = 0;
        for (int i = 0; i < code.size(); i++) {
            Instruction instruction = code.get(i);
            if (instruction.getOpcode() == Opcode.INVOKE_STATIC
                    && ((ReferenceInstruction) instruction).getReference()
                            instanceof MethodReference m
                    && m.getDefiningClass().equals("Lapp/bitchord/telegram/SoundExtras;")
                    && m.getName().equals("smartFadeChanged")) {
                hooks++;
                check(
                        code.get(i + 1).getOpcode() == Opcode.RETURN_VOID,
                        "The Battery saver call sits right before a return");
                check(!into.contains(i + 1), "No branch skips the call");
            }
            if (instruction.getOpcode() == Opcode.RETURN_VOID) returns++;
        }
        check(
                refresh ? hooks == returns : hooks == 0,
                "The Automix switch has one refresh per return when a playback patch needs it");
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--original")) original(new File(args[1]));
        else if (args.length == 2) patched(new File(args[0]), new File(args[1]));
        else
            throw new IllegalArgumentException(
                    "usage: <patched.apk> <original.apk> | --original <apk>");
        System.out.println("PASS: " + checks + " host contract checks");
    }
}

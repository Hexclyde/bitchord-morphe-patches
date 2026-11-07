import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;

import java.io.File;
import java.util.*;
import java.util.stream.Stream;

/** Checks the native player download action. Args: patched.apkOrDexDirectory [present|absent]. */
public final class VerifyPlayerDownloadApk {
    private static final String ENTRY = "Lapp/bitchord/telegram/PlayerDownload;";
    private static final String COMPOSER = "Landroidx/compose/runtime/ComposerImpl;";
    private static final String MODIFIER = "Landroidx/compose/ui/Modifier;";
    private static final String SONG = "Lcom/music/bitchord/data/model/Song;";
    private static final String DOWNLOADS = "Lcom/music/bitchord/download/Downloads;";
    private static final Map<String, ClassDef> classes = new HashMap<>();
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static List<Instruction> code(Method method) {
        List<Instruction> result = new ArrayList<>();
        if (method.getImplementation() != null)
            method.getImplementation().getInstructions().forEach(result::add);
        return result;
    }

    private static Reference reference(Instruction instruction) {
        return instruction instanceof ReferenceInstruction ref ? ref.getReference() : null;
    }

    private static MethodReference call(Instruction instruction) {
        return reference(instruction) instanceof MethodReference method ? method : null;
    }

    private static String text(Instruction instruction) {
        return reference(instruction) instanceof StringReference string ? string.getString() : null;
    }

    private static List<String> args(MethodReference method) {
        List<String> result = new ArrayList<>();
        method.getParameterTypes().forEach(type -> result.add(type.toString()));
        return result;
    }

    private static String signature(MethodReference method) {
        return method.getDefiningClass()
                + "->"
                + method.getName()
                + "("
                + String.join("", args(method))
                + ")"
                + method.getReturnType();
    }

    private static boolean calls(Instruction instruction, String owner, String name) {
        MethodReference method = call(instruction);
        return method != null
                && method.getDefiningClass().equals(owner)
                && method.getName().equals(name);
    }

    private static int[] registers(Instruction instruction) {
        if (instruction instanceof RegisterRangeInstruction range) {
            int[] result = new int[range.getRegisterCount()];
            for (int i = 0; i < result.length; i++) result[i] = range.getStartRegister() + i;
            return result;
        }
        if (instruction instanceof FiveRegisterInstruction five)
            return Arrays.copyOf(
                    new int[] {
                        five.getRegisterC(),
                        five.getRegisterD(),
                        five.getRegisterE(),
                        five.getRegisterF(),
                        five.getRegisterG()
                    },
                    five.getRegisterCount());
        throw new AssertionError("Download call register encoding");
    }

    private static Map<Integer, Integer> targets(List<Instruction> code) {
        Map<Integer, Integer> indexAt = new HashMap<>();
        int address = 0;
        for (int i = 0; i < code.size(); i++) {
            indexAt.put(address, i);
            address += code.get(i).getCodeUnits();
        }
        Map<Integer, Integer> result = new HashMap<>();
        address = 0;
        for (int i = 0; i < code.size(); i++) {
            Instruction instruction = code.get(i);
            String opcode = instruction.getOpcode().name();
            if (instruction instanceof OffsetInstruction offset
                    && !opcode.startsWith("PACKED")
                    && !opcode.startsWith("SPARSE")
                    && !opcode.startsWith("FILL")) {
                Integer target = indexAt.get(address + offset.getCodeOffset());
                check(target != null, "Download host branch lands on an instruction");
                result.put(i, target);
            }
            address += instruction.getCodeUnits();
        }
        return result;
    }

    private static Method unique(List<Method> found, String what) {
        check(found.size() == 1, "Unique " + what + ": " + found.size());
        return found.get(0);
    }

    private static List<Method> methods() {
        List<Method> result = new ArrayList<>();
        classes.values().forEach(type -> type.getMethods().forEach(result::add));
        return result;
    }

    private static List<Method> nativeCalls(String name) {
        List<Method> result = new ArrayList<>();
        for (Method method : methods()) {
            if (method.getDefiningClass().startsWith("Lapp/bitchord/")) continue;
            for (Instruction instruction : code(method))
                if (calls(instruction, ENTRY, name)) result.add(method);
        }
        return result;
    }

    private static void title(Method title) {
        List<Instruction> code = code(title);
        check(
                code.stream().anyMatch(i -> "playerCredits".equals(text(i))),
                "Download is in native title credits row");
        int entry = -1;
        for (int i = 0; i < code.size(); i++) if (calls(code.get(i), ENTRY, "compose")) entry = i;
        check(
                entry > 0 && entry + 5 < code.size(),
                "Download title entry has surrounding native layout");
        MethodReference spacer = call(code.get(entry - 1));
        check(
                spacer != null
                        && spacer.getName().equals("Spacer")
                        && args(spacer).equals(List.of(COMPOSER, MODIFIER))
                        && spacer.getReturnType().equals("V"),
                "Download follows title-to-actions spacer");
        check(
                Arrays.equals(
                        registers(code.get(entry)), new int[] {registers(code.get(entry - 1))[0]}),
                "Download receives the title row composer");
        MethodReference compose = call(code.get(entry));
        check(
                args(compose).equals(List.of("Ljava/lang/Object;"))
                        && compose.getReturnType().equals("V"),
                "Download composition ABI");
        check(
                code.get(entry + 1).getOpcode() == Opcode.IF_NEZ,
                "Download is before signed-in favorite condition");
        MethodReference local = call(code.get(entry + 2));
        check(
                local != null
                        && local.getDefiningClass().equals(SONG)
                        && local.getName().equals("getLocalUri")
                        && args(local).isEmpty(),
                "Download is before local-file favorite condition");
        check(
                code.get(entry + 3).getOpcode() == Opcode.MOVE_RESULT_OBJECT
                        && code.get(entry + 4).getOpcode() == Opcode.IF_EQZ,
                "Favorite local-file branch remains intact");
        Map<Integer, Integer> targets = targets(code);
        int favorite = targets.getOrDefault(entry + 1, -1);
        check(favorite > entry + 4, "Signed-in branch reaches native favorite after download");
        check(
                !targets.containsValue(entry + 1) && !targets.containsValue(entry + 2),
                "Neither login state bypasses download composition");
        int unlike = -1;
        int circular = 0;
        for (int i = favorite; i < code.size(); i++) {
            Reference reference = reference(code.get(i));
            if (reference instanceof FieldReference field
                    && field.getName().equals("remove_from_liked$delegate")) unlike = i;
            MethodReference method = call(code.get(i));
            if (method != null
                    && args(method)
                            .equals(
                                    List.of(
                                            "Landroidx/compose/ui/graphics/vector/ImageVector;",
                                            "Ljava/lang/String;",
                                            "Lkotlin/jvm/functions/Function0;",
                                            "Z",
                                            "Lcom/music/bitchord/ui/haptics/Haptic;",
                                            MODIFIER,
                                            COMPOSER,
                                            "I",
                                            "I"))) circular++;
        }
        check(
                unlike > favorite && circular == 2,
                "Favorite and menu retain their native actions after download");
    }

    private static void controller(Method observer) {
        check(
                observer.getDefiningClass()
                                .equals("Lcom/music/bitchord/playback/PlayerConnectionKt;")
                        && args(observer)
                                .equals(
                                        List.of(
                                                "Landroidx/media3/session/MediaController;",
                                                COMPOSER))
                        && observer.getReturnType()
                                .equals("Lcom/music/bitchord/playback/PlayerState;"),
                "Download observes native player state independently");
        int parameter = observer.getImplementation().getRegisterCount() - 2;
        boolean position = false;
        for (Instruction instruction : code(observer)) {
            if (calls(instruction, ENTRY, "controller"))
                check(
                        Arrays.equals(registers(instruction), new int[] {parameter}),
                        "Download receives actual MediaController parameter");
            if (instruction.getOpcode() == Opcode.NEW_INSTANCE
                    && reference(instruction) instanceof TypeReference type
                    && type.getType().equals("Lcom/music/bitchord/playback/PlaybackPosition;"))
                position = true;
        }
        check(position, "Player observer keeps native playback position state");
    }

    private static void route(Method route) {
        check(
                route.getDefiningClass().equals(DOWNLOADS)
                        && args(route)
                                .equals(
                                        List.of(
                                                "Landroid/content/Context;",
                                                SONG,
                                                "Lcom/music/bitchord/data/settings/DownloadQuality;",
                                                "Lkotlin/coroutines/jvm/internal/ContinuationImpl;"))
                        && route.getReturnType().equals("Ljava/lang/Object;"),
                "Download uses existing manager route");
        List<Instruction> code = code(route);
        int entry = -1;
        for (int i = 0; i < code.size(); i++) if (calls(code.get(i), ENTRY, "route")) entry = i;
        check(
                entry >= 3 && entry + 4 < code.size(),
                "Download route is guarded before ordinary routing");
        int parameter = route.getImplementation().getRegisterCount() - 4;
        for (int i = 0; i < 3; i++) {
            Instruction move = code.get(entry - 3 + i);
            check(
                    move.getOpcode() == Opcode.MOVE_OBJECT_FROM16
                            && move instanceof TwoRegisterInstruction registers
                            && registers.getRegisterA() == i
                            && registers.getRegisterB() == parameter + (i == 2 ? 3 : i),
                    "Download route passes native input " + i);
        }
        check(
                Arrays.equals(registers(code.get(entry)), new int[] {0, 1, 2}),
                "Download route relay registers");
        check(
                code.get(entry + 1).getOpcode() == Opcode.MOVE_RESULT_OBJECT
                        && ((OneRegisterInstruction) code.get(entry + 1)).getRegisterA() == 0
                        && code.get(entry + 2).getOpcode() == Opcode.IF_EQZ
                        && ((OneRegisterInstruction) code.get(entry + 2)).getRegisterA() == 0
                        && code.get(entry + 3).getOpcode() == Opcode.RETURN_OBJECT
                        && ((OneRegisterInstruction) code.get(entry + 3)).getRegisterA() == 0,
                "Only selected download jobs return a supplied route");
        check(
                targets(code).get(entry + 2) == entry + 4,
                "Ordinary downloads fall through to original manager");
        boolean sourceRows = false, finalResolver = false;
        int sourceRoutes = 0;
        for (Instruction instruction : code) {
            MethodReference method = call(instruction);
            if (method == null) continue;
            sourceRows |=
                    method.getDefiningClass()
                                    .equals("Lcom/music/bitchord/data/sources/SourceRegistry;")
                            && method.getName().equals("parseTrackKey");
            finalResolver |=
                    method.getDefiningClass()
                                    .equals("Lcom/music/bitchord/data/innertube/StreamResolver;")
                            && method.getName().equals("resolveForDownload");
            if (method.getReturnType().equals("Lcom/music/bitchord/download/Downloads$Route;")
                    && args(method)
                            .equals(
                                    List.of(
                                            "Landroid/content/Context;",
                                            SONG,
                                            "Lcom/music/bitchord/download/Downloads$Kept;")))
                sourceRoutes++;
        }
        check(
                sourceRows && finalResolver && sourceRoutes == 2,
                "Native source-row/search/final routing remains intact");
    }

    private static void lifecycle(List<Method> lifecycle) {
        Set<String> expected = new HashSet<>(Set.of("clear", "fail", "cancel"));
        for (Method method : lifecycle) {
            check(
                    method.getDefiningClass().equals(DOWNLOADS)
                            && expected.remove(method.getName()),
                    "Download capability cleanup uses each native completion path once");
            check(
                    args(method)
                                    .equals(
                                            method.getName().equals("fail")
                                                    ? List.of(
                                                            "Ljava/lang/String;",
                                                            "Ljava/lang/String;")
                                                    : List.of("Ljava/lang/String;"))
                            && method.getReturnType().equals("V")
                            && (method.getAccessFlags() & 8) != 0,
                    "Download capability cleanup host ABI");
            List<Instruction> code = code(method);
            check(
                    calls(code.get(0), ENTRY, "finished"),
                    "Capability cleanup runs before native state mutation");
            int parameter = method.getImplementation().getRegisterCount() - args(method).size();
            check(
                    Arrays.equals(registers(code.get(0)), new int[] {parameter}),
                    "Capability cleanup receives only the completed job id");
            check(
                    args(call(code.get(0))).equals(List.of("Ljava/lang/String;"))
                            && call(code.get(0)).getReturnType().equals("V"),
                    "Capability cleanup extension ABI");
        }
        check(
                expected.isEmpty(),
                "Success, failure and cancellation release in-memory capabilities");
    }

    private static Map<String, String> symbols() {
        Method method = null;
        for (Method candidate : classes.get(ENTRY).getMethods())
            if (candidate.getName().equals("symbols") && args(candidate).isEmpty())
                method = candidate;
        check(method != null, "Download reflection symbols method exists");
        List<Instruction> code = code(method);
        check(
                code.size() >= 2
                        && text(code.get(0)) != null
                        && code.get(1).getOpcode() == Opcode.RETURN_OBJECT,
                "Download symbols return patched host references");
        Map<String, String> result = new HashMap<>();
        for (String line : text(code.get(0)).replace("\\n", "\n").split("\n")) {
            int at = line.indexOf('=');
            check(
                    at > 0 && result.put(line.substring(0, at), line.substring(at + 1)) == null,
                    "Unique download symbol entry");
        }
        for (String key : List.of("androidView", "companion", "size", "startGroup", "endGroup"))
            check(result.containsKey(key), "Download symbol " + key);
        check(
                !(result.containsKey("tint") && result.containsKey("chrome")),
                "One native player color model");
        if (result.containsKey("tint") || result.containsKey("chrome"))
            check(result.containsKey("consume"), "Download reads native composition color");
        if (result.containsKey("chrome"))
            check(result.containsKey("blend"), "Download uses native chrome blend");
        for (Map.Entry<String, String> entry : result.entrySet()) {
            String reference = entry.getValue();
            int arrow = reference.indexOf("->");
            check(arrow > 0, "Download symbol reference syntax: " + entry.getKey());
            ClassDef owner = classes.get(reference.substring(0, arrow));
            check(owner != null, "Download symbol owner exists: " + entry.getKey());
            boolean found = false;
            if (reference.indexOf('(', arrow) >= 0) {
                for (Method candidate : owner.getMethods())
                    if (signature(candidate).equals(reference)) found = true;
            } else {
                for (Field field : owner.getFields())
                    if ((field.getDefiningClass() + "->" + field.getName() + ":" + field.getType())
                            .equals(reference)) found = true;
            }
            check(found, "Download host ABI exists: " + entry.getKey());
        }
        return result;
    }

    private static void hostMethod(
            String owner, String name, String result, boolean isStatic, String... parameters) {
        ClassDef type = classes.get(owner);
        check(type != null, "Download reflected host class " + owner);
        List<Method> found = new ArrayList<>();
        for (Method method : type.getMethods())
            if (method.getName().equals(name)
                    && method.getReturnType().equals(result)
                    && args(method).equals(Arrays.asList(parameters))) found.add(method);
        Method method = unique(found, "download reflected " + owner + "->" + name);
        check(
                ((method.getAccessFlags() & 8) != 0) == isStatic,
                "Download reflected invocation type " + name);
    }

    private static void hostField(String owner, String name, String expected) {
        ClassDef type = classes.get(owner);
        check(type != null, "Download reflected field class " + owner);
        boolean found = false;
        for (Field field : type.getFields())
            if (field.getName().equals(name) && field.getType().equals(expected)) found = true;
        check(found, "Download reflected field " + owner + "->" + name);
    }

    private static void reflectedHost() {
        String source = "Lcom/music/bitchord/data/sources/";
        String string = "Ljava/lang/String;";
        String context = "Landroid/content/Context;";
        String continuation = "Lkotlin/coroutines/jvm/internal/ContinuationImpl;";
        hostMethod(
                source + "SourceResolver;",
                "forDownloadOfRow",
                "Ljava/lang/Object;",
                false,
                string,
                string,
                string,
                source + "StreamRequest;",
                continuation);
        // 1.9 ends at the drift bar; 1.9.1 adds lengthLater and checkFileLength before it.
        String[] matching = {
            source + "MusicSource;",
            source + "TrackMatcher$Target;",
            source + "StreamRequest;",
            "Z",
            "Z",
            "Z",
            "I"
        };
        List<String[]> signatures = new ArrayList<>();
        signatures.add(
                Stream.concat(Arrays.stream(matching), Stream.of(continuation))
                        .toArray(String[]::new));
        signatures.add(
                Stream.concat(
                                Arrays.stream(matching),
                                Stream.of("Lkotlin/jvm/functions/Function1;", "Z", continuation))
                        .toArray(String[]::new));
        ClassDef resolver = classes.get(source + "SourceResolver;");
        check(resolver != null, "Download reflected host class " + source + "SourceResolver;");
        List<String[]> known = new ArrayList<>();
        for (String[] signature : signatures)
            for (Method method : resolver.getMethods())
                if (method.getName().equals("matchAndStream")
                        && method.getReturnType().equals("Ljava/lang/Object;")
                        && args(method).equals(Arrays.asList(signature))) {
                    known.add(signature);
                    break;
                }
        check(
                known.size() == 1,
                "Download reflected matchAndStream has " + known.size() + " known signatures");
        hostMethod(
                source + "SourceResolver;",
                "matchAndStream",
                "Ljava/lang/Object;",
                false,
                known.get(0));
        hostMethod(
                source + "TrackMatcher$Target;",
                "<init>",
                "V",
                false,
                string,
                string,
                "Ljava/lang/Integer;",
                string,
                "Ljava/lang/Boolean;",
                "Z");
        hostField(source + "StreamRequest$Best;", "INSTANCE", source + "StreamRequest$Best;");
        hostMethod(
                "Lcom/music/bitchord/data/LocalMediaRepository;",
                "hasStoragePermission",
                "Z",
                true,
                context);
        hostMethod(
                "Lcom/music/bitchord/data/LocalMediaRepository;",
                "getLocalMusic",
                "Ljava/lang/Object;",
                true,
                context,
                continuation);
        hostMethod(
                "Lcom/music/bitchord/playback/PlayerConnectionKt;",
                "toSong",
                SONG,
                true,
                "Landroidx/media3/common/MediaItem;");
        hostMethod(source + "StreamFormat;", "isLossless", "Ljava/lang/Boolean;", false);
        hostField(source + "StreamFormat;", "codec", string);
        for (String name : List.of("kbps", "bitDepth", "sampleRateHz"))
            hostField(source + "StreamFormat;", name, "Ljava/lang/Integer;");
        hostField(source + "SourceStream;", "url", string);
        hostField(source + "SourceStream;", "headers", "Ljava/util/Map;");
        hostField(source + "SourceStream;", "format", source + "StreamFormat;");
        hostField(source + "SourceStream;", "durationSec", "Ljava/lang/Integer;");
        hostField(source + "SourceStream;", "sourceConfigId", string);
        for (String name : List.of("getEnabled", "getAllowDownloads"))
            hostMethod(source + "SourceConfig;", name, "Z", false);
        hostMethod(source + "SourceConfig;", "getKind", source + "SourceKind;", false);
        hostMethod(source + "SourceRegistry;", "config", source + "SourceConfig;", true, string);
        hostMethod(
                DOWNLOADS,
                "keepable",
                "Ljava/lang/Object;",
                false,
                source + "SourceStream;",
                "Ljava/lang/Integer;",
                continuation);
        hostMethod(
                DOWNLOADS,
                "sourceRoute",
                "Lcom/music/bitchord/download/Downloads$Route;",
                true,
                context,
                SONG,
                "Lcom/music/bitchord/download/Downloads$Kept;");
        hostMethod(
                DOWNLOADS,
                "access$routeFor",
                "Ljava/lang/Object;",
                true,
                context,
                SONG,
                "Lcom/music/bitchord/data/settings/DownloadQuality;",
                continuation);
        String job = "Lkotlinx/coroutines/Job;", key = "Lkotlinx/coroutines/Job$Key;";
        ClassDef keyType = classes.get(key);
        int singletons = 0;
        boolean accessible = false;
        if (keyType != null) {
            for (Field field : keyType.getFields()) {
                if (!field.getType().equals(key) || (field.getAccessFlags() & 8) == 0) continue;
                singletons++;
                accessible = (field.getAccessFlags() & 25) == 25;
            }
        }
        check(
                keyType != null
                        && keyType.getInterfaces()
                                .contains("Lkotlin/coroutines/CoroutineContext$Key;")
                        && (keyType.getAccessFlags() & 1) != 0
                        && singletons == 1
                        && accessible,
                "Native download coroutine key has one typed public singleton");
        hostMethod(job, "isActive", "Z", false);
        hostMethod(job, "cancel", "V", false, "Ljava/util/concurrent/CancellationException;");
        hostMethod("Lkotlinx/coroutines/JobImpl;", "<init>", "V", false, job);
        String running = "Lcom/music/bitchord/download/DownloadState$Running;";
        hostField(running, "fraction", "F");
        boolean publicFraction = false;
        for (Field field : classes.get(running).getFields())
            if (field.getName().equals("fraction")
                    && field.getType().equals("F")
                    && (field.getAccessFlags() & 1) != 0) publicFraction = true;
        check(publicFraction, "Download progress fraction remains publicly readable");
        String failed = "Lcom/music/bitchord/download/DownloadState$Failed;";
        hostField(failed, "reason", string);
        hostMethod(failed, "<init>", "V", false, string);
        for (String name : List.of("_active", "_saved"))
            hostField(DOWNLOADS, name, "Lkotlinx/coroutines/flow/StateFlowImpl;");
        hostField(
                "Lcom/music/bitchord/data/settings/DownloadQuality;",
                "LOSSLESS",
                "Lcom/music/bitchord/data/settings/DownloadQuality;");
    }

    private static boolean optional(String type) {
        if (type == null) return false;
        while (type.startsWith("[")) type = type.substring(1);
        return type.startsWith("Lorg/drinkless/")
                || type.startsWith("Lapp/bitchord/soulseek/")
                || type.startsWith("Lapp/bitchord/telegram/TelegramBridge")
                || type.startsWith("Lapp/bitchord/telegram/TelegramCatalogue")
                || type.startsWith("Lapp/bitchord/telegram/SourceAutomix")
                || type.startsWith("Lapp/bitchord/telegram/TransitionRuntime")
                || type.startsWith("Lapp/bitchord/telegram/DspAnalysis");
    }

    private static void independence() {
        for (ClassDef type : classes.values()) {
            if (!type.getType().startsWith(ENTRY.substring(0, ENTRY.length() - 1))) continue;
            check(!optional(type.getSuperclass()), "Download helper superclass is independent");
            for (String implemented : type.getInterfaces())
                check(!optional(implemented), "Download helper interface is independent");
            for (Field field : type.getFields())
                check(!optional(field.getType()), "Download helper field is independent");
            for (Method method : type.getMethods()) {
                check(!optional(method.getReturnType()), "Download helper result is independent");
                for (String parameter : args(method))
                    check(!optional(parameter), "Download helper parameter is independent");
                for (Instruction instruction : code(method)) {
                    Reference ref = reference(instruction);
                    if (ref instanceof TypeReference target)
                        check(
                                !optional(target.getType()),
                                "Download type reference is independent");
                    if (ref instanceof MethodReference target) {
                        check(
                                !optional(target.getDefiningClass())
                                        && !optional(target.getReturnType()),
                                "Download call is independent");
                        for (String parameter : args(target))
                            check(!optional(parameter), "Download call argument is independent");
                    }
                    if (ref instanceof FieldReference target)
                        check(
                                !optional(target.getDefiningClass()) && !optional(target.getType()),
                                "Download field reference is independent");
                }
            }
        }
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 1 || arguments.length > 2)
            throw new IllegalArgumentException("patched.apkOrDexDirectory [present|absent]");
        boolean present = arguments.length == 1 || !arguments[1].equals("absent");
        if (arguments.length == 2 && !Set.of("present", "absent").contains(arguments[1]))
            throw new IllegalArgumentException("Expected present or absent");
        File input = new File(arguments[0]);
        if (input.isDirectory()) {
            File[] files = input.listFiles((directory, name) -> name.endsWith(".dex"));
            check(files != null && files.length > 0, "DEX directory contains files");
            for (File file : files)
                for (ClassDef type :
                        DexFileFactory.loadDexFile(file, Opcodes.getDefault()).getClasses())
                    check(
                            classes.put(type.getType(), type) == null,
                            "Unique download-check class " + type.getType());
        } else {
            var container = DexFileFactory.loadDexContainer(input, Opcodes.getDefault());
            for (String entry : container.getDexEntryNames())
                for (ClassDef type : container.getEntry(entry).getDexFile().getClasses())
                    check(
                            classes.put(type.getType(), type) == null,
                            "Unique download-check class " + type.getType());
        }
        check(classes.containsKey(ENTRY) == present, "Player download runtime matches selection");
        List<Method> title = nativeCalls("compose"),
                controller = nativeCalls("controller"),
                route = nativeCalls("route"),
                finished = nativeCalls("finished");
        check(title.size() == (present ? 1 : 0), "One native player download composition hook");
        check(controller.size() == (present ? 1 : 0), "One native download player observer");
        check(route.size() == (present ? 1 : 0), "One native download route hook");
        check(
                finished.size() == (present ? 3 : 0),
                "Three native download capability cleanup hooks");
        if (present) {
            lifecycle(finished);
            title(title.get(0));
            controller(controller.get(0));
            route(route.get(0));
            symbols();
            reflectedHost();
            independence();
        }
        System.out.println("Player download APK checks: " + checks + " passed");
    }
}

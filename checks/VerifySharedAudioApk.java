import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipFile;

/** Checks the shared cancellable decoder and its architecture-specific runtime. */
public final class VerifySharedAudioApk {
    private static final String AUDIO = "Lapp/bitchord/telegram/SourceAudio;";
    private static final String DECODER = "Lcom/music/bitchord/playback/smart/AudioDecoder;";
    private static final String SOURCE = "Landroid/media/MediaDataSource;";
    private static final String FUNCTION0 = "Lkotlin/jvm/functions/Function0;";
    private static final String FUNCTION4 = "Lkotlin/jvm/functions/Function4;";
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static List<String> parameters(MethodReference method) {
        return method.getParameterTypes().stream().map(Object::toString).toList();
    }

    private static Method unique(ClassDef owner, List<String> args, String result) {
        check(owner != null, "Shared decoder class exists");
        List<Method> found = new ArrayList<>();
        for (Method method : owner.getMethods())
            if (parameters(method).equals(args) && method.getReturnType().equals(result))
                found.add(method);
        check(found.size() == 1, "One shared decoder method: " + args + result);
        Method method = found.getFirst();
        check((method.getAccessFlags() & 9) == 9, "Decoder method is public static");
        return method;
    }

    private static List<Instruction> code(Method method) {
        List<Instruction> body = new ArrayList<>();
        if (method.getImplementation() != null)
            method.getImplementation().getInstructions().forEach(body::add);
        return body;
    }

    private static void redirect(Method method, String name, int words) {
        List<Instruction> body = code(method);
        check(body.size() == 3, "Decoder has no platform codec tail");
        check(method.getImplementation().getTryBlocks().isEmpty(), "Decoder has no stale handlers");
        check(
                method.getImplementation().getRegisterCount() == words + 1,
                "Decoder register layout");
        Instruction first = body.getFirst();
        boolean arguments =
                words == 7
                        ? first.getOpcode() == Opcode.INVOKE_STATIC_RANGE
                                && first instanceof RegisterRangeInstruction range
                                && range.getStartRegister() == 1
                                && range.getRegisterCount() == 7
                        : first.getOpcode() == Opcode.INVOKE_STATIC
                                && first instanceof FiveRegisterInstruction five
                                && five.getRegisterCount() == 1
                                && five.getRegisterC() == 1;
        check(arguments, "Decoder forwards every argument, including both double words");
        check(
                first instanceof ReferenceInstruction reference
                        && reference.getReference() instanceof MethodReference call
                        && call.getDefiningClass().equals(AUDIO)
                        && call.getName().equals(name)
                        && parameters(call).equals(parameters(method))
                        && call.getReturnType().equals(method.getReturnType()),
                "Decoder bridge retains its exact callback and return ABI");
        check(
                body.get(1).getOpcode() == Opcode.MOVE_RESULT_OBJECT
                        && ((OneRegisterInstruction) body.get(1)).getRegisterA() == 0
                        && body.get(2).getOpcode() == Opcode.RETURN_OBJECT
                        && ((OneRegisterInstruction) body.get(2)).getRegisterA() == 0,
                "Nullable decoder result is returned unchanged");
    }

    private static void nativeMethod(
            ClassDef audio, String name, List<String> args, String result) {
        List<Method> found = new ArrayList<>();
        for (Method method : audio.getMethods())
            if (method.getName().equals(name)) found.add(method);
        check(found.size() == 1, "One shared native entry: " + name);
        Method method = found.getFirst();
        check(
                parameters(method).equals(args)
                        && method.getReturnType().equals(result)
                        && (method.getAccessFlags() & (8 | 256)) == (8 | 256),
                "Shared native entry ABI: " + name);
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 1, "One APK required");
        Map<String, ClassDef> types = new HashMap<>();
        var container = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.getDefault());
        for (String entry : container.getDexEntryNames())
            for (ClassDef type : container.getEntry(entry).getDexFile().getClasses())
                types.put(type.getType(), type);
        try (ZipFile apk = new ZipFile(args[0])) {
            ClassDef audio = types.get(AUDIO);
            if (audio == null) {
                check(
                        apk.stream()
                                .noneMatch(e -> e.getName().endsWith("/libbitchord_preview.so")),
                        "Selection without shared audio adds no decoder runtime");
                System.out.println("PASS shared audio absent: " + checks);
                return;
            }
            check(
                    ((org.w3c.dom.Element)
                                    VerifyApk.manifest(new File(args[0]))
                                            .getElementsByTagName("application")
                                            .item(0))
                            .getAttributeNS(VerifyApk.NS, "extractNativeLibs")
                            .equals("true"),
                    "Shared native decoder is extracted independently of APK compression and"
                            + " alignment");
            List<String> rawArgs = List.of(SOURCE, "D", "D", FUNCTION0, FUNCTION4);
            Method raw = unique(types.get(DECODER), rawArgs, "Lkotlin/Pair;");
            Method duration = unique(types.get(DECODER), List.of(SOURCE), "Ljava/lang/Double;");
            redirect(raw, "decodeRaw", 7);
            redirect(duration, "containerDurationSeconds", 1);
            check(
                    unique(audio, rawArgs, "Lkotlin/Pair;").getName().equals("decodeRaw"),
                    "Shared streaming bridge is available");
            check(
                    unique(audio, List.of(SOURCE), "Ljava/lang/Double;")
                            .getName()
                            .equals("containerDurationSeconds"),
                    "Shared duration bridge is available");
            nativeMethod(audio, "nativeOpen", List.of("Ljava/lang/Object;"), "J");
            nativeMethod(audio, "nativeInfo", List.of("J"), "[J");
            nativeMethod(audio, "nativeCodec", List.of("J"), "Ljava/lang/String;");
            nativeMethod(audio, "nativeSeek", List.of("J", "J"), "V");
            nativeMethod(audio, "nativeNext", List.of("J"), "[B");
            nativeMethod(audio, "nativeTime", List.of("J"), "J");
            nativeMethod(audio, "nativeClose", List.of("J"), "V");
            Set<String> abis = new HashSet<>();
            apk.stream()
                    .map(e -> e.getName())
                    .filter(n -> n.startsWith("lib/") && n.endsWith(".so"))
                    .forEach(n -> abis.add(n.split("/")[1]));
            check(
                    !abis.isEmpty()
                            && Set.of("arm64-v8a", "armeabi-v7a", "x86_64").containsAll(abis),
                    "Shared decoder has only supported native architectures");
            Map<String, Integer> machines =
                    Map.of("arm64-v8a", 183, "armeabi-v7a", 40, "x86_64", 62);
            for (String abi : abis) {
                var entry = apk.getEntry("lib/" + abi + "/libbitchord_preview.so");
                check(entry != null, "Shared decoder installed for " + abi);
                byte[] elf = apk.getInputStream(entry).readAllBytes();
                check(
                        elf.length >= 64
                                && elf[0] == 127
                                && elf[1] == 'E'
                                && elf[2] == 'L'
                                && elf[3] == 'F'
                                && elf[5] == 1
                                && ((elf[18] & 255) | ((elf[19] & 255) << 8)) == machines.get(abi),
                        "Shared decoder ELF architecture: " + abi);
                String symbols = new String(elf, StandardCharsets.ISO_8859_1);
                for (String name :
                        List.of(
                                "nativeOpen",
                                "nativeInfo",
                                "nativeCodec",
                                "nativeSeek",
                                "nativeNext",
                                "nativeTime",
                                "nativeClose"))
                    check(
                            symbols.contains(
                                    "Java_app_bitchord_telegram_SourceAudio_" + name + "\0"),
                            "Shared decoder JNI symbol: " + abi + "/" + name);
            }
            for (String license :
                    List.of("COPYING.LGPLv2.1", "COPYING.LGPLv3", "LICENSE.md", "NOTICE"))
                check(
                        apk.getEntry("assets/bitchord/runtimes/licenses/preview/" + license)
                                != null,
                        "Shared decoder notice: " + license);
        }
        System.out.println("PASS shared audio: " + checks);
    }
}

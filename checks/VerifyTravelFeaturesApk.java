import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;

import java.io.File;
import java.util.*;

/** Qualifies independent playback features against the patched host bytecode. */
public final class VerifyTravelFeaturesApk {
    private static int checks;
    private static final String BASE = "Lapp/bitchord/telegram/";

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static int calls(Method method, String owner, String name) {
        int count = 0;
        if (method.getImplementation() != null)
            for (Instruction instruction : method.getImplementation().getInstructions())
                if (instruction instanceof ReferenceInstruction reference
                        && reference.getReference() instanceof MethodReference call
                        && call.getDefiningClass().equals(owner)
                        && call.getName().equals(name)) count++;
        return count;
    }

    private static boolean applied(Map<String, ClassDef> classes, String marker) {
        return applied(classes, BASE + "SoundExtras;", marker);
    }

    private static boolean applied(Map<String, ClassDef> classes, String owner, String marker) {
        ClassDef extras = classes.get(owner);
        if (extras == null) return false;
        for (Method method : extras.getMethods())
            if (method.getName().equals(marker))
                for (Instruction instruction : method.getImplementation().getInstructions())
                    if (instruction instanceof NarrowLiteralInstruction literal)
                        return literal.getNarrowLiteral() == 1;
        return false;
    }

    private static void loadingGate(Map<String, ClassDef> classes, boolean present) {
        ClassDef type = classes.get("Landroidx/media3/exoplayer/DefaultLoadControl;");
        Method loading = null;
        for (Method method : type.getMethods())
            if (method.getName().equals("shouldContinueLoading")
                    && method.getParameterTypes()
                            .equals(
                                    List.of(
                                            "Landroidx/media3/exoplayer/LoadControl$Parameters;"))) {
                check(loading == null, "One native loading decision exists");
                loading = method;
            }
        check(loading != null, "Native loading decision exists");
        if (!present) return;
        List<Instruction> code = new ArrayList<>();
        loading.getImplementation().getInstructions().forEach(code::add);
        check(code.size() > 6, "The buffer gate retains the native loading body");
        int registers = loading.getImplementation().getRegisterCount();
        check(
                code.get(0).getOpcode() == Opcode.INVOKE_STATIC_RANGE
                        && code.get(0) instanceof RegisterRangeInstruction range
                        && range.getStartRegister() == registers - 2
                        && range.getRegisterCount() == 2
                        && ((ReferenceInstruction) code.get(0)).getReference()
                                instanceof MethodReference call
                        && call.getDefiningClass().equals(BASE + "SteadyPlayback;")
                        && call.getName().equals("allowLoading")
                        && call.getParameterTypes()
                                .equals(List.of("Ljava/lang/Object;", "Ljava/lang/Object;"))
                        && call.getReturnType().equals("Z"),
                "Every player loading decision enters the exact control/parameter gate");
        check(
                code.get(1).getOpcode() == Opcode.MOVE_RESULT
                        && ((OneRegisterInstruction) code.get(1)).getRegisterA() == 0
                        && code.get(2).getOpcode() == Opcode.IF_NEZ
                        && ((OneRegisterInstruction) code.get(2)).getRegisterA() == 0,
                "An allowed buffer read follows the original decision");
        check(
                code.get(3).getOpcode() == Opcode.CONST_4
                        && ((OneRegisterInstruction) code.get(3)).getRegisterA() == 0
                        && ((NarrowLiteralInstruction) code.get(3)).getNarrowLiteral() == 0
                        && code.get(4).getOpcode() == Opcode.RETURN
                        && ((OneRegisterInstruction) code.get(4)).getRegisterA() == 0,
                "A full buffer returns false without entering the native loader");
        int branch = code.get(0).getCodeUnits() + code.get(1).getCodeUnits();
        int original = branch;
        for (int i = 2; i < 5; i++) original += code.get(i).getCodeUnits();
        check(
                branch + ((OffsetInstruction) code.get(2)).getCodeOffset() == original
                        && code.get(5).getOpcode() == Opcode.NOP
                        && code.get(6).getOpcode() == Opcode.IGET_OBJECT
                        && ((TwoRegisterInstruction) code.get(6)).getRegisterA() == 0
                        && ((TwoRegisterInstruction) code.get(6)).getRegisterB() == registers - 1
                        && ((ReferenceInstruction) code.get(6)).getReference()
                                instanceof FieldReference field
                        && field.getDefiningClass()
                                .equals("Landroidx/media3/exoplayer/LoadControl$Parameters;")
                        && field.getName().equals("playerId"),
                "The gate preserves native entry labels and overwrites only its dead local");
    }

    public static void main(String[] args) throws Exception {
        Map<String, ClassDef> classes = new HashMap<>();
        var dex = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.getDefault());
        for (String entry : dex.getDexEntryNames())
            for (ClassDef type : dex.getEntry(entry).getDexFile().getClasses())
                classes.put(type.getType(), type);
        boolean focus = applied(classes, "audioFocusApplied");
        boolean steady = applied(classes, "steadyPlaybackApplied");
        boolean bufferGate = steady || applied(classes, BASE + "SourceAutomix;", "applied");
        boolean smart = applied(classes, "smartDownloadsApplied");
        boolean sing = applied(classes, "singApplied");
        int built = 0,
                moved = 0,
                control = 0,
                tick = 0,
                retries = 0,
                admission = 0,
                filenames = 0,
                guards = 0;
        int players = 0, downloadSettings = 0, playRequested = 0, loadErrors = 0;
        int flacPlayback = 0, flacSearch = 0;
        int singEnd = 0, singInput = 0, singConfigure = 0, singBoundary = 0;
        int singWrites = 0, unwrappedWrites = 0;
        for (ClassDef type : classes.values())
            if (type.getType().startsWith("Lcom/music/bitchord/")
                    || type.getType().equals("Landroidx/media3/exoplayer/DefaultLoadControl;")
                    || type.getType().equals("Landroidx/media3/exoplayer/ExoPlayerImpl;"))
                for (Method method : type.getMethods()) {
                    players += calls(method, BASE + "SoundExtras;", "player");
                    downloadSettings += calls(method, BASE + "DownloadsSettingsUi;", "row");
                    built += calls(method, BASE + "AudioFocus;", "built");
                    moved += calls(method, BASE + "AudioFocus;", "setAudioAttributes");
                    playRequested += calls(method, BASE + "AudioFocus;", "playRequested");
                    control += calls(method, BASE + "SteadyPlayback;", "loadControl");
                    tick += calls(method, BASE + "SteadyPlayback;", "allowLoading");
                    retries += calls(method, BASE + "SteadyPlayback;", "minRetries");
                    loadErrors += calls(method, BASE + "SteadyPlayback;", "loadError");
                    singEnd += calls(method, BASE + "SingOutput;", "playToEnd");
                    singInput += calls(method, BASE + "SingOutput;", "holdsInput");
                    singConfigure += calls(method, BASE + "SingOutput;", "configure");
                    singBoundary += calls(method, BASE + "SingOutput;", "streamBoundary");
                    if (type.getType()
                                    .equals(
                                            "Lcom/music/bitchord/playback/audio/PrecisionAudioSink;")
                            && Set.of("handleBuffer", "hostHandleBuffer")
                                    .contains(method.getName())) {
                        singWrites += calls(method, type.getType(), "bitchordSingWrite");
                        unwrappedWrites +=
                                calls(
                                        method,
                                        "Landroidx/media3/exoplayer/audio/DefaultAudioSink;",
                                        "handleBuffer");
                    }
                    if (method.getName().equals("activeForPlayback"))
                        flacPlayback += calls(method, BASE + "FlacAddon;", "playbackSources");
                    if (method.getName().equals("submitSearch"))
                        flacSearch += calls(method, BASE + "FlacAddon;", "playbackSources");
                    admission += calls(method, BASE + "DownloadSelection;", "remember");
                    filenames += calls(method, BASE + "DownloadSelection;", "fileNameStart");
                    guards += calls(method, BASE + "DownloadSelection;", "forbidFallback");
                }
        check(
                !sing || players == 4,
                "All four player construction paths receive Sing lifecycle events");
        int singHooks = sing ? 1 : 0;
        check(
                singEnd == singHooks
                        && singInput == singHooks
                        && singConfigure == singHooks
                        && singBoundary == singHooks,
                "Sing timing and drain hooks match the installed marker");
        check(
                sing ? singWrites >= 9 && unwrappedWrites == 0 : singWrites == 0,
                "Every precision write follows Sing timing only when installed ("
                        + singWrites
                        + " wrapped, "
                        + unwrappedWrites
                        + " direct)");
        check(downloadSettings == 1, "Native Downloads settings contains one patch entry");
        check(built == (focus ? 1 : 0), "Alongside builder hook matches installed marker");
        check(moved == (focus ? 4 : 0), "All four focus changes match installed marker");
        check(playRequested == (focus ? 1 : 0), "Explicit pause clears call recovery tickets");
        check(
                control == (bufferGate ? 1 : 0),
                "Shared load control hook matches installed features");
        check(
                tick == (bufferGate ? 1 : 0),
                "Buffer admission runs at every native loading decision");
        loadingGate(classes, bufferGate);
        check(retries == (steady ? 1 : 0), "Offline retry policy matches installed marker");
        check(
                loadErrors == (steady ? 1 : 0),
                "Offline errors retain the normal online retry allowance");
        boolean flac = applied(classes, "flacAddonApplied");
        check(
                flacPlayback == (flac ? 1 : 0) && flacSearch == (flac ? 1 : 0),
                "Prepared-file provider is excluded from playback and playback search");
        check(
                admission == 1 && filenames == 1 && guards == 1,
                "Source choices and download metadata have one shared hook each");
        for (String helper :
                List.of(
                        "AudioFocus",
                        "SteadyPlayback",
                        "SmartDownloads",
                        "SmartDownloadsJob",
                        "SmartDownloadSources",
                        "DownloadSelection",
                        "DownloadFolder",
                        "SeparatedSing",
                        "SeparationModel"))
            check(classes.containsKey(BASE + helper + ";"), "Shared helper present: " + helper);
        ClassDef contract = classes.get("Lcom/music/bitchord/data/sources/MusicSource;");
        check(contract != null, "Host source catalogue exists");
        boolean configId = false;
        for (Method method : contract.getMethods())
            if (method.getName().equals("getConfigId")
                    && method.getReturnType().equals("Ljava/lang/String;")) configId = true;
        check(configId, "Source identity uses the actual interface getter");
        if (smart) {
            ClassDef downloadOwner = classes.get("Lcom/music/bitchord/download/Downloads;");
            boolean instance = false, operation = false;
            for (Field field : downloadOwner.getFields())
                if (field.getName().equals("INSTANCE")
                        && field.getType().equals(downloadOwner.getType())
                        && (field.getAccessFlags() & 8) != 0) instance = true;
            for (Method method : downloadOwner.getMethods())
                if (method.getName().startsWith("downloadNow$")
                        && (method.getAccessFlags() & 8) == 0
                        && method.getParameterTypes().size() == 4
                        && method.getReturnType().equals("Ljava/io/Serializable;"))
                    operation = true;
            check(instance && operation, "Scheduled downloads invoke the host Kotlin object");
        }
        if (args.length > 1) {
            check(focus == args[1].contains("focus"), "Requested Alongside marker");
            check(steady == args[1].contains("steady"), "Requested Steady marker");
            check(smart == args[1].contains("smart"), "Requested Smart downloads marker");
        }
        System.out.println("PASS: " + checks + " independent playback feature contracts");
    }
}

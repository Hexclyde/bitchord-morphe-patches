import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;

import java.io.File;
import java.util.*;

/** Checks the actual optimized host ABI used by the optional beta.7 playback features. */
public final class VerifyBeta7Apk {
    private static final String BASE = "Lapp/bitchord/telegram/";
    private static final String DOWNLOADS = "Lcom/music/bitchord/download/Downloads;";
    private static int checks;

    private static void check(boolean value, String description) {
        checks++;
        if (!value) throw new AssertionError(description);
    }

    private static List<Instruction> code(Method method) {
        List<Instruction> result = new ArrayList<>();
        if (method.getImplementation() != null)
            method.getImplementation().getInstructions().forEach(result::add);
        return result;
    }

    private static boolean applied(Map<String, ClassDef> classes, String name) {
        ClassDef extras = classes.get(BASE + "SoundExtras;");
        if (extras == null) return false;
        for (Method method : extras.getMethods())
            if (method.getName().equals(name)) {
                List<Instruction> instructions = code(method);
                check(!instructions.isEmpty(), "Applied marker has a body: " + name);
                check(
                        instructions.get(0) instanceof NarrowLiteralInstruction,
                        "Applied marker is explicit: " + name);
                return ((NarrowLiteralInstruction) instructions.get(0)).getNarrowLiteral() == 1;
            }
        throw new AssertionError("Missing installed-feature marker " + name);
    }

    private static String signature(MethodReference method) {
        return method.getName() + method.getParameterTypes() + method.getReturnType();
    }

    private static List<Integer> arguments(Instruction instruction) {
        if (instruction instanceof RegisterRangeInstruction range) {
            List<Integer> values = new ArrayList<>();
            for (int i = 0; i < range.getRegisterCount(); i++)
                values.add(range.getStartRegister() + i);
            return values;
        }
        if (instruction instanceof FiveRegisterInstruction five)
            return List.of(
                            five.getRegisterC(),
                            five.getRegisterD(),
                            five.getRegisterE(),
                            five.getRegisterF(),
                            five.getRegisterG())
                    .subList(0, five.getRegisterCount());
        throw new AssertionError("Unexpected invoke register encoding");
    }

    public static void main(String[] args) throws Exception {
        var dex = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.getDefault());
        Map<String, ClassDef> classes = new HashMap<>();
        for (String entry : dex.getDexEntryNames())
            for (ClassDef cls : dex.getEntry(entry).getDexFile().getClasses())
                check(classes.put(cls.getType(), cls) == null, "Unique class " + cls.getType());
        boolean host19 =
                VerifyApk.manifest(new File(args[0]))
                        .getDocumentElement()
                        .getAttributeNS(VerifyApk.NS, "versionName")
                        .equals("1.9");

        boolean jamesDsp = applied(classes, "jamesDspApplied");
        var queriedPackages = VerifyApk.manifest(new File(args[0])).getElementsByTagName("package");
        int jamesQueries = 0;
        for (int i = 0; i < queriedPackages.getLength(); i++) {
            var element = (org.w3c.dom.Element) queriedPackages.item(i);
            if (element.getParentNode().getNodeName().equals("queries")
                    && Set.of("me.timschneeberger.rootlessjamesdsp", "james.dsp")
                            .contains(element.getAttributeNS(VerifyApk.NS, "name"))) jamesQueries++;
        }
        check(
                jamesQueries == (jamesDsp ? 2 : 0),
                "JamesDSP package visibility matches optional patch selection");
        boolean quality = applied(classes, "downloadQualityApplied");
        boolean dj = applied(classes, "djApplied");
        boolean headphones = applied(classes, "headphoneApplied");
        boolean sing = applied(classes, "singApplied");
        boolean pitch = applied(classes, "pitchApplied");
        boolean battery = applied(classes, "batterySaverApplied");
        boolean sleep = applied(classes, "sleepFadeApplied");
        boolean outputEq = applied(classes, "outputEqApplied");
        boolean bluetooth = applied(classes, "losslessBluetoothApplied");
        boolean outputs = headphones || outputEq || battery || bluetooth;
        int outputReturns = 0;
        ClassDef service = classes.get("Lcom/music/bitchord/playback/PlaybackService;");
        for (Method method : service.getMethods())
            if (method.getName().equals("applyOutputRoute"))
                for (Instruction instruction : code(method))
                    if (instruction.getOpcode() == Opcode.RETURN_VOID) outputReturns++;
        Map<String, Integer> hooks = new HashMap<>();
        Set<String> helpers =
                Set.of(
                        "SoundExtras",
                        "Outputs",
                        "SleepFade",
                        "HeadphoneEq",
                        "OutputEq",
                        "KeyTempo",
                        "DjMode",
                        "DownloadChoice",
                        "EffectsApps");

        for (ClassDef cls : classes.values()) {
            String simple =
                    cls.getType().startsWith(BASE)
                            ? cls.getType()
                                    .substring(BASE.length())
                                    .replace(";", "")
                                    .split("\\$", 2)[0]
                            : "";
            for (Method method : cls.getMethods()) {
                List<Instruction> instructions = code(method);
                for (Instruction instruction : instructions) {
                    if (!(instruction instanceof ReferenceInstruction reference)) continue;
                    if (reference.getReference() instanceof MethodReference target) {
                        if (!cls.getType().startsWith(BASE)
                                && target.getDefiningClass().startsWith(BASE))
                            hooks.merge(
                                    target.getDefiningClass() + "->" + target.getName(),
                                    1,
                                    Integer::sum);
                        if (helpers.contains(simple)
                                && (target.getDefiningClass().startsWith("Landroidx/media3/")
                                        || target.getDefiningClass()
                                                .startsWith("Lcom/music/bitchord/")))
                            check(
                                    VerifyApk.resolves(
                                            classes,
                                            target,
                                            target.getDefiningClass(),
                                            new HashSet<>()),
                                    "Optimized host method resolves: " + target);

                        if (target.getDefiningClass().equals(BASE + "SoundExtras;")
                                && target.getName().equals("process")
                                && !cls.getType().startsWith(BASE)
                                && host19) {
                            check(
                                    cls.getType()
                                                    .equals(
                                                            "Lcom/music/bitchord/playback/audio/PrecisionAudioSink;")
                                            && method.getName()
                                                    .equals(
                                                            cls.getInterfaces()
                                                                            .contains(
                                                                                    BASE
                                                                                            + "Downmix$Target;")
                                                                    ? "hostHandleBuffer"
                                                                    : "handleBuffer")
                                            && method.getParameterTypes()
                                                    .equals(
                                                            List.of(
                                                                    "I",
                                                                    "J",
                                                                    "Ljava/nio/ByteBuffer;"))
                                            && method.getReturnType().equals("Z"),
                                    "Both precision taps stay in the actual host sink");
                            int position = instructions.indexOf(instruction);
                            check(
                                    position >= 2 && position + 1 < instructions.size(),
                                    "Precision tap has its original chain context");
                            Instruction chain = instructions.get(position - 2);
                            Instruction block = instructions.get(position - 1);
                            check(
                                    chain.getOpcode() == Opcode.IGET_OBJECT
                                            && block.getOpcode() == Opcode.IGET_OBJECT
                                            && chain instanceof ReferenceInstruction chainRef
                                            && chainRef.getReference()
                                                    instanceof FieldReference chainField
                                            && chainField.getName().equals("dspChain")
                                            && block instanceof ReferenceInstruction blockRef
                                            && blockRef.getReference()
                                                    instanceof FieldReference blockField
                                            && blockField.getName().equals("audioBlock"),
                                    "Precision tap follows the sink's chain and audio block reads");
                            TwoRegisterInstruction chainRead = (TwoRegisterInstruction) chain;
                            TwoRegisterInstruction blockRead = (TwoRegisterInstruction) block;
                            check(
                                    chainRead.getRegisterB() == blockRead.getRegisterB()
                                            && arguments(instruction)
                                                    .equals(
                                                            List.of(
                                                                    chainRead.getRegisterB(),
                                                                    blockRead.getRegisterA()))
                                            && target.getParameterTypes()
                                                    .equals(
                                                            List.of(
                                                                    "Ljava/lang/Object;",
                                                                    "Ljava/lang/Object;")),
                                    "Precision tap gets the live sink and block");
                            Instruction next = instructions.get(position + 1);
                            check(
                                    next instanceof ReferenceInstruction nextRef
                                            && nextRef.getReference()
                                                    instanceof MethodReference nextCall
                                            && nextCall.getName().equals("process")
                                            && arguments(next)
                                                    .equals(
                                                            List.of(
                                                                    chainRead.getRegisterA(),
                                                                    blockRead.getRegisterA())),
                                    "Each precision block reaches the original chain immediately"
                                            + " after its tap");
                        }
                        if (target.getDefiningClass().equals(BASE + "SoundExtras;")
                                && target.getName().equals("processPcm16")
                                && !cls.getType().startsWith(BASE)) {
                            check(
                                    cls.getType()
                                                    .equals(
                                                            "Lcom/music/bitchord/playback/EqualizerProcessor;")
                                            && method.getName().equals("queueInput")
                                            && method.getParameterTypes()
                                                    .equals(List.of("Ljava/nio/ByteBuffer;")),
                                    "PCM16 tap is inside the host equaliser's output paths");
                            int position = instructions.indexOf(instruction);
                            check(
                                    position >= 3
                                            && position + 1 < instructions.size()
                                            && instructions.get(position + 1).getOpcode()
                                                    == Opcode.RETURN_VOID,
                                    "PCM16 tap ends its output path without reaching another tap");
                            Instruction flipped = instructions.get(position - 3);
                            check(
                                    flipped instanceof ReferenceInstruction
                                            && ((ReferenceInstruction) flipped).getReference()
                                                    instanceof MethodReference
                                            && ((MethodReference)
                                                            ((ReferenceInstruction) flipped)
                                                                    .getReference())
                                                    .getDefiningClass()
                                                    .equals("Ljava/nio/ByteBuffer;")
                                            && ((MethodReference)
                                                            ((ReferenceInstruction) flipped)
                                                                    .getReference())
                                                    .getName()
                                                    .equals("flip"),
                                    "Each PCM16 tap follows a completed host output buffer");
                        }
                        if (target.getDefiningClass().equals(BASE + "SoundExtras;")
                                && target.getName().equals("outputChanged")
                                && !cls.getType().startsWith(BASE)) {
                            check(
                                    cls.getType()
                                                    .equals(
                                                            "Lcom/music/bitchord/playback/PlaybackService;")
                                            && method.getName().equals("applyOutputRoute"),
                                    "Output-change hook observes the actual host route handler");
                        }
                        if (target.getDefiningClass().equals(BASE + "DownloadChoice;")
                                && target.getName().equals("hold")
                                && cls.getType().equals(DOWNLOADS)) {
                            check(
                                    method.getName().equals("enqueue")
                                            && (method.getAccessFlags() & 8) != 0,
                                    "Quality chooser is in the static download entry");
                            check(
                                    method.getParameterTypes()
                                            .equals(
                                                    List.of(
                                                            "Landroid/content/Context;",
                                                            "Lcom/music/bitchord/data/model/Song;",
                                                            "Ljava/lang/String;")),
                                    "Download entry uses the pinned host arguments");
                            int firstArgument = method.getImplementation().getRegisterCount() - 3;
                            check(
                                    instruction instanceof RegisterRangeInstruction,
                                    "Quality chooser receives a contiguous argument range");
                            RegisterRangeInstruction call = (RegisterRangeInstruction) instruction;
                            check(
                                    call.getRegisterCount() == 3
                                            && call.getStartRegister() == firstArgument,
                                    "Quality chooser receives p0 Context, p1 Song and p2 origin");
                        }
                        if (target.getDefiningClass().equals(BASE + "DownloadChoice;")
                                && target.getName().equals("quality")
                                && cls.getType().equals(DOWNLOADS)) {
                            check(
                                    method.getName().equals("access$routeFor")
                                            && (method.getAccessFlags() & 8) != 0,
                                    "Chosen quality is in the static route accessor");
                            int firstArgument =
                                    method.getImplementation().getRegisterCount()
                                            - method.getParameterTypes().size();
                            check(
                                    instruction instanceof RegisterRangeInstruction,
                                    "Chosen quality uses a contiguous argument range");
                            RegisterRangeInstruction call = (RegisterRangeInstruction) instruction;
                            check(
                                    call.getRegisterCount() == 2
                                            && call.getStartRegister() == firstArgument + 1,
                                    "Chosen quality receives the actual Song and DownloadQuality"
                                            + " arguments");
                        }
                    } else if (reference.getReference() instanceof FieldReference field
                            && helpers.contains(simple)
                            && (field.getDefiningClass().startsWith("Landroidx/media3/")
                                    || field.getDefiningClass()
                                            .startsWith("Lcom/music/bitchord/"))) {
                        check(
                                VerifyApk.fieldResolves(classes, field),
                                "Optimized host field resolves: " + field);
                    }
                }
            }
        }
        for (String name : List.of("hold", "quality"))
            check(
                    hooks.getOrDefault(BASE + "DownloadChoice;->" + name, 0) == (quality ? 1 : 0),
                    "Exactly one optional download-quality hook: " + name);
        check(
                hooks.getOrDefault(BASE + "SoundExtras;->outputChanged", 0)
                        == (outputs ? outputReturns : 0),
                "Selected-output patch updates run at every host routing completion");
        check(
                hooks.getOrDefault(BASE + "SoundExtras;->service", 0) == (dj ? 1 : 0),
                "DJ service hook matches selection");
        check(
                hooks.getOrDefault(BASE + "SoundExtras;->player", 0)
                        == (pitch || battery || sleep || dj ? 4 : 0),
                "Player hooks are shared once across the dependent features");
        boolean audio = sing || headphones || dj;
        check(
                hooks.getOrDefault(BASE + "SoundExtras;->process", 0)
                        == (audio ? (host19 ? 2 : 1) : 0),
                "Precision audio taps cover each host output path once across dependent features");
        check(
                hooks.getOrDefault(BASE + "SoundExtras;->processPcm16", 0) == (audio ? 2 : 0),
                "The two mutually exclusive PCM16 output paths each receive one shared tap");

        ClassDef watcher = classes.get(BASE + "SoundExtras$Watcher;");
        if (watcher != null) {
            ClassDef listener = classes.get("Landroidx/media3/common/Player$Listener;");
            check(listener != null, "Actual Media3 listener interface is present");
            Set<String> actualCallbacks = new HashSet<>();
            for (Method method : listener.getMethods()) actualCallbacks.add(signature(method));
            for (String name :
                    List.of(
                            "onMediaItemTransition",
                            "onIsPlayingChanged",
                            "onPlaybackParametersChanged",
                            "onPlayWhenReadyChanged",
                            "onPositionDiscontinuity")) {
                Set<String> implementations = new HashSet<>();
                for (Method method : watcher.getMethods())
                    if (method.getName().equals(name)) implementations.add(signature(method));
                check(
                        implementations.stream().anyMatch(actualCallbacks::contains),
                        "Watcher overrides the actual optimized Media3 callback: " + name);
            }
            Set<String> watcherCallbacks = new HashSet<>();
            for (Method method : watcher.getMethods()) watcherCallbacks.add(signature(method));
            for (String callback :
                    List.of(
                            "onMediaItemTransition[I, Landroidx/media3/common/MediaItem;]V",
                            "onPlayWhenReadyChanged[I, Z]V",
                            "onPositionDiscontinuity[I]V",
                            "onPositionDiscontinuity[I,"
                                    + " Landroidx/media3/common/Player$PositionInfo;,"
                                    + " Landroidx/media3/common/Player$PositionInfo;]V")) {
                check(
                        actualCallbacks.contains(callback),
                        "Pinned host callback signature: " + callback);
                check(
                        watcherCallbacks.contains(callback),
                        "Watcher implements pinned callback: " + callback);
            }
        }
        System.out.println("PASS: " + checks + " playback hook/optimized-host ABI checks");
    }
}

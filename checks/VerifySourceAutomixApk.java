import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;

import java.io.File;
import java.util.*;

/** Checks executable hooks and optimized host contracts, including standalone installs. */
public final class VerifySourceAutomixApk {
    static int checks;

    static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    static MethodReference call(Instruction i) {
        return i instanceof ReferenceInstruction r && r.getReference() instanceof MethodReference m
                ? m
                : null;
    }

    static List<Instruction> code(Method m) {
        List<Instruction> out = new ArrayList<>();
        if (m.getImplementation() != null)
            m.getImplementation().getInstructions().forEach(out::add);
        return out;
    }

    public static void main(String[] args) throws Exception {
        Map<String, ClassDef> types = new HashMap<>();
        var dex = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.getDefault());
        for (String name : dex.getDexEntryNames())
            for (ClassDef type : dex.getEntry(name).getDexFile().getClasses())
                types.put(type.getType(), type);
        String runtime = "Lapp/bitchord/telegram/SourceAutomix;",
                pref = "Lapp/bitchord/telegram/SourcePreference;";
        check(types.containsKey(runtime), "Source-independent runtime is installed");
        ClassDef timingPair = types.get("Lkotlin/Pair;");
        check(timingPair != null, "Decoder timing pair is present");
        Set<String> timingFields = new HashSet<>();
        for (Field field : timingPair.getFields())
            if (field.getType().equals("Ljava/lang/Object;")) timingFields.add(field.getName());
        check(
                timingFields.containsAll(Set.of("first", "second")),
                "Decoder timing fields match the recording reader");
        boolean removedGetter = false;
        for (Method method : types.get(pref).getMethods())
            for (Instruction instruction : code(method)) {
                MethodReference reference = call(instruction);
                removedGetter |=
                        reference != null
                                && reference.getDefiningClass().equals("Lkotlin/Pair;")
                                && Set.of("getFirst", "getSecond").contains(reference.getName());
            }
        check(!removedGetter, "Recording verification never calls removed host pair getters");

        int requests = 0,
                readers = 0,
                plans = 0,
                speeds = 0,
                unprotectedSpeeds = 0,
                preferences = 0,
                alignments = 0,
                quiet = 0,
                ranks = 0,
                ties = 0;
        Set<String> bridges = new HashSet<>();
        for (ClassDef type : types.values())
            for (Method method : type.getMethods()) {
                if (type.getType().equals("Lcom/music/bitchord/playback/PlaybackService;")
                        && method.getName().startsWith("bitchordAutomix"))
                    bridges.add(method.getName());
                for (Instruction instruction : code(method)) {
                    MethodReference ref = call(instruction);
                    if (ref == null) continue;
                    if (ref.getDefiningClass().equals(runtime)) {
                        if (ref.getName().equals("request")) {
                            requests++;
                            check(
                                    type.getType()
                                            .equals(
                                                    "Lcom/music/bitchord/playback/PlaybackService$$ExternalSyntheticLambda25;"),
                                    "Hook reaches the optimized analysis callback");
                        }
                        if (ref.getName().equals("opener")) {
                            readers++;
                            check(
                                    method.getName().equals("copyToRead"),
                                    "All host decode/waveform reads use selected-source reader");
                        }
                        if (ref.getName().equals("plan")) plans++;
                        if (ref.getName().equals("speed")) speeds++;
                        if (ref.getName().equals("quiet")) quiet++;
                    }
                    if (type.getType()
                                    .startsWith("Lcom/music/bitchord/playback/CrossfadeController")
                            && ref.getName().equals("setPlaybackSpeed")) unprotectedSpeeds++;
                    if (ref.getDefiningClass().equals(pref) && ref.getName().equals("resolve"))
                        preferences++;
                    if (ref.getDefiningClass().equals(pref)
                            && ref.getName().equals("alignedOffset")) alignments++;
                    if (ref.getDefiningClass().equals(pref) && ref.getName().equals("rank"))
                        ranks++;
                    if (ref.getDefiningClass().equals(pref) && ref.getName().equals("equalUpgrade"))
                        ties++;
                }
            }
        check(
                requests == 1 && readers == 1 && plans == 1,
                "Request, source reader and tempo-neutral plan are reachable exactly once");
        check(
                speeds >= 8 && unprotectedSpeeds == 0,
                "Every transition/ramp/settlement speed path preserves user speed");
        check(
                preferences == 1 && alignments == 1,
                "Verification and relative timeline mapping reach playback");
        check(
                ranks >= 1 && ties == 1,
                "Enabled alternatives outrank YouTube and equal-format upgrades are considered");
        check(quiet >= 1, "Background analysis cannot overwrite audible-source diagnostics");
        check(
                bridges.containsAll(
                        Set.of(
                                "bitchordAutomixSource",
                                "bitchordAutomixCached",
                                "bitchordAutomixResolve",
                                "bitchordAutomixResolveWith",
                                "bitchordAutomixKey",
                                "bitchordAutomixSpeed",
                                "bitchordAutomixEnabled",
                                "bitchordAutomixStopped",
                                "bitchordAutomixRestore")),
                "All host bridges exist");
        ClassDef restore =
                types.get(
                        "Lcom/music/bitchord/playback/smart/TrackAnalyzer$$ExternalSyntheticLambda18;");
        check(restore != null, "Exact-rendition stored-analysis restore implementation retained");
        ClassDef analyzer = types.get("Lcom/music/bitchord/playback/smart/TrackAnalyzer;");
        Set<String> fields = new HashSet<>();
        for (Field field : analyzer.getFields()) fields.add(field.getName());
        check(
                fields.containsAll(
                        Set.of(
                                "results",
                                "running",
                                "executor",
                                "store",
                                "sourceAnalysisUris",
                                "tracker",
                                "vocals",
                                "thermalStatus",
                                "thermalWatch$delegate")),
                "Runtime analysis state contract matches optimized APK");
        check(
                types.containsKey("Lcom/music/bitchord/playback/smart/AudioDecoder;"),
                "Host decoder retained");
        for (String name : List.of("BeatTracker", "VocalTracker")) {
            ClassDef model = types.get("Lcom/music/bitchord/playback/smart/" + name + ";");
            check(model != null, name + " retained");
            Method release = null;
            for (Method method : model.getMethods())
                if (method.getName().equals("release")) release = method;
            check(release != null, name + " release exists");
            List<Instruction> instructions = code(release);
            int monitor = -1, guard = -1, firstGuard = -1, close = -1, guards = 0;
            for (int i = 0; i < instructions.size(); i++) {
                if (instructions.get(i).getOpcode() == Opcode.MONITOR_ENTER && monitor < 0)
                    monitor = i;
                MethodReference ref = call(instructions.get(i));
                if (ref == null) continue;
                if (ref.getDefiningClass().equals(runtime)
                        && ref.getName().equals("deferModelRelease")) {
                    guard = i;
                    if (firstGuard < 0) firstGuard = i;
                    guards++;
                }
                if (ref.getDefiningClass().equals("Lai/onnxruntime/OrtSession;")
                        && ref.getName().equals("close")) close = i;
            }
            check(
                    guards == 2 && firstGuard < monitor && guard > monitor && close > guard,
                    name + " native closure is guarded under its own lock");
            check(
                    instructions.get(guard + 3).getOpcode() == Opcode.MONITOR_EXIT
                            && instructions.get(guard + 4).getOpcode() == Opcode.RETURN_VOID,
                    name + " deferred closure returns without holding the lock");
        }
        System.out.println(
                "PASS: "
                        + checks
                        + " source-independent AutoMix APK checks; "
                        + speeds
                        + " speed paths protected");
    }
}

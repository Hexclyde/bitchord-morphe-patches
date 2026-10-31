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
        System.out.println(
                "PASS: "
                        + checks
                        + " source-independent AutoMix APK checks; "
                        + speeds
                        + " speed paths protected");
    }
}

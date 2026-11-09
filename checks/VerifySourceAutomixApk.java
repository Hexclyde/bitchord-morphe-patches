import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;

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

    static boolean calls(
            Instruction instruction,
            String owner,
            String name,
            String result,
            String... parameters) {
        MethodReference ref = call(instruction);
        return ref != null
                && ref.getDefiningClass().equals(owner)
                && ref.getName().equals(name)
                && ref.getReturnType().equals(result)
                && ref.getParameterTypes().equals(List.of(parameters));
    }

    static int unique(
            List<Instruction> body,
            String owner,
            String name,
            String result,
            String... parameters) {
        int found = -1;
        for (int i = 0; i < body.size(); i++)
            if (calls(body.get(i), owner, name, result, parameters)) {
                check(found == -1, "Unique bounded analysis call: " + name);
                found = i;
            }
        check(found >= 0, "Required bounded analysis call: " + name);
        return found;
    }

    static final String AUDIO = "Lapp/bitchord/telegram/SourceAudio;",
            RUNTIME = "Lapp/bitchord/telegram/SourceAutomix;";

    static int leaseCall(List<Instruction> body) {
        return unique(
                body,
                AUDIO,
                "dspMemory",
                "Lapp/bitchord/telegram/SourceAudio$DspMemory;",
                "J",
                "J");
    }

    /**
     * Calls of one contract: exactly one outside the memory lease (the unleased preflight) and
     * exactly one that cannot be reached without it (the leased model path). Returns {outside,
     * leased}.
     */
    static int[] split(
            List<Instruction> body,
            int lease,
            String owner,
            String name,
            String result,
            String... parameters) {
        List<Integer> found = new ArrayList<>();
        for (int i = 0; i < body.size(); i++)
            if (calls(body.get(i), owner, name, result, parameters)) found.add(i);
        check(found.size() == 2, "One unleased and one leased call: " + name);
        int a = found.get(0), b = found.get(1);
        boolean aLeased = !reaches(body, 0, a, lease), bLeased = !reaches(body, 0, b, lease);
        check(aLeased != bLeased, "Exactly one " + name + " call requires the memory lease");
        return aLeased ? new int[] {b, a} : new int[] {a, b};
    }

    /** The same call with another method name; large methods use the range encoding. */
    static Instruction renamed(Instruction old, String name) {
        MethodReference ref = call(old);
        MethodReference moved =
                new ImmutableMethodReference(
                        ref.getDefiningClass(), name, ref.getParameterTypes(), ref.getReturnType());
        if (old instanceof RegisterRangeInstruction range)
            return new ImmutableInstruction3rc(
                    old.getOpcode(), range.getStartRegister(), range.getRegisterCount(), moved);
        FiveRegisterInstruction five = (FiveRegisterInstruction) old;
        return new ImmutableInstruction35c(
                old.getOpcode(),
                five.getRegisterCount(),
                five.getRegisterC(),
                five.getRegisterD(),
                five.getRegisterE(),
                five.getRegisterF(),
                five.getRegisterG(),
                moved);
    }

    static int[] limits(List<Instruction> body) {
        return split(
                body,
                leaseCall(body),
                AUDIO,
                "analysisLimit",
                "Ljava/lang/String;",
                "Landroid/media/MediaDataSource;",
                "D",
                "J");
    }

    /** Normal control flow must not bypass a preflight stage or accept a rejection. */
    static boolean reaches(List<Instruction> body, int start, int target, int blocked) {
        Map<Integer, Integer> positions = new HashMap<>();
        int[] address = new int[body.size()];
        int at = 0;
        for (int i = 0; i < body.size(); i++) {
            positions.put(at, i);
            address[i] = at;
            at += body.get(i).getCodeUnits();
        }
        Set<Integer> seen = new HashSet<>();
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        pending.add(start);
        while (!pending.isEmpty()) {
            int i = pending.removeFirst();
            if (i == blocked || i >= body.size() || !seen.add(i)) continue;
            if (i == target) return true;
            Instruction instruction = body.get(i);
            Opcode op = instruction.getOpcode();
            if (instruction instanceof OffsetInstruction offset) {
                check(
                        op.name().startsWith("IF_") || op.name().startsWith("GOTO"),
                        "Preflight branches have qualified direct targets");
                Integer jump = positions.get(address[i] + offset.getCodeOffset());
                check(jump != null, "Preflight branch target is executable");
                pending.add(jump);
            }
            if (op.canContinue()) pending.add(i + 1);
        }
        return false;
    }

    record ResourcePath(int index, Set<Integer> aliases) {}

    /** Symbolic object aliases distinguish a nullable resource guard from a skipped close. */
    static void resourcePaths(
            List<Instruction> body,
            Method method,
            int start,
            Set<Integer> initial,
            boolean factory) {
        Map<Integer, Integer> positions = new HashMap<>();
        int[] addresses = new int[body.size()];
        int address = 0;
        for (int i = 0; i < body.size(); i++) {
            positions.put(address, i);
            addresses[i] = address;
            address += body.get(i).getCodeUnits();
        }
        ArrayDeque<ResourcePath> pending = new ArrayDeque<>();
        Set<ResourcePath> visited = new HashSet<>();
        pending.add(new ResourcePath(start, Set.copyOf(initial)));
        int returns = 0, closes = 0;
        while (!pending.isEmpty()) {
            ResourcePath path = pending.removeFirst();
            if (!visited.add(path)) continue;
            int i = path.index();
            check(i < body.size(), "Resource control flow ends at an instruction");
            Instruction step = body.get(i);
            Opcode op = step.getOpcode();
            Set<Integer> known = path.aliases();
            if (!factory
                    && calls(step, "Lapp/bitchord/telegram/SourceAudio$DspMemory;", "close", "V")) {
                int receiver =
                        step instanceof FiveRegisterInstruction call
                                ? call.getRegisterC()
                                : ((RegisterRangeInstruction) step).getStartRegister();
                check(
                        known.contains(receiver),
                        "DSP closes the exact loading lease that was acquired");
                closes++;
                continue;
            }
            if (op.name().startsWith("RETURN")) {
                check(
                        factory
                                && op == Opcode.RETURN_OBJECT
                                && known.contains(((OneRegisterInstruction) step).getRegisterA()),
                        factory
                                ? "The memory factory returns a constructed non-null object"
                                : "DSP must close its lease before returning");
                returns++;
                continue;
            }
            Set<Integer> next = new HashSet<>(known);
            if (op.name().startsWith("MOVE") && step instanceof TwoRegisterInstruction move) {
                if (known.contains(move.getRegisterB())) next.add(move.getRegisterA());
                else next.remove(move.getRegisterA());
                if (op.setsWideRegister()) next.remove(move.getRegisterA() + 1);
            } else if (op == Opcode.NEW_INSTANCE && factory) {
                next.add(((OneRegisterInstruction) step).getRegisterA());
            } else if (op != Opcode.CHECK_CAST
                    && op.setsRegister()
                    && step instanceof OneRegisterInstruction write) {
                next.remove(write.getRegisterA());
                if (op.setsWideRegister()) next.remove(write.getRegisterA() + 1);
            }
            if (op.canThrow()) {
                boolean covered = false;
                for (var region : method.getImplementation().getTryBlocks())
                    if (addresses[i] >= region.getStartCodeAddress()
                            && addresses[i]
                                    < region.getStartCodeAddress() + region.getCodeUnitCount()) {
                        for (var handler : region.getExceptionHandlers()) {
                            Integer target = positions.get(handler.getHandlerCodeAddress());
                            check(
                                    target != null,
                                    "Resource exception handler has an executable target");
                            pending.add(new ResourcePath(target, known));
                            covered |=
                                    handler.getExceptionType() == null
                                            || handler.getExceptionType()
                                                    .equals("Ljava/lang/Throwable;");
                        }
                    }
                check(
                        factory || covered,
                        "An exception cannot escape an acquired DSP lease without cleanup");
            }
            boolean jump = true, fall = op.canContinue();
            if (step instanceof OffsetInstruction offset) {
                check(
                        op.name().startsWith("IF_") || op.name().startsWith("GOTO"),
                        "Resource branches are qualified direct targets");
                if (step instanceof OneRegisterInstruction test
                        && known.contains(test.getRegisterA())) {
                    if (op == Opcode.IF_EQZ) jump = false;
                    if (op == Opcode.IF_NEZ) fall = false;
                }
                Integer target = positions.get(addresses[i] + offset.getCodeOffset());
                check(target != null, "Resource branch has an executable target");
                if (jump) pending.add(new ResourcePath(target, Set.copyOf(next)));
            }
            if (fall) pending.add(new ResourcePath(i + 1, Set.copyOf(next)));
        }
        check(
                factory ? returns > 0 : closes >= 2,
                factory
                        ? "The memory factory has real normal results"
                        : "Normal and exceptional DSP paths close the acquired lease");
    }

    static int preflightAvailable(List<Instruction> body) {
        int limit = limits(body)[0];
        int found = -1;
        for (int i = 0; i < limit; i++)
            if (calls(
                    body.get(i),
                    "Lapp/bitchord/telegram/SourceAudio;",
                    "analysisAvailable",
                    "J",
                    "J",
                    "J")) found = i;
        check(found >= 0, "The selected source preflight reads available analysis memory");
        return found;
    }

    static void preflight(List<Instruction> body, Method analysisMethod) {
        String runtime = "Lapp/bitchord/telegram/SourceAutomix;";
        String audio = "Lapp/bitchord/telegram/SourceAudio;";
        String permit = "Lapp/bitchord/telegram/SourceAudio$Decoding;";
        int decode =
                unique(body, audio, "decode", permit, "J", "Ljava/util/function/BooleanSupplier;");
        int reader =
                unique(
                        body,
                        runtime,
                        "opener",
                        "Lkotlin/jvm/functions/Function0;",
                        "Landroid/net/Uri;");
        int available = preflightAvailable(body);
        int[] limitCalls = limits(body);
        int limit = limitCalls[0], leasedLimit = limitCalls[1];
        int cancelled = unique(body, permit, "check", "V");
        int structural =
                unique(
                        body,
                        "Lapp/bitchord/telegram/DspAnalysis;",
                        "analyze",
                        "Ljava/lang/Object;",
                        "Ljava/lang/String;",
                        "Ljava/io/File;",
                        "Ljava/util/function/Supplier;",
                        "I",
                        "D",
                        "Ljava/util/function/BooleanSupplier;",
                        "Ljava/util/function/LongSupplier;",
                        "Ljava/util/function/LongConsumer;",
                        "Ljava/lang/String;");
        int lease =
                unique(
                        body,
                        audio,
                        "dspMemory",
                        "Lapp/bitchord/telegram/SourceAudio$DspMemory;",
                        "J",
                        "J");
        int[] modelCalls =
                split(
                        body,
                        lease,
                        runtime,
                        "useModels",
                        "[Ljava/lang/Object;",
                        "Ljava/lang/Object;");
        int models = modelCalls[0], leasedModels = modelCalls[1];
        check(
                decode < reader
                        && reader < available
                        && available < limit
                        && limit < cancelled
                        && cancelled < models,
                "Decode admission, selected reader and memory check precede model use");
        for (int stage : new int[] {decode, reader, available, limit, cancelled})
            check(
                    !reaches(body, 0, models, stage),
                    "Model use cannot bypass preflight stage " + stage);
        check(
                body.get(available + 1).getOpcode() == Opcode.MOVE_RESULT_WIDE,
                "Playback buffer reservation keeps its wide result");
        int headroom = ((OneRegisterInstruction) body.get(available + 1)).getRegisterA();
        FiveRegisterInstruction memoryCall = (FiveRegisterInstruction) body.get(limit);
        check(
                memoryCall.getRegisterF() == headroom && memoryCall.getRegisterG() == headroom + 1,
                "Memory admission consumes the reserved headroom");
        for (int i = available + 2; i < limit; i++) {
            Instruction instruction = body.get(i);
            if (instruction.getOpcode().setsRegister()
                    && instruction instanceof OneRegisterInstruction written) {
                int destination = written.getRegisterA();
                check(
                        destination != headroom
                                && destination != headroom + 1
                                && (!instruction.getOpcode().setsWideRegister()
                                        || destination + 1 != headroom),
                        "Reserved headroom is not overwritten before admission");
            }
        }
        check(
                body.get(limit + 1).getOpcode() == Opcode.MOVE_RESULT_OBJECT,
                "Memory admission keeps its rejection result");
        int result = ((OneRegisterInstruction) body.get(limit + 1)).getRegisterA();
        int gate = -1;
        for (int i = limit + 2; i < models; i++) {
            Instruction instruction = body.get(i);
            if (instruction instanceof OneRegisterInstruction one && one.getRegisterA() == result) {
                check(
                        !instruction.getOpcode().setsRegister(),
                        "Memory rejection result is preserved");
                if (instruction.getOpcode() == Opcode.IF_EQZ) {
                    gate = i;
                    break;
                }
            }
        }
        check(
                gate >= 0 && !reaches(body, gate + 1, models, -1),
                "A non-null memory rejection cannot reach the host models");
        check(!reaches(body, 0, models, gate), "Model use always checks memory rejection");
        check(
                structural > limit
                        && structural < models
                        && reaches(body, gate + 1, structural, -1),
                "A memory-limited pass can reach bounded structural DSP without opening models");
        check(
                limit < lease && lease < structural && !reaches(body, 0, structural, lease),
                "Bounded structural DSP holds the installed player loading ceiling");
        // The leased model path may only run after admission, the lease, a passing limit check
        // against the leased allowance, and a granted claim.
        String held = "Lapp/bitchord/telegram/SourceAudio$DspMemory;";
        int claim = -1;
        for (int i = leasedLimit + 1; i < leasedModels; i++)
            if (calls(body.get(i), held, "claim", "Z", "J", "J", "J")) claim = i;
        check(claim >= 0, "The leased model path claims its memory");
        check(
                decode < lease
                        && lease < leasedLimit
                        && leasedLimit < claim
                        && claim < leasedModels,
                "Leased model use follows admission, lease, limit check and claim");
        for (int stage : new int[] {decode, lease, leasedLimit, claim})
            check(
                    !reaches(body, 0, leasedModels, stage),
                    "Leased model use cannot bypass stage " + stage);
        check(
                body.get(leasedLimit + 1).getOpcode() == Opcode.MOVE_RESULT_OBJECT
                        && body.get(claim + 1).getOpcode() == Opcode.MOVE_RESULT,
                "Leased limit and claim keep their results");
        int rejection = ((OneRegisterInstruction) body.get(leasedLimit + 1)).getRegisterA();
        boolean limitTested = false, claimTested = false;
        int granted = ((OneRegisterInstruction) body.get(claim + 1)).getRegisterA();
        for (int i = leasedLimit + 2; i < leasedModels; i++)
            if (body.get(i) instanceof OneRegisterInstruction test
                    && (test.getOpcode() == Opcode.IF_EQZ || test.getOpcode() == Opcode.IF_NEZ)) {
                if (i < claim && test.getRegisterA() == rejection) limitTested = true;
                if (i > claim && test.getRegisterA() == granted) claimTested = true;
            }
        check(limitTested && claimTested, "Leased model use tests its limit and its claim");
        int release = -1, published = -1, closes = 0;
        for (int i = 0; i < body.size(); i++) {
            if (calls(body.get(i), "Lapp/bitchord/telegram/SourceAudio$DspMemory;", "close", "V")) {
                closes++;
                if (i > structural && release < 0) release = i;
            }
            if (body.get(i).getOpcode() == Opcode.IPUT_BOOLEAN
                    && body.get(i) instanceof ReferenceInstruction ref
                    && ref.getReference() instanceof FieldReference field
                    && field.getDefiningClass().equals("Lapp/bitchord/telegram/SourceAutomix$Job;")
                    && field.getName().equals("done")) published = i;
        }
        check(
                closes >= 2 && release > structural && published > release,
                "DSP releases its loading lease on completion and exceptional cleanup before"
                        + " publication");
        check(
                body.get(lease + 1).getOpcode() == Opcode.MOVE_RESULT_OBJECT,
                "The acquired DSP lease is retained");
        int memoryRegister = ((OneRegisterInstruction) body.get(lease + 1)).getRegisterA();
        resourcePaths(body, analysisMethod, lease + 2, Set.of(memoryRegister), false);
        for (int stage : new int[] {decode, reader, available, limit})
            check(
                    !reaches(body, 0, structural, stage),
                    "Bounded structural DSP cannot bypass admission stage " + stage);
    }

    static void preflightRegressions(List<Instruction> body, Method analysisMethod) {
        String audio = "Lapp/bitchord/telegram/SourceAudio;";
        for (String name :
                List.of("decode", "opener", "analysisAvailable", "analysisLimit", "check")) {
            List<Instruction> mutant = new ArrayList<>(body);
            int index = -1;
            for (int i = 0; i < mutant.size(); i++) {
                MethodReference ref = call(mutant.get(i));
                if (ref != null
                        && ref.getName().equals(name)
                        && (ref.getDefiningClass().equals(audio)
                                || ref.getDefiningClass()
                                        .equals("Lapp/bitchord/telegram/SourceAutomix;")
                                || ref.getDefiningClass()
                                        .equals("Lapp/bitchord/telegram/SourceAudio$Decoding;"))) {
                    index = i;
                    break;
                }
            }
            if (name.equals("analysisAvailable")) index = preflightAvailable(body);
            check(index >= 0, "Regression fixture contains " + name);
            mutant.set(index, renamed(mutant.get(index), name + "Bypassed"));
            rejects(mutant, "missing " + name, analysisMethod);
        }
        List<Instruction> mutant = new ArrayList<>(body);
        int limit = limits(body)[0];
        int result = ((OneRegisterInstruction) body.get(limit + 1)).getRegisterA();
        for (int i = limit + 2; i < body.size(); i++)
            if (body.get(i).getOpcode() == Opcode.IF_EQZ
                    && ((OneRegisterInstruction) body.get(i)).getRegisterA() == result) {
                mutant.set(
                        i,
                        new ImmutableInstruction21t(
                                Opcode.IF_NEZ,
                                result,
                                ((OffsetInstruction) body.get(i)).getCodeOffset()));
                break;
            }
        rejects(mutant, "inverted memory rejection", analysisMethod);
        int models =
                split(
                        body,
                        leaseCall(body),
                        "Lapp/bitchord/telegram/SourceAutomix;",
                        "useModels",
                        "[Ljava/lang/Object;",
                        "Ljava/lang/Object;")[0];
        int[] addresses = new int[body.size()];
        for (int i = 1; i < body.size(); i++)
            addresses[i] = addresses[i - 1] + body.get(i - 1).getCodeUnits();
        mutant = new ArrayList<>(body);
        boolean redirected = false;
        for (int i = 0; i < limit; i++)
            if (body.get(i).getOpcode() == Opcode.IF_NEZ) {
                mutant.set(
                        i,
                        new ImmutableInstruction21t(
                                Opcode.IF_NEZ,
                                ((OneRegisterInstruction) body.get(i)).getRegisterA(),
                                addresses[models] - addresses[i]));
                redirected = true;
                break;
            }
        check(redirected, "Regression fixture contains a preflight entry branch");
        rejects(mutant, "branch bypasses preflight", analysisMethod);
        int structural =
                unique(
                        body,
                        "Lapp/bitchord/telegram/DspAnalysis;",
                        "analyze",
                        "Ljava/lang/Object;",
                        "Ljava/lang/String;",
                        "Ljava/io/File;",
                        "Ljava/util/function/Supplier;",
                        "I",
                        "D",
                        "Ljava/util/function/BooleanSupplier;",
                        "Ljava/util/function/LongSupplier;",
                        "Ljava/util/function/LongConsumer;",
                        "Ljava/lang/String;");
        List<Instruction> missingDsp = new ArrayList<>(body);
        RegisterRangeInstruction boundedCall =
                (RegisterRangeInstruction) missingDsp.get(structural);
        MethodReference boundedRef = call(missingDsp.get(structural));
        missingDsp.set(
                structural,
                new com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc(
                        missingDsp.get(structural).getOpcode(),
                        boundedCall.getStartRegister(),
                        boundedCall.getRegisterCount(),
                        new ImmutableMethodReference(
                                boundedRef.getDefiningClass(),
                                "analyzeBypassed",
                                boundedRef.getParameterTypes(),
                                boundedRef.getReturnType())));
        rejects(missingDsp, "bounded structural fallback removed", analysisMethod);
        List<Instruction> skippedAdmission = new ArrayList<>(body);
        boolean bypassed = false;
        int admission =
                unique(
                        body,
                        audio,
                        "decode",
                        "Lapp/bitchord/telegram/SourceAudio$Decoding;",
                        "J",
                        "Ljava/util/function/BooleanSupplier;");
        for (int i = 0; i < admission; i++)
            if (body.get(i).getOpcode() == Opcode.IF_NEZ) {
                skippedAdmission.set(
                        i,
                        new ImmutableInstruction21t(
                                Opcode.IF_NEZ,
                                ((OneRegisterInstruction) body.get(i)).getRegisterA(),
                                addresses[structural] - addresses[i]));
                bypassed = true;
                break;
            }
        check(bypassed, "Regression fixture contains an admission entry branch");
        rejects(skippedAdmission, "bounded DSP bypasses shared decode admission", analysisMethod);
        int lease =
                unique(
                        body,
                        audio,
                        "dspMemory",
                        "Lapp/bitchord/telegram/SourceAudio$DspMemory;",
                        "J",
                        "J");
        List<Instruction> skippedLease = new ArrayList<>(body);
        skippedLease.set(lease, renamed(body.get(lease), "dspMemoryBypassed"));
        rejects(skippedLease, "bounded DSP loading lease removed", analysisMethod);
        List<Instruction> leakedLease = new ArrayList<>(body);
        boolean removedClose = false;
        for (int i = structural + 1; i < body.size(); i++)
            if (calls(body.get(i), "Lapp/bitchord/telegram/SourceAudio$DspMemory;", "close", "V")) {
                FiveRegisterInstruction old = (FiveRegisterInstruction) body.get(i);
                leakedLease.set(
                        i,
                        new ImmutableInstruction35c(
                                Opcode.INVOKE_STATIC,
                                1,
                                old.getRegisterC(),
                                0,
                                0,
                                0,
                                0,
                                new ImmutableMethodReference(
                                        "Ljava/util/Objects;",
                                        "requireNonNull",
                                        List.of("Ljava/lang/Object;"),
                                        "Ljava/lang/Object;")));
                removedClose = true;
                break;
            }
        check(removedClose, "Regression fixture contains normal DSP lease cleanup");
        rejects(leakedLease, "bounded DSP loading lease leaked on publication", analysisMethod);
        List<Instruction> unclaimed = new ArrayList<>(body);
        boolean renamed = false;
        for (int i = 0; i < body.size(); i++)
            if (calls(
                    body.get(i),
                    "Lapp/bitchord/telegram/SourceAudio$DspMemory;",
                    "claim",
                    "Z",
                    "J",
                    "J",
                    "J")) {
                unclaimed.set(i, renamed(body.get(i), "claimBypassed"));
                renamed = true;
            }
        check(renamed, "Regression fixture contains the memory claims");
        rejects(unclaimed, "leased model path without its memory claim", analysisMethod);
        System.out.println("PASS: 12 bounded analysis preflight negative variants rejected");
    }

    static void rejects(List<Instruction> body, String name, Method analysisMethod) {
        try {
            preflight(body, analysisMethod);
        } catch (AssertionError expected) {
            return;
        }
        throw new AssertionError("Preflight regression accepted: " + name);
    }

    static final String HOST = "Lcom/music/bitchord/playback/PlaybackService;",
            DECODER = "Lcom/music/bitchord/playback/smart/AudioDecoder;",
            FEATURES = "Lcom/music/bitchord/playback/smart/TrackFeatures$Features;",
            ANALYSIS = "Lcom/music/bitchord/playback/smart/TrackAnalysis;";

    static Method named(ClassDef owner, String name) {
        List<Method> found = new ArrayList<>();
        for (Method method : owner.getMethods())
            if (method.getName().equals(name)) found.add(method);
        check(found.size() == 1, "One qualified native bridge: " + name);
        return found.get(0);
    }

    /**
     * The one method with this name and exact parameter types; overloads are separate contracts.
     */
    static Method named(ClassDef owner, String name, String... parameters) {
        List<Method> found = new ArrayList<>();
        for (Method method : owner.getMethods())
            if (method.getName().equals(name)
                    && method.getParameterTypes().stream()
                            .map(Object::toString)
                            .collect(java.util.stream.Collectors.toList())
                            .equals(List.of(parameters))) found.add(method);
        check(found.size() == 1, "One qualified method: " + name + List.of(parameters));
        return found.get(0);
    }

    static void decoderBridge(List<Instruction> body, int registers) {
        check(body.size() == 8, "The bounded decoder bridge is a direct streaming call");
        int[] destinations = {0, 3, 5, 6},
                inputs = {registers - 5, registers - 4, registers - 2, registers - 1},
                sites = {0, 2, 3, 4};
        for (int n = 0; n < sites.length; n++) {
            Instruction step = body.get(sites[n]);
            check(
                    step instanceof TwoRegisterInstruction move
                            && move.getRegisterA() == destinations[n]
                            && move.getRegisterB() == inputs[n],
                    "The decoder retains source, end, cancellation and callback argument " + n);
        }
        check(
                body.get(1).getOpcode() == Opcode.CONST_WIDE_16
                        && ((OneRegisterInstruction) body.get(1)).getRegisterA() == 1
                        && ((NarrowLiteralInstruction) body.get(1)).getNarrowLiteral() == 0,
                "The whole-file streaming decode begins at zero");
        check(
                calls(
                                body.get(5),
                                DECODER,
                                "decodeRaw",
                                "Lkotlin/Pair;",
                                "Landroid/media/MediaDataSource;",
                                "D",
                                "D",
                                "Lkotlin/jvm/functions/Function0;",
                                "Lkotlin/jvm/functions/Function4;")
                        && body.get(5) instanceof RegisterRangeInstruction range
                        && range.getStartRegister() == 0
                        && range.getRegisterCount() == 7,
                "The host codec receives the exact bounded streaming arguments");
        check(
                body.get(6).getOpcode() == Opcode.MOVE_RESULT_OBJECT
                        && ((OneRegisterInstruction) body.get(6)).getRegisterA() == 0
                        && body.get(7).getOpcode() == Opcode.RETURN_OBJECT
                        && ((OneRegisterInstruction) body.get(7)).getRegisterA() == 0,
                "The streaming result, including null, is returned unchanged");
    }

    static void directBridge(
            Method bridge, String owner, String name, String result, String... args) {
        List<Instruction> body = code(bridge);
        int words = 0;
        for (String type : args) words += type.equals("D") || type.equals("J") ? 2 : 1;
        check(
                body.size() == 3
                        && calls(body.get(0), owner, name, result, args)
                        && body.get(0) instanceof RegisterRangeInstruction range
                        && range.getRegisterCount() == words
                        && range.getStartRegister()
                                == bridge.getImplementation().getRegisterCount() - words,
                "Direct native port retains all arguments: " + name);
        check(
                body.get(1).getOpcode() == Opcode.MOVE_RESULT_OBJECT
                        && body.get(2).getOpcode() == Opcode.RETURN_OBJECT
                        && ((OneRegisterInstruction) body.get(1)).getRegisterA()
                                == ((OneRegisterInstruction) body.get(2)).getRegisterA(),
                "Direct native port returns its real result: " + name);
    }

    static final List<String> RESULT_FIELDS =
            List.of(
                    "status",
                    "trackId",
                    "duration",
                    "bpm",
                    "beatInterval",
                    "beatConfidence",
                    "downbeats",
                    "phraseBoundaries",
                    "firstBeat",
                    "key",
                    "keyConfidence",
                    "audibleStartTime",
                    "pickupTime",
                    "introEndTime",
                    "contentEndTime",
                    "outroStartTime",
                    "mixInTime",
                    "mixOutTime",
                    "mixInCandidates",
                    "mixOutCandidates",
                    "energyCurve",
                    "lowEnergyCurve",
                    "vocalActivityMask",
                    "vocalProbability",
                    "headBeats",
                    "tailBeats",
                    "vocalWindows",
                    "levelWindows");

    static void resultBridge(List<Instruction> body, int registers) {
        Map<Integer, String> values = new HashMap<>();
        values.put(registers - 4, "input:features");
        values.put(registers - 3, "input:id");
        values.put(registers - 2, "input:duration");
        values.put(registers - 1, "input:duration");
        String returned = null;
        boolean constructed = false, returnedResult = false;
        for (Instruction step : body) {
            Opcode op = step.getOpcode();
            if (op.name().startsWith("MOVE_RESULT")) {
                int destination = ((OneRegisterInstruction) step).getRegisterA();
                values.put(destination, returned);
                if (op.setsWideRegister()) values.put(destination + 1, returned);
            } else if (op.name().startsWith("MOVE")
                    && step instanceof TwoRegisterInstruction move) {
                values.put(move.getRegisterA(), values.get(move.getRegisterB()));
                if (op.setsWideRegister())
                    values.put(move.getRegisterA() + 1, values.get(move.getRegisterB() + 1));
            } else if (op == Opcode.IGET_WIDE || op == Opcode.IGET_OBJECT) {
                TwoRegisterInstruction fieldRead = (TwoRegisterInstruction) step;
                FieldReference field =
                        (FieldReference) ((ReferenceInstruction) step).getReference();
                check(
                        field.getDefiningClass().equals(FEATURES)
                                && "input:features".equals(values.get(fieldRead.getRegisterB())),
                        "DSP result fields come only from the measured feature object");
                values.put(fieldRead.getRegisterA(), "feature:" + field.getName());
                if (op.setsWideRegister())
                    values.put(fieldRead.getRegisterA() + 1, "feature:" + field.getName());
            } else if (op == Opcode.CONST_STRING || op == Opcode.CONST_STRING_JUMBO) {
                values.put(
                        ((OneRegisterInstruction) step).getRegisterA(),
                        "literal:"
                                + ((StringReference) ((ReferenceInstruction) step).getReference())
                                        .getString());
            } else if (op == Opcode.CONST_4) {
                values.put(
                        ((OneRegisterInstruction) step).getRegisterA(),
                        ((NarrowLiteralInstruction) step).getNarrowLiteral() == 0
                                ? "null"
                                : "constant");
            } else if (op == Opcode.NEW_INSTANCE) {
                check(
                        ((ReferenceInstruction) step).getReference().toString().equals(ANALYSIS),
                        "DSP result constructs the native analysis type");
                values.put(((OneRegisterInstruction) step).getRegisterA(), "new:analysis");
            } else if (op == Opcode.CHECK_CAST) {
                // Casts refine an existing value; they do not replace its evidence.
            } else if (step instanceof ReferenceInstruction && call(step) != null) {
                MethodReference ref = call(step);
                if (ref.getDefiningClass().equals("Ljava/lang/Double;")
                        && ref.getName().equals("valueOf")) {
                    returned = values.get(((FiveRegisterInstruction) step).getRegisterC());
                } else if (ref.getDefiningClass().equals("Ljava/util/Collections;")
                        && ref.getName().equals("emptyList")) {
                    returned = "empty";
                } else {
                    check(
                            ref.getDefiningClass().equals(ANALYSIS)
                                    && ref.getName().equals("<init>")
                                    && step instanceof RegisterRangeInstruction,
                            "DSP results use one checked native constructor");
                    RegisterRangeInstruction range = (RegisterRangeInstruction) step;
                    check(
                            "new:analysis".equals(values.get(range.getStartRegister()))
                                    && ref.getParameterTypes().size() == RESULT_FIELDS.size(),
                            "The native analysis constructor retains its complete schema");
                    int register = range.getStartRegister() + 1;
                    for (int i = 0; i < RESULT_FIELDS.size(); i++) {
                        String field = RESULT_FIELDS.get(i);
                        String expected =
                                switch (field) {
                                    case "status" -> "literal:ready";
                                    case "trackId" -> "input:id";
                                    case "duration" -> "input:duration";
                                    case "headBeats", "tailBeats" -> "null";
                                    case "vocalActivityMask", "vocalWindows", "levelWindows" ->
                                            "empty";
                                    default -> "feature:" + field;
                                };
                        check(
                                expected.equals(values.get(register)),
                                "DSP result preserves genuine evidence for " + field);
                        String type = ref.getParameterTypes().get(i).toString();
                        if (type.equals("D")) {
                            check(
                                    expected.equals(values.get(register + 1)),
                                    "DSP result preserves wide field " + field);
                            register += 2;
                        } else register++;
                    }
                    check(
                            register - range.getStartRegister() == range.getRegisterCount(),
                            "DSP result constructor consumes exactly its qualified values");
                    constructed = true;
                }
            } else if (op == Opcode.RETURN_OBJECT) {
                check(
                        constructed
                                && "new:analysis"
                                        .equals(
                                                values.get(
                                                        ((OneRegisterInstruction) step)
                                                                .getRegisterA())),
                        "DSP returns only the constructed native result");
                returnedResult = true;
            } else check(false, "Unexpected native DSP result operation: " + op);
        }
        check(returnedResult, "The native DSP result is returned");
    }

    static void labelHook(List<Instruction> body) {
        int label =
                unique(
                        body,
                        "Lapp/bitchord/telegram/SourceAutomix;",
                        "mixLabel",
                        "Ljava/lang/String;",
                        "Ljava/lang/String;");
        check(
                label >= 2
                        && body.get(label - 2) instanceof ReferenceInstruction call
                        && call.getReference() instanceof MethodReference reference
                        && reference.getReturnType().equals("Ljava/lang/String;")
                        && reference.getParameterTypes().stream()
                                .map(CharSequence::toString)
                                .toList()
                                .equals(
                                        List.of(
                                                "Lorg/jetbrains/compose/resources/StringResource;",
                                                "Landroidx/compose/runtime/ComposerImpl;"))
                        && body.get(label - 1).getOpcode() == Opcode.MOVE_RESULT_OBJECT,
                "The blend label wraps the existing localized native string");
        int original = ((OneRegisterInstruction) body.get(label - 1)).getRegisterA();
        check(
                body.get(label) instanceof RegisterRangeInstruction range
                        && range.getRegisterCount() == 1
                        && range.getStartRegister() == original
                        && body.get(label + 1).getOpcode() == Opcode.MOVE_RESULT_OBJECT
                        && ((OneRegisterInstruction) body.get(label + 1)).getRegisterA()
                                == original,
                "The blend label keeps its native text register and layout");
    }

    static void dspContracts(Map<String, ClassDef> types, boolean regressions) {
        ClassDef audio = types.get("Lapp/bitchord/telegram/SourceAudio;");
        // The production factory takes and pins the loading lease; the scoped one only wraps a
        // caller-owned lease, so the resource-path check belongs to the production signature.
        Method factory = named(audio, "dspMemory", "J", "J");
        resourcePaths(code(factory), factory, 0, Set.of(), true);
        named(
                audio,
                "dspMemory",
                "J",
                "J",
                "Ljava/lang/AutoCloseable;",
                "Ljava/util/function/LongSupplier;",
                "Ljava/util/function/BooleanSupplier;");
        ClassDef host = types.get(HOST);
        Method decode = named(host, "bitchordDspDecode"),
                mono = named(host, "bitchordDspMono"),
                features = named(host, "bitchordDspFeatures"),
                result = named(host, "bitchordDspResult");
        decoderBridge(code(decode), decode.getImplementation().getRegisterCount());
        directBridge(
                mono,
                DECODER,
                "toMono",
                "[F",
                "Ljava/nio/ByteBuffer;",
                "Landroid/media/MediaCodec$BufferInfo;",
                "I");
        directBridge(
                features,
                "Lcom/music/bitchord/playback/smart/TrackFeatures;",
                "analyze",
                FEATURES,
                "[F",
                "D");
        resultBridge(code(result), result.getImplementation().getRegisterCount());
        Method label =
                named(types.get("Lcom/music/bitchord/ui/player/PlayerControlsKt;"), "MixingLabel");
        labelHook(code(label));
        Method real = named(host, "bitchordAutomixRealMix");
        boolean reads = false, writes = false;
        for (Instruction step : code(real)) {
            if (step instanceof ReferenceInstruction ref
                    && ref.getReference() instanceof FieldReference field)
                reads |=
                        field.getDefiningClass()
                                        .equals("Lcom/music/bitchord/data/settings/AppSettings;")
                                && field.getName().equals("smartMixInProgress");
            MethodReference ref = call(step);
            writes |=
                    ref != null
                            && (ref.getName().equals("setValue")
                                    || ref.getName().equals("updateState"));
        }
        check(reads && !writes, "Blend classification reads native state without changing it");
        ClassDef dsp = types.get("Lapp/bitchord/telegram/DspAnalysis;");
        check(dsp != null, "Bounded structural DSP runtime is installed");
        Set<String> ports = new HashSet<>();
        for (Method method : dsp.getMethods())
            for (Instruction step : code(method)) {
                MethodReference ref = call(step);
                if (ref != null
                        && ref.getDefiningClass().equals(HOST)
                        && ref.getName().startsWith("bitchordDsp")) ports.add(ref.getName());
            }
        check(
                ports.containsAll(
                        Set.of(
                                "bitchordDspDecode",
                                "bitchordDspMono",
                                "bitchordDspFeatures",
                                "bitchordDspResult")),
                "Bounded DSP uses all qualified host conversion and analysis ports");
        if (regressions) {
            List<Instruction> changed = new ArrayList<>(code(decode));
            changed.set(
                    3,
                    new com.android.tools.smali.dexlib2.immutable.instruction
                            .ImmutableInstruction22x(
                            Opcode.MOVE_OBJECT_FROM16,
                            5,
                            decode.getImplementation().getRegisterCount() - 1));
            expectRejected(
                    () -> decoderBridge(changed, decode.getImplementation().getRegisterCount()),
                    "decoder cancellation misbound");
            List<Instruction> nonzero = new ArrayList<>(code(decode));
            nonzero.set(
                    1,
                    new com.android.tools.smali.dexlib2.immutable.instruction
                            .ImmutableInstruction21s(Opcode.CONST_WIDE_16, 1, 1));
            expectRejected(
                    () -> decoderBridge(nonzero, decode.getImplementation().getRegisterCount()),
                    "decoder starts past the beginning");
            List<Instruction> swapped = new ArrayList<>(code(result));
            for (int i = 0; i < swapped.size(); i++)
                if (swapped.get(i) instanceof ReferenceInstruction ref
                        && ref.getReference() instanceof FieldReference field
                        && field.getName().equals("beatConfidence")) {
                    TwoRegisterInstruction old = (TwoRegisterInstruction) swapped.get(i);
                    swapped.set(
                            i,
                            new com.android.tools.smali.dexlib2.immutable.instruction
                                    .ImmutableInstruction22c(
                                    Opcode.IGET_WIDE,
                                    old.getRegisterA(),
                                    old.getRegisterB(),
                                    new com.android.tools.smali.dexlib2.immutable.reference
                                            .ImmutableFieldReference(FEATURES, "bpm", "D")));
                    break;
                }
            expectRejected(
                    () -> resultBridge(swapped, result.getImplementation().getRegisterCount()),
                    "measured confidence replaced by tempo");
            List<Instruction> invented = new ArrayList<>(code(result));
            for (int i = 0; i < invented.size(); i++)
                if (invented.get(i).getOpcode() == Opcode.CONST_4) {
                    invented.set(
                            i,
                            new com.android.tools.smali.dexlib2.immutable.instruction
                                    .ImmutableInstruction11n(Opcode.CONST_4, 1, 1));
                    break;
                }
            expectRejected(
                    () -> resultBridge(invented, result.getImplementation().getRegisterCount()),
                    "model evidence invented");
            List<Instruction> bypass = new ArrayList<>(code(label));
            for (int i = 0; i < bypass.size(); i++)
                if (calls(
                        bypass.get(i),
                        "Lapp/bitchord/telegram/SourceAutomix;",
                        "mixLabel",
                        "Ljava/lang/String;",
                        "Ljava/lang/String;")) {
                    RegisterRangeInstruction old = (RegisterRangeInstruction) bypass.get(i);
                    bypass.set(
                            i,
                            new com.android.tools.smali.dexlib2.immutable.instruction
                                    .ImmutableInstruction3rc(
                                    Opcode.INVOKE_STATIC_RANGE,
                                    old.getStartRegister(),
                                    old.getRegisterCount(),
                                    new ImmutableMethodReference(
                                            "Ljava/lang/String;",
                                            "valueOf",
                                            List.of("Ljava/lang/Object;"),
                                            "Ljava/lang/String;")));
                    break;
                }
            expectRejected(() -> labelHook(bypass), "fallback label classification bypassed");
            System.out.println("PASS: 5 bounded DSP and blend-label negative variants rejected");
        }
    }

    static void expectRejected(Runnable contract, String name) {
        try {
            contract.run();
        } catch (AssertionError expected) {
            return;
        }
        throw new AssertionError("Native DSP regression accepted: " + name);
    }

    static void lookaheadContracts(Map<String, ClassDef> types) {
        String player = "Landroidx/media3/common/Player;";
        String timeline = "Landroidx/media3/common/Timeline;";
        for (String[] contract :
                new String[][] {
                    {player, "getCurrentTimeline", timeline},
                    {player, "getRepeatMode", "I"},
                    {player, "getShuffleModeEnabled", "Z"},
                    {player, "getNextMediaItemIndex", "I"},
                    {timeline, "getWindowCount", "I"},
                    {timeline, "getNextWindowIndex", "I", "I", "I", "Z"}
                }) {
            Method api = named(types.get(contract[0]), contract[1]);
            check(
                    api.getReturnType().equals(contract[2])
                            && api.getParameterTypes()
                                    .equals(
                                            List.of(
                                                    Arrays.copyOfRange(
                                                            contract, 3, contract.length)))
                            && (api.getAccessFlags() & 9) == 1,
                    "Lookahead uses the native timeline ABI: " + contract[1]);
        }
        String runtime = "Lapp/bitchord/telegram/SourceAutomix;";
        for (String name : List.of("queueChanged", "cancelLookahead")) {
            Method method = named(types.get(runtime), name);
            check(
                    (method.getAccessFlags() & 9) == 9
                            && method.getReturnType().equals("V")
                            && method.getParameterTypes()
                                    .equals(
                                            Collections.nCopies(
                                                    name.equals("queueChanged") ? 2 : 1,
                                                    "Ljava/lang/Object;")),
                    "Lookahead bridge ABI: " + name);
        }
        Method editor = named(types.get(runtime), "editor");
        check(
                (editor.getAccessFlags() & 9) == 9
                        && editor.getReturnType().equals("V")
                        && editor.getParameterTypes().equals(List.of("Ljava/lang/Object;", "Z")),
                "Visible editor has an identity-scoped static priority lease");
        List<Method> warm = new ArrayList<>();
        for (Method method : types.get(HOST).getMethods()) {
            List<Instruction> body = code(method);
            if (method.getParameterTypes().equals(List.of("Landroidx/media3/exoplayer/ExoPlayer;"))
                    && method.getReturnType().equals("V")
                    && body.stream().anyMatch(i -> calls(i, player, "getNextMediaItemIndex", "I"))
                    && body.stream()
                            .anyMatch(
                                    i ->
                                            calls(
                                                    i,
                                                    "Lcom/music/bitchord/playback/AudioCache;",
                                                    "noteSongs",
                                                    "V",
                                                    "Ljava/util/List;"))) warm.add(method);
        }
        check(warm.size() == 1, "One native queue read-ahead method drives lookahead");
        Method around = warm.getFirst();
        Set<String> reads = new HashSet<>();
        List<FieldReference> fields = new ArrayList<>();
        for (Instruction instruction : code(around)) {
            if (!(instruction instanceof ReferenceInstruction reference)
                    || !(reference.getReference() instanceof FieldReference field)) continue;
            if (instruction.getOpcode() == Opcode.IGET_OBJECT
                    && field.getDefiningClass().equals(HOST)
                    && field.getType().equals("Ljava/util/ArrayList;")) reads.add(field.toString());
        }
        for (Instruction instruction : code(around))
            if (instruction.getOpcode() == Opcode.IPUT_OBJECT
                    && instruction instanceof ReferenceInstruction reference
                    && reference.getReference() instanceof FieldReference field
                    && reads.contains(field.toString())
                    && fields.stream().noneMatch(f -> f.toString().equals(field.toString())))
                fields.add(field);
        check(fields.size() == 1, "One semantic prefetch state field");
        String state = fields.getFirst().toString();
        int stores = 0;
        for (ClassDef type : types.values())
            for (Method method : type.getMethods()) {
                List<Instruction> body = code(method);
                for (int i = 0; i < body.size(); i++) {
                    Instruction instruction = body.get(i);
                    if (instruction instanceof ReferenceInstruction reference
                            && reference.getReference() instanceof FieldReference field
                            && field.toString().equals(state)
                            && instruction.getOpcode().name().startsWith("IPUT")) {
                        check(
                                instruction.getOpcode() == Opcode.IPUT_OBJECT
                                        && i + 1 < body.size(),
                                "Prefetch state is an instance object store");
                        Instruction cancel = body.get(i + 1);
                        check(
                                calls(cancel, runtime, "cancelLookahead", "V", "Ljava/lang/Object;")
                                        && cancel instanceof RegisterRangeInstruction range
                                        && range.getRegisterCount() == 1
                                        && range.getStartRegister()
                                                == ((TwoRegisterInstruction) instruction)
                                                        .getRegisterB(),
                                "Every native prefetch write cancels only the same owner's"
                                        + " speculation");
                        stores++;
                    }
                }
            }
        check(stores > 0, "Native pause/stop/queue prefetch state is observed");
        List<Instruction> body = code(around);
        for (int i = 0; i < body.size(); i++)
            if (body.get(i).getOpcode() == Opcode.RETURN_VOID) {
                check(
                        i > 0
                                && calls(
                                        body.get(i - 1),
                                        runtime,
                                        "queueChanged",
                                        "V",
                                        "Ljava/lang/Object;",
                                        "Ljava/lang/Object;")
                                && body.get(i - 1) instanceof RegisterRangeInstruction range
                                && range.getRegisterCount() == 2
                                && range.getStartRegister()
                                        == around.getImplementation().getRegisterCount() - 2,
                        "Every native read-ahead return observes the exact service/player pair");
            }
    }

    public static void main(String[] args) throws Exception {
        Map<String, ClassDef> types = new HashMap<>();
        var dex = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.getDefault());
        for (String name : dex.getDexEntryNames())
            for (ClassDef type : dex.getEntry(name).getDexFile().getClasses())
                types.put(type.getType(), type);
        dspContracts(types, args.length > 1 && args[1].equals("--regressions"));
        lookaheadContracts(types);
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
                editorReaders = 0,
                preflights = 0,
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
                            if (type.getType().equals(runtime)) {
                                preflights++;
                                check(
                                        method.getName().equals("analyze")
                                                && method.getParameterTypes()
                                                        .equals(
                                                                List.of(
                                                                        "Lapp/bitchord/telegram/SourceAutomix$Job;",
                                                                        "Ljava/util/Map;",
                                                                        "Ljava/util/Set;")),
                                        "Only the qualified analysis preflight reads its selected"
                                                + " source");
                                preflight(code(method), method);
                                if (args.length > 1 && args[1].equals("--regressions"))
                                    preflightRegressions(code(method), method);
                            } else if (type.getType()
                                    .equals("Lapp/bitchord/telegram/TransitionWaveform;")) {
                                editorReaders++;
                                check(
                                        method.getName().equals("read")
                                                && method.getParameterTypes()
                                                        .equals(
                                                                List.of(
                                                                        "Landroid/net/Uri;",
                                                                        "D",
                                                                        "D",
                                                                        "I")),
                                        "Only the bounded editor waveform reads its frozen selected"
                                                + " URI");
                            } else {
                                readers++;
                                check(
                                        type.getType()
                                                        .equals(
                                                                "Lcom/music/bitchord/playback/smart/TrackAnalyzer;")
                                                && method.getName().equals("copyToRead")
                                                && method.getParameterTypes()
                                                        .equals(List.of("Landroid/net/Uri;", "D")),
                                        "All host decode/waveform reads use selected-source"
                                                + " reader");
                            }
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
                requests == 1
                        && readers == 1
                        && editorReaders == 1
                        && preflights == 1
                        && plans == 1,
                "Request, selected-source readers and native plan bridge are reachable exactly"
                        + " once");
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
        String analyzerType = "Lcom/music/bitchord/playback/smart/TrackAnalyzer;";
        String hostType = "Lcom/music/bitchord/playback/PlaybackService;";
        String objectType = "Ljava/lang/Object;";
        Method restoreBridge = null;
        for (Method method : types.get(hostType).getMethods())
            if (method.getName().equals("bitchordAutomixRestore")) {
                check(restoreBridge == null, "One stored-analysis restore bridge");
                restoreBridge = method;
            }
        check(
                restoreBridge != null
                        && (restoreBridge.getAccessFlags() & 0x9) == 0x9
                        && restoreBridge.getReturnType().equals("V")
                        && restoreBridge
                                .getParameterTypes()
                                .equals(List.of(objectType, "Ljava/lang/String;")),
                "Stored-analysis restore bridge ABI");
        String restoreName = null;
        int constructions = 0, constructorCalls = 0, runCalls = 0;
        for (Instruction instruction : code(restoreBridge))
            if (instruction.getOpcode() == Opcode.NEW_INSTANCE) {
                constructions++;
                restoreName =
                        ((TypeReference) ((ReferenceInstruction) instruction).getReference())
                                .getType();
            }
        check(constructions == 1 && restoreName != null, "Restore bridge creates one runnable");
        for (Instruction instruction : code(restoreBridge)) {
            if (instruction.getOpcode() == Opcode.INVOKE_DIRECT
                    && calls(
                            instruction,
                            restoreName,
                            "<init>",
                            "V",
                            analyzerType,
                            "Ljava/lang/String;")) constructorCalls++;
            if (instruction.getOpcode() == Opcode.INVOKE_VIRTUAL
                    && calls(instruction, restoreName, "run", "V")) runCalls++;
        }
        check(
                constructorCalls == 1 && runCalls == 1,
                "Restore bridge constructs and runs the native runnable once");
        ClassDef restore = types.get(restoreName);
        check(
                restore != null
                        && (restore.getAccessFlags() & 0x1) != 0
                        && (restore.getAccessFlags() & 0x400) == 0
                        && restore.getInterfaces().contains("Ljava/lang/Runnable;"),
                "Restore bridge target is a native runnable");
        int publicConstructors = 0;
        for (Method method : restore.getMethods())
            if (method.getName().equals("<init>")
                    && method.getReturnType().equals("V")
                    && method.getParameterTypes()
                            .equals(List.of(analyzerType, "Ljava/lang/String;"))
                    && (method.getAccessFlags() & 0x1) != 0
                    && (method.getAccessFlags() & 0x8) == 0) publicConstructors++;
        check(publicConstructors == 1, "Native restore constructor is publicly callable");
        Method restoredApi = null;
        for (Method method : types.get(runtime).getMethods())
            if (method.getName().equals("restored")) {
                check(restoredApi == null, "One restored publication hook");
                restoredApi = method;
            }
        check(
                restoredApi != null
                        && (restoredApi.getAccessFlags() & 0x9) == 0x9
                        && restoredApi.getReturnType().equals(objectType)
                        && restoredApi
                                .getParameterTypes()
                                .equals(List.of("Ljava/util/Map;", objectType, objectType)),
                "Restored publication hook ABI");
        int restoredHooks = 0;
        for (ClassDef type : types.values())
            for (Method method : type.getMethods())
                for (Instruction instruction : code(method))
                    if (calls(
                            instruction,
                            runtime,
                            "restored",
                            objectType,
                            "Ljava/util/Map;",
                            objectType,
                            objectType)) {
                        restoredHooks++;
                        check(
                                type == restore,
                                "Restored hook is only reached by the native runnable");
                    }
        check(restoredHooks == 1, "Restored publication hook is reached exactly once");
        Method restoreRun = null;
        for (Method method : restore.getMethods())
            if (method.getName().equals("run") && method.getParameterTypes().isEmpty())
                restoreRun = method;
        check(
                restoreRun != null
                        && restoreRun.getReturnType().equals("V")
                        && (restoreRun.getAccessFlags() & 0x1) != 0
                        && (restoreRun.getAccessFlags() & 0x8) == 0,
                "Native runnable has public run()");
        List<Instruction> restoreCode = code(restoreRun);
        int restoredAt =
                unique(
                        restoreCode,
                        runtime,
                        "restored",
                        objectType,
                        "Ljava/util/Map;",
                        objectType,
                        objectType);
        check(
                restoredAt > 0
                        && restoredAt + 1 < restoreCode.size()
                        && restoreCode.get(restoredAt - 1).getOpcode() == Opcode.IGET_OBJECT
                        && restoreCode.get(restoredAt - 1)
                                instanceof TwoRegisterInstruction published
                        && ((ReferenceInstruction) published).getReference()
                                instanceof FieldReference results
                        && results.getDefiningClass().equals(analyzerType)
                        && results.getName().equals("results")
                        && results.getType().equals("Ljava/util/concurrent/ConcurrentHashMap;")
                        && restoreCode.get(restoredAt) instanceof FiveRegisterInstruction hook
                        && hook.getOpcode() == Opcode.INVOKE_STATIC
                        && hook.getRegisterCount() == 3
                        && hook.getRegisterC() == published.getRegisterA()
                        && restoreCode.get(restoredAt + 1).getOpcode() == Opcode.RETURN_VOID,
                "Restored hook directly publishes the analyzer results map");
        int resultAccesses = 0;
        for (Instruction instruction : restoreCode) {
            if (instruction instanceof ReferenceInstruction r
                    && r.getReference() instanceof FieldReference field
                    && field.getDefiningClass().equals(analyzerType)
                    && field.getName().equals("results")) resultAccesses++;
            check(
                    !calls(
                            instruction,
                            "Ljava/util/concurrent/ConcurrentHashMap;",
                            "putIfAbsent",
                            objectType,
                            objectType,
                            objectType),
                    "Native runnable no longer publishes through the original map call");
        }
        check(resultAccesses == 1, "Native runnable has one analysis result access");
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
                        + " Automix Editor APK checks; "
                        + speeds
                        + " speed paths protected");
    }
}

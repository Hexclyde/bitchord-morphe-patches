import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.dexlib2.immutable.instruction.*;

import app.morphe.patcher.patch.PatchLoader;

import java.io.*;
import java.util.*;

/** Qualifies removal of the unavailable provider while saved audio remains ordinary local audio. */
public class VerifyFlacAddonApk {
    static int count;
    static final String BASE = "Lapp/bitchord/telegram/";
    static final String REGISTRY = "Lcom/music/bitchord/data/sources/SourceRegistry;";

    static void check(boolean value, String why) {
        count++;
        if (!value) throw new AssertionError(why);
    }

    static Method method(Map<String, ClassDef> types, String owner, String name) {
        check(types.containsKey(owner), "Required source boundary: " + owner);
        Method found = null;
        for (Method candidate : types.get(owner).getMethods())
            if (candidate.getName().equals(name)) {
                check(found == null, "Unique source boundary: " + name);
                found = candidate;
            }
        check(
                found != null && found.getImplementation() != null,
                "Source boundary exists: " + name);
        return found;
    }

    static List<Instruction> code(Method method) {
        List<Instruction> code = new ArrayList<>();
        method.getImplementation().getInstructions().forEach(code::add);
        return code;
    }

    static void verify(Map<String, ClassDef> types) {
        Method marker = method(types, BASE + "SoundExtras;", "flacAddonApplied");
        List<Instruction> markerCode = code(marker);
        check(
                markerCode.size() == 2
                        && markerCode.get(0).getOpcode() == Opcode.CONST_4
                        && ((NarrowLiteralInstruction) markerCode.get(0)).getNarrowLiteral() == 0
                        && markerCode.get(1).getOpcode() == Opcode.RETURN
                        && ((OneRegisterInstruction) markerCode.get(0)).getRegisterA()
                                == ((OneRegisterInstruction) markerCode.get(1)).getRegisterA(),
                "Unavailable FLAC provider marker remains false");
        int dispatches = 0, playbackFilters = 0;
        for (ClassDef type : types.values())
            if (!type.getType().startsWith(BASE))
                for (Method method : type.getMethods()) {
                    if (method.getImplementation() == null) continue;
                    for (Instruction instruction : method.getImplementation().getInstructions()) {
                        MethodReference call = VerifyApk.call(instruction);
                        if (call != null && call.getDefiningClass().equals(BASE + "FlacAddon;")) {
                            if (call.getName().equals("bodyOrNull")) dispatches++;
                            if (call.getName().equals("playbackSources")) playbackFilters++;
                        }
                    }
                }
        check(dispatches == 0, "No FLAC provider dispatch hook is installed");
        check(
                playbackFilters == 0,
                "No retired provider playback or search transform is installed");
        Method startup = method(types, BASE + "SoundExtras;", "init");
        int cleanup = 0, gated = 0;
        for (Instruction instruction : code(startup)) {
            MethodReference call = VerifyApk.call(instruction);
            if (call == null) continue;
            if (call.getDefiningClass().equals(BASE + "FlacAddon;")
                    && call.getName().equals("init")) cleanup++;
            if (call.getDefiningClass().equals(BASE + "SoundExtras;")
                    && call.getName().equals("flacAddonApplied")) gated++;
        }
        check(cleanup == 1 && gated == 0, "Shared startup prunes an earlier provider installation");
        Method publication = method(types, REGISTRY, "publish");
        check(
                publication.getParameterTypes().equals(List.of("Ljava/util/ArrayList;", "Z"))
                        && publication.getReturnType().equals("V")
                        && (publication.getAccessFlags() & 8) != 0,
                "Native source publication ABI is qualified");
        List<Instruction> publish = code(publication);
        MethodReference filter = VerifyApk.call(publish.get(0));
        int input = publication.getImplementation().getRegisterCount() - 2;
        check(
                filter != null
                        && filter.getDefiningClass().equals(BASE + "FlacAddon;")
                        && filter.getName().equals("availableConfigs")
                        && filter.getParameterTypes().equals(List.of("Ljava/util/ArrayList;"))
                        && filter.getReturnType().equals("Ljava/util/ArrayList;")
                        && publish.get(0).getOpcode() == Opcode.INVOKE_STATIC_RANGE
                        && ((RegisterRangeInstruction) publish.get(0)).getStartRegister() == input
                        && ((RegisterRangeInstruction) publish.get(0)).getRegisterCount() == 1
                        && publish.get(1).getOpcode() == Opcode.MOVE_RESULT_OBJECT
                        && ((OneRegisterInstruction) publish.get(1)).getRegisterA() == input,
                "Owned-source filtering replaces the real input before any native publication or"
                        + " persistence");
        boolean catalogue = false, persistence = false;
        for (int index = 2; index < publish.size(); index++) {
            Instruction instruction = publish.get(index);
            if (instruction instanceof ReferenceInstruction ref
                    && ref.getReference() instanceof FieldReference field)
                catalogue |=
                        field.getDefiningClass().equals(REGISTRY)
                                && field.getName().equals("configs");
            MethodReference call = VerifyApk.call(instruction);
            if (call != null)
                persistence |=
                        call.getDefiningClass().equals("Landroid/content/SharedPreferences$Editor;")
                                && call.getName().equals("putString");
        }
        check(catalogue && persistence, "Native catalogue and persistence follow the filter");
        Method available = method(types, BASE + "FlacAddon;", "availableConfigs");
        Set<String> literals = new HashSet<>();
        boolean guard = false;
        for (Instruction instruction : code(available)) {
            if (instruction instanceof ReferenceInstruction ref
                    && ref.getReference() instanceof StringReference text)
                literals.add(text.getString());
            MethodReference call = VerifyApk.call(instruction);
            if (call != null) {
                guard |=
                        call.getDefiningClass().equals(BASE + "SoundExtras;")
                                && call.getName().equals("flacAddonApplied");
                check(
                        !call.getDefiningClass().startsWith("Ljava/io/")
                                && !call.getDefiningClass().startsWith("Ljava/net/")
                                && !call.getDefiningClass().equals(BASE + "SyncSecrets;"),
                        "Source filtering has no file, credential or network operations");
            }
        }
        check(
                guard
                        && literals.containsAll(
                                Set.of(
                                        "flacdownloader-native",
                                        "https://flac.bitchord.invalid",
                                        "getId",
                                        "getBaseUrl")),
                "Retirement is guarded and matches both the exact owned identity and address");
    }

    public static void main(String[] args) throws Exception {
        Map<String, ClassDef> types = new HashMap<>();
        var container = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.getDefault());
        for (String entry : container.getDexEntryNames())
            for (ClassDef type : container.getEntry(entry).getDexFile().getClasses())
                types.put(type.getType(), type);
        verify(types);
        if (Arrays.asList(args).contains("--regressions")) regressions(types);
        if (args.length > 1 && args[1].equals("standalone")) {
            check(!types.containsKey(BASE + "TelegramBridge;"), "No Telegram account dependency");
            check(!types.containsKey("Lorg/drinkless/tdlib/Client;"), "No TDLib dependency");
            check(
                    !types.containsKey("Lapp/bitchord/soulseek/SoulseekBridge;"),
                    "No Soulseek account dependency");
        }
        if (args.length > 2)
            for (var patch : new PatchLoader.Jar(Set.of(new File(args[2]))))
                check(
                        !"Flac Downloader Audio".equals(patch.getName()),
                        "Unavailable provider is absent from actual Morphe discovery");
        System.out.println("PASS: " + count + " unavailable-provider isolation checks");
    }

    static void reject(Map<String, ClassDef> types, String name) {
        try {
            verify(types);
        } catch (AssertionError expected) {
            System.out.println("PASS: provider isolation negative variant rejected: " + name);
            return;
        }
        throw new AssertionError("Provider isolation negative variant accepted: " + name);
    }

    static void regressions(Map<String, ClassDef> types) {
        Method publication = method(types, REGISTRY, "publish");
        List<Instruction> original = code(publication);
        reject(
                VerifyPatchUiApk.withCode(types, publication, original.subList(2, original.size())),
                "publication filter removed");
        List<Instruction> mutated = new ArrayList<>(original);
        RegisterRangeInstruction call = (RegisterRangeInstruction) original.get(0);
        mutated.set(
                0,
                new ImmutableInstruction3rc(
                        Opcode.INVOKE_STATIC_RANGE,
                        call.getStartRegister() + 1,
                        1,
                        VerifyApk.call(original.get(0))));
        reject(
                VerifyPatchUiApk.withCode(types, publication, mutated),
                "persistence flag passed as catalogue");
        mutated = new ArrayList<>(original);
        mutated.set(1, new ImmutableInstruction10x(Opcode.NOP));
        reject(
                VerifyPatchUiApk.withCode(types, publication, mutated),
                "filtered catalogue discarded");
        Method marker = method(types, BASE + "SoundExtras;", "flacAddonApplied");
        mutated = code(marker);
        mutated.set(
                0,
                new ImmutableInstruction11n(
                        Opcode.CONST_4,
                        ((OneRegisterInstruction) mutated.get(0)).getRegisterA(),
                        1));
        reject(VerifyPatchUiApk.withCode(types, marker, mutated), "provider marker re-enabled");
    }
}

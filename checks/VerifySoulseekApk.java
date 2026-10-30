import com.android.apksig.ApkVerifier;
import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;

import org.w3c.dom.*;

import java.io.File;
import java.nio.*;
import java.util.*;
import java.util.zip.ZipFile;

/** Verifies the independent Soulseek extension and its manual-only first-release guard. */
public final class VerifySoulseekApk {
    static final String PREFIX = "Lapp/bitchord/soulseek/";
    static int checks;

    static void check(boolean okay, String text) {
        checks++;
        if (!okay) throw new AssertionError(text);
    }

    static List<Instruction> code(Method method) {
        List<Instruction> result = new ArrayList<>();
        if (method.getImplementation() != null)
            method.getImplementation().getInstructions().forEach(result::add);
        return result;
    }

    static MethodReference call(Instruction instruction) {
        return instruction instanceof ReferenceInstruction r
                        && r.getReference() instanceof MethodReference m
                ? m
                : null;
    }

    static boolean namedCall(Instruction instruction, String owner, String name) {
        MethodReference ref = call(instruction);
        return ref != null && ref.getDefiningClass().equals(owner) && ref.getName().equals(name);
    }

    public static void main(String[] args) throws Exception {
        File apk = new File(args[0]);
        boolean enabled = args[1].equals("enabled");
        var container = DexFileFactory.loadDexContainer(apk, Opcodes.getDefault());
        Map<String, ClassDef> classes = new HashMap<>();
        for (String entry : container.getDexEntryNames())
            for (ClassDef cls : container.getEntry(entry).getDexFile().getClasses())
                check(
                        classes.put(cls.getType(), cls) == null,
                        "Unique DEX class: " + cls.getType());
        boolean extension = classes.containsKey(PREFIX + "SoulseekBridge;");
        check(extension == enabled, "Soulseek classes match patch selection");
        Document manifest = VerifyApk.manifest(apk);
        var activities = manifest.getElementsByTagName("activity");
        int activityCount = 0;
        for (int i = 0; i < activities.getLength(); i++) {
            Element activity = (Element) activities.item(i);
            if (!Set.of(
                            "app.bitchord.soulseek.SoulseekActivity",
                            "app.bitchord.soulseek.NostrSetupActivity",
                            "app.bitchord.soulseek.BulkDownloadsActivity",
                            "app.bitchord.soulseek.DownloadDestinationActivity",
                            "app.bitchord.soulseek.AudioFormatSetupActivity",
                            "app.bitchord.soulseek.DebridSetupActivity",
                            "app.bitchord.soulseek.DebridDownloadsActivity")
                    .contains(activity.getAttributeNS(VerifyApk.NS, "name"))) continue;
            activityCount++;
            check(
                    classes.containsKey(
                            "L"
                                    + activity.getAttributeNS(VerifyApk.NS, "name")
                                            .replace('.', '/')
                                    + ";"),
                    "Declared download activity has an injected class");
            check(
                    activity.getAttributeNS(VerifyApk.NS, "exported").equals("false"),
                    "Soulseek setup is not exported");
            check(
                    activity.getElementsByTagName("intent-filter").getLength() == 0,
                    "Soulseek setup adds no launcher");
        }
        check(activityCount == (enabled ? 7 : 0), "Soulseek activities match patch selection");
        int serviceCount = 0;
        var services = manifest.getElementsByTagName("service");
        for (int i = 0; i < services.getLength(); i++) {
            Element service = (Element) services.item(i);
            String name = service.getAttributeNS(VerifyApk.NS, "name");
            if (!Set.of("app.bitchord.soulseek.I2pService", "app.bitchord.soulseek.TorService")
                    .contains(name)) continue;
            serviceCount++;
            check(
                    classes.containsKey("L" + name.replace('.', '/') + ";"),
                    "Declared overlay service has an injected class");
            check(
                    service.getAttributeNS(VerifyApk.NS, "exported").equals("false"),
                    "Overlay service is private");
            check(
                    service.getAttributeNS(VerifyApk.NS, "foregroundServiceType")
                            .equals("dataSync"),
                    "Explicit download service type");
            check(
                    service.getAttributeNS(VerifyApk.NS, "process")
                            .equals(
                                    name.endsWith("I2pService")
                                            ? ":bitchord_i2p"
                                            : ":bitchord_tor"),
                    "Overlay runs in its own process");
        }
        check(serviceCount == (enabled ? 2 : 0), "Overlay services match patch selection");
        int transferServices = 0;
        for (int i = 0; i < services.getLength(); i++) {
            Element service = (Element) services.item(i);
            if (!service.getAttributeNS(VerifyApk.NS, "name")
                    .equals("app.bitchord.soulseek.BulkTransferService")) continue;
            transferServices++;
            check(
                    classes.containsKey(PREFIX + "BulkTransferService;"),
                    "Transfer service has an injected class");
            check(
                    service.getAttributeNS(VerifyApk.NS, "exported").equals("false"),
                    "Transfer service is private");
            check(
                    service.getAttributeNS(VerifyApk.NS, "process").isEmpty(),
                    "Transfer service retains the player process");
            check(
                    service.getAttributeNS(VerifyApk.NS, "foregroundServiceType")
                            .equals("dataSync"),
                    "Transfer service declares download work");
        }
        check(
                transferServices == (enabled ? 1 : 0),
                "Foreground transfer service matches selection");
        if (enabled) {
            int overlayPermissions = 0;
            var permissions = manifest.getElementsByTagName("uses-permission");
            for (int i = 0; i < permissions.getLength(); i++)
                if (((Element) permissions.item(i))
                        .getAttributeNS(VerifyApk.NS, "name")
                        .equals("android.permission.HIDE_OVERLAY_WINDOWS")) overlayPermissions++;
            check(
                    overlayPermissions == 1,
                    "Soulseek setup overlay permission declared once, alone or combined");
        }
        Map<String, Integer> hooks = new HashMap<>();
        for (ClassDef cls : classes.values())
            for (Method method : cls.getMethods()) {
                for (Instruction instruction : code(method)) {
                    if (!(instruction instanceof ReferenceInstruction r)) continue;
                    String reference = r.getReference().toString();
                    if (cls.getType().startsWith(PREFIX)) {
                        check(
                                !reference.contains("Lapp/bitchord/telegram/"),
                                "Soulseek has no hard dependency on optional Telegram extension");
                        if (r.getReference() instanceof MethodReference m
                                && (m.getDefiningClass().startsWith("Landroidx/media3/")
                                        || m.getDefiningClass().startsWith("Lcom/music/bitchord/")))
                            check(
                                    VerifyApk.resolves(
                                            classes, m, m.getDefiningClass(), new HashSet<>()),
                                    "Soulseek host method resolves: " + m);
                        if (r.getReference() instanceof FieldReference f
                                && (f.getDefiningClass().startsWith("Landroidx/media3/")
                                        || f.getDefiningClass().startsWith("Lcom/music/bitchord/")))
                            check(
                                    VerifyApk.fieldResolves(classes, f),
                                    "Soulseek host field resolves: " + f);
                    } else if (r.getReference() instanceof MethodReference m
                            && m.getDefiningClass().startsWith(PREFIX)) {
                        ClassDef owner = classes.get(m.getDefiningClass());
                        check(
                                owner != null && (owner.getAccessFlags() & 1) != 0,
                                "Host can access Soulseek class: " + m);
                        boolean accessible = false;
                        for (Method target : owner.getMethods())
                            if (target.getName().equals(m.getName())
                                    && target.getParameterTypes().equals(m.getParameterTypes())
                                    && target.getReturnType().equals(m.getReturnType()))
                                accessible =
                                        (target.getAccessFlags() & 1) != 0
                                                && (target.getAccessFlags() & 8) != 0;
                        check(accessible, "Host can call public static Soulseek method: " + m);
                        hooks.merge(m.getDefiningClass() + "->" + m.getName(), 1, Integer::sum);
                    }
                }
            }
        for (String hook :
                List.of(
                        "SoulseekBridge;->boot",
                        "SoulseekAddon;->bodyOrNull",
                        "SoulseekAddon;->reserved",
                        "SoulseekDataSource;->wrap",
                        "SoulseekDownloadRoute;->routeOrNull",
                        "SoulseekDownloadRoute;->ownsTrack",
                        "SoulseekDownloads;->fetch",
                        "SoulseekSelectedDownloads;->enqueueOrFalse",
                        "SoulseekSourceEditorRoute;->wrap"))
            check(
                    hooks.getOrDefault(PREFIX + hook, 0) == (enabled ? 1 : 0),
                    "Exactly one matching Soulseek host hook: " + hook);

        if (enabled) {
            ClassDef route = classes.get("Lcom/music/bitchord/download/Downloads$Route;");
            check(route != null, "Host download route retained");
            List<String> routeParameters =
                    List.of(
                            "Ljava/lang/String;",
                            "Ljava/lang/String;",
                            "Ljava/lang/String;",
                            "Ljava/lang/String;",
                            "Lcom/google/android/gms/tasks/zzr;",
                            "Lkotlin/jvm/functions/Function3;");
            List<String> maskedParameters = new ArrayList<>(routeParameters);
            maskedParameters.add("I");
            boolean maskedRoute = false;
            boolean legacyRoute = false;
            for (Method method : route.getMethods())
                if (method.getName().equals("<init>")) {
                    maskedRoute |= method.getParameterTypes().equals(maskedParameters);
                    legacyRoute |= method.getParameterTypes().equals(routeParameters);
                }
            check(maskedRoute ^ legacyRoute, "Exactly one supported route constructor ABI");
            boolean taggable = false;
            for (Field field : route.getFields())
                taggable |= field.getName().equals("taggable") && field.getType().equals("Z");
            check(taggable == legacyRoute, "Route metadata policy matches inspected host ABI");
            ClassDef guard = classes.get(PREFIX + "SoulseekDataSource$Routing;");
            check(guard != null, "Download-only playback guard included");
            for (Method method : guard.getMethods())
                for (Instruction instruction : code(method)) {
                    MethodReference ref = call(instruction);
                    check(
                            ref == null
                                    || !ref.getDefiningClass().equals(PREFIX + "SoulseekBridge;"),
                            "Playback guard cannot open a peer download");
                }
            ClassDef downloads = classes.get("Lcom/music/bitchord/download/Downloads;");
            for (Method method : downloads.getMethods())
                if (method.getName().equals("enqueue")) {
                    List<Instruction> enqueueCode = code(method);
                    check(
                            namedCall(
                                    enqueueCode.get(0),
                                    PREFIX + "SoulseekSelectedDownloads;",
                                    "enqueueOrFalse"),
                            "Selected downloads intercepted before filename adoption");
                    check(
                            enqueueCode.get(2).getOpcode() == Opcode.IF_EQZ
                                    && VerifyApk.branchTarget(enqueueCode, 2) == enqueueCode.get(4),
                            "Other downloads reach original enqueue");
                }
            Method routing = null;
            for (Method method : downloads.getMethods())
                if (method.getName().equals("access$routeFor")) routing = method;
            check(routing != null, "Selected-file route method retained");
            check(
                    routing.getImplementation().getRegisterCount() == (maskedRoute ? 18 : 25),
                    "Selected-file hook retains the inspected route register ABI");
            List<Instruction> routeCode = code(routing);
            check(
                    routeCode.get(1).getOpcode() == Opcode.IF_EQZ
                            && VerifyApk.branchTarget(routeCode, 1) == routeCode.get(9),
                    "Coroutine route resumes with a null placeholder reach original state"
                            + " dispatch");
            check(
                    routeCode.subList(0, Math.min(8, routeCode.size())).stream()
                            .anyMatch(
                                    instruction ->
                                            namedCall(
                                                    instruction,
                                                    PREFIX + "SoulseekDownloadRoute;",
                                                    "routeOrNull")),
                    "Selected-file binding precedes source lookup");
            ClassDef tagger = classes.get("Lcom/music/bitchord/download/MediaTagger;");
            for (Method method : tagger.getMethods())
                if (method.getName().equals("embed$app_prodRelease")) {
                    List<Instruction> body = code(method);
                    check(
                            body.get(1).getOpcode() == Opcode.IF_EQZ
                                    && VerifyApk.branchTarget(body, 1) == body.get(9),
                            "Coroutine metadata resumes bypass selected-file guard safely");
                }
            for (ClassDef cls : classes.values())
                if (!cls.getType().startsWith(PREFIX))
                    for (Method method : cls.getMethods()) {
                        List<Instruction> body = code(method);
                        for (int i = 0; i < body.size(); i++) {
                            if (!namedCall(body.get(i), PREFIX + "SoulseekDownloads;", "fetch"))
                                continue;
                            check(
                                    method.getImplementation().getRegisterCount()
                                            == (maskedRoute ? 27 : 29),
                                    "Direct download hook retains inspected coroutine registers");
                            check(
                                    body.get(i) instanceof FiveRegisterInstruction call
                                            && call.getRegisterCount() == 4
                                            && call.getRegisterC() == 4
                                            && call.getRegisterD() == 5
                                            && call.getRegisterE() == 6
                                            && call.getRegisterF() == 7,
                                    "Native download receives URL, output, progress and job"
                                            + " context");
                            check(
                                    i >= 2
                                            && namedCall(
                                                    body.get(i - 2),
                                                    "Lkotlinx/coroutines/CoroutineScope;",
                                                    "getCoroutineContext"),
                                    "Download hook obtains the original coroutine job context");
                            check(
                                    body.get(i + 2).getOpcode() == Opcode.RETURN_OBJECT
                                            && body.get(i - 7).getOpcode() == Opcode.IF_EQZ
                                            && VerifyApk.branchTarget(body, i - 7)
                                                    == body.get(i + 3)
                                            && body
                                                    .subList(i + 3, Math.min(i + 35, body.size()))
                                                    .stream()
                                                    .anyMatch(
                                                            instruction ->
                                                                    instruction.getOpcode()
                                                                                    == Opcode
                                                                                            .NEW_INSTANCE
                                                                            && instruction
                                                                                    instanceof
                                                                                    ReferenceInstruction
                                                                                                    request
                                                                            && request.getReference()
                                                                                    .toString()
                                                                                    .equals(
                                                                                            "Lokhttp3/Request$Builder;")),
                                    "Owned URL exits before host HTTP; other URLs reach original"
                                            + " request");
                        }
                    }
        }

        ClassDef source = classes.get("Lcom/music/bitchord/data/sources/AddonSource;");
        check(source != null, "Host addon source retained");
        Method search = null;
        for (Method method : source.getMethods())
            if (method.getName().equals("search") && method.getParameterTypes().size() == 5)
                search = method;
        check(search != null, "Host addon search retained");
        List<Instruction> searchCode = code(search);
        if (enabled) {
            check(
                    search.getImplementation().getRegisterCount() == 13,
                    "Search register layout retained");
            check(
                    searchCode.get(0).getOpcode() == Opcode.IF_EQZ
                            && ((OneRegisterInstruction) searchCode.get(0)).getRegisterA() == 11,
                    "Manual null StreamRequest branches past automatic guard");
            check(
                    searchCode.get(1).getOpcode() == Opcode.IGET_OBJECT
                            && ((TwoRegisterInstruction) searchCode.get(1)).getRegisterA() == 0
                            && ((TwoRegisterInstruction) searchCode.get(1)).getRegisterB() == 7,
                    "Guard reads this source configuration, not another source");
            check(
                    namedCall(
                            searchCode.get(2),
                            "Lcom/music/bitchord/data/sources/SourceConfig;",
                            "getId"),
                    "Guard compares the actual source ID");
            check(
                    searchCode.get(4) instanceof ReferenceInstruction text
                            && text.getReference() instanceof StringReference value
                            && value.getString().equals("soulseek-native"),
                    "Only Soulseek automatic matching is suppressed");
            check(
                    namedCall(searchCode.get(5), "Ljava/lang/String;", "equals"),
                    "Source comparison is exact");
            check(
                    searchCode.get(7).getOpcode() == Opcode.IF_EQZ
                            && VerifyApk.branchTarget(searchCode, 0)
                                    == VerifyApk.branchTarget(searchCode, 7),
                    "Manual requests and other sources reach the same original search");
            check(
                    namedCall(searchCode.get(8), "Ljava/util/Collections;", "emptyList")
                            && searchCode.get(9).getOpcode() == Opcode.MOVE_RESULT_OBJECT
                            && searchCode.get(10).getOpcode() == Opcode.RETURN_OBJECT,
                    "Unverified automatic Soulseek matching returns no candidate");
            check(
                    VerifyApk.branchTarget(searchCode, 0) == searchCode.get(11),
                    "Guard branches to the original first instruction");
        }
        try (ZipFile zip = new ZipFile(apk)) {
            for (String name :
                    List.of(
                            "nostr-relays.txt",
                            "nostr-relays-source.json",
                            "stun-servers.txt",
                            "stun-servers-source.json")) {
                var asset = zip.getEntry("assets/soulseek-native/" + name);
                check(
                        (asset != null) == enabled,
                        "Relay resource matches patch selection: " + name);
                if (enabled && args.length > 2)
                    try (ZipFile bundle = new ZipFile(args[2])) {
                        check(
                                Arrays.equals(
                                        zip.getInputStream(asset).readAllBytes(),
                                        bundle.getInputStream(bundle.getEntry("soulseek/" + name))
                                                .readAllBytes()),
                                "APK relay resource equals exact MPP input: " + name);
                    }
            }
            List<String> soulseekLibs =
                    zip.stream()
                            .map(e -> e.getName())
                            .filter(n -> n.endsWith("/libbitchord_soulseek.so"))
                            .toList();
            check(soulseekLibs.isEmpty() == !enabled, "Native Soulseek library matches selection");
            for (String library :
                    List.of(
                            "libbitchord_direct.so",
                            "libbitchord_i2p.so",
                            "libtor.so",
                            "libbitchord_ipfs.so")) {
                Set<String> paths = new HashSet<>();
                zip.stream()
                        .map(entry -> entry.getName())
                        .filter(name -> name.endsWith("/" + library))
                        .forEach(paths::add);
                check(
                        paths.isEmpty() == !enabled,
                        "Download transport matches patch selection: " + library);
                if (enabled) {
                    check(paths.size() == soulseekLibs.size(), "Every selected ABI has " + library);
                    for (String name : paths) {
                        check(
                                soulseekLibs.contains(
                                        name.replace(library, "libbitchord_soulseek.so")),
                                "Runtime ABI matches host ABI");
                        if (args.length > 2)
                            try (ZipFile bundle = new ZipFile(args[2])) {
                                check(
                                        Arrays.equals(
                                                zip.getInputStream(zip.getEntry(name))
                                                        .readAllBytes(),
                                                bundle.getInputStream(bundle.getEntry(name))
                                                        .readAllBytes()),
                                        "APK runtime is exact verified MPP input: " + library);
                            }
                    }
                }
            }
            check(
                    classes.containsKey("Lorg/torproject/jni/TorService;") == enabled,
                    "Pinned Tor JNI class matches selection");
            if (enabled && args.length > 2)
                try (ZipFile bundle = new ZipFile(args[2])) {
                    String assetList =
                            new String(
                                    bundle.getInputStream(bundle.getEntry("runtimes/assets.list"))
                                            .readAllBytes(),
                                    java.nio.charset.StandardCharsets.UTF_8);
                    for (String path : assetList.split("\\n")) {
                        check(
                                zip.getEntry("assets/" + path) != null,
                                "Runtime asset included: " + path);
                        check(
                                Arrays.equals(
                                        zip.getInputStream(zip.getEntry("assets/" + path))
                                                .readAllBytes(),
                                        bundle.getInputStream(
                                                        bundle.getEntry("runtimes/assets/" + path))
                                                .readAllBytes()),
                                "Runtime asset matches pinned bundle: " + path);
                    }
                }
            if (enabled) {
                check(
                        zip.stream().noneMatch(entry -> entry.getName().startsWith("lib/x86/")),
                        "No partial unsupported 32-bit x86 ABI remains in the patched APK");
                Set<String> inputAbis = new HashSet<>();
                zip.stream()
                        .map(e -> e.getName())
                        .filter(n -> n.startsWith("lib/") && n.endsWith(".so"))
                        .forEach(n -> inputAbis.add(n.split("/")[1]));
                Set<String> nativeAbis = new HashSet<>();
                for (String name : soulseekLibs) {
                    String abi = name.split("/")[1];
                    nativeAbis.add(abi);
                    byte[] data = zip.getInputStream(zip.getEntry(name)).readAllBytes();
                    check(
                            data.length > 64
                                    && data[0] == 0x7f
                                    && data[1] == 'E'
                                    && data[2] == 'L'
                                    && data[3] == 'F',
                            "Soulseek ELF file");
                    int expected =
                            switch (abi) {
                                case "arm64-v8a" -> 183;
                                case "armeabi-v7a" -> 40;
                                case "x86_64" -> 62;
                                default -> -1;
                            };
                    check(
                            expected >= 0
                                    && Short.toUnsignedInt(
                                                    ByteBuffer.wrap(data)
                                                            .order(ByteOrder.LITTLE_ENDIAN)
                                                            .getShort(18))
                                            == expected,
                            "Soulseek ELF matches ABI");
                    if (args.length > 2)
                        try (ZipFile bundle = new ZipFile(args[2])) {
                            check(
                                    Arrays.equals(
                                            data,
                                            bundle.getInputStream(bundle.getEntry(name))
                                                    .readAllBytes()),
                                    "APK Soulseek library equals exact MPP input");
                        }
                }
                check(
                        nativeAbis.equals(inputAbis),
                        "Only input APK ABIs receive Soulseek libraries");
                check(
                        zip.getEntry("assets/soulseek-native/licenses/soulseek-rs-MIT.txt") != null,
                        "Upstream Soulseek MIT notice included");
                check(
                        zip.getEntry(
                                        "assets/soulseek-native/licenses/soulseek-native-dependencies.txt")
                                != null,
                        "Native dependency and Rust standard-library notices included");
            }
        }
        var signature =
                new ApkVerifier.Builder(apk)
                        .setMinCheckedPlatformVersion(26)
                        .setMaxCheckedPlatformVersion(36)
                        .build()
                        .verify();
        check(signature.isVerified(), "APK signature verifies");
        System.out.println("PASS: " + checks + " Soulseek APK checks; enabled=" + enabled);
    }
}

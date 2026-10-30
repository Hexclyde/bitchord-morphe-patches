import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;

import java.io.File;
import java.util.*;

/** Qualifies the new reflection boundary and verifies both navigation/source-selection hooks. */
public final class VerifyDownloadIntegrationApk {
    static int checks;

    static void check(boolean value, String why) {
        checks++;
        if (!value) throw new AssertionError(why);
    }

    static int calls(Method method, String owner, String name) {
        int count = 0;
        if (method.getImplementation() != null)
            for (Instruction code : method.getImplementation().getInstructions())
                if (code instanceof ReferenceInstruction r
                        && r.getReference() instanceof MethodReference m
                        && m.getDefiningClass().equals(owner)
                        && m.getName().equals(name)) count++;
        return count;
    }

    public static void main(String[] args) throws Exception {
        Map<String, ClassDef> types = new HashMap<>();
        var container = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.getDefault());
        for (String name : container.getDexEntryNames())
            for (ClassDef type : container.getEntry(name).getDexFile().getClasses())
                types.put(type.getType(), type);
        String runtime = "Lapp/bitchord/soulseek/";
        for (String name :
                List.of(
                        "DownloadNavigation",
                        "HostDownloadAccess",
                        "DownloadExplorerActivity",
                        "DownloadsHubActivity",
                        "DownloadsShell",
                        "DownloadPage",
                        "DownloadExplorerPage",
                        "DownloadsHubPage",
                        "BatchDownloadActivity",
                        "BatchDownloadPage",
                        "DownloadBatch",
                        "PublicSoulseekShares",
                        "DownloadArchives"))
            check(types.containsKey(runtime + name + ";"), "Production runtime present: " + name);
        for (String[] bar :
                new String[][] {
                    {"FloatingBottomBarKt", "FloatingBottomBar"}, {"GlassNavBarKt", "GlassNavBar"}
                }) {
            Method found = null;
            for (Method method :
                    types.get("Lcom/music/bitchord/ui/components/" + bar[0] + ";").getMethods())
                if (method.getName().equals(bar[1])) found = method;
            check(found != null, "Host navigation entry exists");
            for (String hook : List.of("index", "callback", "tabs"))
                check(
                        calls(found, runtime + "DownloadNavigation;", hook) == 1,
                        "Navigation has one " + hook + " hook");
        }
        Method library = null, root = null, batch = null, androidView = null, back = null;
        for (Method method :
                types.get("Lcom/music/bitchord/ui/screens/LibraryScreenKt;").getMethods())
            if (method.getName().equals("LibraryScreen")) library = method;
        check(
                library != null && calls(library, runtime + "DownloadsShell;", "compose") == 1,
                "Library content delegates to one embedded downloads page");
        for (Method method : types.get("Lcom/music/bitchord/MainActivityKt;").getMethods())
            if (method.getName().startsWith("BitChordApp-")) root = method;
        check(
                root != null && calls(root, runtime + "DownloadsShell;", "heading") == 2,
                "Player heading follows downloads while Library keeps its tab");
        for (Method method :
                types.get("Lcom/music/bitchord/MainActivityKt$$ExternalSyntheticLambda109;")
                        .getMethods()) if (method.getName().equals("invoke")) batch = method;
        check(
                batch != null
                        && calls(batch, runtime + "SoulseekDownloadChoice;", "interceptBatch") == 1,
                "Manual release callback has one bounded batch choice hook");
        for (Method method :
                types.get("Landroidx/compose/ui/viewinterop/AndroidView_androidKt;").getMethods())
            if (method.getName().equals("AndroidView") && method.getParameterTypes().size() == 6)
                androidView = method;
        check(
                androidView != null
                        && androidView
                                .getParameterTypes()
                                .get(3)
                                .equals("Landroidx/compose/runtime/ComposerImpl;"),
                "Exact AndroidView host ABI retained");
        for (Method method : types.get("Lcoil/util/DrawableUtils;").getMethods())
            if (method.getName().equals("BackHandler")) back = method;
        check(
                back != null
                        && back.getParameterTypes()
                                .equals(
                                        List.of(
                                                "Z",
                                                "Lkotlin/jvm/functions/Function0;",
                                                "Landroidx/compose/runtime/ComposerImpl;",
                                                "I",
                                                "I")),
                "Compose back handler keeps player and dialogs above downloads");
        for (Method method : types.get("Landroidx/activity/OnBackPressedDispatcher;").getMethods())
            check(
                    calls(method, runtime + "DownloadsShell;", "handleBack") == 0,
                    "Global back dispatcher is untouched");
        for (String name : List.of("configureShares", "sharing", "cancelUpload")) {
            boolean present = false;
            for (Method method : types.get(runtime + "SoulseekNative;").getMethods())
                if (method.getName().equals(name) && (method.getAccessFlags() & 256) != 0)
                    present = true;
            check(present, "Public sharing JNI declaration retained: " + name);
        }
        boolean forget = false;
        for (Method method : types.get("Lcom/music/bitchord/download/Downloads;").getMethods())
            if (method.getName().equals("forget")
                    && method.getParameterTypes().equals(List.of("Ljava/lang/String;"))
                    && (method.getAccessFlags() & 9) == 9) forget = true;
        check(forget, "Host saved-file removal ABI retained");
        boolean guarded = false;
        for (Method method : types.get("Lcom/music/bitchord/download/Downloads;").getMethods())
            if (method.getName().equals("fromSources"))
                guarded = calls(method, runtime + "HostDownloadAccess;", "forbidFallback") == 1;
        check(guarded, "Explicit source bindings guard the fallback entry");
        boolean glyph = false;
        for (Field field : types.get("Lcom/music/bitchord/ui/icons/BitChordIcons;").getFields())
            if (field.getName().equals("Download$delegate")) glyph = true;
        check(glyph, "Native Downloads glyph is retained");
        boolean registry = false;
        for (Method method :
                types.get("Lcom/music/bitchord/data/sources/SourceRegistry;").getMethods())
            if (method.getName().equals("activeForDownload") && (method.getAccessFlags() & 8) != 0)
                registry = true;
        check(registry, "Registry download eligibility API retained");
        boolean search = false;
        for (Method method :
                types.get("Lcom/music/bitchord/data/sources/MusicSource;").getMethods())
            if (method.getName().equals("search")
                    && method.getParameterTypes()
                            .equals(
                                    List.of(
                                            "Ljava/lang/String;",
                                            "I",
                                            "Z",
                                            "Lcom/music/bitchord/data/sources/StreamRequest;",
                                            "Lkotlin/coroutines/Continuation;"))) search = true;
        check(search, "Exact suspend-search ABI retained");
        check(
                types.containsKey("Lkotlin/coroutines/intrinsics/CoroutineSingletons;"),
                "Host suspension sentinel exists");
        for (ClassDef type : types.values())
            if (type.getType().startsWith(runtime + "HostDownloadAccess"))
                for (Method method : type.getMethods())
                    if (method.getImplementation() != null)
                        for (Instruction code : method.getImplementation().getInstructions())
                            if (code instanceof ReferenceInstruction r
                                    && r.getReference() instanceof MethodReference m)
                                check(
                                        !m.getDefiningClass().equals("Lkotlin/ResultKt;")
                                                && !m.getDefiningClass()
                                                        .equals(
                                                                "Lkotlin/coroutines/intrinsics/IntrinsicsKt;"),
                                        "No calls to R8-removed Kotlin facades");
        System.out.println("PASS: " + checks + " Downloads integration APK assertions");
    }
}

package app.bitchord.patches

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.*
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val SOULSEEK_ANDROID = "http://schemas.android.com/apk/res/android"
private const val SOULSEEK = "Lapp/bitchord/soulseek/"

private val soulseekTarget =
    Compatibility(
        name = "BitChord",
        packageName = "com.music.bitchord",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xFF8944,
        targets =
            listOf(
                AppTarget(version = "1.8.1", isExperimental = true, minSdk = 26),
                AppTarget(version = "1.9", isExperimental = true, minSdk = 26),
            ),
    )

private object SoulseekApplicationCreate :
    Fingerprint(
        definingClass = "Lcom/music/bitchord/BitChordApplication;",
        name = "onCreate",
        parameters = emptyList(),
        returnType = "V",
    )

private class SoulseekPatchMarker

private val streamseekPreviewResources = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { doc ->
            val app = doc.getElementsByTagName("application").item(0) as org.w3c.dom.Element
            val activity = doc.createElement("activity")
            activity.setAttributeNS(
                SOULSEEK_ANDROID,
                "android:name",
                "app.bitchord.soulseek.SoulseekPreviewActivity",
            )
            activity.setAttributeNS(SOULSEEK_ANDROID, "android:exported", "false")
            activity.setAttributeNS(SOULSEEK_ANDROID, "android:label", "Soulseek preview")
            activity.setAttributeNS(
                SOULSEEK_ANDROID,
                "android:theme",
                "@android:style/Theme.Material.NoActionBar",
            )
            app.appendChild(activity)
        }
    }
}

private val soulseekResources = resourcePatch {
    execute {
        val supportedAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64")
        val originalAbis =
            listApkEntries("lib/").filter { it.endsWith(".so") }.map { it.split('/')[1] }.toSet()
        // The official 1.9 universal APK includes a partial x86 library set. There is no
        // 32-bit x86 Soulseek/transfer runtime, so remove that ABI in full: retaining even
        // the host's partial set could make Android select an ABI without our runtimes.
        val universal19 =
            packageMetadata.versionName == "1.9" && originalAbis == supportedAbis + "x86"
        check(
            originalAbis.isNotEmpty() && (originalAbis.all { it in supportedAbis } || universal19)
        ) {
            "Unsupported Soulseek APK architecture: $originalAbis"
        }
        if (universal19) {
            val unsupportedEntries = listApkEntries("lib/x86/")
            check(unsupportedEntries.isNotEmpty()) { "Universal APK x86 entries are missing" }
            for (path in unsupportedEntries) delete(path)
            println("Removed unsupported 32-bit x86 libraries; kept all three supported ABIs")
        }
        val abis = originalAbis.intersect(supportedAbis)
        for (abi in abis) {
            for (library in
                listOf(
                    "libbitchord_soulseek.so",
                    "libbitchord_direct.so",
                    "libbitchord_i2p.so",
                    "libtor.so",
                    "libbitchord_ipfs.so",
                )) {
                val path = "lib/$abi/$library"
                val output = get(path, false)
                output.parentFile.mkdirs()
                val input =
                    SoulseekPatchMarker::class.java.classLoader.getResourceAsStream(path)
                        ?: error("Download runtime missing from bundle: $abi/$library")
                input.use { output.outputStream().use { out -> it.copyTo(out) } }
            }
        }
        val runtimeAssets =
            SoulseekPatchMarker::class
                .java
                .classLoader
                .getResourceAsStream("runtimes/assets.list")
                ?.bufferedReader()
                ?.use { it.readLines() } ?: error("Download runtime assets missing from bundle")
        check(runtimeAssets.size in 1..4096 && runtimeAssets.distinct().size == runtimeAssets.size)
        for (path in runtimeAssets) {
            check(
                path.startsWith("bitchord/") &&
                    path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
            )
            val output = get("assets/$path", false)
            output.parentFile.mkdirs()
            val input =
                SoulseekPatchMarker::class
                    .java
                    .classLoader
                    .getResourceAsStream("runtimes/assets/$path")
                    ?: error("Missing runtime asset: $path")
            input.use { source -> output.outputStream().use { source.copyTo(it) } }
        }
        for (name in
            listOf("soulseek-rs-MIT.txt", "soulseek-native-dependencies.txt", "GPL-3.0.txt")) {
            val output = get("assets/soulseek-native/licenses/$name", false)
            output.parentFile.mkdirs()
            SoulseekPatchMarker::class
                .java
                .classLoader
                .getResourceAsStream("licenses/$name")!!
                .use { input -> output.outputStream().use { input.copyTo(it) } }
        }
        for (name in
            listOf(
                "nostr-relays.txt",
                "nostr-relays-source.json",
                "stun-servers.txt",
                "stun-servers-source.json",
            )) {
            val output = get("assets/soulseek-native/$name", false)
            output.parentFile.mkdirs()
            SoulseekPatchMarker::class
                .java
                .classLoader
                .getResourceAsStream("soulseek/$name")!!
                .use { input -> output.outputStream().use { input.copyTo(it) } }
        }
        document("AndroidManifest.xml").use { doc ->
            val app = doc.getElementsByTagName("application").item(0) as org.w3c.dom.Element
            app.setAttributeNS(SOULSEEK_ANDROID, "android:extractNativeLibs", "true")
            val permissionNodes = doc.getElementsByTagName("uses-permission")
            for (permissionName in
                listOf(
                    "android.permission.HIDE_OVERLAY_WINDOWS",
                    "android.permission.ACCESS_NETWORK_STATE",
                    "android.permission.FOREGROUND_SERVICE",
                    "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
                )) {
                if (
                    (0 until permissionNodes.length).none {
                        (permissionNodes.item(it) as org.w3c.dom.Element).getAttributeNS(
                            SOULSEEK_ANDROID,
                            "name",
                        ) == permissionName
                    }
                ) {
                    val permission = doc.createElement("uses-permission")
                    permission.setAttributeNS(SOULSEEK_ANDROID, "android:name", permissionName)
                    doc.documentElement.insertBefore(permission, app)
                }
            }
            for (name in
                listOf(
                    "SoulseekActivity",
                    "SoulseekSearchActivity",
                    "DownloadExplorerActivity",
                    "DownloadsHubActivity",
                    "BatchDownloadActivity",
                    "NostrSetupActivity",
                    "PeerDiscoveryActivity",
                    "BulkDownloadsActivity",
                    "DownloadDestinationActivity",
                    "AudioFormatSetupActivity",
                    "DebridSetupActivity",
                    "DebridDownloadsActivity",
                )) {
                val activity = doc.createElement("activity")
                activity.setAttributeNS(
                    SOULSEEK_ANDROID,
                    "android:name",
                    "app.bitchord.soulseek.$name",
                )
                activity.setAttributeNS(SOULSEEK_ANDROID, "android:exported", "false")
                activity.setAttributeNS(SOULSEEK_ANDROID, "android:label", "Unofficial Soulseek")
                activity.setAttributeNS(
                    SOULSEEK_ANDROID,
                    "android:theme",
                    "@android:style/Theme.Material.NoActionBar",
                )
                app.appendChild(activity)
            }
            for ((name, process) in
                listOf("I2pService" to ":bitchord_i2p", "TorService" to ":bitchord_tor")) {
                val service = doc.createElement("service")
                service.setAttributeNS(
                    SOULSEEK_ANDROID,
                    "android:name",
                    "app.bitchord.soulseek.$name",
                )
                service.setAttributeNS(SOULSEEK_ANDROID, "android:exported", "false")
                service.setAttributeNS(SOULSEEK_ANDROID, "android:process", process)
                service.setAttributeNS(
                    SOULSEEK_ANDROID,
                    "android:foregroundServiceType",
                    "dataSync",
                )
                app.appendChild(service)
            }
            val transferService = doc.createElement("service")
            transferService.setAttributeNS(
                SOULSEEK_ANDROID,
                "android:name",
                "app.bitchord.soulseek.BulkTransferService",
            )
            transferService.setAttributeNS(SOULSEEK_ANDROID, "android:exported", "false")
            transferService.setAttributeNS(
                SOULSEEK_ANDROID,
                "android:foregroundServiceType",
                "dataSync",
            )
            transferService.setAttributeNS(SOULSEEK_ANDROID, "android:stopWithTask", "false")
            app.appendChild(transferService)
        }
    }
}

@Suppress("unused")
val nativeSoulseekPatch =
    bytecodePatch(
        name = "Unofficial Soulseek audio",
        description =
            "Adds player-integrated Downloads on BitChord 1.9, addon/Soulseek source choices, batch review, selected-file public Soulseek sharing, and verified Telegram archiving/restore. Telegram copies require the optional Telegram patch and your private main channel. Debrid services use your API keys. Live Soulseek playback stays disabled. Settings are in Patches & tweaks. Android 8 or newer.",
        default = false,
    ) {
        compatibleWith(soulseekTarget)
        dependsOn(soulseekResources, extrasSettingsPatch)
        extendWith("extensions/soulseek.mpe")
        execute {
            val songType = "Lcom/music/bitchord/data/model/Song;"
            // R8 merges this lambda with unrelated callbacks. Identify the manual
            // downloadSong branch by its duration backfill and singleton-list dispatch;
            // albums, cached-folder exports, permission continuations and retries never
            // pass this call site. No queue-wide or stack-trace heuristic is involved.
            val manual =
                mutableClassDefBy("Lcom/dokar/quickjs/QuickJs\$\$ExternalSyntheticLambda0;")
                    .methods
                    .single {
                        it.name == "invoke" &&
                            it.parameterTypes == listOf("Ljava/lang/Object;") &&
                            it.returnType == "Ljava/lang/Object;"
                    }
            check(manual.accessFlags == 17 && manual.implementation!!.registerCount == 48) {
                "Manual single-song download callback changed"
            }
            val manualCode = manual.implementation!!.instructions.toList()
            val durationAt =
                manualCode.indices.single { index ->
                    val call =
                        (manualCode[index] as? ReferenceInstruction)?.reference as? MethodReference
                    call?.definingClass == "Lcom/music/bitchord/data/model/ModelsKt;" &&
                        call.name == "durationMillis" &&
                        call.parameterTypes == listOf(songType) &&
                        call.returnType == "J"
                }
            val dispatchAt =
                ((durationAt + 1) until minOf(durationAt + 100, manualCode.size)).first { index ->
                    val call =
                        (manualCode[index] as? ReferenceInstruction)?.reference as? MethodReference
                    call?.definingClass == "Lkotlin/jvm/functions/Function2;" &&
                        call.name == "invoke" &&
                        call.parameterTypes == listOf("Ljava/lang/Object;", "Ljava/lang/Object;") &&
                        call.returnType == "Ljava/lang/Object;"
                }
            val dispatch = manualCode[dispatchAt] as FiveRegisterInstruction
            val singleton =
                (manualCode[dispatchAt - 2] as? ReferenceInstruction)?.reference as? MethodReference
            check(
                manualCode[dispatchAt].opcode == Opcode.INVOKE_INTERFACE &&
                    dispatch.registerCount == 3 &&
                    manualCode[dispatchAt - 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
                    (manualCode[dispatchAt - 1]
                            as
                            com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction)
                        .registerA == dispatch.registerD &&
                    singleton?.name == "listOf" &&
                    singleton.parameterTypes == listOf("Ljava/lang/Object;") &&
                    singleton.returnType == "Ljava/util/List;" &&
                    singleton.definingClass in
                        setOf("Lkotlin/io/TextStreamsKt;", "Lcoil/util/-Collections;") &&
                    manualCode[dispatchAt + 1].opcode == Opcode.SGET_OBJECT &&
                    manualCode[dispatchAt + 2].opcode == Opcode.RETURN_OBJECT
            ) {
                "Manual single-song singleton dispatch changed"
            }
            manual.replaceInstruction(
                dispatchAt,
                "invoke-static {v${dispatch.registerC}, v${dispatch.registerD}, v${dispatch.registerE}}, ${SOULSEEK}SoulseekDownloadChoice;->offer(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
            )
            val enqueue =
                mutableClassDefBy("Lcom/music/bitchord/download/Downloads;").methods.single {
                    it.name == "enqueue" &&
                        it.parameterTypes ==
                            listOf("Landroid/content/Context;", songType, "Ljava/lang/String;") &&
                        it.returnType == "V"
                }
            check(enqueue.accessFlags and 8 != 0 && enqueue.implementation!!.registerCount == 9) {
                "Download enqueue entry changed"
            }
            val originalEnqueue = enqueue.implementation!!.instructions.first()
            enqueue.addInstructionsWithLabels(
                0,
                """
                invoke-static {p0, p1}, ${SOULSEEK}SoulseekSelectedDownloads;->enqueueOrFalse(Landroid/content/Context;Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :original_soulseek_enqueue
                return-void
                """,
                ExternalLabel("original_soulseek_enqueue", originalEnqueue),
            )
            check(
                classDefBy(songType).methods.any {
                    it.name == "getVideoId" &&
                        it.parameterTypes.isEmpty() &&
                        it.returnType == "Ljava/lang/String;"
                }
            ) {
                "Download song identifier accessor changed"
            }
            val routeType = "Lcom/music/bitchord/download/Downloads\$Route;"
            val routeClass = classDefBy(routeType)
            val routeParameters =
                listOf(
                    "Ljava/lang/String;",
                    "Ljava/lang/String;",
                    "Ljava/lang/String;",
                    "Ljava/lang/String;",
                    "Lcom/google/android/gms/tasks/zzr;",
                    "Lkotlin/jvm/functions/Function3;",
                )
            // 1.9 removed taggable and retained the R8-optimised default-argument mask.
            // Keep the two inspected host ABIs explicit; the runtime mirrors this choice.
            val currentRoute =
                routeClass.methods.any {
                    it.name == "<init>" && it.parameterTypes == routeParameters + "I"
                }
            if (currentRoute) {
                // Preserve the host's route numbers while inserting one visual tab in both bars.
                for ((owner, name) in
                    listOf(
                        "FloatingBottomBarKt" to "FloatingBottomBar",
                        "GlassNavBarKt" to "GlassNavBar",
                    )) {
                    val bar =
                        mutableClassDefBy("Lcom/music/bitchord/ui/components/$owner;")
                            .methods
                            .single {
                                it.name == name &&
                                    it.parameterTypes.take(3) ==
                                        listOf(
                                            "Ljava/util/List;",
                                            "I",
                                            "Lkotlin/jvm/functions/Function1;",
                                        )
                            }
                    check(bar.accessFlags and 8 != 0 && bar.implementation!!.registerCount >= 8) {
                        "Navigation bar ABI changed"
                    }
                    bar.addInstructions(
                        0,
                        """
                        move/from16 v2, p${bar.parameterTypes.lastIndex}
                        and-int/lit16 v2, v2, -0x3ff
                        move/16 p${bar.parameterTypes.lastIndex}, v2
                        move-object/from16 v0, p0
                        move/from16 v1, p1
                        invoke-static {v0, v1}, ${SOULSEEK}DownloadNavigation;->index(Ljava/lang/Object;I)I
                        move-result p1
                        move-object/from16 v1, p2
                        invoke-static {v0, v1}, ${SOULSEEK}DownloadNavigation;->callback(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                        move-result-object p2
                        check-cast p2, Lkotlin/jvm/functions/Function1;
                        invoke-static/range {p0 .. p0}, ${SOULSEEK}DownloadNavigation;->tabs(Ljava/lang/Object;)Ljava/lang/Object;
                        move-result-object p0
                        check-cast p0, Ljava/util/List;
                    """,
                    )
                }

                // The real player content slot retains measured padding, mini player and both bars.
                val library =
                    mutableClassDefBy("Lcom/music/bitchord/ui/screens/LibraryScreenKt;")
                        .methods
                        .single { it.name == "LibraryScreen" }
                check(
                    library.parameterTypes.size == 21 &&
                        library.parameterTypes[14] == "Landroidx/compose/ui/Modifier;" &&
                        library.parameterTypes[15] ==
                            "Landroidx/compose/foundation/layout/PaddingValuesImpl;" &&
                        library.parameterTypes[19] == "Landroidx/compose/runtime/ComposerImpl;" &&
                        library.implementation!!.registerCount == 46
                ) {
                    "Player content slot ABI changed"
                }
                val topBar =
                    mutableClassDefBy("Lcom/music/bitchord/ui/components/FrostedTopBarKt;")
                        .methods
                        .single { it.name == "FrostedTopBar" }
                check(
                    topBar.parameterTypes[2] == "Landroidx/compose/ui/Modifier;" &&
                        topBar.implementation!!.registerCount == 56
                ) {
                    "Top-bar modifier ABI changed"
                }
                topBar.addInstructions(
                    0,
                    """
                    invoke-static/range {p2 .. p2}, ${SOULSEEK}DownloadsShell;->chromeModifier(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v0
                    check-cast v0, Landroidx/compose/ui/Modifier;
                    move-object/from16 p2, v0
                """,
                )
                val libraryCode = library.implementation!!.instructions.toList()
                val groupStart =
                    libraryCode.indices.single { index ->
                        val call =
                            (libraryCode[index] as? ReferenceInstruction)?.reference
                                as? MethodReference
                        call?.definingClass == "Landroidx/compose/runtime/ComposerImpl;" &&
                            call.name == "startRestartGroup"
                    }
                val groupEnd =
                    libraryCode.single { instruction ->
                        val call =
                            (instruction as? ReferenceInstruction)?.reference as? MethodReference
                        call?.definingClass == "Landroidx/compose/runtime/ComposerImpl;" &&
                            call.name == "endRestartGroup"
                    }
                // Keep the host's restart scope and its original captured callback.
                // Reading route state before that scope loses the subscription, and
                // returning early bypasses the host's balanced composition lifecycle.
                // v16..v18 are unused at this point; v0..v3 hold live host arguments.
                check(
                    libraryCode.take(groupStart + 1).none { instruction ->
                        instruction is
                            com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction &&
                            instruction.registerA in 16..18
                    }
                ) {
                    "Library content scratch registers changed"
                }
                library.addInstructionsWithLabels(
                    groupStart + 1,
                    """
                    move-object/from16 v16, p14
                    move-object/from16 v17, p15
                    move-object/from16 v18, p19
                    invoke-static/range {v16 .. v18}, ${SOULSEEK}DownloadsShell;->compose(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z
                    move-result v16
                    if-eqz v16, :original_library_content
                    move-object/from16 v15, p14
                    move/from16 v19, p18
                    goto/16 :finish_library_group
                """,
                    ExternalLabel("original_library_content", libraryCode[groupStart + 1]),
                    ExternalLabel("finish_library_group", groupEnd),
                )
                val root =
                    mutableClassDefBy("Lcom/music/bitchord/MainActivityKt;").methods.single {
                        it.name.startsWith("BitChordApp-")
                    }
                val headingReads =
                    root.implementation!!.instructions.toList().indices.filter { index ->
                        val instruction = root.implementation!!.instructions.elementAt(index)
                        val field =
                            (instruction as? ReferenceInstruction)?.reference as? FieldReference
                        instruction.opcode == Opcode.IGET_OBJECT &&
                            field?.definingClass ==
                                "Lcom/music/bitchord/ui/components/BottomTab;" &&
                            field.name == "label"
                    }
                check(headingReads.size == 2) { "Player page heading changed" }
                for (index in headingReads.reversed()) {
                    val register =
                        (root.implementation!!.instructions.elementAt(index)
                                as TwoRegisterInstruction)
                            .registerA
                    root.addInstructions(
                        index + 1,
                        """
                        invoke-static/range {v$register .. v$register}, ${SOULSEEK}DownloadsShell;->heading(Ljava/lang/String;)Ljava/lang/String;
                        move-result-object v$register
                    """,
                    )
                }
                check(
                    classDefBy("Lcoil/util/DrawableUtils;").methods.any {
                        it.name == "BackHandler" &&
                            it.parameterTypes ==
                                listOf(
                                    "Z",
                                    "Lkotlin/jvm/functions/Function0;",
                                    "Landroidx/compose/runtime/ComposerImpl;",
                                    "I",
                                    "I",
                                )
                    }
                ) {
                    "Player Compose back handler changed"
                }
                // Exact R8 branch for the manual release/selection callback; sync/retry paths stay
                // independent.
                val batch =
                    mutableClassDefBy(
                            "Lcom/music/bitchord/MainActivityKt\$\$ExternalSyntheticLambda109;"
                        )
                        .methods
                        .single {
                            it.name == "invoke" &&
                                it.parameterTypes ==
                                    listOf("Ljava/lang/Object;", "Ljava/lang/Object;")
                        }
                check(
                    batch.implementation!!.instructions.any { instruction ->
                        val method =
                            (instruction as? ReferenceInstruction)?.reference as? MethodReference
                        method?.definingClass == "Lcom/music/bitchord/download/Downloads;" &&
                            method.name == "rememberCollection"
                    }
                ) {
                    "Manual batch callback changed"
                }
                val batchStart = batch.implementation!!.instructions.first()
                batch.addInstructionsWithLabels(
                    0,
                    """
                    move-object/from16 v0, p0
                    iget v0, v0, Lcom/music/bitchord/MainActivityKt${'$'}${'$'}ExternalSyntheticLambda109;->${'$'}r8${'$'}classId:I
                    const/4 v1, 0x1
                    if-ne v0, v1, :original_manual_batch
                    invoke-static/range {p0 .. p2}, ${SOULSEEK}SoulseekDownloadChoice;->interceptBatch(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z
                    move-result v0
                    if-eqz v0, :original_manual_batch
                    sget-object v0, Lkotlin/Unit;->INSTANCE:Lkotlin/Unit;
                    return-object v0
                """,
                    ExternalLabel("original_manual_batch", batchStart),
                )
                val fallback =
                    mutableClassDefBy("Lcom/music/bitchord/download/Downloads;").methods.single {
                        it.name == "fromSources"
                    }
                check(
                    fallback.parameterTypes.first() == songType &&
                        fallback.returnType == "Ljava/lang/Object;"
                ) {
                    "Download fallback ABI changed"
                }
                val originalFallback = fallback.implementation!!.instructions.first()
                fallback.addInstructionsWithLabels(
                    0,
                    """
                    invoke-static/range {p1 .. p1}, ${SOULSEEK}HostDownloadAccess;->forbidFallback(Ljava/lang/Object;)Z
                    move-result v0
                    if-eqz v0, :unselected_download_fallback
                    const/4 v0, 0x0
                    return-object v0
                """,
                    ExternalLabel("unselected_download_fallback", originalFallback),
                )
            }
            check(
                routeClass.methods.any {
                    it.name == "<init>" && it.parameterTypes == routeParameters
                } || currentRoute
            ) {
                "Download route constructor changed"
            }
            check(
                (currentRoute ||
                    routeClass.fields.any { it.name == "taggable" && it.type == "Z" }) &&
                    routeClass.fields.any {
                        it.name == "write" &&
                            it.type == "Lkotlin/coroutines/jvm/internal/SuspendLambda;"
                    }
            ) {
                "Download route writer or metadata policy changed"
            }
            check(
                classDefBy("Lkotlin/coroutines/jvm/internal/SuspendLambda;").methods.any {
                    it.name == "<init>" &&
                        it.parameterTypes == listOf("I", "Lkotlin/coroutines/Continuation;")
                }
            ) {
                "Download coroutine writer constructor changed"
            }
            check(
                classDefBy("Lcom/music/bitchord/data/sources/SourceRegistry;").methods.any {
                    it.name == "config" &&
                        it.parameterTypes == listOf("Ljava/lang/String;") &&
                        it.returnType == "Lcom/music/bitchord/data/sources/SourceConfig;" &&
                        it.accessFlags and 8 != 0
                }
            ) {
                "Download source configuration lookup changed"
            }
            check(
                classDefBy("Lcom/music/bitchord/data/sources/SourceConfig;").methods.any {
                    it.name == "getEnabled" && it.parameterTypes.isEmpty() && it.returnType == "Z"
                }
            ) {
                "Download source enablement accessor changed"
            }
            val selectedRoute =
                mutableClassDefBy("Lcom/music/bitchord/download/Downloads;").methods.single {
                    it.name == "access\$routeFor" &&
                        it.parameterTypes ==
                            listOf(
                                "Landroid/content/Context;",
                                songType,
                                "Lcom/music/bitchord/data/settings/DownloadQuality;",
                                "Lkotlin/coroutines/jvm/internal/ContinuationImpl;",
                            ) &&
                        it.returnType == "Ljava/lang/Object;"
                }
            check(
                selectedRoute.accessFlags and 8 != 0 &&
                    selectedRoute.implementation!!.registerCount == if (currentRoute) 18 else 25
            ) {
                "Explicit download route registers changed"
            }
            val originalRoute = selectedRoute.implementation!!.instructions.first()
            selectedRoute.addInstructionsWithLabels(
                0,
                """
                move-object/from16 v0, p1
                if-eqz v0, :original_soulseek_download_route
                invoke-virtual {v0}, $songType->getVideoId()Ljava/lang/String;
                move-result-object v0
                invoke-static {v0}, ${SOULSEEK}SoulseekDownloadRoute;->routeOrNull(Ljava/lang/String;)Ljava/lang/Object;
                move-result-object v0
                if-eqz v0, :original_soulseek_download_route
                check-cast v0, $routeType
                return-object v0
                """,
                ExternalLabel("original_soulseek_download_route", originalRoute),
            )

            val tagger =
                mutableClassDefBy("Lcom/music/bitchord/download/MediaTagger;").methods.single {
                    it.name == "embed\$app_prodRelease" &&
                        it.parameterTypes ==
                            listOf(
                                "Landroid/content/Context;",
                                "Landroid/net/Uri;",
                                songType,
                                "Ljava/lang/String;",
                                "Lcom/music/bitchord/download/LyricsTag\$Embeddable;",
                                "Lcom/music/bitchord/download/MediaTagger\$Artwork;",
                                "Lkotlin/coroutines/jvm/internal/ContinuationImpl;",
                            ) &&
                        it.returnType == "Ljava/lang/Object;"
                }
            check(tagger.accessFlags and 8 == 0 && tagger.implementation!!.registerCount == 13) {
                "Download metadata writer registers changed"
            }
            val originalTagger = tagger.implementation!!.instructions.first()
            tagger.addInstructionsWithLabels(
                0,
                """
                move-object/from16 v0, p3
                if-eqz v0, :original_soulseek_download_tags
                invoke-virtual {v0}, $songType->getVideoId()Ljava/lang/String;
                move-result-object v0
                invoke-static {v0}, ${SOULSEEK}SoulseekDownloadRoute;->ownsTrack(Ljava/lang/String;)Z
                move-result v0
                if-eqz v0, :original_soulseek_download_tags
                sget-object v0, Lkotlin/Unit;->INSTANCE:Lkotlin/Unit;
                return-object v0
                """,
                ExternalLabel("original_soulseek_download_tags", originalTagger),
            )

            // Manual catalog searches pass null; automatic playback/download matching is excluded.
            val source = mutableClassDefBy("Lcom/music/bitchord/data/sources/AddonSource;")
            check(
                source.fields.any {
                    it.name == "config" &&
                        it.type == "Lcom/music/bitchord/data/sources/SourceConfig;"
                }
            ) {
                "Addon source config field changed"
            }
            val search =
                source.methods.single {
                    it.name == "search" &&
                        it.parameterTypes ==
                            listOf(
                                "Ljava/lang/String;",
                                "I",
                                "Z",
                                "Lcom/music/bitchord/data/sources/StreamRequest;",
                                "Lkotlin/coroutines/Continuation;",
                            ) &&
                        it.returnType == "Ljava/lang/Object;"
                }
            check(search.implementation!!.registerCount == 13) {
                "Addon source search registers changed"
            }
            val originalSearch = search.implementation!!.instructions.first()
            search.addInstructionsWithLabels(
                0,
                """
                if-eqz p4, :original_soulseek_search
                iget-object v0, p0, Lcom/music/bitchord/data/sources/AddonSource;->config:Lcom/music/bitchord/data/sources/SourceConfig;
                invoke-virtual {v0}, Lcom/music/bitchord/data/sources/SourceConfig;->getId()Ljava/lang/String;
                move-result-object v0
                const-string v1, "soulseek-native"
                invoke-virtual {v1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :original_soulseek_search
                invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;
                move-result-object v0
                return-object v0
                """,
                ExternalLabel("original_soulseek_search", originalSearch),
            )

            // Each native source returns null for identifiers it does not own. Keeping the
            // original entry as an external label makes this chain with Telegram in either order.
            val addonType = "Lcom/music/bitchord/download/Downloads\$run\$2;"
            val body =
                mutableClassDefBy(addonType).methods.single {
                    it.name ==
                        "invokeSuspend\$com\$music\$bitchord\$data\$sources\$addon\$AddonClient\$body\$2" &&
                        it.parameterTypes == listOf("Ljava/lang/Object;") &&
                        it.returnType == "Ljava/lang/Object;"
                }
            check(body.implementation!!.registerCount == 14) { "Addon IO registers changed" }
            check(
                body.implementation!!.instructions.any {
                    val ref = (it as? ReferenceInstruction)?.reference as? FieldReference
                    ref?.definingClass == addonType &&
                        ref.name == "L\$0" &&
                        ref.type == "Ljava/lang/Object;"
                }
            ) {
                "Addon URL capture changed"
            }
            val externalBody = body.implementation!!.instructions.first()
            body.addInstructionsWithLabels(
                0,
                """
                iget-object v0, p0, $addonType->L${'$'}0:Ljava/lang/Object;
                check-cast v0, Ljava/lang/String;
                invoke-static {v0}, ${SOULSEEK}SoulseekAddon;->bodyOrNull(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0
                if-eqz v0, :external_soulseek_metadata
                return-object v0
                """,
                ExternalLabel("external_soulseek_metadata", externalBody),
            )

            val constructor =
                mutableClassDefBy("Landroidx/media3/datasource/ResolvingDataSource;")
                    .methods
                    .single { it.name == "<init>" }
            check(constructor.parameterTypes.first() == "Landroidx/media3/datasource/DataSource;")
            val superCall =
                constructor.implementation!!.instructions.indexOfFirst {
                    val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
                    ref?.definingClass == "Ljava/lang/Object;" && ref.name == "<init>"
                }
            check(superCall >= 0)
            constructor.addInstructions(
                superCall + 1,
                """
                invoke-static {p1}, ${SOULSEEK}SoulseekDataSource;->wrap(Landroidx/media3/datasource/DataSource;)Landroidx/media3/datasource/DataSource;
                move-result-object p1
                """,
            )

            // The host still owns the IO coroutine, output stream, progress and cancellation.
            val downloadType = "Lcom/music/bitchord/wear/WearBridge\$sendToWatch\$1;"
            val download =
                mutableClassDefBy(downloadType).methods.single { it.name == "invokeSuspend" }
            val code = download.implementation!!.instructions.toList()
            check(download.implementation!!.registerCount == if (currentRoute) 27 else 29) {
                "Download IO registers changed"
            }
            val anchors =
                code.indices.filter { index ->
                    code[index].opcode == Opcode.NEW_INSTANCE &&
                        (code[index] as? ReferenceInstruction)?.reference.toString() ==
                            "Lokhttp3/Request\$Builder;" &&
                        code.subList(index, minOf(index + 140, code.size)).any {
                            ((it as? ReferenceInstruction)?.reference as? StringReference)
                                ?.string == "fetched "
                        }
                }
            check(anchors.size == 1) { "Native download insertion point changed" }
            val downloadIndex = anchors.single()
            // Both supported APKs use v1 for this merged coroutine and v0 for its IO scope
            // at this branch. Validate those live values before reusing its scratch locals.
            val scopeRead =
                code
                    // An independently selected Telegram hook may precede this same HTTP site.
                    .subList(maxOf(0, downloadIndex - 32), downloadIndex)
                    .filterIsInstance<ReferenceInstruction>()
            check(
                scopeRead.any {
                    val field = it.reference as? FieldReference
                    it.opcode == Opcode.IGET_OBJECT &&
                        field?.definingClass == downloadType &&
                        field.name == "L\$0" &&
                        (it as TwoRegisterInstruction).registerA == 0 &&
                        it.registerB == 1
                } &&
                    scopeRead.any {
                        it.opcode == Opcode.CHECK_CAST &&
                            it.reference.toString() == "Lkotlinx/coroutines/CoroutineScope;"
                    }
            ) {
                "Download IO coroutine scope changed"
            }
            for ((fieldName, castType) in
                listOf(
                    "\$path" to "Ljava/lang/String;",
                    "\$app" to "Ljava/io/OutputStream;",
                    "\$payload" to "Lkotlin/jvm/functions/Function2;",
                )) {
                check(
                    (downloadIndex until minOf(downloadIndex + 40, code.size - 1)).any { index ->
                        val field =
                            (code[index] as? ReferenceInstruction)?.reference as? FieldReference
                        code[index].opcode == Opcode.IGET_OBJECT &&
                            field?.definingClass == downloadType &&
                            field.name == fieldName &&
                            (code[index] as TwoRegisterInstruction).registerB == 1 &&
                            code[index + 1].opcode == Opcode.CHECK_CAST &&
                            (code[index + 1] as ReferenceInstruction).reference.toString() ==
                                castType
                    }
                ) {
                    "Download IO capture changed: $fieldName"
                }
            }
            check(
                classDefBy("Lkotlinx/coroutines/JobKt;").methods.any {
                    it.name == "ensureActive" &&
                        it.parameterTypes == listOf("Lkotlin/coroutines/CoroutineContext;")
                }
            ) {
                "Download cancellation helper changed"
            }
            // Keep native dispatchers in one chain in either selection order.
            // The existing handler's fallback already targets the HTTP builder;
            // inserting after it would make that branch skip this handler.
            val nativeDispatches =
                (maxOf(0, downloadIndex - 40) until downloadIndex - 2).filter { index ->
                    val read = code[index] as? TwoRegisterInstruction
                    val field = (code[index] as? ReferenceInstruction)?.reference as? FieldReference
                    val cast = code[index + 1]
                    val call = code[index + 2]
                    val target = (call as? ReferenceInstruction)?.reference as? MethodReference
                    code[index].opcode == Opcode.IGET_OBJECT &&
                        field?.definingClass == downloadType &&
                        field.name == "\$path" &&
                        read?.registerA == 4 &&
                        read.registerB == 1 &&
                        cast.opcode == Opcode.CHECK_CAST &&
                        (cast
                                as
                                com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction)
                            .registerA == 4 &&
                        (cast as ReferenceInstruction).reference.toString() ==
                            "Ljava/lang/String;" &&
                        call.opcode == Opcode.INVOKE_STATIC &&
                        target?.definingClass == "Lapp/bitchord/telegram/InternalAddon;" &&
                        target.name == "reserved" &&
                        target.parameterTypes == listOf("Ljava/lang/String;") &&
                        target.returnType == "Z" &&
                        (call as FiveRegisterInstruction).registerCount == 1 &&
                        call.registerC == 4
                }
            check(nativeDispatches.size <= 1) { "Existing native download dispatch is not unique" }
            val nativeDownloadIndex = nativeDispatches.singleOrNull() ?: downloadIndex
            download.addInstructionsWithLabels(
                nativeDownloadIndex,
                """
                iget-object v4, v1, $downloadType->${'$'}path:Ljava/lang/Object;
                check-cast v4, Ljava/lang/String;
                invoke-static {v4}, ${SOULSEEK}SoulseekAddon;->reserved(Ljava/lang/String;)Z
                move-result v5
                if-eqz v5, :external_soulseek_download
                iget-object v5, v1, $downloadType->${'$'}app:Ljava/lang/Object;
                check-cast v5, Ljava/io/OutputStream;
                iget-object v6, v1, $downloadType->${'$'}payload:Ljava/lang/Object;
                check-cast v6, Lkotlin/jvm/functions/Function2;
                invoke-interface {v0}, Lkotlinx/coroutines/CoroutineScope;->getCoroutineContext()Lkotlin/coroutines/CoroutineContext;
                move-result-object v7
                invoke-static {v4, v5, v6, v7}, ${SOULSEEK}SoulseekDownloads;->fetch(Ljava/lang/String;Ljava/io/OutputStream;Lkotlin/jvm/functions/Function2;Lkotlin/coroutines/CoroutineContext;)Ljava/lang/Long;
                move-result-object v4
                return-object v4
                """,
                ExternalLabel("external_soulseek_download", code[nativeDownloadIndex]),
            )

            // Registering as an ADDON gives the source its normal Sources row. Intercept only
            // that row's editor callback; other sources retain their own editor or wrapper.
            val row =
                mutableClassDefBy(
                    "Lcom/music/bitchord/ui/screens/SourcesScreenKt\$\$ExternalSyntheticLambda32;"
                )
            val invoke = row.methods.single { it.name == "invoke" && it.parameterTypes.size == 5 }
            val rowCode = invoke.implementation!!.instructions.toList()
            val decision =
                rowCode.indexOfFirst {
                    val ref = (it as? ReferenceInstruction)?.reference as? FieldReference
                    ref?.definingClass == "Lcom/music/bitchord/data/sources/SourceKind;" &&
                        ref.name == "needsServer"
                }
            check(decision >= 0) { "Source-row click decision missing" }
            val load =
                (decision + 1 until minOf(decision + 8, rowCode.size)).single {
                    val ref = (rowCode[it] as? ReferenceInstruction)?.reference as? FieldReference
                    rowCode[it].opcode == Opcode.IGET_OBJECT &&
                        ref?.definingClass == row.type &&
                        ref.type == "Lkotlin/jvm/functions/Function1;"
                }
            val editor = (rowCode[load] as TwoRegisterInstruction).registerA
            val click =
                (load + 1 until minOf(load + 24, rowCode.size)).single {
                    val ref = (rowCode[it] as? ReferenceInstruction)?.reference as? MethodReference
                    rowCode[it].opcode == Opcode.INVOKE_DIRECT &&
                        ref?.name == "<init>" &&
                        ref.parameterTypes ==
                            listOf("Ljava/lang/Object;", "Ljava/lang/Object;", "I")
                }
            check(editor < 16 && (rowCode[click] as FiveRegisterInstruction).registerD == editor) {
                "Source-row callback registers changed"
            }
            invoke.addInstructions(
                load + 1,
                """
                invoke-static {v$editor}, ${SOULSEEK}SoulseekSourceEditorRoute;->wrap(Lkotlin/jvm/functions/Function1;)Lkotlin/jvm/functions/Function1;
                move-result-object v$editor
                """,
            )

            val startup = SoulseekApplicationCreate.method
            val returns =
                startup.implementation!!
                    .instructions
                    .withIndex()
                    .filter { it.value.opcode == Opcode.RETURN_VOID }
                    .map { it.index }
            check(returns.isNotEmpty()) { "Application startup hook missing" }
            for (index in returns.reversed()) startup.addInstructions(
                index,
                """
                invoke-static/range {p0 .. p0}, ${SOULSEEK}SoulseekBridge;->boot(Landroid/content/Context;)V
                invoke-static/range {p0 .. p0}, ${SOULSEEK}HostDownloadAccess;->init(Landroid/content/Context;)V
                """,
            )
            println(
                "Installed download-only Soulseek source, selected-file route and playback guard"
            )
        }
    }

@Suppress("unused")
val streamseekPreviewPatch =
    bytecodePatch(
        name = "Streamseek previews",
        description =
            "Preview an explicitly selected Soulseek MP3 or FLAC through Media3 while it transfers. Keep download reuses buffered bytes. Compare an excerpt with a known local reference and export transparent evidence. Optional; enable in Patches & tweaks. No automatic Soulseek playback or Mornye integration.",
        default = false,
    ) {
        compatibleWith(
            Compatibility(
                name = "BitChord",
                packageName = "com.music.bitchord",
                apkFileType = ApkFileType.APK,
                appIconColor = 0xFF8944,
                targets = listOf(AppTarget(version = "1.9", isExperimental = true, minSdk = 26)),
            )
        )
        dependsOn(nativeSoulseekPatch, streamseekPreviewResources)
        execute {
            val method =
                mutableClassDefBy("${SOULSEEK}SoulseekPreview;").methods.single {
                    it.name == "installed" && it.parameterTypes.isEmpty() && it.returnType == "Z"
                }
            check(method.implementation!!.instructions.count() == 2) {
                "Preview install marker changed"
            }
            method.replaceInstruction(0, "const/4 v0, 0x1")
            println(
                "Enabled explicit Streamseek preview capabilities; automatic playback guard retained"
            )
        }
    }

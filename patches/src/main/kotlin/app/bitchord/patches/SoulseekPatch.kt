package app.bitchord.patches

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
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
        targets = listOf(AppTarget(version = "1.8.1", isExperimental = true, minSdk = 26)),
    )

private object SoulseekApplicationCreate :
    Fingerprint(
        definingClass = "Lcom/music/bitchord/BitChordApplication;",
        name = "onCreate",
        parameters = emptyList(),
        returnType = "V",
    )

private class SoulseekPatchMarker

private val soulseekResources = resourcePatch {
    execute {
        val abis =
            listApkEntries("lib/").filter { it.endsWith(".so") }.map { it.split('/')[1] }.toSet()
        check(abis.isNotEmpty() && abis.all { it in setOf("arm64-v8a", "armeabi-v7a", "x86_64") }) {
            "Unsupported Soulseek APK architecture: $abis"
        }
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
                    "NostrSetupActivity",
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
            "Download selected Soulseek or debrid audio to a phone folder, a configured Telegram main channel, or both. Optional debrid services use your own API keys. Choose accepted formats and their order in setup. Live playback and automatic source matching are disabled. Settings are in Settings, Patches & tweaks. Android 8 or newer.",
        default = false,
    ) {
        compatibleWith(soulseekTarget)
        dependsOn(soulseekResources, extrasSettingsPatch)
        extendWith("extensions/soulseek.mpe")
        execute {
            val songType = "Lcom/music/bitchord/data/model/Song;"
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
            check(
                routeClass.methods.any {
                    it.name == "<init>" &&
                        it.parameterTypes ==
                            listOf(
                                "Ljava/lang/String;",
                                "Ljava/lang/String;",
                                "Ljava/lang/String;",
                                "Ljava/lang/String;",
                                "Lcom/google/android/gms/tasks/zzr;",
                                "Lkotlin/jvm/functions/Function3;",
                            )
                }
            ) {
                "Download route constructor changed"
            }
            check(
                routeClass.fields.any { it.name == "taggable" && it.type == "Z" } &&
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
                    selectedRoute.implementation!!.registerCount == 25
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
            check(download.implementation!!.registerCount == 29) { "Download IO registers changed" }
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
            check(
                classDefBy("Lkotlinx/coroutines/JobKt;").methods.any {
                    it.name == "ensureActive" &&
                        it.parameterTypes == listOf("Lkotlin/coroutines/CoroutineContext;")
                }
            ) {
                "Download cancellation helper changed"
            }
            download.addInstructionsWithLabels(
                downloadIndex,
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
                ExternalLabel("external_soulseek_download", code[downloadIndex]),
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
            for (index in returns.reversed()) startup.addInstruction(
                index,
                "invoke-static/range {p0 .. p0}, ${SOULSEEK}SoulseekBridge;->boot(Landroid/content/Context;)V",
            )
            println(
                "Installed download-only Soulseek source, selected-file route and playback guard"
            )
        }
    }

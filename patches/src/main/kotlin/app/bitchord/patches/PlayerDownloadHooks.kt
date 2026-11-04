package app.bitchord.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.*
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.*

private const val DOWNLOAD_ENTRY = "Lapp/bitchord/telegram/PlayerDownload;"
private const val DOWNLOAD_COMPOSER = "Landroidx/compose/runtime/ComposerImpl;"
private const val DOWNLOAD_MODIFIER = "Landroidx/compose/ui/Modifier;"
private const val DOWNLOAD_FUNCTION = "Lkotlin/jvm/functions/Function1;"
private const val DOWNLOAD_LOCAL = "Landroidx/compose/runtime/ProvidableCompositionLocal;"
private const val DOWNLOAD_COLOR = "Landroidx/compose/ui/graphics/Color;"
private const val DOWNLOAD_BOOLEAN = "Ljava/lang/Boolean;"
private const val DOWNLOAD_SONG = "Lcom/music/bitchord/data/model/Song;"
private const val DOWNLOAD_LAYOUT_CHANGED = "Player title download layout changed"

private fun Instruction.downloadCall() =
    (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.downloadField() =
    (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Instruction.downloadString() =
    ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

private fun Instruction.downloadType() =
    ((this as? ReferenceInstruction)?.reference as? TypeReference)?.type

private fun Method.downloadCode() = implementation?.instructions?.toList().orEmpty()

private fun MethodReference.downloadText() =
    "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun FieldReference.downloadText() = "$definingClass->$name:$type"

private fun MethodReference.downloadTakes(vararg types: String) =
    parameterTypes.map { it.toString() } == types.toList()

private fun Instruction.downloadRegisters(): List<Int> =
    when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is FiveRegisterInstruction ->
            listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> error("$DOWNLOAD_LAYOUT_CHANGED: call encoding")
    }

private fun <T> Collection<T>.downloadSingle(what: String): T {
    check(size == 1) { "$DOWNLOAD_LAYOUT_CHANGED: $what ($size found)" }
    return single()
}

internal data class PlayerDownloadSite(
    val method: Method,
    val insertion: Int,
    val composer: Int,
    val symbols: Map<String, String>,
)

/** The title row's spacer precedes the account/local-file condition around its star. */
internal fun locatePlayerDownload(all: List<ClassDef>): PlayerDownloadSite {
    val methods = all.flatMap { it.methods }
    val circle =
        methods
            .filter {
                val strings = it.downloadCode().mapNotNull { i -> i.downloadString() }
                "glyphDisc" in strings && "playerMenuGlyph" in strings
            }
            .downloadSingle("native circular action")
    check(
        circle.parameterTypes.map { it.toString() } ==
            listOf(
                "Landroidx/compose/ui/graphics/vector/ImageVector;",
                "Ljava/lang/String;",
                "Lkotlin/jvm/functions/Function0;",
                "Z",
                "Lcom/music/bitchord/ui/haptics/Haptic;",
                DOWNLOAD_MODIFIER,
                DOWNLOAD_COMPOSER,
                "I",
                "I",
            )
    ) {
        "$DOWNLOAD_LAYOUT_CHANGED: native circular action signature"
    }
    val title =
        methods
            .filter { method ->
                val code = method.downloadCode()
                code.any { it.downloadString() == "playerCredits" } &&
                    code.count { it.downloadCall()?.downloadText() == circle.downloadText() } == 2
            }
            .downloadSingle("title row with favorite and menu")
    val code = title.downloadCode()
    val (insertion, composer) = playerDownloadTitleAnchor(code, circle)
    return PlayerDownloadSite(title, insertion, composer, downloadSymbols(all, circle, code))
}

internal fun playerDownloadTitleAnchor(
    code: List<Instruction>,
    circle: MethodReference,
): Pair<Int, Int> {
    val firstCircle =
        code.indexOfFirst { it.downloadCall()?.downloadText() == circle.downloadText() }
    val local =
        (0 until firstCircle)
            .filter { i ->
                code[i].downloadCall()?.let {
                    it.definingClass == DOWNLOAD_SONG &&
                        it.name == "getLocalUri" &&
                        it.parameterTypes.isEmpty() &&
                        it.returnType == "Ljava/lang/String;"
                } == true
            }
            .lastOrNull() ?: error("$DOWNLOAD_LAYOUT_CHANGED: local-file favorite condition")
    check(local >= 2 && local + 2 < code.size) {
        "$DOWNLOAD_LAYOUT_CHANGED: favorite condition bounds"
    }
    check(
        code[local - 1].opcode == Opcode.IF_NEZ &&
            code[local + 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
            code[local + 2].opcode == Opcode.IF_EQZ
    ) {
        "$DOWNLOAD_LAYOUT_CHANGED: account/local-file favorite condition"
    }
    val prior = local - 2
    val spacer =
        if (
            code[prior].downloadCall()?.let {
                it.definingClass == DOWNLOAD_ENTRY && it.name == "compose"
            } == true
        )
            prior - 1
        else prior
    check(
        code[spacer].downloadCall()?.let {
            it.name == "Spacer" &&
                it.downloadTakes(DOWNLOAD_COMPOSER, DOWNLOAD_MODIFIER) &&
                it.returnType == "V"
        } == true
    ) {
        "$DOWNLOAD_LAYOUT_CHANGED: title-to-actions spacer"
    }
    check(
        (local until firstCircle).any { i ->
            code[i].downloadField()?.let {
                it.definingClass == "Lcom/music/bitchord/data/model/LikeStatus;" &&
                    it.name == "LIKE"
            } == true
        } &&
            (local until firstCircle).any { i ->
                code[i].downloadField()?.name == "remove_from_liked\$delegate"
            }
    ) {
        "$DOWNLOAD_LAYOUT_CHANGED: favorite behavior"
    }
    val composer = code[spacer].downloadRegisters().first()
    val circleComposer = code[firstCircle].downloadRegisters()[6]
    val composerCopy =
        code.subList(maxOf(local, firstCircle - 20), firstCircle).lastOrNull {
            (it as? OneRegisterInstruction)?.registerA == circleComposer
        } as? TwoRegisterInstruction
    check(
        circleComposer == composer ||
            (composerCopy != null &&
                composerCopy.registerB == composer &&
                composerCopy.opcode in
                    setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16))
    ) {
        "$DOWNLOAD_LAYOUT_CHANGED: title composer"
    }
    val (targets, jumps) = branchTargets(code)
    val signedTarget = jumps[local - 1]
    check(
        signedTarget != null &&
            signedTarget in local + 3 until firstCircle &&
            (spacer + 1) !in targets
    ) {
        "$DOWNLOAD_LAYOUT_CHANGED: favorite branch boundaries"
    }

    return Pair(spacer + 1, composer)
}

private fun downloadSymbols(
    all: List<ClassDef>,
    circle: Method,
    title: List<Instruction>,
): Map<String, String> {
    val symbols = linkedMapOf<String, String>()
    val statics = all.flatMap { c -> c.methods.filter { it.accessFlags and 8 != 0 } }
    fun static(what: String, result: String, vararg args: String): Method =
        statics.filter { it.returnType == result && it.downloadTakes(*args) }.downloadSingle(what)
    symbols["androidView"] =
        static(
                "Compose view interop",
                "V",
                DOWNLOAD_FUNCTION,
                DOWNLOAD_MODIFIER,
                DOWNLOAD_FUNCTION,
                DOWNLOAD_COMPOSER,
                "I",
                "I",
            )
            .downloadText()
    symbols["companion"] =
        title
            .mapNotNull { it.downloadField() }
            .filter { it.type == "Landroidx/compose/ui/Modifier\$Companion;" }
            .map { it.downloadText() }
            .toSet()
            .downloadSingle("Modifier companion")
    symbols["size"] =
        statics
            .filter {
                it.name.startsWith("size-") &&
                    it.returnType == DOWNLOAD_MODIFIER &&
                    it.downloadTakes(DOWNLOAD_MODIFIER, "F")
            }
            .downloadSingle("square size helper")
            .downloadText()
    for ((key, name, args) in
        listOf(
            Triple("startGroup", "startReplaceGroup", listOf("I")),
            Triple("endGroup", "endReplaceGroup", emptyList()),
        )) symbols[key] =
        title
            .mapNotNull { it.downloadCall() }
            .filter {
                it.definingClass == DOWNLOAD_COMPOSER &&
                    it.name == name &&
                    it.parameterTypes.map { p -> p.toString() } == args
            }
            .map { it.downloadText() }
            .toSet()
            .downloadSingle("composer $name")

    val glyph = circle.downloadCode()
    val locals =
        glyph.indices
            .filter { i ->
                glyph[i].downloadCall()?.let {
                    it.definingClass == DOWNLOAD_COMPOSER &&
                        it.name == "consume" &&
                        it.downloadTakes(DOWNLOAD_LOCAL)
                } == true
            }
            .mapNotNull { i ->
                val field =
                    (maxOf(0, i - 3) until i)
                        .mapNotNull { glyph[it].downloadField() }
                        .lastOrNull { it.type.endsWith("CompositionLocal;") }
                val cast =
                    (i + 1..minOf(glyph.lastIndex, i + 3)).firstNotNullOfOrNull { j ->
                        glyph[j].downloadType()?.takeIf {
                            it == DOWNLOAD_COLOR || it == DOWNLOAD_BOOLEAN
                        }
                    }
                if (field == null || cast == null) null
                else Triple(field, cast, glyph[i].downloadCall()!!)
            }
    check(locals.size <= 1) { "$DOWNLOAD_LAYOUT_CHANGED: ambiguous native action color" }
    if (locals.isNotEmpty()) {
        val (field, cast, consume) = locals.single()
        symbols["consume"] = consume.downloadText()
        symbols[if (cast == DOWNLOAD_COLOR) "tint" else "chrome"] = field.downloadText()
        if (cast == DOWNLOAD_BOOLEAN) {
            val vibrant =
                glyph
                    .mapNotNull { it.downloadCall() }
                    .filter {
                        it.definingClass == field.definingClass &&
                            it.downloadTakes(DOWNLOAD_MODIFIER, "Z") &&
                            it.returnType == DOWNLOAD_MODIFIER
                    }
                    .map { it.downloadText() }
                    .toSet()
                    .downloadSingle("native action chrome")
            val wrapper =
                statics
                    .filter { it.downloadText() == vibrant }
                    .downloadSingle("native action chrome body")
            symbols["blend"] =
                wrapper
                    .downloadCode()
                    .mapNotNull { it.downloadCall() }
                    .filter {
                        it.definingClass == field.definingClass &&
                            it.downloadText() != vibrant &&
                            it.downloadTakes(DOWNLOAD_MODIFIER, "Z") &&
                            it.returnType == DOWNLOAD_MODIFIER
                    }
                    .map { it.downloadText() }
                    .toSet()
                    .downloadSingle("native additive blend")
        }
    }
    return symbols
}

internal fun BytecodePatchContext.addPlayerDownloadHooks() {
    val all = mutableListOf<ClassDef>()
    classDefForEach { all += it }
    val site = locatePlayerDownload(all)
    validatePlayerDownloadBackend(all)
    val title =
        mutableClassDefBy(site.method.definingClass).methods.single {
            it.name == site.method.name &&
                it.parameterTypes == site.method.parameterTypes &&
                it.returnType == site.method.returnType
        }
    val existing = title.downloadCode().count { it.downloadCall()?.definingClass == DOWNLOAD_ENTRY }
    check(existing <= 1) { "$DOWNLOAD_LAYOUT_CHANGED: repeated download action" }
    val nativeCalls =
        all.filterNot { it.type.startsWith("Lapp/bitchord/") }
            .flatMap { it.methods }
            .flatMap { it.downloadCode() }
            .mapNotNull { it.downloadCall() }
            .filter { it.definingClass == DOWNLOAD_ENTRY }
    val controllerCount = nativeCalls.count { it.name == "controller" }
    val routeCount = nativeCalls.count { it.name == "route" }
    if (existing == 1) {
        check(
            controllerCount == 1 &&
                routeCount == 1 &&
                nativeCalls.count { it.name == "finished" } == 3
        ) {
            "$DOWNLOAD_LAYOUT_CHANGED: download hooks are only partly installed"
        }
        return
    }
    check(controllerCount == 0 && routeCount == 0) {
        "$DOWNLOAD_LAYOUT_CHANGED: download hooks are only partly installed"
    }
    val symbols =
        mutableClassDefBy(DOWNLOAD_ENTRY).methods.single {
            it.name == "symbols" &&
                it.parameterTypes.isEmpty() &&
                it.returnType == "Ljava/lang/String;"
        }
    check(symbols.implementation!!.registerCount > 0) {
        "$DOWNLOAD_LAYOUT_CHANGED: download symbol storage"
    }
    val table = site.symbols.entries.joinToString("\\n") { (key, value) -> "$key=$value" }
    symbols.addInstructions(0, "const-string v0, \"$table\"\nreturn-object v0")
    title.addInstruction(
        site.insertion,
        "invoke-static/range {v${site.composer} .. v${site.composer}}, " +
            "$DOWNLOAD_ENTRY->compose(Ljava/lang/Object;)V",
    )
    addPlayerDownloadController()
    addPlayerDownloadRoute()
    addPlayerDownloadLifecycle()
    println("Added the player download action")
}

private fun Method.isPlayerDownloadRoute(): Boolean =
    accessFlags and 8 != 0 &&
        returnType == "Ljava/lang/Object;" &&
        downloadTakes(
            "Landroid/content/Context;",
            DOWNLOAD_SONG,
            "Lcom/music/bitchord/data/settings/DownloadQuality;",
            "Lkotlin/coroutines/jvm/internal/ContinuationImpl;",
        ) &&
        downloadCode()
            .mapNotNull { it.downloadCall() }
            .let { calls ->
                calls.any {
                    it.definingClass == "Lcom/music/bitchord/data/sources/SourceRegistry;" &&
                        it.name == "parseTrackKey" &&
                        it.downloadTakes("Ljava/lang/String;") &&
                        it.returnType == "Lkotlin/Pair;"
                } &&
                    calls.any {
                        it.definingClass == "Lcom/music/bitchord/data/innertube/StreamResolver;" &&
                            it.name == "resolveForDownload"
                    } &&
                    calls.count {
                        it.returnType == "Lcom/music/bitchord/download/Downloads\$Route;" &&
                            it.downloadTakes(
                                "Landroid/content/Context;",
                                DOWNLOAD_SONG,
                                "Lcom/music/bitchord/download/Downloads\$Kept;",
                            )
                    } == 2
            }

/** The manager keeps ownership of scheduling, destinations, progress and cancellation. */
internal fun validatePlayerDownloadBackend(all: List<ClassDef>) {
    val downloads =
        all.filter { it.type == "Lcom/music/bitchord/download/Downloads;" }
            .downloadSingle("native download manager")
    fun manager(name: String, result: String, vararg args: String): Method =
        downloads.methods
            .filter { it.name == name && it.returnType == result && it.downloadTakes(*args) }
            .downloadSingle("native download $name")
    val context = "Landroid/content/Context;"
    val stream = "Lcom/music/bitchord/data/sources/SourceStream;"
    val kept = "Lcom/music/bitchord/download/Downloads\$Kept;"
    val continuation = "Lkotlin/coroutines/jvm/internal/ContinuationImpl;"
    manager("keepable", "Ljava/lang/Object;", stream, "Ljava/lang/Integer;", continuation)
    manager(
        "sourceRoute",
        "Lcom/music/bitchord/download/Downloads\$Route;",
        context,
        DOWNLOAD_SONG,
        kept,
    )
    playerDownloadLifecycle(downloads)
    validatePlayerDownloadReflectedHost(all)
    val route =
        downloads.methods
            .filter { it.isPlayerDownloadRoute() }
            .downloadSingle("source-row, source-search and final download route")
    check(route.name == "access\$routeFor") {
        "$DOWNLOAD_LAYOUT_CHANGED: reflected native route entry"
    }
    check(
        route.accessFlags and 8 != 0 &&
            route.implementation != null &&
            route.implementation!!.registerCount - route.parameterTypes.size >= 3
    ) {
        "$DOWNLOAD_LAYOUT_CHANGED: native download route scratch registers"
    }
    for ((owner, fields) in
        listOf(
            stream to
                mapOf(
                    "url" to "Ljava/lang/String;",
                    "headers" to "Ljava/util/Map;",
                    "format" to "Lcom/music/bitchord/data/sources/StreamFormat;",
                    "durationSec" to "Ljava/lang/Integer;",
                    "sourceConfigId" to "Ljava/lang/String;",
                ),
            kept to
                mapOf(
                    "stream" to stream,
                    "probe" to "Lcom/music/bitchord/download/Downloader\$Probe;",
                ),
        )) {
        val type = all.filter { it.type == owner }.downloadSingle("download data $owner")
        for ((name, expected) in fields) check(
            type.fields.any { it.name == name && it.type == expected && it.accessFlags and 1 != 0 }
        ) {
            "$DOWNLOAD_LAYOUT_CHANGED: download data $owner.$name"
        }
    }
    val registry =
        all.filter { it.type == "Lcom/music/bitchord/data/sources/SourceRegistry;" }
            .downloadSingle("source registry")
    check(
        registry.methods.any {
            it.name == "activeForDownload" &&
                it.parameterTypes.isEmpty() &&
                it.returnType in setOf("Ljava/util/List;", "Ljava/util/ArrayList;")
        }
    ) {
        "$DOWNLOAD_LAYOUT_CHANGED: enabled downloadable sources"
    }
}

private fun BytecodePatchContext.addPlayerDownloadController() {
    val owner = mutableClassDefBy("Lcom/music/bitchord/playback/PlayerConnectionKt;")
    val observer =
        owner.methods
            .filter {
                it.accessFlags and 8 != 0 &&
                    it.downloadTakes(
                        "Landroidx/media3/session/MediaController;",
                        DOWNLOAD_COMPOSER,
                    ) &&
                    it.returnType == "Lcom/music/bitchord/playback/PlayerState;" &&
                    it.downloadCode().any { i ->
                        i.opcode == Opcode.NEW_INSTANCE &&
                            i.downloadType() == "Lcom/music/bitchord/playback/PlaybackPosition;"
                    }
            }
            .downloadSingle("native player state observer")
    if (
        observer.downloadCode().none {
            it.downloadCall()?.let { call ->
                call.definingClass == DOWNLOAD_ENTRY && call.name == "controller"
            } == true
        }
    )
        observer.addInstruction(
            0,
            "invoke-static/range {p0 .. p0}, $DOWNLOAD_ENTRY->controller(Ljava/lang/Object;)V",
        )
}

private fun BytecodePatchContext.addPlayerDownloadRoute() {
    val route =
        mutableClassDefBy("Lcom/music/bitchord/download/Downloads;")
            .methods
            .filter { it.isPlayerDownloadRoute() }
            .downloadSingle("native download route body")
    if (route.downloadCode().any { it.downloadCall()?.definingClass == DOWNLOAD_ENTRY }) return
    route.addInstructionsWithLabels(
        0,
        """
        move-object/from16 v0, p0
        move-object/from16 v1, p1
        move-object/from16 v2, p3
        invoke-static {v0, v1, v2}, $DOWNLOAD_ENTRY->route(Landroid/content/Context;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
        move-result-object v0
        if-eqz v0, :ordinary_download
        return-object v0
        """
            .trimIndent(),
        ExternalLabel("ordinary_download", route.implementation!!.instructions.first()),
    )
}

private fun playerDownloadLifecycle(downloads: ClassDef): List<Method> {
    val string = "Ljava/lang/String;"
    return listOf("clear", "fail", "cancel").map { name ->
        val expected = if (name == "fail") listOf(string, string) else listOf(string)
        val method =
            downloads.methods
                .filter {
                    it.name == name &&
                        it.accessFlags and 8 != 0 &&
                        it.returnType == "V" &&
                        it.parameterTypes.map { p -> p.toString() } == expected
                }
                .downloadSingle("native download $name lifecycle")
        val body = method.downloadCode()
        check(
            when (name) {
                "clear" ->
                    body.any {
                        it.downloadField()?.let { field ->
                            field.definingClass == downloads.type && field.name == "_active"
                        } == true
                    }
                "fail" ->
                    body.any {
                        it.downloadCall()?.let { call ->
                            call.definingClass == "Lcom/music/bitchord/download/DownloadSession;" &&
                                call.name == "set"
                        } == true
                    }
                else ->
                    body.any {
                        it.downloadCall()?.let { call ->
                            call.definingClass == "Lcom/music/bitchord/download/DownloadSession;" &&
                                call.name == "forget"
                        } == true
                    }
            }
        ) {
            "$DOWNLOAD_LAYOUT_CHANGED: native download $name behavior"
        }
        method
    }
}

private fun BytecodePatchContext.addPlayerDownloadLifecycle() {
    val downloads = mutableClassDefBy("Lcom/music/bitchord/download/Downloads;")
    for (found in playerDownloadLifecycle(downloads)) {
        val method =
            downloads.methods.single {
                it.name == found.name &&
                    it.parameterTypes == found.parameterTypes &&
                    it.returnType == found.returnType
            }
        if (
            method.downloadCode().none {
                it.downloadCall()?.let { call ->
                    call.definingClass == DOWNLOAD_ENTRY && call.name == "finished"
                } == true
            }
        )
            method.addInstruction(
                0,
                "invoke-static/range {p0 .. p0}, $DOWNLOAD_ENTRY->finished(Ljava/lang/String;)V",
            )
    }
}

internal fun validatePlayerDownloadReflectedHost(all: List<ClassDef>) {
    val string = "Ljava/lang/String;"
    val context = "Landroid/content/Context;"
    val continuation = "Lkotlin/coroutines/jvm/internal/ContinuationImpl;"
    val source = "Lcom/music/bitchord/data/sources/"
    fun type(owner: String) = all.filter { it.type == owner }.downloadSingle("reflected $owner")
    fun method(
        owner: String,
        name: String,
        result: String,
        static: Boolean,
        vararg parameters: String,
    ) {
        val found =
            type(owner)
                .methods
                .filter {
                    it.name == name && it.returnType == result && it.downloadTakes(*parameters)
                }
                .downloadSingle("reflected $owner->$name")
        check((found.accessFlags and 8 != 0) == static && found.accessFlags and 1 != 0) {
            "$DOWNLOAD_LAYOUT_CHANGED: reflected access $owner->$name"
        }
    }
    fun field(owner: String, name: String, expected: String) {
        check(
            type(owner).fields.any {
                it.name == name && it.type == expected && it.accessFlags and 1 != 0
            }
        ) {
            "$DOWNLOAD_LAYOUT_CHANGED: reflected $owner.$name"
        }
    }
    method(
        source + "SourceResolver;",
        "forDownloadOfRow",
        "Ljava/lang/Object;",
        false,
        string,
        string,
        string,
        source + "StreamRequest;",
        continuation,
    )
    method(
        source + "SourceResolver;",
        "matchAndStream",
        "Ljava/lang/Object;",
        false,
        source + "MusicSource;",
        source + "TrackMatcher\$Target;",
        source + "StreamRequest;",
        "Z",
        "Z",
        "Z",
        "I",
        continuation,
    )
    method(
        source + "TrackMatcher\$Target;",
        "<init>",
        "V",
        false,
        string,
        string,
        "Ljava/lang/Integer;",
        string,
        "Ljava/lang/Boolean;",
        "Z",
    )
    field(source + "StreamRequest\$Best;", "INSTANCE", source + "StreamRequest\$Best;")
    method(
        "Lcom/music/bitchord/data/LocalMediaRepository;",
        "hasStoragePermission",
        "Z",
        true,
        context,
    )
    method(
        "Lcom/music/bitchord/data/LocalMediaRepository;",
        "getLocalMusic",
        "Ljava/lang/Object;",
        true,
        context,
        continuation,
    )
    method(
        "Lcom/music/bitchord/playback/PlayerConnectionKt;",
        "toSong",
        DOWNLOAD_SONG,
        true,
        "Landroidx/media3/common/MediaItem;",
    )
    method(source + "StreamFormat;", "isLossless", "Ljava/lang/Boolean;", false)
    field(source + "StreamFormat;", "codec", string)
    for (name in listOf("kbps", "bitDepth", "sampleRateHz")) field(
        source + "StreamFormat;",
        name,
        "Ljava/lang/Integer;",
    )
    field(
        "Lcom/music/bitchord/data/settings/DownloadQuality;",
        "LOSSLESS",
        "Lcom/music/bitchord/data/settings/DownloadQuality;",
    )
    for (name in listOf("getEnabled", "getAllowDownloads")) method(
        source + "SourceConfig;",
        name,
        "Z",
        false,
    )
    method(source + "SourceConfig;", "getKind", source + "SourceKind;", false)
    method(source + "SourceRegistry;", "config", source + "SourceConfig;", true, string)
    field("Lcom/music/bitchord/download/DownloadState\$Running;", "fraction", "F")
    field("Lcom/music/bitchord/download/DownloadState\$Failed;", "reason", string)
    method("Lcom/music/bitchord/download/DownloadState\$Failed;", "<init>", "V", false, string)
    val job = "Lkotlinx/coroutines/Job;"
    val key = "Lkotlinx/coroutines/Job\$Key;"
    val keyType = type(key)
    check(
        "Lkotlin/coroutines/CoroutineContext\$Key;" in keyType.interfaces &&
            keyType.accessFlags and 1 != 0 &&
            keyType.fields.count { it.type == key && it.accessFlags and 8 != 0 } == 1 &&
            keyType.fields.any { it.type == key && it.accessFlags and 25 == 25 }
    ) {
        "$DOWNLOAD_LAYOUT_CHANGED: native download coroutine key singleton"
    }
    method(job, "isActive", "Z", false)
    method(job, "cancel", "V", false, "Ljava/util/concurrent/CancellationException;")
    method("Lkotlinx/coroutines/JobImpl;", "<init>", "V", false, job)
    val downloads = type("Lcom/music/bitchord/download/Downloads;")
    for (name in listOf("_active", "_saved")) check(
        downloads.fields.any {
            it.name == name &&
                it.type == "Lkotlinx/coroutines/flow/StateFlowImpl;" &&
                it.accessFlags and 8 != 0
        }
    ) {
        "$DOWNLOAD_LAYOUT_CHANGED: native download progress $name"
    }
}

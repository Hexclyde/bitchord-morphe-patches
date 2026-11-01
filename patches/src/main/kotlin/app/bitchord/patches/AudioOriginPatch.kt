package app.bitchord.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.*
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.*
import com.android.tools.smali.dexlib2.immutable.*

private const val ORIGIN = "Lapp/bitchord/telegram/AudioOrigin;"
private const val CACHE = "Lcom/music/bitchord/playback/AudioCache;"
private const val SERVICE = "Lcom/music/bitchord/playback/PlaybackService;"
private const val DOWNLOADS = "Lcom/music/bitchord/download/Downloads;"
private const val SONG = "Lcom/music/bitchord/data/model/Song;"
private const val STREAM = "Lcom/music/bitchord/data/sources/SourceStream;"
private const val SNAPSHOT = "Lcom/music/bitchord/data/NerdStats\$Snapshot;"
private const val DATA_SPEC = "Landroidx/media3/datasource/DataSpec;"
private const val MEDIA_ITEM = "Landroidx/media3/common/MediaItem;"
private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"

private fun Instruction.called(): MethodReference? =
    (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.text(): String? =
    ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

/** Records which source really filled each cache rendition and saved file. */
internal val audioOriginPatch = bytecodePatch {
    execute {
        addCacheRecords()
        hookServedRequests()
        hookActiveRendition()
        hookSavedFiles()
    }
}

/**
 * R8 merged the metadata mutator into another class and shortened the read, so those types are
 * taken from the cache's own signatures and the setter is recognised by what it does.
 */
private fun BytecodePatchContext.addCacheRecords() {
    val cacheField = classDefBy(CACHE).fields.singleOrNull { it.name == "cache" }
    check(cacheField != null && cacheField.accessFlags and AccessFlags.STATIC.value != 0) {
        "Audio cache field changed"
    }
    val store = classDefBy(cacheField.type)
    val apply =
        store.methods.singleOrNull {
            it.name == "applyContentMetadataMutations" &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == STRING &&
                it.returnType == "V"
        }
    check(apply != null) { "Cache metadata write changed" }
    val mutatorType = apply.parameterTypes[1].toString()
    val mutator = classDefBy(mutatorType)
    check(mutator.methods.any { it.name == "<init>" && it.parameterTypes.isEmpty() }) {
        "Cache metadata mutator can no longer be created"
    }
    val setters =
        mutator.methods.filter { method ->
            method.parameterTypes.map { it.toString() } == listOf(OBJECT, STRING) &&
                method.returnType == "V" &&
                method.implementation?.instructions?.any {
                    it.called()?.let { call ->
                        call.definingClass == "Ljava/util/HashMap;" && call.name == "put"
                    } == true
                } == true
        }
    check(setters.size == 1) { "Cache metadata setter changed (${setters.size})" }
    val setter = setters.single()
    // The method is (value, name) on 1.9, and its map put is what proves the order.
    val registers = setter.implementation!!.registerCount
    val put =
        setter.implementation!!.instructions.first { it.called()?.name == "put" }
            as FiveRegisterInstruction
    check(put.registerD == registers - 1 && put.registerE == registers - 2) {
        "Cache metadata setter order changed"
    }
    val read = store.methods.singleOrNull { it.name == "getContentMetadata" }
    check(read != null && read.parameterTypes.map { it.toString() } == listOf(STRING)) {
        "Cache metadata read changed"
    }
    val metadataType = read.returnType
    check(
        classDefBy(metadataType).methods.any {
            it.name == "get" &&
                it.parameterTypes.map { type -> type.toString() } == listOf(STRING) &&
                it.returnType == STRING &&
                it.accessFlags and AccessFlags.STATIC.value == 0
        }
    ) {
        "Cache metadata text read changed"
    }
    val spans = store.methods.singleOrNull { it.name == "getCachedSpans" }
    check(spans != null && spans.parameterTypes.map { it.toString() } == listOf(STRING)) {
        "Cached span read changed"
    }

    bridge(
        "bitchordOriginRead",
        listOf(STRING, STRING),
        STRING,
        3,
        """
        sget-object v0, $CACHE->cache:${cacheField.type}
        if-nez v0, :open
        const/4 v0, 0x0
        return-object v0
        :open
        invoke-virtual {v0, p0}, ${cacheField.type}->getContentMetadata($STRING)$metadataType
        move-result-object v0
        invoke-virtual {v0, p1}, $metadataType->get($STRING)$STRING
        move-result-object v0
        return-object v0
        """,
    )
    bridge(
        "bitchordOriginWrite",
        listOf(STRING, "[$STRING"),
        "V",
        8,
        """
        sget-object v0, $CACHE->cache:${cacheField.type}
        if-eqz v0, :done
        new-instance v1, $mutatorType
        invoke-direct {v1}, $mutatorType-><init>()V
        const/4 v2, 0x0
        array-length v3, p1
        :next
        if-ge v2, v3, :apply
        aget-object v4, p1, v2
        add-int/lit8 v2, v2, 0x1
        aget-object v5, p1, v2
        add-int/lit8 v2, v2, 0x1
        invoke-virtual {v1, v5, v4}, $mutatorType->${setter.name}($OBJECT$STRING)V
        goto :next
        :apply
        invoke-virtual {v0, p0, v1}, ${cacheField.type}->applyContentMetadataMutations($STRING$mutatorType)V
        :done
        return-void
        """,
    )
    bridge(
        "bitchordOriginHasBytes",
        listOf(STRING),
        "Z",
        2,
        """
        sget-object v0, $CACHE->cache:${cacheField.type}
        if-eqz v0, :none
        invoke-virtual {v0, p0}, ${cacheField.type}->getCachedSpans($STRING)${spans.returnType}
        move-result-object v0
        invoke-interface {v0}, Ljava/util/Collection;->isEmpty()Z
        move-result v0
        if-nez v0, :none
        const/4 v0, 0x1
        return v0
        :none
        const/4 v0, 0x0
        return v0
        """,
    )
    for ((name, parameters) in
        listOf("cacheKeyOf" to listOf("Landroid/net/Uri;"), "keysOf" to listOf(STRING))) {
        check(
            classDefBy(CACHE).methods.any {
                it.name == name &&
                    it.parameterTypes.map { type -> type.toString() } == parameters &&
                    it.accessFlags and AccessFlags.STATIC.value != 0 &&
                    it.accessFlags and AccessFlags.PUBLIC.value != 0
            }
        ) {
            "Audio cache $name changed"
        }
    }
    check(
        classDefBy(CACHE).fields.any {
            it.name == "appContext" && it.accessFlags and AccessFlags.PUBLIC.value != 0
        }
    ) {
        "Audio cache context changed"
    }
    for ((owner, name, parameter) in
        listOf(
            Triple(
                "Lcom/music/bitchord/playback/QualityUpgrade;",
                "forcedStream",
                "Landroid/net/Uri;",
            ),
            Triple("Lcom/music/bitchord/playback/StreamChoice;", "of", STRING),
            Triple("Lcom/music/bitchord/playback/StreamChoice;", "isSubstitute", STRING),
            Triple("Lcom/music/bitchord/data/sources/SourceRegistry;", "config", STRING),
        )) {
        check(
            classDefBy(owner).methods.any {
                it.name == name &&
                    it.parameterTypes.map { type -> type.toString() } == listOf(parameter) &&
                    it.accessFlags and AccessFlags.PUBLIC.value != 0
            }
        ) {
            "Source lookup $name changed"
        }
    }
    val fields = classDefBy(STREAM).fields.map { it.name to it.accessFlags }.toMap()
    for (name in listOf("url", "sourceConfigId", "format")) check(
        (fields[name] ?: 0) and AccessFlags.PUBLIC.value != 0
    ) {
        "Source stream field $name changed"
    }
    for ((owner, names) in
        listOf(
            DATA_SPEC to listOf("key"),
            "Lcom/music/bitchord/data/sources/StreamFormat;" to
                listOf("codec", "kbps", "kbpsInferred", "bitDepth", "sampleRateHz"),
            "Lcom/music/bitchord/data/sources/SourceRegistry;" to listOf("configs"),
        )) {
        for (name in names) {
            check(
                classDefBy(owner).fields.any {
                    it.name == name && it.accessFlags and AccessFlags.PUBLIC.value != 0
                }
            ) {
                "Source record field $owner->$name changed"
            }
        }
    }
    for ((owner, names) in
        listOf(
            "Lcom/music/bitchord/data/sources/SourceConfig;" to
                listOf("getId", "getDisplayName", "getKind"),
            "Lkotlinx/coroutines/flow/StateFlow;" to listOf("getValue"),
        )) {
        for (name in names) {
            check(
                classDefBy(owner).methods.any {
                    it.name == name &&
                        it.parameterTypes.isEmpty() &&
                        it.accessFlags and AccessFlags.PUBLIC.value != 0
                }
            ) {
                "Source record lookup $owner->$name changed"
            }
        }
    }
}

private fun BytecodePatchContext.bridge(
    name: String,
    args: List<String>,
    result: String,
    registers: Int,
    code: String,
) {
    val type = mutableClassDefBy(CACHE)
    check(type.methods.none { it.name == name }) { "Source record bridge already exists: $name" }
    val method =
        MutableMethod(
            ImmutableMethod(
                CACHE,
                name,
                args.map { ImmutableMethodParameter(it, null, null) },
                result,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                null,
                null,
                ImmutableMethodImplementation(
                    registers,
                    emptyList<Instruction>(),
                    emptyList(),
                    emptyList(),
                ),
            )
        )
    method.addInstructionsWithLabels(0, code)
    type.methods.add(method)
}

/**
 * Records at the player's own resolver boundary, after any verification, so the URL is the final
 * one. Works whichever way round this and the patch that wraps the same call are applied.
 */
private fun BytecodePatchContext.hookServedRequests() {
    val open =
        mutableClassDefBy("Landroidx/media3/datasource/ResolvingDataSource;").methods.single {
            it.name == "open"
        }
    val code = open.implementation!!.instructions.toList()
    val resolves =
        code.indices.filter {
            val call = code[it].called() ?: return@filter false
            call.returnType == DATA_SPEC &&
                (call.name == "resolveDataSpec" ||
                    (call.name == "resolve" &&
                        call.definingClass == "Lapp/bitchord/telegram/SourcePreference;"))
        }
    check(resolves.size == 1) { "Player resolver boundary changed (${resolves.size})" }
    val resolve = resolves.single()
    check(code[resolve + 1].opcode == Opcode.MOVE_RESULT_OBJECT) { "Resolver result changed" }
    val result = (code[resolve + 1] as OneRegisterInstruction).registerA
    val input = (code[resolve] as FiveRegisterInstruction).registerD
    // There are two spare registers here, and the next instruction redefines both.
    check(
        result == input &&
            input == open.implementation!!.registerCount - 1 &&
            open.implementation!!.registerCount >= 4 &&
            code[resolve + 2].opcode == Opcode.CONST_4 &&
            (code[resolve + 2] as OneRegisterInstruction).registerA == 0
    ) {
        "Resolver registers changed"
    }
    open.addInstruction(
        resolve + 2,
        "invoke-static {v1, v$result}, $ORIGIN->recordServed($DATA_SPEC$DATA_SPEC)V",
    )
    open.addInstruction(resolve, "move-object v1, v$input")

    // The source row branch keeps its stream to itself, and may not be the row's own source.
    val resolver = classDefBy(SERVICE).fields.single { it.name == "streamResolver" }
    val lambda =
        mutableClassDefBy(resolver.type).methods.single {
            it.name == "resolveDataSpec" &&
                it.parameterTypes.map { p -> p.toString() } == listOf(DATA_SPEC)
        }
    val lines = lambda.implementation!!.instructions.toList()
    val branches = lines.indices.filter { lines[it].text() == "source" }
    check(branches.size == 1) { "Source row branch changed (${branches.size})" }
    val cast =
        (branches.single() until lines.size).firstOrNull {
            lines[it].opcode == Opcode.CHECK_CAST &&
                ((lines[it] as ReferenceInstruction).reference as TypeReference).type == STREAM
        }
    check(cast != null) { "Source row stream changed" }
    val stream = (lines[cast] as OneRegisterInstruction).registerA
    check(stream <= 15) { "Source row stream register changed" }
    lambda.addInstruction(cast + 1, "invoke-static {v$stream}, $ORIGIN->streamResolved($OBJECT)V")
}

/** Reloads records as a track becomes current, and gives Stats the rendition being heard. */
private fun BytecodePatchContext.hookActiveRendition() {
    val service = mutableClassDefBy(SERVICE)
    val transition =
        service.methods.single {
            it.name == "onTrackBecameCurrent" &&
                it.parameterTypes.map { p -> p.toString() } == listOf(MEDIA_ITEM, "Z", "I", "Z") &&
                it.returnType == "V"
        }
    transition.addInstruction(0, "invoke-static/range {p1 .. p1}, $ORIGIN->played($MEDIA_ITEM)V")

    val publish =
        service.methods.single {
            it.name == "access\$publishNerdStats" &&
                it.parameterTypes.map { p -> p.toString() } == listOf(SERVICE) &&
                it.returnType == "V"
        }
    val code = publish.implementation!!.instructions.toList()

    val snapshotConstructor =
        classDefBy(SNAPSHOT).methods.single {
            it.name == "<init>" &&
                it.implementation?.instructions?.any { line ->
                    ((line as? ReferenceInstruction)?.reference as? FieldReference)?.name ==
                        "sourceName"
                } == true
        }
    val store =
        snapshotConstructor.implementation!!.instructions.first {
            ((it as? ReferenceInstruction)?.reference as? FieldReference)?.name == "sourceName"
        } as TwoRegisterInstruction
    val firstParameter =
        snapshotConstructor.implementation!!.registerCount - snapshotConstructor.parameterTypes.size
    val nameParameter = store.registerA - firstParameter
    check(
        nameParameter in snapshotConstructor.parameterTypes.indices &&
            snapshotConstructor.parameterTypes[nameParameter].toString() == STRING
    ) {
        "Stats source argument changed"
    }

    val builds =
        code.indices.filter {
            val call = code[it].called()
            call?.definingClass == SNAPSHOT && call.name == "<init>"
        }
    check(builds.size == 1 && code[builds.single()] is RegisterRangeInstruction) {
        "Stats snapshot construction changed (${builds.size})"
    }
    val build = builds.single()
    check(((code[build] as BuilderInstruction).location.labels).isEmpty()) {
        "Stats snapshot construction is now a branch target"
    }
    // Argument 0 is the snapshot itself.
    val name = (code[build] as RegisterRangeInstruction).startRegister + 1 + nameParameter
    publish.addInstructions(
        build,
        """
        invoke-static/range {v$name .. v$name}, $ORIGIN->statsName($STRING)$STRING
        move-result-object v$name
        """,
    )

    val items =
        code.indices.filter {
            val call = code[it].called()
            call?.name == "getCurrentMediaItem" && call.returnType == MEDIA_ITEM
        }
    check(items.isNotEmpty() && code[items.first() + 1].opcode == Opcode.MOVE_RESULT_OBJECT) {
        "Stats current item changed"
    }
    val first = items.first()
    val item = (code[first + 1] as OneRegisterInstruction).registerA
    check(item <= 15) { "Stats current item register changed" }
    publish.addInstruction(first + 2, "invoke-static {v$item}, $ORIGIN->statsItem($MEDIA_ITEM)V")
}

/** Binds a source to each Route as it is made, since the Route itself has no place for one. */
private fun BytecodePatchContext.hookSavedFiles() {
    val downloads = mutableClassDefBy(DOWNLOADS)
    fun method(name: String, vararg parameters: String) =
        downloads.methods.singleOrNull {
            it.name == name && it.parameterTypes.map { p -> p.toString() } == parameters.toList()
        } ?: error("Download method $name changed")
    fun routeType(name: String) =
        check(
            downloads.methods.any {
                it.name == name && it.returnType == "Lcom/music/bitchord/download/Downloads\$Route;"
            }
        ) {
            "Download $name result changed"
        }

    for ((type, member) in
        listOf(
            "Lcom/music/bitchord/download/Downloads\$Kept;" to "stream",
            "Lcom/music/bitchord/download/Downloads\$Prepared;" to "route",
            "Lcom/music/bitchord/playback/AudioCache\$CompleteCopy;" to "key",
            SONG to "videoId",
        )) check(
        classDefBy(type).fields.any {
            it.name == member && it.accessFlags and AccessFlags.PUBLIC.value != 0
        }
    ) {
        "Download field $member changed"
    }

    routeType("sourceRoute")
    routeType("cachedRoute")
    val routers =
        listOf(
            method(
                "sourceRoute",
                "Landroid/content/Context;",
                SONG,
                "Lcom/music/bitchord/download/Downloads\$Kept;",
            ) to "sourceRouting",
            method("cachedRoute", "Lcom/music/bitchord/playback/AudioCache\$CompleteCopy;") to
                "cacheRouting",
        )
    for ((router, hook) in routers) {
        val lines = router.implementation!!.instructions.toList()
        val returns = lines.indices.filter { lines[it].opcode == Opcode.RETURN_OBJECT }
        check(returns.isNotEmpty()) { "Download ${router.name} has no result" }
        // A branch may land on any return, so each one turns into the hook followed by a return.
        for (index in returns.reversed()) {
            val register = (lines[index] as OneRegisterInstruction).registerA
            router.replaceInstruction(
                index,
                "invoke-static/range {v$register .. v$register}, $ORIGIN->routed($OBJECT)V",
            )
            router.addInstruction(index + 1, "return-object v$register")
        }
        val parameter = router.parameterTypes.size - 1
        router.addInstruction(
            0,
            "invoke-static/range {p$parameter .. p$parameter}, $ORIGIN->$hook($OBJECT)V",
        )
    }

    val routeFor =
        downloads.methods.single {
            it.name == "access\$routeFor" &&
                it.parameterTypes.take(2).map { p -> p.toString() } ==
                    listOf("Landroid/content/Context;", SONG)
        }
    val lines = routeFor.implementation!!.instructions.toList()
    val made =
        lines.indices.filter {
            val call = lines[it].called()
            call?.definingClass == "Lcom/music/bitchord/download/Downloads\$Route;" &&
                call.name == "<init>"
        }
    check(made.size == 1 && lines[made.single()] is RegisterRangeInstruction) {
        "YouTube download route changed (${made.size})"
    }
    val youtube = (lines[made.single()] as RegisterRangeInstruction).startRegister
    check(lines.subList(0, made.single()).any { it.called()?.name == "resolveForDownload" }) {
        "YouTube download route no longer follows its own resolve"
    }
    routeFor.addInstruction(
        made.single() + 1,
        "invoke-static/range {v$youtube .. v$youtube}, $ORIGIN->youtubeRoute($OBJECT)V",
    )

    val transfer =
        downloads.methods.single {
            it.name == "access\$transfer" &&
                it.parameterTypes.take(3).map { p -> p.toString() } ==
                    listOf(
                        "Landroid/content/Context;",
                        SONG,
                        "Lcom/music/bitchord/download/Downloads\$Prepared;",
                    )
        }
    transfer.addInstruction(
        0,
        "invoke-static/range {p0 .. p2}, $ORIGIN->transferring(Landroid/content/Context;$OBJECT$OBJECT)V",
    )

    val rememberDefault = method("remember\$default", SONG, SONG, "Landroid/net/Uri;", STRING, "I")
    val mask = rememberDefault.implementation!!.instructions.first()
    check(
        mask.opcode == Opcode.AND_INT_LIT8 && (mask as WideLiteralInstruction).wideLiteral == 8L
    ) {
        "Omitted download format flag changed"
    }
    rememberDefault.addInstruction(0, "invoke-static/range {p4 .. p4}, $ORIGIN->adopting(I)V")

    val remember =
        downloads.methods.single {
            it.name == "remember" &&
                it.parameterTypes.take(3).map { p -> p.toString() } ==
                    listOf(SONG, SONG, "Landroid/net/Uri;") &&
                it.returnType == "V"
        }
    remember.addInstruction(
        0,
        "invoke-static/range {p0 .. p2}, $ORIGIN->remembered($OBJECT$OBJECT" + "Landroid/net/Uri;)V",
    )

    method("forget", STRING)
        .addInstruction(0, "invoke-static/range {p0 .. p0}, $ORIGIN->forgetSaved($STRING)V")
    for (ended in listOf(method("clear", STRING), method("fail", STRING, STRING))) ended
        .addInstruction(0, "invoke-static/range {p0 .. p0}, $ORIGIN->downloadEnded($STRING)V")
}

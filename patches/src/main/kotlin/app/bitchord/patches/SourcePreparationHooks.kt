package app.bitchord.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.*
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.*
import com.android.tools.smali.dexlib2.immutable.*

private const val MIX = "Lapp/bitchord/telegram/SourceAutomix;"
private const val PREF = "Lapp/bitchord/telegram/SourcePreference;"
private const val PREPARE = "Lapp/bitchord/telegram/SourceFirst;"
private const val HOST = "Lcom/music/bitchord/playback/PlaybackService;"
private const val CACHE = "Lcom/music/bitchord/playback/AudioCache;"
private const val ANALYZER = "Lcom/music/bitchord/playback/smart/TrackAnalyzer;"
private const val SPEC = "Landroidx/media3/datasource/DataSpec;"
private const val DS = "Landroidx/media3/datasource/DataSource;"
private const val URI = "Landroid/net/Uri;"

private fun Instruction.mixCall() = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.mixField() = (this as? ReferenceInstruction)?.reference as? FieldReference

/** Keep all original branch labels on the hook, never on its result/return tail. */
private fun MutableMethod.wrapObjectReturn(index: Int, invocation: String, register: Int) {
    replaceInstruction(index, invocation)
    addInstructions(index + 1, "move-result-object v$register\nreturn-object v$register")
}

/**
 * What checking a copy of a song against the song needs: BitChord's own decoder and source router
 * for the extension, and the check at the player's resolver. Shared, and done once: both
 * source-independent AutoMix and playing the best source first depend on it, and neither on the
 * other.
 */
internal fun BytecodePatchContext.addSourceVerification() {
    if (mutableClassDefBy(HOST).methods.any { it.name == "bitchordAutomixResolveWith" }) return
    fun add(
        owner: String,
        name: String,
        args: List<String>,
        result: String,
        registers: Int,
        code: String,
    ) {
        val type = mutableClassDefBy(owner)
        check(type.methods.none { it.name == name }) { "Source check bridge already exists: $name" }
        val method =
            MutableMethod(
                ImmutableMethod(
                    owner,
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
    val cache = mutableClassDefBy(CACHE)
    val renditionType = "Lcom/music/bitchord/playback/AudioCache${'$'}Rendition;"
    val cachedType = "Lcom/music/bitchord/playback/AudioCache${'$'}CacheMediaDataSource;"
    add(
        CACHE,
        "bitchordAutomixCached",
        listOf(URI),
        "Landroid/media/MediaDataSource;",
        3,
        """
    sget-object v0, $CACHE->cache:Landroidx/media3/datasource/cache/SimpleCache;
    if-eqz v0, :missing
    invoke-static {p0}, $CACHE->cacheKeyOf($URI)Ljava/lang/String;
    move-result-object v0
    invoke-static {v0}, $CACHE->renditionFor(Ljava/lang/String;)$renditionType
    move-result-object v0
    if-eqz v0, :missing
    invoke-virtual {v0}, $renditionType->isComplete()Z
    move-result v1
    if-eqz v1, :missing
    invoke-static {p0, v0}, $CACHE->renditionDataSource($URI$renditionType)$cachedType
    move-result-object v0
    return-object v0
    :missing
    const/4 v0, 0x0
    return-object v0
""",
    )
    add(
        HOST,
        "bitchordAutomixCached",
        listOf(URI),
        "Landroid/media/MediaDataSource;",
        1,
        """
    invoke-static {p0}, $CACHE->bitchordAutomixCached($URI)Landroid/media/MediaDataSource;
    move-result-object p0
    return-object p0
""",
    )
    val factory = cache.fields.single { it.name == "upstreamFactory" }
    val source =
        mutableClassDefBy(factory.type).methods.single {
            it.name == "createDataSource" && it.returnType == DS
        }
    add(
        CACHE,
        "bitchordAutomixSource",
        emptyList(),
        DS,
        1,
        """
    sget-object v0, $CACHE->upstreamFactory:${factory.type}
    if-nez v0, :ready
    return-object v0
    :ready
    invoke-virtual {v0}, ${source.definingClass}->${source.name}()$DS
    move-result-object v0
    return-object v0
""",
    )
    add(
        HOST,
        "bitchordAutomixSource",
        emptyList(),
        DS,
        1,
        """
    invoke-static {}, $CACHE->bitchordAutomixSource()$DS
    move-result-object v0
    return-object v0
""",
    )
    add(
        HOST,
        "bitchordAutomixKey",
        listOf(URI),
        "Ljava/lang/String;",
        1,
        """
    invoke-static {p0}, $CACHE->cacheKeyOf($URI)Ljava/lang/String;
    move-result-object p0
    return-object p0
""",
    )
    val service = mutableClassDefBy(HOST)
    val ranked =
        mutableClassDefBy("Lcom/music/bitchord/data/sources/SourceRegistry;").methods.single {
            it.name == "activeForPlayback"
        }
    check(ranked.returnType == "Ljava/util/ArrayList;")
    val rankCode = ranked.implementation!!.instructions.toList()
    for (index in
        rankCode.indices.filter { rankCode[it].opcode == Opcode.RETURN_OBJECT }.reversed()) {
        val register = (rankCode[index] as OneRegisterInstruction).registerA
        ranked.wrapObjectReturn(
            index,
            "invoke-static/range {v$register .. v$register}, $PREF->rank(Ljava/util/ArrayList;)Ljava/util/ArrayList;",
            register,
        )
    }
    val predicateOwner = classDefByStrings(" isn't worth swapping '").single()
    val predicate =
        mutableClassDefBy(predicateOwner).methods.single {
            it.name == "invoke" &&
                it.parameterTypes == listOf("Ljava/lang/Object;", "Ljava/lang/Object;")
        }
    predicate.addInstructionsWithLabels(
        0,
        """
    invoke-static/range {p0 .. p2}, $PREF->equalUpgrade(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Boolean;
    move-result-object v0
    if-eqz v0, :original_quality
    return-object v0
    :original_quality
    nop
""",
    )
    val resolver = service.fields.single { it.name == "streamResolver" }
    val resolve =
        mutableClassDefBy(resolver.type).methods.single {
            it.name == "resolveDataSpec" && it.parameterTypes == listOf(SPEC)
        }
    add(
        HOST,
        "bitchordAutomixResolveWith",
        listOf("Ljava/lang/Object;", SPEC),
        SPEC,
        2,
        """
    check-cast p0, ${resolver.type}
    invoke-virtual {p0, p1}, ${resolver.type}->${resolve.name}($SPEC)$SPEC
    move-result-object p0
    return-object p0
""",
    )
    add(
        HOST,
        "bitchordAutomixResolve",
        listOf("Ljava/lang/Object;", URI),
        SPEC,
        4,
        """
    check-cast p0, $HOST
    iget-object v0, p0, $HOST->streamResolver:${resolver.type}
    if-nez v0, :ready
    const/4 v0, 0x0
    return-object v0
    :ready
    new-instance v1, $SPEC
    invoke-direct {v1, p1}, $SPEC-><init>($URI)V
    invoke-virtual {v0, v1}, ${resolver.type}->${resolve.name}($SPEC)$SPEC
    move-result-object v0
    return-object v0
""",
    )
    add(
        HOST,
        "bitchordAutomixStopped",
        emptyList(),
        "Z",
        1,
        """
    invoke-static {}, $ANALYZER->getStopped()Z
    move-result v0
    return v0
""",
    )
    service.methods
        .single { it.name == "onCreate" }
        .addInstruction(0, "invoke-static/range {p0 .. p0}, $MIX->service(Ljava/lang/Object;)V")
    service.methods
        .single { it.name == "onDestroy" }
        .addInstruction(0, "invoke-static/range {p0 .. p0}, $MIX->release(Ljava/lang/Object;)V")
    // Verify automatic source substitutions at the player's own resolver boundary.
    val routing = mutableClassDefBy("Landroidx/media3/datasource/ResolvingDataSource;")
    val open = routing.methods.single { it.name == "open" }
    val original = open.implementation!!.instructions.toList()
    val resolved = original.indexOfFirst { it.mixCall()?.name == "resolveDataSpec" }
    check(resolved >= 0 && original[resolved + 1].opcode == Opcode.MOVE_RESULT_OBJECT)
    val owner = (original[resolved] as FiveRegisterInstruction).registerC
    val input = (original[resolved] as FiveRegisterInstruction).registerD
    open.replaceInstruction(
        resolved,
        "invoke-static {v$owner, v$input}, $PREF->resolve(Ljava/lang/Object;$SPEC)$SPEC",
    )

    val alignment = service.methods.single { it.name == "access${'$'}prepareVersionAlignment" }
    check(
        alignment.parameterTypes.take(3) ==
            listOf(
                HOST,
                "Lcom/music/bitchord/data/model/Song;",
                "Lcom/music/bitchord/data/model/Song;",
            )
    )
    alignment.addInstructionsWithLabels(
        0,
        """
    invoke-static/range {p0 .. p2}, $PREF->alignedOffset(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Long;
    move-result-object v0
    if-eqz v0, :host_alignment
    return-object v0
    :host_alignment
    nop
""",
    )

    // Analysis/reference reads must never overwrite the audible source label.
    val stats = mutableClassDefBy("Lcom/music/bitchord/data/NerdStats;")
    for (method in
        stats.methods.filter {
            it.name in setOf("recordSource", "onSourceStream") && it.returnType == "V"
        }) {
        check(method.implementation!!.registerCount > method.parameterTypes.size)
        method.addInstructionsWithLabels(
            0,
            """
        invoke-static {}, $MIX->quiet()Z
        move-result v0
        if-eqz v0, :audible
        return-void
        :audible
        nop
    """,
        )
    }
}

private fun MethodReference.takes(vararg types: String) =
    parameterTypes.map(CharSequence::toString) == types.toList()

/**
 * Plays a song from its best enabled source first. Called by the native Telegram patch.
 *
 * The open of BitChord's playback source, above its cache, chooses which rendition a media period
 * reads, so a better copy has a cache entry of its own and nothing in the queue changes.
 */
internal fun BytecodePatchContext.addSourcePreparation() {
    addSourceVerification()
    val local = "Ljava/lang/ThreadLocal;"
    val context = "Lkotlin/coroutines/CoroutineContext;"
    val elements = mutableListOf<String>()
    classDefForEach { type ->
        if (
            type.interfaces.contains("Lkotlinx/coroutines/ThreadContextElement;") &&
                type.fields.any { it.type == local } &&
                type.methods.count {
                    it.name == "<init>" && it.takes("Ljava/lang/String;", local)
                } == 1
        ) {
            val update =
                type.methods.count { method ->
                    val calls = method.implementation?.instructions?.mapNotNull { it.mixCall() }
                    method.takes(context) &&
                        method.returnType == "Ljava/lang/Object;" &&
                        calls?.any { it.definingClass == local && it.name == "get" } == true &&
                        calls.any { it.definingClass == local && it.name == "set" }
                }
            val restore =
                type.methods.count { method ->
                    method.takes("Ljava/lang/Object;") &&
                        method.returnType == "V" &&
                        method.implementation?.instructions?.any {
                            it.mixCall()?.let { call ->
                                call.definingClass == local && call.name == "set"
                            } == true
                        } == true
                }
            if (update == 1 && restore == 1) elements.add(type.type)
        }
    }
    check(elements.size == 1) {
        "BitChord's native thread-local coroutine context changed: ${elements.size} matches"
    }
    val contextOwner = mutableClassDefBy(HOST)
    check(contextOwner.methods.none { it.name == "bitchordPreparedContext" }) {
        "Source preparation's native context bridge already exists"
    }
    val bridge =
        MutableMethod(
            ImmutableMethod(
                HOST,
                "bitchordPreparedContext",
                listOf("Ljava/lang/String;", local).map {
                    ImmutableMethodParameter(it, null, null)
                },
                context,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                null,
                null,
                ImmutableMethodImplementation(3, emptyList<Instruction>(), emptyList(), emptyList()),
            )
        )
    bridge.addInstructions(
        0,
        """
        new-instance v0, ${elements.single()}
        invoke-direct {v0, p0, p1}, ${elements.single()}-><init>(Ljava/lang/String;$local)V
        return-object v0
        """,
    )
    contextOwner.methods.add(bridge)
    fun needs(owner: String, name: String, parameters: List<String>) {
        check(
            classDefBy(owner).methods.any {
                it.name == name && it.parameterTypes.map(CharSequence::toString) == parameters
            }
        ) {
            "Source preparation needs $owner$name$parameters, which BitChord no longer has"
        }
    }
    fun needsField(owner: String, name: String) {
        check(classDefBy(owner).fields.any { it.name == name }) {
            "Source preparation needs $owner$name, which BitChord no longer has"
        }
    }
    val quality = "Lcom/music/bitchord/playback/QualityUpgrade;"
    val choice = "Lcom/music/bitchord/playback/StreamChoice;"
    val resolver = "Lcom/music/bitchord/data/sources/SourceResolver;"
    val stream = "Lcom/music/bitchord/data/sources/SourceStream;"
    val simple = "Landroidx/media3/datasource/cache/SimpleCache;"
    val party = "Lcom/music/bitchord/data/listentogether/ListenTogether;"
    val target = "Lcom/music/bitchord/data/sources/TrackMatcher\$Target;"
    val impl = "Lkotlin/coroutines/jvm/internal/ContinuationImpl;"
    needs(resolver, "targetIn", listOf(URI))
    needs(resolver, "requestForNow", emptyList())
    needs(
        "Lcom/music/bitchord/download/ManifestFile\$write\$2;",
        "<init>",
        listOf(
            "Ljava/lang/String;",
            "Z",
            "Lkotlin/jvm/functions/Function2;",
            "Ljava/io/File;",
            "Ljava/lang/String;",
            "Ljava/io/OutputStream;",
            "Ljava/util/Map;",
            "Lkotlin/coroutines/Continuation;",
        ),
    )
    needs(
        "Lcom/music/bitchord/download/ManifestFile\$write\$2;",
        "invokeSuspend",
        listOf("Ljava/lang/Object;"),
    )
    needs(resolver, "substituteForYouTube", listOf(target, impl))
    needsField(resolver, "INSTANCE")
    needsField(quality, "forced")
    needsField(quality, "refused")
    needsField(choice, "refusedSubstitutes")
    needsField(CACHE, "cache")
    needsField(CACHE, "INSTANCE")
    needs(CACHE, "cacheKeyOf", listOf(URI))
    // The real CacheDataSource bypasses cacheKeyOf and calls this factory directly. Freeze at
    // its return boundary, shared by real reads, CacheWriter and the public bookkeeping helper.
    val keyFactoryType = classDefBy(CACHE).fields.single { it.name == "keyFactory" }.type
    val keyFactory =
        mutableClassDefBy(keyFactoryType).methods.single {
            it.name == "buildCacheKey" &&
                it.parameterTypes.map(CharSequence::toString) == listOf(SPEC) &&
                it.returnType == "Ljava/lang/String;"
        }
    val keyCode = keyFactory.implementation!!.instructions.toList()
    val uriRead =
        keyCode.indices.single { index ->
            keyCode[index].opcode == Opcode.IGET_OBJECT &&
                keyCode[index].mixField()?.let {
                    it.definingClass == SPEC && it.name == "uri" && it.type == URI
                } == true
        }
    val uriRegister = (keyCode[uriRead] as TwoRegisterInstruction).registerA
    check(
        uriRegister < 16 &&
            keyCode.drop(uriRead + 1).none {
                it.opcode.setsRegister() &&
                    (it as? OneRegisterInstruction)?.registerA == uriRegister
            }
    ) {
        "Audio cache key factory no longer retains its original URI register"
    }
    val keyReturns = keyCode.indices.filter { keyCode[it].opcode == Opcode.RETURN_OBJECT }
    check(keyReturns.size == 2) {
        "Audio cache key factory return sites changed: ${keyReturns.size}"
    }
    for (index in keyReturns.reversed()) {
        val keyRegister = (keyCode[index] as OneRegisterInstruction).registerA
        check(keyRegister < 16 && keyRegister != uriRegister) {
            "Cache key return registers changed"
        }
        keyFactory.wrapObjectReturn(
            index,
            "invoke-static {v$uriRegister, v$keyRegister}, $PREPARE->cacheKey(${URI}Ljava/lang/String;)Ljava/lang/String;",
            keyRegister,
        )
    }
    val keyHelper =
        classDefBy(CACHE).methods.single {
            it.name == "cacheKeyOf" &&
                it.parameterTypes.map(CharSequence::toString) == listOf(URI) &&
                it.returnType == "Ljava/lang/String;"
        }
    check(
        keyHelper.implementation!!.instructions.count {
            it.mixCall()?.let { call ->
                call.definingClass == keyFactoryType && call.name == "buildCacheKey"
            } == true
        } == 1
    ) {
        "Public cache key lookup no longer uses the playback key factory"
    }
    needs(CACHE, "heldByYouTube", listOf("Ljava/lang/String;"))
    needs(CACHE, "isComplete", listOf("Ljava/lang/String;"))
    needs(CACHE, "isOffline", emptyList())
    needs(CACHE, "contentLengthOf", listOf(URI))
    needs(CACHE, "discardRendition", listOf(URI))
    needs(CACHE, "warmRange", listOf(URI, "J", "Lkotlin/coroutines/jvm/internal/SuspendLambda;"))
    needs(simple, "getCachedSpans", listOf("Ljava/lang/String;"))
    needs(simple, "getKeys", emptyList())
    needs(simple, "getContentMetadata", listOf("Ljava/lang/String;"))
    needs(simple, "removeResource", listOf("Ljava/lang/String;"))
    needs(
        "Landroidx/media3/datasource/cache/DefaultContentMetadata;",
        "get",
        listOf("Ljava/lang/String;"),
    )
    needs(
        "Lcom/music/bitchord/download/Downloads;",
        "verifiedSavedUri",
        listOf("Ljava/lang/String;"),
    )
    needs("Lcom/music/bitchord/playback/OriginalVersion;", "isPinned", listOf("Ljava/lang/String;"))
    needs("Lcom/music/bitchord/playback/DirectSourceGate;", "isActive", emptyList())
    needs(
        "Lcom/music/bitchord/playback/StreamContainer;",
        "manifestMimeOf",
        listOf("Ljava/lang/String;"),
    )
    needs("Lcom/music/bitchord/data/NerdStats;", "isLosslessMime", listOf("Ljava/lang/String;"))
    needs("Lcom/music/bitchord/data/sources/StreamFormat;", "isLossless", emptyList())
    needs("Lcom/music/bitchord/data/sources/MusicSource;", "getConfigId", emptyList())
    needs("Lcom/music/bitchord/data/sources/MusicSource;", "getKind", emptyList())
    needs("Landroidx/media3/common/Player;", "getNextMediaItemIndex", emptyList())
    needs("Landroidx/media3/common/Player;", "getMediaItemCount", emptyList())
    needs("Landroidx/media3/datasource/DataSpec;", "buildUpon", emptyList())
    needs("Landroidx/media3/datasource/DataSpec;", "subrange", listOf("J", "J"))
    needs("Landroidx/media3/datasource/DataSpec\$Builder;", "build", emptyList())
    needsField("Landroidx/media3/datasource/DataSpec\$Builder;", "uri")
    needsField("Landroidx/media3/datasource/DataSpec\$Builder;", "httpRequestHeaders")
    for (name in listOf("url", "format", "headers", "sourceConfigId")) needsField(stream, name)
    needsField(party, "state")
    needsField("$party".removeSuffix(";") + "\$State;", "code")
    needsField("Lcom/music/bitchord/data/sources/StreamFormat;", "kbps")
    needsField(HOST, "player")
    needsField(HOST, "spare")

    // A source-neutral queue-entry identity reaches every DataSpec without changing cache keys.
    val itemType = "Landroidx/media3/common/MediaItem;"
    val songType = "Lcom/music/bitchord/data/model/Song;"
    val connection = mutableClassDefBy("Lcom/music/bitchord/playback/PlayerConnectionKt;")
    val toItem =
        connection.methods.single {
            it.name == "toMediaItem" &&
                it.parameterTypes.map(CharSequence::toString) == listOf(songType) &&
                it.returnType == itemType
        }
    val itemCode = toItem.implementation!!.instructions.toList()
    for (index in
        itemCode.indices.filter { itemCode[it].opcode == Opcode.RETURN_OBJECT }.reversed()) {
        val register = (itemCode[index] as OneRegisterInstruction).registerA
        toItem.wrapObjectReturn(
            index,
            "invoke-static/range {v$register .. v$register}, $PREPARE->tag($itemType)$itemType",
            register,
        )
    }
    // notePlayed is inlined in this release. Its URI reads also feed the host's cache-folder lookup
    // and upgrade marker bookkeeping; all three must describe the rendition this entry chose.
    val current =
        mutableClassDefBy(HOST).methods.single {
            it.name == "onTrackBecameCurrent" && it.parameterTypes.firstOrNull() == itemType
        }
    val currentCode = current.implementation!!.instructions.toList()
    val uriReads =
        currentCode.indices.filter {
            currentCode[it].opcode == Opcode.IGET_OBJECT &&
                currentCode[it].mixField()?.let { field ->
                    field.definingClass ==
                        "Landroidx/media3/common/MediaItem\$LocalConfiguration;" &&
                        field.name == "uri" &&
                        field.type == URI
                } == true
        }
    check(uriReads.size == 3) { "Current-track cache URI reads changed: ${uriReads.size}" }
    for (index in uriReads.reversed()) {
        val register = (currentCode[index] as TwoRegisterInstruction).registerA
        current.addInstructions(
            index + 1,
            """
      invoke-static/range {v$register .. v$register}, $PREPARE->renditionUri($URI)$URI
      move-result-object v$register
    """,
        )
    }
    needs(itemType, "buildUpon", emptyList())
    needs("Landroidx/media3/common/MediaItem\$Builder;", "setUri", listOf("Ljava/lang/String;"))
    needsField("Landroidx/media3/common/MediaItem\$Builder;", "mimeType")
    needs("Landroidx/media3/common/Player;", "replaceMediaItem", listOf("I", itemType))

    val service = mutableClassDefBy(HOST)
    // This accessor contains the inlined Kotlin body. Both the pre-audition check and the final
    // check before replacement call it, so a pending same-ID hunt cannot replace a prepared entry.
    val swapPoint =
        service.methods.singleOrNull {
            it.name == "access\$swapPointFor" &&
                it.parameterTypes.map(CharSequence::toString) ==
                    listOf(HOST, "Ljava/lang/String;") &&
                it.returnType == "Lcom/music/bitchord/playback/PlaybackService\$SwapPoint;"
        } ?: throw PatchException("BitChord's legacy upgrade swap check not found")
    val swapCode =
        swapPoint.implementation?.instructions?.toList()
            ?: throw PatchException("BitChord's legacy upgrade swap check has no body")
    val currentRead =
        swapCode.indices.singleOrNull {
            swapCode[it].mixCall()?.let { call ->
                call.definingClass == "Landroidx/media3/common/Player;" &&
                    call.name == "getCurrentMediaItem" &&
                    call.parameterTypes.isEmpty() &&
                    call.returnType == itemType
            } == true
        } ?: throw PatchException("Upgrade swap's exact current-item read changed")
    if (
        swapCode.size <= currentRead + 4 ||
            swapCode[0].opcode != Opcode.IGET_OBJECT ||
            swapCode[0].mixField()?.name != "player" ||
            swapCode[1].opcode != Opcode.CONST_4 ||
            (swapCode[1] as NarrowLiteralInstruction).narrowLiteral != 0 ||
            swapCode[currentRead + 1].opcode != Opcode.MOVE_RESULT_OBJECT
    )
        throw PatchException("Upgrade swap's current-item/null-result check changed")
    val nullRegister = (swapCode[1] as OneRegisterInstruction).registerA
    val itemRegister = (swapCode[currentRead + 1] as OneRegisterInstruction).registerA
    val scratch =
        (swapCode[currentRead + 4] as? TwoRegisterInstruction)
            ?.takeIf {
                swapCode[currentRead + 2].opcode == Opcode.IF_NEZ &&
                    swapCode[currentRead + 3].opcode in
                        setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32) &&
                    swapCode[currentRead + 4].opcode == Opcode.IGET_OBJECT &&
                    it.registerB == itemRegister &&
                    swapCode[currentRead + 4].mixField()?.name == "mediaId"
            }
            ?.registerA ?: throw PatchException("Upgrade swap's scratch register changed")
    if (scratch == nullRegister || scratch == itemRegister || scratch >= 256)
        throw PatchException("Upgrade swap's guard registers changed")
    for (caller in listOf("\$swapIn\$at\$1;", "\$swapIn\$6;")) {
        val resume =
            classDefByOrNull(HOST.removeSuffix(";") + caller)?.methods?.singleOrNull {
                it.name == "invokeSuspend" &&
                    it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;")
            }
        if (
            resume?.implementation?.instructions?.any {
                it.mixCall()?.let { call ->
                    call.definingClass == HOST && call.name == "access\$swapPointFor"
                } == true
            } != true
        )
            throw PatchException("Legacy upgrade no longer rechecks its current entry: $caller")
    }
    swapPoint.addInstructionsWithLabels(
        currentRead + 2,
        """
        invoke-static/range {v$itemRegister .. v$itemRegister}, $PREPARE->preparedEntry($itemType)Z
        move-result v$scratch
        if-eqz v$scratch, :ordinaryUpgrade
        return-object v$nullRegister
        :ordinaryUpgrade
        nop
        """,
    )
    service.methods
        .single { it.name == "onCreate" && it.parameterTypes.isEmpty() }
        .addInstruction(0, "invoke-static/range {p0 .. p0}, $PREPARE->service(Ljava/lang/Object;)V")
    service.methods
        .single { it.name == "onDestroy" && it.parameterTypes.isEmpty() }
        .addInstruction(0, "invoke-static/range {p0 .. p0}, $PREPARE->release(Ljava/lang/Object;)V")

    // The queue moved or playing started.
    val around =
        service.methods.singleOrNull {
            it.name == "prefetchAround" &&
                it.parameterTypes.map(CharSequence::toString) ==
                    listOf("Landroidx/media3/exoplayer/ExoPlayer;")
        } ?: throw PatchException("BitChord's queue read-ahead not found")
    check(
        around.implementation!!.instructions.any { it.mixCall()?.name == "getNextMediaItemIndex" }
    ) {
        "BitChord's queue read-ahead no longer reads the queue"
    }
    around.addInstruction(
        0,
        "invoke-static/range {p0 .. p1}, $PREPARE->queueChanged(Ljava/lang/Object;Ljava/lang/Object;)V",
    )

    // Whether anyone's listening, and whether what they're listening to failed.
    val listeners = mutableListOf<String>()
    classDefForEach { type ->
        val calls =
            type.methods.any { method ->
                method.name == "onIsPlayingChanged" &&
                    method.parameterTypes.map(CharSequence::toString) == listOf("Z") &&
                    method.implementation?.instructions?.any {
                        it.mixCall()?.let { m ->
                            m.definingClass == HOST && m.name == "prefetchAround"
                        } == true
                    } == true
            }
        if (calls) listeners += type.type
    }
    val listener =
        mutableClassDefBy(
            listeners.singleOrNull()
                ?: throw PatchException("BitChord's playback listener not found: $listeners")
        )
    listener.methods
        .single { it.name == "onIsPlayingChanged" }
        .addInstruction(0, "invoke-static/range {p1 .. p1}, $PREPARE->playing(Z)V")
    listener.methods
        .singleOrNull {
            it.name == "onPlayerError" &&
                it.parameterTypes.map(CharSequence::toString) ==
                    listOf("Landroidx/media3/common/PlaybackException;")
        }
        ?.addInstruction(0, "invoke-static {}, $PREPARE->failed()V")
        ?: throw PatchException("BitChord's playback error listener not found")

    // The open above the cache: found by asking for the same things BitChord settles there.
    val opens = mutableListOf<Triple<String, String, Int>>()
    classDefForEach { type ->
        for (method in type.methods) {
            if (method.name != "open") continue
            if (method.parameterTypes.map(CharSequence::toString) != listOf(SPEC)) continue
            val code = method.implementation?.instructions?.toList() ?: continue
            fun says(text: String) =
                code.indexOfFirst {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == text
                }
            val read = says("watch_read")
            if (read < 0 || says("automix_opus") < 0 || says("file") < 0 || says("content") < 0)
                continue
            for (index in read until code.size) {
                val call = code[index].mixCall() ?: continue
                if (call.definingClass == DS && call.name == "open" && call.takes(SPEC))
                    opens += Triple(type.type, method.name, index)
            }
        }
    }
    val (ownerType, _, at) =
        opens.singleOrNull()
            ?: throw PatchException("BitChord's playback open above the cache not found: $opens")
    val open =
        mutableClassDefBy(ownerType).methods.single {
            it.name == "open" && it.parameterTypes.map(CharSequence::toString) == listOf(SPEC)
        }
    val call = open.implementation!!.instructions.toList()[at] as FiveRegisterInstruction
    val self = open.implementation!!.registerCount - 2
    check(self < 16 && call.registerC < 16 && call.registerD < 16 && call.registerCount == 2) {
        "BitChord's playback open registers changed"
    }
    open.replaceInstruction(
        at,
        "invoke-static {v$self, v${call.registerC}, v${call.registerD}}, $PREPARE->open(Ljava/lang/Object;$DS$SPEC)J",
    )
    println("Plays the best enabled source first, in a cache entry of its own")
}

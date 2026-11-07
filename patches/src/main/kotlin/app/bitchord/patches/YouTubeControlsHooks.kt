package app.bitchord.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.*
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.*

private const val YT_POLICY = "Lapp/bitchord/telegram/YouTubePolicy;"
private const val YT_KIND = "Lcom/music/bitchord/data/sources/SourceKind;"
private const val YT_REGISTRY = "Lcom/music/bitchord/data/sources/SourceRegistry;"
private const val YT_CONFIG = "Lcom/music/bitchord/data/sources/SourceConfig;"
private const val YT_TEXT = "Ljava/lang/String;"
private const val YT_CONTINUATION = "Lkotlin/coroutines/jvm/internal/ContinuationImpl;"
private const val YT_FALLBACK = "Lapp/bitchord/telegram/SourceFallback;"
private const val YT_TARGET = "Lcom/music/bitchord/data/sources/TrackMatcher\$Target;"
private const val YT_SERVICE = "Lcom/music/bitchord/playback/PlaybackService;"
private const val YT_SPEC = "Landroidx/media3/datasource/DataSpec;"

/** Guards source selection, buffered audio and catalogue requests using native source settings. */
internal fun BytecodePatchContext.addYouTubeControls() {
    val metadata = "Landroidx/media3/common/MediaMetadata;"
    val metadataBuilder = "Landroidx/media3/common/MediaMetadata\$Builder;"
    val itemBuilder = "Landroidx/media3/common/MediaItem\$Builder;"
    val metadataClass = mutableClassDefBy(metadata)
    check(
        metadataClass.methods.any {
            it.name == "buildUpon" &&
                it.parameterTypes.isEmpty() &&
                it.returnType == metadataBuilder
        } &&
            metadataClass.methods.any {
                it.name == "<init>" && it.parameterTypes == listOf(metadataBuilder)
            }
    ) {
        "Local playback metadata builder changed"
    }
    check(
        mutableClassDefBy(metadataBuilder).fields.any {
            it.name == "extras" && it.type == "Landroid/os/Bundle;"
        } &&
            mutableClassDefBy(itemBuilder).fields.any {
                it.name == "mediaMetadata" && it.type == "Ljava/lang/Object;"
            }
    ) {
        "Local playback metadata fields changed"
    }
    check(
        mutableClassDefBy(itemBuilder).fields.any {
            it.name == "mimeType" && it.type == "Ljava/lang/Object;"
        }
    ) {
        "Local playback MIME field changed"
    }
    val localBuild =
        mutableClassDefBy(itemBuilder)
            .methods
            .singleOrNull {
                it.name == "build" &&
                    it.parameterTypes.isEmpty() &&
                    it.returnType == "Landroidx/media3/common/MediaItem;"
            }
            ?.implementation
            ?.instructions
            ?.toList()
            .orEmpty()
    val metadataReads =
        localBuild.indices.filter { at ->
            val field = (localBuild[at] as? ReferenceInstruction)?.reference as? FieldReference
            field?.definingClass == itemBuilder && field.name == "mediaMetadata"
        }
    check(metadataReads.size == 1 && metadataReads.single() + 1 < localBuild.size) {
        "Local playback metadata read changed"
    }
    val read = metadataReads.single()
    val cast = localBuild[read + 1]
    check(
        cast.opcode == Opcode.CHECK_CAST &&
            ((cast as? ReferenceInstruction)?.reference as? TypeReference)?.type == metadata &&
            (cast as? OneRegisterInstruction)?.registerA ==
                (localBuild[read] as? TwoRegisterInstruction)?.registerA
    ) {
        "Local playback metadata cast changed"
    }
    fun Instruction.call() = (this as? ReferenceInstruction)?.reference as? MethodReference
    fun Instruction.field() = (this as? ReferenceInstruction)?.reference as? FieldReference
    fun Method.code() = implementation?.instructions?.toList().orEmpty()
    fun Instruction.reg() = (this as OneRegisterInstruction).registerA
    fun List<CharSequence>.names() = map { it.toString() }

    fun methodOf(type: String, name: String, params: List<String>, what: String): MutableMethod {
        val clazz =
            try {
                mutableClassDefBy(type)
            } catch (e: Exception) {
                error("$what not found")
            }
        val matches =
            clazz.methods.filter { it.name == name && it.parameterTypes.names() == params }
        check(matches.size == 1) { "$what changed (${matches.size})" }
        return matches.single()
    }

    fun scan(match: (Method) -> Boolean): List<Pair<ClassDef, Method>> {
        val found = mutableListOf<Pair<ClassDef, Method>>()
        classDefForEach { clazz ->
            if (clazz.type.startsWith("Lcom/music/bitchord/"))
                clazz.methods.filter { match(it) }.forEach { found += clazz to it }
        }
        return found
    }

    fun policyCalls(method: Method) = method.code().any { it.call()?.definingClass == YT_POLICY }

    fun youtubeKind(i: Instruction) =
        i.opcode == Opcode.SGET_OBJECT &&
            i.field()?.let { it.definingClass == YT_KIND && it.name == "YOUTUBE" } == true

    val application =
        methodOf(
            "Lcom/music/bitchord/BitChordApplication;",
            "onCreate",
            emptyList(),
            "Application startup",
        )
    if (!policyCalls(application)) {
        check(application.accessFlags and 8 == 0 && application.implementation != null) {
            "Application startup changed"
        }
        application.addInstructions(
            0,
            "invoke-static/range {p0 .. p0}, $YT_POLICY->init(Landroid/content/Context;)V",
        )
    }

    for ((getter, returns) in listOf("getKind" to YT_KIND, "getEnabled" to "Z")) {
        val method = methodOf(YT_CONFIG, getter, emptyList(), "Source configuration $getter")
        check(method.returnType == returns && method.accessFlags and 1 != 0) {
            "Source configuration $getter changed"
        }
    }

    /** Inserts a no-argument gate call first in a method. */
    fun gate(type: String, name: String, params: List<String>, what: String, call: String) {
        val method = methodOf(type, name, params, what)
        if (policyCalls(method)) return
        method.addInstructions(0, "invoke-static {}, $YT_POLICY->$call()V")
    }

    // 1. The native exit that ignored a request to switch YouTube off.
    val setter = methodOf(YT_REGISTRY, "setEnabled", listOf(YT_TEXT, "Z"), "Source switch handler")
    val applied = policyCalls(setter)
    if (!applied) {
        val body = setter.code()
        val kinds = body.indices.filter { youtubeKind(body[it]) }
        check(kinds.size == 1) { "Source switch YouTube check changed (${kinds.size})" }
        val exit = kinds.single() + 2
        check(
            exit < body.size - 1 &&
                body[exit - 1].opcode == Opcode.IF_NE &&
                body[exit].opcode == Opcode.RETURN_VOID
        ) {
            "Source switch YouTube exit changed"
        }
        check(setter.accessFlags and 8 != 0 && setter.implementation!!.registerCount > 2) {
            "Source switch has no free register"
        }
        val resume = body[exit + 1]
        val scratch = (resume as? OneRegisterInstruction)?.registerA
        check(
            resume.opcode == Opcode.SGET_OBJECT &&
                resume.field()?.let { it.definingClass == YT_REGISTRY && it.name == "configs" } ==
                    true &&
                scratch != null &&
                scratch < setter.implementation!!.registerCount - 2
        ) {
            "Source confirmation has no register overwritten by the resumed path"
        }
        setter.addInstructionsWithLabels(
            exit,
            """
            invoke-static/range {p0 .. p0}, $YT_POLICY->confirmDisable($YT_TEXT)Z
            move-result v$scratch
            if-nez v$scratch, :youtube_confirmed
            """,
            ExternalLabel("youtube_confirmed", body[exit + 1]),
        )
    }

    // 2. The start-up repair that switched YouTube on again, and the first place with a Context.
    val loaders = scan { m ->
        m.code().any {
            it.opcode == Opcode.SPUT_OBJECT &&
                it.field()?.let { f -> f.definingClass == YT_REGISTRY && f.name == "prefs" } == true
        }
    }
    check(loaders.size == 1) { "Saved sources loader not unique (${loaders.size})" }
    val loader =
        methodOf(
            loaders.single().first.type,
            loaders.single().second.name,
            loaders.single().second.parameterTypes.names(),
            "Saved sources loader",
        )
    if (!policyCalls(loader)) {
        val body = loader.code()
        val repairs =
            body.indices.filter { at ->
                youtubeKind(body[at]) &&
                    (at + 1 until minOf(at + 6, body.size)).let { next ->
                        next.any { body[it].call()?.name == "getEnabled" } &&
                            next.any { body[it].opcode == Opcode.IF_NEZ }
                    }
            }
        check(repairs.size == 1) { "Saved sources repair changed (${repairs.size})" }
        val read =
            (repairs.single() + 1 until body.size).first { body[it].call()?.name == "getEnabled" }
        check(
            body[read + 1].opcode == Opcode.MOVE_RESULT &&
                body[read + 2].opcode == Opcode.IF_NEZ &&
                body[read + 2].reg() == body[read + 1].reg()
        ) {
            "Saved sources repair test changed"
        }
        val opens =
            body.indices.filter { at ->
                body[at].call()?.let {
                    it.definingClass == "Lcom/music/bitchord/auth/EncryptedPrefs;" &&
                        it.name == "open" &&
                        it.parameterTypes.names().firstOrNull() == "Landroid/content/Context;"
                } == true
            }
        check(opens.size == 1) { "Saved sources store opening changed (${opens.size})" }
        val open = body[opens.single()] as FiveRegisterInstruction
        val publishes =
            body.indices.filter { at ->
                body[at].call()?.let { it.definingClass == YT_REGISTRY && it.name == "publish" } ==
                    true
            }
        check(publishes.size == 1 && publishes.single() > read) {
            "Saved sources publish changed (${publishes.size})"
        }
        // The flag now always reads as set, so the saved off state is kept.
        loader.addInstructions(publishes.single() + 1, "invoke-static {}, $YT_POLICY->loaded()V")
        loader.replaceInstruction(read + 1, "const/16 v${body[read + 1].reg()}, 0x1")
        loader.addInstructions(
            opens.single(),
            "invoke-static {v${open.registerD}}, $YT_POLICY->init(Landroid/content/Context;)V",
        )
    }

    // 3. The Sources card that passed no switch callback for YouTube.
    if (!applied) {
        val cards = scan { m ->
            val body = m.code()
            body.any {
                it.call()?.let { c ->
                    c.definingClass.endsWith("/SearchScreenKt;") && c.name == "SourceRow"
                } == true
            } && body.any { youtubeKind(it) }
        }
        check(cards.size == 1) { "Sources card not unique (${cards.size})" }
        val card =
            methodOf(
                cards.single().first.type,
                cards.single().second.name,
                cards.single().second.parameterTypes.names(),
                "Sources card",
            )
        val cardBody = card.code()
        val tests = cardBody.indices.filter { youtubeKind(cardBody[it]) }
        check(tests.size == 1) { "Sources card YouTube test changed (${tests.size})" }
        val test = tests.single()
        if (cardBody[test].opcode == Opcode.SGET_OBJECT) {
            check(
                cardBody[test + 1].opcode == Opcode.IF_NE &&
                    cardBody.drop(test).any { it.call()?.name == "SourceRow" }
            ) {
                "Sources card YouTube test changed"
            }
            // The test never matches now, so YouTube takes the branch every other source takes.
            card.replaceInstruction(test, "const/16 v${cardBody[test].reg()}, 0x0")
        }
    }

    // 4. Playback: the open above BitChord's cache, which every audible request passes through.
    val spec = "Landroidx/media3/datasource/DataSpec;"
    val source = "Landroidx/media3/datasource/DataSource;"
    val openers = mutableListOf<Pair<String, Int>>()
    classDefForEach { clazz ->
        if (!clazz.type.startsWith("Lcom/music/bitchord/")) return@classDefForEach
        for (method in clazz.methods) {
            if (method.name != "open" || method.parameterTypes.names() != listOf(spec)) continue
            val code = method.code()
            fun says(text: String) =
                code.indexOfFirst {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == text
                }
            val read = says("watch_read")
            if (read < 0 || says("automix_opus") < 0 || says("file") < 0 || says("content") < 0)
                continue
            for (at in read until code.size) {
                val call = code[at].call() ?: continue
                // The source-first patch may already have replaced the call; it calls this policy.
                if (
                    (call.definingClass == source &&
                        call.name == "open" &&
                        call.parameterTypes.names() == listOf(spec)) ||
                        ((call.definingClass == "Lapp/bitchord/telegram/SourceFirst;" ||
                            call.definingClass == YT_POLICY) && call.name == "open")
                )
                    openers += clazz.type to at
            }
        }
    }
    check(openers.size == 1) { "Playback open above the cache not found (${openers.size})" }
    val opener = methodOf(openers.single().first, "open", listOf(spec), "Playback data source")
    val opening = opener.code()
    val replaced =
        opening.any {
            it.call()?.let { c ->
                c.definingClass == YT_POLICY ||
                    (c.definingClass == "Lapp/bitchord/telegram/SourceFirst;" && c.name == "open")
            } == true
        }
    if (!replaced) {
        val at = openers.single().second
        val call = opening[at] as FiveRegisterInstruction
        check(call.registerCount == 2 && call.registerC < 16 && call.registerD < 16) {
            "Playback open registers changed"
        }
        opener.replaceInstruction(
            at,
            "invoke-static {v${call.registerC}, v${call.registerD}}, " +
                "$YT_POLICY->open($source$spec)J",
        )
    }

    // 5. Audio that is already open: the player is watched, and a source change re-checks it.
    val players =
        mutableClassDefBy("Lcom/music/bitchord/playback/PlaybackService;").methods.filter {
            it.name == "buildPlayer" && it.returnType.startsWith("Landroidx/media3/exoplayer/")
        }
    check(players.size == 1) { "Player builder not unique (${players.size})" }
    val builder = players.single()
    if (!policyCalls(builder)) {
        val body = builder.code()
        val returns = body.indices.filter { body[it].opcode == Opcode.RETURN_OBJECT }
        check(returns.size == 1 && returns.single() > 1) {
            "Player builder returns changed (${returns.size})"
        }
        val end = returns.single()
        check(
            body[end - 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
                body[end - 2].call()?.let {
                    it.definingClass == "Landroidx/media3/exoplayer/ExoPlayer\$Builder;" &&
                        it.name == "build"
                } == true &&
                body[end - 1].reg() == body[end].reg()
        ) {
            "Player builder no longer returns the built player"
        }
        builder.addInstructions(
            end,
            "invoke-static/range {v${body[end].reg()} .. v${body[end].reg()}}, " +
                "$YT_POLICY->observe(Landroidx/media3/common/Player;)V",
        )
    }
    val publisher =
        methodOf(YT_REGISTRY, "publish", listOf("Ljava/util/ArrayList;", "Z"), "Source publisher")
    if (!policyCalls(publisher)) {
        val body = publisher.code()
        val updates =
            body.indices.filter { at ->
                body[at].call()?.let {
                    it.definingClass == "Lkotlinx/coroutines/flow/StateFlowImpl;" &&
                        it.name == "updateState"
                } == true
            }
        check(updates.size == 1) { "Source publisher state update changed (${updates.size})" }
        check(
            updates.single() + 1 < body.size &&
                body[updates.single() + 1].opcode !in
                    setOf(Opcode.MOVE_RESULT, Opcode.MOVE_RESULT_OBJECT, Opcode.MOVE_RESULT_WIDE)
        ) {
            "Source publisher now consumes the state update result"
        }
        // The new flags are visible to readers from here on.
        publisher.addInstructions(
            updates.single() + 1,
            "invoke-static {}, $YT_POLICY->sourcesChanged()V",
        )
    }

    // 6. Catalogue: every YouTube list request goes through these.
    val innertube = "Lcom/music/bitchord/data/innertube/Innertube;"
    gate(
        innertube,
        "withRetry",
        listOf("I", "Lkotlin/jvm/functions/Function1;", YT_CONTINUATION),
        "YouTube retry wrapper",
        "catalogueGate",
    )
    gate(
        innertube,
        "postMusic",
        listOf(
            YT_TEXT,
            "Ljava/util/Map;",
            YT_TEXT,
            "Lkotlin/jvm/functions/Function1;",
            "Lkotlin/coroutines/Continuation;",
        ),
        "YouTube request sender",
        "catalogueGate",
    )
    gate(
        innertube,
        "fetchVisitorData",
        listOf(YT_CONTINUATION),
        "YouTube visitor request",
        "catalogueGate",
    )
    val sourceResolver = "Lcom/music/bitchord/data/sources/SourceResolver;"
    val bestMethods =
        mutableClassDefBy(sourceResolver).methods.filter {
            it.name == "bestAcross\$app_prodRelease\$default"
        }
    check(bestMethods.size == 1) { "Source fallback best lookup changed" }
    val bestMethod = bestMethods.single()
    val bestTypes = bestMethod.parameterTypes.names()
    val extendedBest = bestTypes.size == 13
    check(
        (bestTypes.size == 11 || extendedBest) &&
            bestMethod.accessFlags and 8 != 0 &&
            bestMethod.returnType == "Ljava/lang/Object;" &&
            bestTypes.take(4) ==
                listOf(
                    sourceResolver,
                    "Ljava/util/List;",
                    YT_TARGET,
                    "Lcom/music/bitchord/data/sources/StreamRequest;",
                ) &&
            bestTypes[4] == "Z" &&
            (if (extendedBest) bestTypes[5] == "Z" && bestTypes[6] == "I"
            else bestTypes[5] == "I") &&
            bestTypes.takeLast(2) == listOf(YT_CONTINUATION, "I")
    ) {
        "Source fallback best lookup signature changed"
    }
    val substitute =
        methodOf(
            sourceResolver,
            "substituteForYouTube",
            listOf(YT_TARGET, YT_CONTINUATION),
            "Source fallback defaults",
        )
    check(
        substitute.code().any {
            it is NarrowLiteralInstruction && it.narrowLiteral == (if (extendedBest) 4088 else 1016)
        }
    ) {
        "Source fallback best lookup defaults changed"
    }

    // 7. Ranking: the five checks that ask where YouTube stands among the enabled sources.
    fun ranking(method: MutableMethod, what: String, marker: (Instruction) -> Boolean) {
        val body = method.code()
        if (body.any { it.call()?.definingClass == YT_FALLBACK }) return
        val asks =
            body.indices.filter { at ->
                body[at].call()?.let {
                    it.definingClass == YT_REGISTRY && it.name == "activeForPlayback"
                } == true
            }
        if (asks.isEmpty()) {
            val helpers =
                body.filter {
                    it.call()?.let { call ->
                        call.definingClass == "Lcom/music/bitchord/data/sources/SourceResolver;" &&
                            call.name == "canSubstituteForYouTube" &&
                            call.parameterTypes.isEmpty() &&
                            call.returnType == "Z"
                    } == true && it.opcode == Opcode.INVOKE_STATIC
                }
            check(helpers.size == 1 && body.any(marker)) {
                "$what YouTube ranking helper changed (${helpers.size})"
            }
            val helper = helpers.single().call()!!
            val methods =
                mutableClassDefBy(helper.definingClass).methods.filter {
                    it.name == helper.name && it.parameterTypes.isEmpty() && it.returnType == "Z"
                }
            check(methods.size == 1) { "$what YouTube ranking helper is ambiguous" }
            val target = methods.single()
            check(target.code().none { it.call()?.name == helper.name }) {
                "$what YouTube ranking helper is recursive"
            }
            ranking(target, "$what helper") { instruction -> youtubeKind(instruction) }
            return
        }
        check(asks.size == 1) { "$what YouTube ranking check changed (${asks.size})" }
        val at = asks.single()
        check(
            body[at].opcode == Opcode.INVOKE_STATIC &&
                (at + 1 until minOf(at + 21, body.size)).any { youtubeKind(body[it]) } &&
                body.any(marker)
        ) {
            "$what YouTube ranking check changed (${asks.size})"
        }
        method.replaceInstruction(
            at,
            "invoke-static {}, $YT_FALLBACK->playbackSources()Ljava/util/ArrayList;",
        )
    }

    fun says(text: String): (Instruction) -> Boolean = {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == text
    }

    fun calls(name: String): (Instruction) -> Boolean = { it.call()?.name == name }

    val cacheType = "Lcom/music/bitchord/playback/AudioCache;"
    val keyFactoryType =
        mutableClassDefBy(cacheType).fields.singleOrNull { it.name == "keyFactory" }?.type
            ?: error("Audio cache key factory not found")
    val keyFactories =
        mutableClassDefBy(keyFactoryType).methods.filter {
            it.name == "buildCacheKey" &&
                it.parameterTypes.names() == listOf(YT_SPEC) &&
                it.returnType == YT_TEXT
        }
    check(keyFactories.size == 1) { "Audio cache key factory changed (${keyFactories.size})" }
    ranking(keyFactories.single(), "Audio cache key", says("#alt"))

    val resolverContinuation = listOf(YT_TARGET, YT_CONTINUATION)
    ranking(
        methodOf(
            "Lcom/music/bitchord/data/sources/SourceResolver;",
            "substituteForYouTube",
            resolverContinuation,
            "YouTube substitute lookup",
        ),
        "YouTube substitute lookup",
        calls("rankedAbove"),
    )
    ranking(
        methodOf(
            "Lcom/music/bitchord/data/sources/SourceResolver;",
            "prefetchSubstitute",
            resolverContinuation,
            "Substitute read-ahead lookup",
        ),
        "Substitute read-ahead lookup",
        calls("rankedAbove"),
    )

    val service = mutableClassDefBy(YT_SERVICE)
    val resolverType =
        service.fields.singleOrNull { it.name == "streamResolver" }?.type
            ?: error("Stream resolver not found")
    val resolvers =
        mutableClassDefBy(resolverType).methods.filter {
            it.name == "resolveDataSpec" && it.parameterTypes.names() == listOf(YT_SPEC)
        }
    check(resolvers.size == 1) { "Stream resolver changed (${resolvers.size})" }
    ranking(resolvers.single(), "Stream resolver", says("Stream resolution timed out for "))

    ranking(
        methodOf(
            YT_SERVICE,
            "prefetchAround",
            listOf("Landroidx/media3/exoplayer/ExoPlayer;"),
            "Queue read-ahead",
        ),
        "Queue read-ahead",
        calls("getNextMediaItemIndex"),
    )

    val revertMethods = service.methods.filter { it.name == "replaceWithYouTube" }
    if (revertMethods.isNotEmpty()) {
        check(revertMethods.size == 1) { "YouTube source reversion entry changed" }
        val revert = revertMethods.single()
        check(
            revert.parameterTypes.names() ==
                listOf(
                    "Landroidx/media3/common/MediaItem;",
                    "Landroid/net/Uri;",
                    "J",
                    "Z",
                    YT_TEXT,
                ) &&
                revert.returnType == "Z" &&
                revert.accessFlags and 8 == 0 &&
                revert.implementation!!.registerCount > 7 &&
                revert.code().any { instruction ->
                    ((instruction as? ReferenceInstruction)?.reference as? StringReference)
                        ?.string
                        ?.contains("falling back to YouTube audio") == true
                }
        ) {
            "YouTube source reversion behavior changed"
        }
        val first = revert.implementation!!.instructions.first()
        revert.addInstructionsWithLabels(
            0,
            """
            invoke-static {}, $YT_POLICY->playbackBlocked()Z
            move-result v0
            if-eqz v0, :youtube_reversion_allowed
            const/4 v0, 0x0
            return v0
        """,
            ExternalLabel("youtube_reversion_allowed", first),
        )
    }

    // 8. Resolution: other sources are asked before BitChord races them against YouTube.
    val entry =
        methodOf(
            YT_SERVICE,
            "access\$resolveWithModulePriority",
            listOf(YT_SERVICE, YT_TEXT, YT_TARGET, YT_CONTINUATION),
            "Stream resolution entry",
        )
    if (entry.code().none { it.call()?.definingClass == YT_FALLBACK }) {
        val body = entry.code()
        check(
            entry.accessFlags and 8 != 0 &&
                entry.returnType == "Ljava/lang/Object;" &&
                entry.implementation!!.registerCount > 4 &&
                body.first().opcode == Opcode.MOVE_OBJECT_FROM16 &&
                body.first().reg() == 0
        ) {
            "Stream resolution entry changed"
        }
        entry.addInstructionsWithLabels(
            0,
            """
            invoke-static/range {p1 .. p2}, $YT_FALLBACK->resolve(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v0
            if-eqz v0, :native_race
            return-object v0
            :native_race
            nop
            """,
        )
    }
    println("Added the YouTube Music on/off choice to Sources")
}

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
private const val HOST = "Lcom/music/bitchord/playback/PlaybackService;"
private const val CACHE = "Lcom/music/bitchord/playback/AudioCache;"
private const val ANALYZER = "Lcom/music/bitchord/playback/smart/TrackAnalyzer;"
private const val SETTINGS = "Lcom/music/bitchord/data/settings/AppSettings;"
private const val SPEC = "Landroidx/media3/datasource/DataSpec;"
private const val DS = "Landroidx/media3/datasource/DataSource;"
private const val URI = "Landroid/net/Uri;"

private fun Instruction.mixCall() = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.mixField() = (this as? ReferenceInstruction)?.reference as? FieldReference

@Suppress("unused")
val sourceAutomixPatch =
    bytecodePatch(
        name = "Source-independent AutoMix",
        description =
            "Measures the selected playable source, verifies automatic replacements, preserves playback speed and adds a native, pair-specific transition timeline with volume, EQ, filter controls and preview. BitChord 1.9.",
        default = true,
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
        dependsOn(extrasSettingsPatch)
        execute {
            fun add(
                owner: String,
                name: String,
                args: List<String>,
                result: String,
                registers: Int,
                code: String,
            ) {
                val type = mutableClassDefBy(owner)
                check(type.methods.none { it.name == name }) {
                    "AutoMix bridge already exists: $name"
                }
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
                mutableClassDefBy("Lcom/music/bitchord/data/sources/SourceRegistry;")
                    .methods
                    .single { it.name == "activeForPlayback" }
            check(ranked.returnType == "Ljava/util/ArrayList;")
            val rankCode = ranked.implementation!!.instructions.toList()
            for (index in
                rankCode.indices
                    .filter { rankCode[it].opcode == Opcode.RETURN_OBJECT }
                    .reversed()) {
                val register = (rankCode[index] as OneRegisterInstruction).registerA
                ranked.addInstructions(
                    index,
                    """
                invoke-static/range {v$register .. v$register}, $PREF->rank(Ljava/util/ArrayList;)Ljava/util/ArrayList;
                move-result-object v$register
            """,
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
            val speed = mutableClassDefBy(SETTINGS).fields.single { it.name == "playbackSpeed" }
            check(speed.type == "Lkotlinx/coroutines/flow/StateFlowImpl;") {
                "Speed flow ABI changed"
            }
            add(
                SETTINGS,
                "bitchordAutomixSpeed",
                emptyList(),
                "F",
                1,
                """
            sget-object v0, $SETTINGS->playbackSpeed:${speed.type}
            invoke-virtual {v0}, ${speed.type}->getValue()Ljava/lang/Object;
            move-result-object v0
            check-cast v0, Ljava/lang/Number;
            invoke-virtual {v0}, Ljava/lang/Number;->floatValue()F
            move-result v0
            return v0
        """,
            )
            add(
                HOST,
                "bitchordAutomixSpeed",
                emptyList(),
                "F",
                1,
                """
            invoke-static {}, $SETTINGS->bitchordAutomixSpeed()F
            move-result v0
            return v0
        """,
            )
            add(
                HOST,
                "bitchordAutomixEnabled",
                emptyList(),
                "Z",
                1,
                """
            invoke-static {}, $SETTINGS->getSmartFadeEnabled()Lkotlinx/coroutines/flow/StateFlowImpl;
            move-result-object v0
            invoke-virtual {v0}, Lkotlinx/coroutines/flow/StateFlowImpl;->getValue()Ljava/lang/Object;
            move-result-object v0
            check-cast v0, Ljava/lang/Boolean;
            invoke-virtual {v0}, Ljava/lang/Boolean;->booleanValue()Z
            move-result v0
            return v0
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
            val restore =
                "Lcom/music/bitchord/playback/smart/TrackAnalyzer${'$'}${'$'}ExternalSyntheticLambda18;"
            check(
                mutableClassDefBy(restore).methods.any {
                    it.name == "<init>" &&
                        it.parameterTypes == listOf(ANALYZER, "Ljava/lang/String;")
                }
            )
            add(
                HOST,
                "bitchordAutomixRestore",
                listOf("Ljava/lang/Object;", "Ljava/lang/String;"),
                "V",
                3,
                """
            check-cast p0, $ANALYZER
            new-instance v0, $restore
            invoke-direct {v0, p0, p1}, $restore-><init>(${ANALYZER}Ljava/lang/String;)V
            invoke-virtual {v0}, $restore->run()V
            return-void
        """,
            )

            val request =
                mutableClassDefBy(
                        "Lcom/music/bitchord/playback/PlaybackService${'$'}${'$'}ExternalSyntheticLambda25;"
                    )
                    .methods
                    .single { it.name == "invoke" }
            check(request.parameterTypes == listOf("Ljava/lang/Object;", "Ljava/lang/Object;"))
            check(
                request.implementation!!.instructions.any {
                    it.mixField()?.name == "sourceAnalysisUris"
                }
            ) {
                "AutoMix request arm changed"
            }
            request.addInstructionsWithLabels(
                0,
                """
            invoke-static/range {p0 .. p2}, $MIX->request(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z
            move-result v0
            if-eqz v0, :original
            sget-object v0, Lkotlin/Unit;->INSTANCE:Lkotlin/Unit;
            return-object v0
            :original
            nop
        """,
            )
            val analyzer = mutableClassDefBy(ANALYZER)
            val copy = analyzer.methods.single { it.name == "copyToRead" }
            val copyType = "Lcom/music/bitchord/playback/smart/TrackAnalyzer${'$'}Copy;"
            check(copy.parameterTypes == listOf(URI, "D") && copy.returnType == copyType)
            val replacement =
                MutableMethod(
                    ImmutableMethod(
                        ANALYZER,
                        copy.name,
                        copy.parameters,
                        copy.returnType,
                        copy.accessFlags,
                        copy.annotations,
                        copy.hiddenApiRestrictions,
                        ImmutableMethodImplementation(
                            8,
                            emptyList<Instruction>(),
                            emptyList(),
                            emptyList(),
                        ),
                    )
                )
            replacement.addInstructions(
                0,
                """
            invoke-static {p1}, $MIX->copyKey($URI)Ljava/lang/String;
            move-result-object v1
            invoke-static {p1}, $MIX->opener($URI)Lkotlin/jvm/functions/Function0;
            move-result-object v3
            const/4 v2, 0x0
            new-instance v0, $copyType
            invoke-direct {v0, v1, v2, v3}, $copyType-><init>(Ljava/lang/String;Lcom/music/bitchord/playback/AudioCache${'$'}Rendition;Lkotlin/jvm/functions/Function0;)V
            return-object v0
        """,
            )
            analyzer.methods.remove(copy)
            analyzer.methods.add(replacement)
            service.methods
                .single { it.name == "onCreate" }
                .addInstruction(
                    0,
                    "invoke-static/range {p0 .. p0}, $MIX->service(Ljava/lang/Object;)V",
                )
            service.methods
                .single { it.name == "onDestroy" }
                .addInstruction(
                    0,
                    "invoke-static/range {p0 .. p0}, $MIX->release(Ljava/lang/Object;)V",
                )
            val marker = mutableClassDefBy(MIX).methods.single { it.name == "applied" }
            marker.replaceInstruction(0, "const/4 v0, 0x1")
            val enabled =
                mutableClassDefBy(SETTINGS).methods.single { it.name == "setSmartFadeEnabled" }
            val enableCode = enabled.implementation!!.instructions.toList()
            for (index in
                enableCode.indices
                    .filter { enableCode[it].opcode == Opcode.RETURN_VOID }
                    .reversed()) enabled.addInstruction(
                index,
                "invoke-static {}, $MIX->settingChanged()V",
            )

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

            val alignment =
                service.methods.single { it.name == "access${'$'}prepareVersionAlignment" }
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
            val controller = mutableClassDefBy("Lcom/music/bitchord/playback/CrossfadeController;")
            val consider = controller.methods.single { it.name == "considerSmartTransition" }
            val code = consider.implementation!!.instructions.toList()
            val saved =
                code.indexOfFirst {
                    it.mixField()?.name == "lastSmartPlan" && it.opcode == Opcode.IPUT_OBJECT
                }
            check(saved >= 0)
            val planRegister = (code[saved] as TwoRegisterInstruction).registerA
            // LoopMix's native tempo ramp cannot preserve the listener's chosen speed.
            val loops =
                code.indices.filter {
                    code[it].mixField()?.let { f ->
                        f.definingClass ==
                            "Lcom/music/bitchord/playback/loopmix/LoopMixRenderer;" &&
                            f.name == "available"
                    } == true
                }
            check(loops.size == 1)
            for (index in loops) consider.replaceInstruction(
                index,
                "const/16 v${(code[index] as OneRegisterInstruction).registerA}, 0x0",
            )
            consider.addInstruction(
                saved,
                "invoke-static/range {v$planRegister .. v$planRegister}, $MIX->plan(Ljava/lang/Object;)V",
            )
            val edit = "Lapp/bitchord/telegram/TransitionRuntime;"
            consider.addInstruction(
                0,
                "invoke-static/range {p0 .. p0}, $edit->bind(Ljava/lang/Object;)V",
            )
            val begin = controller.methods.single { it.name == "begin" }
            check(
                begin.parameterTypes.last() == "Lcom/music/bitchord/playback/smart/TransitionPlan;"
            )
            val beginWords = 1 + begin.parameterTypes.sumOf { if (it == "J" || it == "D") 2 else 1 }
            check(begin.implementation!!.registerCount >= beginWords + 2)
            begin.addInstructions(
                0,
                """
                move-object/from16 v0, p0
                move-object/from16 v1, p${beginWords-1}
                invoke-static {v0, v1}, $edit->arm(Ljava/lang/Object;Ljava/lang/Object;)V
            """,
            )
            for (name in listOf("finish", "release")) {
                controller.methods
                    .single { it.name == name }
                    .addInstruction(
                        0,
                        "invoke-static/range {p0 .. p0}, $edit->${if (name == "release") "release" else "finish"}(Ljava/lang/Object;)V",
                    )
            }
            for ((methodName, bridge) in listOf("mixRise" to "incoming", "mixFall" to "outgoing")) {
                val fader = controller.methods.single { it.name == methodName }
                check(fader.parameterTypes == listOf("F") && fader.returnType == "F")
                check(fader.implementation!!.registerCount >= 3)
                fader.addInstructionsWithLabels(
                    0,
                    """
                    invoke-static/range {p0 .. p1}, $edit->$bridge(Ljava/lang/Object;F)Ljava/lang/Float;
                    move-result-object v0
                    if-eqz v0, :host_curve
                    invoke-virtual {v0}, Ljava/lang/Float;->floatValue()F
                    move-result v0
                    return v0
                    :host_curve
                    nop
                """,
                )
            }
            val filters = controller.methods.single { it.name == "rideFilters" }
            check(filters.parameterTypes == listOf("F", "Ljava/lang/Float;"))
            check(filters.implementation!!.registerCount > 3)
            filters.addInstructionsWithLabels(
                0,
                """
                invoke-static/range {p0 .. p2}, $edit->filters(Ljava/lang/Object;FLjava/lang/Float;)Z
                move-result v0
                if-eqz v0, :host_filters
                return-void
                :host_filters
                nop
            """,
            )
            val callbacks =
                mutableClassDefBy(
                    "Lcom/music/bitchord/playback/PlaybackService${'$'}MediaLibraryCallback;"
                )
            for (name in listOf("incoming", "outgoing")) {
                val callback =
                    callbacks.methods.single {
                        it.name == name && it.parameterTypes == listOf("F", "F")
                    }
                val steps = callback.implementation!!.instructions.toList()
                val stores =
                    steps.indices.filter {
                        steps[it].opcode == Opcode.IPUT &&
                            steps[it].mixField()?.name == "targetHighPassHz"
                    }
                check(stores.size == 1) { "Transition physical deck binding changed" }
                val index = stores.single()
                val processor = (steps[index] as TwoRegisterInstruction).registerB
                callback.addInstruction(
                    index + 1,
                    "invoke-static/range {v$processor .. v$processor}, $edit->assign(Ljava/lang/Object;)V",
                )
            }
            val pcm =
                mutableClassDefBy("Lcom/music/bitchord/playback/TransitionFilterProcessor;")
                    .methods
                    .single {
                        it.name == "queueInput" &&
                            it.parameterTypes == listOf("Ljava/nio/ByteBuffer;")
                    }
            val pcmCode = pcm.implementation!!.instructions.toList()
            val ownerCopy = pcmCode.first() as TwoRegisterInstruction
            check(
                pcmCode.first().opcode == Opcode.MOVE_OBJECT_FROM16 &&
                    ownerCopy.registerA == 0 &&
                    ownerCopy.registerB == pcm.implementation!!.registerCount - 2
            )
            val flips = pcmCode.indices.filter { index -> pcmCode[index].mixCall()?.name == "flip" }
            check(flips.size == 2) { "PCM output branches changed" }
            for (index in flips.reversed()) {
                val output = (pcmCode[index] as FiveRegisterInstruction).registerC
                check(pcmCode[index + 1].opcode == Opcode.RETURN_VOID)
                pcm.addInstruction(
                    index + 1,
                    "invoke-static {v0, v$output}, $edit->pcm16(Ljava/lang/Object;Ljava/nio/ByteBuffer;)V",
                )
            }
            val floats =
                mutableClassDefBy("Lokio/internal/DefaultSocket;").methods.single {
                    it.name == "process" &&
                        it.parameterTypes == listOf("Lokhttp3/internal/http2/Huffman${'$'}Node;")
                }
            floats.addInstruction(
                0,
                "invoke-static/range {p0 .. p1}, $edit->floats(Ljava/lang/Object;Ljava/lang/Object;)V",
            )
            val callers = mutableListOf<String>()
            classDefForEach { type ->
                if (type.type.startsWith("Lcom/music/bitchord/playback/CrossfadeController"))
                    callers += type.type
            }
            var guarded = 0
            for (ownerType in callers) for (method in mutableClassDefBy(ownerType).methods) {
                val steps = method.implementation?.instructions?.toList() ?: continue
                for (index in steps.indices.reversed()) {
                    val call = steps[index].mixCall() ?: continue
                    if (call.name != "setPlaybackSpeed" || call.parameterTypes != listOf("F"))
                        continue
                    val instruction = steps[index]
                    val registers =
                        when (instruction) {
                            is FiveRegisterInstruction ->
                                listOf(instruction.registerC, instruction.registerD)
                            is RegisterRangeInstruction ->
                                listOf(instruction.startRegister, instruction.startRegister + 1)
                            else -> error("AutoMix speed call encoding changed")
                        }
                    val invoke =
                        if (registers.max() < 16)
                            "invoke-static {v${registers[0]}, v${registers[1]}}"
                        else {
                            check(registers[1] == registers[0] + 1)
                            "invoke-static/range {v${registers[0]} .. v${registers[1]}}"
                        }
                    method.replaceInstruction(
                        index,
                        "$invoke, $MIX->speed(Landroidx/media3/common/Player;F)V",
                    )
                    guarded++
                }
            }
            check(guarded >= 8) { "AutoMix speed paths changed: $guarded" }
            println(
                "AutoMix: direct source analysis, verified resolver boundary, $guarded original-tempo speed paths"
            )
        }
    }

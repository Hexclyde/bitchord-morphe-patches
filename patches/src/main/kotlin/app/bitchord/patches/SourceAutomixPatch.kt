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

private object AutomixNotices

private val automixResources = resourcePatch {
    execute {
        for (name in listOf("AUTOMIX-COMPONENTS.txt", "GPL-3.0.txt")) {
            get("assets/bitchord/automix/licenses/$name", false).apply {
                parentFile.mkdirs()
                AutomixNotices::class
                    .java
                    .classLoader
                    .getResourceAsStream("licenses/$name")!!
                    .use { input -> outputStream().use { input.copyTo(it) } }
            }
        }
    }
}

@Suppress("unused")
val sourceAutomixPatch =
    bytecodePatch(
        name = "Source-independent AutoMix",
        description =
            "Measures the selected playable source, verifies automatic replacements, preserves playback speed and adds a native, pair-specific transition timeline with volume, EQ, filter controls and preview.",
        default = true,
    ) {
        compatibleWith(
            Compatibility(
                name = "BitChord",
                packageName = "com.music.bitchord",
                apkFileType = ApkFileType.APK,
                appIconColor = 0xFF8944,
                targets =
                    listOf(
                        AppTarget(version = "1.9", isExperimental = true, minSdk = 26),
                        AppTarget(version = "1.9.1-beta1", isExperimental = true, minSdk = 26),
                    ),
            )
        )
        dependsOn(extrasSettingsPatch, playbackBufferGatePatch, automixResources)
        execute {
            addSourceVerification()
            val timingPair = classDefBy("Lkotlin/Pair;")
            check(
                setOf("first", "second").all { name ->
                    timingPair.fields.any { it.name == name && it.type == "Ljava/lang/Object;" }
                }
            ) {
                "Recording decoder timing fields changed in BitChord"
            }
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
            val decoder = "Lcom/music/bitchord/playback/smart/AudioDecoder;"
            val featuresOwner = "Lcom/music/bitchord/playback/smart/TrackFeatures;"
            val features = "Lcom/music/bitchord/playback/smart/TrackFeatures${'$'}Features;"
            val analysis = "Lcom/music/bitchord/playback/smart/TrackAnalysis;"
            val source = "Landroid/media/MediaDataSource;"
            val function0 = "Lkotlin/jvm/functions/Function0;"
            val function4 = "Lkotlin/jvm/functions/Function4;"
            val byteBuffer = "Ljava/nio/ByteBuffer;"
            val info = "Landroid/media/MediaCodec${'$'}BufferInfo;"
            fun staticMethod(owner: String, args: List<String>, result: String): MethodReference {
                val matches =
                    classDefBy(owner).methods.filter {
                        it.parameterTypes == args &&
                            it.returnType == result &&
                            it.accessFlags and AccessFlags.STATIC.value != 0
                    }
                check(matches.size == 1) {
                    "Bounded AutoMix native API changed: $owner$args$result"
                }
                return matches.single()
            }
            val decoded =
                staticMethod(
                    decoder,
                    listOf(source, "D", "D", function0, function4),
                    "Lkotlin/Pair;",
                )
            add(
                HOST,
                "bitchordWaveDecode",
                listOf(source, "D", "D", function0, function4),
                "Ljava/lang/Object;",
                14,
                """
                move-object/from16 v0, p0
                move-wide/from16 v1, p1
                move-wide/from16 v3, p3
                move-object/from16 v5, p5
                move-object/from16 v6, p6
                invoke-static/range {v0 .. v6}, $decoded
                move-result-object v0
                return-object v0
                """
                    .trimIndent(),
            )
            val decodeCode =
                classDefBy(decoder)
                    .methods
                    .single { it == decoded }
                    .implementation!!
                    .instructions
                    .toList()
            check(
                listOf(
                        "Landroid/media/MediaExtractor;" to "setDataSource",
                        "Landroid/media/MediaCodec;" to "getOutputBuffer",
                        function0 to "invoke",
                        function4 to "invoke",
                    )
                    .all { (owner, name) ->
                        decodeCode.any {
                            it.mixCall()?.let { call ->
                                call.definingClass == owner && call.name == name
                            } == true
                        }
                    }
            ) {
                "Bounded AutoMix decoder no longer streams cancellable codec buffers"
            }
            val mono = staticMethod(decoder, listOf(byteBuffer, info, "I"), "[F")
            check(
                classDefBy(decoder)
                    .methods
                    .single { it == mono }
                    .implementation!!
                    .instructions
                    .any {
                        it.mixCall()?.let { call ->
                            call.definingClass == byteBuffer && call.name == "asShortBuffer"
                        } == true
                    }
            ) {
                "Bounded AutoMix native mono conversion changed"
            }
            val measured = staticMethod(featuresOwner, listOf("[F", "D"), features)
            check(
                classDefBy(featuresOwner)
                    .methods
                    .single { it == measured }
                    .implementation!!
                    .instructions
                    .any {
                        it.mixCall()?.let { call ->
                            call.definingClass == featuresOwner &&
                                call.parameterTypes == listOf("[F", "D", "D") &&
                                call.returnType == "Ljava/lang/String;"
                        } == true
                    }
            ) {
                "Bounded AutoMix no longer reaches the native structural analyzer"
            }
            add(
                HOST,
                "bitchordDspDecode",
                listOf(source, "D", function0, function4),
                "Ljava/lang/Object;",
                12,
                """
                move-object/from16 v0, p0
                const-wide/16 v1, 0x0
                move-wide/from16 v3, p1
                move-object/from16 v5, p3
                move-object/from16 v6, p4
                invoke-static/range {v0 .. v6}, $decoded
                move-result-object v0
                return-object v0
                """,
            )
            add(
                HOST,
                "bitchordDspMono",
                listOf(byteBuffer, info, "I"),
                "[F",
                4,
                """
                invoke-static/range {p0 .. p2}, $mono
                move-result-object v0
                return-object v0
                """,
            )
            add(
                HOST,
                "bitchordDspFeatures",
                listOf("[F", "D"),
                "Ljava/lang/Object;",
                4,
                """
                invoke-static/range {p0 .. p2}, $measured
                move-result-object v0
                return-object v0
                """,
            )
            val list = "Ljava/util/List;"
            val boxed = "Ljava/lang/Double;"
            val string = "Ljava/lang/String;"
            val beat = "Lcom/music/bitchord/playback/smart/BeatSide;"
            val resultFields =
                listOf(
                    "status" to string,
                    "trackId" to string,
                    "duration" to "D",
                    "bpm" to "D",
                    "beatInterval" to "D",
                    "beatConfidence" to "D",
                    "downbeats" to list,
                    "phraseBoundaries" to list,
                    "firstBeat" to "D",
                    "key" to string,
                    "keyConfidence" to "D",
                    "audibleStartTime" to boxed,
                    "pickupTime" to boxed,
                    "introEndTime" to "D",
                    "contentEndTime" to "D",
                    "outroStartTime" to "D",
                    "mixInTime" to "D",
                    "mixOutTime" to "D",
                    "mixInCandidates" to list,
                    "mixOutCandidates" to list,
                    "energyCurve" to list,
                    "lowEnergyCurve" to list,
                    "vocalActivityMask" to list,
                    "vocalProbability" to "D",
                    "headBeats" to beat,
                    "tailBeats" to beat,
                    "vocalWindows" to list,
                    "levelWindows" to list,
                )
            val constructor =
                classDefBy(analysis).methods.filter {
                    it.name == "<init>" &&
                        it.parameterTypes == resultFields.map { field -> field.second }
                }
            check(constructor.size == 1) { "Bounded AutoMix analysis result constructor changed" }
            val construct = constructor.single()
            val words = 1 + resultFields.sumOf { if (it.second == "D") 2 else 1 }
            val origins = mutableMapOf<Int, Int>()
            val registers = construct.implementation!!.registerCount
            for (word in 0 until words) origins[registers - words + word] = word
            val assignments = mutableMapOf<String, Int?>()
            for (step in construct.implementation!!.instructions) {
                if (step.opcode.name.startsWith("move") && step is TwoRegisterInstruction) {
                    origins[step.registerA] = origins[step.registerB] ?: -1
                    if (step.opcode.setsWideRegister())
                        origins[step.registerA + 1] = origins[step.registerB + 1] ?: -1
                } else if (step.opcode.name.startsWith("iput") && step is TwoRegisterInstruction) {
                    val field = step.mixField()
                    if (field?.definingClass == analysis) {
                        check(
                            !assignments.containsKey(field.name) && origins[step.registerB] == 0
                        ) {
                            "Bounded AutoMix result field ownership changed: ${field.name}"
                        }
                        assignments[field.name] = origins[step.registerA]
                    }
                } else if (step.opcode.setsRegister() && step is OneRegisterInstruction) {
                    origins.remove(step.registerA)
                    if (step.opcode.setsWideRegister()) origins.remove(step.registerA + 1)
                }
            }
            var argument = 1
            for ((name, type) in resultFields) {
                check(
                    assignments[name] == argument &&
                        classDefBy(analysis).fields.any { it.name == name && it.type == type }
                ) {
                    "Bounded AutoMix analysis field mapping changed: $name"
                }
                argument += if (type == "D") 2 else 1
            }
            var destination = 5
            val resultCode =
                StringBuilder(
                    "move-object/from16 v0, p0\ncheck-cast v0, $features\nnew-instance v4, $analysis\n"
                )
            for ((name, type) in resultFields) {
                when (name) {
                    "status" ->
                        resultCode.append(
                            "const-string v1, \"ready\"\nmove-object/from16 v$destination, v1\n"
                        )
                    "trackId" -> resultCode.append("move-object/from16 v$destination, p1\n")
                    "duration" -> resultCode.append("move-wide/from16 v$destination, p2\n")
                    "headBeats",
                    "tailBeats" ->
                        resultCode.append("const/4 v1, 0x0\nmove-object/from16 v$destination, v1\n")
                    "vocalActivityMask",
                    "vocalWindows",
                    "levelWindows" ->
                        resultCode.append(
                            "invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;\nmove-result-object v1\nmove-object/from16 v$destination, v1\n"
                        )
                    else -> {
                        val field = classDefBy(features).fields.singleOrNull { it.name == name }
                        val expected = if (type == boxed) "D" else type
                        check(
                            field != null &&
                                (field.type == expected ||
                                    type == list && field.type == "Ljava/lang/Object;")
                        ) {
                            "Bounded AutoMix structural feature changed: $name"
                        }
                        if (expected == "D") {
                            resultCode.append("iget-wide v1, v0, $field\n")
                            if (type == boxed)
                                resultCode.append(
                                    "invoke-static {v1, v2}, Ljava/lang/Double;->valueOf(D)Ljava/lang/Double;\nmove-result-object v1\nmove-object/from16 v$destination, v1\n"
                                )
                            else resultCode.append("move-wide/from16 v$destination, v1\n")
                        } else {
                            resultCode.append("iget-object v1, v0, $field\n")
                            if (field.type != type) resultCode.append("check-cast v1, $type\n")
                            resultCode.append("move-object/from16 v$destination, v1\n")
                        }
                    }
                }
                destination += if (type == "D") 2 else 1
            }
            resultCode.append(
                "invoke-direct/range {v4 .. v${destination - 1}}, $construct\nreturn-object v4\n"
            )
            add(
                HOST,
                "bitchordDspResult",
                listOf("Ljava/lang/Object;", string, "D"),
                "Ljava/lang/Object;",
                destination + 4,
                resultCode.toString(),
            )
            val realMix =
                classDefBy(SETTINGS).fields.singleOrNull { it.name == "smartMixInProgress" }
            check(realMix?.type == "Lkotlinx/coroutines/flow/StateFlowImpl;") {
                "AutoMix's analyzed mix state changed"
            }
            add(
                HOST,
                "bitchordAutomixRealMix",
                emptyList(),
                "Z",
                1,
                """
                sget-object v0, $realMix
                invoke-virtual {v0}, ${realMix.type}->getValue()Ljava/lang/Object;
                move-result-object v0
                check-cast v0, Ljava/lang/Boolean;
                invoke-virtual {v0}, Ljava/lang/Boolean;->booleanValue()Z
                move-result v0
                return v0
                """,
            )
            val labelClass = mutableClassDefBy("Lcom/music/bitchord/ui/player/PlayerControlsKt;")
            val labels =
                labelClass.methods.filter { method ->
                    method.returnType == "V" &&
                        method.parameterTypes ==
                            listOf(
                                "Lcom/music/bitchord/ui/player/MixPulse;",
                                "Landroidx/compose/ui/Modifier;",
                                "Landroidx/compose/runtime/ComposerImpl;",
                                "I",
                            ) &&
                        method.implementation?.instructions?.any {
                            it.mixField()?.name == "mixing${'$'}delegate"
                        } == true
                }
            check(labels.size == 1) { "AutoMix's native blend label changed" }
            val label = labels.single()
            val labelCode = label.implementation!!.instructions.toList()
            val textCalls =
                labelCode.indices.filter { index ->
                    labelCode[index].mixCall()?.let {
                        it.definingClass == "Lcom/music/bitchord/ui/AppUi;" &&
                            it.name == "stringResource" &&
                            it.returnType == string
                    } == true
                }
            check(
                textCalls.size == 1 &&
                    labelCode[textCalls.single() + 1].opcode == Opcode.MOVE_RESULT_OBJECT
            ) {
                "AutoMix's localized blend label no longer has one native string result"
            }
            val textAt = textCalls.single() + 1
            val textRegister = (labelCode[textAt] as OneRegisterInstruction).registerA
            label.addInstructions(
                textAt + 1,
                """
                invoke-static/range {v$textRegister .. v$textRegister}, $MIX->mixLabel(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v$textRegister
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
            // Keep native sessions alive until their in-flight inference has returned.
            for (name in listOf("BeatTracker", "VocalTracker")) {
                val model = mutableClassDefBy("Lcom/music/bitchord/playback/smart/$name;")
                check(model.fields.any { it.name == "lock" && it.type == "Ljava/lang/Object;" })
                val release =
                    model.methods.single { it.name == "release" && it.parameterTypes.isEmpty() }
                val code = release.implementation!!.instructions.toList()
                check(
                    code[0].opcode == Opcode.IGET_OBJECT &&
                        code[0].mixField()?.name == "lock" &&
                        (code[0] as TwoRegisterInstruction).registerA == 0 &&
                        code[1].opcode == Opcode.MONITOR_ENTER &&
                        (code[1] as OneRegisterInstruction).registerA == 0 &&
                        release.implementation!!.registerCount >= 3
                ) {
                    "$name release lock ABI changed"
                }
                release.addInstructionsWithLabels(
                    2,
                    """
                    invoke-static {p0}, $MIX->deferModelRelease(Ljava/lang/Object;)Z
                    move-result v1
                    if-eqz v1, :closeSession
                    monitor-exit v0
                    return-void
                    :closeSession
                    nop
                """,
                )
                release.addInstructionsWithLabels(
                    0,
                    """
                    invoke-static {p0}, $MIX->deferModelRelease(Ljava/lang/Object;)Z
                    move-result v0
                    if-eqz v0, :acquireLock
                    return-void
                    :acquireLock
                    nop
                    """,
                )
            }
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
            val marker = mutableClassDefBy(MIX).methods.single { it.name == "applied" }
            marker.replaceInstruction(0, "const/4 v0, 0x1")
            addSmartFadeReconcile()

            val controller = mutableClassDefBy("Lcom/music/bitchord/playback/CrossfadeController;")
            installPlayerHeader("Lapp/bitchord/telegram/VideoSwitch;")
            val bands = classDefBy("Lcom/music/bitchord/playback/smart/MixWaveform${'$'}Bands;")
            for (name in listOf("low", "mid", "high")) check(
                bands.fields.any { it.name == name && it.type == "[F" }
            ) {
                "Editor waveform band ABI changed: $name"
            }
            val waveReader = controller.fields.single { it.name == "mixWaveform" }
            check(
                classDefBy(waveReader.type).methods.any {
                    it.name == "invoke" &&
                        it.parameterTypes.size == 5 &&
                        it.returnType == "Ljava/lang/Object;"
                }
            ) {
                "Editor selected-source waveform reader changed"
            }
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
            val publication = code[saved] as TwoRegisterInstruction
            val planField = code[saved].mixField()!!
            consider.replaceInstruction(
                saved,
                "invoke-static/range {v$planRegister .. v$planRegister}, $MIX->plan(Ljava/lang/Object;)V",
            )
            consider.addInstruction(
                saved + 1,
                "iput-object v${publication.registerA}, v${publication.registerB}, " +
                    "${planField.definingClass}->${planField.name}:${planField.type}",
            )
            val edit = "Lapp/bitchord/telegram/TransitionRuntime;"
            val considerWords =
                1 + consider.parameterTypes.sumOf { if (it == "D" || it == "J") 2 else 1 }
            check(
                consider.returnType == "V" &&
                    consider.implementation!!.registerCount > considerWords
            )
            consider.addInstructionsWithLabels(
                0,
                """
                invoke-static/range {p0 .. p0}, $edit->bind(Ljava/lang/Object;)V
                invoke-static/range {p0 .. p0}, $edit->holdPlanner(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :plan_current_queue
                return-void
                :plan_current_queue
                nop
                """,
            )
            fun previewApi(owner: String, name: String, args: List<String>, result: String) {
                check(
                    classDefBy(owner).methods.any {
                        it.name == name &&
                            it.parameterTypes == args &&
                            it.returnType == result &&
                            it.accessFlags and AccessFlags.PUBLIC.value != 0
                    }
                ) {
                    "Independent AutoMix preview API changed: $owner->$name"
                }
            }
            val previewProcessors =
                listOf(
                        "SpatialAudioProcessor",
                        "EqualizerProcessor",
                        "TransitionFilterProcessor",
                        "LoudnessProcessor",
                    )
                    .map { "Lcom/music/bitchord/playback/$it;" }
            for (type in previewProcessors) previewApi(type, "<init>", emptyList(), "V")
            previewApi(
                "Lcom/music/bitchord/playback/PlaybackService${'$'}silenceSkippingRenderers${'$'}1;",
                "<init>",
                listOf(HOST) + previewProcessors,
                "V",
            )
            val previewBuilder = "Landroidx/media3/exoplayer/ExoPlayer${'$'}Builder;"
            previewApi(previewBuilder, "<init>", listOf("Landroid/content/Context;"), "V")
            previewApi(
                previewBuilder,
                "setMediaSourceFactory",
                listOf("Landroidx/media3/exoplayer/source/MediaSource${'$'}Factory;"),
                "V",
            )
            previewApi(
                previewBuilder,
                "build",
                emptyList(),
                "Landroidx/media3/exoplayer/ExoPlayerImpl;",
            )
            for ((name, type) in
                listOf(
                    "renderersFactorySupplier" to "Lcom/google/common/base/Supplier;",
                    "loadControlSupplier" to "Lcom/google/common/base/Supplier;",
                    "handleAudioFocus" to "Z",
                    "handleAudioBecomingNoisy" to "Z",
                )) check(
                classDefBy(previewBuilder).fields.any {
                    it.name == name &&
                        it.type == type &&
                        it.accessFlags and AccessFlags.PUBLIC.value != 0
                }
            )
            val allocator = "Landroidx/media3/exoplayer/upstream/DefaultAllocator;"
            previewApi(allocator, "<init>", emptyList(), "V")
            previewApi(
                "Landroidx/media3/exoplayer/DefaultLoadControl;",
                "<init>",
                listOf(allocator) + List(9) { "I" } + listOf("Z", "I", "Z", "Ljava/util/Map;"),
                "V",
            )
            val previewPlayer = "Landroidx/media3/common/Player;"
            for (name in
                listOf(
                    "setRepeatMode",
                    "setShuffleModeEnabled",
                    "setMediaItem",
                    "setPlayWhenReady",
                )) {
                val type =
                    if (name == "setMediaItem") "Landroidx/media3/common/MediaItem;"
                    else if (name == "setRepeatMode") "I" else "Z"
                previewApi(previewPlayer, name, listOf(type), "V")
            }
            previewApi(
                previewPlayer,
                "getPlayerError",
                emptyList(),
                "Landroidx/media3/common/PlaybackException;",
            )
            previewApi("Landroidx/media3/exoplayer/ExoPlayer;", "release", emptyList(), "V")
            previewApi(controller.type, "isTransitioning", emptyList(), "Z")
            for ((name, type) in
                listOf(
                    "mediaSourceFactory" to
                        "Landroidx/media3/exoplayer/source/DefaultMediaSourceFactory;",
                    "spatialAudioProcessorA" to previewProcessors[0],
                    "equalizerProcessorA" to previewProcessors[1],
                    "loudnessA" to previewProcessors[3],
                )) check(classDefBy(HOST).fields.any { it.name == name && it.type == type })
            for ((type, name, fieldType) in
                listOf(
                    Triple(previewProcessors[0], "enabled", "Z"),
                    Triple(
                        previewProcessors[1],
                        "target",
                        "Lcom/music/bitchord/playback/EqualizerProcessor${'$'}Tuning;",
                    ),
                    Triple(previewProcessors[3], "gainFor", "Lkotlin/jvm/functions/Function1;"),
                    Triple(previewProcessors[3], "mediaId", "Ljava/lang/String;"),
                )) check(classDefBy(type).fields.any { it.name == name && it.type == fieldType })
            val begin = controller.methods.single { it.name == "begin" }
            check(
                begin.returnType == "Z" &&
                    begin.parameterTypes.last() ==
                        "Lcom/music/bitchord/playback/smart/TransitionPlan;"
            )
            val beginWords = 1 + begin.parameterTypes.sumOf { if (it == "J" || it == "D") 2 else 1 }
            check(begin.implementation!!.registerCount >= beginWords + 2)
            begin.addInstructionsWithLabels(
                0,
                """
                invoke-static/range {p0 .. p0}, $edit->previewHolding(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :audition_does_not_hold_queue
                const/4 v0, 0x0
                return v0
                :audition_does_not_hold_queue
                nop
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

package app.bitchord.patches

import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import com.android.tools.smali.dexlib2.*
import com.android.tools.smali.dexlib2.iface.*
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.*
import com.android.tools.smali.dexlib2.immutable.*
import com.android.tools.smali.dexlib2.immutable.instruction.*
import com.android.tools.smali.dexlib2.immutable.reference.*
import java.io.File

// Names below are deliberately unlike the native ones.
private const val OWNER = "Lq/A;"
private const val SOURCE = "Landroid/media/MediaDataSource;"
private const val OBJECT = "Ljava/lang/Object;"
private const val PAIR = "Lkotlin/Pair;"
private const val DOUBLE = "Ljava/lang/Double;"
private const val EXTRACTOR = "Landroid/media/MediaExtractor;"
private const val FUNCTION0 = "Lkotlin/jvm/functions/Function0;"
private const val FUNCTION4 = "Lkotlin/jvm/functions/Function4;"
private val rawParams = listOf(SOURCE, "D", "D", FUNCTION0, FUNCTION4)

private var checks = 0

private fun ensure(value: Boolean, reason: String) {
    checks++
    check(value) { reason }
}

private fun fails(reason: String, block: () -> Unit) {
    checks++
    val message = runCatching(block).exceptionOrNull()?.message
    check(message != null && message.startsWith("BitChord native decoder changed")) {
        "$reason: $message"
    }
}

private fun ref(owner: String, name: String, params: List<String>, result: String) =
    ImmutableMethodReference(owner, name, params, result)

private fun invoke(owner: String, name: String, params: List<String>, result: String) =
    ImmutableInstruction35c(
        Opcode.INVOKE_VIRTUAL,
        1,
        0,
        0,
        0,
        0,
        0,
        ref(owner, name, params, result),
    )

private fun rawBody(skip: Int = -1): List<Instruction> =
    listOf(
            invoke(EXTRACTOR, "setDataSource", listOf(SOURCE), "V"),
            invoke(EXTRACTOR, "seekTo", listOf("J", "I"), "V"),
            invoke(
                "Landroid/media/MediaCodec;",
                "getOutputBuffer",
                listOf("I"),
                "Ljava/nio/ByteBuffer;",
            ),
            invoke(FUNCTION0, "invoke", emptyList(), OBJECT),
            invoke(FUNCTION4, "invoke", List(4) { OBJECT }, OBJECT),
            invoke(PAIR, "<init>", listOf(OBJECT, OBJECT), "V"),
            ImmutableInstruction22c(
                Opcode.IGET_WIDE,
                0,
                1,
                ImmutableFieldReference(
                    "Landroid/media/MediaCodec${'$'}BufferInfo;",
                    "presentationTimeUs",
                    "J",
                ),
            ),
        )
        .filterIndexed { index, _ -> index != skip } + ImmutableInstruction10x(Opcode.RETURN_VOID)

private fun durationBody(skip: Int = -1): List<Instruction> =
    listOf(
            invoke(EXTRACTOR, "setDataSource", listOf(SOURCE), "V"),
            invoke("Landroid/media/MediaFormat;", "getLong", listOf("Ljava/lang/String;"), "J"),
            ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("durationUs")),
            invoke(EXTRACTOR, "release", emptyList(), "V"),
        )
        .filterIndexed { index, _ -> index != skip } + ImmutableInstruction10x(Opcode.RETURN_VOID)

private fun method(
    name: String,
    params: List<String>,
    result: String,
    code: List<Instruction>?,
    flags: Int = 9,
    registers: Int = 12,
    owner: String = OWNER,
    tries: List<TryBlock<out ExceptionHandler>> = emptyList(),
) =
    ImmutableMethod(
        owner,
        name,
        params.map { ImmutableMethodParameter(it, null, null) },
        result,
        flags,
        null,
        null,
        code?.let { ImmutableMethodImplementation(registers, it, tries, null) },
    )

private fun type(owner: String, methods: List<Method>) =
    ImmutableClassDef(owner, 1, OBJECT, null, null, null, null, methods)

private fun natives(
    raw: ImmutableMethod = method("a", rawParams, PAIR, rawBody()),
    duration: ImmutableMethod = method("b", listOf(SOURCE), DOUBLE, durationBody()),
    extra: List<Method> = emptyList(),
): List<ClassDef> = listOf(type(OWNER, listOf(raw, duration) + extra))

private val mono =
    method(
        "c",
        listOf("Ljava/nio/ByteBuffer;", "Landroid/media/MediaCodec${'$'}BufferInfo;", "I"),
        "[F",
        listOf(
            invoke("Ljava/nio/ByteBuffer;", "asShortBuffer", emptyList(), "Ljava/nio/ShortBuffer;"),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        ),
    )

private fun redirected(raw: Boolean) =
    redirectedDecoder(
        if (raw) method("a", rawParams, PAIR, rawBody())
        else method("b", listOf(SOURCE), DOUBLE, durationBody()),
        raw,
    )

private fun mutatedRedirect(
    raw: Boolean,
    change: (List<Instruction>) -> List<Instruction>,
    registers: Int? = null,
    tries: List<TryBlock<out ExceptionHandler>> = emptyList(),
    flags: Int = 9,
): Method {
    val good = redirected(raw)
    val code = good.implementation!!.instructions.toList()
    return method(
        good.name,
        good.parameterTypes.map { it.toString() },
        good.returnType,
        change(code),
        flags,
        registers ?: good.implementation!!.registerCount,
        tries = tries,
    )
}

private fun synthetic() {
    val sites = locateSourceAudioDecoder(natives(extra = listOf(mono)), OWNER)
    ensure(sites.raw.name == "a" && sites.duration.name == "b", "renamed methods are located")
    ensure(
        locateSourceAudioDecoder(natives(), OWNER).raw.accessFlags == 9,
        "access flags are public static",
    )

    fails("missing decoder class") { locateSourceAudioDecoder(natives(), "Lq/Z;") }
    fails("duplicate raw signature") {
        locateSourceAudioDecoder(
            natives(extra = listOf(method("d", rawParams, PAIR, rawBody()))),
            OWNER,
        )
    }
    fails("duplicate duration signature") {
        locateSourceAudioDecoder(
            natives(extra = listOf(method("d", listOf(SOURCE), DOUBLE, durationBody()))),
            OWNER,
        )
    }
    fails("missing raw signature") {
        locateSourceAudioDecoder(natives(raw = method("a", rawParams, OBJECT, rawBody())), OWNER)
    }
    fails("missing duration signature") {
        locateSourceAudioDecoder(
            natives(duration = method("b", listOf(SOURCE), "D", durationBody())),
            OWNER,
        )
    }
    fails("raw access") {
        locateSourceAudioDecoder(
            natives(raw = method("a", rawParams, PAIR, rawBody(), flags = 1)),
            OWNER,
        )
    }
    fails("duration access") {
        locateSourceAudioDecoder(
            natives(duration = method("b", listOf(SOURCE), DOUBLE, durationBody(), flags = 10)),
            OWNER,
        )
    }
    fails("raw without implementation") {
        locateSourceAudioDecoder(natives(raw = method("a", rawParams, PAIR, null)), OWNER)
    }
    fails("duration without implementation") {
        locateSourceAudioDecoder(
            natives(duration = method("b", listOf(SOURCE), DOUBLE, null)),
            OWNER,
        )
    }
    for (anchor in 0 until 7) {
        fails("raw anchor $anchor") {
            locateSourceAudioDecoder(
                natives(raw = method("a", rawParams, PAIR, rawBody(anchor))),
                OWNER,
            )
        }
    }
    for (anchor in 0 until 4) {
        fails("duration anchor $anchor") {
            locateSourceAudioDecoder(
                natives(duration = method("b", listOf(SOURCE), DOUBLE, durationBody(anchor))),
                OWNER,
            )
        }
    }

    for (raw in listOf(true, false)) {
        val good = redirected(raw)
        ensure(sourceAudioRedirect(good, raw), "redirect shape raw=$raw")
        ensure(!sourceAudioRedirect(good, !raw), "redirect is specific to its method raw=$raw")
        val code = good.implementation!!.instructions.toList()
        ensure(
            good.implementation!!.registerCount == (if (raw) 8 else 2) &&
                good.name == (if (raw) "a" else "b") &&
                good.accessFlags == 9,
            "redirect keeps name and flags raw=$raw",
        )
        val bad =
            listOf(
                "extra instruction" to
                    mutatedRedirect(raw, { it + ImmutableInstruction10x(Opcode.NOP) }),
                "missing return" to mutatedRedirect(raw, { it.dropLast(1) }),
                "wrong return register" to
                    mutatedRedirect(
                        raw,
                        { it.dropLast(1) + ImmutableInstruction11x(Opcode.RETURN_OBJECT, 1) },
                    ),
                "wrong result register" to
                    mutatedRedirect(
                        raw,
                        {
                            listOf(
                                it[0],
                                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1),
                                it[2],
                            )
                        },
                    ),
                "wrong register count" to mutatedRedirect(raw, { it }, registers = 9),
                "wrong access" to mutatedRedirect(raw, { it }, flags = 1),
                "handler present" to
                    mutatedRedirect(
                        raw,
                        { it },
                        tries =
                            listOf(
                                ImmutableTryBlock(0, 1, listOf(ImmutableExceptionHandler(null, 2)))
                            ),
                    ),
                "wrong target" to
                    mutatedRedirect(
                        raw,
                        {
                            listOf(
                                ImmutableInstruction35c(
                                    Opcode.INVOKE_STATIC,
                                    1,
                                    1,
                                    0,
                                    0,
                                    0,
                                    0,
                                    ref(OWNER, "x", listOf(SOURCE), DOUBLE),
                                ),
                                it[1],
                                it[2],
                            )
                        },
                    ),
                "wrong source register" to
                    mutatedRedirect(
                        raw,
                        {
                            listOf(
                                if (raw) {
                                    ImmutableInstruction3rc(
                                        Opcode.INVOKE_STATIC_RANGE,
                                        0,
                                        7,
                                        (it[0] as ReferenceInstruction).reference,
                                    )
                                } else {
                                    ImmutableInstruction35c(
                                        Opcode.INVOKE_STATIC,
                                        1,
                                        0,
                                        0,
                                        0,
                                        0,
                                        0,
                                        (it[0] as ReferenceInstruction).reference,
                                    )
                                },
                                it[1],
                                it[2],
                            )
                        },
                    ),
                "wrong opcode" to
                    mutatedRedirect(
                        raw,
                        {
                            listOf(
                                ImmutableInstruction35c(
                                    Opcode.INVOKE_VIRTUAL,
                                    1,
                                    1,
                                    0,
                                    0,
                                    0,
                                    0,
                                    (it[0] as ReferenceInstruction).reference as MethodReference,
                                ),
                                it[1],
                                it[2],
                            )
                        },
                    ),
            )
        for ((label, method) in bad) ensure(!sourceAudioRedirect(method, raw), "$label raw=$raw")
        ensure(code.size == 3, "redirect has exactly three instructions raw=$raw")
    }
    ensure(
        !sourceAudioRedirect(method("a", rawParams, PAIR, null), true),
        "null implementation is not a redirect",
    )
    ensure(
        !sourceAudioRedirect(method("a", rawParams, PAIR, rawBody()), true),
        "native body is not a redirect",
    )
    val replaced = type(OWNER, listOf(redirected(true), redirected(false), mono))
    fails("redirected body no longer locates as native") {
        locateSourceAudioDecoder(listOf(replaced), OWNER)
    }

    val audio =
        type(
            SOURCE_AUDIO,
            listOf(
                method(
                    "decodeRaw",
                    rawParams,
                    PAIR,
                    listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
                ),
                method(
                    "containerDurationSeconds",
                    listOf(SOURCE),
                    DOUBLE,
                    listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
                ),
            ),
        )
    requireSourceAudio(listOf(audio))
    fails("missing SourceAudio") { requireSourceAudio(emptyList()) }
    fails("SourceAudio access") {
        requireSourceAudio(
            listOf(
                type(
                    SOURCE_AUDIO,
                    audio.methods.map {
                        method(
                            it.name,
                            it.parameterTypes.map(Any::toString),
                            it.returnType,
                            listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
                            flags = if (it.name == "decodeRaw") 8 else 9,
                        )
                    },
                )
            )
        )
    }
    fails("SourceAudio descriptor") {
        requireSourceAudio(listOf(type(SOURCE_AUDIO, audio.methods.take(1))))
    }
    fails("SourceAudio return") {
        requireSourceAudio(
            listOf(
                type(
                    SOURCE_AUDIO,
                    listOf(
                        method(
                            "decodeRaw",
                            rawParams,
                            OBJECT,
                            listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
                        ),
                        method(
                            "containerDurationSeconds",
                            listOf(SOURCE),
                            DOUBLE,
                            listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
                        ),
                    ),
                )
            )
        )
    }

    val three = setOf("arm64-v8a", "armeabi-v7a", "x86_64")
    var plan = sharedAudioAbiPlan("1.9", three + "x86")
    ensure(plan.abis == three && plan.removeX86, "universal 1.9 drops x86")
    plan = sharedAudioAbiPlan("1.9", three)
    ensure(plan.abis == three && !plan.removeX86, "already removed x86 is accepted")
    plan = sharedAudioAbiPlan("1.9.1-beta1", setOf("arm64-v8a"))
    ensure(plan.abis == setOf("arm64-v8a") && !plan.removeX86, "single supported ABI")
    for ((version, set) in
        listOf(
            "1.9.1-beta1" to three + "x86",
            "1.9" to setOf("x86"),
            "1.9" to setOf("arm64-v8a", "x86"),
            null to three + "x86",
            "1.9" to emptySet(),
        )) {
        checks++
        ensure(
            runCatching { sharedAudioAbiPlan(version, set) }.isFailure,
            "unsupported ABI set $version $set",
        )
    }
    val notices =
        listOf("COPYING.LGPLv2.1", "COPYING.LGPLv3", "LICENSE.md", "NOTICE").map {
            "bitchord/runtimes/licenses/preview/$it"
        }
    val listed =
        notices +
            listOf(
                "bitchord/runtimes/licenses/tor/tor.txt",
                "bitchord/runtimes/preview-inputs.json",
                "bitchord/runtimes/tor-inputs.json",
                "bitchord/i2p/certificates/a",
            )
    ensure(
        sharedAudioAssets(listed) == notices + "bitchord/runtimes/preview-inputs.json",
        "only preview notices and inventory are selected",
    )
    for (bad in
        listOf(
            listOf("bitchord/runtimes/preview-inputs.json"),
            notices.dropLast(1),
            notices + notices,
            notices + "bitchord/../x",
            notices + "other/x",
        )) ensure(runCatching { sharedAudioAssets(bad) }.isFailure, "invalid asset list $bad")
}

fun main(args: Array<String>) {
    synthetic()
    for (path in args) {
        val apk = DexFileFactory.loadDexContainer(File(path), Opcodes.forApi(35))
        val all = apk.dexEntryNames.flatMap { apk.getEntry(it)!!.dexFile.classes.toList() }
        val sites = locateSourceAudioDecoder(all)
        val decoder = all.single { it.type == NATIVE_DECODER }
        val mutable = MutableClass(decoder)
        val before = mutable.methods.associateBy { it.toString() }
        installSourceAudioDecoder(mutable, sites)
        val after = mutable.methods.associateBy { it.toString() }
        val changed = before.keys.filter { before[it] !== after[it] }.toSet()
        ensure(
            before.keys == after.keys &&
                changed == setOf(sites.raw.toString(), sites.duration.toString()),
            "Only the two decoder methods are replaced: $path",
        )
        ensure(
            (before.keys - changed).all {
                before[it]!!.implementation === after[it]!!.implementation
            },
            "All unrelated decoder method bodies remain untouched: $path",
        )
        val raw = after.getValue(sites.raw.toString())
        val duration = after.getValue(sites.duration.toString())
        ensure(
            sourceAudioRedirect(raw, true) && sourceAudioRedirect(duration, false),
            "Redirects are exact: $path",
        )
        ensure(
            raw.name == sites.raw.name &&
                raw.parameterTypes == sites.raw.parameterTypes &&
                raw.returnType == sites.raw.returnType &&
                duration.name == sites.duration.name &&
                duration.parameterTypes == sites.duration.parameterTypes &&
                duration.returnType == sites.duration.returnType &&
                raw.accessFlags == 9 &&
                duration.accessFlags == 9,
            "Native method references are retained: $path",
        )
        ensure(
            !sourceAudioRedirect(sites.raw, true) && !sourceAudioRedirect(sites.duration, false),
            "Original platform decoder does not satisfy the replacement contract: $path",
        )
        ensure(
            raw.annotations == sites.raw.annotations &&
                duration.annotations == sites.duration.annotations &&
                raw.hiddenApiRestrictions == sites.raw.hiddenApiRestrictions &&
                duration.hiddenApiRestrictions == sites.duration.hiddenApiRestrictions &&
                raw.parameters == sites.raw.parameters &&
                duration.parameters == sites.duration.parameters,
            "Native parameter annotations and method metadata are preserved: $path",
        )
        val helper =
            decoder.methods.single {
                it.parameterTypes.map(Any::toString) ==
                    listOf(
                        "Ljava/nio/ByteBuffer;",
                        "Landroid/media/MediaCodec${'$'}BufferInfo;",
                        "I",
                    ) && it.returnType == "[F"
            }
        ensure(
            helper.implementation!!.instructions.any {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name ==
                    "asShortBuffer"
            },
            "Native mono helper consumes PCM16: $path",
        )
        println(
            "PASS: native decoder redirect ${File(path).name} raw=${sites.raw.name} duration=${sites.duration.name}"
        )
    }
    println("PASS: $checks native decoder hook checks")
}

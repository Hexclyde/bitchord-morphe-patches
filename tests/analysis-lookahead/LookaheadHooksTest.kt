package app.bitchord.patches

import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.*
import com.android.tools.smali.dexlib2.iface.*
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.*
import com.android.tools.smali.dexlib2.immutable.*
import com.android.tools.smali.dexlib2.immutable.instruction.*
import com.android.tools.smali.dexlib2.immutable.reference.*
import java.io.File

private const val HOST = "Lcom/music/bitchord/playback/PlaybackService;"
private const val CACHE = "Lcom/music/bitchord/playback/AudioCache;"
private const val PLAYER = "Landroidx/media3/common/Player;"
private const val DECK = "Landroidx/media3/exoplayer/ExoPlayer;"
private const val TIMELINE = "Landroidx/media3/common/Timeline;"
private const val MIX = "Lapp/bitchord/telegram/SourceAutomix;"
private const val OBJECT = "Ljava/lang/Object;"
private var checks = 0

private fun ensure(okay: Boolean, reason: String) {
    checks++
    check(okay) { reason }
}

private fun rejected(label: String, body: () -> Unit) {
    val failure = runCatching(body).exceptionOrNull()
    ensure(
        failure is IllegalStateException &&
            failure.message!!.startsWith("BitChord analysis lookahead changed"),
        label,
    )
}

private fun method(
    owner: String,
    name: String,
    params: List<String>,
    result: String,
    code: List<Instruction>?,
    flags: Int = 1,
    regs: Int = 8,
) =
    ImmutableMethod(
        owner,
        name,
        params.map { ImmutableMethodParameter(it, null, null) },
        result,
        flags,
        emptySet(),
        emptySet(),
        code?.let { ImmutableMethodImplementation(regs, it, emptyList(), emptyList()) },
    )

private fun type(owner: String, methods: List<Method>, fields: List<Field> = emptyList()) =
    ImmutableClassDef(
        owner,
        1,
        "Ljava/lang/Object;",
        emptyList(),
        null,
        emptySet(),
        fields,
        methods,
    )

private fun call(
    owner: String,
    name: String,
    params: List<String>,
    result: String,
    vararg regs: Int,
): Instruction {
    val r = regs.toList() + List(5 - regs.size) { 0 }
    return ImmutableInstruction35c(
        if (owner == PLAYER) Opcode.INVOKE_INTERFACE else Opcode.INVOKE_STATIC,
        regs.size,
        r[0],
        r[1],
        r[2],
        r[3],
        r[4],
        ImmutableMethodReference(owner, name, params, result),
    )
}

private val field = ImmutableFieldReference(HOST, "q", "Ljava/util/ArrayList;")

private fun warm() =
    listOf<Instruction>(
        ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 6, field),
        call(PLAYER, "getNextMediaItemIndex", emptyList(), "I", 7),
        call(PLAYER, "getMediaItemAt", listOf("I"), "Landroidx/media3/common/MediaItem;", 7, 1),
        call(CACHE, "noteSongs", listOf("Ljava/util/List;"), "V", 0),
        ImmutableInstruction22c(Opcode.IPUT_OBJECT, 0, 6, field),
        ImmutableInstruction10x(Opcode.RETURN_VOID),
    )

private fun apis() =
    listOf(
        type(
            PLAYER,
            listOf(
                method(PLAYER, "getCurrentTimeline", emptyList(), TIMELINE, null, 1025),
                method(PLAYER, "getRepeatMode", emptyList(), "I", null, 1025),
                method(PLAYER, "getShuffleModeEnabled", emptyList(), "Z", null, 1025),
                method(PLAYER, "getNextMediaItemIndex", emptyList(), "I", null, 1025),
            ),
        ),
        type(
            TIMELINE,
            listOf(
                method(
                    TIMELINE,
                    "getNextWindowIndex",
                    listOf("I", "I", "Z"),
                    "I",
                    listOf(ImmutableInstruction11x(Opcode.RETURN, 0)),
                ),
                method(TIMELINE, "getWindowCount", emptyList(), "I", null, 1025),
            ),
        ),
    )

private fun host(
    code: List<Instruction> = warm(),
    flags: Int = 1,
    extra: List<Method> = emptyList(),
    f: List<Field> =
        listOf(ImmutableField(HOST, "q", "Ljava/util/ArrayList;", 1, null, emptySet(), emptySet())),
) = type(HOST, listOf(method(HOST, "renamedWarm", listOf(DECK), "V", code, flags)) + extra, f)

private fun key(method: Method) = method.toString()

private fun code(method: Method) = method.implementation!!.instructions.toList()

private fun target(i: Instruction) = (i as? ReferenceInstruction)?.reference as? MethodReference

private fun registers(i: Instruction): List<Int> =
    when (i) {
        is RegisterRangeInstruction ->
            (i.startRegister until i.startRegister + i.registerCount).toList()
        is FiveRegisterInstruction ->
            listOf(i.registerC, i.registerD, i.registerE, i.registerF, i.registerG)
                .take(i.registerCount)
        is ThreeRegisterInstruction -> listOf(i.registerA, i.registerB, i.registerC)
        is TwoRegisterInstruction -> listOf(i.registerA, i.registerB)
        is OneRegisterInstruction -> listOf(i.registerA)
        else -> emptyList()
    }

private fun described(i: Instruction): String {
    val reference = (i as? ReferenceInstruction)?.reference
    return "${i.opcode}:${registers(i)}:$reference:" +
        (if (i is WideLiteralInstruction) i.wideLiteral.toString() else "")
}

private fun withoutPayloadPadding(body: List<Instruction>) =
    body.filterIndexed { i, instruction ->
        !(instruction.opcode == Opcode.NOP &&
            body.getOrNull(i + 1)?.opcode in
                listOf(
                    Opcode.PACKED_SWITCH_PAYLOAD,
                    Opcode.SPARSE_SWITCH_PAYLOAD,
                    Opcode.ARRAY_PAYLOAD,
                ))
    }

private fun installed(sites: AnalysisLookaheadSites) {
    val originals = (sites.writes.map { it.method } + sites.warm).distinctBy(::key)
    for (old in originals) {
        val mutable = MutableMethod(old)
        val before = code(mutable)
        installAnalysisLookahead(mutable, sites)
        val after = code(mutable)
        val writes = sites.writes.filter { key(it.method) == key(old) }
        val cancels =
            after.filter {
                target(it)?.definingClass == MIX && target(it)?.name == "cancelLookahead"
            }
        ensure(cancels.size == writes.size, "Every native prefetch state write cancels speculation")
        for ((storeIndex, i) in after.withIndex()) if (
            i.opcode == Opcode.IPUT_OBJECT &&
                ((i as ReferenceInstruction).reference as FieldReference).toString() ==
                    sites.request.toString()
        ) {
            val next = after[storeIndex + 1]
            ensure(
                target(next)?.definingClass == MIX &&
                    target(next)?.name == "cancelLookahead" &&
                    registers(next) == listOf((i as TwoRegisterInstruction).registerB),
                "Cancellation gets the exact stored service receiver",
            )
        }
        val warmed = key(old) == key(sites.warm)
        val queueCalls =
            after.withIndex().filter {
                target(it.value)?.definingClass == MIX && target(it.value)?.name == "queueChanged"
            }
        ensure(
            queueCalls.size == if (warmed) before.count { it.opcode == Opcode.RETURN_VOID } else 0,
            "Every native read-ahead return takes one queue snapshot",
        )
        for ((index, i) in queueCalls) ensure(
            registers(i) ==
                listOf(
                    mutable.implementation!!.registerCount - 2,
                    mutable.implementation!!.registerCount - 1,
                ) && after[index + 1].opcode == Opcode.RETURN_VOID,
            "Snapshot receives service and native player before returning",
        )
        val stripped = withoutPayloadPadding(after.filter { target(it)?.definingClass != MIX })
        val original = withoutPayloadPadding(before)
        ensure(
            stripped.size == original.size &&
                stripped.map(::described) == original.map(::described),
            "Native instructions and registers are unchanged except added callbacks: ${old.definingClass}->${old.name}; " +
                "counts=${before.size}/${stripped.size}; first=" +
                before
                    .map(::described)
                    .zip(stripped.map(::described))
                    .indexOfFirst { it.first != it.second }
                    .let {
                        "$it: ${before.map(::described).getOrNull(it)} / ${stripped.map(::described).getOrNull(it)}"
                    },
        )
        ensure(
            mutable.implementation!!.registerCount == old.implementation!!.registerCount &&
                mutable.implementation!!.tryBlocks.size == old.implementation!!.tryBlocks.size,
            "Native register and handler counts are unchanged",
        )
    }
}

private fun synthetic() {
    val other =
        method(
            "Lq/Merged;",
            "unrelated",
            emptyList(),
            "V",
            listOf(
                ImmutableInstruction22c(Opcode.IPUT_OBJECT, 0, 1, field),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
        )
    val valid = apis() + host() + type("Lq/Merged;", listOf(other))
    val sites = locateAnalysisLookahead(valid)
    ensure(
        sites.warm.name == "renamedWarm" && sites.request.name == "q" && sites.writes.size == 2,
        "Read-ahead, state role and merged cancellation are found without native names",
    )
    installed(sites)
    for (anchor in 1..3) rejected("missing read-ahead anchor$anchor") {
        locateAnalysisLookahead(apis() + host(warm().filterIndexed { i, _ -> i != anchor }))
    }
    rejected("duplicate read-ahead") {
        locateAnalysisLookahead(
            apis() + host(extra = listOf(method(HOST, "another", listOf(DECK), "V", warm())))
        )
    }
    rejected("static read-ahead") { locateAnalysisLookahead(apis() + host(flags = 9)) }
    rejected("private read-ahead") { locateAnalysisLookahead(apis() + host(flags = 2)) }
    rejected("missing state read") { locateAnalysisLookahead(apis() + host(warm().drop(1))) }
    rejected("missing state write") {
        locateAnalysisLookahead(apis() + host(warm().filterIndexed { i, _ -> i != 4 }))
    }
    rejected("missing return") { locateAnalysisLookahead(apis() + host(warm().dropLast(1))) }
    rejected("static state field") {
        locateAnalysisLookahead(
            apis() +
                host(
                    f =
                        listOf(
                            ImmutableField(
                                HOST,
                                "q",
                                "Ljava/util/ArrayList;",
                                9,
                                null,
                                emptySet(),
                                emptySet(),
                            )
                        )
                )
        )
    }
    rejected("foreign opcode writes host state") {
        locateAnalysisLookahead(
            apis() +
                host() +
                type(
                    "Lq/Merged;",
                    listOf(
                        method(
                            "Lq/Merged;",
                            "x",
                            emptyList(),
                            "V",
                            listOf(
                                ImmutableInstruction21c(Opcode.SPUT_OBJECT, 0, field),
                                ImmutableInstruction10x(Opcode.RETURN_VOID),
                            ),
                        )
                    ),
                )
        )
    }
    for (owner in apis()) for (api in owner.methods) {
        for (kind in listOf("missing", "renamed", "static", "wrongtype")) rejected(
            "API$kind:${api.name}"
        ) {
            val changed = owner.methods.filter { it.name != api.name }.toMutableList()
            if (kind != "missing")
                changed +=
                    method(
                        owner.type,
                        if (kind == "renamed") api.name + "X" else api.name,
                        api.parameterTypes.map(Any::toString),
                        if (kind == "wrongtype") OBJECT else api.returnType,
                        null,
                        if (kind == "static") 9 else api.accessFlags,
                    )
            locateAnalysisLookahead(
                apis().filter { it.type != owner.type } + type(owner.type, changed) + host()
            )
        }
    }
    val mutation = MutableMethod(sites.warm)
    mutation.implementation!!.replaceInstruction(
        4,
        com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction22c(
            Opcode.IPUT_OBJECT,
            0,
            5,
            field,
        ),
    )
    rejected("write register changed after discovery") { installAnalysisLookahead(mutation, sites) }
}

fun main(args: Array<String>) {
    synthetic()
    for (path in args) {
        val dex = DexFileFactory.loadDexContainer(File(path), null)
        val all = dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes.toList() }
        val sites = locateAnalysisLookahead(all)
        installed(sites)
        println(
            "PASS native lookahead ${File(path).name}: ${sites.writes.size} state stores, read-ahead=${sites.warm.name}"
        )
    }
    println("PASS: $checks analysis lookahead hook checks")
}

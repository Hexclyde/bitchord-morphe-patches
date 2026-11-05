package app.bitchord.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.*

private const val LOOKAHEAD_HOST = "Lcom/music/bitchord/playback/PlaybackService;"
private const val LOOKAHEAD_CACHE = "Lcom/music/bitchord/playback/AudioCache;"
private const val LOOKAHEAD_PLAYER = "Landroidx/media3/common/Player;"
private const val LOOKAHEAD_DECK = "Landroidx/media3/exoplayer/ExoPlayer;"
private const val LOOKAHEAD_TIMELINE = "Landroidx/media3/common/Timeline;"
private const val LOOKAHEAD_MIX = "Lapp/bitchord/telegram/SourceAutomix;"
private const val LOOKAHEAD_OBJECT = "Ljava/lang/Object;"
private const val LOOKAHEAD_CHANGED = "BitChord analysis lookahead changed"

internal data class AnalysisLookaheadWrite(val method: Method, val at: Int, val owner: Int)

internal data class AnalysisLookaheadSites(
    val warm: Method,
    val request: FieldReference,
    val writes: List<AnalysisLookaheadWrite>,
)

private fun lookaheadCheck(okay: Boolean, reason: String) =
    check(okay) { "$LOOKAHEAD_CHANGED: $reason" }

private fun Instruction.lookaheadCall() =
    (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.lookaheadField() =
    (this as? ReferenceInstruction)?.reference as? FieldReference

private fun MethodReference.lookaheadArgs() = parameterTypes.map { it.toString() }

private fun Method.lookaheadCode() = implementation?.instructions?.toList().orEmpty()

private fun FieldReference.lookaheadKey() = Triple(definingClass, name, type)

private fun Instruction.lookaheadCalls(
    owner: String,
    name: String,
    parameters: List<String>,
    result: String,
) =
    lookaheadCall()?.let {
        it.definingClass == owner &&
            it.name == name &&
            it.lookaheadArgs() == parameters &&
            it.returnType == result
    } == true

internal fun verifyAnalysisLookaheadApi(classes: Iterable<ClassDef>) {
    fun api(owner: String, name: String, args: List<String>, result: String) {
        val types = classes.filter { it.type == owner }
        lookaheadCheck(types.size == 1, "API class $owner")
        val found = types.single().methods.filter { it.name == name }
        lookaheadCheck(found.size == 1, "API method $owner->$name")
        val method = found.single()
        lookaheadCheck(
            method.lookaheadArgs() == args &&
                method.returnType == result &&
                method.accessFlags and AccessFlags.PUBLIC.value != 0 &&
                method.accessFlags and AccessFlags.STATIC.value == 0,
            "API signature $owner->$name",
        )
    }
    api(LOOKAHEAD_PLAYER, "getCurrentTimeline", emptyList(), LOOKAHEAD_TIMELINE)
    api(LOOKAHEAD_PLAYER, "getRepeatMode", emptyList(), "I")
    api(LOOKAHEAD_PLAYER, "getShuffleModeEnabled", emptyList(), "Z")
    api(LOOKAHEAD_PLAYER, "getNextMediaItemIndex", emptyList(), "I")
    api(LOOKAHEAD_TIMELINE, "getNextWindowIndex", listOf("I", "I", "Z"), "I")
    api(LOOKAHEAD_TIMELINE, "getWindowCount", emptyList(), "I")
}

internal fun locateAnalysisLookahead(classes: Iterable<ClassDef>): AnalysisLookaheadSites {
    val all = classes.toList()
    verifyAnalysisLookaheadApi(all)
    val hosts = all.filter { it.type == LOOKAHEAD_HOST }
    lookaheadCheck(hosts.size == 1, "playback service")
    val host = hosts.single()
    val warms =
        host.methods.filter { method ->
            method.lookaheadArgs() == listOf(LOOKAHEAD_DECK) &&
                method.returnType == "V" &&
                method.lookaheadCode().let { code ->
                    code.any {
                        it.lookaheadCalls(
                            LOOKAHEAD_PLAYER,
                            "getNextMediaItemIndex",
                            emptyList(),
                            "I",
                        )
                    } &&
                        code.any {
                            it.lookaheadCalls(
                                LOOKAHEAD_PLAYER,
                                "getMediaItemAt",
                                listOf("I"),
                                "Landroidx/media3/common/MediaItem;",
                            )
                        } &&
                        code.any {
                            it.lookaheadCalls(
                                LOOKAHEAD_CACHE,
                                "noteSongs",
                                listOf("Ljava/util/List;"),
                                "V",
                            )
                        }
                }
        }
    lookaheadCheck(warms.size == 1, "queue read-ahead method")
    val warm = warms.single()
    lookaheadCheck(
        warm.accessFlags and AccessFlags.PUBLIC.value != 0 &&
            warm.accessFlags and AccessFlags.STATIC.value == 0 &&
            warm.implementation!!.registerCount >= 2,
        "queue read-ahead signature",
    )
    val body = warm.lookaheadCode()
    lookaheadCheck(body.any { it.opcode == Opcode.RETURN_VOID }, "queue read-ahead return")
    val read =
        body
            .filter { it.opcode == Opcode.IGET_OBJECT }
            .mapNotNull { it.lookaheadField() }
            .filter { it.definingClass == LOOKAHEAD_HOST && it.type == "Ljava/util/ArrayList;" }
            .map { it.lookaheadKey() }
            .toSet()
    val written =
        body
            .filter { it.opcode == Opcode.IPUT_OBJECT }
            .mapNotNull { it.lookaheadField() }
            .filter { it.lookaheadKey() in read }
            .distinctBy { it.lookaheadKey() }
    lookaheadCheck(written.size == 1, "queue prefetch state")
    val field = written.single()
    val declaration = host.fields.filter { it.lookaheadKey() == field.lookaheadKey() }
    lookaheadCheck(
        declaration.size == 1 && declaration.single().accessFlags and AccessFlags.STATIC.value == 0,
        "queue prefetch field",
    )
    val writes = mutableListOf<AnalysisLookaheadWrite>()
    for (type in all) for (method in type.methods) {
        val code = method.lookaheadCode()
        for ((index, instruction) in code.withIndex()) {
            if (instruction.lookaheadField()?.lookaheadKey() != field.lookaheadKey()) continue
            if (
                !instruction.opcode.toString().startsWith("IPUT") &&
                    !instruction.opcode.toString().startsWith("SPUT")
            )
                continue
            lookaheadCheck(
                instruction.opcode == Opcode.IPUT_OBJECT && instruction is TwoRegisterInstruction,
                "queue prefetch state store",
            )
            val store = instruction as TwoRegisterInstruction
            lookaheadCheck(
                store.registerA < method.implementation!!.registerCount &&
                    store.registerB < method.implementation!!.registerCount,
                "queue prefetch store registers",
            )
            writes += AnalysisLookaheadWrite(method, index, store.registerB)
        }
    }
    lookaheadCheck(writes.isNotEmpty(), "queue prefetch state writes")
    return AnalysisLookaheadSites(warm, field, writes)
}

internal fun installAnalysisLookahead(method: MutableMethod, sites: AnalysisLookaheadSites) {
    val writes =
        sites.writes
            .filter {
                it.method.definingClass == method.definingClass &&
                    it.method.name == method.name &&
                    it.method.lookaheadArgs() == method.lookaheadArgs() &&
                    it.method.returnType == method.returnType
            }
            .associateBy { it.at }
    val warm =
        method.definingClass == sites.warm.definingClass &&
            method.name == sites.warm.name &&
            method.lookaheadArgs() == sites.warm.lookaheadArgs() &&
            method.returnType == sites.warm.returnType
    for (index in method.lookaheadCode().indices.reversed()) {
        val write = writes[index]
        if (write != null) {
            val actual = method.lookaheadCode()[index]
            lookaheadCheck(
                actual.opcode == Opcode.IPUT_OBJECT &&
                    actual.lookaheadField()?.lookaheadKey() == sites.request.lookaheadKey() &&
                    (actual as TwoRegisterInstruction).registerB == write.owner,
                "queue store changed during patching",
            )
            method.addInstruction(
                index + 1,
                "invoke-static/range {v${write.owner} .. v${write.owner}}, $LOOKAHEAD_MIX->cancelLookahead($LOOKAHEAD_OBJECT)V",
            )
        }
        if (warm && method.lookaheadCode()[index].opcode == Opcode.RETURN_VOID) {
            method.replaceInstruction(
                index,
                "invoke-static/range {p0 .. p1}, $LOOKAHEAD_MIX->queueChanged($LOOKAHEAD_OBJECT$LOOKAHEAD_OBJECT)V",
            )
            method.addInstruction(index + 1, "return-void")
        }
    }
}

internal fun BytecodePatchContext.addAnalysisLookaheadHooks() {
    val all = mutableListOf<ClassDef>()
    classDefForEach { all += it }
    val sites = locateAnalysisLookahead(all)
    val mix = all.singleOrNull { it.type == LOOKAHEAD_MIX }
    for ((name, args) in
        listOf(
            "queueChanged" to listOf(LOOKAHEAD_OBJECT, LOOKAHEAD_OBJECT),
            "cancelLookahead" to listOf(LOOKAHEAD_OBJECT),
            "editor" to listOf(LOOKAHEAD_OBJECT, "Z"),
        )) {
        val found = mix?.methods?.filter { it.name == name }.orEmpty()
        lookaheadCheck(
            found.size == 1 &&
                found.single().lookaheadArgs() == args &&
                found.single().returnType == "V" &&
                found.single().accessFlags and 9 == 9 &&
                found.single().implementation != null,
            "SourceAutomix.$name",
        )
    }
    val methods =
        (sites.writes.map { it.method } + sites.warm).distinctBy {
            Triple(it.definingClass, it.name, it.lookaheadArgs() to it.returnType)
        }
    for (original in methods) {
        val method =
            mutableClassDefBy(original.definingClass).methods.single {
                it.name == original.name &&
                    it.lookaheadArgs() == original.lookaheadArgs() &&
                    it.returnType == original.returnType
            }
        installAnalysisLookahead(method, sites)
    }
}

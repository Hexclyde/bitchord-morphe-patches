package app.bitchord.patches

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal data class TransitionTempoSite(val method: Method, val instruction: Int)

internal fun verifyTransitionTempoDeckApi(controller: ClassDef) {
    for (name in listOf("outgoing", "incoming", "easing")) {
        val fields = controller.fields.filter { it.name == name }
        check(
            fields.size == 1 &&
                fields.single().type == "Landroidx/media3/exoplayer/ExoPlayer;" &&
                fields.single().accessFlags and AccessFlags.STATIC.value == 0
        ) {
            "Automix deck ownership changed in BitChord: $name"
        }
    }
}

internal fun locateTransitionTempoStart(controller: ClassDef): TransitionTempoSite {
    fun Instruction.call() = ((this as? ReferenceInstruction)?.reference as? MethodReference)
    fun Instruction.arguments(): List<Int> =
        when (this) {
            is FiveRegisterInstruction ->
                listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
            is RegisterRangeInstruction ->
                (startRegister until startRegister + registerCount).toList()
            else -> error("Automix incoming playback registers changed in BitChord")
        }
    val candidates =
        controller.methods.flatMap { method ->
            val code = method.implementation?.instructions?.toList().orEmpty()
            code.indices
                .filter { at ->
                    at > 0 &&
                        at + 1 < code.size &&
                        code[at].call()?.let {
                            it.definingClass == "Landroidx/media3/common/Player;" &&
                                it.name == "setPlayWhenReady" &&
                                it.parameterTypes == listOf("Z")
                        } == true &&
                        code[at - 1].call()?.let {
                            it.definingClass == "Landroidx/media3/common/Player;" &&
                                it.name == "setVolume" &&
                                it.parameterTypes == listOf("F")
                        } == true &&
                        code[at + 1].call()?.let {
                            it.definingClass == "Landroid/os/SystemClock;" &&
                                it.name == "elapsedRealtime" &&
                                it.parameterTypes.isEmpty() &&
                                it.returnType == "J"
                        } == true
                }
                .map { TransitionTempoSite(method, it) }
        }
    check(candidates.size == 1) { "Automix incoming blend-start sequence changed in BitChord" }
    val site = candidates.single()
    check(
        site.method.accessFlags and AccessFlags.STATIC.value == 0 && site.method.returnType == "V"
    ) {
        "Automix blend start no longer has a controller receiver"
    }
    val code = site.method.implementation!!.instructions.toList()
    check(
        listOf(site.instruction, site.instruction - 1).all {
            code[it].opcode in listOf(Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE)
        }
    ) {
        "Automix incoming deck invocation changed in BitChord"
    }
    val play = code[site.instruction].arguments()
    val volume = code[site.instruction - 1].arguments()
    check(play.size == 2 && volume.size == 2 && play[0] == volume[0]) {
        "Automix incoming blend deck changed in BitChord"
    }
    fun literalBefore(at: Int, register: Int): Int? =
        (code.take(at).lastOrNull {
                it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == register
            } as? NarrowLiteralInstruction)
            ?.narrowLiteral
    check(
        literalBefore(site.instruction, play[1]) == 1 &&
            literalBefore(site.instruction - 1, volume[1]) == 0
    ) {
        "Automix blend start no longer starts a silent incoming deck"
    }
    return site
}

internal fun verifyTransitionPairApi(lookup: (String) -> ClassDef) {
    val changed = "Automix selected-pair API changed in BitChord"
    fun owner(type: String) = runCatching { lookup(type) }.getOrElse { error("$changed: $type") }
    fun needs(type: String, name: String, args: List<String>, result: String? = null): Method {
        val found =
            owner(type).methods.filter {
                it.name == name &&
                    it.parameterTypes.map(CharSequence::toString) == args &&
                    (result == null || it.returnType == result) &&
                    it.accessFlags and AccessFlags.PUBLIC.value != 0
            }
        check(found.size == 1) { "$changed: $type->$name" }
        return found.single()
    }
    fun field(type: String, name: String, value: String) {
        check(owner(type).fields.any { it.name == name && it.type == value }) {
            "$changed: $type->$name"
        }
    }
    val item = "Landroidx/media3/common/MediaItem;"
    val metadata = "Landroidx/media3/common/MediaMetadata;"
    val builder = needs(item, "buildUpon", emptyList()).returnType
    val values = needs(metadata, "buildUpon", emptyList()).returnType
    val build = needs(builder, "build", emptyList(), item)
    needs(builder, "setUri", listOf("Ljava/lang/String;"), "V")
    val slots = owner(builder).fields.filter { it.name == "mediaMetadata" }
    check(slots.size == 1 && slots.single().type in listOf(metadata, "Ljava/lang/Object;")) {
        "$changed: metadata slot"
    }
    if (slots.single().type != metadata) {
        val code = build.implementation?.instructions?.toList().orEmpty()
        check(
            code.indices.any { at ->
                val read = code[at]
                val slot = (read as? ReferenceInstruction)?.reference as? FieldReference
                read.opcode == Opcode.IGET_OBJECT &&
                    slot?.name == "mediaMetadata" &&
                    slot.definingClass == builder &&
                    code.drop(at + 1).take(4).any { cast ->
                        cast.opcode == Opcode.CHECK_CAST &&
                            (cast as? OneRegisterInstruction)?.registerA ==
                                (read as? OneRegisterInstruction)?.registerA &&
                            ((cast as? ReferenceInstruction)?.reference as? TypeReference)?.type ==
                                metadata
                    }
            }
        ) {
            "$changed: erased metadata slot is not read as metadata"
        }
    }
    field(values, "extras", "Landroid/os/Bundle;")
    check(
        owner(metadata).methods.any {
            it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf(values)
        }
    ) {
        "$changed: metadata constructor"
    }
    val player = "Landroidx/media3/common/Player;"
    needs(player, "replaceMediaItem", listOf("I", item), "V")
    needs(player, "getNextMediaItemIndex", emptyList(), "I")
    needs(player, "getRepeatMode", emptyList(), "I")
    needs(player, "getShuffleModeEnabled", emptyList(), "Z")
    needs(player, "seekTo", listOf("I", "J"), "V")
    val converter = "Lcom/music/bitchord/playback/PlayerConnectionKt;"
    val toSong = needs(converter, "toSong", listOf(item))
    val toItem = needs(converter, "toMediaItem", listOf(toSong.returnType), item)
    check(listOf(toSong, toItem).all { it.accessFlags and AccessFlags.STATIC.value != 0 }) {
        "$changed: song conversion is no longer static"
    }
}

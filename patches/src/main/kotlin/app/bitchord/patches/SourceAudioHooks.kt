package app.bitchord.patches

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.*
import com.android.tools.smali.dexlib2.immutable.*
import com.android.tools.smali.dexlib2.immutable.instruction.*
import com.android.tools.smali.dexlib2.immutable.reference.*

private const val DECODER_CHANGED = "BitChord native decoder changed"
internal const val NATIVE_DECODER = "Lcom/music/bitchord/playback/smart/AudioDecoder;"
internal const val SOURCE_AUDIO = "Lapp/bitchord/telegram/SourceAudio;"
private const val DECODER_SOURCE = "Landroid/media/MediaDataSource;"
private const val DECODER_OBJECT = "Ljava/lang/Object;"
private const val DECODER_PAIR = "Lkotlin/Pair;"
private const val DECODER_DOUBLE = "Ljava/lang/Double;"
private const val DECODER_FUNCTION0 = "Lkotlin/jvm/functions/Function0;"
private const val DECODER_FUNCTION4 = "Lkotlin/jvm/functions/Function4;"
private const val DECODER_EXTRACTOR = "Landroid/media/MediaExtractor;"
private const val DECODER_CODEC = "Landroid/media/MediaCodec;"
private const val DECODER_INFO = "Landroid/media/MediaCodec${'$'}BufferInfo;"
private const val DECODER_PUBLIC_STATIC = 9
private val decoderRawParameters =
    listOf(DECODER_SOURCE, "D", "D", DECODER_FUNCTION0, DECODER_FUNCTION4)
private val decoderDurationParameters = listOf(DECODER_SOURCE)
private val decoderRawTarget =
    ImmutableMethodReference(SOURCE_AUDIO, "decodeRaw", decoderRawParameters, DECODER_PAIR)
private val decoderDurationTarget =
    ImmutableMethodReference(
        SOURCE_AUDIO,
        "containerDurationSeconds",
        decoderDurationParameters,
        DECODER_DOUBLE,
    )

internal class SourceAudioSites(val raw: Method, val duration: Method)

private fun decoderCheck(okay: Boolean, label: String) = check(okay) { "$DECODER_CHANGED: $label" }

private fun Method.decoderArguments() = parameterTypes.map { it.toString() }

private fun Method.decoderCode() = implementation?.instructions?.toList().orEmpty()

private fun List<Instruction>.decoderCalls(
    owner: String,
    name: String,
    parameters: List<String>,
    result: String,
) = any {
    val call = (it as? ReferenceInstruction)?.reference as? MethodReference
    call != null &&
        call.definingClass == owner &&
        call.name == name &&
        call.parameterTypes.map { type -> type.toString() } == parameters &&
        call.returnType == result
}

private fun List<Instruction>.decoderString(value: String) = any {
    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == value
}

private fun List<Instruction>.decoderField(owner: String, name: String, type: String) = any {
    val field = (it as? ReferenceInstruction)?.reference as? FieldReference
    field != null && field.definingClass == owner && field.name == name && field.type == type
}

private fun ClassDef.decoderOne(parameters: List<String>, result: String, label: String): Method {
    val matches = methods.filter { it.decoderArguments() == parameters && it.returnType == result }
    decoderCheck(matches.size == 1, "$label signature")
    return matches.single()
}

private fun decoderSignature(method: Method, parameters: List<String>, result: String) =
    method.decoderArguments() == parameters && method.returnType == result

/** Finds the two native entry points by signature and behaviour on the native decoder class. */
internal fun locateSourceAudioDecoder(
    classes: Iterable<ClassDef>,
    owner: String = NATIVE_DECODER,
): SourceAudioSites {
    val types = classes.filter { it.type == owner }
    decoderCheck(types.size == 1, "decoder class")
    val raw = types.single().decoderOne(decoderRawParameters, DECODER_PAIR, "raw decode")
    val duration =
        types.single().decoderOne(decoderDurationParameters, DECODER_DOUBLE, "container duration")
    decoderCheck(raw.accessFlags == DECODER_PUBLIC_STATIC, "raw decode access")
    decoderCheck(duration.accessFlags == DECODER_PUBLIC_STATIC, "container duration access")
    val rawCode = raw.decoderCode()
    val durationCode = duration.decoderCode()
    decoderCheck(rawCode.isNotEmpty() && durationCode.isNotEmpty(), "decoder implementation")
    val open = listOf(DECODER_SOURCE)
    decoderCheck(
        rawCode.decoderCalls(DECODER_EXTRACTOR, "setDataSource", open, "V") &&
            rawCode.decoderCalls(DECODER_EXTRACTOR, "seekTo", listOf("J", "I"), "V") &&
            rawCode.decoderCalls(
                DECODER_CODEC,
                "getOutputBuffer",
                listOf("I"),
                "Ljava/nio/ByteBuffer;",
            ) &&
            rawCode.decoderCalls(DECODER_FUNCTION0, "invoke", emptyList(), DECODER_OBJECT) &&
            rawCode.decoderCalls(
                DECODER_FUNCTION4,
                "invoke",
                List(4) { DECODER_OBJECT },
                DECODER_OBJECT,
            ) &&
            rawCode.decoderCalls(
                DECODER_PAIR,
                "<init>",
                listOf(DECODER_OBJECT, DECODER_OBJECT),
                "V",
            ) &&
            rawCode.decoderField(DECODER_INFO, "presentationTimeUs", "J"),
        "raw decode behaviour",
    )
    decoderCheck(
        durationCode.decoderCalls(DECODER_EXTRACTOR, "setDataSource", open, "V") &&
            durationCode.decoderCalls(
                "Landroid/media/MediaFormat;",
                "getLong",
                listOf("Ljava/lang/String;"),
                "J",
            ) &&
            durationCode.decoderString("durationUs") &&
            durationCode.decoderCalls(DECODER_EXTRACTOR, "release", emptyList(), "V"),
        "container duration behaviour",
    )
    return SourceAudioSites(raw, duration)
}

/** The extension must provide both public static delegates before the native bodies are removed. */
internal fun requireSourceAudio(classes: Iterable<ClassDef>) {
    val types = classes.filter { it.type == SOURCE_AUDIO }
    decoderCheck(types.size == 1, "SourceAudio class")
    for ((target, name) in
        listOf(
            decoderRawTarget to "decodeRaw",
            decoderDurationTarget to "containerDurationSeconds",
        )) {
        val matches =
            types.single().methods.filter {
                it.name == target.name &&
                    decoderSignature(it, target.decoderArguments(), target.returnType)
            }
        decoderCheck(
            matches.size == 1 &&
                matches.single().accessFlags == DECODER_PUBLIC_STATIC &&
                matches.single().implementation != null,
            "SourceAudio.$name",
        )
    }
}

private fun MethodReference.decoderArguments() = parameterTypes.map { it.toString() }

/** Keeps name, flags and annotations; the whole body becomes one call into the extension. */
internal fun redirectedDecoder(old: Method, raw: Boolean): ImmutableMethod {
    val code: List<Instruction> =
        if (raw) {
            listOf(
                ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE, 1, 7, decoderRawTarget),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
            )
        } else {
            listOf(
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    1,
                    1,
                    0,
                    0,
                    0,
                    0,
                    decoderDurationTarget,
                ),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
            )
        }
    return ImmutableMethod(
        old.definingClass,
        old.name,
        old.parameters.map { ImmutableMethodParameter.of(it) },
        old.returnType,
        old.accessFlags,
        old.annotations.map { ImmutableAnnotation.of(it) }.toSet(),
        old.hiddenApiRestrictions,
        ImmutableMethodImplementation(if (raw) 8 else 2, code, emptyList(), emptyList()),
    )
}

/** True only for the exact one-call delegate: no handlers, no other instructions. */
internal fun sourceAudioRedirect(method: Method, raw: Boolean): Boolean {
    val target = if (raw) decoderRawTarget else decoderDurationTarget
    val implementation = method.implementation ?: return false
    val code = implementation.instructions.toList()
    if (
        method.accessFlags != DECODER_PUBLIC_STATIC ||
            !decoderSignature(
                method,
                if (raw) decoderRawParameters else decoderDurationParameters,
                target.returnType,
            ) ||
            implementation.registerCount != (if (raw) 8 else 2) ||
            implementation.tryBlocks.isNotEmpty() ||
            code.size != 3
    )
        return false
    val call = code[0]
    val reference = (call as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    val shape =
        if (raw) {
            call.opcode == Opcode.INVOKE_STATIC_RANGE &&
                call is RegisterRangeInstruction &&
                call.startRegister == 1 &&
                call.registerCount == 7
        } else {
            call.opcode == Opcode.INVOKE_STATIC &&
                call is FiveRegisterInstruction &&
                call.registerCount == 1 &&
                call.registerC == 1
        }
    return shape &&
        reference.definingClass == target.definingClass &&
        reference.name == target.name &&
        reference.decoderArguments() == target.decoderArguments() &&
        reference.returnType == target.returnType &&
        code[1].opcode == Opcode.MOVE_RESULT_OBJECT &&
        (code[1] as OneRegisterInstruction).registerA == 0 &&
        code[2].opcode == Opcode.RETURN_OBJECT &&
        (code[2] as OneRegisterInstruction).registerA == 0
}

internal fun BytecodePatchContext.addSourceAudioDecoder() {
    val all = mutableListOf<ClassDef>()
    classDefForEach { all += it }
    requireSourceAudio(all)
    val sites = locateSourceAudioDecoder(all)
    installSourceAudioDecoder(mutableClassDefBy(NATIVE_DECODER), sites)
    println("Redirected the native audio decoder to SourceAudio")
}

internal fun installSourceAudioDecoder(type: MutableClass, sites: SourceAudioSites) {
    for ((site, raw) in listOf(sites.raw to true, sites.duration to false)) {
        val old =
            type.methods.filter { decoderSignature(it, site.decoderArguments(), site.returnType) }
        decoderCheck(old.size == 1, "decoder method changed during patching")
        val replacement = redirectedDecoder(old.single(), raw)
        decoderCheck(
            type.methods.removeIf {
                decoderSignature(it, site.decoderArguments(), site.returnType)
            },
            "decoder method removal",
        )
        type.methods.add(MutableMethod(replacement))
    }
}

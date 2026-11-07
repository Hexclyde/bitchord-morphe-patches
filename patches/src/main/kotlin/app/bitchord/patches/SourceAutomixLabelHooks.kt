package app.bitchord.patches

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

// R8 moves the native string helper between classes, so it is found by its shape, after the read of
// the blend label's string resource.
internal fun automixLabelTextAt(code: List<Instruction>): Int {
    val reference = { it: Instruction -> (it as? ReferenceInstruction)?.reference }
    val read =
        code.indexOfFirst { (reference(it) as? FieldReference)?.name == "mixing${'$'}delegate" }
    val calls =
        code.indices.filter { index ->
            index > read &&
                (code[index].opcode == Opcode.INVOKE_STATIC ||
                    code[index].opcode == Opcode.INVOKE_STATIC_RANGE) &&
                (reference(code[index]) as? MethodReference)?.let {
                    it.returnType == "Ljava/lang/String;" &&
                        it.parameterTypes.map { type -> type.toString() } ==
                            listOf(
                                "Lorg/jetbrains/compose/resources/StringResource;",
                                "Landroidx/compose/runtime/ComposerImpl;",
                            )
                } == true
        }
    check(
        read >= 0 &&
            calls.size == 1 &&
            code.getOrNull(calls.single() + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT
    ) {
        "AutoMix's localized blend label no longer has one native string result"
    }
    return calls.single() + 1
}

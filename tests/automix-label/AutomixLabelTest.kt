package app.bitchord.patches

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.instruction.*
import com.android.tools.smali.dexlib2.immutable.reference.*

private const val RESOURCE = "Lorg/jetbrains/compose/resources/StringResource;"
private const val COMPOSER = "Landroidx/compose/runtime/ComposerImpl;"
private var passed = 0

private fun call(owner: String, result: String, vararg types: String) =
    ImmutableInstruction35c(
        Opcode.INVOKE_STATIC,
        types.size,
        0,
        1,
        0,
        0,
        0,
        ImmutableMethodReference(owner, "stringResource", types.toList(), result),
    )

private fun label(helper: Instruction, field: String = "mixing\$delegate"): List<Instruction> =
    listOf(
        ImmutableInstruction21c(
            Opcode.SGET_OBJECT,
            2,
            ImmutableFieldReference(
                "Lcom/music/bitchord/sharedui/resources/String0_commonMainKt;",
                field,
                "Lkotlin/SynchronizedLazyImpl;",
            ),
        ),
        helper,
        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 13),
    )

private fun accepts(name: String, code: List<Instruction>) {
    check(automixLabelTextAt(code) == 2) { name }
    passed++
}

private fun refuses(name: String, code: List<Instruction>) {
    try {
        automixLabelTextAt(code)
        error("Accepted $name")
    } catch (expected: IllegalStateException) {
        check(expected.message.orEmpty().startsWith("AutoMix's localized blend label"))
        passed++
    }
}

fun main() {
    val text = "Ljava/lang/String;"
    val helper = { owner: String -> call(owner, text, RESOURCE, COMPOSER) }
    accepts("1.9 helper owner", label(helper("Lcom/music/bitchord/ui/AppUi;")))
    accepts("1.9.1 helper owner", label(helper("Lkotlin/jvm/JvmClassMappingKt;")))
    refuses("no mixing string", label(helper("Lcom/music/bitchord/ui/AppUi;"), "other"))
    refuses("other result type", label(call("Lx/Y;", "Ljava/lang/Object;", RESOURCE, COMPOSER)))
    refuses("other parameters", label(call("Lx/Y;", text, RESOURCE, "I")))
    val extra =
        label(helper("Lx/Y;")) +
            helper("Lx/Z;") +
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1)
    refuses("second string helper", extra)
    refuses(
        "result not moved",
        label(helper("Lx/Y;")).toMutableList().apply {
            this[2] = ImmutableInstruction10x(Opcode.NOP)
        },
    )
    println("AutoMix blend label guards: $passed passed")
}

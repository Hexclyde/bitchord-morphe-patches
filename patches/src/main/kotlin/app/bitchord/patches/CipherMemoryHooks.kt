package app.bitchord.patches

import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.*

private const val CIPHER_CHANGED = "YouTube cipher script layout changed"
private const val CIPHER_TEXT = "Ljava/lang/String;"
private const val CIPHER_OBJECT = "Ljava/lang/Object;"
private const val CIPHER_CONTINUATION = "Lkotlin/coroutines/Continuation;"
private val cipherEvaluateParameters = listOf(CIPHER_TEXT, "I", "Z", CIPHER_CONTINUATION)

private fun Instruction.cipherCall() =
    (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Method.cipherCode() = implementation?.instructions?.toList().orEmpty()

private fun MethodReference.cipherKey() =
    "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun Instruction.cipherString() =
    ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

private fun Instruction.cipherRegisters(): List<Int> =
    when (this) {
        is FiveRegisterInstruction ->
            listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        else -> error("$CIPHER_CHANGED: invocation encoding")
    }

private fun <T> Collection<T>.cipherSingle(label: String): T {
    check(size == 1) { "$CIPHER_CHANGED: $label ($size found)" }
    return single()
}

internal data class CipherMemorySite(val method: Method, val instruction: Int, val register: Int)

internal fun cipherWrapperTrim(method: Method): CipherMemorySite {
    val code = method.cipherCode()
    val at =
        code.indices
            .filter {
                code[it].cipherCall()?.let { call ->
                    call.definingClass.startsWith("Lkotlin/text/") &&
                        call.name == "trimIndent" &&
                        call.parameterTypes.map { p -> p.toString() } == listOf(CIPHER_TEXT) &&
                        call.returnType == CIPHER_TEXT
                } == true
            }
            .cipherSingle("wrapper indentation copy")
    check(at >= 2 && at + 1 < code.size) { "$CIPHER_CHANGED: wrapper instruction bounds" }
    val call = code[at]
    check(call.opcode in listOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)) {
        "$CIPHER_CHANGED: indentation call is not static"
    }
    val input = call.cipherRegisters().cipherSingle("wrapper argument")
    val before = code[at - 1] as? OneRegisterInstruction
    val after = code[at + 1] as? OneRegisterInstruction
    check(
        code[at - 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
            before?.registerA == input &&
            code[at + 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
            after?.registerA == input
    ) {
        "$CIPHER_CHANGED: wrapper string register is not preserved"
    }
    check(
        code[at - 2].cipherCall()?.let {
            it.definingClass == "Ljava/lang/StringBuilder;" &&
                it.name == "toString" &&
                it.parameterTypes.isEmpty() &&
                it.returnType == CIPHER_TEXT
        } == true
    ) {
        "$CIPHER_CHANGED: wrapper builder changed"
    }
    return CipherMemorySite(method, at, input)
}

/** Removes whole-script indentation copies after escaped cipher payloads have been assembled. */
internal fun locateCipherMemory(all: List<ClassDef>): List<CipherMemorySite> {
    val methods = all.flatMap { it.methods }
    fun Method.strings() = cipherCode().mapNotNull { it.cipherString() }
    val solve =
        methods
            .filter { method ->
                val strings = method.strings()
                strings.any { "var payload = " in it } &&
                    strings.any { "var r = jsc(payload);" in it }
            }
            .cipherSingle("EJS payload wrapper")
    check(
        solve.parameterTypes.map { it.toString() } ==
            listOf(CIPHER_TEXT, CIPHER_TEXT, "Ljava/util/List;", "Z", CIPHER_CONTINUATION) &&
            solve.returnType == CIPHER_OBJECT &&
            solve.accessFlags and AccessFlags.STATIC.value == 0
    ) {
        "$CIPHER_CHANGED: EJS wrapper signature"
    }
    val evaluate =
        solve
            .cipherCode()
            .mapNotNull { it.cipherCall() }
            .filter {
                it.parameterTypes.map { p -> p.toString() } == cipherEvaluateParameters &&
                    it.returnType == CIPHER_OBJECT
            }
            .cipherSingle("EJS evaluation call")
    val nativeEvaluate =
        methods.filter { it.cipherKey() == evaluate.cipherKey() }.cipherSingle("native evaluator")
    check(nativeEvaluate.accessFlags and AccessFlags.STATIC.value == 0) {
        "$CIPHER_CHANGED: evaluator is not an instance method"
    }
    val defaults =
        methods
            .filter {
                it.definingClass == evaluate.definingClass &&
                    it.returnType == CIPHER_OBJECT &&
                    it.accessFlags and (AccessFlags.STATIC.value or AccessFlags.SYNTHETIC.value) ==
                        (AccessFlags.STATIC.value or AccessFlags.SYNTHETIC.value) &&
                    it.parameterTypes.map { p -> p.toString() } ==
                        listOf(evaluate.definingClass) +
                            cipherEvaluateParameters +
                            listOf("I", CIPHER_OBJECT)
            }
            .cipherSingle("unused evaluator default bridge")
    val callers =
        methods.flatMap { method ->
            method
                .cipherCode()
                .mapNotNull { it.cipherCall() }
                .filter { it.cipherKey() == evaluate.cipherKey() }
                .map { method }
        }
    check(
        callers.size == 2 &&
            callers.count { it.cipherKey() == solve.cipherKey() } == 1 &&
            callers.count { it.cipherKey() == defaults.cipherKey() } == 1
    ) {
        "$CIPHER_CHANGED: evaluator has a non-EJS caller"
    }
    check(
        methods.none { method ->
            method.cipherCode().any { it.cipherCall()?.cipherKey() == defaults.cipherKey() }
        }
    ) {
        "$CIPHER_CHANGED: generic evaluator default bridge is used"
    }
    val bounded =
        methods
            .filter { method ->
                val strings = method.strings()
                strings.any { "const value = (" in it } &&
                    strings.any { "return text.length <= " in it }
            }
            .cipherSingle("bounded evaluator wrapper")
    check(
        bounded.parameterTypes.map { it.toString() } == listOf(CIPHER_OBJECT) &&
            bounded.returnType == CIPHER_OBJECT &&
            nativeEvaluate.cipherCode().any {
                ((it as? ReferenceInstruction)?.reference as? TypeReference)?.type ==
                    bounded.definingClass
            }
    ) {
        "$CIPHER_CHANGED: bounded evaluator ownership"
    }
    val calls = bounded.cipherCode().mapNotNull { it.cipherCall() }
    check(
        calls.count {
            it.definingClass == "Lcom/dokar/quickjs/QuickJs;" &&
                it.name == "gc" &&
                it.parameterTypes.isEmpty() &&
                it.returnType == "V"
        } == 2 &&
            calls.any {
                it.definingClass == "Lcom/dokar/quickjs/QuickJs;" &&
                    it.name == "setMaxStackSize" &&
                    it.parameterTypes.map { p -> p.toString() } == listOf("J")
            } &&
            calls.any { it.name == "unlock" && it.returnType == "V" } &&
            bounded.strings().contains("Invalid QuickJS result limit")
    ) {
        "$CIPHER_CHANGED: evaluator limits, stack, cleanup or mutex changed"
    }
    return listOf(cipherWrapperTrim(solve), cipherWrapperTrim(bounded))
}

internal fun BytecodePatchContext.addCipherMemoryHooks() {
    val all = mutableListOf<ClassDef>()
    classDefForEach { all += it }
    for (site in locateCipherMemory(all)) {
        val method =
            mutableClassDefBy(site.method.definingClass).methods.single {
                it.name == site.method.name &&
                    it.parameterTypes == site.method.parameterTypes &&
                    it.returnType == site.method.returnType
            }
        method.removeInstructions(site.instruction, 2)
    }
    println("Removed redundant YouTube cipher script copies")
}

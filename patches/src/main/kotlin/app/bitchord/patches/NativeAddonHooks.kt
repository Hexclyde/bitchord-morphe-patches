package app.bitchord.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.*
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * Wraps the editor callback of an addon row in Sources. The row's click lambda is the only invoke(5
 * arguments) that branches on SourceKind.needsServer; R8 numbers the lambda, so it is found by that
 * read. [wrapperClass] declares a static `wrap(Function1): Function1`.
 */
internal fun BytecodePatchContext.wrapSourceRowEditor(wrapperClass: String) {
    val owners = mutableListOf<String>()
    classDefForEach { clazz ->
        if (
            clazz.methods.any { method ->
                method.name == "invoke" &&
                    method.parameterTypes.size == 5 &&
                    method.implementation?.instructions?.any {
                        ((it as? ReferenceInstruction)?.reference as? FieldReference)?.let { ref ->
                            ref.definingClass == "Lcom/music/bitchord/data/sources/SourceKind;" &&
                                ref.name == "needsServer"
                        } == true
                    } == true
            }
        )
            owners += clazz.type
    }
    check(owners.size == 1) { "Source row click lambda not unique (${owners.size})" }
    val row = mutableClassDefBy(owners.single())
    val invoke = row.methods.single { it.name == "invoke" && it.parameterTypes.size == 5 }
    val code = invoke.implementation!!.instructions.toList()
    val decision =
        code.indexOfFirst {
            ((it as? ReferenceInstruction)?.reference as? FieldReference)?.let { ref ->
                ref.definingClass == "Lcom/music/bitchord/data/sources/SourceKind;" &&
                    ref.name == "needsServer"
            } == true
        }
    check(decision >= 0) { "Source row click decision missing" }
    val load =
        (decision + 1 until minOf(decision + 8, code.size)).single {
            val ref = (code[it] as? ReferenceInstruction)?.reference as? FieldReference
            code[it].opcode == Opcode.IGET_OBJECT &&
                ref?.definingClass == row.type &&
                ref.type == "Lkotlin/jvm/functions/Function1;"
        }
    val editor = (code[load] as TwoRegisterInstruction).registerA
    // The click callback is the lambda built from the editor: the editor first, then a trailing
    // selector.
    val click =
        (load + 1 until minOf(load + 24, code.size)).single {
            val ref = (code[it] as? ReferenceInstruction)?.reference as? MethodReference
            code[it].opcode == Opcode.INVOKE_DIRECT &&
                ref?.name == "<init>" &&
                ref.parameterTypes.size == 3 &&
                ref.parameterTypes.last() == "I" &&
                ref.parameterTypes.first() in
                    setOf("Ljava/lang/Object;", "Lkotlin/jvm/functions/Function1;") &&
                (code[it] as FiveRegisterInstruction).registerD == editor
        }
    check(editor < 16 && click > load) { "Source row click callback register changed" }
    invoke.addInstructions(
        load + 1,
        """
        invoke-static {v$editor}, $wrapperClass->wrap(Lkotlin/jvm/functions/Function1;)Lkotlin/jvm/functions/Function1;
        move-result-object v$editor
    """,
    )
}

internal class AddonBodyHost(val method: MutableMethod, val url: FieldReference)

private fun Instruction.method() = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.string() =
    ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

/**
 * The coroutine body that performs AddonClient's plain HTTP request on its IO scope. It is the one
 * method that builds the request from a captured URL and reports the addon-specific failures ("Not
 * held by this addon"); R8 folds it into an unrelated lambda class under a synthetic method name,
 * so it is found by those calls and strings.
 */
internal fun BytecodePatchContext.addonBodyHost(): AddonBodyHost {
    val owners = mutableListOf<Pair<ClassDef, Method>>()
    classDefForEach { clazz ->
        for (method in clazz.methods) {
            val code = method.implementation?.instructions?.toList() ?: continue
            if (
                method.returnType == "Ljava/lang/Object;" &&
                    method.parameterTypes == listOf("Ljava/lang/Object;") &&
                    code.any { it.string() == "Not held by this addon" } &&
                    code.any { it.string() == "Retry-After" } &&
                    code.any {
                        it.method()?.let { call ->
                            call.definingClass == "Lokhttp3/Request\$Builder;" &&
                                call.name == "url" &&
                                call.parameterTypes == listOf("Ljava/lang/String;")
                        } == true
                    } &&
                    code.any {
                        ((it as? ReferenceInstruction)?.reference as? FieldReference)?.name ==
                            "COROUTINE_SUSPENDED"
                    }
            )
                owners += clazz to method
        }
    }
    check(owners.size == 1) { "Addon IO coroutine is not unique (${owners.size})" }
    val (clazz, original) = owners.single()
    val code = original.implementation!!.instructions.toList()
    val urlCall =
        code.indices.single {
            code[it].method()?.let { call ->
                call.definingClass == "Lokhttp3/Request\$Builder;" && call.name == "url"
            } == true
        }
    val self = original.implementation!!.registerCount - original.parameterTypes.size - 1
    val read = code[urlCall - 2] as? TwoRegisterInstruction
    val field = (code[urlCall - 2] as? ReferenceInstruction)?.reference as? FieldReference
    val cast = code[urlCall - 1]
    check(
        code[urlCall - 2].opcode == Opcode.IGET_OBJECT &&
            read?.registerB == self &&
            field?.definingClass == clazz.type &&
            field.type == "Ljava/lang/Object;" &&
            cast.opcode == Opcode.CHECK_CAST &&
            (cast as OneRegisterInstruction).registerA == read.registerA &&
            (cast as ReferenceInstruction).reference.toString() == "Ljava/lang/String;" &&
            (code[urlCall] as FiveRegisterInstruction).registerD == read.registerA &&
            original.implementation!!.registerCount - self - 1 >= 1
    ) {
        "Addon request capture changed"
    }
    val method =
        mutableClassDefBy(clazz.type).methods.single {
            it.name == original.name && it.parameterTypes == original.parameterTypes
        }
    return AddonBodyHost(method, field!!)
}

internal class DirectDownloadHost(
    private val method: MutableMethod,
    private val code: List<Instruction>,
    private val type: String,
    private val index: Int,
    private val scope: Int,
    private val self: Int,
    private val scratch: List<Int>,
) {
    /**
     * Answers [reservedOwner]'s identifiers with [fetchOwner].fetch, using the host coroutine's IO
     * scope, output stream, progress callback and cancellation. Other URLs continue into the
     * original HTTP request. A dispatcher another native patch installed ahead of the request stays
     * first, so either selection order ends up in one chain.
     */
    fun install(reservedOwner: String, fetchOwner: String, label: String, previous: Set<String>) {
        val (path, app) = scratch
        val (extra, context) = scratch.drop(2)
        val existing =
            (maxOf(0, index - 40) until index - 2).filter { at ->
                val read = code[at] as? TwoRegisterInstruction
                val field = (code[at] as? ReferenceInstruction)?.reference as? FieldReference
                val target = code[at + 2].method()
                code[at].opcode == Opcode.IGET_OBJECT &&
                    field?.definingClass == type &&
                    field.name == "\$path" &&
                    read?.registerA == path &&
                    read.registerB == self &&
                    code[at + 1].opcode == Opcode.CHECK_CAST &&
                    (code[at + 1] as OneRegisterInstruction).registerA == path &&
                    (code[at + 1] as ReferenceInstruction).reference.toString() ==
                        "Ljava/lang/String;" &&
                    code[at + 2].opcode == Opcode.INVOKE_STATIC &&
                    target?.definingClass in previous &&
                    target?.name == "reserved" &&
                    target?.parameterTypes == listOf("Ljava/lang/String;") &&
                    target.returnType == "Z" &&
                    (code[at + 2] as FiveRegisterInstruction).registerCount == 1 &&
                    (code[at + 2] as FiveRegisterInstruction).registerC == path
            }
        check(existing.size <= 1) { "Existing native download dispatch is not unique" }
        val insertAt = existing.singleOrNull() ?: index
        method.addInstructionsWithLabels(
            insertAt,
            """
            iget-object v$path, v$self, $type->${'$'}path:Ljava/lang/Object;
            check-cast v$path, Ljava/lang/String;
            invoke-static {v$path}, $reservedOwner->reserved(Ljava/lang/String;)Z
            move-result v$app
            if-eqz v$app, :$label
            iget-object v$app, v$self, $type->${'$'}app:Ljava/lang/Object;
            check-cast v$app, Ljava/io/OutputStream;
            iget-object v$extra, v$self, $type->${'$'}payload:Ljava/lang/Object;
            check-cast v$extra, Lkotlin/jvm/functions/Function2;
            invoke-interface {v$scope}, Lkotlinx/coroutines/CoroutineScope;->getCoroutineContext()Lkotlin/coroutines/CoroutineContext;
            move-result-object v$context
            invoke-static {v$path, v$app, v$extra, v$context}, $fetchOwner->fetch(Ljava/lang/String;Ljava/io/OutputStream;Lkotlin/jvm/functions/Function2;Lkotlin/coroutines/CoroutineContext;)Ljava/lang/Long;
            move-result-object v$path
            return-object v$path
        """,
            ExternalLabel(label, code[insertAt]),
        )
    }
}

/**
 * The merged coroutine that implements the host's direct download: the branch that builds a request
 * for the captured `$path` and later reports "fetched ". R8 folds it into an unrelated class, so it
 * is found by that request, its string and the captured values.
 */
internal fun BytecodePatchContext.directDownloadHost(): DirectDownloadHost {
    val found = mutableListOf<Triple<ClassDef, Method, Int>>()
    classDefForEach { clazz ->
        for (method in clazz.methods) {
            if (
                method.returnType != "Ljava/lang/Object;" ||
                    method.parameterTypes != listOf("Ljava/lang/Object;")
            )
                continue
            val code = method.implementation?.instructions?.toList() ?: continue
            for (at in code.indices) {
                if (
                    code[at].opcode == Opcode.NEW_INSTANCE &&
                        (code[at] as ReferenceInstruction).reference.toString() ==
                            "Lokhttp3/Request\$Builder;" &&
                        ((code.getOrNull(at + 2) as? ReferenceInstruction)?.reference
                                as? FieldReference)
                            ?.let { it.definingClass == clazz.type && it.name == "\$path" } ==
                            true &&
                        code.subList(at, minOf(at + 140, code.size)).any {
                            it.string() == "fetched "
                        }
                )
                    found += Triple(clazz, method, at)
            }
        }
    }
    check(found.size == 1) { "Native download insertion point is not unique (${found.size})" }
    val (clazz, original, index) = found.single()
    val method =
        mutableClassDefBy(clazz.type).methods.single {
            it.name == original.name && it.parameterTypes == original.parameterTypes
        }
    val code = method.implementation!!.instructions.toList()
    val type = clazz.type
    val first = code[index] as OneRegisterInstruction
    val pathRead = code[index + 2] as TwoRegisterInstruction
    val self = pathRead.registerB
    // The request and its path read overwrite these two registers, so the handler may use them
    // before falling through; the branch above them has already read the IO scope.
    val scopeRead =
        (maxOf(0, index - 40) until index - 1).filter { at ->
            val field = (code[at] as? ReferenceInstruction)?.reference as? FieldReference
            code[at].opcode == Opcode.IGET_OBJECT &&
                field?.definingClass == type &&
                field.name == "L\$0" &&
                (code[at] as TwoRegisterInstruction).registerB == self &&
                code[at + 1].opcode == Opcode.CHECK_CAST &&
                (code[at + 1] as OneRegisterInstruction).registerA ==
                    (code[at] as TwoRegisterInstruction).registerA &&
                (code[at + 1] as ReferenceInstruction).reference.toString() ==
                    "Lkotlinx/coroutines/CoroutineScope;"
        }
    check(scopeRead.isNotEmpty()) { "Direct download scope or receiver changed" }
    val scope = (code[scopeRead.last()] as TwoRegisterInstruction).registerA
    for ((name, castType) in
        listOf(
            "\$path" to "Ljava/lang/String;",
            "\$app" to "Ljava/io/OutputStream;",
            "\$payload" to "Lkotlin/jvm/functions/Function2;",
        )) {
        check(
            (index until minOf(index + 60, code.size - 1)).any { at ->
                val field = (code[at] as? ReferenceInstruction)?.reference as? FieldReference
                code[at].opcode == Opcode.IGET_OBJECT &&
                    field?.definingClass == type &&
                    field.name == name &&
                    (code[at] as TwoRegisterInstruction).registerB == self &&
                    code[at + 1].opcode == Opcode.CHECK_CAST &&
                    (code[at + 1] as ReferenceInstruction).reference.toString() == castType
            }
        ) {
            "Direct download capture changed: $name"
        }
    }
    check(
        classDefBy("Lkotlinx/coroutines/JobKt;").methods.any {
            it.name == "ensureActive" &&
                it.parameterTypes == listOf("Lkotlin/coroutines/CoroutineContext;")
        }
    ) {
        "Download cancellation helper changed"
    }
    val registers = method.implementation!!.registerCount
    val taken = setOf(self, scope, first.registerA, pathRead.registerA)
    // Only the request builder and path registers survive on the fall-through, and the
    // handler returns on its own path, so any other low register is free to use there.
    val extra = (0 until minOf(registers, 16)).filter { it !in taken }.take(2)
    check(
        first.registerA != pathRead.registerA &&
            maxOf(self, scope, first.registerA, pathRead.registerA) < 16 &&
            extra.size == 2
    ) {
        "Download registers changed"
    }
    return DirectDownloadHost(
        method,
        code,
        type,
        index,
        scope,
        self,
        listOf(first.registerA, pathRead.registerA) + extra,
    )
}

/** Registers the method keeps for locals, i.e. the ones a prologue may write without saving. */
internal fun Method.localRegisters(): Int =
    implementation!!.registerCount -
        parameterTypes.sumOf { if (it == "J" || it == "D") 2 else 1 as Int } -
        if (accessFlags and AccessFlags.STATIC.value != 0) 0 else 1

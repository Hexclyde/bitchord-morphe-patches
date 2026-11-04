package app.bitchord.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.*
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.*
import com.android.tools.smali.dexlib2.immutable.*
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

internal fun BytecodePatchContext.addTelegramCatalogueCalls() {
    val client = "Lcom/music/bitchord/data/sources/addon/AddonClient;"
    val continuation = "Lkotlin/coroutines/jvm/internal/ContinuationImpl;"
    val functions = listOf("Lkotlin/jvm/functions/Function0;", "Lkotlin/jvm/functions/Function1;")
    val result = "Ljava/lang/Object;"
    fun Instruction.method() = (this as? ReferenceInstruction)?.reference as? MethodReference
    fun Instruction.field() = (this as? ReferenceInstruction)?.reference as? FieldReference
    val owner = classDefBy(client)
    data class Call(
        val method: MethodReference,
        val index: Int,
        val target: MethodReference,
        val field: FieldReference,
    )
    fun lookup(parameters: List<String>): Call {
        val matches =
            owner.methods.filter {
                it.parameterTypes == parameters &&
                    it.returnType == result &&
                    it.implementation != null
            }
        check(matches.size == 1) { "The addon catalogue suspend entry is not unique" }
        val method = matches.single()
        val code = method.implementation!!.instructions.toList()
        val sites =
            code.indices.filter { at ->
                code[at].method()?.let {
                    it.parameterTypes ==
                        listOf("Ljava/lang/String;", *functions.toTypedArray(), continuation) &&
                        it.returnType == result
                } == true
            }
        check(sites.size == 1) { "The addon shared catalogue call is not unique" }
        val index = sites.single()
        val call = code[index] as? FiveRegisterInstruction
        val field = code.getOrNull(index - 1)?.field()
        check(
            code[index].opcode == Opcode.INVOKE_VIRTUAL &&
                call?.registerCount == 5 &&
                code[index - 1].opcode == Opcode.IGET_OBJECT &&
                field?.definingClass == client &&
                field.type == code[index].method()!!.definingClass &&
                (code[index - 1] as TwoRegisterInstruction).registerA == call.registerC
        ) {
            "The addon catalogue cache receiver changed"
        }
        return Call(method, index, code[index].method()!!, field)
    }
    val search = lookup(listOf("Ljava/lang/String;", "Ljava/lang/String;", continuation))
    val stream = lookup(listOf("Ljava/lang/String;", "Ljava/lang/String;", "Z", continuation))
    check(search.target == stream.target && search.field != stream.field) {
        "Addon search and stream no longer use separate shared calls"
    }
    val shared = classDefBy(search.target.definingClass)
    val nativeMatches =
        shared.methods.filter {
            it.name == search.target.name &&
                it.parameterTypes == search.target.parameterTypes &&
                it.returnType == result
        }
    check(nativeMatches.size == 1 && nativeMatches.single().implementation != null) {
        "The native shared catalogue implementation changed"
    }
    val native = nativeMatches.single()
    val nativeCode = native.implementation!!.instructions.toList()
    check(
        shared.fields.count { it.type == "Ljava/util/concurrent/ConcurrentHashMap;" } == 1 &&
            nativeCode.any {
                it.method()?.let { call ->
                    call.name == "get" &&
                        call.definingClass == "Ljava/util/concurrent/ConcurrentHashMap;"
                } == true
            } &&
            nativeCode.any {
                it.method()?.let { call ->
                    call.name == "put" &&
                        call.definingClass == "Ljava/util/concurrent/ConcurrentHashMap;"
                } == true
            } &&
            nativeCode.any {
                it.method()?.let { call ->
                    call.name == "awaitInternal" &&
                        call.parameterTypes == listOf("Lkotlin/coroutines/Continuation;")
                } == true
            } &&
            nativeCode.any {
                it.field()?.let { field ->
                    field.definingClass == "Lkotlin/coroutines/intrinsics/CoroutineSingletons;" &&
                        field.name == "COROUTINE_SUSPENDED"
                } == true
            }
    ) {
        "The native shared-call cache or suspended result changed"
    }
    val base = classDefBy(continuation)
    check(
        base.superclass == "Lkotlin/coroutines/jvm/internal/BaseContinuationImpl;" &&
            base.methods.any {
                it.name == "<init>" &&
                    it.parameterTypes == listOf("Lkotlin/coroutines/Continuation;") &&
                    it.accessFlags and AccessFlags.PUBLIC.value != 0
            } &&
            classDefBy(base.superclass!!).methods.any {
                it.name == "invokeSuspend" &&
                    it.parameterTypes == listOf(result) &&
                    it.returnType == result &&
                    it.accessFlags and AccessFlags.ABSTRACT.value != 0
            }
    ) {
        "The native catalogue continuation ABI changed"
    }
    val valueClass = classDefBy("Lkotlin/Result;")
    val failureClass = classDefBy("Lkotlin/Result${'$'}Failure;")
    check(
        valueClass.methods.any { it.name == "<init>" && it.parameterTypes == listOf(result) } &&
            valueClass.fields.any { it.name == "value" && it.type == result } &&
            failureClass.methods.any {
                it.name == "<init>" &&
                    it.parameterTypes == listOf("Ljava/lang/Throwable;") &&
                    it.accessFlags and AccessFlags.PUBLIC.value != 0
            }
    ) {
        "The native boxed catalogue-result ABI changed"
    }
    var resumed = 0
    classDefForEach { type ->
        if (type.superclass == continuation)
            for (method in type.methods) {
                val code = method.implementation?.instructions?.toList() ?: continue
                if (method.name == "invokeSuspend" && code.any { it.method() == search.target }) {
                    check(
                        code.any {
                            it.opcode == Opcode.NEW_INSTANCE &&
                                ((it as? ReferenceInstruction)?.reference as? TypeReference)
                                    ?.type == valueClass.type
                        } &&
                            code.any {
                                it.method()?.let { target ->
                                    target.definingClass == valueClass.type &&
                                        target.name == "<init>" &&
                                        target.parameterTypes == listOf(result)
                                } == true
                            }
                    ) {
                        "The native suspended catalogue result is no longer boxed"
                    }
                    resumed++
                }
            }
    }
    check(resumed == 1) { "The native shared-call continuation is not unique" }
    fun add(name: String, parameters: List<String>, text: String) {
        val type = mutableClassDefBy(shared.type)
        check(type.methods.none { it.name == name }) {
            "Catalogue cache bridge already exists: $name"
        }
        val method =
            MutableMethod(
                ImmutableMethod(
                    shared.type,
                    name,
                    parameters.map { ImmutableMethodParameter(it, null, null) },
                    result,
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                    null,
                    null,
                    ImmutableMethodImplementation(
                        6,
                        emptyList<Instruction>(),
                        emptyList(),
                        emptyList(),
                    ),
                )
            )
        method.addInstructions(0, text)
        type.methods.add(method)
    }
    add(
        "bitchordTelegramCatalogueGet",
        listOf("Ljava/lang/String;", result, result, result),
        """
        check-cast p2, ${functions[0]}
        check-cast p3, ${functions[1]}
        check-cast p4, $continuation
        invoke-virtual/range {p0 .. p4}, ${search.target}
        move-result-object v0
        return-object v0
    """,
    )
    val wrapper = "bitchordTelegramCatalogueCall"
    add(
        wrapper,
        native.parameterTypes.map { it.toString() },
        """
        invoke-static/range {p0 .. p4}, Lapp/bitchord/telegram/TelegramCatalogueCalls;->get(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
        move-result-object v0
        return-object v0
    """,
    )
    for (site in listOf(search, stream)) {
        val method =
            mutableClassDefBy(client).methods.single {
                it.name == site.method.name &&
                    it.parameterTypes == site.method.parameterTypes &&
                    it.returnType == result
            }
        val call =
            method.implementation!!.instructions.toList()[site.index] as FiveRegisterInstruction
        method.replaceInstruction(
            site.index,
            BuilderInstruction35c(
                Opcode.INVOKE_VIRTUAL,
                call.registerCount,
                call.registerC,
                call.registerD,
                call.registerE,
                call.registerF,
                call.registerG,
                ImmutableMethodReference(shared.type, wrapper, native.parameterTypes, result),
            ),
        )
    }
    val constructor =
        mutableClassDefBy(client).methods.single {
            it.name == "<init>" && it.parameterTypes.firstOrNull() == "Ljava/lang/String;"
        }
    val code = constructor.implementation!!.instructions.toList()
    // The class has several String fields; the base URL is the one derived from the first argument.
    val rawUrl = constructor.implementation!!.registerCount - constructor.parameterTypes.size
    val derived =
        code.indices.filter { at ->
            val call = code[at].method()
            val args = code[at] as? FiveRegisterInstruction
            code[at].opcode == Opcode.INVOKE_STATIC &&
                call?.returnType == "Ljava/lang/String;" &&
                call.parameterTypes == listOf("Ljava/lang/String;") &&
                args?.registerC == rawUrl
        }
    check(derived.size == 1) { "The addon base URL derivation is not unique" }
    val stored = code.getOrNull(derived.single() + 2)
    val url =
        owner.fields.single { it.name == stored?.field()?.name && it.type == "Ljava/lang/String;" }
    check(
        code[derived.single() + 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
            stored?.opcode == Opcode.IPUT_OBJECT &&
            (stored as TwoRegisterInstruction).registerA ==
                (code[derived.single() + 1] as OneRegisterInstruction).registerA
    ) {
        "The addon base URL store changed"
    }
    val end = code.indices.single { code[it].opcode == Opcode.RETURN_VOID }
    check(
        constructor.implementation!!.registerCount >= 5 &&
            listOf(url, search.field, stream.field).all { field ->
                code.take(end).any { it.opcode == Opcode.IPUT_OBJECT && it.field() == field }
            }
    ) {
        "The addon catalogue cache initialization changed"
    }
    constructor.replaceInstruction(end, "iget-object v0, p0, $url")
    constructor.addInstructions(
        end + 1,
        """
        iget-object v1, p0, ${search.field}
        iget-object v2, p0, ${stream.field}
        invoke-static {v0, v1, v2}, Lapp/bitchord/telegram/TelegramCatalogueCalls;->register(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V
        return-void
    """,
    )
}

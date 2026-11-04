package app.bitchord.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.*
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.*
import com.android.tools.smali.dexlib2.immutable.*

private const val DIAGNOSTICS = "Lapp/bitchord/telegram/Diagnostics;"
private const val WEB_VIEW = "Landroid/webkit/WebView;"
private const val WEB_CLIENT = "Landroid/webkit/WebViewClient;"
private const val STRING = "Ljava/lang/String;"

internal fun BytecodePatchContext.addDiagnosticsHooks(startup: MutableMethod) {
    val allClasses = mutableListOf<ClassDef>()
    classDefForEach { allClasses.add(it) }
    fun Instruction.call() = (this as? ReferenceInstruction)?.reference as? MethodReference
    fun Instruction.string() =
        ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
    fun Method.code() = implementation?.instructions?.toList().orEmpty()
    fun registers(instruction: Instruction): List<Int> =
        when (instruction) {
            is RegisterRangeInstruction ->
                (instruction.startRegister until
                        instruction.startRegister + instruction.registerCount)
                    .toList()
            is FiveRegisterInstruction ->
                listOf(
                        instruction.registerC,
                        instruction.registerD,
                        instruction.registerE,
                        instruction.registerF,
                        instruction.registerG,
                    )
                    .take(instruction.registerCount)
            else -> error("Diagnostic boundary invocation encoding changed")
        }
    fun invoke(name: String, parameters: String, registers: List<Int>): String {
        check(registers.isNotEmpty()) { "Diagnostic boundary has no receiver" }
        return if (registers.zipWithNext().all { (a, b) -> b == a + 1 }) {
            "invoke-static/range {v${registers.first()} .. v${registers.last()}}, " +
                "$DIAGNOSTICS->$name($parameters)V"
        } else {
            check(registers.all { it <= 15 }) { "Diagnostic boundary registers changed" }
            "invoke-static {${registers.joinToString(", ") { "v$it" }}}, " +
                "$DIAGNOSTICS->$name($parameters)V"
        }
    }
    fun mutable(method: Method) =
        mutableClassDefBy(method.definingClass).methods.single {
            it.name == method.name &&
                it.parameterTypes == method.parameterTypes &&
                it.returnType == method.returnType
        }

    check(
        startup.name == "onCreate" &&
            startup.parameterTypes.isEmpty() &&
            startup.returnType == "V" &&
            startup.accessFlags and AccessFlags.STATIC.value == 0 &&
            classDefBy(startup.definingClass).superclass == "Landroid/app/Application;" &&
            startup.code().any {
                it.opcode == Opcode.INVOKE_SUPER &&
                    it.call()?.let { call ->
                        call.definingClass == "Landroid/app/Application;" &&
                            call.name == "onCreate" &&
                            call.parameterTypes.isEmpty()
                    } == true
            }
    ) {
        "Application startup diagnostic boundary changed"
    }
    startup.addInstructions(
        0,
        "invoke-static/range {p0 .. p0}, $DIAGNOSTICS->init(Landroid/content/Context;)V",
    )

    val logging =
        allClasses
            .flatMap { it.methods }
            .filter { method ->
                method.accessFlags and AccessFlags.STATIC.value != 0 &&
                    method.returnType == "V" &&
                    method.parameterTypes == listOf("C", STRING, STRING) &&
                    method.code().any { it.opcode == Opcode.MONITOR_ENTER } &&
                    method.code().any {
                        it.call()?.let { call ->
                            call.definingClass == "Ljava/lang/System;" &&
                                call.name == "currentTimeMillis" &&
                                call.returnType == "J"
                        } == true
                    } &&
                    method.code().any {
                        it.call()?.let { call ->
                            call.name == "addLast" &&
                                call.parameterTypes == listOf("Ljava/lang/Object;") &&
                                call.returnType == "V"
                        } == true
                    }
            }
    check(logging.size == 1) { "Native diagnostic log boundary is not unique (${logging.size})" }
    mutable(logging.single())
        .addInstructions(0, "invoke-static/range {p0 .. p1}, $DIAGNOSTICS->nativeLine(C$STRING)V")

    val login =
        "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube" +
            "&prompt=select_account&continue=https%3A%2F%2Fmusic.youtube.com%2F"
    val factories =
        getAllClassesWithStrings()
            .flatMap { it.methods }
            .filter { method -> method.code().any { it.string() == login } }
    check(factories.size == 1) {
        "Native sign-in diagnostic factory is not unique (${factories.size})"
    }
    val factory = factories.single()
    val body = factory.code()
    val url = body.indices.single { body[it].string() == login }
    val loads =
        (url until minOf(url + 10, body.size)).filter { at ->
            body[at].call()?.let { call ->
                call.returnType == "V" &&
                    ((call.definingClass == WEB_VIEW &&
                        call.name == "loadUrl" &&
                        call.parameterTypes == listOf(STRING)) ||
                        (call.definingClass == "Lapp/bitchord/telegram/MicroGSignIn;" &&
                            call.name == "load" &&
                            call.parameterTypes == listOf(WEB_VIEW, STRING)))
            } == true
        }
    check(loads.size == 1) { "Native sign-in URL diagnostic boundary changed" }
    val load = loads.single()
    val loadRegisters = registers(body[load])
    check(loadRegisters.size == 2) { "Native sign-in load arguments changed" }
    val setters =
        (maxOf(0, url - 12) until url).filter { at ->
            body[at].call()?.let { call ->
                call.definingClass == WEB_VIEW &&
                    call.name == "setWebViewClient" &&
                    call.parameterTypes == listOf(WEB_CLIENT)
            } == true
        }
    check(setters.size == 1) { "Native sign-in client diagnostic boundary changed" }
    val setter = setters.single()
    val clientRegisters = registers(body[setter])
    check(clientRegisters.size == 2 && clientRegisters[0] == loadRegisters[0]) {
        "Native sign-in client and URL use different WebViews"
    }
    val clients =
        (maxOf(0, setter - 6) until setter).mapNotNull { at ->
            val instruction = body[at]
            if (
                instruction.opcode == Opcode.NEW_INSTANCE &&
                    (instruction as OneRegisterInstruction).registerA == clientRegisters[1]
            )
                ((instruction as ReferenceInstruction).reference as? TypeReference)?.type
            else null
        }
    check(clients.size == 1) { "Native sign-in client allocation changed" }
    val client = mutableClassDefBy(clients.single())
    check(client.superclass == WEB_CLIENT) { "Native sign-in client superclass changed" }
    val finished =
        client.methods.singleOrNull {
            it.name == "onPageFinished" &&
                it.parameterTypes == listOf(WEB_VIEW, STRING) &&
                it.returnType == "V"
        }
    check(
        finished != null &&
            finished.code().any { it.call()?.name == "startsWith" } &&
            finished.code().any {
                it.call()?.let { call ->
                    call.definingClass == "Lkotlin/jvm/functions/Function1;" &&
                        call.name == "invoke"
                } == true
            }
    ) {
        "Native sign-in completion callback changed"
    }
    val constructors =
        (0 until setter).filter { at ->
            body[at].call()?.let { call ->
                call.definingClass == WEB_VIEW &&
                    call.name == "<init>" &&
                    call.parameterTypes == listOf("Landroid/content/Context;")
            } == true && registers(body[at]).first() == loadRegisters[0]
        }
    check(constructors.size == 1) { "Native sign-in WebView construction changed" }
    val mutableFactory = mutable(factory)
    for ((at, text) in
        listOf(
                load to invoke("requested", WEB_VIEW + STRING, loadRegisters),
                constructors.single() + 1 to invoke("watch", WEB_VIEW, listOf(loadRegisters[0])),
            )
            .sortedByDescending { it.first }) mutableFactory.addInstructions(at, text)

    fun callback(name: String, parameters: List<String>, observer: String, returns: String = "V") {
        val matches = client.methods.filter { it.name == name && it.parameterTypes == parameters }
        check(matches.size <= 1) { "Native sign-in callback is not unique: $name" }
        val existing = matches.singleOrNull()
        check(
            existing == null ||
                existing.returnType == returns &&
                    existing.accessFlags and AccessFlags.STATIC.value == 0 &&
                    existing.implementation != null
        ) {
            "Native sign-in callback signature changed: $name"
        }
        val words = parameters.sumOf { if (it == "J" || it == "D") 2 else 1 }
        val method =
            existing
                ?: MutableMethod(
                    ImmutableMethod(
                        client.type,
                        name,
                        parameters.map { ImmutableMethodParameter(it, null, null) },
                        returns,
                        AccessFlags.PUBLIC.value,
                        null,
                        null,
                        ImmutableMethodImplementation(
                            words + 1 + if (returns == "V") 0 else 1,
                            emptyList<Instruction>(),
                            emptyList(),
                            emptyList(),
                        ),
                    )
                )
        val observe =
            "invoke-static/range {p1 .. p$words}, $DIAGNOSTICS->$observer(${parameters.joinToString("")})V"
        if (existing != null) method.addInstructions(0, observe)
        else {
            method.addInstructions(
                0,
                observe +
                    "\n" +
                    "invoke-super/range {p0 .. p$words}, $WEB_CLIENT->$name(${parameters.joinToString("")})$returns\n" +
                    if (returns == "V") "return-void" else "move-result v0\nreturn v0",
            )
            client.methods.add(method)
        }
    }
    callback("onPageStarted", listOf(WEB_VIEW, STRING, "Landroid/graphics/Bitmap;"), "pageStarted")
    callback("onPageFinished", listOf(WEB_VIEW, STRING), "pageFinished")
    callback("doUpdateVisitedHistory", listOf(WEB_VIEW, STRING, "Z"), "visited")
    callback(
        "onReceivedError",
        listOf(
            WEB_VIEW,
            "Landroid/webkit/WebResourceRequest;",
            "Landroid/webkit/WebResourceError;",
        ),
        "webError",
    )
    callback("onReceivedError", listOf(WEB_VIEW, "I", STRING, STRING), "legacyWebError")
    callback(
        "onReceivedHttpError",
        listOf(
            WEB_VIEW,
            "Landroid/webkit/WebResourceRequest;",
            "Landroid/webkit/WebResourceResponse;",
        ),
        "httpError",
    )
    callback(
        "onReceivedSslError",
        listOf(WEB_VIEW, "Landroid/webkit/SslErrorHandler;", "Landroid/net/http/SslError;"),
        "sslError",
    )
    callback(
        "onRenderProcessGone",
        listOf(WEB_VIEW, "Landroid/webkit/RenderProcessGoneDetail;"),
        "rendererGone",
        "Z",
    )

    var microGLoads = 0
    for (type in allClasses.filter { it.type.startsWith("Lapp/bitchord/telegram/MicroGSignIn") }) {
        for (method in type.methods) {
            val originalCode = method.code()
            val sites =
                originalCode.indices.filter { at ->
                    originalCode[at].call()?.let { call ->
                        call.definingClass == WEB_VIEW &&
                            call.name == "loadUrl" &&
                            call.parameterTypes == listOf(STRING)
                    } == true
                }
            if (sites.isEmpty()) continue
            val target = mutable(method)
            val instructions = target.code()
            for (at in sites.reversed()) {
                val args = registers(instructions[at])
                check(args.size == 2) { "MicroG sign-in load diagnostic boundary changed" }
                target.addInstructions(at, invoke("requested", WEB_VIEW + STRING, args))
                microGLoads++
            }
        }
    }
    println(
        "Added startup, native log and sign-in diagnostics ($microGLoads MicroG load boundaries)"
    )
}

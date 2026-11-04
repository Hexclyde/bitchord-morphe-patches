package app.bitchord.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.*
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.*
import com.android.tools.smali.dexlib2.immutable.*

private const val LOGIN_NAVIGATION = "Lapp/bitchord/telegram/LoginNavigation;"
private const val SIGN_IN_VIEW = "Landroid/webkit/WebView;"
private const val SIGN_IN_CLIENT = "Landroid/webkit/WebViewClient;"
private const val SIGN_IN_REQUEST = "Landroid/webkit/WebResourceRequest;"
private const val SIGN_IN_TEXT = "Ljava/lang/String;"
private const val SIGN_IN_PAGE =
    "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube" +
        "&prompt=select_account&continue=https%3A%2F%2Fmusic.youtube.com%2F"
private const val SIGN_IN_MUSIC = "https://music.youtube.com/"

/**
 * Keeps the YouTube Music sign-in WebView on the web when a page links to another app.
 *
 * The sign-in factory is the only method holding Google's music sign-in address. It builds one
 * WebView, gives it a WebViewClient that R8 merged with the PoToken WebView's, and loads the
 * address with WebView.loadUrl, or MicroGSignIn.load once Sign in with MicroG has replaced that
 * call. Right after the load the WebView is registered with LoginNavigation, and the client gains
 * shouldOverrideUrlLoading, which asks LoginNavigation first and otherwise returns WebViewClient's
 * own answer. LoginNavigation only acts on registered WebViews, so the PoToken WebView behaves as
 * before.
 */
internal fun BytecodePatchContext.addLoginNavigationHooks() {
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
            else -> error("Sign-in navigation call encoding changed")
        }

    val factories =
        getAllClassesWithStrings()
            .flatMap { it.methods }
            .filter { method -> method.code().any { it.string() == SIGN_IN_PAGE } }
    check(factories.size == 1) { "Sign-in navigation factory is not unique (${factories.size})" }
    val found = factories.single()
    val factory =
        mutableClassDefBy(found.definingClass).methods.single {
            it.name == found.name &&
                it.parameterTypes == found.parameterTypes &&
                it.returnType == found.returnType
        }
    // Read the factory as it is now: other patches may already have added calls to it.
    val body = factory.code()
    if (body.any { it.call()?.definingClass == LOGIN_NAVIGATION }) {
        println("Sign-in navigation hooks already applied")
        return
    }
    val url = body.indices.single { body[it].string() == SIGN_IN_PAGE }
    check(body.any { it.string() == SIGN_IN_MUSIC }) { "Sign-in channel page changed" }
    val loads =
        (url until minOf(url + 12, body.size)).filter { at ->
            body[at].call()?.let { call ->
                call.returnType == "V" &&
                    ((call.definingClass == SIGN_IN_VIEW &&
                        call.name == "loadUrl" &&
                        call.parameterTypes == listOf(SIGN_IN_TEXT)) ||
                        (call.definingClass == "Lapp/bitchord/telegram/MicroGSignIn;" &&
                            call.name == "load" &&
                            call.parameterTypes == listOf(SIGN_IN_VIEW, SIGN_IN_TEXT)))
            } == true
        }
    check(loads.size == 1) { "Sign-in page load changed (${loads.size})" }
    val load = loads.single()
    val loadRegisters = registers(body[load])
    check(loadRegisters.size == 2) { "Sign-in page load arguments changed" }
    val view = loadRegisters[0]
    // The registration runs after the load, so nothing may jump past the load to the next step.
    check(load + 1 < body.size && body[load + 1].opcode == Opcode.RETURN_OBJECT) {
        "Sign-in factory no longer returns its WebView after loading"
    }
    check((body[load + 1] as OneRegisterInstruction).registerA == view) {
        "Sign-in factory returns a different view"
    }
    val constructors =
        (0 until load).filter { at ->
            body[at].call()?.let { call ->
                call.definingClass == SIGN_IN_VIEW &&
                    call.name == "<init>" &&
                    call.parameterTypes == listOf("Landroid/content/Context;")
            } == true && registers(body[at]).first() == view
        }
    check(constructors.size == 1) { "Sign-in WebView construction changed" }
    val setters =
        (constructors.single() until load).filter { at ->
            body[at].call()?.let { call ->
                call.definingClass == SIGN_IN_VIEW &&
                    call.name == "setWebViewClient" &&
                    call.parameterTypes == listOf(SIGN_IN_CLIENT)
            } == true
        }
    check(setters.size == 1) { "Sign-in WebViewClient assignment changed" }
    val setter = setters.single()
    val clientRegisters = registers(body[setter])
    check(clientRegisters.size == 2 && clientRegisters[0] == view) {
        "Sign-in client and page load use different WebViews"
    }
    val clients =
        (constructors.single() until setter).mapNotNull { at ->
            val instruction = body[at]
            if (
                instruction.opcode == Opcode.NEW_INSTANCE &&
                    (instruction as OneRegisterInstruction).registerA == clientRegisters[1]
            )
                ((instruction as ReferenceInstruction).reference as? TypeReference)?.type
            else null
        }
    check(clients.size == 1) { "Sign-in client allocation changed" }
    val client = mutableClassDefBy(clients.single())
    check(client.superclass == SIGN_IN_CLIENT) { "Sign-in client superclass changed" }
    val finished =
        client.methods.singleOrNull {
            it.name == "onPageFinished" &&
                it.parameterTypes == listOf(SIGN_IN_VIEW, SIGN_IN_TEXT) &&
                it.returnType == "V"
        }
    check(
        finished != null &&
            finished.code().any { it.string() == "https://music.youtube.com" } &&
            finished.code().any { it.call()?.name == "startsWith" } &&
            finished.code().any {
                it.call()?.let { call ->
                    call.definingClass == "Lkotlin/jvm/functions/Function1;" &&
                        call.name == "invoke"
                } == true
            }
    ) {
        "Sign-in completion callback changed"
    }

    factory.addInstructions(
        load + 1,
        "invoke-static {v${loadRegisters[0]}, v${loadRegisters[1]}}, " +
            "$LOGIN_NAVIGATION->track($SIGN_IN_VIEW$SIGN_IN_TEXT)V",
    )

    val parameters = listOf(SIGN_IN_VIEW, SIGN_IN_REQUEST)
    val signature = "shouldOverrideUrlLoading(${parameters.joinToString("")})Z"
    val ask = "invoke-static {p1, p2}, $LOGIN_NAVIGATION->override(${parameters.joinToString("")})Z"
    val matches =
        client.methods.filter {
            it.name == "shouldOverrideUrlLoading" && it.parameterTypes == parameters
        }
    check(matches.size <= 1) { "Sign-in navigation callback is not unique" }
    val existing = matches.singleOrNull()
    if (existing == null) {
        val method =
            MutableMethod(
                ImmutableMethod(
                    client.type,
                    "shouldOverrideUrlLoading",
                    parameters.map { ImmutableMethodParameter(it, null, null) },
                    "Z",
                    AccessFlags.PUBLIC.value,
                    null,
                    null,
                    ImmutableMethodImplementation(
                        4,
                        emptyList<Instruction>(),
                        emptyList(),
                        emptyList(),
                    ),
                )
            )
        method.addInstructions(
            0,
            """
            $ask
            move-result v0
            if-eqz v0, :web_view_client
            return v0
            :web_view_client
            invoke-super {p0, p1, p2}, $SIGN_IN_CLIENT->$signature
            move-result v0
            return v0
            """,
        )
        client.methods.add(method)
    } else {
        val implementation = existing.implementation
        check(
            existing.returnType == "Z" &&
                existing.accessFlags and AccessFlags.STATIC.value == 0 &&
                implementation != null &&
                implementation.instructions.any()
        ) {
            "Sign-in navigation callback signature changed"
        }
        // p0..p2 are the last three registers; the first is free until the original code runs.
        check(implementation.registerCount > 3) {
            "Sign-in navigation callback has no free register"
        }
        existing.addInstructionsWithLabels(
            0,
            """
            $ask
            move-result v0
            if-eqz v0, :login_navigation_original
            return v0
            """,
            ExternalLabel(
                "login_navigation_original",
                existing.implementation!!.instructions.first(),
            ),
        )
    }
    println("Kept the YouTube Music sign-in WebView on the web when pages link to other apps")
}

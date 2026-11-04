package app.bitchord.patches

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.instruction.*
import com.android.tools.smali.dexlib2.immutable.reference.*

private const val MODIFIER_TYPE = "Landroidx/compose/ui/Modifier;"
private const val COMPOSER_TYPE = "Landroidx/compose/runtime/ComposerImpl;"
private const val LAMBDA = "Landroidx/compose/runtime/internal/ComposableLambdaImpl;"
private const val FN0 = "Lkotlin/jvm/functions/Function0;"
private const val FN1 = "Lkotlin/jvm/functions/Function1;"

private var passed = 0

private fun player(extra: String? = null) =
    ImmutableMethodReference(
        "Lcom/music/bitchord/ui/player/Anywhere;",
        "Anything",
        listOfNotNull(
            "Lcom/music/bitchord/data/model/Song;",
            "Z",
            "Lcoil3/request/ImageRequest;",
            FN1,
            "Z",
            FN0,
            FN0,
            LAMBDA,
            LAMBDA,
            MODIFIER_TYPE,
            COMPOSER_TYPE,
            "I",
            extra,
        ),
        "V",
    )

private fun show() = ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE, 1, 12, player())

private fun other() = ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE, 1, 13, player("Z"))

private fun lit(register: Int, value: Int = 0) =
    ImmutableInstruction11n(Opcode.CONST_4, register, value)

private fun ifz(register: Int) = ImmutableInstruction21t(Opcode.IF_EQZ, register, 0)

private fun jump() = ImmutableInstruction10t(Opcode.GOTO, 0)

private fun read(into: Int, from: Int) = ImmutableInstruction12x(Opcode.MOVE_OBJECT, into, from)

private fun end() = ImmutableInstruction10x(Opcode.RETURN_VOID)

/** Points the branch at [from] to [to], the offset worked out from the real code units. */
private fun List<Instruction>.link(vararg links: Triple<Int, Int, Int>): List<Instruction> {
    val code = toMutableList()
    for ((from, to, register) in links) {
        val units = { range: IntRange -> range.sumOf { code[it].codeUnits } }
        val offset = if (to > from) units(from until to) else -units(to until from)
        code[from] =
            if (code[from].opcode == Opcode.IF_EQZ)
                ImmutableInstruction21t(Opcode.IF_EQZ, register, offset)
            else ImmutableInstruction10t(Opcode.GOTO, offset)
    }
    return code
}

private const val LANDSCAPE = 3
private const val VIDEO = 4

private const val COMPOSER_REGISTER = 7

private fun cast(register: Int = COMPOSER_REGISTER) =
    ImmutableInstruction21c(Opcode.CHECK_CAST, register, ImmutableTypeReference(COMPOSER_TYPE))

private fun composed(register: Int = COMPOSER_REGISTER) =
    ImmutableInstruction35c(
        Opcode.INVOKE_VIRTUAL,
        1,
        register,
        0,
        0,
        0,
        0,
        ImmutableMethodReference(COMPOSER_TYPE, "startReplaceGroup", listOf("I"), "V"),
    )

/**
 * The composer cast, landscape and video tests ahead of the player, a landscape test again on the
 * other path. 0 cast, 1-2 the pair, 3 composer use, 4 lit, 5 player, 6 jump, 7 other path, 8
 * retest, 9-10 ends.
 */
private fun screen(
    retest: Int = LANDSCAPE,
    first: Instruction = lit(5),
    other: Instruction = lit(5),
    call: Instruction = show(),
    use: Instruction = composed(),
) =
    listOf(
            cast(),
            ifz(LANDSCAPE),
            ifz(VIDEO),
            use,
            first,
            call,
            jump(),
            other,
            ifz(retest),
            end(),
            end(),
        )
        .link(Triple(1, 7, LANDSCAPE), Triple(2, 7, VIDEO), Triple(6, 9, 0), Triple(8, 10, retest))

private fun fails(what: String, mark: String, block: () -> Unit) {
    val error = runCatching(block).exceptionOrNull()
    check(error is IllegalStateException && mark in error.message.orEmpty()) {
        "$what: expected a refusal mentioning \"$mark\", got $error"
    }
    passed++
}

private fun holds(what: String, condition: Boolean) {
    check(condition) { what }
    passed++
}

private val ACTIVITY_FIELD =
    ImmutableFieldReference("Lx/Owner;", "window", "Landroid/app/Activity;")
private val OTHER_FIELD = ImmutableFieldReference("Lx/Owner;", "saved", "Ljava/lang/Integer;")

private fun field(register: Int, ref: ImmutableFieldReference, owner: Int = 3) =
    ImmutableInstruction22c(Opcode.IGET_OBJECT, register, owner, ref)

private fun requestOf(vararg head: Instruction) = head.toList() + end()

fun fullscreenChecks() {
    val gate = fullscreenGate(screen(), 9)!!
    holds("the test goes ahead of the pair", gate.at == 1 && gate.landscape == LANDSCAPE)
    holds("the scratch register is one both paths overwrite", gate.free == 5)
    holds("the composer is the cast one", gate.composer == COMPOSER_REGISTER)
    holds(
        "a method without the player is left alone",
        fullscreenGate(screen(call = other()), 9) == null,
    )

    fails("two players in one method", "layout changed") {
        val code = screen().toMutableList()
        code[6] = show()
        fullscreenGate(code, 9)
    }
    holds(
        "a plain wrapper call is not the gate",
        fullscreenGate(listOf(lit(5), show(), end()), 9) == null,
    )
    fails("the other path never tests landscape again", "layout test changed") {
        fullscreenGate(screen(retest = VIDEO), 9)
    }
    fails("landscape replaced on the other path", "layout test changed") {
        fullscreenGate(screen(other = read(LANDSCAPE, 2)), 9)
    }
    fails("a jump lands on the first test", "branch target") {
        val code = screen().toMutableList()
        code[0] = jump()
        fullscreenGate(code.link(Triple(0, 1, 0)), 9)
    }
    fails("composer used by something other than the cast one", "composer changes") {
        fullscreenGate(screen(use = composed(2)), 9)
    }
    fails("composer not cast to its type", "composer changes") {
        val code = screen().toMutableList()
        code[0] = ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, COMPOSER_REGISTER, 2)
        fullscreenGate(code, 9)
    }
    fails("both paths read every register they could use", "free register") {
        fullscreenGate(screen(first = read(0, 5), other = read(1, 5)), 8)
    }

    val request = requestOf(field(0, OTHER_FIELD), field(1, ACTIVITY_FIELD), lit(2), lit(3))
    val site = orientationSite(request, 6, ACTIVITY_FIELD)
    holds(
        "the window load is found",
        site.at == 2 && site.activity == 1 && site.landscape == 5 && site.controller == 3,
    )
    fails("no window load", "no window load") {
        orientationSite(requestOf(lit(0)), 6, ACTIVITY_FIELD)
    }
    fails("the boolean is replaced first", "changed") {
        orientationSite(requestOf(lit(5), field(1, ACTIVITY_FIELD)), 6, ACTIVITY_FIELD)
    }

    fails("the window belongs to another receiver", "receiver") {
        orientationSite(requestOf(field(1, ACTIVITY_FIELD, 2)), 6, ACTIVITY_FIELD)
    }
    fails("the controller is overwritten", "receiver") {
        orientationSite(requestOf(lit(3), field(1, ACTIVITY_FIELD)), 6, ACTIVITY_FIELD)
    }

    val ask = ImmutableMethodReference("Lx/Owner;", "request", listOf("I", "Z"), "V")
    fun asks(orientation: Int, landscape: Int) =
        requestLiterals(
            listOf(
                lit(0, orientation),
                lit(1, landscape),
                ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3, 2, 0, 1, 0, 0, ask),
                end(),
            ),
            ask,
        )
    holds("a landscape request", asks(6, 1) == (6 to 1))
    holds("a portrait request", asks(1, 0) == (1 to 0))
    holds("no request", requestLiterals(listOf(end()), ask) == null)
    fun portrait(receiver: Int = 2, direction: Int = 1, prefix: List<Instruction> = emptyList()) =
        prefix +
            listOf(
                lit(0, direction),
                lit(1, 0),
                ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3, receiver, 0, 1, 0, 0, ask),
                end(),
            )
    holds("portrait calls its own controller", portraitRequestReceiver(portrait(), 3, ask))
    holds("portrait rejects another controller", !portraitRequestReceiver(portrait(3), 3, ask))
    holds("portrait rejects landscape", !portraitRequestReceiver(portrait(direction = 6), 3, ask))
    holds(
        "portrait rejects overwritten receiver",
        !portraitRequestReceiver(portrait(prefix = listOf(lit(2))), 3, ask),
    )

    fun frameTypes(chrome: Boolean = false) =
        listOf(
            MODIFIER_TYPE,
            if (chrome) "Z" else "J",
            "Lx/Frost;",
            "Z",
            FN0,
            LAMBDA,
            LAMBDA,
            COMPOSER_TYPE,
            "I",
        )
    fun frame(chrome: Boolean = false, overlay: Int = 8, compositor: Int = 9): List<Instruction> {
        val parameters = frameTypes(chrome)
        val count = parameters.sumOf { if (it == "J") 2 else 1 }
        val base = 26 - count
        val overlayInput = base + parameters.take(4).sumOf { if (it == "J") 2 else 1 }
        val composerInput = base + parameters.take(7).sumOf { if (it == "J") 2 else 1 }
        return listOf(
            ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, overlay, overlayInput),
            ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, compositor, composerInput),
            ImmutableInstruction21c(
                Opcode.CONST_STRING,
                0,
                ImmutableStringReference("overlayOpen"),
            ),
            ImmutableInstruction35c(
                Opcode.INVOKE_STATIC,
                2,
                overlay,
                0,
                0,
                0,
                0,
                ImmutableMethodReference(
                    "Lkotlin/jvm/internal/Intrinsics;",
                    "checkNotNullParameter",
                    listOf("Ljava/lang/Object;", "Ljava/lang/String;"),
                    "V",
                ),
            ),
            lit(0),
            ImmutableInstruction35c(
                Opcode.INVOKE_VIRTUAL,
                2,
                compositor,
                0,
                0,
                0,
                0,
                ImmutableMethodReference(
                    COMPOSER_TYPE,
                    "startRestartGroup",
                    listOf("I"),
                    COMPOSER_TYPE,
                ),
            ),
            ImmutableInstruction10x(Opcode.NOP),
            end(),
        )
    }
    val framed = fullscreenFrameSite(frame(), 26, frameTypes())
    holds(
        "frame binding is after native restart scope",
        framed.at == 6 && framed.overlay == 8 && framed.composer == 9,
    )
    holds(
        "chrome frame word parameters are supported",
        fullscreenFrameSite(frame(true), 26, frameTypes(true)).at == 6,
    )
    fails("missing native overlay parameter", "native overlay input") {
        fullscreenFrameSite(frame().filterIndexed { index, _ -> index != 2 }, 26, frameTypes())
    }
    fails("wrong native overlay input", "overlay parameter origin") {
        val code = frame().toMutableList()
        code[0] = ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, 8, 20)
        fullscreenFrameSite(code, 26, frameTypes())
    }
    fails("wrong native composer input", "composer origin") {
        val code = frame().toMutableList()
        code[1] = ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, 9, 25)
        fullscreenFrameSite(code, 26, frameTypes())
    }
    fails("overlay overwritten before binding", "binding registers") {
        val code = frame().toMutableList()
        code.add(4, lit(8))
        fullscreenFrameSite(code, 26, frameTypes())
    }
    fails("native scope is required", "restart scope") {
        fullscreenFrameSite(frame().filterIndexed { index, _ -> index != 5 }, 26, frameTypes())
    }
    fails("branches cannot bypass frame binding", "binding registers") {
        fullscreenFrameSite((listOf(jump()) + frame()).link(Triple(0, 7, 0)), 26, frameTypes())
    }
    fails("changed frame signature", "parameters") {
        fullscreenFrameSite(frame(), 26, frameTypes().dropLast(1))
    }

    println("$passed fullscreen checks passed")
}

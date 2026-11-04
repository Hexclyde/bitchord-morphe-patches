package app.bitchord.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.*
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val NOW_PLAYING = "Lcom/music/bitchord/ui/player/NowPlayingScreenKt;"
private const val LANDSCAPE = "Lcom/music/bitchord/ui/player/LandscapePlayerKt;"
private const val PLAYER_UI = "Lcom/music/bitchord/ui/player/"
private const val PLAYER_CONTROLS = "Lcom/music/bitchord/ui/player/PlayerControlsKt;"
private const val PLAYER_ICONS = "Lapp/bitchord/telegram/PlayerIcons;"
private const val COMPOSER = "Landroidx/compose/runtime/ComposerImpl;"
private const val COLUMN_SCOPE = "Landroidx/compose/foundation/layout/ColumnScopeInstance;"
private const val BOX_SCOPE = "Landroidx/compose/foundation/layout/BoxScopeInstance;"
private const val Z_INDEX = "Landroidx/compose/ui/ZIndexElement;"
private const val ALIGNMENT = "Landroidx/compose/ui/Alignment;"
private const val ALIGNMENT_COMPANION = "Landroidx/compose/ui/Alignment\$Companion;"
private const val MODIFIER = "Landroidx/compose/ui/Modifier;"
private const val MODIFIER_COMPANION = "Landroidx/compose/ui/Modifier\$Companion;"
private const val FUNCTION1 = "Lkotlin/jvm/functions/Function1;"
private const val LOCAL = "Landroidx/compose/runtime/ProvidableCompositionLocal;"
private const val COLOR = "Landroidx/compose/ui/graphics/Color;"
private const val BOOLEAN = "Ljava/lang/Boolean;"
private const val BIAS_HORIZONTAL = "Landroidx/compose/ui/BiasAlignment${'$'}Horizontal;"
private const val SIZE_ELEMENT = "Landroidx/compose/foundation/layout/SizeElement;"
private const val OBJECT = "Ljava/lang/Object;"
private const val STATIC = 8
private const val PORTRAIT_CHANGED = "Portrait player layout changed"

private fun Instruction.methodRef() = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.fieldRefOrNull() =
    (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Instruction.stringOrNull() =
    ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

private fun Instruction.typeOrNull() =
    ((this as? ReferenceInstruction)?.reference as? TypeReference)?.type

private fun List<Instruction>.headerCalls(hook: String) = count {
    it.methodRef()?.let { call -> call.definingClass == hook && call.name == "header" } == true
}

private fun MutableMethod.instructionList() = implementation!!.instructions.toList()

private fun Method.code() = implementation?.instructions?.toList().orEmpty()

private fun MethodReference.text() =
    "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun FieldReference.text() = "$definingClass->$name:$type"

private fun MethodReference.takes(vararg types: String) =
    parameterTypes.map { it.toString() } == types.toList()

private fun Method.shaped(vararg parameters: String, returns: String) =
    accessFlags and STATIC != 0 &&
        returnType == returns &&
        parameterTypes.map { it.toString() } == parameters.toList()

private fun Method.sameAs(other: Method) =
    name == other.name && parameterTypes == other.parameterTypes && returnType == other.returnType

private fun <T> Collection<T>.exactly(what: String): T {
    check(size == 1) { "$what ($size found)" }
    return first()
}

private val GOTOS = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)

/** Where each branch lands, so nothing is inserted where another path jumps in. */
internal fun branchTargets(code: List<Instruction>): Pair<Set<Int>, Map<Int, Int>> {
    val address = IntArray(code.size + 1)
    for (i in code.indices) address[i + 1] = address[i] + code[i].codeUnits
    val indexAt = HashMap<Int, Int>()
    for (i in code.indices) indexAt[address[i]] = i
    val targets = HashSet<Int>()
    val single = HashMap<Int, Int>()
    for ((i, step) in code.withIndex()) {
        if (step !is OffsetInstruction) continue
        val at = address[i] + step.codeOffset
        if (step.opcode == Opcode.PACKED_SWITCH || step.opcode == Opcode.SPARSE_SWITCH) {
            val payload = indexAt[at]?.let { code[it] as? SwitchPayload } ?: continue
            for (element in payload.switchElements) indexAt[address[i] + element.offset]?.let {
                targets += it
            }
        } else {
            val target = indexAt[at] ?: continue
            targets += target
            single[i] = target
        }
    }
    return targets to single
}

private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val written = (this as? OneRegisterInstruction)?.registerA ?: return false
    return written == register || (opcode.setsWideRegister() && written + 1 == register)
}

private fun Instruction.reads(register: Int): Boolean =
    when (this) {
        is FiveRegisterInstruction ->
            listOf(registerC, registerD, registerE, registerF, registerG)
                .take(registerCount)
                .contains(register)
        is RegisterRangeInstruction -> register in startRegister until startRegister + registerCount
        is ThreeRegisterInstruction ->
            register == registerB ||
                register == registerC ||
                (!opcode.setsRegister() && register == registerA)
        is TwoRegisterInstruction ->
            register == registerB || (!opcode.setsRegister() && register == registerA)
        is OneRegisterInstruction -> !opcode.setsRegister() && register == registerA
        else -> false
    }

/** True when straight-line code from [from] writes [register] before anything reads it. */
private fun List<Instruction>.overwrittenBeforeRead(from: Int, register: Int): Boolean {
    for (index in from until minOf(size, from + 16)) {
        val step = this[index]
        if (step.reads(register)) return false
        if (step.writes(register)) return true
        if (
            step is OffsetInstruction ||
                step.opcode.name.startsWith("return") ||
                step.opcode == Opcode.THROW
        )
            return false
    }
    return false
}

internal class OriginCaptionSite(val at: Int, val modifier: Int)

internal fun originCaptionSite(code: List<Instruction>, registers: Int): OriginCaptionSite {
    val changed = "Native playback caption changed"
    check(
        code.any { it.stringOrNull() == "text" } &&
            code.any { it.stringOrNull() == "onClick" } &&
            code.any {
                it.fieldRefOrNull()?.let { f ->
                    f.name == "labelSmall" && f.type == "Landroidx/compose/ui/text/TextStyle;"
                } == true
            }
    ) {
        "$changed: text style or inputs"
    }
    val clickable =
        code.indices
            .filter { i ->
                code[i].methodRef()?.let { m ->
                    m.name.startsWith("clickable-") &&
                        m.returnType == MODIFIER &&
                        m.takes(
                            MODIFIER,
                            "Z",
                            "Ljava/lang/String;",
                            "Landroidx/compose/ui/semantics/Role;",
                            "Lkotlin/jvm/functions/Function0;",
                            "I",
                        )
                } == true
            }
            .singleOrNull()
    check(clickable != null && code.getOrNull(clickable + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT) {
        "$changed: native click target"
    }
    val padding =
        (clickable + 2 until code.size)
            .filter { i ->
                code[i].methodRef()?.let { m ->
                    m.name == "padding" &&
                        m.returnType == MODIFIER &&
                        m.takes(MODIFIER, "Landroidx/compose/foundation/layout/PaddingValues;")
                } == true
            }
            .singleOrNull()
    check(padding != null) { "$changed: native content padding" }
    val modifier = code[padding].argument(0)
    val result = (code[clickable + 1] as OneRegisterInstruction).registerA
    val base = registers - 8
    check(
        modifier in 0..255 &&
            modifier == result &&
            modifier != base + 4 &&
            (clickable + 2 until padding).none { code[it].writes(modifier) } &&
            padding !in branchTargets(code).first
    ) {
        "$changed: click result ownership"
    }
    check(
        code.frameInput(code[clickable].argument(4), clickable, base + 1) &&
            code.frameInput(code[padding].argument(1), padding, base + 3)
    ) {
        "$changed: callback or padding ownership"
    }
    check(
        (padding + 1 until code.size).any { i ->
            code[i].methodRef()?.let { m ->
                m.definingClass == "Landroidx/compose/material3/TextKt;" &&
                    m.name.startsWith("Text-") &&
                    m.parameterTypes.take(2).map(Any::toString) ==
                        listOf("Ljava/lang/String;", MODIFIER)
            } == true
        }
    ) {
        "$changed: native text rendering"
    }
    return OriginCaptionSite(padding, modifier)
}

internal fun locateOriginCaption(all: List<ClassDef>): Method {
    val methods =
        all.filter { it.type.startsWith(PLAYER_UI) }
            .flatMap { c ->
                c.methods.filter { m ->
                    m.accessFlags and STATIC != 0 &&
                        m.accessFlags and 1 != 0 &&
                        m.shaped(
                            "Ljava/lang/String;",
                            "Lkotlin/jvm/functions/Function0;",
                            "I",
                            "Landroidx/compose/foundation/layout/PaddingValuesImpl;",
                            MODIFIER,
                            COMPOSER,
                            "I",
                            "I",
                            returns = "V",
                        )
                }
            }
    check(methods.size == 1) { "Native playback caption changed: unique caption renderer" }
    val method = methods.single()
    return method
}

/** What the portrait player's drag strip is hooked at; found from how the strip is built. */
internal class PortraitAnchors(
    val height: Int,
    val heightRegister: Int,
    val gate: Int,
    val composer: Int,
    val close: Int,
)

internal fun portraitAnchors(code: List<Instruction>): PortraitAnchors {
    val dims = code.indices.filter { code[it].stringOrNull() == "spotifyCanvasDim" }
    check(dims.size == 1) { PORTRAIT_CHANGED }
    val dim = dims.single()
    val caption =
        (dim + 1 until code.size).firstOrNull { index ->
            code[index].methodRef()?.let {
                it.definingClass == NOW_PLAYING && it.name == "WhileShown"
            } == true
        }
    check(caption != null) { PORTRAIT_CHANGED }
    val close =
        (caption + 1 until code.size).firstOrNull { index ->
            code[index].methodRef()?.let { it.definingClass == COMPOSER && it.name == "endNode" } ==
                true
        }
    check(close != null) { PORTRAIT_CHANGED }
    // The strip is the stacked, full-width box ahead of the caption: its height call follows the
    // z-index element and the full-width modifier, ahead of the handle's own height.
    val inits =
        (dim until caption).filter { index ->
            code[index].methodRef()?.let { it.definingClass == Z_INDEX && it.name == "<init>" } ==
                true
        }
    check(inits.size == 1) { PORTRAIT_CHANGED }
    val calls = (inits.single() + 1 until caption).filter { code[it].methodRef() != null }.take(2)
    check(
        calls.size == 2 &&
            code[calls[0]].methodRef()!!.name.startsWith("fillMaxWidth") &&
            code[calls[1]].methodRef()!!.let {
                it.name == "height-3ABfNKs" && it.takes(MODIFIER, "F") && it.returnType == MODIFIER
            }
    ) {
        "Portrait drag strip changed"
    }
    val height = calls[1]
    val heightRegister = (code[height] as FiveRegisterInstruction).registerD
    check(heightRegister < 256 && code.overwrittenBeforeRead(height + 1, heightRegister)) {
        "The strip height is used after its call"
    }
    // The caption is skipped by a test on whether to show it; the pair goes in front of it.
    val (targets, jumps) = branchTargets(code)
    val gate =
        (caption - 1 downTo dim).firstOrNull { index ->
            code[index].opcode.name.startsWith("if-") && (jumps[index] ?: -1) in caption + 1..close
        }
    check(gate != null && gate > calls[1] && gate !in targets) { "Portrait caption test changed" }
    val composer = (code[close] as FiveRegisterInstruction).registerC
    check(composer < 256 && (gate until close).none { code[it].writes(composer) }) {
        "The composer register changes before the portrait strip closes"
    }
    // Only the weight receiver and its literal are set up between the strip and the artwork.
    val weight =
        (close + 1 until code.size).firstOrNull { index -> code[index].methodRef() != null }
    val between = if (weight == null) emptyList() else code.subList(close + 1, weight)
    check(
        weight != null &&
            code[weight].methodRef()?.let {
                it.definingClass == COLUMN_SCOPE && it.name == "weight${'$'}default"
            } == true &&
            between.all {
                it is NarrowLiteralInstruction || it.fieldRefOrNull()?.definingClass == COLUMN_SCOPE
            }
    ) {
        "The drag strip no longer ends before the weighted artwork"
    }
    return PortraitAnchors(height, heightRegister, gate, composer, close)
}

/** The stats line's call in the artwork slot: [call], its modifier and composer registers. */
internal class StatsSite(val call: Int, val modifier: Int, val composer: Int)

/**
 * The artwork's stats line is placed at the foot of the sleeve: aligned bottom-centre, padded, in a
 * graphics layer, then handed to the stats composable, and the slot's branch ends right after.
 * Anything else that calls the stats composable, such as its own re-run callback, is not it.
 */
internal fun statsSite(code: List<Instruction>, call: Int): StatsSite? {
    val stats = code[call] as? FiveRegisterInstruction ?: return null
    if (stats.registerCount != 4 || call < 2 || call + 1 >= code.size) return null
    val modifier = stats.registerD
    val result = code[call - 1]
    if (
        result.opcode != Opcode.MOVE_RESULT_OBJECT ||
            (result as OneRegisterInstruction).registerA != modifier
    )
        return null
    val layer = code[call - 2].methodRef() ?: return null
    if (layer.name != "graphicsLayer" || layer.returnType != MODIFIER) return null
    val window = code.subList(maxOf(0, call - 80), call)
    val bottom =
        window.any { step ->
            step.fieldRefOrNull()?.let {
                it.definingClass == ALIGNMENT_COMPANION && it.name == "BottomCenter"
            } == true
        }
    val aligned =
        window.any { step ->
            step.methodRef()?.let { it.name == "align" && it.takes(MODIFIER, ALIGNMENT) } == true
        }
    val padded =
        window.any { step ->
            step.methodRef()?.let {
                it.name.startsWith("padding") && it.takes(MODIFIER, "F", "F")
            } == true
        }
    if (!bottom || !aligned || !padded) return null
    if (code[call + 1].opcode !in GOTOS) return null
    val (targets, _) = branchTargets(code)
    if (call in targets || call + 1 in targets) return null
    val composer = stats.registerE
    if (modifier >= 256 || composer >= 256) return null
    return StatsSite(call, modifier, composer)
}

private fun Instruction.opcodeIs(vararg ops: Opcode) = opcode in ops

private val MOVE_OBJECTS =
    arrayOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)

/** The register an invoke passes as its [index]th argument. */
private fun Instruction.argument(index: Int): Int =
    when (this) {
        is FiveRegisterInstruction ->
            listOf(registerC, registerD, registerE, registerF, registerG)[index]
        is RegisterRangeInstruction -> startRegister + index
        else -> error("Not an invoke")
    }

/**
 * The title row's own placement in the sleeve method: the full-width modifier is offset by the
 * host's title-top lambda, and the same modifier then gets its height, its position callback and
 * the row's measure policy. The icon is emitted as the row's sibling just after that offset, with
 * the modifier register [title] still unread by anything else and [composer] untouched until the
 * row.
 */
internal class TitleAnchor(val insert: Int, val title: Int, val composer: Int)

private fun Instruction.isOffsetCall() =
    methodRef()?.let {
        it.name == "offset" && it.takes(MODIFIER, FUNCTION1) && it.returnType == MODIFIER
    } == true

/** Indices of the title row's offset calls, by shape alone. */
internal fun titleChains(code: List<Instruction>): List<Int> =
    code.indices.filter { o ->
        if (!code[o].isOffsetCall() || o + 1 >= code.size) return@filter false
        if (code[o + 1].opcode != Opcode.MOVE_RESULT_OBJECT) return@filter false
        val receiver = code[o].argument(0)
        val fill =
            (o - 1 downTo 0).firstOrNull {
                code[it].methodRef()?.name?.startsWith("fillMaxWidth") == true
            }
        if (
            fill == null ||
                code[fill + 1].opcode != Opcode.MOVE_RESULT_OBJECT ||
                (code[fill + 1] as OneRegisterInstruction).registerA != receiver ||
                (fill + 2 until o).any { code[it].writes(receiver) || code[it].isOffsetCall() }
        )
            return@filter false
        val start =
            (o + 1 until code.size).firstOrNull {
                code[it].methodRef()?.let { call ->
                    call.definingClass == COMPOSER && call.name == "startReusableNode"
                } == true
            } ?: return@filter false
        fun at(match: (MethodReference) -> Boolean) =
            (o + 1 until start).filter { code[it].methodRef()?.let(match) == true }
        val height = at { it.name == "height-3ABfNKs" && it.takes(MODIFIER, "F") }
        val placed = at { it.name == "onGloballyPositioned" && it.takes(MODIFIER, FUNCTION1) }
        val row = at { it.name == "rowMeasurePolicy" }
        height.size == 1 &&
            placed.size == 1 &&
            row.size == 1 &&
            height[0] < placed[0] &&
            placed[0] < row[0]
    }

internal fun titleAnchor(code: List<Instruction>): TitleAnchor {
    val chains = titleChains(code)
    check(chains.size == 1) { "The title row is placed in ${chains.size} places" }
    val offset = chains.single()
    val title = (code[offset + 1] as OneRegisterInstruction).registerA
    val insert = offset + 2
    val row =
        (insert until code.size).first {
            code[it].methodRef()?.let { call -> call.name == "rowMeasurePolicy" } == true
        }
    val policy = code[row].methodRef()!!
    val at = policy.parameterTypes.map { it.toString() }.indexOf(COMPOSER)
    check(at >= 0) { "The title row's measure policy takes no composer" }
    val composer = code[row].argument(at)
    check(title < 16 && composer < 16) { "The title row's registers are out of reach" }
    check(insert !in branchTargets(code).first) { "The title row's modifier is a branch target" }
    val touch = (insert until code.size).first { code[it].reads(title) || code[it].writes(title) }
    check(code[touch].reads(title)) { "The title row's modifier is replaced before it is used" }
    check((insert..row).none { code[it].writes(composer) }) {
        "The composer register changes before the title row"
    }
    return TitleAnchor(insert, title, composer)
}

/** Where the landscape artwork lambda starts executing, with the artwork modifier and composer. */
internal class SquareAnchor(val insert: Int, val modifier: Int, val composer: Int)

private fun landscapeArtworkCalls(code: List<Instruction>) =
    code.indices.filter {
        code[it].methodRef()?.let { call ->
            call.definingClass == LANDSCAPE && call.name == "LandscapeArtwork"
        } == true
    }

internal fun isSquareCaller(method: Method): Boolean {
    val code = method.code()
    return code.any { it.stringOrNull() == "artworkModifier" } &&
        landscapeArtworkCalls(code).isNotEmpty()
}

/**
 * The lambda that gives the landscape artwork its square modifier. The host's own null check names
 * that modifier, and the branch that runs the artwork starts after the composer's skip test. The
 * icon goes there, still holding the untouched square (R8 reuses the parameter registers later) and
 * the composer; it is a sibling of the artwork and is stacked above it.
 */
internal fun squareAnchor(code: List<Instruction>): SquareAnchor {
    val calls = landscapeArtworkCalls(code)
    check(calls.size == 1) { "The landscape artwork is placed in ${calls.size} places" }
    val named = code.indices.filter { code[it].stringOrNull() == "artworkModifier" }
    check(named.size == 1) { "The landscape artwork modifier is named ${named.size} times" }
    val check = named.single() + 1
    val checkCall = code[check].methodRef()
    check(
        checkCall != null &&
            checkCall.name == "checkNotNullParameter" &&
            code[check].argument(1) == (code[named.single()] as OneRegisterInstruction).registerA
    ) {
        "The landscape artwork modifier is no longer checked first"
    }
    val modifier = code[check].argument(0)
    val test =
        (check until calls.single()).filter {
            code[it].methodRef()?.let { call ->
                call.definingClass == COMPOSER && call.name == "shouldExecute"
            } == true
        }
    check(test.size == 1) { "The landscape artwork lambda has ${test.size} skip tests" }
    val result = test.single() + 1
    val gate = result + 1
    check(
        code[result].opcode == Opcode.MOVE_RESULT &&
            code[gate].opcode == Opcode.IF_EQZ &&
            (code[gate] as OneRegisterInstruction).registerA ==
                (code[result] as OneRegisterInstruction).registerA
    ) {
        "The landscape artwork skip test changed"
    }
    val composer = code[test.single()].argument(0)
    val insert = gate + 1
    check(modifier < 16 && composer < 16) { "The landscape artwork registers are out of reach" }
    check(insert !in branchTargets(code).first) { "The landscape artwork branch is a jump target" }
    check(
        (check + 1 until insert).none { code[it].writes(modifier) || code[it].writes(composer) }
    ) {
        "The landscape artwork modifier or composer changes before the icon"
    }
    val touch =
        (insert until code.size).first { code[it].reads(modifier) || code[it].writes(modifier) }
    check(code[touch].reads(modifier)) {
        "The landscape artwork modifier is replaced before it is used"
    }
    return SquareAnchor(insert, modifier, composer)
}

/** The methods the icons are placed in. */
internal class Located(
    val portrait: Pair<String, Method>,
    val sleeve: Pair<String, Method>,
    val square: Pair<String, Method>,
    val stats: List<Pair<String, Method>>,
)

internal fun locatePlayerIcons(all: List<ClassDef>): Located {
    val owners = mutableListOf<Pair<String, Method>>()
    val statsMethods = mutableListOf<Pair<String, Method>>()
    val squares = mutableListOf<Pair<String, Method>>()
    val titled = mutableListOf<Pair<String, Method>>()
    for (type in all) {
        if (!type.type.startsWith("Lcom/music/bitchord/")) continue
        for (method in type.methods) {
            val code = method.implementation?.instructions ?: continue
            var stats = false
            var dimmed = false
            for (step in code) {
                if (step.stringOrNull() == "spotifyCanvasDim") dimmed = true
                val call = step.methodRef() ?: continue
                if (call.definingClass == PLAYER_CONTROLS && call.name == "SleeveNerdStats")
                    stats = true
            }
            if (type.type.startsWith(NOW_PLAYING.removeSuffix(";"))) {
                if (dimmed) owners += type.type to method
                if (titleChains(code.toList()).isNotEmpty()) titled += type.type to method
            }
            if (stats) statsMethods += type.type to method
            if (isSquareCaller(method)) squares += type.type to method
        }
    }
    check(owners.size == 1) { PORTRAIT_CHANGED }
    // The sleeve method is the title row's layout that builds the stats line's own lambda.
    val statsClasses = statsMethods.map { it.first }.toSet()
    val sleeves =
        titled.filter { (_, method) ->
            method.code().any {
                it.opcode == Opcode.NEW_INSTANCE && it.typeOrNull() in statsClasses
            }
        }
    check(sleeves.size == 1) { "The sleeve layout is found in ${sleeves.size} places" }
    check(squares.size == 1) { "The landscape artwork lambda is found in ${squares.size} places" }
    return Located(owners.single(), sleeves.single(), squares.single(), statsMethods)
}

internal data class FullscreenFrameSite(val at: Int, val overlay: Int, val composer: Int)

private fun List<Instruction>.frameInput(register: Int, before: Int, incoming: Int): Boolean {
    var value = register
    var end = before
    repeat(8) {
        val written = (end - 1 downTo 0).firstOrNull { this[it].writes(value) }
        if (written == null) return value == incoming
        val step = this[written]
        if (
            step.opcode !in
                setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)
        )
            return false
        value = (step as TwoRegisterInstruction).registerB
        end = written
    }
    return false
}

internal fun fullscreenFrameSite(
    code: List<Instruction>,
    registers: Int,
    parameters: List<String>,
): FullscreenFrameSite {
    check(
        parameters.size == 9 &&
            parameters[0] == MODIFIER &&
            parameters[1] in setOf("J", "Z") &&
            parameters[3] == "Z" &&
            parameters[4] == "Lkotlin/jvm/functions/Function0;" &&
            parameters.subList(5, 9) ==
                listOf(
                    "Landroidx/compose/runtime/internal/ComposableLambdaImpl;",
                    "Landroidx/compose/runtime/internal/ComposableLambdaImpl;",
                    COMPOSER,
                    "I",
                )
    ) {
        "$FULLSCREEN_CHANGED: player frame parameters"
    }
    fun words(until: Int) = parameters.take(until).sumOf { if (it == "J" || it == "D") 2 else 1 }
    val base = registers - words(parameters.size)
    check(base >= 0) { "$FULLSCREEN_CHANGED: player frame registers" }
    val checks = code.indices.filter { i -> code[i].stringOrNull() == "overlayOpen" }
    check(checks.size == 1) { "$FULLSCREEN_CHANGED: native overlay input" }
    val name = checks.single()
    val checkAt = name + 1
    val call = code.getOrNull(checkAt)?.methodRef()
    check(
        call?.definingClass == "Lkotlin/jvm/internal/Intrinsics;" &&
            call.name == "checkNotNullParameter" &&
            code[checkAt] is FiveRegisterInstruction
    ) {
        "$FULLSCREEN_CHANGED: overlay parameter check"
    }
    val checked = code[checkAt] as FiveRegisterInstruction
    val overlay = checked.registerC
    check(
        checked.registerD == (code[name] as OneRegisterInstruction).registerA &&
            code.frameInput(overlay, checkAt, base + words(4))
    ) {
        "$FULLSCREEN_CHANGED: overlay parameter origin"
    }
    val groups =
        code.indices.filter { i ->
            code[i].methodRef()?.let {
                it.definingClass == COMPOSER &&
                    it.name == "startRestartGroup" &&
                    it.takes("I") &&
                    it.returnType == COMPOSER
            } == true
        }
    check(
        groups.size == 1 &&
            groups.single() > checkAt &&
            code[groups.single()] is FiveRegisterInstruction
    ) {
        "$FULLSCREEN_CHANGED: player frame restart scope"
    }
    val group = groups.single()
    val receiver = (code[group] as FiveRegisterInstruction).registerC
    check(code.frameInput(receiver, group, base + words(7))) {
        "$FULLSCREEN_CHANGED: frame composer origin"
    }
    var at = group + 1
    val composer =
        if (code.getOrNull(at)?.opcode == Opcode.MOVE_RESULT_OBJECT)
            (code[at++] as OneRegisterInstruction).registerA
        else receiver
    check(
        overlay < 16 &&
            composer < 16 &&
            overlay != composer &&
            at !in branchTargets(code).first &&
            (checkAt + 1 until at).none { code[it].writes(overlay) }
    ) {
        "$FULLSCREEN_CHANGED: overlay frame binding registers"
    }
    return FullscreenFrameSite(at, overlay, composer)
}

private fun locateFullscreenFrame(all: List<ClassDef>): Pair<Method, FullscreenFrameSite> {
    val frames =
        all.filter { owner -> owner.fields.any { it.name == "LocalPlayerFrost" } }
            .flatMap { it.methods }
            .filter { method ->
                val code = method.code()
                method.accessFlags and STATIC != 0 &&
                    method.returnType == "V" &&
                    code.any { it.stringOrNull() == "overlayOpen" } &&
                    code.any { it.stringOrNull() == "modifier" } &&
                    method.parameterTypes.size == 9 &&
                    method.parameterTypes[4].toString() == "Lkotlin/jvm/functions/Function0;" &&
                    method.parameterTypes[7].toString() == COMPOSER
            }
    check(frames.size == 1) { "$FULLSCREEN_CHANGED: native overlay frame (${frames.size})" }
    val frame = frames.single()
    return frame to
        fullscreenFrameSite(
            frame.code(),
            frame.implementation!!.registerCount,
            frame.parameterTypes.map { it.toString() },
        )
}

/** Everything the install needs, found without changing anything. */
internal class IconPlan(
    val anchors: PortraitAnchors,
    val title: TitleAnchor,
    val square: SquareAnchor,
    /**
     * Which of the stats-calling methods are the artwork slots, where in each, and if landscape.
     */
    val sites: List<Triple<Int, StatsSite, Boolean>>,
    val symbols: Map<String, String>,
)

internal fun planPlayerIcons(all: List<ClassDef>, located: Located): IconPlan {
    val portraitCode = located.portrait.second.code()
    val anchors = portraitAnchors(portraitCode)
    val title = titleAnchor(located.sleeve.second.code())
    val square = squareAnchor(located.square.second.code())
    val sites =
        located.stats.withIndex().mapNotNull { (at, entry) ->
            val code = entry.second.code()
            val here =
                code.indices.filter { index ->
                    code[index].methodRef()?.let {
                        it.definingClass == PLAYER_CONTROLS && it.name == "SleeveNerdStats"
                    } == true
                }
            val placed = here.mapNotNull { statsSite(code, it) }
            check(placed.size <= 1) { "The sleeve's stats line is placed twice in one method" }
            placed.singleOrNull()?.let {
                Triple(
                    at,
                    it,
                    code.any { step -> step.stringOrNull() == "${'$'}this${'$'}LandscapeArtwork" },
                )
            }
        }
    check(sites.size == 2 && sites.count { it.third } == 1) {
        "The sleeve's stats line is placed in ${sites.size} layouts"
    }
    val first = sites.first()
    val symbols =
        resolvePlayerIconSymbols(
            all,
            portraitCode,
            located.stats[first.first].second.code(),
            first.second,
        )
    return IconPlan(anchors, title, square, sites, symbols)
}

/** How many of each hook an earlier install left in the code. */
internal data class IconCounts(
    val strip: Int = 0,
    val modes: Int = 0,
    val above: Int = 0,
    val square: Int = 0,
    val liftPortrait: Int = 0,
    val liftLandscape: Int = 0,
    val editor: Int = 0,
    val portraitHeader: Int = 0,
    val landscapeHeader: Int = 0,
    val caption: Int = 0,
)

/** True when nothing is installed yet; false when all of it is; anything else is refused. */
internal fun IconCounts.needsInstall(): Boolean {
    if (this == IconCounts()) return true
    check(editor == 0) { "Shared player icons still place the editor inside the sleeve" }
    check(this == IconCounts(1, 1, 1, 1, 1, 1, 0, 0, 1, 1)) {
        "Shared player icons are only partly installed"
    }
    return false
}

/**
 * The portrait player gets the pair in its drag strip, between the handle and "Playing from …", and
 * a taller strip to hold it; the landscape main pane gets the same pair as the first child of its
 * Column. The Automix Editor icon is a sibling of the portrait title row and of the landscape
 * artwork square, never inside the scaled sleeve, and the stats line is lifted above it while it
 * shows. Every place is found from the Compose structure around it, so the shared icons can be
 * installed by either optional patch, in either order.
 */
internal fun BytecodePatchContext.installPlayerHeader(hook: String) {
    val all = mutableListOf<ClassDef>()
    classDefForEach { all += it }
    val located = locatePlayerIcons(all)
    val portraitMethod =
        mutableClassDefBy(located.portrait.first).methods.single {
            it.sameAs(located.portrait.second)
        }
    val sleeveMethod =
        mutableClassDefBy(located.sleeve.first).methods.single { it.sameAs(located.sleeve.second) }
    val squareMethod =
        mutableClassDefBy(located.square.first).methods.single { it.sameAs(located.square.second) }
    val landscapeMethod =
        mutableClassDefBy(LANDSCAPE).methods.single { it.name == "LandscapeMainPane" }
    val statsEdits =
        located.stats.map { (type, found) ->
            mutableClassDefBy(type).methods.single { it.sameAs(found) }
        }

    val caption = locateOriginCaption(all)
    val captionMethod =
        mutableClassDefBy(caption.definingClass).methods.single { it.sameAs(caption) }
    val portraitCode = portraitMethod.instructionList()
    val landscapeCode = landscapeMethod.instructionList()
    // What an earlier install left in the code, counted from the code as it is now.
    fun List<Instruction>.icons(name: String) = count {
        it.methodRef()?.let { call -> call.definingClass == PLAYER_ICONS && call.name == name } ==
            true
    }
    val statsCode = statsEdits.map { it.instructionList() }
    val counts =
        IconCounts(
            strip = portraitCode.icons("strip"),
            modes = portraitCode.icons("modes"),
            above = sleeveMethod.instructionList().icons("editorAbove"),
            square = squareMethod.instructionList().icons("editorOnSquare"),
            liftPortrait = statsCode.sumOf { it.icons("liftPortrait") },
            liftLandscape = statsCode.sumOf { it.icons("liftLandscape") },
            editor =
                (statsCode + listOf(sleeveMethod.instructionList(), squareMethod.instructionList()))
                    .sumOf { it.icons("editor") },
            portraitHeader = portraitCode.headerCalls(hook),
            landscapeHeader = landscapeCode.headerCalls(hook),
            caption = captionMethod.instructionList().icons("caption"),
        )
    if (!counts.needsInstall()) {
        val table =
            mutableClassDefBy(PLAYER_ICONS)
                .methods
                .single { it.name == "symbols" }
                .code()
                .firstOrNull()
        check(table?.stringOrNull()?.isNotEmpty() == true) {
            "Shared player icons have no host symbols"
        }
        return
    }

    val captionSite =
        originCaptionSite(
            captionMethod.instructionList(),
            captionMethod.implementation!!.registerCount,
        )
    val plan = planPlayerIcons(all, located)
    val anchors = plan.anchors

    val parameters = landscapeMethod.parameterTypes.map { it.toString() }
    check(
        parameters.size >= 2 &&
            parameters[parameters.size - 2] == COMPOSER &&
            parameters.last() == "I" &&
            MODIFIER in parameters
    ) {
        "Landscape main Column changed"
    }
    val columns =
        landscapeCode.withIndex().filter { (_, instruction) ->
            instruction.methodRef()?.let {
                it.definingClass == "Landroidx/compose/foundation/layout/ColumnKt;" &&
                    it.name == "columnMeasurePolicy"
            } == true
        }
    check(columns.size == 1) { "Landscape main Column changed" }
    val modifier =
        (columns.single().index until landscapeCode.size).firstOrNull { index ->
            landscapeCode[index].fieldRefOrNull()?.let {
                it.definingClass == "Landroidx/compose/ui/node/ComposeUiNode${'$'}Companion;" &&
                    it.name == "SetModifier"
            } == true
        }
    check(modifier != null) { "Landscape Column modifier binding changed" }
    val install = modifier + 1
    val apply = landscapeCode[install].methodRef()
    check(
        apply?.definingClass == "Landroidx/compose/runtime/Updater;" && apply.name == "set-impl"
    ) {
        "Landscape Column modifier binding changed"
    }
    val landscapeComposer = (landscapeCode[install] as FiveRegisterInstruction).registerC

    captionMethod.addInstructions(
        captionSite.at,
        "invoke-static/range {v${captionSite.modifier} .. v${captionSite.modifier}}, " +
            "$PLAYER_ICONS->caption(Ljava/lang/Object;)Ljava/lang/Object;\n" +
            "move-result-object v${captionSite.modifier}\n" +
            "check-cast v${captionSite.modifier}, $MODIFIER",
    )
    // Later indices first, so the earlier ones stay where they were found.
    portraitMethod.addInstruction(
        anchors.gate,
        "invoke-static/range {v${anchors.composer} .. v${anchors.composer}}, " +
            "$PLAYER_ICONS->modes(Ljava/lang/Object;)V",
    )
    portraitMethod.addInstructions(
        anchors.height,
        "invoke-static/range {v${anchors.heightRegister} .. v${anchors.heightRegister}}, " +
            "$PLAYER_ICONS->strip(F)F\n" +
            "move-result v${anchors.heightRegister}",
    )
    sleeveMethod.addInstruction(
        plan.title.insert,
        "invoke-static {v${plan.title.title}, v${plan.title.composer}}, " +
            "$PLAYER_ICONS->editorAbove(Ljava/lang/Object;Ljava/lang/Object;)V",
    )
    squareMethod.addInstruction(
        plan.square.insert,
        "invoke-static {v${plan.square.modifier}, v${plan.square.composer}}, " +
            "$PLAYER_ICONS->editorOnSquare(Ljava/lang/Object;Ljava/lang/Object;)V",
    )
    for ((which, at, landscape) in plan.sites) {
        val lift = if (landscape) "liftLandscape" else "liftPortrait"
        statsEdits[which].addInstructions(
            at.call,
            "invoke-static/range {v${at.modifier} .. v${at.modifier}}, " +
                "$PLAYER_ICONS->$lift(Ljava/lang/Object;)Ljava/lang/Object;\n" +
                "move-result-object v${at.modifier}\n" +
                "check-cast v${at.modifier}, $MODIFIER",
        )
    }
    landscapeMethod.addInstruction(
        install + 1,
        "invoke-static/range {v$landscapeComposer .. v$landscapeComposer}, $hook->header(Ljava/lang/Object;)V",
    )
    installPlayerIconSymbols(plan.symbols)
    println("Added the player icons")
}

/**
 * Binds the Compose and BitChord helpers the icons call by what they take and give, and by how the
 * host's own player code uses them. The ink is the player's tint local where it has one, or its
 * chrome local with the blend helper the strip itself applies; one of the two must be there.
 */
internal fun resolvePlayerIconSymbols(
    all: List<ClassDef>,
    portrait: List<Instruction>,
    siteMethod: List<Instruction>,
    site: StatsSite,
): Map<String, String> {
    val symbols = linkedMapOf<String, String>()
    val statics = all.flatMap { c -> c.methods.filter { it.accessFlags and STATIC != 0 } }
    val window = siteMethod.subList(maxOf(0, site.call - 80), site.call)

    symbols["androidView"] =
        statics
            .filter { it.shaped(FUNCTION1, MODIFIER, FUNCTION1, COMPOSER, "I", "I", returns = "V") }
            .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})V" }
            .exactly("The Compose view-interop entry")

    val padVertical =
        window
            .mapNotNull { it.methodRef() }
            .filter { it.name.startsWith("padding") && it.takes(MODIFIER, "F", "F") }
            .map { it.definingClass }
            .toSet()
            .exactly("The padding helper the stats line uses")
    symbols["padding"] =
        statics
            .filter {
                it.definingClass == padVertical &&
                    it.shaped(MODIFIER, "F", "F", "F", "F", returns = MODIFIER)
            }
            .map {
                "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})$MODIFIER"
            }
            .exactly("The four-sided padding helper")

    symbols["companion"] =
        window
            .mapNotNull { it.fieldRefOrNull() }
            .filter { it.type == MODIFIER_COMPANION }
            .map { it.text() }
            .toSet()
            .exactly("The Modifier companion the stats line reads")
    symbols["bottomCenter"] =
        window
            .mapNotNull { it.fieldRefOrNull() }
            .filter { it.definingClass == ALIGNMENT_COMPANION && it.name == "BottomCenter" }
            .map { it.text() }
            .toSet()
            .exactly("The stats line's bottom-centre alignment")
    symbols["topCenter"] =
        portrait
            .mapNotNull { it.fieldRefOrNull() }
            .filter { it.definingClass == ALIGNMENT_COMPANION && it.name == "TopCenter" }
            .map { it.text() }
            .toSet()
            .exactly("The drag strip's top-centre alignment")
    symbols["startGroup"] =
        portrait
            .mapNotNull { it.methodRef() }
            .filter {
                it.definingClass == COMPOSER && it.name == "startReplaceGroup" && it.takes("I")
            }
            .map { it.text() }
            .toSet()
            .exactly("The composer's replaceable group start")
    symbols["endGroup"] =
        portrait
            .mapNotNull { it.methodRef() }
            .filter {
                it.definingClass == COMPOSER &&
                    it.name == "endReplaceGroup" &&
                    it.parameterTypes.isEmpty()
            }
            .map { it.text() }
            .toSet()
            .exactly("The composer's replaceable group end")

    val sizes =
        statics
            .filter {
                it.name == "wrapContentWidth" &&
                    it.shaped(MODIFIER, BIAS_HORIZONTAL, "Z", returns = MODIFIER)
            }
            .exactly("The wrap-content-width helper")
    symbols["wrapWidth"] =
        "${sizes.definingClass}->${sizes.name}(${sizes.parameterTypes.joinToString("")})$MODIFIER"
    symbols["centerH"] =
        sizes
            .code()
            .mapNotNull { it.fieldRefOrNull() }
            .filter {
                it.definingClass == ALIGNMENT_COMPANION &&
                    it.name == "CenterHorizontally" &&
                    it.type == BIAS_HORIZONTAL
            }
            .map { it.text() }
            .toSet()
            .exactly("The centred horizontal alignment")
    symbols["size"] =
        statics
            .filter {
                it.definingClass == sizes.definingClass &&
                    it.name.startsWith("size-") &&
                    it.shaped(MODIFIER, "F", returns = MODIFIER)
            }
            .map {
                "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})$MODIFIER"
            }
            .exactly("The square size helper")
    symbols["captionHeight"] =
        statics
            .filter {
                it.definingClass == sizes.definingClass &&
                    it.name.startsWith("heightIn-") &&
                    it.shaped(MODIFIER, "F", "F", returns = MODIFIER) &&
                    it.code().any { i ->
                        i.methodRef()?.let { call ->
                            call.definingClass == SIZE_ELEMENT &&
                                call.name == "<init>" &&
                                call.takes("F", "F", "F", "F", "I")
                        } == true
                    }
            }
            .map {
                "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})$MODIFIER"
            }
            .exactly("The native minimum caption height helper")
    symbols["offset"] =
        statics
            .filter {
                it.definingClass == padVertical &&
                    it.name.startsWith("offset") &&
                    it.shaped(MODIFIER, "F", "F", returns = MODIFIER)
            }
            .map {
                "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})$MODIFIER"
            }
            .exactly("The offset helper")
    val element = all.filter { it.type == SIZE_ELEMENT }.exactly("The size element class")
    for (side in listOf("minWidth", "minHeight", "maxWidth", "maxHeight")) symbols[side] =
        element.fields
            .filter { it.name == side && it.type == "F" }
            .exactly("The size element's $side")
            .let { "${it.definingClass}->${it.name}:${it.type}" }

    symbols["zIndex"] =
        portrait
            .mapNotNull { it.methodRef() }
            .filter { it.definingClass == Z_INDEX && it.name == "<init>" && it.takes("F") }
            .map { "${it.definingClass}-><init>(F)V" }
            .toSet()
            .exactly("The stacking element the drag strip uses")

    // The box scope's own align() shows which element a child is wrapped in.
    symbols["childData"] =
        all.filter { it.type == BOX_SCOPE }
            .exactly("The box scope class")
            .methods
            .filter {
                it.name == "align" &&
                    it.parameterTypes.map { p -> p.toString() } == listOf(MODIFIER, ALIGNMENT)
            }
            .exactly("The box scope's align")
            .code()
            .mapNotNull { it.methodRef() }
            .filter { it.name == "<init>" && it.takes(ALIGNMENT, "Z") }
            .map { "${it.definingClass}-><init>(${it.parameterTypes.joinToString("")})V" }
            .exactly("The aligned child element of the box scope")

    // The host's own round glyph shows how it inks itself.
    val circle =
        all.filter { it.type.startsWith(PLAYER_UI) }
            .flatMap { c ->
                c.methods.filter { method ->
                    val strings = method.code().mapNotNull { it.stringOrNull() }
                    "glyphDisc" in strings && "playerMenuGlyph" in strings
                }
            }
            .exactly("The player's round glyph")
    val glyph = circle.code()
    val locals =
        glyph.indices
            .filter { i ->
                glyph[i].methodRef()?.let {
                    it.definingClass == COMPOSER && it.name == "consume" && it.takes(LOCAL)
                } == true
            }
            .mapNotNull { i ->
                val local =
                    (maxOf(0, i - 3) until i)
                        .mapNotNull { glyph[it].fieldRefOrNull() }
                        .lastOrNull { it.type.endsWith("CompositionLocal;") }
                val cast =
                    (i + 1..minOf(glyph.size - 1, i + 3)).firstNotNullOfOrNull { j ->
                        glyph[j].typeOrNull()?.takeIf { it == COLOR || it == BOOLEAN }
                    }
                if (local == null || cast == null) null
                else Triple(local, cast, glyph[i].methodRef()!!)
            }
    val tints = locals.filter { it.second == COLOR }
    val chromes = locals.filter { it.second == BOOLEAN }
    check(tints.size + chromes.size <= 1) {
        "The player ink is ambiguous (${tints.size} tints, ${chromes.size} chrome)"
    }
    if (tints.isNotEmpty()) {
        symbols["tint"] = tints.single().first.text()
        symbols["consume"] = tints.single().third.text()
    } else if (chromes.isNotEmpty()) {
        val chrome = chromes.single()
        symbols["chrome"] = chrome.first.text()
        symbols["consume"] = chrome.third.text()
        // The glyph's own blend helper wraps the additive one, which the drag strip also calls.
        val owner = chrome.first.definingClass
        val vibrant =
            glyph
                .mapNotNull { it.methodRef() }
                .filter {
                    it.definingClass == owner &&
                        it.takes(MODIFIER, "Z") &&
                        it.returnType == MODIFIER
                }
                .map { it.text() }
                .toSet()
                .exactly("The player's chrome helper")
        val wrapper =
            statics
                .filter {
                    "${it.definingClass}->${it.name}" == vibrant.substringBefore("(") &&
                        it.shaped(MODIFIER, "Z", returns = MODIFIER)
                }
                .exactly("The chrome helper's body")
        val additive =
            wrapper
                .code()
                .mapNotNull { it.methodRef() }
                .filter {
                    it.definingClass == owner &&
                        it.takes(MODIFIER, "Z") &&
                        it.returnType == MODIFIER &&
                        "${it.definingClass}->${it.name}" != vibrant.substringBefore("(")
                }
                .map { it.text() }
                .toSet()
                .exactly("The player's additive blend")
        check(portrait.mapNotNull { it.methodRef() }.any { it.text() == additive }) {
            "The drag strip no longer applies the additive blend"
        }
        symbols["blend"] = additive
    } else println("Player icons: this BitChord has no player tint or chrome local; white is used")
    return symbols
}

/** Hands the resolved references to the extension, which binds them once. */
private fun BytecodePatchContext.installPlayerIconSymbols(symbols: Map<String, String>) {
    val method =
        mutableClassDefBy(PLAYER_ICONS).methods.single {
            it.name == "symbols" &&
                it.parameterTypes.isEmpty() &&
                it.returnType == "Ljava/lang/String;"
        }
    val table = symbols.entries.joinToString("\\n") { (key, value) -> "$key=$value" }
    method.addInstructions(0, "const-string v0, \"$table\"\nreturn-object v0")
}

private const val FULLSCREEN = "Lapp/bitchord/telegram/VideoSwitch${'$'}Fullscreen;"
private const val FULLSCREEN_CHANGED = "Fullscreen video player layout changed"
private const val ACTIVITY = "Landroid/app/Activity;"
private const val LAMBDA_IMPL = "Landroidx/compose/runtime/internal/ComposableLambdaImpl;"
private val FULLSCREEN_PLAYER =
    listOf(
        null,
        "Z",
        null,
        FUNCTION1,
        "Z",
        "Lkotlin/jvm/functions/Function0;",
        "Lkotlin/jvm/functions/Function0;",
        LAMBDA_IMPL,
        LAMBDA_IMPL,
        MODIFIER,
        COMPOSER,
        "I",
    )

/** The host's fullscreen player, by what it takes: a song, flags, the callbacks, a composer. */
private fun MethodReference.isFullscreenPlayer(): Boolean {
    val types = parameterTypes.map { it.toString() }
    return returnType == "V" &&
        types.size == FULLSCREEN_PLAYER.size &&
        types.indices.all { FULLSCREEN_PLAYER[it] == null || FULLSCREEN_PLAYER[it] == types[it] } &&
        types[0].startsWith("Lcom/music/bitchord/") &&
        types[2].startsWith("Lcoil3/request/")
}

/** True when both paths from here write [register] before any read, so it is free to use. */
private fun List<Instruction>.freeAt(from: Int, register: Int): Boolean {
    for (index in from until minOf(size, from + 200)) {
        val step = this[index]
        if (step.reads(register)) return false
        if (step.writes(register)) return true
        if (
            step is OffsetInstruction ||
                step.opcode.name.startsWith("return") ||
                step.opcode == Opcode.THROW
        )
            return false
    }
    return false
}

/**
 * Where the fullscreen choice goes: the host shows its fullscreen player when landscape and video
 * both hold, and tests landscape again for the landscape layout. [landscape] is the first test.
 */
internal class FullscreenGate(val landscape: Int, val at: Int, val free: Int, val composer: Int)

internal fun fullscreenGate(code: List<Instruction>, registers: Int): FullscreenGate? {
    val calls = code.indices.filter { code[it].methodRef()?.isFullscreenPlayer() == true }
    if (calls.isEmpty()) return null
    check(calls.size == 1) { FULLSCREEN_CHANGED }
    val call = calls.single()
    val (targets, jumps) = branchTargets(code)
    val pairs =
        (0 until call - 1).filter { i ->
            code[i].opcode == Opcode.IF_EQZ &&
                code[i + 1].opcode == Opcode.IF_EQZ &&
                jumps[i] != null &&
                jumps[i] == jumps[i + 1] &&
                jumps[i]!! > call
        }
    // The pair closest to the call is the one the call sits behind.
    val at = pairs.lastOrNull() ?: return null
    check(at !in targets) { "The fullscreen test is a branch target" }
    val landscape = (code[at] as OneRegisterInstruction).registerA
    val video = (code[at + 1] as OneRegisterInstruction).registerA
    val elseAt = jumps[at]!!
    val retest =
        (elseAt until minOf(code.size, elseAt + 200)).firstOrNull {
            code[it].opcode == Opcode.IF_EQZ &&
                (code[it] as OneRegisterInstruction).registerA == landscape
        }
    check(
        landscape != video &&
            retest != null &&
            (elseAt until retest).none { code[it].writes(landscape) }
    ) {
        "The landscape layout test changed"
    }
    check(landscape < 256) { "The landscape register is out of reach" }
    // The composer is the receiver of the first Compose call after the tests, and was last set by
    // a cast to the composer type that nothing since has changed.
    val composerCall =
        (at + 2 until call).firstOrNull {
            code[it].methodRef()?.definingClass == COMPOSER && code[it] is FiveRegisterInstruction
        }
    check(composerCall != null) { "The composer is not used at the fullscreen test" }
    val composer = (code[composerCall] as FiveRegisterInstruction).registerC
    val cast = (at - 1 downTo 0).firstOrNull { code[it].writes(composer) }
    check(
        composer < 256 &&
            cast != null &&
            code[cast].opcode == Opcode.CHECK_CAST &&
            code[cast].typeOrNull() == COMPOSER &&
            (at until composerCall).none { code[it].writes(composer) }
    ) {
        "The composer changes before the fullscreen test"
    }
    val free =
        (0 until registers).firstOrNull {
            it != landscape &&
                it != video &&
                it != composer &&
                code.freeAt(at + 2, it) &&
                code.freeAt(elseAt, it)
        }
    check(free != null) { "No free register at the fullscreen test" }
    return FullscreenGate(landscape, at, free, composer)
}

/** Where the host's orientation requests are told: [at] follows the load of the window. */
internal class OrientationSite(
    val at: Int,
    val activity: Int,
    val landscape: Int,
    val controller: Int,
)

internal fun orientationSite(
    code: List<Instruction>,
    registers: Int,
    activityField: FieldReference,
): OrientationSite {
    val loads =
        code.indices.filter {
            code[it].opcode == Opcode.IGET_OBJECT && code[it].fieldRefOrNull() == activityField
        }
    check(loads.isNotEmpty()) { "The orientation request has no window load" }
    val load = loads.first()
    val activity = (code[load] as OneRegisterInstruction).registerA
    // The boolean is the last parameter, in the last register.
    val landscape = registers - 1
    val controller = registers - 3
    check(
        activity < 16 &&
            landscape < 16 &&
            controller in 0..15 &&
            activity != controller &&
            (0..load).none { code[it].writes(landscape) || code[it].writes(controller) } &&
            code.frameInput((code[load] as TwoRegisterInstruction).registerB, load, controller)
    ) {
        "The orientation request changed receiver or arguments"
    }
    return OrientationSite(load + 1, activity, landscape, controller)
}

internal fun portraitRequestReceiver(
    code: List<Instruction>,
    registers: Int,
    request: MethodReference,
): Boolean {
    if (requestLiterals(code, request) != (1 to 0)) return false
    val index = code.indexOfFirst { it.methodRef()?.sameCall(request) == true }
    val call = code.getOrNull(index) as? FiveRegisterInstruction ?: return false
    return code.frameInput(call.registerC, index, registers - 1)
}

/** What a method asks of `request(orientation, landscape)`, for its two literals. */
internal fun requestLiterals(code: List<Instruction>, request: MethodReference): Pair<Int, Int>? {
    val call = code.indexOfFirst { it.methodRef()?.sameCall(request) == true }
    if (call < 0) return null
    val registers = (code[call] as FiveRegisterInstruction)
    fun literal(register: Int) =
        code
            .take(call)
            .lastOrNull { it.writes(register) }
            ?.let { (it as? NarrowLiteralInstruction)?.narrowLiteral }
    val orientation = literal(registers.registerD) ?: return null
    val landscape = literal(registers.registerE) ?: return null
    return orientation to landscape
}

private fun MethodReference.sameCall(other: MethodReference) =
    definingClass == other.definingClass &&
        name == other.name &&
        parameterTypes == other.parameterTypes

/** Everything the install needs, found without changing anything. */
internal data class FullscreenPlan(
    val type: String,
    val method: Method,
    val gate: FullscreenGate,
    val orienter: ClassDef,
    val request: Method,
    val site: OrientationSite,
    val handler: Pair<String, Method>,
    val frame: Pair<Method, FullscreenFrameSite>,
    val portrait: Method,
)

/** BitChord's own back handler: its name and shape, and it reaches the activity's dispatcher. */
private fun Method.isBackHandler(): Boolean {
    val body = code()
    return name == "BackHandler" &&
        accessFlags and STATIC != 0 &&
        accessFlags and 1 != 0 &&
        returnType == "V" &&
        parameterTypes.map { it.toString() } ==
            listOf("Z", "Lkotlin/jvm/functions/Function0;", COMPOSER, "I", "I") &&
        body.any { it.typeOrNull() == "Landroidx/activity/OnBackPressedDispatcherOwner;" } &&
        body.any {
            it.methodRef()?.let { call ->
                call.definingClass == "Landroidx/activity/OnBackPressedDispatcherOwner;" &&
                    call.returnType == "Landroidx/activity/OnBackPressedDispatcher;"
            } == true
        }
}

internal fun locateVideoFullscreen(all: List<ClassDef>): FullscreenPlan {
    val gates =
        all.flatMap { type ->
            type.methods.mapNotNull { method ->
                fullscreenGate(method.code(), method.implementation?.registerCount ?: 0)?.let {
                    Triple(type.type, method, it)
                }
            }
        }
    val orienters =
        all.filter { type ->
            val asked =
                type.interfaces
                    .flatMap { name -> all.filter { it.type == name } }
                    .flatMap { api -> api.methods }
                    .filter { it.parameterTypes.isEmpty() && it.returnType == "V" }
            asked.size == 2 &&
                type.fields.count { it.accessFlags and STATIC == 0 && it.type == ACTIVITY } == 1 &&
                type.methods.count {
                    it.parameterTypes.map(Any::toString) == listOf("I", "Z") && it.returnType == "V"
                } == 1
        }
    check(gates.size == 1) { "The fullscreen player is shown in ${gates.size} places" }
    check(orienters.size == 1) { "The orientation requests are in ${orienters.size} classes" }
    val orienter = orienters.single()
    val request =
        orienter.methods.single {
            it.parameterTypes.map(Any::toString) == listOf("I", "Z") && it.returnType == "V"
        }
    check(request.accessFlags and STATIC == 0 && orienter.accessFlags and 1 != 0) {
        "$FULLSCREEN_CHANGED: native orientation receiver access"
    }
    val requestBody = request.code()
    check(
        requestBody.any {
            it.methodRef()?.let { call ->
                call.definingClass == ACTIVITY &&
                    call.name == "getRequestedOrientation" &&
                    call.takes() &&
                    call.returnType == "I"
            } == true
        } &&
            requestBody.any {
                it.methodRef()?.let { call ->
                    call.definingClass == ACTIVITY &&
                        call.name == "setRequestedOrientation" &&
                        call.takes("I")
                } == true
            } &&
            requestBody.any {
                it.methodRef()?.let { call ->
                    call.definingClass == "Landroid/view/OrientationEventListener;" &&
                        call.name == "disable" &&
                        call.takes()
                } == true
            }
    ) {
        "$FULLSCREEN_CHANGED: native orientation bookkeeping"
    }
    val portraits =
        orienter.methods.filter {
            it.accessFlags and STATIC == 0 &&
                it.accessFlags and 1 != 0 &&
                it.parameterTypes.isEmpty() &&
                it.returnType == "V" &&
                requestLiterals(it.code(), request) == (1 to 0)
        }
    check(portraits.size == 1) { "$FULLSCREEN_CHANGED: native portrait request" }
    val portrait = portraits.single()
    check(
        portraitRequestReceiver(portrait.code(), portrait.implementation!!.registerCount, request)
    ) {
        "$FULLSCREEN_CHANGED: native portrait receiver ownership"
    }
    val asks =
        orienter.methods
            .filter { it.parameterTypes.isEmpty() && it.returnType == "V" && it.name != "<init>" }
            .mapNotNull { requestLiterals(it.code(), request) }
    check(asks.toSet() == setOf(6 to 1, 1 to 0)) {
        "The window requests are not landscape and portrait"
    }
    val site =
        orientationSite(
            request.code(),
            request.implementation!!.registerCount,
            orienter.fields.single { it.type == ACTIVITY },
        )
    val (type, method, gate) = gates.single()
    val handlers =
        all.flatMap { owner ->
            owner.methods.filter { it.isBackHandler() }.map { owner.type to it }
        }
    check(handlers.size == 1) { "The back handler is found in ${handlers.size} places" }
    return FullscreenPlan(
        type,
        method,
        gate,
        orienter,
        request,
        site,
        handlers.single(),
        locateFullscreenFrame(all),
        portrait,
    )
}

internal fun BytecodePatchContext.installVideoFullscreen() {
    val all = mutableListOf<ClassDef>()
    classDefForEach { all += it }
    val (type, found, gate, orienter, request, site, handler, frame, portrait) =
        locateVideoFullscreen(all)
    val target = mutableClassDefBy(type).methods.single { it.sameAs(found) }
    val body = target.instructionList()
    val hooked = body.count { it.methodRef()?.definingClass == FULLSCREEN }
    if (hooked == 0) {
        val elseAt = branchTargets(body).second[gate.at]!!
        // Later site first, so the earlier index stays where it was found.
        mutableClassDefBy(orienter.type)
            .methods
            .single { it.sameAs(request) }
            .addInstruction(
                site.at,
                "invoke-static {v${site.controller}, v${site.activity}, v${site.landscape}}, " +
                    "$FULLSCREEN->requested(Ljava/lang/Object;Landroid/app/Activity;Z)V",
            )
        mutableClassDefBy(FULLSCREEN)
            .methods
            .single { it.name == "portrait" }
            .addInstructions(
                0,
                """
                check-cast p0, ${orienter.type}
                invoke-virtual/range {p0 .. p0}, ${portrait.text()}
            """,
            )
        mutableClassDefBy(FULLSCREEN)
            .methods
            .single { it.name == "hostBack" }
            .addInstruction(
                0,
                "invoke-static/range {p0 .. p4}, ${handler.first}->BackHandler" +
                    "(ZLkotlin/jvm/functions/Function0;$COMPOSER" +
                    "II)V",
            )
        target.addInstructionsWithLabels(
            gate.at,
            """
            invoke-static/range {v${gate.landscape} .. v${gate.landscape}}, $FULLSCREEN->show(Z)Z
            move-result v${gate.free}
            if-eqz v${gate.free}, :fullscreen_else
            """,
            ExternalLabel("fullscreen_else", body[elseAt]),
        )
        // The host's own landscape test is replaced by the one above; the video test stays.
        target.replaceInstruction(gate.at + 3, "nop")
        mutableClassDefBy(frame.first.definingClass)
            .methods
            .single { it.sameAs(frame.first) }
            .addInstruction(
                frame.second.at,
                "invoke-static {v${frame.second.overlay}, v${frame.second.composer}}, " +
                    "$FULLSCREEN->bind(Lkotlin/jvm/functions/Function0;$COMPOSER)V",
            )
    }
    println("Added the window fullscreen state")
}

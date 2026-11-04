package app.bitchord.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.*
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.*

private const val KARAOKE = "Lapp/bitchord/telegram/KaraokeLyrics;"
private const val PLAYER_UI = "Lcom/music/bitchord/ui/player/"
private const val LYRICS_UI = "Lcom/music/bitchord/ui/player/PlayerLyricsKt;"
private const val COMPOSER = "Landroidx/compose/runtime/ComposerImpl;"
private const val ALIGNMENT = "Landroidx/compose/ui/Alignment\$Companion;"
private const val LAMBDA = "Landroidx/compose/runtime/internal/ComposableLambdaImpl;"
private const val HAZE_STATE = "Ldev/chrisbanes/haze/HazeState;"
private const val HAZE_STYLE = "Ldev/chrisbanes/haze/HazeStyle;"
private const val MODIFIER = "Landroidx/compose/ui/Modifier;"
private const val MODIFIER_COMPANION = "Landroidx/compose/ui/Modifier\$Companion;"
private const val COLOR = "Landroidx/compose/ui/graphics/Color;"
private const val FUNCTION1 = "Lkotlin/jvm/functions/Function1;"
private const val SIZE_KT = "Landroidx/compose/foundation/layout/SizeKt;"
private const val OFFSET_KT = "Landroidx/compose/foundation/layout/OffsetKt;"
private const val INT_OFFSET = "Landroidx/compose/ui/unit/IntOffset;"
private const val STATIC = 8

private fun Instruction.call() = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.field() = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Method.code() = implementation?.instructions?.toList().orEmpty()

private fun Instruction.callsKaraoke() = call()?.definingClass == KARAOKE

private fun Instruction.isEnd() =
    opcode == Opcode.INVOKE_VIRTUAL &&
        call()?.let {
            it.definingClass == COMPOSER && it.name == "end" && it.parameterTypes == listOf("Z")
        } == true

private fun MethodReference.text() =
    "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun FieldReference.text() = "$definingClass->$name:$type"

private fun Method.shape(vararg parameters: String, returns: String) =
    accessFlags and STATIC != 0 &&
        this.returnType == returns &&
        parameterTypes.map { it.toString() } == parameters.toList()

/**
 * The Karaoke capsule is the last child of the box that holds the lyrics in the portrait layout,
 * and a child of the translation slot in the landscape pane's bottom row, where it is moved beside
 * the translate button and the status line leaves room for it. Both places, and every Compose and
 * BitChord helper the capsule calls, are found from what the host does there, not from the names R8
 * gave its lambdas or merged file classes; the extension is handed what was found.
 */
internal fun BytecodePatchContext.addKaraokeLyrics() {
    val all = mutableListOf<ClassDef>()
    classDefForEach { all += it }
    val portrait = mutableListOf<Pair<String, Method>>()
    val landscape = mutableListOf<Pair<String, Method>>()
    for (clazz in all) {
        if (!clazz.type.startsWith(PLAYER_UI)) continue
        for (method in clazz.methods) {
            val code = method.code()
            if (code.isEmpty()) continue
            val buttons =
                code.any {
                    it.call()?.let { c ->
                        c.definingClass == LYRICS_UI && c.name == "TranslationToggleButton"
                    } == true
                }
            val bottomEnd =
                code.any {
                    it.field()?.let { f ->
                        f.definingClass == ALIGNMENT && f.name == "BottomEnd"
                    } == true
                }
            if (buttons && bottomEnd) portrait += clazz.type to method
            val status =
                code.any {
                    it.call()?.let { c ->
                        c.definingClass == LYRICS_UI && c.name == "LyricsStatusWithChange"
                    } == true
                }
            val topStart =
                code.any {
                    it.field()?.let { f -> f.definingClass == ALIGNMENT && f.name == "TopStart" } ==
                        true
                }
            if (status && topStart) landscape += clazz.type to method
        }
    }
    check(portrait.size == 1 && landscape.size == 1) {
        "Karaoke lyrics layouts found (${portrait.size} portrait, ${landscape.size} landscape)"
    }
    val symbols = resolveKaraokeSymbols(all, portrait.single().second)
    for ((type, found) in portrait) {
        val owner = mutableClassDefBy(type).methods.single { it.matches(found) }
        if (hookPortrait(owner, symbols)) {
            println("Added Karaoke to the portrait lyrics")
            if (symbols.containsKey("hazeEffect")) addPlayerHaze(owner)
        }
    }
    for ((type, found) in landscape) {
        val owner = mutableClassDefBy(type).methods.single { it.matches(found) }
        if (hookLandscape(owner)) println("Added Karaoke to the landscape lyrics")
    }
    installKaraokeSymbols(symbols)
}

private fun Method.matches(other: Method) =
    name == other.name && parameterTypes == other.parameterTypes && returnType == other.returnType

/** One distinct reference, or a message saying what no longer holds. */
private fun <T> Collection<T>.exactly(what: String): T {
    check(size == 1) { "$what ($size found)" }
    return first()
}

/**
 * Binds the helpers the capsule calls by what they take and give, and by how the host's own lyrics
 * code uses them. The blur group is all or nothing; the rest are required.
 */
private fun resolveKaraokeSymbols(
    all: List<ClassDef>,
    portrait: Method,
): MutableMap<String, String> {
    val symbols = linkedMapOf<String, String>()
    val code = portrait.code()
    val statics = all.flatMap { c -> c.methods.filter { it.accessFlags and STATIC != 0 } }

    symbols["androidView"] =
        statics
            .filter { it.shape(FUNCTION1, MODIFIER, FUNCTION1, COMPOSER, "I", "I", returns = "V") }
            .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})V" }
            .exactly("The Compose view-interop entry")

    // The landscape slot is sized and moved with Compose's own layout modifiers, found by shape.
    symbols["requiredSize"] =
        statics
            .filter {
                it.definingClass == SIZE_KT &&
                    it.name.startsWith("requiredSizeIn-") &&
                    it.name.endsWith("\$default") &&
                    it.shape(MODIFIER, "F", "F", "F", "F", "I", returns = MODIFIER)
            }
            .map {
                "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})$MODIFIER"
            }
            .exactly("The required-size modifier")
    symbols["offsetBy"] =
        statics
            .filter {
                it.definingClass == OFFSET_KT &&
                    it.name == "offset" &&
                    it.shape(MODIFIER, FUNCTION1, returns = MODIFIER)
            }
            .map {
                "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})$MODIFIER"
            }
            .exactly("The lambda offset modifier")
    symbols["intOffset"] =
        all.filter { it.type == INT_OFFSET }
            .exactly("The packed offset class")
            .methods
            .filter {
                it.name == "<init>" && it.parameterTypes.map { p -> p.toString() } == listOf("J")
            }
            .map { "$INT_OFFSET-><init>(J)V" }
            .exactly("The packed offset constructor")

    symbols["padding"] =
        code
            .mapNotNull { it.call() }
            .filter {
                it.parameterTypes.map { p -> p.toString() } ==
                    listOf(MODIFIER, "F", "F", "F", "F", "I") && it.returnType == MODIFIER
            }
            .map { it.text() }
            .toSet()
            .exactly("The padding helper the lyrics box uses")

    val modifierType =
        code
            .mapNotNull { it.field() }
            .filter { it.type == MODIFIER_COMPANION }
            .map { it.text() }
            .toSet()
            .exactly("The Modifier companion the lyrics box reads")
    symbols["startGroup"] =
        code
            .mapNotNull { it.call() }
            .filter {
                it.definingClass == COMPOSER &&
                    it.name == "startReplaceGroup" &&
                    it.parameterTypes.map { p -> p.toString() } == listOf("I")
            }
            .map { it.text() }
            .toSet()
            .exactly("The composer's replaceable group start")
    symbols["endGroup"] =
        code
            .mapNotNull { it.call() }
            .filter {
                it.definingClass == COMPOSER &&
                    it.name == "endReplaceGroup" &&
                    it.parameterTypes.isEmpty()
            }
            .map { it.text() }
            .toSet()
            .exactly("The composer's replaceable group end")
    symbols["companion"] = modifierType

    val bottomEnd =
        code.indices.firstOrNull { i ->
            code[i].field()?.let { it.definingClass == ALIGNMENT && it.name == "BottomEnd" } == true
        } ?: error("The lyrics box's BottomEnd alignment is missing")
    symbols["alignment"] = code[bottomEnd].field()!!.text()

    // The scope's own align() shows which element a bottom-end child is wrapped in.
    val align =
        code
            .mapNotNull { it.call() }
            .filter { it.name == "align" && it.parameterTypes.size == 2 }
            .toSet()
            .map { it.definingClass to it.parameterTypes.map { p -> p.toString() } }
            .toSet()
            .exactly("The lyrics box's align")
    val scope =
        all.firstOrNull { it.type == align.first } ?: error("The lyrics box scope is missing")
    val element =
        scope.methods
            .filter {
                it.name == "align" && it.parameterTypes.map { p -> p.toString() } == align.second
            }
            .exactly("The lyrics box scope's align")
            .code()
            .mapNotNull { it.call() }
            .filter {
                it.name == "<init>" &&
                    it.parameterTypes.map { p -> p.toString() } == listOf(align.second[1], "Z")
            }
            .map { "${it.definingClass}-><init>(${it.parameterTypes.joinToString("")})V" }
            .exactly("The bottom-end element of the lyrics box")
    symbols["childData"] = element

    // The translation button shows how the host tints itself: a colour local read there.
    val button =
        all.filter { it.type == LYRICS_UI }
            .exactly("The lyrics file class")
            .methods
            .filter { it.name == "TranslationToggleButton" }
            .exactly("The translation button")
    val buttonCode = button.code()
    // The offsets are measured from a translate button that is a 34 dp disc.
    check(
        buttonCode.indices.count { i ->
            buttonCode[i].call()?.let { c ->
                c.definingClass == SIZE_KT &&
                    c.name.startsWith("size-") &&
                    c.parameterTypes.map { p -> p.toString() } == listOf(MODIFIER, "F")
            } == true &&
                (buttonCode[i] as? FiveRegisterInstruction)?.let {
                    buttonCode.floatBefore(i, it.registerD) == 34f
                } == true
        } == 1
    ) {
        "The translate button is no longer a 34 dp disc"
    }
    val tints =
        buttonCode.indices
            .filter { i ->
                buttonCode[i].call()?.let {
                    it.definingClass == COMPOSER && it.name == "consume"
                } == true &&
                    (i + 1..i + 3).any { j ->
                        ((buttonCode.getOrNull(j) as? ReferenceInstruction)?.reference
                                as? TypeReference)
                            ?.type == COLOR
                    }
            }
            .mapNotNull { i ->
                val local =
                    (i - 3 until i)
                        .mapNotNull { buttonCode[it].field() }
                        .lastOrNull { f -> f.type.endsWith("CompositionLocal;") }
                local?.let { it.text() to buttonCode[i].call()!!.text() }
            }
            .toSet()
    check(tints.size <= 1) {
        "The player tint the lyrics button reads is ambiguous (${tints.size})"
    }
    if (tints.isNotEmpty()) {
        symbols["tint"] = tints.first().first
        // The composer's own consume, with the parameter type it really takes.
        symbols["consume"] = tints.first().second
    } else println("Karaoke: this BitChord has no player tint local; the capsule uses white")

    // The host's own circle button shows the clip helper and its shape.
    val hazeCandidates =
        statics.filter { it.shape(MODIFIER, HAZE_STATE, HAZE_STYLE, FUNCTION1, returns = MODIFIER) }
    // BitChord wraps the library's effect; the wrapper is the one that calls the other.
    val hazeEffect =
        hazeCandidates
            .filter { outer ->
                hazeCandidates.size == 1 ||
                    outer.code().any { step ->
                        step.call()?.let { c ->
                            hazeCandidates.any { other ->
                                other !== outer &&
                                    other.definingClass == c.definingClass &&
                                    other.name == c.name
                            }
                        } == true
                    }
            }
            .map {
                "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})$MODIFIER"
            }
    val frost =
        statics
            .filter { it.shape("F", "J", returns = HAZE_STYLE) }
            .map { "${it.definingClass}->${it.name}(FJ)$HAZE_STYLE" }
    val group = listOf(hazeEffect.size, frost.size)
    if (group.all { it == 0 }) {
        println(
            "Karaoke: no frosted-glass helpers in this BitChord; the capsule is translucent only"
        )
        return symbols
    }
    check(group.all { it == 1 }) {
        "The frosted-glass helpers are partly missing or ambiguous (haze, style: $group)"
    }
    symbols["hazeEffect"] = hazeEffect.single()
    symbols["frost"] = frost.single()
    val classes = all.associateBy { it.type }
    val drawings =
        all.mapNotNull { element ->
                val constructor =
                    element.methods.singleOrNull {
                        it.parameterTypes.map { p -> p.toString() } == listOf(FUNCTION1) &&
                            it.returnType == "V" &&
                            it.name == "<init>"
                    } ?: return@mapNotNull null
                val create =
                    element.methods.singleOrNull {
                        it.parameterTypes.isEmpty() &&
                            it.returnType == "Landroidx/compose/ui/Modifier\$Node;" &&
                            it.accessFlags and STATIC == 0
                    } ?: return@mapNotNull null
                val child =
                    create
                        .code()
                        .mapNotNull {
                            if (it.opcode == Opcode.NEW_INSTANCE)
                                ((it as ReferenceInstruction).reference as? TypeReference)?.type
                            else null
                        }
                        .singleOrNull()
                        ?.let { classes[it] } ?: return@mapNotNull null
                val fields = child.fields.filter { it.accessFlags and STATIC == 0 }
                if (fields.size != 1 || fields.single().type != FUNCTION1) return@mapNotNull null
                val draw =
                    child.methods.singleOrNull { method ->
                        method.parameterTypes.size == 1 &&
                            method.returnType == "V" &&
                            method.accessFlags and STATIC == 0 &&
                            method.code().map { it.opcode } ==
                                listOf(
                                    Opcode.IGET_OBJECT,
                                    Opcode.INVOKE_INTERFACE,
                                    Opcode.RETURN_VOID,
                                ) &&
                            method.code().count {
                                it.call()?.let { c ->
                                    c.definingClass == FUNCTION1 && c.name == "invoke"
                                } == true
                            } == 1 &&
                            method.code().none { it.call()?.name == "drawContent" }
                    } ?: return@mapNotNull null
                val callbackScope =
                    classes[draw.parameterTypes.single().toString()] ?: return@mapNotNull null
                if (
                    callbackScope.methods.none {
                        it.name == "drawContent" &&
                            it.parameterTypes.isEmpty() &&
                            it.returnType == "V"
                    } ||
                        callbackScope.methods.none {
                            it.name == "getDrawContext" &&
                                it.parameterTypes.isEmpty() &&
                                it.accessFlags and STATIC == 0
                        }
                )
                    return@mapNotNull null
                constructor to draw
            }
            .exactly("The content-only drawing modifier")
    val drawingScope =
        classes[drawings.second.parameterTypes.single().toString()]
            ?: error("The content drawing scope is missing")
    val drawContent =
        drawingScope.methods
            .filter {
                it.name == "drawContent" &&
                    it.parameterTypes.isEmpty() &&
                    it.returnType == "V" &&
                    it.accessFlags and STATIC == 0
            }
            .exactly("The native draw-content operation")
    val context =
        drawingScope.methods
            .filter {
                it.name == "getDrawContext" &&
                    it.parameterTypes.isEmpty() &&
                    it.accessFlags and STATIC == 0
            }
            .exactly("The native drawing context")
    val canvas =
        classes[context.returnType]
            ?.methods
            ?.toList()
            .orEmpty()
            .filter {
                it.parameterTypes.isEmpty() &&
                    it.accessFlags and STATIC == 0 &&
                    it.returnType == "Landroidx/compose/ui/graphics/Canvas;"
            }
            .exactly("The drawing context's canvas")
    val nativeCanvas =
        statics
            .filter { it.shape(canvas.returnType, returns = "Landroid/graphics/Canvas;") }
            .exactly("The native drawing canvas adapter")
    symbols["drawElement"] = drawings.first.text()
    symbols["drawContent"] = drawContent.text()
    symbols["drawContext"] = context.text()
    symbols["contextCanvas"] = canvas.text()
    symbols["nativeCanvas"] = nativeCanvas.text()
    return symbols
}

/** Hands the resolved references to the extension, which binds them once. */
private fun BytecodePatchContext.installKaraokeSymbols(symbols: Map<String, String>) {
    val method =
        mutableClassDefBy(KARAOKE).methods.single {
            it.name == "symbols" &&
                it.parameterTypes.isEmpty() &&
                it.returnType == "Ljava/lang/String;"
        }
    val first = method.code().firstOrNull()
    if (
        first?.opcode == Opcode.CONST_STRING &&
            ((first as ReferenceInstruction).reference as StringReference).string.isNotEmpty()
    )
        return
    val table = symbols.entries.joinToString("\\n") { (key, value) -> "$key=$value" }
    method.addInstructions(0, "const-string v0, \"$table\"\nreturn-object v0")
}

private fun Instruction.writes(register: Int): Boolean {
    val name = opcode.name
    if (
        listOf("if-", "iput", "sput", "aput", "return", "monitor", "throw", "invoke").any {
            name.startsWith(it)
        }
    )
        return false
    return (this as? OneRegisterInstruction)?.registerA == register
}

/**
 * The capsule follows the lyrics panel, not the host's translate buttons: those fade away with the
 * lyrics controls, and a control that vanished with them could not be found while reading. The hook
 * sits right after the panel's motion wrapper, where the host tests whether the lyrics are on
 * screen, and is told that answer.
 */
private fun hookPortrait(method: MutableMethod, symbols: MutableMap<String, String>): Boolean {
    val code = method.code()
    if (code.any { it.callsKaraoke() }) return false
    val motions =
        code.indices.filter {
            code[it].call()?.let { c ->
                c.definingClass == LYRICS_UI && c.name == "LyricsTranslationMotion"
            } == true
        }
    check(motions.size == 1) { "The lyrics panel's motion call changed (${motions.size})" }
    val motion = motions.single()
    val call =
        code[motion] as? RegisterRangeInstruction ?: error("The lyrics motion call changed shape")
    check(call.registerCount == 6) {
        "The lyrics motion call takes ${call.registerCount} registers"
    }
    val slot = call.startRegister + 4
    val move =
        (motion - 1 downTo maxOf(0, motion - 12)).firstOrNull {
            code[it].opcode.name.startsWith("move-object") &&
                (code[it] as TwoRegisterInstruction).registerA == slot
        } ?: error("The lyrics motion call's composer is not copied from a register")
    val composer = (code[move] as TwoRegisterInstruction).registerB
    check((move + 1 until motion).none { code[it].writes(composer) }) {
        "The composer register changes before the lyrics motion call"
    }
    val gate = code[motion + 1]
    check(gate.opcode == Opcode.IF_EQZ) { "The lyrics-visible test no longer follows the panel" }
    val visible = (gate as OneRegisterInstruction).registerA
    check(composer < 16 && visible < 16) { "Lyrics composer or visibility register out of range" }
    // The share-picking state is read right after: while lines are being picked the bar owns the
    // foot of the lyrics, as it does for the host's own buttons.
    val pick =
        (motion + 1 until code.size).firstOrNull {
            code[it].call()?.let { c ->
                c.name == "getPicking" && c.parameterTypes.isEmpty() && c.returnType == "Z"
            } == true
        }
    if (pick != null) {
        val reader = code[pick] as FiveRegisterInstruction
        val picker = reader.registerC
        check(picker < 16 && (motion + 1 until pick).none { code[it].writes(picker) }) {
            "The share-picking state is not reachable at the lyrics panel"
        }
        symbols["picking"] = code[pick].call()!!.text()
        method.addInstruction(
            motion + 1,
            "invoke-static {v$composer, v$visible, v$picker}, " +
                "$KARAOKE->portrait(Ljava/lang/Object;ZLjava/lang/Object;)V",
        )
    } else {
        println("Karaoke: no share-picking state beside the lyrics panel")
        method.addInstruction(
            motion + 1,
            "invoke-static {v$composer, v$visible}, $KARAOKE->portrait(Ljava/lang/Object;Z)V",
        )
    }
    return true
}

/**
 * The whole-number float in [register] just before [at]: a small constant run through int-to-float.
 */
private fun List<Instruction>.floatBefore(at: Int, register: Int): Float? {
    val convert = (at - 1 downTo 0).firstOrNull { this[it].writes(register) } ?: return null
    if (this[convert].opcode != Opcode.INT_TO_FLOAT) return null
    val source = (this[convert] as TwoRegisterInstruction).registerB
    val load = (convert - 1 downTo 0).firstOrNull { this[it].writes(source) } ?: return null
    if (this[load].opcode != Opcode.CONST_4 && this[load].opcode != Opcode.CONST_16) return null
    return (this[load] as NarrowLiteralInstruction).narrowLiteral.toFloat()
}

/**
 * The bottom row of the landscape pane holds a 34 dp slot at each end of a status line padded 12 dp
 * a side, centred vertically. The capsule goes in the translation slot, which the row centres on
 * the translate button's own line whatever height the status line gives the row, and the status
 * line's padding grows by the room the capsule's target takes. The translate button is itself 34
 * dp, which the offsets rely on. Returns false when this method already carries the hook.
 */
private fun hookLandscape(method: MutableMethod): Boolean {
    val code = method.code()
    if (code.any { it.callsKaraoke() }) {
        check(
            code.count {
                it.call()?.let { c ->
                    c.definingClass == KARAOKE && c.name == "landscapeReserve"
                } == true
            } == 1
        ) {
            "An older Karaoke layout is already patched; start from a clean BitChord APK"
        }
        return false
    }
    val statuses =
        code.indices.filter {
            code[it].call()?.let { c ->
                c.definingClass == LYRICS_UI && c.name == "LyricsStatusWithChange"
            } == true
        }
    check(statuses.size == 1) { "The landscape status line changed (${statuses.size})" }
    val status = statuses.single()
    val slots =
        code.indices.filter {
            code[it].call()?.let { c ->
                c.definingClass == SIZE_KT &&
                    c.name.startsWith("size-") &&
                    c.parameterTypes.map { p -> p.toString() } == listOf(MODIFIER, "F")
            } == true
        }
    check(slots.size == 2 && slots[0] < status && slots[1] > status) {
        "The landscape row no longer has one slot either side of the status line (${slots.size})"
    }
    for (slot in slots) check(
        (code[slot] as? FiveRegisterInstruction)?.let {
            code.floatBefore(slot, it.registerD) == 34f
        } == true
    ) {
        "A landscape row slot is no longer a 34 dp call with short argument registers"
    }
    val paddings =
        code.indices.filter {
            it in slots[0] until status &&
                code[it].call()?.let { c ->
                    c.definingClass == OFFSET_KT &&
                        c.name.startsWith("padding-") &&
                        c.parameterTypes.map { p -> p.toString() } ==
                            listOf("F", "F", "I", MODIFIER)
                } == true
        }
    check(paddings.size == 1) { "The status line's side padding changed (${paddings.size})" }
    val padding = paddings.single()
    check(code[padding] is FiveRegisterInstruction) {
        "The landscape padding argument layout changed"
    }
    val side = (code[padding] as FiveRegisterInstruction).registerC
    check(code.floatBefore(padding, side) == 12f && side < 16) {
        "The status line is no longer padded 12 dp a side"
    }
    val convert = (padding - 1 downTo 0).first { code[it].writes(side) }
    // The translation slot's content is the next composable slot invoked after the status line.
    val content =
        (status until code.size).firstOrNull { i ->
            code[i].call()?.let {
                it.definingClass == LAMBDA &&
                    it.name == "invoke" &&
                    it.parameterTypes == listOf("Ljava/lang/Object;", "Ljava/lang/Object;")
            } == true
        } ?: error("The landscape translation slot is missing")
    val gotos = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)
    val first =
        (content until code.size).firstOrNull { code[it].opcode in gotos }
            ?: error("The landscape translation slot no longer jumps to the slot's end")
    val close = (code[first] as BuilderOffsetInstruction).target.location.index
    check(code[close].isEnd()) {
        "The landscape translation slot no longer closes where the branches meet"
    }
    // Branches keep their label on the closing call, so the hook goes before the jump into it.
    val jumps =
        code.indices.filter {
            code[it].opcode in gotos &&
                (code[it] as BuilderOffsetInstruction).target.location.index == close
        }
    val entries =
        code.indices.filter {
            (code[it] as? BuilderOffsetInstruction)?.target?.location?.index == close
        }
    check(jumps.size == 1 && entries == jumps && code[close - 1].opcode in gotos) {
        "The landscape translation slot has a branch that could bypass Karaoke"
    }
    val composer = (code[close] as FiveRegisterInstruction).registerC
    check(composer < 16) { "Lyrics composer register out of range" }
    // The later place first, so the earlier index still holds.
    method.addInstruction(
        jumps.single(),
        "invoke-static {v$composer}, $KARAOKE->landscape(Ljava/lang/Object;)V",
    )
    method.addInstructions(
        convert + 1,
        "invoke-static {v$side}, $KARAOKE->landscapeReserve(F)F\nmove-result v$side",
    )
    return true
}

/**
 * The player screen takes the frosted-glass state it marks its backdrop with as a parameter, so the
 * capsule blurs that backdrop, not whatever state was made last. The one parameter of that type is
 * reported at the start of the screen's lambda.
 */
private fun addPlayerHaze(method: MutableMethod) {
    val slots = method.parameterTypes.map { if (it == "J" || it == "D") 2 else 1 }
    val first = if (method.accessFlags and STATIC != 0) 0 else 1
    val owners = method.parameterTypes.indices.filter { method.parameterTypes[it] == HAZE_STATE }
    check(owners.size == 1) { "The player screen has ${owners.size} frosted-glass states" }
    check(
        method.code().any { step ->
            step.call()?.let {
                it.parameterTypes.map { p -> p.toString() }.take(2) ==
                    listOf(MODIFIER, HAZE_STATE) && it.returnType == MODIFIER
            } == true
        }
    ) {
        "The player screen no longer marks its backdrop as the frost source"
    }
    val register = first + slots.take(owners.single()).sum()
    method.addInstruction(
        0,
        "invoke-static/range {p$register .. p$register}, $KARAOKE->haze(Ljava/lang/Object;)V",
    )
}

package app.bitchord.patches

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.reference.Reference
import com.android.tools.smali.dexlib2.immutable.instruction.*
import com.android.tools.smali.dexlib2.immutable.reference.*

private const val MOD = "Landroidx/compose/ui/Modifier;"
private const val F1 = "Lkotlin/jvm/functions/Function1;"
private const val CMP = "Landroidx/compose/runtime/ComposerImpl;"

private var passed = 0

private fun method(owner: String, name: String, returns: String, vararg parameters: String) =
    ImmutableMethodReference(owner, name, parameters.toList(), returns)

private fun call(opcode: Opcode, ref: Reference, vararg registers: Int): Instruction {
    val r = registers.toList() + List(5 - registers.size) { 0 }
    return ImmutableInstruction35c(opcode, registers.size, r[0], r[1], r[2], r[3], r[4], ref)
}

private fun result(register: Int) = ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, register)

private fun fill(register: Int) =
    call(
        Opcode.INVOKE_STATIC,
        method("Landroidx/compose/foundation/layout/SizeKt;", "fillMaxWidth\$default", MOD, MOD),
        register,
    )

private fun offset(a: Int, b: Int) =
    call(
        Opcode.INVOKE_STATIC,
        method("Landroidx/compose/foundation/layout/OffsetKt;", "offset", MOD, MOD, F1),
        a,
        b,
    )

private fun height(a: Int, b: Int) =
    call(
        Opcode.INVOKE_STATIC,
        method("Landroidx/compose/foundation/layout/SizeKt;", "height-3ABfNKs", MOD, MOD, "F"),
        a,
        b,
    )

private fun placed(a: Int, b: Int) =
    call(
        Opcode.INVOKE_STATIC,
        method("Landroidx/compose/ui/layout/RulerKt;", "onGloballyPositioned", MOD, MOD, F1),
        a,
        b,
    )

private fun row(a: Int, b: Int, composer: Int, d: Int) =
    call(
        Opcode.INVOKE_STATIC,
        method(
            "Landroidx/compose/foundation/layout/RowKt;",
            "rowMeasurePolicy",
            "Ljava/lang/Object;",
            "Ljava/lang/Object;",
            "Ljava/lang/Object;",
            CMP,
            "I",
        ),
        a,
        b,
        composer,
        d,
    )

private fun start(composer: Int) =
    call(Opcode.INVOKE_VIRTUAL, method(CMP, "startReusableNode", "V"), composer)

private fun nop(register: Int = 5) = ImmutableInstruction11x(Opcode.MOVE_RESULT, register)

private fun write(register: Int) = ImmutableInstruction12x(Opcode.MOVE_OBJECT, register, 2)

/** A jump from [from] to [to] in [code], with the offset worked out from the real code units. */
private fun jumpTo(code: List<Instruction>, from: Int, to: Int): Instruction {
    val units = { range: IntRange -> range.sumOf { code[it].codeUnits } }
    val offset = if (to > from) units(from until to) else -units(to until from)
    return ImmutableInstruction10t(Opcode.GOTO, offset)
}

private fun title(vararg extra: Pair<Int, Instruction>, repeat: Int = 1): List<Instruction> {
    val one =
        listOf(
            fill(12),
            result(0),
            offset(0, 4),
            result(0),
            nop(1),
            height(0, 2),
            result(0),
            placed(0, 4),
            result(0),
            row(7, 4, 15, 9),
            result(4),
            start(15),
        )
    val code = MutableList(0) { ImmutableInstruction10x(Opcode.RETURN_VOID) as Instruction }
    repeat(repeat) { code += one }
    code += ImmutableInstruction10x(Opcode.RETURN_VOID)
    for ((at, step) in extra) code.add(at, step)
    return code
}

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

private fun stringIn(register: Int, text: String) =
    ImmutableInstruction21c(Opcode.CONST_STRING, register, ImmutableStringReference(text))

private fun square(vararg extra: Pair<Int, Instruction>, artwork: Int = 1): List<Instruction> {
    val landscape =
        method(
            "Lcom/music/bitchord/ui/player/LandscapePlayerKt;",
            "LandscapeArtwork",
            "V",
            "Ljava/lang/Object;",
        )
    val code =
        mutableListOf<Instruction>(
            stringIn(3, "artworkModifier"),
            call(
                Opcode.INVOKE_STATIC,
                method(
                    "Lkotlin/jvm/internal/Intrinsics;",
                    "checkNotNullParameter",
                    "V",
                    "Ljava/lang/Object;",
                    "Ljava/lang/String;",
                ),
                1,
                3,
            ),
            call(Opcode.INVOKE_VIRTUAL, method(CMP, "shouldExecute", "Z", "I", "Z"), 14, 6, 5),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 2),
            ImmutableInstruction21t(Opcode.IF_EQZ, 2, 0),
            nop(8),
            call(
                Opcode.INVOKE_INTERFACE,
                method("Landroidx/compose/ui/Modifier;", "then", MOD, MOD),
                1,
                7,
            ),
            result(1),
        )
    repeat(artwork) { code += call(Opcode.INVOKE_STATIC, landscape, 1) }
    code += ImmutableInstruction10t(Opcode.GOTO, 1)
    code += ImmutableInstruction10x(Opcode.RETURN_VOID)
    for ((at, step) in extra) code.add(at, step)
    // The skip test jumps over everything to the final return.
    val end = code.size - 1
    val gate = code.indexOfFirst { it.opcode == Opcode.IF_EQZ }
    code[gate] =
        ImmutableInstruction21t(
            Opcode.IF_EQZ,
            2,
            (gate + 1 until end).sumOf { code[it].codeUnits } + 2,
        )
    return code
}

private fun stats(vararg after: Instruction): List<Instruction> {
    val stats =
        method(
            "Lcom/music/bitchord/ui/player/PlayerControlsKt;",
            "SleeveNerdStats",
            "V",
            "Ljava/lang/Object;",
            MOD,
            CMP,
            "I",
        )
    return listOf(
        ImmutableInstruction21c(
            Opcode.SGET_OBJECT,
            9,
            ImmutableFieldReference(
                "Landroidx/compose/ui/Alignment\$Companion;",
                "BottomCenter",
                "Ljava/lang/Object;",
            ),
        ),
        call(
            Opcode.INVOKE_VIRTUAL,
            method(
                "Landroidx/compose/foundation/layout/BoxScopeInstance;",
                "align",
                MOD,
                MOD,
                "Landroidx/compose/ui/Alignment;",
            ),
            3,
            7,
            9,
        ),
        result(3),
        call(
            Opcode.INVOKE_STATIC,
            method(
                "Landroidx/compose/foundation/layout/OffsetKt;",
                "padding-VpY3zN4",
                MOD,
                MOD,
                "F",
                "F",
            ),
            3,
            2,
            5,
        ),
        result(2),
        call(
            Opcode.INVOKE_STATIC,
            method("Landroidx/compose/ui/graphics/ColorKt;", "graphicsLayer", MOD, MOD, F1),
            2,
            5,
        ),
        result(2),
        call(Opcode.INVOKE_STATIC, stats, 0, 2, 10, 8),
        *after,
        ImmutableInstruction10t(Opcode.GOTO, 1),
        ImmutableInstruction10x(Opcode.RETURN_VOID),
    )
}

fun main() {
    // The title row: found, and refused whenever the host's shape or registers do not hold.
    val base = title()
    val anchor = titleAnchor(base)
    holds(
        "the icon goes right after the offset's result",
        anchor.insert == 4 && base[anchor.insert - 1].opcode == Opcode.MOVE_RESULT_OBJECT,
    )
    holds("the registers are the row's own", anchor.title == 0 && anchor.composer == 15)

    fails("jump into the insertion point", "branch target") {
        val code = title().toMutableList()
        code.add(code.size - 1, ImmutableInstruction10t(Opcode.GOTO, 0))
        code[code.size - 2] = jumpTo(code, code.size - 2, 4)
        titleAnchor(code)
    }
    fails("composer rewritten before the row", "composer register changes") {
        titleAnchor(title(5 to write(15)))
    }
    fails("title modifier replaced before it is read", "replaced before") {
        titleAnchor(title(4 to ImmutableInstruction12x(Opcode.MOVE_OBJECT, 0, 2)))
    }
    fails("two title rows in one method", "2 places") { titleAnchor(title(repeat = 2)) }
    fails("no title row", "0 places") {
        titleAnchor(
            listOf(fill(12), result(0), height(0, 2), ImmutableInstruction10x(Opcode.RETURN_VOID))
        )
    }
    fails("registers out of reach of a plain invoke", "out of reach") {
        // A valid range invoke whose composer argument sits in v16.
        val code = title().toMutableList()
        val policy = (code[9] as ImmutableInstruction35c).reference
        code[9] = ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE, 14, 4, policy)
        titleAnchor(code)
    }
    holds(
        "a row whose measure policy comes before its height is not the title row",
        titleChains(
                listOf(
                    fill(12),
                    result(0),
                    offset(0, 4),
                    result(0),
                    row(7, 4, 15, 9),
                    height(0, 2),
                    placed(0, 4),
                    start(15),
                )
            )
            .isEmpty(),
    )

    // The landscape artwork lambda.
    val artwork = square()
    val landscape = squareAnchor(artwork)
    holds(
        "the icon starts the executed branch",
        landscape.insert == 5 && landscape.modifier == 1 && landscape.composer == 14,
    )
    fails("jump into the executed branch", "jump target") {
        val code = square().toMutableList()
        code[code.size - 2] = jumpTo(code, code.size - 2, 5)
        squareAnchor(code)
    }
    fails("composer rewritten before the branch", "changes before the icon") {
        squareAnchor(square(2 to write(14)))
    }
    fails("modifier rewritten before the branch", "changes before the icon") {
        squareAnchor(square(2 to write(1)))
    }
    fails("modifier replaced before it is used", "replaced before") {
        val code = square().toMutableList()
        code[6] = result(1)
        code[7] = ImmutableInstruction12x(Opcode.MOVE_OBJECT, 1, 7)
        squareAnchor(code)
    }
    fails("artwork placed twice", "2 places") { squareAnchor(square(artwork = 2)) }
    fails("no skip test", "0 skip tests") {
        val code = square().toMutableList()
        code[2] = nop(8)
        squareAnchor(code)
    }

    // The stats slot: the scaled lambda the editor used to live in is no longer a valid site.
    val site = statsSite(stats(), 7)
    holds("the stats site is found", site != null && site.modifier == 2 && site.composer == 10)
    holds(
        "an old install that left the editor after the stats is not a fresh site",
        statsSite(
            stats(
                call(
                    Opcode.INVOKE_STATIC,
                    method(
                        "Lapp/bitchord/telegram/PlayerIcons;",
                        "editor",
                        "V",
                        "Ljava/lang/Object;",
                    ),
                    10,
                )
            ),
            7,
        ) == null,
    )
    holds(
        "a stats call without the bottom-centre slot is not the sleeve",
        statsSite(stats().drop(1), 6) == null,
    )

    // What an earlier install left behind.
    holds("nothing installed", IconCounts().needsInstall())
    holds("everything installed", !IconCounts(1, 1, 1, 1, 1, 1, 0, 0, 1, 1).needsInstall())
    fails("the old editor inside the sleeve", "inside the sleeve") {
        IconCounts(1, 1, 0, 0, 1, 1, 2, 0, 1).needsInstall()
    }
    fails("half installed", "partly installed") {
        IconCounts(1, 1, 1, 0, 1, 1, 0, 0, 1).needsInstall()
    }
    fails("a title row hooked twice", "partly installed") {
        IconCounts(1, 1, 2, 1, 1, 1, 0, 0, 1).needsInstall()
    }
    fails("the pair left in the portrait column", "partly installed") {
        IconCounts(1, 1, 1, 1, 1, 1, 0, 1, 1).needsInstall()
    }
    val caption =
        listOf(
            ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("text")),
            ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("onClick")),
            ImmutableInstruction21c(
                Opcode.SGET_OBJECT,
                0,
                ImmutableFieldReference(
                    "Landroidx/compose/material3/Typography;",
                    "labelSmall",
                    "Landroidx/compose/ui/text/TextStyle;",
                ),
            ),
            ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, 5, 29),
            ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, 8, 31),
            ImmutableInstruction3rc(
                Opcode.INVOKE_STATIC_RANGE,
                1,
                6,
                method(
                    "Lnative/Merged;",
                    "clickable-oSLSa3U\$default",
                    MOD,
                    MOD,
                    "Z",
                    "Ljava/lang/String;",
                    "Landroidx/compose/ui/semantics/Role;",
                    "Lkotlin/jvm/functions/Function0;",
                    "I",
                ),
            ),
            result(2),
            ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, 23, 1),
            call(
                Opcode.INVOKE_STATIC,
                method(
                    "Lnative/Merged;",
                    "padding",
                    MOD,
                    MOD,
                    "Landroidx/compose/foundation/layout/PaddingValues;",
                ),
                2,
                8,
            ),
            result(1),
            ImmutableInstruction3rc(
                Opcode.INVOKE_STATIC_RANGE,
                0,
                2,
                method(
                    "Landroidx/compose/material3/TextKt;",
                    "Text-native",
                    "V",
                    "Ljava/lang/String;",
                    MOD,
                ),
            ),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
    val captionSite = originCaptionSite(caption, 36)
    holds(
        "caption wraps click result before padding",
        captionSite.at == 8 && captionSite.modifier == 2,
    )
    val inplace =
        caption.toMutableList().apply {
            add(5, ImmutableInstruction12x(Opcode.MOVE_OBJECT, 12, 1))
            this[7] = result(1)
            this[8] = ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, 23, 12)
            this[9] =
                call(
                    Opcode.INVOKE_STATIC,
                    method(
                        "Lnative/Merged;",
                        "padding",
                        MOD,
                        MOD,
                        "Landroidx/compose/foundation/layout/PaddingValues;",
                    ),
                    1,
                    8,
                )
        }
    holds(
        "caption accepts an in-place click result with its original modifier saved",
        originCaptionSite(inplace, 36).modifier == 1,
    )
    fails("caption callback replaced", "ownership") {
        originCaptionSite(
            caption.toMutableList().apply {
                this[3] = ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, 5, 30)
            },
            36,
        )
    }
    fails("caption padding replaced", "ownership") {
        originCaptionSite(
            caption.toMutableList().apply {
                this[4] = ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, 8, 32)
            },
            36,
        )
    }
    fails("caption click result overwritten", "ownership") {
        originCaptionSite(
            caption.toMutableList().apply { add(7, ImmutableInstruction11n(Opcode.CONST_4, 2, 0)) },
            36,
        )
    }
    fails("caption style changed", "text style") {
        originCaptionSite(caption.toMutableList().apply { removeAt(2) }, 36)
    }
    fails("caption text renderer changed", "text rendering") {
        originCaptionSite(caption.toMutableList().apply { removeAt(10) }, 36)
    }
    fails("caption modifier missing from installed icons", "partly installed") {
        IconCounts(1, 1, 1, 1, 1, 1, 0, 0, 1).needsInstall()
    }
    fullscreenChecks()
    println("PASS: $passed player icon anchor checks")
}

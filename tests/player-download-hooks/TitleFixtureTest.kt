package app.bitchord.patches

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.instruction.*
import com.android.tools.smali.dexlib2.immutable.reference.*

private const val CMP = "Landroidx/compose/runtime/ComposerImpl;"
private const val MOD = "Landroidx/compose/ui/Modifier;"
private const val SONG = "Lcom/music/bitchord/data/model/Song;"
private val circle =
    ImmutableMethodReference("Lnative/Controls;", "round", emptyList<String>(), "V")
private var passed = 0

private fun call(owner: String, name: String, result: String, vararg types: String) =
    ImmutableMethodReference(owner, name, types.toList(), result)

private fun field(owner: String, name: String, type: String) =
    ImmutableInstruction21c(Opcode.SGET_OBJECT, 0, ImmutableFieldReference(owner, name, type))

private fun fixture(composer: Int = 15, circleComposer: Int = 49): List<Instruction> {
    val body =
        listOf(
                ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC,
                    2,
                    composer,
                    11,
                    0,
                    0,
                    0,
                    call("Lnative/Layout;", "Spacer", "V", CMP, MOD),
                ),
                ImmutableInstruction21t(Opcode.IF_NEZ, 104, 0),
                ImmutableInstruction3rc(
                    Opcode.INVOKE_VIRTUAL_RANGE,
                    96,
                    1,
                    call(SONG, "getLocalUri", "Ljava/lang/String;"),
                ),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 11),
                ImmutableInstruction21t(Opcode.IF_EQZ, 11, 0),
                ImmutableInstruction10t(Opcode.GOTO, 0),
                ImmutableInstruction10x(Opcode.NOP),
                ImmutableInstruction10t(Opcode.GOTO, 0),
                field(
                    "Lcom/music/bitchord/data/model/LikeStatus;",
                    "LIKE",
                    "Lcom/music/bitchord/data/model/LikeStatus;",
                ),
                field(
                    "Lnative/Strings;",
                    "remove_from_liked\$delegate",
                    "Lkotlin/SynchronizedLazyImpl;",
                ),
                ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, circleComposer, composer),
                ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE, circleComposer - 6, 9, circle),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            )
            .toMutableList<Instruction>()
    fun units(from: Int, to: Int) = (from until to).sumOf { body[it].codeUnits }
    body[1] = ImmutableInstruction21t(Opcode.IF_NEZ, 104, units(1, 8))
    body[4] = ImmutableInstruction21t(Opcode.IF_EQZ, 11, units(4, 6))
    body[5] = ImmutableInstruction10t(Opcode.GOTO, units(5, 8))
    body[7] = ImmutableInstruction10t(Opcode.GOTO, units(7, 12))
    return body
}

private fun holds(name: String, value: Boolean) {
    check(value) { name }
    passed++
}

private fun refuses(name: String, code: List<Instruction>) {
    try {
        playerDownloadTitleAnchor(code, circle)
        error("Accepted changed $name")
    } catch (expected: IllegalStateException) {
        check(expected.message.orEmpty().startsWith("Player title download layout changed"))
        passed++
    }
}

fun main() {
    val base = fixture()
    holds("before authentication condition", playerDownloadTitleAnchor(base, circle) == Pair(1, 15))
    holds(
        "different compositor register",
        playerDownloadTitleAnchor(fixture(14, 50), circle) == Pair(1, 14),
    )
    holds("no range-copy needed", playerDownloadTitleAnchor(fixture(15, 15), circle) == Pair(1, 15))
    holds(
        "an installed entry keeps the same anchor",
        playerDownloadTitleAnchor(
            base.toMutableList().apply {
                add(
                    1,
                    ImmutableInstruction3rc(
                        Opcode.INVOKE_STATIC_RANGE,
                        15,
                        1,
                        call(
                            "Lapp/bitchord/telegram/PlayerDownload;",
                            "compose",
                            "V",
                            "Ljava/lang/Object;",
                        ),
                    ),
                )
            },
            circle,
        ) == Pair(1, 15),
    )
    refuses(
        "authentication branch",
        base.toMutableList().apply { this[1] = ImmutableInstruction21t(Opcode.IF_EQZ, 104, 0) },
    )
    refuses(
        "local favorite branch",
        base.toMutableList().apply { this[4] = ImmutableInstruction21t(Opcode.IF_NEZ, 11, 0) },
    )
    refuses(
        "native title spacer",
        base.toMutableList().apply { this[0] = ImmutableInstruction10x(Opcode.NOP) },
    )
    refuses(
        "like action",
        base.toMutableList().apply {
            this[8] = field("Lnative/Other;", "LIKE", "Ljava/lang/Object;")
        },
    )
    refuses(
        "unlike action",
        base.toMutableList().apply {
            this[9] = field("Lnative/Strings;", "share\$delegate", "Ljava/lang/Object;")
        },
    )
    refuses(
        "different circle composer",
        base.toMutableList().apply {
            this[10] = ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, 49, 14)
        },
    )
    refuses(
        "branch bypasses action",
        base.toMutableList().apply {
            add(0, ImmutableInstruction10t(Opcode.GOTO, 1 + this[0].codeUnits))
        },
    )
    println("Player download title guards: $passed passed")
}

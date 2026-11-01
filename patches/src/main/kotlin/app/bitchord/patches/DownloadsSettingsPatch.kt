package app.bitchord.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/** Adds the shared controls after the host's existing downloaded-playlist setting. */
internal fun BytecodePatchContext.addDownloadsSettingsRow() {
    val candidates = mutableListOf<Pair<String, String>>()
    classDefForEach { type ->
        for (method in type.methods) {
            val code = method.implementation?.instructions?.toList() ?: continue
            if (
                code.any {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string ==
                        "setSyncDownloadedPlaylists(Z)V"
                }
            )
                candidates.add(type.type to method.name)
        }
    }
    check(candidates.size == 1) { "Downloads settings callback is not unique: ${candidates.size}" }
    val (owner, name) = candidates.single()
    val method =
        mutableClassDefBy(owner).methods.single { it.name == name && it.implementation != null }
    val code = method.implementation!!.instructions.toList()
    val anchor =
        code.indices.single {
            ((code[it] as? ReferenceInstruction)?.reference as? StringReference)?.string ==
                "setSyncDownloadedPlaylists(Z)V"
        }
    val row =
        ((anchor + 1) until minOf(anchor + 64, code.size)).firstOrNull {
            val call = (code[it] as? ReferenceInstruction)?.reference as? MethodReference
            call?.definingClass == "Lcom/music/bitchord/ui/screens/SettingsSheetKt;" &&
                call.name == "SettingsSubRow"
        }
    check(row != null) { "Downloaded-playlist settings row changed" }
    val call = (code[row] as ReferenceInstruction).reference as MethodReference
    check(
        call.returnType == "V" &&
            call.parameterTypes ==
                listOf(
                    "Ljava/lang/String;",
                    "Z",
                    "Lkotlin/jvm/functions/Function1;",
                    "Ljava/lang/String;",
                    "Ljava/lang/String;",
                    "Z",
                    "Landroidx/compose/runtime/ComposerImpl;",
                    "I",
                    "I",
                )
    ) {
        "Downloads settings row signature changed"
    }
    val composer =
        when (val instruction = code[row]) {
            is com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction ->
                instruction.startRegister + 6
            else -> error("Downloads settings compositor registers changed")
        }
    method.addInstruction(
        row + 1,
        "invoke-static/range {v$composer .. v$composer}, Lapp/bitchord/telegram/DownloadsSettingsUi;->row(Landroidx/compose/runtime/ComposerImpl;)V",
    )
}

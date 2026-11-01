package app.bitchord.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.*
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** Downloads keep the complete catalogue; playback and its search omit prepared-file providers. */
internal fun BytecodePatchContext.addFlacDownloadOnly() {
    val registry = "Lcom/music/bitchord/data/sources/SourceRegistry;"
    val helper = "Lapp/bitchord/telegram/FlacAddon;"
    check(
        classDefBy("Lcom/music/bitchord/data/sources/MusicSource;").methods.any {
            it.name == "getConfigId" &&
                it.parameterTypes.isEmpty() &&
                it.returnType == "Ljava/lang/String;"
        }
    ) {
        "Download-only source identity changed"
    }
    val playback =
        mutableClassDefBy(registry).methods.singleOrNull {
            it.name == "activeForPlayback" &&
                it.parameterTypes.isEmpty() &&
                it.returnType == "Ljava/util/ArrayList;"
        }
    check(playback != null && playback.accessFlags and 8 != 0) {
        "Playback source catalogue ABI changed"
    }
    val code = playback.implementation!!.instructions.toList()
    check(
        code.any {
            val call = (it as? ReferenceInstruction)?.reference as? MethodReference
            call?.definingClass == registry && call.name == "active"
        }
    ) {
        "Playback catalogue no longer starts from enabled sources"
    }
    val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_OBJECT }
    check(returns.isNotEmpty()) { "Playback source catalogue has no result" }
    for (index in returns.reversed()) {
        val register = (code[index] as OneRegisterInstruction).registerA
        playback.addInstructions(
            index,
            """
            invoke-static/range {v$register .. v$register}, $helper->playbackSources(Ljava/util/ArrayList;)Ljava/util/ArrayList;
            move-result-object v$register
        """,
        )
    }
    val submit =
        mutableClassDefBy("Lcom/music/bitchord/ui/MainViewModel;").methods.singleOrNull {
            it.name == "submitSearch" && it.parameterTypes.isEmpty() && it.returnType == "V"
        }
    check(submit != null) { "Submitted add-on search entry point changed" }
    val searchCode = submit.implementation!!.instructions.toList()
    val walks =
        searchCode.indices.filter {
            val call = (searchCode[it] as? ReferenceInstruction)?.reference as? MethodReference
            call?.definingClass == registry &&
                call.name == "active" &&
                call.parameterTypes.isEmpty() &&
                call.returnType == "Ljava/util/ArrayList;"
        }
    check(walks.size == 1) { "Submitted add-on search catalogue changed" }
    val result = walks.single() + 1
    check(searchCode[result].opcode == Opcode.MOVE_RESULT_OBJECT) {
        "Submitted add-on search result changed"
    }
    val register = (searchCode[result] as OneRegisterInstruction).registerA
    submit.addInstructions(
        result + 1,
        """
        invoke-static/range {v$register .. v$register}, $helper->playbackSources(Ljava/util/ArrayList;)Ljava/util/ArrayList;
        move-result-object v$register
    """,
    )
}

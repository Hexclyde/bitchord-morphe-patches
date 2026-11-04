package app.bitchord.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.*
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.*

/** Exact source and filename identity, independent of any optional transfer runtime. */
internal val selectedDownloadsPatch = bytecodePatch {
    execute {
        addDownloadFolder()
        val songType = "Lcom/music/bitchord/data/model/Song;"
        val selection = "Lapp/bitchord/telegram/DownloadSelection;"
        val fileName =
            mutableClassDefBy("Lcom/music/bitchord/download/DownloadStore;").methods.single {
                it.name == "fileNameFor" &&
                    it.parameterTypes == listOf(songType, "Ljava/lang/String;") &&
                    it.returnType == "Ljava/lang/String;"
            }
        check(fileName.accessFlags and 8 != 0 && fileName.implementation!!.registerCount <= 16) {
            "Download filename ABI changed"
        }
        val fileNameCode = fileName.implementation!!.instructions.toList()
        for (index in fileNameCode.indices.reversed()) {
            if (fileNameCode[index].opcode != Opcode.RETURN_OBJECT) continue
            val result =
                (fileNameCode[index]
                        as com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction)
                    .registerA
            fileName.addInstructions(
                index,
                """
                    invoke-static {v$result}, $selection->fileNameFinish(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$result
                """,
            )
        }
        fileName.addInstructions(
            0,
            "invoke-static {p0}, $selection->fileNameStart(Ljava/lang/Object;)V",
        )
        val fallback =
            mutableClassDefBy("Lcom/music/bitchord/download/Downloads;").methods.single {
                it.name == "fromSources"
            }
        check(
            fallback.parameterTypes.first() == songType &&
                fallback.returnType == "Ljava/lang/Object;"
        ) {
            "Download fallback ABI changed"
        }
        val originalFallback = fallback.implementation!!.instructions.first()
        fallback.addInstructionsWithLabels(
            0,
            """
                    invoke-static/range {p1 .. p1}, $selection->forbidFallback(Ljava/lang/Object;)Z
                    move-result v0
                    if-eqz v0, :unselected_download_fallback
                    const/4 v0, 0x0
                    return-object v0
                """,
            ExternalLabel("unselected_download_fallback", originalFallback),
        )

        val enqueue =
            mutableClassDefBy("Lcom/music/bitchord/download/Downloads;").methods.single {
                it.name == "enqueue" &&
                    it.parameterTypes ==
                        listOf("Landroid/content/Context;", songType, "Ljava/lang/String;") &&
                    it.returnType == "V"
            }
        check(enqueue.accessFlags and 8 != 0 && enqueue.implementation!!.registerCount >= 3) {
            "Download admission ABI changed"
        }
        enqueue.addInstructions(
            0,
            "invoke-static/range {p0 .. p2}, $selection->remember(Landroid/content/Context;Ljava/lang/Object;Ljava/lang/String;)V",
        )
    }
}

internal fun BytecodePatchContext.validateSmartDownloadTransfer() {
    val downloads = classDefBy("Lcom/music/bitchord/download/Downloads;")
    val operations =
        downloads.methods.filter {
            (it.name == "downloadNow" || it.name.startsWith("downloadNow$")) &&
                it.parameterTypes ==
                    listOf(
                        "Landroid/content/Context;",
                        "Ljava/util/ArrayList;",
                        "Ljava/lang/String;",
                        "Lkotlin/coroutines/jvm/internal/ContinuationImpl;",
                    ) &&
                it.returnType == "Ljava/io/Serializable;" &&
                it.accessFlags and 8 == 0
        }
    check(operations.size == 1) { "Scheduled download coroutine ABI changed" }
    check(
        downloads.fields.any {
            it.name == "INSTANCE" && it.type == downloads.type && it.accessFlags and 8 != 0
        }
    ) {
        "Scheduled download owner changed"
    }
    check(
        classDefBy("Lkotlinx/coroutines/JobImpl;").methods.any {
            it.name == "<init>" && it.parameterTypes == listOf("Lkotlinx/coroutines/Job;")
        }
    ) {
        "Scheduled download coroutine scope changed"
    }
    check(
        classDefBy("Lkotlinx/coroutines/Job;").methods.any {
            it.name == "cancel" &&
                it.returnType == "V" &&
                it.parameterTypes == listOf("Ljava/util/concurrent/CancellationException;")
        }
    ) {
        "Scheduled download cancellation ABI changed"
    }
    check(
        classDefBy("Lcom/music/bitchord/data/sources/MusicSource;").methods.any {
            it.name == "getConfigId" &&
                it.parameterTypes.isEmpty() &&
                it.returnType == "Ljava/lang/String;"
        }
    ) {
        "Scheduled download source identity changed"
    }
}

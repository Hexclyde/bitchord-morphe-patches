package app.bitchord.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.*

private const val FOLDER = "Lapp/bitchord/telegram/DownloadFolder;"
private const val RESOLVER = "Landroid/content/ContentResolver;"
private const val URI = "Landroid/net/Uri;"
private const val CONTEXT = "Landroid/content/Context;"
private const val VALUES = "Landroid/content/ContentValues;"
private const val TEXT = "Ljava/lang/String;"
private const val STORE = "Lcom/music/bitchord/download/DownloadStore;"
private const val CHANGED = "Download folder storage ABI changed"

private fun Instruction.folderCall() =
    (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.folderField() =
    (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Method.folderCode() = implementation?.instructions?.toList().orEmpty()

private fun Instruction.folderRegisters(): List<Int> =
    when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is FiveRegisterInstruction ->
            listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> error("$CHANGED: invocation registers")
    }

private fun MethodReference.folderSignature(
    name: String,
    parameters: List<String>,
    result: String,
) = this.name == name && parameterTypes.map { it.toString() } == parameters && returnType == result

private fun folderTarget(code: List<Instruction>, index: Int): Int {
    val offset = code[index] as? OffsetInstruction ?: error("$CHANGED: progressive branch encoding")
    val target = code.take(index).sumOf { it.codeUnits } + offset.codeOffset
    var address = 0
    for (position in code.indices) {
        if (address == target) return position
        address += code[position].codeUnits
    }
    error("$CHANGED: progressive branch target")
}

private fun Instruction.folderWrites(register: Int): Boolean =
    opcode.setsRegister() &&
        this is OneRegisterInstruction &&
        (registerA == register || (opcode.setsWideRegister() && registerA + 1 == register))

internal data class DownloadFolderSites(
    val existing: Method,
    val transfer: Method,
    val cleanup: Method,
    val savedDelete: Method,
    val pending: String,
)

internal fun locateDownloadFolder(all: List<ClassDef>): DownloadFolderSites {
    val methods = all.flatMap { it.methods }
    val existing =
        methods
            .filter {
                it.definingClass == STORE &&
                    it.folderSignature("existing", listOf(CONTEXT, TEXT), URI)
            }
            .singleOrNull() ?: error("$CHANGED: existing-file lookup")
    check(existing.accessFlags and 8 != 0 && existing.implementation!!.registerCount in 3..16) {
        "$CHANGED: lookup scratch register"
    }
    val first = existing.folderCode().first()
    check(first.opcode == Opcode.CONST_STRING && (first as OneRegisterInstruction).registerA == 0) {
        "$CHANGED: lookup scratch lifetime"
    }
    val settings =
        all.singleOrNull { it.type == "Lcom/music/bitchord/data/settings/AppSettings;" }
            ?: error("$CHANGED: settings owner")
    val policy =
        settings.fields.singleOrNull { it.name == "exportDownloads" && it.accessFlags and 9 == 9 }
            ?: error("$CHANGED: exported storage policy")
    check(
        all.singleOrNull { it.type == policy.type }
            ?.methods
            ?.count {
                it.name == "getValue" &&
                    it.parameterTypes.isEmpty() &&
                    it.returnType == "Ljava/lang/Object;" &&
                    it.accessFlags and 1 != 0 &&
                    it.accessFlags and 8 == 0
            } == 1
    ) {
        "$CHANGED: storage policy reader"
    }
    val session =
        all.singleOrNull { it.type == "Lcom/music/bitchord/download/DownloadSession;" }
            ?: error("$CHANGED: queued request session")
    check(
        session.fields.count {
            it.name == "_state" && it.accessFlags and 8 != 0 && it.type == policy.type
        } == 1
    ) {
        "$CHANGED: queued request state"
    }
    val referenced =
        session.methods
            .flatMap { it.folderCode() }
            .mapNotNull { (it as? ReferenceInstruction)?.reference }
            .mapNotNull {
                when (it) {
                    is TypeReference -> it.type
                    is MethodReference -> it.definingClass
                    is FieldReference -> it.definingClass
                    else -> null
                }
            }
            .toSet()
    fun field(type: ClassDef, name: String, value: String) =
        type.fields.count { it.name == name && it.type == value && it.accessFlags and 8 == 0 } == 1
    check(
        all.count {
            it.type in referenced &&
                field(it, "items", "Ljava/util/List;") &&
                field(it, "seenAt", "J") &&
                field(it, "settledAt", "J")
        } == 1
    ) {
        "$CHANGED: queued request list"
    }
    check(
        all.count {
            it.type in referenced &&
                field(it, "videoId", TEXT) &&
                field(it, "song", "Lcom/music/bitchord/data/model/Song;") &&
                field(it, "from", TEXT) &&
                field(it, "sequence", "J")
        } == 1
    ) {
        "$CHANGED: queued request metadata"
    }
    check(
        all.single { it.type == "Lcom/music/bitchord/download/Downloads;" }
            .fields
            .count { it.name == "_saved" && it.type == policy.type && it.accessFlags and 8 != 0 } ==
            1
    ) {
        "$CHANGED: saved request catalogue"
    }
    val signature = listOf(CONTEXT, URI, TEXT, "Ljava/io/File;", "Ljava/io/File;")
    val constructor =
        methods.filter { it.folderSignature("<init>", signature, "V") }.singleOrNull()
            ?: error("$CHANGED: pending-write constructor")
    val pending = constructor.definingClass
    val parameterBase = constructor.implementation!!.registerCount - 5
    check(
        constructor
            .folderCode()
            .filter { it.opcode == Opcode.IPUT_OBJECT }
            .all {
                val write = it as TwoRegisterInstruction
                write.registerA in parameterBase until parameterBase + 5 &&
                    write.registerB == parameterBase - 1 &&
                    it.folderField()?.type == signature[write.registerA - parameterBase]
            }
    ) {
        "$CHANGED: pending-write argument identity"
    }
    val writes =
        constructor
            .folderCode()
            .mapNotNull { it.folderField() }
            .filter { it.definingClass == pending }
    check(writes.size == 5 && writes.map { it.type }.sorted() == signature.sorted()) {
        "$CHANGED: pending-write fields"
    }
    val transfer =
        methods
            .filter { method ->
                val code = method.folderCode()
                code.count {
                    it.folderCall()?.let { c ->
                        c.definingClass == RESOLVER && c.name == "insert"
                    } == true
                } == 1 &&
                    code.any {
                        it.folderCall()?.let { c ->
                            c.definingClass == STORE && c.name == "fileNameFor"
                        } == true
                    } &&
                    code.any {
                        it.folderCall()?.let { c ->
                            c.definingClass == pending && c.name == "<init>"
                        } == true
                    }
            }
            .singleOrNull() ?: error("$CHANGED: progressive transfer")
    val code = transfer.folderCode()
    val filenameCalls =
        methods.flatMap { method ->
            val body = method.folderCode()
            body.indices
                .filter {
                    body[it].folderCall()?.let { c ->
                        c.definingClass == STORE && c.name == "fileNameFor"
                    } == true
                }
                .map { body to it }
        }
    check(filenameCalls.size == 2) { "$CHANGED: filename lookup callers" }
    for ((body, index) in filenameCalls) {
        check(
            body[index + 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
                body[index + 2].folderCall()?.let {
                    it.definingClass == STORE &&
                        it.folderSignature("existing", listOf(CONTEXT, TEXT), URI)
                } == true &&
                body[index + 2].folderRegisters()[1] ==
                    (body[index + 1] as OneRegisterInstruction).registerA
        ) {
            "$CHANGED: filename and lookup must remain adjacent"
        }
    }
    val filename =
        code.indices.single {
            code[it].folderCall()?.let { c ->
                c.definingClass == STORE && c.name == "fileNameFor"
            } == true
        }
    check(
        code[filename + 3].opcode == Opcode.MOVE_RESULT_OBJECT &&
            code[filename + 4].opcode == Opcode.IF_EQZ &&
            (code[filename + 3] as OneRegisterInstruction).registerA ==
                (code[filename + 4] as OneRegisterInstruction).registerA
    ) {
        "$CHANGED: already-downloaded branch"
    }
    val manifest = folderTarget(code, filename + 4)
    check(code[manifest].opcode == Opcode.IF_EQZ) { "$CHANGED: progressive manifest branch" }
    val manifestRegister = (code[manifest] as OneRegisterInstruction).registerA
    val manifestRead =
        (0 until filename).lastOrNull {
            code[it].opcode == Opcode.IGET_OBJECT &&
                (code[it] as OneRegisterInstruction).registerA == manifestRegister &&
                code[it].folderField()?.let { f ->
                    f.definingClass == "Lcom/music/bitchord/download/Downloads\$Route;" &&
                        f.name == "offlineHls"
                } == true
        } ?: error("$CHANGED: manifest identity")
    check(((manifestRead + 1) until manifest).none { code[it].folderWrites(manifestRegister) }) {
        "$CHANGED: manifest register lifetime"
    }
    val progressive = folderTarget(code, manifest)
    val insertion =
        code.indices.single {
            code[it].folderCall()?.let { c -> c.definingClass == RESOLVER && c.name == "insert" } ==
                true
        }
    check(
        progressive in (manifest + 1) until insertion &&
            (progressive until insertion).none { i ->
                code[i].folderCall()?.let { c ->
                    c.parameterTypes.any { it.toString().startsWith("Lkotlin/coroutines/") } ||
                        (c.name == "invoke" && c.definingClass.startsWith("Lkotlin/jvm/functions/"))
                } == true
            }
    ) {
        "$CHANGED: destination capture crosses a suspension"
    }
    fun calls(name: String, parameters: List<String>, result: String) =
        code.indices.filter {
            code[it].folderCall()?.let { c ->
                c.definingClass == RESOLVER && c.folderSignature(name, parameters, result)
            } == true
        }
    check(
        calls("insert", listOf(URI, VALUES), URI).size == 1 &&
            calls("openOutputStream", listOf(URI), "Ljava/io/OutputStream;").size == 1
    ) {
        "$CHANGED: destination creation and stream"
    }
    val update =
        calls("update", listOf(URI, VALUES, TEXT, "[Ljava/lang/String;"), "I").singleOrNull()
            ?: error("$CHANGED: completion publication")
    check(code[update + 1].opcode != Opcode.MOVE_RESULT) {
        "$CHANGED: completion result is consumed"
    }
    val completion = code[update].folderRegisters()
    val clearing = code[update + 1] as? TwoRegisterInstruction
    val nil = code[update - 1]
    check(
        completion[3] == completion[4] &&
            completion[1] != completion[3] &&
            nil.opcode == Opcode.CONST_4 &&
            (nil as OneRegisterInstruction).registerA == completion[3] &&
            (nil as NarrowLiteralInstruction).narrowLiteral == 0 &&
            code[update + 1].opcode == Opcode.IPUT_OBJECT &&
            clearing?.registerA == completion[3] &&
            code[update + 1].folderField()?.let {
                it.definingClass == "Lkotlin/jvm/internal/Ref\$ObjectRef;" &&
                    it.name == "element" &&
                    it.type == "Ljava/lang/Object;"
            } == true
    ) {
        "$CHANGED: completed pending destination is cleared"
    }
    val uri = code[update].folderRegisters()[1]
    val remember =
        ((update + 1)..minOf(update + 8, code.lastIndex)).singleOrNull { i ->
            code[i].folderCall()?.let { c ->
                c.definingClass == "Lcom/music/bitchord/download/Downloads;" && c.name == "remember"
            } == true
        } ?: error("$CHANGED: saved-file publication")
    check(code[remember].folderRegisters().getOrNull(2) == uri) {
        "$CHANGED: completed URI identity"
    }
    check(
        code.subList(update + 1, remember).none {
            it is OneRegisterInstruction && it.registerA == uri
        }
    ) {
        "$CHANGED: completed URI is overwritten"
    }
    val cleanup =
        methods
            .filter { method ->
                val body = method.folderCode()
                body.any {
                    it.folderField()?.let { f -> f.definingClass == pending && f.type == URI } ==
                        true
                } &&
                    body.any {
                        it.folderCall()?.let { c ->
                            c.definingClass == RESOLVER &&
                                c.folderSignature(
                                    "delete",
                                    listOf(URI, TEXT, "[Ljava/lang/String;"),
                                    "I",
                                )
                        } == true
                    }
            }
            .singleOrNull() ?: error("$CHANGED: interrupted-write cleanup")
    check(
        cleanup.folderCode().count {
            it.folderCall()?.let { c -> c.definingClass == RESOLVER && c.name == "delete" } == true
        } == 1
    ) {
        "$CHANGED: cleanup IO sites"
    }
    val savedDelete =
        methods
            .filter { method ->
                val body = method.folderCode()
                body.any {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string ==
                        "could not delete "
                } &&
                    body.any {
                        it.folderField()?.let { f ->
                            f.definingClass == "Lcom/music/bitchord/download/Downloads;" &&
                                f.name == "_saved"
                        } == true
                    } &&
                    body.count {
                        it.folderCall()?.let { c ->
                            c.definingClass == RESOLVER &&
                                c.folderSignature(
                                    "delete",
                                    listOf(URI, TEXT, "[Ljava/lang/String;"),
                                    "I",
                                )
                        } == true
                    } == 1
            }
            .singleOrNull() ?: error("$CHANGED: saved-file deletion")
    val deletion = savedDelete.folderCode()
    val remove =
        deletion.indexOfFirst {
            it.folderCall()?.let { c -> c.definingClass == RESOLVER && c.name == "delete" } == true
        }
    val forgets =
        deletion.indices.filter { i ->
            deletion[i].folderCall()?.let { c ->
                c.definingClass == "Lcom/music/bitchord/download/Downloads;" &&
                    c.folderSignature("forget", listOf(TEXT), "V")
            } == true
        }
    check(forgets.size == 2 && forgets[0] < remove && forgets[1] > remove) {
        "$CHANGED: missing-file cleanup and explicit deletion must remain separate"
    }
    val forget = forgets[1]
    check(forget >= 2 && forget + 1 < deletion.size && remove + 2 < deletion.size) {
        "$CHANGED: saved-file deletion result sites"
    }
    check(
        deletion[remove + 1].opcode == Opcode.MOVE_RESULT &&
            deletion[remove + 2].opcode == Opcode.IF_LEZ &&
            (deletion[remove + 1] as OneRegisterInstruction).registerA ==
                (deletion[remove + 2] as OneRegisterInstruction).registerA
    ) {
        "$CHANGED: saved-file deletion success comparison"
    }
    val cast = deletion[forget - 2]
    val singleton = deletion[forget - 1]
    val result = (cast as? OneRegisterInstruction)?.registerA
    val scratch = (singleton as? OneRegisterInstruction)?.registerA
    val done =
        if (deletion[forget + 1].opcode in listOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32))
            folderTarget(deletion, forget + 1)
        else -1
    check(
        cast.opcode == Opcode.CHECK_CAST &&
            ((cast as ReferenceInstruction).reference as? TypeReference)?.type ==
                "Ljava/lang/Boolean;" &&
            singleton.opcode == Opcode.SGET_OBJECT &&
            singleton.folderField()?.let {
                it.definingClass == "Lcom/music/bitchord/download/Downloads;" &&
                    it.name == "INSTANCE" &&
                    it.type == it.definingClass
            } == true &&
            result != null &&
            result in 0..15 &&
            scratch != null &&
            scratch in 0..15 &&
            result != scratch &&
            scratch !in deletion[forget].folderRegisters() &&
            done in deletion.indices &&
            deletion[done].opcode == Opcode.RETURN_OBJECT &&
            (deletion[done] as OneRegisterInstruction).registerA == result
    ) {
        "$CHANGED: saved-file deletion result and scratch lifetime"
    }
    for (method in listOf(existing, transfer)) {
        val body = method.folderCode()
        val field =
            body.indices
                .filter {
                    body[it].folderField()?.let { f ->
                        f.definingClass == "Lcom/music/bitchord/data/settings/AppSettings;" &&
                            f.name == "exportDownloads"
                    } == true
                }
                .singleOrNull() ?: error("$CHANGED: destination policy")
        check(
            body[field + 4].folderCall()?.let {
                it.definingClass == "Ljava/lang/Boolean;" &&
                    it.folderSignature("booleanValue", emptyList(), "Z")
            } == true && body[field + 5].opcode == Opcode.MOVE_RESULT
        ) {
            "$CHANGED: destination policy value"
        }
    }
    return DownloadFolderSites(existing, transfer, cleanup, savedDelete, pending)
}

internal fun BytecodePatchContext.addDownloadFolder() {
    val all = mutableListOf<ClassDef>()
    classDefForEach { all.add(it) }
    val sites = locateDownloadFolder(all)
    for (site in listOf(sites.existing, sites.transfer, sites.cleanup, sites.savedDelete)) {
        val method =
            mutableClassDefBy(site.definingClass).methods.single {
                it.name == site.name && it.parameterTypes == site.parameterTypes
            }
        val code = method.folderCode()
        for (i in code.indices.reversed()) {
            val call = code[i].folderCall()
            if (
                site == sites.savedDelete &&
                    call?.definingClass == "Lcom/music/bitchord/download/Downloads;" &&
                    call.name == "forget" &&
                    i > code.indexOfFirst { it.folderCall()?.definingClass == RESOLVER }
            ) {
                val result = (code[i - 2] as OneRegisterInstruction).registerA
                val scratch = (code[i - 1] as OneRegisterInstruction).registerA
                method.addInstructionsWithLabels(
                    i,
                    """
                    invoke-virtual {v$result}, Ljava/lang/Boolean;->booleanValue()Z
                    move-result v$scratch
                    if-eqz v$scratch, :delete_failed
                    """,
                    ExternalLabel(
                        "delete_failed",
                        method.implementation!!.instructions[folderTarget(code, i + 1)],
                    ),
                )
            }
            if (
                call?.definingClass == RESOLVER &&
                    ((site == sites.transfer &&
                        call.name in listOf("insert", "openOutputStream", "update")) ||
                        (site in listOf(sites.cleanup, sites.savedDelete) && call.name == "delete"))
            ) {
                val registers = code[i].folderRegisters()
                val name =
                    when (call.name) {
                        "openOutputStream" -> "open"
                        "update" -> "commit"
                        else -> call.name
                    }
                val result = if (call.name == "update") URI else call.returnType
                method.replaceInstruction(
                    i,
                    "invoke-static {${registers.joinToString(", ") { "v$it" }}}, $FOLDER->$name($RESOLVER${call.parameterTypes.joinToString("")})$result",
                )
                if (call.name == "update")
                    method.addInstructions(i + 1, "move-result-object v${registers[1]}")
            }
            if (
                code[i].folderField()?.let {
                    it.definingClass == "Lcom/music/bitchord/data/settings/AppSettings;" &&
                        it.name == "exportDownloads"
                } == true
            ) {
                val result = (code[i + 5] as OneRegisterInstruction).registerA
                method.addInstructions(
                    i + 6,
                    "invoke-static {v$result}, $FOLDER->export(Z)Z\nmove-result v$result",
                )
            }
        }
        if (site == sites.existing)
            method.addInstructionsWithLabels(
                0,
                """
            invoke-static {p1}, $FOLDER->custom(Ljava/lang/String;)Z
            move-result v0
            if-eqz v0, :native_folder_lookup
            invoke-static {p0, p1}, $FOLDER->existing(Landroid/content/Context;Ljava/lang/String;)Landroid/net/Uri;
            move-result-object v0
            return-object v0
        """,
                ExternalLabel("native_folder_lookup", method.implementation!!.instructions.first()),
            )
    }
}

package app.bitchord.patches

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.*
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

private const val RESTORE_ANALYSIS = "Lcom/music/bitchord/playback/smart/TrackAnalysis;"
private const val RESTORE_ANALYZER = "Lcom/music/bitchord/playback/smart/TrackAnalyzer;"
private const val RESTORE_TEXT = "Ljava/lang/String;"
private const val RESTORE_OBJECT = "Ljava/lang/Object;"
private const val RESTORE_MAP = "Ljava/util/concurrent/ConcurrentHashMap;"
private const val RESTORE_CHANGED = "Native analysis restore changed"

internal data class AnalysisRestoreSite(
    val type: ClassDef,
    val run: Method,
    val instruction: Int,
    val map: Int,
    val key: Int,
    val value: Int,
)

private fun Instruction.restoreCall() =
    (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.restoreField() =
    (this as? ReferenceInstruction)?.reference as? FieldReference

private fun MethodReference.restoreParameters() = parameterTypes.map { it.toString() }

private fun Instruction.restoreWrites(register: Int): Boolean {
    if (this !is OneRegisterInstruction) return false
    return (opcode.setsRegister() && registerA == register) ||
        (opcode.setsWideRegister() && (registerA == register || registerA + 1 == register))
}

private fun Instruction.restoreArguments(): List<Int> =
    when (this) {
        is FiveRegisterInstruction ->
            listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        else -> emptyList()
    }

private fun FieldReference.sameField(other: FieldReference) =
    definingClass == other.definingClass && name == other.name && type == other.type

private fun Instruction.restoreCapturedFrom(self: Int, field: FieldReference) =
    opcode == Opcode.IGET_OBJECT &&
        this is TwoRegisterInstruction &&
        registerB == self &&
        restoreField()?.sameField(field) == true

private fun restoreRequire(condition: Boolean, reason: String) {
    check(condition) { "$RESTORE_CHANGED: $reason" }
}

// Reads the field roles from the constructor: p1 stores the analyzer, p2 stores the track key.
private fun restoreCapture(type: ClassDef): Pair<FieldReference, FieldReference> {
    val constructors =
        type.methods.filter {
            it.name == "<init>" &&
                it.parameterTypes.map { p -> p.toString() } ==
                    listOf(RESTORE_ANALYZER, RESTORE_TEXT)
        }
    restoreRequire(constructors.size == 1, "constructor (${constructors.size} found)")
    val constructor = constructors.single()
    restoreRequire(
        constructor.accessFlags and AccessFlags.PUBLIC.value != 0 &&
            constructor.accessFlags and AccessFlags.STATIC.value == 0 &&
            constructor.returnType == "V" &&
            constructor.implementation != null,
        "constructor signature",
    )
    val code = constructor.implementation!!.instructions.toList()
    val registers = constructor.implementation!!.registerCount
    val analyzerRegister = registers - 2
    val keyRegister = registers - 1
    val stores = code.filter { it.opcode == Opcode.IPUT_OBJECT }
    restoreRequire(
        code.size == 4 &&
            code[0].restoreCall()?.let {
                it.definingClass == RESTORE_OBJECT && it.name == "<init>"
            } == true &&
            code[0].restoreArguments() == listOf(registers - 3) &&
            stores.size == 2 &&
            code[3].opcode == Opcode.RETURN_VOID,
        "constructor body",
    )
    fun storeOf(register: Int, fieldType: String): FieldReference {
        val field =
            stores
                .filter { (it as TwoRegisterInstruction).registerA == register }
                .map { it as TwoRegisterInstruction to it.restoreField()!! }
                .singleOrNull { (store, _) -> store.registerB == registers - 3 }
                ?.second
        restoreRequire(
            field != null && field.definingClass == type.type && field.type == fieldType,
            "constructor capture of $fieldType",
        )
        return field!!
    }
    val analyzer = storeOf(analyzerRegister, RESTORE_ANALYZER)
    val key = storeOf(keyRegister, RESTORE_TEXT)
    restoreRequire(
        !analyzer.sameField(key) &&
            type.instanceFields.count() == 2 &&
            type.instanceFields.map { Triple(it.definingClass, it.name, it.type) }.toSet() ==
                setOf(
                    Triple(analyzer.definingClass, analyzer.name, analyzer.type),
                    Triple(key.definingClass, key.name, key.type),
                ) &&
            type.staticFields.none(),
        "capture fields",
    )
    return analyzer to key
}

private fun restoreQualify(type: ClassDef): AnalysisRestoreSite {
    restoreRequire(
        type.accessFlags and AccessFlags.PUBLIC.value != 0 &&
            type.accessFlags and AccessFlags.ABSTRACT.value == 0,
        "runnable signature",
    )
    val (analyzerField, keyField) = restoreCapture(type)
    val runs = type.methods.filter { it.name == "run" && it.parameterTypes.isEmpty() }
    restoreRequire(runs.size == 1, "run method (${runs.size} found)")
    val run = runs.single()
    restoreRequire(
        run.returnType == "V" &&
            run.accessFlags and AccessFlags.PUBLIC.value != 0 &&
            run.accessFlags and AccessFlags.STATIC.value == 0 &&
            run.implementation != null &&
            type.methods.count() == 2,
        "run signature",
    )
    val code = run.implementation!!.instructions.toList()
    val self = run.implementation!!.registerCount - 1
    restoreRequire(
        code.none { it.opcode == Opcode.RETURN || it.opcode == Opcode.RETURN_OBJECT } &&
            code.none { it.opcode == Opcode.RETURN_WIDE },
        "run returns a value",
    )

    val published =
        code.indices.filter {
            code[it].restoreField()?.let { f ->
                f.definingClass == RESTORE_ANALYZER && f.name == "results"
            } == true
        }
    restoreRequire(published.size == 1, "results accesses (${published.size} found)")
    val read = published.single()
    val results = code[read] as? TwoRegisterInstruction
    restoreRequire(
        results != null &&
            results.opcode == Opcode.IGET_OBJECT &&
            results.restoreField()!!.type == RESTORE_MAP,
        "results field",
    )
    results!!
    restoreRequire(read + 2 < code.size, "publication bounds")
    val put = code[read + 1]
    val putCall = put.restoreCall()
    restoreRequire(
        put.opcode == Opcode.INVOKE_VIRTUAL &&
            put is FiveRegisterInstruction &&
            put.registerCount == 3 &&
            putCall != null &&
            putCall.definingClass == RESTORE_MAP &&
            putCall.name == "putIfAbsent" &&
            putCall.restoreParameters() == listOf(RESTORE_OBJECT, RESTORE_OBJECT) &&
            putCall.returnType == RESTORE_OBJECT &&
            code[read + 2].opcode == Opcode.RETURN_VOID,
        "results publication",
    )
    put as FiveRegisterInstruction
    restoreRequire(code.count { it.restoreCall()?.name == "putIfAbsent" } == 1, "extra putIfAbsent")
    val map = put.registerC
    val key = put.registerD
    val value = put.registerE
    restoreRequire(
        map == results.registerA && setOf(map, key, value).size == 3 && maxOf(map, key, value) < 16,
        "publication registers",
    )

    fun writers(register: Int) =
        code.indices.filter { it != read && code[it].restoreWrites(register) }
    val analyzer = results.registerB
    val analyzerWrite = writers(analyzer)
    restoreRequire(
        analyzerWrite.size == 1 &&
            analyzerWrite.single() < read &&
            code[analyzerWrite.single()].restoreCapturedFrom(self, analyzerField),
        "analyzer register origin",
    )
    val keyWrite = writers(key)
    restoreRequire(
        keyWrite.size == 1 && code[keyWrite.single()].restoreCapturedFrom(self, keyField),
        "key register origin",
    )
    restoreRequire(analyzer != key && analyzer != value, "analyzer register collision")

    fun calls(match: (MethodReference) -> Boolean) =
        code.indices.filter { code[it].restoreCall()?.let(match) == true }
    val readText = calls {
        it.name == "readText\$default" &&
            it.definingClass.startsWith("Lkotlin/io/") &&
            it.restoreParameters() == listOf("Ljava/io/File;") &&
            it.returnType == RESTORE_TEXT
    }
    val decode = calls {
        it.name == "decodeFromString" &&
            it.definingClass == "Lkotlinx/serialization/json/Json;" &&
            it.restoreParameters() == listOf(RESTORE_TEXT, "Lkotlinx/serialization/KSerializer;") &&
            it.returnType == RESTORE_OBJECT
    }
    val convert = calls {
        it.name == "toAnalysis" && it.restoreParameters() == listOf(RESTORE_TEXT)
    }
    restoreRequire(
        readText.size == 1 && decode.size == 1 && convert.size == 1,
        "stored read (${readText.size} read, ${decode.size} decode, ${convert.size} conversion)",
    )
    val conversion = code[convert.single()].restoreCall()!!
    restoreRequire(
        readText.single() < decode.single() &&
            decode.single() < convert.single() &&
            convert.single() < read &&
            conversion.returnType == RESTORE_ANALYSIS &&
            code[convert.single()].opcode == Opcode.INVOKE_VIRTUAL &&
            code[convert.single() + 1].opcode == Opcode.MOVE_RESULT_OBJECT,
        "stored read order",
    )
    val decoded = decode.single()
    restoreRequire(
        code[decoded + 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
            ((code[decoded + 2] as? ReferenceInstruction)?.reference as? TypeReference)?.type ==
                conversion.definingClass &&
            code[decoded + 2].opcode == Opcode.CHECK_CAST,
        "decoded stored type",
    )
    val store =
        code.indices.filter {
            code[it].restoreField()?.let { f ->
                f.definingClass == RESTORE_ANALYZER && f.name == "store"
            } == true
        }
    restoreRequire(store.size == 1, "store reads (${store.size} found)")
    val storeRead = code[store.single()] as? TwoRegisterInstruction
    restoreRequire(storeRead != null && storeRead.opcode == Opcode.IGET_OBJECT, "store field")
    storeRead!!
    val storeType = storeRead.restoreField()!!.type
    restoreRequire(
        storeRead.registerB == analyzer &&
            store.single() < readText.single() &&
            code.any {
                it.restoreField()?.let { f ->
                    f.definingClass == storeType && f.type == RESTORE_MAP
                } == true
            } &&
            code.any {
                it.restoreField()?.let { f ->
                    f.definingClass == storeType &&
                        f.type.startsWith("Lkotlinx/serialization/json/Json")
                } == true
            } &&
            code.any {
                it.restoreCall()?.let { c ->
                    c.definingClass == storeType &&
                        c.restoreParameters() == listOf(RESTORE_TEXT) &&
                        c.returnType == RESTORE_TEXT
                } == true
            },
        "store ABI",
    )

    val valueWrites = writers(value)
    val casts = valueWrites.filter { code[it].opcode == Opcode.CHECK_CAST }
    restoreRequire(casts.size == 1 && casts.single() == valueWrites.max(), "value cast")
    val cast = casts.single()
    restoreRequire(
        cast < read &&
            cast > convert.single() &&
            (code[cast] as OneRegisterInstruction).registerA == value &&
            ((code[cast] as ReferenceInstruction).reference as TypeReference).type ==
                conversion.returnType,
        "value type",
    )
    val moved = code[cast - 1] as? TwoRegisterInstruction
    restoreRequire(
        moved != null &&
            moved.opcode in
                setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16) &&
            moved.registerA == value &&
            moved.registerB == (code[convert.single() + 1] as OneRegisterInstruction).registerA,
        "value origin",
    )
    restoreRequire(
        valueWrites.all {
            val op = code[it]
            op.opcode == Opcode.CHECK_CAST ||
                op.opcode == Opcode.MOVE_OBJECT ||
                op.opcode == Opcode.MOVE_OBJECT_FROM16 ||
                op.opcode == Opcode.MOVE_OBJECT_16 ||
                (op is WideLiteralInstruction &&
                    op.wideLiteral == 0L &&
                    op.opcode in listOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST))
        },
        "value origin",
    )
    return AnalysisRestoreSite(type, run, read + 1, map, key, value)
}

internal fun locateAnalysisRestore(classes: Iterable<ClassDef>): AnalysisRestoreSite {
    val candidates =
        classes.filter { type ->
            "Ljava/lang/Runnable;" in type.interfaces &&
                type.methods.any { method ->
                    method.implementation?.instructions?.any {
                        it.restoreField()?.let { f ->
                            f.definingClass == RESTORE_ANALYZER && f.name == "results"
                        } == true
                    } == true
                }
        }
    restoreRequire(candidates.isNotEmpty(), "no runnable publishes analysis results (0 found)")
    val failures = mutableListOf<String>()
    val sites =
        candidates.mapNotNull {
            try {
                restoreQualify(it)
            } catch (error: IllegalStateException) {
                failures += "${it.type}: ${error.message}"
                null
            }
        }
    restoreRequire(
        sites.isNotEmpty(),
        "no qualified runnable (0 found; ${failures.joinToString()})",
    )
    restoreRequire(sites.size == 1, "ambiguous runnables (${sites.size} found)")
    return sites.single()
}

internal fun restoredSmali(site: AnalysisRestoreSite, owner: String) =
    "invoke-static {v${site.map}, v${site.key}, v${site.value}}, " +
        "$owner->restored(Ljava/util/Map;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"

internal fun restoredInstruction(site: AnalysisRestoreSite, owner: String): Instruction =
    ImmutableInstruction35c(
        Opcode.INVOKE_STATIC,
        3,
        site.map,
        site.key,
        site.value,
        0,
        0,
        ImmutableMethodReference(
            owner,
            "restored",
            listOf("Ljava/util/Map;", RESTORE_OBJECT, RESTORE_OBJECT),
            RESTORE_OBJECT,
        ),
    )

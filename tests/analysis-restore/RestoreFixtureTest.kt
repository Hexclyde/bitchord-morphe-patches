package app.bitchord.patches

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.MethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.immutable.*
import com.android.tools.smali.dexlib2.immutable.instruction.*
import com.android.tools.smali.dexlib2.immutable.reference.*
import java.io.File

private const val OBJ = "Ljava/lang/Object;"
private const val TEXT = "Ljava/lang/String;"
private const val CHM = "Ljava/util/concurrent/ConcurrentHashMap;"
private const val ANALYZER = "Lcom/music/bitchord/playback/smart/TrackAnalyzer;"
private const val STORE = "Lfixture/Store;"
private const val STORED = "Lfixture/Store\$Stored;"
private const val ANALYSIS = "Lcom/music/bitchord/playback/smart/TrackAnalysis;"
private const val JSON = "Lkotlinx/serialization/json/Json;"
private const val MIX = "Lapp/bitchord/telegram/SourceAutomix;"
private var checks = 0

private fun field(owner: String, name: String, type: String) =
    ImmutableFieldReference(owner, name, type)

private fun call(owner: String, name: String, params: List<String>, result: String) =
    ImmutableMethodReference(owner, name, params, result)

private fun invoke(opcode: Opcode, ref: ImmutableMethodReference, vararg registers: Int) =
    ImmutableInstruction35c(
        opcode,
        registers.size,
        registers.getOrElse(0) { 0 },
        registers.getOrElse(1) { 0 },
        registers.getOrElse(2) { 0 },
        0,
        0,
        ref,
    )

private fun iget(a: Int, b: Int, ref: ImmutableFieldReference) =
    ImmutableInstruction22c(Opcode.IGET_OBJECT, a, b, ref)

private fun moveResult(a: Int) = ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, a)

private val results = field(ANALYZER, "results", CHM)
private val putIfAbsent = call(CHM, "putIfAbsent", listOf(OBJ, OBJ), OBJ)
private val toAnalysis = call(STORED, "toAnalysis", listOf(TEXT), ANALYSIS)

private fun runBody(a: String, k: String, owner: String): MutableList<Instruction> =
    mutableListOf(
        iget(0, 10, field(owner, a, ANALYZER)),
        iget(1, 0, field(ANALYZER, "store", STORE)),
        iget(2, 1, field(STORE, "known", CHM)),
        iget(5, 10, field(owner, k, TEXT)),
        invoke(Opcode.INVOKE_STATIC, call(STORE, "fileNameFor", listOf(TEXT), TEXT), 5),
        moveResult(9),
        invoke(
            Opcode.INVOKE_STATIC,
            call("Lkotlin/io/FilesKt;", "readText\$default", listOf("Ljava/io/File;"), TEXT),
            4,
        ),
        moveResult(8),
        iget(1, 1, field(STORE, "json", "Lkotlinx/serialization/json/JsonImpl;")),
        invoke(
            Opcode.INVOKE_VIRTUAL,
            call(
                JSON,
                "decodeFromString",
                listOf(TEXT, "Lkotlinx/serialization/KSerializer;"),
                OBJ,
            ),
            1,
            8,
            7,
        ),
        moveResult(1),
        ImmutableInstruction21c(Opcode.CHECK_CAST, 1, ImmutableTypeReference(STORED)),
        invoke(Opcode.INVOKE_VIRTUAL, toAnalysis, 1, 5),
        moveResult(1),
        ImmutableInstruction11n(Opcode.CONST_4, 6, 0),
        ImmutableInstruction12x(Opcode.MOVE_OBJECT, 6, 1),
        ImmutableInstruction21c(Opcode.CHECK_CAST, 6, ImmutableTypeReference(ANALYSIS)),
        iget(0, 0, results),
        invoke(Opcode.INVOKE_VIRTUAL, putIfAbsent, 0, 5, 6),
        ImmutableInstruction10x(Opcode.RETURN_VOID),
    )

private fun ctorBody(a: String, k: String, owner: String): MutableList<Instruction> =
    mutableListOf(
        invoke(Opcode.INVOKE_DIRECT, call(OBJ, "<init>", emptyList(), "V"), 0),
        ImmutableInstruction22c(Opcode.IPUT_OBJECT, 1, 0, field(owner, a, ANALYZER)),
        ImmutableInstruction22c(Opcode.IPUT_OBJECT, 2, 0, field(owner, k, TEXT)),
        ImmutableInstruction10x(Opcode.RETURN_VOID),
    )

private class Spec(
    var owner: String = "Lfixture/Task;",
    var a: String = "alpha",
    var k: String = "beta",
    var runnable: Boolean = true,
    var run: MutableList<Instruction> = mutableListOf(),
    var ctor: MutableList<Instruction> = mutableListOf(),
    var extraField: Boolean = false,
    var runFlags: Int = 0x11,
    var ctorFlags: Int = 0x10001,
    var classFlags: Int = 0x1011,
    var analyzerType: String = ANALYZER,
) {
    init {
        run = runBody(a, k, owner)
        ctor = ctorBody(a, k, owner)
    }
}

private fun build(spec: Spec): ClassDef {
    val owner = spec.owner
    val ctor =
        ImmutableMethod(
            owner,
            "<init>",
            listOf(
                ImmutableMethodParameter(ANALYZER, emptySet(), null),
                ImmutableMethodParameter(TEXT, emptySet(), null),
            ),
            "V",
            spec.ctorFlags,
            emptySet(),
            emptySet(),
            ImmutableMethodImplementation(3, spec.ctor, emptyList(), emptyList()),
        )
    val run =
        ImmutableMethod(
            owner,
            "run",
            emptyList(),
            "V",
            spec.runFlags,
            emptySet(),
            emptySet(),
            ImmutableMethodImplementation(11, spec.run, emptyList(), emptyList()),
        )
    val fields =
        mutableListOf(
            ImmutableField(owner, spec.a, spec.analyzerType, 0x1011, null, emptySet(), emptySet()),
            ImmutableField(owner, spec.k, TEXT, 0x1011, null, emptySet(), emptySet()),
        )
    if (spec.extraField)
        fields += ImmutableField(owner, "gamma", TEXT, 0x1011, null, emptySet(), emptySet())
    return ImmutableClassDef(
        owner,
        spec.classFlags,
        OBJ,
        if (spec.runnable) listOf("Ljava/lang/Runnable;") else emptyList(),
        null,
        emptySet(),
        fields,
        listOf(ctor, run),
    )
}

private fun ok(spec: Spec = Spec()) = locateAnalysisRestore(listOf(build(spec)))

private fun reject(name: String, expected: String, spec: Spec.() -> Unit) {
    val value = Spec().apply(spec)
    val message =
        try {
            locateAnalysisRestore(listOf(build(value)))
            null
        } catch (error: IllegalStateException) {
            error.message
        }
    check(
        message != null &&
            message.contains(expected) &&
            message.startsWith("Native analysis restore changed")
    ) {
        "Mutation not rejected as '$expected': $name -> $message"
    }
    checks++
}

private fun List<Instruction>.indexOfCall(name: String) = indexOfFirst {
    (it as? ReferenceInstruction)?.reference.let { r ->
        r is ImmutableMethodReference && r.name == name
    }
}

private fun List<Instruction>.indexOfField(name: String) = indexOfFirst {
    (it as? ReferenceInstruction)?.reference.let { r ->
        r is ImmutableFieldReference && r.name == name
    }
}

private fun synthetic() {
    val site = ok()
    check(site.map == 0 && site.key == 5 && site.value == 6 && site.instruction == 18) {
        "fixture site"
    }
    checks++
    val renamed = ok(Spec(owner = "La/b/c;", a = "q", k = "z"))
    check(renamed.type.type == "La/b/c;")
    checks++
    val swappedDeclaration = ok(Spec(owner = "La/x;", a = "z", k = "a"))
    check(swappedDeclaration.run.name == "run")
    checks++
    val ctorStores = Spec(owner = "Lr/Q;", a = "b\$c", k = "a")
    check(ok(ctorStores).type.type == "Lr/Q;")
    checks++
    val twin = build(Spec(owner = "Lfixture/Twin;"))
    val other = build(Spec())
    val unrelated = build(Spec(owner = "Lfixture/Plain;", runnable = false))
    check(locateAnalysisRestore(listOf(unrelated, other)).type.type == "Lfixture/Task;")
    checks++
    try {
        locateAnalysisRestore(listOf(twin, other))
        error("duplicate accepted")
    } catch (error: IllegalStateException) {
        check(error.message!!.contains("ambiguous runnables (2 found)")) { error.message!! }
        checks++
    }

    reject("not a runnable", "0 found") { runnable = false }
    reject("private constructor", "constructor signature") { ctorFlags = 0x10002 }
    reject("nonpublic runnable", "runnable signature") { classFlags = 0x1010 }
    reject("declared capture type changed", "capture fields") { analyzerType = TEXT }
    reject("static results field", "results field") {
        run[17] = ImmutableInstruction21c(Opcode.SGET_OBJECT, 0, results)
    }
    reject("static store field", "store field") {
        run[1] = ImmutableInstruction21c(Opcode.SGET_OBJECT, 1, field(ANALYZER, "store", STORE))
    }
    reject("conversion returns unrelated type", "stored read order") {
        run[12] =
            invoke(
                Opcode.INVOKE_VIRTUAL,
                call(STORED, "toAnalysis", listOf(TEXT), "Lfixture/Other;"),
                1,
                5,
            )
        run[16] =
            ImmutableInstruction21c(Opcode.CHECK_CAST, 6, ImmutableTypeReference("Lfixture/Other;"))
    }
    reject("value moved from unrelated register", "value origin") {
        run[15] = ImmutableInstruction12x(Opcode.MOVE_OBJECT, 6, 2)
    }
    reject("swapped capture stores", "constructor capture") {
        ctor[1] = ImmutableInstruction22c(Opcode.IPUT_OBJECT, 2, 0, field(owner, a, ANALYZER))
        ctor[2] = ImmutableInstruction22c(Opcode.IPUT_OBJECT, 1, 0, field(owner, k, TEXT))
    }
    reject("both captures from p1", "constructor capture") {
        ctor[2] = ImmutableInstruction22c(Opcode.IPUT_OBJECT, 1, 0, field(owner, k, TEXT))
    }
    reject("missing capture", "constructor body") { ctor.removeAt(2) }
    reject("extra instance field", "capture fields") { extraField = true }
    reject("key from analyzer field", "key register origin") {
        run[3] = iget(5, 10, field(owner, a, ANALYZER))
    }
    reject("analyzer from key field", "analyzer register origin") {
        run[0] = iget(0, 10, field(owner, k, TEXT))
    }
    reject("analyzer rewritten", "analyzer register origin") {
        run.add(1, iget(0, 10, field(owner, a, ANALYZER)))
    }
    reject("key rewritten", "key register origin") {
        run.add(
            5,
            ImmutableInstruction21c(Opcode.CONST_STRING, 5, ImmutableStringReference("other")),
        )
    }
    reject("map receiver changed", "publication registers") {
        run[run.indexOfCall("putIfAbsent")] = invoke(Opcode.INVOKE_VIRTUAL, putIfAbsent, 2, 5, 6)
    }
    reject("key argument changed", "key register origin") {
        run[run.indexOfCall("putIfAbsent")] = invoke(Opcode.INVOKE_VIRTUAL, putIfAbsent, 0, 9, 6)
    }
    reject("value argument changed", "value cast") {
        run[run.indexOfCall("putIfAbsent")] = invoke(Opcode.INVOKE_VIRTUAL, putIfAbsent, 0, 5, 1)
    }
    reject("value cast to other type", "value type") {
        run[16] =
            ImmutableInstruction21c(Opcode.CHECK_CAST, 6, ImmutableTypeReference("Lfixture/Other;"))
    }
    reject("value cast removed", "value cast") { run.removeAt(16) }
    reject("value from unrelated write", "value origin") {
        run.add(15, ImmutableInstruction21c(Opcode.CONST_STRING, 6, ImmutableStringReference("x")))
    }
    reject("results is HashMap", "results field") {
        run[17] = iget(0, 0, field(ANALYZER, "results", "Ljava/util/HashMap;"))
    }
    reject("put replaces putIfAbsent", "results publication") {
        run[18] = invoke(Opcode.INVOKE_VIRTUAL, call(CHM, "put", listOf(OBJ, OBJ), OBJ), 0, 5, 6)
    }
    reject("wrong map owner", "results publication") {
        run[18] =
            invoke(
                Opcode.INVOKE_VIRTUAL,
                call("Ljava/util/HashMap;", "putIfAbsent", listOf(OBJ, OBJ), OBJ),
                0,
                5,
                6,
            )
    }
    reject("wrong result type", "results publication") {
        run[18] =
            invoke(Opcode.INVOKE_VIRTUAL, call(CHM, "putIfAbsent", listOf(OBJ, OBJ), "V"), 0, 5, 6)
    }
    reject("publication result used", "results publication") { run.add(19, moveResult(0)) }
    reject("publication not followed by return", "results publication") {
        run.add(19, ImmutableInstruction11n(Opcode.CONST_4, 3, 0))
    }
    reject("extra results read", "results accesses (2 found)") { run.add(2, iget(3, 0, results)) }
    reject("extra results write", "results accesses (2 found)") {
        run.add(19, ImmutableInstruction22c(Opcode.IPUT_OBJECT, 6, 0, results))
    }
    reject("extra putIfAbsent", "extra putIfAbsent") {
        run.add(2, invoke(Opcode.INVOKE_VIRTUAL, putIfAbsent, 2, 5, 6))
    }
    reject("missing readText", "stored read (0 read") {
        run.removeAt(run.indexOfCall("readText\$default"))
    }
    reject("duplicate readText", "stored read (2 read") {
        run.add(6, run[run.indexOfCall("readText\$default")])
    }
    reject("missing decode", "stored read (1 read, 0 decode") {
        run.removeAt(run.indexOfCall("decodeFromString"))
    }
    reject("missing conversion", "stored read (1 read, 1 decode, 0 conversion") {
        run.removeAt(run.indexOfCall("toAnalysis"))
    }
    reject("conversion before decode", "stored read order") {
        val conversion = run.removeAt(run.indexOfCall("toAnalysis"))
        run.add(run.indexOfCall("readText\$default"), conversion)
    }
    reject("conversion on another type", "decoded stored type") {
        run[11] =
            ImmutableInstruction21c(Opcode.CHECK_CAST, 1, ImmutableTypeReference("Lfixture/Else;"))
    }
    reject("store read missing", "store reads (0 found)") {
        run.removeAt(run.indexOfField("store"))
    }
    reject("store typed differently", "store ABI") {
        run[run.indexOfField("store")] = iget(1, 0, field(ANALYZER, "store", "Lfixture/Missing;"))
    }
    reject("value returned", "run returns a value") {
        run[19] = ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)
    }
    reject("run not public", "run signature") { runFlags = 0x2 }

    replacement(site, build(Spec()).methods.single { it.name == "run" })
}

private fun instructionValue(value: Instruction): String = buildString {
    append(value.opcode)
    if (value is ReferenceInstruction) append(" ref=${value.reference}")
    if (value is OneRegisterInstruction) append(" a=${value.registerA}")
    if (value is TwoRegisterInstruction) append(" b=${value.registerB}")
    if (value is ThreeRegisterInstruction) append(" c=${value.registerC}")
    if (value is FiveRegisterInstruction)
        append(
            " args=${listOf(value.registerC, value.registerD, value.registerE, value.registerF, value.registerG).take(value.registerCount)}"
        )
    if (value is RegisterRangeInstruction)
        append(" range=${value.startRegister}:${value.registerCount}")
    if (value is WideLiteralInstruction) append(" literal=${value.wideLiteral}")
    if (value is OffsetInstruction) append(" offset=${value.codeOffset}")
    append(" units=${value.codeUnits}")
}

private fun shape(implementation: MethodImplementation) =
    implementation.tryBlocks.map {
        "${it.startCodeAddress}:${it.codeUnitCount}:" +
            it.exceptionHandlers.map { h -> "${h.exceptionType}@${h.handlerCodeAddress}" }
    } to implementation.debugItems.map { "${it.debugItemType}@${it.codeAddress}" }

private fun replacement(site: AnalysisRestoreSite, run: Method) {
    val before = run.implementation!!
    val baseline = shape(MutableMethod(run).implementation!!)
    val changed = MutableMethod(run)
    changed.replaceInstruction(site.instruction, restoredSmali(site, MIX))
    val after = changed.implementation!!
    val expected = before.instructions.map(::instructionValue).toMutableList()
    expected[site.instruction] = instructionValue(restoredInstruction(site, MIX))
    check(after.instructions.map(::instructionValue) == expected) {
        "Replacement changed more than the publication call"
    }
    check(after.registerCount == before.registerCount && shape(after) == baseline) {
        "Replacement changed registers, handlers or debug items"
    }
    check(shape(before).first == shape(after).first) { "Replacement moved a try block" }
    val replaced = after.instructions.toList()[site.instruction] as FiveRegisterInstruction
    check(
        listOf(replaced.registerC, replaced.registerD, replaced.registerE) ==
            listOf(site.map, site.key, site.value) && replaced.registerCount == 3
    )
    checks++
}

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        synthetic()
        println("PASS: $checks native analysis restore guards")
        return
    }
    for (path in args) {
        val input = File(path)
        val all =
            if (input.isDirectory) {
                input
                    .listFiles()!!
                    .filter { it.extension == "dex" }
                    .flatMap { DexFileFactory.loadDexFile(it, null).classes }
            } else {
                val container = DexFileFactory.loadDexContainer(input, null)
                container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }
            }
        val site = locateAnalysisRestore(all)
        check(listOf(site.map, site.key, site.value) == listOf(0, 5, 6)) {
            "unexpected registers for $path"
        }
        replacement(site, site.run)
        println(
            "PASS $path ${site.type.type}->run at=${site.instruction} registers=${site.map},${site.key},${site.value}; body, handlers, debug items and registers preserved"
        )
    }
}

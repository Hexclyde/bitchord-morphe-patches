package app.bitchord.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.*
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.builder.instruction.BuilderSwitchElement
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.ArrayPayload
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * Wraps the editor callback of an addon row in Sources. The row's click lambda is the only invoke(5
 * arguments) that branches on SourceKind.needsServer; R8 numbers the lambda, so it is found by that
 * read. [wrapperClass] declares a static `wrap(Function1): Function1`.
 */
internal fun BytecodePatchContext.wrapSourceRowEditor(wrapperClass: String) {
    val owners = mutableListOf<String>()
    classDefForEach { clazz ->
        if (
            clazz.methods.any { method ->
                method.name == "invoke" &&
                    method.parameterTypes.size == 5 &&
                    method.implementation?.instructions?.any {
                        ((it as? ReferenceInstruction)?.reference as? FieldReference)?.let { ref ->
                            ref.definingClass == "Lcom/music/bitchord/data/sources/SourceKind;" &&
                                ref.name == "needsServer"
                        } == true
                    } == true
            }
        )
            owners += clazz.type
    }
    check(owners.size == 1) { "Source row click lambda not unique (${owners.size})" }
    val row = mutableClassDefBy(owners.single())
    val invoke = row.methods.single { it.name == "invoke" && it.parameterTypes.size == 5 }
    val code = invoke.implementation!!.instructions.toList()
    val decision =
        code.indexOfFirst {
            ((it as? ReferenceInstruction)?.reference as? FieldReference)?.let { ref ->
                ref.definingClass == "Lcom/music/bitchord/data/sources/SourceKind;" &&
                    ref.name == "needsServer"
            } == true
        }
    check(decision >= 0) { "Source row click decision missing" }
    val load =
        (decision + 1 until minOf(decision + 8, code.size)).single {
            val ref = (code[it] as? ReferenceInstruction)?.reference as? FieldReference
            code[it].opcode == Opcode.IGET_OBJECT &&
                ref?.definingClass == row.type &&
                ref.type == "Lkotlin/jvm/functions/Function1;"
        }
    val editor = (code[load] as TwoRegisterInstruction).registerA
    // The click callback is the lambda built from the editor: the editor first, then a trailing
    // selector.
    val click =
        (load + 1 until minOf(load + 24, code.size)).single {
            val ref = (code[it] as? ReferenceInstruction)?.reference as? MethodReference
            code[it].opcode == Opcode.INVOKE_DIRECT &&
                ref?.name == "<init>" &&
                ref.parameterTypes.size == 3 &&
                ref.parameterTypes.last() == "I" &&
                ref.parameterTypes.first() in
                    setOf("Ljava/lang/Object;", "Lkotlin/jvm/functions/Function1;") &&
                (code[it] as FiveRegisterInstruction).registerD == editor
        }
    check(editor < 16 && click > load) { "Source row click callback register changed" }
    invoke.addInstructions(
        load + 1,
        """
        invoke-static {v$editor}, $wrapperClass->wrap(Lkotlin/jvm/functions/Function1;)Lkotlin/jvm/functions/Function1;
        move-result-object v$editor
    """,
    )
}

/**
 * The addon request coroutine and the request URL it reads. [branch] is the `$r8$classId` of the
 * merged coroutine's addon branch, or null when the method is only that branch.
 */
internal class AddonBodySite(
    val clazz: ClassDef,
    val method: Method,
    val url: FieldReference,
    val branch: Int?,
)

internal class AddonBodyHost(val method: MutableMethod, val url: FieldReference, val branch: Int?) {
    /**
     * Answers identifiers [owner] reserves from the phone: [owner] is `Lclass;->bodyOrNull(String)
     * String`, which returns null for any other URL. Other addon requests continue into the
     * original method. The hook sits ahead of the earlier first instruction, so native sources
     * installed in either order form one chain.
     */
    fun install(owner: String, label: String) {
        val first = method.implementation!!.instructions.first()
        val read =
            """
            move-object/from16 v0, p0
            iget-object v0, v0, ${url.definingClass}->${url.name}:${url.type}
            check-cast v0, Ljava/lang/String;
            invoke-static {v0}, $owner
            move-result-object v0
            if-eqz v0, :$label
            return-object v0
            """
        // R8 merged unrelated coroutines into this method; only the addon branch holds a String
        // URL, so the other branches must reach their own code untouched.
        val guard =
            if (branch == null) ""
            else
                """
                move-object/from16 v0, p0
                iget v1, v0, ${url.definingClass}->$CLASS_ID:I
                const/16 v0, $branch
                if-ne v1, v0, :$label
                """
        method.addInstructionsWithLabels(0, guard + read, ExternalLabel(label, first))
    }
}

private const val CLASS_ID = "\$r8\$classId"

private fun Instruction.reads(owner: String, name: String) =
    // Opcode.name is the smali mnemonic; the constant's own name is its string form.
    opcode.toString().startsWith("IGET") &&
        ((this as? ReferenceInstruction)?.reference as? FieldReference)?.let {
            it.definingClass == owner && it.name == name
        } == true

private fun jumpIndex(address: IntArray, from: Int, instruction: Instruction): Int =
    if (instruction is BuilderOffsetInstruction) instruction.target.location.index
    else address.indexOf(address[from] + (instruction as OffsetInstruction).codeOffset)

private fun switchTargets(
    code: List<Instruction>,
    address: IntArray,
    at: Int,
): List<Pair<Int, Int>> {
    val payload = code[jumpIndex(address, at, code[at])] as SwitchPayload
    return payload.switchElements.map {
        it.key to
            if (it is BuilderSwitchElement) it.target.location.index
            else address.indexOf(address[at] + it.offset)
    }
}

/**
 * The instruction indexes a branch of [code] can reach, from [start], without leaving the method.
 */
private fun reachable(code: List<Instruction>, address: IntArray, start: Int): Set<Int> {
    val seen = mutableSetOf<Int>()
    val pending = ArrayDeque(listOf(start))
    while (pending.isNotEmpty()) {
        val at = pending.removeLast()
        if (at !in code.indices || !seen.add(at)) continue
        val instruction = code[at]
        val name = instruction.opcode.toString()
        when {
            instruction is SwitchPayload || instruction is ArrayPayload -> {}
            instruction.opcode == Opcode.PACKED_SWITCH ||
                instruction.opcode == Opcode.SPARSE_SWITCH -> {
                switchTargets(code, address, at).forEach { pending.add(it.second) }
                pending.add(at + 1)
            }
            instruction is OffsetInstruction || instruction is BuilderOffsetInstruction -> {
                pending.add(jumpIndex(address, at, instruction))
                if (!name.startsWith("GOTO")) pending.add(at + 1)
            }
            name.startsWith("RETURN") || instruction.opcode == Opcode.THROW -> {}
            else -> pending.add(at + 1)
        }
    }
    return seen
}

/**
 * The `$r8$classId` that selects the branch reaching [urlCall], or null when [code] never reads
 * that discriminator. One case of the dispatch switch must reach the request, and the default
 * branch must not.
 */
internal fun addonBranchId(clazz: ClassDef, code: List<Instruction>, urlCall: Int): Int? {
    val reads = code.indices.filter { code[it].reads(clazz.type, CLASS_ID) }
    if (reads.isEmpty()) return null
    // A guard another native patch put at the entry reads the id too; only the original read
    // feeds the dispatch switch.
    val dispatches =
        reads.mapNotNull { load ->
            val register = (code[load] as TwoRegisterInstruction).registerA
            (load + 1 until minOf(load + 12, code.size))
                .singleOrNull {
                    (code[it].opcode == Opcode.PACKED_SWITCH ||
                        code[it].opcode == Opcode.SPARSE_SWITCH) &&
                        (code[it] as OneRegisterInstruction).registerA == register
                }
                ?.let { load to it }
        }
    check(clazz.fields.any { it.name == CLASS_ID && it.type == "I" } && dispatches.size == 1) {
        "Addon coroutine dispatch changed"
    }
    val switch = dispatches.single().second
    var units = 0
    val address = IntArray(code.size) { index -> units.also { units += code[index].codeUnits } }
    val cases = switchTargets(code, address, switch)
    val owning = cases.filter { urlCall in reachable(code, address, it.second) }.map { it.first }
    check(
        owning.size == 1 && urlCall !in reachable(code, address, switch + 1) && cases.isNotEmpty()
    ) {
        "Addon request branch is not one dispatch case (${owning.size})"
    }
    return owning.single()
}

/**
 * Finds the addon request coroutine among [classes] and the URL read that feeds its request. R8 may
 * fold it into an unrelated lambda class under a synthetic name, so it is found by the request
 * call, the strings it reports and the coroutine state.
 */
internal fun locateAddonBody(classes: Iterable<ClassDef>): AddonBodySite {
    val owners = mutableListOf<Pair<ClassDef, Method>>()
    for (clazz in classes) {
        for (method in clazz.methods) {
            val code = method.implementation?.instructions?.toList() ?: continue
            if (
                method.returnType == "Ljava/lang/Object;" &&
                    method.parameterTypes == listOf("Ljava/lang/Object;") &&
                    code.any { it.string() == "Not held by this addon" } &&
                    code.any { it.string() == "Retry-After" } &&
                    code.any {
                        it.method()?.let { call ->
                            call.definingClass == "Lokhttp3/Request\$Builder;" &&
                                call.name == "url" &&
                                call.parameterTypes == listOf("Ljava/lang/String;")
                        } == true
                    } &&
                    code.any {
                        ((it as? ReferenceInstruction)?.reference as? FieldReference)?.name ==
                            "COROUTINE_SUSPENDED"
                    }
            )
                owners += clazz to method
        }
    }
    check(owners.size == 1) { "Addon IO coroutine is not unique (${owners.size})" }
    val (clazz, original) = owners.single()
    val code = original.implementation!!.instructions.toList()
    val urlCall =
        code.indices.single {
            code[it].method()?.let { call ->
                call.definingClass == "Lokhttp3/Request\$Builder;" && call.name == "url"
            } == true
        }
    val self = original.implementation!!.registerCount - original.parameterTypes.size - 1
    val selfCopies =
        code.indices
            .take(64)
            .filter { at ->
                val copy = code[at] as? TwoRegisterInstruction
                code[at].opcode == Opcode.MOVE_OBJECT_FROM16 &&
                    copy?.registerB == self &&
                    at < urlCall - 2 &&
                    code.subList(at + 1, urlCall - 2).none { instruction ->
                        val written = (instruction as? OneRegisterInstruction)?.registerA
                        instruction.opcode.setsRegister() &&
                            (written == copy.registerA ||
                                (instruction.opcode.setsWideRegister() &&
                                    written != null &&
                                    written + 1 == copy.registerA))
                    }
            }
            .map { (code[it] as TwoRegisterInstruction).registerA }
    val read = code[urlCall - 2] as? TwoRegisterInstruction
    val field = (code[urlCall - 2] as? ReferenceInstruction)?.reference as? FieldReference
    val cast = code[urlCall - 1]
    check(
        code[urlCall - 2].opcode == Opcode.IGET_OBJECT &&
            read != null &&
            (read.registerB == self || read.registerB in selfCopies) &&
            field?.definingClass == clazz.type &&
            field.type == "Ljava/lang/Object;" &&
            cast.opcode == Opcode.CHECK_CAST &&
            (cast as OneRegisterInstruction).registerA == read.registerA &&
            (cast as ReferenceInstruction).reference.toString() == "Ljava/lang/String;" &&
            (code[urlCall] as FiveRegisterInstruction).registerD == read.registerA &&
            original.implementation!!.registerCount - self - 1 >= 1
    ) {
        "Addon request capture changed"
    }
    val branch = addonBranchId(clazz, code, urlCall)
    // The guard needs the object, the discriminator and a constant at once.
    check(branch == null || original.localRegisters() >= 2) { "Addon coroutine registers changed" }
    return AddonBodySite(clazz, original, field, branch)
}

internal fun BytecodePatchContext.addonBodyHost(): AddonBodyHost {
    val classes = mutableListOf<ClassDef>()
    classDefForEach { classes += it }
    val site = locateAddonBody(classes)
    val method =
        mutableClassDefBy(site.clazz.type).methods.single {
            it.name == site.method.name && it.parameterTypes == site.method.parameterTypes
        }
    return AddonBodyHost(method, site.url, site.branch)
}

private fun Instruction.method() = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.string() =
    ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

internal class DirectDownloadHost(
    private val method: MutableMethod,
    private val code: List<Instruction>,
    private val type: String,
    private val index: Int,
    private val scope: Int,
    private val self: Int,
    private val scratch: List<Int>,
) {
    /**
     * Answers [reservedOwner]'s identifiers with [fetchOwner].fetch, using the host coroutine's IO
     * scope, output stream, progress callback and cancellation. Other URLs continue into the
     * original HTTP request. A dispatcher another native patch installed ahead of the request stays
     * first, so either selection order ends up in one chain.
     */
    fun install(reservedOwner: String, fetchOwner: String, label: String, previous: Set<String>) {
        val (path, app) = scratch
        val (extra, context) = scratch.drop(2)
        val existing =
            (maxOf(0, index - 40) until index - 2).filter { at ->
                val read = code[at] as? TwoRegisterInstruction
                val field = (code[at] as? ReferenceInstruction)?.reference as? FieldReference
                val target = code[at + 2].method()
                code[at].opcode == Opcode.IGET_OBJECT &&
                    field?.definingClass == type &&
                    field.name == "\$path" &&
                    read?.registerA == path &&
                    read.registerB == self &&
                    code[at + 1].opcode == Opcode.CHECK_CAST &&
                    (code[at + 1] as OneRegisterInstruction).registerA == path &&
                    (code[at + 1] as ReferenceInstruction).reference.toString() ==
                        "Ljava/lang/String;" &&
                    code[at + 2].opcode == Opcode.INVOKE_STATIC &&
                    target?.definingClass in previous &&
                    target?.name == "reserved" &&
                    target?.parameterTypes == listOf("Ljava/lang/String;") &&
                    target.returnType == "Z" &&
                    (code[at + 2] as FiveRegisterInstruction).registerCount == 1 &&
                    (code[at + 2] as FiveRegisterInstruction).registerC == path
            }
        check(existing.size <= 1) { "Existing native download dispatch is not unique" }
        val insertAt = existing.singleOrNull() ?: index
        method.addInstructionsWithLabels(
            insertAt,
            """
            iget-object v$path, v$self, $type->${'$'}path:Ljava/lang/Object;
            check-cast v$path, Ljava/lang/String;
            invoke-static {v$path}, $reservedOwner->reserved(Ljava/lang/String;)Z
            move-result v$app
            if-eqz v$app, :$label
            iget-object v$app, v$self, $type->${'$'}app:Ljava/lang/Object;
            check-cast v$app, Ljava/io/OutputStream;
            iget-object v$extra, v$self, $type->${'$'}payload:Ljava/lang/Object;
            check-cast v$extra, Lkotlin/jvm/functions/Function2;
            invoke-interface {v$scope}, Lkotlinx/coroutines/CoroutineScope;->getCoroutineContext()Lkotlin/coroutines/CoroutineContext;
            move-result-object v$context
            invoke-static {v$path, v$app, v$extra, v$context}, $fetchOwner->fetch(Ljava/lang/String;Ljava/io/OutputStream;Lkotlin/jvm/functions/Function2;Lkotlin/coroutines/CoroutineContext;)Ljava/lang/Long;
            move-result-object v$path
            return-object v$path
        """,
            ExternalLabel(label, code[insertAt]),
        )
    }
}

/**
 * The merged coroutine that implements the host's direct download: the branch that builds a request
 * for the captured `$path` and later reports "fetched ". R8 folds it into an unrelated class, so it
 * is found by that request, its string and the captured values.
 */
internal fun BytecodePatchContext.directDownloadHost(): DirectDownloadHost {
    val found = mutableListOf<Triple<ClassDef, Method, Int>>()
    classDefForEach { clazz ->
        for (method in clazz.methods) {
            if (
                method.returnType != "Ljava/lang/Object;" ||
                    method.parameterTypes != listOf("Ljava/lang/Object;")
            )
                continue
            val code = method.implementation?.instructions?.toList() ?: continue
            for (at in code.indices) {
                if (
                    code[at].opcode == Opcode.NEW_INSTANCE &&
                        (code[at] as ReferenceInstruction).reference.toString() ==
                            "Lokhttp3/Request\$Builder;" &&
                        ((code.getOrNull(at + 2) as? ReferenceInstruction)?.reference
                                as? FieldReference)
                            ?.let { it.definingClass == clazz.type && it.name == "\$path" } ==
                            true &&
                        code.subList(at, minOf(at + 140, code.size)).any {
                            it.string() == "fetched "
                        }
                )
                    found += Triple(clazz, method, at)
            }
        }
    }
    check(found.size == 1) { "Native download insertion point is not unique (${found.size})" }
    val (clazz, original, index) = found.single()
    val method =
        mutableClassDefBy(clazz.type).methods.single {
            it.name == original.name && it.parameterTypes == original.parameterTypes
        }
    val code = method.implementation!!.instructions.toList()
    val type = clazz.type
    val first = code[index] as OneRegisterInstruction
    val pathRead = code[index + 2] as TwoRegisterInstruction
    val self = pathRead.registerB
    // The request and its path read overwrite these two registers, so the handler may use them
    // before falling through; the branch above them has already read the IO scope.
    val scopeRead =
        (maxOf(0, index - 40) until index - 1).filter { at ->
            val field = (code[at] as? ReferenceInstruction)?.reference as? FieldReference
            code[at].opcode == Opcode.IGET_OBJECT &&
                field?.definingClass == type &&
                field.name == "L\$0" &&
                (code[at] as TwoRegisterInstruction).registerB == self &&
                code[at + 1].opcode == Opcode.CHECK_CAST &&
                (code[at + 1] as OneRegisterInstruction).registerA ==
                    (code[at] as TwoRegisterInstruction).registerA &&
                (code[at + 1] as ReferenceInstruction).reference.toString() ==
                    "Lkotlinx/coroutines/CoroutineScope;"
        }
    check(scopeRead.isNotEmpty()) { "Direct download scope or receiver changed" }
    val scope = (code[scopeRead.last()] as TwoRegisterInstruction).registerA
    for ((name, castType) in
        listOf(
            "\$path" to "Ljava/lang/String;",
            "\$app" to "Ljava/io/OutputStream;",
            "\$payload" to "Lkotlin/jvm/functions/Function2;",
        )) {
        check(
            (index until minOf(index + 60, code.size - 1)).any { at ->
                val field = (code[at] as? ReferenceInstruction)?.reference as? FieldReference
                code[at].opcode == Opcode.IGET_OBJECT &&
                    field?.definingClass == type &&
                    field.name == name &&
                    (code[at] as TwoRegisterInstruction).registerB == self &&
                    code[at + 1].opcode == Opcode.CHECK_CAST &&
                    (code[at + 1] as ReferenceInstruction).reference.toString() == castType
            }
        ) {
            "Direct download capture changed: $name"
        }
    }
    check(
        classDefBy("Lkotlinx/coroutines/JobKt;").methods.any {
            it.name == "ensureActive" &&
                it.parameterTypes == listOf("Lkotlin/coroutines/CoroutineContext;")
        }
    ) {
        "Download cancellation helper changed"
    }
    val registers = method.implementation!!.registerCount
    val taken = setOf(self, scope, first.registerA, pathRead.registerA)
    // Only the request builder and path registers survive on the fall-through, and the
    // handler returns on its own path, so any other low register is free to use there.
    val extra = (0 until minOf(registers, 16)).filter { it !in taken }.take(2)
    check(
        first.registerA != pathRead.registerA &&
            maxOf(self, scope, first.registerA, pathRead.registerA) < 16 &&
            extra.size == 2
    ) {
        "Download registers changed"
    }
    return DirectDownloadHost(
        method,
        code,
        type,
        index,
        scope,
        self,
        listOf(first.registerA, pathRead.registerA) + extra,
    )
}

/** Registers the method keeps for locals, i.e. the ones a prologue may write without saving. */
internal fun Method.localRegisters(): Int =
    implementation!!.registerCount -
        parameterTypes.sumOf { if (it == "J" || it == "D") 2 else 1 as Int } -
        if (accessFlags and AccessFlags.STATIC.value != 0) 0 else 1

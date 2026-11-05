package app.bitchord.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.*

private const val FLAC_CHANGED = "BitChord FLAC tag writer changed"
private const val FLAC_OBJECT = "Ljava/lang/Object;"
private const val FLAC_TEXT = "Ljava/lang/String;"
private const val FLAC_BYTES = "[B"
private const val FLAC_STREAM = "Ljava/io/ByteArrayOutputStream;"
internal const val FLAC_METADATA = "Lapp/bitchord/telegram/FlacMetadata;"
internal const val FLAC_MERGE = "$FLAC_METADATA->mergeMetadata([B[BI)[B"
private val flacHeaderParameters =
    listOf(FLAC_BYTES, FLAC_TEXT, FLAC_TEXT, FLAC_TEXT, FLAC_TEXT, FLAC_BYTES, FLAC_TEXT, FLAC_TEXT)
private val flacVorbisKeys = listOf("TITLE=", "ARTIST=", "ALBUM=", "LYRICS=")
private val flacConstants = listOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST)

private fun flacCheck(okay: Boolean, label: String) = check(okay) { "$FLAC_CHANGED: $label" }

private fun Instruction.flacCall() = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.flacField() = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Instruction.flacString() =
    ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

private fun Instruction.flacType() =
    ((this as? ReferenceInstruction)?.reference as? TypeReference)?.type

private fun Method.flacCode() = implementation?.instructions?.toList().orEmpty()

private fun Method.flacArguments() = parameterTypes.map { it.toString() }

private fun MethodReference.flacArguments() = parameterTypes.map { it.toString() }

private fun Instruction.flacRegisters(): List<Int> =
    when (this) {
        is FiveRegisterInstruction ->
            listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is ThreeRegisterInstruction -> listOf(registerA, registerB, registerC)
        is TwoRegisterInstruction -> listOf(registerA, registerB)
        is OneRegisterInstruction -> listOf(registerA)
        else -> emptyList()
    }

private fun Instruction.flacWrites(register: Int): Boolean {
    if (!opcode.setsRegister() || this !is OneRegisterInstruction) return false
    return registerA == register || (opcode.setsWideRegister() && registerA + 1 == register)
}

private fun Instruction.flacReads(register: Int): Boolean {
    if (this is OneRegisterInstruction && opcode.setsRegister()) {
        val sources =
            when (this) {
                is ThreeRegisterInstruction -> listOf(registerB, registerC)
                is TwoRegisterInstruction -> listOf(registerB)
                else -> emptyList()
            }
        return register in sources
    }
    return register in flacRegisters()
}

private fun Method.flacParameterStart(): Int {
    val width =
        parameterTypes.fold(0) { total, type ->
            total + if (type.toString() == "J" || type.toString() == "D") 2 else 1
        }
    val receiver = if (accessFlags and AccessFlags.STATIC.value != 0) 0 else 1
    return implementation!!.registerCount - width - receiver
}

private fun flacPositions(code: List<Instruction>): List<Int> {
    var at = 0
    return code.map {
        val start = at
        at += it.codeUnits
        start
    }
}

private fun flacNoSwitch(code: List<Instruction>, label: String) =
    flacCheck(
        code.none { it.opcode == Opcode.PACKED_SWITCH || it.opcode == Opcode.SPARSE_SWITCH },
        "$label uses a switch",
    )

internal data class FlacMetadataSite(
    val method: Method,
    val at: Int,
    val holder: Int,
    val metadata: Int,
    val offset: Int,
    val scratch: Int,
    val original: Int,
    val discriminator: Int,
    val constructor: MethodReference,
)

private data class FlacShape(val data: String, val offset: String, val discriminator: String)

/** Reads which constructor parameter lands in which field, since R8 renamed all of them. */
private fun flacShape(type: ClassDef): FlacShape {
    val constructors =
        type.methods.filter {
            it.name == "<init>" && it.flacArguments() == listOf(FLAC_OBJECT, "I", "I")
        }
    flacCheck(constructors.size == 1, "result constructor (${constructors.size} found)")
    val constructor = constructors.single()
    val receiver = constructor.implementation!!.registerCount - 4
    val stores =
        constructor.flacCode().filter {
            (it.opcode == Opcode.IPUT || it.opcode == Opcode.IPUT_OBJECT) &&
                it is TwoRegisterInstruction &&
                it.registerB == receiver &&
                it.flacField()?.definingClass == type.type
        }
    fun field(parameter: Int, opcode: Opcode, valueType: String): String {
        val found =
            stores.filter { (it as TwoRegisterInstruction).registerA == receiver + parameter }
        flacCheck(
            found.size == 1 &&
                found.single().opcode == opcode &&
                found.single().flacField()!!.type == valueType,
            "result constructor parameter $parameter",
        )
        return found.single().flacField()!!.name
    }
    val shape =
        FlacShape(
            field(1, Opcode.IPUT_OBJECT, FLAC_OBJECT),
            field(2, Opcode.IPUT, "I"),
            field(3, Opcode.IPUT, "I"),
        )
    flacCheck(
        listOf(shape.data, shape.offset, shape.discriminator).distinct().size == 3,
        "result constructor stores into shared fields",
    )
    return shape
}

private fun flacMagicRead(owner: ClassDef, code: List<Instruction>): Int {
    val static = AccessFlags.STATIC.value
    val initializer = owner.methods.singleOrNull { it.name == "<clinit>" } ?: return -1
    val init = initializer.flacCode()
    return code.indexOfFirst { read ->
        val field = read.flacField()
        read.opcode == Opcode.SGET_OBJECT &&
            field != null &&
            field.definingClass == owner.type &&
            field.type == FLAC_BYTES &&
            owner.fields.any { it.name == field.name && it.accessFlags and static != 0 } &&
            init.any { it.flacString() == "fLaC" } &&
            init.any {
                it.opcode == Opcode.SPUT_OBJECT &&
                    it.flacField()?.let { put ->
                        put.definingClass == owner.type && put.name == field.name
                    } == true
            }
    }
}

private fun flacHeaderCandidate(owner: ClassDef, method: Method): Boolean {
    if (
        method.accessFlags and AccessFlags.STATIC.value == 0 ||
            method.flacArguments() != flacHeaderParameters ||
            !method.returnType.startsWith("L") ||
            method.implementation == null
    )
        return false
    val code = method.flacCode()
    val strings = code.mapNotNull { it.flacString() }
    return flacVorbisKeys.all { it in strings } && flacMagicRead(owner, code) >= 0
}

private fun MethodReference.flacSame(method: Method) =
    definingClass == method.definingClass &&
        name == method.name &&
        returnType == method.returnType &&
        flacArguments() == method.flacArguments()

/** Proves a header consumer treats a null result as "keep the original bytes". */
private fun flacConsumer(method: Method, at: Int, type: String, shape: FlacShape): Boolean {
    val code = method.flacCode()
    val pcs = flacPositions(code)
    val index = pcs.withIndex().associate { it.value to it.index }
    flacNoSwitch(code, "header consumer")
    flacCheck(
        at + 3 < code.size &&
            code[at + 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
            code[at + 2].opcode == Opcode.IF_NEZ,
        "header result is not tested for null",
    )
    val result = (code[at + 1] as OneRegisterInstruction).registerA
    flacCheck((code[at + 2] as OneRegisterInstruction).registerA == result, "null test register")
    val tries = method.implementation!!.tryBlocks
    fun target(address: Int) = index[address] ?: error("$FLAC_CHANGED: branch target")
    fun successors(at: Int): List<Int> {
        val next = mutableListOf<Int>()
        val instruction = code[at]
        if (instruction.opcode.canContinue() && at + 1 < code.size) next += at + 1
        if (instruction is OffsetInstruction) next += target(pcs[at] + instruction.codeOffset)
        for (block in tries) if (
            pcs[at] >= block.startCodeAddress &&
                pcs[at] < block.startCodeAddress + block.codeUnitCount
        )
            block.exceptionHandlers.forEach { next += target(it.handlerCodeAddress) }
        return next
    }
    fun walk(start: Int, visit: (Int) -> Unit) {
        val seen = HashSet<Int>()
        val work = ArrayDeque(listOf(start))
        while (work.isNotEmpty()) {
            val now = work.removeLast()
            if (!seen.add(now)) continue
            if (code[now].flacReads(result)) visit(now)
            if (code[now].flacWrites(result)) continue
            work += successors(now)
        }
    }
    walk(at + 3) { flacCheck(false, "null header result is read") }
    val seen = mutableSetOf<String>()
    val known = listOf(shape.data, shape.offset, shape.discriminator)
    walk(target(pcs[at + 2] + (code[at + 2] as OffsetInstruction).codeOffset)) { now ->
        val field = code[now].flacField()
        flacCheck(
            (code[now].opcode == Opcode.IGET || code[now].opcode == Opcode.IGET_OBJECT) &&
                (code[now] as TwoRegisterInstruction).registerB == result &&
                field != null &&
                field.definingClass == type &&
                field.name in known,
            "header result escapes its consumer",
        )
        seen += field!!.name
    }
    flacCheck(shape.data in seen && shape.offset in seen, "header consumer ignores the tuple")
    val copy = code[at + 3]
    return copy.opcode in
        listOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16) &&
        (copy as TwoRegisterInstruction).registerB == method.flacParameterStart() &&
        method.returnType == FLAC_BYTES
}

/** Finds the single place where the FLAC header's rebuilt comment block becomes its result. */
internal fun locateFlacMetadata(all: List<ClassDef>): FlacMetadataSite {
    val types = all.associateBy { it.type }
    val candidates =
        all.flatMap { owner ->
            owner.methods.filter { flacHeaderCandidate(owner, it) }.map { owner to it }
        }
    flacCheck(candidates.size == 1, "header (${candidates.size} found)")
    val (owner, header) = candidates.single()
    val code = header.flacCode()
    val pcs = flacPositions(code)
    val registerCount = header.implementation!!.registerCount
    val parameters = header.flacParameterStart()
    flacCheck(
        code.none {
            it.flacCall()?.let { call -> "${call.definingClass}->${call.name}" } ==
                FLAC_MERGE.substringBefore("(")
        },
        "metadata merge is already applied",
    )
    flacNoSwitch(code, "header")
    val resultType = header.returnType
    val resultClass = types[resultType]
    flacCheck(resultClass != null, "header result type")
    val shape = flacShape(resultClass!!)
    val constructors =
        code.indices.filter {
            code[it].opcode == Opcode.INVOKE_DIRECT &&
                code[it].flacCall()?.let { call ->
                    call.definingClass == resultType &&
                        call.name == "<init>" &&
                        call.flacArguments() == listOf(FLAC_OBJECT, "I", "I")
                } == true
        }
    flacCheck(constructors.size == 1, "result construction (${constructors.size} found)")
    val k = constructors.single()
    flacCheck(k >= 5 && k + 1 < code.size, "result construction bounds")
    val create = code[k - 5]
    val toBytes = code[k - 4]
    val moved = code[k - 3]
    val verify = code[k - 2]
    val constant = code[k - 1]
    val build = code[k] as FiveRegisterInstruction
    val finish = code[k + 1]
    val holder = (create as? OneRegisterInstruction)?.registerA ?: -1
    flacCheck(
        create.opcode == Opcode.NEW_INSTANCE && create.flacType() == resultType,
        "result allocation",
    )
    flacCheck(
        toBytes.opcode == Opcode.INVOKE_VIRTUAL &&
            toBytes.flacCall()?.let {
                it.definingClass == FLAC_STREAM &&
                    it.name == "toByteArray" &&
                    it.flacArguments().isEmpty() &&
                    it.returnType == FLAC_BYTES
            } == true,
        "comment block bytes",
    )
    val stream = (toBytes as FiveRegisterInstruction).registerC
    flacCheck(moved.opcode == Opcode.MOVE_RESULT_OBJECT, "comment block result")
    val metadata = (moved as OneRegisterInstruction).registerA
    flacCheck(
        verify.opcode == Opcode.INVOKE_STATIC &&
            verify.flacCall()?.let {
                it.definingClass == "Lkotlin/jvm/internal/Intrinsics;" &&
                    it.name == "checkNotNullExpressionValue" &&
                    it.flacArguments() == listOf(FLAC_OBJECT, FLAC_TEXT)
            } == true &&
            (verify as FiveRegisterInstruction).registerC == metadata,
        "comment block null check",
    )
    val scratch = (verify as FiveRegisterInstruction).registerD
    flacCheck(
        constant.opcode in flacConstants &&
            constant is NarrowLiteralInstruction &&
            (constant as OneRegisterInstruction).registerA == scratch,
        "result discriminator",
    )
    val offset = build.registerE
    flacCheck(
        build.registerCount == 4 &&
            build.registerC == holder &&
            build.registerD == metadata &&
            build.registerF == scratch &&
            finish.opcode == Opcode.RETURN_OBJECT &&
            (finish as OneRegisterInstruction).registerA == holder,
        "result tuple",
    )
    val used = listOf(holder, metadata, offset, scratch)
    flacCheck(used.distinct().size == 4, "tuple registers alias")
    flacCheck(used.all { it in 0..15 && it < parameters }, "tuple registers are not low locals")
    flacCheck(
        code[0].opcode == Opcode.MOVE_OBJECT_FROM16 &&
            (code[0] as TwoRegisterInstruction).registerB == parameters,
        "header does not start by copying its first argument",
    )
    flacCheck(
        (parameters until registerCount).none { register -> code.any { it.flacWrites(register) } },
        "header overwrites an argument",
    )
    val offsetWrite = code.indexOfLast { it.flacWrites(offset) }
    flacCheck(
        offsetWrite in 0 until k - 5 && code[offsetWrite].opcode == Opcode.ADD_INT,
        "audio offset is not an addition",
    )
    val magic = flacMagicRead(owner, code)
    val magicRegister = (code[magic] as OneRegisterInstruction).registerA
    val written =
        (magic + 1 until k - 4).firstOrNull {
            code[it].opcode == Opcode.INVOKE_VIRTUAL &&
                code[it].flacCall()?.let { call ->
                    call.name == "write" &&
                        call.flacArguments() == listOf(FLAC_BYTES) &&
                        call.returnType == "V"
                } == true &&
                (code[it] as FiveRegisterInstruction).let { call ->
                    call.registerCount == 2 &&
                        call.registerC == stream &&
                        call.registerD == magicRegister
                }
        }
    flacCheck(written != null, "rebuilt stream does not begin with the magic")
    val write = written!!
    flacCheck(
        (magic + 1 until write).none { code[it].flacWrites(magicRegister) },
        "magic register changes before the stream write",
    )
    val opened = code.subList(0, write).indexOfLast { it.flacWrites(stream) }
    flacCheck(
        opened >= 0 &&
            code[opened].opcode == Opcode.NEW_INSTANCE &&
            code[opened].flacType() == FLAC_STREAM,
        "rebuilt stream allocation",
    )
    flacCheck(
        (write + 1 until k - 4).none { code[it].flacWrites(stream) },
        "rebuilt stream changes after the magic",
    )
    val tailStart = pcs[k - 4]
    val tailEnd = pcs[k + 1] + code[k + 1].codeUnits
    val blocks = header.implementation!!.tryBlocks
    flacCheck(
        blocks.none {
            it.startCodeAddress < tailEnd && it.startCodeAddress + it.codeUnitCount > pcs[k - 5]
        },
        "a try block covers the result construction",
    )
    val targets =
        code.indices.mapNotNull {
            (code[it] as? OffsetInstruction)?.let { jump -> pcs[it] + jump.codeOffset }
        } + blocks.flatMap { block -> block.exceptionHandlers.map { it.handlerCodeAddress } }
    flacCheck(
        targets.none { it in tailStart until tailEnd },
        "a branch or handler enters the result construction",
    )
    val site =
        FlacMetadataSite(
            header,
            k - 1,
            holder,
            metadata,
            offset,
            scratch,
            parameters,
            (constant as NarrowLiteralInstruction).narrowLiteral,
            build.flacCall()!!,
        )
    val users =
        all.flatMap { type ->
            type.methods.flatMap { method ->
                val body = method.flacCode()
                body.indices
                    .filter {
                        body[it].opcode in
                            listOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE) &&
                            body[it].flacCall()?.flacSame(header) == true
                    }
                    .map { method to it }
            }
        }
    val restoring = users.map { (method, at) -> flacConsumer(method, at, resultType, shape) }
    flacCheck(users.size >= 2, "header consumers (${users.size} found)")
    flacCheck(
        restoring.any { it } && users.any { it.first.returnType == "V" },
        "header is not consumed by both the whole-file and the streaming writer",
    )
    return site
}

/** Routes the rebuilt comment block through the merge helper, or returns no header if it fails. */
internal fun injectFlacMetadata(method: MutableMethod, site: FlacMetadataSite) {
    val code = method.implementation!!.instructions
    val registers = method.implementation!!.registerCount
    val size = code.size
    flacCheck(
        method.definingClass == site.method.definingClass &&
            method.name == site.method.name &&
            method.parameterTypes == site.method.parameterTypes &&
            registers == site.method.implementation!!.registerCount &&
            size == site.method.implementation!!.instructions.count(),
        "header changed before the merge was added",
    )
    val constant = code[site.at]
    flacCheck(
        constant.opcode in flacConstants &&
            (constant as NarrowLiteralInstruction).narrowLiteral == site.discriminator,
        "result discriminator moved",
    )
    method.addInstructionsWithLabels(
        site.at,
        """
        move-object/from16 v${site.scratch}, v${site.original}
        invoke-static {v${site.scratch}, v${site.metadata}, v${site.offset}}, $FLAC_MERGE
        move-result-object v${site.metadata}
        if-nez v${site.metadata}, :flac_merged
        const/4 v${site.scratch}, 0x0
        return-object v${site.scratch}
        """,
        ExternalLabel("flac_merged", constant),
    )
    val after = method.implementation!!.instructions
    flacCheck(
        after.size == size + 6 &&
            method.implementation!!.registerCount == registers &&
            after[site.at + 6] === constant,
        "merge insertion changed the header",
    )
}

internal fun BytecodePatchContext.addFlacMetadata() {
    val all = mutableListOf<ClassDef>()
    classDefForEach { all += it }
    val site = locateFlacMetadata(all)
    val method =
        mutableClassDefBy(site.method.definingClass).methods.single {
            it.name == site.method.name &&
                it.parameterTypes == site.method.parameterTypes &&
                it.returnType == site.method.returnType
        }
    injectFlacMetadata(method, site)
    println("Added FLAC metadata preservation")
}

package app.bitchord.patches

import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.*
import com.android.tools.smali.dexlib2.iface.*
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.immutable.*
import com.android.tools.smali.dexlib2.immutable.instruction.*
import com.android.tools.smali.dexlib2.immutable.reference.*
import java.io.File

// Every type, field and the discriminator below are deliberately unlike the native names.
private const val OWNER = "Lq/A;"
private const val RESULT = "Lq/B;"
private const val USERS = "Lq/C;"
private const val OBJECT = "Ljava/lang/Object;"
private const val TEXT = "Ljava/lang/String;"
private const val STREAM = "Ljava/io/ByteArrayOutputStream;"
private const val PREFIX = "BitChord FLAC tag writer changed"
private val params = listOf("[B", TEXT, TEXT, TEXT, TEXT, "[B", TEXT, TEXT)
private val magic = ImmutableFieldReference(OWNER, "z", "[B")
private val header = ImmutableMethodReference(OWNER, "y", params, RESULT)
private val ctor = ImmutableMethodReference(RESULT, "<init>", listOf(OBJECT, "I", "I"), "V")
private const val CTOR_KEY = "$RESULT-><init>(${OBJECT}II)V"
private val fieldData = ImmutableFieldReference(RESULT, "q", OBJECT)
private val fieldOffset = ImmutableFieldReference(RESULT, "w", "I")
private val fieldKind = ImmutableFieldReference(RESULT, "e", "I")
private val merge =
    ImmutableMethodReference(FLAC_METADATA, "mergeMetadata", listOf("[B", "[B", "I"), "[B")

private const val NEW = 10
private const val TO_BYTES = 11
private const val RESULT_MOVE = 12
private const val CHECK = 13
private const val KIND = 14
private const val BUILD = 15
private const val FINISH = 16

private var checks = 0

private fun ensure(value: Boolean, reason: String) {
    checks++
    check(value) { reason }
}

private fun units(code: List<Instruction>) = code.fold(0) { total, it -> total + it.codeUnits }

private fun call(
    opcode: Opcode,
    reference: ImmutableMethodReference,
    vararg registers: Int,
): Instruction {
    val r = registers.toList() + List(5 - registers.size) { 0 }
    return ImmutableInstruction35c(opcode, registers.size, r[0], r[1], r[2], r[3], r[4], reference)
}

private fun text(register: Int, value: String) =
    ImmutableInstruction21c(Opcode.CONST_STRING, register, ImmutableStringReference(value))

private fun headerBody(): List<Instruction> =
    listOf(
        ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, 0, 17),
        text(1, "TITLE="),
        text(1, "ARTIST="),
        text(1, "ALBUM="),
        text(1, "LYRICS="),
        ImmutableInstruction21c(Opcode.SGET_OBJECT, 7, magic),
        ImmutableInstruction23x(Opcode.ADD_INT, 14, 12, 11),
        ImmutableInstruction21c(Opcode.NEW_INSTANCE, 2, ImmutableTypeReference(STREAM)),
        call(
            Opcode.INVOKE_DIRECT,
            ImmutableMethodReference(STREAM, "<init>", emptyList<String>(), "V"),
            2,
        ),
        call(
            Opcode.INVOKE_VIRTUAL,
            ImmutableMethodReference("Ljava/io/OutputStream;", "write", listOf("[B"), "V"),
            2,
            7,
        ),
        ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0, ImmutableTypeReference(RESULT)),
        call(
            Opcode.INVOKE_VIRTUAL,
            ImmutableMethodReference(STREAM, "toByteArray", emptyList<String>(), "[B"),
            2,
        ),
        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2),
        call(
            Opcode.INVOKE_STATIC,
            ImmutableMethodReference(
                "Lkotlin/jvm/internal/Intrinsics;",
                "checkNotNullExpressionValue",
                listOf(OBJECT, TEXT),
                "V",
            ),
            2,
            1,
        ),
        ImmutableInstruction21s(Opcode.CONST_16, 1, 9),
        call(Opcode.INVOKE_DIRECT, ctor, 0, 2, 14, 1),
        ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
    )

private fun edited(change: MutableList<Instruction>.() -> Unit) =
    headerBody().toMutableList().apply(change)

private fun jumping(to: Int, from: Int = 7): List<Instruction> {
    val base = edited { add(from, ImmutableInstruction21t(Opcode.IF_EQZ, 14, 0)) }
    base[from] = ImmutableInstruction21t(Opcode.IF_EQZ, 14, units(base.subList(from, to + 1)))
    return base
}

private fun constructorBody(kindStore: Instruction? = null): List<Instruction> =
    listOf(
        kindStore ?: ImmutableInstruction22c(Opcode.IPUT, 3, 0, fieldKind),
        ImmutableInstruction22c(Opcode.IPUT_OBJECT, 1, 0, fieldData),
        ImmutableInstruction22c(Opcode.IPUT, 2, 0, fieldOffset),
        call(
            Opcode.INVOKE_DIRECT,
            ImmutableMethodReference(OBJECT, "<init>", emptyList<String>(), "V"),
            0,
        ),
        ImmutableInstruction10x(Opcode.RETURN_VOID),
    )

private fun method(
    owner: String,
    name: String,
    arguments: List<String>,
    result: String,
    flags: Int,
    registers: Int,
    code: List<Instruction>,
    tries: List<TryBlock<out ExceptionHandler>> = emptyList(),
) =
    ImmutableMethod(
        owner,
        name,
        arguments.map { ImmutableMethodParameter(it, emptySet(), null) },
        result,
        flags,
        emptySet(),
        emptySet(),
        ImmutableMethodImplementation(registers, code, tries, emptyList()),
    )

private fun user(
    name: String,
    result: String,
    registers: Int,
    start: Int,
    reg: Int,
    nullPath: List<Instruction>,
    nonNull: List<Instruction>,
    guard: Boolean = true,
): Method {
    val test: Instruction =
        if (guard) ImmutableInstruction21t(Opcode.IF_NEZ, reg, 2 + units(nullPath))
        else ImmutableInstruction10x(Opcode.NOP)
    return method(
        USERS,
        name,
        params,
        result,
        9,
        registers,
        listOf(
            ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE, start, 8, header),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, reg),
            test,
        ) + nullPath + nonNull,
    )
}

private fun whole(
    nullPath: List<Instruction> =
        listOf(
            ImmutableInstruction12x(Opcode.MOVE_OBJECT, 2, 1),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 2),
        ),
    guard: Boolean = true,
) =
    user(
        "a",
        "[B",
        9,
        1,
        2,
        nullPath,
        listOf(
            ImmutableInstruction22c(Opcode.IGET, 3, 2, fieldOffset),
            ImmutableInstruction22c(Opcode.IGET_OBJECT, 2, 2, fieldData),
            ImmutableInstruction21c(Opcode.CHECK_CAST, 2, ImmutableTypeReference("[B")),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 2),
        ),
        guard,
    )

private fun stream(
    nullPath: List<Instruction> = listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
    nonNull: List<Instruction> =
        listOf(
            ImmutableInstruction22c(Opcode.IGET, 1, 0, fieldOffset),
            ImmutableInstruction22c(Opcode.IGET_OBJECT, 2, 0, fieldData),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        ),
) = user("b", "V", 12, 4, 0, nullPath, nonNull)

private fun classes(
    body: List<Instruction> = headerBody(),
    tries: List<TryBlock<out ExceptionHandler>> = emptyList(),
    magicText: String = "fLaC",
    magicFlags: Int = 25,
    users: List<Method> = listOf(whole(), stream()),
    constructorCode: List<Instruction> = constructorBody(),
    duplicate: Boolean = false,
    headerRegisters: Int = 25,
): List<ClassDef> {
    val methods =
        mutableListOf<Method>(
            method(
                OWNER,
                "<clinit>",
                emptyList(),
                "V",
                65544,
                1,
                listOf(
                    text(0, magicText),
                    ImmutableInstruction21c(Opcode.SPUT_OBJECT, 0, magic),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ),
            ),
            method(OWNER, "y", params, RESULT, 9, headerRegisters, body, tries),
        )
    if (duplicate) methods += method(OWNER, "x", params, RESULT, 9, headerRegisters, body, tries)
    fun type(name: String, fields: List<Field>, list: List<Method>) =
        ImmutableClassDef(name, 1, OBJECT, emptyList(), null, emptySet(), fields, list)
    fun field(owner: String, name: String, value: String, flags: Int) =
        ImmutableField(owner, name, value, flags, null, emptySet(), emptySet())
    return listOf(
        type(OWNER, listOf(field(OWNER, "z", "[B", magicFlags)), methods),
        type(
            RESULT,
            listOf(
                field(RESULT, "q", OBJECT, 1),
                field(RESULT, "w", "I", 1),
                field(RESULT, "e", "I", 1),
            ),
            listOf(
                method(RESULT, "<init>", listOf(OBJECT, "I", "I"), "V", 65537, 4, constructorCode)
            ),
        ),
        type(USERS, emptyList(), users),
    )
}

private fun rejects(reason: String, types: List<ClassDef>) {
    var rejected = false
    try {
        locateFlacMetadata(types)
    } catch (expected: IllegalStateException) {
        rejected = expected.message.orEmpty().startsWith(PREFIX)
    }
    ensure(rejected, reason)
}

private fun key(reference: com.android.tools.smali.dexlib2.iface.reference.Reference): String =
    when (reference) {
        is com.android.tools.smali.dexlib2.iface.reference.MethodReference ->
            "${reference.definingClass}->${reference.name}(" +
                reference.parameterTypes.joinToString("") +
                ")" +
                reference.returnType
        is com.android.tools.smali.dexlib2.iface.reference.FieldReference ->
            "${reference.definingClass}->${reference.name}:${reference.type}"
        is com.android.tools.smali.dexlib2.iface.reference.TypeReference -> reference.type
        is com.android.tools.smali.dexlib2.iface.reference.StringReference -> reference.string
        else -> reference.toString()
    }

private fun describe(value: Instruction): String = buildString {
    append(value.opcode)
    if (value is ReferenceInstruction) append(" ref=${key(value.reference)}")
    if (value is FiveRegisterInstruction)
        append(
            " args=${listOf(value.registerC, value.registerD, value.registerE, value.registerF, value.registerG).take(value.registerCount)}"
        )
    else if (value is RegisterRangeInstruction)
        append(" range=${value.startRegister}:${value.registerCount}")
    else {
        if (value is OneRegisterInstruction) append(" a=${value.registerA}")
        if (value is TwoRegisterInstruction) append(" b=${value.registerB}")
        if (value is ThreeRegisterInstruction) append(" c=${value.registerC}")
    }
    if (value is WideLiteralInstruction) append(" literal=${value.wideLiteral}")
}

/** Applies the hook to a copy and proves only the six new instructions differ. */
private fun applied(site: FlacMetadataSite): List<Instruction> {
    val source = site.method.implementation!!.instructions.toList()
    val copy = MutableMethod(site.method)
    injectFlacMetadata(copy, site)
    val patched = copy.implementation!!.instructions.toList()
    ensure(patched.size == source.size + 6, "Exactly six instructions are added")
    ensure(
        copy.implementation!!.registerCount == site.method.implementation!!.registerCount,
        "The header keeps its register count",
    )
    ensure(
        patched.take(site.at).map(::describe) == source.take(site.at).map(::describe) &&
            patched.drop(site.at + 6).map(::describe) == source.drop(site.at).map(::describe),
        "Every original instruction keeps its opcode, registers, reference and literal",
    )
    val inserted = patched.subList(site.at, site.at + 6)
    ensure(
        inserted.map(::describe) ==
            listOf(
                "MOVE_OBJECT_FROM16 a=${site.scratch} b=${site.original}",
                "INVOKE_STATIC ref=$FLAC_MERGE args=[${site.scratch}, ${site.metadata}, ${site.offset}]",
                "MOVE_RESULT_OBJECT a=${site.metadata}",
                "IF_NEZ a=${site.metadata}",
                "CONST_4 a=${site.scratch} literal=0",
                "RETURN_OBJECT a=${site.scratch}",
            ),
        "The merge tuple, null branch and null return use the scratch register",
    )
    return patched
}

private fun synthetic() {
    val base = classes()
    val site = locateFlacMetadata(base)
    ensure(
        site.holder == 0 &&
            site.metadata == 2 &&
            site.offset == 14 &&
            site.scratch == 1 &&
            site.original == 17 &&
            site.discriminator == 9 &&
            site.at == KIND &&
            site.constructor.definingClass == RESULT,
        "The hook derives the tuple from renamed types without native names",
    )
    val patched = applied(site)
    val tail = patched.drop(KIND + 6).map(::describe)
    ensure(
        tail ==
            listOf(
                "CONST_16 a=1 literal=9",
                "INVOKE_DIRECT ref=$CTOR_KEY args=[0, 2, 14, 1]",
                "RETURN_OBJECT a=0",
            ),
        "Original discriminator, constructor arguments, audio offset and return are unchanged",
    )
    ensure(
        patched.take(KIND).map(::describe) == headerBody().take(KIND).map(::describe),
        "Nothing before the discriminator changes",
    )
    locateFlacMetadata(classes(jumping(NEW)))
    ensure(true, "A loop exit may branch to the result allocation")

    rejects(
        "A header that already merges",
        classes(
            edited {
                addAll(
                    KIND,
                    listOf(
                        ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, 1, 17),
                        call(Opcode.INVOKE_STATIC, merge, 1, 2, 14),
                        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2),
                        ImmutableInstruction21t(Opcode.IF_NEZ, 2, 4),
                        ImmutableInstruction11n(Opcode.CONST_4, 1, 0),
                        ImmutableInstruction11x(Opcode.RETURN_OBJECT, 1),
                    ),
                )
            }
        ),
    )
    rejects(
        "Overwritten first argument",
        classes(edited { add(1, ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, 17, 0)) }),
    )
    rejects(
        "Overwritten later argument",
        classes(edited { add(1, ImmutableInstruction21s(Opcode.CONST_16, 20, 0)) }),
    )
    rejects(
        "Header without the argument copy",
        classes(edited { this[0] = ImmutableInstruction11n(Opcode.CONST_4, 0, 0) }),
    )
    rejects(
        "Range constructor encoding",
        classes(
            edited { this[BUILD] = ImmutableInstruction3rc(Opcode.INVOKE_DIRECT_RANGE, 0, 4, ctor) }
        ),
    )
    rejects(
        "Scratch aliasing metadata",
        classes(
            edited {
                this[CHECK] =
                    call(
                        Opcode.INVOKE_STATIC,
                        (this[CHECK] as ReferenceInstruction).reference as ImmutableMethodReference,
                        2,
                        2,
                    )
                this[KIND] = ImmutableInstruction21s(Opcode.CONST_16, 2, 9)
                this[BUILD] = call(Opcode.INVOKE_DIRECT, ctor, 0, 2, 14, 2)
            }
        ),
    )
    rejects(
        "Scratch aliasing the offset",
        classes(
            edited {
                this[CHECK] =
                    call(
                        Opcode.INVOKE_STATIC,
                        (this[CHECK] as ReferenceInstruction).reference as ImmutableMethodReference,
                        2,
                        14,
                    )
                this[KIND] = ImmutableInstruction21s(Opcode.CONST_16, 14, 9)
                this[BUILD] = call(Opcode.INVOKE_DIRECT, ctor, 0, 2, 14, 14)
            }
        ),
    )
    rejects(
        "Discriminator written to another register",
        classes(edited { this[KIND] = ImmutableInstruction21s(Opcode.CONST_16, 3, 9) }),
    )
    rejects(
        "Metadata is not the stream bytes",
        classes(edited { this[BUILD] = call(Opcode.INVOKE_DIRECT, ctor, 0, 3, 14, 1) }),
    )
    rejects(
        "Offset in the first argument register",
        classes(
            edited {
                this[0] = ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, 0, 15)
                this[6] = ImmutableInstruction23x(Opcode.ADD_INT, 15, 12, 11)
                this[BUILD] = call(Opcode.INVOKE_DIRECT, ctor, 0, 2, 15, 1)
            },
            headerRegisters = 23,
        ),
    )
    rejects(
        "Offset is not an addition",
        classes(edited { this[6] = ImmutableInstruction12x(Opcode.MOVE, 14, 12) }),
    )
    rejects(
        "Constructor receiver differs from the allocation",
        classes(edited { this[BUILD] = call(Opcode.INVOKE_DIRECT, ctor, 3, 2, 14, 1) }),
    )
    rejects(
        "Return of another register",
        classes(edited { this[FINISH] = ImmutableInstruction11x(Opcode.RETURN_OBJECT, 3) }),
    )
    rejects(
        "Constructor of another shape",
        classes(
            edited {
                this[BUILD] =
                    call(
                        Opcode.INVOKE_DIRECT,
                        ImmutableMethodReference(RESULT, "<init>", listOf(OBJECT, "I"), "V"),
                        0,
                        2,
                        14,
                    )
            }
        ),
    )
    rejects(
        "Second construction",
        classes(edited { add(call(Opcode.INVOKE_DIRECT, ctor, 0, 2, 14, 1)) }),
    )
    rejects(
        "Constructor stores the discriminator as payload",
        classes(
            constructorCode =
                constructorBody(ImmutableInstruction22c(Opcode.IPUT_OBJECT, 3, 0, fieldData))
        ),
    )
    rejects("Branch into the discriminator", classes(jumping(KIND)))
    rejects("Branch into the byte conversion", classes(jumping(TO_BYTES)))
    rejects("Branch into the constructor", classes(jumping(BUILD)))
    rejects("Branch into the return", classes(jumping(FINISH)))
    val pcs = headerBody().indices.map { units(headerBody().take(it)) }
    rejects(
        "Try block over the tail",
        classes(
            tries =
                listOf(
                    ImmutableTryBlock(
                        pcs[TO_BYTES],
                        pcs[FINISH] - pcs[TO_BYTES],
                        listOf(ImmutableExceptionHandler(null, 0)),
                    )
                )
        ),
    )
    rejects(
        "Handler into the tail",
        classes(
            tries =
                listOf(ImmutableTryBlock(0, 1, listOf(ImmutableExceptionHandler(null, pcs[KIND]))))
        ),
    )
    rejects("Wrong magic", classes(magicText = "fLaX"))
    rejects("Magic field is not static", classes(magicFlags = 17))
    rejects("Missing lyrics key", classes(edited { removeAt(4) }))
    rejects("Two matching headers", classes(duplicate = true))
    rejects(
        "Stream does not start with the magic",
        classes(
            edited {
                this[9] =
                    call(
                        Opcode.INVOKE_VIRTUAL,
                        ImmutableMethodReference(
                            "Ljava/io/OutputStream;",
                            "write",
                            listOf("[B"),
                            "V",
                        ),
                        2,
                        3,
                    )
            }
        ),
    )
    rejects("No consumers", classes(users = emptyList()))
    rejects("One consumer", classes(users = listOf(whole())))
    rejects("No streaming consumer", classes(users = listOf(whole(), whole())))
    rejects("No whole-file consumer", classes(users = listOf(stream(), stream())))
    rejects("Consumer without a null test", classes(users = listOf(whole(guard = false), stream())))
    rejects(
        "Null path reads the result",
        classes(
            users =
                listOf(
                    whole(),
                    stream(
                        nullPath =
                            listOf(
                                ImmutableInstruction22c(Opcode.IGET, 1, 0, fieldOffset),
                                ImmutableInstruction10x(Opcode.RETURN_VOID),
                            )
                    ),
                )
        ),
    )
    rejects(
        "Null path jumps into a reader",
        classes(
            users =
                listOf(whole(), stream(nullPath = listOf(ImmutableInstruction10t(Opcode.GOTO, 1))))
        ),
    )
    rejects(
        "Result escapes the streaming consumer",
        classes(
            users =
                listOf(
                    whole(),
                    stream(
                        nonNull =
                            listOf(
                                ImmutableInstruction22c(Opcode.IGET, 1, 0, fieldOffset),
                                ImmutableInstruction22c(Opcode.IGET_OBJECT, 2, 0, fieldData),
                                call(
                                    Opcode.INVOKE_VIRTUAL,
                                    ImmutableMethodReference(
                                        OBJECT,
                                        "hashCode",
                                        emptyList<String>(),
                                        "I",
                                    ),
                                    0,
                                ),
                                ImmutableInstruction10x(Opcode.RETURN_VOID),
                            )
                    ),
                )
        ),
    )
    rejects(
        "Consumer ignores the payload",
        classes(
            users =
                listOf(
                    whole(),
                    stream(
                        nonNull =
                            listOf(
                                ImmutableInstruction22c(Opcode.IGET, 1, 0, fieldOffset),
                                ImmutableInstruction10x(Opcode.RETURN_VOID),
                            )
                    ),
                )
        ),
    )
    rejects(
        "Whole-file consumer does not return its argument",
        classes(
            users =
                listOf(
                    whole(
                        nullPath =
                            listOf(
                                ImmutableInstruction11n(Opcode.CONST_4, 2, 0),
                                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 2),
                            )
                    ),
                    stream(),
                )
        ),
    )
}

fun main(args: Array<String>) {
    synthetic()
    for (path in args) {
        val apk = DexFileFactory.loadDexContainer(File(path), Opcodes.forApi(35))
        val all = apk.dexEntryNames.flatMap { apk.getEntry(it)!!.dexFile.classes.toList() }
        val site = locateFlacMetadata(all)
        val patched = applied(site)
        val tail = patched.drop(site.at + 6).take(3).map(::describe)
        ensure(
            tail.size == 3 &&
                tail[0].contains("literal=${site.discriminator}") &&
                tail[1].contains(
                    "args=[${site.holder}, ${site.metadata}, ${site.offset}, ${site.scratch}]"
                ) &&
                tail[2] == "RETURN_OBJECT a=${site.holder}",
            "Native discriminator, offset and constructor arguments are unchanged: $path",
        )
        println(
            "PASS: native FLAC metadata seam ${File(path).name} ${site.method.definingClass} " +
                "at=${site.at} holder=v${site.holder} metadata=v${site.metadata} " +
                "offset=v${site.offset} scratch=v${site.scratch} original=v${site.original} " +
                "discriminator=${site.discriminator}"
        )
    }
    println("PASS: $checks FLAC metadata hook checks")
}

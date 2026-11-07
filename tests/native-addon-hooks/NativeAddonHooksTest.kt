package app.bitchord.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.*
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.*
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.*
import com.android.tools.smali.dexlib2.immutable.instruction.*
import com.android.tools.smali.dexlib2.immutable.reference.*
import java.io.File

// The names are deliberately unlike the host's.
private const val HOST = "Lq/Host;"
private const val OBJECT = "Ljava/lang/Object;"
private const val TEXT = "Ljava/lang/String;"
private const val STATE = "Lq/State;"
private const val OWNER_A = "Lq/A;"
private const val OWNER_B = "Lq/B;"
private const val ADDON_BRANCH = 11
private val classId = ImmutableFieldReference(HOST, "\$r8\$classId", "I")
private val held = ImmutableFieldReference(HOST, "\$cachedPlaceholder", OBJECT)
private val suspended =
    ImmutableFieldReference(
        "Lkotlin/coroutines/intrinsics/CoroutineSingletons;",
        "COROUTINE_SUSPENDED",
        "Lkotlin/coroutines/intrinsics/CoroutineSingletons;",
    )
private val builder = "Lokhttp3/Request\$Builder;"
private val url = ImmutableMethodReference(builder, "url", listOf(TEXT), "V")
private val builderInit = ImmutableMethodReference(builder, "<init>", emptyList<String>(), "V")

private var checks = 0

private fun ensure(value: Boolean, reason: String) {
    checks++
    check(value) { reason }
}

/** Assembles instructions with symbolic labels, as the dex writer would lay them out. */
private class Assembly {
    private val items = mutableListOf<Pair<Int, (Map<String, Int>, Int) -> Instruction>>()
    private val labels = mutableMapOf<String, Int>()
    private var units = 0

    fun label(name: String) {
        labels[name] = units
    }

    fun add(instruction: Instruction) = add(instruction.codeUnits) { _, _ -> instruction }

    fun add(size: Int, build: (Map<String, Int>, Int) -> Instruction) {
        items += size to build
        units += size
    }

    fun jump(opcode: Opcode, register: Int, target: String) =
        add(3) { at, here -> ImmutableInstruction31t(opcode, register, at.getValue(target) - here) }

    fun table(name: String, targets: List<String>) {
        if (units % 2 != 0) add(ImmutableInstruction10x(Opcode.NOP))
        label(name)
        add(4 + 2 * targets.size) { at, here ->
            ImmutablePackedSwitchPayload(
                targets.mapIndexed { key, target ->
                    ImmutableSwitchElement(key, at.getValue(target) - tableOwner.getValue(name))
                }
            )
        }
    }

    // The switch instruction each table belongs to; offsets are relative to it.
    val tableOwner = mutableMapOf<String, Int>()

    fun build(): List<Instruction> {
        var here = 0
        return items.map { (size, build) -> build(labels, here).also { here += size } }
    }

    fun switch(register: Int, table: String) {
        tableOwner[table] = units
        add(3) { at, here ->
            ImmutableInstruction31t(Opcode.PACKED_SWITCH, register, at.getValue(table) - here)
        }
    }
}

private fun get(opcode: Opcode, register: Int, base: Int, field: ImmutableFieldReference) =
    ImmutableInstruction22c(opcode, register, base, field)

private fun invoke(opcode: Opcode, reference: ImmutableMethodReference, vararg registers: Int) =
    registers.toList().let {
        val r = it + List(5 - it.size) { 0 }
        ImmutableInstruction35c(opcode, it.size, r[0], r[1], r[2], r[3], r[4], reference)
    }

private fun Assembly.request() {
    add(ImmutableInstruction21c(Opcode.NEW_INSTANCE, 3, ImmutableTypeReference(builder)))
    add(invoke(Opcode.INVOKE_DIRECT, builderInit, 3))
    add(get(Opcode.IGET_OBJECT, 4, 1, held))
    add(ImmutableInstruction21c(Opcode.CHECK_CAST, 4, ImmutableTypeReference(TEXT)))
    add(invoke(Opcode.INVOKE_VIRTUAL, url, 3, 4))
    add(ImmutableInstruction21c(Opcode.CONST_STRING, 5, ImmutableStringReference("Retry-After")))
    add(
        ImmutableInstruction21c(
            Opcode.CONST_STRING,
            6,
            ImmutableStringReference("Not held by this addon"),
        )
    )
    add(ImmutableInstruction21c(Opcode.SGET_OBJECT, 7, suspended))
    add(ImmutableInstruction11x(Opcode.RETURN_OBJECT, 7))
}

/** The coroutine as R8 merges it: a switch on the class id, with the addon request in one case. */
private fun merged(
    sharedTail: Boolean = false,
    defaultIntoRequest: Boolean = false,
    readOnly: Boolean = false,
): List<Instruction> {
    val a = Assembly()
    a.add(ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, 1, 10))
    a.add(get(Opcode.IGET, 0, 1, classId))
    if (!readOnly) a.switch(0, "table")
    if (defaultIntoRequest)
        a.add(2) { at, here ->
            ImmutableInstruction20t(Opcode.GOTO_16, at.getValue("addon") - here)
        }
    else {
        a.add(ImmutableInstruction11n(Opcode.CONST_4, 2, 0))
        a.add(ImmutableInstruction11x(Opcode.RETURN_OBJECT, 2))
    }
    a.label("other")
    a.add(get(Opcode.IGET_OBJECT, 3, 1, held))
    a.add(ImmutableInstruction21c(Opcode.CHECK_CAST, 3, ImmutableTypeReference(STATE)))
    a.add(ImmutableInstruction11x(Opcode.RETURN_OBJECT, 3))
    a.label("third")
    if (sharedTail)
        a.add(2) { at, here ->
            ImmutableInstruction20t(Opcode.GOTO_16, at.getValue("addon") - here)
        }
    else a.add(ImmutableInstruction11x(Opcode.RETURN_OBJECT, 1))
    a.label("addon")
    a.request()
    if (!readOnly)
        a.table(
            "table",
            List(13) { if (it == ADDON_BRANCH) "addon" else if (it == 12) "third" else "other" },
        )
    return a.build()
}

/** The coroutine as BitChord 1.9 has it: the request alone, with no dispatch. */
private fun plain(): List<Instruction> {
    val a = Assembly()
    a.add(ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, 1, 10))
    a.request()
    return a.build()
}

private fun classOf(method: Method) =
    ImmutableClassDef(
        method.definingClass,
        1,
        OBJECT,
        emptyList<String>(),
        null,
        emptySet(),
        listOf(
            ImmutableField(
                method.definingClass,
                "\$r8\$classId",
                "I",
                0,
                null,
                emptySet(),
                emptySet(),
            ),
            ImmutableField(
                method.definingClass,
                "\$cachedPlaceholder",
                OBJECT,
                0,
                null,
                emptySet(),
                emptySet(),
            ),
        ),
        listOf(method),
    )

private fun host(code: List<Instruction>, registers: Int = 12) =
    ImmutableMethod(
        HOST,
        "invokeSuspend",
        listOf(ImmutableMethodParameter(OBJECT, emptySet(), null)),
        OBJECT,
        17,
        emptySet(),
        emptySet(),
        ImmutableMethodImplementation(registers, code, emptyList(), emptyList()),
    )

/** What the hook does on one pass through the method's entry, for a given branch and URL. */
private class Frame(val owned: Map<String, String>)

private object FrameClock

private fun walk(
    code: List<Instruction>,
    originalAt: Int,
    branch: Int,
    value: Any,
    owned: Map<String, String>,
    self: Int = 10,
): List<String> {
    val registers = mutableMapOf<Int, Any?>(self to "this")
    var result: Any? = null
    val events = mutableListOf<String>()
    var at = 0
    var steps = 0
    fun jump(instruction: Instruction) =
        (instruction as BuilderOffsetInstruction).target.location.index
    while (steps++ < 400) {
        if (at == originalAt) return events + "original"
        val instruction = code[at]
        val a = (instruction as? OneRegisterInstruction)?.registerA
        val b = (instruction as? TwoRegisterInstruction)?.registerB
        val reference = (instruction as? ReferenceInstruction)?.reference
        when (instruction.opcode) {
            Opcode.MOVE_OBJECT_FROM16 -> registers[a!!] = registers[b!!]
            Opcode.IGET -> registers[a!!] = branch
            Opcode.IGET_OBJECT -> registers[a!!] = value
            Opcode.CONST_4,
            Opcode.CONST_16 ->
                registers[a!!] = (instruction as WideLiteralInstruction).wideLiteral.toInt()
            Opcode.IF_NE ->
                if (registers[a!!] != registers[b!!]) {
                    at = jump(instruction)
                    continue
                }
            Opcode.IF_EQZ ->
                if (registers[a!!] == null) {
                    at = jump(instruction)
                    continue
                }
            Opcode.GOTO,
            Opcode.GOTO_16,
            Opcode.GOTO_32 -> {
                at = jump(instruction)
                continue
            }
            Opcode.CHECK_CAST -> {
                if (reference.toString() == TEXT && registers[a!!] !is String)
                    return events + "ClassCastException"
                events += "cast ${reference}"
            }
            Opcode.INVOKE_STATIC -> {
                val call = reference as MethodReference
                val argument =
                    registers[(instruction as FiveRegisterInstruction).registerC] as String
                events += "hook ${call.definingClass}"
                result =
                    owned[call.definingClass]
                        ?.takeIf { argument.startsWith(it) }
                        ?.let { "body of $it" }
            }
            Opcode.MOVE_RESULT_OBJECT -> registers[a!!] = result
            Opcode.RETURN_OBJECT -> return events + "return ${registers[a!!]}"
            else -> error("Unexpected ${instruction.opcode} at $at")
        }
        at++
    }
    error("Entry did not finish")
}

private val ownedA = mapOf(OWNER_A to "a://")
private val ownedB = mapOf(OWNER_B to "b://")
private val both = ownedA + ownedB

private fun hook(owner: String) = "$owner->bodyOrNull(Ljava/lang/String;)Ljava/lang/String;"

private fun install(method: MutableMethod, owner: String, label: String): MutableMethod {
    val site = locateAddonBody(listOf(classOf(method)))
    AddonBodyHost(method, site.url, site.branch).install(hook(owner), label)
    return method
}

private fun entryOf(method: Method) = method.implementation!!.instructions.toList()

/** Checks every dispatch branch of [method] after both owners are installed in [order]. */
private fun verify(
    name: String,
    method: MutableMethod,
    originalAt: Int,
    branches: List<Int>,
    guarded: Boolean,
) {
    val code = entryOf(method)
    for (branch in branches) {
        val other = guarded && branch != ADDON_BRANCH
        val events =
            walk(code, originalAt, branch, if (other) FrameClock else "https://example.org", both)
        if (other)
            ensure(
                events == listOf("original"),
                "$name: branch $branch reaches its own code untouched: $events",
            )
        else {
            ensure(events.first() == "cast $TEXT", "$name: addon branch reads the URL: $events")
            ensure(
                events.last() == "original",
                "$name: an external URL continues into the host: $events",
            )
        }
    }
    for ((owner, prefix) in both) {
        val events = walk(code, originalAt, ADDON_BRANCH, "$prefix/track", both)
        ensure(
            events.last() == "return body of $prefix" && events.count { it == "hook $owner" } == 1,
            "$name: $owner answers its own identifiers: $events",
        )
    }
}

private fun rejects(name: String, fragment: String, code: () -> List<Instruction>) {
    try {
        locateAddonBody(listOf(classOf(host(code()))))
        ensure(false, "$name was accepted")
    } catch (expected: IllegalStateException) {
        ensure(
            expected.message!!.contains(fragment),
            "$name refused for another reason: ${expected.message}",
        )
    }
}

private fun synthetic() {
    val source = merged()
    val site = locateAddonBody(listOf(classOf(host(source))))
    ensure(
        site.branch == ADDON_BRANCH,
        "The dispatch case that reaches the request is found: ${site.branch}",
    )
    ensure(site.url.name == "\$cachedPlaceholder", "The request URL field is found")

    // The first hook, with the guard the dispatch needs, in both installation orders.
    for ((first, second) in listOf(OWNER_A to OWNER_B, OWNER_B to OWNER_A)) {
        val method = MutableMethod(host(merged()))
        install(method, first, "external_a")
        install(method, second, "external_b")
        val added = entryOf(method).size - source.size
        verify("$first then $second", method, added, (0..13).toList() + 99, true)
    }

    // Without a guard the foreign branch's state reaches the cast, which is the failure to avoid.
    val plain = MutableMethod(host(merged()))
    val first = plain.implementation!!.instructions.first()
    plain.addInstructionsWithLabels(
        0,
        """
        move-object/from16 v0, p0
        iget-object v0, v0, $HOST->${'$'}cachedPlaceholder:$OBJECT
        check-cast v0, $TEXT
        invoke-static {v0}, ${hook(OWNER_A)}
        move-result-object v0
        if-eqz v0, :external
        return-object v0
        """,
        ExternalLabel("external", first),
    )
    ensure(
        walk(entryOf(plain), entryOf(plain).size - source.size, 0, FrameClock, both) ==
            listOf("ClassCastException"),
        "An unconditional cast fails on a branch that holds another object",
    )

    // BitChord 1.9 keeps the request in a method of its own, so nothing is dispatched.
    val single = MutableMethod(host(plain()))
    val plainSite = locateAddonBody(listOf(classOf(single)))
    ensure(plainSite.branch == null, "A method with no dispatch is not guarded")
    install(single, OWNER_A, "external_a")
    install(single, OWNER_B, "external_b")
    val unguarded = entryOf(single)
    ensure(
        unguarded.first().opcode == Opcode.MOVE_OBJECT_FROM16 &&
            unguarded.none { it.opcode == Opcode.IGET },
        "An unmerged request reads no class id",
    )
    for ((owner, prefix) in both) {
        val events = walk(unguarded, unguarded.size - plain().size, 0, "$prefix/track", both)
        ensure(
            events.last() == "return body of $prefix",
            "Unmerged: $owner answers its identifiers: $events",
        )
    }
    ensure(
        walk(unguarded, unguarded.size - plain().size, 0, "https://example.org", both).last() ==
            "original",
        "Unmerged: an external URL continues",
    )

    rejects("A second case into the request", "not one dispatch case") { merged(sharedTail = true) }
    rejects("A default into the request", "not one dispatch case") {
        merged(defaultIntoRequest = true)
    }
    rejects("A class id read with no dispatch", "dispatch changed") { merged(readOnly = true) }
}

private fun apk(path: String) {
    val dex = DexFileFactory.loadDexContainer(File(path), Opcodes.forApi(35))
    val all = dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes.toList() }
    val site = locateAddonBody(all)
    val size = site.method.implementation!!.instructions.count()
    for ((first, second) in listOf(OWNER_A to OWNER_B, OWNER_B to OWNER_A)) {
        val method = MutableMethod(site.method)
        install(method, first, "external_a")
        install(method, second, "external_b")
        val added = entryOf(method).size - size
        ensure(
            added > 0 && locateAddonBody(listOf(classOf(method))).branch == site.branch,
            "Hooked method is found again: $path",
        )
        verify(
            "$path $first then $second",
            method,
            added,
            (0..40).toList() + 99,
            site.branch != null,
        )
    }
    println(
        "PASS: addon request ${File(path).name} ${site.clazz.type}->${site.method.name} " +
            "branch=${site.branch ?: "none"}"
    )
}

fun main(args: Array<String>) {
    synthetic()
    for (path in args) apk(path)
    println("PASS: $checks addon request hook checks")
}

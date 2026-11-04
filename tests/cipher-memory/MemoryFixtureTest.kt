package app.bitchord.patches

import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.*
import com.android.tools.smali.dexlib2.immutable.instruction.*
import com.android.tools.smali.dexlib2.immutable.reference.*
import java.io.File

private const val TEXT = "Ljava/lang/String;"
private const val OBJ = "Ljava/lang/Object;"
private const val CONT = "Lkotlin/coroutines/Continuation;"
private const val ENGINE = "Lfixture/Engine;"
private const val SOLVER = "Lfixture/Solver;"
private const val BOUND = "Lfixture/Bound;"
private val evaluate =
    ImmutableMethodReference(ENGINE, "renamed", listOf(TEXT, "I", "Z", CONT), OBJ)
private val bridge =
    ImmutableMethodReference(ENGINE, "unused", listOf(ENGINE, TEXT, "I", "Z", CONT, "I", OBJ), OBJ)
private var checks = 0

private fun invoke(ref: ImmutableMethodReference, opcode: Opcode = Opcode.INVOKE_VIRTUAL) =
    ImmutableInstruction35c(opcode, 1, 0, 0, 0, 0, 0, ref)

private fun text(value: String) =
    ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(value))

private fun method(
    owner: String,
    name: String,
    params: List<String>,
    code: List<Instruction>,
    flags: Int = 1,
) =
    ImmutableMethod(
        owner,
        name,
        params.map { ImmutableMethodParameter(it, emptySet(), null) },
        OBJ,
        flags,
        emptySet(),
        emptySet(),
        ImmutableMethodImplementation(16, code, emptyList(), emptyList()),
    )

private fun klass(owner: String, methods: List<Method>): ClassDef =
    ImmutableClassDef(owner, 1, OBJ, emptyList(), null, emptySet(), emptyList(), methods)

private fun wrapper(register: Int): List<Instruction> =
    listOf(
        invoke(
            ImmutableMethodReference(
                "Ljava/lang/StringBuilder;",
                "toString",
                emptyList<String>(),
                TEXT,
            )
        ),
        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, register),
        ImmutableInstruction35c(
            Opcode.INVOKE_STATIC,
            1,
            register,
            0,
            0,
            0,
            0,
            ImmutableMethodReference(
                "Lkotlin/text/StringsKt__IndentKt;",
                "trimIndent",
                listOf(TEXT),
                TEXT,
            ),
        ),
        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, register),
    )

private fun fixture(): List<ClassDef> =
    listOf(
        klass(
            SOLVER,
            listOf(
                method(
                    SOLVER,
                    "obscured",
                    listOf(TEXT, TEXT, "Ljava/util/List;", "Z", CONT),
                    listOf(text("var payload = "), text("var r = jsc(payload);")) +
                        wrapper(2) +
                        listOf(invoke(evaluate)),
                )
            ),
        ),
        klass(
            ENGINE,
            listOf(
                method(
                    ENGINE,
                    evaluate.name,
                    evaluate.parameterTypes.map { it.toString() },
                    listOf(
                        ImmutableInstruction21c(
                            Opcode.NEW_INSTANCE,
                            0,
                            ImmutableTypeReference(BOUND),
                        )
                    ),
                ),
                method(
                    ENGINE,
                    bridge.name,
                    bridge.parameterTypes.map { it.toString() },
                    listOf(invoke(evaluate)),
                    0x1009,
                ),
            ),
        ),
        klass(
            BOUND,
            listOf(
                method(
                    BOUND,
                    "merged",
                    listOf(OBJ),
                    listOf(
                        text("const value = ("),
                        text("return text.length <= "),
                        text("Invalid QuickJS result limit"),
                    ) +
                        wrapper(0) +
                        listOf(
                            invoke(
                                ImmutableMethodReference(
                                    "Lcom/dokar/quickjs/QuickJs;",
                                    "setMaxStackSize",
                                    listOf("J"),
                                    "V",
                                )
                            ),
                            invoke(
                                ImmutableMethodReference(
                                    "Lcom/dokar/quickjs/QuickJs;",
                                    "gc",
                                    emptyList<String>(),
                                    "V",
                                )
                            ),
                            invoke(
                                ImmutableMethodReference(
                                    "Lcom/dokar/quickjs/QuickJs;",
                                    "gc",
                                    emptyList<String>(),
                                    "V",
                                )
                            ),
                            invoke(
                                ImmutableMethodReference(
                                    "Lfixture/Mutex;",
                                    "unlock",
                                    listOf(OBJ),
                                    "V",
                                )
                            ),
                        ),
                )
            ),
        ),
    )

private fun rewrite(all: List<ClassDef>, target: Method, code: List<Instruction>): List<ClassDef> =
    all.map { type ->
        klass(
            type.type,
            type.methods
                .map { old ->
                    if (old == target)
                        method(
                            old.definingClass,
                            old.name,
                            old.parameterTypes.map { it.toString() },
                            code,
                            old.accessFlags,
                        )
                    else old
                }
                .toList(),
        )
    }

private fun reject(label: String, work: () -> Unit) {
    try {
        work()
        error("Accepted $label")
    } catch (expected: IllegalStateException) {
        check(expected.message.orEmpty().startsWith("YouTube cipher script layout changed"))
        checks++
    }
}

private fun synthetic() {
    val base = fixture()
    val sites = locateCipherMemory(base)
    check(sites.size == 2)
    checks++
    check(sites.map { it.register } == listOf(2, 0))
    checks++
    for (site in sites) {
        removalPreservesBody(site)
        val body = site.method.implementation!!.instructions.toMutableList()
        reject("changed output register") {
            locateCipherMemory(
                rewrite(
                    base,
                    site.method,
                    body.toMutableList().apply {
                        this[site.instruction + 1] =
                            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 9)
                    },
                )
            )
        }
        reject("changed builder result") {
            locateCipherMemory(
                rewrite(
                    base,
                    site.method,
                    body.toMutableList().apply {
                        this[site.instruction - 1] =
                            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 9)
                    },
                )
            )
        }
        reject("missing copy") {
            locateCipherMemory(
                rewrite(
                    base,
                    site.method,
                    body.toMutableList().apply {
                        removeAt(site.instruction + 1)
                        removeAt(site.instruction)
                    },
                )
            )
        }
        reject("duplicate copy") {
            locateCipherMemory(rewrite(base, site.method, body + wrapper(site.register)))
        }
        reject("different builder") {
            locateCipherMemory(
                rewrite(
                    base,
                    site.method,
                    body.toMutableList().apply {
                        this[site.instruction - 2] =
                            invoke(
                                ImmutableMethodReference(
                                    "Lfixture/Builder;",
                                    "toString",
                                    emptyList<String>(),
                                    TEXT,
                                )
                            )
                    },
                )
            )
        }
    }
    reject("new generic caller") {
        locateCipherMemory(
            base +
                klass(
                    "Lfixture/Caller;",
                    listOf(
                        method("Lfixture/Caller;", "call", emptyList(), listOf(invoke(evaluate)))
                    ),
                )
        )
    }
    reject("used default bridge") {
        locateCipherMemory(
            base +
                klass(
                    "Lfixture/Caller;",
                    listOf(
                        method(
                            "Lfixture/Caller;",
                            "call",
                            emptyList(),
                            listOf(invoke(bridge, Opcode.INVOKE_STATIC)),
                        )
                    ),
                )
        )
    }
    val bound = sites.last().method
    val body = bound.implementation!!.instructions.toList()
    reject("removed cleanup") {
        locateCipherMemory(
            rewrite(
                base,
                bound,
                body.filterNot {
                    (it as? com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)
                        ?.reference
                        .toString()
                        .contains("->gc(")
                },
            )
        )
    }
    reject("changed result bound") {
        locateCipherMemory(
            rewrite(base, bound, body.toMutableList().apply { this[2] = text("changed limit") })
        )
    }
}

private fun instructionValue(value: Instruction): String = buildString {
    append(value.opcode)
    if (value is com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)
        append(" ref=${value.reference}")
    if (value is com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction)
        append(" a=${value.registerA}")
    if (value is com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction)
        append(" b=${value.registerB}")
    if (value is com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction)
        append(" c=${value.registerC}")
    if (value is com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction)
        append(
            " args=${listOf(value.registerC,value.registerD,value.registerE,value.registerF,value.registerG).take(value.registerCount)}"
        )
    if (value is com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction)
        append(" range=${value.startRegister}:${value.registerCount}")
    if (value is com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction)
        append(" literal=${value.wideLiteral}")
}

private fun removalPreservesBody(site: CipherMemorySite) {
    val source = site.method.implementation!!.instructions.toList()
    val expected =
        source
            .filterIndexed { index, _ ->
                index != site.instruction && index != site.instruction + 1
            }
            .map(::instructionValue)
    val changed = MutableMethod(site.method)
    changed.removeInstructions(site.instruction, 2)
    check(changed.implementation!!.instructions.map(::instructionValue) == expected) {
        "Removing indentation changed another opcode, reference, literal or register"
    }
    checks++
}

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        synthetic()
        println("PASS: $checks cipher memory guards")
        return
    }
    for (path in args) {
        val all =
            File(path)
                .listFiles()!!
                .filter { it.extension == "dex" }
                .flatMap { DexFileFactory.loadDexFile(it, null).classes }
        val sites = locateCipherMemory(all)
        check(sites.size == 2)
        for (site in sites) {
            removalPreservesBody(site)
            println(
                "PASS $path ${site.method.definingClass}->${site.method.name} at=${site.instruction} register=${site.register}; remaining instruction fields preserved"
            )
        }
    }
}

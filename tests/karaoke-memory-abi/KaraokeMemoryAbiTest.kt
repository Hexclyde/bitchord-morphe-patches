package app.bitchord.patches

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.*
import com.android.tools.smali.dexlib2.immutable.instruction.*
import com.android.tools.smali.dexlib2.immutable.reference.*
import java.io.File

private const val T = "Lai/onnxruntime/OnnxTensor;"
private const val B = "Ljava/nio/ByteBuffer;"
private const val O = "Ljava/nio/ByteOrder;"
private const val INFO = "Lai/onnxruntime/TensorInfo;"
private const val TYPE = "Lai/onnxruntime/OnnxJavaType;"
private var checks = 0

private fun call(owner: String, name: String, parameters: List<String>, result: String) =
    ImmutableMethodReference(owner, name, parameters, result)

private fun method(
    name: String,
    parameters: List<String>,
    result: String,
    flags: Int,
    code: List<Instruction>?,
    owner: String = T,
) =
    ImmutableMethod(
        owner,
        name,
        parameters.map { ImmutableMethodParameter(it, emptySet(), null) },
        result,
        flags,
        emptySet(),
        emptySet(),
        code?.let { ImmutableMethodImplementation(5, it, emptyList(), emptyList()) },
    )

private fun type(
    name: String,
    methods: List<Method> = emptyList(),
    fields: List<ImmutableField> = emptyList(),
): ClassDef =
    ImmutableClassDef(name, 1, "Ljava/lang/Object;", emptyList(), null, emptySet(), fields, methods)

private fun code(): List<Instruction> =
    listOf(
        ImmutableInstruction21c(
            Opcode.SGET_WIDE,
            0,
            ImmutableFieldReference("Lai/onnxruntime/OnnxRuntime;", "ortApiHandle", "J"),
        ),
        ImmutableInstruction22c(
            Opcode.IGET_WIDE,
            2,
            4,
            ImmutableFieldReference("Lai/onnxruntime/OnnxTensorLike;", "nativeHandle", "J"),
        ),
        ImmutableInstruction35c(
            Opcode.INVOKE_DIRECT,
            5,
            4,
            0,
            1,
            2,
            3,
            call(T, "getBuffer", listOf("J", "J"), B),
        ),
        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
        ImmutableInstruction35c(
            Opcode.INVOKE_STATIC,
            0,
            0,
            0,
            0,
            0,
            0,
            call(O, "nativeOrder", emptyList(), O),
        ),
        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1),
        ImmutableInstruction35c(
            Opcode.INVOKE_VIRTUAL,
            2,
            0,
            1,
            0,
            0,
            0,
            call(B, "order", listOf(O), B),
        ),
        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
        ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
    )

private fun fixture(
    body: List<Instruction> = code(),
    visibility: Int = 2,
    nativeFlags: Int = 258,
    result: String = B,
    metadataFlags: Int = 17,
    hasFloat: Boolean = true,
): List<ClassDef> =
    listOf(
        type(
            T,
            listOf(
                method("getBuffer", emptyList(), result, visibility, body),
                method("getBuffer", listOf("J", "J"), B, nativeFlags, null),
            ),
        ),
        type(
            "Lai/onnxruntime/OnnxTensorLike;",
            listOf(method("getInfo", emptyList(), INFO, 1, null, "Lai/onnxruntime/OnnxTensorLike;")),
        ),
        type(
            INFO,
            listOf(method("getShape", emptyList(), "[J", 1, null, INFO)),
            fields =
                listOf(
                    ImmutableField(INFO, "type", TYPE, metadataFlags, null, emptySet(), emptySet())
                ),
        ),
        type(
            TYPE,
            fields =
                if (hasFloat)
                    listOf(ImmutableField(TYPE, "FLOAT", TYPE, 16409, null, emptySet(), emptySet()))
                else emptyList(),
        ),
    )

private fun rejected(label: String, classes: List<ClassDef>) {
    checks++
    try {
        verifyKaraokeTensorMemory(classes)
    } catch (failure: IllegalStateException) {
        check(failure.message.orEmpty().startsWith("Karaoke tensor memory ABI changed:"))
        return
    }
    error("Accepted changed tensor memory ABI: $label")
}

fun main(args: Array<String>) {
    verifyKaraokeTensorMemory(fixture())
    checks++
    rejected("missing", fixture().drop(1))
    rejected("static view", fixture(visibility = 10))
    rejected("public view", fixture(visibility = 1))
    rejected("wrong return", fixture(result = "Ljava/nio/FloatBuffer;"))
    rejected("static native", fixture(nativeFlags = 266))
    rejected("metadata static", fixture(metadataFlags = 25))
    rejected("missing float", fixture(hasFloat = false))
    rejected("copy", fixture(body = code() + ImmutableInstruction10x(Opcode.RETURN_VOID)))
    rejected(
        "wrong order",
        fixture(
            body =
                code().toMutableList().also {
                    it[4] =
                        ImmutableInstruction35c(
                            Opcode.INVOKE_STATIC,
                            0,
                            0,
                            0,
                            0,
                            0,
                            0,
                            call(O, "BIG_ENDIAN", emptyList(), O),
                        )
                }
        ),
    )
    rejected(
        "swapped handles",
        fixture(
            body =
                code().toMutableList().also {
                    it[2] =
                        ImmutableInstruction35c(
                            Opcode.INVOKE_DIRECT,
                            5,
                            4,
                            2,
                            3,
                            0,
                            1,
                            call(T, "getBuffer", listOf("J", "J"), B),
                        )
                }
        ),
    )
    rejected(
        "wrong returned view",
        fixture(
            body =
                code().toMutableList().also {
                    it[8] = ImmutableInstruction11x(Opcode.RETURN_OBJECT, 1)
                }
        ),
    )
    for (path in args) {
        val container = DexFileFactory.loadDexContainer(File(path), null)
        val classes = container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }
        verifyKaraokeTensorMemory(classes)
        checks++
        println("PASS: borrowed tensor ABI ${File(path).name}")
    }
    println("PASS: $checks borrowed tensor ABI checks")
}

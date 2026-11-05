package app.bitchord.patches

import app.morphe.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val TENSOR = "Lai/onnxruntime/OnnxTensor;"
private const val BUFFER = "Ljava/nio/ByteBuffer;"

/** The borrowed view stays valid only while the owning tensor is open. */
internal fun verifyKaraokeTensorMemory(classes: Iterable<ClassDef>) {
    fun requireAbi(okay: Boolean, label: String) =
        check(okay) { "Karaoke tensor memory ABI changed: $label" }
    val all = classes.associateBy { it.type }
    val tensor = all[TENSOR] ?: error("Karaoke tensor memory ABI changed: tensor missing")
    val views =
        tensor.methods.filter {
            it.name == "getBuffer" && it.parameterTypes.isEmpty() && it.returnType == BUFFER
        }
    requireAbi(views.size == 1, "borrowed buffer signature")
    val view = views.single()
    requireAbi(view.accessFlags == 2, "borrowed buffer visibility")
    val code = view.implementation?.instructions?.toList().orEmpty()
    requireAbi(
        code.map { it.opcode } ==
            listOf(
                Opcode.SGET_WIDE,
                Opcode.IGET_WIDE,
                Opcode.INVOKE_DIRECT,
                Opcode.MOVE_RESULT_OBJECT,
                Opcode.INVOKE_STATIC,
                Opcode.MOVE_RESULT_OBJECT,
                Opcode.INVOKE_VIRTUAL,
                Opcode.MOVE_RESULT_OBJECT,
                Opcode.RETURN_OBJECT,
            ),
        "borrowed buffer body",
    )
    fun method(index: Int) = (code[index] as? ReferenceInstruction)?.reference as? MethodReference
    fun field(index: Int) = (code[index] as? ReferenceInstruction)?.reference as? FieldReference
    val api = field(0)
    val handle = field(1)
    requireAbi(
        api?.definingClass == "Lai/onnxruntime/OnnxRuntime;" &&
            api.name == "ortApiHandle" &&
            api.type == "J",
        "runtime handle",
    )
    requireAbi(
        handle?.definingClass == "Lai/onnxruntime/OnnxTensorLike;" &&
            handle.name == "nativeHandle" &&
            handle.type == "J",
        "tensor handle",
    )
    val native = method(2)
    requireAbi(
        native?.definingClass == TENSOR &&
            native.name == "getBuffer" &&
            native.parameterTypes.map { it.toString() } == listOf("J", "J") &&
            native.returnType == BUFFER,
        "native buffer call",
    )
    requireAbi(
        tensor.methods.count {
            it.name == "getBuffer" &&
                it.parameterTypes.map { type -> type.toString() } == listOf("J", "J") &&
                it.returnType == BUFFER &&
                it.accessFlags == 258 &&
                it.implementation == null
        } == 1,
        "native buffer entry",
    )
    val order = method(4)
    val setOrder = method(6)
    requireAbi(
        order?.definingClass == "Ljava/nio/ByteOrder;" &&
            order.name == "nativeOrder" &&
            order.parameterTypes.isEmpty() &&
            order.returnType == "Ljava/nio/ByteOrder;",
        "native byte order",
    )
    requireAbi(
        setOrder?.definingClass == BUFFER &&
            setOrder.name == "order" &&
            setOrder.parameterTypes.map { it.toString() } == listOf("Ljava/nio/ByteOrder;") &&
            setOrder.returnType == BUFFER,
        "buffer byte order",
    )
    val receiver = view.implementation!!.registerCount - 1
    val apiRegister = (code[0] as OneRegisterInstruction).registerA
    val handleRegisters = code[1] as TwoRegisterInstruction
    val call = code[2] as FiveRegisterInstruction
    val bufferRegister = (code[3] as OneRegisterInstruction).registerA
    val orderCall = code[4] as FiveRegisterInstruction
    val orderRegister = (code[5] as OneRegisterInstruction).registerA
    val ordered = code[6] as FiveRegisterInstruction
    requireAbi(
        handleRegisters.registerB == receiver &&
            call.registerCount == 5 &&
            call.registerC == receiver &&
            call.registerD == apiRegister &&
            call.registerE == apiRegister + 1 &&
            call.registerF == handleRegisters.registerA &&
            call.registerG == handleRegisters.registerA + 1 &&
            orderCall.registerCount == 0 &&
            ordered.registerCount == 2 &&
            ordered.registerC == bufferRegister &&
            ordered.registerD == orderRegister &&
            (code[7] as OneRegisterInstruction).registerA ==
                (code[8] as OneRegisterInstruction).registerA,
        "borrowed view data flow",
    )
    requireAbi(
        all["Lai/onnxruntime/TensorInfo;"]?.fields?.count {
            it.name == "type" &&
                it.type == "Lai/onnxruntime/OnnxJavaType;" &&
                it.accessFlags and 9 == 1
        } == 1,
        "tensor element type",
    )
    requireAbi(
        all["Lai/onnxruntime/OnnxJavaType;"]?.fields?.count {
            it.name == "FLOAT" &&
                it.type == "Lai/onnxruntime/OnnxJavaType;" &&
                it.accessFlags and 9 == 9
        } == 1,
        "float element type",
    )
    requireAbi(
        all["Lai/onnxruntime/OnnxTensorLike;"]?.methods?.count {
            it.name == "getInfo" &&
                it.parameterTypes.isEmpty() &&
                it.returnType == "Lai/onnxruntime/TensorInfo;" &&
                it.accessFlags and 9 == 1
        } == 1,
        "tensor metadata access",
    )
    requireAbi(
        all["Lai/onnxruntime/TensorInfo;"]?.methods?.count {
            it.name == "getShape" &&
                it.parameterTypes.isEmpty() &&
                it.returnType == "[J" &&
                it.accessFlags and 9 == 1
        } == 1,
        "tensor shape access",
    )
}

internal fun BytecodePatchContext.verifyKaraokeMemoryAbi() {
    val all = mutableListOf<ClassDef>()
    classDefForEach { all += it }
    verifyKaraokeTensorMemory(all)
}

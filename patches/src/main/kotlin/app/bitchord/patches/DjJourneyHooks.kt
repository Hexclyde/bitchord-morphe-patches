package app.bitchord.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.*
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal fun BytecodePatchContext.addDjJourney() {
    val player = classDefBy("Landroidx/media3/common/Player;")
    for ((name, result) in listOf("getShuffleModeEnabled" to "Z", "getNextMediaItemIndex" to "I")) {
        check(
            player.methods.count {
                it.name == name && it.parameterTypes.isEmpty() && it.returnType == result
            } == 1
        ) {
            "DJ queue player API changed: $name"
        }
    }
    // BitChord's in-app shuffle state is read at run time; a build that has the class must expose
    // it.
    val shuffles = mutableListOf<ClassDef>()
    classDefForEach { candidate ->
        if (candidate.type == "Lcom/music/bitchord/playback/QueueShuffle;") shuffles.add(candidate)
    }
    check(shuffles.size <= 1) { "DJ queue shuffle state owner is not unique" }
    shuffles.singleOrNull()?.let { shuffle ->
        val states =
            setOf(
                "Lkotlinx/coroutines/flow/StateFlow;",
                "Lkotlinx/coroutines/flow/ReadonlyStateFlow;",
            )
        check(
            shuffle.methods.count {
                it.name == "getEnabled" &&
                    it.parameterTypes.isEmpty() &&
                    it.returnType in states &&
                    it.accessFlags and 1 != 0
            } == 1
        ) {
            "DJ queue shuffle state changed"
        }
    }
    val owners = mutableListOf<ClassDef>()
    classDefForEach { candidate ->
        if (
            candidate.fields.count {
                it.name == "lastSmartPlan" && it.type.endsWith("/TransitionPlan;")
            } == 1
        )
            owners.add(candidate)
    }
    check(owners.size == 1) { "DJ transition plan owner changed (${owners.size})" }
    val original = owners.single()
    val field = original.fields.single { it.name == "lastSmartPlan" }
    val methods =
        original.methods.filter { method ->
            val code = method.implementation?.instructions?.toList().orEmpty()
            code.any { instruction ->
                val target = (instruction as? ReferenceInstruction)?.reference as? FieldReference
                instruction.opcode == Opcode.IPUT_OBJECT &&
                    target?.definingClass == original.type &&
                    target.name == field.name
            } &&
                code.any { instruction ->
                    val target =
                        (instruction as? ReferenceInstruction)?.reference as? MethodReference
                    target?.returnType == field.type
                }
        }
    check(methods.size == 1) { "DJ transition planning boundary changed (${methods.size})" }
    val method =
        mutableClassDefBy(original).methods.single {
            it.name == methods.single().name && it.parameterTypes == methods.single().parameterTypes
        }
    val stores =
        method.implementation!!.instructions.withIndex().filter { (_, instruction) ->
            val target = (instruction as? ReferenceInstruction)?.reference as? FieldReference
            instruction.opcode == Opcode.IPUT_OBJECT &&
                target?.definingClass == original.type &&
                target.name == field.name
        }
    check(stores.size == 1) { "DJ transition plan publication changed (${stores.size})" }
    val (index, instruction) = stores.single()
    val store = instruction as TwoRegisterInstruction
    // Keep the store's branch entries on the hook and the following entries after the store.
    method.addInstruction(
        index,
        "invoke-static/range {v${store.registerA} .. v${store.registerA}}, Lapp/bitchord/telegram/DjMode;->plan(Ljava/lang/Object;)V",
    )
}

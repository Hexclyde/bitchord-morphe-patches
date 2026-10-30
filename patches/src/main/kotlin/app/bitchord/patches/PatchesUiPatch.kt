package app.bitchord.patches

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.*
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation

private const val UI = "Lapp/bitchord/telegram/ExtrasUi;"
private const val GROUP =
    "Ljava/lang/String;Ljava/lang/String;FLandroidx/compose/runtime/internal/ComposableLambdaImpl;Landroidx/compose/runtime/ComposerImpl;II"
private const val NS = "http://schemas.android.com/apk/res/android"

private object PatchesApplicationCreate :
    Fingerprint(
        definingClass = "Lcom/music/bitchord/BitChordApplication;",
        name = "onCreate",
        parameters = emptyList(),
        returnType = "V",
    )

/** Shared settings and sound controls contain no Telegram account or TDLib implementation. */
internal val patchesUiRuntime = bytecodePatch { extendWith("extensions/patches-ui.mpe") }

private val patchesUiResources = resourcePatch {
    execute {
        // ReAndroid derives a value's resource type from its XML filename. Append
        // to styles.xml so the new entries are registered as styles while the
        // original BitChord styles remain intact.
        document("res/values/styles.xml").use { doc ->
            fun addTheme(name: String, parent: String, dark: Boolean) {
                val style = doc.createElement("style")
                style.setAttribute("name", name)
                style.setAttribute("parent", parent)
                val foreground = if (dark) "#FFFFFFFF" else "#FF000000"
                val background = if (dark) "#FF000000" else "#FFFFFFFF"
                val secondary = if (dark) "#FF8E8E93" else "#FF6E6E73"
                val values =
                    linkedMapOf(
                        "windowBackground" to background,
                        "colorBackground" to background,
                        "colorAccent" to foreground,
                        "colorControlActivated" to foreground,
                        "colorControlNormal" to secondary,
                        "colorControlHighlight" to if (dark) "#228E8E93" else "#22000000",
                        "textColorPrimary" to foreground,
                        "textColorSecondary" to secondary,
                        "windowLightStatusBar" to (!dark).toString(),
                        "windowLightNavigationBar" to (!dark).toString(),
                    )
                for ((attribute, value) in values) {
                    val item = doc.createElement("item")
                    item.setAttribute("name", "android:$attribute")
                    item.textContent = value
                    style.appendChild(item)
                }
                doc.documentElement.appendChild(style)
            }
            addTheme("PatchNativeTheme", "@android:style/Theme.Material.NoActionBar", true)
            addTheme(
                "PatchNativeThemeLight",
                "@android:style/Theme.Material.Light.NoActionBar",
                false,
            )
        }
        document("AndroidManifest.xml").use { doc ->
            val app = doc.getElementsByTagName("application").item(0) as org.w3c.dom.Element
            val activity = doc.createElement("activity")
            activity.setAttributeNS(NS, "android:name", "app.bitchord.telegram.ExtrasActivity")
            activity.setAttributeNS(NS, "android:exported", "false")
            activity.setAttributeNS(NS, "android:label", "Patches & tweaks")
            activity.setAttributeNS(
                NS,
                "android:theme",
                "@android:style/Theme.Material.NoActionBar",
            )
            app.appendChild(activity)
        }
    }
}

private fun registers(instruction: Instruction): List<Int> =
    when (instruction) {
        is RegisterRangeInstruction ->
            (instruction.startRegister until instruction.startRegister + instruction.registerCount)
                .toList()
        is FiveRegisterInstruction ->
            listOf(
                    instruction.registerC,
                    instruction.registerD,
                    instruction.registerE,
                    instruction.registerF,
                    instruction.registerG,
                )
                .take(instruction.registerCount)
        else -> error("Unexpected settings call encoding")
    }

/** One shared entry, regardless of the order in which optional patches were selected. */
internal val extrasSettingsPatch = bytecodePatch {
    dependsOn(patchesUiRuntime, patchesUiResources)
    execute {
        val screen = mutableClassDefBy("Lcom/music/bitchord/ui/screens/SettingsSheetKt;")
        val method = screen.methods.single { it.name.startsWith("SettingsScreen-") }
        val code = method.implementation!!.instructions.toList()
        val groups =
            code.indices.filter { i ->
                val ref = (code[i] as? ReferenceInstruction)?.reference as? MethodReference
                ref?.definingClass == screen.type && ref.name.startsWith("SearchableSettingsGroup-")
            }
        check(groups.size >= 3) { "Settings groups not found" }
        val composer = registers(code[groups.first()])[4]
        method.addInstruction(
            groups.first() + 1,
            "invoke-static/range {v$composer .. v$composer}, $UI->tweaksSection(Landroidx/compose/runtime/ComposerImpl;)V",
        )
        val group =
            screen.methods.single {
                it.name.startsWith("SettingsGroup-") && it.parameterTypes.joinToString("") == GROUP
            }
        val ui = mutableClassDefBy(UI)
        val original = ui.methods.single { it.name == "hostGroup" }
        val replacement =
            MutableMethod(
                ImmutableMethod(
                    original.definingClass,
                    original.name,
                    original.parameters,
                    original.returnType,
                    original.accessFlags,
                    original.annotations,
                    original.hiddenApiRestrictions,
                    ImmutableMethodImplementation(
                        8,
                        emptyList<Instruction>(),
                        emptyList(),
                        emptyList(),
                    ),
                )
            )
        replacement.addInstructions(
            0,
            "invoke-static/range {p0 .. p6}, ${group.definingClass}->${group.name}($GROUP)V\nreturn-void",
        )
        ui.methods.remove(original)
        ui.methods.add(replacement)

        val start = PatchesApplicationCreate.method
        val returns =
            start.implementation!!
                .instructions
                .withIndex()
                .filter { it.value.opcode == Opcode.RETURN_VOID }
                .map { it.index }
        check(returns.isNotEmpty()) { "Application startup hook not found" }
        for (index in returns.reversed()) start.addInstruction(
            index,
            "invoke-static/range {p0 .. p0}, $UI->start(Landroid/content/Context;)V",
        )

        // R8 merged SourcesScreen into DiscordScreenKt in the pinned BitChord APK.
        // Wrap only its collected UI state. The registry, playback resolver and search
        // retain their original source configurations and enabled state.
        val sources =
            mutableClassDefBy("Lcom/music/bitchord/ui/screens/DiscordScreenKt;").methods.single {
                it.name == "SourcesScreen"
            }
        val sourceCode = sources.implementation!!.instructions.toList()
        val reads =
            sourceCode.indices.filter { i ->
                val field = (sourceCode[i] as? ReferenceInstruction)?.reference as? FieldReference
                sourceCode[i].opcode == Opcode.SGET_OBJECT &&
                    field?.definingClass == "Lcom/music/bitchord/data/sources/SourceRegistry;" &&
                    field.name == "configs"
            }
        check(reads.size == 1) { "Sources UI configuration read is not unique" }
        val read = reads.single()
        val collect = (sourceCode[read + 1] as? ReferenceInstruction)?.reference as? MethodReference
        check(
            collect?.name == "collectAsStateWithLifecycle" &&
                collect.returnType == "Landroidx/compose/runtime/MutableState;" &&
                sourceCode[read + 2].opcode == Opcode.MOVE_RESULT_OBJECT
        ) {
            "Sources UI state collection changed"
        }
        val state = (sourceCode[read + 2] as OneRegisterInstruction).registerA
        sources.addInstructions(
            read + 3,
            """
            invoke-static/range {v$state .. v$state}, Lapp/bitchord/telegram/PatchSources;->visibleState(Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v$state
            check-cast v$state, Landroidx/compose/runtime/MutableState;
        """,
        )
        println("Added Patches & tweaks and isolated its managed sources from the Sources UI")
    }
}

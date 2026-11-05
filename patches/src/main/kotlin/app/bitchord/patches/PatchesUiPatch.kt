package app.bitchord.patches

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
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
private const val SOURCE_CONFIG = "Lcom/music/bitchord/data/sources/SourceConfig;"
private const val STRING = "Ljava/lang/String;"
private const val FUNCTION0 = "Lkotlin/jvm/functions/Function0;"
private const val FUNCTION1 = "Lkotlin/jvm/functions/Function1;"

// BitChord 1.9 builds a source row from seven values.
private val SOURCE_CONFIG_LEGACY_CONSTRUCTOR =
    listOf(STRING, "Lcom/music/bitchord/data/sources/SourceKind;", STRING, STRING, "Z", "Z", "Z")

// Addons that publish quality choices add the list and the picked value.
private val SOURCE_CONFIG_QUALITY_CONSTRUCTOR =
    SOURCE_CONFIG_LEGACY_CONSTRUCTOR + listOf("Ljava/util/List;", STRING)

private val SOURCES_SCREEN_PREFIX = listOf("Landroidx/compose/foundation/layout/PaddingValuesImpl;")
private val SOURCES_SCREEN_SUFFIX =
    listOf("Landroidx/compose/ui/Modifier;", "Landroidx/compose/runtime/ComposerImpl;", "I")

// Edit, WebDAV, SMB and JioSaavn callbacks.
private val SOURCES_SCREEN_LEGACY =
    SOURCES_SCREEN_PREFIX +
        listOf(FUNCTION1, FUNCTION0, FUNCTION0, FUNCTION0) +
        SOURCES_SCREEN_SUFFIX

// The quality chooser callback follows the edit callback.
private val SOURCES_SCREEN_QUALITY =
    SOURCES_SCREEN_PREFIX +
        listOf(FUNCTION1, FUNCTION1, FUNCTION0, FUNCTION0, FUNCTION0) +
        SOURCES_SCREEN_SUFFIX

private object PatchesApplicationCreate :
    Fingerprint(
        definingClass = "Lcom/music/bitchord/BitChordApplication;",
        name = "onCreate",
        parameters = emptyList(),
        returnType = "V",
    )

/** Whether SourceConfig carries the quality options, from its constructor and fields. */
internal fun BytecodePatchContext.qualitySourceShape(): Boolean {
    val config = classDefBy(SOURCE_CONFIG)
    val legacyConfig =
        config.methods.count {
            it.name == "<init>" &&
                it.accessFlags and 1 != 0 &&
                it.parameterTypes == SOURCE_CONFIG_LEGACY_CONSTRUCTOR
        }
    val qualityConfig =
        config.methods.count {
            it.name == "<init>" &&
                it.accessFlags and 1 != 0 &&
                it.parameterTypes == SOURCE_CONFIG_QUALITY_CONSTRUCTOR
        }
    val qualityFields =
        config.fields.count {
            (it.name == "qualityOptions" && it.type == "Ljava/util/List;") ||
                (it.name == "pickedQuality" && it.type == STRING)
        }
    check(
        (legacyConfig == 1 && qualityConfig == 0 && qualityFields == 0) ||
            (legacyConfig == 0 && qualityConfig == 1 && qualityFields == 2)
    ) {
        "Source configuration constructor is not a known BitChord shape"
    }
    return qualityConfig == 1
}

internal val cipherMemoryRuntime = bytecodePatch { execute { addCipherMemoryHooks() } }

/** Shared settings and sound controls contain no Telegram account or TDLib implementation. */
internal val patchesUiRuntime = bytecodePatch {
    dependsOn(cipherMemoryRuntime, audioOriginPatch, selectedDownloadsPatch, sharedAudioResources)
    extendWith("extensions/patches-ui.mpe")
    execute {
        addSourceAudioDecoder()
        addYouTubeControls()
        addLoginNavigationHooks()
        addPlayerDownloadHooks()
        val sourceConfig = classDefBy("Lcom/music/bitchord/data/sources/SourceConfig;")
        for (getter in listOf("getId", "getBaseUrl")) {
            check(
                sourceConfig.methods.count {
                    it.name == getter &&
                        it.parameterTypes.isEmpty() &&
                        it.returnType == "Ljava/lang/String;" &&
                        it.accessFlags and 1 != 0 &&
                        it.accessFlags and 8 == 0 &&
                        it.implementation != null
                } == 1
            ) {
                "Source configuration $getter identity getter changed"
            }
        }
        qualitySourceShape()
        val registry = mutableClassDefBy("Lcom/music/bitchord/data/sources/SourceRegistry;")
        val publication =
            registry.methods.singleOrNull {
                it.name == "publish" &&
                    it.parameterTypes == listOf("Ljava/util/ArrayList;", "Z") &&
                    it.returnType == "V" &&
                    it.accessFlags and 8 != 0 &&
                    it.implementation != null
            }
        check(publication != null) { "Source configuration publication changed" }
        val publicationCode = publication.implementation!!.instructions.toList()
        check(
            publicationCode.any { instruction ->
                val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
                field?.definingClass == registry.type && field.name == "configs"
            } &&
                publicationCode.any { instruction ->
                    val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                    call?.definingClass == "Landroid/content/SharedPreferences${'$'}Editor;" &&
                        call.name == "putString"
                } &&
                publication.implementation!!.registerCount >= 2
        ) {
            "Source configuration publication no longer updates the native catalogue and storage"
        }
        publication.addInstructions(
            0,
            """
            invoke-static/range {p0 .. p0}, Lapp/bitchord/telegram/FlacAddon;->availableConfigs(Ljava/util/ArrayList;)Ljava/util/ArrayList;
            move-result-object p0
            """,
        )
        val policy = "Lapp/bitchord/telegram/CapturePolicy;"
        val marker =
            mutableClassDefBy(policy).methods.single {
                it.name == "testingBuild" && it.parameterTypes.isEmpty() && it.returnType == "Z"
            }
        val code = marker.implementation!!.instructions.toList()
        check(
            marker.accessFlags and 8 != 0 &&
                code.size == 2 &&
                code[0].opcode == Opcode.CONST_4 &&
                code[1].opcode == Opcode.RETURN &&
                (code[0] as NarrowLiteralInstruction).narrowLiteral == 0 &&
                (code[0] as OneRegisterInstruction).registerA == 0 &&
                (code[1] as OneRegisterInstruction).registerA == 0
        ) {
            "Testing capture marker changed"
        }
        marker.replaceInstruction(0, "const/4 v0, ${if (TESTING_CAPTURE) "0x1" else "0x0"}")
        val start = PatchesApplicationCreate.method
        val returns =
            start.implementation!!
                .instructions
                .withIndex()
                .filter { it.value.opcode == Opcode.RETURN_VOID }
                .map { it.index }
        check(returns.isNotEmpty()) { "Capture startup hook not found" }
        for (index in returns.reversed()) start.addInstruction(
            index,
            "invoke-static/range {p0 .. p0}, $policy->start(Landroid/content/Context;)V",
        )
    }
}

private val patchesUiResources = resourcePatch {
    execute {
        get("res/drawable/patches_tweaks.xml", false).apply {
            parentFile.mkdirs()
            writeText(
                """<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
                <path android:fillColor="@android:color/transparent" android:strokeColor="#FFFFFFFF" android:strokeWidth="1.7" android:strokeLineCap="round" android:pathData="M3,5H6M10,5H21M3,12H14M18,12H21M3,19H6M10,19H21M10,5A2,2 0,1 1,6 5A2,2 0,1 1,10 5M18,12A2,2 0,1 1,14 12A2,2 0,1 1,18 12M10,19A2,2 0,1 1,6 19A2,2 0,1 1,10 19"/>
                </vector>"""
            )
        }
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
            val reports = doc.createElement("provider")
            reports.setAttributeNS(NS, "android:name", "app.bitchord.telegram.DiagnosticFiles")
            reports.setAttributeNS(
                NS,
                "android:authorities",
                doc.documentElement.getAttribute("package") + ".diagnostics",
            )
            reports.setAttributeNS(NS, "android:exported", "false")
            reports.setAttributeNS(NS, "android:grantUriPermissions", "true")
            app.appendChild(reports)

            val callback = doc.createElement("activity")
            callback.setAttributeNS(
                NS,
                "android:name",
                "app.bitchord.telegram.PlaylistAuthActivity",
            )
            callback.setAttributeNS(NS, "android:exported", "true")
            callback.setAttributeNS(NS, "android:excludeFromRecents", "true")
            val filter = doc.createElement("intent-filter")
            for ((tag, name) in
                listOf(
                    "action" to "android.intent.action.VIEW",
                    "category" to "android.intent.category.DEFAULT",
                    "category" to "android.intent.category.BROWSABLE",
                )) {
                val element = doc.createElement(tag)
                element.setAttributeNS(NS, "android:name", name)
                filter.appendChild(element)
            }
            val uri = doc.createElement("data")
            uri.setAttributeNS(NS, "android:scheme", "app.bitchord.sync")
            uri.setAttributeNS(NS, "android:host", "spotify")
            uri.setAttributeNS(NS, "android:path", "/callback")
            filter.appendChild(uri)
            callback.appendChild(filter)
            app.appendChild(callback)
            for (name in
                listOf(
                    "TransitionEditorActivity",
                    "FlacVerificationActivity",
                    "PlaylistToolsActivity",
                    "DownloadsSettingsActivity",
                    "StorageSenseActivity",
                    "DiagnosticsActivity",
                    "SmartPlaylistActivity",
                    "PlaylistImportActivity",
                    "PlaylistSyncActivity",
                )) {
                val extra = doc.createElement("activity")
                extra.setAttributeNS(NS, "android:name", "app.bitchord.telegram.$name")
                extra.setAttributeNS(NS, "android:exported", "false")
                extra.setAttributeNS(
                    NS,
                    "android:theme",
                    "@android:style/Theme.Material.NoActionBar",
                )
                app.appendChild(extra)
            }
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

internal fun BytecodePatchContext.checkHostSettingsRow() {
    val lead =
        listOf(
            "Landroidx/compose/ui/graphics/vector/ImageVector;",
            STRING,
            STRING,
            "Lkotlin/jvm/functions/Function2;",
            STRING,
            STRING,
            "Z",
            "Landroidx/compose/ui/graphics/painter/Painter;",
        )
    val trailing =
        listOf(
            FUNCTION0,
            "Lkotlin/jvm/functions/Function2;",
            "Landroidx/compose/runtime/ComposerImpl;",
            "I",
            "I",
        )
    val shapes =
        listOf(lead + trailing, lead + listOf("Lkotlin/jvm/functions/Function2;") + trailing + "I")
    val rows =
        classDefBy("Lcom/music/bitchord/ui/screens/SettingsSheetKt;").methods.filter {
            it.name == "SettingsRow"
        }
    check(
        rows.size == 1 &&
            rows.single().accessFlags and 9 == 9 &&
            rows.single().returnType == "V" &&
            rows.single().parameterTypes.map { it.toString() } in shapes
    ) {
        "The native settings row signature is unknown or ambiguous"
    }
}

/** One shared entry, regardless of the order in which optional patches were selected. */
internal val extrasSettingsPatch = bytecodePatch {
    dependsOn(patchesUiRuntime, patchesUiResources, selectedDownloadsPatch)
    execute {
        checkHostSettingsRow()
        addDownloadsSettingsRow()
        val playback =
            mutableClassDefBy("Lcom/music/bitchord/playback/PlaybackService;").methods.single {
                it.name == "onCreate" && it.parameterTypes.isEmpty() && it.returnType == "V"
            }
        check(playback.implementation != null && playback.accessFlags and 8 == 0) {
            "Storage playback lifecycle changed"
        }
        playback.addInstruction(
            0,
            "invoke-static/range {p0 .. p0}, Lapp/bitchord/telegram/StorageSenseActive;->playbackService(Ljava/lang/Object;)V",
        )
        val storageMarker =
            mutableClassDefBy("Lapp/bitchord/telegram/StorageSenseActive;").methods.single {
                it.name == "playbackHookApplied" &&
                    it.parameterTypes.isEmpty() &&
                    it.returnType == "Z"
            }
        check(storageMarker.implementation!!.instructions.first().opcode == Opcode.CONST_4) {
            "Storage playback marker changed"
        }
        storageMarker.replaceInstruction(0, "const/4 v0, 0x1")
        val screen = mutableClassDefBy("Lcom/music/bitchord/ui/screens/SettingsSheetKt;")
        val method = screen.methods.single { it.name.startsWith("SettingsScreen-") }
        val code = method.implementation!!.instructions.toList()
        val groups =
            code.indices.filter { i ->
                val ref = (code[i] as? ReferenceInstruction)?.reference as? MethodReference
                ref?.definingClass == screen.type && ref.name.startsWith("SearchableSettingsGroup-")
            }
        // 1.9 inlines SearchableSettingsGroup and opens individual categories as
        // separate pages. Its category list is on the main page only; putting
        // our entry after it does not repeat it inside each category or depend
        // on whether the account group matched the current search.
        val categories =
            code.indices.filter { i ->
                val ref = (code[i] as? ReferenceInstruction)?.reference as? MethodReference
                ref?.definingClass == screen.type &&
                    ref.name == "SettingsCategoryList" &&
                    ref.parameterTypes ==
                        listOf(
                            "Lkotlin/jvm/functions/Function1;",
                            "Landroidx/compose/runtime/ComposerImpl;",
                            "I",
                        ) &&
                    ref.returnType == "V"
            }
        val insertion: Int
        val composer: Int
        if (groups.isNotEmpty()) {
            check(groups.size >= 3 && categories.isEmpty()) { "Settings groups changed" }
            insertion = groups.first()
            composer = registers(code[insertion])[4]
        } else {
            check(categories.size == 1) { "Main settings category list is not unique" }
            insertion = categories.single()
            composer = registers(code[insertion])[1]
        }
        method.addInstruction(
            insertion + 1,
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

        val painters = mutableListOf<com.android.tools.smali.dexlib2.iface.Method>()
        classDefForEach { clazz ->
            painters +=
                clazz.methods.filter {
                    it.name == "painterResource" &&
                        it.parameterTypes ==
                            listOf("I", "Landroidx/compose/runtime/ComposerImpl;") &&
                        it.returnType == "Landroidx/compose/ui/graphics/painter/Painter;" &&
                        it.accessFlags and 8 != 0 &&
                        it.implementation != null
                }
        }
        check(painters.size == 1) {
            "Patches settings painterResource is not unique (${painters.size})"
        }
        val painter = painters.single()
        val iconBridge =
            ui.methods.singleOrNull {
                it.name == "hostPainter" &&
                    it.parameterTypes == listOf("I", "Landroidx/compose/runtime/ComposerImpl;") &&
                    it.returnType == "Landroidx/compose/ui/graphics/painter/Painter;" &&
                    it.accessFlags and 8 != 0
            }
        check(iconBridge != null) { "Patches settings icon bridge changed" }
        val linkedPainter =
            MutableMethod(
                ImmutableMethod(
                    iconBridge.definingClass,
                    iconBridge.name,
                    iconBridge.parameters,
                    iconBridge.returnType,
                    iconBridge.accessFlags,
                    iconBridge.annotations,
                    iconBridge.hiddenApiRestrictions,
                    ImmutableMethodImplementation(
                        3,
                        emptyList<Instruction>(),
                        emptyList(),
                        emptyList(),
                    ),
                )
            )
        linkedPainter.addInstructions(
            0,
            "invoke-static/range {p0 .. p1}, ${painter.definingClass}->${painter.name}(ILandroidx/compose/runtime/ComposerImpl;)Landroidx/compose/ui/graphics/painter/Painter;\nmove-result-object p0\nreturn-object p0",
        )
        ui.methods.remove(iconBridge)
        ui.methods.add(linkedPainter)

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

        // R8 merges SourcesScreen into different ScreenKt classes in 1.9/1.9.1.
        // Wrap only its collected UI state. The registry, playback resolver and search
        // retain their original source configurations and enabled state.
        val sourceOwners = mutableListOf<String>()
        classDefForEach { candidate ->
            if (
                candidate.type.startsWith("Lcom/music/bitchord/ui/screens/") &&
                    candidate.methods.any {
                        it.name == "SourcesScreen" &&
                            it.returnType == "V" &&
                            it.parameterTypes ==
                                (if (qualitySourceShape()) SOURCES_SCREEN_QUALITY
                                else SOURCES_SCREEN_LEGACY)
                    }
            )
                sourceOwners.add(candidate.type)
        }
        check(sourceOwners.size == 1) { "Sources UI entry is not unique" }
        val sources =
            mutableClassDefBy(sourceOwners.single()).methods.single { it.name == "SourcesScreen" }
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

package app.bitchord.patches

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.*
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction31c
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference

private const val ORIGINAL = "com.music.bitchord"
private const val CLONE = "com.music.bitchord.telegramtest"
private val cloneStrings = mutableMapOf(ORIGINAL to CLONE, "$ORIGINAL." to "$CLONE.")
private val applicationIdOption =
    intOption(
        key = "telegramApplicationId",
        title = "Telegram application API ID",
        description = "Leave at 0 and enter your API ID in the app after installing.",
        default = 0,
        required = false,
        validator = { it != null && it >= 0 },
    )
private val applicationHashOption =
    stringOption(
        key = "telegramApplicationHash",
        title = "Telegram application API hash",
        description = "Leave empty and enter your API hash in the app after installing.",
        default = "",
        required = false,
        validator = { it != null && (it.isEmpty() || it.matches(Regex("[a-fA-F0-9]{32}"))) },
    )
private val installationNameOption =
    stringOption(
        key = "installationName",
        title = "Installation name",
        description =
            "The app name on your home screen, up to 80 characters. Leave blank for Bitchord+. A name containing Telegram must include Unofficial Telegram.",
        default = "",
        required = false,
        validator = { value ->
            value != null &&
                value.length <= 80 &&
                value.none { Character.isISOControl(it) || it == '\u2028' || it == '\u2029' } &&
                !value
                    .replace(Regex("(?i)unofficial\\s+telegram"), "")
                    .contains("telegram", ignoreCase = true)
        },
    )

private fun installationName() =
    installationNameOption.value.orEmpty().trim().ifEmpty { "Bitchord+" }

private const val ANDROID = "http://schemas.android.com/apk/res/android"
private val target =
    Compatibility(
        name = "BitChord",
        packageName = "com.music.bitchord",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xFF8944,
        targets = listOf(AppTarget(version = "1.9", isExperimental = true, minSdk = 26)),
    )

private object ApplicationCreate :
    Fingerprint(
        definingClass = "Lcom/music/bitchord/BitChordApplication;",
        name = "onCreate",
        parameters = emptyList(),
        returnType = "V",
    )

private fun argumentRegisters(
    instruction: com.android.tools.smali.dexlib2.iface.instruction.Instruction
): List<Int> =
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
        else -> error("Unsupported settings call encoding")
    }

private val telegramResources = resourcePatch {
    execute {
        val applicationId = applicationIdOption.value!!
        val applicationHash = applicationHashOption.value!!
        check(
            (applicationId == 0 && applicationHash.isEmpty()) ||
                (applicationId > 0 && applicationHash.matches(Regex("[a-fA-F0-9]{32}")))
        ) {
            "Supply both optional seed credentials or leave both empty to configure them in the app"
        }
        get("assets/telegram-native/application.json", false).apply {
            parentFile.mkdirs()
            writeText("""{"apiId":$applicationId,"apiHash":"$applicationHash"}""")
        }
        // Every installable ABI must have the complete native runtime. The official
        // 1.9 universal APK also includes x86, for which these runtimes are unavailable.
        val supportedAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64")
        val originalAbis =
            listApkEntries("lib/").filter { it.endsWith(".so") }.map { it.split('/')[1] }.toSet()
        val universal19 =
            packageMetadata.versionName == "1.9" && originalAbis == supportedAbis + "x86"
        check(
            originalAbis.isNotEmpty() && (originalAbis.all { it in supportedAbis } || universal19)
        ) {
            "Unsupported APK native architecture: $originalAbis"
        }
        if (universal19) for (path in listApkEntries("lib/x86/")) delete(path)
        val inputAbis = originalAbis.intersect(supportedAbis)
        for (abi in inputAbis) for (name in
            listOf("libtdjni.so", "libsslx.so", "libcryptox.so", "libc++_shared.so")) {
            val path = "lib/$abi/$name"
            val file = get(path, false)
            file.parentFile.mkdirs()
            TelegramPatchMarker::class.java.classLoader.getResourceAsStream(path)!!.use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
        for (name in
            listOf(
                "TDLIB-BOOST.txt",
                "OPENSSL-APACHE.txt",
                "LIBCXX-LICENSE.txt",
                "ANDROID-NDK-NOTICE.txt",
                "LLVM-NOTICE.txt",
                "GPL-3.0.txt",
            )) {
            val file = get("assets/telegram-native/licenses/$name", false)
            file.parentFile.mkdirs()
            TelegramPatchMarker::class
                .java
                .classLoader
                .getResourceAsStream("licenses/$name")!!
                .use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        get("res/drawable/telegram_native.xml", false).apply {
            parentFile.mkdirs()
            writeText(
                """<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
                <path android:fillColor="#FFFFFFFF" android:pathData="M4,8h3v8H4zM10.5,3h3v18h-3zM17,6h3v12h-3z"/>
                </vector>"""
            )
        }
        get("res/xml/telegram_network_security.xml", false).apply {
            parentFile.mkdirs()
            writeText(
                """<?xml version="1.0" encoding="utf-8"?>
                <network-security-config>
                    <base-config cleartextTrafficPermitted="false" />
                </network-security-config>"""
            )
        }
        document("AndroidManifest.xml").use { document ->
            val doc = document
            val app = doc.getElementsByTagName("application").item(0) as org.w3c.dom.Element
            val overlayName = "android.permission.HIDE_OVERLAY_WINDOWS"
            val permissions = doc.getElementsByTagName("uses-permission")
            if (
                (0 until permissions.length).none {
                    (permissions.item(it) as org.w3c.dom.Element).getAttributeNS(ANDROID, "name") ==
                        overlayName
                }
            ) {
                val overlayPermission = doc.createElement("uses-permission")
                overlayPermission.setAttributeNS(ANDROID, "android:name", overlayName)
                doc.documentElement.insertBefore(overlayPermission, app)
            }
            check(!app.hasAttributeNS(ANDROID, "networkSecurityConfig")) {
                "Unexpected network policy in target APK"
            }
            app.setAttributeNS(
                ANDROID,
                "android:networkSecurityConfig",
                "@xml/telegram_network_security",
            )
            // Extract libs on installation: independent of zip-entry alignment and compression.
            app.setAttributeNS(ANDROID, "android:extractNativeLibs", "true")
            val activity = doc.createElement("activity")
            activity.setAttributeNS(
                ANDROID,
                "android:name",
                "app.bitchord.telegram.TelegramActivity",
            )
            activity.setAttributeNS(ANDROID, "android:exported", "false")
            activity.setAttributeNS(ANDROID, "android:label", "Unofficial Telegram")
            activity.setAttributeNS(
                ANDROID,
                "android:theme",
                "@android:style/Theme.Material.NoActionBar",
            )
            // Settings opens this activity explicitly, so it needs no launcher entry.
            app.appendChild(activity)
        }
    }
}

private class TelegramPatchMarker

private val cloneResources = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { doc ->
            val manifest = doc.documentElement
            check(manifest.getAttribute("package") == ORIGINAL) {
                "BitChord's package is ${manifest.getAttribute("package")}, not $ORIGINAL: a patch " +
                    "from another source changed it first. Patch BitChord with only this " +
                    "source's patches, with the other sources turned off."
            }
            check(!manifest.hasAttributeNS(ANDROID, "sharedUserId")) {
                "Shared UID apps are unsupported"
            }
            manifest.setAttribute("package", CLONE)
            val nodes = doc.getElementsByTagName("*")
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as org.w3c.dom.Element
                val tag = element.tagName
                // Real component classes keep their existing bytecode namespace.
                // Aliases have no class: their identities follow the test package.
                if (
                    tag in
                        setOf(
                            "application",
                            "activity",
                            "service",
                            "receiver",
                            "provider",
                            "instrumentation",
                        )
                ) {
                    for (attribute in
                        listOf(
                            "name",
                            "backupAgent",
                            "appComponentFactory",
                            "manageSpaceActivity",
                        )) {
                        val value = element.getAttributeNS(ANDROID, attribute)
                        if (value.startsWith("."))
                            element.setAttributeNS(ANDROID, "android:$attribute", ORIGINAL + value)
                        else if (value.isNotEmpty() && !value.contains('.'))
                            element.setAttributeNS(
                                ANDROID,
                                "android:$attribute",
                                "$ORIGINAL.$value",
                            )
                    }
                }
                if (tag == "activity-alias") {
                    val old =
                        element.getAttributeNS(ANDROID, "name").let {
                            if (it.startsWith('.')) ORIGINAL + it else it
                        }
                    if (old.startsWith("$ORIGINAL.")) {
                        val renamed = CLONE + old.removePrefix(ORIGINAL)
                        cloneStrings[old] = renamed
                        element.setAttributeNS(ANDROID, "android:name", renamed)
                    }
                    element.setAttributeNS(ANDROID, "android:label", installationName())
                }
                val targetActivity = element.getAttributeNS(ANDROID, "targetActivity")
                if (targetActivity.startsWith('.'))
                    element.setAttributeNS(
                        ANDROID,
                        "android:targetActivity",
                        ORIGINAL + targetActivity,
                    )
                for (attribute in
                    listOf(
                        "authorities",
                        "permission",
                        "readPermission",
                        "writePermission",
                        "taskAffinity",
                        "targetPackage",
                    )) {
                    val value = element.getAttributeNS(ANDROID, attribute)
                    if (value == ORIGINAL || value.startsWith("$ORIGINAL.")) {
                        val renamed = value.replace(ORIGINAL, CLONE)
                        value.split(';').zip(renamed.split(';')).forEach { (a, b) ->
                            cloneStrings[a] = b
                        }
                        element.setAttributeNS(ANDROID, "android:$attribute", renamed)
                    }
                }
                if (tag in setOf("permission", "uses-permission", "action")) {
                    val value = element.getAttributeNS(ANDROID, "name")
                    if (value.startsWith("$ORIGINAL.")) {
                        val renamed = CLONE + value.removePrefix(ORIGINAL)
                        cloneStrings[value] = renamed
                        element.setAttributeNS(ANDROID, "android:name", renamed)
                    }
                }
                if (
                    tag == "application" ||
                        (tag == "activity" &&
                            element.getAttributeNS(ANDROID, "name") ==
                                "com.music.bitchord.MainActivity")
                )
                    element.setAttributeNS(ANDROID, "android:label", installationName())
            }
        }
    }
}

private const val DOWNMIX = "Lapp/bitchord/telegram/Downmix;"
private const val DOWNMIX_TARGET = "Lapp/bitchord/telegram/Downmix\$Target;"
private const val SINK_CONFIG = "Landroidx/media3/exoplayer/audio/AudioSink\$AudioSinkConfig;"

/**
 * Folds surround PCM to stereo in BitChord's own audio sink, ahead of its effects. The sink's
 * configure() is told the stereo format, and handleBuffer() is wrapped so the decoder's surround
 * buffers are converted before the original code sees them.
 */
private fun BytecodePatchContext.addSurroundDownmix() {
    val sink = mutableClassDefBy("Lcom/music/bitchord/playback/audio/PrecisionAudioSink;")
    check("Landroidx/media3/exoplayer/audio/AudioSink;" in sink.interfaces) {
        "Audio sink no longer implements AudioSink"
    }
    fun method(name: String, parameters: List<String>, returnType: String) =
        sink.methods.single {
            it.name == name && it.parameterTypes == parameters && it.returnType == returnType
        }

    val configure = method("configure", listOf(SINK_CONFIG), "V")
    val code = configure.implementation!!.instructions.toList()
    val formatRead =
        code.indexOfFirst {
            it.opcode == Opcode.IGET_OBJECT &&
                ((it as ReferenceInstruction).reference as FieldReference).let { field ->
                    field.definingClass == SINK_CONFIG && field.name == "format"
                }
        }
    check(formatRead >= 0) { "Audio sink format read not found" }
    val formatRegister = (code[formatRead] as TwoRegisterInstruction).registerA
    val configRegister = (code[formatRead] as TwoRegisterInstruction).registerB
    check(formatRegister < 16) { "Audio sink format register out of range" }
    // The legacy path hands the original (surround) configuration to the platform sink.
    val passThrough =
        code.indexOfLast {
            it.opcode == Opcode.INVOKE_VIRTUAL &&
                ((it as ReferenceInstruction).reference as MethodReference).let { call ->
                    call.name == "configure" && call.parameterTypes == listOf(SINK_CONFIG)
                } &&
                (it as FiveRegisterInstruction).registerD == configRegister
        }
    check(passThrough > formatRead) { "Audio sink fallback configure not found" }
    configure.addInstruction(
        passThrough,
        "invoke-static/range {p0 .. p0}, $DOWNMIX->off(Ljava/lang/Object;)V",
    )
    configure.addInstructions(
        formatRead + 1,
        """
        invoke-static {v$formatRegister}, $DOWNMIX->format(Landroidx/media3/common/Format;)Landroidx/media3/common/Format;
        move-result-object v$formatRegister
    """,
    )
    configure.addInstruction(
        0,
        "invoke-static/range {p0 .. p1}, $DOWNMIX->configure(Ljava/lang/Object;$SINK_CONFIG)V",
    )

    for (name in listOf("flush", "reset")) method(name, emptyList(), "V")
        .addInstruction(0, "invoke-static/range {p0 .. p0}, $DOWNMIX->flush(Ljava/lang/Object;)V")

    // R8 may reorder handleBuffer's parameters, so match them in any order.
    val handle =
        sink.methods.single {
            it.name == "handleBuffer" &&
                it.returnType == "Z" &&
                it.parameterTypes.map(CharSequence::toString).sorted() ==
                    listOf("I", "J", "Ljava/nio/ByteBuffer;")
        }
    val hostTypes = handle.parameterTypes.map(CharSequence::toString)
    val hostSignature = hostTypes.joinToString("")
    // Parameter registers with no locals: this is p0, then each parameter, J taking two.
    fun registersFor(types: List<String>): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        var next = 1
        for (type in types) {
            result[type] = next
            next += if (type == "J") 2 else 1
        }
        return result
    }
    fun arguments(types: List<String>, order: List<String>): String {
        val at = registersFor(types)
        return order.joinToString(", ") { type ->
            val register = at.getValue(type)
            if (type == "J") "p$register, p${register + 1}" else "p$register"
        }
    }
    fun newMethod(name: String, types: List<String>, code: String) {
        val method =
            MutableMethod(
                ImmutableMethod(
                    sink.type,
                    name,
                    types.map {
                        com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter(
                            it,
                            null,
                            null,
                        )
                    },
                    "Z",
                    handle.accessFlags,
                    null,
                    null,
                    ImmutableMethodImplementation(
                        5,
                        emptyList<Instruction>(),
                        emptyList(),
                        emptyList(),
                    ),
                )
            )
        method.addInstructions(0, code)
        sink.methods.add(method)
    }
    val targetOrder = listOf("Ljava/nio/ByteBuffer;", "J", "I")
    handle.name = "hostHandleBuffer"
    sink.interfaces.add(DOWNMIX_TARGET)
    // Downmix.Target, called by the extension with the buffer it wants written.
    newMethod(
        "originalHandleBuffer",
        targetOrder,
        """
        invoke-virtual {p0, ${arguments(targetOrder, hostTypes)}}, ${sink.type}->hostHandleBuffer($hostSignature)Z
        move-result p0
        return p0
    """,
    )
    // The renderer's entry point, in the host's parameter order.
    newMethod(
        "handleBuffer",
        hostTypes,
        """
        invoke-static {p0, ${arguments(hostTypes, targetOrder)}}, $DOWNMIX->handleBuffer(${DOWNMIX_TARGET}Ljava/nio/ByteBuffer;JI)Z
        move-result p0
        return p0
    """,
    )
    println("Added surround-to-stereo downmix to the audio sink")
}

private const val SERVICE = "Lcom/music/bitchord/playback/PlaybackService;"
private const val VERSION_SWAP =
    "Lcom/music/bitchord/playback/PlaybackService\$smoothSwapCurrentTrackVersion\$1;"
private const val SMOOTH = "Lapp/bitchord/telegram/SmoothUpgrade;"
private const val PLAYER = "Landroidx/media3/common/Player;"
private const val MEDIA_ITEM = "Landroidx/media3/common/MediaItem;"

private fun Instruction.methodRef() = (this as? ReferenceInstruction)?.reference as? MethodReference

/**
 * The classes with a method that calls [owner]'s [name]. R8 moves coroutine and lambda bodies into
 * whichever class it merges them with, so callers are found by what they call, not by class name.
 */
private fun BytecodePatchContext.classesCalling(owner: String, name: String): List<String> {
    val found = mutableListOf<String>()
    classDefForEach { classDef ->
        val calls =
            classDef.methods.any { method ->
                method.implementation?.instructions?.any { instruction ->
                    instruction.methodRef()?.let { it.definingClass == owner && it.name == name } ==
                        true
                } == true
            }
        if (calls) found += classDef.type
    }
    return found
}

private fun Instruction.fieldRef() = (this as? ReferenceInstruction)?.reference as? FieldReference

/**
 * Routes BitChord's automatic quality upgrade through its own two-player version swap, so a better
 * copy is crossfaded in instead of replacing the source under the live player. Every reference is
 * read from BitChord's own launcher for that swap rather than assumed.
 */
private fun BytecodePatchContext.addSmoothUpgrade() {
    // 1. BitChord's version-swap launcher, inlined by R8 wherever its one caller ended up.
    val launcherClass =
        classDefByStrings("version swap deferred: Automix is mid-transition").singleOrNull()
            ?: throw PatchException("Version swap launcher not found")
    val launcher =
        launcherClass.methods.single { method ->
            method.implementation?.instructions?.any {
                it.opcode == Opcode.NEW_INSTANCE &&
                    ((it as ReferenceInstruction).reference
                            as com.android.tools.smali.dexlib2.iface.reference.TypeReference)
                        .type == VERSION_SWAP
            } == true
        }
    val code = launcher.implementation!!.instructions.toList()
    val created =
        code.indexOfFirst {
            it.opcode == Opcode.NEW_INSTANCE &&
                ((it as ReferenceInstruction).reference
                        as com.android.tools.smali.dexlib2.iface.reference.TypeReference)
                    .type == VERSION_SWAP
        }
    fun before(predicate: (Instruction) -> Boolean) =
        code.subList(0, created).lastOrNull(predicate)
            ?: throw PatchException("Version swap launcher layout changed")
    fun after(predicate: (Instruction) -> Boolean) =
        code.subList(created, code.size).firstOrNull(predicate)
            ?: throw PatchException("Version swap launcher layout changed")
    val constructor =
        after {
                it.methodRef()?.let { m ->
                    m.definingClass == VERSION_SWAP && m.name == "<init>"
                } == true
            }
            .methodRef()!!
    check(
        constructor.parameterTypes.map(CharSequence::toString) ==
            listOf(
                MEDIA_ITEM,
                SERVICE,
                "Z",
                "Landroidx/media3/exoplayer/ExoPlayer;",
                "Z",
                "Lcom/music/bitchord/data/model/Song;",
                "Lcom/music/bitchord/data/model/Song;",
                "Lcom/music/bitchord/data/model/VersionTimeMap;",
                "I",
                "I",
                "Lkotlin/coroutines/Continuation;",
            )
    ) {
        "Version swap parameters changed: ${constructor.parameterTypes}"
    }
    val launch = after { it.methodRef()?.name == "launch\$default" }.methodRef()!!
    check(
        launch.parameterTypes.map(CharSequence::toString) ==
            listOf(
                "Lkotlinx/coroutines/CoroutineScope;",
                "Lkotlin/coroutines/CoroutineContext;",
                "Lkotlinx/coroutines/CoroutineStart;",
                "Lkotlin/jvm/functions/Function2;",
                "I",
            )
    ) {
        "Coroutine launcher signature changed"
    }
    val job =
        after { it.opcode == Opcode.IPUT_OBJECT && it.fieldRef()?.name == "versionSwapJob" }
            .fieldRef()!!
    val generation =
        before { it.opcode == Opcode.IPUT && it.fieldRef()?.name == "versionSwapGeneration" }
            .fieldRef()!!
    val scope =
        before { it.opcode == Opcode.IGET_OBJECT && it.fieldRef()?.name == "scope" }.fieldRef()!!
    val dispatcher =
        before {
                it.opcode == Opcode.SGET_OBJECT &&
                    it.fieldRef()?.definingClass ==
                        "Lkotlinx/coroutines/internal/MainDispatcherLoader;"
            }
            .fieldRef()!!
    // The old job is cancelled through Kotlin's default-argument helper, or, once R8 inlines that,
    // straight through JobSupport.cancel(null).
    val cancel =
        before {
                it.methodRef()?.let { m ->
                    m.name == "cancel\$default" ||
                        (m.name == "cancel" &&
                            m.definingClass == "Lkotlinx/coroutines/JobSupport;" &&
                            m.parameterTypes.map(CharSequence::toString) ==
                                listOf("Ljava/util/concurrent/CancellationException;"))
                } == true
            }
            .methodRef()!!
    val toSong = before { it.methodRef()?.name == "toSong" }.methodRef()!!
    val crossfade =
        before { it.opcode == Opcode.IGET_OBJECT && it.fieldRef()?.name == "crossfade" }
            .fieldRef()!!
    val transitioning = before { it.methodRef()?.name == "isTransitioning" }.methodRef()!!
    val service = mutableClassDefBy(SERVICE)
    val spare =
        service.fields.singleOrNull {
            it.name == "spare" && it.type == "Landroidx/media3/exoplayer/ExoPlayer;"
        } ?: throw PatchException("Standby player field not found")
    for (field in listOf(job, generation, scope, crossfade)) check(field.definingClass == SERVICE) {
        "Version swap state moved out of the service: $field"
    }
    fun ref(m: MethodReference) =
        "${m.definingClass}->${m.name}(${m.parameterTypes.joinToString("")})${m.returnType}"
    fun ref(f: FieldReference) = "${f.definingClass}->${f.name}:${f.type}"
    val cancelCall =
        if (cancel.name == "cancel")
            "const/4 v12, 0x0\n        invoke-virtual {v0, v12}, ${ref(cancel)}"
        else "invoke-static {v0}, ${ref(cancel)}"

    // 2. A static entry point the extension calls: starts the swap, or returns false.
    val entry =
        MutableMethod(
            ImmutableMethod(
                SERVICE,
                "bitchordSmoothSwap",
                listOf("Ljava/lang/Object;", PLAYER, "I", MEDIA_ITEM).map {
                    com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter(
                        it,
                        null,
                        null,
                    )
                },
                "Z",
                com.android.tools.smali.dexlib2.AccessFlags.PUBLIC.value or
                    com.android.tools.smali.dexlib2.AccessFlags.STATIC.value,
                null,
                null,
                ImmutableMethodImplementation(
                    26,
                    emptyList<Instruction>(),
                    emptyList(),
                    emptyList(),
                ),
            )
        )
    entry.addInstructionsWithLabels(
        0,
        """
        move-object/from16 v3, p0
        instance-of v0, v3, $SERVICE
        if-eqz v0, :no
        move-object/from16 v3, p1
        instance-of v0, v3, Landroidx/media3/exoplayer/ExoPlayer;
        if-eqz v0, :no
        move-object/from16 v20, p0
        move-object/from16 v21, p1
        check-cast v20, $SERVICE
        check-cast v21, Landroidx/media3/exoplayer/ExoPlayer;
        move-object/from16 v0, v20
        iget-object v0, v0, ${ref(crossfade)}
        if-eqz v0, :free
        invoke-virtual {v0}, ${ref(transitioning)}
        move-result v0
        if-nez v0, :no
        :free
        move-object/from16 v0, v20
        iget-object v0, v0, $SERVICE->spare:Landroidx/media3/exoplayer/ExoPlayer;
        if-eqz v0, :no
        move-object/from16 v0, v21
        invoke-interface {v0}, $PLAYER->getCurrentMediaItem()$MEDIA_ITEM
        move-result-object v0
        if-eqz v0, :no
        invoke-static {v0}, ${ref(toSong)}
        move-result-object v1
        invoke-static/range {p3 .. p3}, ${ref(toSong)}
        move-result-object v2
        move-object/from16 v3, v20
        iget-object v0, v3, ${ref(job)}
        if-eqz v0, :nojob
        $cancelCall
        :nojob
        iget v0, v3, ${ref(generation)}
        add-int/lit8 v0, v0, 0x1
        iput v0, v3, ${ref(generation)}
        move v14, v0
        new-instance v4, $VERSION_SWAP
        move-object/from16 v5, p3
        move-object/from16 v6, v20
        const/4 v7, 0x0
        move-object/from16 v8, v21
        const/4 v9, 0x0
        move-object v10, v1
        move-object v11, v2
        const/4 v12, 0x0
        move/from16 v13, p2
        const/4 v15, 0x0
        invoke-direct/range {v4 .. v15}, ${ref(constructor)}
        move-object/from16 v3, v20
        iget-object v0, v3, ${ref(scope)}
        sget-object v1, ${ref(dispatcher)}
        const/4 v2, 0x0
        const/4 v3, 0x2
        invoke-static {v0, v1, v2, v4, v3}, ${ref(launch)}
        move-result-object v0
        move-object/from16 v3, v20
        iput-object v0, v3, ${ref(job)}
        const/4 v0, 0x1
        return v0
        :no
        const/4 v0, 0x0
        return v0
    """,
    )
    service.methods.add(entry)
    check(spare.type == "Landroidx/media3/exoplayer/ExoPlayer;")

    // 3. The upgrade's commit point: replaceMediaItem, seekTo, prepare on the live player.
    val upgrade =
        classDefByStrings("upgrade abandoned: its stream was dropped while it was being proved")
            .singleOrNull() ?: throw PatchException("Quality upgrade commit not found")
    val commit =
        mutableClassDefBy(upgrade).methods.single { method ->
            method.implementation?.instructions?.any {
                ((it as? ReferenceInstruction)?.reference as? StringReference)?.string ==
                    "bitchord.qualityUpgraded"
            } == true
        }
    val steps = commit.implementation!!.instructions.toList()
    val marker =
        steps.indexOfFirst {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string ==
                "bitchord.qualityUpgraded"
        }
    val logged =
        steps.indexOfFirst {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "upgraded to "
        }
    check(marker in 0 until logged) { "Quality upgrade commit layout changed" }
    fun call(name: String, parameters: List<String>): Int {
        val found =
            (marker until logged).filter { index ->
                steps[index].opcode == Opcode.INVOKE_INTERFACE &&
                    steps[index].methodRef()?.let {
                        it.definingClass == PLAYER &&
                            it.name == name &&
                            it.parameterTypes.map(CharSequence::toString) == parameters
                    } == true
            }
        check(found.size == 1) { "Quality upgrade $name call changed" }
        return found.single()
    }
    val replace = call("replaceMediaItem", listOf("I", MEDIA_ITEM))
    val seek = call("seekTo", listOf("I", "J"))
    val prepare = call("prepare", emptyList())
    check(replace < seek && seek < prepare) { "Quality upgrade call order changed" }
    val owner =
        steps.subList(0, replace).lastOrNull {
            it.opcode == Opcode.IPUT_OBJECT && it.fieldRef()?.name == "swappingMediaId"
        } as? TwoRegisterInstruction ?: throw PatchException("Upgrade service register not found")
    val r = steps[replace] as FiveRegisterInstruction
    val s = steps[seek] as FiveRegisterInstruction
    val p = steps[prepare] as FiveRegisterInstruction
    check(owner.registerB < 16 && r.registerC == s.registerC && r.registerC == p.registerC) {
        "Quality upgrade player register changed"
    }
    commit.replaceInstruction(
        prepare,
        "invoke-static {v${p.registerC}}, $SMOOTH->prepare($PLAYER)V",
    )
    commit.replaceInstruction(
        seek,
        "invoke-static {v${s.registerC}, v${s.registerD}, v${s.registerE}, v${s.registerF}}, $SMOOTH->seekTo(${PLAYER}IJ)V",
    )
    commit.replaceInstruction(
        replace,
        "invoke-static {v${owner.registerB}, v${r.registerC}, v${r.registerD}, v${r.registerE}}, $SMOOTH->replace(Ljava/lang/Object;${PLAYER}I$MEDIA_ITEM)V",
    )
    println("Routed quality upgrades through BitChord's two-player version swap")

    // 4. The offset between the two copies: BitChord takes it as zero for the same song, so the
    // one measured for this upgrade is handed over first.
    val alignment =
        service.methods.singleOrNull {
            it.name == "access\$prepareVersionAlignment" &&
                it.parameterTypes.map(CharSequence::toString) ==
                    listOf(
                        SERVICE,
                        "Lcom/music/bitchord/data/model/Song;",
                        "Lcom/music/bitchord/data/model/Song;",
                        "J",
                        "Lkotlin/coroutines/jvm/internal/ContinuationImpl;",
                    )
        } ?: throw PatchException("Version alignment not found")
    check(alignment.implementation!!.registerCount - 6 >= 1) { "Version alignment has no locals" }
    alignment.addInstructionsWithLabels(
        0,
        """
        invoke-static {}, $SMOOTH->alignedOffset()Ljava/lang/Long;
        move-result-object v0
        if-eqz v0, :measure
        return-object v0
        :measure
        nop
    """,
    )

    // 5. The sink's playing position, so the audio heard can be put on the song's timeline.
    val sink = mutableClassDefBy("Lcom/music/bitchord/playback/audio/PrecisionAudioSink;")
    val position =
        sink.methods.single {
            it.name == "getCurrentPositionUs" &&
                it.parameterTypes.map(CharSequence::toString) == listOf("Z") &&
                it.returnType == "J"
        }
    val read = position.implementation!!.instructions.toList()
    val asked =
        read.indexOfFirst {
            it.methodRef()?.let { m ->
                m.definingClass == "Landroidx/media3/exoplayer/audio/DefaultAudioSink;" &&
                    m.name == "getCurrentPositionUs"
            } == true
        }
    check(asked >= 0 && read[asked + 1].opcode == Opcode.MOVE_RESULT_WIDE) {
        "Audio sink position read changed"
    }
    val result = (read[asked + 1] as OneRegisterInstruction).registerA
    val self = position.implementation!!.registerCount - 2
    check(result < 15 && self < 16) { "Audio sink position registers out of range" }
    position.addInstructions(
        asked + 2,
        """
        invoke-static {v$self, v$result, v${result + 1}}, $ALIGN->position(Ljava/lang/Object;J)J
        move-result-wide v$result
    """,
    )
    println("Lined up quality upgrades with the copy they replace")

    // 6. Before the crossfade, BitChord waits up to 300 ms for the new copy to be ready. That
    // wait is held until it plays in step with the old one, for up to 2.6 seconds.
    val swapClass = mutableClassDefBy(VERSION_SWAP)
    val run = swapClass.methods.single { it.name == "invokeSuspend" }
    val body = run.implementation!!.instructions.toList()
    val waits =
        body.indices.filter {
            body[it].opcode == Opcode.CONST_WIDE_16 &&
                (body[it] as WideLiteralInstruction).wideLiteral == 300L
        }
    check(waits.size == 1) { "Version swap wait changed" }
    val wait = waits.single()
    val state =
        (wait until body.size).firstOrNull {
            body[it].methodRef()?.let { m ->
                m.definingClass == PLAYER && m.name == "getPlaybackState"
            } == true
        } ?: throw PatchException("Version swap readiness check not found")
    val asks = body[state]
    val player =
        when (asks) {
            is RegisterRangeInstruction -> {
                check(asks.registerCount == 1) { "Version swap readiness check changed" }
                asks.startRegister
            }
            is FiveRegisterInstruction -> asks.registerC
            else -> throw PatchException("Version swap readiness check changed")
        }
    check(body[state + 1].opcode == Opcode.MOVE_RESULT) { "Version swap readiness check changed" }
    run.replaceInstruction(
        state,
        "invoke-static/range {v$player .. v$player}, $SMOOTH->settled($PLAYER)I",
    )
    val waitRegister = (body[wait] as OneRegisterInstruction).registerA
    run.replaceInstruction(wait, "const-wide/16 v$waitRegister, 0xa28")
    println("Held the upgrade's crossfade until the new copy plays in step")
}

private const val ALIGN = "Lapp/bitchord/telegram/VersionAlign;"

private const val SONG = "Lcom/music/bitchord/data/model/Song;"
private const val TEXT_KT = "Landroidx/compose/material3/TextKt;"
private const val WEIGHT = "Landroidx/compose/foundation/layout/LayoutWeightElement;"

/** 10.sp as Compose packs it: unit type SP in the high bits, the float's bits in the low ones. */
private const val TEN_SP = 0x141200000L

/** White, as Compose packs an sRGB colour. */
private const val WHITE = -0x100000000L
private const val LEGEND = "Lapp/bitchord/telegram/FormatLegend;"

/**
 * BitChord 1.9 opens each download candidate's URL to see what it holds before letting it win. A
 * Telegram file's identifier isn't a web address, so the check is answered by the extension from
 * the index, and the identifier never reaches the network.
 */
private fun BytecodePatchContext.addDownloadProbe() {
    val name = "invokeSuspend\$com\$music\$bitchord\$download\$Downloader\$probe\$2"
    val owners = mutableListOf<String>()
    classDefForEach { clazz ->
        if (
            clazz.methods.any {
                (it.name == name ||
                    (clazz.type == "Lcom/music/bitchord/download/Downloader\$probe\$2;" &&
                        it.name == "invokeSuspend")) && it.parameterTypes.size == 1
            }
        )
            owners += clazz.type
    }
    check(owners.size == 1) { "Download check not found (${owners.size})" }
    val probe =
        mutableClassDefBy(owners.single()).methods.single {
            (it.name == name || it.name == "invokeSuspend") &&
                it.parameterTypes == listOf("Ljava/lang/Object;") &&
                it.implementation?.instructions?.any { instruction ->
                    instruction.methodRef()?.name?.startsWith("sniff") == true
                } == true
        }
    val code = probe.implementation!!.instructions.toList()
    // The check builds its request for the URL; the codec the source stated is read just before.
    val builder =
        code.indices.single {
            code[it].opcode == Opcode.NEW_INSTANCE &&
                (code[it] as ReferenceInstruction).reference.toString() ==
                    "Lokhttp3/Request\$Builder;"
        }
    val urlCall =
        (builder + 1 until minOf(builder + 4, code.size)).single {
            code[it].methodRef()?.let { m ->
                m.definingClass == "Lokhttp3/Request\$Builder;" && m.name == "url"
            } == true
        }
    val url = argumentRegisters(code[urlCall])[1]
    val codecCast = code[builder - 1]
    check(
        codecCast.opcode == Opcode.CHECK_CAST &&
            (codecCast as ReferenceInstruction).reference.toString() == "Ljava/lang/String;" &&
            code.subList(urlCall, code.size).any {
                it.methodRef()?.name?.startsWith("sniff") == true
            }
    ) {
        "Download check layout changed"
    }
    val codec = (codecCast as OneRegisterInstruction).registerA
    val scratch = (code[builder] as OneRegisterInstruction).registerA
    check(maxOf(url, codec, scratch) < 16 && scratch != url && scratch != codec) {
        "Download check registers changed"
    }
    probe.addInstructionsWithLabels(
        builder,
        """
        invoke-static {v$url}, Lapp/bitchord/telegram/InternalAddon;->reserved(Ljava/lang/String;)Z
        move-result v$scratch
        if-eqz v$scratch, :network_check
        invoke-static {v$url, v$codec}, Lapp/bitchord/telegram/NativeDownloads;->probe(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;
        move-result-object v$scratch
        return-object v$scratch
    """,
        ExternalLabel("network_check", code[builder]),
    )
    println("Telegram files answer BitChord's download check from the index")
}

/**
 * Adds the "FLAC 24bit" legend after a song title. BitChord draws every song title (search rows,
 * mini player, queue) through one ExplicitSongTitle composable: its title Text is made to hug its
 * text, and the same Text call is made again with the legend at 10sp. The legend is always emitted,
 * as "" when there's nothing to show, so the composition's shape never changes.
 */
private fun BytecodePatchContext.addFormatLegend() {
    val songText =
        mutableClassDefBy("Lcom/music/bitchord/ui/components/SongTextKt;").methods.single {
            it.name.startsWith("ExplicitSongTitle") && it.parameterTypes.firstOrNull() == SONG
        }
    val code = songText.implementation!!.instructions.toList()
    val texts =
        code.withIndex().filter { (_, it) ->
            it.opcode == Opcode.INVOKE_STATIC_RANGE &&
                it.methodRef()?.let { m ->
                    m.definingClass == TEXT_KT && m.name.startsWith("Text")
                } == true
        }
    check(texts.size == 1) { "Song title Text call changed" }
    val textIndex = texts.single().index
    val call = code[textIndex] as RegisterRangeInstruction
    val text = call.methodRef()!!
    val types = text.parameterTypes.map(CharSequence::toString)
    // Register of each parameter within the range; J takes two.
    val slots = mutableListOf<Int>()
    var next = call.startRegister
    for (type in types) {
        slots += next
        next += if (type == "J" || type == "D") 2 else 1
    }
    check(next == call.startRegister + call.registerCount) { "Text call register layout changed" }
    check(
        types.take(4) == listOf("Ljava/lang/String;", "Landroidx/compose/ui/Modifier;", "J", "J") &&
            types.contains("Landroidx/compose/ui/text/TextStyle;") &&
            types.takeLast(4) == listOf("Landroidx/compose/runtime/ComposerImpl;", "I", "I", "I")
    ) {
        "Text parameters changed: $types"
    }
    val maskRegister = slots.last()
    val maskWrite =
        code.subList(0, textIndex).lastOrNull {
            it is OneRegisterInstruction && it.registerA == maskRegister
        } as? NarrowLiteralInstruction ?: throw PatchException("Text default mask not found")
    // Bit 3 marks fontSize as defaulted; clear it so the legend's size applies.
    val mask = maskWrite.narrowLiteral and 8.inv()

    songText.addInstructions(
        textIndex + 1,
        """
        invoke-static/range {p0 .. p0}, Lapp/bitchord/telegram/FormatLegend;->text($SONG)Ljava/lang/String;
        move-result-object v${slots[0]}
        invoke-static {v${slots[0]}}, Lapp/bitchord/telegram/FormatLegend;->modifier(Ljava/lang/String;)Ljava/lang/Object;
        move-result-object v${slots[1]}
        check-cast v${slots[1]}, Landroidx/compose/ui/Modifier;
        const-wide v${slots[2]}, -0x100000000L
        const-wide v${slots[3]}, 0x${TEN_SP.toString(16)}L
        const v${slots[slots.size - 3]}, 0x0
        const v${slots[slots.size - 2]}, 0x0
        const v${slots.last()}, $mask
        invoke-static/range {v${call.startRegister} .. v${next - 1}}, ${text.definingClass}->${text.name}(${types.joinToString("")})${text.returnType}
    """,
    )

    val added = songText.implementation!!.instructions.toList()
    check(
        added.count {
            it.methodRef()?.let { m -> m.definingClass == TEXT_KT && m.name == text.name } == true
        } == 2 &&
            added.any {
                it.opcode == Opcode.CONST_WIDE &&
                    (it as com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction)
                        .wideLiteral == TEN_SP
            }
    ) {
        "Format legend Text call wasn't added"
    }
    check(
        added.any {
            it.opcode == Opcode.CONST_WIDE &&
                (it as com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction)
                    .wideLiteral == WHITE
        } && added.count { it.methodRef()?.definingClass == LEGEND } == 2
    ) {
        "Format legend chip wasn't added"
    }

    // weight(1f) → weight(1f, fill = false), so the legend sits right after the title. BitChord
    // 1.9 already lays its titles out this way, and then nothing is changed.
    val weights =
        code.indices.filter { i ->
            i < textIndex &&
                code[i].opcode == Opcode.INVOKE_DIRECT &&
                code[i].methodRef()?.let { m -> m.definingClass == WEIGHT && m.name == "<init>" } ==
                    true
        }
    check(weights.isNotEmpty()) { "Title weight not found" }
    for (weightIndex in weights.reversed()) {
        val fill = (code[weightIndex] as FiveRegisterInstruction).registerE
        val fillWrite =
            code.subList(0, weightIndex).lastOrNull {
                it is OneRegisterInstruction && it.registerA == fill
            } as? NarrowLiteralInstruction
        check(fillWrite?.narrowLiteral in listOf(0, 1) && fill < 16) { "Title weight fill changed" }
        if (fillWrite?.narrowLiteral == 0) continue
        songText.addInstructions(weightIndex, "const/4 v$fill, 0x0")
        songText.addInstructions(weightIndex + 2, "const/4 v$fill, 0x1")
    }
    linkLegendHelpers()
    // A main search also looks for the query in the selected Telegram chats.
    val request =
        mutableClassDefBy("Lcom/music/bitchord/ui/MainViewModel\$SearchRequest;").methods.single {
            it.name == "<init>" && it.parameterTypes.firstOrNull() == "Ljava/lang/String;"
        }
    request.addInstruction(
        0,
        "invoke-static/range {p1 .. p1}, $LEGEND->searching(Ljava/lang/String;)V",
    )
    println("Added the format legend after song titles")
}

/**
 * Fills FormatLegend's placeholders with calls to BitChord's own padding, background and
 * rounded-shape functions, found by their signatures wherever R8 placed them.
 */
private fun BytecodePatchContext.linkLegendHelpers() {
    val modifier = "Landroidx/compose/ui/Modifier;"
    val shapeType = "Landroidx/compose/ui/graphics/Shape;"
    var padding: MethodReference? = null
    var background: MethodReference? = null
    var shape: MethodReference? = null
    classDefForEach { clazz ->
        for (method in clazz.methods) {
            val types = method.parameterTypes.map(CharSequence::toString)
            val public =
                method.accessFlags and com.android.tools.smali.dexlib2.AccessFlags.STATIC.value !=
                    0 &&
                    method.accessFlags and
                        com.android.tools.smali.dexlib2.AccessFlags.PUBLIC.value != 0
            if (!public) continue
            if (
                method.name.startsWith("padding-") &&
                    types == listOf(modifier, "F", "F", "F", "F") &&
                    method.returnType == modifier
            )
                padding = method
            if (
                method.name.startsWith("background-") &&
                    types == listOf(modifier, "J", shapeType) &&
                    method.returnType == modifier
            )
                background = method
            if (
                method.name.startsWith("RoundedCornerShape-") &&
                    types == listOf("F") &&
                    method.returnType == "Landroidx/compose/foundation/shape/RoundedCornerShape;"
            )
                shape = method
        }
    }
    fun ref(m: MethodReference?, what: String): String {
        m ?: throw PatchException("Compose $what function not found")
        return "${m.definingClass}->${m.name}(${m.parameterTypes.joinToString("")})${m.returnType}"
    }
    val legend = mutableClassDefBy(LEGEND)
    fun body(name: String, registers: Int, code: String) {
        val original = legend.methods.single { it.name == name }
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
                        registers,
                        emptyList<Instruction>(),
                        emptyList(),
                        emptyList(),
                    ),
                )
            )
        replacement.addInstructions(0, code)
        legend.methods.remove(original)
        legend.methods.add(replacement)
    }
    body(
        "hostPadding",
        5,
        """
        check-cast p0, $modifier
        invoke-static {p0, p1, p2, p3, p4}, ${ref(padding, "padding")}
        move-result-object p0
        return-object p0
    """,
    )
    body(
        "hostBackground",
        4,
        """
        check-cast p0, $modifier
        check-cast p3, $shapeType
        invoke-static {p0, p1, p2, p3}, ${ref(background, "background")}
        move-result-object p0
        return-object p0
    """,
    )
    body(
        "hostShape",
        1,
        """
        invoke-static {p0}, ${ref(shape, "rounded shape")}
        move-result-object p0
        return-object p0
    """,
    )
}

/**
 * Telegram files face a stricter title check before BitChord's matcher can pick one for a song: the
 * candidate list is filtered first in TrackMatcher.best and .ranked, where its scoring was inlined.
 */
private fun BytecodePatchContext.addStricterTelegramMatch() {
    val target = "Lcom/music/bitchord/data/sources/TrackMatcher\$Target;"
    val matcher = mutableClassDefBy("Lcom/music/bitchord/data/sources/TrackMatcher;")
    var hooked = 0
    for (method in matcher.methods.toList()) {
        if (method.name != "best" && method.name != "ranked") continue
        check(
            method.parameterTypes.map(CharSequence::toString) == listOf("Ljava/util/List;", target)
        ) {
            "TrackMatcher.${method.name} parameters changed"
        }
        method.addInstructions(
            0,
            """
            invoke-static/range {p0 .. p1}, Lapp/bitchord/telegram/TelegramMatch;->filter(Ljava/util/List;$target)Ljava/util/List;
            move-result-object p0
        """,
        )
        hooked++
    }
    check(hooked == 2) { "TrackMatcher best/ranked not found" }
    println("Added the stricter title check for Telegram files")
}

@Suppress("unused")
val secondInstallationPatch =
    bytecodePatch(
        name = "Install as Bitchord+",
        description =
            "Installs next to your regular BitChord as Bitchord+, or under a name you choose.",
        default = true,
    ) {
        compatibleWith(target)
        installationNameOption()
        dependsOn(cloneResources)
        execute {
            var changed = 0
            for (originalClass in getAllClassesWithStrings()) {
                // Inspect immutable classes first; make only matching classes mutable.
                if (
                    originalClass.methods.none { method ->
                        method.implementation?.instructions?.any { instruction ->
                            val ref =
                                (instruction as? ReferenceInstruction)?.reference
                                    as? StringReference
                            ref != null && cloneStrings.containsKey(ref.string)
                        } == true
                    }
                )
                    continue
                val clazz = mutableClassDefBy(originalClass)
                for (method in clazz.methods) {
                    val instructions = method.implementation?.instructions ?: continue
                    for ((index, instruction) in instructions.withIndex()) {
                        val old =
                            ((instruction as? ReferenceInstruction)?.reference as? StringReference)
                                ?.string ?: continue
                        val replacement = cloneStrings[old] ?: continue
                        val register = (instruction as OneRegisterInstruction).registerA
                        val reference = ImmutableStringReference(replacement)
                        method.replaceInstruction(
                            index,
                            if (instruction.opcode == Opcode.CONST_STRING_JUMBO)
                                BuilderInstruction31c(
                                    Opcode.CONST_STRING_JUMBO,
                                    register,
                                    reference,
                                )
                            else BuilderInstruction21c(Opcode.CONST_STRING, register, reference),
                        )
                        changed++
                    }
                }
            }
            println("Isolated $changed package, authority, permission and launcher references")
        }
    }

@Suppress("unused")
val nativeTelegramPatch =
    bytecodePatch(
        name = "Unofficial Telegram music",
        description =
            "Search and play audio from your own Telegram channels, right on your phone. Android 8 or newer.",
        default = true,
    ) {
        compatibleWith(target)
        applicationIdOption()
        applicationHashOption()
        dependsOn(telegramResources, patchesUiRuntime)
        extendWith("extensions/telegram.mpe")
        execute {
            // AddonClient's original coroutine still supplies Dispatchers.IO. Only
            // the reserved native identifier is answered directly; external addons
            // retain their original HTTP implementation.
            val addonBodyClass = "Lcom/music/bitchord/download/Downloads\$run\$2;"
            val addonBody =
                mutableClassDefBy(addonBodyClass).methods.single {
                    it.name ==
                        "invokeSuspend\$com\$music\$bitchord\$data\$sources\$addon\$AddonClient\$body\$2" &&
                        it.parameterTypes == listOf("Ljava/lang/Object;") &&
                        it.returnType == "Ljava/lang/Object;"
                }
            check(addonBody.implementation!!.registerCount == 14) {
                "Addon IO coroutine registers changed"
            }
            check(
                addonBody.implementation!!.instructions.any {
                    val ref = (it as? ReferenceInstruction)?.reference as? FieldReference
                    ref?.definingClass == addonBodyClass &&
                        ref.name == "L\$0" &&
                        ref.type == "Ljava/lang/Object;"
                }
            ) {
                "Addon request capture changed"
            }
            // Continuations are existing host instructions, not labels at the end
            // of a separately assembled snippet. The latter have snippet-relative
            // locations and corrupt branches inserted at nonzero code addresses.
            val externalAddon = addonBody.implementation!!.instructions.first()
            addonBody.addInstructionsWithLabels(
                0,
                """
            iget-object v0, p0, $addonBodyClass->L${'$'}0:Ljava/lang/Object;
            check-cast v0, Ljava/lang/String;
            invoke-static {v0}, Lapp/bitchord/telegram/InternalAddon;->bodyOrNull(Ljava/lang/String;)Ljava/lang/String;
            move-result-object v0
            if-eqz v0, :external_addon
            return-object v0
        """,
                ExternalLabel("external_addon", externalAddon),
            )
            // Wrap the downstream after resolution. Placing a router above the
            // resolver would miss Telegram substitutes for ordinary YouTube items.
            val resolving = mutableClassDefBy("Landroidx/media3/datasource/ResolvingDataSource;")
            val resolvingConstructor = resolving.methods.single { it.name == "<init>" }
            check(
                resolvingConstructor.parameterTypes.first() ==
                    "Landroidx/media3/datasource/DataSource;"
            )
            val superCall =
                resolvingConstructor.implementation!!.instructions.indexOfFirst {
                    val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
                    ref?.definingClass == "Ljava/lang/Object;" && ref.name == "<init>"
                }
            check(superCall >= 0)
            resolvingConstructor.addInstructions(
                superCall + 1,
                """
            invoke-static {p1}, Lapp/bitchord/telegram/TelegramDataSource;->wrap(Landroidx/media3/datasource/DataSource;)Landroidx/media3/datasource/DataSource;
            move-result-object p1
        """,
            )
            // R8 merged fetchDirect into this coroutine. Keep its original IO
            // dispatcher, cancellation context, sink and progress callback.
            val downloadClass = "Lcom/music/bitchord/wear/WearBridge\$sendToWatch\$1;"
            val directDownload =
                mutableClassDefBy(downloadClass).methods.single { it.name == "invokeSuspend" }
            val directCode = directDownload.implementation!!.instructions.toList()
            // The branch starts by building the request for the URL it was given, right after it
            // reads its coroutine scope into v0.
            val downloadIndex =
                directCode.indices.single { i ->
                    directCode[i].opcode == Opcode.NEW_INSTANCE &&
                        (directCode[i] as ReferenceInstruction).reference.toString() ==
                            "Lokhttp3/Request\$Builder;" &&
                        directCode.getOrNull(i + 2)?.fieldRef()?.name == "\$path"
                }
            // A selected Soulseek patch may already have installed its native dispatch
            // before this HTTP branch. Inspect the original captured scope through that
            // bounded dispatch instead of requiring it immediately before the request.
            check(
                (maxOf(0, downloadIndex - 40) until downloadIndex - 1).any {
                    val field = directCode[it].fieldRef()
                    val read = directCode[it] as? TwoRegisterInstruction
                    val cast = directCode[it + 1]
                    directCode[it].opcode == Opcode.IGET_OBJECT &&
                        field?.definingClass == downloadClass &&
                        field.name == "L\$0" &&
                        read?.registerA == 0 &&
                        read.registerB == 1 &&
                        cast.opcode == Opcode.CHECK_CAST &&
                        (cast as OneRegisterInstruction).registerA == 0 &&
                        (cast as ReferenceInstruction).reference.toString() ==
                            "Lkotlinx/coroutines/CoroutineScope;"
                }
            ) {
                "Direct download scope or receiver changed"
            }
            check(directDownload.implementation!!.registerCount >= 10) {
                "Direct download registers changed"
            }
            for ((name, type) in
                listOf(
                    "\$path" to "Ljava/lang/String;",
                    "\$app" to "Ljava/io/OutputStream;",
                    "\$payload" to "Lkotlin/jvm/functions/Function2;",
                )) {
                check(
                    (downloadIndex until minOf(downloadIndex + 40, directCode.size - 1)).any {
                        val field = directCode[it].fieldRef()
                        val read = directCode[it] as? TwoRegisterInstruction
                        val cast = directCode[it + 1]
                        directCode[it].opcode == Opcode.IGET_OBJECT &&
                            field?.definingClass == downloadClass &&
                            field.name == name &&
                            read?.registerB == 1 &&
                            cast.opcode == Opcode.CHECK_CAST &&
                            (cast as OneRegisterInstruction).registerA == read.registerA &&
                            (cast as ReferenceInstruction).reference.toString() == type
                    }
                ) {
                    "Direct download capture changed: $name"
                }
            }
            check(
                directCode.subList(downloadIndex, downloadIndex + 140).any {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string ==
                        "fetched "
                }
            ) {
                "Direct download body changed"
            }
            check(
                classDefBy("Lkotlinx/coroutines/JobKt;").methods.any {
                    it.name == "ensureActive" &&
                        it.parameterTypes == listOf("Lkotlin/coroutines/CoroutineContext;")
                }
            )
            // Prepend to a previously installed native dispatcher, rather than to
            // its HTTP fallback. Otherwise that dispatcher's unowned-URL branch
            // would jump past this new handler and bypass it entirely.
            val nativeDispatches =
                (maxOf(0, downloadIndex - 40) until downloadIndex - 2).filter { index ->
                    val read = directCode[index] as? TwoRegisterInstruction
                    val field = directCode[index].fieldRef()
                    val cast = directCode[index + 1]
                    val call = directCode[index + 2]
                    directCode[index].opcode == Opcode.IGET_OBJECT &&
                        field?.definingClass == downloadClass &&
                        field.name == "\$path" &&
                        read?.registerA == 4 &&
                        read.registerB == 1 &&
                        cast.opcode == Opcode.CHECK_CAST &&
                        (cast as OneRegisterInstruction).registerA == 4 &&
                        (cast as ReferenceInstruction).reference.toString() ==
                            "Ljava/lang/String;" &&
                        call.opcode == Opcode.INVOKE_STATIC &&
                        call.methodRef()?.let {
                            it.definingClass == "Lapp/bitchord/soulseek/SoulseekAddon;" &&
                                it.name == "reserved" &&
                                it.parameterTypes == listOf("Ljava/lang/String;") &&
                                it.returnType == "Z"
                        } == true &&
                        argumentRegisters(call) == listOf(4)
                }
            check(nativeDispatches.size <= 1) { "Existing native download dispatch is not unique" }
            val nativeDownloadIndex = nativeDispatches.singleOrNull() ?: downloadIndex
            directDownload.addInstructionsWithLabels(
                nativeDownloadIndex,
                """
            iget-object v4, v1, $downloadClass->${'$'}path:Ljava/lang/Object;
            check-cast v4, Ljava/lang/String;
            invoke-static {v4}, Lapp/bitchord/telegram/InternalAddon;->reserved(Ljava/lang/String;)Z
            move-result v5
            if-eqz v5, :external_download
            iget-object v5, v1, $downloadClass->${'$'}app:Ljava/lang/Object;
            check-cast v5, Ljava/io/OutputStream;
            iget-object v6, v1, $downloadClass->${'$'}payload:Ljava/lang/Object;
            check-cast v6, Lkotlin/jvm/functions/Function2;
            invoke-interface {v0}, Lkotlinx/coroutines/CoroutineScope;->getCoroutineContext()Lkotlin/coroutines/CoroutineContext;
            move-result-object v7
            invoke-static {v4, v5, v6, v7}, Lapp/bitchord/telegram/NativeDownloads;->fetch(Ljava/lang/String;Ljava/io/OutputStream;Lkotlin/jvm/functions/Function2;Lkotlin/coroutines/CoroutineContext;)Ljava/lang/Long;
            move-result-object v4
            return-object v4
        """,
                ExternalLabel("external_download", directCode[nativeDownloadIndex]),
            )
            addDownloadProbe()
            println(
                "Installed internal Telegram metadata calls and Media3 data source; no local HTTP listener"
            )
            // The UI shim links only to these verified, host-provided Compose signatures.
            val settings = classDefBy("Lcom/music/bitchord/ui/screens/SettingsSheetKt;")
            val rowParameters =
                listOf(
                    "Landroidx/compose/ui/graphics/vector/ImageVector;",
                    "Ljava/lang/String;",
                    "Ljava/lang/String;",
                    "Lkotlin/jvm/functions/Function2;",
                    "Ljava/lang/String;",
                    "Ljava/lang/String;",
                    "Z",
                    "Landroidx/compose/ui/graphics/painter/Painter;",
                    "Lkotlin/jvm/functions/Function0;",
                    "Lkotlin/jvm/functions/Function2;",
                    "Landroidx/compose/runtime/ComposerImpl;",
                    "I",
                    "I",
                )
            check(
                settings.methods.any {
                    it.name == "SettingsRow" &&
                        it.parameterTypes == rowParameters &&
                        it.returnType == "V"
                }
            ) {
                "Settings row signature changed"
            }
            data class UiHook(
                val classType: String,
                val methodName: String,
                val parameters: List<String>,
                val index: Int,
                val register: Int,
                val target: String,
            )
            val hooks = mutableListOf<UiHook>()
            classDefForEach { clazz ->
                clazz.methods.forEach { candidate ->
                    val instructions =
                        candidate.implementation?.instructions?.toList() ?: return@forEach
                    instructions.forEachIndexed { index, instruction ->
                        val ref =
                            (instruction as? ReferenceInstruction)?.reference as? MethodReference
                                ?: return@forEachIndexed
                        if (
                            // R8 moves this screen into whichever screen file it merges.
                            candidate.name == "AccountAndScrobblingScreen" &&
                                ref.definingClass ==
                                    "Lcom/music/bitchord/ui/screens/SettingsSheetKt;" &&
                                ref.name.startsWith("SettingsGroup-") &&
                                hooks.none { it.target == "accountSection" }
                        ) {
                            check(
                                ref.parameterTypes ==
                                    listOf(
                                        "Ljava/lang/String;",
                                        "Ljava/lang/String;",
                                        "F",
                                        "Landroidx/compose/runtime/internal/ComposableLambdaImpl;",
                                        "Landroidx/compose/runtime/ComposerImpl;",
                                        "I",
                                        "I",
                                    )
                            )
                            hooks +=
                                UiHook(
                                    clazz.type,
                                    candidate.name,
                                    candidate.parameterTypes.map { it.toString() },
                                    index + 1,
                                    argumentRegisters(instruction)[4],
                                    "accountSection",
                                )
                        }
                    }
                }
            }
            check(hooks.count { it.target == "accountSection" } == 1) {
                "Account settings insertion point is not unique"
            }
            // Keep the account entry in its existing outer Compose group.
            for (hook in hooks.sortedByDescending { it.index }) {
                val method =
                    mutableClassDefBy(hook.classType).methods.single {
                        it.name == hook.methodName && it.parameterTypes == hook.parameters
                    }
                method.addInstruction(
                    hook.index,
                    "invoke-static/range {v${hook.register} .. v${hook.register}}, Lapp/bitchord/telegram/TelegramUi;->${hook.target}(Landroidx/compose/runtime/ComposerImpl;)V",
                )
            }
            val group =
                settings.methods.single {
                    it.name.startsWith("SettingsGroup-") &&
                        it.parameterTypes ==
                            listOf(
                                "Ljava/lang/String;",
                                "Ljava/lang/String;",
                                "F",
                                "Landroidx/compose/runtime/internal/ComposableLambdaImpl;",
                                "Landroidx/compose/runtime/ComposerImpl;",
                                "I",
                                "I",
                            )
                }
            // Compose's painterResource, in whichever class R8 merged it into.
            fun isPainter(method: com.android.tools.smali.dexlib2.iface.Method) =
                method.name == "painterResource" &&
                    method.parameterTypes ==
                        listOf("I", "Landroidx/compose/runtime/ComposerImpl;") &&
                    method.returnType == "Landroidx/compose/ui/graphics/painter/Painter;"
            val painters = mutableListOf<com.android.tools.smali.dexlib2.iface.Method>()
            classDefForEach { clazz -> painters += clazz.methods.filter(::isPainter) }
            check(painters.size == 1) { "Compose painterResource not unique (${painters.size})" }
            val painter = painters.single()
            val ui = mutableClassDefBy("Lapp/bitchord/telegram/TelegramUi;")
            fun linkedMethod(name: String, registers: Int, code: String) {
                val original = ui.methods.single { it.name == name }
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
                                registers,
                                emptyList<Instruction>(),
                                emptyList(),
                                emptyList(),
                            ),
                        )
                    )
                replacement.addInstructions(0, code)
                ui.methods.remove(original)
                ui.methods.add(replacement)
            }
            linkedMethod(
                "hostGroup",
                8,
                "invoke-static/range {p0 .. p6}, ${group.definingClass}->${group.name}(Ljava/lang/String;Ljava/lang/String;FLandroidx/compose/runtime/internal/ComposableLambdaImpl;Landroidx/compose/runtime/ComposerImpl;II)V\nreturn-void",
            )
            linkedMethod(
                "hostPainter",
                3,
                "invoke-static/range {p0 .. p1}, ${painter.definingClass}->${painter.name}(ILandroidx/compose/runtime/ComposerImpl;)Landroidx/compose/ui/graphics/painter/Painter;\nmove-result-object p0\nreturn-object p0",
            )
            check(
                classDefBy("Landroidx/compose/runtime/internal/ComposableLambdaImpl;").methods.any {
                    it.name == "<init>" &&
                        it.parameterTypes == listOf("Ljava/lang/Object;", "Z", "I")
                }
            ) {
                "Compose lambda constructor changed"
            }
            println(
                "Added standalone Telegram settings section and icon; Sources setup row omitted"
            )
            // Extend the existing stats overlay, retaining all host source-selection and
            // generation checks. These signatures/operands are pinned to optimized 1.9.
            val snapshotType = "Lcom/music/bitchord/data/NerdStats\$Snapshot;"
            val statsType = "Lapp/bitchord/telegram/PlaybackStats;"
            val snapshot = classDefBy(snapshotType)
            for ((name, type) in
                listOf(
                    "mimeType" to "Ljava/lang/String;",
                    "sourceName" to "Ljava/lang/String;",
                    "bitrateKbps" to "Ljava/lang/Integer;",
                )) {
                check(
                    snapshot.fields.any {
                        it.name == name && it.type == type && (it.accessFlags and 1) != 0
                    }
                ) {
                    "Stats snapshot field changed: $name"
                }
            }
            // A tap on the Telegram row in Sources opens the Telegram settings page instead of
            // the generic editor, which would only show the internal add-on address.
            // R8 numbers the screen's lambdas, so the row's click lambda is the one that decides
            // on SourceKind.needsServer.
            val rowLambdas = mutableListOf<String>()
            classDefForEach { clazz ->
                if (
                    clazz.type.startsWith(
                        "Lcom/music/bitchord/ui/screens/SourcesScreenKt\$\$ExternalSyntheticLambda"
                    ) &&
                        clazz.methods.any { method ->
                            method.name == "invoke" &&
                                method.parameterTypes.size == 5 &&
                                method.implementation?.instructions?.any {
                                    it.fieldRef()?.let { ref ->
                                        ref.definingClass ==
                                            "Lcom/music/bitchord/data/sources/SourceKind;" &&
                                            ref.name == "needsServer"
                                    } == true
                                } == true
                        }
                )
                    rowLambdas += clazz.type
            }
            check(rowLambdas.size == 1) {
                "Source row click lambda not unique (${rowLambdas.size})"
            }
            val rowLambda = mutableClassDefBy(rowLambdas.single())
            val rowInvoke =
                rowLambda.methods.single { it.name == "invoke" && it.parameterTypes.size == 5 }
            val rowCode = rowInvoke.implementation!!.instructions.toList()
            val needsServer =
                rowCode.indexOfFirst {
                    val ref = (it as? ReferenceInstruction)?.reference as? FieldReference
                    ref?.definingClass == "Lcom/music/bitchord/data/sources/SourceKind;" &&
                        ref.name == "needsServer"
                }
            check(needsServer >= 0) { "Source row click decision missing" }
            val editorLoad =
                (needsServer + 1 until minOf(needsServer + 8, rowCode.size)).single {
                    val ref = (rowCode[it] as? ReferenceInstruction)?.reference as? FieldReference
                    rowCode[it].opcode == Opcode.IGET_OBJECT &&
                        ref?.definingClass == rowLambda.type &&
                        ref.type == "Lkotlin/jvm/functions/Function1;"
                }
            val editor = (rowCode[editorLoad] as TwoRegisterInstruction).registerA
            val clickLambda =
                (editorLoad + 1 until minOf(editorLoad + 24, rowCode.size)).single {
                    val ref = (rowCode[it] as? ReferenceInstruction)?.reference as? MethodReference
                    rowCode[it].opcode == Opcode.INVOKE_DIRECT &&
                        ref?.name == "<init>" &&
                        ref.parameterTypes ==
                            listOf("Ljava/lang/Object;", "Ljava/lang/Object;", "I")
                }
            check(
                editor < 16 && (rowCode[clickLambda] as FiveRegisterInstruction).registerD == editor
            ) {
                "Source row click callback register changed"
            }
            rowInvoke.addInstructions(
                editorLoad + 1,
                """
                invoke-static {v$editor}, Lapp/bitchord/telegram/SourceEditorRoute;->wrap(Lkotlin/jvm/functions/Function1;)Lkotlin/jvm/functions/Function1;
                move-result-object v$editor
            """,
            )
            val playback = mutableClassDefBy("Lcom/music/bitchord/playback/PlaybackService;")
            // Actual player events, not metadata reads/download prefetch, acknowledge direct
            // Telegram items.
            val playing =
                mutableClassDefBy(
                        "Lcom/music/bitchord/playback/PlaybackService\$playbackListener\$1;"
                    )
                    .methods
                    .single {
                        it.name == "onIsPlayingChanged" &&
                            it.parameterTypes == listOf("Z") &&
                            it.returnType == "V"
                    }
            val playingCode = playing.implementation!!.instructions.toList()
            check(
                playing.implementation!!.registerCount == 12 &&
                    playingCode.size > 4 &&
                    playingCode[3].opcode == Opcode.RETURN_VOID
            ) {
                "Playing callback layout changed"
            }
            val playerField =
                (playingCode[1] as? ReferenceInstruction)?.reference as? FieldReference
            check(
                playerField?.definingClass == playback.type &&
                    playerField.name == "player" &&
                    playerField.type == "Landroidx/media3/exoplayer/ExoPlayer;" &&
                    (playingCode[1] as? TwoRegisterInstruction)?.registerA == 1
            ) {
                "Playing callback player register changed"
            }
            playing.addInstruction(
                2,
                "invoke-static {v1, p1}, Lapp/bitchord/telegram/PlaybackPolicy;->onState(Landroidx/media3/common/Player;Z)V",
            )
            val transition =
                playback.methods.single {
                    it.name == "onTrackBecameCurrent" &&
                        it.parameterTypes ==
                            listOf("Landroidx/media3/common/MediaItem;", "Z", "I", "Z") &&
                        it.returnType == "V"
                }
            // The hook only needs one local register of its own, v0, before BitChord's code runs.
            check(transition.implementation!!.registerCount > 5) {
                "Current-track callback layout changed"
            }
            transition.addInstructions(
                0,
                """
                move-object/from16 v0, p0
                iget-object v0, v0, ${playback.type}->player:Landroidx/media3/exoplayer/ExoPlayer;
                invoke-static {v0}, Lapp/bitchord/telegram/PlaybackPolicy;->onTransition(Landroidx/media3/common/Player;)V
            """,
            )
            val publish =
                playback.methods.single {
                    it.name == "access\$publishNerdStats" &&
                        it.parameterTypes == listOf(playback.type) &&
                        it.returnType == "V"
                }
            val publishCode = publish.implementation!!.instructions.toList()
            val lossless =
                publishCode.indexOfFirst {
                    val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
                    ref?.definingClass == "Lcom/music/bitchord/data/sources/StreamFormat;" &&
                        ref.name == "isLossless"
                }
            check(lossless >= 0) { "Lossless stats branch missing" }
            val blankIndex = lossless + 8
            val blank = publishCode[blankIndex] as? TwoRegisterInstruction
            check(
                publishCode[blankIndex].opcode == Opcode.MOVE_OBJECT &&
                    blank?.registerA == 0 &&
                    blank.registerB == 4 &&
                    publishCode[blankIndex + 1].opcode == Opcode.GOTO
            ) {
                "Lossless stats result layout changed"
            }
            val measureCall = publishCode[blankIndex + 3]
            val measureRef = (measureCall as? ReferenceInstruction)?.reference as? MethodReference
            check(
                measureRef?.name == "invoke" &&
                    measureRef.returnType == "Ljava/lang/Object;" &&
                    measureRef.parameterTypes.isEmpty()
            ) {
                "Rendition measurement callback changed"
            }
            check(
                classDefBy(measureRef!!.definingClass)
                    .interfaces
                    .contains("Lkotlin/jvm/functions/Function0;")
            ) {
                "Measurement callback is not Function0"
            }
            val lambdaRegister = argumentRegisters(measureCall).single()
            publish.replaceInstruction(
                blankIndex,
                "invoke-static {v$lambdaRegister}, $statsType->measureLossless(Lkotlin/jvm/functions/Function0;)Lkotlin/Pair;",
            )
            publish.addInstruction(blankIndex + 1, "move-result-object v0")
            val constructorIndex =
                publish.implementation!!.instructions.indexOfFirst {
                    val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
                    ref?.definingClass == snapshotType && ref.name == "<init>"
                }
            val constructor = publish.implementation!!.instructions[constructorIndex]
            val constructorArgs = argumentRegisters(constructor)
            check(constructorArgs == (5..16).toList()) { "Snapshot constructor arguments changed" }
            publish.addInstruction(
                constructorIndex + 1,
                "invoke-static {v5, v14}, $statsType->capture(${snapshotType}Lcom/music/bitchord/data/TelemetryProvenance;)V",
            )

            val controls = mutableClassDefBy("Lcom/music/bitchord/ui/player/PlayerControlsKt;")
            val sleeve =
                controls.methods.single {
                    it.name == "SleeveNerdStats" &&
                        it.parameterTypes ==
                            listOf(
                                "Lcom/music/bitchord/data/model/Song;",
                                "Landroidx/compose/ui/Modifier;",
                                "Landroidx/compose/runtime/ComposerImpl;",
                                "I",
                            )
                }
            val sleeveCode = sleeve.implementation!!.instructions.toList()
            // v45 is borrowed below, so it has to be a local register (four parameters follow).
            check(sleeve.implementation!!.registerCount - 4 > 45) {
                "Stats overlay register layout changed"
            }
            val firstField =
                sleeveCode.indexOfFirst {
                    val ref = (it as? ReferenceInstruction)?.reference as? FieldReference
                    ref?.definingClass == snapshotType && ref.name == "mimeType"
                }
            val field = sleeveCode[firstField] as TwoRegisterInstruction
            check(field.registerB == 4) { "Stats snapshot register changed" }
            val join =
                sleeveCode.indexOfFirst {
                    val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
                    ref?.name == "joinToString\$default" && ref.returnType == "Ljava/lang/String;"
                }
            check(
                join > firstField && (sleeveCode[join + 1] as OneRegisterInstruction).registerA == 4
            ) {
                "Stats description layout changed"
            }
            // v45 is dead throughout this region and is redefined by the original
            // color setup later. Preserve the *same* snapshot used for the localized text.
            check(
                sleeveCode.subList(firstField, join + 2).none {
                    (it as? OneRegisterInstruction)?.registerA == 45 ||
                        (it as? TwoRegisterInstruction)?.registerB == 45 ||
                        (it is RegisterRangeInstruction &&
                            45 in it.startRegister until it.startRegister + it.registerCount) ||
                        (it is FiveRegisterInstruction && 45 in argumentRegisters(it))
                }
            ) {
                "Stats scratch register is live"
            }
            val textIndex =
                sleeveCode.indexOfFirst {
                    val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
                    ref?.definingClass == "Landroidx/compose/material3/TextKt;" &&
                        ref.name.startsWith("Text-")
                }
            check(
                textIndex > join && argumentRegisters(sleeveCode[textIndex]) == (2..24).toList()
            ) {
                "Stats Text call changed"
            }
            check(
                sleeveCode.subList(join + 2, textIndex).any {
                    (it as? OneRegisterInstruction)?.registerA == 17 &&
                        (it as? NarrowLiteralInstruction)?.narrowLiteral == 1
                }
            ) {
                "Stats maxLines argument changed"
            }
            // Work backwards so the previously inspected indices stay valid.
            sleeve.addInstruction(textIndex, "const/16 v17, 0x2")
            sleeve.addInstructions(
                join + 2,
                """
            move-object/from16 v11, v45
            invoke-static {v4, v11}, $statsType->decorate(Ljava/lang/String;$snapshotType)Ljava/lang/String;
            move-result-object v4
        """,
            )
            sleeve.addInstruction(firstField, "move-object/from16 v45, v4")
            println("Added active-source/bitrate stats and asynchronous lossless bitrate fallback")
            addSurroundDownmix()
            addSmoothUpgrade()
            addFormatLegend()
            addStricterTelegramMatch()
            val method = ApplicationCreate.method
            val implementation = method.implementation!!
            // After BitChord has finished initializing its own source registry.
            val returns =
                implementation.instructions
                    .withIndex()
                    .filter { it.value.opcode == Opcode.RETURN_VOID }
                    .map { it.index }
            check(returns.isNotEmpty()) { "Application startup hook not found" }
            for (index in returns.reversed()) method.addInstruction(
                index,
                "invoke-static/range {p0 .. p0}, Lapp/bitchord/telegram/TelegramBridge;->init(Landroid/content/Context;)V",
            )
        }
    }

/** YouTube Music's package, and the SHA-1 of the certificate it's signed with. */
private const val YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"
private const val YOUTUBE_MUSIC_SIGNATURE = "afb0fed5eeaebdd86f56a97742f4b6b33ef59875"

/** MicroG RE reads these to treat an app as the Google app it stands in for. */
private const val MICROG_SPOOF = "app.revanced.android.gms.SPOOFED_PACKAGE_"

/** MicroG RE's own package. */
private const val MICROG_PACKAGE = "app.revanced.android.gms"

private val microGResources = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { doc ->
            val app = doc.getElementsByTagName("application").item(0) as org.w3c.dom.Element
            for ((name, value) in
                listOf(
                    MICROG_SPOOF + "NAME" to YOUTUBE_MUSIC,
                    MICROG_SPOOF + "SIGNATURE" to YOUTUBE_MUSIC_SIGNATURE,
                )) {
                val meta = doc.createElement("meta-data")
                meta.setAttributeNS(ANDROID, "android:name", name)
                meta.setAttributeNS(ANDROID, "android:value", value)
                app.appendChild(meta)
            }
            // Since Android 11 an app only sees the account types of apps it declares here, so
            // without this BitChord can't tell MicroG RE is installed.
            val queries = doc.createElement("queries")
            val gms = doc.createElement("package")
            gms.setAttributeNS(ANDROID, "android:name", MICROG_PACKAGE)
            queries.appendChild(gms)
            val intent = doc.createElement("intent")
            val action = doc.createElement("action")
            action.setAttributeNS(ANDROID, "android:name", "android.accounts.AccountAuthenticator")
            intent.appendChild(action)
            queries.appendChild(intent)
            doc.documentElement.insertBefore(queries, app)
        }
    }
}

@Suppress("unused")
val microGSignInPatch =
    bytecodePatch(
        name = "Sign in with MicroG",
        description =
            "Offers to sign in to YouTube Music with an account that's already in MicroG RE, " +
                "instead of Google's sign-in page.",
        default = true,
    ) {
        compatibleWith(target)
        dependsOn(microGResources, patchesUiRuntime)
        extendWith("extensions/telegram.mpe")
        execute {
            // The sign-in WebView loads Google's sign-in page, or YouTube Music when changing
            // channel, with one loadUrl right after the two URLs.
            val login =
                "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube" +
                    "&prompt=select_account&continue=https%3A%2F%2Fmusic.youtube.com%2F"
            var hooked = 0
            for (original in getAllClassesWithStrings()) {
                val methods =
                    original.methods.filter { method ->
                        method.implementation?.instructions?.any {
                            ((it as? ReferenceInstruction)?.reference as? StringReference)
                                ?.string == login
                        } == true
                    }
                if (methods.isEmpty()) continue
                val clazz = mutableClassDefBy(original)
                for (found in methods) {
                    val method =
                        clazz.methods.single {
                            it.name == found.name && it.parameterTypes == found.parameterTypes
                        }
                    val steps = method.implementation!!.instructions.toList()
                    val url =
                        steps.indexOfFirst {
                            ((it as? ReferenceInstruction)?.reference as? StringReference)
                                ?.string == login
                        }
                    val load =
                        (url until minOf(url + 8, steps.size)).single { index ->
                            steps[index].methodRef()?.let {
                                it.definingClass == "Landroid/webkit/WebView;" &&
                                    it.name == "loadUrl" &&
                                    it.parameterTypes.map(CharSequence::toString) ==
                                        listOf("Ljava/lang/String;")
                            } == true
                        }
                    val call = steps[load] as FiveRegisterInstruction
                    check(call.registerCount == 2) { "Sign-in loadUrl call changed" }
                    method.replaceInstruction(
                        load,
                        "invoke-static {v${call.registerC}, v${call.registerD}}, " +
                            "Lapp/bitchord/telegram/MicroGSignIn;->load(Landroid/webkit/WebView;Ljava/lang/String;)V",
                    )
                    hooked++
                }
            }
            check(hooked == 1) { "YouTube Music sign-in page not found ($hooked)" }
            println("Offered MicroG RE sign-in on the YouTube Music sign-in page")
        }
    }

private const val EXTRAS = "Lapp/bitchord/telegram/SoundExtras;"
private const val DOWNLOAD_CHOICE = "Lapp/bitchord/telegram/DownloadChoice;"
private const val VIDEO_SWITCH = "Lapp/bitchord/telegram/VideoSwitch;"
private const val SINK = "Lcom/music/bitchord/playback/audio/PrecisionAudioSink;"

/**
 * The audio tap: each decoded block passes through the patches (Sing, Headphone correction, DJ
 * mode) before BitChord's equaliser and effects.
 */
private fun BytecodePatchContext.addAudioTap() {
    val sink = mutableClassDefBy(SINK)
    fun Instruction.readsField(name: String) =
        opcode == Opcode.IGET_OBJECT &&
            fieldRef()?.let { it.definingClass == SINK && it.name == name } == true
    check(sink.fields.any { it.name == "configuredSampleRate" && it.type == "I" }) {
        "Audio sink sample rate field changed"
    }
    // The audio chain starts where the sink reads its effects chain, then the block it fills, and
    // hands the block to the chain. BitChord 1.9 does this twice: for the song as it plays and for
    // Automix's loop mix, so both pass through the tap.
    var tapped = 0
    for (method in sink.methods) {
        val code = method.implementation?.instructions?.toList() ?: continue
        val at =
            code.indices.filter { i ->
                code[i].readsField("dspChain") &&
                    code.getOrNull(i + 1)?.readsField("audioBlock") == true &&
                    code.getOrNull(i + 2)?.methodRef()?.name == "process"
            }
        if (at.isEmpty()) continue
        val targets =
            code
                .filterIsInstance<
                    com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
                >()
                .map { it.target.location.index }
                .toSet()
        for (i in at.reversed()) {
            check(i + 2 !in targets) { "Audio chain call is a branch target" }
            val chainRead = code[i] as TwoRegisterInstruction
            val blockRead = code[i + 1] as TwoRegisterInstruction
            val sinkRegister = chainRead.registerB
            check(blockRead.registerB == sinkRegister) { "Audio block read changed" }
            val block = blockRead.registerA
            check(maxOf(sinkRegister, block) < 16) { "Audio chain registers out of range" }
            method.addInstruction(
                i + 2,
                "invoke-static {v$sinkRegister, v$block}, $EXTRAS->process(Ljava/lang/Object;Ljava/lang/Object;)V",
            )
            tapped++
        }
    }
    check(tapped == 2) { "Audio chain entries not found ($tapped)" }
    for (name in listOf("flush", "reset")) sink.methods
        .single { it.name == name && it.parameterTypes.isEmpty() && it.returnType == "V" }
        .addInstruction(0, "invoke-static/range {p0 .. p0}, $EXTRAS->flush(Ljava/lang/Object;)V")
    // BitChord's usual mode plays through Android's media player, with its effects as audio
    // processors; the sink above only runs its own chain in its precision mode. The tap then gets
    // what the equaliser has just written, a 16-bit block.
    val equaliserType = "Lcom/music/bitchord/playback/EqualizerProcessor;"
    val equaliser = mutableClassDefBy(equaliserType)
    for (field in listOf("channelCount", "sampleRate")) check(
        equaliser.fields.any { it.name == field && it.type == "I" }
    ) {
        "Equaliser field changed: $field"
    }
    val queue =
        equaliser.methods.single {
            it.name == "queueInput" &&
                it.parameterTypes == listOf("Ljava/nio/ByteBuffer;") &&
                it.returnType == "V"
        }
    val steps = queue.implementation!!.instructions.toList()
    val targets =
        steps
            .filterIsInstance<com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction>()
            .map { it.target.location.index }
            .toSet()
    val written =
        steps.indices.filter { i ->
            steps[i].methodRef()?.let {
                it.definingClass == "Ljava/nio/ByteBuffer;" && it.name == "flip"
            } == true && steps.getOrNull(i + 1)?.opcode == Opcode.RETURN_VOID
        }
    check(written.size == 2) { "Equaliser output not found (${written.size})" }
    for (i in written.reversed()) {
        check(i + 1 !in targets) { "Equaliser return is a branch target" }
        val out = (steps[i] as FiveRegisterInstruction).registerC
        val (channels, rate) = (0 until 16).filter { it != out }.take(2)
        check(out < 16 && queue.implementation!!.registerCount - 2 > maxOf(channels, rate)) {
            "Equaliser registers changed"
        }
        queue.addInstructions(
            i + 1,
            """
            iget v$channels, p0, $equaliserType->channelCount:I
            iget v$rate, p0, $equaliserType->sampleRate:I
            invoke-static {p0, v$out, v$channels, v$rate}, $EXTRAS->processPcm16(Ljava/lang/Object;Ljava/nio/ByteBuffer;II)V
        """,
        )
    }
    for (name in listOf("onFlush", "onReset")) equaliser.methods
        .single { it.name == name && it.parameterTypes.isEmpty() && it.returnType == "V" }
        .addInstruction(0, "invoke-static/range {p0 .. p0}, $EXTRAS->flush(Ljava/lang/Object;)V")
    println("Added the audio tap to BitChord's audio chain")
}

/** The loudness gain BitChord works out for a song, moved to the chosen level. */
private fun BytecodePatchContext.addLoudnessLevel() {
    val method =
        mutableClassDefBy(SERVICE).methods.single {
            it.name == "loudnessGainMb" &&
                it.parameterTypes == listOf("Ljava/lang/String;") &&
                it.returnType == "Ljava/lang/Integer;"
        }
    val code = method.implementation!!.instructions.toList()
    // Only the computed gain: it's boxed and returned straight away. Other returns are null.
    val boxed =
        code.indices.filter { i ->
            code[i].methodRef()?.let {
                it.definingClass == "Ljava/lang/Integer;" && it.name == "valueOf"
            } == true &&
                code[i + 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
                code[i + 2].opcode == Opcode.RETURN_OBJECT
        }
    check(boxed.size == 1) { "Loudness gain return changed" }
    val register = (code[boxed[0] + 1] as OneRegisterInstruction).registerA
    method.addInstructions(
        boxed[0] + 2,
        """
        invoke-static/range {v$register .. v$register}, $EXTRAS->loudness(Ljava/lang/Integer;)Ljava/lang/Integer;
        move-result-object v$register
    """,
    )
    println("Added the loudness level")
}

/** Pitch and battery saver are set on each player BitChord builds. */
private fun BytecodePatchContext.addPlayerSettings() {
    var hooked = 0
    for (type in classesCalling(SERVICE, "buildPlayer")) {
        val clazz = mutableClassDefBy(type)
        for (method in clazz.methods) {
            val code = method.implementation?.instructions?.toList() ?: continue
            val calls =
                code.indices.filter { i ->
                    code[i].methodRef()?.let {
                        it.definingClass == SERVICE && it.name == "buildPlayer"
                    } == true
                }
            for (i in calls.reversed()) {
                val result = code[i + 1]
                check(result.opcode == Opcode.MOVE_RESULT_OBJECT) { "Player build result changed" }
                val register = (result as OneRegisterInstruction).registerA
                method.addInstruction(
                    i + 2,
                    "invoke-static/range {v$register .. v$register}, $EXTRAS->player(Landroidx/media3/common/Player;)V",
                )
                hooked++
            }
        }
    }
    // The main and spare players, at start and when the output changes.
    check(hooked == 4) { "BitChord's players not found ($hooked)" }
    println("Added pitch and battery saver to BitChord's $hooked players")
}

/** The sleep timer's pause at the end of its time becomes a fade, then the pause. */
private fun BytecodePatchContext.addSleepFade() {
    val method =
        mutableClassDefBy("Lcom/music/bitchord/playback/PlaybackService\$reportProgress\$1;")
            .methods
            .single { it.name == "invokeSuspend" }
    val code = method.implementation!!.instructions.toList()
    val pauses =
        code.indices.filter { i ->
            code[i].opcode == Opcode.INVOKE_INTERFACE &&
                code[i].methodRef()?.let { it.definingClass == PLAYER && it.name == "pause" } ==
                    true &&
                code.getOrNull(i + 1)?.methodRef()?.let {
                    it.definingClass == "Lcom/music/bitchord/playback/SleepTimer;" &&
                        it.name == "cancel"
                } == true
        }
    check(pauses.size == 1) { "Sleep timer pause not found (${pauses.size})" }
    val player = (code[pauses[0]] as FiveRegisterInstruction).registerC
    method.replaceInstruction(
        pauses[0],
        "invoke-static {v$player}, $EXTRAS->sleepPause(Landroidx/media3/common/Player;)V",
    )
    println("Added the sleep-timer fade")
}

/** BitChord leaves lossless addons out while it can't play lossless; this keeps them in. */
private fun BytecodePatchContext.addLosslessOverBluetooth() {
    val method =
        mutableClassDefBy("Lcom/music/bitchord/data/sources/SourceConfig;").methods.single {
            it.name == "getAwaitsLosslessOutput" &&
                it.parameterTypes.isEmpty() &&
                it.returnType == "Z"
        }
    val implementation = method.implementation!!
    check(implementation.registerCount >= 2) { "Lossless addon check has no free register" }
    method.addInstructionsWithLabels(
        0,
        """
        invoke-static {}, $EXTRAS->losslessAnywhere()Z
        move-result v0
        if-eqz v0, :original
        const/4 v0, 0x0
        return v0
    """,
        ExternalLabel("original", implementation.instructions.first()),
    )
    println("Added lossless addons over Bluetooth")
}

/** BitChord's playback service is handed to the patches as it starts, for DJ mode. */
private fun BytecodePatchContext.addServiceHook() {
    val method =
        mutableClassDefBy(SERVICE).methods.single {
            it.name == "onCreate" && it.parameterTypes.isEmpty() && it.returnType == "V"
        }
    method.addInstruction(
        0,
        "invoke-static/range {p0 .. p0}, $EXTRAS->service(Ljava/lang/Object;)V",
    )
    println("Added the playback service hook")
}

/** Refresh per-output patch state after BitChord applies its actual output selection. */
private fun BytecodePatchContext.addOutputRouting() {
    val method =
        mutableClassDefBy(SERVICE).methods.single {
            it.name == "applyOutputRoute" && it.parameterTypes.isEmpty() && it.returnType == "V"
        }
    check(method.accessFlags and 8 == 0) { "Output route handler must remain an instance method" }
    val returns =
        method.implementation!!
            .instructions
            .withIndex()
            .filter { it.value.opcode == Opcode.RETURN_VOID }
            .map { it.index }
    check(returns.isNotEmpty()) { "Output route completion hook missing" }
    for (index in returns.reversed()) method.addInstruction(
        index,
        "invoke-static {}, $EXTRAS->outputChanged()V",
    )
    println("Added output-selection updates for playback patches")
}

/**
 * Per-download quality: a song queued for download is held while the quality is asked, and the
 * quality BitChord reads for it is the one chosen.
 */
private fun BytecodePatchContext.addDownloadChoice() {
    val downloads = mutableClassDefBy("Lcom/music/bitchord/download/Downloads;")
    val song = "Lcom/music/bitchord/data/model/Song;"
    val quality = "Lcom/music/bitchord/data/settings/DownloadQuality;"
    val enqueue =
        downloads.methods.single {
            it.name == "enqueue" &&
                it.parameterTypes ==
                    listOf("Landroid/content/Context;", song, "Ljava/lang/String;") &&
                it.returnType == "V"
        }
    val implementation = enqueue.implementation!!
    check(enqueue.accessFlags and 8 != 0 && implementation.registerCount >= 5) {
        "Download queue must remain static with a free local register"
    }
    enqueue.addInstructionsWithLabels(
        0,
        """
        invoke-static/range {p0 .. p2}, $DOWNLOAD_CHOICE->hold(Landroid/content/Context;${song}Ljava/lang/String;)Z
        move-result v0
        if-eqz v0, :original
        return-void
    """,
        ExternalLabel("original", implementation.instructions.first()),
    )
    val route =
        downloads.methods.single {
            it.name == "access${'$'}routeFor" &&
                it.parameterTypes.take(3) == listOf("Landroid/content/Context;", song, quality)
        }
    check(route.accessFlags and 8 != 0) { "Download route accessor must remain static" }
    route.addInstructions(
        0,
        """
        invoke-static/range {p1 .. p2}, $DOWNLOAD_CHOICE->quality(${song}Ljava/lang/Object;)Ljava/lang/Object;
        move-result-object p2
        check-cast p2, $quality
    """,
    )
    println("Added per-download quality to BitChord's downloads")
}

/** Makes SoundExtras.[name] answer true: the settings page shows that patch's part. */
private fun BytecodePatchContext.markApplied(name: String) {
    val method =
        mutableClassDefBy(EXTRAS).methods.single {
            it.name == name && it.parameterTypes.isEmpty() && it.returnType == "Z"
        }
    method.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
}

/** Each player BitChord builds, for the pitch, battery saver, sleep fade and DJ mode patches. */
private val extrasPlayersPatch = bytecodePatch {
    dependsOn(extrasSettingsPatch)
    execute { addPlayerSettings() }
}

/** The selected output, shared once by the patches that depend on it. */
private val extrasOutputsPatch = bytecodePatch {
    dependsOn(extrasSettingsPatch)
    execute { addOutputRouting() }
}

/** The decoded audio, for Sing, Headphone correction and DJ mode. */
private val extrasAudioPatch = bytecodePatch {
    dependsOn(extrasSettingsPatch)
    execute { addAudioTap() }
}

/** The playback service, for DJ mode. */
private val extrasServicePatch = bytecodePatch {
    dependsOn(extrasSettingsPatch)
    execute { addServiceHook() }
}

/** Since Android 11 an app only sees the packages it declares; JamesDSP's are declared. */
private val jamesDspResources = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { doc ->
            val app = doc.getElementsByTagName("application").item(0) as org.w3c.dom.Element
            val queries = doc.createElement("queries")
            for (name in JAMES_DSP_PACKAGES) {
                val pkg = doc.createElement("package")
                pkg.setAttributeNS(ANDROID, "android:name", name)
                queries.appendChild(pkg)
            }
            doc.documentElement.insertBefore(queries, app)
        }
    }
}

/** JamesDSP's two versions, and Shizuku, which the rootless one's setup uses. */
private val JAMES_DSP_PACKAGES =
    listOf("me.timschneeberger.rootlessjamesdsp", "james.dsp", "moe.shizuku.privileged.api")

private fun extrasPatch(
    name: String,
    description: String,
    vararg needs: Patch<*>,
    apply: BytecodePatchContext.() -> Unit,
) =
    bytecodePatch(name = name, description = description, default = false) {
        compatibleWith(target)
        dependsOn(extrasSettingsPatch, *needs)
        execute { apply() }
    }

private const val WHERE = " Settings are in Settings, Patches & tweaks."

@Suppress("unused")
val singPatch =
    extrasPatch(
        "Sing",
        "Turns the lead vocal down so you can sing over the song. Ported from the vocal remover " +
            "in Auris by OreoMuncher45, a fork of BitChord." +
            WHERE,
        extrasAudioPatch,
    ) {
        markApplied("singApplied")
    }

@Suppress("unused")
val pitchPatch =
    extrasPatch(
        "Pitch control",
        "Moves the key up or down by up to 12 semitones without changing the speed. From Prism " +
            "by Yigder." +
            WHERE,
        extrasPlayersPatch,
    ) {
        markApplied("pitchApplied")
    }

@Suppress("unused")
val loudnessLevelPatch =
    extrasPatch(
        "Loudness level",
        "Sets how loud loudness normalisation plays songs, from -23 to -8 LUFS instead of " +
            "-14. From Prism by Yigder." +
            WHERE,
    ) {
        addLoudnessLevel()
        markApplied("loudnessApplied")
    }

@Suppress("unused")
val sleepFadePatch =
    extrasPatch(
        "Sleep timer fade-out",
        "Fades the song out before the sleep timer pauses it, and fades the end of the last " +
            "song with the timer's \"After this song\" option. From Prism by Yigder." +
            WHERE,
        extrasPlayersPatch,
    ) {
        addSleepFade()
        markApplied("sleepFadeApplied")
    }

@Suppress("unused")
val batterySaverPatch =
    extrasPatch(
        "Battery saver",
        "Lets the phone's audio chip decode songs, so long listens use less battery. " +
            "BitChord's effects don't run on songs played this way." +
            WHERE,
        extrasPlayersPatch,
        extrasOutputsPatch,
    ) {
        markApplied("batterySaverApplied")
    }

@Suppress("unused")
val downloadQualityPatch =
    extrasPatch(
        "Per-download quality",
        "Asks which quality to download each song in: Standard, High or Lossless. An album " +
            "is asked about once. From Stash by Rawnald Clark." +
            WHERE,
    ) {
        addDownloadChoice()
        markApplied("downloadQualityApplied")
    }

@Suppress("unused")
val headphoneCorrectionPatch =
    extrasPatch(
        "Headphone correction",
        "Flattens your headphones' sound with a measured profile, one per output, from " +
            "AutoEq's thousands of models or pasted in. From Prism by Yigder." +
            WHERE,
        extrasAudioPatch,
        extrasOutputsPatch,
    ) {
        markApplied("headphoneApplied")
    }

@Suppress("unused")
val outputEqualiserPatch =
    extrasPatch(
        "Equaliser per output",
        "Remembers BitChord's equaliser for each output (speaker, wired, each Bluetooth " +
            "device) and puts it back when that output is used; shares settings as codes. " +
            "From Prism by Yigder." +
            WHERE,
        extrasOutputsPatch,
    ) {
        markApplied("outputEqApplied")
    }

@Suppress("unused")
val djModePatch =
    extrasPatch(
        "DJ mode",
        "Picks the next song in the queue for a neighbouring key and a close tempo. Keys " +
            "and tempos are learned as songs play. From Prism by Yigder." +
            WHERE,
        extrasPlayersPatch,
        extrasAudioPatch,
        extrasServicePatch,
    ) {
        markApplied("djApplied")
    }

@Suppress("unused")
val jamesDspPatch =
    extrasPatch(
        "JamesDSP",
        "Hands BitChord's audio to JamesDSP, the open-source effect engine: open it from " +
            "BitChord, announce the audio session again and let it take over the effects." +
            WHERE,
        jamesDspResources,
    ) {
        markApplied("jamesDspApplied")
    }

@Suppress("unused")
val videoSwitchPatch =
    extrasPatch(
        "Audio and video switch",
        "Adds a song and video switch to the top of the player, as in YouTube Music: one tap " +
            "plays a song's music video, or the song again, in the same place in the queue." +
            WHERE,
    ) {
        addVideoSwitch()
        markApplied("videoSwitchApplied")
    }

/**
 * The switch needs BitChord's player connection, which the app's screen reads its state from, and
 * the player sheet's motion, which knows when the player is open.
 */
private fun BytecodePatchContext.addVideoSwitch() {
    val remember =
        mutableClassDefBy("Lcom/music/bitchord/playback/PlayerConnectionKt;").methods.single {
            it.name == "rememberPlayerState" &&
                it.parameterTypes.firstOrNull() == "Landroidx/media3/session/MediaController;"
        }
    remember.addInstruction(
        0,
        "invoke-static/range {p0 .. p0}, $VIDEO_SWITCH->controller(Ljava/lang/Object;)V",
    )
    val motion = mutableClassDefBy("Lcom/music/bitchord/ui/player/PlayerSheetMotion;")
    check(motion.fields.any { it.name == "shown" && it.type.startsWith("L") }) {
        "The player sheet has no shown state"
    }
    check(motion.fields.any { it.name == "window" && it.type == "Landroid/view/Window;" }) {
        "The player sheet has no window"
    }
    val init = motion.methods.single { it.name == "<init>" }
    val returns =
        init.implementation!!
            .instructions
            .withIndex()
            .filter { it.value.opcode == Opcode.RETURN_VOID }
            .map { it.index }
    check(returns.isNotEmpty()) { "The player sheet's constructor doesn't return" }
    for (index in returns.reversed()) init.addInstruction(
        index,
        "invoke-static/range {p0 .. p0}, $VIDEO_SWITCH->motion(Ljava/lang/Object;)V",
    )
    // R8 inlines attachWindow/onSheetGone into other classes. Observe the actual
    // field writes so every newly created dialog gets the switch, including after
    // a close/reopen or configuration change.
    val windowOwners = mutableListOf<String>()
    classDefForEach { clazz ->
        if (
            clazz.methods.any { method ->
                method.implementation?.instructions?.any { instruction ->
                    val field = instruction.fieldRef()
                    instruction.opcode == Opcode.IPUT_OBJECT &&
                        field?.definingClass == motion.type &&
                        field.name == "window"
                } == true
            }
        )
            windowOwners += clazz.type
    }
    check(windowOwners.isNotEmpty()) { "Player window lifecycle writes not found" }
    for (owner in windowOwners) for (method in mutableClassDefBy(owner).methods) {
        val code = method.implementation?.instructions?.toList() ?: continue
        val writes =
            code.indices.filter { index ->
                val field = code[index].fieldRef()
                code[index].opcode == Opcode.IPUT_OBJECT &&
                    field?.definingClass == motion.type &&
                    field.name == "window"
            }
        for (index in writes.reversed()) {
            val register = (code[index] as TwoRegisterInstruction).registerB
            method.addInstruction(
                index + 1,
                "invoke-static/range {v$register .. v$register}, $VIDEO_SWITCH->motion(Ljava/lang/Object;)V",
            )
        }
    }
    println("Added the audio and video switch")
}

@Suppress("unused")
val losslessBluetoothPatch =
    extrasPatch(
        "Lossless addons over Bluetooth",
        "Keeps addons marked lossless in use over Bluetooth, where BitChord normally skips " +
            "them. Lossless-first, as in Auris by OreoMuncher45." +
            WHERE,
        extrasOutputsPatch,
    ) {
        addLosslessOverBluetooth()
        markApplied("losslessBluetoothApplied")
    }

private const val MIXED_PLAYLISTS = "Lapp/bitchord/telegram/MixedPlaylists;"

/** Spotube .smplug files are not BitChord addons: adapt the observed protocol on device. */
@Suppress("unused")
val flacAudioPatch =
    extrasPatch(
        "Flac Downloader Audio",
        "Optional native adapter for aztecempire's Spotube FLAC Audio source. Search, stream and download " +
            "through flacdownloader.com, with explicit FLAC/MP3 choices. Starts disabled; enable in Sources or Patches & tweaks. " +
            "Requested quality is not file verification. No Spotube runtime or account is installed.",
    ) {
        markApplied("flacAddonApplied")
        val owner = "Lcom/music/bitchord/download/Downloads\$run\$2;"
        val body =
            mutableClassDefBy(owner).methods.single {
                it.name ==
                    "invokeSuspend\$com\$music\$bitchord\$data\$sources\$addon\$AddonClient\$body\$2" &&
                    it.parameterTypes == listOf("Ljava/lang/Object;") &&
                    it.returnType == "Ljava/lang/Object;"
            }
        check(body.implementation!!.registerCount == 14) { "Addon IO coroutine registers changed" }
        check(
            body.implementation!!.instructions.any {
                val field = (it as? ReferenceInstruction)?.reference as? FieldReference
                field?.definingClass == owner &&
                    field.name == "L\$0" &&
                    field.type == "Ljava/lang/Object;"
            }
        ) {
            "Addon request capture changed"
        }
        val previous = body.implementation!!.instructions.first()
        body.addInstructionsWithLabels(
            0,
            """
        iget-object v0, p0, $owner->L${'$'}0:Ljava/lang/Object;
        check-cast v0, Ljava/lang/String;
        invoke-static {v0}, Lapp/bitchord/telegram/FlacAddon;->bodyOrNull(Ljava/lang/String;)Ljava/lang/String;
        move-result-object v0
        if-eqz v0, :non_flac_addon
        return-object v0
    """,
            ExternalLabel("non_flac_addon", previous),
        )
    }

@Suppress("unused")
val mixedPlaylistsPatch =
    extrasPatch(
        "Mixed-source playlists",
        "Adds downloaded files, local audio and streamed tracks to device playlists without a YouTube account. Preserves file and addon source metadata across restarts." +
            WHERE,
    ) {
        markApplied("mixedPlaylistsApplied")
        val menu =
            mutableClassDefBy("Lcom/music/bitchord/ui/components/SongActionsMenuKt;")
                .methods
                .single {
                    it.name == "SongActionsMenu" &&
                        it.parameterTypes.firstOrNull() == "Lcom/music/bitchord/data/model/Song;"
                }
        check(
            menu.parameterTypes[10] == "Lkotlin/jvm/functions/Function0;" &&
                menu.implementation!!.registerCount > menu.parameterTypes.size + 2
        ) {
            "Playlist menu callback ABI changed"
        }
        menu.addInstructions(
            0,
            """
        move-object/from16 v0, p0
        move-object/from16 v1, p10
        invoke-static {v0, v1}, $MIXED_PLAYLISTS->playlistAction(Ljava/lang/Object;Lkotlin/jvm/functions/Function0;)Lkotlin/jvm/functions/Function0;
        move-result-object v0
        move-object/16 p10, v0
    """,
        )
        val library =
            mutableClassDefBy("Lcom/music/bitchord/ui/screens/LibraryScreenKt;").methods.single {
                it.name == "LibraryScreen"
            }
        check(
            library.parameterTypes[5] == "Lkotlin/jvm/functions/Function0;" &&
                library.implementation!!.registerCount > library.parameterTypes.size + 1
        ) {
            "New playlist callback ABI changed"
        }
        library.addInstructions(
            0,
            """
        move-object/from16 v0, p5
        invoke-static {v0}, $MIXED_PLAYLISTS->newPlaylistAction(Lkotlin/jvm/functions/Function0;)Lkotlin/jvm/functions/Function0;
        move-result-object v0
        move-object/16 p5, v0
    """,
        )
        // Only the Add to playlist section is expanded. Rating/sign-in gates remain untouched.
        val owners = mutableListOf<Triple<String, String, List<String>>>()
        classDefForEach { type ->
            if (type.type.startsWith("Lcom/music/bitchord/ui/components/SongActionsMenuKt"))
                for (method in type.methods) {
                    val found =
                        method.implementation?.instructions?.any { instruction ->
                            val ref =
                                (instruction as? ReferenceInstruction)?.reference as? FieldReference
                            ref?.definingClass == "Lcom/music/bitchord/ui/icons/SfIcons;" &&
                                ref.name == "AddToPlaylist\$delegate"
                        } == true
                    if (found)
                        owners.add(
                            Triple(
                                type.type,
                                method.name,
                                method.parameterTypes.map { it.toString() },
                            )
                        )
                }
        }
        check(owners.size == 1) { "Playlist row must have one native menu entry" }
        val (type, name, parameters) = owners.single()
        val rows =
            mutableClassDefBy(type).methods.single {
                it.name == name && it.parameterTypes == parameters
            }
        val code = rows.implementation!!.instructions.toList()
        val icon =
            code.indexOfFirst {
                ((it as? ReferenceInstruction)?.reference as? FieldReference)?.name ==
                    "AddToPlaylist\$delegate"
            }
        val offsets = mutableListOf<Int>()
        var address = 0
        for (instruction in code) {
            offsets += address
            address += instruction.codeUnits
        }
        val guard =
            (0 until icon).lastOrNull { index ->
                code[index].opcode == Opcode.IF_EQZ &&
                    offsets[index] + (code[index] as OffsetInstruction).codeOffset > offsets[icon]
            } ?: error("Playlist account gate not found")
        val register = (code[guard] as OneRegisterInstruction).registerA
        val end = offsets.indexOf(offsets[guard] + (code[guard] as OffsetInstruction).codeOffset)
        check(register <= 255 && end > icon + 3) { "Playlist gate boundaries changed" }
        val enter = code[guard + 1]
        val skip = code[end]
        rows.addInstructionsWithLabels(
            guard,
            """
        if-nez v$register, :mixed_playlist_allowed
        invoke-static {}, $MIXED_PLAYLISTS->allowPlaylist()Z
        move-result v$register
        if-eqz v$register, :mixed_playlist_blocked
        const/16 v$register, 0x0
        goto :mixed_playlist_allowed
    """,
            ExternalLabel("mixed_playlist_allowed", enter),
            ExternalLabel("mixed_playlist_blocked", skip),
        )
        rows.removeInstruction(guard + 6)
    }

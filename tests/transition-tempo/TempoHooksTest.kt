package app.bitchord.patches

import com.android.tools.smali.dexlib2.*
import com.android.tools.smali.dexlib2.iface.*
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.*
import com.android.tools.smali.dexlib2.immutable.instruction.*
import com.android.tools.smali.dexlib2.immutable.reference.*
import java.io.File

private var checks = 0
private val play =
    ImmutableMethodReference(
        "Landroidx/media3/common/Player;",
        "setPlayWhenReady",
        listOf("Z"),
        "V",
    )
private val volume =
    ImmutableMethodReference("Landroidx/media3/common/Player;", "setVolume", listOf("F"), "V")
private val clock =
    ImmutableMethodReference(
        "Landroid/os/SystemClock;",
        "elapsedRealtime",
        emptyList<String>(),
        "J",
    )

private fun body(flag: Int = 1): List<Instruction> =
    listOf(
        ImmutableInstruction11n(Opcode.CONST_4, 1, flag),
        ImmutableInstruction11n(Opcode.CONST_4, 2, 0),
        ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 2, 0, 2, 0, 0, 0, volume),
        ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 2, 0, 1, 0, 0, 0, play),
        ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, clock),
        ImmutableInstruction10x(Opcode.RETURN_VOID),
    )

private fun method(code: List<Instruction>, flags: Int = 2, name: String = "renamed") =
    ImmutableMethod(
        "Lfixture/Control;",
        name,
        emptyList(),
        "V",
        flags,
        emptySet(),
        emptySet(),
        ImmutableMethodImplementation(4, code, emptyList(), emptyList()),
    )

private fun controller(vararg methods: Method): ClassDef =
    ImmutableClassDef(
        "Lfixture/Control;",
        1,
        "Ljava/lang/Object;",
        emptyList(),
        null,
        emptySet(),
        emptyList(),
        methods.toList(),
    )

private fun check(value: Boolean, reason: String) {
    checks++
    check(value) { reason }
}

private fun rejects(owner: ClassDef, reason: String) {
    var failed = false
    try {
        locateTransitionTempoStart(owner)
    } catch (expected: IllegalStateException) {
        failed = expected.message?.contains("Automix") == true
    }
    check(failed, reason)
}

private fun pairApiFixture(): Map<String, ClassDef> {
    val item = "Landroidx/media3/common/MediaItem;"
    val metadata = "Landroidx/media3/common/MediaMetadata;"
    val builder = "Lfixture/ItemBuilder;"
    val values = "Lfixture/MetadataBuilder;"
    val player = "Landroidx/media3/common/Player;"
    val converter = "Lcom/music/bitchord/playback/PlayerConnectionKt;"
    val song = "Lfixture/Song;"
    fun m(
        owner: String,
        name: String,
        args: List<String>,
        result: String,
        code: List<Instruction>? = null,
        flags: Int = 1,
    ) =
        ImmutableMethod(
            owner,
            name,
            args.map { ImmutableMethodParameter(it, emptySet(), null) },
            result,
            flags,
            emptySet(),
            emptySet(),
            code?.let { ImmutableMethodImplementation(4, it, emptyList(), emptyList()) },
        )
    fun c(type: String, methods: List<Method>, fields: List<Field> = emptyList()) =
        ImmutableClassDef(
            type,
            1,
            "Ljava/lang/Object;",
            emptyList(),
            null,
            emptySet(),
            fields,
            methods,
        )
    return listOf(
            c(item, listOf(m(item, "buildUpon", emptyList(), builder))),
            c(
                metadata,
                listOf(
                    m(metadata, "buildUpon", emptyList(), values),
                    m(metadata, "<init>", listOf(values), "V"),
                ),
            ),
            c(
                builder,
                listOf(
                    m(
                        builder,
                        "build",
                        emptyList(),
                        item,
                        listOf(
                            ImmutableInstruction22c(
                                Opcode.IGET_OBJECT,
                                0,
                                1,
                                ImmutableFieldReference(
                                    builder,
                                    "mediaMetadata",
                                    "Ljava/lang/Object;",
                                ),
                            ),
                            ImmutableInstruction21c(
                                Opcode.CHECK_CAST,
                                0,
                                ImmutableTypeReference(metadata),
                            ),
                        ),
                    ),
                    m(builder, "setUri", listOf("Ljava/lang/String;"), "V"),
                ),
                listOf(
                    ImmutableField(
                        builder,
                        "mediaMetadata",
                        "Ljava/lang/Object;",
                        1,
                        null,
                        emptySet(),
                        emptySet(),
                    )
                ),
            ),
            c(
                values,
                emptyList(),
                listOf(
                    ImmutableField(
                        values,
                        "extras",
                        "Landroid/os/Bundle;",
                        1,
                        null,
                        emptySet(),
                        emptySet(),
                    )
                ),
            ),
            c(
                player,
                listOf(
                    m(player, "replaceMediaItem", listOf("I", item), "V"),
                    m(player, "getNextMediaItemIndex", emptyList(), "I"),
                    m(player, "getRepeatMode", emptyList(), "I"),
                    m(player, "getShuffleModeEnabled", emptyList(), "Z"),
                    m(player, "seekTo", listOf("I", "J"), "V"),
                ),
            ),
            c(
                converter,
                listOf(
                    m(converter, "toSong", listOf(item), song, flags = 9),
                    m(converter, "toMediaItem", listOf(song), item, flags = 9),
                ),
            ),
        )
        .associateBy { it.type }
}

private fun pairApiRejects(types: Map<String, ClassDef>, reason: String) {
    var rejected = false
    try {
        verifyTransitionPairApi { types.getValue(it) }
    } catch (expected: IllegalStateException) {
        rejected = expected.message?.contains("Automix selected-pair API") == true
    }
    check(rejected, reason)
}

private fun deckApiFixture(fields: List<Field>): ClassDef =
    ImmutableClassDef(
        "Lfixture/Decks;",
        1,
        "Ljava/lang/Object;",
        emptyList(),
        null,
        emptySet(),
        fields,
        emptyList(),
    )

private fun deckApiChecks() {
    val names = listOf("outgoing", "incoming", "easing")
    val player = "Landroidx/media3/exoplayer/ExoPlayer;"
    fun field(name: String, type: String = player, flags: Int = 1) =
        ImmutableField("Lfixture/Decks;", name, type, flags, null, emptySet(), emptySet())
    val fields = names.map { field(it) }
    verifyTransitionTempoDeckApi(deckApiFixture(fields))
    check(true, "Typed native deck ownership is available")
    for (name in names) {
        val others = fields.filter { it.name != name }
        for (changed in
            listOf(
                others,
                others + field(name, "Ljava/lang/Object;"),
                others + field(name, flags = 9),
                others + field(name + "Changed"),
                fields + field(name, "Ljava/lang/Object;"),
            )) {
            val failure =
                runCatching { verifyTransitionTempoDeckApi(deckApiFixture(changed)) }
                    .exceptionOrNull()
            check(
                failure is IllegalStateException && failure.message?.contains(name) == true,
                "Changed deck name, type, static storage or ambiguity must fail: $name",
            )
        }
    }
}

fun main(args: Array<String>) {
    deckApiChecks()
    val located = locateTransitionTempoStart(controller(method(body())))
    check(
        located.method.name == "renamed" && located.instruction == 3,
        "The hook follows blend behavior without relying on method names",
    )
    rejects(controller(method(body(0))), "Do not hook a paused incoming deck")
    rejects(controller(method(body(), 10)), "Do not treat a static parameter as the controller")
    rejects(
        controller(method(body()), method(body(), name = "other")),
        "Reject ambiguous playback starts",
    )
    rejects(
        controller(method(body().filterIndexed { index, _ -> index != 4 })),
        "Reject a missing blend clock",
    )
    rejects(
        controller(method(body().filterIndexed { index, _ -> index != 3 })),
        "Reject a missing incoming playback call",
    )
    rejects(
        controller(
            method(
                listOf(body()[0], ImmutableInstruction11n(Opcode.CONST_4, 1, 0)) + body().drop(1)
            )
        ),
        "A later write must not turn the enabled flag into false",
    )
    rejects(
        controller(
            method(
                body().mapIndexed { index, instruction ->
                    if (index == 1) ImmutableInstruction11n(Opcode.CONST_4, 2, 1) else instruction
                }
            )
        ),
        "An already audible deck is not the blend-start contract",
    )
    val api = pairApiFixture()
    verifyTransitionPairApi { api.getValue(it) }
    check(true, "Pair API follows returned builder types and qualified Object erasure")
    for (type in api.keys) pairApiRejects(
        api.filterKeys { it != type },
        "Missing API class must fail clearly: $type",
    )
    val itemBuilder = api.getValue("Lfixture/ItemBuilder;")
    val withoutBuild =
        ImmutableClassDef(
            itemBuilder.type,
            1,
            itemBuilder.superclass,
            itemBuilder.interfaces,
            null,
            emptySet(),
            itemBuilder.fields,
            itemBuilder.methods.filter { it.name != "build" },
        )
    pairApiRejects(
        api + (itemBuilder.type to withoutBuild),
        "Missing builder build must fail clearly",
    )
    val withoutSlot =
        ImmutableClassDef(
            itemBuilder.type,
            1,
            itemBuilder.superclass,
            itemBuilder.interfaces,
            null,
            emptySet(),
            emptyList(),
            itemBuilder.methods,
        )
    pairApiRejects(
        api + (itemBuilder.type to withoutSlot),
        "Missing metadata slot must fail clearly",
    )
    val build = itemBuilder.methods.single { it.name == "build" }
    val noCast =
        ImmutableMethod(
            build.definingClass,
            build.name,
            build.parameters,
            build.returnType,
            build.accessFlags,
            build.annotations,
            build.hiddenApiRestrictions,
            ImmutableMethodImplementation(
                4,
                build.implementation!!.instructions.filter { it.opcode != Opcode.CHECK_CAST },
                emptyList(),
                emptyList(),
            ),
        )
    val erasedWithoutProof =
        ImmutableClassDef(
            itemBuilder.type,
            1,
            itemBuilder.superclass,
            itemBuilder.interfaces,
            null,
            emptySet(),
            itemBuilder.fields,
            itemBuilder.methods.map { if (it.name == "build") noCast else it },
        )
    pairApiRejects(
        api + (itemBuilder.type to erasedWithoutProof),
        "An Object slot needs a real metadata cast before it is safe to write",
    )
    val converter = api.getValue("Lcom/music/bitchord/playback/PlayerConnectionKt;")
    val nonStaticConverter =
        ImmutableClassDef(
            converter.type,
            1,
            converter.superclass,
            converter.interfaces,
            null,
            emptySet(),
            converter.fields,
            converter.methods.map {
                ImmutableMethod(
                    it.definingClass,
                    it.name,
                    it.parameters,
                    it.returnType,
                    1,
                    it.annotations,
                    it.hiddenApiRestrictions,
                    it.implementation,
                )
            },
        )
    pairApiRejects(
        api + (converter.type to nonStaticConverter),
        "Song conversion must remain callable without an instance",
    )
    for (path in args) {
        val apk = DexFileFactory.loadDexContainer(File(path), Opcodes.forApi(35))
        val types =
            apk.dexEntryNames
                .asSequence()
                .flatMap { apk.getEntry(it)!!.dexFile.classes.asSequence() }
                .associateBy { it.type }
        val owner = types.getValue("Lcom/music/bitchord/playback/CrossfadeController;")
        verifyTransitionTempoDeckApi(owner)
        check(true, "Native deck ownership ABI is available")
        verifyTransitionPairApi { types.getValue(it) }
        check(true, "Native selected-pair API is available")
        val actual = locateTransitionTempoStart(owner)
        check(actual.instruction > 0, "Native incoming playback start is guarded: $path")
        println("PASS: native blend-start guard ${File(path).name}")
    }
    println("PASS: $checks transition tempo hook checks")
}

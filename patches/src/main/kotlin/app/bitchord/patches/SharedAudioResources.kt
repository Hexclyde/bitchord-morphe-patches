package app.bitchord.patches

import app.morphe.patcher.patch.resourcePatch

private val sharedAudioSupportedAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64")
private const val SHARED_AUDIO_LICENSES = "bitchord/runtimes/licenses/preview/"
private val sharedAudioInventory =
    listOf("bitchord/runtimes/preview-inputs.json", "bitchord/runtimes/preview-provenance.json")

private class SharedAudioMarker

internal class SharedAudioAbiPlan(val abis: Set<String>, val removeX86: Boolean)

/** Supported libraries are copied; the official 1.9 universal APK's partial x86 set is removed. */
internal fun sharedAudioAbiPlan(version: String?, original: Set<String>): SharedAudioAbiPlan {
    val universal19 = version == "1.9" && original == sharedAudioSupportedAbis + "x86"
    check(
        original.isNotEmpty() && (original.all { it in sharedAudioSupportedAbis } || universal19)
    ) {
        "Unsupported audio decoder APK architecture: $original"
    }
    return SharedAudioAbiPlan(original.intersect(sharedAudioSupportedAbis), universal19)
}

/** Preview-only notice and inventory paths from the bundled runtime asset list. */
internal fun sharedAudioAssets(listed: List<String>): List<String> {
    check(
        listed.distinct().size == listed.size &&
            listed.all { path ->
                path.startsWith("bitchord/") &&
                    path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
            }
    ) {
        "Runtime asset list is invalid"
    }
    val selected =
        listed.filter { it.startsWith(SHARED_AUDIO_LICENSES) || it in sharedAudioInventory }
    check(
        listOf("COPYING.LGPLv2.1", "COPYING.LGPLv3", "LICENSE.md", "NOTICE").all {
            "$SHARED_AUDIO_LICENSES$it" in selected
        }
    ) {
        "Audio decoder notices missing from bundle"
    }
    return selected
}

/** The shared audio decoder library and its notices, with no Soulseek, Tor or account assets. */
internal val sharedAudioResources = resourcePatch {
    execute {
        val plan =
            sharedAudioAbiPlan(
                packageMetadata.versionName,
                listApkEntries("lib/")
                    .filter { it.endsWith(".so") }
                    .map { it.split('/')[1] }
                    .toSet(),
            )
        if (plan.removeX86) {
            val unsupported = listApkEntries("lib/x86/")
            check(unsupported.isNotEmpty()) { "Universal APK x86 entries are missing" }
            for (path in unsupported) delete(path)
        }
        document("AndroidManifest.xml").use { doc ->
            val app = doc.getElementsByTagName("application").item(0) as org.w3c.dom.Element
            app.setAttributeNS(
                "http://schemas.android.com/apk/res/android",
                "android:extractNativeLibs",
                "true",
            )
        }
        val loader = SharedAudioMarker::class.java.classLoader
        for (abi in plan.abis) {
            val output = get("lib/$abi/libbitchord_preview.so", false)
            output.parentFile.mkdirs()
            val input =
                loader.getResourceAsStream("lib/$abi/libbitchord_preview.so")
                    ?: error("Audio decoder missing from bundle: $abi")
            input.use { source -> output.outputStream().use { source.copyTo(it) } }
        }
        val listed =
            loader.getResourceAsStream("runtimes/assets.list")?.bufferedReader()?.use {
                it.readLines()
            } ?: error("Runtime asset list missing from bundle")
        for (path in sharedAudioAssets(listed)) {
            val output = get("assets/$path", false)
            output.parentFile.mkdirs()
            val input =
                loader.getResourceAsStream("runtimes/assets/$path")
                    ?: error("Missing runtime asset: $path")
            input.use { source -> output.outputStream().use { source.copyTo(it) } }
        }
    }
}

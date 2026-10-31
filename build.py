#!/usr/bin/env python3
"""Build the patch bundle (.mpp)."""

from pathlib import Path
import os, subprocess, zipfile, hashlib, shutil, fcntl, tempfile, uuid, struct, json

from buildenv import (
    BUNDLE_NAME,
    MORPHE_JAR as MORPHE,
    PATCHER_VERSION,
    ROOT,
    TDLIB as TD,
    TOOLS,
    VERSION,
    jdk,
)

JDK = jdk()
JAVA, JAVAC = str(JDK / "bin/java"), str(JDK / "bin/javac")
ANDROID = TOOLS / "android.jar"
R8 = TOOLS / "r8-latest.jar"
SOULSEEK_NATIVE = Path(os.environ.get("SOULSEEK_NATIVE", ROOT / ".deps/soulseek"))
DOWNLOAD_RUNTIMES = {
    "i2p": (ROOT / ".deps/i2p", "libbitchord_i2p.so"),
    "tor": (ROOT / ".deps/tor", "libtor.so"),
    "ipfs": (ROOT / ".deps/ipfs", "libbitchord_ipfs.so"),
    "direct": (ROOT / ".deps/direct", "libbitchord_direct.so"),
}
OUT = ROOT / "build"
OUT.mkdir(exist_ok=True)
lock = open(ROOT / ".build.lock", "w")
fcntl.flock(lock, fcntl.LOCK_EX)
ENV = dict(
    os.environ,
    JAVA_HOME=str(JDK),
    PATH=str(JDK / "bin") + os.pathsep + os.environ["PATH"],
)


def run(*args):
    subprocess.run([str(a) for a in args], check=True, env=ENV)


def jar(path, base):
    # Publish completed archives atomically; readers never see a partly written JAR/MPP.
    with tempfile.TemporaryDirectory(prefix="telegram-archive-") as temp:
        staged = Path(temp) / path.name
        with zipfile.ZipFile(staged, "w", zipfile.ZIP_DEFLATED) as z:
            for file in sorted(base.rglob("*")):
                if file.is_file() and not any(
                    part.startswith(".") for part in file.relative_to(base).parts
                ):
                    z.write(file, file.relative_to(base))
        with zipfile.ZipFile(staged) as z:
            if z.testzip() is not None:
                raise RuntimeError("Archive verification failed")
        pending = path.with_name("." + path.name + "." + uuid.uuid4().hex)
        shutil.copy2(staged, pending)
        pending.replace(path)


for name in (
    "extension-classes",
    "patch-classes",
    "extension-dex",
    "telegram-runtime-classes",
    "patches-ui-classes",
    "patches-ui-dex",
    "patch-dex",
    "bundle",
    "compile-api",
    "soulseek-classes",
    "soulseek-dex",
):
    shutil.rmtree(OUT / name, ignore_errors=True)
    (OUT / name).mkdir()
stdlib = TOOLS / "kotlinc/lib/kotlin-stdlib.jar"
run(
    JAVAC,
    "-J-Xshare:off",
    "--release",
    "8",
    "-Xlint:-options",
    "-cp",
    os.pathsep.join(map(str, [stdlib, ANDROID])),
    "-d",
    OUT / "compile-api",
    *sorted((ROOT / "compile-only").rglob("*.java")),
)
jar(OUT / "compile-api.jar", OUT / "compile-api")
sources = list((ROOT / "extensions/telegram/src/main/java").rglob("*.java")) + list(
    (TD / "src/main/java").rglob("*.java")
)
run(
    JAVAC,
    "-J-Xshare:off",
    "-encoding",
    "UTF-8",
    "--release",
    "8",
    "-Xlint:-options",
    "-cp",
    os.pathsep.join(
        map(str, [ANDROID, TOOLS / "annotation.jar", stdlib, OUT / "compile-api.jar"])
    ),
    "-d",
    OUT / "extension-classes",
    *sources,
)
jar(OUT / "extension.jar", OUT / "extension-classes")
# Publish a small shared settings runtime separately, so download-only installs
# never receive Telegram's account, TDLib client or generated API classes.
shared_ui = {
    "ExtrasActivity",
    "ExtrasUi",
    "SoundExtras",
    "TelegramStyle",
    "TelegramSwitch",
    "Strings",
    "PatchStrings",
    "CapturePolicy",
    "Translations",
    "HostLog",
    "VocalCut",
    "PatchSources",
    "Outputs",
    "SleepFade",
    "HeadphoneEq",
    "OutputEq",
    "KeyTempo",
    "DjMode",
    "DownloadChoice",
    "EffectsApps",
    "BitPerfect",
    "VideoSwitch",
    "MixedPlaylists",
    "MixedSongCodec",
    "FlacAddon",
    "Fingerprint",
    "RecordingMatch",
}
for source in (OUT / "extension-classes").rglob("*.class"):
    relative = source.relative_to(OUT / "extension-classes")
    common = (
        relative.parent.as_posix() == "app/bitchord/telegram"
        and source.stem.split("$", 1)[0] in shared_ui
    )
    target = (
        OUT
        / ("patches-ui-classes" if common else "telegram-runtime-classes")
        / relative
    )
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)
for name, classes, output in (
    ("telegram-runtime", "telegram-runtime-classes", "extension-dex"),
    ("patches-ui", "patches-ui-classes", "patches-ui-dex"),
):
    jar(OUT / (name + ".jar"), OUT / classes)
    run(
        JAVA,
        "-Xshare:off",
        "-Xmx3g",
        "-cp",
        R8,
        "com.android.tools.r8.D8",
        "--release",
        "--min-api",
        "26",
        "--lib",
        ANDROID,
        "--lib",
        stdlib,
        "--lib",
        OUT / "compile-api.jar",
        "--output",
        OUT / output,
        OUT / (name + ".jar"),
    )
# Keep the Soulseek extension independent: selecting it never injects TDLib or
# Telegram-only classes, and native sources can be selected together safely.
soulseek_sources = sorted((ROOT / "extensions/soulseek/src/main/java").rglob("*.java"))
if not soulseek_sources:
    raise RuntimeError("Soulseek extension sources are missing")
run(
    JAVAC,
    "-J-Xshare:off",
    "-encoding",
    "UTF-8",
    "--release",
    "8",
    "-Xlint:-options",
    "-cp",
    os.pathsep.join(
        map(
            str,
            [
                ANDROID,
                TOOLS / "annotation.jar",
                stdlib,
                OUT / "patches-ui.jar",
                OUT / "compile-api.jar",
            ],
        )
    ),
    "-d",
    OUT / "soulseek-classes",
    *soulseek_sources,
)
jar(OUT / "soulseek.jar", OUT / "soulseek-classes")
run(
    JAVA,
    "-Xshare:off",
    "-Xmx2g",
    "-cp",
    R8,
    "com.android.tools.r8.D8",
    "--release",
    "--min-api",
    "26",
    "--lib",
    ANDROID,
    "--lib",
    stdlib,
    "--lib",
    OUT / "compile-api.jar",
    "--output",
    OUT / "soulseek-dex",
    OUT / "soulseek.jar",
)
flags = OUT / "BuildFlags.kt"
flags.write_text(
    "package app.bitchord.patches\n\ninternal const val TESTING_CAPTURE = "
    + (
        "true"
        if VERSION
        in (
            "1.1.0-beta.8",
            "1.1.0-beta.9",
            "1.1.0-beta.10",
            "1.1.0-beta.11",
            "1.1.0-beta.12",
            "1.1.0-beta.13",
            "1.1.0-beta.14",
            "1.1.0-beta.15",
            "1.1.0-beta.16",
            "1.1.0-beta.16.1",
            "1.1.0-beta.16.2",
        )
        else "false"
    )
    + "\n"
)
run(
    TOOLS / "kotlinc/bin/kotlinc",
    "-Xskip-metadata-version-check",
    "-Xcontext-parameters",
    "-jvm-target",
    "11",
    "-cp",
    MORPHE,
    "-d",
    OUT / "patch-classes",
    *sorted((ROOT / "patches/src/main/kotlin").rglob("*.kt")),
    flags,
)
jar(OUT / "patch.jar", OUT / "patch-classes")
run(
    JAVA,
    "-Xshare:off",
    "-Xmx2g",
    "-cp",
    R8,
    "com.android.tools.r8.D8",
    "--release",
    "--min-api",
    "26",
    "--lib",
    ANDROID,
    "--lib",
    MORPHE,
    "--output",
    OUT / "patch-dex",
    OUT / "patch.jar",
)
bundle = OUT / "bundle"
shutil.copytree(OUT / "patch-classes", bundle, dirs_exist_ok=True)
for dex in (OUT / "patch-dex").glob("*.dex"):
    shutil.copy2(dex, bundle / dex.name)
(bundle / "extensions").mkdir(exist_ok=True)
relay_resources = bundle / "soulseek"
relay_resources.mkdir(exist_ok=True)
for name in (
    "nostr-relays.txt",
    "nostr-relays-source.json",
    "stun-servers.txt",
    "stun-servers-source.json",
):
    shutil.copy2(ROOT / "resources/soulseek" / name, relay_resources / name)
# Morphe's extension input may be a multidex ZIP; use one DEX here and fail if it grows.
dexes = list((OUT / "extension-dex").glob("*.dex"))
if len(dexes) != 1:
    raise RuntimeError("Expected one extension DEX")
shutil.copy2(dexes[0], bundle / "extensions/telegram.mpe")
ui_dexes = list((OUT / "patches-ui-dex").glob("*.dex"))
if len(ui_dexes) != 1:
    raise RuntimeError("Expected one shared settings extension DEX")
shutil.copy2(ui_dexes[0], bundle / "extensions/patches-ui.mpe")
soulseek_dexes = list((OUT / "soulseek-dex").glob("*.dex"))
if len(soulseek_dexes) != 1:
    raise RuntimeError("Expected one Soulseek extension DEX")
shutil.copy2(soulseek_dexes[0], bundle / "extensions/soulseek.mpe")
for abi in ("arm64-v8a", "armeabi-v7a", "x86_64"):
    libs = bundle / "lib" / abi
    libs.mkdir(parents=True, exist_ok=True)
    for src in [
        TD / f"src/main/libs/30.0.16248370/android-24/{abi}/libtdjni.so",
        TD / f"openssl/30.0.16248370/android-24/{abi}/lib/libsslx.so",
        TD / f"openssl/30.0.16248370/android-24/{abi}/lib/libcryptox.so",
        TOOLS / "libcxx" / abi / "libc++_shared.so",
    ]:
        shutil.copy2(src, libs / src.name)
# Check the ELF architecture before packaging each cross-compiled Android input.
for abi, machine in (("arm64-v8a", 183), ("armeabi-v7a", 40), ("x86_64", 62)):
    native = SOULSEEK_NATIVE / abi / "libbitchord_soulseek.so"
    if not native.is_file():
        raise RuntimeError(
            f"Missing Soulseek native library: {native}. Run tools/build_soulseek_native.py first."
        )
    data = native.read_bytes()
    if (
        len(data) < 20
        or data[:4] != b"\x7fELF"
        or struct.unpack_from("<H", data, 18)[0] != machine
    ):
        raise RuntimeError(f"Wrong Soulseek ELF architecture: {abi}")
    shutil.copy2(native, bundle / "lib" / abi / native.name)

runtime_assets = bundle / "runtimes/assets"
asset_paths = []


def runtime_asset(source, relative):
    target = runtime_assets / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)
    asset_paths.append(relative)


for runtime, (base, library) in DOWNLOAD_RUNTIMES.items():
    if runtime == "tor":
        inventory = json.loads((ROOT / "native/tor/PINNED.json").read_text())[
            "libraries"
        ]
    else:
        inventory = json.loads((base / "build-hashes.json").read_text())
    for abi, machine in (("arm64-v8a", 183), ("armeabi-v7a", 40), ("x86_64", 62)):
        source = base / "lib" / abi / library
        if not source.is_file():
            source = base / abi / library
        data = source.read_bytes()
        expected = inventory[abi]
        if (
            len(data) < 64
            or data[:4] != b"\x7fELF"
            or struct.unpack_from("<H", data, 18)[0] != machine
            or hashlib.sha256(data).hexdigest() != expected["sha256"]
            or len(data) != expected.get("bytes", expected.get("size"))
        ):
            raise RuntimeError(f"Invalid {runtime} native input: {abi}")
        shutil.copy2(source, bundle / "lib" / abi / library)
    notice_root = (
        (ROOT / "native/tor/licenses") if runtime == "tor" else base / "licenses"
    )
    notices = sorted(path for path in notice_root.rglob("*") if path.is_file())
    if not notices:
        raise RuntimeError(f"Missing {runtime} runtime notices")
    for source in notices:
        runtime_asset(
            source,
            "bitchord/runtimes/licenses/"
            + runtime
            + "/"
            + source.relative_to(notice_root).as_posix(),
        )
    pin = (
        (ROOT / "native/tor/PINNED.json")
        if runtime == "tor"
        else base / "build-hashes.json"
    )
    runtime_asset(pin, "bitchord/runtimes/" + runtime + "-inputs.json")
    for name in ("dependencies.json", "runtime-manifest.json", "provenance.json"):
        source = base / name
        if source.is_file():
            runtime_asset(source, "bitchord/runtimes/" + runtime + "-" + name)

certificate_root = ROOT / ".deps/i2p/assets/certificates"
certificates = sorted(path for path in certificate_root.rglob("*") if path.is_file())
certificate_hashes = json.loads((ROOT / ".deps/i2p/runtime-manifest.json").read_text())[
    "certificates"
]
if not certificates or len(certificates) != len(certificate_hashes):
    raise RuntimeError("Incomplete I2P bootstrap certificates")
for source in certificates:
    relative = source.relative_to(certificate_root).as_posix()
    if (
        hashlib.sha256(source.read_bytes()).hexdigest()
        != certificate_hashes["certificates/" + relative]
    ):
        raise RuntimeError("Changed I2P bootstrap certificate: " + relative)
    runtime_asset(source, "bitchord/i2p/certificates/" + relative)
(bundle / "runtimes/assets.list").write_text("\n".join(sorted(asset_paths)) + "\n")
shutil.copytree(ROOT / "licenses", bundle / "licenses", dirs_exist_ok=True)
shutil.copy2(
    ROOT / "vendor/soulseek-rs-lib/LICENSE", bundle / "licenses/soulseek-rs-MIT.txt"
)
(bundle / "META-INF").mkdir(exist_ok=True)
(bundle / "META-INF/MANIFEST.MF").write_text(
    f"Manifest-Version: 1.0\nName: BitChord Morphe Patches\nVersion: {VERSION}\nPatcher-Version: {PATCHER_VERSION}\n\n"
)
output = OUT / BUNDLE_NAME
jar(output, bundle)
print(f"Built {output} ({output.stat().st_size:,} bytes)")
print(hashlib.sha256(output.read_bytes()).hexdigest())

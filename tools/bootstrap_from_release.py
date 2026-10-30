#!/usr/bin/env python3
"""Verify a pinned release MPP and stage its unchanged native dependencies.

This rebuilds Java/Kotlin tooling inputs; it does not rebuild native TDLib.
No credentials or account/session data are read.
"""

from pathlib import Path
import argparse
import hashlib
import json
import re
import shutil
import tarfile
import time
import urllib.error
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64")
RUNTIMES = {
    "direct": "libbitchord_direct.so",
    "i2p": "libbitchord_i2p.so",
    "tor": "libtor.so",
    "ipfs": "libbitchord_ipfs.so",
}
ASSET_ROOT = "runtimes/assets/"


def native_hashes(metadata):
    result = {}
    for field in (
        "native_sha256",
        "soulseek_native_sha256",
        "download_runtime_native_sha256",
    ):
        for name, expected in metadata.get(field, {}).items():
            if not re.fullmatch(
                r"lib/(arm64-v8a|armeabi-v7a|x86_64)/[A-Za-z0-9_+.-]+\.so", name
            ):
                raise ValueError("Unsafe native dependency path")
            if name in result and result[name] != expected:
                raise ValueError("Conflicting native dependency hashes")
            result[name] = expected
    return result


def runtime_assets(archive):
    """Resolve only the bounded runtime manifest, never arbitrary ZIP paths."""
    try:
        info = archive.getinfo("runtimes/assets.list")
    except KeyError:
        return {}
    if info.file_size > 1024 * 1024:
        raise ValueError("Runtime asset manifest is too large")
    names = archive.read(info).decode("utf-8").splitlines()
    if not 1 <= len(names) <= 4096 or len(set(names)) != len(names):
        raise ValueError("Invalid runtime asset manifest")
    assets = {}
    total = 0
    for name in names:
        parts = name.split("/")
        if (
            not name.startswith("bitchord/")
            or len(name) > 512
            or any(part in ("", ".", "..") for part in parts)
            or "\\" in name
            or any(ord(char) < 32 for char in name)
        ):
            raise ValueError("Unsafe runtime asset path")
        entry = archive.getinfo(ASSET_ROOT + name)
        if entry.is_dir() or entry.file_size > 8 * 1024 * 1024:
            raise ValueError("Invalid runtime asset size")
        total += entry.file_size
        if total > 32 * 1024 * 1024:
            raise ValueError("Runtime assets exceed staging limit")
        assets[name] = entry
    return assets


def digest(data):
    return hashlib.sha256(data).hexdigest()


def download(url, target, attempts=5):
    """Download url to target, retrying rate limits, server errors and dropped connections."""
    for attempt in range(1, attempts + 1):
        try:
            with urllib.request.urlopen(url, timeout=120) as response, open(
                target, "wb"
            ) as out:
                shutil.copyfileobj(response, out)
            return
        except urllib.error.HTTPError as error:
            if error.code != 429 and error.code < 500 or attempt == attempts:
                raise
            delay = int(error.headers.get("Retry-After", "0") or 0) or 5 * 2**attempt
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            if attempt == attempts:
                raise
            delay = 5 * 2**attempt
        print(f"Download failed, retrying in {delay}s: {url}", flush=True)
        time.sleep(min(delay, 120))


def verify_bundle(path, metadata):
    if digest(path.read_bytes()) != metadata["patch_bundle_sha256"]:
        raise ValueError("Release bundle SHA-256 does not match PROVENANCE.json")
    with zipfile.ZipFile(path) as archive:
        if len(set(archive.namelist())) != len(archive.namelist()):
            raise ValueError("Duplicate release bundle entry")
        if archive.testzip() is not None:
            raise ValueError("Corrupt release bundle")
        for name, expected in native_hashes(metadata).items():
            if digest(archive.read(name)) != expected:
                raise ValueError("Native dependency hash mismatch: " + name)
        assets = runtime_assets(archive)
        if metadata.get("download_runtime_native_sha256"):
            if not assets:
                raise ValueError("Download runtime assets are missing")
            for runtime, library in RUNTIMES.items():
                inputs = json.loads(
                    archive.read(
                        ASSET_ROOT + "bitchord/runtimes/" + runtime + "-inputs.json"
                    )
                )
                libraries = inputs["libraries"] if runtime == "tor" else inputs
                for abi in ABIS:
                    name = f"lib/{abi}/{library}"
                    entry = libraries[abi]
                    if entry["sha256"] != metadata[
                        "download_runtime_native_sha256"
                    ].get(name) or archive.getinfo(name).file_size != entry.get(
                        "bytes", entry.get("size")
                    ):
                        raise ValueError("Download runtime inventory mismatch: " + name)
                prefix = "bitchord/runtimes/licenses/" + runtime + "/"
                if not any(name.startswith(prefix) for name in assets):
                    raise ValueError("Download runtime notices are missing: " + runtime)
            manifest = json.loads(
                archive.read(ASSET_ROOT + "bitchord/runtimes/i2p-runtime-manifest.json")
            )
            certificates = {
                name.removeprefix("bitchord/i2p/")
                for name in assets
                if name.startswith("bitchord/i2p/certificates/")
            }
            if certificates != set(manifest["certificates"]):
                raise ValueError("I2P certificate inventory is incomplete")
            for name, expected in manifest["certificates"].items():
                if (
                    digest(archive.read(ASSET_ROOT + "bitchord/i2p/" + name))
                    != expected
                ):
                    raise ValueError("I2P certificate hash mismatch")
    api = ROOT / "vendor/tdlib-api/TdApi.java"
    if digest(api.read_bytes()) != metadata["generated_tdapi_sha256"]:
        raise ValueError("Generated TDLib Java API hash mismatch")


def stage_native(path, tools, root=None):
    root = ROOT if root is None else Path(root)
    td = root / ".deps/tdlib"
    with zipfile.ZipFile(path) as archive:
        for abi in ABIS:
            for name in (
                "libtdjni.so",
                "libsslx.so",
                "libcryptox.so",
                "libc++_shared.so",
            ):
                if name == "libtdjni.so":
                    target = td / f"src/main/libs/30.0.16248370/android-24/{abi}" / name
                elif name == "libc++_shared.so":
                    target = tools / "libcxx" / abi / name
                else:
                    target = td / f"openssl/30.0.16248370/android-24/{abi}/lib" / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.read(f"lib/{abi}/{name}"))
            soulseek = f"lib/{abi}/libbitchord_soulseek.so"
            if soulseek in archive.namelist():
                target = root / ".deps/soulseek" / abi / "libbitchord_soulseek.so"
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.read(soulseek))
            for runtime, library in RUNTIMES.items():
                name = f"lib/{abi}/{library}"
                if name in archive.namelist():
                    target = root / ".deps" / runtime / "lib" / abi / library
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(archive.read(name))
        assets = runtime_assets(archive)
        if assets:
            # These directories contain generated build inputs, never phone data.
            # Do not carry certificates or notices from a previous release forward.
            shutil.rmtree(root / ".deps/i2p/assets/certificates", ignore_errors=True)
            for runtime in RUNTIMES:
                base = root / ".deps" / runtime
                shutil.rmtree(base / "licenses", ignore_errors=True)
                for name in (
                    "dependencies.json",
                    "runtime-manifest.json",
                    "provenance.json",
                ):
                    (base / name).unlink(missing_ok=True)
        for name, entry in assets.items():
            relative = name.removeprefix("bitchord/")
            if relative.startswith("i2p/certificates/"):
                target = root / ".deps/i2p/assets" / relative.removeprefix("i2p/")
            elif relative.startswith("runtimes/licenses/"):
                components = relative.split("/")
                runtime = components[2]
                if runtime not in RUNTIMES:
                    raise ValueError("Unknown runtime notice scope")
                target = root / ".deps" / runtime / "licenses" / Path(*components[3:])
            elif relative.startswith("runtimes/"):
                filename = relative.removeprefix("runtimes/")
                runtime, separator, suffix = filename.partition("-")
                if runtime not in RUNTIMES or not separator or "/" in suffix:
                    raise ValueError("Unknown runtime metadata scope")
                if suffix == "inputs.json":
                    suffix = "PINNED.json" if runtime == "tor" else "build-hashes.json"
                elif suffix not in (
                    "dependencies.json",
                    "runtime-manifest.json",
                    "provenance.json",
                ):
                    raise ValueError("Unknown runtime metadata file")
                target = root / ".deps" / runtime / suffix
            else:
                raise ValueError("Unknown runtime asset scope")
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(entry))
    target = td / "src/main/java/org/drinkless/tdlib"
    target.mkdir(parents=True, exist_ok=True)
    for name in ("Client.java", "TdApi.java"):
        shutil.copy2(ROOT / "vendor/tdlib-api" / name, target / name)


def download_tools(tools, metadata):
    tools.mkdir(parents=True, exist_ok=True)
    for item in metadata["downloads"]:
        if item["file"] == "ndk.zip":
            continue  # exact libc++ runtime is staged from the verified release
        target = tools / item["file"]
        if not target.exists() or digest(target.read_bytes()) != item["sha256"]:
            temporary = target.with_suffix(target.suffix + ".download")
            print("Downloading pinned tool:", item["file"], flush=True)
            download(item["url"], temporary)
            if digest(temporary.read_bytes()) != item["sha256"]:
                temporary.unlink()
                raise ValueError("Build tool hash mismatch: " + item["file"])
            temporary.replace(target)
    with tarfile.open(tools / "jdk21.tar.gz") as archive:
        archive.extractall(tools, filter="data")
    with zipfile.ZipFile(tools / "kotlin.zip") as archive:
        # Hash-pinned upstream archive; reject absolute/traversal names too.
        for name in archive.namelist():
            if name.startswith("/") or ".." in Path(name).parts:
                raise ValueError("Unsafe compiler archive path")
        archive.extractall(tools)
    for path in (tools / "kotlinc/bin").iterdir():
        path.chmod(path.stat().st_mode | 0o111)
    with zipfile.ZipFile(tools / "platform35.zip") as archive:
        name = next(n for n in archive.namelist() if n.endswith("/android.jar"))
        (tools / "android.jar").write_bytes(archive.read(name))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--bundle",
        type=Path,
        required=True,
        help="Release MPP whose hash matches PROVENANCE.json",
    )
    parser.add_argument(
        "--verify-only",
        action="store_true",
        help="Validate without writing/downloading",
    )
    parser.add_argument(
        "--download-tools",
        action="store_true",
        help="Fetch and unpack hash-pinned Java/Kotlin tools",
    )
    args = parser.parse_args()
    if args.verify_only and args.download_tools:
        parser.error("--verify-only cannot download tools")
    metadata = json.loads((ROOT / "PROVENANCE.json").read_text())
    verify_bundle(args.bundle, metadata)
    if not args.verify_only:
        tools = ROOT / ".deps/tools"
        stage_native(args.bundle, tools)
        if args.download_tools:
            download_tools(tools, metadata)
    print(
        f"PASS: release MPP, {len(native_hashes(metadata))} pinned native files and generated Java API hashes verified."
    )
    if not args.verify_only:
        print(
            "Native dependencies staged in .deps; supply matching tools or use --download-tools."
        )


if __name__ == "__main__":
    main()

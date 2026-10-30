#!/usr/bin/env python3
"""Build the pinned on-device Direct QUIC JNI library for the supported Android ABIs.

This does not download SDKs/toolchains, handle credentials or publish anything.
Set ANDROID_NDK_HOME to an NDK or its LLVM prebuilt directory. The local bootstrap
layout (.deps/ndk plus .deps/cargo and .deps/rustup) is detected automatically.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
TARGETS = {
    "arm64-v8a": ("aarch64-linux-android", "aarch64-linux-android26-clang", 183, 2),
    "armeabi-v7a": (
        "armv7-linux-androideabi",
        "armv7a-linux-androideabi26-clang",
        40,
        1,
    ),
    "x86_64": ("x86_64-linux-android", "x86_64-linux-android26-clang", 62, 2),
}


def verify_elf(data, machine, elf_class):
    if (
        len(data) < 64
        or data[:4] != b"\x7fELF"
        or data[4] != elf_class
        or data[5] != 1
        or struct.unpack_from("<H", data, 18)[0] != machine
    ):
        raise ValueError(
            "Direct QUIC output is not the expected little-endian Android ELF"
        )

    offset = struct.unpack_from(
        "<Q" if elf_class == 2 else "<I", data, 32 if elf_class == 2 else 28
    )[0]
    entry = struct.unpack_from("<H", data, 54 if elf_class == 2 else 42)[0]
    count = struct.unpack_from("<H", data, 56 if elf_class == 2 else 44)[0]
    align_offset, align_format = (48, "<Q") if elf_class == 2 else (28, "<I")
    loads = 0
    for index in range(count):
        position = offset + index * entry
        if struct.unpack_from("<I", data, position)[0] == 1:
            loads += 1
            if (
                struct.unpack_from(align_format, data, position + align_offset)[0]
                < 16384
            ):
                raise ValueError("Direct QUIC ELF LOAD segment lacks 16 KiB alignment")
    if not loads:
        raise ValueError("Direct QUIC ELF has no LOAD segments")


def verify_stun_snapshot(refresh=False):
    manifest = ROOT / "resources/soulseek/stun-servers-source.json"
    pin = json.loads(manifest.read_text())
    target = ROOT / "resources/soulseek/stun-servers.txt"
    if refresh:
        with urllib.request.urlopen(pin["url"], timeout=60) as source:
            data = source.read(65537)
        if len(data) > 65536 or hashlib.sha256(data).hexdigest() != pin["sha256"]:
            raise ValueError("Pinned STUN snapshot SHA-256 mismatch")
        pending = target.with_suffix(".txt.pending")
        pending.write_bytes(data)
        pending.replace(target)
    data = target.read_bytes()
    if (
        len(data) > 65536
        or len(data) != pin["bytes"]
        or hashlib.sha256(data).hexdigest() != pin["sha256"]
    ):
        raise ValueError("Bundled pinned STUN snapshot SHA-256 mismatch")


def find_toolchain():
    ndk = Path(os.environ.get("ANDROID_NDK_HOME", ROOT / ".deps/ndk"))
    candidates = [ndk, ndk / "toolchains/llvm/prebuilt/linux-x86_64"]
    for candidate in candidates:
        if (candidate / "bin/clang").is_file():
            return candidate.resolve()
    raise SystemExit(
        "Set ANDROID_NDK_HOME to an installed NDK (or LLVM prebuilt directory)."
    )


def licenses(cargo, manifest, env, destination):
    """Collect notices from exactly the locked dependency graph, preserving nested notices."""
    metadata = json.loads(
        subprocess.check_output(
            [
                cargo,
                "metadata",
                "--locked",
                "--offline",
                "--format-version",
                "1",
                "--manifest-path",
                str(manifest),
                "--filter-platform",
                "aarch64-linux-android",
            ],
            cwd=ROOT,
            env=env,
        )
    )
    resolved = {item["id"] for item in metadata["resolve"]["nodes"]}
    inventory = []
    for package in sorted(
        metadata["packages"], key=lambda item: (item["name"], item["version"])
    ):
        if package["id"] not in resolved:
            continue
        item = {
            key: package.get(key)
            for key in ("name", "version", "license", "repository", "source")
        }
        directory = Path(package["manifest_path"]).parent
        files = {
            path
            for path in directory.rglob("*")
            if path.is_file()
            and path.name.upper().startswith(
                ("LICENSE", "LICENCE", "COPYING", "NOTICE", "COPYRIGHT")
            )
        }
        explicit = package.get("license_file")
        if explicit and (directory / explicit).is_file():
            files.add(directory / explicit)
        copied = []
        for source in sorted(files):
            target = (
                destination
                / "licenses"
                / (package["name"] + "-" + package["version"])
                / source.relative_to(directory)
            )
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
            copied.append(str(target.relative_to(destination)))
        item["licenseFiles"] = copied
        inventory.append(item)
    text = json.dumps(inventory, indent=2) + "\n"
    destination.mkdir(parents=True, exist_ok=True)
    (destination / "dependencies.json").write_text(text)
    (ROOT / "native/direct/DEPENDENCIES.json").write_text(text)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--abi", choices=TARGETS, action="append", help="Default: all supported ABIs"
    )
    parser.add_argument(
        "--offline", action="store_true", help="Require dependencies already cached"
    )
    parser.add_argument(
        "--refresh-stun",
        action="store_true",
        help="Re-download only the immutable hash-pinned STUN snapshot",
    )
    args = parser.parse_args()
    verify_stun_snapshot(args.refresh_stun)
    cargo = os.environ.get("DIRECT_CARGO") or shutil.which("cargo")
    if not cargo:
        local = ROOT / ".deps/cargo/bin/cargo"
        if local.is_file():
            cargo = str(local)
    if not cargo:
        raise SystemExit("Install Rust 1.91+ and Android targets, or set DIRECT_CARGO.")
    toolchain = find_toolchain()
    env = dict(os.environ)
    for variable, directory in (
        ("CARGO_HOME", ".deps/cargo"),
        ("RUSTUP_HOME", ".deps/rustup"),
    ):
        local = ROOT / directory
        if variable not in env and local.is_dir():
            env[variable] = str(local)
    env["PATH"] = str(Path(cargo).resolve().parent) + os.pathsep + env.get("PATH", "")
    manifest = ROOT / "native/direct/Cargo.toml"
    target_dir = ROOT / "build/direct-native"
    out = Path(os.environ.get("DIRECT_NATIVE", ROOT / ".deps/direct"))
    hashes_path = out / "build-hashes.json"
    hashes = json.loads(hashes_path.read_text()) if hashes_path.exists() else {}
    for abi in args.abi or TARGETS:
        target, linker_name, machine, elf_class = TARGETS[abi]
        linker = toolchain / "bin" / linker_name
        if not linker.is_file():
            raise SystemExit(f"NDK linker missing: {linker}")
        child = dict(env)
        child["CARGO_TARGET_" + target.upper().replace("-", "_") + "_LINKER"] = str(
            linker
        )
        compiler_target = target.replace("-", "_")
        child["CC_" + compiler_target] = str(linker)
        child["AR_" + compiler_target] = str(toolchain / "bin/llvm-ar")
        child["RUSTFLAGS"] = " ".join(
            filter(
                None,
                (
                    env.get("RUSTFLAGS", ""),
                    "-C link-arg=-Wl,-z,max-page-size=16384",
                    "-C link-arg=-Wl,-z,relro,-z,now",
                ),
            )
        )
        command = [
            cargo,
            "build",
            "--locked",
            "--release",
            "--manifest-path",
            str(manifest),
            "--target-dir",
            str(target_dir),
            "--target",
            target,
        ]
        if args.offline:
            command.append("--offline")
        child["CARGO_BUILD_JOBS"] = "2"
        subprocess.run(command, cwd=ROOT, env=child, check=True)
        built = target_dir / target / "release/libbitchord_direct.so"
        data = built.read_bytes()
        verify_elf(data, machine, elf_class)
        directory = out / "lib" / abi
        directory.mkdir(parents=True, exist_ok=True)
        destination = directory / built.name
        pending = directory / (built.name + ".pending")
        pending.write_bytes(data)
        pending.replace(destination)
        hashes[abi] = {
            "sha256": hashlib.sha256(data).hexdigest(),
            "bytes": len(data),
            "api": 26,
            "elfLoadAlignment": 16384,
        }
        print(f"Built {abi}: {len(data):,} bytes", flush=True)
    (out / "build-hashes.json").write_text(json.dumps(hashes, indent=2) + "\n")
    licenses(cargo, manifest, env, out)


if __name__ == "__main__":
    main()

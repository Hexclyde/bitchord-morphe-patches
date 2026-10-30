#!/usr/bin/env python3
"""Build the pinned on-device Soulseek JNI library for the supported Android ABIs.

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
            "Soulseek output is not the expected little-endian Android ELF"
        )


def find_toolchain():
    ndk = Path(os.environ.get("ANDROID_NDK_HOME", ROOT / ".deps/ndk"))
    candidates = [ndk, ndk / "toolchains/llvm/prebuilt/linux-x86_64"]
    for candidate in candidates:
        if (candidate / "bin/clang").is_file():
            return candidate.resolve()
    raise SystemExit(
        "Set ANDROID_NDK_HOME to an installed NDK (or LLVM prebuilt directory)."
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--abi", choices=TARGETS, action="append", help="Default: all supported ABIs"
    )
    parser.add_argument(
        "--offline", action="store_true", help="Require dependencies already cached"
    )
    args = parser.parse_args()
    cargo = os.environ.get("SOULSEEK_CARGO") or shutil.which("cargo")
    if not cargo:
        local = ROOT / ".deps/cargo/bin/cargo"
        if local.is_file():
            cargo = str(local)
    if not cargo:
        raise SystemExit(
            "Install Rust 1.91+ and Android targets, or set SOULSEEK_CARGO."
        )
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
    manifest = ROOT / "native/soulseek/Cargo.toml"
    target_dir = ROOT / "build/soulseek-native"
    out = Path(os.environ.get("SOULSEEK_NATIVE", ROOT / ".deps/soulseek"))
    hashes = {}
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
        subprocess.run(command, cwd=ROOT, env=child, check=True)
        built = target_dir / target / "release/libbitchord_soulseek.so"
        data = built.read_bytes()
        verify_elf(data, machine, elf_class)
        directory = out / abi
        directory.mkdir(parents=True, exist_ok=True)
        destination = directory / built.name
        pending = directory / (built.name + ".pending")
        pending.write_bytes(data)
        pending.replace(destination)
        hashes[abi] = {
            "sha256": hashlib.sha256(data).hexdigest(),
            "bytes": len(data),
            "api": 26,
        }
        print(f"Built {abi}: {len(data):,} bytes", flush=True)
    (out / "build-hashes.json").write_text(json.dumps(hashes, indent=2) + "\n")


if __name__ == "__main__":
    main()

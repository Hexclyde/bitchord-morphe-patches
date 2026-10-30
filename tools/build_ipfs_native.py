#!/usr/bin/env python3
"""Build pinned Beetswap/libp2p JNI libraries; no discovery or peer network traffic.

Uses Rust 1.91+ and a local Android NDK at ANDROID_NDK_HOME or .deps/ndk.
Outputs .deps/ipfs/{abi}/libbitchord_ipfs.so, hashes and dependency licenses.
"""

import argparse
import hashlib
import json
import os
import re
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
        raise ValueError("IPFS output is not the expected Android ELF")
    if elf_class == 2:
        offset = struct.unpack_from("<Q", data, 32)[0]
        entry, count = struct.unpack_from("<HH", data, 54)
        align_offset, align_format = 48, "<Q"
    else:
        offset = struct.unpack_from("<I", data, 28)[0]
        entry, count = struct.unpack_from("<HH", data, 42)
        align_offset, align_format = 28, "<I"
    loads = 0
    for index in range(count):
        pos = offset + index * entry
        if struct.unpack_from("<I", data, pos)[0] == 1:
            loads += 1
            if struct.unpack_from(align_format, data, pos + align_offset)[0] < 16384:
                raise ValueError("IPFS ELF LOAD segment lacks 16 KiB alignment")
    if not loads:
        raise ValueError("IPFS ELF has no LOAD segments")


def licenses(cargo, manifest, env, destination):
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
    tree = subprocess.check_output(
        [
            cargo,
            "tree",
            "--locked",
            "--offline",
            "--manifest-path",
            str(manifest),
            "--target",
            "aarch64-linux-android",
            "--edges",
            "normal,build",
            "--prefix",
            "none",
            "--format",
            "{p}",
        ],
        cwd=ROOT,
        env=env,
        text=True,
    )
    # Metadata also includes optional/dev packages not used by this library.
    resolved = {
        (m.group(1), m.group(2))
        for line in tree.splitlines()
        if (m := re.match(r"^(\S+) v(\S+)", line))
    }
    inventory = []
    for package in sorted(
        metadata["packages"], key=lambda p: (p["name"], p["version"])
    ):
        if (package["name"], package["version"]) not in resolved:
            continue
        item = {
            key: package.get(key)
            for key in ("name", "version", "license", "repository", "source")
        }
        directory = Path(package["manifest_path"]).parent
        files = [
            p
            for p in directory.iterdir()
            if p.is_file()
            and p.name.upper().startswith(("LICENSE", "LICENCE", "COPYING", "NOTICE"))
        ]
        explicit = package.get("license_file")
        if explicit and (directory / explicit).is_file():
            files.append(directory / explicit)
        copied = []
        for source in set(files):
            target = (
                destination
                / "licenses"
                / (package["name"] + "-" + package["version"])
                / source.name
            )
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
            copied.append(str(target.relative_to(destination)))
        item["licenseFiles"] = sorted(copied)
        inventory.append(item)
    text = json.dumps(inventory, indent=2) + "\n"
    (destination / "dependencies.json").write_text(text)
    (ROOT / "native/ipfs/DEPENDENCIES.json").write_text(text)
    digest = hashlib.sha256()
    source = ROOT / "native/ipfs"
    paths = (
        [source / "Cargo.toml", source / "Cargo.lock"]
        + sorted(source.rglob("*.rs"))
        + sorted((source / "vendor").rglob("Cargo.toml"))
    )
    for path in paths:
        digest.update(
            str(path.relative_to(source)).encode() + b"\0" + path.read_bytes() + b"\0"
        )
    (destination / "provenance.json").write_text(
        json.dumps(
            {
                "sourceSha256": digest.hexdigest(),
                "beetswapUpstreamVersion": "0.5.0",
                "beetswapUpstreamArchiveSha256": "1a920844a4696d53e72190a8348f0048a6799647b3448d38bef90b85e0310df0",
                "localPatch": "native/ipfs/vendor/PATCHES.md",
                "cargo": subprocess.check_output(
                    [cargo, "--version"], cwd=ROOT, env=env, text=True
                ).strip(),
                "dependencyScope": "Android normal and build dependencies; optional/dev-only packages excluded",
            },
            indent=2,
        )
        + "\n"
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abi", choices=TARGETS, action="append")
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    env = dict(os.environ)
    for name, path in (("CARGO_HOME", ".deps/cargo"), ("RUSTUP_HOME", ".deps/rustup")):
        if name not in env and (ROOT / path).is_dir():
            env[name] = str(ROOT / path)
    cargo = os.environ.get("IPFS_CARGO") or str(ROOT / ".deps/cargo/bin/cargo")
    if not Path(cargo).is_file():
        cargo = shutil.which("cargo")
    if not cargo:
        raise SystemExit("Rust 1.91+ cargo is required")
    env["PATH"] = str(Path(cargo).resolve().parent) + os.pathsep + env.get("PATH", "")
    env["CARGO_BUILD_JOBS"] = "2"
    env["CARGO_INCREMENTAL"] = "0"
    ndk = Path(env.get("ANDROID_NDK_HOME", ROOT / ".deps/ndk"))
    toolchain = next(
        (
            p
            for p in (ndk, ndk / "toolchains/llvm/prebuilt/linux-x86_64")
            if (p / "bin/clang").is_file()
        ),
        None,
    )
    if toolchain is None:
        raise SystemExit(
            "Set ANDROID_NDK_HOME to the Android NDK LLVM prebuilt directory"
        )
    manifest = ROOT / "native/ipfs/Cargo.toml"
    target_dir = ROOT / "build/ipfs-native"
    out = Path(env.get("IPFS_NATIVE", ROOT / ".deps/ipfs"))
    out.mkdir(parents=True, exist_ok=True)
    hashes_path = out / "build-hashes.json"
    hashes = json.loads(hashes_path.read_text()) if hashes_path.exists() else {}
    for abi in args.abi or TARGETS:
        target, linker, machine, elf_class = TARGETS[abi]
        child = dict(env)
        child["CARGO_TARGET_" + target.upper().replace("-", "_") + "_LINKER"] = str(
            toolchain / "bin" / linker
        )
        child["CC_" + target.replace("-", "_")] = str(toolchain / "bin" / linker)
        child["AR_" + target.replace("-", "_")] = str(toolchain / "bin/llvm-ar")
        child["RUSTFLAGS"] = (
            env.get("RUSTFLAGS", "")
            + " -C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,relro,-z,now"
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
        built = target_dir / target / "release/libbitchord_ipfs.so"
        data = built.read_bytes()
        verify_elf(data, machine, elf_class)
        destination = out / abi / built.name
        destination.parent.mkdir(parents=True, exist_ok=True)
        pending = destination.with_suffix(".so.pending")
        pending.write_bytes(data)
        pending.replace(destination)
        hashes[abi] = {
            "sha256": hashlib.sha256(data).hexdigest(),
            "bytes": len(data),
            "api": 26,
            "elfLoadAlignment": 16384,
        }
        hashes_path.write_text(json.dumps(hashes, indent=2) + "\n")
        print(f"Built IPFS {abi}: {len(data):,} bytes", flush=True)
    licenses(cargo, manifest, env, out)


if __name__ == "__main__":
    main()

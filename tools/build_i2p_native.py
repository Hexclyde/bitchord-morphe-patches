#!/usr/bin/env python3
"""Build the pinned embedded i2pd JNI runtime, without starting an I2P router.

Sources are checksum verified by native/i2p/prepare.py. Android API 26, static
OpenSSL/libc++, hidden symbols and 16 KiB ELF load alignment are mandatory.
"""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
TARGETS = {
    "arm64-v8a": (
        "aarch64-linux-android",
        "aarch64-linux-android26-clang",
        "android-arm64",
        183,
        2,
    ),
    "armeabi-v7a": (
        "arm-linux-androideabi",
        "armv7a-linux-androideabi26-clang",
        "android-arm",
        40,
        1,
    ),
    "x86_64": (
        "x86_64-linux-android",
        "x86_64-linux-android26-clang",
        "android-x86_64",
        62,
        2,
    ),
}


def run(command, *, cwd=ROOT, env=None, log=None):
    if log:
        with log.open("ab") as target:
            result = subprocess.run(
                [str(x) for x in command],
                cwd=cwd,
                env=env,
                stdout=target,
                stderr=subprocess.STDOUT,
            )
        if result.returncode:
            tail = log.read_text(errors="replace").splitlines()[-45:]
            raise RuntimeError("\n".join(tail))
    else:
        subprocess.run([str(x) for x in command], cwd=cwd, env=env, check=True)


def toolchain():
    root = Path(os.environ.get("ANDROID_NDK_HOME", ROOT / ".deps/ndk"))
    for path in (root, root / "toolchains/llvm/prebuilt/linux-x86_64"):
        if (path / "bin/clang").is_file():
            return path.resolve()
    raise RuntimeError(
        "Set ANDROID_NDK_HOME to the Android NDK or LLVM prebuilt directory"
    )


def verify_elf(path, machine, elf_class):
    data = path.read_bytes()
    if (
        data[:6] != b"\x7fELF" + bytes([elf_class, 1])
        or struct.unpack_from("<H", data, 18)[0] != machine
    ):
        raise ValueError("Unexpected Android ELF architecture")
    if elf_class == 2:
        offset, size, count = struct.unpack_from("<Q", data, 32)[
            0
        ], *struct.unpack_from("<HH", data, 54)
        fmt, align_offset = "<I", 48
    else:
        offset, size, count = struct.unpack_from("<I", data, 28)[
            0
        ], *struct.unpack_from("<HH", data, 42)
        fmt, align_offset = "<I", 28
    for index in range(count):
        position = offset + index * size
        if struct.unpack_from(fmt, data, position)[0] == 1:
            alignment = struct.unpack_from(
                "<Q" if elf_class == 2 else "<I", data, position + align_offset
            )[0]
            if alignment < 16384:
                raise ValueError("ELF load segment lacks 16 KiB alignment")
    return {"sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data), "api": 26}


def openssl_build(abi, spec, source, build, ndk, jobs):
    triplet, clang, target, _, _ = spec
    directory = build / "openssl" / abi
    directory.mkdir(parents=True, exist_ok=True)
    # OpenSSL also supports standalone NDK toolchains. This small, private
    # compatibility layout preserves its Android target configuration while
    # selecting the API-qualified clang shipped by both supported NDK layouts.
    standalone = build / "standalone" / abi
    (standalone / "bin").mkdir(parents=True, exist_ok=True)
    (standalone / "AndroidVersion.txt").write_text("API 26 compiler wrappers\n")
    sysroot = standalone / "sysroot"
    if not sysroot.exists():
        sysroot.symlink_to(ndk / "sysroot", target_is_directory=True)
    for suffix, executable in (
        ("clang", clang),
        ("ar", "llvm-ar"),
        ("ranlib", "llvm-ranlib"),
    ):
        wrapper = standalone / "bin" / (triplet + "-" + suffix)
        wrapper.write_text(
            "#!/bin/sh\nexec '"
            + str(ndk / "bin" / executable).replace("'", "'\\''")
            + '\' "$@"\n'
        )
        wrapper.chmod(0o755)
    env = dict(
        os.environ,
        ANDROID_NDK_ROOT=str(standalone),
        CC="clang",
        AR="ar",
        RANLIB="ranlib",
    )
    env["PATH"] = (
        str(standalone / "bin")
        + os.pathsep
        + str(ndk / "bin")
        + os.pathsep
        + env.get("PATH", "")
    )
    marker = directory / ".built"
    if not marker.exists():
        log = directory / "build.log"
        log.unlink(missing_ok=True)
        run(
            [
                "perl",
                source / "Configure",
                target,
                "no-shared",
                "no-tests",
                "no-module",
                "no-legacy",
                "no-apps",
                "no-docs",
                "-fPIC",
                "-O2",
                "-D__ANDROID_API__=26",
            ],
            cwd=directory,
            env=env,
            log=log,
        )
        run(["make", f"-j{jobs}", "build_libs"], cwd=directory, env=env, log=log)
        marker.write_text("OpenSSL 3.5.9, Android API26\n")
    return directory


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abi", choices=TARGETS, action="append")
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--jobs", type=int, default=2, choices=(1, 2))
    args = parser.parse_args()
    prepare = [
        sys.executable,
        ROOT / "native/i2p/prepare.py",
        "--component",
        "i2pd",
        "--component",
        "boost",
        "--component",
        "openssl",
    ]
    if args.offline:
        prepare.append("--offline")
    run(prepare)
    ndk = toolchain()
    base = ROOT / ".deps/i2p"
    source = base / "sources"
    build = ROOT / "build/i2p-native"
    build.mkdir(parents=True, exist_ok=True)
    out = Path(os.environ.get("I2P_NATIVE", base))
    hashes_path = out / "build-hashes.json"
    hashes = json.loads(hashes_path.read_text()) if hashes_path.exists() else {}
    core = source / "i2pd/libi2pd"
    boost = source / "boost"
    sources = sorted(core.glob("*.cpp")) + sorted(
        (boost / "libs/program_options/src").glob("*.cpp")
    )
    sources += [ROOT / "native/i2p/i2p_jni.cpp"]
    for abi in args.abi or TARGETS:
        spec = TARGETS[abi]
        compiler = ndk / "bin" / spec[1].replace("-clang", "-clang++")
        if not compiler.is_file():
            raise RuntimeError("Missing NDK compiler: " + str(compiler))
        print("Building OpenSSL for " + abi, flush=True)
        ssl = openssl_build(abi, spec, source / "openssl", build, ndk, args.jobs)
        objects = build / "objects" / abi
        objects.mkdir(parents=True, exist_ok=True)
        flags = [
            "-std=c++20",
            "-O2",
            "-fPIC",
            "-fvisibility=hidden",
            "-ffunction-sections",
            "-fdata-sections",
            "-fstack-protector-strong",
            "-D_FORTIFY_SOURCE=2",
            "-DANDROID",
            "-DSTD_FILESYSTEM=1",
            "-DNO_TORRENTS",
            "-DOPENSSL_SUPPRESS_DEPRECATED",
            "-DBOOST_ERROR_CODE_HEADER_ONLY",
            "-DBOOST_ASIO_DISABLE_STD_ALIGNED_ALLOC",
            "-I" + str(core),
            "-I" + str(boost),
            "-I" + str(ssl / "include"),
            "-I" + str(source / "openssl/include"),
        ]
        command_hash = hashlib.sha256(
            (str(compiler) + "\0" + "\0".join(flags)).encode()
        ).hexdigest()

        def compile_one(item):
            name = (
                hashlib.sha256(str(item.relative_to(ROOT)).encode()).hexdigest()[:12]
                + "-"
                + item.stem
            )
            obj, log, stamp = (
                objects / (name + ".o"),
                objects / (name + ".log"),
                objects / (name + ".sha256"),
            )
            digest = hashlib.sha256(
                item.read_bytes() + command_hash.encode()
            ).hexdigest()
            if not obj.exists() or not stamp.exists() or stamp.read_text() != digest:
                log.unlink(missing_ok=True)
                run([compiler, *flags, "-c", item, "-o", obj], log=log)
                stamp.write_text(digest)
            return obj

        print(f"Compiling {len(sources)} i2pd/JNI/Boost sources for {abi}", flush=True)
        with ThreadPoolExecutor(max_workers=args.jobs) as pool:
            compiled = list(pool.map(compile_one, sources))
        directory = out / "lib" / abi
        directory.mkdir(parents=True, exist_ok=True)
        pending = directory / "libbitchord_i2p.so.pending"
        run(
            [
                compiler,
                "-shared",
                "-static-libstdc++",
                "-Wl,--no-undefined",
                "-Wl,--gc-sections",
                "-Wl,--exclude-libs,ALL",
                "-Wl,-z,max-page-size=16384",
                "-Wl,-z,relro,-z,now",
                "-Wl,-soname,libbitchord_i2p.so",
                "-Wl,--version-script=" + str(ROOT / "native/i2p/exports.map"),
                *compiled,
                ssl / "libssl.a",
                ssl / "libcrypto.a",
                "-lz",
                "-ldl",
                "-llog",
                "-o",
                pending,
            ],
            log=build / (abi + "-link.log"),
        )
        run([ndk / "bin/llvm-strip", "--strip-unneeded", pending])
        hashes[abi] = verify_elf(pending, spec[3], spec[4])
        pending.replace(directory / "libbitchord_i2p.so")
        hashes_path.parent.mkdir(parents=True, exist_ok=True)
        hashes_path.write_text(json.dumps(hashes, indent=2) + "\n")
        print("Built " + abi + ": " + str(hashes[abi]["bytes"]) + " bytes", flush=True)
    certificates = out / "assets/certificates"
    if certificates.exists():
        shutil.rmtree(certificates)
    shutil.copytree(source / "i2pd/contrib/certificates", certificates)
    licenses = out / "licenses"
    licenses.mkdir(parents=True, exist_ok=True)
    for name, filename in (
        ("i2pd", "LICENSE"),
        ("boost", "LICENSE_1_0.txt"),
        ("openssl", "LICENSE.txt"),
    ):
        shutil.copyfile(source / name / filename, licenses / (name + ".txt"))
    if (ndk / "NOTICE").is_file():
        shutil.copyfile(ndk / "NOTICE", licenses / "android-llvm-runtime-NOTICE.txt")
    (out / "runtime-manifest.json").write_text(
        json.dumps(
            {
                "schema": 1,
                "implementation": "i2pd",
                "version": "3.0.0",
                "api": 26,
                "sources": json.loads((ROOT / "native/i2p/sources.json").read_text()),
                "libraries": hashes,
                "certificates": {
                    str(p.relative_to(out / "assets")): hashlib.sha256(
                        p.read_bytes()
                    ).hexdigest()
                    for p in sorted(certificates.rglob("*"))
                    if p.is_file()
                },
                "compiler": subprocess.check_output(
                    [ndk / "bin/clang", "--version"], text=True
                ).splitlines()[0],
            },
            indent=2,
        )
        + "\n"
    )


if __name__ == "__main__":
    main()

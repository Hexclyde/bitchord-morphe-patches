#!/usr/bin/env python3
"""Build the pinned, offline Streamseek audio decoder for Android API 26+."""

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
ARCHIVE = ROOT / "native/preview/vendor/ffmpeg-7.1.5.tar.xz"
SHA256 = "de668509caf9e35e3cd162473441fdb29538c6d96ed080292b3cf9e6fc5d558f"
NDK = Path(os.environ.get("ANDROID_NDK_HOME", ROOT / ".deps/tools/android-ndk-r27d"))
TC = NDK / "toolchains/llvm/prebuilt/linux-x86_64/bin"
WORK = ROOT / ".deps/preview/build"
OUT = ROOT / ".deps/preview"
TARGETS = {
    "arm64-v8a": ("aarch64-linux-android", "aarch64"),
    "armeabi-v7a": ("armv7a-linux-androideabi", "arm"),
    "x86_64": ("x86_64-linux-android", "x86_64"),
}
DECODERS = (
    "flac,alac,ape,wavpack,tta,tak,shorten,wmalossless,als,dca,truehd,mlp,dst,dsd_lsbf,dsd_msbf,dsd_lsbf_planar,dsd_msbf_planar,mp3,aac,vorbis,opus,"
    + ",".join(
        "pcm_" + x
        for x in [
            "s8",
            "u8",
            "s16le",
            "s16be",
            "s24le",
            "s24be",
            "s32le",
            "s32be",
            "s64le",
            "s64be",
            "f32le",
            "f32be",
            "f64le",
            "f64be",
        ]
    )
)
DEMUXERS = "flac,aiff,wav,w64,mov,caf,ape,wv,tta,tak,shorten,asf,dsf,iff,matroska,ogg,mp3,dts,truehd,mlp"


def run(args, cwd):
    subprocess.run([str(a) for a in args], cwd=cwd, check=True)


def main():
    if hashlib.sha256(ARCHIVE.read_bytes()).hexdigest() != SHA256:
        raise ValueError("FFmpeg source archive checksum mismatch")
    properties = NDK / "source.properties"
    if not properties.is_file() or "27.3.13750724" not in properties.read_text():
        raise ValueError(
            "The decoder build requires pinned Android NDK r27d (27.3.13750724)"
        )
    if not (TC / "clang").is_file():
        raise ValueError("Set ANDROID_NDK_HOME to Android NDK r27d")
    source = WORK / "ffmpeg-7.1.5"
    WORK.mkdir(parents=True, exist_ok=True)
    if not source.exists():
        with tarfile.open(ARCHIVE) as tar:
            tar.extractall(WORK, filter="data")
    inventory = {}
    for abi, (triple, arch) in TARGETS.items():
        build = WORK / abi
        build.mkdir(exist_ok=True)
        flags = [f"--target={triple}26", "-fPIC", "-O2"]
        configure = [
            source / "configure",
            "--enable-cross-compile",
            "--target-os=android",
            f"--arch={arch}",
            f"--cc={TC}/clang",
            f"--cxx={TC}/clang++",
            f"--ar={TC}/llvm-ar",
            f"--nm={TC}/llvm-nm",
            f"--ranlib={TC}/llvm-ranlib",
            f"--strip={TC}/llvm-strip",
            "--extra-cflags=" + " ".join(flags),
            f"--extra-ldflags=--target={triple}26",
            "--enable-static",
            "--disable-shared",
            "--enable-pic",
            "--disable-autodetect",
            "--disable-programs",
            "--disable-doc",
            "--disable-network",
            "--disable-everything",
            "--disable-avdevice",
            "--disable-avfilter",
            "--disable-swscale",
            "--enable-swresample",
            "--disable-asm",
            "--enable-decoder=" + DECODERS,
            "--enable-demuxer=" + DEMUXERS,
            "--enable-parser=flac,mpegaudio,aac,vorbis,opus,tak,dca,mlp",
        ]
        run(configure, build)
        run(["make", "-j" + os.environ.get("JOBS", "6")], build)
        target = OUT / abi / "libbitchord_preview.so"
        target.parent.mkdir(parents=True, exist_ok=True)
        run(
            [
                TC / "clang",
                f"--target={triple}26",
                "-shared",
                "-fPIC",
                "-O2",
                "-fvisibility=hidden",
                "-I" + str(source),
                "-I" + str(build),
                ROOT / "native/preview/preview_jni.c",
                "-Wl,--start-group",
                build / "libavformat/libavformat.a",
                build / "libavcodec/libavcodec.a",
                build / "libswresample/libswresample.a",
                build / "libavutil/libavutil.a",
                "-Wl,--end-group",
                "-lm",
                "-llog",
                "-lz",
                "-Wl,--no-undefined",
                "-Wl,--exclude-libs,ALL",
                "-Wl,-z,max-page-size=16384",
                "-Wl,-soname,libbitchord_preview.so",
                "-o",
                target,
            ],
            build,
        )
        run([TC / "llvm-strip", "--strip-unneeded", target], build)
        inventory[abi] = {
            "sha256": hashlib.sha256(target.read_bytes()).hexdigest(),
            "bytes": target.stat().st_size,
        }
        (build / "configure-command.json").write_text(
            json.dumps([str(x) for x in configure], indent=2) + "\n"
        )
    (OUT / "build-hashes.json").write_text(json.dumps(inventory, indent=2) + "\n")
    notices = OUT / "licenses"
    notices.mkdir(exist_ok=True)
    for name in ["COPYING.LGPLv2.1", "COPYING.LGPLv3", "LICENSE.md"]:
        shutil.copy2(source / name, notices / name)
    shutil.copy2(ROOT / "native/preview/NOTICE", notices / "NOTICE")
    (OUT / "provenance.json").write_text(
        json.dumps(
            {
                "source": "native/preview/vendor/" + ARCHIVE.name,
                "source_sha256": SHA256,
                "version": "7.1.5",
                "ndk": "27.3.13750724",
                "api": 26,
                "decoders": DECODERS.split(","),
                "demuxers": DEMUXERS.split(","),
                "asm": False,
                "network": False,
                "gpl": False,
                "nonfree": False,
            },
            indent=2,
        )
        + "\n"
    )


if __name__ == "__main__":
    main()

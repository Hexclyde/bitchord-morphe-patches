#!/usr/bin/env python3
"""Exercise the installed Streamseek JNI decoder with generated lossless fixtures.

Requires ADB, ffmpeg, JDK 21 and the project's pinned Android/D8 toolchain.
Only /data/local/tmp/bitchord-streamseek-test is written on the device. No app
accounts, settings, transfers or files are changed. Pass --apk and --libraries
from `adb shell pm path` and `adb shell dumpsys package`, respectively.
"""

import argparse
from pathlib import Path
import os
import shutil
import struct
import subprocess
import tempfile
import wave

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent


def run(args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, **kwargs)


def fixtures(root, samples=None):
    for bits, rate in ((24, 96000), (32, 96000), (24, 192000), (32, 384000)):
        raw = bytearray()
        for i in range(12000):
            for channel in range(2):
                value = (i * 170003 + channel * 7919) & ((1 << bits) - 1)
                raw.extend(value.to_bytes(bits // 8, "little"))
        path = root / (f"pcm{bits}.wav" if rate == 96000 else f"pcm{bits}-{rate}.wav")
        with wave.open(str(path), "wb") as wav:
            wav.setnchannels(2)
            wav.setsampwidth(bits // 8)
            wav.setframerate(rate)
            wav.writeframes(raw)
    for name, codec, extra in [
        ("flac.flac", "flac", []),
        ("alac.m4a", "alac", ["-movflags", "+faststart"]),
        ("aac.aac", "aac", ["-f", "adts"]),
        ("aac.ts", "aac", ["-f", "mpegts"]),
        ("aiff.aiff", "pcm_s24be", []),
        ("caf.caf", "pcm_s24be", []),
        ("wave64.w64", "pcm_s24le", []),
        ("rf64.wav", "pcm_s24le", ["-rf64", "always"]),
        ("wavpack.wv", "wavpack", []),
        ("trueaudio.tta", "tta", []),
    ]:
        run(
            [
                "ffmpeg",
                "-v",
                "error",
                "-y",
                "-i",
                root / "pcm24.wav",
                "-c:a",
                codec,
                *extra,
                root / name,
            ]
        )
    dsd = bytes([0x69]) * 4096 + bytes([0x96]) * 4096
    fmt = b"fmt " + struct.pack(
        "<QIIIIIIQII", 52, 1, 0, 2, 2, 2822400, 1, 32768, 4096, 0
    )
    payload = fmt + b"data" + struct.pack("<Q", 12 + len(dsd)) + dsd
    (root / "dsd.dsf").write_bytes(
        b"DSD " + struct.pack("<QQQ", 28, 28 + len(payload), 0) + payload
    )

    def chunk(name, data):
        return (
            name
            + struct.pack(">Q", len(data))
            + data
            + (b"\0" if len(data) % 2 else b"")
        )

    props = (
        b"SND "
        + chunk(b"FS  ", struct.pack(">I", 2822400))
        + chunk(b"CHNL", struct.pack(">H", 2) + b"SLFTSRGT")
        + chunk(b"CMPR", b"DSD " + b"\0")
    )
    interleaved = bytes([0x69, 0x96]) * 4096
    payload = (
        b"DSD "
        + chunk(b"FVER", struct.pack(">I", 0x01050000))
        + chunk(b"PROP", props)
        + chunk(b"DSD ", interleaved)
    )
    (root / "dsd.dff").write_bytes(b"FRM8" + struct.pack(">Q", len(payload)) + payload)
    if samples:
        for sample in sorted(samples.iterdir()):
            if sample.suffix.lower() in {
                ".ape",
                ".tak",
                ".shn",
                ".wma",
                ".mlp",
                ".thd",
                ".dtshd",
            }:
                shutil.copy2(sample, root / sample.name)
    for path in sorted(root.iterdir()):
        if path.is_file():
            run(
                [
                    "ffmpeg",
                    "-v",
                    "error",
                    "-y",
                    "-i",
                    path,
                    "-c:a",
                    "pcm_s32le",
                    "-f",
                    "s32le",
                    str(path) + ".expect",
                ]
            )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True)
    parser.add_argument("--libraries", required=True)
    parser.add_argument("--serial")
    parser.add_argument(
        "--samples",
        type=Path,
        help="Optional existing codec fixtures; never downloaded or redistributed",
    )
    args = parser.parse_args()
    adb = ["adb"] + (["-s", args.serial] if args.serial else [])
    device = "/data/local/tmp/bitchord-streamseek-test"
    java = ROOT / ".deps/tools/jdk-21.0.13+9/bin"
    android = ROOT / ".deps/tools/android.jar"
    with tempfile.TemporaryDirectory(
        prefix="streamseek-test-", dir=ROOT.parent / "artifacts"
    ) as temporary:
        work = Path(temporary)
        audio = work / "audio"
        audio.mkdir()
        classes = work / "classes"
        classes.mkdir()
        fixtures(audio, args.samples)
        run(
            [
                java / "javac",
                "--release",
                "8",
                "-cp",
                android,
                "-d",
                classes,
                *sorted(HERE.glob("*.java")),
            ]
        )
        run(
            [
                java / "java",
                "-cp",
                ROOT / ".deps/tools/r8-latest.jar",
                "com.android.tools.r8.D8",
                "--lib",
                android,
                "--output",
                work / "probe.jar",
                *sorted(classes.glob("*.class")),
            ]
        )
        run([*adb, "shell", "mkdir", "-p", device])
        run([*adb, "push", audio, device])
        run([*adb, "push", work / "probe.jar", device + "/probe.jar"])
        run(
            [
                *adb,
                "shell",
                f"CLASSPATH={device}/probe.jar app_process /system/bin PreviewNativeProbe {args.apk} {args.libraries} {device}/probe.jar {device}/audio",
            ]
        )
        run([*adb, "shell", "rm", "-rf", device])


if __name__ == "__main__":
    main()

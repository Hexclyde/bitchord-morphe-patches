#!/usr/bin/env python3
"""Patch pinned BitChord APKs in each integration combination and inspect the output.

Run build.py first. This creates local test APKs only; it never installs or uploads
anything. Default: all supported ABIs and the five integration/standalone-tweak combinations.
"""

import argparse
import hashlib
import json
import os
import re
import tempfile
import zipfile
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from buildenv import APP_VERSION, BUNDLE_NAME, MORPHE_JAR, jdk  # noqa: E402

MODES = {
    "neither": [],
    "telegram": ["Unofficial Telegram music", "Sign in with MicroG"],
    "soulseek": ["Unofficial Soulseek audio"],
    "tweaks": [
        "Sing",
        "Pitch control",
        "Loudness level",
        "Sleep timer fade-out",
        "Battery saver",
        "Lossless addons over Bluetooth",
        "Per-download quality",
        "Headphone correction",
        "Equaliser per output",
        "DJ mode",
        "JamesDSP",
    ],
    "combined": [
        "Unofficial Telegram music",
        "Sign in with MicroG",
        "Unofficial Soulseek audio",
    ],
}
ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64")


def verify_native(apk):
    """Check Android loader dependencies and hardened ELF segments, not just the filename."""
    with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory() as temporary:
        for name in archive.namelist():
            if name.rsplit("/", 1)[-1] not in {
                "libbitchord_soulseek.so",
                "libbitchord_direct.so",
                "libbitchord_i2p.so",
                "libtor.so",
                "libbitchord_ipfs.so",
            }:
                continue
            abi = name.split("/")[1]
            local = Path(temporary) / (abi + ".so")
            local.write_bytes(archive.read(name))
            headers = subprocess.check_output(["readelf", "-lW", str(local)], text=True)
            dynamic = subprocess.check_output(["readelf", "-dW", str(local)], text=True)
            alignments = [
                int(line.split()[-1], 16)
                for line in headers.splitlines()
                if line.strip().startswith("LOAD")
            ]
            if not alignments or min(alignments) < (
                0x1000 if abi == "armeabi-v7a" else 0x4000
            ):
                raise RuntimeError(
                    "Download runtime page alignment is insufficient: " + name
                )
            if "GNU_RELRO" not in headers or not (
                "BIND_NOW" in dynamic or " NOW" in dynamic
            ):
                raise RuntimeError("Soulseek library is missing RELRO/NOW: " + abi)
            stacks = [line for line in headers.splitlines() if "GNU_STACK" in line]
            if not stacks or any("E" in "".join(line.split()[6:-1]) for line in stacks):
                raise RuntimeError(
                    "Soulseek library has a missing/executable stack: " + abi
                )
            system = {
                "libc.so",
                "libm.so",
                "libdl.so",
                "liblog.so",
                "libandroid.so",
                "libz.so",
                "libstdc++.so",
            }
            for needed in re.findall(r"Shared library: \[([^]]+)\]", dynamic):
                if (
                    needed not in system
                    and f"lib/{abi}/{needed}" not in archive.namelist()
                ):
                    raise RuntimeError(f"Unbundled Android dependency: {abi}/{needed}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--inputs",
        type=Path,
        default=Path(os.environ.get("ORIGINAL_APKS_DIR", ROOT / ".deps/apks")),
    )
    parser.add_argument("--abi", choices=ABIS, action="append")
    parser.add_argument("--mode", choices=MODES, action="append")
    parser.add_argument(
        "--reverse-selection",
        action="store_true",
        help="Also exercise reversed explicit patch selection",
    )
    args = parser.parse_args()
    java = str(jdk() / "bin/java")
    javac = str(jdk() / "bin/javac")
    out = ROOT / "build/soulseek-validation"
    out.mkdir(parents=True, exist_ok=True)
    checks = out / "checks"
    checks.mkdir(exist_ok=True)
    subprocess.run(
        [
            javac,
            "--release",
            "21",
            "-cp",
            str(MORPHE_JAR),
            "-d",
            str(checks),
            *map(str, (ROOT / "checks").glob("Verify*.java")),
        ],
        check=True,
    )
    classpath = str(checks) + os.pathsep + str(MORPHE_JAR)
    bundle = ROOT / "build" / BUNDLE_NAME
    provenance = json.loads((ROOT / "PROVENANCE.json").read_text())
    results = []
    for abi in args.abi or ABIS:
        original = args.inputs / f"BitChord-v{APP_VERSION}_{abi}.apk"
        expected = next(
            p["sha256"]
            for p in provenance["original_apks"]
            if p["file"] == original.name
        )
        if hashlib.sha256(original.read_bytes()).hexdigest() != expected:
            raise RuntimeError("Original APK hash mismatch: " + original.name)
        for mode in args.mode or MODES:
            selected = ["Install as Bitchord+", *MODES[mode]]
            if args.reverse_selection:
                selected.reverse()
            suffix = "-reverse" if args.reverse_selection else ""
            name = f"{abi}-{mode}{suffix}"
            patched = out / f"{name}.apk"
            report = out / f"{name}-patch.json"
            command = [
                java,
                "-Xshare:off",
                "-Xmx4g",
                "-jar",
                str(MORPHE_JAR),
                "patch",
                "--validate",
                "--exclusive",
                "-p",
                str(bundle),
                *[arg for patch in selected for arg in ("-e", patch)],
                "-r",
                str(report),
                "-o",
                str(patched),
                str(original),
            ]
            with (out / f"{name}-patch.log").open("w") as log:
                subprocess.run(
                    command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True
                )
            patch_result = json.loads(report.read_text())
            if not patch_result.get("success") or patch_result.get("failedPatches"):
                raise RuntimeError("Patch report failed: " + name)
            telegram = "Unofficial Telegram music" in selected
            soulseek = "Unofficial Soulseek audio" in selected
            tweaks = mode == "tweaks"
            for checker, check_args in [
                ("VerifyDexControlFlow", [str(patched)]),
                ("VerifyBeta7Apk", [str(patched)]),
                (
                    "VerifyPatchUiApk",
                    [
                        str(patched),
                        str(telegram).lower(),
                        str(soulseek).lower(),
                        str(tweaks).lower(),
                    ],
                ),
                (
                    "VerifyApk",
                    [
                        str(original),
                        str(patched),
                        "combined" if telegram else "clone-only",
                    ],
                ),
                (
                    "VerifySoulseekApk",
                    [str(patched), "enabled" if soulseek else "disabled", str(bundle)],
                ),
            ]:
                result = subprocess.run(
                    [
                        java,
                        "-Xshare:off",
                        "-Xmx3g",
                        "-cp",
                        classpath,
                        checker,
                        *check_args,
                    ],
                    cwd=ROOT,
                    capture_output=True,
                    text=True,
                )
                (out / f"{name}-{checker}.log").write_text(
                    result.stdout + result.stderr
                )
                if result.returncode:
                    print(result.stdout + result.stderr, flush=True)
                    result.check_returncode()
                print(f"{name}: {checker}: {result.stdout.strip()}", flush=True)
            if soulseek:
                verify_native(patched)
                print(
                    f"{name}: Android native loader/page alignment/security checks passed",
                    flush=True,
                )
            results.append(
                {"abi": abi, "mode": mode, "selection": selected, "passed": True}
            )
            (out / "results.json").write_text(json.dumps(results, indent=2) + "\n")
    print(f"PASS: {len(results)} pinned APK integration combinations", flush=True)


if __name__ == "__main__":
    main()

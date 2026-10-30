#!/usr/bin/env python3
"""Validate standalone Soulseek on pinned BitChord1.8.1 and1.9 phone APKs.

Selects only Unofficial Soulseek audio. Does not install, publish, sign into a
provider, or apply the Telegram, clone, MicroG or optional playback patches.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from buildenv import BUNDLE_NAME, MORPHE_JAR, jdk  # noqa: E402
from validate_soulseek_apks import ABIS, verify_native  # noqa: E402


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inputs", type=Path, default=ROOT / ".deps/apks")
    parser.add_argument("--version", choices=("1.8.1", "1.9"), action="append")
    parser.add_argument("--abi", choices=(*ABIS, "universal"), action="append")
    args = parser.parse_args()
    provenance = json.loads((ROOT / "PROVENANCE.json").read_text())
    compatibility = json.loads(
        (ROOT / "resources/soulseek/compatibility-1.9.json").read_text()
    )
    pins = {
        item["file"]: item["sha256"]
        for item in provenance["original_apks"]
        + compatibility["original_apks"]
        + compatibility.get("previous_original_apks", [])
    }
    out = ROOT / "build/soulseek-19-validation"
    checks = out / "checks"
    checks.mkdir(parents=True, exist_ok=True)
    java = str(jdk() / "bin/java")
    subprocess.run(
        [
            str(jdk() / "bin/javac"),
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
    bundle = ROOT / "build" / BUNDLE_NAME
    results = []
    for version in args.version or ("1.9", "1.8.1"):
        layouts = (*ABIS, "universal") if version == "1.9" else ABIS
        for abi in args.abi or layouts:
            suffix = "" if abi == "universal" else "_" + abi
            original = args.inputs / f"BitChord-v{version}{suffix}.apk"
            if hashlib.sha256(original.read_bytes()).hexdigest() != pins[original.name]:
                raise ValueError("Original APK hash mismatch: " + original.name)
            name = f"{version}-{abi}-soulseek"
            patched = out / (name + ".apk")
            report = out / (name + "-patch.json")
            with (out / (name + "-patch.log")).open("w") as log:
                subprocess.run(
                    [
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
                        "-e",
                        "Unofficial Soulseek audio",
                        "-r",
                        str(report),
                        "-o",
                        str(patched),
                        str(original),
                    ],
                    cwd=ROOT,
                    stdout=log,
                    stderr=subprocess.STDOUT,
                    check=True,
                )
            result = json.loads(report.read_text())
            if not result.get("success") or result.get("failedPatches"):
                raise ValueError("Patching failed: " + name)
            for checker, values in (
                ("VerifyDexControlFlow", [str(patched)]),
                *(
                    (("VerifyDownloadIntegrationApk", [str(patched)]),)
                    if version == "1.9"
                    else ()
                ),
                ("VerifyCaptureApk", [str(patched)]),
                ("VerifyNativeDispatchApk", [str(patched)]),
                ("VerifyPatchUiApk", [str(patched), "false", "true", "false"]),
                ("VerifySoulseekApk", [str(patched), "enabled", str(bundle)]),
                ("VerifySoulseekHostAbi", [str(original), str(patched)]),
            ):
                checked = subprocess.run(
                    [
                        java,
                        "-Xshare:off",
                        "-Xmx3g",
                        "-cp",
                        str(checks) + os.pathsep + str(MORPHE_JAR),
                        checker,
                        *values,
                    ],
                    cwd=ROOT,
                    capture_output=True,
                    text=True,
                )
                (out / (name + "-" + checker + ".log")).write_text(
                    checked.stdout + checked.stderr
                )
                if checked.returncode:
                    print(checked.stdout + checked.stderr, flush=True)
                    checked.check_returncode()
            verify_native(patched)
            results.append(
                {
                    "version": version,
                    "abi": abi,
                    "file": patched.name,
                    "sha256": hashlib.sha256(patched.read_bytes()).hexdigest(),
                }
            )
            (out / "results.json").write_text(json.dumps(results, indent=2) + "\n")
            print("PASS: " + name, flush=True)
    print(f"PASS: {len(results)} standalone Soulseek APK combinations", flush=True)


if __name__ == "__main__":
    main()

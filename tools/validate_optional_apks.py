#!/usr/bin/env python3
"""Patch and inspect independent optional-feature selections after validate_apks.py."""

from pathlib import Path
import os
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from buildenv import APP_VERSION, BUNDLE_NAME, MORPHE_JAR, VERSION, jdk


def main():
    java = str(jdk() / "bin/java")
    checks = ROOT / "build/apk-check-classes"
    if not (checks / "VerifyTelegramPageApk.class").is_file():
        raise SystemExit("Run validate_apks.py first to compile the APK checkers.")
    inputs = Path(os.environ.get("ORIGINAL_APKS_DIR", ROOT / ".deps/apks"))
    original = inputs / f"BitChord-v{APP_VERSION}_arm64-v8a.apk"
    outputs = ROOT / "build/validation-apks"
    cp = str(checks) + os.pathsep + str(MORPHE_JAR)
    cases = [
        (
            "Streamseek",
            ["Streamseek previews"],
            [
                ("VerifyStreamseekApk", ["standalone"]),
                ("VerifySoulseekApk", ["enabled", str(ROOT / "build" / BUNDLE_NAME)]),
                ("VerifyPatchUiApk", ["false", "true", "false"]),
            ],
        ),
        (
            "Soulseek",
            ["Unofficial Soulseek audio"],
            [
                ("VerifyStreamseekApk", ["disabled"]),
                ("VerifySoulseekApk", ["enabled", str(ROOT / "build" / BUNDLE_NAME)]),
                ("VerifyPatchUiApk", ["false", "true", "false"]),
            ],
        ),
        (
            "FLAC",
            ["Flac Downloader Audio"],
            [
                ("VerifyFlacAddonApk", ["standalone"]),
                ("VerifyPatchUiApk", ["false", "false", "true"]),
            ],
        ),
        (
            "MixedPlaylists",
            ["Mixed-source playlists"],
            [
                ("VerifyMixedPlaylistsApk", ["standalone"]),
                ("VerifyPatchUiApk", ["false", "false", "true"]),
            ],
        ),
        (
            "TelegramOnly",
            ["Unofficial Telegram music", "Install as Bitchord+"],
            [
                ("VerifyTelegramPageApk", ["standalone"]),
                ("VerifyCaptureApk", []),
                ("VerifyPatchUiApk", ["true", "false", "false"]),
            ],
        ),
    ]
    for label, selections, inspectors in cases:
        output = outputs / f"BitChord-{label}-{VERSION}-arm64-v8a-test.apk"
        with (ROOT / "build" / f"optional-{VERSION}-{label}.log").open("w") as log:
            subprocess.run(
                [
                    java,
                    "-Xshare:off",
                    "-Xmx3g",
                    "-jar",
                    str(MORPHE_JAR),
                    "patch",
                    "--validate",
                    "--exclusive",
                    "-p",
                    str(ROOT / "build" / BUNDLE_NAME),
                    *[arg for name in selections for arg in ("-e", name)],
                    "-o",
                    str(output),
                    str(original),
                ],
                stdout=log,
                stderr=subprocess.STDOUT,
                check=True,
            )
            for checker, arguments in [("VerifyDexControlFlow", []), *inspectors]:
                subprocess.run(
                    [
                        java,
                        "-Xshare:off",
                        "-Xmx2g",
                        "-cp",
                        cp,
                        checker,
                        str(output),
                        *arguments,
                    ],
                    stdout=log,
                    stderr=subprocess.STDOUT,
                    check=True,
                )
        print("PASS optional:", label, flush=True)


if __name__ == "__main__":
    main()

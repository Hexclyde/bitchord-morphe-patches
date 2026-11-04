"""Paths and versions shared by build.py, test.py and validate_apks.py."""

import json
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
PROVENANCE = json.loads((ROOT / "PROVENANCE.json").read_text())

VERSION = PROVENANCE["patch_bundle_version"]
PATCHER_VERSION = PROVENANCE["patcher_version"]
APP_VERSION = PROVENANCE["app_version"]
BUNDLE_NAME = f"bitchord-patches-{VERSION}.mpp"


def patch_version_source(version=None):
    value = VERSION if version is None else version
    if not isinstance(value, str) or not re.fullmatch(
        r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?(?:\+[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?",
        value,
    ):
        raise ValueError("Invalid patch bundle version")
    return (
        "package app.bitchord.telegram;\n\n"
        "final class PatchVersion {\n"
        f"    static final String VALUE = {json.dumps(value)};\n\n"
        "    private PatchVersion() {}\n"
        "}\n"
    )


PATCHES = [
    "Unofficial Telegram music",
    "Unofficial Soulseek audio",
    "Install as Bitchord+",
    "Sign in with MicroG",
    "Diagnostics",
    "Karaoke",
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
    "Audio and video switch",
    "Play alongside other apps",
    "Steady playback",
    "Smart downloads",
    "Mixed-source playlists",
    "Streamseek previews",
    "Automix Editor",
]

TOOLS = Path(os.environ.get("MORPHE_TOOLS", ROOT / ".deps/tools"))
TDLIB = Path(os.environ.get("TDLIB_ANDROID", ROOT / ".deps/tdlib"))

MORPHE_JAR = TOOLS / next(
    item["file"]
    for item in PROVENANCE["downloads"]
    if item["file"].startswith("morphe-desktop-")
)


def jdk(tools=None):
    tools = TOOLS if tools is None else Path(tools)
    candidates = sorted(tools.glob("jdk-21*"))
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]))
    for home in dict.fromkeys(candidates):
        binaries = [home / "bin" / name for name in ("java", "javac")]
        if not all(path.is_file() and os.access(path, os.X_OK) for path in binaries):
            continue
        try:
            versions = [
                subprocess.run(
                    [str(path), "-version"],
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    text=True,
                    timeout=10,
                    check=True,
                ).stdout
                for path in binaries
            ]
        except (OSError, subprocess.SubprocessError):
            continue
        if all(
            re.search(
                r'(?m)^(?:openjdk|java|javac)(?: version)?\s+"?21(?:[.\s"+_-]|$)', value
            )
            for value in versions
        ):
            return home
    sys.exit(
        f"No usable JDK 21 found in {tools} or JAVA_HOME. Run "
        "tools/bootstrap_from_release.py or bootstrap.py first, set MORPHE_TOOLS, "
        "or set JAVA_HOME to a JDK 21 installation."
    )

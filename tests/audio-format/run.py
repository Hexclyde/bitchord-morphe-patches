#!/usr/bin/env python3
"""Network-free preference, candidate identity and byte-format tests."""

from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from buildenv import TOOLS, jdk

output = ROOT / "build/audio-format-tests"
output.mkdir(parents=True, exist_ok=True)
source = ROOT / "extensions/soulseek/src/main/java/app/bitchord/soulseek"
classpath = str(TOOLS / "json.jar") + ":" + str(TOOLS / "android.jar")
subprocess.run(
    [
        str(jdk() / "bin/javac"),
        "--release",
        "8",
        "-cp",
        classpath,
        "-d",
        str(output),
        *[
            str(source / name)
            for name in (
                "AudioFormatPreferences.java",
                "AudioFormatSetupActivity.java",
                "SoulseekTrack.java",
            )
        ],
        str(Path(__file__).with_name("AudioFormatPreferencesTest.java")),
    ],
    check=True,
)
subprocess.run(
    [
        str(jdk() / "bin/java"),
        "-cp",
        str(output) + ":" + classpath,
        "app.bitchord.soulseek.AudioFormatPreferencesTest",
    ],
    check=True,
)

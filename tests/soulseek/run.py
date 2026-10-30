#!/usr/bin/env python3
"""Run platform-independent Soulseek transfer and metadata tests using the pinned JDK."""

from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from buildenv import TOOLS, jdk

OUT = ROOT / "build/soulseek-host-tests"
OUT.mkdir(parents=True, exist_ok=True)
JAVA = jdk() / "bin"
EXT = ROOT / "extensions/soulseek/src/main/java/app/bitchord/soulseek"
subprocess.run(
    [
        str(JAVA / "javac"),
        "--release",
        "11",
        "-cp",
        str(TOOLS / "json.jar"),
        "-d",
        str(OUT),
        str(EXT / "ProgressiveReader.java"),
        str(EXT / "SoulseekTrack.java"),
        str(EXT / "SoulseekCacheFiles.java"),
        str(EXT / "StunServers.java"),
        str(EXT / "NostrConfig.java"),
        str(Path(__file__).parent / "ProgressiveReaderTest.java"),
        str(Path(__file__).parent / "SoulseekTrackTest.java"),
        str(Path(__file__).parent / "SoulseekCacheFilesTest.java"),
        str(Path(__file__).parent / "StunServersTest.java"),
        str(Path(__file__).parent / "NostrConfigTest.java"),
    ],
    check=True,
)
for name in (
    "ProgressiveReaderTest",
    "SoulseekTrackTest",
    "SoulseekCacheFilesTest",
    "StunServersTest",
    "NostrConfigTest",
):
    subprocess.run(
        [
            str(JAVA / "java"),
            "-cp",
            str(OUT) + ":" + str(TOOLS / "json.jar"),
            "app.bitchord.soulseek." + name,
        ],
        check=True,
    )

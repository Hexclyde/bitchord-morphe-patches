#!/usr/bin/env python3
"""Exercise the Android coordinator against local deterministic transport fixtures."""

from pathlib import Path
import os
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from buildenv import TOOLS, jdk

OUT = ROOT / "build/coordination-tests"
OUT.mkdir(parents=True, exist_ok=True)
JAVA = jdk() / "bin"
EXT = ROOT / "extensions/soulseek/src/main/java/app/bitchord/soulseek"
subprocess.run(
    [
        str(JAVA / "javac"),
        "--release",
        "8",
        "-Xlint:-options",
        "-cp",
        str(TOOLS / "json.jar"),
        "-d",
        str(OUT),
        str(EXT / "NostrCoordinator.java"),
        str(EXT / "NostrConfig.java"),
        *(str(path) for path in sorted(Path(__file__).parent.rglob("*.java"))),
    ],
    check=True,
)
subprocess.run(
    [
        str(JAVA / "java"),
        "-cp",
        str(OUT) + os.pathsep + str(TOOLS / "json.jar"),
        "app.bitchord.soulseek.NostrCoordinatorTest",
    ],
    check=True,
)

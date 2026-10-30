#!/usr/bin/env python3
"""Exercise exact selected-file routes against the pinned host's constructor contract."""

from pathlib import Path
import os
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from buildenv import TOOLS, jdk

OUT = ROOT / "build/download-route-tests"
OUT.mkdir(parents=True, exist_ok=True)
STDLIB = TOOLS / "kotlinc/lib/kotlin-stdlib.jar"
JAVA = jdk() / "bin"
subprocess.run(
    [
        str(JAVA / "javac"),
        "--release",
        "8",
        "-Xlint:-options",
        "-cp",
        str(STDLIB),
        "-d",
        str(OUT),
        str(
            ROOT
            / "extensions/soulseek/src/main/java/app/bitchord/soulseek/SoulseekDownloadRoute.java"
        ),
        *map(str, Path(__file__).parent.rglob("*.java")),
    ],
    check=True,
)
subprocess.run(
    [
        str(JAVA / "java"),
        "-cp",
        str(OUT) + os.pathsep + str(STDLIB),
        "app.bitchord.soulseek.SoulseekDownloadRouteTest",
    ],
    check=True,
)

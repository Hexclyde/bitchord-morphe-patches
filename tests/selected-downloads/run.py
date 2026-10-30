#!/usr/bin/env python3
"""Exercise exact selected-source enqueue and staging with local host fixtures."""

from pathlib import Path
import os
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from buildenv import TOOLS, jdk

OUT = ROOT / "build/selected-download-tests"
OUT.mkdir(parents=True, exist_ok=True)
JAVA = jdk() / "bin"
CP = str(TOOLS / "kotlinc/lib/kotlin-stdlib.jar")
subprocess.run(
    [
        str(JAVA / "javac"),
        "--release",
        "8",
        str(ROOT / "compile-only/app/bitchord/telegram/PatchStrings.java"),
        "-Xlint:-options",
        "-cp",
        CP,
        "-d",
        str(OUT),
        str(
            ROOT
            / "extensions/soulseek/src/main/java/app/bitchord/soulseek/SoulseekSelectedDownloads.java"
        ),
        *(str(file) for file in sorted(Path(__file__).parent.rglob("*.java"))),
    ],
    check=True,
)
subprocess.run(
    [
        str(JAVA / "java"),
        "-cp",
        str(OUT) + os.pathsep + CP,
        "app.bitchord.soulseek.SoulseekSelectedDownloadsTest",
    ],
    check=True,
)

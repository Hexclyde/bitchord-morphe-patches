#!/usr/bin/env python3
"""Exercise exact selected-file routes against the pinned host's constructor contract."""

from pathlib import Path
import os
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from buildenv import TOOLS, jdk

STDLIB = TOOLS / "kotlinc/lib/kotlin-stdlib.jar"
JAVA = jdk() / "bin"
for version in ("1.8.1", "1.9"):
    OUT = ROOT / "build/download-route-tests" / version
    OUT.mkdir(parents=True, exist_ok=True)
    fixtures = list(Path(__file__).parent.rglob("*.java"))
    if version == "1.9":
        fixtures = [
            path
            for path in fixtures
            if path.relative_to(Path(__file__).parent).as_posix()
            != "com/music/bitchord/download/Downloads.java"
        ] + list((ROOT / "tests/download-route-v19").rglob("*.java"))
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
            *map(str, fixtures),
        ],
        check=True,
    )
    subprocess.run(
        [
            str(JAVA / "java"),
            "-Dhost.v19=" + str(version == "1.9").lower(),
            "-cp",
            str(OUT) + os.pathsep + str(STDLIB),
            "app.bitchord.soulseek.SoulseekDownloadRouteTest",
        ],
        check=True,
    )

#!/usr/bin/env python3
"""Provider request/response fixtures only; no credentials or public API traffic."""

from pathlib import Path
import os
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from buildenv import TOOLS, jdk

source = ROOT / "extensions/soulseek/src/main/java/app/bitchord/soulseek"
with tempfile.TemporaryDirectory(prefix="bitchord-pmtb-") as directory:
    sources = [
        source / (name + ".java")
        for name in (
            "DebridProvider",
            "DebridModels",
            "DebridHttp",
            "DebridTlsSockets",
            "PremiumizeProvider",
            "TorboxProvider",
        )
    ]
    sources.extend(Path(__file__).parent.rglob("*.java"))
    subprocess.run(
        [
            str(jdk() / "bin/javac"),
            "--release",
            "8",
            "-Xlint:-options",
            "-cp",
            str(TOOLS / "json.jar"),
            "-d",
            directory,
            *map(str, sources),
        ],
        check=True,
    )
    subprocess.run(
        [
            str(jdk() / "bin/java"),
            "-cp",
            directory + os.pathsep + str(TOOLS / "json.jar"),
            "app.bitchord.soulseek.ProviderTest",
        ],
        check=True,
    )

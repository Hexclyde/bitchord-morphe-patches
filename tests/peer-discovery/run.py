#!/usr/bin/env python3
"""Exercise bounded Java provider discovery and PEX with deterministic fixtures."""

from pathlib import Path
import os
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from buildenv import TOOLS, jdk

HERE = Path(__file__).parent
OUT = ROOT / "build/peer-discovery-tests"
OUT.mkdir(parents=True, exist_ok=True)
JAVA = jdk() / "bin"
EXT = ROOT / "extensions/soulseek/src/main/java/app/bitchord/soulseek"
CP = os.pathsep.join(str(TOOLS / file) for file in ("json.jar", "android.jar"))
subprocess.run(
    [
        str(JAVA / "javac"),
        "--release",
        "8",
        "-Xlint:-options",
        "-cp",
        CP,
        "-d",
        str(OUT),
        *(
            str(EXT / name)
            for name in (
                "PeerDiscovery.java",
                "PeerDiscoveryNative.java",
                "PeerCandidateCache.java",
                "PeerExchange.java",
            )
        ),
        *(str(path) for path in sorted(HERE.rglob("*.java"))),
    ],
    check=True,
)
for name in ("PeerCacheExchangeTest", "PeerDiscoveryTest"):
    if any(HERE.rglob(name + ".java")):
        subprocess.run(
            [
                str(JAVA / "java"),
                "-cp",
                os.pathsep.join((str(OUT), CP)),
                "app.bitchord.soulseek." + name,
            ],
            check=True,
        )

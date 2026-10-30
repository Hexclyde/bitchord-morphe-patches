#!/usr/bin/env python3
"""Check candidate validation, bounded exploration and network-local relay selection."""

from pathlib import Path
import os
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from buildenv import TOOLS, jdk

OUT = ROOT / "build/relay-policy-tests"
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
        *(
            str(EXT / (name + ".java"))
            for name in ("NostrConfig", "RelayCandidates", "RelayRanking")
        ),
        str(Path(__file__).parent / "RelayPolicyTest.java"),
    ],
    check=True,
)
subprocess.run(
    [
        str(JAVA / "java"),
        "-cp",
        str(OUT) + os.pathsep + str(TOOLS / "json.jar"),
        "app.bitchord.soulseek.RelayPolicyTest",
        str(ROOT / "resources/soulseek/nostr-relays.txt"),
    ],
    check=True,
)

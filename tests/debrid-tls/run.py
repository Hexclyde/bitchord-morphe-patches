#!/usr/bin/env python3
"""Bounded DNS and trusted TLS routing fixtures. No provider or public network access."""

from pathlib import Path
import os
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from buildenv import TOOLS, jdk

SOURCE = ROOT / "extensions/soulseek/src/main/java/app/bitchord/soulseek"
with tempfile.TemporaryDirectory(prefix="bitchord-debrid-tls-") as output:
    files = [
        SOURCE / (name + ".java")
        for name in ("DebridModels", "DebridProvider", "DebridHttp", "DebridTlsSockets")
    ]
    subprocess.run(
        [
            str(jdk() / "bin/javac"),
            "--release",
            "8",
            "-Xlint:-options",
            "-cp",
            str(TOOLS / "json.jar"),
            "-d",
            output,
            *map(str, files),
            str(Path(__file__).with_name("DebridTlsSocketsTest.java")),
        ],
        check=True,
    )
    subprocess.run(
        [
            str(jdk() / "bin/java"),
            "-cp",
            output + os.pathsep + str(TOOLS / "json.jar"),
            "app.bitchord.soulseek.DebridTlsSocketsTest",
        ],
        check=True,
        timeout=20,
    )

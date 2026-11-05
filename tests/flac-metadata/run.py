#!/usr/bin/env python3
"""Check lossless metadata merging with bounded prefixes and unchanged audio bytes."""

from pathlib import Path
import os
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from buildenv import jdk

with tempfile.TemporaryDirectory(prefix="bitchord-flac-metadata-") as out:
    subprocess.run(
        [
            str(jdk() / "bin/javac"),
            "--release",
            "8",
            "-d",
            out,
            str(
                ROOT
                / "extensions/telegram/src/main/java/app/bitchord/telegram/FlacMetadata.java"
            ),
            str(Path(__file__).with_name("FlacMetadataTest.java")),
        ],
        check=True,
    )
    subprocess.run(
        [
            str(jdk() / "bin/java"),
            "-Xmx64m",
            "-cp",
            out,
            "app.bitchord.telegram.FlacMetadataTest",
        ],
        check=True,
    )

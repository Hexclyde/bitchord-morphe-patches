#!/usr/bin/env python3
"""Run the audio/video rendition swap against faithful host Song and conversion fixtures."""

from pathlib import Path
import os
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from buildenv import MORPHE_JAR, TOOLS, jdk

HERE = Path(__file__).resolve().parent
CP = os.pathsep.join(
    str(p)
    for p in (
        TOOLS / "json.jar",
        TOOLS / "android.jar",
        TOOLS / "annotation.jar",
        MORPHE_JAR,
    )
)
with tempfile.TemporaryDirectory() as out:
    subprocess.run(
        [
            str(jdk() / "bin/javac"),
            "-J-Xshare:off",
            "--release",
            "11",
            "-cp",
            CP,
            "-sourcepath",
            os.pathsep.join(
                [
                    str(HERE / "stubs"),
                    str(ROOT / "tests/stubs"),
                    str(ROOT / "extensions/telegram/src/main/java"),
                    str(ROOT / "compile-only"),
                ]
            ),
            "-d",
            out,
            str(HERE / "VideoRenditionTest.java"),
            str(HERE / "stubs/com/music/bitchord/playback/PlayerConnectionKt.java"),
        ],
        check=True,
    )
    subprocess.run(
        [
            str(jdk() / "bin/java"),
            "-Xshare:off",
            "-cp",
            out + os.pathsep + CP,
            "app.bitchord.telegram.VideoRenditionTest",
        ],
        check=True,
    )

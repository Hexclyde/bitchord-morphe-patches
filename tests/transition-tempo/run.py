#!/usr/bin/env python3
"""Check directed-pair tempo persistence, matching and source-time geometry."""

from pathlib import Path
import os
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from buildenv import MORPHE_JAR, TOOLS, jdk

sources = ROOT / "extensions/telegram/src/main/java/app/bitchord/telegram"
with tempfile.TemporaryDirectory(prefix="bitchord-tempo-") as out:
    subprocess.run(
        [
            str(jdk() / "bin/javac"),
            "--release",
            "11",
            "-cp",
            str(TOOLS / "json.jar"),
            "-d",
            out,
            str(sources / "TransitionEdit.java"),
            str(sources / "MixTools.java"),
            str(Path(__file__).with_name("TempoTest.java")),
        ],
        check=True,
    )
    subprocess.run(
        [
            str(jdk() / "bin/java"),
            "-cp",
            out + os.pathsep + str(TOOLS / "json.jar"),
            "app.bitchord.telegram.TempoTest",
        ],
        check=True,
    )

with tempfile.TemporaryDirectory(prefix="bitchord-tempo-hooks-") as out:
    subprocess.run(
        [
            str(TOOLS / "kotlinc/bin/kotlinc"),
            "-Xskip-metadata-version-check",
            "-jvm-target",
            "11",
            "-cp",
            str(MORPHE_JAR),
            "-d",
            out,
            str(
                ROOT
                / "patches/src/main/kotlin/app/bitchord/patches/TransitionTempoHooks.kt"
            ),
            str(Path(__file__).with_name("TempoHooksTest.kt")),
        ],
        check=True,
    )
    subprocess.run(
        [
            str(jdk() / "bin/java"),
            "-Xshare:off",
            "-cp",
            os.pathsep.join(
                [out, str(MORPHE_JAR), str(TOOLS / "kotlinc/lib/kotlin-stdlib.jar")]
            ),
            "app.bitchord.patches.TempoHooksTestKt",
            *sys.argv[1:],
        ],
        check=True,
    )

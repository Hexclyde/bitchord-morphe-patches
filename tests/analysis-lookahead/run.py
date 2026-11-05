#!/usr/bin/env python3
"""Check queue analysis hooks against changed native shapes and optimized APKs."""

from pathlib import Path
import os
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from buildenv import MORPHE_JAR, TOOLS, jdk

with tempfile.TemporaryDirectory(prefix="bitchord-lookahead-") as out:
    subprocess.run(
        [
            str(TOOLS / "kotlinc/bin/kotlinc"),
            "-Xskip-metadata-version-check",
            "-Xcontext-parameters",
            "-jvm-target",
            "11",
            "-cp",
            str(MORPHE_JAR),
            "-d",
            out,
            str(
                ROOT
                / "patches/src/main/kotlin/app/bitchord/patches/AnalysisLookaheadHooks.kt"
            ),
            str(Path(__file__).with_name("LookaheadHooksTest.kt")),
        ],
        check=True,
    )
    subprocess.run(
        [
            str(jdk() / "bin/java"),
            "-Xshare:off",
            "-Xmx3g",
            "-cp",
            os.pathsep.join(
                [out, str(MORPHE_JAR), str(TOOLS / "kotlinc/lib/kotlin-stdlib.jar")]
            ),
            "app.bitchord.patches.LookaheadHooksTestKt",
            *sys.argv[1:],
        ],
        check=True,
    )

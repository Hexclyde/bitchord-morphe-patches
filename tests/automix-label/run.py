#!/usr/bin/env python3
"""Run the AutoMix blend label guard against synthetic instruction lists."""

from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from buildenv import MORPHE_JAR, TOOLS, jdk

with tempfile.TemporaryDirectory() as out:
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
                / "patches/src/main/kotlin/app/bitchord/patches/SourceAutomixLabelHooks.kt"
            ),
            str(Path(__file__).with_name("AutomixLabelTest.kt")),
        ],
        check=True,
    )
    subprocess.run(
        [
            str(jdk() / "bin/java"),
            "-Xshare:off",
            "-cp",
            out
            + ":"
            + str(MORPHE_JAR)
            + ":"
            + str(TOOLS / "kotlinc/lib/kotlin-stdlib.jar"),
            "app.bitchord.patches.AutomixLabelTestKt",
        ],
        check=True,
    )

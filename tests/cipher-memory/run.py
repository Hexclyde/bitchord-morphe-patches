#!/usr/bin/env python3
"""Check cipher wrapper guards and escaped-payload equivalence."""

from pathlib import Path
import os
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from buildenv import MORPHE_JAR, TOOLS, jdk

with tempfile.TemporaryDirectory(prefix="bitchord-cipher-") as out:
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
                / "patches/src/main/kotlin/app/bitchord/patches/CipherMemoryHooks.kt"
            ),
            str(Path(__file__).with_name("MemoryFixtureTest.kt")),
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
            "app.bitchord.patches.MemoryFixtureTestKt",
            *sys.argv[1:],
        ],
        check=True,
    )

subprocess.run(
    [sys.executable, str(Path(__file__).with_name("js_equivalence.py"))], check=True
)

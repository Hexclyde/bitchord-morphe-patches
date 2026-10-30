#!/usr/bin/env python3
"""Network-free state test; does not claim Android foreground-service execution."""

from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from buildenv import TOOLS, jdk

output = ROOT / "build/foreground-service-tests"
output.mkdir(parents=True, exist_ok=True)
subprocess.run(
    [
        str(jdk() / "bin/javac"),
        "--release",
        "8",
        "-cp",
        str(TOOLS / "android.jar"),
        "-d",
        str(output),
        str(
            ROOT
            / "extensions/soulseek/src/main/java/app/bitchord/soulseek/BulkTransferService.java"
        ),
        str(Path(__file__).with_name("Dependencies.java")),
        str(Path(__file__).with_name("LifetimeTest.java")),
    ],
    check=True,
)
subprocess.run(
    [
        str(jdk() / "bin/java"),
        "-cp",
        str(output) + ":" + str(TOOLS / "android.jar"),
        "app.bitchord.soulseek.LifetimeTest",
    ],
    check=True,
)

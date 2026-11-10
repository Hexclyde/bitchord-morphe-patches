#!/usr/bin/env python3
"""Run isolated wrapper tests without Android, JNI or a Telegram account."""

import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from buildenv import TOOLS, jdk

out = ROOT / "build/telegram-stream-tests"
out.mkdir(parents=True, exist_ok=True)
ext = ROOT / "extensions/telegram/src/main/java/app/bitchord/telegram"
sources = [
    ext / (name + ".java")
    for name in (
        "DiagnosticRecord",
        "DiagnosticStore",
        "TelegramStreamTrace",
        "TelegramStreamCoordinator",
        "TelegramStreamContext",
        "TelegramContainerRanges",
    )
]
sources += sorted(Path(__file__).parent.glob("*.java"))
sources += [ROOT / "tests/DiagnosticRecordTest.java"]
cp = str(TOOLS / "json.jar")
subprocess.run(
    [
        str(jdk() / "bin/javac"),
        "--release",
        "8",
        "-cp",
        cp,
        "-d",
        str(out),
        *map(str, sources),
    ],
    check=True,
)
for name in (
    "DiagnosticRecordTest",
    "TelegramStreamTraceTest",
    "TelegramStreamCoordinatorTest",
    "TelegramContainerRangesTest",
):
    subprocess.run(
        [
            str(jdk() / "bin/java"),
            "-cp",
            str(out) + os.pathsep + cp,
            "app.bitchord.telegram." + name,
        ],
        check=True,
    )

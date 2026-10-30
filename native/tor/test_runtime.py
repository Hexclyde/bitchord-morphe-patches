#!/usr/bin/env python3
"""Compile Java 8 Tor wrappers and run local protocol fixtures, without Tor bootstrap."""

from pathlib import Path
import os
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
java_home = ROOT / ".deps/tools/jdk-21.0.13+9/bin"
javac = (
    str(java_home / "javac")
    if (java_home / "javac").is_file()
    else shutil.which("javac")
)
java = (
    str(java_home / "java") if (java_home / "java").is_file() else shutil.which("java")
)
base = ROOT / "extensions/soulseek/src/main/java"
with tempfile.TemporaryDirectory(prefix="bitchord-tor-test-") as out:
    subprocess.run(
        [
            javac,
            "--release",
            "8",
            "-Xlint:-options",
            "-d",
            out,
            str(base / "org/torproject/jni/TorService.java"),
            str(base / "app/bitchord/soulseek/TorNative.java"),
            str(base / "app/bitchord/soulseek/TorRuntime.java"),
            str(ROOT / "native/tor/TorRuntimeTest.java"),
        ],
        check=True,
    )
    subprocess.run(
        [java, "-cp", out, "app.bitchord.soulseek.TorRuntimeTest"],
        check=True,
        timeout=30,
    )

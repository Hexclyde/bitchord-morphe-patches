#!/usr/bin/env python3
"""Portable verified-transfer, protocol and durable journal tests. No public networking."""

from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / ".deps/tools/jdk-21.0.13+9/bin"
SOURCE = ROOT / "extensions/soulseek/src/main/java/app/bitchord/soulseek"
JSON = ROOT / ".deps/tools/json.jar"
with tempfile.TemporaryDirectory(prefix="bitchord-bulk-tests-") as output:
    files = [
        SOURCE / (name + ".java")
        for name in (
            "BulkManifest",
            "BulkOffer",
            "BulkJobStore",
            "BulkFileProtocol",
            "BulkTransfer",
            "BulkPublish",
        )
    ]
    subprocess.run(
        [
            str(JAVA / "javac"),
            "--release",
            "8",
            "-cp",
            str(JSON),
            "-d",
            output,
            *map(str, files),
            str(Path(__file__).with_name("BulkDownloadsTest.java")),
        ],
        check=True,
    )
    subprocess.run(
        [
            str(JAVA / "java"),
            "-cp",
            output + ":" + str(JSON),
            "app.bitchord.soulseek.BulkDownloadsTest",
        ],
        check=True,
    )
    android = ROOT / ".deps/tools/android.jar"
    stdlib = ROOT / ".deps/tools/kotlinc/lib/kotlin-stdlib.jar"
    api = Path(output) / "api"
    api.mkdir()
    subprocess.run(
        [
            str(JAVA / "javac"),
            "--release",
            "8",
            "-Xlint:-options",
            "-cp",
            str(android) + ":" + str(stdlib),
            "-d",
            str(api),
            *map(str, (ROOT / "compile-only").rglob("*.java")),
        ],
        check=True,
    )
    classpath = ":".join(map(str, (Path(output), JSON, android, stdlib, api)))
    subprocess.run(
        [
            str(JAVA / "javac"),
            "--release",
            "8",
            "-Xlint:-options",
            "-cp",
            classpath,
            "-d",
            output,
            *map(str, (ROOT / "extensions/soulseek/src/main/java").rglob("*.java")),
            str(Path(__file__).with_name("BulkRunTest.java")),
        ],
        check=True,
    )
    subprocess.run(
        [str(JAVA / "java"), "-cp", classpath, "app.bitchord.soulseek.BulkRunTest"],
        check=True,
    )

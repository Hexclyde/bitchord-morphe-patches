#!/usr/bin/env python3
"""Run real QUIC, mocked STUN and actual host JNI fixtures; no public STUN traffic."""

from pathlib import Path
import os
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
env = dict(os.environ)
env.setdefault("CARGO_HOME", str(ROOT / ".deps/cargo"))
env.setdefault("RUSTUP_HOME", str(ROOT / ".deps/rustup"))
env["CARGO_BUILD_JOBS"] = "2"
cargo = (
    str(ROOT / ".deps/cargo/bin/cargo")
    if (ROOT / ".deps/cargo/bin/cargo").exists()
    else shutil.which("cargo")
)
common = [
    "--locked",
    "--manifest-path",
    str(ROOT / "native/direct/Cargo.toml"),
    "--target-dir",
    str(ROOT / "build/direct-native"),
    "--offline",
]
subprocess.run(
    [cargo, "test", *common, "--", "--test-threads=1"], cwd=ROOT, env=env, check=True
)
subprocess.run([cargo, "build", *common], cwd=ROOT, env=env, check=True)
java_bin = ROOT / ".deps/tools/jdk-21.0.13+9/bin"
javac = (
    str(java_bin / "javac") if (java_bin / "javac").exists() else shutil.which("javac")
)
java = str(java_bin / "java") if (java_bin / "java").exists() else shutil.which("java")
with tempfile.TemporaryDirectory(prefix="direct-jni-tests-") as classes:
    subprocess.run(
        [
            javac,
            "--release",
            "8",
            "-Xlint:-options",
            "-d",
            classes,
            str(
                ROOT
                / "extensions/soulseek/src/main/java/app/bitchord/soulseek/DirectNative.java"
            ),
            str(ROOT / "native/direct/DirectNativeTest.java"),
        ],
        check=True,
    )
    subprocess.run(
        [
            java,
            "-Djava.library.path=" + str(ROOT / "build/direct-native/debug"),
            "-cp",
            classes,
            "app.bitchord.soulseek.DirectNativeTest",
        ],
        check=True,
        timeout=30,
    )

with tempfile.TemporaryDirectory(prefix="direct-client-tests-") as classes:
    sources = sorted((ROOT / "native/direct/client-fixtures").rglob("*.java"))
    sources += [
        ROOT
        / "extensions/soulseek/src/main/java/app/bitchord/soulseek/DirectClient.java",
        ROOT
        / "extensions/soulseek/src/main/java/app/bitchord/soulseek/StunServers.java",
    ]
    subprocess.run(
        [javac, "--release", "8", "-Xlint:-options", "-d", classes, *map(str, sources)],
        check=True,
    )
    subprocess.run(
        [java, "-cp", classes, "app.bitchord.soulseek.DirectClientTest"],
        check=True,
        timeout=10,
    )

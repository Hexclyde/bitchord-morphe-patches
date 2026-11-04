"""Stage the pinned C++ runtime without its non-runtime debug sections."""

import hashlib
import os
from pathlib import Path
import subprocess


def strip_debug(runtime, expected, tools):
    def digest():
        return hashlib.sha256(runtime.read_bytes()).hexdigest()

    if digest() == expected:
        return
    ndk = Path(os.environ.get("ANDROID_NDK_HOME", tools / "android-ndk-r27d"))
    candidates = (
        tools / "native-strip/llvm-strip",
        ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip",
        ndk / "bin/llvm-strip",
    )
    strip = next((path for path in candidates if path.is_file()), None)
    if strip is None:
        raise RuntimeError(
            "Missing llvm-strip. Run bootstrap.py or set ANDROID_NDK_HOME."
        )
    subprocess.run([str(strip), "--strip-debug", str(runtime)], check=True)
    if digest() != expected:
        raise RuntimeError(
            "C++ runtime hash differs from PROVENANCE.json: " + str(runtime)
        )

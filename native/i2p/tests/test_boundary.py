"""Exercise real JNI glue with an explicitly fake, network-free core backend."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]


class BoundaryTest(unittest.TestCase):
    def test_jni_memory_backend(self):
        compiler, javac, java = (
            shutil.which("g++"),
            shutil.which("javac"),
            shutil.which("java"),
        )
        local_jdk = next((ROOT / ".deps/tools").glob("jdk-21*"), None)
        if local_jdk:
            javac = javac or str(local_jdk / "bin/javac")
            java = java or str(local_jdk / "bin/java")
        boost = ROOT / ".deps/i2p/sources/boost"
        if not (compiler and javac and java and (boost / "boost/asio.hpp").is_file()):
            self.skipTest("Host g++, JDK and prepared Boost are required")
        java_home = Path(javac).resolve().parents[1]
        with tempfile.TemporaryDirectory(prefix="i2p-boundary-") as temporary:
            target = Path(temporary)
            subprocess.run(
                [
                    compiler,
                    "-std=c++20",
                    "-shared",
                    "-fPIC",
                    "-pthread",
                    "-DI2P_BOUNDARY_TEST",
                    "-DBOOST_ERROR_CODE_HEADER_ONLY",
                    "-I" + str(boost),
                    "-I" + str(java_home / "include"),
                    "-I" + str(java_home / "include/linux"),
                    str(ROOT / "native/i2p/i2p_jni.cpp"),
                    "-o",
                    str(target / "libbitchord_i2p.so"),
                ],
                check=True,
            )
            subprocess.run(
                [
                    javac,
                    "--release",
                    "8",
                    "-d",
                    str(target),
                    str(
                        ROOT
                        / "extensions/soulseek/src/main/java/app/bitchord/soulseek/I2pNative.java"
                    ),
                    str(Path(__file__).with_name("BoundaryTest.java")),
                ],
                check=True,
            )
            home = target / "router"
            home.mkdir()
            subprocess.run(
                [
                    java,
                    "-Djava.library.path=" + str(target),
                    "-cp",
                    str(target),
                    "app.bitchord.soulseek.BoundaryTest",
                    str(home),
                ],
                check=True,
                timeout=15,
            )


if __name__ == "__main__":
    unittest.main()

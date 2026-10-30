"""Check compiled Android artifacts; no router is loaded or started."""

import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location(
    "build_i2p_native", ROOT / "tools/build_i2p_native.py"
)
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


class AndroidArtifactTest(unittest.TestCase):
    def test_android_elf_and_provenance(self):
        base = ROOT / ".deps/i2p"
        paths = {
            abi: base / "lib" / abi / "libbitchord_i2p.so" for abi in builder.TARGETS
        }
        if not all(path.exists() for path in paths.values()):
            self.skipTest("Build all three Android ABIs to check artifact provenance")
        manifest = json.loads((base / "runtime-manifest.json").read_text())
        recorded = json.loads((base / "build-hashes.json").read_text())
        self.assertEqual(manifest["libraries"], recorded)
        ndk = builder.toolchain()
        expected = {
            "Java_app_bitchord_soulseek_I2pNative_" + name
            for name in (
                "start",
                "status",
                "localDestination",
                "accept",
                "connect",
                "read",
                "write",
                "closeStream",
                "stop",
            )
        }
        for abi, path in paths.items():
            with self.subTest(abi=abi):
                actual = builder.verify_elf(path, *builder.TARGETS[abi][3:])
                self.assertEqual(actual, recorded[abi])
                dynamic = subprocess.check_output(
                    [ndk / "bin/llvm-readelf", "-d", path], text=True
                )
                dependencies = set(re.findall(r"Shared library: \[([^]]+)\]", dynamic))
                self.assertLessEqual(
                    dependencies,
                    {"libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so"},
                )
                self.assertIn("BIND_NOW", dynamic)
                symbols = subprocess.check_output(
                    [ndk / "bin/llvm-nm", "-D", "--defined-only", path], text=True
                )
                self.assertEqual(
                    {line.split()[-1] for line in symbols.splitlines()}, expected
                )
        self.assertGreater(len(manifest["certificates"]), 5)
        for name, digest in manifest["certificates"].items():
            self.assertEqual(
                hashlib.sha256((base / "assets" / name).read_bytes()).hexdigest(),
                digest,
            )
        for name in ("i2pd", "boost", "openssl"):
            self.assertGreater(
                (base / "licenses" / (name + ".txt")).stat().st_size, 100
            )


if __name__ == "__main__":
    unittest.main()

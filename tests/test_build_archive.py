import ast
import hashlib
from pathlib import Path
import random
import shutil
import stat
import struct
import tempfile
import unittest
import uuid
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[1]


def archive_code():
    tree = ast.parse((ROOT / "build.py").read_text())
    writer = next(
        node
        for node in tree.body
        if isinstance(node, ast.FunctionDef) and node.name == "jar"
    )
    final_call = next(
        node
        for node in tree.body
        if isinstance(node, ast.Expr)
        and isinstance(node.value, ast.Call)
        and isinstance(node.value.func, ast.Name)
        and node.value.func.id == "jar"
        and len(node.value.args) == 2
        and isinstance(node.value.args[0], ast.Name)
        and node.value.args[0].id == "output"
        and isinstance(node.value.args[1], ast.Name)
        and node.value.args[1].id == "bundle"
    )
    namespace = {
        "Path": Path,
        "zipfile": zipfile,
        "tempfile": tempfile,
        "shutil": shutil,
        "uuid": uuid,
    }
    exec(
        compile(ast.Module(body=[writer], type_ignores=[]), "build.py", "exec"),
        namespace,
    )
    return namespace, final_call


def raw_deflate(data, level):
    compressor = zlib.compressobj(level, zlib.DEFLATED, -15)
    return compressor.compress(data) + compressor.flush()


def compressed_payload(archive, name):
    raw = archive.read_bytes()
    with zipfile.ZipFile(archive) as handle:
        entry = handle.getinfo(name)
    names, extras = struct.unpack_from("<HH", raw, entry.header_offset + 26)
    start = entry.header_offset + 30 + names + extras
    return raw[start : start + entry.compress_size]


def content(archive):
    with zipfile.ZipFile(archive) as handle:
        return {
            entry.filename: (
                hashlib.sha256(handle.read(entry)).hexdigest(),
                entry.external_attr,
            )
            for entry in handle.infolist()
        }


class BuildArchiveTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.bundle = self.root / "bundle"
        randomizer = random.Random(7)
        chunks = [
            b"common-prefix---" + bytes(randomizer.randrange(256) for _ in range(32))
            for _ in range(512)
        ]
        self.payload = b"".join(randomizer.choice(chunks) for _ in range(6000))
        self.file = self.bundle / "extensions/runtime.mpe"
        self.file.parent.mkdir(parents=True)
        self.file.write_bytes(self.payload)
        self.file.chmod(0o640)
        notice = self.bundle / "licenses/NOTICE.txt"
        notice.parent.mkdir()
        notice.write_text("Example project - MIT\n")
        notice.chmod(0o644)
        self.namespace, self.final_call = archive_code()

    def test_intermediate_archive_keeps_default_compression_and_permissions(self):
        archive = self.root / "intermediate.jar"
        self.namespace["jar"](archive, self.bundle)
        self.assertEqual(
            raw_deflate(self.payload, 6),
            compressed_payload(archive, "extensions/runtime.mpe"),
        )
        with zipfile.ZipFile(archive) as handle:
            self.assertEqual(self.payload, handle.read("extensions/runtime.mpe"))
            self.assertEqual(
                stat.S_IFREG | 0o640,
                handle.getinfo("extensions/runtime.mpe").external_attr >> 16,
            )
            self.assertEqual(
                {"extensions/runtime.mpe", "licenses/NOTICE.txt"},
                set(handle.namelist()),
            )

    def test_final_bundle_uses_stronger_compression_with_identical_contents_and_modes(
        self,
    ):
        original = self.root / "intermediate.jar"
        self.namespace["jar"](original, self.bundle)
        archive = self.root / "final.mpp"
        self.namespace.update(output=archive, bundle=self.bundle)
        exec(
            compile(
                ast.Module(body=[self.final_call], type_ignores=[]), "build.py", "exec"
            ),
            self.namespace,
        )
        self.assertNotEqual(raw_deflate(self.payload, 6), raw_deflate(self.payload, 9))
        self.assertEqual(
            raw_deflate(self.payload, 9),
            compressed_payload(archive, "extensions/runtime.mpe"),
        )
        self.assertEqual(content(original), content(archive))
        self.assertLess(archive.stat().st_size, original.stat().st_size)


if __name__ == "__main__":
    unittest.main()

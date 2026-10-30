import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

MODULE = Path(__file__).resolve().parents[1] / "prepare.py"
SPEC = importlib.util.spec_from_file_location("i2p_prepare", MODULE)
prepare = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(prepare)


class PreparationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)

    def tearDown(self):
        self.temporary.cleanup()

    def archive(self, members):
        archive = self.root / "core.tar.gz"
        with tarfile.open(archive, "w:gz") as output:
            for name, content, kind in members:
                member = tarfile.TarInfo(name)
                if kind == "link":
                    member.type = tarfile.SYMTYPE
                    member.linkname = "../../outside"
                    output.addfile(member)
                else:
                    raw = content.encode()
                    member.size = len(raw)
                    output.addfile(member, io.BytesIO(raw))
        return archive, {
            "name": "core",
            "archive": archive.name,
            "prefix": "core",
            "sha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
            "max_archive_bytes": 4096,
            "max_source_bytes": 4096,
            "include": [],
            "required": ["LICENSE"],
            "urls": ["https://example.com/core.tar.gz"],
        }

    def test_verified_extraction_is_reusable_and_detects_modified_sources(self):
        archive, entry = self.archive(
            [("core/LICENSE", "license", "file"), ("core/api.h", "api", "file")]
        )
        output = self.root / "source"
        record = prepare.extract(entry, archive, output)
        self.assertEqual(record["files"], 2)
        self.assertEqual(record, prepare.extract(entry, archive, output))
        (output / "core/api.h").write_text("changed")
        with self.assertRaises(ValueError):
            prepare.extract(entry, archive, output)

    def test_unsafe_archives_leave_no_published_tree(self):
        for name, kind in [
            ("core/../outside", "file"),
            ("/outside", "file"),
            ("core/link", "link"),
            ("wrong/LICENSE", "file"),
        ]:
            with self.subTest(name=name):
                archive, entry = self.archive(
                    [("core/LICENSE", "license", "file"), (name, "value", kind)]
                )
                output = self.root / "source"
                with self.assertRaises(ValueError):
                    prepare.extract(entry, archive, output)
                self.assertFalse((output / "core").exists())
                self.assertEqual(list(output.iterdir()), [])

    def test_checksum_and_expansion_limits_are_enforced(self):
        archive, entry = self.archive([("core/LICENSE", "license", "file")])
        entry["sha256"] = "0" * 64
        with self.assertRaises(ValueError):
            prepare.extract(entry, archive, self.root / "source")
        entry["sha256"] = hashlib.sha256(archive.read_bytes()).hexdigest()
        entry["max_source_bytes"] = 2
        with self.assertRaises(ValueError):
            prepare.extract(entry, archive, self.root / "source")

    def test_missing_required_and_duplicate_files_are_rejected(self):
        archive, entry = self.archive([("core/api.h", "api", "file")])
        with self.assertRaises(ValueError):
            prepare.extract(entry, archive, self.root / "source")
        archive, entry = self.archive(
            [("core/LICENSE", "a", "file"), ("core/LICENSE", "b", "file")]
        )
        with self.assertRaises(ValueError):
            prepare.extract(entry, archive, self.root / "source")

    def test_cache_is_verified_even_in_offline_mode(self):
        archive, entry = self.archive([("core/LICENSE", "license", "file")])
        self.assertEqual(prepare.fetch(entry, self.root, True), archive)
        archive.write_bytes(b"corrupt")
        with self.assertRaises(ValueError):
            prepare.fetch(entry, self.root, True)
        archive.unlink()
        with self.assertRaises(ValueError):
            prepare.fetch(entry, self.root, True)

    def test_selection_extracts_only_needed_source_members(self):
        archive, entry = self.archive(
            [
                ("core/LICENSE", "license", "file"),
                ("core/include/api.h", "api", "file"),
                ("core/docs/example", "omit", "file"),
            ]
        )
        entry["include"] = ["LICENSE", "include"]
        output = self.root / "source"
        self.assertEqual(prepare.extract(entry, archive, output)["files"], 2)
        self.assertFalse((output / "core/docs").exists())

    def test_pinned_manifest_is_valid(self):
        manifest = prepare.load_manifest()
        self.assertEqual(
            {e["name"] for e in manifest["components"]}, {"i2pd", "boost", "openssl"}
        )
        invalid = dict(manifest)
        invalid["schema"] = 2
        path = self.root / "invalid.json"
        path.write_text(json.dumps(invalid))
        with self.assertRaises(ValueError):
            prepare.load_manifest(path)


if __name__ == "__main__":
    unittest.main()

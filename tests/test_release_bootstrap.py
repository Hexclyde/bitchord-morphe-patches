"""Offline native/runtime reconstruction and release archive bounds."""

from pathlib import Path
import json
import sys
import tempfile
import unittest
import warnings
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import bootstrap_from_release as bootstrap


class ReleaseBootstrapTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.metadata = json.loads((ROOT / "PROVENANCE.json").read_text())
        self.files = {}
        self.metadata["native_sha256"] = {}
        self.metadata["soulseek_native_sha256"] = {}
        self.metadata["download_runtime_native_sha256"] = {}
        for abi in bootstrap.ABIS:
            for library in (
                "libtdjni.so",
                "libsslx.so",
                "libcryptox.so",
                "libc++_shared.so",
            ):
                self.native(abi, library, "native_sha256")
            self.native(abi, "libbitchord_soulseek.so", "soulseek_native_sha256")
        self.assets = {}
        for runtime, library in bootstrap.RUNTIMES.items():
            inventory = {}
            for abi in bootstrap.ABIS:
                name = self.native(abi, library, "download_runtime_native_sha256")
                inventory[abi] = {
                    "sha256": bootstrap.digest(self.files[name]),
                    "bytes": len(self.files[name]),
                    "api": 26,
                }
            self.asset(
                "bitchord/runtimes/" + runtime + "-inputs.json",
                json.dumps(
                    {"libraries": inventory} if runtime == "tor" else inventory
                ).encode(),
            )
            self.asset(
                "bitchord/runtimes/licenses/" + runtime + "/LICENSE", b"upstream notice"
            )
        self.asset(
            "bitchord/i2p/certificates/reseed/example.crt",
            b"signed-bootstrap-certificate",
        )
        self.asset(
            "bitchord/runtimes/i2p-runtime-manifest.json",
            json.dumps(
                {
                    "certificates": {
                        "certificates/reseed/example.crt": bootstrap.digest(
                            b"signed-bootstrap-certificate"
                        )
                    }
                }
            ).encode(),
        )

    def native(self, abi, library, field):
        name = f"lib/{abi}/{library}"
        self.files[name] = name.encode()
        self.metadata[field][name] = bootstrap.digest(self.files[name])
        return name

    def asset(self, name, data):
        self.assets[name] = data

    def bundle(self, duplicate=False):
        path = self.directory / "release.mpp"
        entries = dict(self.files)
        entries.update(
            {bootstrap.ASSET_ROOT + name: data for name, data in self.assets.items()}
        )
        entries["runtimes/assets.list"] = ("\n".join(self.assets) + "\n").encode()
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(path, "w") as archive:
                for name, data in entries.items():
                    archive.writestr(name, data)
                if duplicate:
                    archive.writestr(
                        "runtimes/assets.list", entries["runtimes/assets.list"]
                    )
        self.metadata["patch_bundle_sha256"] = bootstrap.digest(path.read_bytes())
        return path

    def test_all_dependencies_are_restored_to_build_paths(self):
        path = self.bundle()
        bootstrap.verify_bundle(path, self.metadata)
        target = self.directory / "source"
        stale = target / ".deps/i2p/assets/certificates/reseed/old.crt"
        stale.parent.mkdir(parents=True)
        stale.write_bytes(b"obsolete")
        notice = target / ".deps/direct/licenses/old-LICENSE"
        notice.parent.mkdir(parents=True)
        notice.write_bytes(b"obsolete")
        bootstrap.stage_native(path, target / ".deps/tools", target)
        self.assertEqual(27, len(list((target / ".deps").rglob("*.so"))))
        self.assertFalse(stale.exists())
        self.assertFalse(notice.exists())
        self.assertEqual(
            b"signed-bootstrap-certificate",
            (target / ".deps/i2p/assets/certificates/reseed/example.crt").read_bytes(),
        )
        for runtime, library in bootstrap.RUNTIMES.items():
            for abi in bootstrap.ABIS:
                self.assertEqual(
                    self.files[f"lib/{abi}/{library}"],
                    (target / ".deps" / runtime / "lib" / abi / library).read_bytes(),
                )
            self.assertEqual(
                b"upstream notice",
                (target / ".deps" / runtime / "licenses/LICENSE").read_bytes(),
            )
        for runtime in ("direct", "i2p", "ipfs"):
            self.assertTrue(
                (target / ".deps" / runtime / "build-hashes.json").is_file()
            )
        self.assertTrue((target / ".deps/i2p/runtime-manifest.json").is_file())
        self.assertEqual(
            (ROOT / "vendor/tdlib-api/TdApi.java").read_bytes(),
            (
                target / ".deps/tdlib/src/main/java/org/drinkless/tdlib/TdApi.java"
            ).read_bytes(),
        )

    def test_runtime_tampering_fails_even_with_updated_archive_hash(self):
        self.files["lib/arm64-v8a/libbitchord_direct.so"] = b"substituted"
        with self.assertRaisesRegex(ValueError, "Native dependency hash"):
            bootstrap.verify_bundle(self.bundle(), self.metadata)

    def test_inventory_mismatch_rejected(self):
        name = "bitchord/runtimes/direct-inputs.json"
        inventory = json.loads(self.assets[name])
        inventory["arm64-v8a"]["bytes"] += 1
        self.assets[name] = json.dumps(inventory).encode()
        with self.assertRaisesRegex(ValueError, "inventory mismatch"):
            bootstrap.verify_bundle(self.bundle(), self.metadata)

    def test_certificate_tampering_rejected(self):
        self.assets["bitchord/i2p/certificates/reseed/example.crt"] = (
            b"untrusted bootstrap"
        )
        with self.assertRaisesRegex(ValueError, "certificate hash"):
            bootstrap.verify_bundle(self.bundle(), self.metadata)

    def test_missing_certificate_rejected(self):
        del self.assets["bitchord/i2p/certificates/reseed/example.crt"]
        with self.assertRaisesRegex(ValueError, "certificate inventory"):
            bootstrap.verify_bundle(self.bundle(), self.metadata)

    def test_asset_traversal_rejected(self):
        self.asset("bitchord/runtimes/licenses/direct/../../../escape", b"unsafe")
        with self.assertRaisesRegex(ValueError, "Unsafe runtime asset"):
            bootstrap.verify_bundle(self.bundle(), self.metadata)

    def test_duplicate_zip_member_rejected(self):
        with self.assertRaisesRegex(ValueError, "Duplicate release"):
            bootstrap.verify_bundle(self.bundle(duplicate=True), self.metadata)

    def test_archive_substitution_rejected_before_staging(self):
        path = self.bundle()
        path.write_bytes(b"unreviewed archive")
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            bootstrap.verify_bundle(path, self.metadata)


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""Verify pinned Guardian AAR and extract only supported Tor native ABIs.

No upstream Java/manifest/service is injected. TorRuntime owns its private
process, authenticated control connection and JNI-compatible Java 8 shim.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PIN_FILE = ROOT / "native/tor/PINNED.json"


def digest(path):
    with path.open("rb") as stream:
        result = hashlib.sha256()
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def prepare(cache_dir=None, output_dir=None):
    pin = json.loads(PIN_FILE.read_text())
    cache = Path(cache_dir) if cache_dir else ROOT / ".deps/tor"
    output = Path(output_dir) if output_dir else cache / "lib"
    cache.mkdir(parents=True, exist_ok=True)
    aar = cache / ("tor-android-" + pin["version"] + ".aar")
    if not aar.exists() or digest(aar) != pin["sha256"]:
        request = urllib.request.Request(
            pin["url"], headers={"User-Agent": "bitchord-tor-runtime-build"}
        )
        with tempfile.NamedTemporaryFile(dir=cache, delete=False) as temporary:
            path = Path(temporary.name)
            try:
                with urllib.request.urlopen(request, timeout=90) as response:
                    count = 0
                    while True:
                        chunk = response.read(1024 * 1024)
                        if not chunk:
                            break
                        count += len(chunk)
                        if count > 32 * 1024 * 1024:
                            raise RuntimeError("Tor AAR exceeds expected size bounds")
                        temporary.write(chunk)
                temporary.flush()
                if digest(path) != pin["sha256"]:
                    raise RuntimeError("Pinned Tor AAR SHA-256 mismatch")
                os.replace(path, aar)
            finally:
                path.unlink(missing_ok=True)
    verified = {}
    with zipfile.ZipFile(aar) as archive:
        for abi, expected in pin["libraries"].items():
            data = archive.read("jni/" + abi + "/libtor.so")
            if (
                len(data) != expected["size"]
                or hashlib.sha256(data).hexdigest() != expected["sha256"]
            ):
                raise RuntimeError("Pinned Tor library mismatch: " + abi)
            if (
                data[:4] != b"\x7fELF"
                or data[4] != expected["elf_class"]
                or data[5] != 1
                or int.from_bytes(data[18:20], "little") != expected["elf_machine"]
            ):
                raise RuntimeError("Unexpected Tor ELF architecture: " + abi)
            target = output / abi / "libtor.so"
            target.parent.mkdir(parents=True, exist_ok=True)
            if not target.exists() or digest(target) != expected["sha256"]:
                with tempfile.NamedTemporaryFile(
                    dir=target.parent, delete=False
                ) as temporary:
                    temporary.write(data)
                    path = Path(temporary.name)
                os.replace(path, target)
                target.chmod(0o644)
            verified[abi] = str(target.resolve())
    return verified


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache-dir", type=Path)
    parser.add_argument("--output-dir", type=Path)
    args = parser.parse_args()
    print(json.dumps(prepare(args.cache_dir, args.output_dir), indent=2))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Prepare pinned native I2P sources; no build or router startup is performed."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import tarfile
import tempfile
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = Path(__file__).with_name("sources.json")
MARKER = ".prepared-source.json"
MAX_MEMBERS = 400_000
MAX_FILE = 64 * 1024 * 1024


def digest(path: Path, maximum: int) -> str:
    result = hashlib.sha256()
    size = 0
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            size += len(block)
            if size > maximum:
                raise ValueError("Source archive exceeds its size limit")
            result.update(block)
    return result.hexdigest()


def load_manifest(path: Path = MANIFEST) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if value.get("schema") != 1 or not isinstance(value.get("components"), list):
        raise ValueError("Unsupported native source manifest")
    names = set()
    for entry in value["components"]:
        name = entry["name"]
        if not re.fullmatch(r"[a-z0-9_-]{1,40}", name) or name in names:
            raise ValueError("Invalid or repeated source component")
        names.add(name)
        if not re.fullmatch(r"[a-zA-Z0-9_.-]{1,160}", entry["archive"]):
            raise ValueError("Invalid source archive filename")
        if not re.fullmatch(r"[0-9a-f]{64}", entry["sha256"]):
            raise ValueError("Invalid source checksum")
        if not re.fullmatch(r"[a-zA-Z0-9_.-]{1,160}", entry["prefix"]):
            raise ValueError("Invalid archive prefix")
        for field in ("max_archive_bytes", "max_source_bytes"):
            if (
                not isinstance(entry[field], int)
                or not 0 < entry[field] <= 512 * 1024 * 1024
            ):
                raise ValueError("Invalid source size limit")
        if not entry["urls"]:
            raise ValueError("Missing source URL")
        for url in entry["urls"]:
            parsed = urllib.parse.urlsplit(url)
            if (
                parsed.scheme != "https"
                or not parsed.hostname
                or parsed.username
                or parsed.password
            ):
                raise ValueError("Source downloads require HTTPS without credentials")
        for field in ("include", "required"):
            if not isinstance(entry[field], list) or any(
                not safe_relative(p) for p in entry[field]
            ):
                raise ValueError("Invalid source selection")
    return value


def safe_relative(name: str) -> bool:
    return (
        isinstance(name, str)
        and bool(name)
        and "\\" not in name
        and ":" not in name
        and not any(ord(c) < 32 or ord(c) == 127 for c in name)
        and not name.startswith("/")
        and all(part not in ("", ".", "..") for part in name.split("/"))
    )


def fetch(entry: dict, cache: Path, offline: bool) -> Path:
    cache.mkdir(parents=True, exist_ok=True)
    archive = cache / entry["archive"]
    if archive.is_symlink():
        raise ValueError("Source archives cannot be links")
    if archive.exists():
        if digest(archive, entry["max_archive_bytes"]) != entry["sha256"]:
            raise ValueError("Cached source checksum mismatch")
        return archive
    if offline:
        raise ValueError(f"Offline source archive missing: {entry['archive']}")
    failure = None
    for url in entry["urls"]:
        descriptor, temporary = tempfile.mkstemp(
            prefix="source-", suffix=".part", dir=cache
        )
        try:
            checksum = hashlib.sha256()
            size = 0
            with os.fdopen(descriptor, "wb") as target, urllib.request.urlopen(
                url, timeout=30
            ) as source:
                if urllib.parse.urlsplit(source.geturl()).scheme != "https":
                    raise ValueError("Source download redirected away from HTTPS")
                for block in iter(lambda: source.read(1024 * 1024), b""):
                    size += len(block)
                    if size > entry["max_archive_bytes"]:
                        raise ValueError("Source download exceeds its size limit")
                    checksum.update(block)
                    target.write(block)
            if checksum.hexdigest() != entry["sha256"]:
                raise ValueError("Downloaded source checksum mismatch")
            os.replace(temporary, archive)
            return archive
        except Exception as error:
            failure = error
        finally:
            Path(temporary).unlink(missing_ok=True)
    raise ValueError(f"Could not fetch verified {entry['name']} sources") from failure


def tree_digest(directory: Path) -> tuple[str, int, int]:
    checksum = hashlib.sha256()
    files = 0
    size = 0
    for file in sorted(directory.rglob("*")):
        relative = file.relative_to(directory).as_posix()
        if file.is_symlink():
            raise ValueError("Prepared sources cannot contain links")
        if file.is_dir() or relative == MARKER:
            continue
        if not file.is_file():
            raise ValueError("Prepared sources contain a special file")
        checksum.update(relative.encode("utf-8") + b"\0")
        checksum.update(bytes.fromhex(digest(file, MAX_FILE)))
        files += 1
        size += file.stat().st_size
    return checksum.hexdigest(), files, size


def extract(entry: dict, archive: Path, output: Path) -> dict:
    if digest(archive, entry["max_archive_bytes"]) != entry["sha256"]:
        raise ValueError("Source checksum mismatch before extraction")
    output.mkdir(parents=True, exist_ok=True)
    destination = output / entry["name"]
    if destination.is_symlink():
        raise ValueError("Prepared source directory cannot be a link")
    if destination.exists():
        saved = json.loads((destination / MARKER).read_text(encoding="utf-8"))
        tree, files, size = tree_digest(destination)
        if saved != {
            "sha256": entry["sha256"],
            "tree_sha256": tree,
            "files": files,
            "bytes": size,
        }:
            raise ValueError(
                "Prepared source tree changed or belongs to another revision"
            )
        return saved
    temporary = Path(tempfile.mkdtemp(prefix="extract-", dir=output))
    try:
        total = 0
        seen = set()
        with tarfile.open(archive, "r|gz") as tar:
            for index, member in enumerate(tar):
                if index >= MAX_MEMBERS:
                    raise ValueError("Source archive contains too many members")
                raw_name = member.name.rstrip("/")
                if not safe_relative(raw_name):
                    raise ValueError("Unsafe source archive path")
                parts = PurePosixPath(raw_name).parts
                if parts[0] != entry["prefix"]:
                    raise ValueError("Source archive has an unexpected root")
                if len(parts) == 1:
                    if not member.isdir():
                        raise ValueError("Invalid source archive root")
                    continue
                relative = "/".join(parts[1:])
                if entry["include"] and not any(
                    relative == prefix or relative.startswith(prefix + "/")
                    for prefix in entry["include"]
                ):
                    continue
                if member.isdir():
                    continue
                if not member.isfile() or relative == MARKER or relative in seen:
                    raise ValueError(
                        "Source archive contains a link, special file or duplicate"
                    )
                if member.size < 0 or member.size > MAX_FILE:
                    raise ValueError("Source file exceeds its size limit")
                total += member.size
                if total > entry["max_source_bytes"]:
                    raise ValueError("Source tree exceeds its size limit")
                seen.add(relative)
                target = temporary.joinpath(*parts[1:])
                target.parent.mkdir(parents=True, exist_ok=True)
                with tar.extractfile(member) as source, target.open("xb") as sink:
                    copied = 0
                    while block := source.read(
                        min(1024 * 1024, member.size - copied + 1)
                    ):
                        copied += len(block)
                        if copied > member.size:
                            raise ValueError(
                                "Source member exceeds its declared length"
                            )
                        sink.write(block)
                if copied != member.size:
                    raise ValueError("Truncated source archive member")
        for required in entry["required"]:
            if not temporary.joinpath(*PurePosixPath(required).parts).is_file():
                raise ValueError(f"Missing required source file: {required}")
        tree, files, size = tree_digest(temporary)
        record = {
            "sha256": entry["sha256"],
            "tree_sha256": tree,
            "files": files,
            "bytes": size,
        }
        (temporary / MARKER).write_text(
            json.dumps(record, indent=2) + "\n", encoding="utf-8"
        )
        os.rename(temporary, destination)
        return record
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    manifest = load_manifest()
    entries = {entry["name"]: entry for entry in manifest["components"]}
    parser.add_argument(
        "--component", choices=entries, action="append", help="Default: i2pd core only"
    )
    parser.add_argument("--cache-dir", type=Path, default=ROOT / ".deps/i2p/cache")
    parser.add_argument("--source-dir", type=Path, default=ROOT / ".deps/i2p/sources")
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    for name in dict.fromkeys(args.component or ["i2pd"]):
        entry = entries[name]
        archive = fetch(entry, args.cache_dir, args.offline)
        record = extract(entry, archive, args.source_dir)
        print(
            json.dumps(
                {
                    "component": name,
                    "version": entry["version"],
                    "archive_bytes": archive.stat().st_size,
                    **record,
                }
            ),
            flush=True,
        )


if __name__ == "__main__":
    main()

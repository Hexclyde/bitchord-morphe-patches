#!/usr/bin/env python3
"""Regenerate locked native dependency notices, retaining the Rust runtime appendix."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "licenses/soulseek-native-dependencies.txt"
MANIFEST = ROOT / "native/soulseek/Cargo.toml"
NOTICE_NAME = re.compile(
    r"^(?:licen[cs]e|copying|notice|copyright|authors)(?:[._-].*)?$", re.IGNORECASE
)
COMMENT = re.compile(r"/\*.*?\*/|(?m:(?:^[ \t]*//[^\n]*(?:\n|$))+)", re.DOTALL)
ATTRIBUTION = re.compile(r"\bcopyright\b|\bSPDX-License-Identifier\b", re.IGNORECASE)
SOURCE_EXTENSIONS = {".rs", ".c", ".h", ".S", ".s", ".inl", ".inc", ".asm", ".pl"}
SUPPLEMENTS = {
    "nostr": [
        (
            "https://raw.githubusercontent.com/rust-nostr/nostr/"
            "a86ce27c3b4d0dcab186a707336237653a01b114/LICENSE",
            "a333d394b9f31b6ca64d08f3048a8a38125c181d68d3d376c4ddf988cffd12d2",
        )
    ],
    "nostr-database": [
        (
            "https://raw.githubusercontent.com/rust-nostr/nostr/"
            "c1a24383cd361a408fc53eeac7c1708a3b589194/LICENSE",
            "a333d394b9f31b6ca64d08f3048a8a38125c181d68d3d376c4ddf988cffd12d2",
        )
    ],
    "nostr-gossip": [
        (
            "https://raw.githubusercontent.com/rust-nostr/nostr/"
            "c1a24383cd361a408fc53eeac7c1708a3b589194/LICENSE",
            "a333d394b9f31b6ca64d08f3048a8a38125c181d68d3d376c4ddf988cffd12d2",
        )
    ],
    "nostr-relay-pool": [
        (
            "https://raw.githubusercontent.com/rust-nostr/nostr/"
            "eac2a5af540374af2940ddb932c76d0e00b0bf99/LICENSE",
            "a333d394b9f31b6ca64d08f3048a8a38125c181d68d3d376c4ddf988cffd12d2",
        )
    ],
    "nostr-sdk": [
        (
            "https://raw.githubusercontent.com/rust-nostr/nostr/"
            "c1a24383cd361a408fc53eeac7c1708a3b589194/LICENSE",
            "a333d394b9f31b6ca64d08f3048a8a38125c181d68d3d376c4ddf988cffd12d2",
        )
    ],
    "gloo-timers": [
        (
            "https://raw.githubusercontent.com/rustwasm/gloo/"
            "f3bb1d1a1c33dbcccaf26446aca7d0cea8e9d574/LICENSE-APACHE",
            "a60eea817514531668d7e00765731449fe14d059d3249e0bc93b36de45f759f2",
        ),
        (
            "https://raw.githubusercontent.com/rustwasm/gloo/"
            "f3bb1d1a1c33dbcccaf26446aca7d0cea8e9d574/LICENSE-MIT",
            "5a6f5443ea473f130adbdcf84254761d1292bc79759b5253915d4b5e7afbef66",
        ),
    ],
    "jni-sys-macros": [
        (
            "https://raw.githubusercontent.com/jni-rs/jni-sys/"
            "64d77b7a5f119d7b55b4e2c169a4668067ff59e6/LICENSE-MIT",
            "1d85bd754b04ceec93e98e890edd1fa3c6a22e81bcb32135806beeccefa51cd1",
        ),
        (
            "https://raw.githubusercontent.com/jni-rs/jni-sys/"
            "64d77b7a5f119d7b55b4e2c169a4668067ff59e6/LICENSE-APACHE",
            "c6596eb7be8581c18be736c846fb9173b69eccf6ef94c5135893ec56bd92ba08",
        ),
    ],
}
CC0_PACKAGES = {
    "bitcoin-consensus-encoding",
    "bitcoin-internals",
    "bitcoin-io",
    "bitcoin_hashes",
}


def metadata(offline):
    local = ROOT / ".deps/cargo/bin/cargo"
    cargo = os.environ.get("SOULSEEK_CARGO") or (
        str(local) if local.is_file() else shutil.which("cargo")
    )
    if not cargo:
        raise RuntimeError("Install Cargo or set SOULSEEK_CARGO.")
    env = dict(os.environ)
    for name, path in (("CARGO_HOME", ".deps/cargo"), ("RUSTUP_HOME", ".deps/rustup")):
        if name not in env and (ROOT / path).is_dir():
            env[name] = str(ROOT / path)
    command = [
        cargo,
        "metadata",
        "--locked",
        "--format-version",
        "1",
        "--manifest-path",
        str(MANIFEST),
    ]
    if offline:
        command.append("--offline")
    return json.loads(subprocess.check_output(command, cwd=ROOT, env=env))


def supplemental(url, digest, offline):
    cache = ROOT / ".deps/license-sources" / digest
    if not cache.is_file():
        if offline:
            raise RuntimeError(
                "An upstream license is not cached; rerun without --offline."
            )
        with urllib.request.urlopen(url, timeout=20) as response:
            data = response.read(128 * 1024 + 1)
        if len(data) > 128 * 1024 or hashlib.sha256(data).hexdigest() != digest:
            raise RuntimeError(
                "Upstream license content does not match its pinned SHA-256."
            )
        cache.parent.mkdir(parents=True, exist_ok=True)
        cache.write_bytes(data)
    data = cache.read_bytes()
    if hashlib.sha256(data).hexdigest() != digest:
        raise RuntimeError(
            "Cached upstream license content does not match its pinned SHA-256."
        )
    return data.decode("utf-8")


def source_notices(root):
    grouped = {}
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.suffix not in SOURCE_EXTENSIONS:
            continue
        text = path.read_text(encoding="utf-8")
        comments = [match.group(0).strip() for match in COMMENT.finditer(text)]
        if path.suffix in {".pl", ".asm"}:
            symbol = "#" if path.suffix == ".pl" else ";"
            comments.extend(
                re.findall(rf"(?m)(?:^[ \t]*\{symbol}[^\n]*(?:\n|$))+", text)
            )
        for comment in comments:
            if ATTRIBUTION.search(comment):
                grouped.setdefault(comment.strip(), set()).add(
                    path.relative_to(root).as_posix()
                )
    return [
        ("Source notice: " + ", ".join(sorted(paths)), text)
        for text, paths in sorted(grouped.items(), key=lambda row: min(row[1]))
    ]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check", action="store_true", help="Fail if notices need regeneration"
    )
    parser.add_argument(
        "--offline", action="store_true", help="Use cached crates and license sources"
    )
    args = parser.parse_args()
    current = OUTPUT.read_bytes()
    marker = re.search(rb"(?m)^Rust [0-9.]+ standard library notices\r?$", current)
    if not marker:
        raise RuntimeError(
            "The existing Rust standard-library notice appendix is missing."
        )
    rust = current[marker.start() :]
    graph = metadata(args.offline)
    packages = sorted(
        (p for p in graph["packages"] if p["id"] != graph["resolve"]["root"]),
        key=lambda p: (p["name"], p["version"]),
    )
    cc0 = next(p for p in packages if p["name"] == "secp256k1")
    cc0_text = (Path(cc0["manifest_path"]).parent / "LICENSE").read_text(
        encoding="utf-8"
    )
    if "CC0 1.0 Universal" not in cc0_text:
        raise RuntimeError("The canonical CC0-1.0 license text is missing.")
    lock_hash = hashlib.sha256(
        MANIFEST.with_name("Cargo.lock").read_bytes()
    ).hexdigest()
    sections = [
        "Soulseek native dependency notices\n\n"
        "Generated by tools/native_licenses.py from native/soulseek/Cargo.lock.\n"
        "Includes locked target-specific and build-time dependencies; not every listed\n"
        "component is linked on Android. Individual components retain their licenses.\n"
        f"Dependency packages: {len(packages)}\nLockfile SHA-256: {lock_hash}\n"
    ]
    notice_count = 0
    for package in packages:
        if not package["license"]:
            raise RuntimeError(
                f"License expression missing: {package['name']} {package['version']}"
            )
        root = Path(package["manifest_path"]).parent
        files = sorted(
            p for p in root.rglob("*") if p.is_file() and NOTICE_NAME.fullmatch(p.name)
        )
        if package.get("license_file"):
            declared = root / package["license_file"]
            if declared not in files:
                files.append(declared)
        notices = [
            (p.relative_to(root).as_posix(), p.read_text(encoding="utf-8"))
            for p in files
        ]
        if package["name"] in SUPPLEMENTS:
            vcs = json.loads(
                (root / ".cargo_vcs_info.json").read_text(encoding="utf-8")
            )
            commit = vcs["git"]["sha1"]
            for url, digest in SUPPLEMENTS[package["name"]]:
                if f"/{commit}/" not in url:
                    raise RuntimeError(
                        "Review upstream license sources for the changed dependency."
                    )
                notices.append(
                    (
                        "Upstream license: " + url,
                        supplemental(url, digest, args.offline),
                    )
                )
        if package["name"] in CC0_PACKAGES:
            if package["license"] != "CC0-1.0":
                raise RuntimeError("Review the changed Bitcoin dependency license.")
            notices.append(("CC0-1.0 text (secp256k1 LICENSE)", cc0_text))
        if package["name"] in {"ring", "secp256k1-sys"}:
            notices.extend(source_notices(root))
        if not notices:
            raise RuntimeError(
                f"License text missing: {package['name']} {package['version']}"
            )
        authors = "; ".join(package["authors"]) or "Not specified in Cargo metadata"
        section = (
            "\n\n" + "=" * 72 + "\n"
            f"{package['name']} {package['version']}\n"
            f"License expression: {package['license']}\n"
            f"Repository: {package.get('repository') or 'Not specified'}\n"
            f"Authors: {authors}\n"
        )
        for label, text in notices:
            section += f"\n\n--- {label} ---\n{text.rstrip()}\n"
        sections.append(section)
        notice_count += len(notices)
    generated = "".join(sections).encode("utf-8") + b"\n\n" + rust
    if args.check:
        if generated != current:
            raise RuntimeError("Native dependency notices need regeneration.")
    else:
        OUTPUT.write_bytes(generated)
    print(
        f"Verified {len(packages)} dependency packages, {notice_count} license/source notices; "
        "Rust standard-library appendix retained unchanged."
    )


if __name__ == "__main__":
    main()

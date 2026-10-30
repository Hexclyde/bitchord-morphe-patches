#!/usr/bin/env python3
"""Refresh or verify the pinned public Nostr candidate list using runtime validation."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from buildenv import TOOLS, jdk

DIRECTORY = ROOT / "resources/soulseek"
SOURCE = DIRECTORY / "nostr-relays-source.txt"
OUTPUT = DIRECTORY / "nostr-relays.txt"
METADATA = DIRECTORY / "nostr-relays-source.json"
REPOSITORY = "https://github.com/sesseor/nostr-relays-list"
REVISION = "23234b5d167ba7a482b4d3d2a523e7b1f0d69050"
SOURCE_SHA256 = "41234efd3164f869a8a15e560b1a255380a1bd2079f7ded4bee75a161b044369"
LIMIT = 256 * 1024

JAVA = """package app.bitchord.soulseek;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;
public final class RelayListTool {
    public static void main(String[] args) throws Exception {
        String text = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8);
        List<String> parsed = RelayCandidates.parse(text);
        List<String> merged = RelayCandidates.merge(RelayCandidates.DEFAULTS, parsed);
        int entries=0, supported=0;
        for (String raw : text.split("\\n", -1)) {
            String line=raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            entries++;
            try { RelayCandidates.canonical(line); supported++; }
            catch (java.io.IOException ignored) {}
        }
        Files.write(Paths.get(args[1]), (String.join("\\n",merged)+"\\n").getBytes(StandardCharsets.UTF_8));
        System.out.println(new JSONObject().put("source_entries",entries)
            .put("supported_entries",supported).put("unsupported_entries",entries-supported)
            .put("duplicate_entries",supported-parsed.size())
            .put("source_unique",parsed.size()).put("bundled_candidates",merged.size()).toString());
    }
}
"""


def download(url):
    request = urllib.request.Request(
        url, headers={"User-Agent": "BitChord-relay-inputs"}
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        data = response.read(LIMIT + 1)
    if len(data) > LIMIT:
        raise ValueError("Relay source exceeds the download limit")
    return data


def validate(data):
    if len(data) > LIMIT:
        raise ValueError("Relay source exceeds the import limit")
    data.decode("utf-8", errors="strict")
    extension = ROOT / "extensions/soulseek/src/main/java/app/bitchord/soulseek"
    java = jdk() / "bin"
    with tempfile.TemporaryDirectory(prefix="nostr-candidates-") as temporary:
        directory = Path(temporary)
        raw = directory / "source.txt"
        clean = directory / "clean.txt"
        helper = directory / "RelayListTool.java"
        raw.write_bytes(data)
        helper.write_text(JAVA)
        subprocess.run(
            [
                str(java / "javac"),
                "--release",
                "8",
                "-Xlint:-options",
                "-cp",
                str(TOOLS / "json.jar"),
                "-d",
                str(directory),
                *(
                    str(extension / (name + ".java"))
                    for name in ("NostrConfig", "RelayCandidates", "RelayRanking")
                ),
                str(helper),
            ],
            check=True,
        )
        result = subprocess.check_output(
            [
                str(java / "java"),
                "-cp",
                str(directory) + os.pathsep + str(TOOLS / "json.jar"),
                "app.bitchord.soulseek.RelayListTool",
                str(raw),
                str(clean),
            ],
            text=True,
        )
        return clean.read_bytes(), json.loads(result)


def atomic(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as output:
        temporary = Path(output.name)
        output.write(data)
    try:
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--refresh", action="store_true", help="Pin the current upstream main revision"
    )
    parser.add_argument(
        "--offline", action="store_true", help="Require the saved source snapshot"
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="Verify saved output without changing files",
    )
    args = parser.parse_args()
    if args.refresh and (args.offline or args.check):
        parser.error("--refresh cannot be combined with --offline or --check")
    saved = json.loads(METADATA.read_text()) if METADATA.exists() else None
    revision = saved["revision"] if saved else REVISION
    expected = saved["source_sha256"] if saved else SOURCE_SHA256
    if args.refresh:
        info = json.loads(
            download(
                "https://api.github.com/repos/sesseor/nostr-relays-list/commits/main"
            )
        )
        revision, expected = info["sha"], None
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("Invalid pinned relay revision")
    source_url = f"https://raw.githubusercontent.com/sesseor/nostr-relays-list/{revision}/relays.txt"
    if SOURCE.exists() and not args.refresh:
        data = SOURCE.read_bytes()
    else:
        if args.offline or args.check:
            raise FileNotFoundError("Pinned relay source snapshot is unavailable")
        data = download(source_url)
    digest = hashlib.sha256(data).hexdigest()
    if expected is not None and digest != expected:
        raise ValueError("Pinned relay source checksum does not match")
    clean, counts = validate(data)
    metadata = {
        "schema": 1,
        "repository": REPOSITORY,
        "revision": revision,
        "source_url": source_url,
        "source_sha256": digest,
        "bundled_sha256": hashlib.sha256(clean).hexdigest(),
        **counts,
    }
    if args.check:
        if not OUTPUT.exists() or OUTPUT.read_bytes() != clean or saved != metadata:
            raise ValueError(
                "Bundled relay candidates do not match the pinned validated source"
            )
    else:
        atomic(SOURCE, data)
        atomic(OUTPUT, clean)
        atomic(METADATA, (json.dumps(metadata, indent=2) + "\n").encode())
    print(
        f"Verified {counts['bundled_candidates']} public WSS candidates; "
        f"excluded {counts['unsupported_entries']} unsupported entries and "
        f"{counts['duplicate_entries']} duplicates. Revision {revision}."
    )


if __name__ == "__main__":
    main()

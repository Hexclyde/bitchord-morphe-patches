#!/usr/bin/env python3
"""Capture scoped Android service diagnostics over an authorized ADB connection."""

import argparse
import datetime
import json
import os
from pathlib import Path
import re
import queue
import threading
import shutil
import subprocess
import time

SERVICES = (
    "app.revanced.android.gms",
    "com.google.android.gms",
    "com.android.vending",
    "com.google.android.webview",
    "com.android.webview",
)
MAX_BYTES = 4 * 1024 * 1024
PACKAGE = re.compile(r"com\.music\.bitchord(?:\.[a-zA-Z0-9_]+)*\Z")
HEADER = re.compile(
    r"^\s*\d\d-\d\d\s+\S+\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+([^:]+):\s?(.*)$"
)
REASONS = (
    "ERR_UNKNOWN_URL_SCHEME",
    "ERR_INTERNET_DISCONNECTED",
    "ERR_CONNECTION_REFUSED",
    "ERR_CONNECTION_RESET",
    "ERR_NAME_NOT_RESOLVED",
    "ERR_TIMED_OUT",
    "ERR_CERT_AUTHORITY_INVALID",
    "FATAL EXCEPTION",
    "ANR in",
    "OutOfMemoryError",
    "SecurityException",
    "ActivityNotFoundException",
    "DeadObjectException",
    "RemoteException",
    "SSLHandshakeException",
    "UnknownHostException",
    "SocketTimeoutException",
    "authorizationStateWaitPhoneNumber",
    "authorizationStateWaitCode",
    "authorizationStateReady",
    "FLOOD_WAIT",
    "Sign in to Telegram first",
    "Recording check stopped",
)
TAGS = {
    "AndroidRuntime",
    "ActivityTaskManager",
    "ActivityManager",
    "chromium",
    "WebViewFactory",
    "WebViewUpdateService",
    "GoogleAuthUtil",
    "GmsAuth",
    "GmsAuthManager",
    "Auth",
    "BitChord",
    "TDLib",
    "libc",
    "DEBUG",
}


def event(line):
    """Project arbitrary log text onto fixed categories without copying its payload."""
    match = HEADER.match(line)
    if not match:
        return None
    pid, tid, level, tag, text = match.groups()
    result = {
        "pid": int(pid),
        "tid": int(tid),
        "severity": level,
        "tag": tag.strip() if tag.strip() in TAGS else "OTHER",
        "reason": next((reason for reason in REASONS if reason in text), "event"),
    }
    frame = re.fullmatch(
        r"\s*at ((?:android|java|javax|com\.music\.bitchord|app\.bitchord|com\.google|org\.chromium)\.[A-Za-z0-9_.$]+)\.([A-Za-z0-9_$<>]+)\((?:[A-Za-z0-9_$]+\.java:([0-9]{1,7})|Native Method|Unknown Source)\)\s*",
        text,
    )
    if frame:
        result["frame"] = frame[1] + "." + frame[2]
        if frame[3]:
            result["line"] = int(frame[3])
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", required=True)
    parser.add_argument("--duration", type=int, default=180)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--private-local-text",
        action="store_true",
        help="Also keep scoped original text locally. This may contain private data; review it before sharing.",
    )
    args = parser.parse_args()
    if not PACKAGE.fullmatch(args.package) or not 1 <= args.duration <= 900:
        parser.error("Use a BitChord package and a duration between 1 and 900 seconds.")
    adb = shutil.which("adb")
    if not adb:
        parser.error("Install Android SDK Platform Tools and make adb available.")
    prefix = [adb, "-s", args.serial]

    def command(*parts):
        return subprocess.run(
            prefix + list(parts), capture_output=True, text=True, timeout=15, check=True
        ).stdout

    command("get-state")
    packages = (args.package,) + SERVICES
    uids, present = set(), []
    for package in packages:
        rows = command("shell", "cmd", "package", "list", "packages", "-U", package)
        match = re.search(
            r"^package:" + re.escape(package) + r" uid:([0-9]+)$", rows, re.M
        )
        if match:
            uids.add(int(match[1]))
            present.append(package)
    if args.package not in present:
        parser.error("The selected BitChord package is not installed.")
    args.output.mkdir(parents=True, exist_ok=False)
    os.chmod(args.output, 0o700)
    details = {
        "scope": present,
        "seconds": args.duration,
        "started_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "raw_local_text": args.private_local_text,
        "limits": "Only accessible scoped buffers; rotated, suppressed and inaccessible logs cannot be recovered. Free-text messages are excluded from the shareable report.",
    }
    (args.output / "capture.json").write_text(json.dumps(details, indent=2) + "\n")
    target = args.output / "events.jsonl"
    stream = target.open("w", encoding="utf-8")
    raw = (
        (args.output / "PRIVATE-LOCAL-TEXT.txt").open("w", encoding="utf-8")
        if args.private_local_text
        else None
    )
    if raw:
        os.chmod(raw.name, 0o600)
    process = subprocess.Popen(
        prefix
        + [
            "logcat",
            "-b",
            "main",
            "-b",
            "system",
            "-b",
            "crash",
            "--uid=" + ",".join(map(str, sorted(uids))),
            "-v",
            "threadtime",
            "-T",
            "1",
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
    )
    deadline = time.monotonic() + args.duration
    pending = queue.Queue(maxsize=128)
    finished = threading.Event()
    dropped = [0]

    def read():
        while not finished.is_set():
            line = process.stdout.readline(32769)
            if not line:
                break
            try:
                pending.put_nowait(line)
            except queue.Full:
                dropped[0] += 1
        finished.set()

    reader = threading.Thread(target=read, name="android-log-reader", daemon=True)
    reader.start()
    size = 0
    print(
        "Capture is running. Reproduce the problem on the phone. No app data or log buffers are cleared.",
        flush=True,
    )
    try:
        while time.monotonic() < deadline and size < MAX_BYTES:
            try:
                line = pending.get(
                    timeout=min(1, max(0.001, deadline - time.monotonic()))
                )
            except queue.Empty:
                if finished.is_set():
                    break
                continue
            text = line[:32768].decode("utf-8", errors="replace")
            record = event(text)
            if record is None:
                continue
            encoded = json.dumps(record, ensure_ascii=True) + "\n"
            if size + len(encoded) > MAX_BYTES:
                break
            stream.write(encoded)
            size += len(encoded)
            if raw and raw.tell() < MAX_BYTES:
                raw.write(text.rstrip("\r\n") + "\n")
    except KeyboardInterrupt:
        pass
    finally:
        finished.set()
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
        stream.close()
        if raw:
            raw.close()
    details["dropped_lines"] = dropped[0]
    details["ended_utc"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    details["logcat_exit"] = process.returncode
    (args.output / "capture.json").write_text(json.dumps(details, indent=2) + "\n")
    print(
        "Share events.jsonl and capture.json with the in-app report. Review any PRIVATE-LOCAL-TEXT.txt separately."
    )


if __name__ == "__main__":
    main()

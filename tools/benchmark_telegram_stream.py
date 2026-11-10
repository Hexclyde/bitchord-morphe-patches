#!/usr/bin/env python3
"""Compare sanitized, short Diagnostics captures; never run a network speed test."""

import argparse
from collections import Counter, defaultdict
import json
import math
from pathlib import Path
import statistics
import zipfile

MAX_INPUT = 4 * 1024 * 1024


def records(path):
    if path.stat().st_size > MAX_INPUT:
        raise ValueError("Capture exceeds the bounded input size")
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            entry = archive.getinfo("bitchord-diagnostics.txt")
            if entry.file_size > MAX_INPUT:
                raise ValueError("Expanded capture exceeds the bounded input size")
            data = archive.read(entry)
    else:
        data = path.read_bytes()
    result = []
    for line in data.decode("utf-8").splitlines():
        try:
            row = json.loads(line)
        except (ValueError, TypeError):
            continue
        if isinstance(row, dict) and str(row.get("type", "")).startswith(
            "telegram.stream."
        ):
            result.append(row)
    return result


def distribution(values):
    values = sorted(v for v in values if isinstance(v, (float, int)) and v >= 0)
    return {
        "n": len(values),
        "p50": statistics.median(values) if values else None,
        "p95": values[max(0, math.ceil(len(values) * 0.95) - 1)] if values else None,
    }


def summarize(rows, enabled):
    variants = {r["stream_v2"] for r in rows if isinstance(r.get("stream_v2"), bool)}
    if variants and variants != {enabled}:
        raise ValueError("Use a separate capture for each flag setting")
    requests = {}
    for row in rows:
        if row["type"] in (
            "telegram.stream.request",
            "telegram.stream.readable",
            "telegram.stream.end",
        ):
            requests.setdefault(row["request_id"], {}).update(row)
    timing = list(requests.values())
    progress = [r for r in rows if r["type"] == "telegram.stream.progress"]
    output = [r for r in rows if r["type"] == "telegram.stream.output"]
    windows = [r for r in rows if r["type"] == "telegram.stream.window"]
    rates = defaultdict(lambda: [0, 0])
    for row in progress:
        if not row.get("progress_reset") and row.get("interval_ms", 0) > 0:
            values = rates[row["file_ref"]]
            values[0] += row.get("new_bytes_estimate", 0)
            values[1] += row["interval_ms"]
    samples = [r for r in output if r.get("output_signal") == "sample"]
    cpu = [r for r in samples if "process_cpu_ms" in r and "mono_ms" in r]
    cpu_percent = None
    if len(cpu) >= 2:
        elapsed = cpu[-1]["mono_ms"] - cpu[0]["mono_ms"]
        used = cpu[-1]["process_cpu_ms"] - cpu[0]["process_cpu_ms"]
        if elapsed > 0 and used >= 0:
            cpu_percent = round(
                100 * used / elapsed, 2
            )  # May exceed 100 on multiple cores.
    ranges = Counter()
    for window in windows:
        request = requests.get(window.get("request_id"), {})
        ranges[
            request.get("container_format", window.get("container_format", "unknown"))
        ] += 1
    # Count commands actually issued before an exposed sink signal, not a theoretical minimum.
    boundaries, previous = [], {}
    for row in output:
        signal = row.get("output_signal")
        if signal not in ("sink-started", "sink-advancing") or not row.get("file_ref"):
            continue
        file = row["file_ref"]
        matches = [
            w
            for w in windows
            if w.get("file_ref") == file
            and previous.get(file, -1) < w.get("mono_ms", -1) <= row["mono_ms"]
        ]
        previous[file] = row["mono_ms"]
        boundaries.append(
            {
                "signal": signal,
                "commands": len(matches),
                "formats": dict(
                    Counter(
                        requests.get(w.get("request_id"), {}).get(
                            "container_format", w.get("container_format", "unknown")
                        )
                        for w in matches
                    )
                ),
            }
        )
    builds = [r for r in rows if r["type"] == "telegram.stream.session"]
    return {
        "stream_v2": enabled,
        "wrapper_builds": [
            {
                k: r[k]
                for k in (
                    "wrapper_version",
                    "wrapper_commit",
                    "wrapper_fingerprint",
                    "tdlib_version",
                )
                if k in r
            }
            for r in builds
        ],
        "requests": len(timing),
        "first_readable_ms": distribution(r.get("first_byte_ms", -1) for r in timing),
        "required_range_ms": distribution(
            r.get("required_range_ms", -1) for r in timing
        ),
        "observed_wait_before_progress_ms": distribution(
            r.get("before_progress_ms", -1) for r in timing
        ),
        "progress_to_first_readable_ms": distribution(
            r.get("after_progress_ms", -1) for r in timing
        ),
        "progress_to_required_range_ms": distribution(
            r.get("range_after_progress_ms", -1) for r in timing
        ),
        "seek_to_sink_advancement_ms": distribution(
            r.get("output_ms", -1)
            for r in output
            if r.get("output_signal") == "sink-advancing"
        ),
        "physical_seek_to_audio_ms": None,
        "cache_served_bytes": sum(r.get("cache_bytes", 0) for r in timing),
        "not_cached_at_open_served_bytes": sum(
            r.get("uncached_bytes", 0) for r in timing
        ),
        "new_downloaded_counter_bytes_estimate": sum(
            r.get("new_bytes_estimate", 0) for r in progress
        ),
        "file_progress_mbps_estimate": distribution(
            b * 8 / (ms * 1000) for b, ms in rates.values()
        ),
        "excess_downloaded_bytes": None,
        "counter_resets": sum(bool(r.get("progress_reset")) for r in progress),
        "poll_or_fallback_wakes": sum(r.get("poll_wakes", 0) for r in timing),
        "update_wakes": sum(r.get("update_wakes", 0) for r in timing),
        "control_wakes": sum(r.get("control_wakes", 0) for r in timing),
        "unreadable_wakes": sum(r.get("unreadable_wakes", 0) for r in timing),
        "range_commands_per_format": dict(ranges),
        "range_commands_until_sink_signals": boundaries or None,
        "container_prefetch_commands": sum(
            w.get("range_kind") == "container-prefetch" for w in windows
        ),
        "recorded_rebuffers": sum(r.get("output_signal") == "rebuffer" for r in output),
        "recorded_underruns": sum(r.get("output_signal") == "underrun" for r in output),
        "buffer_ms": distribution(r["buffer_ms"] for r in samples if "buffer_ms" in r),
        "java_heap_peak_mib": max(
            (r["heapUsedMiB"] for r in samples if "heapUsedMiB" in r), default=None
        ),
        "process_cpu_percent_of_one_core": cpu_percent,
        "limitations": "TDLib file counters estimate payload, not wire bytes or connection RTT. Cache-served bytes may repeat. Exact excess bytes, physical audio output and missing sink signals remain unmeasured. Use short captures to avoid recorder rotation.",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    off, on = records(args.baseline), records(args.candidate)
    if not off or not on:
        parser.error("Both captures must contain Telegram streaming records")
    result = {"baseline": summarize(off, False), "candidate": summarize(on, True)}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print("Wrote comparison; null values are unmeasured, not zero.")


if __name__ == "__main__":
    main()

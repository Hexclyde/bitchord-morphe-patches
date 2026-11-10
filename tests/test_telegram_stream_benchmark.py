"""Synthetic records test aggregation only; these are not device benchmarks."""

import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    "stream_bench",
    Path(__file__).resolve().parents[1] / "tools/benchmark_telegram_stream.py",
)
bench = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bench)


class TelegramStreamBenchmarkTest(unittest.TestCase):
    def test_readable_end_and_wakes_do_not_duplicate_requests(self):
        rows = [
            {"type": "telegram.stream.request", "request_id": 2, "stream_v2": True},
            {"type": "telegram.stream.readable", "request_id": 2, "first_byte_ms": 150},
            {
                "type": "telegram.stream.end",
                "request_id": 2,
                "first_byte_ms": 150,
                "cache_bytes": 128,
                "poll_wakes": 2,
            },
            {
                "type": "telegram.stream.progress",
                "file_ref": 1,
                "new_bytes_estimate": 1000,
                "interval_ms": 100,
            },
        ]
        report = bench.summarize(rows, True)
        self.assertEqual(report["requests"], 1)
        self.assertEqual(report["first_readable_ms"]["p50"], 150)
        self.assertEqual(report["new_downloaded_counter_bytes_estimate"], 1000)
        self.assertEqual(report["file_progress_mbps_estimate"]["p50"], 0.08)
        self.assertEqual(report["poll_or_fallback_wakes"], 2)
        self.assertIsNone(report["physical_seek_to_audio_ms"])
        self.assertIsNone(report["excess_downloaded_bytes"])

    def test_flag_cohorts_cannot_be_mixed(self):
        with self.assertRaises(ValueError):
            bench.summarize(
                [{"type": "telegram.stream.session", "stream_v2": False}], True
            )

    def test_unknown_latency_is_not_a_zero(self):
        self.assertEqual(
            bench.distribution([-1, -1]), {"n": 0, "p50": None, "p95": None}
        )


if __name__ == "__main__":
    unittest.main()

"""Checks for pinned C++ runtime staging."""

import hashlib
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from tools.strip_native_runtime import strip_debug


class NativeRuntimeTest(unittest.TestCase):
    def test_pinned_runtime_needs_no_strip_tool(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "runtime.so"
            path.write_bytes(b"pinned runtime")
            with patch("tools.strip_native_runtime.subprocess.run") as run:
                strip_debug(
                    path, hashlib.sha256(path.read_bytes()).hexdigest(), Path(directory)
                )
            run.assert_not_called()

    def test_strip_removes_only_debug_and_checks_output(self):
        with tempfile.TemporaryDirectory() as directory:
            tools = Path(directory)
            runtime = tools / "runtime.so"
            runtime.write_bytes(b"debug runtime")
            strip = tools / "native-strip/llvm-strip"
            strip.parent.mkdir()
            strip.touch()
            expected = hashlib.sha256(b"runtime").hexdigest()
            with patch(
                "tools.strip_native_runtime.subprocess.run",
                side_effect=lambda *args, **kwargs: runtime.write_bytes(b"runtime"),
            ) as run:
                strip_debug(runtime, expected, tools)
            run.assert_called_once_with(
                [str(strip), "--strip-debug", str(runtime)], check=True
            )

    def test_wrong_output_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            tools = Path(directory)
            runtime = tools / "runtime.so"
            runtime.write_bytes(b"wrong runtime")
            strip = tools / "native-strip/llvm-strip"
            strip.parent.mkdir()
            strip.touch()
            with patch(
                "tools.strip_native_runtime.subprocess.run"
            ), self.assertRaisesRegex(RuntimeError, "hash differs"):
                strip_debug(runtime, "0" * 64, tools)

    def test_failed_strip_stops_build(self):
        with tempfile.TemporaryDirectory() as directory:
            tools = Path(directory)
            runtime = tools / "runtime.so"
            runtime.write_bytes(b"runtime")
            strip = tools / "native-strip/llvm-strip"
            strip.parent.mkdir()
            strip.touch()
            with patch(
                "tools.strip_native_runtime.subprocess.run",
                side_effect=subprocess.CalledProcessError(1, "llvm-strip"),
            ), self.assertRaises(subprocess.CalledProcessError):
                strip_debug(runtime, "0" * 64, tools)

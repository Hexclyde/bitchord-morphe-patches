"""Check Java selection for source checks and mobile metadata export."""

from pathlib import Path
import hashlib
import os
import re
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "tools"))
import buildenv
import prepare_mobile_source as mobile


class JavaToolchainTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.real = buildenv.jdk()

    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="bitchord-java-toolchain-")
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name)
        self.tools = self.base / "tools"
        self.tools.mkdir()

    def fake_jdk(self, name, runtime=21, compiler=21):
        home = self.base / name
        (home / "bin").mkdir(parents=True)
        for binary, version in (("java", runtime), ("javac", compiler)):
            file = home / "bin" / binary
            message = (
                f'openjdk version "{version}.0.1"'
                if binary == "java"
                else f"javac {version}.0.1"
            )
            file.write_text("#!/bin/sh\nprintf '%s\\n' '" + message + "'\n")
            file.chmod(0o700)
        return home

    def test_fresh_checkout_uses_java_home_to_compile_and_run_java_21(self):
        with patch.object(buildenv, "TOOLS", self.tools), patch.dict(
            os.environ, {"JAVA_HOME": str(self.real)}
        ):
            selected = buildenv.jdk()
        self.assertEqual(self.real, selected)
        source = self.base / "Java21Fixture.java"
        source.write_text(
            "public class Java21Fixture { public static void main(String[] args) { "
            "System.out.print(Runtime.version().feature()); } }"
        )
        subprocess.run(
            [str(selected / "bin/javac"), "--release", "21", str(source)],
            check=True,
            capture_output=True,
            text=True,
        )
        result = subprocess.run(
            [str(selected / "bin/java"), "-cp", str(self.base), "Java21Fixture"],
            check=True,
            capture_output=True,
            text=True,
        )
        self.assertEqual("21", result.stdout)

    def test_pinned_java_21_wins_over_old_java_home(self):
        pinned = self.tools / "jdk-21-fixture"
        pinned.symlink_to(self.real, target_is_directory=True)
        old = self.fake_jdk("jdk17", runtime=17, compiler=17)
        with patch.dict(os.environ, {"JAVA_HOME": str(old)}):
            self.assertEqual(pinned, buildenv.jdk(self.tools))

    def test_mislabeled_pinned_java_is_rejected_before_java_home(self):
        old = self.fake_jdk("old", runtime=17, compiler=17)
        (self.tools / "jdk-21-invalid").symlink_to(old, target_is_directory=True)
        with patch.dict(os.environ, {"JAVA_HOME": str(self.real)}):
            self.assertEqual(self.real, buildenv.jdk(self.tools))

    def test_runtime_and_compiler_must_both_be_java_21(self):
        for runtime, compiler in ((17, 21), (21, 17), (25, 25)):
            with self.subTest(runtime=runtime, compiler=compiler):
                home = self.fake_jdk(f"java-{runtime}-{compiler}", runtime, compiler)
                with patch.dict(os.environ, {"JAVA_HOME": str(home)}):
                    with self.assertRaisesRegex(SystemExit, "No usable JDK 21"):
                        buildenv.jdk(self.tools)

    def test_missing_or_unexecutable_compiler_is_rejected(self):
        home = self.fake_jdk("incomplete")
        (home / "bin/javac").chmod(0o600)
        with patch.dict(os.environ, {"JAVA_HOME": str(home)}):
            with self.assertRaisesRegex(SystemExit, "No usable JDK 21"):
                buildenv.jdk(self.tools)
        (home / "bin/javac").unlink()
        with patch.dict(os.environ, {"JAVA_HOME": str(home)}):
            with self.assertRaisesRegex(SystemExit, "No usable JDK 21"):
                buildenv.jdk(self.tools)

    def test_failed_version_probe_is_not_a_valid_toolchain(self):
        home = self.fake_jdk("failed")
        (home / "bin/java").write_text("#!/bin/sh\nexit 1\n")
        with patch.dict(os.environ, {"JAVA_HOME": str(home)}):
            with self.assertRaisesRegex(SystemExit, "No usable JDK 21"):
                buildenv.jdk(self.tools)

    def test_missing_toolchains_fail_with_java_home_instructions(self):
        with patch.dict(os.environ, {"JAVA_HOME": str(self.base / "absent")}):
            with self.assertRaisesRegex(SystemExit, "set JAVA_HOME to a JDK 21"):
                buildenv.jdk(self.tools)

    def test_mobile_export_uses_the_shared_selected_java_and_compiler(self):
        jar = self.tools / "morphe-desktop-fixture.jar"
        jar.write_bytes(b"owned metadata export fixture")
        metadata = {
            "downloads": [
                {
                    "file": jar.name,
                    "sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
                }
            ]
        }
        commands = []

        def export(command, **kwargs):
            commands.append(command)
            if "ExportPatchList" in command:
                Path(command[-1]).write_text('{"patches": []}')
            return subprocess.CompletedProcess(command, 0)

        with patch.dict(os.environ, {"MORPHE_TOOLS": str(self.tools)}), patch.object(
            mobile, "jdk", return_value=self.real
        ) as resolve, patch.object(mobile.subprocess, "run", side_effect=export):
            self.assertEqual(
                {"patches": []},
                mobile.export_patches(self.base / "fixture.mpp", metadata, False),
            )
        resolve.assert_called_once_with(self.tools)
        self.assertEqual(str(self.real / "bin/javac"), commands[0][0])
        self.assertEqual(["--release", "21"], commands[0][1:3])
        self.assertEqual(str(self.real / "bin/java"), commands[1][0])

    def test_source_and_build_jobs_set_up_the_same_java_21_as_formatting(self):
        workflow = (ROOT / ".github/workflows/source-checks.yml").read_text()
        jobs = {}
        for name in ("source-checks", "format", "build"):
            body = workflow.split("\n  " + name + ":\n", 1)[1]
            jobs[name] = re.split(r"\n  [a-z][a-z-]*:\n", body, maxsplit=1)[0]
        action = re.search(r"actions/setup-java@[0-9a-f]{40}", jobs["format"]).group()
        for name, target in (
            ("source-checks", "Python unit tests"),
            ("build", "Bootstrap"),
        ):
            with self.subTest(job=name):
                self.assertIn(action, jobs[name])
                self.assertIn("java-version: '21'", jobs[name])
                self.assertLess(jobs[name].index(action), jobs[name].index(target))


if __name__ == "__main__":
    unittest.main()

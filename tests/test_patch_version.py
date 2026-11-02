from pathlib import Path
import json
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from buildenv import VERSION, jdk, patch_version_source


class PatchVersionTests(unittest.TestCase):
    def test_metadata_version_is_the_default_source(self):
        version = json.loads((ROOT / "PROVENANCE.json").read_text())[
            "patch_bundle_version"
        ]
        self.assertEqual(version, VERSION)
        self.assertIn("VALUE = " + json.dumps(version) + ";", patch_version_source())
        self.assertEqual(patch_version_source(), patch_version_source(None))

    def test_invalid_versions_cannot_become_java_source(self):
        for value in (
            "",
            "1.2",
            '1.2.3"; throw new Error();',
            "1.2.3\n",
            "1.2.3\\invalid",
            12,
        ):
            with self.subTest(value=value), self.assertRaises(ValueError):
                patch_version_source(value)

    def test_actual_diagnostic_renderer_follows_current_and_future_metadata(self):
        source = (
            ROOT
            / "extensions/telegram/src/main/java/app/bitchord/telegram/TelegramActivity.java"
        ).read_text()
        start = source.index("    static String failureDetails(Throwable failure)")
        end = source.index("\n    interface Job", start)
        method = source[start:end]
        fixture = (
            """package app.bitchord.telegram;
import android.os.Build;
import java.util.*;
public class DiagnosticVersionFixture {
"""
            + method
            + """
    public static void main(String[] args) {
        String text = failureDetails(new IllegalArgumentException("private-value"));
        if (!text.contains("\\nPatch " + args[0] + "\\nAndroid API 33\\n"))
            throw new AssertionError("Diagnostic does not report the compiled catalogue version");
        if (text.contains("private-value")) throw new AssertionError("Exception message disclosed");
        if (!text.contains("java.lang.IllegalArgumentException"))
            throw new AssertionError("Exception class missing");
    }
}
"""
        )
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            build = base / "Build.java"
            build.write_text(
                "package android.os; public final class Build { public static final class VERSION { public static final int SDK_INT = 33; } }"
            )
            activity = base / "DiagnosticVersionFixture.java"
            activity.write_text(fixture)
            for index, version in enumerate((VERSION, "2.7.0-beta.99")):
                output = base / str(index)
                output.mkdir()
                patch = base / "PatchVersion.java"
                patch.write_text(patch_version_source(version))
                result = subprocess.run(
                    [
                        str(jdk() / "bin/javac"),
                        "--release",
                        "8",
                        "-Xlint:-options",
                        "-d",
                        str(output),
                        str(patch),
                        str(activity),
                        str(build),
                    ],
                    capture_output=True,
                    text=True,
                )
                self.assertEqual(0, result.returncode, result.stderr)
                (output / "app/bitchord/telegram/PatchVersion.class").unlink()
                result = subprocess.run(
                    [
                        str(jdk() / "bin/java"),
                        "-cp",
                        str(output),
                        "app.bitchord.telegram.DiagnosticVersionFixture",
                        version,
                    ],
                    capture_output=True,
                    text=True,
                )
                self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()

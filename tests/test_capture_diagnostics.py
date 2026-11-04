import importlib.util
from pathlib import Path
import json
import unittest

spec = importlib.util.spec_from_file_location(
    "capture_diagnostics",
    Path(__file__).resolve().parents[1] / "tools/capture_diagnostics.py",
)
capture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(capture)


class CaptureDiagnosticsTest(unittest.TestCase):
    def test_arbitrary_authentication_payload_never_reaches_event(self):
        for text in [
            "Cookie: SID=private-cookie email=person@example.com",
            "https://accounts.google.com/?code=private-code&token=private-token",
            "phone=+12345678901 password=private-password",
            "Bearer private-token ERR_UNKNOWN_URL_SCHEME",
        ]:
            record = capture.event("11-03 12:00:00.001 123 124 E chromium: " + text)
            exported = json.dumps(record)
            for secret in [
                "private",
                "person@example.com",
                "12345678901",
                "accounts.google.com",
            ]:
                self.assertNotIn(secret, exported)
        self.assertEqual("ERR_UNKNOWN_URL_SCHEME", record["reason"])

    def test_exception_frames_keep_location_without_exception_message(self):
        line = "11-03 12:00:00.001 123 124 E AndroidRuntime:     at app.bitchord.telegram.TelegramBridge.open(TelegramBridge.java:42)"
        record = capture.event(line)
        self.assertEqual("app.bitchord.telegram.TelegramBridge.open", record["frame"])
        self.assertEqual(42, record["line"])
        cause = capture.event(
            "11-03 12:00:00.001 123 124 E AndroidRuntime: java.lang.SecurityException: secret-account"
        )
        self.assertEqual("SecurityException", cause["reason"])
        self.assertNotIn("secret-account", json.dumps(cause))

    def test_unknown_tags_and_unstructured_text_are_not_copied(self):
        record = capture.event(
            "11-03 12:00:00.001 123 124 E private-account: private-text"
        )
        self.assertEqual("OTHER", record["tag"])
        self.assertEqual("event", record["reason"])
        self.assertIsNone(capture.event("private continuation without a header"))

    def test_package_constraint_rejects_other_projects_and_shell_text(self):
        for package in [
            "com.music.bitchord",
            "com.music.bitchord.telegramtest",
            "com.music.bitchord.plus",
        ]:
            self.assertIsNotNone(capture.PACKAGE.fullmatch(package))
        for package in [
            "com.other.app",
            "com.music.bitchord;reboot",
            "com.music.bitchord\n",
            "com.music.bitchordish",
        ]:
            self.assertIsNone(capture.PACKAGE.fullmatch(package))


if __name__ == "__main__":
    unittest.main()

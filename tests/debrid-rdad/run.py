#!/usr/bin/env python3
"""Exercise the actual Real-Debrid/AllDebrid adapters with bounded official-schema fixtures."""

from pathlib import Path
import os
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from buildenv import TOOLS, jdk

with tempfile.TemporaryDirectory(prefix="bitchord-rdad-tests-") as temporary:
    out = Path(temporary)
    stub = out / "AudioFormatPreferences.java"
    stub.write_text("""package app.bitchord.soulseek;
public final class AudioFormatPreferences {
 public static java.util.List<String> candidateFormats(String name) {
  String n=name.toLowerCase(java.util.Locale.ROOT);
  return n.matches(".*[.](flac|mp3|m4a|alac|wav|wave|aif|aiff|aifc|dsf|dff|opus|ogg|oga|vorbis|aac|mp4)$")
   ? java.util.Collections.singletonList("audio") : java.util.Collections.emptyList();
 }
}
""")
    source = ROOT / "extensions/soulseek/src/main/java/app/bitchord/soulseek"
    files = [
        source / (name + ".java")
        for name in (
            "DebridModels",
            "DebridProvider",
            "DebridHttp",
            "DebridTlsSockets",
            "RealDebridProvider",
            "AllDebridProvider",
        )
    ]
    classpath = str(TOOLS / "json.jar")
    subprocess.run(
        [
            str(jdk() / "bin/javac"),
            "--release",
            "8",
            "-Xlint:-options",
            "-cp",
            classpath,
            "-d",
            temporary,
            *map(str, files),
            str(stub),
            str(Path(__file__).with_name("ProvidersTest.java")),
        ],
        check=True,
    )
    subprocess.run(
        [
            str(jdk() / "bin/java"),
            "-cp",
            temporary + os.pathsep + classpath,
            "app.bitchord.soulseek.ProvidersTest",
        ],
        check=True,
        timeout=30,
    )

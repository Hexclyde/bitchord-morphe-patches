#!/usr/bin/env python3
"""Compile and run the host tests. Run build.py first."""

import os
import subprocess
import sys

from buildenv import MORPHE_JAR, ROOT, TOOLS, jdk

JDK = jdk()
OUT = ROOT / "build/host-tests"
OUT.mkdir(parents=True, exist_ok=True)
CP = os.pathsep.join(
    str(p)
    for p in (
        TOOLS / "json.jar",
        TOOLS / "android.jar",
        ROOT / "build/extension-classes",
        TOOLS / "annotation.jar",
        ROOT / "build/compile-api.jar",
        MORPHE_JAR,
    )
)
EXTENSION = ROOT / "extensions/telegram/src/main/java/app/bitchord/telegram"
# TelegramBridge and the Android UI classes are replaced by fixtures in tests/.
SOURCES = [
    "PlaybackPolicy",
    "MediaPolicy",
    "ChannelSponsorship",
    "ChannelContent",
    "CachePolicy",
    "TrackMetadata",
    "TrackNames",
    "TrackDetails",
    "Artwork",
    "AudioHeader",
    "FormatProbe",
    "LegendMemory",
    "LegendMatch",
    "TelegramMatch",
    "HostLog",
    "Downmix",
    "SmoothUpgrade",
    "LiveSearch",
    "IndexStore",
    "TelegramIndex",
    "HostLibrary",
    "Fingerprint",
    "SoundCheck",
    "MainChannel",
    "ZipDirectory",
    "ZipAlbums",
    "Duplicates",
    "DolbyHeader",
    "DolbySupport",
    "FlacDecoder",
    "CueAudio",
    "CueSheet",
    "CueAlbums",
    "AlbumList",
    "IndexTransfer",
    "LosslessCheck",
    "Chromaprint",
    "AcoustId",
    "FileChecks",
    "VersionAlign",
    "MicroGSignIn",
    "Strings",
    "Translations",
    "InternalAddon",
    "NativeAudio",
    "TelegramDataSource",
    "NativeDownloads",
    "TelegramUi",
    "NativeSource",
    "SourceEditorRoute",
    "ApplicationCredentials",
    "CredentialCipher",
    "PlaybackStats",
    "VocalCut",
    "Outputs",
    "SleepFade",
    "HeadphoneEq",
    "OutputEq",
    "KeyTempo",
    "DjMode",
    "DownloadChoice",
    "EffectsApps",
    "SoundExtras",
]
TESTS = [
    "NativeAudioTest",
    "NavigationTest",
    "CredentialsTest",
    "LiveSearchTest",
    "CachePolicyTest",
    "PlaybackStatsTest",
    "PolicyTest",
    "TrackNamesTest",
    "TrackDetailsTest",
    "DownmixTest",
    "SmoothUpgradeTest",
    "AudioHeaderTest",
    "LegendMemoryTest",
    "LegendMatchTest",
    "TelegramMatchTest",
    "TelegramIndexTest",
    "HostLibraryTest",
    "FingerprintTest",
    "SoundCheckTest",
    "MainChannelTest",
    "ZipDirectoryTest",
    "ZipAlbumsTest",
    "DuplicatesTest",
    "DolbyHeaderTest",
    "CueTest",
    "AlbumListTest",
    "IndexTransferTest",
    "LosslessCheckTest",
    "ChromaprintTest",
    "FileChecksTest",
    "VersionAlignTest",
    "MicroGSignInTest",
    "SoundExtrasTest",
    "KeyTempoTest",
    "HeadphoneEqTest",
    "OutputEqTest",
    "DjModeTest",
]

subprocess.run(
    [
        str(JDK / "bin/javac"),
        "-J-Xshare:off",
        "--release",
        "11",
        "-cp",
        CP,
        "-d",
        str(OUT),
        *(str(EXTENSION / f"{name}.java") for name in SOURCES),
        # Independent Soulseek/JNI tests have their own classpaths and runners.
        *(
            str(path)
            for path in (ROOT / "tests").rglob("*.java")
            if path.relative_to(ROOT / "tests").parts[0]
            not in {
                "soulseek",
                "soulseek-wire",
                "coordination",
                "download-policy",
                "download-route",
                "relay-policy",
                "relay-discovery",
                "bulk-downloads",
                "bulk-coordination",
                "overlay-bridge",
                "selected-downloads",
                "audio-format",
                "download-destinations",
                "download-uploads",
                "foreground-service",
                "debrid-core",
                "debrid-rdad",
                "debrid-pmtb",
                "debrid-settings",
                "debrid-tls",
            }
        ),
    ],
    check=True,
)
for test in TESTS:
    subprocess.run(
        [
            str(JDK / "bin/java"),
            "-Xshare:off",
            "-cp",
            str(OUT) + os.pathsep + CP,
            f"app.bitchord.telegram.{test}",
        ],
        check=True,
    )

# Each download suite owns its Android/host fixtures and must use a separate classpath.
for suite in (
    "soulseek",
    "coordination",
    "download-policy",
    "download-route",
    "relay-policy",
    "relay-discovery",
    "bulk-coordination",
    "bulk-downloads",
    "audio-format",
    "selected-downloads",
    "overlay-bridge",
    "download-uploads",
    "download-destinations",
    "foreground-service",
    "debrid-core",
    "debrid-rdad",
    "debrid-pmtb",
    "debrid-settings",
    "debrid-tls",
    "patch-controls",
):
    subprocess.run([sys.executable, str(ROOT / "tests" / suite / "run.py")], check=True)

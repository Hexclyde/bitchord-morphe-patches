#!/usr/bin/env python3
"""Build internal addon rows against the 1.9 seven-value config, the nine-value one and an unknown one."""

from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from buildenv import jdk

COMMON = {
    "com/music/bitchord/data/sources/SourceKind.java": "package com.music.bitchord.data.sources;public enum SourceKind{ADDON,YOUTUBE}",
    "kotlinx/coroutines/flow/StateFlow.java": "package kotlinx.coroutines.flow;public interface StateFlow<T>{T getValue();}",
    "com/music/bitchord/data/sources/SourceRegistry.java": """package com.music.bitchord.data.sources;
import java.util.*;import kotlinx.coroutines.flow.StateFlow;
public final class SourceRegistry {
 public static List<Object> rows=new ArrayList<>();public static int publications;
 public static final StateFlow<List<Object>> configs=()->rows;
 public static void publish(ArrayList<Object> next,boolean persist){publications++;rows=next;}
}
""",
}
HEAD = "package com.music.bitchord.data.sources;import java.util.*;public final class SourceConfig{"
GETTERS = "public String getId(){return id;}public String getBaseUrl(){return url;}public boolean getEnabled(){return enabled;}"
FIELDS = "public final String id,label,url;public final SourceKind kind;public final boolean enabled,downloads,lossless;"
SEVEN = "public SourceConfig(String id,SourceKind kind,String label,String url,boolean enabled,boolean downloads,boolean lossless){this.id=id;this.kind=kind;this.label=label;this.url=url;this.enabled=enabled;this.downloads=downloads;this.lossless=lossless;"
VARIANTS = {
    "legacy": HEAD + FIELDS + SEVEN + "}" + GETTERS + "}",
    "quality": HEAD
    + FIELDS
    + "public final List<String> qualityOptions;public final String pickedQuality;"
    + "public SourceConfig(String id,SourceKind kind,String label,String url,boolean enabled,boolean downloads,boolean lossless,List<String> options,String picked){this.id=id;this.kind=kind;this.label=label;this.url=url;this.enabled=enabled;this.downloads=downloads;this.lossless=lossless;this.qualityOptions=options;this.pickedQuality=picked;}"
    + GETTERS
    + "}",
    "future": HEAD
    + FIELDS
    + "public SourceConfig(String id,SourceKind kind,String label,String url,boolean enabled,boolean downloads,boolean lossless,List<String> options,String picked,String added){this.id=id;this.kind=kind;this.label=label;this.url=url;this.enabled=enabled;this.downloads=downloads;this.lossless=lossless;}"
    + GETTERS
    + "}",
}
EXTENSION = ROOT / "extensions/telegram/src/main/java/app/bitchord/telegram"
SOULSEEK = (
    ROOT / "extensions/soulseek/src/main/java/app/bitchord/soulseek/SoulseekSource.java"
)
TESTS = Path(__file__).resolve().parent

with tempfile.TemporaryDirectory(prefix="source-config-") as temporary:
    base = Path(temporary)
    for variant, config in VARIANTS.items():
        stubs = base / variant / "stubs"
        classes = base / variant / "classes"
        classes.mkdir(parents=True)
        sources = []
        for relative, source in {
            **COMMON,
            "com/music/bitchord/data/sources/SourceConfig.java": config,
        }.items():
            path = stubs / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source)
            sources.append(str(path))
        subprocess.run(
            [
                str(jdk() / "bin/javac"),
                "-J-Xshare:off",
                "--release",
                "11",
                "-d",
                str(classes),
                *sources,
                str(EXTENSION / "NativeSourceConfig.java"),
                str(EXTENSION / "NativeSource.java"),
                str(SOULSEEK),
                str(TESTS / "NativeSourceConfigTest.java"),
            ],
            check=True,
        )
        subprocess.run(
            [
                str(jdk() / "bin/java"),
                "-Xshare:off",
                "-cp",
                str(classes),
                "app.bitchord.telegram.NativeSourceConfigTest",
                variant,
            ],
            check=True,
        )

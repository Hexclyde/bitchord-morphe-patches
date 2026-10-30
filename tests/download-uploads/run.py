#!/usr/bin/env python3
"""Upload receipt tests with the real TDLib API and a deterministic backend; no messages sent."""

from pathlib import Path
import os
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from buildenv import TOOLS, jdk

STUBS = {
    "android/content/Context.java": """package android.content;import java.io.*;public class Context {
final File root;public Context(File r){root=r;}public File getNoBackupFilesDir(){File f=new File(root,"private");f.mkdirs();return f;}
public File getFilesDir(){File f=new File(root,"files");f.mkdirs();return f;}}
""",
    "android/content/Intent.java": "package android.content;public class Intent{public Intent(Context c,Class<?> k){}}",
    "android/app/Activity.java": 'package android.app;public class Activity extends android.content.Context{public Activity(){super(new java.io.File("."));}public void startActivity(android.content.Intent i){}}',
    "android/os/Looper.java": "package android.os;public final class Looper{private static final Looper MAIN=new Looper();public static Looper myLooper(){return null;}public static Looper getMainLooper(){return MAIN;}}",
    "android/util/AtomicFile.java": """package android.util;import java.io.*;import java.nio.file.*;public final class AtomicFile {
final File base;public AtomicFile(File f){base=f;}public File getBaseFile(){return base;}public byte[] readFully()throws IOException{return Files.readAllBytes(base.toPath());}
public FileOutputStream startWrite()throws IOException{return new FileOutputStream(new File(base+".new"));}
public void finishWrite(FileOutputStream out)throws IOException{out.getFD().sync();out.close();Files.move(new File(base+".new").toPath(),base.toPath(),StandardCopyOption.REPLACE_EXISTING);}
public void failWrite(FileOutputStream out)throws IOException{out.close();new File(base+".new").delete();}}
""",
    "app/bitchord/telegram/TelegramActivity.java": "package app.bitchord.telegram;public final class TelegramActivity{}",
}

with tempfile.TemporaryDirectory(prefix="bitchord-upload-tests-") as temporary:
    temporary = Path(temporary)
    sources = temporary / "src"
    output = temporary / "classes"
    for relative, body in STUBS.items():
        target = sources / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(body)
    classpath = os.pathsep.join(
        map(
            str,
            [
                TOOLS / "json.jar",
                TOOLS / "annotation.jar",
                TOOLS / "kotlinc/lib/kotlin-stdlib.jar",
            ],
        )
    )
    subprocess.run(
        [
            str(jdk() / "bin/javac"),
            "-J-Xmx512m",
            "--release",
            "8",
            "-Xlint:-options",
            "-cp",
            classpath,
            "-d",
            str(output),
            *map(str, sources.rglob("*.java")),
            str(ROOT / "vendor/tdlib-api/TdApi.java"),
            str(
                ROOT
                / "extensions/telegram/src/main/java/app/bitchord/telegram/DownloadUploadBridge.java"
            ),
            str(Path(__file__).with_name("TelegramBridge.java")),
            str(Path(__file__).with_name("DownloadUploadTest.java")),
        ],
        check=True,
    )
    subprocess.run(
        [
            str(jdk() / "bin/java"),
            "-cp",
            str(output) + os.pathsep + classpath,
            "app.bitchord.telegram.DownloadUploadTest",
            str(temporary / "data"),
        ],
        check=True,
        timeout=30,
    )

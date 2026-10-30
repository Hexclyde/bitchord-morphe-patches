#!/usr/bin/env python3
"""Test the real credential policy and AES-GCM envelope without Android keys or provider traffic."""

from pathlib import Path
import os
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from buildenv import TOOLS, jdk

with tempfile.TemporaryDirectory(prefix="bitchord-debrid-settings-") as directory:
    temporary = Path(directory)
    provider = temporary / "source/app/bitchord/soulseek/DebridProvider.java"
    provider.parent.mkdir(parents=True)
    provider.write_text(
        "package app.bitchord.soulseek; public interface DebridProvider { enum Kind {"
        'REAL_DEBRID("real-debrid"),ALLDEBRID("alldebrid"),PREMIUMIZE("premiumize"),TORBOX("torbox");'
        "public final String id;Kind(String id){this.id=id;}}}"
    )
    output = temporary / "classes"
    classpath = os.pathsep.join(map(str, [TOOLS / "json.jar", TOOLS / "android.jar"]))
    subprocess.run(
        [
            str(jdk() / "bin/javac"),
            "--release",
            "8",
            "-Xlint:-options",
            "-cp",
            classpath,
            "-d",
            str(output),
            str(provider),
            str(
                ROOT
                / "extensions/soulseek/src/main/java/app/bitchord/soulseek/DebridCredentials.java"
            ),
            str(Path(__file__).with_name("DebridCredentialsTest.java")),
        ],
        check=True,
    )
    subprocess.run(
        [
            str(jdk() / "bin/java"),
            "-cp",
            str(output) + os.pathsep + classpath,
            "app.bitchord.soulseek.DebridCredentialsTest",
        ],
        check=True,
    )

    # Exercise the actual file-selection renderer with minimal host Android widgets.
    production = temporary / "production"
    stdlib = TOOLS / "kotlinc/lib/kotlin-stdlib.jar"
    api = temporary / "api"
    subprocess.run(
        [
            str(jdk() / "bin/javac"),
            "--release",
            "8",
            "-Xlint:-options",
            "-cp",
            str(TOOLS / "android.jar") + os.pathsep + str(stdlib),
            "-d",
            str(api),
            *map(str, (ROOT / "compile-only").rglob("*.java")),
        ],
        check=True,
    )
    sdk_classpath = os.pathsep.join(
        map(str, [TOOLS / "json.jar", TOOLS / "android.jar", stdlib, api])
    )
    subprocess.run(
        [
            str(jdk() / "bin/javac"),
            "--release",
            "8",
            "-Xlint:-options",
            "-cp",
            sdk_classpath,
            "-d",
            str(production),
            *map(str, (ROOT / "extensions/soulseek/src/main/java").rglob("*.java")),
        ],
        check=True,
    )
    fixtures = {
        "android/content/Context.java": "package android.content;public class Context {public static String policy;public static boolean enabled;public static java.io.File home;public java.io.File getNoBackupFilesDir(){return home;}public Context getApplicationContext(){return this;}public SharedPreferences getSharedPreferences(String n,int m){return new SharedPreferences(){public String getString(String k,String d){return policy;}public boolean contains(String k){return true;}public boolean getBoolean(String k,boolean d){return enabled;}public Editor edit(){return new Editor(){public Editor putBoolean(String k,boolean v){enabled=v;return this;}public boolean commit(){return true;}public void apply(){}};}};}}",
        "android/content/SharedPreferences.java": "package android.content;public interface SharedPreferences {String getString(String key,String fallback);boolean contains(String key);boolean getBoolean(String key,boolean fallback);Editor edit();interface Editor{Editor putBoolean(String key,boolean value);boolean commit();void apply();}}",
        "android/app/Activity.java": "package android.app;public class Activity extends android.content.Context {public boolean isFinishing(){return false;}public void runOnUiThread(Runnable r){r.run();}}",
        "android/view/View.java": "package android.view;public class View {public void setPadding(int a,int b,int c,int d){}}",
        "android/widget/LinearLayout.java": "package android.widget;import android.content.Context;import android.view.View;public class LinearLayout extends View {public LinearLayout(Context c){}public void removeAllViews(){}public void addView(View v){}}",
        "android/widget/TextView.java": "package android.widget;import android.content.Context;import android.view.View;public class TextView extends View {public TextView(Context c){}public void setText(CharSequence s){}public void setTextSize(float size){}}",
        "android/widget/CheckBox.java": "package android.widget;import android.content.Context;public class CheckBox extends TextView {boolean enabled=true,checked;public CheckBox(Context c){super(c);}public void setEnabled(boolean v){enabled=v;}public boolean isEnabled(){return enabled;}public void setChecked(boolean v){checked=v;}public boolean isChecked(){return checked;}}",
        "android/os/Looper.java": "package android.os;public class Looper {public static Looper getMainLooper(){return new Looper();}}",
        "android/os/Handler.java": "package android.os;public class Handler {public Handler(Looper l){}}",
    }
    fixture_source = temporary / "fixture-source"
    fixture_output = temporary / "fixture-output"
    for name, body in fixtures.items():
        path = fixture_source / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body)
    subprocess.run(
        [
            str(jdk() / "bin/javac"),
            "--release",
            "8",
            "-Xlint:-options",
            "-cp",
            str(production) + os.pathsep + sdk_classpath,
            "-d",
            str(fixture_output),
            *map(str, fixture_source.rglob("*.java")),
            str(Path(__file__).with_name("DebridUiSelectionTest.java")),
        ],
        check=True,
    )
    subprocess.run(
        [
            str(jdk() / "bin/java"),
            "-cp",
            str(fixture_output)
            + os.pathsep
            + str(production)
            + os.pathsep
            + sdk_classpath,
            "app.bitchord.soulseek.DebridUiSelectionTest",
        ],
        check=True,
    )

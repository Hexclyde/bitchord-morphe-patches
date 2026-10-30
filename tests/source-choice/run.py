#!/usr/bin/env python3
"""Exercise the actual manual source-choice facade without Android or network access."""

from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from buildenv import jdk

STUBS = {
    "android/content/Context.java": """package android.content;
import java.util.*;
public class Context {public final List<Intent> launched=new ArrayList<>();public boolean fail;
public void startActivity(Intent intent){if(fail)throw new IllegalStateException("fixture launch refused");launched.add(intent);}}
""",
    "android/content/Intent.java": """package android.content;
import java.util.*;
public class Intent {public static final int FLAG_ACTIVITY_NEW_TASK=0x10000000;public final Class<?> target;
public int flags;private final Map<String,String> extras=new HashMap<>();
public Intent(Context context,Class<?> target){this.target=target;}public Intent putExtra(String name,String value){extras.put(name,value);return this;}
public Intent addFlags(int flags){this.flags|=flags;return this;}public String getStringExtra(String name){return extras.get(name);}}
""",
    "android/os/Looper.java": """package android.os;public class Looper {private static final Looper MAIN=new Looper();public static Looper getMainLooper(){return MAIN;}}""",
    "android/os/Handler.java": """package android.os;import java.util.*;public class Handler {
public static final List<Runnable> delayed=new ArrayList<>();public Handler(Looper looper){}
public boolean post(Runnable action){action.run();return true;}public boolean postDelayed(Runnable action,long delay){delayed.add(action);return true;}}
""",
    "android/os/SystemClock.java": """package android.os;public class SystemClock {public static long now=1;public static long elapsedRealtime(){return now;}}""",
    "android/widget/Toast.java": """package android.widget;import android.content.Context;
public class Toast {public static final int LENGTH_LONG=1;public static String last="";
public static Toast makeText(Context context,String text,int duration){last=text;return new Toast();}public void show(){}}
""",
    "app/bitchord/telegram/PatchStrings.java": """package app.bitchord.telegram;public class PatchStrings {public static String t(String text){return text;}}""",
    "kotlin/Unit.java": """package kotlin;public final class Unit {public static final Unit INSTANCE=new Unit();private Unit(){}}""",
    "kotlin/jvm/functions/Function2.java": """package kotlin.jvm.functions;public interface Function2<A,B,C> {C invoke(A a,B b);}""",
    "app/bitchord/soulseek/ServiceFixtures.java": """package app.bitchord.soulseek;
import android.content.Context;import java.io.IOException;
final class SoulseekBridge {static SoulseekBridge instance;final Context context;SoulseekBridge(Context context){this.context=context;}
static SoulseekBridge get()throws IOException{if(instance==null)throw new IOException("fixture unavailable");return instance;}}
final class DownloadSettings {static final String SOULSEEK="soulseek";static boolean on=true;static boolean enabled(Context context,String feature){return on;}}
final class SoulseekSearchActivity {}
""",
}

with tempfile.TemporaryDirectory(prefix="soulseek-source-choice-") as temporary:
    output = Path(temporary)
    fixtures = []
    for relative, source in STUBS.items():
        path = output / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source)
        fixtures.append(str(path))
    java = jdk() / "bin"
    subprocess.run(
        [
            str(java / "javac"),
            "--release",
            "8",
            "-Xlint:-options",
            "-d",
            str(output),
            str(
                ROOT
                / "extensions/soulseek/src/main/java/app/bitchord/soulseek/SoulseekDownloadChoice.java"
            ),
            str(Path(__file__).parent / "SourceChoiceTest.java"),
            *fixtures,
        ],
        check=True,
    )
    subprocess.run(
        [
            str(java / "java"),
            "-cp",
            str(output),
            "app.bitchord.soulseek.SourceChoiceTest",
        ],
        check=True,
    )

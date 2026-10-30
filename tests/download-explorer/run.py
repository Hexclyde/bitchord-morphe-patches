#!/usr/bin/env python3
"""Exercise production navigation, provider binding and registry search without Android/network."""

from pathlib import Path
import os
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from buildenv import TOOLS, jdk

STUBS = {
    "android/content/Context.java": """package android.content;
import java.util.*;public class Context{public int launches;public final Prefs prefs=new Prefs();
public void startActivity(Intent i){launches++;}public Prefs getSharedPreferences(String n,int m){return prefs;}
public static class Prefs{public Set<String> saved=new HashSet<>();public boolean success=true;public String getString(String k,String d){return d;}public Prefs putString(String k,String v){return this;}
public Set<String> getStringSet(String n,Set<String>d){return new HashSet<>(saved);}public Prefs edit(){return this;}
public Prefs putStringSet(String k,Set<String> s){if(success)saved=new HashSet<>(s);return this;}public boolean commit(){return success;}}}""",
    "android/content/Intent.java": """package android.content;public class Intent{public static final int FLAG_ACTIVITY_NEW_TASK=1;
public Intent(Context c,Class<?> t){}public Intent addFlags(int f){return this;}}""",
    "app/bitchord/telegram/PatchStrings.java": """package app.bitchord.telegram;public class PatchStrings{public static String t(String s){return s;}}""",
    "app/bitchord/soulseek/Fixtures.java": """package app.bitchord.soulseek;
class DownloadsHubActivity{} class SoulseekBridge{static SoulseekBridge instance;final android.content.Context context;
SoulseekBridge(android.content.Context c){context=c;}static SoulseekBridge get(){return instance;}}""",
    "com/music/bitchord/ui/icons/BitChordIcons.java": """package com.music.bitchord.ui.icons;
public class BitChordIcons{public static final kotlin.Lazy<Object> Download$delegate=new kotlin.Lazy<Object>(){
public Object getValue(){return ICON;}public boolean isInitialized(){return true;}};public static final Object ICON=new Object();}""",
    "com/music/bitchord/ui/components/BottomTab.java": """package com.music.bitchord.ui.components;
public class BottomTab{public final String label;public final Object icon;public BottomTab(String l,Object i){label=l;icon=i;}}""",
    "com/music/bitchord/data/sources/SourceRegistry.java": """package com.music.bitchord.data.sources;
public class SourceRegistry{public static final Config CONFIG=new Config();public static final java.util.List<Object> SOURCES=new java.util.ArrayList<>();
public static java.util.List<Object> activeForDownload(){return SOURCES;}
public static Config config(String id){return id.equals("fixture")?CONFIG:null;}
public static class Config{public boolean enabled=true,allowed=true;public boolean getEnabled(){return enabled;}public boolean getAllowDownloads(){return allowed;}}}""",
    "com/music/bitchord/data/sources/MusicSource.java": """package com.music.bitchord.data.sources;
public interface MusicSource{String getConfigId();String getDisplayName();String getKind();
Object search(String query,int limit,boolean all,Object request,kotlin.coroutines.Continuation<Object> continuation);}""",
    "com/music/bitchord/download/Downloads.java": """package com.music.bitchord.download;
public class Downloads{public static final Object lock=new Object();public static final java.util.Map<String,Object> pending=new java.util.LinkedHashMap<>();
public static void enqueue(android.content.Context c,Object song,String from)throws Exception{String id=(String)song.getClass().getField("videoId").get(song);
synchronized(lock){pending.put(id,song);}}public static void cancel(String id){pending.remove(id);}}""",
}

with tempfile.TemporaryDirectory(prefix="download-explorer-tests-") as temporary:
    out = Path(temporary)
    fixtures = []
    for relative, content in STUBS.items():
        path = out / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        fixtures.append(str(path))
    cp = str(TOOLS / "kotlinc/lib/kotlin-stdlib.jar")
    production = ROOT / "extensions/soulseek/src/main/java/app/bitchord/soulseek"
    subprocess.run(
        [
            str(jdk() / "bin/javac"),
            "--release",
            "8",
            "-cp",
            cp,
            "-d",
            str(out),
            str(production / "DownloadNavigation.java"),
            str(production / "HostDownloadAccess.java"),
            str(Path(__file__).with_name("DownloadExplorerTest.java")),
            *fixtures,
        ],
        check=True,
    )
    subprocess.run(
        [
            str(jdk() / "bin/java"),
            "-cp",
            str(out) + os.pathsep + cp,
            "app.bitchord.soulseek.DownloadExplorerTest",
        ],
        check=True,
    )

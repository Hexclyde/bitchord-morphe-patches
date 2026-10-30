#!/usr/bin/env python3
"""Real debrid controller and HTTP boundary fixtures without provider network traffic."""

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
import java.io.File;
public class Context {
 final File home; public Context(File home){this.home=home;}
 public Context getApplicationContext(){return this;}
 public File getNoBackupFilesDir(){return home;}
}
""",
    "com/music/bitchord/data/settings/AppSettings.java": """package com.music.bitchord.data.settings;
public final class AppSettings {public static volatile boolean allowed=true;public static boolean getDownloadsAllowedNow(){return allowed;}}
""",
    "app/bitchord/soulseek/DebridCredentials.java": """package app.bitchord.soulseek;
import android.content.Context;import java.io.*;import java.util.*;
public final class DebridCredentials {
 public interface Listener{void onChanged(DebridProvider.Kind kind);}
 static final class Credential {final String key,revision;Credential(String key,String revision){this.key=key;this.revision=revision;}}
 final Map<DebridProvider.Kind,Credential> values=new EnumMap<>(DebridProvider.Kind.class);final Set<Listener> listeners=new HashSet<>();
 public static DebridCredentials get(Context context){return new DebridCredentials();}
 synchronized Credential enabledSnapshot(DebridProvider.Kind kind)throws IOException{Credential c=values.get(kind);if(c==null)throw new IOException("Provider is disabled.");return c;}
 public synchronized void requireCurrent(DebridProvider.Kind kind,String revision)throws IOException{if(!enabledSnapshot(kind).revision.equals(revision))throw new IOException("Provider settings changed.");}
 public synchronized void addListener(Listener value){listeners.add(value);}public synchronized void removeListener(Listener value){listeners.remove(value);}
 void enable(DebridProvider.Kind kind,String key){List<Listener> ls; synchronized(this){values.put(kind,new Credential(key,UUID.randomUUID().toString()));ls=new ArrayList<>(listeners);}for(Listener l:ls)l.onChanged(kind);}
 void disable(DebridProvider.Kind kind){List<Listener> ls;synchronized(this){values.remove(kind);ls=new ArrayList<>(listeners);}for(Listener l:ls)l.onChanged(kind);}
}
""",
    "app/bitchord/soulseek/AudioFormatPreferences.java": """package app.bitchord.soulseek;
import java.io.*;import java.util.*;
public final class AudioFormatPreferences {
 public enum Format {FLAC,MP3}
 public static final class Settings {public final boolean configured=true;public final Set<Format> enabled;Settings(Set<Format> enabled){this.enabled=enabled;}}
 static Settings decode(String value)throws IOException{if(value==null||value.isEmpty())throw new IOException("Missing formats");return new Settings(value.equals("mp3")?EnumSet.of(Format.MP3):EnumSet.allOf(Format.class));}
 public static List<Format> candidateFormats(String name){String s=name.toLowerCase();return s.endsWith(".flac")?Arrays.asList(Format.FLAC):s.endsWith(".mp3")?Arrays.asList(Format.MP3):Collections.emptyList();}
 public static String audioName(String name)throws IOException{if(candidateFormats(name).isEmpty())throw new IOException("Unsupported audio file.");return name.replace('/','_').replace('\\\\','_');}
 static Format validateSelectedFile(Settings settings,File file,String name)throws IOException{try(InputStream in=new FileInputStream(file)){byte[] b=new byte[4];if(in.read(b)!=4||!Arrays.equals(b,new byte[]{102,76,97,67}))throw new IOException("Audio codec validation failed.");}return Format.FLAC;}
}
""",
    "app/bitchord/soulseek/DownloadDestinations.java": """package app.bitchord.soulseek;
public final class DownloadDestinations {public static final class Snapshot {public final String mode,phoneTreeUri;public final long telegramChatId,telegramUserId;public Snapshot(String mode,String tree,long chat,long user){this.mode=mode;phoneTreeUri=tree;telegramChatId=chat;telegramUserId=user;}}}
""",
    "app/bitchord/soulseek/BulkTransferService.java": """package app.bitchord.soulseek;
import android.content.Context;public final class BulkTransferService {public static void ensureRunning(Context context){}public static void awaitRunning(){}}
""",
    "app/bitchord/soulseek/BulkDownloads.java": """package app.bitchord.soulseek;
import android.content.Context;import java.io.*;import java.util.*;
public final class BulkDownloads {
 static BulkDownloads value=new BulkDownloads();static int imports,resumes,pauses,cancels;static DownloadDestinations.Snapshot destination;static String formats;
 public static BulkDownloads get(Context context){return value;}public static BulkDownloads existing(){return value;}
 public synchronized String importVerified(File source,String name,DownloadDestinations.Snapshot dest,String token,String policy)throws IOException{destination=dest;formats=policy;imports++;File owned=new File(source.getParentFile(),token+".owned");if(!source.renameTo(owned))throw new IOException("Move failed");return token;}
 public void resume(String id){resumes++;}public void pause(String id){pauses++;}public void cancel(String id){cancels++;}
}
""",
}

SOURCE = ROOT / "extensions/soulseek/src/main/java/app/bitchord/soulseek"
with tempfile.TemporaryDirectory(prefix="bitchord-debrid-core-") as temporary:
    out = Path(temporary)
    stubs = []
    for name, content in STUBS.items():
        path = out / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        stubs.append(path)
    sources = [
        SOURCE / (name + ".java")
        for name in (
            "DebridModels",
            "DebridProvider",
            "DebridHttp",
            "DebridTlsSockets",
            "DebridDownloads",
            "RealDebridProvider",
            "AllDebridProvider",
            "PremiumizeProvider",
            "TorboxProvider",
        )
    ]
    subprocess.run(
        [
            str(jdk() / "bin/javac"),
            "--release",
            "8",
            "-Xlint:-options",
            "-cp",
            str(TOOLS / "json.jar"),
            "-d",
            str(out),
            *map(str, sources),
            *map(str, stubs),
            str(Path(__file__).with_name("DebridCoreTest.java")),
        ],
        check=True,
    )
    subprocess.run(
        [
            str(jdk() / "bin/java"),
            "-cp",
            str(out) + os.pathsep + str(TOOLS / "json.jar"),
            "app.bitchord.soulseek.DebridCoreTest",
        ],
        check=True,
        timeout=30,
    )

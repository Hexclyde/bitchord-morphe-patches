#!/usr/bin/env python3
"""Exercise feature opt-ins and the Sources-only state projection without networking."""

from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from buildenv import jdk

STUBS = {
    "app/bitchord/soulseek/PeerDiscovery.java": "package app.bitchord.soulseek;final class PeerDiscovery {static PeerDiscovery existing(){return null;}void stop(){}boolean shutdownFailed(){return false;}}",
    "android/content/SharedPreferences.java": """package android.content;
public interface SharedPreferences {
 boolean contains(String key); boolean getBoolean(String key,boolean fallback); Editor edit();
 interface Editor {Editor putBoolean(String key,boolean value);boolean commit();void apply();}
}
""",
    "android/content/Context.java": """package android.content;
import java.io.*;import java.util.*;import java.util.concurrent.*;
public class Context {
 public static final int MODE_PRIVATE=0;final File home;public Class<?> opened;
 final Map<String,SharedPreferences> preferences=new ConcurrentHashMap<>();
 public Context(File home){this.home=home;}public Context getApplicationContext(){return this;}
 public File getNoBackupFilesDir(){return home;}public void startActivity(Intent intent){opened=intent.target;}
 public SharedPreferences getSharedPreferences(String name,int mode){return preferences.computeIfAbsent(name,n->new Memory());}
 public static final class Memory implements SharedPreferences {
  final Map<String,Boolean> values=new ConcurrentHashMap<>();
  public boolean contains(String key){return values.containsKey(key);}public boolean getBoolean(String key,boolean fallback){return values.getOrDefault(key,fallback);}
  public Editor edit(){return new Editor(){final Map<String,Boolean> pending=new HashMap<>();public Editor putBoolean(String k,boolean v){pending.put(k,v);return this;}public boolean commit(){values.putAll(pending);return true;}public void apply(){commit();}};}
 }
}
""",
    "android/content/Intent.java": """package android.content;public final class Intent {public final Class<?> target;public Intent(Context context,Class<?> target){this.target=target;}}
""",
    "androidx/compose/runtime/MutableState.java": """package androidx.compose.runtime;public interface MutableState<T>{T getValue();void setValue(T value);T component1();Object component2();}
""",
    "com/music/bitchord/data/sources/SourceKind.java": """package com.music.bitchord.data.sources;public enum SourceKind{ADDON,YOUTUBE}
""",
    "com/music/bitchord/data/sources/SourceConfig.java": """package com.music.bitchord.data.sources;
public final class SourceConfig{public final String id,label,url;public final SourceKind kind;public final boolean enabled,downloads,lossless;
 public SourceConfig(String id,SourceKind kind,String label,String url,boolean enabled,boolean downloads,boolean lossless){this.id=id;this.kind=kind;this.label=label;this.url=url;this.enabled=enabled;this.downloads=downloads;this.lossless=lossless;}
 public String getId(){return id;}public String getBaseUrl(){return url;}public boolean getEnabled(){return enabled;}}
""",
    "kotlinx/coroutines/flow/StateFlow.java": """package kotlinx.coroutines.flow;public interface StateFlow<T>{T getValue();}
""",
    "com/music/bitchord/data/sources/SourceRegistry.java": """package com.music.bitchord.data.sources;
import java.util.*;import kotlinx.coroutines.flow.StateFlow;
public final class SourceRegistry {
 public static List<SourceConfig> rows=new ArrayList<>();public static final StateFlow<List<SourceConfig>> configs=()->rows;
 public static SourceConfig config(String id){return rows.stream().filter(r->r.id.equals(id)).findFirst().orElse(null);}
 public static void publish(ArrayList<SourceConfig> next,boolean persist){rows=next;}
 public static void setEnabled(String id,boolean on){ArrayList<SourceConfig> next=new ArrayList<>();for(SourceConfig r:rows)next.add(r.id.equals(id)?new SourceConfig(r.id,r.kind,r.label,r.url,on,r.downloads,r.lossless):r);rows=next;}
}
""",
    "app/bitchord/soulseek/SoulseekBridge.java": """package app.bitchord.soulseek;
import android.content.Context;
final class SoulseekBridge {static final SoulseekBridge instance=new SoulseekBridge();static volatile int reconnects,disconnects,invalidations;
 static void boot(Context context){}static SoulseekBridge get(){return instance;}String url(){return "https://soulseek.bitchord.invalid/session";}
 void reconnectSaved(){reconnects++;}long beginLogin(){return ++invalidations;}void disconnect(){disconnects++;}boolean shutdownFailed(){return false;}}
""",
    "app/bitchord/soulseek/SoulseekSelectedDownloads.java": """package app.bitchord.soulseek;final class SoulseekSelectedDownloads {static volatile int cancels;static void cancelAll(){cancels++;}}
""",
    "app/bitchord/soulseek/DebridProvider.java": """package app.bitchord.soulseek;interface DebridProvider{enum Kind{REAL_DEBRID,ALLDEBRID,PREMIUMIZE,TORBOX}}
""",
    "app/bitchord/soulseek/DebridCredentials.java": """package app.bitchord.soulseek;
import android.content.Context;import java.io.IOException;
final class DebridCredentials {static boolean legacyEnabled;static final DebridCredentials instance=new DebridCredentials();static DebridCredentials get(Context context)throws IOException{return instance;}
 static final class Snapshot{final boolean enabled;Snapshot(boolean enabled){this.enabled=enabled;}}
 Snapshot snapshot(DebridProvider.Kind kind){return new Snapshot(legacyEnabled&&kind==DebridProvider.Kind.TORBOX);}}
""",
    "app/bitchord/soulseek/BulkCoordinator.java": """package app.bitchord.soulseek;
import java.util.concurrent.*;import java.io.*;
final class BulkCoordinator {static BulkCoordinator instance;static CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);static volatile int pauses;
 static BulkCoordinator existing(){return instance;}void disablePeers()throws IOException{pauses++;entered.countDown();try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IOException(e);}}}
""",
    "app/bitchord/soulseek/NostrCoordinator.java": """package app.bitchord.soulseek;final class NostrCoordinator {static NostrCoordinator instance;static volatile int stops;static NostrCoordinator existing(){return instance;}void stop(){stops++;}boolean shutdownFailed(){return false;}}
""",
    "app/bitchord/soulseek/BulkDownloads.java": """package app.bitchord.soulseek;final class BulkDownloads {static BulkDownloads instance;static volatile int pauses;static BulkDownloads existing(){return instance;}void pausePeers(){pauses++;}}
""",
    "app/bitchord/soulseek/DebridDownloads.java": """package app.bitchord.soulseek;final class DebridDownloads {static DebridDownloads instance;static volatile int stops;static DebridDownloads existing(){return instance;}void stop(){stops++;}}
""",
    "app/bitchord/soulseek/SoulseekActivity.java": """package app.bitchord.soulseek;public class SoulseekActivity{}
""",
    "app/bitchord/soulseek/BulkDownloadsActivity.java": """package app.bitchord.soulseek;public class BulkDownloadsActivity{}
""",
    "app/bitchord/soulseek/DebridSetupActivity.java": """package app.bitchord.soulseek;public class DebridSetupActivity{}
""",
    "app/bitchord/soulseek/AudioFormatSetupActivity.java": "package app.bitchord.soulseek;public class AudioFormatSetupActivity{}",
    "app/bitchord/soulseek/NostrSetupActivity.java": "package app.bitchord.soulseek;public class NostrSetupActivity{}",
    "app/bitchord/soulseek/PatchControlsTest.java": """package app.bitchord.soulseek;
import app.bitchord.telegram.*;import android.content.*;import androidx.compose.runtime.MutableState;import com.music.bitchord.data.sources.*;
import java.io.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;
public final class PatchControlsTest {
 static int checks;static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
 interface Action{void run()throws Exception;}static void denied(Action action,String why)throws Exception{try{action.run();throw new AssertionError(why);}catch(IOException expected){checks++;}}
 static Context context()throws Exception{return new Context(Files.createTempDirectory("patch-controls-").toFile());}
 static void until(java.util.function.BooleanSupplier condition)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!condition.getAsBoolean()&&System.nanoTime()<until)Thread.sleep(5);check(condition.getAsBoolean(),"background shutdown completed");}
 public static final class State implements MutableState<List<?>>{List<?> value;State(List<?> value){this.value=value;}public List<?> getValue(){return value;}public void setValue(List<?> v){value=v;}public List<?> component1(){return value;}public Object component2(){return null;}}
 static SourceConfig row(String id){return new SourceConfig(id,SourceKind.ADDON,id,"https://example.invalid/",true,true,false);}
 static void sources()throws Exception{
  SourceConfig youtube=row("youtube"),telegram=row("telegram-native"),soulseek=row("soulseek-native"),custom=row("telegram-native-custom");
  List<?> original=Arrays.asList(youtube,telegram,soulseek,custom);
  List<?> filtered=PatchSources.visible(original);
  check(filtered.size()==3&&filtered.get(0)==youtube&&filtered.get(1)==telegram&&filtered.get(2)==custom,"only Soulseek hidden; Telegram and user sources preserved");
  check(original.size()==4,"underlying registry list unchanged");check(PatchSources.visible(filtered)==filtered,"unchanged list keeps identity");
  Object unknown=new Object();check(PatchSources.visible(Arrays.asList(unknown)).get(0)==unknown,"unknown host rows preserved");
  State state=new State(original);Object projected=PatchSources.visibleState(state);check(projected instanceof MutableState,"state projection keeps host MutableState interface");
  check(projected==PatchSources.visibleState(state),"projection stable per host state");
  MutableState<?> view=(MutableState<?>)projected;check(((List<?>)view.getValue()).equals(filtered),"getValue filters the visible list");
  check(((List<?>)view.component1()).equals(filtered),"component1 uses same projection");
  state.value=Arrays.asList(custom,soulseek,youtube);check(((List<?>)view.getValue()).equals(Arrays.asList(custom,youtube)),"projection follows host state changes");
  try{((MutableState)view).setValue(Collections.emptyList());throw new AssertionError("read-only state");}catch(UnsupportedOperationException expected){checks++;}
  check(state.value.size()==3,"projection setter cannot change registry state");check(projected.equals(projected)&&!projected.equals(state),"proxy equality remains reflexive and distinct");
  SourceRegistry.rows=new ArrayList<>();NativeSource.register("https://telegram.bitchord.invalid/session");check(SourceRegistry.config("telegram-native").enabled,"Telegram source default preserved");
 }
 static void settings()throws Exception{
  Context app=context();check(!DownloadSettings.enabled(app,DownloadSettings.PEERS),"fresh peer downloads off");check(!DownloadSettings.enabled(app,DownloadSettings.DEBRID),"fresh debrid downloads off");
  denied(()->DownloadSettings.open(app,DownloadSettings.PEERS),"disabled feature cannot open controls");
  DownloadSettings.setEnabled(app,DownloadSettings.PEERS,true);check(DownloadSettings.enabled(app,DownloadSettings.PEERS),"explicit peer opt-in persisted");
  DownloadSettings.open(app,DownloadSettings.PEERS);check(app.opened==BulkDownloadsActivity.class,"enabled gear opens matching controls");
  DownloadSettings.open(app,DownloadSettings.PEERS,"connections");check(app.opened==NostrSetupActivity.class,"connection action uses fixed peer setup screen");
  DownloadSettings.open(app,DownloadSettings.PEERS,"formats");check(app.opened==AudioFormatSetupActivity.class,"format action uses shared native settings");
  denied(()->DownloadSettings.open(app,DownloadSettings.PEERS,"services"),"actions are feature-specific allowlists");
  denied(()->DownloadSettings.open(app,DownloadSettings.PEERS,"arbitrary.Class"),"caller cannot open an arbitrary class");
  BulkCoordinator.instance=new BulkCoordinator();long start=System.nanoTime();DownloadSettings.setEnabled(app,DownloadSettings.PEERS,false);
  check(System.nanoTime()-start<TimeUnit.MILLISECONDS.toNanos(500),"disable does not wait for network shutdown on UI");
  check(!DownloadSettings.enabled(app,DownloadSettings.PEERS),"disable gate immediate");check(BulkCoordinator.entered.await(1,TimeUnit.SECONDS),"cleanup worker started");
  denied(()->DownloadSettings.setEnabled(app,DownloadSettings.PEERS,true),"enable rejected until old engine closes");
  BulkCoordinator.release.countDown();until(()->{try{DownloadSettings.setEnabled(app,DownloadSettings.PEERS,true);return true;}catch(IOException e){return false;}});
  check(BulkCoordinator.pauses==1,"peer disable uses only peer shutdown");BulkCoordinator.instance=null;
  Context saved=context();File envelope=new File(saved.getNoBackupFilesDir(),"debrid-downloads/credentials.sealed.json");envelope.getParentFile().mkdirs();Files.write(envelope.toPath(),new byte[]{1});
  DebridCredentials.legacyEnabled=false;check(!DownloadSettings.enabled(saved,DownloadSettings.DEBRID),"saved API key alone does not enable debrid");
  Context legacy=context();File old=new File(legacy.getNoBackupFilesDir(),"debrid-downloads/credentials.sealed.json");old.getParentFile().mkdirs();Files.write(old.toPath(),new byte[]{1});
  DebridCredentials.legacyEnabled=true;check(DownloadSettings.enabled(legacy,DownloadSettings.DEBRID),"explicit existing provider opt-in migrated");
  DownloadSettings.setEnabled(legacy,DownloadSettings.DEBRID,false);until(()->{try{DownloadSettings.setEnabled(legacy,DownloadSettings.DEBRID,true);return true;}catch(IOException e){return false;}});
  check(old.isFile()&&DebridCredentials.legacyEnabled,"disable preserves protected provider settings");
  DownloadSettings.setEnabled(app,DownloadSettings.SOULSEEK,true);check(SoulseekSource.enabled()&&SoulseekBridge.reconnects==1,"Soulseek toggle changes real registry and reconnects saved account");
  DownloadSettings.setEnabled(app,DownloadSettings.SOULSEEK,false);check(!SoulseekSource.enabled(),"Soulseek disable changes registry immediately");until(()->SoulseekBridge.disconnects==1);check(SoulseekSelectedDownloads.cancels==1&&SoulseekBridge.invalidations==1,"Soulseek disable invalidates login and cancels active capture");
  check(!DownloadSettings.enabled(app,"unknown"),"unknown features stay disabled");
 }
 public static void main(String[] args)throws Exception{sources();settings();System.out.println("Patch controls: "+checks+" assertions passed");}
}
""",
}

with tempfile.TemporaryDirectory(prefix="bitchord-patch-controls-") as temporary:
    output = Path(temporary)
    files = []
    for name, text in STUBS.items():
        path = output / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        files.append(path)
    files.extend(
        ROOT / relative
        for relative in (
            "extensions/telegram/src/main/java/app/bitchord/telegram/PatchSources.java",
            "extensions/telegram/src/main/java/app/bitchord/telegram/NativeSource.java",
            "extensions/soulseek/src/main/java/app/bitchord/soulseek/DownloadSettings.java",
            "extensions/soulseek/src/main/java/app/bitchord/soulseek/SoulseekSource.java",
        )
    )
    subprocess.run(
        [
            str(jdk() / "bin/javac"),
            "--release",
            "8",
            "-Xlint:-options",
            "-d",
            str(output),
            *map(str, files),
        ],
        check=True,
    )
    subprocess.run(
        [
            str(jdk() / "bin/java"),
            "-cp",
            str(output),
            "app.bitchord.soulseek.PatchControlsTest",
        ],
        check=True,
        timeout=15,
    )

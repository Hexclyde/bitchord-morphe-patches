#!/usr/bin/env python3
"""Exercise real bridge code with host IPC/FD fixtures; never contact overlay peers."""

from pathlib import Path
import os
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from buildenv import TOOLS, jdk

STUBS = {
    "android/os/Parcelable.java": "package android.os; public interface Parcelable {}",
    "android/os/RemoteException.java": "package android.os; public class RemoteException extends Exception {}",
    "android/os/IBinder.java": """package android.os;
public interface IBinder { interface DeathRecipient { void binderDied(); }
void linkToDeath(DeathRecipient d,int f)throws RemoteException; boolean unlinkToDeath(DeathRecipient d,int f); }
""",
    "android/os/Process.java": """package android.os; public final class Process {
public static volatile int kills; public static int myUid(){return 12345;} public static int myPid(){return 4321;}
public static void killProcess(int pid){if(pid!=4321)throw new AssertionError("wrong PID");kills++;} }
""",
    "android/os/Build.java": "package android.os; public final class Build { public static final class VERSION {public static int SDK_INT=35;} }",
    "android/os/SystemClock.java": "package android.os; public final class SystemClock {public static long elapsedRealtime(){return System.nanoTime()/1000000;}}",
    "android/os/Looper.java": """package android.os; import java.util.concurrent.*;
public final class Looper {
static final ThreadLocal<Looper> CURRENT=new ThreadLocal<>();
static final Looper MAIN=new Looper("fixture-main"); final ScheduledThreadPoolExecutor executor;
Looper(String name){executor=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(()->{CURRENT.set(this);r.run();},name);t.setDaemon(true);return t;});executor.setRemoveOnCancelPolicy(true);}
public static Looper getMainLooper(){return MAIN;} public static Looper myLooper(){return CURRENT.get();}
public static void markMain(boolean on){if(on)CURRENT.set(MAIN);else CURRENT.remove();}
}
""",
    "android/os/HandlerThread.java": """package android.os; public class HandlerThread extends Thread {
private Looper looper; public HandlerThread(String n){super(n);} public synchronized void start(){looper=new Looper(getName());}
public Looper getLooper(){return looper;} public boolean quitSafely(){looper.executor.shutdown();return true;} }
""",
    "android/os/Handler.java": """package android.os; import java.util.concurrent.*; import java.util.*;
public class Handler {final Looper looper; final Map<Runnable,ScheduledFuture<?>> delayed=new ConcurrentHashMap<>();
public Handler(Looper l){looper=l;} public void handleMessage(Message m){}
public boolean post(Runnable r){looper.executor.execute(r);return true;}
public boolean postDelayed(Runnable r,long ms){delayed.put(r,looper.executor.schedule(r,ms,TimeUnit.MILLISECONDS));return true;}
public void removeCallbacks(Runnable r){ScheduledFuture<?> f=delayed.remove(r);if(f!=null)f.cancel(false);}}
""",
    "android/os/Bundle.java": """package android.os; import java.util.*;
public class Bundle {final Map<String,Object> values=new HashMap<>(); public Bundle(){} public Bundle(Bundle b){values.putAll(b.values);}
public void putString(String k,String v){values.put(k,v);} public String getString(String k){return (String)values.get(k);}
public String getString(String k,String fallback){String v=getString(k);return v==null?fallback:v;}
public void putLong(String k,long v){values.put(k,v);} public long getLong(String k){Object v=values.get(k);return v==null?0:(Long)v;}
public void putInt(String k,int v){values.put(k,v);} public int getInt(String k){Object v=values.get(k);return v==null?0:(Integer)v;}
public void putBoolean(String k,boolean v){values.put(k,v);} public boolean getBoolean(String k){return Boolean.TRUE.equals(values.get(k));}
public void putParcelable(String k,Parcelable v){values.put(k,v);} @SuppressWarnings("unchecked") public <T extends Parcelable>T getParcelable(String k){return (T)values.get(k);}
Bundle copied()throws java.io.IOException {Bundle b=new Bundle(this);for(String k:values.keySet())if(values.get(k) instanceof ParcelFileDescriptor)b.values.put(k,ParcelFileDescriptor.dup(((ParcelFileDescriptor)values.get(k)).getFileDescriptor()));return b;}}
""",
    "android/os/Message.java": """package android.os; public final class Message {public int what,sendingUid;public Messenger replyTo;private Bundle data;
public static Message obtain(Object o,int what){Message m=new Message();m.what=what;return m;}public void setData(Bundle b){data=b;}public Bundle getData(){return data==null?new Bundle():data;}}
""",
    "android/os/Messenger.java": """package android.os; import java.util.*;import java.util.concurrent.*;
public final class Messenger {final LocalBinder binder;
public Messenger(Handler handler){binder=new LocalBinder(handler);}public Messenger(IBinder b){binder=(LocalBinder)b;}
public IBinder getBinder(){return binder;}public void send(Message m)throws RemoteException {
if(binder.dead)throw new RemoteException();Message copy=Message.obtain(null,m.what);copy.sendingUid=Process.myUid();copy.replyTo=m.replyTo;
try{copy.setData(m.getData().copied());binder.handler.post(()->binder.handler.handleMessage(copy));}catch(Exception e){throw new RemoteException();}}
public static final class LocalBinder implements IBinder {final Handler handler;volatile boolean dead;final List<DeathRecipient> deaths=new CopyOnWriteArrayList<>();
LocalBinder(Handler h){handler=h;}public void linkToDeath(DeathRecipient d,int f)throws RemoteException{if(dead)throw new RemoteException();deaths.add(d);}public boolean unlinkToDeath(DeathRecipient d,int f){return deaths.remove(d);}
public void die(){dead=true;for(DeathRecipient d:deaths)d.binderDied();}}
}
""",
    "android/os/ParcelFileDescriptor.java": """package android.os;
import java.io.*;import java.net.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
public final class ParcelFileDescriptor implements Parcelable,Closeable {
public static final Map<FileDescriptor,End> ENDS=new ConcurrentHashMap<>();static final AtomicInteger live=new AtomicInteger();
final FileDescriptor fd=new FileDescriptor();final End end;final AtomicBoolean closed=new AtomicBoolean();
public static final class End {public final Socket socket;final AtomicInteger refs=new AtomicInteger();End(Socket s){socket=s;}}
ParcelFileDescriptor(End e){end=e;e.refs.incrementAndGet();ENDS.put(fd,e);live.incrementAndGet();}
public FileDescriptor getFileDescriptor(){return fd;} public static int liveCount(){return live.get();}
public static ParcelFileDescriptor dup(FileDescriptor fd)throws IOException {End e=ENDS.get(fd);if(e==null)throw new IOException("closed FD");return new ParcelFileDescriptor(e);}
public static ParcelFileDescriptor[] createSocketPair()throws IOException {try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){Socket a=new Socket(InetAddress.getLoopbackAddress(),server.getLocalPort());Socket b=server.accept();return new ParcelFileDescriptor[]{new ParcelFileDescriptor(new End(a)),new ParcelFileDescriptor(new End(b))};}}
public void close()throws IOException{if(closed.compareAndSet(false,true)){ENDS.remove(fd);live.decrementAndGet();if(end.refs.decrementAndGet()==0)end.socket.close();}}
public static final class AutoCloseInputStream extends InputStream{final ParcelFileDescriptor p;final InputStream in;public AutoCloseInputStream(ParcelFileDescriptor p){this.p=p;try{in=p.end.socket.getInputStream();}catch(IOException e){throw new RuntimeException(e);}}public int read()throws IOException{return in.read();}public int read(byte[] b,int o,int l)throws IOException{return in.read(b,o,l);}public void close()throws IOException{p.close();}}
public static final class AutoCloseOutputStream extends OutputStream{final ParcelFileDescriptor p;final OutputStream out;public AutoCloseOutputStream(ParcelFileDescriptor p){this.p=p;try{out=p.end.socket.getOutputStream();}catch(IOException e){throw new RuntimeException(e);}}public void write(int b)throws IOException{out.write(b);}public void write(byte[] b,int o,int l)throws IOException{out.write(b,o,l);}public void close()throws IOException{p.close();}}
}
""",
    "android/system/ErrnoException.java": "package android.system; public class ErrnoException extends Exception {}",
    "android/system/OsConstants.java": "package android.system; public final class OsConstants {public static final int SHUT_WR=1,SHUT_RDWR=2,SOL_SOCKET=1,SO_RCVTIMEO=20,SO_SNDTIMEO=21;}",
    "android/system/StructTimeval.java": "package android.system; public final class StructTimeval {public long ms;public static StructTimeval fromMillis(long x){StructTimeval t=new StructTimeval();t.ms=x;return t;}}",
    "android/system/Os.java": """package android.system; import java.io.*;import android.os.ParcelFileDescriptor;
public final class Os {public static void chmod(String p,int m)throws ErrnoException{}
public static void shutdown(FileDescriptor fd,int how)throws ErrnoException {ParcelFileDescriptor.End e=ParcelFileDescriptor.ENDS.get(fd);if(e==null)throw new ErrnoException();try{if(how==OsConstants.SHUT_WR)e.socket.shutdownOutput();else e.socket.close();}catch(IOException x){throw new ErrnoException();}}
public static void setsockoptTimeval(FileDescriptor fd,int l,int o,StructTimeval t)throws ErrnoException {try{if(o==OsConstants.SO_RCVTIMEO)ParcelFileDescriptor.ENDS.get(fd).socket.setSoTimeout((int)t.ms);}catch(IOException x){throw new ErrnoException();}}}
""",
    "android/content/pm/ApplicationInfo.java": 'package android.content.pm;public class ApplicationInfo {public String processName="fixture";}',
    "android/content/ComponentName.java": "package android.content; public class ComponentName {}",
    "android/content/Intent.java": "package android.content;public class Intent {public final Class<?> target;String action;public Intent(Context c,Class<?> t){target=t;}public Intent setAction(String a){action=a;return this;}public String getAction(){return action;}}",
    "android/content/ServiceConnection.java": "package android.content;import android.os.IBinder;public interface ServiceConnection {void onServiceConnected(ComponentName c,IBinder b);void onServiceDisconnected(ComponentName c);void onBindingDied(ComponentName c);void onNullBinding(ComponentName c);}",
    "android/content/Context.java": """package android.content;import android.app.*;import android.content.pm.ApplicationInfo;import java.io.*;
public class Context {public static final int BIND_AUTO_CREATE=1;public static final String NOTIFICATION_SERVICE="notification";public static volatile Service active;static int bindings;
public Context getApplicationContext(){return this;}public ComponentName startForegroundService(Intent i){try{Service s=active;if(s==null){s=(Service)i.target.newInstance();active=s;s.onCreate();}s.onStartCommand(i,0,1);return new ComponentName();}catch(Exception e){throw new RuntimeException(e);}}
public boolean bindService(Intent i,ServiceConnection c,int f){bindings++;c.onServiceConnected(new ComponentName(),active.onBind(i));return true;}
public void unbindService(ServiceConnection c){if(--bindings==0&&active!=null)active.onUnbind(null);}public boolean stopService(Intent i){if(active!=null)active.onDestroy();return true;}
public Object getSystemService(String n){return new NotificationManager();}public ApplicationInfo getApplicationInfo(){return new ApplicationInfo();}
public File getNoBackupFilesDir(){File f=new File(System.getProperty("java.io.tmpdir"),"bitchord-overlay-fixture");f.mkdirs();return f;}}
""",
    "android/app/Application.java": 'package android.app;public class Application {public static volatile String processName="fixture:bitchord_i2p";public static String getProcessName(){return processName;}}',
    "android/app/Service.java": """package android.app;import android.content.*;import android.os.*;
public abstract class Service extends Context {public static final int START_NOT_STICKY=2,STOP_FOREGROUND_REMOVE=1;
public volatile boolean foregroundStopped,selfStopped;
public void onCreate(){}public int onStartCommand(Intent i,int f,int s){return 2;}public abstract IBinder onBind(Intent i);public boolean onUnbind(Intent i){return false;}public void onDestroy(){}
public void onTimeout(int id,int type){}public void startForeground(int id,Notification n){}public void stopForeground(int f){foregroundStopped=true;}public void stopSelf(){selfStopped=true;Context.active=null;}public Looper getMainLooper(){return Looper.getMainLooper();}}
""",
    "android/app/NotificationChannel.java": "package android.app;public class NotificationChannel {public NotificationChannel(String a,String b,int c){}}",
    "android/app/NotificationManager.java": "package android.app;public class NotificationManager {public static final int IMPORTANCE_LOW=2;public void createNotificationChannel(NotificationChannel c){}}",
    "android/app/Notification.java": """package android.app;import android.content.Context;public class Notification {public static final String CATEGORY_SERVICE="service";
public static class Builder {public Builder(Context c,String s){}public Builder setSmallIcon(int x){return this;}public Builder setContentTitle(String s){return this;}public Builder setContentText(String s){return this;}public Builder setCategory(String s){return this;}public Builder setOngoing(boolean b){return this;}public Notification build(){return new Notification();}}}
""",
    "android/R.java": "package android;public final class R {public static final class drawable {public static final int stat_sys_download=1;}}",
    "app/bitchord/soulseek/I2pService.java": """package app.bitchord.soulseek;import java.io.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
public class I2pService extends OverlayService {
public static final AtomicInteger opened=new AtomicInteger(),closed=new AtomicInteger(),stopped=new AtomicInteger();
public static volatile CountDownLatch gate,entered,writeGate,writeEntered;public static volatile boolean discardWrites;
public static final AtomicInteger bytesWritten=new AtomicInteger();protected String label(){return "I2P";}protected String processSuffix(){return ":bitchord_i2p";}
protected void startRuntime(File home){}protected String runtimeStatus(){return "ready";}protected String runtimeEndpoint(){return "fixture.b32.i2p";}
protected Transport acceptRuntime(int ms)throws IOException{try{Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();}return null;}
protected Transport connectRuntime(String s,int ms)throws IOException {CountDownLatch wait=gate;if(wait!=null){entered.countDown();long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(ms+500);boolean done=false;while(!done&&System.nanoTime()<deadline)try{done=wait.await(10,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){}}opened.incrementAndGet();return new Memory();}
protected void stopRuntime(){if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw new AssertionError("native stop on main");stopped.incrementAndGet();}
static class Memory implements Transport {final BlockingQueue<byte[]> data=new LinkedBlockingQueue<>();final AtomicBoolean done=new AtomicBoolean();final boolean discard=discardWrites;
public int read(byte[] b,int ms)throws IOException {try{byte[] next=data.poll(ms,TimeUnit.MILLISECONDS);if(next==null)throw new java.net.SocketTimeoutException();if(next.length==0)return -1;System.arraycopy(next,0,b,0,next.length);return next.length;}catch(InterruptedException e){throw new IOException(e);}}
public void write(byte[] b,int n,int ms)throws IOException{CountDownLatch wait=writeGate;if(wait!=null){writeEntered.countDown();try{if(!wait.await(ms,TimeUnit.MILLISECONDS))throw new IOException("write timeout");}catch(InterruptedException e){throw new IOException(e);}}bytesWritten.addAndGet(n);if(!discard)data.add(java.util.Arrays.copyOf(b,n));}public void close(){if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw new AssertionError("native close on main");if(done.compareAndSet(false,true)){closed.incrementAndGet();data.add(new byte[0]);}}}}
""",
    "app/bitchord/soulseek/TorService.java": "package app.bitchord.soulseek;public final class TorService extends I2pService {}",
}

with tempfile.TemporaryDirectory(prefix="bitchord-overlay-tests-") as temporary:
    temporary = Path(temporary)
    sources = temporary / "src"
    output = temporary / "classes"
    for relative, body in STUBS.items():
        target = sources / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(body)
    extension = ROOT / "extensions/soulseek/src/main/java/app/bitchord/soulseek"
    subprocess.run(
        [
            str(jdk() / "bin/javac"),
            "--release",
            "8",
            "-Xlint:-options",
            "-d",
            str(output),
            *map(str, sources.rglob("*.java")),
            str(extension / "OverlayClient.java"),
            str(extension / "OverlayService.java"),
            str(Path(__file__).with_name("OverlayBridgeTest.java")),
        ],
        check=True,
    )
    subprocess.run(
        [
            str(jdk() / "bin/java"),
            "-cp",
            str(output),
            "app.bitchord.soulseek.OverlayBridgeTest",
        ],
        check=True,
        timeout=30,
    )

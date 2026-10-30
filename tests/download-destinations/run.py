#!/usr/bin/env python3
"""Check explicit immutable download destination policy without Android or network operations."""

from pathlib import Path
import os
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from buildenv import TOOLS, jdk

with tempfile.TemporaryDirectory(prefix="bitchord-destination-tests-") as temporary:
    extension = ROOT / "extensions/soulseek/src/main/java/app/bitchord/soulseek"
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
            temporary,
            str(extension / "DownloadDestinations.java"),
            str(extension / "DownloadDestinationActivity.java"),
            str(Path(__file__).with_name("DestinationPolicyTest.java")),
        ],
        check=True,
    )
    subprocess.run(
        [
            str(jdk() / "bin/java"),
            "-cp",
            temporary + os.pathsep + classpath,
            "app.bitchord.soulseek.DestinationPolicyTest",
        ],
        check=True,
    )

    fixtures = {
        "android/content/SharedPreferences.java": """package android.content;import java.util.*;public final class SharedPreferences {
final Map<String,Object> data=new HashMap<>();public boolean getBoolean(String k,boolean f){Object v=data.get(k);return v==null?f:(Boolean)v;}
public String getString(String k,String f){Object v=data.get(k);return v==null?f:(String)v;}public long getLong(String k,long f){Object v=data.get(k);return v==null?f:(Long)v;}
public Editor edit(){return new Editor();}public final class Editor{final Map<String,Object> next=new HashMap<>();public Editor putString(String k,String v){next.put(k,v);return this;}public Editor putLong(String k,long v){next.put(k,v);return this;}public Editor putBoolean(String k,boolean v){next.put(k,v);return this;}public boolean commit(){data.putAll(next);return true;}}}
""",
        "android/content/Context.java": """package android.content;public class Context {public static final int MODE_PRIVATE=0;
public final ContentResolver resolver=new ContentResolver();final SharedPreferences prefs=new SharedPreferences();
public SharedPreferences getSharedPreferences(String n,int m){return prefs;}public ContentResolver getContentResolver(){return resolver;}public ClassLoader getClassLoader(){return getClass().getClassLoader();}}
""",
        "android/content/ContentResolver.java": """package android.content;import android.net.Uri;import android.database.Cursor;import java.util.*;
public class ContentResolver {public boolean queryFailure,cursorFailure,write=true;
public List<UriPermission> getPersistedUriPermissions(){return Arrays.asList(new UriPermission(Uri.parse("content://fixture/tree/downloads"),write));}
public Cursor query(Uri u,String[] p,String s,String[] a,String order){if(queryFailure)throw new IllegalStateException("provider query failed");return new Cursor(){public boolean moveToFirst(){return true;}public String getString(int i){if(cursorFailure)throw new IllegalStateException("provider cursor failed");return "vnd.android.document/directory";}public int getInt(int i){return 1;}public void close(){}};}}
""",
        "android/content/UriPermission.java": "package android.content;import android.net.Uri;public final class UriPermission {final Uri uri;final boolean write;public UriPermission(Uri u,boolean w){uri=u;write=w;}public Uri getUri(){return uri;}public boolean isReadPermission(){return true;}public boolean isWritePermission(){return write;}}",
        "android/content/Intent.java": "package android.content;public final class Intent {public Intent(Context c,Class<?> target){}}",
        "android/app/Activity.java": "package android.app;public class Activity extends android.content.Context {public int launches;public void startActivity(android.content.Intent i){launches++;}}",
        "android/net/Uri.java": "package android.net;public final class Uri {final String text;private Uri(String t){text=t;}public static Uri parse(String t){return new Uri(t);}public String toString(){return text;}public boolean equals(Object o){return o instanceof Uri&&text.equals(((Uri)o).text);}public int hashCode(){return text.hashCode();}}",
        "android/database/Cursor.java": "package android.database;public interface Cursor extends AutoCloseable {boolean moveToFirst();String getString(int c);int getInt(int c);void close();}",
        "android/provider/DocumentsContract.java": 'package android.provider;import android.net.Uri;public final class DocumentsContract {public static boolean isTreeUri(Uri u){return u.toString().startsWith("content://")&&u.toString().contains("/tree/");}public static String getTreeDocumentId(Uri u){return "downloads";}public static Uri buildDocumentUriUsingTree(Uri u,String id){return u;}public static final class Document {public static final String COLUMN_MIME_TYPE="mime",COLUMN_FLAGS="flags",MIME_TYPE_DIR="vnd.android.document/directory";public static final int FLAG_DIR_SUPPORTS_CREATE=1;}}',
        "app/bitchord/soulseek/DownloadDestinationActivity.java": "package app.bitchord.soulseek;public final class DownloadDestinationActivity{}",
    }
    fixture_source = Path(temporary) / "fixture-source"
    fixture_output = Path(temporary) / "fixture-classes"
    for relative, body in fixtures.items():
        target = fixture_source / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(body)
    subprocess.run(
        [
            str(jdk() / "bin/javac"),
            "--release",
            "8",
            "-Xlint:-options",
            "-cp",
            str(TOOLS / "json.jar"),
            "-d",
            str(fixture_output),
            *map(str, fixture_source.rglob("*.java")),
            str(extension / "DownloadDestinations.java"),
            str(Path(__file__).with_name("PhoneFolderFailureTest.java")),
        ],
        check=True,
    )
    subprocess.run(
        [
            str(jdk() / "bin/java"),
            "-cp",
            str(fixture_output) + os.pathsep + str(TOOLS / "json.jar"),
            "app.bitchord.soulseek.PhoneFolderFailureTest",
        ],
        check=True,
    )

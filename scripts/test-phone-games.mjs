import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdtemp, rm, mkdir, writeFile, readdir, access } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { tmpdir, homedir } from 'node:os';

// JVM tests use synthetic ROMs and an authenticated loopback receiver, never user media/devices.
const run = promisify(execFile);
const root = await mkdtemp(join(tmpdir(), 'luma-phone-games-'));
const bundled = '/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin';
const bin = async name => { const candidate = join(bundled, name); try { await access(candidate); return candidate; } catch { return name; } };
async function findJson() {
  if (process.env.JSON_JAR) return process.env.JSON_JAR;
  for (const base of [resolve('android/.gradle/caches/modules-2/files-2.1/org.json/json'), resolve('android-receiver/.gradle/caches/modules-2/files-2.1/org.json/json'), join(homedir(), '.gradle/caches/modules-2/files-2.1/org.json/json')]) {
    try { for (const version of await readdir(base)) for (const hash of await readdir(join(base, version))) for (const file of await readdir(join(base, version, hash))) if (file.endsWith('.jar')) return join(base, version, hash, file); } catch { }
  }
  throw new Error('Set JSON_JAR to an org.json JVM jar, or run receiver unit tests to cache it.');
}
try {
  const json = await findJson();
  await run(await bin('javac'), ['--release', '17', '-cp', json, '-d', root,
    resolve('android/app/src/main/java/dev/framextv/lumaremote/GamesLibrary.java'),
    resolve('android/backend-tests/GamesLibraryTest.java')]);
  const { stdout } = await run(await bin('java'), ['-cp', `${root}:${json}`, 'dev.framextv.lumaremote.GamesLibraryTest', join(root, 'library')]);
  process.stdout.write(stdout);
  const stubs = {
    'android/content/Context.java': `package android.content; import java.io.File; public class Context { final File root; public Context(File root) {this.root=root;} public Context getApplicationContext(){return this;} public File getFilesDir(){return root;} public ContentResolver getContentResolver(){return new ContentResolver();} }`,
    'android/content/ContentResolver.java': `package android.content; import android.net.Uri; import android.database.Cursor; import java.io.*; public class ContentResolver { public Cursor query(Uri u,String[] p,String s,String[] a,String o){return null;} public InputStream openInputStream(Uri u) throws Exception{return new FileInputStream(new File(java.net.URI.create(u.toString())));} }`,
    'android/database/Cursor.java': `package android.database; public interface Cursor extends AutoCloseable { boolean moveToFirst(); int getColumnIndex(String name); String getString(int column); boolean isNull(int column); long getLong(int column); void close(); }`,
    'android/net/Uri.java': `package android.net; public class Uri { private final java.net.URI u; private Uri(String s){u=java.net.URI.create(s);} public static Uri parse(String s){return new Uri(s);} public String getScheme(){return u.getScheme();} public String getLastPathSegment(){return new java.io.File(u.getPath()).getName();} public String toString(){return u.toString();} }`,
    'android/os/Looper.java': `package android.os; public class Looper {public static Looper getMainLooper(){return new Looper();}}`,
    'android/os/Handler.java': `package android.os; public class Handler {public Handler(Looper l){} public boolean post(Runnable r){r.run();return true;} public void removeCallbacksAndMessages(Object o){} }`,
    'android/os/SystemClock.java': `package android.os; public class SystemClock {public static long elapsedRealtime(){return System.nanoTime()/1000000;}}`,
    'android/provider/OpenableColumns.java': `package android.provider; public interface OpenableColumns {String DISPLAY_NAME="name", SIZE="size";}`,
    'android/util/Base64.java': `package android.util; public class Base64 {public static final int NO_WRAP=2; public static String encodeToString(byte[] b,int o,int n,int f){return java.util.Base64.getEncoder().encodeToString(java.util.Arrays.copyOfRange(b,o,o+n));} public static byte[] decode(String s,int f){return java.util.Base64.getDecoder().decode(s);}}`,
    'dev/framextv/lumaremote/NativeBackend.java': `package dev.framextv.lumaremote; public class NativeBackend {public static class Endpoint {final String host,token;final int port; Endpoint(String h,int p,String t){host=h;port=p;token=t;}}}`,
    'dev/framextv/lumaremote/ConnectionService.java': `package dev.framextv.lumaremote; import org.json.JSONObject; public class ConnectionService {public interface Callback {void success(JSONObject result);void failure(String code,String message);}}`,
  };
  const paths = [];
  for (const [name, source] of Object.entries(stubs)) { const path = join(root, 'stubs', name); await mkdir(path.slice(0,path.lastIndexOf('/')), {recursive:true}); await writeFile(path,source); paths.push(path); }
  await run(await bin('javac'), ['--release','17','-cp',`${root}:${json}`,'-d',root,...paths,
    resolve('android/app/src/main/java/dev/framextv/lumaremote/GamesManager.java'),resolve('android/backend-tests/GamesSyncTest.java')]);
  const sync = await run(await bin('java'), ['-cp',`${root}:${json}`,'dev.framextv.lumaremote.GamesSyncTest',join(root,'sync')], {timeout:45000});
  process.stdout.write(sync.stdout); if (sync.stderr) process.stderr.write(sync.stderr);
} finally { await rm(root, { recursive: true, force: true }); }

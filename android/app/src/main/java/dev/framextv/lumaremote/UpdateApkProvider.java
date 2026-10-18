package dev.framextv.lumaremote;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.UUID;

/** One temporary, verified APK. No directory, arbitrary path, or write access. */
public final class UpdateApkProvider extends ContentProvider {
    private static Uri exposedUri;
    private static File exposedFile;
    private static String expectedHash;
    private static long expectedSize, expires;
    @Override public boolean onCreate() { return true; }
    static synchronized Uri expose(Context context, File file, String hash, long size) throws Exception {
        File allowed = new File(context.getCacheDir(), "updates/verified.apk");
        if (!allowed.getCanonicalFile().equals(file.getCanonicalFile()) || !file.isFile() || size != file.length()
                || size <= 0 || size > UpdateManager.MAX_APK_BYTES || !hash.equals(UpdateManager.sha256(file))) throw new FileNotFoundException("Verified update unavailable");
        revoke(context);
        exposedUri = new Uri.Builder().scheme("content").authority(context.getPackageName() + ".updates")
            .appendPath("apk").appendPath(UUID.randomUUID().toString()).appendPath("luma-remote.apk").build();
        exposedFile = file; expectedHash = hash; expectedSize = size; expires = SystemClock.elapsedRealtime() + 30L * 60 * 1000;
        return exposedUri;
    }
    static synchronized void revoke(Context context) {
        if (exposedUri != null) context.revokeUriPermission(exposedUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        exposedUri = null; exposedFile = null; expectedHash = null; expectedSize = 0; expires = 0;
    }
    private static synchronized File checked(Uri uri, boolean hash) throws FileNotFoundException {
        try {
            if (exposedUri == null || !exposedUri.equals(uri) || exposedFile == null || SystemClock.elapsedRealtime() >= expires
                    || !exposedFile.isFile() || exposedFile.length() != expectedSize || hash && !expectedHash.equals(UpdateManager.sha256(exposedFile))) throw new Exception();
            return exposedFile;
        } catch (Exception unavailable) { throw new FileNotFoundException("Verified update unavailable"); }
    }
    @Override public String getType(Uri uri) {
        try { checked(uri, false); return "application/vnd.android.package-archive"; }
        catch (FileNotFoundException invalid) { return null; }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only update");
        synchronized (UpdateApkProvider.class) {
            return ParcelFileDescriptor.open(checked(uri, true), ParcelFileDescriptor.MODE_READ_ONLY);
        }
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File file = checked(uri, false);
            String[] columns = projection == null ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor cursor = new MatrixCursor(columns); Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = "luma-remote.apk";
                else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
            }
            cursor.addRow(row); return cursor;
        } catch (FileNotFoundException invalid) { return null; }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read-only update"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("Read-only update"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("Read-only update"); }
}

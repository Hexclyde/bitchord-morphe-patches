package app.bitchord.telegram;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** Read-only URI grants for individual support ZIP attachments. */
public final class DiagnosticFiles extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    static Uri uri(android.content.Context context, File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(context.getPackageName() + ".diagnostics")
                .appendPath(file.getName())
                .build();
    }

    private File file(Uri uri) throws FileNotFoundException {
        if (getContext() == null
                || !"content".equals(uri.getScheme())
                || !(getContext().getPackageName() + ".diagnostics").equals(uri.getAuthority())
                || uri.getQuery() != null
                || uri.getFragment() != null
                || uri.getPathSegments().size() != 1
                || !uri.getLastPathSegment().matches("bitchord-diagnostics-[a-f0-9]{32}\\.zip"))
            throw new FileNotFoundException("Invalid diagnostic attachment");
        File directory = new File(getContext().getCacheDir(), "diagnostic-exports");
        File file = new File(directory, uri.getLastPathSegment());
        try {
            if (!file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile())
                    || java.nio.file.Files.isSymbolicLink(file.toPath())
                    || !file.isFile()
                    || System.currentTimeMillis() - file.lastModified()
                            > DiagnosticArchive.MAX_AGE_MS)
                throw new FileNotFoundException("Diagnostic attachment has expired");
        } catch (java.io.IOException invalid) {
            throw new FileNotFoundException("Diagnostic attachment is unavailable");
        }
        return file;
    }

    @Override
    public String getType(Uri uri) {
        try {
            file(uri);
            return "application/zip";
        } catch (FileNotFoundException missing) {
            return null;
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode))
            throw new FileNotFoundException("Diagnostic attachments are read only");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(
            Uri uri, String[] projection, String selection, String[] args, String sort) {
        try {
            File file = file(uri);
            String[] columns =
                    projection == null
                            ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                            : projection;
            MatrixCursor cursor = new MatrixCursor(columns, 1);
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = file.getName();
                if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
            }
            cursor.addRow(row);
            return cursor;
        } catch (FileNotFoundException missing) {
            return null;
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException("Read only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("Read only");
    }
}

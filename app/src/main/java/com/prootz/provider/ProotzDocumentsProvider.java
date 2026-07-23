package com.prootz.provider;

import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Point;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;

import com.prootz.R;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.LinkedList;

/**
 * Exposes prootz's files/ directory (including rootfs) to external file managers
 * via Android's Storage Access Framework (SAF).
 *
 * File managers like MT Manager, Solid Explorer, or the system Files app can
 * browse the rootfs by adding this as a "document provider" or "local storage".
 */
public class ProotzDocumentsProvider extends DocumentsProvider {

    private static final String ALL_MIME_TYPES = "*/*";

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[]{
        Root.COLUMN_ROOT_ID, Root.COLUMN_MIME_TYPES, Root.COLUMN_FLAGS,
        Root.COLUMN_ICON, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
        Root.COLUMN_DOCUMENT_ID, Root.COLUMN_AVAILABLE_BYTES
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[]{
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_DISPLAY_NAME, Document.COLUMN_LAST_MODIFIED,
        Document.COLUMN_FLAGS, Document.COLUMN_SIZE
    };

    private File baseDir() {
        return getContext().getFilesDir();
    }

    private String getDocIdForFile(File file) {
        return file.getAbsolutePath();
    }

    private File getFileForDocId(String docId) throws FileNotFoundException {
        File f = new File(docId);
        if (!f.exists()) throw new FileNotFoundException(docId + " not found");
        return f;
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_ROOT_PROJECTION);
        File base = baseDir();
        MatrixCursor.RowBuilder row = result.newRow();
        row.add(Root.COLUMN_ROOT_ID, getDocIdForFile(base));
        row.add(Root.COLUMN_DOCUMENT_ID, getDocIdForFile(base));
        row.add(Root.COLUMN_SUMMARY, null);
        row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_SEARCH | Root.FLAG_SUPPORTS_IS_CHILD);
        row.add(Root.COLUMN_TITLE, "prootz");
        row.add(Root.COLUMN_MIME_TYPES, ALL_MIME_TYPES);
        row.add(Root.COLUMN_AVAILABLE_BYTES, base.getFreeSpace());
        row.add(Root.COLUMN_ICON, R.mipmap.ic_launcher);
        return result;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        includeFile(result, documentId, null);
        return result;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        File parent = getFileForDocId(parentDocumentId);
        File[] children = parent.listFiles();
        if (children != null) {
            for (File file : children) {
                includeFile(result, null, file);
            }
        }
        return result;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        File file = getFileForDocId(documentId);
        int flags = ParcelFileDescriptor.parseMode(mode);
        return ParcelFileDescriptor.open(file, flags);
    }

    @Override
    public boolean onCreate() { return true; }

    private void includeFile(MatrixCursor result, String docId, File file) {
        if (docId == null && file != null) docId = getDocIdForFile(file);
        if (docId == null) return;
        if (file == null) { try { file = getFileForDocId(docId); } catch (FileNotFoundException e) { return; } }

        int flags = 0;
        if (file.canWrite()) flags |= Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME;
        if (file.isDirectory()) flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
        if (file.isFile()) flags |= Document.FLAG_SUPPORTS_WRITE;

        String mime = file.isDirectory() ? Document.MIME_TYPE_DIR : getMime(file.getName());

        MatrixCursor.RowBuilder row = result.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, docId);
        row.add(Document.COLUMN_DISPLAY_NAME, file.getName());
        row.add(Document.COLUMN_MIME_TYPE, mime);
        row.add(Document.COLUMN_WRITE_URI, null);
        row.add(Document.COLUMN_LAST_MODIFIED, file.lastModified());
        row.add(Document.COLUMN_FLAGS, flags);
        row.add(Document.COLUMN_SIZE, file.isFile() ? file.length() : null);
    }

    private String getMime(String name) {
        String ext = name.substring(name.lastIndexOf('.') + 1);
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime != null ? mime : "application/octet-stream";
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        File file = getFileForDocId(documentId);
        return file.isDirectory() ? Document.MIME_TYPE_DIR : getMime(file.getName());
    }

    @Override
    public Cursor querySearchDocuments(String rootId, String query, String[] projection)
            throws FileNotFoundException {
        MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        File base = getFileForDocId(rootId);
        LinkedList<File> stack = new LinkedList<>();
        stack.add(base);
        while (!stack.isEmpty()) {
            File dir = stack.removeFirst();
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (f.getName().toLowerCase().contains(query.toLowerCase())) {
                    includeFile(result, getDocIdForFile(f), f);
                }
                if (f.isDirectory()) stack.add(f);
            }
        }
        return result;
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        File file = getFileForDocId(documentId);
        deleteRecursive(file);
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        File parent = getFileForDocId(parentDocumentId);
        File f = new File(parent, displayName);
        try {
            if (Document.MIME_TYPE_DIR.equals(mimeType)) {
                f.mkdirs();
            } else {
                f.createNewFile();
            }
        } catch (IOException e) {
            throw new FileNotFoundException("Failed to create: " + displayName);
        }
        return getDocIdForFile(f);
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        File file = getFileForDocId(documentId);
        File parent = file.getParentFile();
        File renamed = new File(parent, displayName);
        file.renameTo(renamed);
        return getDocIdForFile(renamed);
    }

    @Override
    public AssetFileDescriptor openDocumentThumbnail(String documentId, Point sizeHint,
                                                     CancellationSignal signal) throws FileNotFoundException {
        return null;
    }
}

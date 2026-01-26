package patch;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public class ProxyDocumentsProvider extends DocumentsProvider {
    private static final String[] ROOT_PROJECTION = new String[]{
        "root_id", "document_id", "summary", "flags", "title", "mime_types", "icon"
    };
    private static final String[] DOCUMENT_PROJECTION = new String[]{
        "document_id", "_display_name", "_size", "mime_type", "last_modified", "flags"
    };
    
    private String rootDocumentId;
    private File baseDirectory;

    private boolean deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursive(child)) return false;
                }
            }
        }
        return file.delete();
    }

    private String getMimeType(File file) {
        if (file.isDirectory()) return "vnd.android.document/directory";
        String name = file.getName();
        int lastDot = name.lastIndexOf('.');
        if (lastDot >= 0) {
            String extension = name.substring(lastDot + 1).toLowerCase();
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }

    @Override
    public final void attachInfo(Context context, ProviderInfo info) {
        this.rootDocumentId = context.getPackageName();
        this.baseDirectory = new File(context.getFilesDir(), "proxy");
        if (!this.baseDirectory.exists()) {
            this.baseDirectory.mkdirs();
        }
        try {
            String libraryPath = context.getApplicationInfo().nativeLibraryDir.concat("/libproxy.so");
            String[] command = {libraryPath, "-c", "config.toml"};
            Runtime.getRuntime().exec(command, null, this.baseDirectory);
        } catch (Exception ignored) {}
        super.attachInfo(context, info);
    }

    private File getFileForDocId(String docId, boolean mustExist) throws FileNotFoundException {
        File target;
        if (docId.equals(this.rootDocumentId)) {
            target = this.baseDirectory;
        } else {
            int slashIndex = docId.indexOf('/');
            String relativePath = (slashIndex == -1) ? docId : docId.substring(slashIndex + 1);
            target = new File(this.baseDirectory, relativePath);
        }

        if (mustExist && !target.exists()) {
            throw new FileNotFoundException(docId + " not found");
        }
        return target;
    }

    @Override
    public final String createDocument(String parentId, String mimeType, String displayName) throws FileNotFoundException {
        File parent = getFileForDocId(parentId, true);
        File file = new File(parent, displayName);
        try {
            boolean success = "vnd.android.document/directory".equals(mimeType) ? file.mkdir() : file.createNewFile();
            if (success) {
                return parentId + (parentId.endsWith("/") ? "" : "/") + file.getName();
            }
        } catch (IOException ignored) {}
        throw new FileNotFoundException("Failed to create document");
    }

    private void includeFile(MatrixCursor cursor, String docId, File file) {
        if (file == null) {
            try { file = getFileForDocId(docId, true); } catch (Exception e) { return; }
        }

        int flags = 0;
        if (file.canWrite()) {
            flags |= (file.isDirectory() ? 8 : 2);
        }
        File parent = file.getParentFile();
        if (parent != null && parent.canWrite()) {
            flags |= 68;
        }

        String displayName = file.getPath().equals(this.baseDirectory.getPath()) ? "proxy" : file.getName();
        cursor.newRow()
                .add("document_id", docId)
                .add("_display_name", displayName)
                .add("_size", file.length())
                .add("mime_type", getMimeType(file))
                .add("last_modified", file.lastModified())
                .add("flags", flags);
    }

    @Override
    public final void deleteDocument(String docId) throws FileNotFoundException {
        if (!deleteRecursive(getFileForDocId(docId, true))) {
            throw new FileNotFoundException("Failed to delete document");
        }
    }

    @Override
    public final String getDocumentType(String docId) throws FileNotFoundException {
        return getMimeType(getFileForDocId(docId, true));
    }

    @Override
    public final boolean isChildDocument(String parentId, String childId) {
        return childId.startsWith(parentId);
    }

    @Override
    public final String moveDocument(String sourceId, String sourceParentId, String targetParentId) throws FileNotFoundException {
        File sourceFile = getFileForDocId(sourceId, true);
        File targetDir = getFileForDocId(targetParentId, true);
        File targetFile = new File(targetDir, sourceFile.getName());

        if (!targetFile.exists() && sourceFile.renameTo(targetFile)) {
            return targetParentId + (targetParentId.endsWith("/") ? "" : "/") + targetFile.getName();
        }
        throw new FileNotFoundException("Failed to move document");
    }

    @Override
    public final boolean onCreate() { return true; }

    @Override
    public final ParcelFileDescriptor openDocument(String docId, String mode, CancellationSignal signal) throws FileNotFoundException {
        return ParcelFileDescriptor.open(getFileForDocId(docId, true), ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public final Cursor queryChildDocuments(String parentId, String[] projection, String sortOrder) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOCUMENT_PROJECTION);
        File parent = getFileForDocId(parentId, true);
        File[] children = parent.listFiles();
        if (children != null) {
            for (File child : children) {
                String childDocId = parentId + (parentId.endsWith("/") ? "" : "/") + child.getName();
                includeFile(cursor, childDocId, child);
            }
        }
        return cursor;
    }

    @Override
    public final Cursor queryDocument(String docId, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOCUMENT_PROJECTION);
        includeFile(cursor, docId, null);
        return cursor;
    }

    @Override
    public final Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : ROOT_PROJECTION);
        ApplicationInfo appInfo = getContext().getApplicationInfo();
        cursor.newRow()
                .add("root_id", this.rootDocumentId)
                .add("document_id", this.rootDocumentId)
                .add("summary", this.rootDocumentId)
                .add("flags", 17)
                .add("title", appInfo.loadLabel(getContext().getPackageManager()).toString())
                .add("mime_types", "*/*")
                .add("icon", appInfo.icon);
        return cursor;
    }

    @Override
    public final void removeDocument(String docId, String parentId) throws FileNotFoundException {
        deleteDocument(docId);
    }

    @Override
    public final String renameDocument(String docId, String displayName) throws FileNotFoundException {
        File file = getFileForDocId(docId, true);
        File renamedFile = new File(file.getParentFile(), displayName);
        if (file.renameTo(renamedFile)) {
            int lastSlash = docId.lastIndexOf('/');
            return (lastSlash == -1) ? displayName : docId.substring(0, lastSlash + 1) + displayName;
        }
        throw new FileNotFoundException("Failed to rename document");
    }
}

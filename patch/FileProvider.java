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
import android.system.Os;
import android.webkit.MimeTypeMap;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public class FileProvider extends DocumentsProvider {
    public static final String[] DEFAULT_ROOT_PROJECTION = new String[]{"root_id", "document_id", "summary", "flags", "title", "mime_types", "icon"};
    public static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[]{"document_id", "_display_name", "_size", "mime_type", "last_modified", "flags", "mt_extras"};
    
    public String b;
    public File c;

    public static boolean a(File file) {
        if (file.isDirectory()) {
            File[] listFiles = file.listFiles();
            if (listFiles != null) {
                for (File child : listFiles) {
                    if (!a(child)) return false;
                }
            }
        }
        return file.delete();
    }

    public static String c(File file) {
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
    public final void attachInfo(Context context, ProviderInfo providerInfo) {
        if (providerInfo != null) {
            this.b = providerInfo.authority;
        }
        this.c = new File(context.getFilesDir(), "proxy");
        if (!this.c.exists()) {
            this.c.mkdirs();
        }
        try {
            String libPath = context.getApplicationInfo().nativeLibraryDir.concat("/libproxy.so");
            String[] cmd = {libPath, "-c", "config.toml"};
            Runtime.getRuntime().exec(cmd, null, this.c);
        } catch (Exception ignored) {}
        super.attachInfo(context, providerInfo);
    }

    public final File b(String docId, boolean checkExists) throws FileNotFoundException {
        File target;
        if (docId.equals(this.b)) {
            target = this.c;
        } else {
            int index = docId.indexOf('/');
            String path = (index == -1) ? docId : docId.substring(index + 1);
            target = new File(this.c, path);
        }

        if (checkExists && !target.exists()) {
            throw new FileNotFoundException(docId + " not found");
        }
        return target;
    }

    @Override
    public final Bundle call(String method, String arg, Bundle extras) {
        Bundle result = super.call(method, arg, extras);
        if (result != null || method == null || !method.startsWith("mt:")) return result;

        result = new Bundle();
        try {
            String docId = ((Uri) extras.getParcelable("uri")).getLastPathSegment();
            File file = b(docId, true);

            if ("mt:setLastModified".equals(method)) {
                result.putBoolean("result", file.setLastModified(extras.getLong("time")));
            } else if ("mt:setPermissions".equals(method)) {
                Os.chmod(file.getPath(), extras.getInt("permissions"));
                result.putBoolean("result", true);
            } else {
                result.putBoolean("result", false);
                result.putString("message", "Unsupported method: " + method);
            }
        } catch (Exception e) {
            result.putBoolean("result", false);
            result.putString("message", e.toString());
        }
        return result;
    }

    @Override
    public final String createDocument(String parentId, String mimeType, String displayName) throws FileNotFoundException {
        File parent = b(parentId, true);
        File file = new File(parent, displayName);
        try {
            if ("vnd.android.document/directory".equals(mimeType) ? file.mkdir() : file.createNewFile()) {
                return parentId + (parentId.endsWith("/") ? "" : "/") + file.getName();
            }
        } catch (IOException ignored) {}
        throw new FileNotFoundException("Create failed");
    }

    public final void d(MatrixCursor cursor, String docId, File file) {
        if (file == null) {
            try { file = b(docId, true); } catch (Exception e) { return; }
        }

        int flags = 0;
        if (file.canWrite()) {
            flags |= (file.isDirectory() ? 8 : 2);
        }
        File parent = file.getParentFile();
        if (parent != null && parent.canWrite()) {
            flags |= 68;
        }

        String name = file.getPath().equals(this.c.getPath()) ? "proxy" : file.getName();
        cursor.newRow()
                .add("document_id", docId)
                .add("_display_name", name)
                .add("_size", file.length())
                .add("mime_type", c(file))
                .add("last_modified", file.lastModified())
                .add("flags", flags)
                .add("mt_extras", "33206|1000|1000");
    }

    @Override
    public final void deleteDocument(String docId) throws FileNotFoundException {
        if (!a(b(docId, true))) throw new FileNotFoundException("Delete failed");
    }

    @Override
    public final String getDocumentType(String docId) throws FileNotFoundException {
        return c(b(docId, true));
    }

    @Override
    public final boolean isChildDocument(String parentId, String childId) {
        return childId.startsWith(parentId);
    }

    @Override
    public final String moveDocument(String sourceId, String sourceParentId, String targetParentId) throws FileNotFoundException {
        File source = b(sourceId, true);
        File targetDir = b(targetParentId, true);
        File target = new File(targetDir, source.getName());

        if (!target.exists() && source.renameTo(target)) {
            return targetParentId + (targetParentId.endsWith("/") ? "" : "/") + target.getName();
        }
        throw new FileNotFoundException("Move failed");
    }

    @Override
    public final boolean onCreate() { return true; }

    @Override
    public final ParcelFileDescriptor openDocument(String docId, String mode, CancellationSignal signal) throws FileNotFoundException {
        return ParcelFileDescriptor.open(b(docId, true), ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public final Cursor queryChildDocuments(String parentId, String[] projection, String sortOrder) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        File parent = b(parentId, true);
        File[] files = parent.listFiles();
        if (files != null) {
            for (File f : files) {
                d(cursor, parentId + (parentId.endsWith("/") ? "" : "/") + f.getName(), f);
            }
        }
        return cursor;
    }

    @Override
    public final Cursor queryDocument(String docId, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        d(cursor, docId, null);
        return cursor;
    }

    @Override
    public final Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DEFAULT_ROOT_PROJECTION);
        ApplicationInfo ai = getContext().getApplicationInfo();
        cursor.newRow()
                .add("root_id", this.b)
                .add("document_id", this.b)
                .add("summary", this.b)
                .add("flags", 17)
                .add("title", ai.loadLabel(getContext().getPackageManager()).toString())
                .add("mime_types", "*/*")
                .add("icon", ai.icon);
        return cursor;
    }

    @Override
    public final void removeDocument(String docId, String parentId) throws FileNotFoundException {
        deleteDocument(docId);
    }

    @Override
    public final String renameDocument(String docId, String displayName) throws FileNotFoundException {
        File file = b(docId, true);
        if (file.renameTo(new File(file.getParentFile(), displayName))) {
            int lastSlash = docId.lastIndexOf('/');
            return (lastSlash == -1) ? displayName : docId.substring(0, lastSlash + 1) + displayName;
        }
        throw new FileNotFoundException("Rename failed");
    }
}

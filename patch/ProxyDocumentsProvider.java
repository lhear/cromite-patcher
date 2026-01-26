package patch;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public class ProxyDocumentsProvider extends DocumentsProvider {
    private static final String[] ROOT_PROJECTION = {"root_id", "document_id", "title", "flags", "mime_types"};
    private static final String[] DOC_PROJECTION = {"document_id", "_display_name", "_size", "mime_type", "last_modified", "flags"};
    
    private String rootId;
    private File baseDir;

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        rootId = context.getPackageName();
        baseDir = new File(context.getFilesDir(), "proxy");
        if (!baseDir.exists()) baseDir.mkdirs();
        try {
            String lib = context.getApplicationInfo().nativeLibraryDir + "/libproxy.so";
            Runtime.getRuntime().exec(new String[]{lib, "-c", "config.toml"}, null, baseDir);
        } catch (Exception ignored) {}
        super.attachInfo(context, info);
    }

    private File getFile(String id) throws FileNotFoundException {
        File f = id.equals(rootId) ? baseDir : new File(baseDir, id.substring(id.indexOf('/') + 1));
        if (!f.exists()) throw new FileNotFoundException();
        return f;
    }

    private void addRow(MatrixCursor c, String id, File f) {
        int flags = f.canWrite() ? (f.isDirectory() ? 78 : 70) : 0;
        c.newRow().add("document_id", id)
                .add("_display_name", f.equals(baseDir) ? "proxy" : f.getName())
                .add("_size", f.length())
                .add("mime_type", getMime(f))
                .add("last_modified", f.lastModified())
                .add("flags", flags);
    }

    private String getMime(File f) {
        if (f.isDirectory()) return "vnd.android.document/directory";
        String ext = MimeTypeMap.getFileExtensionFromUrl(f.getName());
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase());
        return mime != null ? mime : "application/octet-stream";
    }

    @Override
    public String createDocument(String pId, String mime, String name) throws FileNotFoundException {
        File f = new File(getFile(pId), name);
        if ("vnd.android.document/directory".equals(mime) ? f.mkdir() : createNew(f)) 
            return pId + "/" + name;
        throw new FileNotFoundException();
    }

    private boolean createNew(File f) {
        try { return f.createNewFile(); } catch (IOException e) { return false; }
    }

    @Override
    public Cursor queryRoots(String[] proj) {
        MatrixCursor c = new MatrixCursor(proj != null ? proj : ROOT_PROJECTION);
        ApplicationInfo ai = getContext().getApplicationInfo();
        c.newRow().add("root_id", rootId).add("document_id", rootId)
                .add("summary", this.rootId).add("icon", appInfo.icon)
                .add("title", ai.loadLabel(getContext().getPackageManager()))
                .add("flags", 17).add("mime_types", "*/*");
        return c;
    }

    @Override
    public Cursor queryDocument(String id, String[] proj) throws FileNotFoundException {
        MatrixCursor c = new MatrixCursor(proj != null ? proj : DOC_PROJECTION);
        addRow(c, id, getFile(id));
        return c;
    }

    @Override
    public Cursor queryChildDocuments(String pId, String[] proj, String sort) throws FileNotFoundException {
        MatrixCursor c = new MatrixCursor(proj != null ? proj : DOC_PROJECTION);
        File[] files = getFile(pId).listFiles();
        if (files != null) for (File f : files) addRow(c, pId + "/" + f.getName(), f);
        return c;
    }

    @Override
    public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal sig) throws FileNotFoundException {
        return ParcelFileDescriptor.open(getFile(id), ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public void deleteDocument(String id) throws FileNotFoundException {
        if (!deleteFile(getFile(id))) throw new FileNotFoundException();
    }

    private boolean deleteFile(File f) {
        if (f.isDirectory()) {
            File[] subs = f.listFiles();
            if (subs != null) for (File s : subs) deleteFile(s);
        }
        return f.delete();
    }

    @Override
    public String renameDocument(String id, String name) throws FileNotFoundException {
        File f = getFile(id);
        File n = new File(f.getParentFile(), name);
        if (f.renameTo(n)) return id.substring(0, Math.max(0, id.lastIndexOf('/') + 1)) + name;
        throw new FileNotFoundException();
    }

    @Override public boolean onCreate() { return true; }
    @Override public boolean isChildDocument(String pId, String cId) { return cId.startsWith(pId); }
}

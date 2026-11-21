package rs.ruffle

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException

/**
 * Local .swf library backed either by a Storage Access Framework tree URI or,
 * on older releases, by a plain directory on shared storage.
 *
 * Games are opened as `file://` URIs from a private copy, so relative asset loads
 * (`url = "data/x.swf"`) resolve against a real filesystem instead of a content URI.
 */
object SwfLibrary {
    private const val PREFS = "swf_library"
    private const val KEY_TREE = "tree_uri"
    private const val KEY_NAME = "tree_name"
    private const val KEY_DIR = "dir_path"

    class Entry(val name: String, val uri: Uri)

    /**
     * Shared-storage folders are readable without SAF only up to Android 10
     * (`requestLegacyExternalStorage`); newer releases need the tree picker.
     */
    fun supportsDirScan(): Boolean = Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q

    fun hasStoragePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    /** Directories tried by the built-in scan, most specific first. */
    fun candidateDirs(): List<File> {
        val root = Environment.getExternalStorageDirectory() ?: return emptyList()
        return listOf(
            File(root, "Download/swf"),
            File(root, "Download/SWF"),
            File(root, "swf"),
            File(root, "Download"),
        ).filter { it.isDirectory && it.canRead() }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun treeUri(context: Context): Uri? =
        prefs(context).getString(KEY_TREE, null)?.takeIf { it.isNotEmpty() }?.let(Uri::parse)

    fun treeName(context: Context): String? = prefs(context).getString(KEY_NAME, null)

    /** Absolute path of the folder picked by the built-in scanner, if any. */
    fun dirPath(context: Context): String? =
        prefs(context).getString(KEY_DIR, null)?.takeIf { it.isNotEmpty() }

    fun dirName(context: Context): String? = dirPath(context)?.let { File(it).name }

    fun setTree(context: Context, uri: Uri?, name: String? = null) {
        prefs(context).edit().apply {
            remove(KEY_DIR)
            if (uri == null) {
                remove(KEY_TREE)
                remove(KEY_NAME)
            } else {
                putString(KEY_TREE, uri.toString())
                putString(KEY_NAME, name ?: uri.lastPathSegment ?: "swf")
            }
        }.commit()
    }

    fun setDir(context: Context, path: String?) {
        prefs(context).edit().apply {
            remove(KEY_TREE)
            remove(KEY_NAME)
            if (path == null) remove(KEY_DIR) else putString(KEY_DIR, path)
        }.commit()
    }

    /** Recursively collects .swf/.spl documents under [tree], sorted by name. */
    fun scan(context: Context, tree: Uri): List<Entry> {
        val found = ArrayList<Entry>()
        val resolver = context.contentResolver
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )

        fun walk(parentId: String, depth: Int) {
            if (depth > 4) return
            val childrenUri =
                DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        walk(docId, depth + 1)
                    } else if (isSwf(name)) {
                        found.add(Entry(name, DocumentsContract.buildDocumentUriUsingTree(tree, docId)))
                    }
                }
            }
        }

        walk(DocumentsContract.getTreeDocumentId(tree), 0)
        found.sortBy { it.name.lowercase() }
        return found
    }

    /** Recursively collects .swf/.spl files under a real directory, sorted by name. */
    fun scanDir(dir: File): List<Entry> {
        val found = ArrayList<Entry>()
        fun walk(current: File, depth: Int) {
            if (depth > 4) return
            for (child in current.listFiles() ?: return) {
                if (child.isDirectory) {
                    walk(child, depth + 1)
                } else if (isSwf(child.name)) {
                    found.add(Entry(child.name, Uri.fromFile(child)))
                }
            }
        }
        walk(dir, 0)
        found.sortBy { it.name.lowercase() }
        return found
    }

    fun isSwf(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".swf") || lower.endsWith(".spl")
    }

    /**
     * Copies [uri] into `filesDir/library` and returns the local file, reusing an
     * existing copy whose size matches.
     */
    @Throws(IOException::class)
    fun copyLocal(context: Context, uri: Uri, displayName: String): File {
        val dir = File(context.filesDir, "library")
        if (!dir.exists() && !dir.mkdirs()) throw IOException("cannot create $dir")
        val safe = displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120)
            .ifEmpty { "game.swf" }
        val target = File(dir, safe)
        val remoteSize = remoteSize(context, uri)
        if (target.exists() && (remoteSize == null || target.length() == remoteSize)) {
            return target
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException("cannot open $uri")
        return target
    }

    private fun remoteSize(context: Context, uri: Uri): Long? = try {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
    } catch (e: Exception) {
        null
    }
}

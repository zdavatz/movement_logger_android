package ch.ywesee.movementlogger.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Mirror downloaded box files (and on-phone GPS logs) into the public
 * Downloads directory so Google Files / any third-party file manager can
 * see and share them. Internal `getExternalFilesDir(...)` is invisible to
 * Google Files on modern Android — this class makes the same bytes show
 * up under `Download/MovementLogger/` instead.
 *
 * On Android 10+ (API 29+) writes go through `MediaStore.Downloads` with
 * `RELATIVE_PATH = "Download/MovementLogger/"`. No runtime permission is
 * needed for files this app creates. On older Android (API ≤ 28) the
 * mirror is written via plain [File] APIs under
 * `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)`,
 * guarded by `WRITE_EXTERNAL_STORAGE` (capped at maxSdkVersion=28 in
 * the manifest).
 *
 * The class is **best-effort**: every write/delete catches and logs;
 * losing the public mirror must never break the canonical internal sync.
 * Per-name [Uri] lookups are cached so repeated appends to the same file
 * don't re-query the provider.
 */
class PublicMirror(private val context: Context) {

    private val uriCache = HashMap<String, Uri>()

    /**
     * Append [bytes] starting at byte offset [base]. If the public mirror
     * is shorter than [base] the resume is misaligned — realign to the
     * current size (so a stale prefix is never extended past [base]).
     * If it's longer than [base] (rotation / re-pull from 0), truncate
     * down to [base]. Mirrors the semantics of `FileSyncCore.appendMirror`.
     */
    fun appendAt(name: String, base: Long, bytes: ByteArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appendAtMediaStore(name, base, bytes)
            } else {
                appendAtLegacy(name, base, bytes)
            }
        } catch (e: Exception) {
            Log.w(TAG, "appendAt $name @${base}+${bytes.size} failed: ${e.message}")
        }
    }

    /**
     * Drop the entry. Used on rotation (local was deleted because it grew
     * past the box's size, indicating a re-used name). A subsequent
     * [appendAt] with base=0 will re-create it.
     */
    fun delete(name: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val uri = synchronized(uriCache) { uriCache.remove(name) } ?: queryUri(name)
                if (uri != null) context.contentResolver.delete(uri, null, null)
            } else {
                legacyFile(name)?.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "delete $name failed: ${e.message}")
        }
    }

    // ---------------- MediaStore (Android 10+) ----------------

    private fun appendAtMediaStore(name: String, base: Long, bytes: ByteArray) {
        val uri = ensureUri(name)
        context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
            FileOutputStream(pfd.fileDescriptor).use { fos ->
                val ch = fos.channel
                val curSize = ch.size()
                if (curSize != base) ch.truncate(base.coerceAtMost(curSize))
                ch.position(ch.size())
                ch.write(ByteBuffer.wrap(bytes))
            }
        } ?: throw IOException("openFileDescriptor(rw) returned null for $name")
    }

    private fun ensureUri(name: String): Uri {
        synchronized(uriCache) { uriCache[name]?.let { return it } }
        val existing = queryUri(name)
        if (existing != null) {
            synchronized(uriCache) { uriCache[name] = existing }
            return existing
        }
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
        }
        val uri = context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv,
        ) ?: throw IOException("MediaStore insert returned null for $name")
        synchronized(uriCache) { uriCache[name] = uri }
        return uri
    }

    private fun queryUri(name: String): Uri? {
        val proj = arrayOf(MediaStore.MediaColumns._ID)
        val sel = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND " +
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?"
        val args = arrayOf(RELATIVE_PATH, name)
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj, sel, args, null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                return ContentUris.withAppendedId(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, id,
                )
            }
        }
        return null
    }

    /**
     * Picking the MIME type matters here: MediaStore enforces extension
     * ↔ MIME consistency and will *rename* the file (e.g. append `.txt`
     * to a `.csv` declared as `text/plain`) if it disagrees. So we map
     * each known extension to its canonical RFC-registered MIME, and
     * fall back to `application/octet-stream` for anything else (which
     * MediaStore leaves alone). The `lowercase()` is just for the
     * lookup; the original display name (and its case) is preserved.
     */
    private fun mimeOf(name: String): String = when (
        name.substringAfterLast('.', "").lowercase()
    ) {
        "csv" -> "text/csv"
        "log", "txt" -> "text/plain"
        "wav" -> "audio/x-wav"
        "mp4", "mov", "m4v" -> "video/mp4"
        "png" -> "image/png"
        else -> "application/octet-stream"
    }

    // ---------------- Legacy (Android ≤ 9) ----------------

    private fun appendAtLegacy(name: String, base: Long, bytes: ByteArray) {
        val f = legacyFile(name) ?: throw IOException("legacy public dir unavailable for $name")
        f.parentFile?.mkdirs()
        RandomAccessFile(f, "rw").use { raf ->
            if (raf.length() != base) raf.setLength(base.coerceAtMost(raf.length()))
            raf.seek(raf.length())
            raf.write(bytes)
        }
    }

    private fun legacyFile(name: String): File? {
        val root = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS,
        ) ?: return null
        return File(File(root, FOLDER), name)
    }

    companion object {
        private const val TAG = "PublicMirror"
        private const val FOLDER = "MovementLogger"
        /**
         * `RELATIVE_PATH` must end with `/` for MediaStore to treat it as
         * a directory. The leading segment is [Environment.DIRECTORY_DOWNLOADS]
         * (`"Download"`); we build the value at class-load time rather
         * than const-folding so we don't bake the value of a Java static
         * field into the bytecode.
         */
        private val RELATIVE_PATH = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/"
    }
}

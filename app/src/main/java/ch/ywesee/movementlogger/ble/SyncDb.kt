package ch.ywesee.movementlogger.ble

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Local SQLite sync-state DB. Android port of the desktop
 * `stbox-viz-gui/src/sync_db.rs` (movement_logger_desktop issues #3/#4).
 *
 * An **audit log** of completed pulls. As of the v0.0.14 live-mirror
 * model the sync *decision* is made by comparing the local mirror file's
 * size to the box's reported size (see `mirrorOffset` / `runSyncDiff` in
 * [FileSyncCore]), which is what makes a continuously-growing log fetch
 * only its new tail. This table is no longer consulted to decide what to
 * fetch; it's kept as a per-box record of "this file reached this size at
 * this time, saved here" for history/debugging.
 *
 * Policy (user decision, desktop v0.0.6, locked): sync is **purely
 * additive** — it never issues DELETE. Nothing on the box is ever removed
 * by a sync.
 *
 * Uses the platform `android.database.sqlite` API directly — no Room / no
 * new Gradle dependency, consistent with the desktop's self-contained
 * `rusqlite bundled` choice and this project's no-extra-deps stance.
 */
class SyncDb private constructor(private val db: SQLiteDatabase) {

    companion object {
        /**
         * `<filesDir>/sqlite/sync.db`.
         *
         * Anchored to the app's private internal `filesDir`, *not* the
         * `getExternalFilesDir(null)` folder where the downloaded CSVs
         * land. The external folder is user-visible/clearable; putting the
         * DB there would let "clear files" wipe sync history. This is the
         * Android analogue of the desktop's "anchored to $HOME, not the
         * download folder" rule. Own `sqlite/` subdir mirrors desktop
         * issue #4's flat-file -> `sqlite/` move.
         */
        fun defaultDbFile(context: Context): File =
            File(File(context.filesDir, "sqlite").apply { mkdirs() }, "sync.db")

        /** Open (creating file + parent dir + schema if missing). Returns
         *  null if SQLite can't open — callers degrade to "nothing synced"
         *  rather than crashing the Sync tab. */
        fun open(context: Context): SyncDb? = try {
            val f = defaultDbFile(context)
            val db = SQLiteDatabase.openOrCreateDatabase(f, null)
            // `size` is part of the primary key on purpose: the firmware
            // reuses session-style names, and a file that grew (new
            // session, same name) must count as a *new* file and be
            // re-pulled rather than silently skipped. Schema is
            // byte-for-byte the desktop's.
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS synced_files (
                    box_id        TEXT    NOT NULL,
                    name          TEXT    NOT NULL,
                    size          INTEGER NOT NULL,
                    downloaded_at TEXT    NOT NULL,
                    local_path    TEXT    NOT NULL,
                    PRIMARY KEY (box_id, name, size)
                );
                """.trimIndent()
            )
            SyncDb(db)
        } catch (_: Exception) {
            null
        }

        private val iso8601: SimpleDateFormat
            get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
    }

    /**
     * Record a successfully-saved file. INSERT OR REPLACE so a re-download
     * of the same triple just refreshes the timestamp / path instead of
     * erroring on the primary key.
     */
    fun markSynced(boxId: String, name: String, size: Long, localPath: String) {
        db.execSQL(
            "INSERT OR REPLACE INTO synced_files " +
                "(box_id, name, size, downloaded_at, local_path) VALUES (?, ?, ?, ?, ?)",
            arrayOf(boxId, name, size, iso8601.format(Date()), localPath)
        )
    }
}

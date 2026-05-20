package ch.ywesee.movementlogger.sync

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent config that survives process death + device reboots — drives the
 * [SyncWorker] background loop. Port of the desktop
 * `~/.movementlogger/config.toml` (`stbox-viz-gui/src/agent_config.rs`):
 *
 *   box_id           → which SensorTile.box to mirror (BLE MAC on Android)
 *   keep_synced      → user toggle: "pull whenever I'm near the box"
 *   log_mode_manual  → last-known box log mode; agent only runs when AUTO
 *
 * GUI is the writer ([FileSyncCore] persists on connect / log-mode events /
 * setKeepSynced); worker is the reader. Backed by SharedPreferences — atomic
 * `apply()` writes are safe across processes for our two-writer model
 * (the worker only reads).
 */
data class AgentConfig(
    /** BLE MAC of the box to auto-mirror, or null if no box has connected yet. */
    val boxId: String?,
    /** User toggle: "Keep synced" — enables both the in-foreground 30 s poll
     *  and the background WorkManager loop. */
    val keepSynced: Boolean,
    /** Last-known box log mode. null = unknown (legacy FW / not yet queried).
     *  Background sync runs only when this is NOT explicitly true — matches
     *  desktop `cfg.log_mode_manual != Some(true)`. */
    val logModeManual: Boolean?,
) {
    /** True iff the background loop should currently be scheduled. */
    val active: Boolean
        get() = keepSynced && boxId != null && logModeManual != true

    companion object {
        private const val PREFS = "movement_logger_agent"
        private const val KEY_BOX_ID = "box_id"
        private const val KEY_KEEP_SYNCED = "keep_synced"
        private const val KEY_LOG_MODE_MANUAL = "log_mode_manual"
        /** Used to encode null logModeManual via a sentinel — SharedPrefs
         *  has no native tri-state Boolean. */
        private const val KEY_LOG_MODE_MANUAL_KNOWN = "log_mode_manual_known"

        fun prefs(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun load(context: Context): AgentConfig {
            val p = prefs(context)
            val mode = if (p.getBoolean(KEY_LOG_MODE_MANUAL_KNOWN, false)) {
                p.getBoolean(KEY_LOG_MODE_MANUAL, false)
            } else null
            return AgentConfig(
                boxId = p.getString(KEY_BOX_ID, null)?.takeIf { it.isNotBlank() },
                keepSynced = p.getBoolean(KEY_KEEP_SYNCED, false),
                logModeManual = mode,
            )
        }

        fun setBoxId(context: Context, boxId: String?) {
            prefs(context).edit().apply {
                if (boxId.isNullOrBlank()) remove(KEY_BOX_ID) else putString(KEY_BOX_ID, boxId)
            }.apply()
        }

        fun setKeepSynced(context: Context, on: Boolean) {
            prefs(context).edit().putBoolean(KEY_KEEP_SYNCED, on).apply()
        }

        fun setLogModeManual(context: Context, manual: Boolean?) {
            prefs(context).edit().apply {
                if (manual == null) {
                    putBoolean(KEY_LOG_MODE_MANUAL_KNOWN, false)
                    remove(KEY_LOG_MODE_MANUAL)
                } else {
                    putBoolean(KEY_LOG_MODE_MANUAL_KNOWN, true)
                    putBoolean(KEY_LOG_MODE_MANUAL, manual)
                }
            }.apply()
        }
    }
}

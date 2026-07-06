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
        /** Magnetometer hard-iron offset (mG) from the Live tab's
         *  "Calibrate compass" flow — desktop `mag_offset_mg` parity.
         *  Subtracted from the raw mag before the eCompass heading. */
        private const val KEY_MAG_OFF_X = "mag_off_x"
        private const val KEY_MAG_OFF_Y = "mag_off_y"
        private const val KEY_MAG_OFF_Z = "mag_off_z"
        private const val KEY_MAG_CALIBRATED = "mag_calibrated"
        /** One-tap direction calibration (iOS parity): bias subtracted
         *  from the heading; set via "USB-C points SOUTH". */
        private const val KEY_HEADING_BIAS = "heading_bias_deg"
        /** Which body-axis end is the FRONT (USB-C end): +Y or -Y. */
        private const val KEY_NOSE_PLUS_Y = "nose_plus_y"
        private const val KEY_NOSE_PLUS_Y_KNOWN = "nose_plus_y_known"
        /** Lateral render sign from the right-side confirm tap (+1/-1). */
        private const val KEY_LATERAL_SIGN = "lateral_sign"
        private const val KEY_LATERAL_SIGN_KNOWN = "lateral_sign_known"
        /** Calibrated board-angle zero reference [pitch, roll, yaw]° captured at
         *  the Live tab's "Zero here" tap (yaw sampled at bias 0). Persisted so
         *  a mounted-box tare survives reconnect / app restart. iOS
         *  `angleZeroRef` parity. */
        private const val KEY_ANGLE_ZERO_P = "angle_zero_pitch"
        private const val KEY_ANGLE_ZERO_R = "angle_zero_roll"
        private const val KEY_ANGLE_ZERO_Y = "angle_zero_yaw"
        private const val KEY_ANGLE_ZERO_KNOWN = "angle_zero_known"
        /** Wall-clock (epoch millis) of the last "Zero here" tap — drives the
         *  "zeroed N ago" note. iOS `angleZeroAtEpoch` parity. */
        private const val KEY_ANGLE_ZERO_AT = "angle_zero_at_ms"

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

        /** Stored hard-iron offset [x, y, z] in mG, or null if never calibrated. */
        fun magOffset(context: Context): FloatArray? {
            val p = prefs(context)
            if (!p.getBoolean(KEY_MAG_CALIBRATED, false)) return null
            return floatArrayOf(
                p.getFloat(KEY_MAG_OFF_X, 0f),
                p.getFloat(KEY_MAG_OFF_Y, 0f),
                p.getFloat(KEY_MAG_OFF_Z, 0f),
            )
        }

        fun setMagOffset(context: Context, off: FloatArray) {
            prefs(context).edit()
                .putBoolean(KEY_MAG_CALIBRATED, true)
                .putFloat(KEY_MAG_OFF_X, off[0])
                .putFloat(KEY_MAG_OFF_Y, off[1])
                .putFloat(KEY_MAG_OFF_Z, off[2])
                .apply()
        }

        fun headingBiasDeg(context: Context): Float =
            prefs(context).getFloat(KEY_HEADING_BIAS, 0f)

        fun setHeadingBias(context: Context, deg: Float) {
            prefs(context).edit().putFloat(KEY_HEADING_BIAS, deg).apply()
        }

        fun nosePlusY(context: Context): Boolean? {
            val p = prefs(context)
            if (!p.getBoolean(KEY_NOSE_PLUS_Y_KNOWN, false)) return null
            return p.getBoolean(KEY_NOSE_PLUS_Y, false)
        }

        fun setNosePlusY(context: Context, v: Boolean?) {
            prefs(context).edit().apply {
                if (v == null) {
                    putBoolean(KEY_NOSE_PLUS_Y_KNOWN, false)
                    remove(KEY_NOSE_PLUS_Y)
                } else {
                    putBoolean(KEY_NOSE_PLUS_Y_KNOWN, true)
                    putBoolean(KEY_NOSE_PLUS_Y, v)
                }
            }.apply()
        }

        fun lateralSign(context: Context): Float? {
            val p = prefs(context)
            if (!p.getBoolean(KEY_LATERAL_SIGN_KNOWN, false)) return null
            return p.getFloat(KEY_LATERAL_SIGN, 1f)
        }

        fun setLateralSign(context: Context, v: Float?) {
            prefs(context).edit().apply {
                if (v == null) {
                    putBoolean(KEY_LATERAL_SIGN_KNOWN, false)
                    remove(KEY_LATERAL_SIGN)
                } else {
                    putBoolean(KEY_LATERAL_SIGN_KNOWN, true)
                    putFloat(KEY_LATERAL_SIGN, v)
                }
            }.apply()
        }

        /**
         * Stored calibrated-angle zero reference [pitch, roll, yaw] in degrees,
         * or null if not zeroed. Kept independent of the compass calibration
         * (only [setAngleZeroRef]/Clear touch it), matching iOS.
         */
        fun angleZeroRef(context: Context): DoubleArray? {
            val p = prefs(context)
            if (!p.getBoolean(KEY_ANGLE_ZERO_KNOWN, false)) return null
            return doubleArrayOf(
                p.getFloat(KEY_ANGLE_ZERO_P, 0f).toDouble(),
                p.getFloat(KEY_ANGLE_ZERO_R, 0f).toDouble(),
                p.getFloat(KEY_ANGLE_ZERO_Y, 0f).toDouble(),
            )
        }

        fun setAngleZeroRef(context: Context, ref: DoubleArray?) {
            prefs(context).edit().apply {
                if (ref == null || ref.size != 3) {
                    putBoolean(KEY_ANGLE_ZERO_KNOWN, false)
                    remove(KEY_ANGLE_ZERO_P); remove(KEY_ANGLE_ZERO_R); remove(KEY_ANGLE_ZERO_Y)
                } else {
                    putBoolean(KEY_ANGLE_ZERO_KNOWN, true)
                    putFloat(KEY_ANGLE_ZERO_P, ref[0].toFloat())
                    putFloat(KEY_ANGLE_ZERO_R, ref[1].toFloat())
                    putFloat(KEY_ANGLE_ZERO_Y, ref[2].toFloat())
                }
            }.apply()
        }

        /** Epoch millis of the last "Zero here", or null if never/after Clear. */
        fun angleZeroAtMs(context: Context): Long? {
            val p = prefs(context)
            if (!p.contains(KEY_ANGLE_ZERO_AT)) return null
            return p.getLong(KEY_ANGLE_ZERO_AT, 0L).takeIf { it > 0L }
        }

        fun setAngleZeroAtMs(context: Context, atMs: Long?) {
            prefs(context).edit().apply {
                if (atMs == null) remove(KEY_ANGLE_ZERO_AT) else putLong(KEY_ANGLE_ZERO_AT, atMs)
            }.apply()
        }

        /** Wipe the whole compass calibration (offset, direction, ends). */
        fun resetMagCalibration(context: Context) {
            prefs(context).edit()
                .remove(KEY_MAG_CALIBRATED)
                .remove(KEY_MAG_OFF_X).remove(KEY_MAG_OFF_Y).remove(KEY_MAG_OFF_Z)
                .remove(KEY_HEADING_BIAS)
                .remove(KEY_NOSE_PLUS_Y).remove(KEY_NOSE_PLUS_Y_KNOWN)
                .remove(KEY_LATERAL_SIGN).remove(KEY_LATERAL_SIGN_KNOWN)
                .apply()
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

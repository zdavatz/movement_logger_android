package ch.ywesee.movementlogger.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import ch.ywesee.movementlogger.ble.BleSyncService
import ch.ywesee.movementlogger.ble.FileSyncCore
import ch.ywesee.movementlogger.ble.LiveSample
import kotlinx.coroutines.flow.StateFlow

data class DiscoveredDevice(val address: String, val name: String, val rssi: Int)

data class RemoteFile(val name: String, val size: Long)

data class DownloadProgress(val bytesDone: Long, val total: Long) {
    val fraction: Float get() = if (total <= 0) 0f else (bytesDone.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * In-flight firmware-upload progress. `name` is the picked `.bin` filename
 * (display only). Drives the upload progress card and the busy guard.
 */
data class FwUploadState(
    val name: String,
    val bytesDone: Long,
    val total: Long,
) {
    val fraction: Float get() = if (total <= 0) 0f else (bytesDone.toFloat() / total).coerceIn(0f, 1f)
}

/** Result of the most recent firmware upload — a dismissable banner. */
data class FwUploadResult(val success: Boolean, val message: String)

enum class Connection { Disconnected, Connecting, Connected }

/**
 * In-flight LOG session on the box. Recorded optimistically when the user
 * taps Start session: the firmware reboots ~50 ms later so the BLE link
 * dies, and the box is invisible to Scan until `durationSeconds` elapses.
 * Used to render the countdown banner.
 */
data class SessionRunning(
    val startedAtMillis: Long,
    val durationSeconds: Int,
) {
    fun remainingMillis(nowMillis: Long): Long =
        (startedAtMillis + durationSeconds * 1000L - nowMillis).coerceAtLeast(0L)
}

/**
 * Sparkline sample for the Live tab. `tSec` is seconds since the first
 * sample of this connection (relative — the box has no RTC), `value` is
 * already in display units (g for acc magnitude, hPa for pressure).
 */
data class LivePoint(val tSec: Double, val value: Double)

/**
 * Live SensorStream state. Cleared on disconnect.
 *
 * `latestSample` is the most recent 46-byte snapshot decoded into typed
 * fields; `acc`/`pressure` histories are bounded rolling buffers for the
 * sparklines (~4 min at 0.5 Hz).
 */
data class LiveState(
    val latestSample: LiveSample? = null,
    /** `System.currentTimeMillis()` when `latestSample` arrived. Used for
     *  the "x s ago" freshness label so a stalled stream is obvious. */
    val latestSampleAtMs: Long? = null,
    val sampleCount: Long = 0,
    val accHistory: List<LivePoint> = emptyList(),
    val pressureHistory: List<LivePoint> = emptyList(),
    /** True iff the connected firmware exposed the SensorStream char.
     *  Surfaced in the UI so the user knows whether to expect live data. */
    val streamCapable: Boolean = false,
)

data class FileSyncUiState(
    val connection: Connection = Connection.Disconnected,
    val scanning: Boolean = false,
    val discovered: List<DiscoveredDevice> = emptyList(),
    val files: List<RemoteFile> = emptyList(),
    val downloads: Map<String, DownloadProgress> = emptyMap(),
    val savedPaths: Map<String, String> = emptyMap(),
    /**
     * Bytes already in the local mirror per file, refreshed on LIST and
     * after each download. A file counts as fully downloaded (no more
     * "Download" button) when its mirror size ≥ the box's reported size.
     */
    val localBytes: Map<String, Long> = emptyMap(),
    val listing: Boolean = false,
    /** "Sync now" in progress (LIST -> diff -> serial pull of new files). */
    val syncing: Boolean = false,
    /** One-line sync result, mirrors the desktop status line
     *  ("Sync: 3 new, 12 already synced — downloading…" / "up to date"). */
    val syncStatus: String? = null,
    /** "Keep synced" continuous-mirror toggle (desktop v0.0.14). */
    val keepSynced: Boolean = false,
    /** Name of the file the *sync* pass is currently pulling, or null for
     *  a manual download / idle. Drives the per-file row in the progress
     *  card so the user sees which file is mid-READ. */
    val syncInFlight: String? = null,
    /** Total file count of the current sync pass (set at start, reset on
     *  completion). Renders "Syncing X of N files". */
    val syncPassTotal: Int = 0,
    /** Files still queued for this pass, drained one-at-a-time. Used by
     *  the progress card to render "N of M" as `total - queue - inFlight`. */
    val syncQueueRemaining: Int = 0,
    /** Sum of every queued file's box-reported size — constant across the
     *  pass; the cumulative progress bar's denominator. */
    val syncPassTotalBytes: Long = 0,
    /** Bytes drained: sum of completed files' final on-disk sizes. The
     *  in-flight file's contribution comes from `downloads[name].bytesDone`
     *  via [syncCumulativeBytes] so the bar moves while the current READ
     *  streams. */
    val syncPassCompletedBytes: Long = 0,
    /**
     * Box log-mode: null = unknown (not yet queried / legacy firmware
     * that ignores GET_MODE), false = auto (logs on boot), true =
     * manual (idle until START_LOG).
     */
    val logModeManual: Boolean? = null,
    /** A transfer was cut by a link drop / stall; the partial is safe
     *  in the mirror. Drives the reconnect banner and the auto-resume
     *  on the next Connected (desktop v0.0.9). Persists across the
     *  disconnect on purpose. */
    val transferInterrupted: Boolean = false,
    /** A DELETE the box rejected (BUSY / NOT_FOUND / IO_ERROR /
     *  BAD_REQUEST). Surfaced as a dismissable banner; cleared on a
     *  successful delete, a fresh attempt, or disconnect (desktop #7). */
    val deleteError: String? = null,
    val log: List<String> = emptyList(),
    val sessionDurationSeconds: Int = 1800,  // 30-min default, matches desktop
    val sessionRunning: SessionRunning? = null,
    val live: LiveState = LiveState(),
    /** Firmware-update (OTA) upload state. Non-null while a `.bin` is
     *  streaming to the box's inactive flash bank; drives the progress card
     *  and the busy guard. Cleared on completion (success or failure). */
    val fwUpload: FwUploadState? = null,
    /** Result banner of the most recent firmware upload: success ("box
     *  rebooting…") or a mapped failure reason. Dismissable; cleared on a
     *  fresh upload or disconnect. */
    val fwUploadResult: FwUploadResult? = null,
) {
    /** Live cumulative bytes pulled in the current sync pass: completed
     *  files' final sizes + the in-flight file's `bytesDone`. Use this
     *  for the overall progress-bar numerator. */
    val syncCumulativeBytes: Long
        get() = syncPassCompletedBytes + (syncInFlight?.let { downloads[it]?.bytesDone } ?: 0L)
    /** 0…1 overall progress of the in-flight sync pass. */
    val syncCumulativeFraction: Float
        get() = if (syncPassTotalBytes <= 0) 0f
            else (syncCumulativeBytes.toFloat() / syncPassTotalBytes).coerceIn(0f, 1f)
}

/**
 * Thin wrapper over [FileSyncCore]. The core owns the BleClient + state so
 * they survive Activity/ViewModel recreation and can keep running inside
 * [BleSyncService] when the user backgrounds the app.
 *
 * Every command that initiates real BLE work also starts the foreground
 * service; the service self-terminates once [FileSyncCore.isBusy] is false.
 */
class FileSyncViewModel(app: Application) : AndroidViewModel(app) {

    init {
        FileSyncCore.ensureInit(app.applicationContext)
    }

    val state: StateFlow<FileSyncUiState> = FileSyncCore.state

    fun scan() {
        BleSyncService.start(getApplication())
        FileSyncCore.scan()
    }

    fun connect(address: String) {
        BleSyncService.start(getApplication())
        FileSyncCore.connect(address)
    }

    fun disconnect() = FileSyncCore.disconnect()
    fun listFiles() = FileSyncCore.listFiles()
    fun syncNow() {
        BleSyncService.start(getApplication())
        FileSyncCore.syncNow()
    }
    fun download(file: RemoteFile) = FileSyncCore.download(file)
    fun delete(file: RemoteFile) = FileSyncCore.delete(file)
    fun dismissDeleteError() = FileSyncCore.dismissDeleteError()
    fun setKeepSynced(on: Boolean) = FileSyncCore.setKeepSynced(on)
    fun stopLog() = FileSyncCore.stopLog()
    fun setSessionDuration(seconds: Int) = FileSyncCore.setSessionDuration(seconds)
    fun startSession() = FileSyncCore.startSession()
    fun clearSession() = FileSyncCore.clearSession()
    fun logFilePath(): String? = FileSyncCore.logFilePath()
    fun setLogMode(manual: Boolean) = FileSyncCore.setLogMode(manual)

    /**
     * Upload a firmware `.bin` chosen via the SAF picker. Reads the exact
     * file bytes from the content Uri and computes their SHA-256 here (off
     * the worker), then hands them to [FileSyncCore.uploadFirmware]. Keeps
     * the foreground service alive for the duration like every other BLE op.
     */
    fun uploadFirmware(uri: android.net.Uri) {
        BleSyncService.start(getApplication())
        FileSyncCore.uploadFirmware(getApplication(), uri)
    }

    fun abortFirmwareUpload() = FileSyncCore.abortFirmwareUpload()
    fun dismissFwUploadResult() = FileSyncCore.dismissFwUploadResult()
}

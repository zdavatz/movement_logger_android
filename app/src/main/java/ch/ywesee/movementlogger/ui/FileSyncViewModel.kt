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
    val listing: Boolean = false,
    /** "Sync now" in progress (LIST -> diff -> serial pull of new files). */
    val syncing: Boolean = false,
    /** One-line sync result, mirrors the desktop status line
     *  ("Sync: 3 new, 12 already synced — downloading…" / "up to date"). */
    val syncStatus: String? = null,
    /** "Keep synced" continuous-mirror toggle (desktop v0.0.14). */
    val keepSynced: Boolean = false,
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
)

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
}
